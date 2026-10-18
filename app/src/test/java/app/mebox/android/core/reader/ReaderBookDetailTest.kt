package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderTocChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderBookDetailTest {

    @Test
    fun `first readable skips volumes and blank chapters`() {
        val chapters = listOf(
            ReaderTocChapterDto(index = 0, title = "第一卷", isVolume = true),
            ReaderTocChapterDto(index = 1, title = "占位", url = ""),
            ReaderTocChapterDto(index = 2, title = "第一章", url = "u2"),
        )

        assertEquals("第一章", ReaderBookDetail.firstReadable(chapters)?.title)
        assertNull(ReaderBookDetail.firstReadable(emptyList()))
    }
}
