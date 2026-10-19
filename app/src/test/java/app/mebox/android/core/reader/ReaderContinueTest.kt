package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderBookDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderContinueTest {

    private fun book(
        id: String,
        name: String = id,
        durChapterTime: Long = 0,
        durChapterIndex: Int = 0,
        durChapterTitle: String = "",
        type: Int = 0,
    ) = ReaderBookDto(
        id = id,
        name = name,
        type = type,
        durChapterTime = durChapterTime,
        durChapterIndex = durChapterIndex,
        durChapterTitle = durChapterTitle,
    )

    @Test
    fun `no started book yields no bar`() {
        val books = listOf(book("a"), book("b"))

        assertNull(ReaderContinue.pick(books, null, audioActive = false))
        assertNull(ReaderContinue.pick(emptyList(), "a", audioActive = true))
    }

    @Test
    fun `most recently read book wins`() {
        val books = listOf(
            book("a", durChapterTime = 100),
            book("b", durChapterTime = 300),
            book("c", durChapterTime = 0),
        )

        assertEquals("b", ReaderContinue.pick(books, null, audioActive = false)?.id)
    }

    @Test
    fun `active audiobook wins even with older progress`() {
        val books = listOf(
            book("text", durChapterTime = 900),
            book("audio", durChapterTime = 100, type = 1),
        )

        assertEquals(
            "audio",
            ReaderContinue.pick(books, "audio", audioActive = true)?.id,
        )
    }

    @Test
    fun `paused audiobook still participates by progress time`() {
        val books = listOf(
            book("text", durChapterTime = 900),
            book("audio", durChapterTime = 100, type = 1),
        )

        assertEquals(
            "text",
            ReaderContinue.pick(books, "audio", audioActive = false)?.id,
        )
    }

    @Test
    fun `chapter label includes index and title`() {
        val book = book("a", durChapterIndex = 4, durChapterTitle = "第五章")

        assertEquals("第 5 章 · 第五章", ReaderContinue.chapterLabel(book))
        assertEquals("第 5 章", ReaderContinue.chapterLabel(book.copy(durChapterTitle = " ")))
    }
}
