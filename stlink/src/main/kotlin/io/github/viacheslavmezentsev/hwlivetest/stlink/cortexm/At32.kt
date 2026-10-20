package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

/**
 * Artery AT32. Регистр идентификатора — DEBUG_IDCODE (PID) по адресу 0xE0042000, как
 * DBGMCU у STM32, но это полный 32-битный код изделия, а не DEV_ID/REV_ID.
 * Размер Flash (КБ) — 16-битный регистр по адресу 0x1FFFF7E0, как у STM32F1.
 */
object At32 {
    const val DEBUG_IDCODE = 0xE0042000L
    const val FLASH_SIZE = 0x1FFFF7E0L

    /**
     * Эвристика: у STM32 поле REV_ID (биты 31:16) — небольшие значения 0x0000..0x3xxx,
     * а у известных AT32 старшая тетрада PID равна 0x5 или 0x7 (например 0x7005xxxx).
     * Проверяется на AT32F403A при аппаратном тестировании.
     */
    fun looksLikeAt32(pid: Int, core: CortexCore?): Boolean {
        val top = (pid ushr 28) and 0x0F
        val coreOk = core == null || core == CortexCore.M4 || core == CortexCore.M3 || core == CortexCore.M0_PLUS
        return coreOk && (top == 0x5 || top == 0x7)
    }
}
