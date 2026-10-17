package app.mebox.android.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextTest {

    @Test
    fun `parse keeps text lines and turns image markers into image paragraphs`() {
        val content = "第一段\n\n  第二段  \n[img]/api/reader/media?b=1&u=x\n[img]\n"

        val paragraphs = ReaderText.parse(content)

        assertEquals(3, paragraphs.size)
        assertEquals("第一段", paragraphs[0].text)
        assertEquals("第二段", paragraphs[1].text)
        assertTrue(paragraphs[2].isImage)
        assertEquals("/api/reader/media?b=1&u=x", paragraphs[2].imageUrl)
        assertEquals(3, paragraphs[2].line)
    }

    @Test
    fun `parse handles empty content`() {
        assertTrue(ReaderText.parse("").isEmpty())
        assertTrue(ReaderText.parse("  \n\n").isEmpty())
    }

    @Test
    fun `chars per page stays positive for tiny viewports`() {
        val chars = ReaderPaging.charsPerPage(
            fontSizeSp = 18f,
            lineHeight = 1.8f,
            viewportWidthDp = 1f,
            viewportHeightDp = 1f,
        )
        assertTrue(chars >= 1)
    }

    @Test
    fun `chars per page shrinks when font grows`() {
        val small = ReaderPaging.charsPerPage(14f, 1.4f, 360f, 640f)
        val large = ReaderPaging.charsPerPage(32f, 2.6f, 360f, 640f)
        assertTrue("small=$small large=$large", small > large)
    }

    @Test
    fun `paragraph to page mapping is monotonic`() {
        val paragraphs = listOf(
            ReaderParagraph("0123456789"),
            ReaderParagraph("0123456789"),
            ReaderParagraph("0123456789"),
        )
        assertEquals(0, ReaderPaging.pageForParagraph(0, paragraphs, 10))
        assertEquals(1, ReaderPaging.pageForParagraph(1, paragraphs, 10))
        assertEquals(2, ReaderPaging.pageForParagraph(2, paragraphs, 10))
        assertEquals(3, ReaderPaging.pageForParagraph(3, paragraphs, 10))
    }

    @Test
    fun `page to paragraph mapping clamps beyond the end`() {
        val paragraphs = listOf(
            ReaderParagraph("0123456789"),
            ReaderParagraph("0123456789"),
        )
        assertEquals(0, ReaderPaging.paragraphForPage(0, paragraphs, 10))
        assertEquals(1, ReaderPaging.paragraphForPage(1, paragraphs, 10))
        assertEquals(1, ReaderPaging.paragraphForPage(99, paragraphs, 10))
    }

    @Test
    fun `image counts as a full page`() {
        val paragraphs = listOf(
            ReaderParagraph(text = "", imageUrl = "/img/1"),
            ReaderParagraph("0123456789"),
        )
        assertEquals(1, ReaderPaging.pageForParagraph(1, paragraphs, 10))
        assertEquals(1, ReaderPaging.paragraphForPage(1, paragraphs, 10))
    }

    @Test
    fun `empty paragraphs never crash page mapping`() {
        assertEquals(0, ReaderPaging.pageForParagraph(5, emptyList(), 10))
        assertEquals(0, ReaderPaging.paragraphForPage(5, emptyList(), 10))
        assertEquals(0, ReaderPaging.pageForParagraph(1, listOf(ReaderParagraph("x")), 0))
    }
}
