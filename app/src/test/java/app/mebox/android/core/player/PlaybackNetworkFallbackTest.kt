package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNetworkFallbackTest {
    @Test
    fun `only known 115 CDN direct URLs can use the existing HTTP workaround`() {
        assertEquals("http://cdnfhnfile.115cdn.net/video.mkv?k=signature", cdnHttpFallbackUrl("https://cdnfhnfile.115cdn.net/video.mkv?k=signature"))
        assertNull(cdnHttpFallbackUrl("https://mebox.linkmy.fun/api/stream/id?token=secret"))
        assertNull(cdnHttpFallbackUrl("https://cdn.example/video"))
        assertNull(cdnHttpFallbackUrl("https://cdn.115cdn.net.evil.example/video"))
        assertNull(cdnHttpFallbackUrl("http://cdnfhnfile.115cdn.net/video"))
        assertNull(cdnHttpFallbackUrl("https://cdnfhnfile.115cdn.net:443/video"))
    }

    @Test
    fun `native logs preserve status and Range but redact all query values`() {
        val log = "GET /video?token=private-token&path=private-path HTTP/1.1\n" +
            "https://cdn/video?t=123&k=signature' gives access https\n" +
            "HTTP/1.1 206 Partial Content\nRange: bytes=107-"
        val safe = redactPlaybackLog(log)
        assertFalse(safe.contains("private-token"))
        assertFalse(safe.contains("private-path"))
        assertFalse(safe.contains("signature"))
        assertTrue(safe.contains("token=…&path=…"))
        assertTrue(safe.contains("206 Partial Content"))
        assertTrue(safe.contains("Range: bytes=107-"))
    }
}
