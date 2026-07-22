import unittest
from pathlib import Path

WORKFLOW = Path(__file__).parents[1] / "workflows" / "manual-generate-release.yml"


class ManualReleaseWorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def step(self, name):
        marker = f"      - name: {name}\n"
        self.assertEqual(self.workflow.count(marker), 1, f"step {name!r} must exist exactly once")
        return self.workflow.split(marker, 1)[1].split("\n      - ", 1)[0]

    def test_release_write_is_probed_before_the_expensive_build(self):
        probe = self.step("Preflight release write credentials")
        self.assertLess(
            self.workflow.index("      - name: Preflight release write credentials\n"),
            self.workflow.index("      - name: Mount RAM-backed build dir (tmpfs)\n"),
        )
        self.assertIn("[ \"$code\" = 422 ]", probe)
        self.assertIn("RELEASE_TOKEN_KIND=automatic", probe)
        self.assertIn("RELEASE_TOKEN_KIND=cross-repo", probe)

    def test_publisher_uses_only_the_preflight_selected_credential(self):
        publish = self.step("Create draft, verify every uploaded asset, then publish")
        self.assertIn("AUTOMATIC_TOKEN: ${{ secrets.GITHUB_TOKEN }}", publish)
        self.assertIn("CROSS_REPO_TOKEN: ${{ secrets.PIPELINE_TOKEN }}", publish)
        self.assertIn('case "$RELEASE_TOKEN_KIND" in', publish)
        self.assertIn('export GH_TOKEN="$AUTOMATIC_TOKEN"', publish)
        self.assertIn('export GH_TOKEN="$CROSS_REPO_TOKEN"', publish)

    def test_recovery_sets_both_cleanup_titles_and_cleanup_defaults_them(self):
        relink = self.step("Run LinkerToOtzaria relink on this snapshot (and wait)")
        cleanup = self.step("Cancel any in-flight relink for this build (no orphaned linker run)")
        self.assertIn('echo "KAGGLE_TITLE=kaggle-relink request=$RELINK_REQUEST_ID', relink)
        self.assertIn(': "${RELINK_TITLE:=}"', cleanup)
        self.assertIn(': "${KAGGLE_TITLE:=}"', cleanup)


if __name__ == "__main__":
    unittest.main()
