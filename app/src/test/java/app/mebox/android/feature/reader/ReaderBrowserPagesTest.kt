package app.mebox.android.feature.reader

import app.mebox.android.core.network.ReaderBrowserPageDto
import org.junit.Assert.*
import org.junit.Test

class ReaderBrowserPagesTest {
    private fun page(id: String, seq: Long, mode: String = "open", source: String = "source") =
        ReaderBrowserPageDto(id = id, seq = seq, mode = mode, sourceId = source)

    @Test fun `closed open page is not reopened by repeated pending responses`() {
        val pages = ReaderBrowserPages()
        val closed = page("login", 1)
        pages.finish(closed, listOf(closed))
        repeat(5) {
            assertNull(pages.newest(null, pages.pending(listOf(closed), "source")))
        }
    }

    @Test fun `completed or cancelled wait page is not reopened`() {
        val pages = ReaderBrowserPages()
        val closed = page("captcha", 1, mode = "wait")
        pages.finish(closed, emptyList())
        assertTrue(pages.pending(listOf(closed), "source").isEmpty())
    }

    @Test fun `response captured before close cannot reopen page`() {
        val pages = ReaderBrowserPages()
        val closed = page("login", 1)
        val inFlightResponse = listOf(closed)
        pages.finish(closed, emptyList())
        assertNull(pages.newest(null, pages.pending(inFlightResponse, "source")))
    }

    @Test fun `closing newest page does not fall back to superseded page`() {
        val pages = ReaderBrowserPages()
        val old = page("line", 1)
        val latest = page("settings", 2)
        val pending = listOf(old, latest)
        assertEquals(latest, pages.newest(old, pages.pending(pending, "source")))
        pages.finish(latest, pending)
        assertNull(pages.newest(null, pages.pending(pending, "source")))
    }

    @Test fun `closing page still allows genuinely new pages including same URL`() {
        val pages = ReaderBrowserPages()
        val old = page("old", 1).copy(targetUrl = "https://source/login")
        val fresh = page("new", 2).copy(targetUrl = old.targetUrl)
        pages.finish(old, listOf(old))
        assertEquals(fresh, pages.newest(null, pages.pending(listOf(old, fresh), "source")))
    }

    @Test fun `closing does not discard a newer pending page`() {
        val pages = ReaderBrowserPages()
        val old = page("old", 1)
        val fresh = page("new", 2)
        pages.finish(old, listOf(old, fresh))
        assertEquals(listOf(fresh), pages.pending(listOf(old, fresh), "source"))
    }

    @Test fun `empty pending response and older pages do not replace active page`() {
        val pages = ReaderBrowserPages()
        val active = page("active", 2)
        assertEquals(active, pages.newest(active, emptyList()))
        assertEquals(active, pages.newest(active, listOf(page("old", 1))))
        assertEquals(active, pages.newest(active, listOf(active.copy(title = "changed"))))
    }

    @Test fun `pending ignores invalid and unrelated source pages but accepts legacy source`() {
        val pages = ReaderBrowserPages()
        val valid = page("valid", 1)
        val legacy = page("legacy", 2, source = "")
        assertEquals(listOf(valid, legacy), pages.pending(
            listOf(page("", 0), valid, page("foreign", 3, source = "other"), legacy),
            "source",
        ))
    }
}
