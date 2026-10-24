package io.github.viacheslavmezentsev.hwlivetest.firmware

/** Как трактовать значение переменной. */
enum class SignalKind { FLOAT, SIGNED, UNSIGNED, BOOL, POINTER, ENUM }

/** Переменная или её часть (поле структуры, элемент массива) по отладочной информации ELF. */
data class Signal(val name: String, val address: Long, val size: Int, val kind: SignalKind, val type: String)

/**
 * Минимальный читатель DWARF 2–5 (32-битный формат): глобальные и статические переменные с
 * адресом DW_OP_addr/DW_OP_addrx, их типы, поля структур и элементы массивов — то, что в
 * mcu_power_board считает `gdb-py3` по выражениям вида `&sensors.cached_data.voltage_batt`
 * (ТЗ прошивки 5.9.2, р.1.4). Битовые поля пропускаются.
 */
class Dwarf private constructor(private val elf: ElfFile) {
    class Die(val offset: Int, val tag: Int, val attrs: Map<Int, Any>, val children: MutableList<Die> = ArrayList())

    private class Abbrev(val tag: Int, val hasChildren: Boolean, val specs: List<IntArray>, val consts: Map<Int, Long>)

    private val info = elf.sectionBytes(".debug_info") ?: ByteArray(0)
    private val abbrevSec = elf.sectionBytes(".debug_abbrev") ?: ByteArray(0)
    private val str = elf.sectionBytes(".debug_str") ?: ByteArray(0)
    private val lineStr = elf.sectionBytes(".debug_line_str") ?: ByteArray(0)
    private val strOffsets = elf.sectionBytes(".debug_str_offsets") ?: ByteArray(0)
    private val addrSec = elf.sectionBytes(".debug_addr") ?: ByteArray(0)

    private val dies = HashMap<Int, Die>()
    val units = ArrayList<Die>()
    val warnings = ArrayList<String>()

    init {
        var off = 0
        while (off + 11 <= info.size) {
            val len = u32(info, off)
            if (len == 0xFFFFFFFFL) {
                warnings += "64-битный DWARF не поддерживается"
                break
            }
            val end = off + 4 + len.toInt()
            if (end > info.size) break
            try {
                parseUnit(off, end)?.let { units += it }
            } catch (e: Exception) {
                warnings += "единица компиляции ${"0x%X".format(off)}: ${e.message}"
            }
            off = end
        }
    }

    private class Cursor(val buf: ByteArray, var pos: Int) {
        fun u8(): Int = buf[pos++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = (u16().toLong() or (u16().toLong() shl 16)) and 0xFFFFFFFFL
        fun uleb(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val b = u8()
                if (shift < 64) r = r or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) return r
            }
        }
        fun sleb(): Long {
            var r = 0L
            var shift = 0
            var b: Int
            do {
                b = u8()
                if (shift < 64) r = r or ((b and 0x7F).toLong() shl shift)
                shift += 7
            } while (b and 0x80 != 0)
            if (shift < 64 && b and 0x40 != 0) r = r or (-1L shl shift)
            return r
        }
        fun bytes(n: Int): ByteArray = buf.copyOfRange(pos, pos + n).also { pos += n }
        fun cstr(): String {
            val s = pos
            while (buf[pos] != 0.toByte()) pos++
            return String(buf, s, pos++ - s, Charsets.UTF_8)
        }
    }

    private fun parseUnit(start: Int, end: Int): Die? {
        val c = Cursor(info, start + 4)
        val version = c.u16()
        val abbrevOff: Long
        val addrSize: Int
        if (version >= 5) {
            val type = c.u8()
            addrSize = c.u8()
            abbrevOff = c.u32()
            when (type) {
                1, 3 -> Unit // compile, partial
                4, 5 -> c.pos += 8 // skeleton, split_compile: dwo_id
                else -> return null // type units не нужны
            }
        } else if (version in 2..4) {
            abbrevOff = c.u32()
            addrSize = c.u8()
        } else {
            warnings += "версия DWARF $version не поддерживается"
            return null
        }
        val abbrevs = readAbbrevs(abbrevOff.toInt())
        var strOffBase = 8L
        var addrBase = 8L
        val stack = ArrayList<Die>()
        var root: Die? = null
        while (c.pos < end) {
            val dieOff = c.pos
            val code = c.uleb().toInt()
            if (code == 0) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            val ab = abbrevs[code] ?: throw IllegalStateException("нет сокращения $code")
            val attrs = HashMap<Int, Any>()
            for ((i, spec) in ab.specs.withIndex()) {
                val v = readForm(c, spec[1], version, addrSize, start, ab.consts[i], strOffBase, addrBase)
                if (v != null) attrs[spec[0]] = v
            }
            val die = Die(dieOff, ab.tag, attrs)
            if (root == null) {
                root = die
                (attrs[DW_AT_str_offsets_base] as? Long)?.let { strOffBase = it }
                (attrs[DW_AT_addr_base] as? Long)?.let { addrBase = it }
            }
            dies[dieOff] = die
            stack.lastOrNull()?.children?.add(die)
            if (ab.hasChildren) stack += die
        }
        return root
    }

    private fun readAbbrevs(offset: Int): Map<Int, Abbrev> {
        val out = HashMap<Int, Abbrev>()
        val c = Cursor(abbrevSec, offset)
        while (c.pos < abbrevSec.size) {
            val code = c.uleb().toInt()
            if (code == 0) break
            val tag = c.uleb().toInt()
            val children = c.u8() != 0
            val specs = ArrayList<IntArray>()
            val consts = HashMap<Int, Long>()
            while (true) {
                val name = c.uleb().toInt()
                val form = c.uleb().toInt()
                if (name == 0 && form == 0) break
                if (form == FORM_implicit_const) consts[specs.size] = c.sleb()
                specs += intArrayOf(name, form)
            }
            out[code] = Abbrev(tag, children, specs, consts)
        }
        return out
    }

    /** Значение атрибута: Long (числа, ссылки — абсолютные смещения в .debug_info), String, ByteArray. */
    private fun readForm(c: Cursor, form: Int, version: Int, addrSize: Int, cu: Int, implicit: Long?, strOffBase: Long, addrBase: Long): Any? =
        when (form) {
            0x01 -> if (addrSize == 4) c.u32() else c.bytes(addrSize).let { b -> (0 until minOf(8, b.size)).fold(0L) { a, i -> a or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
            0x03 -> c.bytes(c.u16())
            0x04 -> c.bytes(c.u32().toInt())
            0x05 -> c.u16().toLong()
            0x06 -> c.u32()
            0x07 -> c.u32() or (c.u32() shl 32)
            0x08 -> c.cstr()
            0x09, 0x18 -> c.bytes(c.uleb().toInt())
            0x0a -> c.bytes(c.u8())
            0x0b -> c.u8().toLong()
            0x0c -> c.u8().toLong()
            0x0d -> c.sleb()
            0x0e -> cstrAt(str, c.u32().toInt())
            0x0f -> c.uleb()
            0x10 -> if (version <= 2) (if (addrSize == 4) c.u32() else c.bytes(addrSize).let { 0L }) else c.u32()
            0x11 -> cu + c.u8().toLong()
            0x12 -> cu + c.u16().toLong()
            0x13 -> cu + c.u32()
            0x14 -> cu + (c.u32() or (c.u32() shl 32))
            0x15 -> cu + c.uleb()
            0x16 -> readForm(c, c.uleb().toInt(), version, addrSize, cu, implicit, strOffBase, addrBase)
            0x17 -> c.u32()
            0x19 -> 1L
            0x1a -> strx(c.uleb(), strOffBase)
            0x1b -> addrx(c.uleb(), addrBase)
            0x1c, 0x1d -> c.u32().let { null }
            0x1e -> c.bytes(16)
            0x1f -> cstrAt(lineStr, c.u32().toInt())
            0x20 -> c.bytes(8).let { null }
            FORM_implicit_const -> implicit
            0x22, 0x23 -> c.uleb()
            0x24 -> c.bytes(8).let { null }
            0x25 -> strx(c.u8().toLong(), strOffBase)
            0x26 -> strx(c.u16().toLong(), strOffBase)
            0x27 -> strx((c.u16() or (c.u8() shl 16)).toLong(), strOffBase)
            0x28 -> strx(c.u32(), strOffBase)
            0x29 -> addrx(c.u8().toLong(), addrBase)
            0x2a -> addrx(c.u16().toLong(), addrBase)
            0x2b -> addrx((c.u16() or (c.u8() shl 16)).toLong(), addrBase)
            0x2c -> addrx(c.u32(), addrBase)
            else -> throw IllegalStateException("форма 0x%X не поддерживается".format(form))
        }

    private fun strx(index: Long, base: Long): String? {
        val o = (base + 4 * index).toInt()
        return if (o + 4 <= strOffsets.size) cstrAt(str, u32(strOffsets, o).toInt()) else null
    }

    private fun addrx(index: Long, base: Long): Long? {
        val o = (base + 4 * index).toInt()
        return if (o + 4 <= addrSec.size) u32(addrSec, o) else null
    }

    private var addrBaseForExpr = 8L

    // ---------- Переменные ----------

    /** Все переменные с постоянным адресом, развёрнутые до скалярных значений. */
    fun signals(maxArray: Int = MAX_ARRAY, limit: Int = MAX_SIGNALS): List<Signal> {
        val out = ArrayList<Signal>()
        val seen = HashSet<String>()
        for (cu in units) {
            addrBaseForExpr = (cu.attrs[DW_AT_addr_base] as? Long) ?: 8L
            for (d in cu.children) {
                if (d.tag != TAG_variable) continue
                val address = location(d) ?: continue
                val spec = (d.attrs[DW_AT_specification] as? Long)?.let { dies[it.toInt()] }
                val origin = (d.attrs[DW_AT_abstract_origin] as? Long)?.let { dies[it.toInt()] }
                val name = (d.attrs[DW_AT_name] ?: spec?.attrs?.get(DW_AT_name) ?: origin?.attrs?.get(DW_AT_name)) as? String ?: continue
                val type = (d.attrs[DW_AT_type] ?: spec?.attrs?.get(DW_AT_type) ?: origin?.attrs?.get(DW_AT_type)) as? Long ?: continue
                if (!seen.add("$name@$address")) continue
                flatten(name, address, type.toInt(), 0, maxArray, out, limit)
                if (out.size >= limit) {
                    warnings += "таблица обрезана до $limit значений"
                    return out
                }
            }
        }
        return out
    }

    private fun location(d: Die): Long? {
        val e = d.attrs[DW_AT_location] as? ByteArray ?: return null
        if (e.isEmpty()) return null
        return when (e[0].toInt() and 0xFF) {
            OP_addr -> if (e.size == 5) u32(e, 1) else null
            OP_addrx -> {
                val c = Cursor(e, 1)
                addrx(c.uleb(), addrBaseForExpr)?.takeIf { c.pos == e.size }
            }
            else -> null // регистры, стек, TLS — не постоянный адрес
        }
    }

    private fun die(off: Int): Die? = dies[off]

    private fun typeName(off: Int, depth: Int = 0): String {
        val t = die(off) ?: return "?"
        val sub = (t.attrs[DW_AT_type] as? Long)?.toInt()
        val name = t.attrs[DW_AT_name] as? String
        if (depth > 8) return name ?: "?"
        return when (t.tag) {
            TAG_typedef, TAG_base_type, TAG_structure_type, TAG_union_type, TAG_enumeration_type, TAG_class_type -> name ?: "(анонимный)"
            TAG_const_type -> "const " + (sub?.let { typeName(it, depth + 1) } ?: "void")
            TAG_volatile_type -> "volatile " + (sub?.let { typeName(it, depth + 1) } ?: "void")
            TAG_pointer_type -> (sub?.let { typeName(it, depth + 1) } ?: "void") + "*"
            TAG_array_type -> (sub?.let { typeName(it, depth + 1) } ?: "?") + "[]"
            else -> name ?: "?"
        }
    }

    /** Убрать typedef/const/volatile; вернуть DIE типа. */
    private fun strip(off: Int): Die? {
        var t = die(off) ?: return null
        var n = 0
        while (t.tag in TRANSPARENT && n++ < 16) {
            t = die(((t.attrs[DW_AT_type] as? Long) ?: return null).toInt()) ?: return null
        }
        return t
    }

    private fun sizeOf(off: Int): Int? {
        val t = strip(off) ?: return null
        (t.attrs[DW_AT_byte_size] as? Long)?.let { return it.toInt() }
        return when (t.tag) {
            TAG_pointer_type -> 4
            TAG_array_type -> {
                val elem = sizeOf(((t.attrs[DW_AT_type] as? Long) ?: return null).toInt()) ?: return null
                t.children.filter { it.tag == TAG_subrange_type }.fold(elem) { acc, s -> acc * (count(s) ?: return null) }
            }
            else -> null
        }
    }

    private fun count(sub: Die): Int? {
        (sub.attrs[DW_AT_count] as? Long)?.let { return it.toInt() }
        val ub = sub.attrs[DW_AT_upper_bound] as? Long ?: return null
        val lb = (sub.attrs[DW_AT_lower_bound] as? Long) ?: 0L
        return (ub - lb + 1).toInt()
    }

    private fun flatten(name: String, address: Long, typeOff: Int, depth: Int, maxArray: Int, out: MutableList<Signal>, limit: Int) {
        if (out.size >= limit || depth > MAX_DEPTH) return
        val shown = typeName(typeOff)
        val t = strip(typeOff) ?: return
        when (t.tag) {
            TAG_base_type -> {
                val size = (t.attrs[DW_AT_byte_size] as? Long)?.toInt() ?: return
                val kind = when ((t.attrs[DW_AT_encoding] as? Long)?.toInt()) {
                    ATE_float -> SignalKind.FLOAT
                    ATE_signed, ATE_signed_char -> SignalKind.SIGNED
                    ATE_boolean -> SignalKind.BOOL
                    else -> SignalKind.UNSIGNED
                }
                out += Signal(name, address, size, kind, shown)
            }
            TAG_enumeration_type -> {
                val size = (t.attrs[DW_AT_byte_size] as? Long)?.toInt() ?: 4
                out += Signal(name, address, size, SignalKind.ENUM, shown)
            }
            TAG_pointer_type -> out += Signal(name, address, (t.attrs[DW_AT_byte_size] as? Long)?.toInt() ?: 4, SignalKind.POINTER, shown)
            TAG_structure_type, TAG_union_type, TAG_class_type -> {
                for (m in t.children) {
                    if (m.tag != TAG_member) continue
                    if (m.attrs.containsKey(DW_AT_bit_size)) continue // битовые поля пропускаются
                    val mName = m.attrs[DW_AT_name] as? String
                    val mType = (m.attrs[DW_AT_type] as? Long)?.toInt() ?: continue
                    val off = memberOffset(m) ?: if (t.tag == TAG_union_type) 0L else continue
                    val full = if (mName == null) name else "$name.$mName"
                    flatten(full, address + off, mType, depth + 1, maxArray, out, limit)
                }
            }
            TAG_array_type -> {
                val elemType = (t.attrs[DW_AT_type] as? Long)?.toInt() ?: return
                val subs = t.children.filter { it.tag == TAG_subrange_type }
                val elemSize = sizeOf(elemType) ?: return
                if (subs.size != 1) {
                    // многомерный массив — только первый элемент целиком
                    val stride = subs.drop(1).fold(elemSize) { acc, s -> acc * (count(s) ?: 1) }
                    for (i in 0 until minOf(count(subs.firstOrNull() ?: return) ?: 0, maxArray)) {
                        flatten("$name[$i]", address + i.toLong() * stride, elemType, depth + 1, maxArray, out, limit)
                    }
                    return
                }
                val n = count(subs[0]) ?: return
                for (i in 0 until minOf(n, maxArray)) {
                    flatten("$name[$i]", address + i.toLong() * elemSize, elemType, depth + 1, maxArray, out, limit)
                }
            }
        }
    }

    private fun memberOffset(m: Die): Long? = when (val v = m.attrs[DW_AT_data_member_location]) {
        is Long -> v
        is ByteArray -> if (v.isNotEmpty() && v[0].toInt() and 0xFF == OP_plus_uconst) Cursor(v, 1).uleb() else null
        else -> null
    }

    private fun cstrAt(buf: ByteArray, o: Int): String? {
        if (o < 0 || o >= buf.size) return null
        var e = o
        while (e < buf.size && buf[e] != 0.toByte()) e++
        return String(buf, o, e - o, Charsets.UTF_8)
    }

    companion object {
        const val MAX_ARRAY = 8
        const val MAX_SIGNALS = 5000
        const val MAX_DEPTH = 8

        private const val FORM_implicit_const = 0x21
        private const val OP_addr = 0x03
        private const val OP_addrx = 0xA1
        private const val OP_plus_uconst = 0x23

        private const val TAG_array_type = 0x01
        private const val TAG_class_type = 0x02
        private const val TAG_enumeration_type = 0x04
        private const val TAG_member = 0x0d
        private const val TAG_pointer_type = 0x0f
        private const val TAG_structure_type = 0x13
        private const val TAG_typedef = 0x16
        private const val TAG_union_type = 0x17
        private const val TAG_subrange_type = 0x21
        private const val TAG_base_type = 0x24
        private const val TAG_const_type = 0x26
        private const val TAG_variable = 0x34
        private const val TAG_volatile_type = 0x35
        private const val TAG_restrict_type = 0x37
        private const val TAG_atomic_type = 0x47
        private val TRANSPARENT = setOf(TAG_typedef, TAG_const_type, TAG_volatile_type, TAG_restrict_type, TAG_atomic_type)

        private const val DW_AT_location = 0x02
        private const val DW_AT_name = 0x03
        private const val DW_AT_byte_size = 0x0b
        private const val DW_AT_bit_size = 0x0d
        private const val DW_AT_lower_bound = 0x22
        private const val DW_AT_upper_bound = 0x2f
        private const val DW_AT_abstract_origin = 0x31
        private const val DW_AT_count = 0x37
        private const val DW_AT_data_member_location = 0x38
        private const val DW_AT_encoding = 0x3e
        private const val DW_AT_specification = 0x47
        private const val DW_AT_type = 0x49
        private const val DW_AT_str_offsets_base = 0x72
        private const val DW_AT_addr_base = 0x73

        private const val ATE_boolean = 0x02
        private const val ATE_float = 0x04
        private const val ATE_signed = 0x05
        private const val ATE_signed_char = 0x06

        fun of(elf: ElfFile): Dwarf = Dwarf(elf)

        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))
    }
}
