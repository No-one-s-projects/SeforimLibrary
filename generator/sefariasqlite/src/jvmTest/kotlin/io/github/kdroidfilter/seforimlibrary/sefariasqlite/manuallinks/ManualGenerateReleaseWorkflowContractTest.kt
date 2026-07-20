package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualGenerateReleaseWorkflowContractTest {
    @Test
    fun resultAndRecoveryContractsRemainCompleteAndCanonical() {
        val workflow = repositoryRoot()
            .resolve(".github/workflows/manual-generate-release.yml")
            .readText()
        val requiredResultFields = listOf(
            "schema_version",
            "status",
            "correlation_id",
            "child_run_id",
            "child_run_attempt",
            "source_commit",
            "sefaria_tag",
            "sefaria_release_metadata_sha256",
            "sefaria_archive_sha256",
            "otzaria_tag",
            "otzaria_asset_sha256",
            "expected_links_commit",
            "otzaria_target_commit",
            "release_tag",
            "build_provenance_sha256",
            "lineage_sha256",
            "config_sha256",
            "source_links_tree_sha256",
            "packaged_links_tree_sha256",
            "assets",
        )
        requiredResultFields.forEach { field ->
            assertTrue(workflow.contains("\"$field\""), "pipeline result must contain $field")
        }
        assertTrue(workflow.contains("json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(\",\", \":\")) + \"\\n\""))
        assertTrue(workflow.contains("gh api --paginate \"repos/\$GITHUB_REPOSITORY/releases?per_page=100\""))
        assertTrue(workflow.contains("\"lineage_sha256\""))
        assertTrue(workflow.contains("build_provenance.json --argjson size"))
        assertTrue(workflow.contains("refresh-release-manifest:"))
        assertTrue(workflow.contains("mapfile -t SEFARIA_DB_ROOTS"))
        assertTrue(workflow.contains("test \"\${#SEFARIA_DB_ROOTS[@]}\" -eq 1"))
        assertTrue(workflow.contains(".sefaria.archive == \$metadata[0].archive"))
        assertTrue(workflow.contains("repos/otzaria/otzaria-library/commits/\$OTZARIA_TAG"))
        assertTrue(
            workflow.contains(
                "GENERATED_AT=\$(jq -r '[.[].publishedAt | select(. != null)] | max // \"1970-01-01T00:00:00Z\"'",
            ),
            "manifest generation must converge from immutable release timestamps",
        )
        assertFalse(workflow.contains("GENERATED_AT=\$(date"))
        assertFalse(workflow.contains("-name database_export -print -quit"))
        assertFalse(workflow.contains("gh api \"repos/\$GITHUB_REPOSITORY/releases?per_page=100\" >"))
    }

    @Test
    fun forDbRulesArchiveIsPinnedByDigestVerifiedAndRecorded() {
        val workflow = repositoryRoot()
            .resolve(".github/workflows/manual-generate-release.yml")
            .readText()
        // Declared as a required, sha256-shaped pinned input — same discipline as
        // the Sefaria/Otzaria pins, so the build is a pure function of its inputs.
        assertTrue(workflow.contains("fordb_archive_sha256:"), "ForDB archive digest must be a pinned input")
        assertTrue(
            workflow.contains("[[ \"\$FORDB_ARCHIVE_SHA\" =~ ^[0-9a-f]{64}\$ ]]"),
            "the ForDB digest input must be validated as a sha256",
        )
        // Fetched exactly once and verified against the pin (fail closed on drift).
        assertTrue(workflow.contains("gh release download fordb-latest -R otzaria/otzaria-library"))
        assertTrue(workflow.contains("echo \"\$FORDB_ARCHIVE_SHA  \$FORDB_ARCHIVE\" | sha256sum -c -"))
        // The one verified archive is handed to every ForDB post-process JVM.
        assertTrue(workflow.contains("-PforDbArchive=\"\$FORDB_ARCHIVE\""))
        assertTrue(workflow.contains("-PforDbSha256=\${{ inputs.fordb_archive_sha256 }}"))
        // Recorded in provenance and part of the reuse-match, so a ForDB change can
        // never be silently reused as an older build.
        assertTrue(workflow.contains("\"fordb_archive_sha256\": os.environ[\"FORDB_ARCHIVE_SHA\"]"))
        assertTrue(workflow.contains(".fordb_archive_sha256==\$fd"))
    }

    @Test
    fun bothReleaseManifestWritersConvergeOnImmutableState() {
        val root = repositoryRoot()
        val workflows = listOf(
            root.resolve(".github/workflows/manual-generate-release.yml").readText(),
            root.resolve(".github/workflows/update-release-manifest.yml").readText(),
        )
        workflows.forEach { workflow ->
            assertTrue(workflow.contains("del(.assets[].downloadCount)"))
            assertTrue(
                workflow.contains(
                    "GENERATED_AT=\$(jq -r '[.[].publishedAt | select(. != null)] | max // \"1970-01-01T00:00:00Z\"'",
                ),
            )
            assertFalse(workflow.contains("GENERATED_AT=\$(date"))
        }
    }

    @Test
    fun pinnedCorpusWorkflowRemainsImmutableAndComplete() {
        val root = repositoryRoot()
        val workflow = root.resolve(".github/workflows/manual-links-corpus-qa.yml").readText()
        val validator = root.resolve(".github/scripts/validate-sefaria-release-metadata.py").readText()

        listOf(
            "mode:",
            "otzaria_commit:",
            "sefaria_tag:",
            "sefaria_release_metadata_sha256:",
            "sefaria_archive_sha256:",
            "seforim_tool_commit:",
        ).forEach { input ->
            assertTrue(workflow.contains(input), "pinned corpus workflow must expose $input")
        }
        assertTrue(workflow.contains("ref: \${{ inputs.seforim_tool_commit }}"))
        assertTrue(workflow.contains("ref: \${{ inputs.otzaria_commit }}"))
        assertTrue(workflow.contains("validate-sefaria-release-metadata.py"))
        assertTrue(workflow.contains(":sefariasqlite:manualLinksCorpusTest"))
        assertTrue(workflow.contains("EXPECTED_TARGET_RECORDS: '65397'"))
        assertTrue(workflow.contains("EXPECTED_SOURCE_RECORDS: '17980'"))
        assertTrue(workflow.contains("EXPECTED_EXCLUDED_RECORDS: '174'"))
        assertTrue(workflow.contains("EXPECTED_ANCHORS: '17980'"))
        assertTrue(workflow.contains("test \"\${#EXPORT_ROOTS[@]}\" -eq 1"))
        assertTrue(workflow.contains(".refs.missing == 0"))
        assertTrue(workflow.contains(".refs.duplicate == 0"))
        assertTrue(workflow.contains(".anchors.drifted == 0"))
        assertTrue(workflow.contains(".packaging_collisions == 0"))
        assertTrue(workflow.contains("actions/upload-artifact@v4"))
        assertTrue(workflow.contains("include-hidden-files: true"), "completion marker is a dotfile")
        assertTrue(workflow.contains("DISK_AVAILABLE_KIB < 12582912"), "corpus gate requires 12 GiB free disk")
        assertTrue(workflow.contains("MEM_AVAILABLE_KIB < 6291456"), "corpus gate requires 6 GiB available memory")
        assertTrue(workflow.contains("--max-workers=2"), "full-corpus parallelism must be bounded")
        assertTrue(
            workflow.indexOf("Preflight full-corpus capacity") <
                workflow.indexOf("Download and verify the exact Sefaria export"),
            "capacity preflight must fail before the large export is downloaded or extracted",
        )
        assertFalse(workflow.contains("releases/latest"), "immutable corpus QA must not discover a moving release")

        assertTrue(validator.contains("archive.parts must be non-empty"))
        assertTrue(validator.contains("archive parts must have unique UTF-8-sorted names"))
        assertTrue(validator.contains("archive part sizes do not sum to archive.size"))
    }

    private fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve(".github/workflows/manual-generate-release.yml")) }
        ?: error("Could not locate repository root")
}
