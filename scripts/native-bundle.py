#!/usr/bin/env python3
"""Build-time only, signed/pinned Termux packages for the offline runtime companion.

Never called on an Android device. Generated payloads live under build/, not Git.
"""
import argparse
import base64
import gzip
import hashlib
import json
import lzma
import os
import io
import shutil
import tarfile
import zipfile
import posixpath
from pathlib import Path
import re
import subprocess
import tempfile
import traceback
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
MIRROR = "https://packages.termux.dev/apt/termux-main/"
RECIPE_COMMIT = "6ec3ffd97c11005ed390a16218d8616a9c284064"
ROOTS = ["bash", "coreutils", "findutils", "grep", "sed", "clang", "lld", "make", "python", "nodejs-lts", "npm"]


def fetch(url, limit=256 * 1024 * 1024):
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Download too large: " + url)
    return data


def paragraphs(data):
    result = []
    for paragraph in data.split("\n\n"):
        fields = {}
        key = None
        for line in paragraph.splitlines():
            if line.startswith((" ", "\t")) and key:
                fields[key] += "\n" + line[1:]
            elif ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value
        if fields:
            result.append(fields)
    return result


def signed_index(arch):
    release = fetch(MIRROR + "dists/stable/InRelease", 1024 * 1024)
    with tempfile.TemporaryDirectory() as temp:
        keyring = Path(temp) / "keys.gpg"
        keyring.write_bytes(b"".join(p.read_bytes() for p in sorted((ROOT / "tools/native/keys").glob("*.gpg"))))
        source = Path(temp) / "InRelease"
        source.write_bytes(release)
        verified = Path(temp) / "Release"
        check = subprocess.run(["gpgv", "--keyring", str(keyring), "--output", str(verified), str(source)], capture_output=True, text=True)
        if check.returncode:
            raise ValueError("Catalog signature verification failed: " + check.stderr)
        text = verified.read_text()
    section = text.split("SHA256:\n", 1)[1].split("\n\n", 1)[0]
    match = None
    for suffix in (".xz", ".gz", ""):
        relative = "main/binary-" + arch + "/Packages" + suffix
        match = re.search(r"^\s*([a-f0-9]{64})\s+(\d+)\s+" + re.escape(relative) + r"$", section, re.M)
        if match:
            break
    if not match:
        raise ValueError("Signed release has no SHA256 for package index: " + text[-1800:])
    compressed = fetch(MIRROR + "dists/stable/" + relative, 32 * 1024 * 1024)
    if len(compressed) != int(match[2]) or hashlib.sha256(compressed).hexdigest() != match[1]:
        raise ValueError("Signed package-index checksum mismatch")
    plain = lzma.decompress(compressed) if suffix == ".xz" else gzip.decompress(compressed) if suffix == ".gz" else compressed
    return paragraphs(plain.decode()), hashlib.sha256(release).hexdigest()


def resolve(packages, roots):
    available = {p["Package"]: p for p in packages}
    aliases = {}
    for package in packages:
        for name in package.get("Provides", "").split(","):
            aliases[re.split(r"[ (:]", name.strip())[0]] = package["Package"]
    selected = {}

    def add(name):
        name = aliases.get(name, name) if name not in available else name
        if name in selected:
            return
        for existing in selected.values():
            provided = [re.split(r"[ (:]", v.strip())[0] for v in existing.get("Provides", "").split(",")]
            if name in provided:
                return
        if name not in available:
            raise ValueError("Unresolved dependency: " + name)
        package = available[name]
        selected[name] = package
        for clause in (package.get("Pre-Depends", "") + "," + package.get("Depends", "")).split(","):
            if not clause.strip():
                continue
            alternatives = [re.split(r"[ (:]", v.strip())[0] for v in clause.split("|")]
            chosen = next((v for v in alternatives if v in available or v in aliases), None)
            if not chosen:
                raise ValueError("Unresolved dependency of " + name + ": " + clause)
            add(chosen)

    for name in roots:
        add(name)
    return [selected[name] for name in sorted(selected)]


def lock(output):
    result = {"schema": 1, "mirror": MIRROR, "recipes": RECIPE_COMMIT, "roots": ROOTS, "architectures": {}}
    for arch in ("aarch64", "x86_64"):
        packages, release_sha = signed_index(arch)
        chosen = resolve(packages, ROOTS)
        result["architectures"][arch] = {
            "signedReleaseSha256": release_sha,
            "packages": [{key: p[key] for key in ("Package", "Version", "Architecture", "Filename", "Size", "SHA256")} for p in chosen],
        }
        print(arch, len(chosen), "packages,", sum(int(p["Size"]) for p in chosen), "compressed bytes")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n")
    compressed = base64.b64encode(gzip.compress(output.read_bytes(), mtime=0)).decode()
    for n, start in enumerate(range(0, len(compressed), 3800)):
        print(f"::notice title=Native lock {n:02d},file=docs/NATIVE_RUNTIME.md::{compressed[start:start+3800]}")
    print("::notice title=Native lock manifest,file=docs/NATIVE_RUNTIME.md::sha256=" + hashlib.sha256(output.read_bytes()).hexdigest())



def build(lockfile, destination):
    locked_bytes = lockfile.read_bytes()
    locked = json.loads(locked_bytes)
    if locked["schema"] != 1 or locked["mirror"] != MIRROR:
        raise ValueError("Unsupported native package lock")
    bundle_id = hashlib.sha256(locked_bytes).hexdigest()
    destination.mkdir(parents=True, exist_ok=True)
    metadata = {"schema": 1, "id": bundle_id, "architectures": {}}
    old_prefix = "data/data/com.termux/files/usr/"
    for arch, info in locked["architectures"].items():
        records = {}
        def relative(name):
            name = name.removeprefix("./").lstrip("/")
            if name.rstrip("/") in ("data", "data/data", "data/data/com.termux", "data/data/com.termux/files", old_prefix.rstrip("/")):
                return None
            if not name.startswith(old_prefix):
                raise ValueError("Package entry outside runtime prefix: " + name)
            rel = name[len(old_prefix):].rstrip("/")
            if not rel or rel.startswith("/") or any(v in ("", ".", "..") for v in rel.split("/")):
                raise ValueError("Invalid runtime package path: " + name)
            return rel
        def store(name, kind, value, mode):
            existing = records.get(name)
            item = (kind, value, mode)
            if existing and existing[:2] != item[:2]:
                raise ValueError("Conflicting native packages at " + name)
            records[name] = item
        for package in info["packages"]:
            filename = package["Filename"]
            if not re.fullmatch(r"pool/[A-Za-z0-9_+./:%~-]+\.deb", filename) or ".." in filename:
                raise ValueError("Invalid pinned package URL")
            cache = ROOT / "build/native/downloads" / (package["SHA256"] + ".deb")
            cache.parent.mkdir(parents=True, exist_ok=True)
            if not cache.exists():
                cache.write_bytes(fetch(MIRROR + filename))
            if cache.stat().st_size != int(package["Size"]) or hashlib.sha256(cache.read_bytes()).hexdigest() != package["SHA256"]:
                raise ValueError("Native package checksum mismatch: " + package["Package"])
            archive = subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(cache)], stdout=subprocess.PIPE)
            with tarfile.open(fileobj=archive.stdout, mode="r|*") as tar:
                for member in tar:
                    if member.isdir():
                        continue
                    name = relative(member.name)
                    if not name:
                        continue
                    mode = 0o755 if member.mode & 0o111 else 0o644
                    if member.isfile():
                        data = tar.extractfile(member).read()
                        data = data.replace(b"/data/data/com.termux", b"/data/data/com.forgex")
                        store(name, "file", data, mode)
                    elif member.issym():
                        target = member.linkname.replace("/data/data/com.termux/files/usr/", "") if member.linkname.startswith("/") else posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
                        if target.startswith(("/", "../")) or target == "..":
                            raise ValueError("Runtime link escapes bundle: " + name + " -> " + target)
                        store(name, "link", target, mode)
                    elif member.islnk():
                        target = relative(member.linkname)
                        store(name, "link", target, mode)
                    else:
                        raise ValueError("Unsupported package entry type")
            if archive.wait() != 0:
                raise ValueError("dpkg-deb failed")
        for required in ("bin/bash", "bin/python", "bin/node", "bin/clang", "bin/clang++", "bin/ld.lld", "lib/node_modules/npm/bin/npm-cli.js"):
            if required not in records:
                raise ValueError("Missing bundled tool: " + required)
        # Static bootstrap launchers are not needed; the companion never runs package postinst hooks.
        manifest = {"schema": 1, "id": bundle_id, "files": [], "links": []}
        payload = destination / ("runtime-" + arch + ".zip")
        unpacked = 0
        with zipfile.ZipFile(payload, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            for name, (kind, value, mode) in sorted(records.items()):
                if kind == "link":
                    manifest["links"].append({"path": name, "target": value})
                    continue
                entry = zipfile.ZipInfo("files/" + name, (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = mode << 16
                output.writestr(entry, value)
                unpacked += len(value)
                manifest["files"].append({"path": name, "size": len(value), "sha256": hashlib.sha256(value).hexdigest(), "mode": mode})
            output.writestr("manifest.json", json.dumps(manifest, separators=(",", ":")))
            output.writestr("package-lock.json", locked_bytes)
        metadata["architectures"][arch] = {"sha256": hashlib.sha256(payload.read_bytes()).hexdigest(), "bytes": payload.stat().st_size, "unpackedBytes": unpacked, "files": len(records), "versions": {p["Package"]: p["Version"] for p in info["packages"] if p["Package"] in ("clang", "python", "nodejs", "nodejs-lts", "npm", "bash")}}
        print("::notice title=Offline native payload::" + arch + " " + json.dumps(metadata["architectures"][arch]), flush=True)
    (destination / "runtime-catalog.json").write_text(json.dumps(metadata, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["lock", "build"])
    parser.add_argument("--output", type=Path, default=ROOT / "build/native/runtime-lock.json")
    parser.add_argument("--lock", type=Path, default=ROOT / "tools/native/runtime-lock.json")
    parser.add_argument("--assets", type=Path, default=ROOT / "build/native/assets")
    args = parser.parse_args()
    if args.command == "lock":
        lock(args.output)
    else:
        build(args.lock, args.assets)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        detail = traceback.format_exc()[-3500:].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=Native bundle verification::" + detail, flush=True)
        raise
