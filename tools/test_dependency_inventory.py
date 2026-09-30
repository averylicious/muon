import copy
import json
import unittest
from dependency_inventory import MARKER, SCOPES, parse


class InventoryTest(unittest.TestCase):
    commit = "a" * 40

    def fixture(self):
        return {"schema": 1, "commit": self.commit, "configurations": [
            {"scope": name, "modules": ["androidx.media3:media3-common:1.11.0"]} for name in sorted(SCOPES)]}

    def test_file_and_timestamped_log_preserve_scoped_components(self):
        data = self.fixture()
        raw = json.dumps(data)
        self.assertEqual(data, parse(raw, self.commit))
        self.assertEqual(data, parse("other log\n2026-09-30 " + MARKER + raw + "\ndone", self.commit))

    def test_missing_duplicate_and_wrong_commit_records_fail(self):
        raw = json.dumps(self.fixture())
        for text, commit in [("not an inventory", self.commit), (MARKER + raw + "\n" + MARKER + raw, self.commit),
                             (raw, "b" * 40), (raw, "HEAD")]:
            with self.subTest(text=text[:20], commit=commit), self.assertRaises(ValueError):
                parse(text, commit)

    def test_missing_empty_duplicate_and_unexpected_scopes_fail(self):
        data = self.fixture()
        variants = []
        for change in ["missing", "empty", "duplicate", "other"]:
            d = copy.deepcopy(data)
            if change == "missing": d["configurations"].pop()
            if change == "empty": d["configurations"][0]["modules"] = []
            if change == "duplicate": d["configurations"][0]["scope"] = d["configurations"][1]["scope"]
            if change == "other": d["configurations"][0]["scope"] = ":app:unknown"
            variants.append(d)
        for d in variants:
            with self.assertRaises(ValueError): parse(json.dumps(d), self.commit)

    def test_malformed_unsorted_and_duplicate_coordinates_fail(self):
        for modules in [["missing-version"], ["a:b:"], ["a:b:1\n"], ["z:b:1", "a:b:1"], ["a:b:1", "a:b:1"], [None]]:
            d = self.fixture(); d["configurations"][0]["modules"] = modules
            with self.subTest(modules=modules), self.assertRaises(ValueError):
                parse(json.dumps(d), self.commit)
