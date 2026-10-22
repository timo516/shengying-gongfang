package com.mediakit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** 时间/尺寸格式化：这部分以前埋着 Locale 坑，靠单测钉住。 */
class FmtTest {

    @Test
    fun `parse plain seconds`() {
        assertEquals(90.0, Fmt.parseTime("90")!!, 1e-9)
        assertEquals(90.5, Fmt.parseTime("90.5")!!, 1e-9)
    }

    @Test
    fun `parse mm ss`() {
        assertEquals(90.0, Fmt.parseTime("1:30")!!, 1e-9)
        assertEquals(600.0, Fmt.parseTime("10:00")!!, 1e-9)
    }

    @Test
    fun `parse hh mm ss with fraction`() {
        assertEquals(90.5, Fmt.parseTime("00:01:30.5")!!, 1e-9)
        assertEquals(3661.0, Fmt.parseTime("1:01:01")!!, 1e-9)
    }

    @Test
    fun `reject out of range parts`() {
        // "1:70" 以前会被悄悄算成 130 秒，用户以为自己输错了地方
        assertNull(Fmt.parseTime("1:70"))
        assertNull(Fmt.parseTime("1:2:99"))
    }

    @Test
    fun `reject garbage`() {
        assertNull(Fmt.parseTime(""))
        assertNull(Fmt.parseTime("   "))
        assertNull(Fmt.parseTime("abc"))
        assertNull(Fmt.parseTime("1:2:3:4"))
        assertNull(Fmt.parseTime("-5"))
    }

    @Test
    fun `sec always uses a dot even on comma locales`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // 这条以前会产出 "1,500"，ffmpeg 直接报错
            assertEquals("1.500", Fmt.sec(1.5))
            assertEquals("30.000", Fmt.sec(30.0))
            assertEquals("1.500", Fmt.secMs(1_500))
            assertEquals("1.5 MB", Fmt.humanSize(1_572_864))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun `clock and duration text`() {
        assertEquals("0:12.3", Fmt.clock(12_300))
        assertEquals("12.3秒", Fmt.dur(12_300))
        assertEquals("1分05秒", Fmt.dur(65_000))
    }

    @Test
    fun `human size buckets`() {
        assertEquals("512 B", Fmt.humanSize(512))
        assertEquals("1 KB", Fmt.humanSize(1024))
        assertEquals("1.0 MB", Fmt.humanSize(1024L * 1024))
        assertEquals("1.00 GB", Fmt.humanSize(1024L * 1024 * 1024))
    }
}
