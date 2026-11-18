#!/usr/bin/env python3
"""Explicitly opt-in, fixture-only visual QA through GitHub check annotations.

Connected-device tooling can uninstall the test app before adb pull runs. In that
case, reconstruct the bounded thumbnail from the test-only ForgeVisual log tag.
Never enable visualReport/FORGE_VISUAL_REPORT on an installation with private data.
"""
from pathlib import Path
import base64
import re
import sys

folder = Path(sys.argv[1])
files = sorted(folder.rglob("workspace-preview.jpg"))
if files:
    image = files[0].read_bytes()
else:
    log = folder.parent / "device-visual.log"
    text = log.read_text(errors="replace") if log.exists() else ""
    chunks = re.findall(r"FORGE_VISUAL (\d+)/(\d+) ([A-Za-z0-9+/=]+)", text)
    if not chunks:
        raise SystemExit("Opt-in native visual QA requested, but no fixture screenshot was produced")
    expected = int(chunks[-1][1])
    parts = {int(index): part for index, total, part in chunks if int(total) == expected}
    if expected > 50 or sorted(parts) != list(range(expected)):
        raise SystemExit("Incomplete fixture screenshot log; refusing a partial visual report")
    image = base64.b64decode("".join(parts[index] for index in range(expected)), validate=True)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "workspace-preview.jpg").write_bytes(image)
if len(image) > 100000 or not image.startswith(b"\xff\xd8"):
    raise SystemExit("QA thumbnail exceeds diagnostic transport limit or is not JPEG")
encoded = base64.b64encode(image).decode("ascii")
size = 6000
for offset in range(0, len(encoded), size):
    index = offset // size
    print(f"::notice file=docs/TESTING.md,line=1,title=Native screenshot {index:03d}::{encoded[offset:offset + size]}")
