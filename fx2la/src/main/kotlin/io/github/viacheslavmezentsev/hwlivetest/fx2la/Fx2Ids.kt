package io.github.viacheslavmezentsev.hwlivetest.fx2la

/** Известный прибор на FX2: VID:PID и имя файла прошивки fx2lafw для него. */
data class Fx2Profile(
    val vid: Int,
    val pid: Int,
    val title: String,
    /** Имя файла из пакета sigrok-firmware-fx2lafw. */
    val firmwareName: String,
) {
    val vidPid: String get() = "%04X:%04X".format(vid, pid)
}

/**
 * Логические анализаторы на Cypress FX2 (ТЗ ЛА 3.1.1). VID:PID у WeAct LogicAnalyzerV1 —
 * `0925:3881` (как у Saleae Logic) и после загрузки fx2lafw не меняется.
 */
object Fx2Ids {
    const val CYPRESS_VID = 0x04B4
    const val FX2_BARE_PID = 0x8613
    const val OPENMOKO_VID = 0x1D50
    const val FX2LAFW_PID = 0x608C
    const val SALEAE_VID = 0x0925
    const val SALEAE_LOGIC_PID = 0x3881

    // ТЗ ЛА 3.1.2: таблица — данные, логика от неё не зависит.
    val profiles: List<Fx2Profile> = listOf(
        Fx2Profile(SALEAE_VID, SALEAE_LOGIC_PID, "WeAct LogicAnalyzerV1 / Saleae Logic", "fx2lafw-saleae-logic.fw"),
        Fx2Profile(OPENMOKO_VID, FX2LAFW_PID, "sigrok FX2 LA (8 каналов)", "fx2lafw-sigrok-fx2-8ch.fw"),
        Fx2Profile(CYPRESS_VID, FX2_BARE_PID, "Cypress FX2 без прошивки", "fx2lafw-cypress-fx2.fw"),
    )

    fun find(vid: Int, pid: Int): Fx2Profile? = profiles.firstOrNull { it.vid == vid && it.pid == pid }

    fun isFx2(vid: Int, pid: Int): Boolean = find(vid, pid) != null

    /** Строки дескриптора, которые выставляет загруженная fx2lafw (ТЗ ЛА 3.1.3). */
    const val FX2LAFW_MANUFACTURER = "sigrok"
    const val FX2LAFW_PRODUCT = "fx2lafw"
}
