package io.github.viacheslavmezentsev.hwlivetest.firmware

import java.util.Locale

/**
 * Таблица сигналов из ELF: переменные в ОЗУ с адресами, пригодные для чтения на ходу, и отпечатки,
 * без которых адреса не имеют смысла (ТЗ прошивки 5.9, р.1.4):
 * - [elfSha256] — сам файл ELF (как `elf_sha256` в addresses.json mcu_power_board);
 * - [imageSha256] — образ для прошивки (загружаемые сегменты); таблица применима к цели, только
 *   если Flash цели совпадает с этим образом (сверка или прошивка этим ELF).
 */
class SignalTable(
    val elfName: String,
    val elfSha256: String,
    val image: List<Segment>,
    val signals: List<Signal>,
    val skipped: Int,
    val warnings: List<String>,
) {
    val imageSha256: String = IntelHex.sha256(image.fold(ByteArray(0)) { acc, s -> acc + s.data })

    /** Поле addresses.json: адрес выровнен на слово, у 1- и 2-байтовых значений — сдвиг и маска. */
    data class Entry(val signal: Signal, val word: Long, val shift: Int?, val mask: Long?, val kind: String, val message: String)

    val entries: List<Entry> = signals.mapNotNull { entry(it) }

    // ТЗ прошивки 5.9.6: разные static-переменные не теряются при совпадении имён.
    private val topicCounts = entries.groupingBy { topic(it.signal.name, it.signal.sourceFile) }.eachCount()
    fun topicFor(e: Entry): String {
        val base = topic(e.signal.name, e.signal.sourceFile)
        return if (topicCounts.getValue(base) > 1) base + "_at_" + e.signal.address.toString(16) else base
    }

    /** addresses.json, schema 2 (контракт mcu_power_board) с добавочными ключами `image_sha256`, `source`. */
    fun toAddressesJson(): String = buildString {
        append("{\n")
        append("  \"schema\": 2,\n")
        append("  \"elf\": \"").append(HistoryEntry.escape(elfName)).append("\",\n")
        append("  \"elf_sha256\": \"").append(elfSha256).append("\",\n")
        append("  \"image_sha256\": \"").append(imageSha256).append("\",\n")
        append("  \"svd\": \"\",\n")
        append("  \"source\": \"HW Live Test: ELF/DWARF\",\n")
        append("  \"fields\": [")
        entries.forEachIndexed { i, e ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"topic\": \"").append(HistoryEntry.escape(topicFor(e))).append("\"")
            append(", \"message\": \"").append(e.message).append("\"")
            append(", \"kind\": \"").append(e.kind).append("\"")
            append(", \"address\": ").append(e.word)
            append(", \"address_hex\": \"").append("0x%08X".format(e.word)).append("\"")
            append(", \"expr\": \"").append(HistoryEntry.escape(e.signal.name)).append("\"")
            append(", \"source_type\": \"").append(HistoryEntry.escape(e.signal.type)).append("\"")
            append(", \"source_file\": \"").append(HistoryEntry.escape(e.signal.sourceFile)).append("\"")
            append(", \"size_bits\": ").append(e.signal.size * 8)
            e.shift?.let { append(", \"shift\": ").append(it) }
            e.mask?.let { append(", \"mask\": ").append(it) }
            append(", \"description\": \"").append(HistoryEntry.escape(e.signal.type)).append("\"}")
        }
        append(if (entries.isEmpty()) "]\n" else "\n  ]\n")
        append("}\n")
    }

    fun summary(): List<String> = buildList {
        add("ELF: $elfName")
        add("SHA-256 ELF: $elfSha256")
        add("SHA-256 образа: $imageSha256")
        add("Сигналов: ${entries.size}" + if (skipped > 0) ", пропущено $skipped (8 байт, невыровненные, вне ОЗУ)" else "")
    }

    companion object {
        /** ОЗУ, где живут переменные: SRAM (0x2000_0000–0x3FFF_FFFF) и CCM/DTCM (0x1000_0000–0x1FFF_FFFF). */
        fun inRam(address: Long): Boolean = address in 0x10000000L..0x3FFFFFFFL

        fun topic(name: String, source: String = ""): String {
            fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9_/]"), "_")
            val group = source.takeIf { it.isNotBlank() }?.let { clean(it) + "/" } ?: ""
            return "/elf/" + group + clean(name.replace('.', '/'))
        }

        fun fromElf(bytes: ByteArray, name: String): SignalTable {
            val elf = ElfFile.parse(bytes)
            val image = elf.loadSegments()
            val dwarf = Dwarf.of(elf)
            val all = dwarf.signals()
            val warnings = ArrayList(dwarf.warnings)
            if (elf.section(".debug_info") == null) warnings += "в ELF нет отладочной информации (.debug_info): соберите с -g"
            if (elf.machine != ElfFile.EM_ARM) warnings += "ELF не для ARM (e_machine ${elf.machine})"
            val ram = all.filter { inRam(it.address) }
            val table = SignalTable(name, IntelHex.sha256(bytes), image, ram, 0, warnings)
            val skipped = all.size - table.entries.size
            return SignalTable(name, table.elfSha256, image, ram, skipped, warnings)
        }

        private fun entry(s: Signal): Entry? {
            if (!inRam(s.address)) return null
            val (kind, message) = when (s.kind) {
                SignalKind.FLOAT -> if (s.size == 4) "float32" to "std_msgs/Float32" else return null
                SignalKind.SIGNED -> "int32" to "std_msgs/Int32"
                else -> "uint32" to "std_msgs/UInt32"
            }
            return when (s.size) {
                4 -> if (s.address % 4 == 0L) Entry(s, s.address, null, null, kind, message) else null
                1, 2 -> {
                    val word = s.address and 3L.inv()
                    val shift = ((s.address - word) * 8).toInt()
                    if (shift + s.size * 8 > 32) return null
                    val mask = ((1L shl (s.size * 8)) - 1) shl shift
                    Entry(s, word, shift, mask, kind, message)
                }
                else -> null
            }
        }

        fun describe(s: Signal): String = "%-32s 0x%08X %d %s".format(Locale.ROOT, s.name, s.address, s.size, s.type)
    }
}
