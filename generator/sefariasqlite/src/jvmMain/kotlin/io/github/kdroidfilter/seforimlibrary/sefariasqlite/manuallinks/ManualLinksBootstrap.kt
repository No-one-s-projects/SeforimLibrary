package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

internal object ManualLinksBootstrap {
    fun nationalLibraryHeRef(heRef2: String, expectedTargetTitle: String): String {
        val parts = heRef2.split(", ")
        require(parts.size == 4 && parts[2].startsWith("פרק ") && parts[2].length > 4 && parts[3].isNotEmpty()) {
            "Malformed National Library heRef_2"
        }
        return "$expectedTargetTitle ${parts[2].removePrefix("פרק ")}, ${parts[3]}"
    }

    fun moreBooksHeRef(heRef2: String): String = heRef2.trimEnd { it == ',' || it == ' ' }
}
