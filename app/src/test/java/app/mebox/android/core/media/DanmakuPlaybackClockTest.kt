package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DanmakuPlaybackClockTest {

    private fun DanmakuPlaybackClock.at(
        frameMs: Long,
        sampleMs: Long,
        playing: Boolean = true,
        speed: Float = 1f,
        discontinuity: Int = 0,
    ) = positionMs(frameMs * 1_000_000L, sampleMs, playing, speed, discontinuity)

    @Test
    fun `repeated player sample still advances on each display frame`() {
        val clock = DanmakuPlaybackClock()
        assertEquals(1_000L, clock.at(0L, 1_000L))
        assertEquals(1_016L, clock.at(16L, 1_000L))
        assertEquals(1_033L, clock.at(33L, 1_000L))
        assertEquals(1_050L, clock.at(50L, 1_000L))
    }

    @Test
    fun `quarter second player samples move continuously at 60 and 120 hz`() {
        for (refreshRate in listOf(60, 120)) {
            val clock = DanmakuPlaybackClock()
            var previous = clock.positionMs(0L, 10_000L, true, 1f)
            for (frame in 1..refreshRate * 10) {
                val nanos = frame * 1_000_000_000L / refreshRate
                val elapsedMs = nanos / 1_000_000L
                val sample = 10_000L + elapsedMs / 250L * 250L
                val position = clock.positionMs(nanos, sample, true, 1f)
                val movement = position - previous
                assertTrue("$refreshRate Hz frame $frame moved $movement ms", movement in 6L..19L)
                assertTrue("drift at frame $frame", abs(position - (10_000L + elapsedMs)) < 30L)
                previous = position
            }
        }
    }

    @Test
    fun `clock follows playback speed between samples`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L, speed = 2f)
        assertEquals(1_032L, clock.at(16L, 1_000L, speed = 2f))
        assertEquals(1_200L, clock.at(100L, 1_000L, speed = 2f))
    }

    @Test
    fun `pause or buffering stops movement and resume reanchors`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        assertEquals(1_100L, clock.at(100L, 1_000L))
        assertEquals(1_120L, clock.at(150L, 1_120L, playing = false))
        assertEquals(1_120L, clock.at(400L, 1_120L, playing = false))
        assertEquals(1_120L, clock.at(450L, 1_120L))
        assertEquals(1_136L, clock.at(466L, 1_120L))
    }

    @Test
    fun `speed change drops old interpolation anchor`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        clock.at(100L, 1_000L)
        assertEquals(1_090L, clock.at(110L, 1_090L, speed = 0.5f))
        assertEquals(1_140L, clock.at(210L, 1_090L, speed = 0.5f))
    }

    @Test
    fun `explicit small forward and backward seeks synchronize immediately`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        clock.at(100L, 1_000L)
        assertEquals(1_140L, clock.at(110L, 1_140L, discontinuity = 1))
        assertEquals(1_156L, clock.at(126L, 1_140L, discontinuity = 1))
        assertEquals(1_100L, clock.at(130L, 1_100L, discontinuity = 2))
    }

    @Test
    fun `delayed player sample after a short seek bypasses drift correction`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        clock.at(100L, 1_000L)
        // VLC 的 setTime 返回后，time 可能暂时还是 seek 前的采样。
        clock.at(110L, 1_000L, discontinuity = 1)
        clock.at(126L, 1_000L, discontinuity = 1)
        assertEquals(1_500L, clock.at(142L, 1_500L, discontinuity = 1))
        assertEquals(1_516L, clock.at(158L, 1_500L, discontinuity = 1))
    }

    @Test
    fun `buffering during a pending seek keeps the next sample as a discontinuity`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        clock.at(16L, 1_000L, discontinuity = 1)
        clock.at(32L, 1_000L, playing = false, discontinuity = 1)
        clock.at(48L, 1_000L, discontinuity = 1)
        assertEquals(1_500L, clock.at(64L, 1_500L, discontinuity = 1))
    }

    @Test
    fun `paused asynchronous seek follows its final sample and then resumes smoothly`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L, playing = false)
        clock.at(16L, 1_000L, playing = false, discontinuity = 1)
        assertEquals(1_500L, clock.at(32L, 1_500L, playing = false, discontinuity = 1))
        assertEquals(1_500L, clock.at(48L, 1_500L, discontinuity = 1))
        assertEquals(1_516L, clock.at(64L, 1_500L, discontinuity = 1))
    }

    @Test
    fun `large forward jump and backward player sample reanchor immediately`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        assertEquals(20_000L, clock.at(16L, 20_000L))
        assertEquals(2_000L, clock.at(32L, 2_000L))
        assertEquals(2_016L, clock.at(48L, 2_000L))
    }

    @Test
    fun `small sample error is corrected without a visible position jump`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        val before = clock.at(100L, 1_000L)
        val after = clock.at(116L, 1_080L)
        assertTrue("small correction should still move forward", after - before in 14L..18L)
        val next = clock.at(132L, 1_080L)
        assertTrue(next - after in 14L..18L)
    }

    @Test
    fun `stalled player never extrapolates indefinitely`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        for (frameMs in 100L..2_000L step 100L) {
            clock.at(frameMs, 1_000L)
        }
        assertEquals(1_500L, clock.at(2_016L, 1_000L))
        assertEquals(1_500L, clock.at(2_032L, 1_000L))
        assertTrue(clock.at(2_048L, 1_600L) > 1_500L)
    }

    @Test
    fun `long gap without display frames reanchors instead of running ahead`() {
        val clock = DanmakuPlaybackClock()
        clock.at(0L, 1_000L)
        assertEquals(1_200L, clock.at(5_000L, 1_200L))
        assertEquals(1_216L, clock.at(5_016L, 1_200L))
    }

    @Test
    fun `backward frame timestamp resets interpolation`() {
        val clock = DanmakuPlaybackClock()
        clock.at(100L, 1_000L)
        clock.at(200L, 1_000L)
        assertEquals(1_050L, clock.at(10L, 1_050L))
        assertEquals(1_066L, clock.at(26L, 1_050L))
    }
}
