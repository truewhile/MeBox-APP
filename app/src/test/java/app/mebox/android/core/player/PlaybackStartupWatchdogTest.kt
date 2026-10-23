package app.mebox.android.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartupWatchdogTest {
    @Test
    fun `a completely idle open eventually times out`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 1_000)
        assertFalse(watchdog.hasTimedOut(20_999))
        assertTrue(watchdog.hasTimedOut(21_000))
        assertFalse(watchdog.hasProgress)
    }

    @Test
    fun `parsed tracks prove progress even when VLC byte statistics are zero`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 0)
        watchdog.recordProgress(8_500)
        watchdog.observeBytes(10_000, readBytes = 0, demuxBytes = 0)
        assertTrue(watchdog.hasProgress)
        assertFalse(watchdog.hasTimedOut(10_000))
        assertFalse(watchdog.hasTimedOut(28_499))
        assertTrue(watchdog.hasTimedOut(28_500))
    }

    @Test
    fun `only increasing byte counts extend the idle deadline`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 0)
        watchdog.observeBytes(15_000, readBytes = 1_024, demuxBytes = 0)
        watchdog.observeBytes(30_000, readBytes = 1_024, demuxBytes = 0)
        assertFalse(watchdog.hasTimedOut(34_999))
        assertTrue(watchdog.hasTimedOut(35_000))
    }

    @Test
    fun `continuous progress cannot extend startup indefinitely`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 0)
        for (time in 10_000L..80_000L step 10_000L) watchdog.recordProgress(time)
        assertFalse(watchdog.hasTimedOut(89_999))
        assertTrue(watchdog.hasTimedOut(90_000))
    }

    @Test
    fun `missing byte statistics do not erase previously observed progress`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 0)
        watchdog.observeBytes(5_000, readBytes = 10, demuxBytes = 0)
        watchdog.observeBytes(6_000, readBytes = -1, demuxBytes = -1)
        assertTrue(watchdog.hasProgress)
        assertTrue(watchdog.hasTimedOut(25_000))
    }

    @Test
    fun `no-data deadline fires earlier when a fallback address is available`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 0)
        assertFalse(watchdog.hasTimedOut(7_999, noProgressTimeoutMs = 8_000))
        assertTrue(watchdog.hasTimedOut(8_000, noProgressTimeoutMs = 8_000))
    }

    @Test
    fun `progress disables the early no-data deadline`() {
        val watchdog = PlaybackStartupWatchdog(startedAtMs = 0)
        watchdog.recordProgress(1_000)
        assertFalse(watchdog.hasTimedOut(8_500, noProgressTimeoutMs = 8_000))
        assertTrue(watchdog.hasTimedOut(21_000, noProgressTimeoutMs = 8_000))
    }
}
