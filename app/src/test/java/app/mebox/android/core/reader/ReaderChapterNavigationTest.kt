package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderChapterNavigationTest {

    private fun chapter(index: Int, volume: Boolean = false) =
        ReaderChapterDto(index = index, title = "第 ${index + 1} 章", isVolume = volume)

    @Test
    fun `empty toc has no neighbour`() {
        assertNull(ReaderChapterNavigation.nextReadable(emptyList(), 0))
        assertNull(ReaderChapterNavigation.previousReadable(emptyList(), 0))
    }

    @Test
    fun `normal next and previous skip volume entries`() {
        val chapters = listOf(
            chapter(0),
            chapter(1, volume = true),
            chapter(2),
            chapter(3),
            chapter(4, volume = true),
            chapter(5),
        )
        assertEquals(2, ReaderChapterNavigation.nextReadable(chapters, 0))
        assertEquals(5, ReaderChapterNavigation.nextReadable(chapters, 3))
        assertEquals(3, ReaderChapterNavigation.previousReadable(chapters, 5))
        assertEquals(0, ReaderChapterNavigation.previousReadable(chapters, 2))
    }

    @Test
    fun `chapter index from another longer book does not crash`() {
        // 有声书停在第 428 章时打开只有 400 章的漫画：旧章节号必须被当成越界，
        // 而不是拿去索引漫画目录。
        val comic = List(400) { chapter(it) }
        assertNull(ReaderChapterNavigation.previousReadable(comic, 427))
        assertNull(ReaderChapterNavigation.nextReadable(comic, 427))
    }

    @Test
    fun `index before first chapter only offers next`() {
        val chapters = listOf(chapter(0), chapter(1))
        assertEquals(0, ReaderChapterNavigation.nextReadable(chapters, -1))
        assertNull(ReaderChapterNavigation.previousReadable(chapters, -1))
    }

    @Test
    fun `last chapter has no next`() {
        val chapters = listOf(chapter(0), chapter(1))
        assertNull(ReaderChapterNavigation.nextReadable(chapters, 1))
        assertEquals(0, ReaderChapterNavigation.previousReadable(chapters, 1))
    }
}
