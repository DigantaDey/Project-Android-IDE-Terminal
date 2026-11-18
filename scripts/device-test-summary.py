#!/usr/bin/env python3
"""Require real instrumentation results and expose the count in the checks API."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

cases = {}
for path in sorted(Path(sys.argv[1]).rglob("TEST-*.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        key = (case.get("classname", ""), case.get("name", ""))
        cases[key] = (case.find("failure") is not None or case.find("error") is not None,
                      case.find("skipped") is not None)
if not cases:
    raise SystemExit("No instrumentation testcase results were produced")
failed = sum(value[0] for value in cases.values())
skipped = sum(value[1] for value in cases.values())
passed = len(cases) - failed - skipped
print(f"::notice file=docs/TESTING.md,line=1,title=Device test summary::{passed} instrumentation tests passed; {failed} failed; {skipped} skipped.")
if failed or passed == 0:
    raise SystemExit("Instrumentation did not complete successfully")
