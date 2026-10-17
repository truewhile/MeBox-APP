package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImageUrlsTest {

    private val origin = "http://192.168.1.10:8080"

    @Test
    fun `local windows path is sent through the image proxy`() {
        val url = MediaImageUrls.poster(origin, """D:\test\movie\poster.jpg""")
        assertTrue(url!!.startsWith("$origin/api/img?url="))
        assertTrue(url.contains("D%3A%5Ctest%5Cmovie%5Cposter.jpg"))
        assertTrue(url.contains("maxWidth=480"))
        assertTrue(url.contains("maxHeight=600"))
    }

    @Test
    fun `remote http url is proxied and encoded`() {
        val url = MediaImageUrls.backdrop(origin, "https://image.tmdb.org/t/p/original/a b.jpg")
        assertTrue(url!!.contains("url=https%3A%2F%2Fimage.tmdb.org"))
        assertTrue(url.contains("%20a%20b.jpg") || url.contains("a%20b.jpg"))
        // 列表剧照与网页端 ARTWORK.backdropStrip 对齐（480x320）。
        assertTrue(url.contains("maxWidth=480"))
        assertTrue(url.contains("maxHeight=320"))
    }

    @Test
    fun `hero backdrop keeps a wide size`() {
        val url = MediaImageUrls.backdropHero(origin, "https://image.tmdb.org/t/p/original/hero.jpg")
        assertTrue(url!!.contains("maxWidth=1280"))
        assertTrue(url.contains("maxHeight=720"))
    }

    @Test
    fun `already proxied api path keeps its own query`() {
        val url = MediaImageUrls.poster(origin, "/api/img?url=x&refresh=1")
        assertEquals(
            "$origin/api/img?url=x&refresh=1&maxWidth=480&maxHeight=600&quality=80",
            url,
        )
    }

    @Test
    fun `blank artwork produces no url`() {
        assertNull(MediaImageUrls.poster(origin, ""))
        assertNull(MediaImageUrls.poster(origin, null))
    }
}
