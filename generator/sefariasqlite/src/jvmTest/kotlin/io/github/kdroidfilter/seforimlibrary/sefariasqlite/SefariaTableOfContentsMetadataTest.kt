package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SefariaTableOfContentsMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(content: String): ParsedTableOfContents {
        val root = createTempDirectory("toc-metadata-test")
        return try {
            Files.writeString(root.resolve("table_of_contents.json"), content)
            parseTableOfContentsMetadata(root, json, Logger.withTag("test"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `walker captures roots unordered descendants and ordering independently`() {
        val result = parse(
            """
            [
              {
                "category":"Tanakh", "heCategory":"תנ\"ך", "order":1,
                "heShortDesc":" קצר ", "heDesc":" ארוך ",
                "contents":[
                  {
                    "category":"Translations", "heCategory":"תרגומים", "heDesc":" תרגומים ",
                    "contents":[
                      {"category":"Onkelos", "heCategory":"אונקלוס", "heShortDesc":"אונקלוס"}
                    ]
                  },
                  {"category":"Torah", "heCategory":"תורה", "order":2, "heShortDesc":"סדר"}
                ]
              }
            ]
            """.trimIndent(),
        )

        assertEquals(CategoryDescriptions("קצר", "ארוך"), result.categoryDescriptions["תנ״ך"])
        assertEquals("תרגומים", result.categoryDescriptions["תנ״ך/תרגומים"]?.heDesc)
        assertEquals("אונקלוס", result.categoryDescriptions["תנ״ך/תרגומים/אונקלוס"]?.heShortDesc)
        assertEquals(1, result.categoryOrders["תנ״ך"])
        assertEquals(2, result.categoryOrders["תנ״ך/תורה"])
        assertNull(result.categoryOrders["תנ״ך/תרגומים"])
        assertEquals("תנ״ך", sanitizeFolder(" תנ\"ך "))
    }

    @Test
    fun `talmud child descriptions use flattened final paths`() {
        val result = parse(
            """
            [{
              "category":"Talmud", "heCategory":"תלמוד", "heShortDesc":"קצר אב",
              "contents":[
                {"category":"Bavli", "heCategory":"בבלי", "heDesc":"בבלי ארוך"},
                {"category":"Yerushalmi", "heCategory":"ירושלמי", "heDesc":"ירושלמי ארוך"}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals("קצר אב", result.categoryDescriptions["תלמוד"]?.heShortDesc)
        assertEquals("בבלי ארוך", result.categoryDescriptions["תלמוד בבלי"]?.heDesc)
        assertEquals("ירושלמי ארוך", result.categoryDescriptions["תלמוד ירושלמי"]?.heDesc)
    }

    @Test
    fun `blank descriptions disappear and equal duplicates collapse`() {
        val result = parse(
            """
            [
              {"category":"A", "heCategory":"א", "heShortDesc":"same", "heDesc":"   "},
              {"category":"A", "heCategory":"א", "heShortDesc":"same"},
              {"category":"B", "heCategory":"ב", "heShortDesc":"   ", "heDesc":"\n"}
            ]
            """.trimIndent(),
        )
        assertEquals(CategoryDescriptions("same", null), result.categoryDescriptions["א"])
        assertTrue("ב" !in result.categoryDescriptions)
    }

    @Test
    fun `book descriptions are ignored while category description without Hebrew name fails`() {
        val result = parse(
            """
            [{
              "category":"Root", "heCategory":"שורש",
              "contents":[{"title":"Genesis", "heTitle":"בראשית", "heShortDesc":"תיאור ספר"}]
            }]
            """.trimIndent(),
        )
        assertTrue(result.categoryDescriptions.isEmpty())

        assertFailsWith<IllegalArgumentException> {
            parse("""[{"category":"English only", "heShortDesc":"תיאור קטגוריה"}]""")
        }
        assertFailsWith<IllegalArgumentException> {
            parse(
                """
                [{
                  "category":"English only",
                  "contents":[{"category":"Child", "heCategory":"ילד", "heDesc":"תיאור"}]
                }]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `conflicting duplicate descriptions fail`() {
        assertFailsWith<IllegalStateException> {
            parse(
                """
                [
                  {"category":"A", "heCategory":"א", "heShortDesc":"one"},
                  {"category":"A", "heCategory":"א", "heShortDesc":"two"}
                ]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `missing malformed and non-array TOC fail`() {
        val missingRoot: Path = createTempDirectory("toc-missing-test")
        try {
            assertFailsWith<IllegalArgumentException> {
                parseTableOfContentsMetadata(missingRoot, json, Logger.withTag("test"))
            }
        } finally {
            missingRoot.toFile().deleteRecursively()
        }
        assertFailsWith<IllegalArgumentException> { parse("not json") }
        assertFailsWith<IllegalArgumentException> { parse("{}") }
    }
}
