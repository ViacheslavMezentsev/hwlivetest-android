package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class ActionConfirmationsTest {
    @Test fun quickCoreActionsStillRequireWriteMode() {
        for (action in listOf("Остановить ядро", "Запустить ядро", "Сброс с запуском", "Сброс с остановкой")) {
            assertTrue(ActionConfirmations.required(action, false, true))
            assertTrue(ActionConfirmations.required(action, true, false))
            assertFalse(ActionConfirmations.required(action, true, true))
        }
    }
    @Test fun destructiveAndUnknownActionsAlwaysAsk() {
        for (action in listOf("Прошить", "Стереть", "Полное стирание", "Импульс NRST", "запуск перед уходом", "Новая команда")) {
            assertTrue(ActionConfirmations.required(action, true, true))
        }
    }
}
