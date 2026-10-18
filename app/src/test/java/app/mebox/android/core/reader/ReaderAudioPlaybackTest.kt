package app.mebox.android.core.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAudioPlaybackTest {
    @Test
    fun `old player idle events cannot hide chapter preparation`() {
        val playback = ReaderAudioPlayback()
        playback.begin(autoPlay = true)

        playback.playerChanged(buffering = false, playing = false, playWhenReady = false)

        assertTrue(playback.preparing)
        assertTrue(playback.loading)
        assertTrue(playback.playWhenReady)
        assertFalse(playback.playing)
    }

    @Test
    fun `installing media keeps loading and never pretends autoplay is actual playback`() {
        val playback = ReaderAudioPlayback()
        val request = playback.begin(autoPlay = true)

        assertTrue(playback.mediaInstalled(request))

        assertFalse(playback.preparing)
        assertTrue(playback.loading)
        assertTrue(playback.playWhenReady)
        assertFalse(playback.playing)

        playback.playerChanged(buffering = true, playing = false, playWhenReady = true)
        assertTrue(playback.loading)
        assertFalse(playback.playing)

        playback.playerChanged(buffering = false, playing = true, playWhenReady = true)
        assertFalse(playback.loading)
        assertTrue(playback.playing)
    }

    @Test
    fun `pause during chapter preparation survives media installation`() {
        val playback = ReaderAudioPlayback()
        val request = playback.begin(autoPlay = true)

        playback.setPlayWhenReady(false)
        playback.mediaInstalled(request)

        assertFalse(playback.playWhenReady)
        assertFalse(playback.playing)
    }

    @Test
    fun `play during preparation of a paused chapter is remembered`() {
        val playback = ReaderAudioPlayback()
        val request = playback.begin(autoPlay = false)

        playback.setPlayWhenReady(true)
        playback.mediaInstalled(request)

        assertTrue(playback.playWhenReady)
        assertTrue(playback.loading)
        assertFalse(playback.playing)
    }

    @Test
    fun `buffering can be paused without becoming a new play request`() {
        val playback = ReaderAudioPlayback()
        val request = playback.begin(autoPlay = true)
        playback.mediaInstalled(request)
        playback.playerChanged(buffering = true, playing = false, playWhenReady = true)

        playback.setPlayWhenReady(false)
        playback.playerChanged(buffering = true, playing = false, playWhenReady = false)

        assertFalse(playback.playWhenReady)
        assertFalse(playback.playing)
    }

    @Test
    fun `failure clears loading and a retry starts a fresh request`() {
        val playback = ReaderAudioPlayback()
        val failed = playback.begin(autoPlay = true)
        assertTrue(playback.fail(failed))

        assertFalse(playback.loading)
        assertFalse(playback.playing)
        assertFalse(playback.playWhenReady)
        assertTrue(playback.failed)

        val retry = playback.begin(autoPlay = true)
        assertFalse(playback.failed)
        assertTrue(playback.loading)
        assertTrue(playback.playWhenReady)
        assertFalse(playback.isCurrent(failed))
        assertTrue(playback.isCurrent(retry))
    }

    @Test
    fun `late callbacks from a replaced request cannot install media or fail the new request`() {
        val playback = ReaderAudioPlayback()
        val oldRequest = playback.begin(autoPlay = true)
        val currentRequest = playback.begin(autoPlay = false)

        assertFalse(playback.mediaInstalled(oldRequest))
        assertFalse(playback.fail(oldRequest))
        assertTrue(playback.preparing)
        assertTrue(playback.isCurrent(currentRequest))
        assertFalse(playback.playWhenReady)
    }

    @Test
    fun `stop invalidates queued media installation and clears playback intent`() {
        val playback = ReaderAudioPlayback()
        val request = playback.begin(autoPlay = true)

        playback.reset()

        assertFalse(playback.isCurrent(request))
        assertFalse(playback.mediaInstalled(request))
        assertFalse(playback.loading)
        assertFalse(playback.playing)
        assertFalse(playback.playWhenReady)
    }

    @Test
    fun `late player events and failures after stop cannot reactivate playback`() {
        val playback = ReaderAudioPlayback()
        playback.begin(autoPlay = true)
        playback.reset()

        playback.playerChanged(buffering = true, playing = true, playWhenReady = true)

        assertFalse(playback.loading)
        assertFalse(playback.playing)
        assertFalse(playback.playWhenReady)
        assertFalse(playback.fail(playback.requestId))
    }

    @Test
    fun `external pause events update intent after media installation`() {
        val playback = ReaderAudioPlayback()
        val request = playback.begin(autoPlay = true)
        playback.mediaInstalled(request)

        playback.playerChanged(buffering = false, playing = false, playWhenReady = false)

        assertFalse(playback.playWhenReady)
        assertFalse(playback.loading)
        assertFalse(playback.playing)
    }
}
