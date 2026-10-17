package app.mebox.android.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAudioTuningTest {

    @Test
    fun `speed snaps to five hundredths and stays inside range`() {
        assertEquals(1.25f, ReaderAudioTuning.snapSpeed(1.23f), 0f)
        assertEquals(1.05f, ReaderAudioTuning.snapSpeed(1.051f), 0f)
        assertEquals(ReaderAudioTuning.SPEED_MIN, ReaderAudioTuning.snapSpeed(0.1f), 0f)
        assertEquals(ReaderAudioTuning.SPEED_MAX, ReaderAudioTuning.snapSpeed(9f), 0f)
        assertEquals(1f, ReaderAudioTuning.snapSpeed(1f), 0f)
        assertEquals(1f, ReaderAudioTuning.snapSpeed(Float.NaN), 0f)
    }

    @Test
    fun `timer minutes clamp between off and three hours`() {
        assertEquals(0, ReaderAudioTuning.clampTimerMinutes(-5))
        assertEquals(0, ReaderAudioTuning.clampTimerMinutes(0))
        assertEquals(37, ReaderAudioTuning.clampTimerMinutes(37))
        assertEquals(ReaderAudioTuning.TIMER_MAX_MINUTES, ReaderAudioTuning.clampTimerMinutes(999))
    }

    @Test
    fun `credits snap to five second steps and clamp`() {
        assertEquals(0, ReaderAudioTuning.snapCreditsSeconds(2))
        assertEquals(5, ReaderAudioTuning.snapCreditsSeconds(3))
        assertEquals(30, ReaderAudioTuning.snapCreditsSeconds(31))
        assertEquals(
            ReaderAudioTuning.CREDITS_MAX_SECONDS,
            ReaderAudioTuning.clampCreditsSeconds(10_000),
        )
        assertEquals(0, ReaderAudioTuning.clampCreditsSeconds(-8))
    }

    @Test
    fun `intro is skipped only when the resume point is inside it`() {
        assertEquals(
            30_000L,
            ReaderAudioTuning.startPositionMs(30, requestedMs = 0L, durationMs = 0L),
        )
        assertEquals(
            30_000L,
            ReaderAudioTuning.startPositionMs(30, requestedMs = 12_000L, durationMs = 0L),
        )
        assertEquals(
            45_000L,
            ReaderAudioTuning.startPositionMs(30, requestedMs = 45_000L, durationMs = 0L),
        )
        assertEquals(
            0L,
            ReaderAudioTuning.startPositionMs(0, requestedMs = 0L, durationMs = 0L),
        )
        assertEquals(
            30_000L,
            ReaderAudioTuning.startPositionMs(30, requestedMs = -3_000L, durationMs = 0L),
        )
    }

    @Test
    fun `intro longer than the chapter is not applied`() {
        assertEquals(
            0L,
            ReaderAudioTuning.startPositionMs(120, requestedMs = 0L, durationMs = 60_000L),
        )
        assertEquals(
            120_000L,
            ReaderAudioTuning.startPositionMs(120, requestedMs = 0L, durationMs = 180_000L),
        )
    }

    @Test
    fun `outro triggers inside the closing credits region`() {
        assertTrue(
            ReaderAudioTuning.isEndCreditsReached(
                openCreditsSeconds = 30,
                closeCreditsSeconds = 20,
                positionMs = 590_000L,
                durationMs = 600_000L,
            ),
        )
        assertTrue(
            ReaderAudioTuning.isEndCreditsReached(0, 20, 599_000L, 600_000L),
        )
        assertFalse(
            ReaderAudioTuning.isEndCreditsReached(30, 20, 500_000L, 600_000L),
        )
        assertFalse(
            ReaderAudioTuning.isEndCreditsReached(30, 0, 599_000L, 600_000L),
        )
        assertFalse(
            ReaderAudioTuning.isEndCreditsReached(30, 20, 0L, 0L),
        )
    }

    @Test
    fun `outro is ignored when credits would cover the whole chapter`() {
        assertFalse(
            ReaderAudioTuning.isEndCreditsReached(
                openCreditsSeconds = 30,
                closeCreditsSeconds = 30,
                positionMs = 50_000L,
                durationMs = 60_000L,
            ),
        )
    }
}
