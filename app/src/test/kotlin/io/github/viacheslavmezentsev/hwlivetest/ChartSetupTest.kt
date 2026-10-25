package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldKind
import org.junit.Assert.*
import org.junit.Test

class ChartSetupTest {
    private fun field(topic: String, address: Long = 0x20000000) = Field(topic, FieldKind.FLOAT32, address)

    @Test fun colorsFollowCheckOrderRatherThanTopicNames() {
        val selected = listOf("/sensor/z", "/raw/a", "/sensor/b")
        assertEquals(mapOf("/sensor/z" to 0, "/raw/a" to 1, "/sensor/b" to 2), ChartSetup.colors(selected))
        assertEquals(0xFF005FAF.toInt(), ChartSetup.PALETTE[0])
        assertEquals(0xFFAF0000.toInt(), ChartSetup.PALETTE[1])
        assertEquals(0xFF00875F.toInt(), ChartSetup.PALETTE[2])
    }

    @Test fun recheckingAppendsChannelAndRestoredOrderKeepsColors() {
        val selected = listOf("/z", "/a", "/b")
        val rechecked = selected.filter { it != "/a" } + "/a"
        assertEquals(mapOf("/z" to 0, "/b" to 1, "/a" to 2), ChartSetup.colors(rechecked))
        val restored = ChartSetup.topics(rechecked, rechecked.map { field(it) }, "/z")
        assertEquals(ChartSetup.colors(rechecked), ChartSetup.colors(restored))
        assertEquals((0..5).toList(), ChartSetup.colors((0..5).map { "/$it" }).values.toList())
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

    @Test fun duplicateNamesRemainDistinct() {
        val fields = listOf(field("/sensor/v"), field("/raw/v"), field("/sensor/t"))
        assertNotEquals(ChartSetup.label(fields[0], fields), ChartSetup.label(fields[1], fields))
        assertEquals("t", ChartSetup.label(fields[2], fields))
    }
}
