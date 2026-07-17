package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.seforimlibrary.common.OptimizedHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Post-processing step to rename and merge categories, rename books, and
 * relocate books between categories. Runs after Sefaria import but before
 * Otzaria, so naming is unified before additional books are added.
 *
 * Rules are downloaded from otzaria-library/ForDB/ on GitHub (UTF-8):
 *  - category_renames.csv `old,new` — category renames (exact match, explicit prefix rules)
 *  - category_moves.csv   `sourcePath,destParentPath` — reparent a category (with its subtree) under a new parent
 *  - book_renames.csv     `old,new` — book title renames (exact match)
 *  - book_moves.csv       `name,sourcePath,destPath` (simple CSV; embedded newlines in quoted fields are not supported)
 *
 * Rules are fetched from the fixed `fordb-latest` GitHub release asset.
 * Download failures are fatal because silently skipping these rules can produce
 * an invalid DB delta.
 *
 * Category renames handle two cases:
 * 1. Simple rename: When no target category exists under the same parent
 * 2. Merge: When a target category already exists, books are moved and source is deleted
 *
 * Book moves require the source path and the destination's parent path to exist;
 * only the final (leaf) destination segment is auto-created if missing (idempotent).
 *
 * Category moves reparent an existing category (books and subcategories follow).
 * The destination parent must already exist (nothing is auto-created); moving a
 * category under itself or its own descendant fails. Idempotent: once applied,
 * the source path no longer resolves and the row is skipped.
 *
 * Order of operations: category renames → category moves → book renames → book
 * moves. Paths in category_moves.csv and book_moves.csv must therefore reference
 * the POST-rename category names, and book_moves paths the POST-move tree.
 * If a move references a pre-rename destPath, the task fails with a clear error.
 *
 * Usage:
 *   ./gradlew -p SeforimLibrary :sefariasqlite:renameCategories -PseforimDb=/path/to/seforim.db
 *
 * Env alternatives:
 *   SEFORIM_DB
 */
private const val FOR_DB_RELEASE_API =
    "https://api.github.com/repos/Otzaria/otzaria-library/releases/tags/fordb-latest"
private const val FOR_DB_ARCHIVE_NAME = "fordb_latest.zip"
private const val FOR_DB_USER_AGENT = "SeforimLibrary-ForDBFetcher/1.0"
internal val FOR_DB_CSV_FILES = mapOf(
    "categoryRenames" to "category_renames.csv",
    "categoryMoves" to "category_moves.csv",
    "bookRenames" to "book_renames.csv",
    "bookMoves" to "book_moves.csv",
    "generations" to "generations.csv",
    "categoryDescriptions" to "sefaria_category_changes.csv",
)

fun main(args: Array<String>) {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("RenameCategories")

    val dbPathStr = args.getOrNull(0)
        ?: System.getProperty("seforimDb")
        ?: System.getenv("SEFORIM_DB")
        ?: Paths.get("build", "seforim.db").toString()
    val dbPath = Paths.get(dbPathStr)

    if (!dbPath.exists()) {
        logger.e { "DB not found at $dbPath" }
        exitProcess(1)
    }

    logger.i { "Renaming/merging categories in $dbPath" }

    // Rules downloaded from otzaria-library/ForDB/ at startup.
    // category_renames.csv and book_renames.csv have no header; book_moves.csv has one.
    val categoryRenames: List<CategoryRename> =
        parseCategoryRenames(downloadRequiredForDbFile(FOR_DB_CSV_FILES.getValue("categoryRenames"), logger))
    val bookRenames: List<Pair<String, String>> =
        parsePairs(
            downloadRequiredForDbFile(FOR_DB_CSV_FILES.getValue("bookRenames"), logger),
            FOR_DB_CSV_FILES.getValue("bookRenames")
        )
    val bookMoves: List<BookMove> =
        parseBookMoves(downloadRequiredForDbFile(FOR_DB_CSV_FILES.getValue("bookMoves"), logger), logger)
    val categoryMoves: List<CategoryMove> =
        parseCategoryMoves(downloadRequiredForDbFile(FOR_DB_CSV_FILES.getValue("categoryMoves"), logger), logger)

    try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.autoCommit = false

            var totalRenamed = 0
            var totalMerged = 0
            runSection("Category renames", categoryRenames, logger) { rule ->
                val result = renameOrMergeCategory(conn, rule, logger)
                when (result) {
                    is RenameResult.Renamed -> totalRenamed += result.count
                    is RenameResult.Merged -> totalMerged += result.booksMoved
                    is RenameResult.NotFound -> logger.i {
                        "Category rename: '${rule.oldName}' not found; no rows changed"
                    }
                }
                result.rows()
            }

            val categoriesMoved = runSection("Category moves", categoryMoves, logger) { move ->
                applyCategoryMove(conn, move, logger)
            }

            val booksRenamed = runSection("Book renames", bookRenames, logger) { (oldTitle, newTitle) ->
                renameBookTitle(conn, oldTitle, newTitle, logger)
            }

            val booksMoved = runSection("Book moves", bookMoves, logger) { move -> applyBookMove(conn, move, logger) }

            conn.commit()
            logger.i {
                "Post-process done: categories renamed=$totalRenamed merged=$totalMerged moved=$categoriesMoved; " +
                    "books renamed=$booksRenamed; books moved=$booksMoved"
            }
        }
    } catch (e: Exception) {
        logger.e(e) { "Failed to open or commit DB; aborting" }
        exitProcess(1)
    }
}

/** `old,new` rows — ignores blank rows and fails on malformed non-blank rows. */
internal fun parsePairs(lines: List<String>, sourceName: String = "pairs CSV"): List<Pair<String, String>> =
    parseRequiredCsvRows(lines, sourceName, minFields = 2).map { f -> f[0] to f[1] }

internal enum class CategoryMatchMode { Exact, Prefix }

internal data class CategoryRename(
    val oldName: String,
    val newName: String,
    val matchMode: CategoryMatchMode,
)

private val EXPLICIT_CATEGORY_PREFIX_RULES = setOf(
    "ראשונים על",
    "אחרונים על",
    "פירושים מודרניים",
)

private fun parseCategoryRenames(lines: List<String>): List<CategoryRename> =
    parseRequiredCsvRows(lines, FOR_DB_CSV_FILES.getValue("categoryRenames"), minFields = 2).map { f ->
        val matchMode = if (f[0] in EXPLICIT_CATEGORY_PREFIX_RULES) {
            CategoryMatchMode.Prefix
        } else {
            CategoryMatchMode.Exact
        }
        CategoryRename(oldName = f[0], newName = f[1], matchMode = matchMode)
    }.distinct()

// Fields are kept verbatim (no trim) so rules can exact-match DB values that
// carry edge spaces.
internal fun parseRequiredCsvRows(lines: List<String>, sourceName: String, minFields: Int): List<List<String>> =
    lines.mapIndexedNotNull { index, line ->
        val fields = parseForDbCsvLine(line)
        if (fields.all { it.isBlank() }) return@mapIndexedNotNull null
        require(fields.size >= minFields && fields.take(minFields).none { it.isBlank() }) {
            "$sourceName row ${index + 1} is malformed: $line"
        }
        fields
    }

internal fun parseForDbCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                sb.append(c)
            }
        } else {
            when (c) {
                '"' -> if (sb.isEmpty()) inQuotes = true else sb.append(c)
                ',' -> {
                    result += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        i++
    }
    result += sb.toString()
    return result
}

/**
 * Splits CSV text into logical records, honoring RFC-4180 quoted fields that may
 * span multiple physical lines. Needed for files whose fields can contain
 * newlines (e.g. multi-line descriptions); single-line files use parseForDbCsvLine.
 * Blank records (no fields, all empty) are dropped.
 */
internal fun parseForDbCsvRecords(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var fields = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var recordHasCsvSyntax = false
    var i = 0

    fun endField() { fields.add(sb.toString()); sb.setLength(0) }
    fun endRecord() {
        endField()
        if (fields.any { it.isNotEmpty() } || recordHasCsvSyntax) records.add(fields)
        fields = mutableListOf()
        recordHasCsvSyntax = false
    }

    while (i < text.length) {
        val c = text[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ }
                else inQuotes = false
            } else sb.append(c)
        } else {
            when (c) {
                '"' -> if (sb.isEmpty()) {
                    inQuotes = true
                    recordHasCsvSyntax = true
                } else sb.append(c)
                ',' -> {
                    recordHasCsvSyntax = true
                    endField()
                }
                '\n' -> endRecord()
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') { endRecord(); i++ } else endRecord()
                else -> sb.append(c)
            }
        }
        i++
    }
    require(!inQuotes) { "Unterminated quoted field in ForDB CSV" }
    if (sb.isNotEmpty() || fields.isNotEmpty()) endRecord()
    return records
}

/**
 * `name,Source path,Destination path` rows. Drops the header row if present;
 * detection requires both `source path` and `destination path` tokens so a
 * stray book title containing the word "path" can't be mistaken for a header.
 * Missing or malformed headers fail the task so release data issues are not
 * hidden.
 */
private fun parseBookMoves(lines: List<String>, logger: Logger): List<BookMove> {
    val firstLower = lines.firstOrNull()?.lowercase()
    val isHeader = firstLower != null && "source path" in firstLower && "destination path" in firstLower
    require(isHeader) {
        "${FOR_DB_CSV_FILES.getValue("bookMoves")} must start with a header containing Source path and Destination path"
    }
    return parseRequiredCsvRows(lines.drop(1), FOR_DB_CSV_FILES.getValue("bookMoves"), minFields = 3)
        .map { f -> BookMove(f[0], f[1], f[2]) }
        .also { logger.i { "Loaded ${it.size} book move rule(s)" } }
}

/**
 * `Source path,Destination parent path` rows. Same header discipline as
 * [parseBookMoves]: a missing or malformed header fails the task.
 */
internal fun parseCategoryMoves(lines: List<String>, logger: Logger): List<CategoryMove> {
    val firstLower = lines.firstOrNull()?.lowercase()
    val isHeader = firstLower != null && "source path" in firstLower && "destination parent path" in firstLower
    require(isHeader) {
        "${FOR_DB_CSV_FILES.getValue("categoryMoves")} must start with a header containing Source path and Destination parent path"
    }
    return parseRequiredCsvRows(lines.drop(1), FOR_DB_CSV_FILES.getValue("categoryMoves"), minFields = 2)
        .map { f -> CategoryMove(f[0], f[1]) }
        .also { logger.i { "Loaded ${it.size} category move rule(s)" } }
}

private fun <T> runSection(name: String, items: List<T>, logger: Logger, apply: (T) -> Int): Int {
    var applied = 0
    for (item in items) {
        applied += apply(item)
    }
    logger.i { "$name: applied=$applied" }
    return applied
}

internal sealed class RenameResult {
    data class Renamed(val count: Int) : RenameResult()
    data class Merged(val booksMoved: Int) : RenameResult()
    data object NotFound : RenameResult()

    fun rows(): Int = when (this) {
        is Renamed -> count
        is Merged -> booksMoved
        is NotFound -> 0
    }
}

/**
 * Renames a category or merges it into an existing category with the target name.
 *
 * @return The result of the operation
 */
internal fun renameOrMergeCategory(
    conn: Connection,
    rule: CategoryRename,
    logger: Logger
): RenameResult {
    val oldName = rule.oldName
    val newName = rule.newName
    val sourceCats = findSourceCategories(conn, rule)

    if (sourceCats.isEmpty()) {
        return RenameResult.NotFound
    }

    var totalRenamed = 0
    var totalBooksMoved = 0

    for ((sourceId, parentId) in sourceCats) {
        // Check if a target category with newName exists under the same parent
        val targetId = findCategoryByNameAndParent(conn, newName, parentId)

        if (targetId != null && targetId != sourceId) {
            // Resolve descriptions before any destructive part of the merge.
            mergeCategoryDescriptions(conn, sourceId, targetId)
            val booksMoved = moveBooksToCategory(conn, sourceId, targetId)
            val subCatsMoved = moveSubcategoriesToParent(conn, sourceId, targetId)
            deleteCategory(conn, sourceId)
            logger.i { "Merged '$oldName' (id=$sourceId) into '$newName' (id=$targetId): $booksMoved books, $subCatsMoved subcategories" }
            totalBooksMoved += booksMoved
        } else {
            // Simple rename
            conn.prepareStatement("UPDATE category SET title = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, newName)
                stmt.setLong(2, sourceId)
                stmt.executeUpdate()
            }
            logger.i { "Renamed '$oldName' (id=$sourceId) -> '$newName'" }
            totalRenamed++
        }
    }

    return if (totalBooksMoved > 0) {
        RenameResult.Merged(totalBooksMoved)
    } else {
        RenameResult.Renamed(totalRenamed)
    }
}

private fun findCategoryByNameAndParent(conn: Connection, name: String, parentId: Long?): Long? {
    val sql = if (parentId != null) {
        "SELECT id FROM category WHERE title = ? AND parentId = ?"
    } else {
        "SELECT id FROM category WHERE title = ? AND parentId IS NULL"
    }
    conn.prepareStatement(sql).use { stmt ->
        stmt.setString(1, name)
        if (parentId != null) {
            stmt.setLong(2, parentId)
        }
        stmt.executeQuery().use { rs ->
            return if (rs.next()) rs.getLong(1) else null
        }
    }
}

private data class StoredCategoryDescriptions(
    val heShortDesc: String?,
    val heDesc: String?,
)

/** Preserves non-conflicting descriptions when one category is merged into another. */
internal fun mergeCategoryDescriptions(conn: Connection, sourceId: Long, targetId: Long) {
    fun read(categoryId: Long): StoredCategoryDescriptions =
        conn.prepareStatement(
            "SELECT heShortDesc, heDesc FROM category WHERE id = ?",
        ).use { stmt ->
            stmt.setLong(1, categoryId)
            stmt.executeQuery().use { result ->
                check(result.next()) { "Category id=$categoryId disappeared during merge" }
                StoredCategoryDescriptions(
                    heShortDesc = result.getString(1),
                    heDesc = result.getString(2),
                )
            }
        }

    fun resolve(field: String, source: String?, target: String?): String? = when {
        source == null -> target
        target == null -> source
        source == target -> target
        else -> error(
            "Cannot merge category id=$sourceId into id=$targetId: " +
                "conflicting $field values",
        )
    }

    val source = read(sourceId)
    val target = read(targetId)
    val mergedShort = resolve("heShortDesc", source.heShortDesc, target.heShortDesc)
    val mergedLong = resolve("heDesc", source.heDesc, target.heDesc)
    if (mergedShort == target.heShortDesc && mergedLong == target.heDesc) return

    conn.prepareStatement(
        "UPDATE category SET heShortDesc = ?, heDesc = ? WHERE id = ?",
    ).use { stmt ->
        stmt.setString(1, mergedShort)
        stmt.setString(2, mergedLong)
        stmt.setLong(3, targetId)
        check(stmt.executeUpdate() == 1) { "Failed to update merge target category id=$targetId" }
    }
}

private fun moveBooksToCategory(conn: Connection, fromCategoryId: Long, toCategoryId: Long): Int {
    conn.prepareStatement("UPDATE book SET categoryId = ? WHERE categoryId = ?").use { stmt ->
        stmt.setLong(1, toCategoryId)
        stmt.setLong(2, fromCategoryId)
        return stmt.executeUpdate()
    }
}

private fun moveSubcategoriesToParent(conn: Connection, fromCategoryId: Long, toParentId: Long): Int {
    conn.prepareStatement("UPDATE category SET parentId = ? WHERE parentId = ?").use { stmt ->
        stmt.setLong(1, toParentId)
        stmt.setLong(2, fromCategoryId)
        return stmt.executeUpdate()
    }
}

private fun deleteCategory(conn: Connection, categoryId: Long) {
    conn.prepareStatement("DELETE FROM category WHERE id = ?").use { stmt ->
        stmt.setLong(1, categoryId)
        stmt.executeUpdate()
    }
}

/**
 * Category prefix rules are intentionally limited to [EXPLICIT_CATEGORY_PREFIX_RULES].
 * All other rows are exact-match only.
 */
private fun findSourceCategories(conn: Connection, rule: CategoryRename): List<Pair<Long, Long?>> {
    fun query(sql: String, param: String): List<Pair<Long, Long?>> {
        val rows = mutableListOf<Pair<Long, Long?>>()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, param)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val parent = rs.getLong(2).let { if (rs.wasNull()) null else it }
                    rows.add(rs.getLong(1) to parent)
                }
            }
        }
        return rows
    }

    return when (rule.matchMode) {
        CategoryMatchMode.Exact ->
            query("SELECT id, parentId FROM category WHERE title = ?", rule.oldName)
        CategoryMatchMode.Prefix -> {
            val likePattern = rule.oldName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            query("SELECT id, parentId FROM category WHERE title LIKE ? ESCAPE '\\'", likePattern)
        }
    }
}

private fun renameBookTitle(conn: Connection, oldTitle: String, newTitle: String, logger: Logger): Int {
    val ids = findBookIdsByTitle(conn, oldTitle)
    if (ids.isEmpty()) {
        val alreadyRenamed = findBookIdsByTitle(conn, newTitle)
        if (alreadyRenamed.isNotEmpty()) {
            logger.i { "Book rename '$oldTitle' -> '$newTitle' already applied (${alreadyRenamed.size} row(s))" }
            return 0
        }
        logger.w { "Book rename: '$oldTitle' not found; no rows changed" }
        return 0
    }
    require(ids.size == 1) {
        "Book rename '$oldTitle' -> '$newTitle' matched ${ids.size} books"
    }
    val n = conn.prepareStatement("UPDATE book SET title = ? WHERE id = ?").use { stmt ->
        stmt.setString(1, newTitle)
        stmt.setLong(2, ids.single())
        stmt.executeUpdate()
    }
    logger.i { "Renamed book '$oldTitle' -> '$newTitle' ($n rows)" }
    return n
}

private fun findBookIdsByTitle(conn: Connection, title: String): List<Long> =
    conn.prepareStatement("SELECT id FROM book WHERE title = ?").use { stmt ->
        stmt.setString(1, title)
        stmt.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.getLong(1))
            }
        }
    }

private data class BookMove(val name: String, val sourcePath: String, val destPath: String)

internal data class CategoryMove(val sourcePath: String, val destParentPath: String)

/**
 * Reparents the category at [CategoryMove.sourcePath] (books and subtree follow via
 * parentId) under [CategoryMove.destParentPath], which must already exist. Subtree
 * levels are shifted to match the new depth. Idempotent: a source path that no
 * longer resolves but whose leaf already sits under the destination is skipped.
 */
internal fun applyCategoryMove(conn: Connection, move: CategoryMove, logger: Logger): Int {
    val title = move.sourcePath.split('/').map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
        ?: error("Category move has an empty source path")
    val destParentId = resolveCategoryPath(conn, move.destParentPath)
        ?: error("Category move destination parent '${move.destParentPath}' not found for '${move.sourcePath}' (nothing is auto-created)")

    val sourceId = resolveCategoryPath(conn, move.sourcePath)
    if (sourceId == null) {
        val existing = findCategoryByNameAndParent(conn, title, destParentId)
        require(existing != null) { "Category move source '${move.sourcePath}' not found" }
        logger.i { "Category move '${move.sourcePath}' already applied (id=$existing, dest=${move.destParentPath})" }
        return 0
    }
    if (categoryParent(conn, sourceId) == destParentId) {
        logger.i { "Category move '${move.sourcePath}' already applied (id=$sourceId, dest=${move.destParentPath})" }
        return 0
    }
    require(findCategoryByNameAndParent(conn, title, destParentId) == null) {
        "Category move '${move.sourcePath}': a category titled '$title' already exists under '${move.destParentPath}' (merge is not supported)"
    }
    // Walk up from the destination to reject a move under the category itself or its descendant.
    var cursor: Long? = destParentId
    while (cursor != null) {
        require(cursor != sourceId) {
            "Category move '${move.sourcePath}' would create a cycle: '${move.destParentPath}' is inside the moved category"
        }
        cursor = categoryParent(conn, cursor)
    }

    val delta = categoryLevel(conn, destParentId) + 1 - categoryLevel(conn, sourceId)
    conn.prepareStatement("UPDATE category SET parentId = ? WHERE id = ?").use { stmt ->
        stmt.setLong(1, destParentId)
        stmt.setLong(2, sourceId)
        stmt.executeUpdate()
    }
    if (delta != 0) {
        conn.prepareStatement(
            """
            WITH RECURSIVE sub(id) AS (
                SELECT ? UNION ALL SELECT c.id FROM category c JOIN sub ON c.parentId = sub.id
            )
            UPDATE category SET level = level + ? WHERE id IN (SELECT id FROM sub)
            """.trimIndent(),
        ).use { stmt ->
            stmt.setLong(1, sourceId)
            stmt.setInt(2, delta)
            stmt.executeUpdate()
        }
    }
    logger.i { "Moved category '${move.sourcePath}' (id=$sourceId) under '${move.destParentPath}' (parentId=$destParentId, level delta=$delta)" }
    return 1
}

private fun categoryParent(conn: Connection, categoryId: Long): Long? =
    conn.prepareStatement("SELECT parentId FROM category WHERE id = ?").use { stmt ->
        stmt.setLong(1, categoryId)
        stmt.executeQuery().use { rs ->
            check(rs.next()) { "Category id=$categoryId not found" }
            rs.getLong(1).let { if (rs.wasNull()) null else it }
        }
    }

/**
 * Updates the matching book's categoryId. Source path must fully exist; the
 * destination's leaf is created if missing (parents must exist).
 */
private fun applyBookMove(conn: Connection, move: BookMove, logger: Logger): Int {
    val sourceCatId = resolveCategoryPath(conn, move.sourcePath)
        ?: error("Book move source '${move.sourcePath}' not found for '${move.name}'")
    val destCatId = resolveOrCreateDestCategory(conn, move.destPath, logger)
        ?: error("Book move destination parent path '${move.destPath}' not found for '${move.name}' (only the final segment is auto-created)")

    val candidates = mutableListOf<Pair<Long, Long>>() // (bookId, categoryId)
    conn.prepareStatement("SELECT id, categoryId FROM book WHERE title = ?").use { stmt ->
        stmt.setString(1, move.name)
        stmt.executeQuery().use { rs ->
            while (rs.next()) candidates.add(rs.getLong(1) to rs.getLong(2))
        }
    }
    require(candidates.isNotEmpty()) { "Book move: '${move.name}' not found" }

    candidates.singleOrNull { it.second == destCatId }?.let { (bookId, _) ->
        logger.i { "Book move '${move.name}' already applied (id=$bookId, dest=${move.destPath})" }
        return 0
    }
    val sourceMatches = candidates.filter { it.second == sourceCatId }
    require(sourceMatches.size == 1) {
        "Book move '${move.name}' has ${candidates.size} candidate(s); source '${move.sourcePath}' matched ${sourceMatches.size}"
    }
    val bookId = sourceMatches.single().first

    conn.prepareStatement("UPDATE book SET categoryId = ? WHERE id = ?").use { stmt ->
        stmt.setLong(1, destCatId)
        stmt.setLong(2, bookId)
        stmt.executeUpdate()
    }
    logger.i { "Moved book '${move.name}' (id=$bookId) -> '${move.destPath}' (catId=$destCatId)" }
    return 1
}

/** Walks `a/b/c` from category roots; returns null on the first missing segment. */
private fun resolveCategoryPath(conn: Connection, path: String): Long? {
    val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null
    var parentId: Long? = null
    for (segment in segments) {
        parentId = findCategoryByNameAndParent(conn, segment, parentId) ?: return null
    }
    return parentId
}

/** Resolves the dest path, creating only a missing leaf; parents must exist (else null). */
private fun resolveOrCreateDestCategory(conn: Connection, path: String, logger: Logger): Long? {
    val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null
    var parentId: Long? = null
    for ((idx, segment) in segments.withIndex()) {
        val existing = findCategoryByNameAndParent(conn, segment, parentId)
        if (existing != null) {
            parentId = existing
        } else if (idx == segments.lastIndex) {
            parentId = createCategory(conn, segment, parentId, logger)
        } else {
            return null
        }
    }
    return parentId
}

/** Inserts a category under [parentId] (level = parent.level + 1) and returns its new id. */
private fun createCategory(conn: Connection, title: String, parentId: Long?, logger: Logger): Long {
    val level = if (parentId == null) 0 else categoryLevel(conn, parentId) + 1
    conn.prepareStatement(
        "INSERT INTO category (parentId, title, level) VALUES (?, ?, ?)",
        Statement.RETURN_GENERATED_KEYS,
    ).use { stmt ->
        if (parentId == null) stmt.setNull(1, java.sql.Types.INTEGER) else stmt.setLong(1, parentId)
        stmt.setString(2, title)
        stmt.setInt(3, level)
        stmt.executeUpdate()
        stmt.generatedKeys.use { rs ->
            require(rs.next()) { "Failed to create category '$title'" }
            val id = rs.getLong(1)
            logger.i { "Created category '$title' (id=$id, parentId=$parentId, level=$level)" }
            return id
        }
    }
}

private fun categoryLevel(conn: Connection, categoryId: Long): Int =
    conn.prepareStatement("SELECT level FROM category WHERE id = ?").use { stmt ->
        stmt.setLong(1, categoryId)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

/** Resolves the seforim.db path: positional arg, -PseforimDb, SEFORIM_DB, then build default. */
internal fun resolveSeforimDbPath(args: Array<String>): Path =
    Paths.get(
        args.getOrNull(0)
            ?: System.getProperty("seforimDb")
            ?: System.getenv("SEFORIM_DB")
            ?: Paths.get("build", "seforim.db").toString(),
    )

/**
 * Returns the lines of a required file (CSV or JSON) from the cached
 * `fordb-latest` release archive. Throws if the file is absent.
 */
internal fun downloadRequiredForDbFile(fileName: String, logger: Logger): List<String> =
    forDbReleaseFiles(logger).getValue(fileName)

private var cachedForDbFiles: Map<String, List<String>>? = null

private fun forDbReleaseFiles(logger: Logger): Map<String, List<String>> {
    cachedForDbFiles?.let { return it }

    val archiveUrl = forDbReleaseArchiveUrl(logger)
    logger.i { "Downloading ForDB release archive from $archiveUrl" }
    val files = try {
        OptimizedHttpClient.downloadStream(
            url = archiveUrl,
            userAgent = FOR_DB_USER_AGENT,
            logger = logger
        ).stream.use { stream ->
            readForDbZip(stream)
        }
    } catch (e: Exception) {
        logger.e(e) { "Failed to download or read required ForDB release archive; aborting" }
        throw IllegalStateException("Failed to load required ForDB release archive", e)
    }

    val missing = FOR_DB_CSV_FILES.values.filterNot { it in files }
    check(missing.isEmpty()) {
        "ForDB release archive is missing required file(s): ${missing.joinToString()}"
    }

    cachedForDbFiles = files
    return files
}

private fun forDbReleaseArchiveUrl(logger: Logger): String {
    val body = OptimizedHttpClient.fetchJson(FOR_DB_RELEASE_API, FOR_DB_USER_AGENT, logger)
    val assets = Json.parseToJsonElement(body).jsonObject.getValue("assets").jsonArray
    val asset = assets.firstOrNull {
        it.jsonObject["name"]?.jsonPrimitive?.content == FOR_DB_ARCHIVE_NAME
    } ?: throw IllegalStateException("No $FOR_DB_ARCHIVE_NAME asset found in fordb-latest release")
    return asset.jsonObject.getValue("url").jsonPrimitive.content
}

private fun readForDbZip(stream: InputStream): Map<String, List<String>> {
    val files = mutableMapOf<String, List<String>>()
    ZipInputStream(stream).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val normalizedName = entry.name.replace('\\', '/')
                if (normalizedName.startsWith("ForDB/")) {
                    val fileName = normalizedName.removePrefix("ForDB/")
                    // Capture text assets directly under ForDB/ — CSV rule files
                    // plus all_metadata.json (consumed by seedAllMetadata).
                    if ('/' !in fileName && (fileName.endsWith(".csv") || fileName.endsWith(".json"))) {
                        val bytes = ByteArrayOutputStream()
                        zip.copyTo(bytes)
                        files[fileName] = ByteArrayInputStream(bytes.toByteArray())
                            .bufferedReader(StandardCharsets.UTF_8)
                            .readLines()
                    }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return files
}
