package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.core.models.Book
import io.github.kdroidfilter.seforimlibrary.core.models.Category
import io.github.kdroidfilter.seforimlibrary.core.models.Line
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end coverage of the blank-row daf gate (in-text citation links wrongly
 * promoted to COMMENTARY — the "Rashi on Niddah 4b shown under Niddah 11a" bug).
 *
 * Two dependants of the same base exercise both sides of the alignment pre-scan:
 *  - "Rashi on Niddah": typed rows are same-daf → pair is daf-aligned → its blank
 *    cross-daf citation row must store as REFERENCE.
 *  - "Rif Niddah" (own pagination): typed rows are never same-daf → pair exempt →
 *    its blank cross-daf row must keep the schema promotion (COMMENTARY).
 */
class SefariaDafAlignmentGateTest {
    @Test
    fun alignedPairDemotesCrossDafCitation_rifPaginatedPairIsProtected() = runBlocking {
        val tempDir = Files.createTempDirectory("seforim-daf-gate")
        val linksDir = Files.createDirectories(tempDir.resolve("links"))

        Files.writeString(
            linksDir.resolve("links0.csv"),
            """
            |Citation 1,Citation 2,Conection Type
            |"Niddah 4b:11","Rashi on Niddah 4b:11:1","commentary"
            |"Niddah 11a:7","Rashi on Niddah 11a:7:1","commentary"
            |"Niddah 11a:7","Rashi on Niddah 4b:11:1",""
            |"Niddah 4b:11","Rif Niddah 2a:1","commentary"
            |"Niddah 11a:7","Rif Niddah 3a:1","commentary"
            |"Niddah 13a:2","Rif Niddah 2a:1",""
            """.trimMargin()
        )

        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)

        val sourceId = repo.insertSource("Sefaria-Test")
        val catId = repo.insertCategory(Category(0, null, "תלמוד", level = 0, order = 1))

        fun book(id: Long, title: String, isBase: Boolean) = Book(
            id = id, categoryId = catId, sourceId = sourceId, title = title, heRef = title,
            authors = emptyList(), pubPlaces = emptyList(), pubDates = emptyList(),
            heShortDesc = null, notesContent = null, order = id.toFloat(), topics = emptyList(),
            isBaseBook = isBase, totalLines = 5,
            hasAltStructures = false, hasTeamim = false, hasNekudot = false,
        )
        repo.insertBook(book(1, "נדה", isBase = true))
        repo.insertBook(book(2, "רש\"י על נדה", isBase = false))
        repo.insertBook(book(3, "רי\"ף נדה", isBase = false))

        // Lines: Niddah 4b:11 / 11a:7 / 13a:2; Rashi 4b:11:1 / 11a:7:1; Rif 2a:1 / 3a:1.
        repo.insertLinesBatch(
            listOf(
                Line(id = 1, bookId = 1, lineIndex = 0, content = "n-4b-11", heRef = "נדה ד:, יא"),
                Line(id = 2, bookId = 1, lineIndex = 1, content = "n-11a-7", heRef = "נדה יא., ז"),
                Line(id = 3, bookId = 1, lineIndex = 2, content = "n-13a-2", heRef = "נדה יג., ב"),
                Line(id = 10, bookId = 2, lineIndex = 0, content = "rashi-4b", heRef = "רש\"י על נדה ד:, יא, א"),
                Line(id = 11, bookId = 2, lineIndex = 1, content = "rashi-11a", heRef = "רש\"י על נדה יא., ז, א"),
                Line(id = 20, bookId = 3, lineIndex = 0, content = "rif-2a", heRef = "רי\"ף נדה ב., א"),
                Line(id = 21, bookId = 3, lineIndex = 1, content = "rif-3a", heRef = "רי\"ף נדה ג., א"),
            )
        )

        val lineKeyToId = mapOf(
            "Niddah" to 0 to 1L, "Niddah" to 1 to 2L, "Niddah" to 2 to 3L,
            "Rashi on Niddah" to 0 to 10L, "Rashi on Niddah" to 1 to 11L,
            "Rif Niddah" to 0 to 20L, "Rif Niddah" to 1 to 21L,
        )
        val lineIdToBookId = mapOf(1L to 1L, 2L to 1L, 3L to 1L, 10L to 2L, 11L to 2L, 20L to 3L, 21L to 3L)
        val bookMeta = mapOf(
            1L to BookMeta(isBaseBook = true, categoryLevel = 0, priorityRank = 0),
            2L to BookMeta(
                isBaseBook = false, categoryLevel = 1, priorityRank = null,
                dependence = Dependence.COMMENTARY, baseTextBookIds = setOf(1L),
            ),
            3L to BookMeta(
                isBaseBook = false, categoryLevel = 1, priorityRank = null,
                dependence = Dependence.COMMENTARY, baseTextBookIds = setOf(1L),
            ),
        )

        fun ref(en: String, he: String, path: String, lineIndex: Int) =
            RefEntry(en, he, path, lineIndex)
        val refsByCanonical = mapOf(
            canonicalCitation("Niddah 4b:11") to listOf(ref("Niddah 4b:11", "נדה ד:, יא", "Niddah", 1)),
            canonicalCitation("Niddah 11a:7") to listOf(ref("Niddah 11a:7", "נדה יא., ז", "Niddah", 2)),
            canonicalCitation("Niddah 13a:2") to listOf(ref("Niddah 13a:2", "נדה יג., ב", "Niddah", 3)),
            canonicalCitation("Rashi on Niddah 4b:11:1") to listOf(
                ref("Rashi on Niddah 4b:11:1", "רש\"י על נדה ד:, יא, א", "Rashi on Niddah", 1)
            ),
            canonicalCitation("Rashi on Niddah 11a:7:1") to listOf(
                ref("Rashi on Niddah 11a:7:1", "רש\"י על נדה יא., ז, א", "Rashi on Niddah", 2)
            ),
            canonicalCitation("Rif Niddah 2a:1") to listOf(ref("Rif Niddah 2a:1", "רי\"ף נדה ב., א", "Rif Niddah", 1)),
            canonicalCitation("Rif Niddah 3a:1") to listOf(ref("Rif Niddah 3a:1", "רי\"ף נדה ג., א", "Rif Niddah", 2)),
        )
        val refsByBase = refsByCanonical.values.flatten().associateBy { canonicalBase(it.ref) }

        val bindings = io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings(
            io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator.load(path = null),
            repo,
        )
        val importer = SefariaLinksImporter(repo, bindings, Logger.withTag("SefariaDafAlignmentGateTest"))
        importer.processLinksInParallel(
            linksDir = linksDir,
            refsByCanonical = refsByCanonical,
            refsByBase = refsByBase,
            lineKeyToId = lineKeyToId,
            lineIdToBookId = lineIdToBookId,
            bookMetaById = bookMeta,
        )

        fun count(sql: String): Long {
            val conn: Connection = driver.getConnection()
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    return if (rs.next()) rs.getLong(1) else -1L
                }
            }
        }
        fun typeCount(bookId: Long, type: String) = count(
            "SELECT COUNT(*) FROM link l JOIN connection_type ct ON ct.id=l.connectionTypeId " +
                "WHERE l.targetBookId = $bookId AND ct.name = '$type'"
        )

        // Rashi (aligned pair): 2 typed + 1 same-daf-blank... the blank cross-daf row
        // must be REFERENCE, so exactly 2 COMMENTARY rows (typed) survive.
        assertEquals(2L, typeCount(2L, "COMMENTARY"), "Rashi: only the typed home rows stay COMMENTARY")
        assertEquals(1L, typeCount(2L, "REFERENCE"), "Rashi: the blank cross-daf citation row must demote")

        // Rif (exempt pair): typed rows are never same-daf; the blank cross-daf row
        // keeps the schema promotion → 3 COMMENTARY rows, 0 REFERENCE.
        assertEquals(3L, typeCount(3L, "COMMENTARY"), "Rif: blank row must keep the COMMENTARY promotion")
        assertEquals(0L, typeCount(3L, "REFERENCE"), "Rif: nothing may demote for a non-aligned pair")

        repo.close()
    }
}
