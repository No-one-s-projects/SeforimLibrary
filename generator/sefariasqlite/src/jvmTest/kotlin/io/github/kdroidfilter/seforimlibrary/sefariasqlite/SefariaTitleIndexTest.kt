package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the Meilah base-resolution bug: the title→bookId index must be
 * built in two global phases (all primaries, then all aliases) so a primary
 * title always beats any alias, even one contributed by an earlier (higher
 * priority) book.
 */
class SefariaTitleIndexTest {
    @Test
    fun laterPrimaryBeatsEarlierBooksAlias() {
        // bookA (earlier in priority) contributes alias "meilah"; bookB (later)
        // contributes PRIMARY "Meilah" → the key must resolve to bookB.
        val entries = listOf(
            BookTitleIndexEntry(
                bookId = 1L,
                primaryTitles = listOf("Mishnah Meilah", "משנה מעילה"),
                aliasKeys = listOf("meilah"),
            ),
            BookTitleIndexEntry(
                bookId = 2L,
                primaryTitles = listOf("Meilah", "מעילה"),
                aliasKeys = emptyList(),
            ),
        )
        val map = buildNormalizedTitleToBookId(entries)
        assertEquals(2L, map["meilah"], "primary title of book 2 must beat book 1's alias")
        assertEquals(2L, map[normalizeTitleKey("מעילה")])
        assertEquals(1L, map[normalizeTitleKey("Mishnah Meilah")])
    }

    @Test
    fun aliasStillResolvesWhenNoPrimaryClaimsTheKey() {
        val entries = listOf(
            BookTitleIndexEntry(
                bookId = 7L,
                primaryTitles = listOf("Pirkei Avot"),
                aliasKeys = listOf("avot"),
            ),
        )
        val map = buildNormalizedTitleToBookId(entries)
        assertEquals(7L, map[normalizeTitleKey("Pirkei Avot")])
        assertEquals(7L, map["avot"], "alias-only key must still resolve")
    }

    @Test
    fun firstPrimaryClaimantWinsInPriorityOrder() {
        val entries = listOf(
            BookTitleIndexEntry(bookId = 1L, primaryTitles = listOf("Rashi"), aliasKeys = emptyList()),
            BookTitleIndexEntry(bookId = 2L, primaryTitles = listOf("Rashi"), aliasKeys = emptyList()),
        )
        val map = buildNormalizedTitleToBookId(entries)
        assertEquals(1L, map["rashi"], "earliest primary claimant wins within phase 1")
    }
}
