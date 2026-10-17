package app.mebox.android.feature.reader

import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderChaptersTest {

    private val chapters = listOf(
        ReaderChapterDto(index = 0, title = "第一卷", isVolume = true),
        ReaderChapterDto(index = 1, title = "第一章"),
        ReaderChapterDto(index = 2, title = "第二章"),
        ReaderChapterDto(index = 3, title = "第二卷", isVolume = true),
        ReaderChapterDto(index = 4, title = "第三章"),
    )

    @Test
    fun `empty chapter list never indexes out of bounds`() {
        // 书籍保存的进度可能远超当前目录长度（目录缓存为空时尤其明显）。
        assertNull(ReaderChapters.previousReadable(emptyList(), 20))
        assertNull(ReaderChapters.nextReadable(emptyList(), 20))
    }

    @Test
    fun `initial index skips volumes and honors saved chapter`() {
        val book = ReaderBookDto(id = "b", durChapterIndex = 4)
        assertEquals(4, ReaderChapters.initialIndex(book, chapters))

        val invalid = ReaderBookDto(id = "b", durChapterIndex = 99)
        assertEquals(1, ReaderChapters.initialIndex(invalid, chapters))

        val volume = ReaderBookDto(id = "b", durChapterIndex = 3)
        assertEquals(1, ReaderChapters.initialIndex(volume, chapters))
    }

    @Test
    fun `navigation skips volume rows`() {
        assertEquals(2, ReaderChapters.nextReadable(chapters, 1))
        assertEquals(4, ReaderChapters.nextReadable(chapters, 2))
        assertEquals(2, ReaderChapters.previousReadable(chapters, 4))
        assertEquals(1, ReaderChapters.previousReadable(chapters, 2))
    }

    @Test
    fun `navigation stops at the ends`() {
        assertNull(ReaderChapters.nextReadable(chapters, 4))
        assertNull(ReaderChapters.previousReadable(chapters, 1))
        assertNull(ReaderChapters.previousReadable(chapters, 0))
        assertNull(ReaderChapters.nextReadable(chapters, 99))
    }
}
