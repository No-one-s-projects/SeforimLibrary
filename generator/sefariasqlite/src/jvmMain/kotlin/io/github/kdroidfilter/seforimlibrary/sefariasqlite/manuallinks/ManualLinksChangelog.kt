package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal data class RenameEvent(
    val oldEn: String?,
    val newEn: String?,
    val oldHe: String?,
    val newHe: String?,
)

internal data class VerifiedChangelog(
    val metadata: ReleaseMetadata,
    val metadataSha256: String,
    val changelogPath: Path,
    val renames: List<RenameEvent>,
)

internal object ManualLinksChangelog {
    fun verifiedChain(
        targetMetadataPath: Path,
        targetMetadataSha256: String,
        target: ReleaseMetadata,
        base: ManualLinksLineage?,
        changelogDir: Path?,
    ): List<VerifiedChangelog> {
        if (base == null) return emptyList()
        if (target.tag == base.sefariaTag) {
            require(targetMetadataSha256 == base.releaseMetadataSha256) { "Same Sefaria tag has different metadata digest" }
            return emptyList()
        }
        val metadataFiles = buildList {
            add(targetMetadataPath)
            if (changelogDir != null && Files.isDirectory(changelogDir)) {
                Files.walk(changelogDir).use { stream ->
                    stream.filter {
                        Files.isRegularFile(it) &&
                            (it.name == "release_metadata.json" || it.name.endsWith("-release_metadata.json"))
                    }.forEach(::add)
                }
            }
        }.distinct()
        val targetPath = targetMetadataPath.toAbsolutePath().normalize()
        val entries = metadataFiles.map { path ->
            val metadata = ReleaseMetadata.read(path)
            Triple(path, metadata.tag, ManualLinksJson.rawSha256(path))
        }
        // The verified chain handoff keeps the authoritative target metadata at
        // its root and one ordinal mirror next to the target changelog. Treat
        // that single cross-directory mirror as transport, not a second node.
        val targetMirrors = entries.filter { (path, tag, digest) ->
            path.toAbsolutePath().normalize() != targetPath && tag == target.tag && digest == targetMetadataSha256
        }
        val targetInsideChainDir = isUnderDirectory(targetPath, changelogDir)
        require(targetMirrors.size <= if (targetInsideChainDir) 0 else 1) {
            "Duplicate target release metadata tag/digest pair"
        }
        val groupedMetadata = entries
            .filterNot { it in targetMirrors }
            .groupBy({ it.second to it.third }, { it.first })
        require(groupedMetadata.values.all { it.size == 1 }) { "Duplicate release metadata tag/digest pair" }
        val digestsByTag = groupedMetadata.keys.groupBy({ it.first }, { it.second })
        require(digestsByTag.values.all { it.distinct().size == 1 }) { "Conflicting release metadata digests for one tag" }

        val reversed = ArrayList<VerifiedChangelog>()
        val visited = HashSet<String>()
        var metadata = target
        var metadataPath = targetMetadataPath
        var metadataDigest = targetMetadataSha256
        while (metadata.tag != base.sefariaTag) {
            require(visited.add(metadata.tag)) { "Cycle in release metadata chain at ${metadata.tag}" }
            val previous = metadata.previous ?: error("Release chain reached null before ${base.sefariaTag}")
            val changelog = locateHashedAsset(metadataPath, changelogDir, metadata.tag, metadata.changelog)
            val renames = readAndValidate(changelog, previous.tag, metadata.tag)
            reversed += VerifiedChangelog(metadata, metadataDigest, changelog, renames)
            if (previous.tag == base.sefariaTag) {
                require(previous.metadataSha256 == base.releaseMetadataSha256) {
                    "Release chain reached baseline tag with the wrong digest"
                }
                break
            }
            val previousPath = groupedMetadata[previous.tag to previous.metadataSha256]?.firstOrNull()
                ?: error("Missing release metadata ${previous.tag} with digest ${previous.metadataSha256}")
            metadataPath = previousPath
            metadataDigest = previous.metadataSha256
            metadata = ReleaseMetadata.read(previousPath)
        }
        return reversed.asReversed().also { chain ->
            validateRenameGraph(chain.flatMap { it.renames })
        }
    }

    private fun isUnderDirectory(path: Path, directory: Path?): Boolean =
        directory != null && path.startsWith(directory.toAbsolutePath().normalize())

    private fun locateHashedAsset(
        metadataPath: Path,
        changelogDir: Path?,
        tag: String,
        asset: AssetDescriptor,
    ): Path {
        val candidates = LinkedHashSet<Path>()
        metadataPath.parent?.resolve(asset.name)?.let(candidates::add)
        val metadataPrefix = metadataPath.name.removeSuffix("release_metadata.json")
        if (metadataPrefix.isNotEmpty()) metadataPath.parent?.resolve(metadataPrefix + asset.name)?.let(candidates::add)
        changelogDir?.resolve(tag)?.resolve(asset.name)?.let(candidates::add)
        if (changelogDir != null && Files.isDirectory(changelogDir)) {
            Files.walk(changelogDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && (it.name == asset.name || it.name.endsWith("-${asset.name}")) }
                    .forEach(candidates::add)
            }
        }
        val inspected = candidates.filter(Files::isRegularFile).associateWith { path ->
            Files.size(path) to ManualLinksJson.rawSha256(path)
        }
        val matching = inspected.filterValues { (size, digest) -> size == asset.size && digest == asset.sha256 }.keys.distinct()
        require(matching.size == 1) {
            "Expected exactly one verified ${asset.name} for $tag; found ${matching.size}; " +
                "regular candidates=${inspected.map { (path, identity) -> "${path.fileName}:${identity.first}:${identity.second}" }}"
        }
        return matching.single()
    }

    private fun readAndValidate(path: Path, expectedOld: String, expectedNew: String): List<RenameEvent> {
        val root = ManualLinksJson.readStrict(path).requireObject("changelog")
        require(root.requiredText("old_tag") == expectedOld) { "changelog old_tag mismatch: $path" }
        require(root.requiredText("new_tag") == expectedNew) { "changelog new_tag mismatch: $path" }
        val books = root.requiredObject("books")
        val events = ArrayList<RenameEvent>()
        books.requiredArray("en_renamed").forEachIndexed { index, node ->
            val item = node.requireObject("en_renamed[$index]")
            events += RenameEvent(
                oldEn = item.requiredText("old_en"),
                newEn = item.requiredText("new_en"),
                oldHe = item.get("old_he")?.takeUnless { it.isNull }?.requireText("old_he"),
                newHe = item.get("new_he")?.takeUnless { it.isNull }?.requireText("new_he"),
            )
        }
        books.requiredArray("he_renamed").forEachIndexed { index, node ->
            val item = node.requireObject("he_renamed[$index]")
            events += RenameEvent(
                oldEn = null,
                newEn = item.requiredText("en"),
                oldHe = item.requiredText("old_he"),
                newHe = item.requiredText("new_he"),
            )
        }
        validateRenameGraph(events)
        return events
    }

    internal fun validateRenameGraph(events: List<RenameEvent>) {
        fun validate(pairs: List<Pair<String, String>>, label: String) {
            val forward = LinkedHashMap<String, String>()
            val reverse = LinkedHashMap<String, String>()
            pairs.filter { it.first != it.second }.forEach { (old, new) ->
                require(forward.putIfAbsent(old, new) in setOf(null, new)) { "$label rename conflict for $old" }
                require(reverse.putIfAbsent(new, old) in setOf(null, old)) { "$label rename merge into $new" }
            }
            forward.keys.forEach { start ->
                val visited = HashSet<String>()
                var current: String? = start
                while (current != null) {
                    require(visited.add(current)) { "$label rename cycle at $current" }
                    current = forward[current]
                }
            }
        }
        validate(events.mapNotNull { event -> event.oldEn?.let { old -> event.newEn?.let { old to it } } }, "English")
        validate(events.mapNotNull { event -> event.oldHe?.let { old -> event.newHe?.let { old to it } } }, "Hebrew")
    }
}
