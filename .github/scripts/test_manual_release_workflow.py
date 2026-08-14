import unittest
from pathlib import Path

WORKFLOW = Path(__file__).parents[1] / "workflows" / "manual-generate-release.yml"
MANIFEST_WORKFLOW = Path(__file__).parents[1] / "workflows" / "update-release-manifest.yml"
HANDOFF_PUBLISHER = Path(__file__).parent / "publish_release_handoff.sh"


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
        self.assertIn("RELEASE_AUTOMATIC_WRITABLE", probe)
        self.assertIn("RELEASE_CROSS_REPO_WRITABLE", probe)
        self.assertIn("RELEASE_TOKEN_KIND=automatic", probe)
        self.assertIn("RELEASE_TOKEN_KIND=cross-repo", probe)

    def test_publisher_reconciles_and_falls_back_only_to_preflighted_credentials(self):
        publish = self.step("Create draft, verify every uploaded asset, then publish")
        self.assertIn("AUTOMATIC_TOKEN: ${{ secrets.GITHUB_TOKEN }}", publish)
        self.assertIn("CROSS_REPO_TOKEN: ${{ secrets.PIPELINE_TOKEN }}", publish)
        self.assertIn('use_token "$RELEASE_TOKEN_KIND"', publish)
        self.assertIn('switch_token()', publish)
        self.assertIn('export GH_TOKEN="$AUTOMATIC_TOKEN"', publish)
        self.assertIn('export GH_TOKEN="$CROSS_REPO_TOKEN"', publish)
        self.assertIn('exact-empty-draft', publish)
        self.assertIn('for asset_path in release-staging/*', publish)
        self.assertNotIn('gh release upload "$RELEASE_TAG" "$asset_path" --clobber', publish)

    def test_recovery_sets_both_cleanup_titles_and_cleanup_defaults_them(self):
        relink = self.step("Run LinkerToOtzaria relink on this snapshot (and wait)")
        cleanup = self.step("Cancel any in-flight relink for this build (no orphaned linker run)")
        self.assertIn('echo "KAGGLE_TITLE=kaggle-relink request=$RELINK_REQUEST_ID', relink)
        self.assertIn(': "${RELINK_TITLE:=}"', cleanup)
        self.assertIn(': "${KAGGLE_TITLE:=}"', cleanup)

    def test_large_snapshot_uses_content_addressed_release_not_actions_artifact(self):
        publish = self.step("Publish immutable snapshot release for the relink run")
        self.assertIn('tag="lines-snapshot-sha256-$SNAPSHOT_ZST_SHA256"', publish)
        self.assertIn('gh release create "$tag"', publish)
        self.assertIn('gh release upload "$tag" "$snapshot"', publish)
        self.assertIn('digest=="sha256:"+sys.argv[3]', publish)
        self.assertNotIn("actions/upload-artifact", publish)
        self.assertNotIn("Upload snapshot artifact for the relink run", self.workflow)

        relink = self.step("Run LinkerToOtzaria relink on this snapshot (and wait)")
        self.assertIn('SNAPSHOT_RELEASE_TAG="lines-snapshot-sha256-$SNAPSHOT_ZST_SHA256"', relink)
        self.assertIn("recovery parent snapshot release is missing or not byte-exact", relink)
        self.assertNotIn("recovery parent must retain exactly one live source snapshot artifact", relink)

    def test_weekly_workflow_has_no_actions_artifact_handoffs(self):
        self.assertNotIn("actions/upload-artifact", self.workflow)
        self.assertNotIn("actions/download-artifact", self.workflow)
        self.assertNotIn("gh run download", self.workflow)
        self.assertIn("pipeline-result-run-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}", self.workflow)
        self.assertIn("linker-output-${EXPECTED_RELINK_REQUEST_ID}-${RUN_ATTEMPT}", self.workflow)

    def test_handoff_prereleases_do_not_pollute_database_manifest(self):
        refresh = self.workflow.split("  refresh-release-manifest:\n", 1)[1]
        self.assertIn("(.prerelease|not)", refresh)
        standalone = MANIFEST_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("(.prerelease|not)", standalone)
        self.assertIn("github.event.release.prerelease == false", standalone)

    def test_split_kaggle_child_releases_and_reacquires_host_lease(self):
        relink = self.step("Run LinkerToOtzaria relink on this snapshot (and wait)")
        release = (
            'python3 .pipeline-control/.github/scripts/host_lease.py release '
            '--state "$HOST_LEASE_STATE"'
        )
        reacquire = (
            "python3 .pipeline-control/.github/scripts/host_lease.py start \\\n"
            "            --lock /run/lock/otzaria/host-heavy.lock"
        )
        dispatch_case = 'case "$SERIAL_LINKER_TARGET" in\n            kaggle)'
        terminal = "completed:success) break"

        self.assertEqual(relink.count(release), 1)
        self.assertEqual(relink.count(reacquire), 1)
        self.assertLess(relink.index(release), relink.index(dispatch_case))
        self.assertLess(relink.index(terminal), relink.index(reacquire))
        self.assertNotIn(
            'if [ "$SERIAL_LINKER_TARGET" = server ]; then',
            relink,
            "the split Kaggle child also needs the Oracle host lease",
        )

    def test_parent_timeout_covers_db_build_and_complete_split_child(self):
        self.assertIn(
            "    timeout-minutes: 1440\n",
            self.workflow,
            "the self-hosted parent must outlive DB generation plus the legal split child chain",
        )
        self.assertIn("90m GPU NER + 480m CPU resolution", self.workflow)
        self.assertEqual(
            self.workflow.count('--ttl 90000'),
            2,
            "both lease lives must exceed the 24-hour parent ceiling",
        )

    def test_weekly_database_releases_default_to_final(self):
        prerelease_input = self.workflow.split("      prerelease:\n", 1)[1].split(
            "      source_commit:\n", 1
        )[0]
        self.assertIn("default: false", prerelease_input)
        self.assertIn("Weekly database builds are final releases", prerelease_input)

    def test_reuse_skips_invalid_legacy_provenance_but_not_the_requested_source(self):
        lookup = self.step("Find and verify exact provenance")
        validation = 'if ! python3 .github/scripts/validate_build_provenance.py "$file"; then'
        requested_source_guard = 'if [ "$target" = "$SOURCE_COMMIT" ]; then'
        legacy_skip = '::warning::Skipping legacy release $tag with invalid build provenance'

        self.assertIn(validation, lookup)
        self.assertIn(requested_source_guard, lookup)
        self.assertIn(legacy_skip, lookup)
        self.assertLess(lookup.index(validation), lookup.index(requested_source_guard))
        self.assertLess(lookup.index(requested_source_guard), lookup.index(legacy_skip))

    def test_release_publisher_rejects_asset_names_github_would_normalize(self):
        helper = HANDOFF_PUBLISHER.read_text(encoding="utf-8")
        self.assertIn("release asset basename is unsafe or would be normalized by GitHub", helper)
        self.assertIn("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$", helper)
        self.assertIn('repos/$GITHUB_REPOSITORY/releases/tags/$tag', helper)
        self.assertIn("targetCommitish:.target_commitish", helper)
        self.assertNotIn('gh release view "$tag" --json', helper)


if __name__ == "__main__":
    unittest.main()
