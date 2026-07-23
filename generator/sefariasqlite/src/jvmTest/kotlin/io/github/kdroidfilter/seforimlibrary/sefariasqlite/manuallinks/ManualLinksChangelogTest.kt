package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ManualLinksChangelogTest {
    @Test
    fun readsAuthoritativeRootMetadataWithFlatOrdinalTransportMirror() {
        val root = Files.createTempDirectory("manual-links-transport")
        val dir = Files.createDirectories(root.resolve("changelogs"))
        val changelog = dir.resolve("0001-r2-changelog_diff.json")
        Files.writeString(
            changelog,
            """{"old_tag":"r1","new_tag":"r2","books":{"en_renamed":[],"he_renamed":[]}}""",
        )
        val metadataJson = metadataJson(
            tag = "r2",
            previousTag = "r1",
            previousDigest = "a".repeat(64),
            changelogSize = Files.size(changelog),
            changelogSha = ManualLinksJson.rawSha256(changelog),
        )
        val metadata = root.resolve("release_metadata.json")
        Files.writeString(metadata, metadataJson)
        Files.writeString(dir.resolve("0001-r2-release_metadata.json"), metadataJson)
        val base = ManualLinksLineage(
            sefariaTag = "r1",
            releaseMetadataSha256 = "a".repeat(64),
            runId = 1,
            runAttempt = 1,
            archiveSha256 = "1".repeat(64),
            archiveSize = 0,
            archiveParts = listOf(AssetDescriptor("part", 0, "2".repeat(64))),
            appliedChangelogChain = emptyList(),
            seforimToolCommit = "b".repeat(40),
            sourceLinksTreeSha256 = "3".repeat(64),
            packagedLinksTreeSha256 = "4".repeat(64),
            configSha256 = "5".repeat(64),
        )

        val chain = ManualLinksChangelog.verifiedChain(
            targetMetadataPath = metadata,
            targetMetadataSha256 = ManualLinksJson.rawSha256(metadata),
            target = ReleaseMetadata.read(metadata),
            base = base,
            changelogDir = dir,
        )

        assertEquals(1, chain.size)
    }

    @Test
    fun readsBaselineMetadataEmittedByReleaseContract() {
        val metadata = Files.createTempFile("baseline-release-metadata", ".json")
        Files.writeString(metadata, metadataJson(tag = "r1", previousTag = null, changelogOldTag = ""))

        val parsed = ReleaseMetadata.read(metadata)

        assertNull(parsed.previous)
        assertNull(parsed.changelogOldTag)
        assertEquals("r1", parsed.changelogNewTag)
    }

    @Test
    fun rejectsEmptyOldTagForNonBaselineMetadata() {
        val metadata = Files.createTempFile("nonbaseline-empty-old-tag", ".json")
        Files.writeString(metadata, metadataJson(tag = "r2", previousTag = "r1", changelogOldTag = ""))

        assertFailsWith<IllegalArgumentException> { ReleaseMetadata.read(metadata) }
    }

    @Test
    fun rejectsNonEmptyOldTagWhenPreviousIsNull() {
        val metadata = Files.createTempFile("baseline-inconsistent-old-tag", ".json")
        Files.writeString(metadata, metadataJson(tag = "r1", previousTag = null, changelogOldTag = "r0"))

        assertFailsWith<IllegalArgumentException> { ReleaseMetadata.read(metadata) }
    }

    @Test
    fun readsFlatOrdinalMetadataAndChangelogPairs() {
        val dir = Files.createTempDirectory("manual-links-chain")
        val changelog = dir.resolve("0001-r2-changelog_diff.json")
        Files.writeString(
            changelog,
            """{"old_tag":"r1","new_tag":"r2","books":{"en_renamed":[{"old_en":"Old","new_en":"New","old_he":"ישן","new_he":"חדש"}],"he_renamed":[]}}""",
        )
        val metadata = dir.resolve("0001-r2-release_metadata.json")
        Files.writeString(
            metadata,
            metadataJson(
                tag = "r2",
                previousTag = "r1",
                previousDigest = "a".repeat(64),
                changelogSize = Files.size(changelog),
                changelogSha = ManualLinksJson.rawSha256(changelog),
            ),
        )
        val base = ManualLinksLineage(
            sefariaTag = "r1",
            releaseMetadataSha256 = "a".repeat(64),
            runId = 1,
            runAttempt = 1,
            archiveSha256 = "1".repeat(64),
            archiveSize = 0,
            archiveParts = listOf(AssetDescriptor("part", 0, "2".repeat(64))),
            appliedChangelogChain = emptyList(),
            seforimToolCommit = "b".repeat(40),
            sourceLinksTreeSha256 = "3".repeat(64),
            packagedLinksTreeSha256 = "4".repeat(64),
            configSha256 = "5".repeat(64),
        )

        val chain = ManualLinksChangelog.verifiedChain(
            targetMetadataPath = metadata,
            targetMetadataSha256 = ManualLinksJson.rawSha256(metadata),
            target = ReleaseMetadata.read(metadata),
            base = base,
            changelogDir = dir,
        )

        assertEquals(1, chain.size)
        assertEquals(RenameEvent("Old", "New", "ישן", "חדש"), chain.single().renames.single())
    }

    @Test
    fun rejectsDuplicateMetadataForTheSameTagEvenWhenDigestMatches() {
        val dir = Files.createTempDirectory("duplicate-manual-links-chain")
        val changelog = dir.resolve("0001-r2-changelog_diff.json")
        Files.writeString(changelog, """{"old_tag":"r1","new_tag":"r2","books":{"en_renamed":[],"he_renamed":[]}}""")
        val metadata = dir.resolve("0001-r2-release_metadata.json")
        Files.writeString(
            metadata,
            metadataJson(
                tag = "r2",
                previousTag = "r1",
                changelogSize = Files.size(changelog),
                changelogSha = ManualLinksJson.rawSha256(changelog),
            ),
        )
        Files.copy(metadata, dir.resolve("0002-r2-release_metadata.json"))
        val base = ManualLinksLineage(
            sefariaTag = "r1",
            releaseMetadataSha256 = "a".repeat(64),
            runId = 1,
            runAttempt = 1,
            archiveSha256 = "1".repeat(64),
            archiveSize = 0,
            archiveParts = listOf(AssetDescriptor("part", 0, "2".repeat(64))),
            appliedChangelogChain = emptyList(),
            seforimToolCommit = "b".repeat(40),
            sourceLinksTreeSha256 = "3".repeat(64),
            packagedLinksTreeSha256 = "4".repeat(64),
            configSha256 = "5".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            ManualLinksChangelog.verifiedChain(
                targetMetadataPath = metadata,
                targetMetadataSha256 = ManualLinksJson.rawSha256(metadata),
                target = ReleaseMetadata.read(metadata),
                base = base,
                changelogDir = dir,
            )
        }
    }

    @Test
    fun rejectsRenameCycleAndMergeAcrossDifferentChangelogNodes() {
        assertFailsWith<IllegalArgumentException> {
            ManualLinksChangelog.validateRenameGraph(
                listOf(
                    RenameEvent("A", "B", "א", "ב"),
                    RenameEvent("B", "A", "ב", "א"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ManualLinksChangelog.validateRenameGraph(
                listOf(
                    RenameEvent("A", "C", "א", "ג"),
                    RenameEvent("B", "C", "ב", "ג"),
                ),
            )
        }
    }

    private fun metadataJson(
        tag: String,
        previousTag: String?,
        previousDigest: String = "a".repeat(64),
        changelogSize: Long = 0,
        changelogSha: String = "5".repeat(64),
        changelogOldTag: String = previousTag ?: "",
    ) = """
        {
          "schema_version":1,
          "tag":"$tag",
          "run_id":2,
          "run_attempt":1,
          "source_commit":"${"c".repeat(40)}",
          "previous":${previousTag?.let { "{\"tag\":\"$it\",\"metadata_sha256\":\"$previousDigest\"}" } ?: "null"},
          "archive":{"sha256":"${"1".repeat(64)}","size":0,"parts":[{"name":"part","size":0,"sha256":"${"2".repeat(64)}"}]},
          "manifest":{"name":"manifest","size":0,"sha256":"${"3".repeat(64)}"},
          "titles":{"name":"titles","size":0,"sha256":"${"4".repeat(64)}"},
          "changelog":{"name":"changelog_diff.json","size":$changelogSize,"sha256":"$changelogSha","old_tag":"$changelogOldTag","new_tag":"$tag"}
        }
    """.trimIndent()
}
