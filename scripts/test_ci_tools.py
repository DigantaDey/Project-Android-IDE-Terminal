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

    def run_tool(self, name, path):
        return subprocess.run(
            [sys.executable, str(ROOT / "scripts" / name), str(path)],
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


if __name__ == "__main__":
    unittest.main()
