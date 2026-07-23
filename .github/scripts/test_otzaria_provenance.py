import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("validate_otzaria_provenance.py")
SPEC = importlib.util.spec_from_file_location("validate_otzaria_provenance", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


class OtzariaProvenanceTest(unittest.TestCase):
    target = "a" * 40
    tag = "library-links-test"
    asset_sha = "b" * 64

    def value(self):
        descriptor = lambda name, sha: {"name": name, "size": 1, "sha256": sha}
        return {
            "schema_version": 1,
            "correlation_id": "release:1",
            "target_commit": self.target,
            "tag": self.tag,
            "asset": descriptor("otzaria_latest.zip", self.asset_sha),
            "auxiliary_assets": [
                descriptor("otzaria_dicta_latest.zip", "c" * 64),
                descriptor("talmud_bavli_latest.tar.zst", "d" * 64),
            ],
            "packaging_runtime": {"python": "3.12", "zlib": "1.3", "zip_compression": "deflate"},
            "packaging_toolchain": {
                "schema_version": 1, "python": "3.12", "zlib_build": "1.3",
                "zlib_runtime": "1.3", "gnu_tar": "1.35", "zstd": "1.5",
            },
            "config_sha256": "e" * 64,
            "source_links_tree_sha256": "f" * 64,
            "packaged_links_tree_sha256": "0" * 64,
            "lineage_sha256": "1" * 64,
        }

    def test_exact_canonical_contract_passes(self):
        validator.validate(self.value(), self.target, self.tag, self.asset_sha)

    def test_adversarial_identity_and_type_mutations_fail(self):
        mutations = (
            ("schema_version", True),
            ("target_commit", "2" * 40),
            ("tag", "other"),
            ("asset", {"name": "otzaria_latest.zip", "size": True, "sha256": self.asset_sha}),
            ("auxiliary_assets", []),
        )
        for field, replacement in mutations:
            with self.subTest(field=field):
                value = self.value()
                value[field] = replacement
                with self.assertRaises(ValueError):
                    validator.validate(value, self.target, self.tag, self.asset_sha)

    def test_duplicate_and_noncanonical_json_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "provenance.json"
            path.write_text('{"schema_version":1,"schema_version":1}', encoding="utf-8")
            with self.assertRaises(ValueError):
                validator.load(path)
            path.write_text(json.dumps(self.value(), indent=2), encoding="utf-8")
            with self.assertRaises(ValueError):
                validator.load(path)


if __name__ == "__main__":
    unittest.main()
