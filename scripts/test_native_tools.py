"""Host-side package/source lock and transport regressions; no network needed."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bundle = load("native-bundle")
sources = load("native-sources")


class NativeToolsTests(unittest.TestCase):
    def test_dependency_alternative_prefers_already_selected_lts(self):
        packages = [{"Package": "nodejs"}, {"Package": "nodejs-lts"}, {"Package": "npm", "Depends": "nodejs | nodejs-lts"}]
        resolved = bundle.resolve(packages, ["nodejs-lts", "npm"])
        self.assertEqual(["nodejs-lts", "npm"], [v["Package"] for v in resolved])

    def test_dependency_cycles_and_unavailable_dependencies(self):
        packages = [{"Package": "a", "Depends": "b"}, {"Package": "b", "Depends": "a"}]
        self.assertEqual(2, len(bundle.resolve(packages, ["a"])))
        with self.assertRaises(ValueError):
            bundle.resolve([{"Package": "a", "Depends": "missing"}], ["a"])

    def test_control_paragraphs_keep_continuations(self):
        value = bundle.paragraphs("Package: a\nDescription: first\n second\n\nPackage: b\nVersion: 1\n")
        self.assertEqual("first\nsecond", value[0]["Description"])
        self.assertEqual("b", value[1]["Package"])

    def test_source_inventory_matches_every_locked_binary_and_recipe_revision(self):
        binary_bytes = (ROOT / "tools/native/runtime-lock.json").read_bytes()
        binary = json.loads(binary_bytes)
        source = json.loads((ROOT / "tools/native/sources-lock.json").read_text())
        self.assertEqual(hashlib.sha256(binary_bytes).hexdigest(), source["packageLockSha256"])
        self.assertEqual(binary["recipes"], source["recipes"]["commit"])
        indexed = {v["name"]: v["binaryVersion"] for v in source["packages"]}
        for arch in binary["architectures"].values():
            packages = arch["packages"]
            self.assertEqual(indexed, {v["Package"]: v["Version"] for v in packages})
            self.assertEqual(1, sum(p["Package"] in ("nodejs", "nodejs-lts") for p in packages))
            for package in packages:
                self.assertRegex(package["SHA256"], "^[a-f0-9]{64}$")
        copyleft = {p["recipe"] for p in source["packages"] if any(v in p["license"] for v in ("GPL", "MPL"))}
        self.assertTrue(copyleft.issubset({v["package"] for v in source["inputs"]}))
        for record in source["inputs"]:
            self.assertRegex(record["sha256"], "^[a-f0-9]{64}$")
            self.assertTrue(record["url"].startswith("https://"))
        self.assertEqual(20, sum(v["filename"].startswith("bash-patch-") for v in source["inputs"]))
        self.assertEqual(6, sum(v["filename"].startswith("readline-patch-") for v in source["inputs"]))

    def test_source_download_is_atomic_hashed_and_rechecks_cache(self):
        data = b"source fixture"
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / "source.tar.gz"
            with mock.patch.object(sources.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                sources.verified_download("https://example.invalid/source", digest, destination)
            self.assertEqual(data, destination.read_bytes())
            with mock.patch.object(sources.urllib.request, "urlopen", side_effect=AssertionError("cache must be checked before network")):
                sources.verified_download("https://example.invalid/source", digest, destination)
            with mock.patch.object(sources.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupt")):
                with self.assertRaises(ValueError):
                    sources.verified_download("https://example.invalid/source", "0" * 64, destination)
            self.assertEqual(data, destination.read_bytes())
            self.assertFalse(destination.with_suffix(".gz.partial").exists())

    def test_source_mirrors_cannot_weaken_the_pinned_digest(self):
        data = b"verified source fixture"
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / "source.tar.gz"
            with mock.patch.object(sources.urllib.request, "urlopen", side_effect=[io.BytesIO(b"proxy HTML error"), io.BytesIO(data)]) as download:
                sources.verified_download("https://example.invalid/source", digest, destination, ["https://mirror.invalid/source"])
            self.assertEqual(data, destination.read_bytes())
            self.assertEqual(["https://example.invalid/source", "https://mirror.invalid/source"], [c.args[0].full_url for c in download.call_args_list])
            destination.unlink()
            with mock.patch.object(sources.urllib.request, "urlopen", side_effect=[io.BytesIO(b"wrong 1"), io.BytesIO(b"wrong 2")]):
                with self.assertRaisesRegex(ValueError, "expected " + digest):
                    sources.verified_download("https://example.invalid/source", digest, destination, ["https://mirror.invalid/source"])
            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_suffix(".gz.partial").exists())

    def test_source_build_reports_every_failure_without_publishing_partial_inventory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            index = root / "index.json"
            index.write_text(json.dumps({"schema": 1, "packageLockSha256": hashlib.sha256((ROOT / "tools/native/runtime-lock.json").read_bytes()).hexdigest(), "recipes": {"url": "https://example.invalid/recipes", "sha256": "a" * 64}, "inputs": [{"filename": n, "url": "https://example.invalid/" + n, "sha256": "b" * 64} for n in ("first", "second")]}))
            def download(url, sha, destination, mirrors=()):
                if "recipes" not in url:
                    raise ValueError("fixture unavailable " + url)
            with mock.patch.object(sources, "verified_download", side_effect=download) as fetch, mock.patch.object(sources.subprocess, "run") as archive:
                with self.assertRaisesRegex(ValueError, r"(?s)first:.*second:"):
                    sources.build(index, root / "output")
            self.assertEqual(3, fetch.call_count)
            archive.assert_not_called()
            self.assertFalse((root / "output/source-lock.json").exists())
            self.assertFalse((root / "output/SHA256SUMS").exists())

    def test_sources_cannot_use_unpinned_file_urls(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                sources.verified_download("file:///etc/passwd", "0" * 64, Path(folder) / "source")


if __name__ == "__main__":
    unittest.main()
