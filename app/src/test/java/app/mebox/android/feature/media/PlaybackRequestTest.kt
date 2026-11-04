package app.mebox.android.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRequestTest {
    private val episodes = listOf(
        PlaybackEpisode("s1e1", "第 1 集", "开篇"),
        PlaybackEpisode("s1e2", "第 2 集", "重逢"),
        PlaybackEpisode("s2e1", "第 1 集", "新篇章"),
    )

    private fun request(mediaId: String, items: List<PlaybackEpisode> = episodes) = PlaybackRequest(
        mediaId = mediaId,
        title = "测试剧集",
        streamUrl = "https://example.com/video",
        startPositionMs = 0L,
        durationMs = 60_000L,
        episodes = items,
    )

    @Test
    fun `middle episode exposes the preceding episode`() {
        assertEquals(episodes[0], request("s1e2").previousEpisode)
    }

    @Test
    fun `last episode can return to the preceding episode`() {
        assertEquals(episodes[1], request("s2e1").previousEpisode)
    }

    @Test
    fun `first episode has no previous episode and does not wrap`() {
        assertNull(request("s1e1").previousEpisode)
    }

    @Test
    fun `unknown media does not select an unrelated episode`() {
        assertNull(request("movie").previousEpisode)
    }

    @Test
    fun `empty and single episode lists have no previous episode`() {
        assertNull(request("movie", emptyList()).previousEpisode)
        assertNull(request("s1e1", episodes.take(1)).previousEpisode)
    }

    @Test
    fun `episode change recomputes the previous episode`() {
        val current = request("s1e2")
        assertNull(current.copy(mediaId = "s1e1").previousEpisode)
        assertEquals(episodes[1], current.copy(mediaId = "s2e1").previousEpisode)
    }
}
