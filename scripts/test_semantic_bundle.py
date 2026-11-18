"""Offline provenance, extraction and compatibility-identity regressions for bundled LSP."""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import tempfile
import unittest
from unittest import mock

import semantic_bundle as bundle


class SemanticBundleTests(unittest.TestCase):
    def test_all_engines_and_transitives_are_versioned_licensed_and_integrity_locked(self):
        entries = bundle.packages()
        self.assertGreater(len(entries), 50)
        packages = dict(entries)
        for location, package in entries:
            self.assertTrue(package["license"])
            self.assertEqual(64, len(base64.b64decode(package["integrity"].split("-", 1)[1], validate=True)))
            self.assertNotIn("fsevents", location)
        for name in ("pyright", "typescript", "typescript-language-server", "bash-language-server", "vscode-langservers-extracted", "web-tree-sitter"):
            self.assertIn("node_modules/" + name, packages)
        self.assertEqual("6.0.2", packages["node_modules/typescript"]["version"])
        # TS 7 native distributions do not support Android; never let a floating
        # transitive dependency silently substitute one for the pinned JS engine.
        self.assertFalse(any("@typescript/typescript-" in path for path in packages))
        self.assertEqual(1, sum(path.endswith("/typescript") for path in packages))

    def test_extracts_source_without_lifecycle_hooks_or_platform_binaries(self):
        data = self.archive("package/index.js", b"module.exports = 42;")
        self.assertEqual([("index.js", b"module.exports = 42;")], list(bundle.archive_records(data)))
        for name in ("package/../escape", "/package/absolute", "package/a\\b", "package/a.node", "package/a.exe", "package/a/./x"):
            with self.assertRaises(ValueError, msg=name):
                list(bundle.archive_records(self.archive(name, b"bad")))

    def test_rejects_symlinks_hardlinks_duplicate_and_special_archive_entries(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE):
            with self.assertRaises(ValueError):
                list(bundle.archive_records(self.archive("package/source.js", b"", kind)))
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            for _ in range(2):
                record = tarfile.TarInfo("package/index.js")
                archive.addfile(record, io.BytesIO())
        with self.assertRaises(ValueError):
            list(bundle.archive_records(output.getvalue()))

    def test_distribution_download_is_hashed_atomic_and_rechecks_cache(self):
        data = b"pinned npm fixture"
        package = {"resolved": "https://registry.npmjs.org/fixture/-/fixture-1.tgz", "integrity": "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()}
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            with mock.patch.object(bundle.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                file = bundle.verified_archive(package, cache)
            self.assertEqual(data, file.read_bytes())
            with mock.patch.object(bundle.urllib.request, "urlopen", side_effect=AssertionError("must reuse verified data")):
                self.assertEqual(file, bundle.verified_archive(package, cache))
            file.write_bytes(b"corrupt cache")
            with mock.patch.object(bundle.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupt network")):
                with self.assertRaises(ValueError):
                    bundle.verified_archive(package, cache)
            self.assertFalse(list(cache.glob("*.partial")))

    def test_payload_identity_covers_both_locks_and_launch_recipe(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in bundle.BUNDLE_INPUTS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            with mock.patch.object(bundle, "ROOT", root):
                before = bundle.bundle_id()
                self.assertRegex(before, r"^[a-f0-9]{64}$")
                for name in bundle.BUNDLE_INPUTS:
                    path = root / name
                    content = path.read_bytes()
                    path.write_bytes(content + b"changed")
                    self.assertNotEqual(before, bundle.bundle_id(), name)
                    path.write_bytes(content)

    def test_gradle_and_packager_hash_identical_ordered_inputs(self):
        for module in ("app", "native-runtime"):
            gradle = (bundle.ROOT / module / "build.gradle.kts").read_text()
            listed = re.search(r'listOf\(("tools/native/runtime-lock.json".*?)\)\.forEach', gradle).group(1)
            self.assertEqual(list(bundle.BUNDLE_INPUTS), re.findall(r'"([^"]+)"', listed))
            self.assertIn("nativeBundleDigest.update(0.toByte())", gradle)
            self.assertIn('versionName = "0.3.0-preview.1"', gradle)

    def test_inventory_preserves_original_source_urls_and_licenses(self):
        inventory = bundle.inventory()
        self.assertTrue(inventory["offline"])
        self.assertFalse(inventory["lifecycleScripts"])
        self.assertFalse(inventory["optionalDependencies"])
        self.assertEqual(len(bundle.packages()), len(inventory["packages"]))
        self.assertIn("Pyright's typeshed", bundle.notice())
        self.assertIn("Apache-2.0", bundle.notice())

    @staticmethod
    def archive(name, data, kind=tarfile.REGTYPE):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            record = tarfile.TarInfo(name)
            record.type = kind
            record.linkname = "../../host"
            record.size = len(data)
            archive.addfile(record, io.BytesIO(data))
        return output.getvalue()


if __name__ == "__main__":
    unittest.main()
