package com.mediakit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    @Test
    fun `merge overlapping ranges`() {
        assertEquals(listOf(0L..1000L), Timeline.merge(listOf(0L..500L, 400L..1000L)))
    }

    @Test
    fun `merge keeps disjoint ranges sorted`() {
        assertEquals(
            listOf(0L..100L, 200L..300L),
            Timeline.merge(listOf(200L..300L, 0L..100L))
        )
    }

    @Test
    fun `merge joins touching ranges`() {
        assertEquals(listOf(0L..200L), Timeline.merge(listOf(0L..100L, 100L..200L)))
    }

    @Test
    fun `kept with no deletion is the whole file`() {
        assertEquals(listOf(0L..1000L), Timeline.kept(emptyList(), 1000L))
    }

    @Test
    fun `kept splits around the deletion`() {
        assertEquals(listOf(0L..300L, 700L..1000L), Timeline.kept(listOf(300L..700L), 1000L))
    }

    @Test
    fun `kept drops fragments shorter than the minimum`() {
        // 只剩 20ms 的缝，导出时会被丢掉（否则拼接处是杂音）
        assertTrue(Timeline.kept(listOf(0L..490L, 510L..1000L), 1000L).isEmpty())
    }

    @Test
    fun `kept of zero duration is empty`() {
        assertTrue(Timeline.kept(emptyList(), 0L).isEmpty())
    }

    @Test
    fun `total sums segment lengths`() {
        assertEquals(300L, Timeline.total(listOf(0L..100L, 500L..700L)))
    }
}
