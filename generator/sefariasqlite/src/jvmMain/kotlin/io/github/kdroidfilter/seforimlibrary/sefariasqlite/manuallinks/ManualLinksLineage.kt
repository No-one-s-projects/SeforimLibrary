package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

internal data class AssetDescriptor(val name: String, val size: Long, val sha256: String)

internal data class PreviousRelease(val tag: String, val metadataSha256: String)

internal data class ReleaseMetadata(
    val tag: String,
    val runId: Long,
    val runAttempt: Long,
    val sourceCommit: String,
    val previous: PreviousRelease?,
    val archiveSha256: String,
    val archiveSize: Long,
    val archiveParts: List<AssetDescriptor>,
    val changelog: AssetDescriptor,
    val changelogOldTag: String?,
    val changelogNewTag: String,
) {
    companion object {
        fun read(path: Path): ReleaseMetadata {
            val root = ManualLinksJson.readStrict(path).requireObject("release metadata")
            require(root.requiredInt("schema_version") == 1) { "Unsupported release metadata schema" }
            val tag = root.requiredText("tag")
            val previousNode = root.get("previous")
            val previous = when {
                previousNode == null || previousNode.isNull -> null
                else -> previousNode.requireObject("previous").let {
                    PreviousRelease(it.requiredText("tag"), it.requiredSha256("metadata_sha256"))
                }
            }
            val archive = root.requiredObject("archive")
            val parts = archive.requiredArray("parts").mapIndexed { index, node ->
                node.requireObject("archive.parts[$index]").toAsset()
            }
            require(parts.isNotEmpty()) { "archive.parts must be non-empty" }
            require(parts.map { it.name }.distinct().size == parts.size) { "archive part names must be unique" }
            require(parts.map { it.name } == parts.map { it.name }.sorted()) { "archive parts must be lexicographically sorted" }
            val archiveSize = archive.requiredLong("size")
            require(parts.sumOf { it.size } == archiveSize) { "archive part sizes do not sum to archive.size" }
            root.requiredObject("manifest").toAsset()
            root.requiredObject("titles").toAsset()
            val changelogNode = root.requiredObject("changelog")
            val changelog = changelogNode.toAsset()
            val changelogNew = changelogNode.requiredText("new_tag")
            require(changelogNew == tag) { "changelog.new_tag does not match metadata tag" }
            val oldRaw = changelogNode.get("old_tag") ?: error("Missing changelog.old_tag")
            require(oldRaw.isTextual) { "changelog.old_tag must be a string" }
            val old = oldRaw.textValue()
            if (previous == null) {
                require(old.isEmpty()) { "Baseline changelog.old_tag must be empty when previous is null" }
            } else {
                require(old.isNotEmpty() && old == previous.tag) {
                    "changelog.old_tag must be the exact non-empty previous tag"
                }
            }
            val runId = root.requiredLong("run_id").also { require(it > 0) { "run_id must be positive" } }
            val runAttempt = root.requiredLong("run_attempt").also { require(it > 0) { "run_attempt must be positive" } }
            return ReleaseMetadata(
                tag = tag,
                runId = runId,
                runAttempt = runAttempt,
                sourceCommit = root.requiredCommit("source_commit"),
                previous = previous,
                archiveSha256 = archive.requiredSha256("sha256"),
                archiveSize = archiveSize,
                archiveParts = parts,
                changelog = changelog,
                changelogOldTag = old.takeIf(String::isNotEmpty),
                changelogNewTag = changelogNew,
            )
        }
    }
}

internal data class AppliedChangelog(
    val tag: String,
    val metadataSha256: String,
    val previous: PreviousRelease?,
    val changelogName: String,
    val changelogSha256: String,
)

internal data class ManualLinksLineage(
    val sefariaTag: String,
    val releaseMetadataSha256: String,
    val runId: Long,
    val runAttempt: Long,
    val archiveSha256: String,
    val archiveSize: Long,
    val archiveParts: List<AssetDescriptor>,
    val appliedChangelogChain: List<AppliedChangelog>,
    val seforimToolCommit: String,
    val sourceLinksTreeSha256: String,
    val packagedLinksTreeSha256: String,
    val configSha256: String,
) {
    fun toJson(): ObjectNode = ManualLinksJson.mapper.createObjectNode().apply {
        put("schema_version", 1)
        set<ObjectNode>("sefaria", ManualLinksJson.mapper.createObjectNode().apply {
            put("tag", sefariaTag)
            put("release_metadata_sha256", releaseMetadataSha256)
            put("run_id", runId)
            put("run_attempt", runAttempt)
            set<ObjectNode>("archive", ManualLinksJson.mapper.createObjectNode().apply {
                put("sha256", archiveSha256)
                put("size", archiveSize)
                set<ArrayNode>("parts", ManualLinksJson.mapper.createArrayNode().apply {
                    archiveParts.forEach { add(it.toJson()) }
                })
            })
            set<ArrayNode>("applied_changelog_chain", ManualLinksJson.mapper.createArrayNode().apply {
                appliedChangelogChain.forEach { item ->
                    add(ManualLinksJson.mapper.createObjectNode().apply {
                        put("tag", item.tag)
                        put("metadata_sha256", item.metadataSha256)
                        if (item.previous == null) putNull("previous") else set<ObjectNode>(
                            "previous",
                            ManualLinksJson.mapper.createObjectNode().apply {
                                put("tag", item.previous.tag)
                                put("metadata_sha256", item.previous.metadataSha256)
                            },
                        )
                        put("changelog_name", item.changelogName)
                        put("changelog_sha256", item.changelogSha256)
                    })
                }
            })
        })
        put("seforim_tool_commit", seforimToolCommit)
        put("source_links_tree_sha256", sourceLinksTreeSha256)
        put("packaged_links_tree_sha256", packagedLinksTreeSha256)
        put("config_sha256", configSha256)
    }

    fun canonicalBytes(): ByteArray = ManualLinksJson.canonicalBytes(toJson())

    companion object {
        fun read(path: Path): ManualLinksLineage {
            val root = ManualLinksJson.readStrict(path).requireObject("lineage")
            require(root.requiredInt("schema_version") == 1) { "Unsupported lineage schema" }
            val sefaria = root.requiredObject("sefaria")
            val archive = sefaria.requiredObject("archive")
            val parts = archive.requiredArray("parts").map { it.requireObject("archive part").toAsset() }
            require(parts.isNotEmpty()) { "lineage archive.parts must be non-empty" }
            require(parts.map { it.name }.distinct().size == parts.size) { "lineage archive part names must be unique" }
            require(parts.map { it.name } == parts.map { it.name }.sorted()) { "lineage archive parts must be lexicographically sorted" }
            val archiveSize = archive.requiredLong("size")
            require(parts.sumOf { it.size } == archiveSize) { "lineage archive part sizes do not sum to archive.size" }
            val chain = sefaria.requiredArray("applied_changelog_chain").map { node ->
                val item = node.requireObject("applied changelog")
                val previousNode = item.get("previous")
                AppliedChangelog(
                    tag = item.requiredText("tag"),
                    metadataSha256 = item.requiredSha256("metadata_sha256"),
                    previous = if (previousNode == null || previousNode.isNull) null else previousNode.requireObject("previous").let {
                        PreviousRelease(it.requiredText("tag"), it.requiredSha256("metadata_sha256"))
                    },
                    changelogName = item.requiredText("changelog_name"),
                    changelogSha256 = item.requiredSha256("changelog_sha256"),
                )
            }
            return ManualLinksLineage(
                sefariaTag = sefaria.requiredText("tag"),
                releaseMetadataSha256 = sefaria.requiredSha256("release_metadata_sha256"),
                runId = sefaria.requiredLong("run_id").also { require(it > 0) { "lineage run_id must be positive" } },
                runAttempt = sefaria.requiredLong("run_attempt").also { require(it > 0) { "lineage run_attempt must be positive" } },
                archiveSha256 = archive.requiredSha256("sha256"),
                archiveSize = archiveSize,
                archiveParts = parts,
                appliedChangelogChain = chain,
                seforimToolCommit = root.requiredCommit("seforim_tool_commit"),
                sourceLinksTreeSha256 = root.requiredSha256("source_links_tree_sha256"),
                packagedLinksTreeSha256 = root.requiredSha256("packaged_links_tree_sha256"),
                configSha256 = root.requiredSha256("config_sha256"),
            )
        }
    }
}

internal fun writeCanonical(path: Path, node: ObjectNode) {
    Files.write(path, ManualLinksJson.canonicalBytes(node))
}

private fun AssetDescriptor.toJson(): ObjectNode = ManualLinksJson.mapper.createObjectNode().apply {
    put("name", name)
    put("sha256", sha256)
    put("size", size)
}

private fun ObjectNode.toAsset(): AssetDescriptor = AssetDescriptor(
    name = requiredText("name"),
    size = requiredLong("size"),
    sha256 = requiredSha256("sha256"),
)

internal fun ObjectNode.requiredLong(name: String): Long {
    val node = get(name) ?: error("Missing $name")
    require(node.isIntegralNumber && node.canConvertToLong()) { "$name must be an integer" }
    return node.longValue().also { require(it >= 0) { "$name must be non-negative" } }
}

internal fun ObjectNode.requiredCommit(name: String): String = requiredText(name).also {
    require(it.matches(Regex("[0-9a-f]{40}"))) { "$name must be a full lowercase git commit" }
}
