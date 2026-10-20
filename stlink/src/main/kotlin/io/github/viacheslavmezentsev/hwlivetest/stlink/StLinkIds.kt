package io.github.viacheslavmezentsev.hwlivetest.stlink

/** Поколение адаптера: определяет набор команд и конечные точки USB. */
enum class StLinkGeneration { V2, V2_1, V3 }

/** Известная модель ST-Link по VID:PID. */
data class StLinkModel(val pid: Int, val generation: StLinkGeneration, val title: String)

object StLinkIds {
    const val ST_VID = 0x0483

    val models: List<StLinkModel> = listOf(
        StLinkModel(0x3748, StLinkGeneration.V2, "ST-Link/V2"),
        StLinkModel(0x374B, StLinkGeneration.V2_1, "ST-Link/V2-1"),
        StLinkModel(0x3752, StLinkGeneration.V2_1, "ST-Link/V2-1 (без накопителя)"),
        StLinkModel(0x374E, StLinkGeneration.V3, "STLINK-V3E"),
        StLinkModel(0x374F, StLinkGeneration.V3, "STLINK-V3"),
        StLinkModel(0x3753, StLinkGeneration.V3, "STLINK-V3 (2 VCP)"),
        StLinkModel(0x3754, StLinkGeneration.V3, "STLINK-V3 (без накопителя)"),
    )

    fun find(vid: Int, pid: Int): StLinkModel? =
        if (vid != ST_VID) null else models.firstOrNull { it.pid == pid }

    /** Конечные точки по умолчанию (если дескриптор интерфейса не удалось разобрать). */
    fun defaultOutEndpoint(generation: StLinkGeneration): Int =
        if (generation == StLinkGeneration.V2) 0x02 else 0x01

    const val DEFAULT_IN_ENDPOINT = 0x81
}
