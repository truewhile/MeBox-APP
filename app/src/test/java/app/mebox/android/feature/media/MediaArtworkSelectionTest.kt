package app.mebox.android.feature.media

import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.SeriesCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 海报卡的选图规则要和网页/手机一致：
 * 先 `poster_url`，再退回 `backdrop_url`，尺寸固定 480x600（网页 ARTWORK.posterCard）。
 */
class MediaArtworkSelectionTest {

    private val origin = "http://192.168.1.10:8080"

    private fun card(poster: String, backdrop: String = "") = SeriesCardDto(
        key = "series:1",
        rep = MediaDto(id = "1", title = "作品", posterUrl = poster, backdropUrl = backdrop),
    )

    @Test
    fun `series card prefers poster over backdrop`() {
        val url = seriesCardPoster(
            origin,
            card("D:\\test\\poster.jpg", "D:\\test\\backdrop.jpg"),
        )!!

        assertTrue(url.contains("D%3A%5Ctest%5Cposter.jpg"))
        assertTrue(url.contains("maxWidth=480"))
        assertTrue(url.contains("maxHeight=600"))
    }

    @Test
    fun `series card falls back to backdrop when poster is missing`() {
        val url = seriesCardPoster(origin, card("", "D:\\test\\backdrop.jpg"))!!

        assertTrue(url.contains("D%3A%5Ctest%5Cbackdrop.jpg"))
    }

    @Test
    fun `media poster prefers poster and falls back to backdrop`() {
        val preferred = mediaPosterImage(
            origin,
            MediaDto(id = "1", posterUrl = "poster.jpg", backdropUrl = "backdrop.jpg"),
        )!!
        assertTrue(preferred.contains("url=poster.jpg"))

        val fallback = mediaPosterImage(
            origin,
            MediaDto(id = "1", posterUrl = "", backdropUrl = "backdrop.jpg"),
        )!!
        assertTrue(fallback.contains("url=backdrop.jpg"))
    }

    @Test
    fun `no artwork produces no url`() {
        assertNull(seriesCardPoster(origin, card("")))
        assertNull(mediaPosterImage(origin, MediaDto(id = "1")))
        assertEquals("", MediaDto(id = "1").posterUrl)
    }
}
