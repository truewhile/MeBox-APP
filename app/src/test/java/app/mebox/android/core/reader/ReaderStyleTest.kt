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
    fun `merge into profile keeps fields owned by the web client`() {
        val base = ReaderProfileDto(
            audioSpeed = 1.5,
            audioTimerMinutes = 30,
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
            shelfSort = "name",
        )

        val merged = style.mergeInto(base)

        assertEquals("preset3", merged.themeId)
        assertTrue(merged.night)
        assertEquals(22, merged.fontSize)
        assertEquals(2.1, merged.lineHeight, 0.001)
        assertEquals(14, merged.paragraphSpacing)
        assertEquals("name", merged.shelfSort)
        assertEquals(1.5, merged.audioSpeed, 0.001)
        assertEquals(30, merged.audioTimerMinutes)
        assertEquals("list", merged.shelfLayout)
        assertEquals(3, merged.shelfGridColumns)
        assertEquals(false, merged.shelfShowUnread)
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
