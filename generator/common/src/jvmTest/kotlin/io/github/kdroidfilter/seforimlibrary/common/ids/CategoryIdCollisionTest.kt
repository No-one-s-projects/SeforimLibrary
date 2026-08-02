package io.github.kdroidfilter.seforimlibrary.common.ids

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.kdroidfilter.seforimlibrary.common.buildstate.IdTable
import io.github.kdroidfilter.seforimlibrary.core.models.Category
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Regression test for the db_version=19 misplacement: 26 Otzaria books landed
 * inside unrelated folders (`אור הישר על נדה` under `מחשבת ישראל/אחרונים/רמחל`,
 * `אור הישר/סדר נזיקין` under `מורה נבוכים`, …).
 *
 * Cause: `renameCategories` runs BEFORE `appendOtzaria` and auto-creates the leaf
 * of every book_moves.csv destination with an *implicit rowid* — landing exactly
 * on the ids the persisted [InMemoryIdAllocator] was about to hand out. Because
 * `insertCategoryWithId` is `INSERT OR IGNORE`, the Otzaria folder was silently
 * never written while its books still got that categoryId.
 */
class CategoryIdCollisionTest {

    private fun newRepo(): SeforimRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SeforimDb.Schema.create(driver)
        return SeforimRepository(":memory:", driver)
    }

    @Test
    fun `a foreign row on the reserved id does not swallow the Otzaria folder`() = runBlocking {
        val repo = newRepo()
        val allocator = InMemoryIdAllocator.load(path = null)
        val bindings = IdAllocatorBindings(allocator, repo)

        // ── Sefaria stage: allocator-driven categories ────────────────────────
        val bavli = bindings.upsertCategory("תלמוד בבלי", null, "תלמוד בבלי", 0, 1)
        val acharonim = bindings.upsertCategory("תלמוד בבלי/אחרונים", bavli, "אחרונים", 1, 1)
        val machshava = bindings.upsertCategory("מחשבת ישראל", null, "מחשבת ישראל", 0, 2)
        val rishonim = bindings.upsertCategory("מחשבת ישראל/ראשונים", machshava, "ראשונים", 1, 1)

        // ── renameCategories: auto-creates a book_moves.csv leaf with an IMPLICIT
        //    rowid = MAX(id)+1 — precisely the id the allocator hands out next ───
        val squatter = repo.insertCategory(
            Category(id = 0, parentId = rishonim, title = "מורה נבוכים", level = 2, order = 999)
        )
        assertEquals(rishonim + 1, squatter, "the squatter must land on allocator territory")

        // ── appendOtzaria: raise the floor past the DB, then create the folder ─
        // `squatter` is MAX(category.id) at this point, exactly what GenerateLines reads.
        allocator.ensureCounterAtLeast(IdTable.CATEGORY, squatter + 1)
        val ohrHayashar =
            bindings.upsertCategory("תלמוד בבלי/אחרונים/אור הישר", acharonim, "אור הישר", 2, 999)
        val nezikin = bindings.upsertCategory(
            "תלמוד בבלי/אחרונים/אור הישר/סדר נזיקין", ohrHayashar, "סדר נזיקין", 3, 999
        )

        // The folder exists, under the right parent, and is NOT the squatter.
        assertNotEquals(squatter, nezikin)
        val stored = assertNotNull(repo.getCategory(nezikin))
        assertEquals("סדר נזיקין", stored.title)
        assertEquals(ohrHayashar, stored.parentId)

        // The book_moves leaf is untouched.
        val squatterRow = assertNotNull(repo.getCategory(squatter))
        assertEquals("מורה נבוכים", squatterRow.title)
        assertEquals(rishonim, squatterRow.parentId)
    }

    @Test
    fun `upsertCategory recovers even when the allocator floor was not raised`() = runBlocking {
        val repo = newRepo()
        val allocator = InMemoryIdAllocator.load(path = null)
        val bindings = IdAllocatorBindings(allocator, repo)

        val root = bindings.upsertCategory("מדרש", null, "מדרש", 0, 1)
        val aggadah = bindings.upsertCategory("מדרש/אגדה", root, "אגדה", 1, 1)

        // Three implicit-rowid leaves squat on the next three allocator ids and the
        // Otzaria stage forgets ensureCounterAtLeast — verify-after-insert still wins.
        val squatters = listOf("תנא דבי אליהו", "ילקוט שמעוני", "שות מהרשם").map { title ->
            repo.insertCategory(Category(id = 0, parentId = aggadah, title = title, level = 2, order = 999))
        }

        val katanot = bindings.upsertCategory(
            "תלמוד בבלי/אחרונים/אור הישר/מסכתות קטנות", aggadah, "מסכתות קטנות", 3, 999
        )

        // It had to walk past all three squatters, so recovery really ran.
        assertEquals(squatters.max() + 1, katanot)
        val stored = assertNotNull(repo.getCategory(katanot))
        assertEquals("מסכתות קטנות", stored.title)
        assertEquals(aggadah, stored.parentId)
        squatters.forEachIndexed { index, id ->
            val row = assertNotNull(repo.getCategory(id))
            assertEquals(listOf("תנא דבי אליהו", "ילקוט שמעוני", "שות מהרשם")[index], row.title)
        }
    }
}
