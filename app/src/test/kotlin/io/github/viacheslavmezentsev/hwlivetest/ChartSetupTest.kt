package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldKind
import org.junit.Assert.*
import org.junit.Test

class ChartSetupTest {
    private fun field(topic: String, address: Long = 0x20000000) = Field(topic, FieldKind.FLOAT32, address)


    @Test fun paletteCollisionsDoNotGiveSelectedChannelsTheSameColor() {
        val all = (0..12).map { field("/signal/$it") }
        val topics = listOf("/signal/0", "/signal/6", "/signal/12")
        val first = ChartSetup.colors(topics, all, emptyMap(), 6)
        assertEquals(3, first.values.toSet().size)
        assertEquals(first, ChartSetup.colors(topics.reversed(), all, first, 6))
        val added = ChartSetup.colors(topics + "/signal/3", all, first, 6)
        first.forEach { (topic, color) -> assertEquals(color, added[topic]) }
        assertEquals(4, added.values.toSet().size)
    }

    @Test fun tableIdentityIgnoresOrderingButChangesWithMemoryLayout() {
        val fields = listOf(field("/sensor/v"), field("/raw/v", 0x20000004))
        assertEquals(ChartSetup.key(fields), ChartSetup.key(fields.reversed()))
        assertNotEquals(ChartSetup.key(fields), ChartSetup.key(fields.map { it.copy(address = it.address + 4) }))
        assertNotEquals(ChartSetup.key(fields), ChartSetup.key(fields.map { it.copy(kind = FieldKind.UINT32) }))
    }

    @Test fun restoreDropsMissingAndDuplicateChannelsWithoutHidingRequestedSignal() {
        val fields = (0..7).map { field("/signal/$it") }
        assertEquals(listOf("/signal/1"), ChartSetup.topics(listOf("/gone", "/signal/1", "/signal/1"), fields, "/signal/1"))
        assertEquals(listOf("/signal/7"), ChartSetup.topics(listOf("/signal/1"), fields, "/signal/7"))
        assertEquals(6, ChartSetup.topics(fields.map { it.topic }, fields, null).size)
    }

    @Test fun duplicateNamesAndColorsRemainDistinctWhenChannelSelectionChanges() {
        val fields = listOf(field("/sensor/v"), field("/raw/v"), field("/sensor/t"))
        assertNotEquals(ChartSetup.label(fields[0], fields), ChartSetup.label(fields[1], fields))
        assertEquals(ChartSetup.colorIndex("/sensor/v", fields), ChartSetup.colorIndex("/sensor/v", fields.reversed()))
        assertEquals("t", ChartSetup.label(fields[2], fields))
    }
}
