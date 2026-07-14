package io.github.kdroidfilter.seforimlibrary.common.patch

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies [resolveSchemaVersion] precedence: explicit -P override wins,
 * else schema_meta.db_schema_version is read off the DB, else a hard fail
 * (no silent "1" default — no-fallbacks policy).
 */
class ResolveSchemaVersionTest {
    @JvmField @Rule
    val tmp = TemporaryFolder()

    private fun makeDb(schemaVersion: Int?): Path {
        val path = tmp.newFolder().toPath().resolve("seforim.db")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE schema_meta(key TEXT PRIMARY KEY, value TEXT)")
            }
            if (schemaVersion != null) {
                conn.prepareStatement("INSERT INTO schema_meta(key, value) VALUES ('db_schema_version', ?)").use { ps ->
                    ps.setString(1, schemaVersion.toString()); ps.executeUpdate()
                }
            }
        }
        return path
    }

    @Test
    fun `reads db_schema_version from schema_meta`() {
        val db = makeDb(schemaVersion = 2)
        System.clearProperty("fromSchemaVersion")
        assertEquals(2, resolveSchemaVersion(db, "fromSchemaVersion"))
    }

    @Test
    fun `explicit property overrides schema_meta`() {
        val db = makeDb(schemaVersion = 2)
        System.setProperty("fromSchemaVersion", "7")
        try {
            assertEquals(7, resolveSchemaVersion(db, "fromSchemaVersion"))
        } finally {
            System.clearProperty("fromSchemaVersion")
        }
    }

    @Test
    fun `hard fails when key missing and no property`() {
        val db = makeDb(schemaVersion = null)
        System.clearProperty("toSchemaVersion")
        val ex = assertFailsWith<IllegalStateException> {
            resolveSchemaVersion(db, "toSchemaVersion")
        }
        assertTrue("db_schema_version" in ex.message.orEmpty(), "error must name the cause: ${ex.message}")
    }

    @Test
    fun `non-integer explicit property fails loudly`() {
        val db = makeDb(schemaVersion = 2)
        System.setProperty("fromSchemaVersion", "notanint")
        try {
            val ex = assertFailsWith<IllegalStateException> {
                resolveSchemaVersion(db, "fromSchemaVersion")
            }
            assertTrue("not an integer" in ex.message.orEmpty())
        } finally {
            System.clearProperty("fromSchemaVersion")
        }
    }
}
