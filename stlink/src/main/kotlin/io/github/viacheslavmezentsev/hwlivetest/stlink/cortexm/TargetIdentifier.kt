package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex12
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex16
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32

enum class McuVendor(val title: String) {
    STM32("STM32 (ST)"),
    STM32_COMPATIBLE("STM32-совместимый"),
    AT32("Artery AT32"),
    UNKNOWN("не опознан"),
}

data class TargetInfo(
    val cpuId: CpuId?,
    val romBase: Long?,
    val romBaseSource: String,
    val rom: ComponentId?,
    val idRegisterAddress: Long?,
    val idRegister: Int?,
    val vendor: McuVendor,
    val stm32Part: Stm32.Part?,
    val flashSizeAddress: Long?,
    val flashSizeKb: Int?,
    val notes: List<String>,
    val profileOverride: McuProfile? = null,
) {
    val profile: McuProfile get() = profileOverride ?: McuProfile.forTarget(this)

    /** Размер SRAM, если известен по справочнику (только STM32). */
    val sramKb: Int? get() = stm32Part?.sramKb

    /** Короткое имя для строки состояния: серия STM32 или семейство. */
    val shortName: String
        get() = stm32Part?.name ?: if (vendor == McuVendor.UNKNOWN) cpuId?.core?.title ?: vendor.title else vendor.title

    /** Строки для экрана и журнала: «параметр — значение». */
    fun summary(): List<Pair<String, String>> = buildList {
        cpuId?.let {
            add("Ядро" to "${it.coreName} ${it.revisionLabel} (${it.implementerName})")
            add("CPUID" to it.raw.hex32())
        } ?: add("Ядро" to "CPUID не прочитан")
        rom?.let {
            add("ROM-таблица" to "${romBase?.hex32()} ($romBaseSource)")
            add("Разработчик" to (it.designer.name ?: it.designer.label))
            add("ROM part" to "${it.partNo.hex12()} rev ${it.revision}")
        }
        add("Семейство" to vendor.title)
        add("Профиль" to (profile.title + if (profileOverride != null) " (выбран вручную)" else " (автоматически)"))
        if (idRegisterAddress != null && idRegister != null) {
            val label = if (vendor == McuVendor.AT32) "PID" else "DBGMCU"
            add(label to "${idRegister.hex32()} @ ${idRegisterAddress.hex32()}")
        }
        if (vendor == McuVendor.STM32 || vendor == McuVendor.STM32_COMPATIBLE) {
            val id = Stm32.IdCode(idRegister ?: 0)
            add("DEV_ID" to "${id.devId.hex12()} ${stm32Part?.name ?: "(нет в справочнике)"}")
            add("REV_ID" to id.revId.hex16())
        }
        flashSizeKb?.let { add("Flash" to "$it КБ") }
    }
}

/**
 * Опознание микроконтроллера Cortex-M только чтением: CPUID, ROM-таблица, DBGMCU/PID, F_SIZE.
 * Ошибки отдельных шагов не прерывают опознание, а попадают в [TargetInfo.notes].
 */
class TargetIdentifier(private val mem: DebugMemory) {

    fun identify(): TargetInfo {
        val notes = mutableListOf<String>()

        val cpuId = attempt(notes, "CPUID") { CpuId(mem.read32(CpuId.ADDRESS)) }
        val core = cpuId?.core

        var romBaseSource = "по умолчанию"
        var romBase: Long? = RomTable.DEFAULT_BASE
        val apBase = attempt(notes, "MEM-AP BASE") { mem.readApRegister(0, RomTable.AP_BASE_REGISTER) }
        if (apBase != null) {
            romBase = RomTable.baseFromApRegister(apBase)
            romBaseSource = "MEM-AP"
        }
        val rom = romBase?.let { base ->
            attempt(notes, "ROM-таблица") { ComponentId.read(mem, base) }
        }?.also {
            if (!it.isValid) notes += "CIDR ROM-таблицы не похож на CoreSight: ${it.cidr.joinToString { c -> "%02X".format(c) }}"
        }
        val romValid = rom?.isValid == true

        // DBGMCU_IDCODE / DEBUG_IDCODE
        var idAddress: Long? = null
        var idValue: Int? = null
        // ТЗ ST-Link 5.13: известный сторонний разработчик — только общие регистры.
        val foreignDesigner = romValid && rom?.designer?.name != null &&
            rom.designer != Jep106.ST && rom.designer != Jep106.ARM
        val candidates = if (foreignDesigner) emptyList() else Stm32.dbgmcuCandidates(core).filter {
            it >= 0xE0000000L || (romValid && rom?.designer == Jep106.ST)
        }
        for (addr in candidates) {
            val v = attempt(notes, "ID ${addr.hex32()}") { mem.read32(addr) } ?: continue
            if (v != 0 && v != -1) {
                idAddress = addr
                idValue = v
                break
            }
        }

        var vendor = McuVendor.UNKNOWN
        var part: Stm32.Part? = null
        var flashAddr: Long? = null
        if (idValue != null) {
            val stm = Stm32.IdCode(idValue)
            when {
                stm.looksLikeStm32 -> {
                    part = stm.part
                    flashAddr = part?.flashSizeAddress
                    vendor = if (!romValid || rom?.designer == Jep106.ST) McuVendor.STM32 else McuVendor.STM32_COMPATIBLE
                    if (vendor == McuVendor.STM32_COMPATIBLE) {
                        notes += "ROM-таблица от «${rom?.designer?.label}», а не ST: возможно, GD32/APM32/CKS32 или другой совместимый."
                    }
                }
                At32.looksLikeAt32(idValue, core) -> {
                    vendor = McuVendor.AT32
                    flashAddr = At32.FLASH_SIZE
                    notes += "AT32 опознан по формату PID (эвристика)."
                }
                else -> notes += "Регистр ${idAddress?.hex32()} = ${idValue.hex32()}: формат не опознан."
            }
        } else if (romValid && rom?.designer == Jep106.ST) {
            // Part ROM-таблицы у ST не всегда равен DEV_ID (у F411 это 0x411 при DEV_ID 0x431),
            // поэтому серию по нему не определяем.
            vendor = McuVendor.STM32
            notes += "DBGMCU_IDCODE не прочитан; по ROM-таблице это ST, серия не определена."
        }

        val flashKb = flashAddr?.let { addr ->
            attempt(notes, "F_SIZE ${addr.hex32()}") { Stm32.flashSizeKb(mem.read16(addr)) }
        }

        return TargetInfo(
            cpuId = cpuId,
            romBase = romBase,
            romBaseSource = romBaseSource,
            rom = rom,
            idRegisterAddress = idAddress,
            idRegister = idValue,
            vendor = vendor,
            stm32Part = part,
            flashSizeAddress = flashAddr,
            flashSizeKb = flashKb,
            notes = notes,
        )
    }

    private inline fun <T> attempt(notes: MutableList<String>, what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: StLinkException) {
            if (e.kind != StLinkErrorKind.SWD_FAULT && e.kind != StLinkErrorKind.BAD_ARGUMENT) throw e
            notes += "$what: ${e.message}"
            null
        }
}
