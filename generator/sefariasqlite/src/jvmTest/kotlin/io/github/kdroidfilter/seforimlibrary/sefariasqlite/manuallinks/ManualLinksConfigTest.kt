package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ManualLinksConfigTest {
    @Test
    fun exclusionReasonIsRequiredAndPathsAreUnique() {
        val validExclude = """
            {"path":"root/links/book_links.json","reason":"both-sides-sefaria; owned by SefariaLinksImporter","raw_file_sha256":"${"1".repeat(64)}","expected_record_count":1,"source_sefaria_primary_en_title":"Book","source_sefaria_primary_he_title":"ספר","external_primary_target_count":0,"legacy_self_target":{"count":1,"basename":"ספר","required_heRef_prefix":"ספר, "}}
        """.trimIndent()
        val cases = listOf(
            validExclude.replace(",\"reason\":\"both-sides-sefaria; owned by SefariaLinksImporter\"", ""),
            validExclude.replace("both-sides-sefaria; owned by SefariaLinksImporter", "ignored"),
            "$validExclude,$validExclude",
        )

        cases.forEachIndexed { index, excludes ->
            val path = Files.createTempFile("invalid-excludes-$index", ".json")
            Files.writeString(
                path,
                """{"schema_version":1,"seforim_tool_ref":"refs/heads/test","links_roots":[{"path":"root/links","expected_state":"present"}],"bootstrap_adapters":{},"excluded_files":[$excludes],"bootstrap_file_renames":[],"bootstrap_record_overrides":[]}""",
            )
            assertFailsWith<Exception>("invalid exclusion case $index must fail") {
                ManualLinksConfig.read(path)
            }
        }
    }
}
