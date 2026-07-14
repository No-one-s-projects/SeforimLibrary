package io.github.kdroidfilter.seforimlibrary.common.patch

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract guard against the Dart `seforim_library_updater` package.
 *
 * Serializes [PATCH_TABLES_IN_FK_ORDER], [LogicalContentHasher.DEFAULT_TABLES]
 * and [PatchDbSchema.CURRENT_VERSION] to a canonical JSON form and compares it
 * to a committed fixture. The identical fixture lives in the updater repo
 * (`test/patch_tables_contract.json`), where its own test asserts the Dart
 * lists produce byte-identical output — so the two table specs cannot drift.
 *
 * Canonical rules: fixed key order, FK/hash order preserved (no sorting),
 * UTF-8, trailing newline.
 */
class PatchTablesContractTest {

    private fun canonicalContract(
        fkOrder: List<PatchTable>,
        hashOrder: List<String>,
        schemaVersion: Int,
    ): String {
        val b = StringBuilder()
        b.append("{\n")
        b.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n")
        b.append("  \"fkOrder\": [\n")
        for ((i, t) in fkOrder.withIndex()) {
            val pk = t.primaryKey.joinToString(", ") { "\"$it\"" }
            b.append("    { \"table\": \"").append(t.name)
                .append("\", \"pk\": [").append(pk)
                .append("], \"updatable\": ").append(t.updatable).append(" }")
            if (i != fkOrder.lastIndex) b.append(",")
            b.append("\n")
        }
        b.append("  ],\n")
        b.append("  \"hashOrder\": [\n")
        for ((i, name) in hashOrder.withIndex()) {
            b.append("    \"").append(name).append("\"")
            if (i != hashOrder.lastIndex) b.append(",")
            b.append("\n")
        }
        b.append("  ]\n")
        b.append("}\n")
        return b.toString()
    }

    @Test
    fun `canonical serialization matches committed fixture`() {
        val expected = javaClass.getResourceAsStream("/patch_tables_contract.json")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture patch_tables_contract.json missing from test resources")
        val actual = canonicalContract(
            PATCH_TABLES_IN_FK_ORDER,
            LogicalContentHasher.DEFAULT_TABLES,
            PatchDbSchema.CURRENT_VERSION,
        )
        assertEquals(expected, actual)
    }
}
