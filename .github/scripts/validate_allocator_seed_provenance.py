#!/usr/bin/env python3
"""Validate local schema-3 provenance for allocator seeding only.

This contract deliberately does not prove that a weekly build can be reused.
It only binds a complete, healthy local release to the build-state that a
subsequent full rebuild may use to preserve allocator IDs.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


SHA40 = re.compile(r"[0-9a-f]{40}")
SHA64 = re.compile(r"[0-9a-f]{64}")
RELEASE_TAG = re.compile(r"v([1-9][0-9]*)-([0-9]{14})")
TOP_LEVEL_KEYS = {
    "schema_version",
    "release_version",
    "release_tag",
    "build_mode",
    "source_commit",
    "linker_commit",
    "database",
    "snapshot",
    "delta",
    "assets",
}
DATABASE_KEYS = {
    "db_version",
    "db_schema_version",
    "quick_check",
    "books",
    "linker_artifact_records",
    "linker_links",
    "link_anchors",
    "link_target_ranges",
    "heading_links",
    "relative_leil_records",
    "relative_lekaman_records",
    "removed_books",
}
SNAPSHOT_KEYS = {"schema_version", "books", "lines", "quick_check"}
DELTA_KEYS = {"published", "attempted_from_version", "reason"}
REQUIRED_ASSETS = {
    "lines_snapshot.db.zst",
    "seforim.db.buildstate",
    "seforim.db.zst",
}


def load(path: Path) -> dict:
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value:
                raise ValueError(f"duplicate key {key!r}")
            value[key] = item
        return value

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs)
    if not isinstance(value, dict):
        raise ValueError("allocator seed provenance must be an object")
    return value


def require_exact_keys(value: object, keys: set[str], field: str) -> dict:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"invalid {field} key set")
    return value


def require_integer(value: object, field: str, *, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise ValueError(f"{field} must be an integer >= {minimum}")
    return value


def validate(
    value: dict,
    *,
    expected_tag: str | None = None,
    expected_version: int | None = None,
) -> None:
    require_exact_keys(value, TOP_LEVEL_KEYS, "top-level")
    if value["schema_version"] != 3 or type(value["schema_version"]) is not int:
        raise ValueError("schema_version must be integer 3")
    if value["build_mode"] != "local-full-corpus-context-relink":
        raise ValueError("unsupported local build_mode")

    release_version = require_integer(value["release_version"], "release_version", minimum=1)
    release_tag = value["release_tag"]
    if not isinstance(release_tag, str):
        raise ValueError("release_tag must be a string")
    tag_match = RELEASE_TAG.fullmatch(release_tag)
    if not tag_match or int(tag_match.group(1)) != release_version:
        raise ValueError("release_tag does not match release_version")
    if expected_tag is not None and release_tag != expected_tag:
        raise ValueError("release_tag differs from the selected release")
    if expected_version is not None and release_version != expected_version:
        raise ValueError("release_version differs from the selected release")

    for field in ("source_commit", "linker_commit"):
        if not isinstance(value[field], str) or not SHA40.fullmatch(value[field]):
            raise ValueError(f"invalid {field}")

    database = require_exact_keys(value["database"], DATABASE_KEYS, "database")
    db_version = require_integer(database["db_version"], "database.db_version", minimum=1)
    if db_version != release_version:
        raise ValueError("database.db_version differs from release_version")
    require_integer(database["db_schema_version"], "database.db_schema_version", minimum=1)
    if database["quick_check"] != "ok":
        raise ValueError("database quick_check is not ok")
    books = require_integer(database["books"], "database.books", minimum=1)
    for field in (
        "linker_artifact_records",
        "linker_links",
        "link_anchors",
        "link_target_ranges",
        "heading_links",
        "relative_leil_records",
        "relative_lekaman_records",
    ):
        require_integer(database[field], f"database.{field}")
    if database["heading_links"] != 0:
        raise ValueError("database contains heading links")
    removed_books = database["removed_books"]
    if (
        not isinstance(removed_books, list)
        or any(not isinstance(item, str) or not item for item in removed_books)
        or len(removed_books) != len(set(removed_books))
    ):
        raise ValueError("database.removed_books must contain unique non-empty strings")

    snapshot = require_exact_keys(value["snapshot"], SNAPSHOT_KEYS, "snapshot")
    require_integer(snapshot["schema_version"], "snapshot.schema_version", minimum=1)
    if snapshot["quick_check"] != "ok":
        raise ValueError("snapshot quick_check is not ok")
    if require_integer(snapshot["books"], "snapshot.books", minimum=1) != books:
        raise ValueError("snapshot and database book counts differ")
    require_integer(snapshot["lines"], "snapshot.lines", minimum=1)

    delta = require_exact_keys(value["delta"], DELTA_KEYS, "delta")
    if delta["published"] is not False:
        raise ValueError("schema-3 seed must describe a full release without a published delta")
    attempted = require_integer(
        delta["attempted_from_version"],
        "delta.attempted_from_version",
        minimum=1,
    )
    if attempted >= release_version:
        raise ValueError("delta.attempted_from_version must precede release_version")
    if not isinstance(delta["reason"], str) or not delta["reason"].strip():
        raise ValueError("delta.reason must be a non-empty string")

    assets = value["assets"]
    if not isinstance(assets, list):
        raise ValueError("assets must be an array")
    names = []
    for index, asset in enumerate(assets):
        require_exact_keys(asset, {"name", "size", "sha256"}, f"asset descriptor {index}")
        name = asset["name"]
        if not isinstance(name, str) or not name or Path(name).name != name:
            raise ValueError(f"invalid asset name {index}")
        require_integer(asset["size"], f"asset size {index}", minimum=1)
        if not isinstance(asset["sha256"], str) or not SHA64.fullmatch(asset["sha256"]):
            raise ValueError(f"invalid asset digest {index}")
        names.append(name)
    if names != sorted(names, key=lambda item: item.encode("utf-8")):
        raise ValueError("asset names must be bytewise sorted")
    if len(names) != len(set(names)):
        raise ValueError("asset names must be unique")
    if set(names) != REQUIRED_ASSETS:
        raise ValueError("schema-3 seed must bind exactly the three required release assets")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path")
    parser.add_argument("--expected-tag")
    parser.add_argument("--expected-version", type=int)
    args = parser.parse_args()
    try:
        validate(
            load(Path(args.path)),
            expected_tag=args.expected_tag,
            expected_version=args.expected_version,
        )
        return 0
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"allocator seed provenance contract error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
