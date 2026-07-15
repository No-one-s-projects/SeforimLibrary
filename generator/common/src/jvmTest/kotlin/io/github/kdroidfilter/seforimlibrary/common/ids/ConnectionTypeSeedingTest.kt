package io.github.kdroidfilter.seforimlibrary.common.ids

import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import org.junit.Test
import kotlin.test.assertEquals

class ConnectionTypeSeedingTest {

    @Test
    fun `fresh allocator seeds connection type ids as ordinal plus one`() {
        // Mirrors the generators' seeding loop (ConnectionType.values().forEach { upsert }):
        // ids are handed out in declaration order, so id == ordinal + 1 must hold.
        val allocator = InMemoryIdAllocator.load(path = null)
        for (type in ConnectionType.values()) {
            assertEquals(
                (type.ordinal + 1).toLong(),
                allocator.connectionTypeId(type.name),
                "seeded id for ${type.name} must equal ordinal + 1",
            )
        }
    }
}
