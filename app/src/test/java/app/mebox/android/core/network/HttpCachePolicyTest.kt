package app.mebox.android.core.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓存策略是「首屏能不能秒开」的关键：读接口按路径给定 TTL，写接口与
 * 实时数据（播放地址、续播、收藏状态）必须永远不缓存。
 */
class HttpCachePolicyTest {

    private fun ttl(path: String, post: Boolean = false): Int? {
        val builder = Request.Builder().url("https://mebox.example.com$path")
        val request = if (post) {
            builder.post(ByteArray(0).toRequestBody()).build()
        } else {
            builder.get().build()
        }
        return HttpCachePolicy.ttlSeconds(request)
    }

    @Test
    fun `read endpoints are cached`() {
        assertEquals(30, ttl("/api/libraries?with_preview=1&include_total=0"))
        assertEquals(30, ttl("/api/media/recent?limit=18"))
        assertEquals(20, ttl("/api/watch-history/continue?limit=12"))
        assertEquals(120, ttl("/api/media/media-1"))
        assertEquals(3600, ttl("/api/media/media-1/subtitles?include_embedded=false"))
        assertEquals(300, ttl("/api/media/media-1/episodes"))
        assertEquals(300, ttl("/api/libraries/lib-1/facets"))
        assertEquals(300, ttl("/api/libraries/lib-1/series/episodes?key=abc"))
        assertEquals(1800, ttl("/api/danmaku/media-1"))
        assertEquals(60, ttl("/api/reader/books"))
    }

    @Test
    fun `realtime endpoints are never cached`() {
        // 播放地址带令牌，续播进度必须实时；收藏状态刚切换就要生效。
        assertNull(ttl("/api/playback/media-1/info"))
        assertNull(ttl("/api/playback/media-1/resume"))
        assertNull(ttl("/api/media/media-1/favorite/status"))
        assertNull(ttl("/api/favourites"))
        assertNull(ttl("/api/watch-history"))
        assertNull(ttl("/api/history", post = true))
        assertNull(ttl("/api/media"))
    }

    @Test
    fun `explicit refresh bypasses the cache`() {
        assertNull(ttl("/api/media/media-1/subtitles?refresh=1"))
    }
}
