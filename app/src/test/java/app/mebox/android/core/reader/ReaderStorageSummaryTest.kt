package app.mebox.android.core.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderStorageSummaryTest {
    @Test fun `merges main and local decoder bytes without hiding manual downloads`() {
        val main = ReaderBookStorage("book", "一本书", 100, 200, 2, 3)
        val decoder = ReaderBookStorage("book", "book", 50, 0, 1, 0)
        val merged = mergeReaderBookStorage(listOf(main), listOf(decoder)).single()
        assertEquals("一本书", merged.name)
        assertEquals(150L, merged.automaticBytes)
        assertEquals(200L, merged.downloadBytes)
        assertEquals(350L, merged.totalBytes)
        assertEquals(3, merged.automaticChapters)
        assertEquals(3, merged.downloadChapters)
    }

    @Test fun `empty metadata is omitted and largest books are listed first`() {
        val books = mergeReaderBookStorage(listOf(
            ReaderBookStorage("empty", "空", 0, 0, 0, 0),
            ReaderBookStorage("small", "小", 1, 2, 1, 1),
            ReaderBookStorage("big", "大", 100, 0, 1, 0),
        ))
        assertEquals(listOf("big", "small"), books.map { it.bookId })
    }
}
