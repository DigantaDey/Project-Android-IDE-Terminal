#!/usr/bin/env python3
"""Publish pinned copyleft source inputs, recipe/patch tree, and this integration's source.

Runs at build time only. Keeps large corresponding-source files in ignored build/.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import traceback
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def verified_download(url, sha256, destination):
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == sha256:
        return
    if not url.startswith("https://") or len(sha256) != 64:
        raise ValueError("Unpinned source URL")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".partial")
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "curl/8.5.0"})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            digest = hashlib.sha256()
            size = 0
            while block := response.read(65536):
                size += len(block)
                if size > 512 * 1024 * 1024:
                    raise ValueError("Source archive too large")
                digest.update(block)
                output.write(block)
        if digest.hexdigest() != sha256:
            raise ValueError("Source checksum mismatch: " + url)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def build(index_path, output):
    index = json.loads(index_path.read_text())
    if index["schema"] != 1 or hashlib.sha256((ROOT / "tools/native/runtime-lock.json").read_bytes()).hexdigest() != index["packageLockSha256"]:
        raise ValueError("Source manifest does not match the native binary lock")
    output.mkdir(parents=True, exist_ok=True)
    recipe = index["recipes"]
    verified_download(recipe["url"], recipe["sha256"], output / "termux-build-recipes-and-patches.zip")
    for record in index["inputs"]:
        name = record["filename"]
        if "/" in name or "\\" in name or name.startswith("."):
            raise ValueError("Invalid source filename")
        try:
            verified_download(record["url"], record["sha256"], output / "upstream" / name)
        except Exception as error:
            raise ValueError("Could not obtain corresponding source " + name + " from " + record["url"] + ": " + str(error)) from error
    shutil.copyfile(index_path, output / "source-lock.json")
    shutil.copyfile(ROOT / "tools/native/runtime-lock.json", output / "native-package-lock.json")
    subprocess.run(["git", "archive", "--format=tar.gz", "--output=" + str((output / "forge-integration-source.tar.gz").resolve()), "HEAD"], cwd=ROOT, check=True)
    lines = ["# Native package license and source inventory", "", "The APK retains upstream copyright/license files under usr/share/doc.", "Copyleft inputs below are supplied with their complete pinned recipe/patch tree.", "The integration archive contains the JNI launcher, bridge, relocation scripts,", "build configuration and modified remote terminal adapter at the exact APK commit.", "Non-copyleft source links remain in the upstream recipes; their notices still apply.", "", "| Binary package | Version | Recipe | License |", "| --- | --- | --- | --- |"]
    lines += ["| {name} | {binaryVersion} | {recipe} | {license} |".format(**record) for record in index["packages"]]
    lines += ["", "## Rebuild", "", "Extract termux-build-recipes-and-patches.zip and follow its build-package.sh", "instructions with the specified versions and Android NDK. That archive includes", "all recipes, local patches, source URLs and source checksums. GNU Bash and Readline", "incremental patches are also provided in upstream/. Build the Forge integration", "using its Gradle wrapper and native-bundle.py; its ELF prefix relocation is explicit.", "No runtime package post-install scripts are executed by Forge.", "", "These files support source/distribution obligations; they are not a legal audit.", "A production distributor must review transitive notices, linking, platform policies", "and the exact release/signing artifacts independently.", ""]
    (output / "LICENSES-AND-REBUILD.md").write_text("\n".join(lines))
    manifest = []
    for path in sorted(output.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS":
            manifest.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(output).as_posix())
    (output / "SHA256SUMS").write_text("\n".join(manifest) + "\n")
    print("::notice title=Native corresponding sources::Verified " + str(len(index["inputs"])) + " source archives/patches, the complete recipe tree and the exact Forge source commit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, default=ROOT / "tools/native/sources-lock.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/native-sources")
    args = parser.parse_args()
    build(args.index, args.output)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        detail = traceback.format_exc()[-3500:].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=Native corresponding sources::" + detail, flush=True)
        raise
