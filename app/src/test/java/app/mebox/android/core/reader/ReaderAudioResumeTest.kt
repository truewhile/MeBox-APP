package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAudioResumeTest {

    private val chapters = listOf(
        ReaderChapterDto(index = 0, title = "第一章"),
        ReaderChapterDto(index = 1, title = "第二章"),
        ReaderChapterDto(index = 2, title = "第三章"),
    )

    @Test
    fun `local checkpoint newer than server wins`() {
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 1,
            serverPositionSec = 30,
            serverUpdatedAtMs = 1_000,
            local = checkpoint(bookId = "book", chapter = 2, positionMs = 125_000, updatedAt = 2_000),
            chapters = chapters,
        )

        assertEquals(2, result.chapterIndex)
        assertEquals(125, result.positionSec)
        assertTrue(result.fromLocalCheckpoint)
    }

    @Test
    fun `server newer than local checkpoint wins`() {
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 1,
            serverPositionSec = 30,
            serverUpdatedAtMs = 3_000,
            local = checkpoint(bookId = "book", chapter = 2, positionMs = 125_000, updatedAt = 2_000),
            chapters = chapters,
        )

        assertEquals(1, result.chapterIndex)
        assertEquals(30, result.positionSec)
        assertFalse(result.fromLocalCheckpoint)
    }

    @Test
    fun `missing local checkpoint uses server`() {
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 2,
            serverPositionSec = 7,
            serverUpdatedAtMs = 0,
            local = null,
            chapters = chapters,
        )

        assertEquals(2, result.chapterIndex)
        assertEquals(7, result.positionSec)
        assertFalse(result.fromLocalCheckpoint)
    }

    @Test
    fun `checkpoint for another book is ignored`() {
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 0,
            serverPositionSec = 5,
            serverUpdatedAtMs = 0,
            local = checkpoint(bookId = "other", chapter = 2, positionMs = 9_000, updatedAt = 2_000),
            chapters = chapters,
        )

        assertEquals(0, result.chapterIndex)
        assertEquals(5, result.positionSec)
        assertFalse(result.fromLocalCheckpoint)
    }

    @Test
    fun `invalid local chapter falls back to server`() {
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 1,
            serverPositionSec = 5,
            serverUpdatedAtMs = 0,
            local = checkpoint(bookId = "book", chapter = 99, positionMs = 9_000, updatedAt = 2_000),
            chapters = chapters,
        )

        assertEquals(1, result.chapterIndex)
        assertEquals(5, result.positionSec)
        assertFalse(result.fromLocalCheckpoint)
    }

    @Test
    fun `local chapter that became a volume falls back to server`() {
        val withVolume = listOf(
            ReaderChapterDto(index = 0, title = "第一章"),
            ReaderChapterDto(index = 1, title = "第一卷", isVolume = true),
            ReaderChapterDto(index = 2, title = "第二章"),
        )
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 0,
            serverPositionSec = 5,
            serverUpdatedAtMs = 0,
            local = checkpoint(bookId = "book", chapter = 1, positionMs = 9_000, updatedAt = 2_000),
            chapters = withVolume,
        )

        assertEquals(0, result.chapterIndex)
        assertFalse(result.fromLocalCheckpoint)
    }

    @Test
    fun `invalid server chapter is normalized to first readable chapter`() {
        val result = ReaderAudioResume.choose(
            bookId = "book",
            serverChapterIndex = 99,
            serverPositionSec = 0,
            serverUpdatedAtMs = 0,
            local = null,
            chapters = chapters,
        )

        assertEquals(0, result.chapterIndex)
    }

    @Test
    fun `checkpoint survives JSON round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = checkpoint(
            bookId = "book",
            chapter = 2,
            positionMs = 125_000,
            updatedAt = 2_000,
        ).copy(durationMs = 600_000, playing = true, chapterTitle = "第三章")

        val decoded = json.decodeFromString(
            ReaderAudioCheckpoint.serializer(),
            json.encodeToString(ReaderAudioCheckpoint.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    private fun checkpoint(
        bookId: String,
        chapter: Int,
        positionMs: Long,
        updatedAt: Long,
    ) = ReaderAudioCheckpoint(
        bookId = bookId,
        chapterIndex = chapter,
        positionMs = positionMs,
        updatedAtMs = updatedAt,
    )
}
