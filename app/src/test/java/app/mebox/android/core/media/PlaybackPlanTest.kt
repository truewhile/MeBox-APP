package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPlanTest {

    @Test
    fun `server progress wins when it is ahead of the cache`() {
        assertEquals(
            120_000L,
            PlaybackPlan.startPosition(
                serverPositionMs = 120_000L,
                cachedPositionMs = 60_000L,
                durationMs = 1_800_000L,
            ),
        )
    }

    @Test
    fun `cache fills the gap when the server has no progress`() {
        assertEquals(
            90_000L,
            PlaybackPlan.startPosition(
                serverPositionMs = 0L,
                cachedPositionMs = 90_000L,
                durationMs = 1_800_000L,
            ),
        )
    }

    @Test
    fun `local cache wins when the last sync failed`() {
        assertEquals(
            180_000L,
            PlaybackPlan.startPosition(
                serverPositionMs = 150_000L,
                cachedPositionMs = 180_000L,
                durationMs = 1_800_000L,
            ),
        )
    }

    @Test
    fun `position near the end restarts from zero`() {
        assertEquals(
            0L,
            PlaybackPlan.startPosition(
                serverPositionMs = 1_790_000L,
                cachedPositionMs = 0L,
                durationMs = 1_800_000L,
            ),
        )
    }

    @Test
    fun `completed media always restarts`() {
        assertEquals(
            0L,
            PlaybackPlan.startPosition(
                serverPositionMs = 900_000L,
                cachedPositionMs = 900_000L,
                durationMs = 1_800_000L,
                completed = true,
            ),
        )
    }

    @Test
    fun `unknown duration keeps the cached value`() {
        assertEquals(
            42_000L,
            PlaybackPlan.startPosition(0L, 42_000L, durationMs = 0L),
        )
    }

    @Test
    fun `seek target is clamped to the timeline`() {
        assertEquals(0L, PlaybackPlan.seekTarget(5_000L, -30_000L, 100_000L))
        assertEquals(100_000L, PlaybackPlan.seekTarget(90_000L, 30_000L, 100_000L))
        assertEquals(40_000L, PlaybackPlan.seekTarget(30_000L, 10_000L, 100_000L))
    }

    @Test
    fun `time formatting switches to hours only when needed`() {
        assertEquals("0:00", PlaybackPlan.formatTime(0L))
        assertEquals("1:05", PlaybackPlan.formatTime(65_000L))
        assertEquals("1:01:01", PlaybackPlan.formatTime(3_661_000L))
        assertEquals("0:00", PlaybackPlan.formatTime(-5_000L))
    }

    @Test
    fun `speed labels are compact`() {
        assertEquals("1×", PlaybackPlan.formatSpeed(1f))
        assertEquals("1.25×", PlaybackPlan.formatSpeed(1.25f))
        assertEquals("0.5×", PlaybackPlan.formatSpeed(0.5f))
    }

    @Test
    fun `progress fraction stays inside zero and one`() {
        assertEquals(0.5f, PlaybackPlan.progressFraction(500L, 1_000L), 0.0001f)
        assertEquals(0f, PlaybackPlan.progressFraction(500L, 0L), 0.0001f)
        assertEquals(1f, PlaybackPlan.progressFraction(2_000L, 1_000L), 0.0001f)
    }
}
