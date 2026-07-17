package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CategoryMovesTest {
    private val logger = Logger.withTag("test")

    @Test
    fun `move reparents category and shifts subtree levels`() {
        withConnection { conn ->
            // מחשבת ישראל(1) > מורה נבוכים(2) > מפרשים(3); מחשבת ישראל(1) > ראשונים(4)
            insert(conn, 1, "מחשבת ישראל", parentId = null, level = 0)
            insert(conn, 2, "מורה נבוכים", parentId = 1, level = 1)
            insert(conn, 3, "מפרשים", parentId = 2, level = 2)
            insert(conn, 4, "ראשונים", parentId = 1, level = 1)

            val applied = applyCategoryMove(
                conn,
                CategoryMove("מחשבת ישראל/מורה נבוכים", "מחשבת ישראל/ראשונים"),
                logger,
            )

            assertEquals(1, applied)
            assertEquals(4L, parentOf(conn, 2))
            assertEquals(2, levelOf(conn, 2))
            assertEquals(3, levelOf(conn, 3))
        }
    }

    @Test
    fun `move is idempotent once applied`() {
        withConnection { conn ->
            insert(conn, 1, "root", parentId = null, level = 0)
            insert(conn, 2, "moved", parentId = 1, level = 1)
            insert(conn, 3, "dest", parentId = 1, level = 1)

            val move = CategoryMove("root/moved", "root/dest")
            assertEquals(1, applyCategoryMove(conn, move, logger))
            // הנתיב המקורי כבר לא קיים אבל העלה יושב תחת היעד — דילוג
            assertEquals(0, applyCategoryMove(conn, move, logger))
            assertEquals(3L, parentOf(conn, 2))
        }
    }

    @Test
    fun `missing destination parent fails`() {
        withConnection { conn ->
            insert(conn, 1, "root", parentId = null, level = 0)
            insert(conn, 2, "moved", parentId = 1, level = 1)

            assertFailsWith<IllegalStateException> {
                applyCategoryMove(conn, CategoryMove("root/moved", "root/missing"), logger)
            }
            assertEquals(1L, parentOf(conn, 2))
        }
    }

    @Test
    fun `missing source with no applied leaf fails`() {
        withConnection { conn ->
            insert(conn, 1, "root", parentId = null, level = 0)
            insert(conn, 2, "dest", parentId = 1, level = 1)

            assertFailsWith<IllegalArgumentException> {
                applyCategoryMove(conn, CategoryMove("root/missing", "root/dest"), logger)
            }
        }
    }

    @Test
    fun `duplicate title under destination fails`() {
        withConnection { conn ->
            insert(conn, 1, "root", parentId = null, level = 0)
            insert(conn, 2, "moved", parentId = 1, level = 1)
            insert(conn, 3, "dest", parentId = 1, level = 1)
            insert(conn, 4, "moved", parentId = 3, level = 2)

            assertFailsWith<IllegalArgumentException> {
                applyCategoryMove(conn, CategoryMove("root/moved", "root/dest"), logger)
            }
            assertEquals(1L, parentOf(conn, 2))
        }
    }

    @Test
    fun `move under own descendant fails`() {
        withConnection { conn ->
            insert(conn, 1, "root", parentId = null, level = 0)
            insert(conn, 2, "moved", parentId = 1, level = 1)
            insert(conn, 3, "child", parentId = 2, level = 2)

            assertFailsWith<IllegalArgumentException> {
                applyCategoryMove(conn, CategoryMove("root/moved", "root/moved/child"), logger)
            }
            assertEquals(1L, parentOf(conn, 2))
        }
    }

    @Test
    fun `parse requires header and two fields`() {
        assertFailsWith<IllegalArgumentException> {
            parseCategoryMoves(listOf("a/b,c/d"), logger)
        }
        val moves = parseCategoryMoves(
            listOf("Source path,Destination parent path", "a/b,c/d"),
            logger,
        )
        assertEquals(listOf(CategoryMove("a/b", "c/d")), moves)
    }

    private fun withConnection(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE category (" +
                        "id INTEGER PRIMARY KEY, parentId INTEGER, title TEXT NOT NULL, " +
                        "level INTEGER NOT NULL DEFAULT 0)",
                )
            }
            block(connection)
        }
    }

    private fun insert(conn: Connection, id: Long, title: String, parentId: Long?, level: Int) {
        conn.prepareStatement("INSERT INTO category (id,parentId,title,level) VALUES (?,?,?,?)").use { stmt ->
            stmt.setLong(1, id)
            if (parentId == null) stmt.setNull(2, java.sql.Types.INTEGER) else stmt.setLong(2, parentId)
            stmt.setString(3, title)
            stmt.setInt(4, level)
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

    private fun levelOf(conn: Connection, id: Long): Int =
        conn.prepareStatement("SELECT level FROM category WHERE id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs ->
                check(rs.next())
                rs.getInt(1)
            }
        }
}
