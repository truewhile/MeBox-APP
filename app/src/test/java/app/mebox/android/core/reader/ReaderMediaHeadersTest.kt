package app.mebox.android.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMediaHeadersTest {

    private val sampleHeaders = mapOf(
        "Referer" to "https://source.example.com/",
        "User-Agent" to "Mozilla/5.0 (reader)",
    )

    @Test
    fun `registered urls get their headers back`() {
        ReaderMediaHeaders.clear()
        val url = "https://cdn.example.com/audio/1.m4a"
        ReaderMediaHeaders.register(listOf(url), sampleHeaders)

        assertEquals(sampleHeaders, ReaderMediaHeaders.headersFor(url))
        assertEquals(mapOf<String, String>(), ReaderMediaHeaders.headersFor("https://other.example.com/2.m4a"))
    }

    @Test
    fun `option suffix is stripped before matching`() {
        ReaderMediaHeaders.clear()
        // 书源常在图片地址后带 ",{headers:{...}}" 选项段；服务端按基础地址下发，
        // 请求时 URL 也是基础地址，两边必须对齐才能命中。
        ReaderMediaHeaders.register(
            listOf("""https://img.example.com/1.webp,{"headers":{"Referer":"https://a/"}}"""),
            sampleHeaders,
        )

        assertEquals(
            sampleHeaders,
            ReaderMediaHeaders.headersFor("https://img.example.com/1.webp"),
        )
    }

    @Test
    fun `comma inside normal url is not treated as option suffix`() {
        // 只有逗号后紧跟 '{' 才是选项段，普通带逗号的地址不能被截断。
        val url = "https://img.example.com/a,b.webp"
        assertEquals(url, ReaderMediaHeaders.baseUrl(url))
        assertEquals(
            "https://img.example.com/1.webp",
            ReaderMediaHeaders.baseUrl("""https://img.example.com/1.webp , {"headers":{}}"""),
        )
    }

    @Test
    fun `proxy mode registers nothing`() {
        ReaderMediaHeaders.clear()
        // mediaDirect=false 时地址是服务端签名代理，不需要也不应该带书源头。
        ReaderMediaHeaders.registerChapter(
            mediaDirect = false,
            headers = sampleHeaders,
            tracks = listOf("https://cdn.example.com/audio/1.m4a"),
            images = null,
        )
        assertEquals(
            mapOf<String, String>(),
            ReaderMediaHeaders.headersFor("https://cdn.example.com/audio/1.m4a"),
        )
    }

    @Test
    fun `chapter registration covers tracks images and inline markers`() {
        ReaderMediaHeaders.clear()
        ReaderMediaHeaders.registerChapter(
            mediaDirect = true,
            headers = sampleHeaders,
            tracks = listOf("https://cdn.example.com/a.m4a"),
            images = listOf("https://img.example.com/1.webp"),
            content = "正文\n[img]https://img.example.com/inline.webp\n结束",
        )

        assertNotNull(ReaderMediaHeaders.headersFor("https://cdn.example.com/a.m4a"))
        assertNotNull(ReaderMediaHeaders.headersFor("https://img.example.com/1.webp"))
        // 文本型漫画源的正文用 [img] 标记内嵌图片地址，同样要覆盖。
        assertNotNull(ReaderMediaHeaders.headersFor("https://img.example.com/inline.webp"))
    }

    @Test
    fun `clear drops every registration`() {
        ReaderMediaHeaders.register(listOf("https://cdn.example.com/a.m4a"), sampleHeaders)
        ReaderMediaHeaders.clear()
        assertTrue(ReaderMediaHeaders.headersFor("https://cdn.example.com/a.m4a").isEmpty())
    }

    @Test
    fun `blank entries are dropped`() {
        ReaderMediaHeaders.clear()
        ReaderMediaHeaders.register(
            listOf("https://cdn.example.com/a.m4a"),
            mapOf("Referer" to "", "User-Agent" to "ua"),
        )
        assertEquals(
            mapOf("User-Agent" to "ua"),
            ReaderMediaHeaders.headersFor("https://cdn.example.com/a.m4a"),
        )
    }

    @Test
    fun `invalid base url yields null`() {
        assertNull(ReaderMediaHeaders.baseUrl("   "))
        assertEquals("https://a.example.com/x", ReaderMediaHeaders.baseUrl("https://a.example.com/x"))
    }
}
