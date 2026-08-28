package io.github.kdroidfilter.seforimlibrary.common.content

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/// Zstd compression is CPU-bound and independent per book/edition; only the
/// SQLite writes need to stay serialized. Reads+encode happen a chunk at a
/// time (bounds how much raw text sits in memory), compression within a
/// chunk runs across all cores, then that chunk's writes commit sequentially.
private val PARALLELISM = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

fun main() {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("CompactContent")
    val path = Paths.get(System.getProperty("dbPath") ?: error("-PdbPath= missing"))
    val level = (System.getProperty("contentZstdLevel") ?: "19").toInt().coerceIn(1, 22)
    require(Files.isRegularFile(path)) { "Database file not found: $path" }
    val before = Files.size(path)

    Class.forName("org.sqlite.JDBC")
    val (books, versions, skippedBooks, skippedVersions) =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            CompactContent.ensureSchema(conn)
            val (books, skippedBooks) = compactBooks(conn, level, logger)
            val (versions, skippedVersions) = compactVersions(conn, level, logger)
            verifyAll(conn)
            conn.createStatement().use {
                it.executeUpdate("INSERT OR REPLACE INTO schema_meta(key,value) VALUES ('content_storage_format','1')")
                it.executeUpdate("INSERT OR REPLACE INTO schema_meta(key,value) VALUES ('db_schema_version','3')")
            }
            listOf(books, versions, skippedBooks, skippedVersions)
        }

    // VACUUM needs a connection with no prior statement history - after tens of
    // thousands of prepared statements over one connection, sqlite-jdbc can still
    // consider some "in progress" even though every one was individually closed.
    // A fresh connection sidesteps that instead of chasing the leak.
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { it.execute("VACUUM") }
    }

    if (skippedBooks > 0 || skippedVersions > 0) {
        logger.w {
            "Skipped $skippedBooks book(s) and $skippedVersions edition(s) with malformed " +
                "line data - left in legacy (uncompacted) storage, see warnings above"
        }
    }
    logger.i { "Compacted $books books and $versions editions at Zstd level $level" }

    val after = Files.size(path)
    logger.i { "Database: ${humanSize(before)} -> ${humanSize(after)} (${"%.1f".format(after * 100.0 / before)}%)" }
}

/// One malformed book must not abort compaction of the other thousands - a
/// book that fails encoding is skipped (logged) and stays in legacy
/// (uncompacted) [line.content], which the reader already falls back to.
private fun compactBooks(conn: Connection, level: Int, logger: Logger): Pair<Int, Int> {
    val ids = queryLongs(conn, "SELECT id FROM book ORDER BY id")
    var count = 0
    var skipped = 0
    val pool = Executors.newFixedThreadPool(PARALLELISM)
    try {
        for (chunk in ids.chunked(PARALLELISM * 4)) {
            val prepared = ArrayList<Pair<Long, ByteArray>>(chunk.size)
            for (bookId in chunk) {
                if (hasRow(conn, "book_content", "bookId", bookId)) continue
                try {
                    val lines = ArrayList<String>()
                    var expectedIndex = 0
                    conn.prepareStatement("SELECT lineIndex, content FROM line WHERE bookId=? ORDER BY lineIndex").use { ps ->
                        ps.setLong(1, bookId)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                require(rs.getInt(1) == expectedIndex++) { "Non-contiguous lineIndex in book $bookId" }
                                lines += rs.getString(2)
                            }
                        }
                    }
                    prepared += bookId to CompactContent.encodeBook(lines)
                } catch (e: IllegalArgumentException) {
                    logger.w { "Skipping book $bookId: ${e.message}" }
                    skipped++
                }
            }
            if (prepared.isEmpty()) continue
            val compressedById = pool.invokeAll(prepared.map { (id, payload) ->
                Callable { id to CompactContent.compress(payload, level) }
            }).associate { it.get() }
            for ((bookId, payload) in prepared) {
                transaction(conn) {
                    insertContent(conn, "book_content", "bookId", bookId, compressedById.getValue(bookId), payload)
                    conn.prepareStatement("UPDATE line SET content='' WHERE bookId=?").use {
                        it.setLong(1, bookId); it.executeUpdate()
                    }
                }
                count++
            }
        }
    } finally {
        pool.shutdown()
    }
    return count to skipped
}

private fun compactVersions(conn: Connection, level: Int, logger: Logger): Pair<Int, Int> {
    val ids = queryLongs(conn, "SELECT DISTINCT versionId FROM version_line ORDER BY versionId")
    var count = 0
    var skipped = 0
    val pool = Executors.newFixedThreadPool(PARALLELISM)
    try {
        for (chunk in ids.chunked(PARALLELISM * 4)) {
            val prepared = ArrayList<Pair<Long, ByteArray>>(chunk.size)
            for (versionId in chunk) {
                if (hasRow(conn, "version_content", "versionId", versionId)) continue
                try {
                    val lines = ArrayList<Pair<Long, String>>()
                    conn.prepareStatement("SELECT lineId, content FROM version_line WHERE versionId=? ORDER BY lineId").use { ps ->
                        ps.setLong(1, versionId)
                        ps.executeQuery().use { rs -> while (rs.next()) lines += rs.getLong(1) to rs.getString(2) }
                    }
                    prepared += versionId to CompactContent.encodeVersion(lines)
                } catch (e: IllegalArgumentException) {
                    logger.w { "Skipping edition $versionId: ${e.message}" }
                    skipped++
                }
            }
            if (prepared.isEmpty()) continue
            val compressedById = pool.invokeAll(prepared.map { (id, payload) ->
                Callable { id to CompactContent.compress(payload, level) }
            }).associate { it.get() }
            for ((versionId, payload) in prepared) {
                transaction(conn) {
                    insertContent(conn, "version_content", "versionId", versionId, compressedById.getValue(versionId), payload)
                    conn.prepareStatement("UPDATE version_line SET content='' WHERE versionId=?").use {
                        it.setLong(1, versionId); it.executeUpdate()
                    }
                }
                count++
            }
        }
    } finally {
        pool.shutdown()
    }
    return count to skipped
}

private fun verifyAll(conn: Connection) {
    verifyTable(conn, "book_content", "bookId") { id, payload ->
        val count = scalarInt(conn, "SELECT COUNT(*) FROM line WHERE bookId=?", id)
        CompactContent.decodeBook(payload, count)
        require(scalarInt(conn, "SELECT COUNT(*) FROM line WHERE bookId=? AND content<>''", id) == 0)
    }
    verifyTable(conn, "version_content", "versionId") { id, payload ->
        val count = scalarInt(conn, "SELECT COUNT(*) FROM version_line WHERE versionId=?", id)
        CompactContent.decodeVersion(payload, count)
        require(scalarInt(conn, "SELECT COUNT(*) FROM version_line WHERE versionId=? AND content<>''", id) == 0)
    }
}

private fun verifyTable(conn: Connection, table: String, idColumn: String, verify: (Long, ByteArray) -> Unit) {
    conn.createStatement().use { st ->
        st.executeQuery("SELECT $idColumn, format, contentZstd, uncompressedSize, contentHash FROM $table").use { rs ->
            while (rs.next()) {
                require(rs.getInt(2) == CompactContent.FORMAT) { "Unsupported $table format" }
                verify(rs.getLong(1), CompactContent.decompress(rs.getBytes(3), rs.getInt(4), rs.getBytes(5)))
            }
        }
    }
}

private fun insertContent(conn: Connection, table: String, idColumn: String, id: Long, compressed: ByteArray, raw: ByteArray) {
    conn.prepareStatement("INSERT INTO $table($idColumn,format,contentZstd,uncompressedSize,contentHash) VALUES (?,1,?,?,?)").use {
        it.setLong(1, id); it.setBytes(2, compressed); it.setInt(3, raw.size)
        it.setBytes(4, CompactContent.sha256(raw)); it.executeUpdate()
    }
}

private fun transaction(conn: Connection, block: () -> Unit) {
    val autoCommit = conn.autoCommit
    conn.autoCommit = false
    try { block(); conn.commit() } catch (t: Throwable) { conn.rollback(); throw t } finally { conn.autoCommit = autoCommit }
}

private fun queryLongs(conn: Connection, sql: String): List<Long> = conn.createStatement().use { st ->
    st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
}

private fun hasRow(conn: Connection, table: String, column: String, id: Long): Boolean =
    scalarInt(conn, "SELECT COUNT(*) FROM $table WHERE $column=?", id) != 0

private fun scalarInt(conn: Connection, sql: String, id: Long): Int = conn.prepareStatement(sql).use {
    it.setLong(1, id); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
}

private fun humanSize(bytes: Long): String = "%.2f MiB".format(bytes / 1048576.0)
