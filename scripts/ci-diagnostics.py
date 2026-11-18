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
    relevant = [line for line in lines if any(term in line.lower() for term in ("error", "failed", "could not", "what went wrong", " at ", "exception"))]
    report = "\n".join(relevant[:85]) if relevant else text[-16000:]
    report = report[:24000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={path.name}::{report}")
