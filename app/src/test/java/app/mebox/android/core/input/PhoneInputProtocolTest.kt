package app.mebox.android.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInputProtocolTest {

    @Test
    fun parseFormDecodesUtf8AndPlus() {
        val form = PhoneInputProtocol.parseForm(
            "type=search&query=%E7%B9%81%E8%8A%B1+%E6%97%A5%E8%AE%B0&note=a%2Bb",
        )
        assertEquals("search", form["type"])
        assertEquals("繁花 日记", form["query"])
        assertEquals("a+b", form["note"])
    }

    @Test
    fun serverEventRequiresAddress() {
        val event = PhoneInputProtocol.eventFromForm(
            mapOf("type" to "server", "address" to "  https://example.com  "),
        )
        assertEquals(
            PhoneInputEvent.Server("https://example.com"),
            event,
        )
        assertNull(
            PhoneInputProtocol.eventFromForm(mapOf("type" to "server", "address" to "   ")),
        )
    }

    @Test
    fun loginEventRequiresBothFields() {
        assertEquals(
            PhoneInputEvent.Login("alice", "secret"),
            PhoneInputProtocol.eventFromForm(
                mapOf("type" to "login", "username" to "alice", "password" to "secret"),
            ),
        )
        assertNull(
            PhoneInputProtocol.eventFromForm(
                mapOf("type" to "login", "username" to "alice", "password" to ""),
            ),
        )
    }

    @Test
    fun searchEventTrimsQuery() {
        assertEquals(
            PhoneInputEvent.Search("繁花"),
            PhoneInputProtocol.eventFromForm(mapOf("type" to "search", "query" to " 繁花 ")),
        )
        assertNull(PhoneInputProtocol.eventFromForm(mapOf("type" to "search", "query" to " ")))
        assertNull(PhoneInputProtocol.eventFromForm(mapOf("type" to "unknown")))
    }

    @Test
    fun pagePostsToTokenScopedSubmitPath() {
        val page = PhoneInputProtocol.page("AbC123")
        assertTrue(page.contains("action=\"/t/AbC123/submit\""))
        assertTrue(page.contains("name=\"type\" value=\"server\""))
        assertTrue(page.contains("name=\"type\" value=\"login\""))
        assertTrue(page.contains("name=\"type\" value=\"search\""))
    }

    @Test
    fun searchOnlyPageHidesServerAndLoginForms() {
        val page = PhoneInputProtocol.page("token", searchOnly = true)
        assertTrue(page.contains("value=\"search\""))
        assertTrue(!page.contains("value=\"server\""))
        assertTrue(!page.contains("value=\"login\""))
    }

    @Test
    fun pagesAreSelfContainedAndDoNotLeakSecrets() {
        val page = PhoneInputProtocol.page("token")
        assertTrue(page.contains("viewport"))
        assertTrue(!page.contains("http://") || page.contains("/t/token/submit"))
        assertTrue(PhoneInputProtocol.successPage().contains("已推送到电视"))
        assertTrue(PhoneInputProtocol.notFoundPage().contains("链接已失效"))
    }
}
