package app.mebox.android.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAudioStateTest {

    @Test
    fun `progress is clamped and safe with zero duration`() {
        assertEquals(0f, ReaderAudioState().progress, 0f)
        assertEquals(0.5f, ReaderAudioState(positionMs = 500, durationMs = 1000).progress, 0.001f)
        assertEquals(1f, ReaderAudioState(positionMs = 2000, durationMs = 1000).progress, 0.001f)
        assertEquals(0f, ReaderAudioState(positionMs = -5, durationMs = 1000).progress, 0f)
    }

    @Test
    fun `active reflects the bound book`() {
        assertFalse(ReaderAudioState().active)
        assertTrue(
            ReaderAudioState(
                bookId = "b1",
                bookName = "书",
                chapterIndex = 3,
                chapterTitle = "第三章",
                playing = true,
            ).active,
        )
    }

    @Test
    fun `speed and timer values survive state copies`() {
        val state = ReaderAudioState(speed = 1.5f, timerMinutes = 30, timerEndsAtMs = 123L)
        val copy = state.copy(playing = true)
        assertEquals(1.5f, copy.speed, 0f)
        assertEquals(30, copy.timerMinutes)
        assertEquals(123L, copy.timerEndsAtMs)
    }

    @Test
    fun `credits and stop-at-chapter-end survive state copies`() {
        val state = ReaderAudioState(
            openCreditsSec = 30,
            closeCreditsSec = 20,
            stopAtChapterEnd = true,
        )
        val copy = state.copy(playing = true)
        assertEquals(30, copy.openCreditsSec)
        assertEquals(20, copy.closeCreditsSec)
        assertTrue(copy.stopAtChapterEnd)
    }
}
