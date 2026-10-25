package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ RTT 2.13: free space for completing MCAP and exporting ZIP, plus reserve. */
object RttRecordingLimits {
    const val RESERVE = 64L * 1024 * 1024
    fun completionSpace(frames: Long, fields: Int): Long {
        require(frames >= 0 && fields in 1..6)
        // Raw already occupies disk. Reserve MCAP and a worst-case uncompressed archive.
        return Math.addExact(Math.multiplyExact(frames, (8 + 4 * fields) + 2L * (70 + 39 * fields)), RESERVE)
    }
}
