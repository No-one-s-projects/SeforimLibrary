package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the resolver-reuse mechanism the Phase-2 LINKER importer relies on: build
 * refsByCanonical/refsByBase from sidecar-style RefEntry rows, then resolve a target_ref
 * back to the RefEntry whose (path, lineIndex) → lineId. (The full ~99% corpus coverage
 * is proven at bootstrap against a real sidecar; the earlier Python proxy showed 98–99%.)
 */
class GenerateLinkerLinksTest {

    private val refs = listOf(
        RefEntry("Psalms 16:8", "תהלים ט״ז:ח׳", "Tanakh/Writings/Psalms", 100),
        RefEntry("Genesis 1:1", "בראשית א׳:א׳", "Tanakh/Torah/Genesis", 0),
        RefEntry("Exodus 29:43", "שמות כ״ט:מ״ג", "Tanakh/Torah/Exodus", 500),
        RefEntry("Berakhot 2a:1", "ברכות ב׳ א:א", "Talmud/Bavli/Berakhot", 3),
    )
    private val byCanonical = refs.groupBy { canonicalCitation(it.ref) }
    private val byBase = HashMap<String, RefEntry>().apply {
        for (e in refs) {
            val base = canonicalBase(e.ref)
            val ex = this[base]
            if (ex == null || e.lineIndex < ex.lineIndex) this[base] = e
        }
    }

    private fun resolveOne(ref: String): RefEntry? =
        resolveRefs(ref, byCanonical, byBase).firstOrNull()

    @Test
    fun exactSegmentRefResolves() {
        val e = resolveOne("Psalms 16:8")
        assertNotNull(e)
        assertEquals(100, e.lineIndex)
        assertEquals("Tanakh/Writings/Psalms", e.path)
    }

    @Test
    fun rangeStartResolvesToItsSegment() {
        // A ranged citation resolves via its range-start (Exodus 29:43-44 → Exodus 29:43).
        val e = resolveOne("Exodus 29:43-44")
        assertNotNull(e)
        assertEquals(500, e.lineIndex)
    }

    @Test
    fun baseFallbackResolves() {
        // "Berakhot 2a" (no segment) falls back to the base's first entry.
        val e = resolveOne("Berakhot 2a")
        assertNotNull(e)
        assertEquals(3, e.lineIndex)
    }

    @Test
    fun unknownRefResolvesToEmpty() {
        assertTrue(resolveRefs("Nonexistent Book 9:9", byCanonical, byBase).isEmpty())
    }

    @Test
    fun linkerConnectionTypeIsWiredAndAppendedLast() {
        assertEquals(ConnectionType.LINKER, ConnectionType.fromString("linker"))
        // Appended last so existing ordinals — and therefore stable ids — don't shift.
        assertEquals(ConnectionType.LINKER, ConnectionType.values().last())
    }

    @Test
    fun contentHashMatchesPythonContract() {
        // Locks the cross-language contract: the SAME constants are asserted in
        // LinkerToOtzaria/tests/test_linker_artifact.py for linker_artifact.content_hash.
        assertEquals("aaf4c61ddcc5e8a2", linkerContentHash("hello"))
        assertEquals("643cbc0fbf2800d7", linkerContentHash("שלום עולם"))
        assertEquals("da39a3ee5e6b4b0d", linkerContentHash(""))
    }
}
