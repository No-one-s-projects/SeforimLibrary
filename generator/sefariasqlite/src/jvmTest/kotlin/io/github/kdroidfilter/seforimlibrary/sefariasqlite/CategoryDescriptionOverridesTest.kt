package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.core.models.Category
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CategoryDescriptionOverridesTest {
    private val header =
        "\"categoryPath\",\"heShortDesc\",\"heDesc\",\"heShortDescNew\",\"heDescNew\""

    @Test
    fun `parser distinguishes keep clear replace and preserves multiline values`() {
        val overrides = parseCategoryDescriptionOverrides(
            listOf(
                header,
                "\"תנ״ך\",\"old\",\"old long\",\"\",\"[מחק]\"",
                "\"הלכה\",\"\",\"\",\" short \",\"line one",
                "line two\"",
            ),
        )

        assertEquals(DescriptionEdit.Keep, overrides[0].shortEdit)
        assertEquals(DescriptionEdit.Clear, overrides[0].longEdit)
        assertEquals(DescriptionEdit.Replace("short"), overrides[1].shortEdit)
        assertEquals(DescriptionEdit.Replace("line one\nline two"), overrides[1].longEdit)
    }

    @Test
    fun `parser rejects malformed headers paths and duplicates`() {
        assertFailsWith<IllegalArgumentException> {
            parseCategoryDescriptionOverrides(listOf("categoryPath,heShortDesc"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseCategoryDescriptionOverrides(listOf(header, "\"א\",\"\",\"\",\"unterminated"))
        }
        for (path in listOf("", "/א", "א/", "א//ב", " א", "תנ\"ך")) {
            assertFailsWith<IllegalArgumentException>("path '$path' must fail") {
                parseCategoryDescriptionOverrides(
                    listOf(header, "\"$path\",\"\",\"\",\"\",\"\""),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            parseCategoryDescriptionOverrides(
                listOf(
                    header,
                    "\"א\",\"\",\"\",\"\",\"\"",
                    "\"א\",\"\",\"\",\"\",\"\"",
                ),
            )
        }
    }

    @Test
    fun `apply is validated before writing and skips unchanged rows`() = runBlocking {
        withRepository { repository ->
            val categoryId = repository.insertCategory(
                Category(title = "הלכה", heShortDesc = "seed short", heDesc = "seed long"),
            )
            val invalid = listOf(
                CategoryDescriptionOverride(2, "הלכה", DescriptionEdit.Replace("changed"), DescriptionEdit.Keep),
                CategoryDescriptionOverride(3, "חסר", DescriptionEdit.Replace("bad"), DescriptionEdit.Keep),
            )
            assertFailsWith<IllegalArgumentException> {
                applyCategoryDescriptionOverrides(repository, invalid, Logger.withTag("test"))
            }
            assertEquals("seed short", repository.getCategory(categoryId)?.heShortDesc)

            val result = applyCategoryDescriptionOverrides(
                repository,
                listOf(
                    CategoryDescriptionOverride(
                        2,
                        "הלכה",
                        DescriptionEdit.Keep,
                        DescriptionEdit.Clear,
                    ),
                ),
                Logger.withTag("test"),
            )
            assertEquals(1, result.updated)
            assertEquals("seed short", repository.getCategory(categoryId)?.heShortDesc)
            assertNull(repository.getCategory(categoryId)?.heDesc)

            val noOp = applyCategoryDescriptionOverrides(
                repository,
                listOf(CategoryDescriptionOverride(2, "הלכה", DescriptionEdit.Keep, DescriptionEdit.Keep)),
                Logger.withTag("test"),
            )
            assertEquals(0, noOp.updated)
            assertEquals(1, noOp.unchanged)
        }
    }

    @Test
    fun `CSV parse through batch replaces both fields and persists multiline long description`() = runBlocking {
        withRepository { repository ->
            val categoryId = repository.insertCategory(
                Category(title = "הלכה", heShortDesc = "old short", heDesc = "old long"),
            )
            val overrides = parseCategoryDescriptionOverrides(
                listOf(
                    header,
                    "\"הלכה\",\"old short\",\"old long\",\"new short\",\"long line one",
                    "long line two\"",
                ),
            )

            val result = applyCategoryDescriptionOverrides(
                repository,
                overrides,
                Logger.withTag("test"),
            )

            assertEquals(1, result.updated)
            assertEquals("new short", repository.getCategory(categoryId)?.heShortDesc)
            assertEquals("long line one\nlong line two", repository.getCategory(categoryId)?.heDesc)
        }
    }

    @Test
    fun `talmud overrides add parent short description and preserve child long descriptions`() = runBlocking {
        withRepository { repository ->
            val bavli = repository.insertCategory(Category(title = "תלמוד בבלי", heDesc = "בבלי ארוך"))
            val yerushalmi = repository.insertCategory(Category(title = "תלמוד ירושלמי", heDesc = "ירושלמי ארוך"))
            val parentShort = "קצר אב"
            applyCategoryDescriptionOverrides(
                repository,
                listOf(
                    CategoryDescriptionOverride(2, "תלמוד בבלי", DescriptionEdit.Replace(parentShort), DescriptionEdit.Keep),
                    CategoryDescriptionOverride(3, "תלמוד ירושלמי", DescriptionEdit.Replace(parentShort), DescriptionEdit.Keep),
                ),
                Logger.withTag("test"),
            )

            assertEquals(parentShort, repository.getCategory(bavli)?.heShortDesc)
            assertEquals("בבלי ארוך", repository.getCategory(bavli)?.heDesc)
            assertEquals(parentShort, repository.getCategory(yerushalmi)?.heShortDesc)
            assertEquals("ירושלמי ארוך", repository.getCategory(yerushalmi)?.heDesc)
        }
    }

    private suspend fun withRepository(block: suspend (SeforimRepository) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repository = SeforimRepository(":memory:", driver)
        try {
            block(repository)
        } finally {
            repository.close()
        }
    }
}
