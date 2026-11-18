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
    relative = "main/binary-" + arch + "/Packages.xz"
    section = text.split("SHA256:\n", 1)[1].split("\n\n", 1)[0]
    match = re.search(r"^\s*([a-f0-9]{64})\s+(\d+)\s+" + re.escape(relative) + r"$", section, re.M)
    if not match:
        raise ValueError("Signed release has no SHA256 for " + relative)
    compressed = fetch(MIRROR + "dists/stable/" + relative, 32 * 1024 * 1024)
    if len(compressed) != int(match[2]) or hashlib.sha256(compressed).hexdigest() != match[1]:
        raise ValueError("Signed package-index checksum mismatch")
    return paragraphs(lzma.decompress(compressed).decode()), hashlib.sha256(release).hexdigest()


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["lock"])
    parser.add_argument("--output", type=Path, default=ROOT / "build/native/runtime-lock.json")
    args = parser.parse_args()
    if args.command == "lock":
        lock(args.output)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        detail = traceback.format_exc()[-3500:].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=Native bundle verification::" + detail, flush=True)
        raise
