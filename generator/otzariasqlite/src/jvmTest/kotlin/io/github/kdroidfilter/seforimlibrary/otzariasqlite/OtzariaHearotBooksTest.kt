package io.github.kdroidfilter.seforimlibrary.otzariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 'הערות על <title>' companion files must be imported as standalone books and
 * linked to their base book via the links dir — not attached as notesContent
 * (the legacy mechanism, which no client ever displayed).
 */
class OtzariaHearotBooksTest {
    @Test
    fun hearotFileImportsAsBookAndLinksToBase() = runBlocking {
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)

        val sourceDir = Files.createTempDirectory("otzaria-hearot")
        val bookDir = Files.createDirectories(sourceDir.resolve("אוצריא").resolve("מוסר"))
        Files.writeString(bookDir.resolve("מזור ותרופה.txt"), "<h1>מזור ותרופה</h1>\nגוף הספר")
        Files.writeString(
            bookDir.resolve("הערות על מזור ותרופה.txt"),
            "<h1>הערות על מזור ותרופה</h1>\nהערה ראשונה",
        )
        val linksDir = Files.createDirectories(sourceDir.resolve("links"))
        // line_index 2 on both sides: line 1 is a heading, and heading links are skipped.
        Files.writeString(
            linksDir.resolve("מזור ותרופה_links.json"),
            """
            |[
            | {"line_index_1": 2, "heRef_2": "הערות", "path_2": "הערות על מזור ותרופה.txt",
            |  "line_index_2": 2, "Conection Type": "commentary"}
            |]
            """.trimMargin(),
        )

        val generator = DatabaseGenerator(sourceDirectory = sourceDir, repository = repo)
        generator.generateLinesOnly()
        generator.generateLinksOnly()

        val base = repo.getAllBooks().find { it.title == "מזור ותרופה" }
        assertNotNull(base, "base book missing")
        assertNull(base.notesContent, "notesContent must stay empty — notes are a standalone book")
        val hearot = repo.getAllBooks().find { it.title == "הערות על מזור ותרופה" }
        assertNotNull(hearot, "hearot book was not imported as a standalone book")

        val baseLine = repo.getLines(base.id, 1, 1).single()
        val hearotLine = repo.getLines(hearot.id, 1, 1).single()
        assertEquals(1, repo.getLinkIdsBetweenLines(baseLine.id, hearotLine.id).size)
    }
}
