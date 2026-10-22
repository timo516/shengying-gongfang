package com.mediakit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这一组测试对应的都是真实踩过的坑（1.0.3 的「快速剪」在 mkv+字幕 / opus / wav 上必挂）。
 * 见 tools/make-fixtures.sh 生成的样本，以及 tools/smoke.sh 的真机/命令行回归。
 */
class FfmpegArgsTest {

    @Test
    fun `copy clip keeps the source container`() {
        assertEquals("mkv", FfmpegArgs.clipContainer("片子.MKV", precise = false))
        assertEquals("wav", FfmpegArgs.clipContainer("录音.wav", precise = false))
        assertEquals("mp4", FfmpegArgs.clipContainer("来源不明.xyz", precise = false))
        // 精确剪统一重编码进 mp4
        assertEquals("mp4", FfmpegArgs.clipContainer("片子.mkv", precise = true))
    }

    @Test
    fun `copy clip maps only the first video and audio stream`() {
        val a = FfmpegArgs.clip("/x/a.mkv", "/x/o.mkv", 1.5, 2.0, precise = false)
        assertTrue(a.containsAll(listOf("-c", "copy")))
        assertTrue(a.containsAll(listOf("-map", "0:v:0?")))
        assertTrue(a.containsAll(listOf("-map", "0:a:0?")))
        // -map 0 会把字幕/数据轨带进去，目标容器装不下就整条失败
        assertFalse(a.contains("0"))
        assertFalse(a.contains("-movflags"))
    }

    @Test
    fun `time arguments always use a dot`() {
        val a = FfmpegArgs.clip("/x/a.mp4", "/x/o.mp4", 1.5, 2.25, precise = false)
        // 用 indexOf 定位，不数下标：以后往参数里插一个新开关，这里不会假失败
        assertEquals("1.500", a[a.indexOf("-ss") + 1])
        assertEquals("2.250", a[a.indexOf("-t") + 1])
        assertTrue(a.indexOf("-ss") < a.indexOf("-i"))
        // 逗号小数点地区会产出 "1,500"
        assertFalse(a.any { it.contains(',') })
    }

    @Test
    fun `precise clip re-encodes with faststart`() {
        val a = FfmpegArgs.clip("/x/a.mp4", "/x/o.mp4", 0.0, 3.0, precise = true)
        assertTrue(a.containsAll(listOf("-c:v", "libx264")))
        assertTrue(a.containsAll(listOf("-movflags", "+faststart")))
        assertTrue(a.containsAll(listOf("-t", "3.000")))
        assertFalse(a.contains("copy"))
    }

    @Test
    fun `clip without start does not seek`() {
        val a = FfmpegArgs.clip("/x/a.mp4", "/x/o.mp4", 0.0, null, precise = false)
        assertFalse(a.contains("-ss"))
        assertFalse(a.contains("-t"))
    }

    @Test
    fun `extract picks encoder per target format`() {
        assertTrue(
            FfmpegArgs.extractEncode("/x/a.mp4", "/x/o.mp3", "mp3")
                .containsAll(listOf("-c:a", "libmp3lame", "-b:a", "192k"))
        )
        assertTrue(FfmpegArgs.extractEncode("/x/a.mp4", "/x/o.flac", "flac").containsAll(listOf("-c:a", "flac")))
        assertTrue(FfmpegArgs.extractEncode("/x/a.mp4", "/x/o.wav", "wav").containsAll(listOf("-c:a", "pcm_s16le")))
        assertTrue(FfmpegArgs.extractEncode("/x/a.mp4", "/x/o.m4a", "m4a").containsAll(listOf("-c:a", "aac")))
        // 无损拷贝不重编码
        assertTrue(FfmpegArgs.extractCopy("/x/a.mp4", "/x/o.m4a").containsAll(listOf("-c:a", "copy")))
    }

    @Test
    fun `concat filter has one atrim per kept segment`() {
        val f = FfmpegArgs.concatFilter(listOf(0L..1000L, 2000L..3000L))
        assertEquals(2, Regex("atrim=").findAll(f).count())
        assertTrue(f.contains("[0:a]atrim=start=0.000:end=1.000,asetpts=PTS-STARTPTS[a0]"))
        assertTrue(f.contains("[a0][a1]concat=n=2:v=0:a=1[out]"))
    }

    @Test
    fun `export one only adds -t when not to the end`() {
        val mid = FfmpegArgs.exportOne("/x/a.m4a", "/x/o.m4a", 1000, 2000, 5000, "m4a")
        assertTrue(mid.containsAll(listOf("-t", "1.000")))
        val tail = FfmpegArgs.exportOne("/x/a.m4a", "/x/o.m4a", 1000, 5000, 5000, "m4a")
        assertFalse(tail.contains("-t"))
    }

    @Test
    fun `mime follows the container`() {
        assertEquals("video/x-matroska", FfmpegArgs.mimeFor("mkv"))
        assertEquals("video/mp4", FfmpegArgs.mimeFor("mp4"))
        assertEquals("audio/wav", FfmpegArgs.mimeFor("wav"))
        assertEquals("application/octet-stream", FfmpegArgs.mimeFor("xyz"))
    }

    @Test
    fun `video and audio extensions are recognized`() {
        assertTrue(FfmpegArgs.isVideoExt("mkv"))
        assertTrue(FfmpegArgs.isAudioExt("flac"))
        assertFalse(FfmpegArgs.isVideoExt("flac"))
    }
}
