#!/usr/bin/env python3
"""Put build diagnostics in GitHub's annotations API as well as downloadable logs."""
from pathlib import Path
import sys
for filename in sys.argv[1:]:
    path = Path(filename)
    if not path.exists():
        continue
    text = path.read_text(errors="replace")
    # Compiler diagnostics come before Gradle's long stack traces.
    lines = text.splitlines()
    selected = []
    for index, line in enumerate(lines):
        if "error:" in line.lower() or "what went wrong" in line.lower() or "could not" in line.lower():
            selected.extend(lines[index:index + 5])
    report = "\n".join(selected) if selected else text[-14000:]
    # GitHub limits each annotation's message; split before escaping.
    for offset in range(0, min(len(report), 40000), 6000):
        part = report[offset:offset + 6000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title={path.name}::{part}")
