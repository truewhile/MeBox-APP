package app.mebox.android.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderUrlsTest {

    private val origin = "https://mebox.example"

    @Test
    fun `relative signed paths are joined to the server origin`() {
        assertEquals(
            "https://mebox.example/api/reader/media?b=1&u=x",
            ReaderUrls.resolve(origin, "/api/reader/media?b=1&u=x"),
        )
        assertEquals(
            "https://mebox.example/path",
            ReaderUrls.resolve(origin, "path"),
        )
    }

    @Test
    fun `absolute and offline file urls pass through`() {
        assertEquals("https://cdn.example/a.jpg", ReaderUrls.resolve(origin, "https://cdn.example/a.jpg"))
        assertEquals(
            "file:///data/user/0/app/files/reader-cache/a.jpg",
            ReaderUrls.resolve(origin, "file:///data/user/0/app/files/reader-cache/a.jpg"),
        )
    }

    @Test
    fun `offline assets are renderable`() {
        assertTrue(ReaderUrls.isRenderable("file:///data/a.jpg"))
        assertTrue(ReaderUrls.isRenderable("/api/reader/media?b=1"))
        assertTrue(ReaderUrls.isRenderable("data:image/png;base64,AA=="))
        assertFalse(ReaderUrls.isRenderable(""))
        assertFalse(ReaderUrls.isRenderable("javascript:alert(1)"))
    }

    @Test
    fun `blank urls resolve to null`() {
        assertEquals(null, ReaderUrls.resolve(origin, null))
        assertEquals(null, ReaderUrls.resolve(origin, "  "))
    }
}
