package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings
import io.github.kdroidfilter.seforimlibrary.core.models.AltTocEntry
import io.github.kdroidfilter.seforimlibrary.core.models.AltTocStructure
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository

internal class SefariaAltTocBuilder(
    private val repository: SeforimRepository,
    @Suppress("unused") private val bindings: IdAllocatorBindings,
) {
    // NOTE: alt_toc_entry ids are still auto-allocated here. Their natural-key
    // wiring (DELTA_UPDATE_PLAN.md §3.3, `(structure_id, ancestor_path)`)
    // requires threading a deterministic path through the recursive traversal
    // (createContainerEntry / traverseAltNode / addEntry). Deferred to Phase 1.5
    // — alt-toc rows are rebuilt wholesale per book so unstable ids cost an
    // extra DELETE+INSERT batch but don't affect cross-book stability.
    suspend fun buildAltTocStructuresForBook(
        payload: BookPayload,
        bookId: Long,
        bookPath: String,
        lineKeyToId: Map<Pair<String, Int>, Long>,
        totalLines: Int
    ): Boolean {
        if (payload.altStructures.isEmpty()) {
            // These midrashim ship no alt-struct and their siman level is a
            // flattened leaf; synthesize a siman alt-TOC (alt_toc rows only).
            if (isSimanAltTocBook(payload)) {
                return buildSynthesizedSimanimAltToc(payload, bookId, bookPath, lineKeyToId, totalLines)
            }
            return false
        }

        var hasGeneratedAltStructures = false

        // Only Bavli tractates should suppress alt-struct child enumeration —
        // their main schema already renders daf-by-daf, so enumerating refs
        // under the alt-struct produces duplicates. Yerushalmi uses a
        // chapter×halakhah×segment main schema and Venice/Vilna alt-structs
        // that MUST be expanded to produce daf (column) headings.
        val isYerushalmi = payload.categoriesHe.any { it.contains("ירושלמי") }
        val isTalmudTractate = payload.categoriesHe.any { it.contains("תלמוד") } && !isYerushalmi
        val isShulchanArukhCode = payload.categoriesHe.any { it.contains("שולחן ערוך") }
        val isTurCode = payload.categoriesHe.any { it.contains("טור") }

        val refsForBook = payload.refEntries.map { it.copy(path = bookPath) }
        val bookAliasKeys = buildSet {
            val titles = listOf(
                payload.enTitle,
                payload.heTitle,
                sanitizeFolder(payload.enTitle),
                sanitizeFolder(payload.heTitle)
            )
            titles.forEach { title ->
                add(canonicalCitation(title))
                normalizeTitleKey(title)?.let { normalized ->
                    add(canonicalCitation(normalized))
                }
            }
        }.filterNot { it.isBlank() }.toSet()

        // Alt-struct refs sometimes use a non-primary title spelling listed in
        // the index titleVariants (e.g. "Messilat Yesharim" vs the primary
        // "Mesillat Yesharim"). An unrecognized book title fails canonical
        // matching and falls onto the bare-ordinal tail fallback, which then
        // collides with a same-numbered Introduction segment. Rewrite a known
        // alias prefix back to the primary title so the lookup hits the chapter.
        val canonicalEnTitle = canonicalCitation(payload.enTitle)
        val canonicalHeTitle = canonicalCitation(payload.heTitle)
        val titleAliasesCanonical = buildSet {
            payload.titleAliasKeys.forEach { add(canonicalCitation(it)) }
            addAll(bookAliasKeys)
        }.filterNot { it.isBlank() || it == canonicalEnTitle || it == canonicalHeTitle }
            .sortedByDescending { it.length }

        fun normalizeCitationBookTitle(raw: String): String {
            val canon = canonicalCitation(raw)
            val primary =
                if (canon.any { it in 'א'..'ת' }) canonicalHeTitle else canonicalEnTitle
            for (alias in titleAliasesCanonical) {
                if (canon == alias) return primary
                if (canon.startsWith("$alias ")) return primary + canon.substring(alias.length)
            }
            return raw
        }

        val canonicalToLine: Map<String, Pair<Long?, Int?>> = buildMap {
            refsForBook.forEach { entry ->
                val lineIdx = entry.lineIndex - 1
                val lineId = lineKeyToId[bookPath to lineIdx]
                val refsForEntry = listOfNotNull(entry.ref, entry.heRef)
                refsForEntry.forEach { value ->
                    val canonical = canonicalCitation(value)
                    fun addKey(key: String?) {
                        if (key.isNullOrBlank()) return
                        val current = this[key]?.second
                        if (current == null || (lineIdx in 0..<current)) {
                            put(key, lineId to lineIdx)
                        }
                    }
                    addKey(canonical)
                    addKey(stripBookAlias(canonical, bookAliasKeys))
                    addKey(canonicalTail(value))
                }
            }
        }
        val maxColonDepth = canonicalToLine.keys.maxOfOrNull { key -> key.count { it == ':' } } ?: 0

        payload.altStructures.forEach { structure ->
            val isPsalms30DayCycle = structure.key == "30 Day Cycle"
            val structureId = bindings.upsertAltTocStructureStable(
                AltTocStructure(
                    bookId = bookId,
                    key = structure.key,
                    title = structure.title,
                    heTitle = structure.heTitle
                )
            )

            val headingLineToToc = mutableMapOf<Int, Long>()
            val entriesByParent = mutableMapOf<Long?, MutableList<Long>>()
            val entryLineInfo = mutableMapOf<Long, Pair<Long?, Int?>>()
            val usedLineIdsByParent = mutableMapOf<Long?, MutableSet<Long>>()

            fun parseDafIndex(address: String?): Int? {
                if (address.isNullOrBlank()) return null
                val match = DAF_INDEX_REGEX.find(address.trim())
                val (pageStr, amudRaw) = match?.destructured ?: return null
                val page = pageStr.toIntOrNull() ?: return null
                val amud = amudRaw.lowercase()
                val offset = if (amud == "b") 2 else 1
                return ((page - 1) * 2) + offset
            }

            fun computeAddressValue(node: AltNodePayload, idx: Int): Int? {
                node.addresses.getOrNull(idx)?.let { return it }
                val skip = node.skippedAddresses.toSet()
                val base = node.offset
                    ?: parseDafIndex(node.startingAddress)?.minus(1)
                    ?: -1
                if (base < 0) return null
                var current = base
                var steps = idx
                while (steps >= 0) {
                    current += 1
                    if (current in skip) continue
                    steps--
                }
                return current
            }

            fun resolveLineForCitation(
                citation: String?,
                isChapterOrSimanLevel: Boolean,
                allowChapterFallback: Boolean = true,
                allowTailFallback: Boolean = true
            ): Pair<Long?, Int?> {
                if (citation.isNullOrBlank()) return null to null
                val normalizedCitation = normalizeCitationBookTitle(citation)

                fun expandedCandidates(base: String): List<String> {
                    if (base.isBlank() || maxColonDepth <= 0) return emptyList()
                    val colonCount = base.count { it == ':' }
                    if (colonCount >= maxColonDepth) return emptyList()
                    val expansions = mutableListOf<String>()
                    var current = base
                    repeat(maxColonDepth - colonCount) {
                        current += ":1"
                        expansions += current
                    }
                    return expansions
                }

                fun matchKey(key: String): Pair<Long?, Int?>? {
                    val variants = linkedSetOf(key).apply {
                        if (key.contains('.')) {
                            add(key.replace('.', ' '))
                            add(key.replace(DOTTED_INDEX_REGEX) { match -> ":${match.groupValues[1]}" })
                            add(key.replace(DOTTED_INDEX_REGEX) { match -> " ${match.groupValues[1]}" })
                            add(key.replace(".", ""))
                        }
                    }.filter { it.isNotBlank() }
                    variants.forEach { variant ->
                        canonicalToLine[variant]?.let { return it }
                        for (expanded in expandedCandidates(variant)) {
                            canonicalToLine[expanded]?.let { return it }
                        }
                    }
                    return null
                }

                fun fallbackWithinChapter(canonical: String): Pair<Long?, Int?>? {
                    if (!canonical.contains(':')) return null
                    val base = canonical.substringBefore(':')
                    val numStr = canonical.substringAfter(':').takeWhile { it.isDigit() }
                    val start = numStr.toIntOrNull() ?: return null
                    for (n in start downTo 1) {
                        val candidate = "$base:$n"
                        val candidates = listOf(candidate, stripBookAlias(candidate, bookAliasKeys))
                        candidates.forEach { key ->
                            if (key.isNotBlank()) {
                                matchKey(key)?.let { return it }
                            }
                        }
                    }
                    return null
                }

                fun lookup(raw: String): Pair<Long?, Int?>? {
                    val canonical = canonicalCitation(raw)
                    val stripped = stripBookAlias(canonical, bookAliasKeys)
                    val tail = canonicalTail(raw)
                    val candidates = buildList {
                        add(canonical)
                        add(stripped)
                        if (allowTailFallback) add(tail)
                    }
                    candidates.forEach { key ->
                        if (key.isNotBlank()) {
                            matchKey(key)?.let { return it }
                        }
                    }
                    val rangeStart = citationRangeStart(canonical)
                    if (rangeStart != null) {
                        val rangeCandidates = buildList {
                            add(rangeStart)
                            add(stripBookAlias(rangeStart, bookAliasKeys))
                            if (allowTailFallback) add(canonicalTail(rangeStart))
                        }
                        rangeCandidates.forEach { key ->
                            if (key.isNotBlank()) {
                                matchKey(key)?.let { return it }
                            }
                        }
                    }

                    if (allowChapterFallback) {
                        val chapterKey = canonical.substringBefore(':').takeIf { it.isNotBlank() }
                        if (chapterKey != null) {
                            val chapterStart = "$chapterKey:1"
                            val chapterCandidates = buildList {
                                add(chapterStart)
                                add(stripBookAlias(chapterStart, bookAliasKeys))
                                // Only use tail fallback if explicitly allowed
                                if (allowTailFallback) add(canonicalTail(chapterStart))
                                add(chapterKey)
                                add(stripBookAlias(chapterKey, bookAliasKeys))
                            }
                            chapterCandidates.forEach { key ->
                                if (key.isNotBlank()) {
                                    matchKey(key)?.let { return it }
                                }
                            }
                        }
                    }
                    fallbackWithinChapter(canonical)?.let { return it }
                    return null
                }

                lookup(normalizedCitation)?.let { return it }

                if (isChapterOrSimanLevel) {
                    val canonical = canonicalCitation(normalizedCitation)
                    val base = canonical.substringBefore('-').trim()
                    if (!base.contains(':')) {
                        val withColon = "$base:1"
                        lookup(withColon)?.let { return it }
                    }
                }

                return null to null
            }

            fun mapBaseToHebrew(base: String?): String? = mapSectionNameToHebrew(base)

            fun buildChildLabel(base: String?, idx: Int, addressValue: Int?, addressType: String?): String {
                val numericValue = (addressValue ?: (idx + 1)).coerceAtLeast(1)
                val hebBase = mapBaseToHebrew(base)
                if (hebBase == ALIYAH_SECTION_LABEL) {
                    aliyahOrdinalLabel(numericValue)?.let { return it }
                }
                val suffix = if (addressType.equals("Talmud", ignoreCase = true)) {
                    toDaf(numericValue)
                } else {
                    toGematria(numericValue)
                }
                val cleanBase = hebBase?.takeIf { it.isNotBlank() }
                return cleanBase?.let { "$it $suffix" } ?: suffix
            }

            suspend fun updateParentLineIfMissing(tocId: Long) {
                // Container entries (parents without their own refs) should NOT inherit
                // the lineId from their first child. This was causing duplicate headings
                // to appear when both parent and first child pointed to the same line.
                // The parent will remain with lineId = null, which means it won't appear
                // as a heading in the content, but will still be navigable via the TOC panel.
            }

            fun nodeLabel(node: AltNodePayload, position: Int?): String {
                if (!node.heTitle.isNullOrBlank()) return node.heTitle
                if (!node.title.isNullOrBlank()) return node.title

                val addressType = node.addressTypes.firstOrNull()
                val addrValue = computeAddressValue(node, 0)
                val base = mapBaseToHebrew(node.childLabel)
                    ?: if (addressType.equals("Talmud", ignoreCase = true)) "דף" else null
                if (base == ALIYAH_SECTION_LABEL) {
                    val aliyahIndex = addrValue ?: position?.plus(1) ?: 1
                    aliyahOrdinalLabel(aliyahIndex)?.let { return it }
                }
                val suffix = when {
                    addrValue != null && addressType.equals("Talmud", ignoreCase = true) -> toDaf(addrValue)
                    addrValue != null -> toGematria(addrValue)
                    position != null -> toGematria(position + 1)
                    else -> toGematria(1)
                }
                return base?.let { "$it $suffix" } ?: "פרק $suffix"
            }

            suspend fun addEntry(node: AltNodePayload, level: Int, parentId: Long?, position: Int?): Long {
                val isChapterOrSimanLevel = node.addressTypes.any {
                    it.equals("Siman", ignoreCase = true) ||
                            it.equals("Perek", ignoreCase = true) ||
                            it.equals("Chapter", ignoreCase = true) ||
                            it.equals("Integer", ignoreCase = true)
                }
                val isDafNode = node.addressTypes.any { it.equals("Talmud", ignoreCase = true) }

                val primaryCandidates = buildList {
                    node.wholeRef?.let { add(it) }
                    addAll(node.refs)
                }
                var lineId: Long? = null
                var lineIndex: Int? = null
                // Disable ALL fallbacks for multi-section books (Tur, Shulchan Arukh) to prevent
                // cross-section matches. If the exact citation doesn't match, return null.
                val isMultiSectionBook = isTurCode || isShulchanArukhCode
                for (candidate in primaryCandidates) {
                    val (lid, lidx) = resolveLineForCitation(
                        candidate,
                        isChapterOrSimanLevel,
                        allowChapterFallback = !isDafNode && !isMultiSectionBook,
                        allowTailFallback = !isDafNode && !isMultiSectionBook
                    )
                    if (lid != null && lidx != null) {
                        lineId = lid
                        lineIndex = lidx
                        break
                    }
                }
                if (lineId == null || lineIndex == null) return 0L
                val text = nodeLabel(node, position)

                val used = usedLineIdsByParent.getOrPut(parentId) { mutableSetOf() }
                if (lineId in used) return 0L
                used += lineId

                val tocId = repository.insertAltTocEntry(
                    AltTocEntry(
                        structureId = structureId,
                        parentId = parentId,
                        // Pre-resolve via IdAllocator so the tocText row gets
                        // inserted at the allocator-assigned id; otherwise the
                        // repo's getOrCreateTocText falls back to auto-increment
                        // and the delta producer's stable-id invariant breaks.
                        textId = bindings.upsertTocText(text),
                        text = text,
                        level = level,
                        lineId = lineId,
                        isLastChild = false,
                        hasChildren = false
                    )
                )
                hasGeneratedAltStructures = true
                entryLineInfo[tocId] = lineId to lineIndex
                entriesByParent.getOrPut(parentId) { mutableListOf() }.add(tocId)
                headingLineToToc[lineIndex] = tocId

                var hasChild = false
                if (!isTalmudTractate && !isShulchanArukhCode && !isTurCode && !isPsalms30DayCycle && node.refs.isNotEmpty()) {
                    for ((idx, ref) in node.refs.withIndex()) {
                        val (childLineId, childLineIndex) = resolveLineForCitation(
                            ref,
                            isChapterOrSimanLevel,
                            allowChapterFallback = !isDafNode && !isMultiSectionBook,
                            allowTailFallback = !isDafNode && !isMultiSectionBook
                        )
                        if (childLineId == null || childLineIndex == null) continue

                        val used = usedLineIdsByParent.getOrPut(tocId) { mutableSetOf() }
                        if (childLineId in used) continue
                        used += childLineId

                        val addressValue = computeAddressValue(node, idx)
                        val label = buildChildLabel(node.childLabel, idx, addressValue, node.addressTypes.firstOrNull())
                        val childTocId = repository.insertAltTocEntry(
                            AltTocEntry(
                                structureId = structureId,
                                parentId = tocId,
                                // Same allocator-routing as above so child labels
                                // get the stable id reserved by the allocator.
                                textId = bindings.upsertTocText(label),
                                text = label,
                                level = level + 1,
                                lineId = childLineId,
                                isLastChild = false,
                                hasChildren = false
                            )
                        )
                        hasGeneratedAltStructures = true
                        hasChild = true
                        entryLineInfo[childTocId] = childLineId to childLineIndex
                        entriesByParent.getOrPut(tocId) { mutableListOf() }.add(childTocId)
                        headingLineToToc[childLineIndex] = childTocId
                    }
                }

                if (hasChild) {
                    repository.updateAltTocEntryHasChildren(tocId, true)
                }

                return tocId
            }

            suspend fun createContainerEntry(node: AltNodePayload, level: Int, parentId: Long?, position: Int?): Long {
                val text = when {
                    !node.heTitle.isNullOrBlank() -> node.heTitle
                    position != null -> "פרק ${toGematria(position + 1)}"
                    !node.title.isNullOrBlank() -> node.title
                    !structure.heTitle.isNullOrBlank() -> structure.heTitle
                    !structure.title.isNullOrBlank() -> structure.title
                    else -> structure.key
                }
                val tocId = repository.insertAltTocEntry(
                    AltTocEntry(
                        structureId = structureId,
                        parentId = parentId,
                        // Pre-resolve via IdAllocator so the tocText row gets
                        // inserted at the allocator-assigned id; otherwise the
                        // repo's getOrCreateTocText falls back to auto-increment
                        // and the delta producer's stable-id invariant breaks.
                        textId = bindings.upsertTocText(text),
                        text = text,
                        level = level,
                        lineId = null,
                        isLastChild = false,
                        hasChildren = false
                    )
                )
                entryLineInfo[tocId] = null to null
                entriesByParent.getOrPut(parentId) { mutableListOf() }.add(tocId)
                return tocId
            }

            suspend fun traverseAltNode(node: AltNodePayload, level: Int, parentId: Long?, position: Int?): Boolean {
                val hasOwnRefs = node.wholeRef != null || node.refs.isNotEmpty()
                val hasTitle = !node.heTitle.isNullOrBlank() || !node.title.isNullOrBlank()
                val isDafNode = node.addressTypes.any { it.equals("Talmud", ignoreCase = true) }
                val inlineChildrenOnly = isDafNode && node.refs.isNotEmpty() && !hasTitle
                var currentParent = parentId
                var containerId: Long? = null
                var inserted = false

                if (!hasOwnRefs && node.children.isNotEmpty() && hasTitle) {
                    containerId = createContainerEntry(node, level, parentId, position)
                    currentParent = containerId
                }

                if (inlineChildrenOnly) {
                    node.refs.forEachIndexed { idx, ref ->
                        val (childLineId, childLineIndex) = resolveLineForCitation(
                            ref,
                            isChapterOrSimanLevel = false,
                            allowChapterFallback = false,
                            allowTailFallback = false
                        )
                        if (childLineId == null || childLineIndex == null) return@forEachIndexed
                        val addressValue = computeAddressValue(node, idx)
                        val label = buildChildLabel(node.childLabel, idx, addressValue, node.addressTypes.firstOrNull())
                        if (childLineId in usedLineIdsByParent.getOrPut(currentParent) { mutableSetOf() }) return@forEachIndexed
                        usedLineIdsByParent.getOrPut(currentParent) { mutableSetOf() } += childLineId

                        val childId = repository.insertAltTocEntry(
                            AltTocEntry(
                                structureId = structureId,
                                parentId = currentParent,
                                // Same allocator-routing as above so child labels
                                // get the stable id reserved by the allocator.
                                textId = bindings.upsertTocText(label),
                                text = label,
                                level = level,
                                lineId = childLineId,
                                isLastChild = false,
                                hasChildren = false
                            )
                        )
                        hasGeneratedAltStructures = true
                        inserted = true
                        entryLineInfo[childId] = childLineId to childLineIndex
                        entriesByParent.getOrPut(currentParent) { mutableListOf() }.add(childId)
                        headingLineToToc[childLineIndex] = childId
                    }
                } else if (hasOwnRefs) {
                    val tocId = addEntry(node, level, parentId, position)
                    if (tocId != 0L) {
                        entriesByParent.getOrPut(parentId) { mutableListOf() }.add(tocId)
                        inserted = true
                        if (node.children.isNotEmpty()) {
                            currentParent = tocId
                        }
                    }
                }

                var childInserted = false
                if (node.children.isNotEmpty()) {
                    val childLevel = level + if (currentParent != null && currentParent != parentId) 1 else 0
                    node.children.forEachIndexed { idx, child ->
                        if (traverseAltNode(child, childLevel, currentParent, idx)) {
                            childInserted = true
                        }
                    }
                    if (currentParent == containerId && childInserted) {
                        repository.updateAltTocEntryHasChildren(containerId!!, true)
                    } else if (currentParent != null && currentParent != parentId && childInserted) {
                        repository.updateAltTocEntryHasChildren(currentParent, true)
                    }
                }

                if (containerId != null) {
                    val hasChildren = entriesByParent[containerId].orEmpty().isNotEmpty()
                    if (hasChildren) {
                        repository.updateAltTocEntryHasChildren(containerId, true)
                        updateParentLineIfMissing(containerId)
                        if (entryLineInfo[containerId]?.second != null) {
                            hasGeneratedAltStructures = true
                            inserted = true
                        }
                    } else {
                        repository.executeRawQuery("DELETE FROM alt_toc_entry WHERE id=$containerId")
                        entriesByParent[parentId]?.remove(containerId)
                        entryLineInfo.remove(containerId)
                    }
                }

                return inserted || childInserted
            }

            structure.nodes.forEachIndexed { idx, node ->
                traverseAltNode(node, level = 0, parentId = null, position = idx)
            }

            for ((_, children) in entriesByParent) {
                if (children.isNotEmpty()) {
                    val lastChildId = children.last()
                    repository.updateAltTocEntryIsLastChild(lastChildId, true)
                }
            }

            val sortedKeys = headingLineToToc.keys.sorted()
            for (lineIdx in 0 until totalLines) {
                val key = sortedKeys.lastOrNull { it <= lineIdx } ?: continue
                val tocId = headingLineToToc[key] ?: continue
                val lineId = lineKeyToId[bookPath to lineIdx] ?: continue
                repository.upsertLineAltToc(lineId, structureId, tocId)
            }
        }
        return hasGeneratedAltStructures
    }

    /** Curated midrashim whose innermost siman level is flattened; exact title. */
    private fun isSimanAltTocBook(payload: BookPayload): Boolean {
        normalizeTitleKey(payload.enTitle)?.let { if (it in SIMAN_ALTTOC_EN_KEYS) return true }
        normalizeTitleKey(payload.heTitle)?.let { if (it in SIMAN_ALTTOC_HE_KEYS) return true }
        return false
    }

    /** Mirror the parasha heading tree as alt-TOC containers and hang each siman
     *  leaf under its parasha, labelled by ordinal gematria. Writes alt_toc only. */
    private suspend fun buildSynthesizedSimanimAltToc(
        payload: BookPayload,
        bookId: Long,
        bookPath: String,
        lineKeyToId: Map<Pair<String, Int>, Long>,
        totalLines: Int
    ): Boolean {
        // Phase 1: resolve to lines; bail if there is no siman to add.
        val sectionHeadings = payload.headings
            .filter { it.level >= 1 } // drop the book-title <h1> (level 0)
            .sortedBy { it.lineIndex }
        if (sectionHeadings.isEmpty() || payload.refEntries.isEmpty()) return false

        // Ascending list of heading line indices whose lines actually exist.
        val headingLines = sectionHeadings
            .filter { lineKeyToId.containsKey(bookPath to it.lineIndex) }
            .map { it.lineIndex }
            .sorted()
        if (headingLines.isEmpty()) return false

        // Each leaf → (its 0-based lineIndex, the nearest preceding heading line).
        val leaves = payload.refEntries
            .sortedBy { it.lineIndex }
            .mapNotNull { entry ->
                val lineIndex0 = entry.lineIndex - 1
                if (!lineKeyToId.containsKey(bookPath to lineIndex0)) return@mapNotNull null
                val parentLine = headingLines.lastOrNull { it <= lineIndex0 } ?: return@mapNotNull null
                if (parentLine == lineIndex0) return@mapNotNull null // headings carry no leaf ref
                lineIndex0 to parentLine
            }
        if (leaves.isEmpty()) return false

        // Phase 2: write the structure, the heading mirror, then the simanim.
        val structureId = bindings.upsertAltTocStructureStable(
            AltTocStructure(
                bookId = bookId,
                key = SIMANIM_STRUCTURE_KEY,
                title = SIMANIM_STRUCTURE_TITLE_EN,
                heTitle = SIMANIM_STRUCTURE_TITLE_HE
            )
        )

        val headingByLine = HashMap<Int, Pair<Long, Int>>()          // headingLine -> (tocId, altLevel)
        val childrenByParent = LinkedHashMap<Long?, MutableList<Long>>()
        val lineToTocId = HashMap<Int, Long>()                       // any owning line -> its tocId
        val stack = ArrayDeque<Triple<Int, Long, Int>>()             // (headingLevel, tocId, altLevel)

        for (h in sectionHeadings) {
            val lineId = lineKeyToId[bookPath to h.lineIndex] ?: continue
            while (stack.isNotEmpty() && stack.last().first >= h.level) stack.removeLast()
            val parentId = stack.lastOrNull()?.second
            val altLevel = stack.size
            val tocId = repository.insertAltTocEntry(
                AltTocEntry(
                    structureId = structureId,
                    parentId = parentId,
                    textId = bindings.upsertTocText(h.title),
                    text = h.title,
                    level = altLevel,
                    lineId = lineId,
                    isLastChild = false,
                    hasChildren = false
                )
            )
            stack.addLast(Triple(h.level, tocId, altLevel))
            headingByLine[h.lineIndex] = tocId to altLevel
            childrenByParent.getOrPut(parentId) { mutableListOf() }.add(tocId)
            lineToTocId[h.lineIndex] = tocId
        }

        val simanOrdinalByParent = HashMap<Long, Int>()
        for ((lineIndex0, parentLine) in leaves) {
            val lineId = lineKeyToId[bookPath to lineIndex0] ?: continue
            val (parentTocId, parentAltLevel) = headingByLine[parentLine] ?: continue
            val ordinal = (simanOrdinalByParent[parentTocId] ?: 0) + 1
            simanOrdinalByParent[parentTocId] = ordinal
            val label = toGematria(ordinal)
            val childTocId = repository.insertAltTocEntry(
                AltTocEntry(
                    structureId = structureId,
                    parentId = parentTocId,
                    textId = bindings.upsertTocText(label),
                    text = label,
                    level = parentAltLevel + 1,
                    lineId = lineId,
                    isLastChild = false,
                    hasChildren = false
                )
            )
            childrenByParent.getOrPut(parentTocId) { mutableListOf() }.add(childTocId)
            lineToTocId[lineIndex0] = childTocId
        }

        // hasChildren + isLastChild bookkeeping per sibling group.
        for ((parentId, children) in childrenByParent) {
            if (children.isEmpty()) continue
            if (parentId != null) repository.updateAltTocEntryHasChildren(parentId, true)
            repository.updateAltTocEntryIsLastChild(children.last(), true)
        }

        // line_alt_toc: map every content line to its nearest preceding entry.
        val ownerLines = lineToTocId.keys.sorted()
        var oi = 0
        var currentTocId: Long? = null
        for (lineIdx in 0 until totalLines) {
            while (oi < ownerLines.size && ownerLines[oi] <= lineIdx) {
                currentTocId = lineToTocId[ownerLines[oi]]
                oi++
            }
            val tocId = currentTocId ?: continue
            val lineId = lineKeyToId[bookPath to lineIdx] ?: continue
            repository.upsertLineAltToc(lineId, structureId, tocId)
        }
        return true
    }

    companion object {
        // Lift these out of the hot loop — regex compile is non-trivial and
        // these were being created per call to parseDafIndex / per key
        // replace (called millions of times for the alt-TOC builder).
        private val DAF_INDEX_REGEX = Regex("(\\d+)([ab])?", RegexOption.IGNORE_CASE)
        private val DOTTED_INDEX_REGEX = Regex("\\.(\\d+)")

        // Synthetic siman-level alt-TOC for curated aggadic midrashim.
        private const val SIMANIM_STRUCTURE_KEY = "Simanim"
        private const val SIMANIM_STRUCTURE_TITLE_EN = "Simanim"
        private const val SIMANIM_STRUCTURE_TITLE_HE = "סימנים"

        // Curated aggadic midrashim: 10 Midrash Rabbah books + Pesikta DeRav
        // Kahana, Midrash Shmuel, Midrash Mishlei (structures vary; builder adapts).
        private val SIMAN_ALTTOC_EN_KEYS: Set<String> = listOf(
            "Bereishit Rabbah", "Shemot Rabbah", "Vayikra Rabbah",
            "Bamidbar Rabbah", "Devarim Rabbah", "Ruth Rabbah",
            "Eichah Rabbah", "Esther Rabbah", "Kohelet Rabbah",
            "Shir HaShirim Rabbah",
            "Pesikta DeRav Kahana", "Midrash Shmuel", "Midrash Mishlei",
        ).mapNotNull { normalizeTitleKey(it) }.toSet()

        private val SIMAN_ALTTOC_HE_KEYS: Set<String> = listOf(
            "בראשית רבה", "שמות רבה", "ויקרא רבה", "במדבר רבה", "דברים רבה",
            "רות רבה", "איכה רבה", "אסתר רבה", "קוהלת רבה", "שיר השירים רבה",
            "פסיקתא דרב כהנא", "מדרש שמואל", "מדרש משלי",
        ).mapNotNull { normalizeTitleKey(it) }.toSet()
    }
}
