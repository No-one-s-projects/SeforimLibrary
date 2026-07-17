package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Several Sefaria books ship their section names only in English (the book's
 * `heSectionNames` is blank or absent), e.g. Sheiltot d'Rav Achai Gaon with
 * `sectionNames=["Sheilta","Paragraph"]`. Without a Hebrew mapping the TOC and
 * alt-TOC render "Sheilta א" mid-Hebrew. Each mapping below is verified against
 * the book's schema and source text.
 */
class SefariaEnglishSectionNamesTest {

    @Test
    fun mapsEnglishOnlySectionNamesToHebrew() {
        val cases = mapOf(
            "Sheilta" to "שאילתא",     // שאילתות דרב אחאי גאון, העמק שאלה
            "Maayan" to "מעיין",        // חסד לאברהם
            "Nahar" to "נהר",           // חסד לאברהם
            "Shoket" to "שוקת",         // חסד לאברהם
            "Shaar" to "שער",           // שערי קדושה
            "Treatise" to "שער",        // יסוד מורא ("אסדר שנים עשר שערים")
            "Shorash" to "שורש",        // ביאור על ספר המצוות לרס"ג
            "Manuscript" to "כתב יד",   // ליקוטי מוהר"ן (node "כתבי יד")
            "Epistle" to "אגרת",        // נועם אלימלך (אגרת הקודש)
            "Comment" to "פירוש",       // נועם אלימלך
            "'Comment'" to "פירוש",     // נועם אלימלך ships the name wrapped in apostrophes
            "Question" to "שאלה",       // אגרת רב שרירא גאון ("וששאלתם")
            "Principle" to "עיקר",      // ספר הבחור ("שלש עשרה עקרים")
            "Page" to "עמוד",           // גנזי מצרים, הלכות ספר תורה
            "Word" to "מילה",           // מחברת מנחם ("כל מלה כפי שאת")
            "Room" to "חדר",            // רב פנינים על משלי (Chamber)
            "Vav" to "וו",              // ספר יראים (ווים ועמודים)
            "Ot" to "אות",              // מדרש ילמדנו
            "Se'if" to "סעיף",          // בני יששכר — apostrophe variant of Seif
            "Seif" to "סעיף",
        )
        for ((en, he) in cases) {
            assertEquals(he, mapSectionNameToHebrew(en), "mapping for '$en'")
        }
    }

    @Test
    fun leavesUnknownNamesUnchangedAndHandlesBlank() {
        // Genuinely unknown names pass through verbatim.
        assertEquals("Xyzzy", mapSectionNameToHebrew("Xyzzy"))
        assertNull(mapSectionNameToHebrew(null))
        assertNull(mapSectionNameToHebrew("   "))
        // Short tokens are matched exactly, never as substrings, so unrelated
        // words that merely contain "ot"/"vav" are left untouched.
        assertEquals("Note", mapSectionNameToHebrew("Note"))
        assertEquals("Total", mapSectionNameToHebrew("Total"))
    }

    @Test
    fun sheiltaHeadingsRenderInHebrewEndToEnd() = runBlocking {
        // Full pipeline: schema with English-only sectionNames -> Hebrew headings.
        val tempDir = Files.createTempDirectory("seforim-sheilta")
        val schemaDir = Files.createDirectories(tempDir.resolve("schemas"))
        val jsonDir = Files.createDirectories(tempDir.resolve("json"))
        val bookDir = Files.createDirectories(jsonDir.resolve("Sheiltot dRav Achai Gaon"))

        Files.writeString(schemaDir.resolve("Sheiltot_dRav_Achai_Gaon.json"), schemaJson)
        Files.writeString(bookDir.resolve("merged.json"), mergedJson)

        val reader = SefariaBookPayloadReader(
            Json { ignoreUnknownKeys = true; coerceInputValues = true },
            Logger.withTag("SefariaEnglishSectionNamesTest")
        )
        val schemaLookup = reader.buildSchemaLookup(schemaDir)
        val payload = reader.readBooksInParallel(jsonDir, schemaDir, schemaLookup).single()

        assertTrue(payload.lines.any { it.contains("text of sheilta 1") })
        assertTrue(
            payload.headings.any { it.title == "שאילתא א" },
            "expected <h2>שאילתא א</h2> heading (got ${payload.headings.map { it.title }})"
        )
        assertTrue(
            payload.headings.none { it.title.contains("Sheilta") },
            "no heading should still contain English 'Sheilta' (got ${payload.headings.map { it.title }})"
        )
    }

    companion object {
        // Mirrors the real Sheiltot schema: sectionNames in English, heSectionNames absent.
        private val schemaJson = """
            {
              "title": "Sheiltot dRav Achai Gaon",
              "heTitle": "שאילתות דרב אחאי גאון",
              "schema": {
                "nodeType": "JaggedArrayNode",
                "depth": 2,
                "addressTypes": ["Integer", "Integer"],
                "sectionNames": ["Sheilta", "Paragraph"],
                "title": "Sheiltot dRav Achai Gaon",
                "heTitle": "שאילתות דרב אחאי גאון"
              }
            }
        """.trimIndent()

        private val mergedJson = """
            {
              "title": "Sheiltot dRav Achai Gaon",
              "heTitle": "שאילתות דרב אחאי גאון",
              "text": [
                ["text of sheilta 1 paragraph 1", "text of sheilta 1 paragraph 2"],
                ["text of sheilta 2 paragraph 1"]
              ]
            }
        """.trimIndent()
    }
}
