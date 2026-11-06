package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderChapterDto
import org.junit.Assert.*
import org.junit.Test

class ReaderCachePolicyTest {
    @Test fun `window has five readable neighbors on both sides and skips volumes`() {
        val chapters = (0..30).map { ReaderChapterDto(index = 100 + it, isVolume = it % 2 == 0) }
        assertEquals(setOf(5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25), ReaderCachePolicy.window(chapters, 15))
        assertEquals(setOf(1, 3, 5, 7, 9, 11), ReaderCachePolicy.window(chapters, 1))
        assertFalse(ReaderCachePolicy.window(chapters, 14).contains(14))
        assertTrue(ReaderCachePolicy.window(chapters, -1).isEmpty())
        assertTrue(ReaderCachePolicy.window(chapters, 31).isEmpty())
        assertTrue(ReaderCachePolicy.window(emptyList(), 0).isEmpty())
    }

    @Test fun `storage totals combine both kinds`() {
        assertEquals(30L, ReaderBookStorage("id", "name", 10, 20, 1, 2).totalBytes)
    }
}
