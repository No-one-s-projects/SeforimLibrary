package io.github.kdroidfilter.seforimlibrary.common.content

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompactContentTest {
    @Test
    fun `book content roundtrips without newline ambiguity`() {
        val lines = listOf("", "first\nsecond", "שלום 😀")
        val raw = CompactContent.encodeBook(lines)
        val compressed = CompactContent.compress(raw, 3)

        val decoded = CompactContent.decompress(compressed, raw.size, CompactContent.sha256(raw))

        assertEquals(lines, CompactContent.decodeBook(decoded, lines.size))
    }

    @Test
    fun `sparse version content preserves line ids`() {
        val lines = listOf(4L to "one", 99L to "two\nthree")
        val raw = CompactContent.encodeVersion(lines)

        assertEquals(lines, CompactContent.decodeVersion(raw, lines.size))
    }

    @Test
    fun `hash mismatch rejects corrupt content`() {
        val raw = CompactContent.encodeBook(listOf("text"))
        val wrongHash = CompactContent.sha256(raw).also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertFailsWith<IllegalArgumentException> {
            CompactContent.decompress(CompactContent.compress(raw, 1), raw.size, wrongHash)
        }
    }
}
