#!/usr/bin/env python3
"""Regression tests for the report transport; no Android SDK or third-party modules required."""
import base64
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class CiToolsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)

    def run_tool(self, name, *paths):
        return subprocess.run(
            [sys.executable, str(ROOT / "scripts" / name), *(str(path) for path in paths)],
            text=True, capture_output=True, check=False,
        )

    def image(self, size=22000):
        # Transport byte fixture, not a rendered screenshot or image-decoder test.
        return b"\xff\xd8" + b"x" * (size - 4) + b"\xff\xd9"

    def test_native_thumbnail_has_complete_verifiable_bounded_annotations(self):
        image = self.image(24000)
        (self.folder / "workspace-preview.jpg").write_bytes(image)
        report = self.run_tool("screenshot-diagnostics.py", self.folder)
        self.assertEqual(0, report.returncode, report.stderr)
        notices = report.stdout.splitlines()
        self.assertLessEqual(len(notices), 10)  # GitHub's per-step notice ceiling.
        manifest = dict(item.split("=") for item in notices[0].split("::")[-1].split(";"))
        self.assertEqual(len(image), int(manifest["bytes"]))
        self.assertEqual(len(notices) - 1, int(manifest["chunks"]))
        self.assertEqual(hashlib.sha256(image).hexdigest(), manifest["sha256"])
        chunks = [notice.split("::")[-1] for notice in notices[1:]]
        self.assertTrue(all(len(chunk) < 4096 for chunk in chunks))
        self.assertEqual(image, base64.b64decode("".join(chunks), validate=True))

    def test_fixture_log_recovers_thumbnail_after_runner_uninstall(self):
        image = self.image()
        encoded = base64.b64encode(image).decode("ascii")
        parts = [encoded[index:index + 2800] for index in range(0, len(encoded), 2800)]
        (self.folder / "device-visual.log").write_text("\n".join(
            f"11-18 ForgeVisual: FORGE_VISUAL {index}/{len(parts)} {part}"
            for index, part in enumerate(parts)
        ))
        report = self.run_tool("screenshot-diagnostics.py", self.folder / "images")
        self.assertEqual(0, report.returncode, report.stderr)
        self.assertEqual(image, (self.folder / "images/workspace-preview.jpg").read_bytes())

    def test_missing_or_incomplete_transport_is_not_claimed_as_a_screenshot(self):
        report = self.run_tool("screenshot-diagnostics.py", self.folder / "images")
        self.assertNotEqual(0, report.returncode)
        (self.folder / "device-visual.log").write_text("FORGE_VISUAL 0/2 /9j/")
        report = self.run_tool("screenshot-diagnostics.py", self.folder / "images")
        self.assertNotEqual(0, report.returncode)
        self.assertIn("Incomplete", report.stderr)

    def test_oversized_or_truncated_jpeg_is_rejected(self):
        for image in (self.image(24001), self.image()[:-2], b"not a JPEG"):
            (self.folder / "workspace-preview.jpg").write_bytes(image)
            report = self.run_tool("screenshot-diagnostics.py", self.folder)
            self.assertNotEqual(0, report.returncode)
            self.assertNotIn("Native image manifest", report.stdout)

    def test_device_summary_requires_real_nonempty_results(self):
        report = self.run_tool("device-test-summary.py", self.folder)
        self.assertNotEqual(0, report.returncode)
        (self.folder / "TEST-empty.xml").write_text("<testsuite/>")
        report = self.run_tool("device-test-summary.py", self.folder)
        self.assertNotEqual(0, report.returncode)

    def test_device_summary_deduplicates_copied_result_files(self):
        xml = '<testsuite><testcase classname="A" name="one"/><testcase classname="A" name="two"/></testsuite>'
        for name in ("TEST-one.xml", "TEST-copy.xml"):
            (self.folder / name).write_text(xml)
        report = self.run_tool("device-test-summary.py", self.folder)
        self.assertEqual(0, report.returncode, report.stderr)
        self.assertIn("2 instrumentation tests passed; 0 failed; 0 skipped", report.stdout)

    def test_failed_instrumentation_cannot_have_a_successful_summary(self):
        (self.folder / "TEST-failed.xml").write_text(
            '<testsuite><testcase classname="A" name="bad"><failure>failure</failure></testcase>'
            '<testcase classname="A" name="ignored"><skipped/></testcase></testsuite>'
        )
        report = self.run_tool("device-test-summary.py", self.folder)
        self.assertEqual(1, report.returncode)
        self.assertIn("0 instrumentation tests passed; 1 failed; 1 skipped", report.stdout)

    def test_malformed_results_are_not_accepted(self):
        (self.folder / "TEST-broken.xml").write_text("<testsuite>")
        report = self.run_tool("device-test-summary.py", self.folder)
        self.assertNotEqual(0, report.returncode)


class InstrumentationProtocolTest(unittest.TestCase):
    setUp = CiToolsTest.setUp
    run_tool = CiToolsTest.run_tool
    # Real AndroidJUnitRunner wire-format fixtures, not fabricated evidence for an emulator run.
    def status(self, name="one", code=0, total=1, stack=""):
        common = (f"INSTRUMENTATION_STATUS: class=fixture.Case\n"
                  f"INSTRUMENTATION_STATUS: test={name}\n"
                  f"INSTRUMENTATION_STATUS: numtests={total}\n")
        return (common + "INSTRUMENTATION_STATUS_CODE: 1\n" + common +
                (f"INSTRUMENTATION_STATUS: stack={stack}\n" if stack else "") +
                f"INSTRUMENTATION_STATUS_CODE: {code}\n")

    def convert(self, text):
        source = self.folder / "protocol.log"
        source.write_text(text)
        return self.run_tool("instrumentation-report.py", source, self.folder / "TEST-device.xml")

    def test_complete_runner_protocol_is_converted_to_real_case_identities(self):
        import xml.etree.ElementTree as ET
        result = self.convert(self.status("one", total=2) + self.status("two", total=2) +
                              "INSTRUMENTATION_RESULT: stream=OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(0, result.returncode, result.stderr)
        suite = ET.parse(self.folder / "TEST-device.xml").getroot()
        self.assertEqual("2", suite.get("tests"))
        self.assertEqual(["one", "two"], [case.get("name") for case in suite.findall("testcase")])

    def test_failed_case_writes_diagnostics_but_never_returns_success(self):
        import xml.etree.ElementTree as ET
        stack = "java.lang.AssertionError: value < 42\n  at fixture.Case.one(Case.java:12)"
        result = self.convert(self.status(code=-2, stack=stack) + "INSTRUMENTATION_CODE: -1\n")
        self.assertEqual(1, result.returncode)
        failure = ET.parse(self.folder / "TEST-device.xml").getroot().find("testcase/failure")
        self.assertEqual(stack, failure.text)

    def test_truncated_runner_output_is_not_accepted_even_with_a_success_exit_marker(self):
        result = self.convert(self.status(total=2) + "INSTRUMENTATION_CODE: -1\n")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.folder / "TEST-device.xml").exists())

    def test_runner_crash_or_adb_exit_is_not_test_success(self):
        for end in ("", "INSTRUMENTATION_CODE: 0\n", "INSTRUMENTATION_FAILED: process died\nINSTRUMENTATION_CODE: -1\n",
                    "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: -1\n"):
            result = self.convert(self.status() + end)
            self.assertNotEqual(0, result.returncode)

    def test_duplicate_or_unknown_case_status_fails_closed(self):
        for text in (self.status() + self.status(), self.status(code=42),
                     "INSTRUMENTATION_STATUS_CODE: 0\n"):
            result = self.convert(text + "INSTRUMENTATION_CODE: -1\n")
            self.assertNotEqual(0, result.returncode)

    def test_ignored_and_assumption_cases_are_explicitly_skipped(self):
        import xml.etree.ElementTree as ET
        text = self.status("one", -3, 2) + self.status("two", -4, 2, "assumption failed")
        result = self.convert(text + "INSTRUMENTATION_CODE: -1\n")
        self.assertEqual(0, result.returncode, result.stderr)
        suite = ET.parse(self.folder / "TEST-device.xml").getroot()
        self.assertEqual("2", suite.get("skipped"))
        self.assertEqual(2, len(suite.findall("testcase/skipped")))


if __name__ == "__main__":
    unittest.main()
