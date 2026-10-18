package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionStateTest {

    @Test
    fun `fresh session plays from the requested position`() {
        val session = PlaybackSessionState(initialPositionMs = 12_000)

        assertTrue(session.playWhenReady)
        assertEquals(12_000, session.consumeResumePosition())
    }

    @Test
    fun `zero position is a valid resume point`() {
        val session = PlaybackSessionState(initialPositionMs = 90_000)

        session.recordPosition(0)
        session.beginHandoff(currentPositionMs = 0)

        assertEquals(0, session.consumeResumePosition())
    }

    @Test
    fun `handoff keeps the last known position while the engine reports zero`() {
        val session = PlaybackSessionState(initialPositionMs = 30_000)
        session.recordPosition(42_000)

        // 缓冲或出错时引擎可能返回 0；这不是用户想回到的起点。
        session.beginHandoff(currentPositionMs = 0)

        assertEquals(42_000, session.consumeResumePosition())
    }

    @Test
    fun `newer engine position wins over the last known position`() {
        val session = PlaybackSessionState(initialPositionMs = 30_000)
        session.recordPosition(42_000)

        session.beginHandoff(currentPositionMs = 47_500)

        assertEquals(47_500, session.consumeResumePosition())
    }

    @Test
    fun `premature zero report does not overwrite a pending resume position`() {
        val session = PlaybackSessionState(initialPositionMs = 30_000)
        session.beginHandoff(currentPositionMs = 42_000)

        // 新内核还没装载媒体，轮询先读到了 0。
        session.recordPosition(0)

        assertEquals(42_000, session.consumeResumePosition())
        assertEquals(42_000, session.lastKnownPositionMs)
    }

    @Test
    fun `consecutive handoffs keep the position until the engine reports one`() {
        val session = PlaybackSessionState(initialPositionMs = 30_000)
        session.beginHandoff(currentPositionMs = 42_000)
        assertEquals(42_000, session.consumeResumePosition())

        session.beginHandoff(currentPositionMs = 0)

        assertEquals(42_000, session.consumeResumePosition())
    }

    @Test
    fun `pause intent survives switching engines`() {
        val session = PlaybackSessionState()

        session.onUserPause()
        session.beginHandoff(currentPositionMs = 12_000)

        assertFalse(session.playWhenReady)
    }

    @Test
    fun `lifecycle pause keeps the new engine paused`() {
        val session = PlaybackSessionState()

        session.onUserPause()

        assertFalse(session.playWhenReady)
    }

    @Test
    fun `explicit retry asks the engine to play`() {
        val session = PlaybackSessionState()
        session.onUserPause()

        session.onExplicitRetry()

        assertTrue(session.playWhenReady)
    }

    @Test
    fun `loading a new episode applies its autoplay choice`() {
        val session = PlaybackSessionState()
        session.onUserPause()

        session.onMediaLoaded(autoPlay = true)

        assertTrue(session.playWhenReady)
    }

    @Test
    fun `buffering does not count as a user pause`() {
        // 会话状态只记录用户意图；内核缓冲由引擎状态单独汇报。
        val intent = PlaybackIntent(initialPlayWhenReady = true)

        intent.onMediaLoaded(autoPlay = true)

        assertTrue(intent.playWhenReady)
    }
}

class PlaybackIntentTest {

    @Test
    fun `starts with the requested play state`() {
        assertTrue(PlaybackIntent().playWhenReady)
        assertFalse(PlaybackIntent(initialPlayWhenReady = false).playWhenReady)
    }

    @Test
    fun `internal reload keeps the pause intent`() {
        val intent = PlaybackIntent()
        intent.onPause()

        // 重新装载媒体（换中转地址/换软解）不应重新开播。
        assertFalse(intent.playWhenReady)
    }

    @Test
    fun `play and pause update the intent`() {
        val intent = PlaybackIntent()
        intent.onPause()
        intent.onPlay()
        assertTrue(intent.playWhenReady)

        intent.onPause()
        assertFalse(intent.playWhenReady)
    }
}
