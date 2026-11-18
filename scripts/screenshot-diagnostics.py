#!/usr/bin/env python3
"""Opt-in visual QA fallback when a sandbox cannot follow an Actions artifact's storage redirect.

Default push CI only uploads the original PNG artifact. A workflow_dispatch with
visual_report=true additionally exposes a small, fixture-only JPEG as machine-readable notices.
Never run this on screenshots containing user projects or credentials.
"""
from pathlib import Path
import base64
import sys

files = sorted(Path(sys.argv[1]).rglob("workspace-preview.jpg"))
if not files:
    print("No native screenshot was produced.")
    raise SystemExit(0)
image = files[0].read_bytes()
if len(image) > 100000:
    raise SystemExit("QA thumbnail exceeds diagnostic transport limit")
encoded = base64.b64encode(image).decode("ascii")
size = 16000
for offset in range(0, len(encoded), size):
    index = offset // size
    print(f"::notice title=Native screenshot {index:03d}::{encoded[offset:offset + size]}")
