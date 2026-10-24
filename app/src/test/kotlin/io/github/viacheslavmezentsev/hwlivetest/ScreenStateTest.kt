package io.github.viacheslavmezentsev.hwlivetest

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.viacheslavmezentsev.hwlivetest.firmware.Segment
import org.junit.Assert.*
import org.junit.Test

/** ТЗ прошивки 5.10.2, 5.2.4: новый владелец экрана использует ту же операцию. */
class ScreenStateTest {
    @Test fun diagnosticsRetainsRunningStateAndCancelsWhenOwnerIsCleared() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        val first = ViewModelProvider(store, factory)[SimpleUi::class.java]
        first.running = true
        first.progress = 0.5f
        val recreated = ViewModelProvider(store, factory)[SimpleUi::class.java]
        assertSame(first, recreated)
        assertTrue(recreated.running)
        assertEquals(0.5f, recreated.progress, 0f)
        assertFalse(recreated.cancel)
        store.clear()
        assertTrue(recreated.cancel)
    }

    @Test fun flashRetainsReadOnlyModeFileAndOperationAcrossRecreation() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        val first = ViewModelProvider(store, factory)[FlashUi::class.java]
        first.readOnly = true
        first.busy = true
        first.file = "test.hex"
        first.segments = listOf(Segment(0x08000000, byteArrayOf(1, 2, 3, 4)))
        val recreated = ViewModelProvider(store, factory)[FlashUi::class.java]
        assertSame(first, recreated)
        assertTrue(recreated.readOnly)
        assertTrue(recreated.busy)
        assertEquals("test.hex", recreated.file)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), recreated.segments!!.single().data)
        store.clear()
        assertTrue(recreated.cancel)
    }
}
