package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ManualLinksLineageTest {
    @Test
    fun rejectsNonPositiveRunsAndMalformedArchivePartLists() {
        val valid = ManualLinksLineage(
            sefariaTag = "r1",
            releaseMetadataSha256 = "1".repeat(64),
            runId = 1,
            runAttempt = 1,
            archiveSha256 = "2".repeat(64),
            archiveSize = 3,
            archiveParts = listOf(
                AssetDescriptor("part-00", 1, "3".repeat(64)),
                AssetDescriptor("part-01", 2, "4".repeat(64)),
            ),
            appliedChangelogChain = emptyList(),
            seforimToolCommit = "5".repeat(40),
            sourceLinksTreeSha256 = "6".repeat(64),
            packagedLinksTreeSha256 = "7".repeat(64),
            configSha256 = "8".repeat(64),
        ).toJson()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.requiredObject("sefaria").put("run_id", 0) },
            { it.requiredObject("sefaria").put("run_attempt", 0) },
            { it.archiveParts().removeAll() },
            { it.archiveParts().add(it.archiveParts().first().deepCopy<ObjectNode>()) },
            {
                val parts = it.archiveParts()
                val first = parts.remove(0)
                parts.add(first)
            },
            { it.requiredObject("sefaria").requiredObject("archive").put("size", 4) },
        )

        mutations.forEachIndexed { index, mutate ->
            val node = valid.deepCopy()
            mutate(node)
            val path = Files.createTempFile("invalid-lineage-$index", ".json")
            writeCanonical(path, node)
            assertFailsWith<IllegalArgumentException>("mutation $index must fail") {
                ManualLinksLineage.read(path)
            }
        }
    }

    private fun ObjectNode.archiveParts(): ArrayNode =
        requiredObject("sefaria").requiredObject("archive").get("parts") as? ArrayNode
            ?: error("parts must be an array")
}
