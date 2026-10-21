package app.mebox.android.core.media

import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.SeriesCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryArtworkTest {

    private val origin = "http://192.168.1.10:8080"

    private fun card(poster: String, backdrop: String = "") = SeriesCardDto(
        key = "series:1",
        rep = MediaDto(id = "1", title = "作品", posterUrl = poster, backdropUrl = backdrop),
    )

    @Test
    fun `server generated cover wins over preview cards`() {
        val library = LibraryDto(
            id = "lib",
            coverImageUrl = "/api/libraries/lib/cover",
            coverUrl = "",
            cards = listOf(card("D:\\test\\poster.jpg", "D:\\test\\backdrop.jpg")),
        )

        val url = LibraryArtwork.cover(origin, library)!!

        // 服务端拼图接口：走 origin + /api/... 直连，由图片加载器补 Authorization，
        // 并透传尺寸档位让服务端直接产出该尺寸。
        assertTrue(url, url.startsWith("$origin/api/libraries/lib/cover"))
        assertTrue(url, url.contains("maxWidth=480"))
        assertTrue(url, url.contains("maxHeight=320"))
        assertTrue(url, !url.contains("/api/img"))
    }

    @Test
    fun `explicit cover wins`() {
        val library = LibraryDto(
            id = "lib",
            coverUrl = "https://example.com/cover.jpg",
            cards = listOf(card("D:\\test\\poster.jpg")),
        )

        val url = LibraryArtwork.cover(origin, library)!!

        assertTrue(url.contains("url=https%3A%2F%2Fexample.com%2Fcover.jpg"))
        assertTrue(url.contains("maxWidth=480"))
    }

    @Test
    fun `empty cover falls back to preview backdrop`() {
        val library = LibraryDto(
            id = "lib",
            coverUrl = "",
            cards = listOf(card("D:\\test\\poster.jpg", "D:\\test\\backdrop.jpg")),
        )

        val url = LibraryArtwork.cover(origin, library)!!

        assertTrue(url.contains("D%3A%5Ctest%5Cbackdrop.jpg"))
    }

    @Test
    fun `preview poster is used when backdrop is missing`() {
        val library = LibraryDto(
            id = "lib",
            cards = listOf(card("D:\\test\\poster.jpg")),
        )

        val url = LibraryArtwork.cover(origin, library)!!

        assertTrue(url.contains("D%3A%5Ctest%5Cposter.jpg"))
    }

    @Test
    fun `cards without artwork are skipped`() {
        val library = LibraryDto(
            id = "lib",
            cards = listOf(
                card(""),
                card("", "D:\\test\\backdrop-2.jpg"),
            ),
        )

        val url = LibraryArtwork.cover(origin, library)!!

        assertTrue(url.contains("D%3A%5Ctest%5Cbackdrop-2.jpg"))
    }

    @Test
    fun `no cover and no previews produces no url`() {
        assertNull(LibraryArtwork.cover(origin, LibraryDto(id = "lib")))
        assertEquals("", LibraryDto(id = "lib").coverUrl)
    }
}
