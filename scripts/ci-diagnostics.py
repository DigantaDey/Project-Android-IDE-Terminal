#!/usr/bin/env python3
"""Expose compiler and instrumentation failures through GitHub's check annotations API."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def annotate(title, report):
    report = re.sub(r"\x1b\[[0-9;]*m", "", report)
    for offset in range(0, min(len(report), 40000), 6000):
        part = report[offset:offset + 6000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title={title}::{part}")


for filename in sys.argv[1:]:
    path = Path(filename)
    if not path.exists():
        continue
    if path.is_dir():
        for xml in sorted(path.rglob("TEST-*.xml")):
            try:
                root = ET.parse(xml).getroot()
                for case in root.iter("testcase"):
                    for tag in ("failure", "error"):
                        for failure in case.findall(tag):
                            annotate(case.get("classname", "") + "." + case.get("name", ""),
                                     failure.get("message", "") + "\n" + (failure.text or ""))
            except ET.ParseError as exc:
                annotate(xml.name, str(exc))
        continue
    text = path.read_text(errors="replace")
    lines = text.splitlines()
    selected = []
    for index, line in enumerate(lines):
        if any(marker in line.lower() for marker in ("error:", "what went wrong", "could not", "failed", "exception")):
            selected.extend(lines[max(0, index - 1):index + 6])
    annotate(path.name, "\n".join(selected) if selected else text[-14000:])
