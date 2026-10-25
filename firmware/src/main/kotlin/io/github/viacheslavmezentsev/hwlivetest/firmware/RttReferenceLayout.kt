package io.github.viacheslavmezentsev.hwlivetest.firmware

/** ТЗ RTT 2.4. Layout of the measured 152-byte reference stub, not an installable image. */
object RttReferenceLayout {
    data class Layout(val bytes: Long, val vectors: Long, val bufferBytes: Int)
    fun calculate(base: Long, fields: Int, vectorBytes: Int): Layout {
        require(base in 0x20000000L..0x2001FFFCL && base % 4 == 0L)
        require(fields in 1..64 && vectorBytes in 268..1024 && vectorBytes % 4 == 0)
        val frame = 8 + 4 * fields
        val buffer = (4096 / frame) * frame
        var cursor = base
        fun place(size: Int, alignment: Int): Long {
            cursor = (cursor + alignment - 1) and (alignment.toLong() - 1).inv()
            val address = cursor
            cursor += size
            return address
        }
        place(48, 16); place(buffer, 16); place(16, 4); place(4 + 4 * fields, 4); place(152, 4)
        var alignment = 512
        while (alignment < vectorBytes) alignment *= 2
        val vectors = place(vectorBytes, alignment)
        return Layout(cursor - base, vectors, buffer)
    }
}
