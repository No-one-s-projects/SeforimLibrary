package io.github.kdroidfilter.seforimlibrary.otzariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.kdroidfilter.seforimlibrary.core.models.Book
import io.github.kdroidfilter.seforimlibrary.core.models.Category
import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import io.github.kdroidfilter.seforimlibrary.core.models.Line
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `"source"`-typed Otzaria link is authored from the *dependant* book's own
 * file — e.g. a Rambam commentary whose links file is named after itself and
 * points at משנה תורה. The commentary cannot own a links file named after the
 * Sefaria-owned base, so it declares the base as its `source`. SOURCE is
 * virtual and is never stored, so the generator must persist it in the
 * canonical base→dependant direction: source = the JSON target (the base),
 * target = the authoring book, connectionType = COMMENTARY. A plain
 * `"commentary"` link in the same run must stay in its authored direction.
 *
 * Regression for the reversed Rambam-commentary links (Otzaria/otzaria#531).
 */
class OtzariaSourceLinksTest {
    @Test
    fun sourceTypedLinkIsStoredFlippedAsCommentary() = runBlocking {
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite::memory:")
        SeforimDb.Schema.create(driver)
        val repo = SeforimRepository(":memory:", driver)

        val sefariaSourceId = repo.insertSource("Sefaria")
        val nliSourceId = repo.insertSource("NationalLibrary")
        val catId = repo.insertCategory(Category(0, null, "הלכה", level = 0, order = 1))
        fun book(id: Long, title: String, sourceId: Long, totalLines: Int) = Book(
            id = id, categoryId = catId, sourceId = sourceId, title = title, heRef = title,
            authors = emptyList(), pubPlaces = emptyList(), pubDates = emptyList(),
            heShortDesc = null, notesContent = null, order = id.toFloat(), topics = emptyList(),
            isBaseBook = false, totalLines = totalLines, hasAltStructures = false,
            hasTeamim = false, hasNekudot = false,
        )
        // Rambam is Sefaria-owned; the commentary is an Otzaria (National Library) book.
        repo.insertBook(book(1, "משנה תורה, הלכות יסודי התורה", sefariaSourceId, 2))
        repo.insertBook(book(2, "אדני יד החזקה", nliSourceId, 2))
        // A plain Otzaria base→commentary pair — the control that must not flip.
        repo.insertBook(book(3, "בסיס", nliSourceId, 2))
        repo.insertBook(book(4, "מפרש רגיל", nliSourceId, 2))

        repo.insertLinesBatch(
            listOf(
                Line(id = 100, bookId = 1, lineIndex = 0, content = "רמבם שורה 1", heRef = "רמבם 1"),
                Line(id = 101, bookId = 1, lineIndex = 1, content = "רמבם שורה 2", heRef = "רמבם 2"),
                Line(id = 200, bookId = 2, lineIndex = 0, content = "מפרש שורה 1", heRef = "מפרש 1"),
                Line(id = 201, bookId = 2, lineIndex = 1, content = "מפרש שורה 2", heRef = "מפרש 2"),
                Line(id = 300, bookId = 3, lineIndex = 0, content = "בסיס שורה 1", heRef = "בסיס 1"),
                Line(id = 301, bookId = 3, lineIndex = 1, content = "בסיס שורה 2", heRef = "בסיס 2"),
                Line(id = 400, bookId = 4, lineIndex = 0, content = "מפרש רגיל שורה 1", heRef = "מר 1"),
                Line(id = 401, bookId = 4, lineIndex = 1, content = "מפרש רגיל שורה 2", heRef = "מר 2"),
            )
        )

        val sourceDir = Files.createTempDirectory("otzaria-source-links")
        val linksDir = Files.createDirectories(sourceDir.resolve("links"))
        // Authored from the commentary's own file: "משנה תורה is my source".
        Files.writeString(
            linksDir.resolve("אדני יד החזקה_links.json"),
            """
            |[
            | {"line_index_1": 2, "heRef_2": "ספר מדע, הלכות יסודי התורה, פרק א, א",
            |  "path_2": "משנה תורה, הלכות יסודי התורה.txt", "line_index_2": 2, "Conection Type": "source"}
            |]
            """.trimMargin()
        )
        // Control: a normal base→commentary link stays in its authored direction.
        Files.writeString(
            linksDir.resolve("בסיס_links.json"),
            """
            |[
            | {"line_index_1": 2, "heRef_2": "מפרש רגיל", "path_2": "מפרש רגיל.txt",
            |  "line_index_2": 2, "Conection Type": "commentary"}
            |]
            """.trimMargin()
        )

        DatabaseGenerator(sourceDirectory = sourceDir, repository = repo).generateLinksOnly()

        // SOURCE link is flipped to canonical base→dependant storage:
        // source = רמבם line (101), target = commentary line (201).
        val flipped = repo.getLinkIdsBetweenLines(101, 201)
        assertEquals(1, flipped.size)
        val link = repo.getLink(flipped.single())!!
        assertEquals(1, link.sourceBookId)   // Rambam (base) is the source
        assertEquals(2, link.targetBookId)   // the commentary is the target
        assertEquals(ConnectionType.COMMENTARY, link.connectionType)
        // The authored (un-flipped) direction must not exist.
        assertTrue(repo.getLinkIdsBetweenLines(201, 101).isEmpty())

        // Control: plain commentary stays as authored (source=בסיס line 301).
        val control = repo.getLinkIdsBetweenLines(301, 401)
        assertEquals(1, control.size)
        val controlLink = repo.getLink(control.single())!!
        assertEquals(3, controlLink.sourceBookId)
        assertEquals(4, controlLink.targetBookId)
        assertEquals(ConnectionType.COMMENTARY, controlLink.connectionType)
    }
}
