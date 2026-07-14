package io.github.kdroidfilter.seforimlibrary.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionTypeTest {

    @Test
    fun linkerOrdinalStableAndNamedTypesAppendAfter() {
        assertEquals(14, ConnectionType.LINKER.ordinal)
        val expected = listOf(
            ConnectionType.SIFREI_MITZVOT,
            ConnectionType.ESSAY,
            ConnectionType.ALLUSION,
            ConnectionType.LITURGY,
            ConnectionType.ELUCIDATION,
            ConnectionType.EXPLICATION,
            ConnectionType.LAW,
            ConnectionType.SUMMARY,
        )
        expected.forEachIndexed { i, t -> assertEquals(15 + i, t.ordinal) }
    }

    @Test
    fun fromStringRoundTripsEveryEnumName() {
        for (v in ConnectionType.values()) {
            assertEquals(v, ConnectionType.fromString(v.name))
        }
    }

    @Test
    fun fromKnownStringOrNullMapsRawSefariaValues() {
        assertEquals(ConnectionType.SIFREI_MITZVOT, ConnectionType.fromKnownStringOrNull("sifrei mitzvot"))
        assertEquals(ConnectionType.ESSAY, ConnectionType.fromKnownStringOrNull("essay"))
        assertEquals(ConnectionType.ALLUSION, ConnectionType.fromKnownStringOrNull("allusion"))
        assertEquals(ConnectionType.LITURGY, ConnectionType.fromKnownStringOrNull("liturgy"))
        assertEquals(ConnectionType.EXPLICATION, ConnectionType.fromKnownStringOrNull("explication"))
        assertEquals(ConnectionType.LAW, ConnectionType.fromKnownStringOrNull("law"))
        assertEquals(ConnectionType.SUMMARY, ConnectionType.fromKnownStringOrNull("summary"))
    }

    @Test
    fun fromKnownStringOrNullAcceptsBothElucidationSpellings() {
        assertEquals(ConnectionType.ELUCIDATION, ConnectionType.fromKnownStringOrNull("ellucidation"))
        assertEquals(ConnectionType.ELUCIDATION, ConnectionType.fromKnownStringOrNull("elucidation"))
    }

    @Test
    fun fromKnownStringOrNullMapsEmptyNoneOtherToOther() {
        assertEquals(ConnectionType.OTHER, ConnectionType.fromKnownStringOrNull(""))
        assertEquals(ConnectionType.OTHER, ConnectionType.fromKnownStringOrNull("none"))
        assertEquals(ConnectionType.OTHER, ConnectionType.fromKnownStringOrNull("other"))
    }

    @Test
    fun fromKnownStringOrNullReturnsNullForUnknown() {
        assertNull(ConnectionType.fromKnownStringOrNull("no_such_type"))
    }
}
