package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.math.BigDecimal
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes

internal object ManualLinksJson {
    val factory: JsonFactory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()

    val mapper: ObjectMapper = ObjectMapper(factory)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)

    fun readStrict(path: Path): JsonNode = factory.createParser(path.toFile()).use { parser ->
        val node = mapper.readTree<JsonNode>(parser) ?: error("Empty JSON: $path")
        require(parser.nextToken() == null) { "Trailing JSON token in $path" }
        node
    }

    fun canonicalBytes(node: JsonNode): ByteArray = (canonicalString(node) + "\n").toByteArray(Charsets.UTF_8)

    fun canonicalString(node: JsonNode): String = buildString { appendCanonical(node) }

    fun stableHash(node: JsonNode): String = sha256(canonicalString(node).toByteArray(Charsets.UTF_8))

    private fun StringBuilder.appendCanonical(node: JsonNode) {
        when {
            node.isObject -> {
                append('{')
                val keys = node.fieldNames().asSequence().toList().sortedWith(::compareCodePoints)
                keys.forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(mapper.writeValueAsString(key)).append(':')
                    appendCanonical(node.get(key))
                }
                append('}')
            }
            node.isArray -> {
                append('[')
                node.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendCanonical(child)
                }
                append(']')
            }
            node.isTextual -> append(mapper.writeValueAsString(node.textValue()))
            node.isBoolean -> append(if (node.booleanValue()) "true" else "false")
            node.isNull -> append("null")
            node.isNumber -> {
                val decimal = node.decimalValue()
                require(decimal.signum() != 0 || !node.asText().startsWith('-')) { "negative zero is forbidden" }
                val normalized = decimal.stripTrailingZeros()
                append(if (normalized.scale() <= 0) normalized.toBigIntegerExact().toString() else normalized.toPlainString())
            }
            else -> error("Unsupported JSON node: ${node.nodeType}")
        }
    }

    private fun compareCodePoints(left: String, right: String): Int {
        val leftPoints = left.codePoints().toArray()
        val rightPoints = right.codePoints().toArray()
        val common = minOf(leftPoints.size, rightPoints.size)
        for (index in 0 until common) {
            val compared = leftPoints[index].compareTo(rightPoints[index])
            if (compared != 0) return compared
        }
        return leftPoints.size.compareTo(rightPoints.size)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun rawSha256(path: Path): String = sha256(path.readBytes())
}

internal data class JsonScalarSpan(
    val fieldStart: Int,
    val valueStart: Int,
    val valueEnd: Int,
)

/** Lossless editor for the flat array-of-objects manual-link format. */
internal class ManualLinksDocument private constructor(
    val source: String,
    val records: ArrayNode,
    private val spans: List<Map<String, JsonScalarSpan>>,
) {
    private val edits = LinkedHashMap<Int, LinkedHashMap<String, JsonNode>>()

    val changed: Boolean get() = edits.isNotEmpty()

    fun record(index: Int): ObjectNode = records[index] as ObjectNode

    fun stableRecordHash(index: Int): String = ManualLinksJson.stableHash(record(index))

    fun setString(index: Int, field: String, value: String) = set(index, field, TextNode(value))

    fun setInt(index: Int, field: String, value: Int) = set(index, field, ManualLinksJson.mapper.nodeFactory.numberNode(value))

    private fun set(index: Int, field: String, value: JsonNode) {
        val record = record(index)
        if (record.has(field) &&
            ManualLinksJson.canonicalString(record.get(field)) == ManualLinksJson.canonicalString(value)
        ) return
        if (!record.has(field) && field !in setOf("ref_1", "ref_2", "anchor_src_hash")) {
            error("Refusing to insert unsupported field '$field' at record $index")
        }
        record.set<JsonNode>(field, value)
        edits.getOrPut(index) { LinkedHashMap() }[field] = value
    }

    fun render(): String {
        if (!changed) return source
        val replacements = ArrayList<Replacement>()
        edits.forEach { (recordIndex, fields) ->
            val recordSpans = spans[recordIndex]
            val missing = fields.filterKeys { it !in recordSpans }
            fields.filterKeys { it in recordSpans }.forEach { (field, value) ->
                val span = recordSpans.getValue(field)
                replacements += Replacement(span.valueStart, span.valueEnd, ManualLinksJson.mapper.writeValueAsString(value))
            }

            val pending = LinkedHashMap(missing)
            fun insertAfter(anchor: String, names: List<String>) {
                val values = names.mapNotNull { name -> pending.remove(name)?.let { name to it } }
                if (values.isEmpty()) return
                val anchorSpan = recordSpans[anchor]
                    ?: error("Cannot insert ${values.map { it.first }}: missing anchor $anchor")
                val multiline = source.indexOf('\n', anchorSpan.fieldStart).let { it >= 0 && it < recordSpans.values.maxOf { s -> s.valueEnd } }
                val indent = if (multiline) {
                    val lineStart = source.lastIndexOf('\n', anchorSpan.fieldStart).let { if (it < 0) 0 else it + 1 }
                    source.substring(lineStart, anchorSpan.fieldStart).takeWhile { it == ' ' || it == '\t' }
                } else ""
                val separator = if (multiline) "\n$indent" else " "
                val insertion = values.joinToString(separator = ",$separator", prefix = ",$separator") { (name, value) ->
                    "${ManualLinksJson.mapper.writeValueAsString(name)}: ${ManualLinksJson.mapper.writeValueAsString(value)}"
                }
                replacements += Replacement(anchorSpan.valueEnd, anchorSpan.valueEnd, insertion)
            }
            if ("ref_1" in pending) insertAfter("line_index_1", listOf("ref_1", "anchor_src_hash"))
            if ("anchor_src_hash" in pending) {
                val refSpan = recordSpans["ref_1"] ?: error("anchor_src_hash requires existing or inserted ref_1")
                insertAfterAtSpan(
                    refSpan,
                    recordSpans.values.maxOf { it.valueEnd },
                    pending.remove("anchor_src_hash")!!,
                    "anchor_src_hash",
                    replacements,
                )
            }
            if ("ref_2" in pending) insertAfter("line_index_2", listOf("ref_2"))
            check(pending.isEmpty()) { "Unapplied fields: ${pending.keys}" }
        }

        replacements.sortByDescending { it.start }
        replacements.zipWithNext().forEach { (higher, lower) ->
            require(lower.end <= higher.start) { "Overlapping lossless JSON edits" }
        }
        val output = StringBuilder(source)
        replacements.forEach { output.replace(it.start, it.end, it.value) }
        val rendered = output.toString()
        val reparsed = runCatching { parse(rendered, "rendered output") }.getOrElse { cause ->
            throw IllegalStateException("Lossless patch produced invalid JSON: $rendered", cause)
        }
        require(ManualLinksJson.canonicalString(reparsed.records) == ManualLinksJson.canonicalString(records)) {
            "Lossless patch semantic verification failed; rendered=$rendered expected=$records actual=${reparsed.records}"
        }
        return rendered
    }

    private fun insertAfterAtSpan(
        span: JsonScalarSpan,
        recordScalarEnd: Int,
        value: JsonNode,
        name: String,
        replacements: MutableList<Replacement>,
    ) {
        val lineStart = source.lastIndexOf('\n', span.fieldStart).let { if (it < 0) 0 else it + 1 }
        val indent = source.substring(lineStart, span.fieldStart).takeWhile { it == ' ' || it == '\t' }
        val multiline = source.indexOf('\n', span.fieldStart).let { it >= 0 && it < recordScalarEnd }
        val separator = if (multiline) "\n$indent" else " "
        replacements += Replacement(
            span.valueEnd,
            span.valueEnd,
            ",$separator${ManualLinksJson.mapper.writeValueAsString(name)}: ${ManualLinksJson.mapper.writeValueAsString(value)}",
        )
    }

    private data class Replacement(val start: Int, val end: Int, val value: String)

    companion object {
        fun read(path: Path): ManualLinksDocument {
            val bytes = Files.readAllBytes(path)
            require(bytes.size < 3 || !(bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte())) {
                "UTF-8 BOM is forbidden: $path"
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            require('\r' !in text) { "CRLF/CR is forbidden: $path" }
            val trailing = text.reversed().takeWhile { it == '\n' }.length
            require(trailing <= 1) { "More than one trailing LF: $path" }
            return parse(text, path.toString())
        }

        fun parse(text: String, sourceName: String = "input"): ManualLinksDocument {
            val root = ManualLinksJson.mapper.readTree(text)
            require(root is ArrayNode) { "$sourceName must contain a JSON array" }
            val recordSpans = ArrayList<Map<String, JsonScalarSpan>>()
            ManualLinksJson.factory.createParser(text).use { parser ->
                require(parser.nextToken() == JsonToken.START_ARRAY) { "$sourceName must contain an array" }
                var recordIndex = 0
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    require(parser.currentToken == JsonToken.START_OBJECT) { "$sourceName[$recordIndex] must be an object" }
                    val fields = LinkedHashMap<String, JsonScalarSpan>()
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        require(parser.currentToken == JsonToken.FIELD_NAME) { "Expected field in $sourceName[$recordIndex]" }
                        val name = parser.currentName
                        val fieldStart = parser.currentTokenLocation().charOffset.toInt()
                        val token = parser.nextToken()
                        val valueStart = parser.currentTokenLocation().charOffset.toInt()
                        if (token.isNumeric && parser.text.matches(Regex("-0(?:\\.0+)?(?:[eE][+-]?0+)?"))) {
                            error("negative zero is forbidden in $sourceName[$recordIndex].$name")
                        }
                        parser.skipChildren()
                        val valueEnd = if (token.isScalarValue) scalarValueEnd(text, valueStart) else -1
                        if (token.isScalarValue) fields[name] = JsonScalarSpan(fieldStart, valueStart, valueEnd)
                    }
                    recordSpans += fields
                    recordIndex++
                }
                require(parser.nextToken() == null) { "Trailing token in $sourceName" }
            }
            require(recordSpans.size == root.size()) { "Record span mismatch in $sourceName" }
            root.forEachIndexed { index, node ->
                require(node is ObjectNode) { "$sourceName[$index] must be an object" }
                validateRecord(node, "$sourceName[$index]")
            }
            return ManualLinksDocument(text, root, recordSpans)
        }

        private fun validateRecord(record: ObjectNode, location: String) {
            requirePositiveInt(record, "line_index_1", location)
            requireNonBlankText(record, "heRef_2", location)
            requireNonBlankText(record, "path_2", location)
            requirePositiveInt(record, "line_index_2", location)
            record.get("start")?.let { exactInt(it, "$location.start", allowZero = true) }
        }

        internal fun exactInt(node: JsonNode, location: String, allowZero: Boolean = false): Int {
            require(node.isNumber) { "$location must be a JSON number" }
            val value = try {
                val decimal = BigDecimal(node.asText())
                require(decimal.stripTrailingZeros().scale() <= 0) { "$location must be integral" }
                decimal.intValueExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("$location is outside Int range", e)
            }
            require(if (allowZero) value >= 0 else value >= 1) { "$location is out of range" }
            return value
        }

        private fun requirePositiveInt(record: ObjectNode, field: String, location: String) {
            exactInt(record.get(field) ?: error("Missing $location.$field"), "$location.$field")
        }

        private fun requireNonBlankText(record: ObjectNode, field: String, location: String) {
            val value = record.get(field)
            require(value?.isTextual == true && value.textValue().isNotBlank()) { "$location.$field must be non-blank text" }
        }

        private fun scalarValueEnd(source: String, start: Int): Int {
            require(start in source.indices) { "Scalar token starts outside source" }
            if (source[start] == '"') {
                var escaped = false
                var index = start + 1
                while (index < source.length) {
                    val char = source[index]
                    if (!escaped && char == '"') return index + 1
                    escaped = !escaped && char == '\\'
                    if (char != '\\') escaped = false
                    index++
                }
                error("Unterminated JSON string scalar")
            }
            var index = start
            while (index < source.length && source[index] !in charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')) index++
            require(index > start) { "Empty JSON scalar" }
            return index
        }
    }
}
