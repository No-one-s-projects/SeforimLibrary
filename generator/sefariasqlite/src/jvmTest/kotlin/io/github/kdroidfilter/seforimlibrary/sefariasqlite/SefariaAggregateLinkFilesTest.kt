package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings
import io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sefaria's export ships two AGGREGATE summary CSVs in links/ (header
 * `Text 1,Text 2,Link Count` — per-book link counts, not per-link rows).
 * They must be skipped by exact filename; any OTHER file missing the
 * required per-link headers must still fail the build loudly.
 */
class SefariaAggregateLinkFilesTest {

    private fun runImport(linksDir: Path) = runBlocking {
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)
        val bindings = IdAllocatorBindings(InMemoryIdAllocator.load(path = null), repo)
        val importer = SefariaLinksImporter(repo, bindings, Logger.withTag("SefariaAggregateLinkFilesTest"))
        try {
            importer.processLinksInParallel(
                linksDir = linksDir,
                refsByCanonical = emptyMap(),
                refsByBase = emptyMap(),
                lineKeyToId = emptyMap(),
                lineIdToBookId = emptyMap(),
                bookMetaById = emptyMap(),
            )
        } finally {
            repo.close()
        }
    }

    @Test
    fun aggregateSummaryFilesAreSkippedWithoutError() {
        val linksDir = Files.createTempDirectory("seforim-aggregate-links")
        for (name in listOf("links_by_book.csv", "links_by_book_without_commentary.csv")) {
            Files.writeString(
                linksDir.resolve(name),
                """
                |Text 1,Text 2,Link Count
                |"Genesis","Rashi on Genesis","7442"
                """.trimMargin()
            )
        }
        // Must complete without throwing — the aggregate files are skipped by name.
        runImport(linksDir)
    }

    @Test
    fun otherFileWithMissingHeadersStillFailsBuild() {
        val linksDir = Files.createTempDirectory("seforim-bad-header-links")
        Files.writeString(
            linksDir.resolve("links42.csv"),
            """
            |Text 1,Text 2,Link Count
            |"Genesis","Rashi on Genesis","7442"
            """.trimMargin()
        )
        val ex = assertFailsWith<IllegalStateException> { runImport(linksDir) }
        assertTrue("links42.csv" in ex.message!!)
        assertTrue("Citation 1" in ex.message!!)
        assertTrue("Citation 2" in ex.message!!)
        assertTrue("Conection Type" in ex.message!!)
    }
}
