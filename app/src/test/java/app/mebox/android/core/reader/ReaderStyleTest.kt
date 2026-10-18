package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleTest {

    @Test
    fun `sanitize clamps typography and falls back to defaults`() {
        val style = ReaderStyle(
            themeId = "  ",
            fontSize = 99f,
            lineHeight = 9f,
            paragraphSpacing = -4,
            shelfSort = "unknown",
        ).sanitized()

        assertEquals("preset1", style.themeId)
        assertEquals(ReaderStyle.FONT_MAX, style.fontSize, 0f)
        assertEquals(ReaderStyle.LINE_MAX, style.lineHeight, 0f)
        assertEquals(0, style.paragraphSpacing)
        assertEquals("recent", style.shelfSort)
    }

    @Test
    fun `sanitize keeps supported values`() {
        val style = ReaderStyle(
            themeId = "preset2",
            night = true,
            fontSize = 20f,
            lineHeight = 2.0f,
            paragraphSpacing = 12,
            shelfSort = "update",
        ).sanitized()

        assertEquals(style, ReaderStyle(themeId = "preset2", night = true, fontSize = 20f, lineHeight = 2.0f, paragraphSpacing = 12, shelfSort = "update"))
    }

    @Test
    fun `merge into profile keeps audio fields and writes shelf settings`() {
        val base = ReaderProfileDto(
            audioSpeed = 1.5,
            audioTimerMinutes = 30,
            pageMode = "page",
            shelfLayout = "list",
            shelfGridColumns = 3,
            shelfShowUnread = false,
            shelfShowUpdateTime = false,
        )
        val style = ReaderStyle(
            themeId = "preset3",
            night = true,
            fontSize = 22f,
            lineHeight = 2.1f,
            paragraphSpacing = 14,
            shelfLayout = "compact",
            shelfGridColumns = 4,
            shelfSort = "name",
            shelfShowUnread = true,
            shelfShowUpdateTime = false,
        )

        val merged = style.mergeInto(base)

        assertEquals("preset3", merged.themeId)
        assertTrue(merged.night)
        assertEquals(22, merged.fontSize)
        assertEquals(2.1, merged.lineHeight, 0.001)
        assertEquals(14, merged.paragraphSpacing)
        assertEquals("name", merged.shelfSort)
        assertEquals("compact", merged.shelfLayout)
        assertEquals(4, merged.shelfGridColumns)
        assertEquals(true, merged.shelfShowUnread)
        assertEquals(false, merged.shelfShowUpdateTime)
        // 本客户端没暴露的字段保持服务端原值。
        assertEquals(1.5, merged.audioSpeed, 0.001)
        assertEquals(30, merged.audioTimerMinutes)
        assertEquals("page", merged.pageMode)
    }

    @Test
    fun `profile projection restores shelf settings`() {
        val style = ReaderStyle.fromProfile(
            ReaderProfileDto(
                shelfLayout = "list",
                shelfGridColumns = 5,
                shelfSort = "author",
                shelfShowUnread = false,
                shelfShowUpdateTime = false,
            ),
        )

        assertEquals("list", style.shelfLayout)
        assertEquals(5, style.shelfGridColumns)
        assertEquals("author", style.shelfSort)
        assertEquals(false, style.shelfShowUnread)
        assertEquals(false, style.shelfShowUpdateTime)
    }

    @Test
    fun `sanitize clamps shelf settings`() {
        val style = ReaderStyle(
            shelfLayout = "unknown",
            shelfGridColumns = 99,
            shelfShowUnread = false,
            shelfShowUpdateTime = false,
        ).sanitized()

        assertEquals(ReaderShelf.DEFAULT_LAYOUT, style.shelfLayout)
        assertEquals(ReaderStyle.GRID_COLUMNS_MAX, style.shelfGridColumns)
        assertEquals(false, style.shelfShowUnread)
        assertEquals(false, style.shelfShowUpdateTime)
    }

    @Test
    fun `profile projection is sanitized`() {
        val style = ReaderStyle.fromProfile(
            ReaderProfileDto(
                themeId = "",
                fontSize = 100,
                lineHeight = 0.1,
                paragraphSpacing = 200,
                shelfSort = "nope",
            ),
        )

        assertEquals("preset1", style.themeId)
        assertEquals(ReaderStyle.FONT_MAX, style.fontSize, 0f)
        assertEquals(ReaderStyle.LINE_MIN, style.lineHeight, 0f)
        assertEquals(ReaderStyle.SPACING_MAX, style.paragraphSpacing)
        assertEquals("recent", style.shelfSort)
    }

    @Test
    fun `line height rounds to one decimal when merged`() {
        val merged = ReaderStyle(lineHeight = 1.86f).mergeInto(ReaderProfileDto())
        assertEquals(1.9, merged.lineHeight, 0.0001)
    }
}
