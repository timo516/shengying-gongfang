package com.mediakit.app

/**
 * 选区/删除区间的运算：纯函数，可单测。
 * 之前这些逻辑是 Activity 的私有方法，只能上真机手点验证，现在抽出来配单测。
 */
object Timeline {

    /** 选区最短 300ms，再短两个手柄就叠在一起了 */
    const val MIN_SEL_MS = 300L

    /** 导出时短于这个长度的碎片直接丢掉 */
    const val MIN_KEEP_MS = 50L

    /** 波形采样桶数 */
    const val BUCKETS = 1600

    /** 合并重叠/相接的区间，返回升序结果 */
    fun merge(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val merged = ArrayList<LongRange>(ranges.size)
        for (r in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    /** 由"已删除区间"算出"保留区间" */
    fun kept(deleted: List<LongRange>, durationMs: Long, minKeepMs: Long = MIN_KEEP_MS): List<LongRange> {
        if (durationMs <= 0L) return emptyList()
        if (deleted.isEmpty()) return listOf(0L..durationMs)
        val kept = ArrayList<LongRange>()
        var cur = 0L
        for (r in deleted.sortedBy { it.first }) {
            if (r.first > cur) kept.add(cur..r.first)
            cur = maxOf(cur, r.last)
        }
        if (cur < durationMs) kept.add(cur..durationMs)
        return kept.filter { it.last - it.first >= minKeepMs }
    }

    fun total(ranges: List<LongRange>): Long = ranges.sumOf { it.last - it.first }
}
