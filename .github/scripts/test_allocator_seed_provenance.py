import copy
import json
from pathlib import Path
import tempfile
import unittest

from validate_allocator_seed_provenance import load, validate


def valid_provenance():
    return {
        "schema_version": 3,
        "release_version": 18,
        "release_tag": "v18-20260727202417",
        "build_mode": "local-full-corpus-context-relink",
        "source_commit": "a" * 40,
        "linker_commit": "b" * 40,
        "database": {
            "db_version": 18,
            "db_schema_version": 2,
            "quick_check": "ok",
            "books": 7284,
            "linker_artifact_records": 1940879,
            "linker_links": 1535154,
            "link_anchors": 1582920,
            "link_target_ranges": 791611,
            "heading_links": 0,
            "relative_leil_records": 11098,
            "relative_lekaman_records": 10212,
            "removed_books": ["גירסת ספריה", "עריכת ספר באוצריא"],
        },
        "snapshot": {
            "schema_version": 2,
            "books": 7284,
            "lines": 5878292,
            "quick_check": "ok",
        },
        "delta": {
            "published": False,
            "attempted_from_version": 17,
            "reason": "allocator lineages differ",
        },
        "assets": [
            {"name": "lines_snapshot.db.zst", "size": 11, "sha256": "1" * 64},
            {"name": "seforim.db.buildstate", "size": 12, "sha256": "2" * 64},
            {"name": "seforim.db.zst", "size": 13, "sha256": "3" * 64},
        ],
    }


class AllocatorSeedProvenanceTest(unittest.TestCase):
    def test_accepts_v18_shape_and_binds_selected_release(self):
        validate(
            valid_provenance(),
            expected_tag="v18-20260727202417",
            expected_version=18,
        )

    def test_rejects_selected_release_mismatch(self):
        with self.assertRaisesRegex(ValueError, "selected release"):
            validate(valid_provenance(), expected_tag="v19-20260727202417")

    def test_rejects_heading_links(self):
        value = valid_provenance()
        value["database"]["heading_links"] = 1
        with self.assertRaisesRegex(ValueError, "heading links"):
            validate(value)

    def test_rejects_snapshot_book_count_mismatch(self):
        value = valid_provenance()
        value["snapshot"]["books"] -= 1
        with self.assertRaisesRegex(ValueError, "book counts"):
            validate(value)

    def test_rejects_missing_release_asset(self):
        value = valid_provenance()
        value["assets"].pop()
        with self.assertRaisesRegex(ValueError, "three required release assets"):
            validate(value)

    def test_rejects_unknown_key(self):
        value = valid_provenance()
        value["database"]["unexpected"] = 1
        with self.assertRaisesRegex(ValueError, "database key set"):
            validate(value)

    def test_rejects_duplicate_json_key(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "seed.json"
            path.write_text('{"schema_version":3,"schema_version":3}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate key"):
                load(path)

    def test_rejects_boolean_integer(self):
        value = copy.deepcopy(valid_provenance())
        value["database"]["books"] = True
        with self.assertRaisesRegex(ValueError, "database.books"):
            validate(value)

    def test_loads_pretty_printed_local_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "seed.json"
            path.write_text(
                json.dumps(valid_provenance(), ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            validate(load(path))


if __name__ == "__main__":
    unittest.main()
