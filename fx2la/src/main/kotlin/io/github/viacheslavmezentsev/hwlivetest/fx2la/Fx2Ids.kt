package io.github.viacheslavmezentsev.hwlivetest.fx2la

/**
 * Задел под логический анализатор на Cypress FX2 (WeAct LogicAnalyzerV1).
 *
 * Пока только идентификаторы USB; загрузка прошивки fx2lafw и захват появятся позже.
 */
object Fx2Ids {
    /** FX2 без прошивки (загрузчик в ПЗУ). */
    const val CYPRESS_VID = 0x04B4
    const val FX2_BARE_PID = 0x8613

    /** FX2 с загруженной открытой прошивкой fx2lafw (sigrok). */
    const val OPENMOKO_VID = 0x1D50
    const val FX2LAFW_PID = 0x608C

    fun isFx2(vid: Int, pid: Int): Boolean =
        (vid == CYPRESS_VID && pid == FX2_BARE_PID) || (vid == OPENMOKO_VID && pid == FX2LAFW_PID)
}
