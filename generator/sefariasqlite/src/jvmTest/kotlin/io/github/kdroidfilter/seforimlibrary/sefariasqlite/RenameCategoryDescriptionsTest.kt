package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RenameCategoryDescriptionsTest {
    @Test
    fun `merge descriptions copies source keeps target and accepts equal values`() {
        withConnection { connection ->
            insert(connection, 1, "source", "source long")
            insert(connection, 2, null, null)
            mergeCategoryDescriptions(connection, 1, 2)
            assertEquals("source" to "source long", read(connection, 2))

            insert(connection, 3, null, null)
            insert(connection, 4, "target", "target long")
            mergeCategoryDescriptions(connection, 3, 4)
            assertEquals("target" to "target long", read(connection, 4))

            insert(connection, 5, "equal", "same")
            insert(connection, 6, "equal", "same")
            mergeCategoryDescriptions(connection, 5, 6)
            assertEquals("equal" to "same", read(connection, 6))
        }
    }

    @Test
    fun `merge descriptions rejects conflicts before either category changes`() {
        withConnection { connection ->
            insert(connection, 1, "source", "same")
            insert(connection, 2, "target", "same")

            assertFailsWith<IllegalStateException> {
                mergeCategoryDescriptions(connection, 1, 2)
            }
            assertEquals("source" to "same", read(connection, 1))
            assertEquals("target" to "same", read(connection, 2))
        }
    }

    @Test
    fun `rename merge path updates descriptions moves relations and deletes source`() {
        withConnection { connection ->
            insert(connection, 1, "source short", "source long", title = "old")
            insert(connection, 2, null, null, title = "new")
            insert(connection, 3, null, null, title = "child", parentId = 1)
            insertBook(connection, id = 10, categoryId = 1)

            val result = renameOrMergeCategory(
                connection,
                CategoryRename("old", "new", CategoryMatchMode.Exact),
                Logger.withTag("test"),
            )

            assertIs<RenameResult.Merged>(result)
            assertFalse(categoryExists(connection, 1))
            assertEquals("source short" to "source long", read(connection, 2))
            assertEquals(2, bookCategory(connection, 10))
            assertEquals(2, categoryParent(connection, 3))
        }
    }

    @Test
    fun `rename merge conflict leaves source book and child untouched`() {
        withConnection { connection ->
            insert(connection, 1, "source", "same", title = "old")
            insert(connection, 2, "target", "same", title = "new")
            insert(connection, 3, null, null, title = "child", parentId = 1)
            insertBook(connection, id = 10, categoryId = 1)

            assertFailsWith<IllegalStateException> {
                renameOrMergeCategory(
                    connection,
                    CategoryRename("old", "new", CategoryMatchMode.Exact),
                    Logger.withTag("test"),
                )
            }

            assertTrue(categoryExists(connection, 1))
            assertEquals("source" to "same", read(connection, 1))
            assertEquals("target" to "same", read(connection, 2))
            assertEquals(1, bookCategory(connection, 10))
            assertEquals(1, categoryParent(connection, 3))
        }
    }

    private fun withConnection(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE category (" +
                        "id INTEGER PRIMARY KEY, parentId INTEGER, title TEXT NOT NULL, " +
                        "heShortDesc TEXT, heDesc TEXT)",
                )
                statement.execute(
                    "CREATE TABLE book (id INTEGER PRIMARY KEY, categoryId INTEGER NOT NULL)",
                )
            }
            block(connection)
        }
    }

    private fun insert(
        connection: Connection,
        id: Long,
        short: String?,
        long: String?,
        title: String = "category $id",
        parentId: Long? = null,
    ) {
        connection.prepareStatement(
            "INSERT INTO category (id,parentId,title,heShortDesc,heDesc) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, id)
            if (parentId == null) statement.setNull(2, java.sql.Types.INTEGER) else statement.setLong(2, parentId)
            statement.setString(3, title)
            statement.setString(4, short)
            statement.setString(5, long)
            statement.executeUpdate()
        }
    }

    private fun insertBook(connection: Connection, id: Long, categoryId: Long) {
        connection.prepareStatement("INSERT INTO book (id,categoryId) VALUES (?, ?)").use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, categoryId)
            statement.executeUpdate()
        }
    }

    private fun categoryExists(connection: Connection, id: Long): Boolean =
        queryLong(connection, "SELECT COUNT(*) FROM category WHERE id = ?", id) == 1L

    private fun categoryParent(connection: Connection, id: Long): Long? =
        queryLong(connection, "SELECT parentId FROM category WHERE id = ?", id)

    private fun bookCategory(connection: Connection, id: Long): Long? =
        queryLong(connection, "SELECT categoryId FROM book WHERE id = ?", id)

    private fun queryLong(connection: Connection, sql: String, id: Long): Long? =
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                result.getLong(1).let { if (result.wasNull()) null else it }
            }
        }

    private fun read(connection: Connection, id: Long): Pair<String?, String?> =
        connection.prepareStatement(
            "SELECT heShortDesc, heDesc FROM category WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getString(1) to result.getString(2)
            }
        }
}
