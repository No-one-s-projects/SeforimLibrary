package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import java.io.File

internal const val LINKER_SIDECAR_HEADER = "#linker-sidecar-v2"

internal data class LinkerSidecarEntry(
    val ref: String,
    val heRef: String,
    val path: String,
    val lineIndex: Int,
    val lineId: Long,
    val sourceName: String,
    val canonicalHeTitle: String,
)

internal fun writeLinkerSidecar(
    path: String,
    refs: List<RefEntry>,
    lineKeyToId: Map<Pair<String, Int>, Long>,
    canonicalTitleByBookPath: Map<String, String>,
    sourceName: String,
) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine(LINKER_SIDECAR_HEADER)
        for (entry in refs) {
            val lineId = lineKeyToId[entry.path to (entry.lineIndex - 1)] ?: continue
            val canonicalTitle = checkNotNull(canonicalTitleByBookPath[entry.path]) {
                "No stable book identity for linker sidecar path ${entry.path}"
            }
            val fields = listOf(
                entry.ref,
                entry.heRef,
                entry.path,
                entry.lineIndex.toString(),
                lineId.toString(),
                sourceName,
                canonicalTitle,
            )
            check(fields.none { '\t' in it || '\n' in it || '\r' in it }) {
                "Linker sidecar field contains a TSV delimiter for ${entry.ref}"
            }
            writer.appendLine(fields.joinToString("\t"))
        }
    }
}

internal fun readLinkerSidecar(path: String): List<LinkerSidecarEntry> {
    File(path).bufferedReader(Charsets.UTF_8).use { reader ->
        check(reader.readLine() == LINKER_SIDECAR_HEADER) {
            "Unsupported or missing linker sidecar header; rebuild Phase-1 with the current generator"
        }
        return reader.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, line ->
            val fields = line.split('\t')
            check(fields.size == 7) { "Malformed linker sidecar row ${index + 2}: expected 7 fields" }
            LinkerSidecarEntry(
                ref = fields[0],
                heRef = fields[1],
                path = fields[2],
                lineIndex = fields[3].toInt(),
                lineId = fields[4].toLong(),
                sourceName = fields[5],
                canonicalHeTitle = fields[6],
            ).also {
                check(it.ref.isNotBlank() && it.path.isNotBlank() && it.lineIndex > 0 && it.lineId > 0) {
                    "Invalid linker sidecar identity at row ${index + 2}"
                }
                check(it.sourceName.isNotBlank() && it.canonicalHeTitle.isNotBlank()) {
                    "Missing stable linker sidecar book identity at row ${index + 2}"
                }
            }
        }.toList()
    }
}
