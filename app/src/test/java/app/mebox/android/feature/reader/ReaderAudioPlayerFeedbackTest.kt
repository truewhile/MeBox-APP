package app.mebox.android.feature.reader

import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.reader.ReaderAudioState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAudioPlayerFeedbackTest {
    private val book = ReaderBookDto(id = "book", type = 1)

    @Test
    fun `audio player remains visible while chapter information is loading`() {
        val state = ReaderUiState(book = book, chapterLoading = true, audioPlayRequested = true)

        assertTrue(state.audioPlayerVisible)
        val feedback = state.audioPlayerFeedback()
        assertEquals(AudioPlayerPhase.PREPARING, feedback.phase)
        assertTrue(feedback.loading)
        assertTrue(feedback.playRequested)
    }

    @Test
    fun `waiting audio is a pause action even though it is not playing yet`() {
        val state = ReaderUiState(
            book = book,
            content = ReaderChapterContentDto(type = "audio"),
            audio = ReaderAudioState(
                bookId = "book", chapterIndex = 0,
                loading = true, playing = false, playWhenReady = true,
            ),
        )

        val feedback = state.audioPlayerFeedback()
        assertEquals(AudioPlayerPhase.BUFFERING, feedback.phase)
        assertTrue(feedback.loading)
        assertTrue(feedback.playRequested)
    }

    @Test
    fun `paused buffering does not show a pause action`() {
        val state = ReaderUiState(
            book = book,
            audio = ReaderAudioState(bookId = "book", chapterIndex = 0, loading = true),
        )

        assertFalse(state.audioPlayerFeedback().playRequested)
        assertEquals(AudioPlayerPhase.BUFFERING, state.audioPlayerFeedback().phase)
    }

    @Test
    fun `chapter request failure remains in the audio player and offers retry`() {
        val state = ReaderUiState(book = book, chapterError = "network failed")

        assertTrue(state.audioPlayerVisible)
        val feedback = state.audioPlayerFeedback()
        assertEquals(AudioPlayerPhase.FAILED, feedback.phase)
        assertEquals("network failed", feedback.error)
        assertFalse(feedback.loading)
        assertFalse(feedback.playRequested)
    }

    @Test
    fun `errors and playing state from another book never leak into this player`() {
        val state = ReaderUiState(
            book = book,
            audio = ReaderAudioState(
                bookId = "other", chapterIndex = 0,
                playing = true, playWhenReady = true, error = "old error",
            ),
        )

        val feedback = state.audioPlayerFeedback()
        assertEquals(AudioPlayerPhase.PAUSED, feedback.phase)
        assertFalse(feedback.playRequested)
        assertEquals(null, feedback.error)
    }

    @Test
    fun `explicit pause during chapter loading overrides inherited playback intent`() {
        val state = ReaderUiState(
            book = book,
            chapterLoading = true,
            audioPlayRequested = false,
            audio = ReaderAudioState(bookId = "book", chapterIndex = 0, playWhenReady = true),
        )

        assertFalse(state.audioPlayerFeedback().playRequested)
    }

    @Test
    fun `chapter loading inherits playback intent until user changes it`() {
        val state = ReaderUiState(
            book = book,
            chapterIndex = 1,
            chapterLoading = true,
            audio = ReaderAudioState(bookId = "book", chapterIndex = 0, playWhenReady = true),
        )

        assertTrue(state.audioPlayerFeedback().playRequested)
    }

    @Test
    fun `retrying chapter preparation hides the previous player error`() {
        val state = ReaderUiState(
            book = book,
            chapterLoading = true,
            audioPlayRequested = true,
            audio = ReaderAudioState(bookId = "book", chapterIndex = 0, error = "previous failure"),
        )

        val feedback = state.audioPlayerFeedback()
        assertEquals(AudioPlayerPhase.PREPARING, feedback.phase)
        assertTrue(feedback.loading)
        assertEquals(null, feedback.error)
    }

    @Test
    fun `loaded text content takes precedence over the books audio type`() {
        val state = ReaderUiState(book = book, content = ReaderChapterContentDto(type = "text"))

        assertFalse(state.audioPlayerVisible)
    }

    @Test
    fun `actual playing and paused states are distinguished`() {
        val state = ReaderUiState(
            book = book,
            audio = ReaderAudioState(bookId = "book", chapterIndex = 0, playing = true, playWhenReady = true),
        )
        assertEquals(AudioPlayerPhase.PLAYING, state.audioPlayerFeedback().phase)
        val paused = state.copy(audio = state.audio.copy(playing = false, playWhenReady = false))
        assertEquals(AudioPlayerPhase.PAUSED, paused.audioPlayerFeedback().phase)
    }
}
