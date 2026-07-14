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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exact per-type counter semantics (see [LinkImportTypeMetrics]):
 * rowsRead counts every data row incl. an empty-citation row; dropped counts
 * rows that produced no link; resolvedPairs counts pairs whose both sides
 * resolved to line ids; written counts ACTUAL insertions — a duplicate link
 * (same allocator id → INSERT OR IGNORE) sent twice is written once.
 */
class SefariaLinkImportMetricsTest {
    @Test
    fun countersMeasureReadsDropsResolutionsAndActualWrites() = runBlocking {
        val tempDir = Files.createTempDirectory("seforim-link-import-metrics")
        val linksDir = Files.createDirectories(tempDir.resolve("links"))

        // Row 1: resolves and is written.
        // Row 2: exact duplicate of row 1 — same link id, OR IGNOREd (written stays 1).
        // Row 3: empty Citation 1 — counted in rowsRead AND dropped.
        // Row 4: unresolvable Citation 1 — counted in rowsRead AND dropped.
        Files.writeString(
            linksDir.resolve("links0.csv"),
            """
            |Citation 1,Citation 2,Conection Type
            |"Rashi on Genesis 1:1:1","Genesis 1:1","Commentary"
            |"Rashi on Genesis 1:1:1","Genesis 1:1","Commentary"
            |"","Genesis 1:1","Commentary"
            |"Rashi on Genesis 9:9:9","Genesis 1:1","Commentary"
            """.trimMargin()
        )

        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)

        val sourceId = repo.insertSource("Sefaria-Test")
        val catId = repo.insertCategory(Category(0, null, "תורה", level = 0, order = 1))
        fun book(id: Long, title: String, isBase: Boolean, lines: Int) = Book(
            id = id, categoryId = catId, sourceId = sourceId, title = title, heRef = title,
            authors = emptyList(), pubPlaces = emptyList(), pubDates = emptyList(),
            heShortDesc = null, notesContent = null, order = id.toFloat(), topics = emptyList(),
            isBaseBook = isBase, totalLines = lines, hasAltStructures = false,
            hasTeamim = false, hasNekudot = false,
        )
        repo.insertBook(book(1, "בראשית", isBase = true, lines = 1))
        repo.insertBook(book(2, "רש\"י על בראשית", isBase = false, lines = 1))
        repo.insertLinesBatch(
            listOf(
                Line(id = 1, bookId = 1, lineIndex = 0, content = "gen 1:1", heRef = "בראשית א, א"),
                Line(id = 10, bookId = 2, lineIndex = 0, content = "rashi 1:1:1", heRef = "רש\"י א, א, א"),
            )
        )

        val allRefs = listOf(
            RefEntry("Genesis 1:1", "בראשית א, א", "Genesis", 1),
            RefEntry("Rashi on Genesis 1:1:1", "", "Rashi on Genesis", 1),
        )
        val refsByCanonical = allRefs.groupBy { canonicalCitation(it.ref) }
        val refsByBase = allRefs.associateBy { canonicalBase(it.ref) }
        val lineKeyToId = mapOf("Genesis" to 0 to 1L, "Rashi on Genesis" to 0 to 10L)
        val lineIdToBookId = mapOf(1L to 1L, 10L to 2L)
        val bookMeta = mapOf(
            1L to BookMeta(isBaseBook = true, categoryLevel = 0, priorityRank = 0),
            2L to BookMeta(
                isBaseBook = false, categoryLevel = 1, priorityRank = null,
                dependence = Dependence.COMMENTARY, baseTextBookIds = setOf(1L),
            ),
        )

        val bindings = io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings(
            io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator.load(path = null),
            repo,
        )
        val importer = SefariaLinksImporter(repo, bindings, Logger.withTag("SefariaLinkImportMetricsTest"))
        importer.processLinksInParallel(
            linksDir = linksDir,
            refsByCanonical = refsByCanonical,
            refsByBase = refsByBase,
            lineKeyToId = lineKeyToId,
            lineIdToBookId = lineIdToBookId,
            bookMetaById = bookMeta,
        )

        val metrics = importer.metricsSnapshot()
        assertEquals(setOf("COMMENTARY"), metrics.insertedByType.keys)
        val t = metrics.insertedByType.getValue("COMMENTARY")
        assertEquals(4, t.rowsRead, "rowsRead must count every data row, incl. the empty-citation row")
        assertEquals(2, t.dropped, "empty-citation row + unresolvable row are dropped")
        assertEquals(2, t.resolvedPairs, "both copies of the duplicate row resolve to a pair")
        assertEquals(1, t.written, "the duplicate send is OR IGNOREd — only one actual insertion")

        assertEquals(1L, repo.countLinks(), "exactly one link row must exist in the DB")
    }

    /**
     * End-to-end demotion: a COMMENTARY link whose base (source) is anchored on
     * תנ״ך and dependant (target) on תלמוד is cross-corpus, so
     * demoteCrossCorpusDependantLinks() retypes it to RELATED. Asserts the
     * insert-time snapshot still reports COMMENTARY, the persisted DB split
     * reports RELATED, and the retype-preserves-total invariant holds.
     */
    @Test
    fun crossCorpusCommentaryDemotesToRelatedAndPreservesTotal() = runBlocking {
        val tempDir = Files.createTempDirectory("seforim-link-import-demote")
        val linksDir = Files.createDirectories(tempDir.resolve("links"))
        Files.writeString(
            linksDir.resolve("links0.csv"),
            """
            |Citation 1,Citation 2,Conection Type
            |"Rashi on Genesis 1:1:1","Genesis 1:1","Commentary"
            """.trimMargin()
        )

        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)

        // First source → id 1, which demoteCrossCorpusDependantLinks() requires.
        val sourceId = repo.insertSource("Sefaria")
        assertEquals(1L, sourceId, "demotion query is scoped to sourceId=1")
        // Distinct top-level corpora so the link is genuinely cross-corpus.
        val tanakhId = repo.insertCategory(Category(0, null, "תנ״ך", level = 0, order = 1))
        val talmudId = repo.insertCategory(Category(0, null, "תלמוד בבלי", level = 0, order = 2))
        fun book(id: Long, title: String, isBase: Boolean, categoryId: Long) = Book(
            id = id, categoryId = categoryId, sourceId = sourceId, title = title, heRef = title,
            authors = emptyList(), pubPlaces = emptyList(), pubDates = emptyList(),
            heShortDesc = null, notesContent = null, order = id.toFloat(), topics = emptyList(),
            isBaseBook = isBase, totalLines = 1, hasAltStructures = false,
            hasTeamim = false, hasNekudot = false,
        )
        repo.insertBook(book(1, "בראשית", isBase = true, categoryId = tanakhId))
        repo.insertBook(book(2, "רש\"י על בראשית", isBase = false, categoryId = talmudId))
        repo.insertLinesBatch(
            listOf(
                Line(id = 1, bookId = 1, lineIndex = 0, content = "gen 1:1", heRef = "בראשית א, א"),
                Line(id = 10, bookId = 2, lineIndex = 0, content = "rashi 1:1:1", heRef = "רש\"י א, א, א"),
            )
        )

        val allRefs = listOf(
            RefEntry("Genesis 1:1", "בראשית א, א", "Genesis", 1),
            RefEntry("Rashi on Genesis 1:1:1", "", "Rashi on Genesis", 1),
        )
        val refsByCanonical = allRefs.groupBy { canonicalCitation(it.ref) }
        val refsByBase = allRefs.associateBy { canonicalBase(it.ref) }
        val lineKeyToId = mapOf("Genesis" to 0 to 1L, "Rashi on Genesis" to 0 to 10L)
        val lineIdToBookId = mapOf(1L to 1L, 10L to 2L)
        // baseProvenance stays 0 (no declared/inferred base) → eligible for demotion.
        val bookMeta = mapOf(
            1L to BookMeta(isBaseBook = true, categoryLevel = 0, priorityRank = 0),
            2L to BookMeta(
                isBaseBook = false, categoryLevel = 1, priorityRank = null,
                dependence = Dependence.COMMENTARY, baseTextBookIds = setOf(1L),
            ),
        )

        val bindings = io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings(
            io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator.load(path = null),
            repo,
        )
        val importer = SefariaLinksImporter(repo, bindings, Logger.withTag("SefariaLinkImportDemoteTest"))
        importer.processLinksInParallel(
            linksDir = linksDir,
            refsByCanonical = refsByCanonical,
            refsByBase = refsByBase,
            lineKeyToId = lineKeyToId,
            lineIdToBookId = lineIdToBookId,
            bookMetaById = bookMeta,
        )

        // Insert-time snapshot: the link went in as COMMENTARY.
        val inserted = importer.metricsSnapshot()
        assertEquals(1L, inserted.insertedByType.getValue("COMMENTARY").written)

        // Demotion requires the category closure to resolve each book's corpus.
        repo.rebuildCategoryClosure()
        importer.demoteCrossCorpusDependantLinks()

        // Persisted split: the row is now RELATED, not COMMENTARY.
        val persisted = importer.persistedCountsByType()
        assertEquals(mapOf("RELATED" to 1L), persisted, "cross-corpus COMMENTARY must land in RELATED")

        // Retype-preserves-total invariant (mirrors the importer's check(...)).
        val insertedWrittenSum = inserted.insertedByType.values.sumOf { it.written }
        val persistedSum = persisted.values.sum()
        assertEquals(insertedWrittenSum, persistedSum, "demotion only retypes rows; totals must match")
        assertTrue(persistedSum == 1L)
    }
}
