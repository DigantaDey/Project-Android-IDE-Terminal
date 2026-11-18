#!/usr/bin/env python3
"""Convert AndroidJUnitRunner's real -w -r status protocol to checked JUnit XML.

The device matrix installs the *same* APKs built by the build job, avoiding three
independent Gradle dependency resolutions. No success is inferred from adb's exit
status: missing cases, process death, runner errors and incomplete runs fail.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def parse(text):
    cases = {}
    started = set()
    fields = {}
    field = None
    expected = None
    final_code = None
    runner_error = False
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            value = line[len("INSTRUMENTATION_STATUS: "):]
            key, separator, content = value.partition("=")
            if not separator:
                raise ValueError("Malformed instrumentation status field")
            fields[key] = content
            field = key
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(": ", 1)[1])
            if "numtests" in fields:
                count = int(fields["numtests"])
                if count < 1 or (expected is not None and count != expected):
                    raise ValueError("Invalid or changing instrumentation testcase count")
                expected = count
            key = (fields.get("class", ""), fields.get("test", ""))
            if not all(key):
                raise ValueError("Instrumentation status lacks a testcase identity")
            if code == 1:
                if key in started:
                    raise ValueError("Duplicate instrumentation testcase start")
                started.add(key)
            elif code in (0, -1, -2, -3, -4):
                if key not in started or key in cases:
                    raise ValueError("Unstarted or duplicate instrumentation testcase result")
                cases[key] = (code, fields.get("stack", ""))
            else:
                raise ValueError(f"Unknown instrumentation testcase result: {code}")
            fields = {}
            field = None
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final_code = int(line.rsplit(": ", 1)[1])
            field = None
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            runner_error = True
            field = None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if line.startswith("INSTRUMENTATION_RESULT: shortMsg="):
                runner_error = True
            field = None
        elif field is not None:
            fields[field] += "\n" + line
    if runner_error or final_code != -1:
        raise ValueError("Instrumentation runner failed or did not finish normally")
    if expected is None or len(cases) != expected or len(started) != expected:
        raise ValueError(f"Incomplete instrumentation: {len(cases)} finished; expected {expected}")
    return cases


def main():
    source, target = map(Path, sys.argv[1:])
    if source.stat().st_size > 8 * 1024 * 1024:
        raise SystemExit("Instrumentation protocol exceeds the 8 MiB report limit")
    try:
        cases = parse(source.read_text(errors="replace"))
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from error
    failures = sum(code in (-1, -2) for code, _ in cases.values())
    skipped = sum(code in (-3, -4) for code, _ in cases.values())
    suite = ET.Element("testsuite", name="Forge device integration", tests=str(len(cases)),
                       failures=str(failures), skipped=str(skipped), errors="0")
    for (classname, name), (code, stack) in cases.items():
        case = ET.SubElement(suite, "testcase", classname=classname, name=name)
        if code in (-1, -2):
            failure = ET.SubElement(case, "failure", message=stack.splitlines()[0] if stack else "Instrumentation failure")
            failure.text = stack
        elif code in (-3, -4):
            ET.SubElement(case, "skipped", message=stack)
    target.parent.mkdir(parents=True, exist_ok=True)
    # XML 1.0 cannot contain arbitrary control characters from a stack trace.
    xml = ET.tostring(suite, encoding="unicode")
    xml = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "\ufffd", xml)
    target.write_text('<?xml version="1.0" encoding="utf-8"?>\n' + xml, encoding="utf-8")
    if failures:
        raise SystemExit("Instrumentation contains failed testcases; inspect the generated JUnit report")


if __name__ == "__main__":
    main()
