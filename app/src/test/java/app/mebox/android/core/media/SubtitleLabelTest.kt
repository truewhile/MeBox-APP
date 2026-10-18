package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLabelTest {

    @Test
    fun `language tags map to readable names`() {
        assertEquals("简体中文", SubtitleLabel.languageName("chs"))
        assertEquals("简体中文", SubtitleLabel.languageName("zh-Hans"))
        assertEquals("繁体中文", SubtitleLabel.languageName("cht"))
        assertEquals("中文", SubtitleLabel.languageName("chi"))
        assertEquals("日本語", SubtitleLabel.languageName("jpn"))
        assertEquals("English", SubtitleLabel.languageName("eng"))
        assertEquals("简体中文", SubtitleLabel.languageName("chs[BilibiliID_danmu]"))
        assertNull(SubtitleLabel.languageName("und"))
        assertNull(SubtitleLabel.languageName(""))
    }

    @Test
    fun `external ass subtitle gets a readable name`() {
        // 服务端对外挂 .chs.ass 只给 label=chs
        assertEquals(
            "简体中文（外挂） · ASS 样式",
            SubtitleLabel.display("chs", "chs", "external", assStyled = true),
        )
    }

    @Test
    fun `danmaku sidecar is marked`() {
        assertEquals(
            "简体中文 · 弹幕（外挂） · ASS 样式",
            SubtitleLabel.display(
                rawLabel = "chs[bilibiliid_danmu]",
                lang = "chs[bilibiliid_danmu]",
                source = "external",
                assStyled = true,
            ),
        )
        // 服务端已经标注过弹幕时不要重复追加。
        assertEquals(
            "简体中文 · 弹幕（外挂）",
            SubtitleLabel.display("简体中文 · 弹幕", "chs[bilibiliid_danmu]", "external", false),
        )
        assertTrue(SubtitleLabel.isDanmaku("xxx[BilibiliID_danmu].ass"))
        assertTrue(SubtitleLabel.isDanmaku("弹幕.ass"))
        assertFalse(SubtitleLabel.isDanmaku("chs"))
    }

    @Test
    fun `embedded subtitle keeps language marker`() {
        assertEquals(
            "中文（默认）（内嵌）",
            SubtitleLabel.display("chi（默认）", "chi", "embedded", assStyled = false),
        )
    }

    @Test
    fun `readable server label is kept as is`() {
        assertEquals(
            "简日双语（外挂）",
            SubtitleLabel.display("简日双语", "zh", "external", assStyled = false),
        )
    }

    @Test
    fun `unknown label falls back to the raw value`() {
        assertEquals("foo", SubtitleLabel.display("foo", "und", null, assStyled = false))
        assertEquals("字幕", SubtitleLabel.display("", "und", null, assStyled = false))
        // 未标注语言时不要显示裸的 und
        assertEquals("字幕（外挂）", SubtitleLabel.display("und", "und", "external", assStyled = false))
    }

    @Test
    fun `libvlc native track names are rewritten`() {
        assertEquals("内嵌字幕（中文）", SubtitleLabel.nativeTrackName("Track 1 - [Chinese]", 0))
        assertEquals("内嵌字幕（日本語）", SubtitleLabel.nativeTrackName("Track 2 - [Japanese]", 1))
        assertEquals("内嵌字幕 3", SubtitleLabel.nativeTrackName("Track 3", 2))
        assertEquals("内嵌字幕 1", SubtitleLabel.nativeTrackName(null, 0))
        // 不是 VLC 的编号命名时保持原样
        assertEquals("简体中文", SubtitleLabel.nativeTrackName("简体中文", 0))
    }
}
