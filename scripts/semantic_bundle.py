#!/usr/bin/env python3
"""Pinned, platform-independent offline LSP payload. Build-time HTTPS only.

Extracts npm distribution archives directly: NO npm invocation, lifecycle scripts,
platform binaries, optional dependencies, or on-device downloads. Every original
archive is also supplied in the corresponding-source release artifact.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
SERVER_DIR = ROOT / "tools/native/servers"
BUNDLE_INPUTS = (
    "tools/native/runtime-lock.json",
    "tools/native/servers/package.json",
    "tools/native/servers/package-lock.json",
    "tools/native/servers/launch.cjs",
)


def bundle_id():
    digest = hashlib.sha256()
    for name in BUNDLE_INPUTS:
        digest.update((ROOT / name).read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def packages():
    lock = json.loads((SERVER_DIR / "package-lock.json").read_text())
    if lock["lockfileVersion"] != 3:
        raise ValueError("Unsupported semantic-server lock version")
    result = []
    for location, package in lock["packages"].items():
        if not location or package.get("optional"):
            continue
        if not re.fullmatch(r"node_modules/(?:@[a-z0-9._-]+/)?[a-z0-9._-]+(?:/node_modules/(?:@[a-z0-9._-]+/)?[a-z0-9._-]+)*", location):
            raise ValueError("Invalid locked module location: " + location)
        if not re.fullmatch(r"https://registry\.npmjs\.org/[A-Za-z0-9@%_./~-]+\.tgz", package["resolved"]) or ".." in package["resolved"]:
            raise ValueError("Untrusted npm registry URL")
        if not package.get("license") or package.get("os") or package.get("cpu"):
            raise ValueError("Missing license or platform-specific dependency: " + location)
        if not re.fullmatch(r"sha512-[A-Za-z0-9+/]{86}==", package["integrity"]):
            raise ValueError("Require SHA-512 npm integrity: " + location)
        result.append((location, package))
    return result


def verified_archive(package, cache):
    expected = base64.b64decode(package["integrity"].split("-", 1)[1], validate=True)
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / (expected.hex() + ".tgz")
    if destination.is_file() and hashlib.sha512(destination.read_bytes()).digest() == expected:
        return destination
    temporary = destination.with_suffix(".partial")
    try:
        request = urllib.request.Request(package["resolved"], headers={"User-Agent": "Forge-offline-build/0.3"})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024 or hashlib.sha512(data).digest() != expected:
            raise ValueError("Semantic-server distribution integrity mismatch: " + package["resolved"])
        temporary.write_bytes(data)
        temporary.replace(destination)
        return destination
    finally:
        temporary.unlink(missing_ok=True)


def archive_records(data):
    seen = set()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for member in archive:
            if member.isdir():
                continue
            parts = member.name.split("/")
            if parts[0] != "package" or len(parts) < 2 or any(p in ("", ".", "..") or "\\" in p or "\0" in p for p in parts):
                raise ValueError("Unsafe npm archive path: " + member.name)
            name = "/".join(parts[1:])
            if name in seen or not member.isfile() or member.size > 64 * 1024 * 1024:
                raise ValueError("Duplicate, linked, special or oversized npm entry: " + member.name)
            if PurePosixPath(name).suffix in (".node", ".exe", ".dll", ".so"):
                raise ValueError("Platform binary in platform-independent server: " + member.name)
            seen.add(name)
            yield name, archive.extractfile(member).read()
            if len(seen) > 30000:
                raise ValueError("Too many npm archive entries")


def inventory():
    return {
        "schema": 1,
        "offline": True,
        "optionalDependencies": False,
        "lifecycleScripts": False,
        "packages": [{"path": location, "version": p["version"], "license": p["license"], "url": p["resolved"], "integrity": p["integrity"]} for location, p in packages()],
    }


def notice():
    lines = ["# Bundled language-server notices", "", "Original upstream license, copyright and third-party notice files are retained", "unchanged inside every package below (including Pyright's typeshed notices).", "These npm source distributions and their SHA-512 lock are supplied with the release.", "The Termux/LLVM clangd license is covered separately in the native inventory.", "No package install/lifecycle scripts are run. Darwin fsevents is omitted.", "", "| Package path | Version | License |", "| --- | --- | --- |"]
    lines += [f"| {location} | {p['version']} | {p['license']} |" for location, p in packages()]
    return "\n".join(lines) + "\n"


def server_records(cache=None):
    cache = cache or ROOT / "build/semantic-downloads"
    entries = packages()
    with ThreadPoolExecutor(max_workers=4) as executor:
        archives = list(executor.map(lambda item: verified_archive(item[1], cache), entries))
    base = "share/forge-lsp/"
    for (location, _), archive in zip(entries, archives):
        for name, data in archive_records(archive.read_bytes()):
            yield base + location + "/" + name, data
    for name in ("package.json", "package-lock.json", "launch.cjs"):
        yield base + name, (SERVER_DIR / name).read_bytes()
    yield base + "inventory.json", (json.dumps(inventory(), indent=2) + "\n").encode()
    yield base + "NOTICE.md", notice().encode()


def sources(output):
    output.mkdir(parents=True, exist_ok=True)
    for location, package in packages():
        archive = verified_archive(package, ROOT / "build/semantic-downloads")
        filename = location.replace("node_modules/", "").replace("/", "_") + "-" + package["version"] + ".tgz"
        shutil.copyfile(archive, output / filename)
    for name in ("package.json", "package-lock.json", "launch.cjs"):
        shutil.copyfile(SERVER_DIR / name, output / name)
    (output / "inventory.json").write_text(json.dumps(inventory(), indent=2) + "\n")
    (output / "NOTICE.md").write_text(notice())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("build", "sources", "id"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/semantic-host")
    args = parser.parse_args()
    if args.command == "id":
        print(bundle_id())
    elif args.command == "sources":
        sources(args.output)
    else:
        for name, data in server_records():
            path = args.output / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        print("Verified and extracted", len(packages()), "offline semantic-server source distributions")


if __name__ == "__main__":
    main()
