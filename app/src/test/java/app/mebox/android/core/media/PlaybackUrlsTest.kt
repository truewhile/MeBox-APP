package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUrlsTest {

    @Test
    fun `existing external token is replaced with the access token`() {
        val result = PlaybackUrls.withAccessToken(
            "http://host/api/stream/m1?token=old-token&profile_id=p1",
            "new-token",
        )
        assertEquals("http://host/api/stream/m1?token=new-token&profile_id=p1", result)
    }

    @Test
    fun `missing token is appended`() {
        val result = PlaybackUrls.withAccessToken("http://host/api/hls/m1/index.m3u8", "abc")
        assertEquals("http://host/api/hls/m1/index.m3u8?token=abc", result)
    }

    @Test
    fun `token characters are url encoded`() {
        val result = PlaybackUrls.withAccessToken("http://host/api/stream/m1?token=x", "a+b/c")
        assertTrue(result.contains("token=a%2Bb%2Fc"))
    }

    @Test
    fun `null token leaves the url untouched`() {
        val url = "http://host/api/stream/m1?token=x"
        assertEquals(url, PlaybackUrls.withAccessToken(url, null))
    }

    @Test
    fun `same origin proxy keeps existing query parameters`() {
        val result = PlaybackUrls.withSameOriginProxy(
            "http://host/api/stream/m1?token=abc&profile_id=p1",
        )
        assertEquals(
            "http://host/api/stream/m1?token=abc&profile_id=p1&proxy=1",
            result,
        )
    }

    @Test
    fun `same origin proxy starts the query when missing`() {
        assertEquals(
            "http://host/api/stream/m1?proxy=1",
            PlaybackUrls.withSameOriginProxy("http://host/api/stream/m1"),
        )
    }

    @Test
    fun `fresh link keeps existing query parameters`() {
        assertEquals(
            "http://host/api/stream/m1?token=abc&profile_id=p1&refresh=1",
            PlaybackUrls.withFreshLink("http://host/api/stream/m1?token=abc&profile_id=p1"),
        )
    }

    @Test
    fun `fresh link starts the query when missing`() {
        assertEquals(
            "http://host/api/stream/m1?refresh=1",
            PlaybackUrls.withFreshLink("http://host/api/stream/m1"),
        )
    }

    @Test
    fun `subtitle url encodes path and carries the access token`() {
        val result = PlaybackUrls.subtitleUrl(
            origin = "http://host:8080/",
            mediaId = "m1",
            path = "/media/电影/Movie.zh.srt",
            accessToken = "a+b/c",
        )
        assertEquals(
            "http://host:8080/api/subtitles/m1" +
                "?path=%2Fmedia%2F%E7%94%B5%E5%BD%B1%2FMovie.zh.srt" +
                "&token=a%2Bb%2Fc",
            result,
        )
    }

    @Test
    fun `subtitle url supports embedded stream paths`() {
        val result = PlaybackUrls.subtitleUrl("http://host", "m1", "embedded:3", null)
        assertEquals("http://host/api/subtitles/m1?path=embedded%3A3", result)
    }

    @Test
    fun `ass subtitle url uses the ass endpoint and keeps the token`() {
        val result = PlaybackUrls.assSubtitleUrl(
            origin = "http://host:8080/",
            mediaId = "m1",
            path = "/media/电影/Movie.zh.ass",
            accessToken = "a+b/c",
        )
        assertEquals(
            "http://host:8080/api/subtitles/m1/ass" +
                "?path=%2Fmedia%2F%E7%94%B5%E5%BD%B1%2FMovie.zh.ass" +
                "&token=a%2Bb%2Fc",
            result,
        )
    }
}
