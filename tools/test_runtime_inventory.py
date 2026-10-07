import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import runtime_inventory as ri


class RuntimeInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repository"
        self.dir = self.root / "org/robolectric/android-all-instrumented/14-robolectric-test-i7"
        self.dir.mkdir(parents=True)
        self.prefix = "android-all-instrumented-14-robolectric-test-i7"
        for suffix, data in [(".jar", b"synthetic jar bytes"), (".pom", b"<project/>")]:
            (self.dir / (self.prefix + suffix)).write_bytes(data)
            (self.dir / (self.prefix + suffix + ".sha512")).write_text(hashlib.sha512(data).hexdigest())
        self.commit = "a" * 40

    def result(self):
        return ri.inventory(self.root, self.commit)

    def test_receipt_contains_public_coordinates_and_hashes_without_local_paths(self):
        result = self.result()
        self.assertFalse(result["publisher_authenticated"])
        self.assertEqual(len(result["records"]), 4)
        self.assertEqual(2, sum(r.get("same_repository_sha512_match", False) for r in result["records"]))
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertEqual(hashlib.sha256(b"synthetic jar bytes").hexdigest(),
                         next(r for r in result["records"] if r["file"].endswith(".jar"))["sha256"])

    def test_mismatch_fails(self):
        (self.dir / (self.prefix + ".jar")).write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "mismatch"): self.result()

    def test_missing_companion_fails(self):
        (self.dir / (self.prefix + ".pom.sha512")).unlink()
        with self.assertRaisesRegex(ValueError, "missing"): self.result()

    def test_extra_private_file_is_not_exported(self):
        (self.root / "settings.xml").write_text("private")
        with self.assertRaisesRegex(ValueError, "unexpected"): self.result()

    def test_symlink_file_and_directory_are_rejected(self):
        jar = self.dir / (self.prefix + ".jar")
        jar.unlink(); jar.symlink_to(self.dir / (self.prefix + ".pom"))
        with self.assertRaisesRegex(ValueError, "invalid"): self.result()
        jar.unlink(); jar.write_bytes(b"synthetic jar bytes")
        (self.root / "outside").symlink_to(self.dir, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "non-directory"): self.result()

    def test_budget_limits_fail(self):
        for budget, value in [("MAX_FILES", 3), ("MAX_FILE_BYTES", 4), ("MAX_TOTAL_BYTES", 4)]:
            with self.subTest(budget=budget), patch.object(ri, budget, value):
                with self.assertRaises(ValueError): self.result()

    def test_empty_repository_fails(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "no Robolectric"): ri.inventory(Path(root), self.commit)

    def test_invalid_commit_fails(self):
        with self.assertRaisesRegex(ValueError, "commit"): ri.inventory(self.root, "not-a-sha")

    def test_checksum_bytes_must_be_hex_not_private_text(self):
        (self.dir / (self.prefix + ".jar.sha512")).write_text("x" * 128)
        with self.assertRaisesRegex(ValueError, "mismatch"): self.result()
