package com.mediakit.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {

    @Test
    fun `illegal chars become underscores`() {
        assertEquals("a_b_c", Names.sanitize("a/b:c"))
        assertEquals("x_y", Names.sanitize("x?y"))
    }

    @Test
    fun `blank falls back`() {
        assertEquals("未命名", Names.sanitize("   "))
        assertEquals("未命名", Names.sanitize(""))
    }

    @Test
    fun `long name is truncated but keeps extension`() {
        val n = Names.sanitize("a".repeat(300) + ".mp4")
        assertEquals(100, n.length)
        assertEquals(".mp4", n.takeLast(4))
    }

    @Test
    fun `extension is lowercased`() {
        assertEquals("mkv", Names.ext("片子.MKV"))
        assertEquals("", Names.ext("没有扩展名"))
    }

    @Test
    fun `base drops the extension`() {
        assertEquals("夏天", Names.base("夏天.mp4"))
        assertEquals("a.b", Names.base("a.b.mp4"))
        assertEquals("未命名", Names.base(""))
        assertEquals("mp4", Names.base(".mp4"))
    }
}
