package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings
import io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Regression: the strict `Conection Type` guard must run BEFORE the
 * empty-citation skip, so an unknown type on a row with a blank citation still
 * fails the build (rather than silently escaping via the skip). The error must
 * also carry the 1-based CSV line number ("<file>:<lineNumber>").
 */
class SefariaLinkGuardOrderingTest {
    @Test
    fun unknownTypeOnRowWithEmptyCitationStillFailsBuild() {
        val tempDir = Files.createTempDirectory("seforim-guard-ordering")
        val linksDir = Files.createDirectories(tempDir.resolve("links"))
        // Row 2: Citation 1 blank + an unmapped type. The old ordering skipped
        // this row (empty citation) before validating the type.
        Files.writeString(
            linksDir.resolve("links0.csv"),
            """
            |Citation 1,Citation 2,Conection Type
            |"","Genesis 1:1","brand_new_type"
            """.trimMargin()
        )

        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)
        val bindings = IdAllocatorBindings(InMemoryIdAllocator.load(path = null), repo)
        val importer = SefariaLinksImporter(repo, bindings, Logger.withTag("SefariaLinkGuardOrderingTest"))

        val ex = assertFailsWith<IllegalStateException> {
            runBlocking {
                importer.processLinksInParallel(
                    linksDir = linksDir,
                    refsByCanonical = emptyMap(),
                    refsByBase = emptyMap(),
                    lineKeyToId = emptyMap(),
                    lineIdToBookId = emptyMap(),
                    bookMetaById = emptyMap(),
                )
            }
        }
        assertTrue("brand_new_type" in ex.message!!, "message must name the unmapped type")
        assertTrue("links0.csv:2" in ex.message!!, "message must carry file:lineNumber; was: ${ex.message}")
        repo.close()
    }
}
