package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ForDB dry-run validator: EVERY broken rule is reported (not just the first),
 * good rules keep applying inside the same transaction (build-identical semantics),
 * and the rollback leaves the DB byte-identical — the validator never mutates.
 */
class ValidateForDbInputsTest {
    private val logger = Logger.withTag("test")

    @Test
    fun `collects every failure across sections and leaves the db untouched on rollback`() {
        withConnection { conn ->
            // root(1) > מקור(2); root(1) > יעד(3); book "ספר" under מקור
            insertCategory(conn, 1, "root", parentId = null, level = 0)
            insertCategory(conn, 2, "מקור", parentId = 1, level = 1)
            insertCategory(conn, 3, "יעד", parentId = 1, level = 1)
            insertBook(conn, 10, "ספר", categoryId = 2)

            conn.autoCommit = false
            val failures = collectForDbRuleFailures(
                conn,
                categoryRenames = emptyList(),
                categoryMoves = listOf(
                    CategoryMove("root/מקור", "root/יעד"),          // good
                    CategoryMove("root/לא-קיים", "root/יעד"),       // bad: source missing
                    CategoryMove("root/יעד", "root/איננו"),         // bad: dest parent missing (the מהרש"ם class)
                ),
                bookRenames = emptyList(),
                bookMoves = listOf(
                    // good — and note it depends on the FIRST move having applied
                    // (build-identical apply-as-you-go): the source path is post-move.
                    BookMove("ספר", "root/יעד/מקור", "root"),
                    BookMove("איננו", "root/יעד", "root"),           // bad: book missing
                ),
                logger = logger,
            )
            conn.rollback()

            assertEquals(3, failures.size, "every broken rule must be reported: $failures")
            assertEquals(
                listOf("category_moves.csv", "category_moves.csv", "book_moves.csv"),
                failures.map { it.section },
            )
            assertTrue(failures[0].message.contains("root/לא-קיים"), failures[0].message)
            assertTrue(failures[1].message.contains("root/איננו"), failures[1].message)
            assertTrue(failures[2].message.contains("איננו"), failures[2].message)

            // Rollback restored the pre-validation state exactly.
            assertEquals(1L, parentOf(conn, 2), "category move must be rolled back")
            assertEquals(2L, bookCategoryOf(conn, 10), "book move must be rolled back")
        }
    }

    @Test
    fun `clean rule set reports no failures`() {
        withConnection { conn ->
            insertCategory(conn, 1, "root", parentId = null, level = 0)
            insertCategory(conn, 2, "מקור", parentId = 1, level = 1)
            insertCategory(conn, 3, "יעד", parentId = 1, level = 1)
            insertBook(conn, 10, "ספר", categoryId = 2)

            conn.autoCommit = false
            val failures = collectForDbRuleFailures(
                conn,
                categoryRenames = emptyList(),
                categoryMoves = listOf(CategoryMove("root/מקור", "root/יעד")),
                bookRenames = listOf("ספר" to "ספר חדש"),
                bookMoves = emptyList(),
                logger = logger,
            )
            conn.rollback()
            assertEquals(emptyList(), failures)
            assertEquals(1L, parentOf(conn, 2))
        }
    }

    @Test
    fun `failed rule rolls back its partial destination leaf before the next rule`() {
        withConnection { conn ->
            insertCategory(conn, 1, "root", parentId = null, level = 0)
            insertCategory(conn, 2, "מקור", parentId = 1, level = 1)
            insertBook(conn, 10, "ספר", categoryId = 2)
            conn.autoCommit = false
            val failures = collectForDbRuleFailures(
                conn,
                categoryRenames = emptyList(),
                categoryMoves = emptyList(),
                bookRenames = emptyList(),
                bookMoves = listOf(
                    // Creates root/חדש and only then discovers that the book is absent.
                    BookMove("איננו", "root/מקור", "root/חדש"),
                    // Must also fail: root/חדש from the failed rule must not leak.
                    BookMove("ספר", "root/מקור", "root/חדש/עלה"),
                ),
                logger = logger,
            )
            assertEquals(2, failures.size)
            assertEquals(null, categoryId(conn, "חדש"))
            conn.rollback()
        }
    }

    @Test
    fun `rollback leaves a physical sqlite file byte identical`() {
        val path = Files.createTempFile("fordb-validator-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
                conn.createStatement().use { statement ->
                    statement.execute("CREATE TABLE category (id INTEGER PRIMARY KEY, parentId INTEGER, title TEXT NOT NULL, level INTEGER NOT NULL DEFAULT 0)")
                    statement.execute("CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT NOT NULL, categoryId INTEGER NOT NULL)")
                }
                insertCategory(conn, 1, "root", null, 0)
                insertCategory(conn, 2, "מקור", 1, 1)
                insertCategory(conn, 3, "יעד", 1, 1)
                insertBook(conn, 10, "ספר", 2)
            }
            val before = Files.readAllBytes(path)
            DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
                conn.autoCommit = false
                collectForDbRuleFailures(
                    conn, emptyList(), listOf(CategoryMove("root/מקור", "root/יעד")),
                    emptyList(), emptyList(), logger,
                )
                conn.rollback()
            }
            assertTrue(before.contentEquals(Files.readAllBytes(path)), "validation changed SQLite file bytes")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun withConnection(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE category (" +
                        "id INTEGER PRIMARY KEY, parentId INTEGER, title TEXT NOT NULL, " +
                        "level INTEGER NOT NULL DEFAULT 0)",
                )
                statement.execute(
                    "CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT NOT NULL, categoryId INTEGER NOT NULL)",
                )
            }
            block(connection)
        }
    }

    private fun insertCategory(conn: Connection, id: Long, title: String, parentId: Long?, level: Int) {
        conn.prepareStatement("INSERT INTO category (id,parentId,title,level) VALUES (?,?,?,?)").use { stmt ->
            stmt.setLong(1, id)
            if (parentId == null) stmt.setNull(2, java.sql.Types.INTEGER) else stmt.setLong(2, parentId)
            stmt.setString(3, title)
            stmt.setInt(4, level)
            stmt.executeUpdate()
        }
    }

    private fun insertBook(conn: Connection, id: Long, title: String, categoryId: Long) {
        conn.prepareStatement("INSERT INTO book (id,title,categoryId) VALUES (?,?,?)").use { stmt ->
            stmt.setLong(1, id)
            stmt.setString(2, title)
            stmt.setLong(3, categoryId)
            stmt.executeUpdate()
        }
    }

    private fun parentOf(conn: Connection, id: Long): Long? =
        conn.prepareStatement("SELECT parentId FROM category WHERE id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1).let { if (rs.wasNull()) null else it }
            }
        }

    private fun bookCategoryOf(conn: Connection, id: Long): Long =
        conn.prepareStatement("SELECT categoryId FROM book WHERE id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }

    private fun categoryId(conn: Connection, title: String): Long? =
        conn.prepareStatement("SELECT id FROM category WHERE title = ?").use { stmt ->
            stmt.setString(1, title)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
}
