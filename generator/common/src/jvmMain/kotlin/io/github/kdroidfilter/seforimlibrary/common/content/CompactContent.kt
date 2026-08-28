package io.github.kdroidfilter.seforimlibrary.common.content

import com.github.luben.zstd.Zstd
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.sql.Connection

object CompactContent {
    const val FORMAT = 1

    fun encodeBook(lines: List<String>): ByteArray = ByteArrayOutputStream().use { out ->
        lines.forEach { content ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            out.write(leInt(bytes.size))
            out.write(bytes)
        }
        out.toByteArray()
    }

    fun decodeBook(payload: ByteArray, lineCount: Int): List<String> {
        val input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return List(lineCount) {
            require(input.remaining() >= Int.SIZE_BYTES) { "Truncated book content length" }
            val size = input.int
            require(size >= 0 && size <= input.remaining()) { "Invalid book content length: $size" }
            ByteArray(size).also(input::get).toString(Charsets.UTF_8)
        }.also { require(!input.hasRemaining()) { "Trailing bytes in book content" } }
    }

    fun encodeVersion(lines: List<Pair<Long, String>>): ByteArray = ByteArrayOutputStream().use { out ->
        lines.forEach { (lineId, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            out.write(leLong(lineId))
            out.write(leInt(bytes.size))
            out.write(bytes)
        }
        out.toByteArray()
    }

    fun decodeVersion(payload: ByteArray, lineCount: Int): List<Pair<Long, String>> {
        val input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return List(lineCount) {
            require(input.remaining() >= Long.SIZE_BYTES + Int.SIZE_BYTES) { "Truncated version content record" }
            val lineId = input.long
            val size = input.int
            require(size >= 0 && size <= input.remaining()) { "Invalid version content length: $size" }
            lineId to ByteArray(size).also(input::get).toString(Charsets.UTF_8)
        }.also { require(!input.hasRemaining()) { "Trailing bytes in version content" } }
    }

    fun compress(payload: ByteArray, level: Int): ByteArray = Zstd.compress(payload, level)

    fun decompress(compressed: ByteArray, size: Int, hash: ByteArray): ByteArray {
        require(size >= 0) { "Negative uncompressed size" }
        val payload = Zstd.decompress(compressed, size)
        require(payload.size == size) { "Unexpected uncompressed size" }
        require(sha256(payload).contentEquals(hash)) { "Compact content hash mismatch" }
        return payload
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun ensureSchema(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS book_content (
                    bookId INTEGER PRIMARY KEY NOT NULL REFERENCES book(id) ON DELETE CASCADE,
                    format INTEGER NOT NULL DEFAULT 1, contentZstd BLOB NOT NULL,
                    uncompressedSize INTEGER NOT NULL, contentHash BLOB NOT NULL)""",
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS version_content (
                    versionId INTEGER PRIMARY KEY NOT NULL REFERENCES book_version(id) ON DELETE CASCADE,
                    format INTEGER NOT NULL DEFAULT 1, contentZstd BLOB NOT NULL,
                    uncompressedSize INTEGER NOT NULL, contentHash BLOB NOT NULL)""",
            )
        }
    }

    private fun leInt(value: Int) = ByteBuffer.allocate(Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun leLong(value: Long) = ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}
