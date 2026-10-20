package app.mebox.android.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReaderApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MeBoxApiClient
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val address = (ServerAddress.parse(server.url("/").toString()) as ServerAddressResult.Valid).address
        client = MeBoxApiClient(createMobileApi(address, OkHttpClient(), json), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `progress reconciliation explicitly bypasses cached shelf`() = runTest {
        server.enqueue(MockResponse().setBody("""{"books":[]}"""))
        client.readerBooks("token", fresh = true)
        assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
    }

    @Test
    fun `shelf parses books and tolerates null lists`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"books":[
                  {"id":"b1","name":"长夜","author":"作者","type":2,"cover_url":"/api/reader/media?x=1",
                   "total_chapter_num":120,"dur_chapter_index":7,"dur_chapter_pos":3,"is_local":false}
                ]}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"books":null}"""))

        val first = client.readerBooks("token")
        val second = client.readerBooks("token")

        assertTrue(first is ApiResult.Success)
        val book = (first as ApiResult.Success).value.books.orEmpty().single()
        assertEquals("b1", book.id)
        assertTrue(book.isComic)
        assertEquals(7, book.durChapterIndex)
        assertTrue((second as ApiResult.Success).value.books.orEmpty().isEmpty())
        assertEquals("Bearer token", server.takeRequest().getHeader("Authorization"))
        assertEquals("/api/reader/books", server.takeRequest().path)
    }

    @Test
    fun `search sends key and page and parses origins`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"books":[
                  {"name":"长夜","author":"作者","cover_url":"https://img.example/1.jpg",
                   "book_url":"https://source/book/1",
                   "origins":[{"source_id":"s1","origin":"https://source","origin_name":"书源一",
                               "origin_type":0,"book_url":"https://source/book/1"}]}
                ],"skipped":[{"source_id":"s2","origin_name":"书源二","reason":"超时"}]}
                """.trimIndent(),
            ),
        )

        val result = client.readerSearch("token", key = "长夜", page = 2)

        assertTrue(result is ApiResult.Success)
        val value = (result as ApiResult.Success).value
        assertEquals(1, value.books.orEmpty().size)
        assertEquals("书源一", value.books.orEmpty().single().origins?.single()?.originName)
        assertEquals("超时", value.skipped.orEmpty().single().reason)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/reader/search", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"key\":\"长夜\""))
        assertTrue(body.contains("\"page\":2"))
    }

    @Test
    fun `content parses text comic and legacy errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"type":"text","content":"第一段\n第二段"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"type":"image","images":["/api/reader/media?b=1&u=a"],"image_style":"default"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"章节缓存为空，请先在详情页刷新目录"}""",
            ),
        )

        val text = client.readerContent("token", "b1", 1)
        val comic = client.readerContent("token", "b1", 2)
        val failed = client.readerContent("token", "b1", 3)

        assertTrue(text is ApiResult.Success)
        assertEquals("text", (text as ApiResult.Success).value.type)
        assertTrue(text.value.content.contains("第一段"))
        assertTrue(comic is ApiResult.Success)
        assertEquals(listOf("/api/reader/media?b=1&u=a"), (comic as ApiResult.Success).value.images)
        assertTrue(failed is ApiResult.Failure)
        assertEquals("章节缓存为空，请先在详情页刷新目录", (failed as ApiResult.Failure).message)
        assertEquals("/api/reader/books/b1/content?chapter=1", server.takeRequest().path)
        assertEquals("/api/reader/books/b1/content?chapter=2", server.takeRequest().path)
        assertEquals("/api/reader/books/b1/content?chapter=3", server.takeRequest().path)
    }

    @Test
    fun `progress and chapters use documented methods and fields`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"chapters":[{"index":0,"title":"第一章","url":"u1","is_volume":false}]}""",
            ),
        )

        val progress = client.readerSaveProgress("token", "b1", chapterIndex = 3, pos = 9, chapterTitle = "第三章")
        val chapters = client.readerChapters("token", "b1")

        assertTrue(progress is ApiResult.Success)
        assertTrue(chapters is ApiResult.Success)
        assertEquals("第一章", (chapters as ApiResult.Success).value.chapters.orEmpty().single().title)

        val progressRequest = server.takeRequest()
        assertEquals("PUT", progressRequest.method)
        assertEquals("/api/reader/books/b1/progress", progressRequest.path)
        val body = progressRequest.body.readUtf8()
        assertTrue(body.contains("\"chapter_index\":3"))
        assertTrue(body.contains("\"pos\":9"))
        assertTrue(body.contains("\"chapter_title\":\"第三章\""))

        val chapterRequest = server.takeRequest()
        assertEquals("GET", chapterRequest.method)
        assertEquals("/api/reader/books/b1/chapters", chapterRequest.path)
    }

    @Test
    fun `profile round trip keeps unknown fields intact`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"profile":{"theme_id":"preset2","font_size":20,"line_height":2.0,
                   "shelf_sort":"name","audio_speed":1.4,"shelf_layout":"list"}}""",
            ),
        )

        val result = client.readerProfile("token")

        assertTrue(result is ApiResult.Success)
        val profile = (result as ApiResult.Success).value.profile
        assertEquals("preset2", profile?.themeId)
        assertEquals(20, profile?.fontSize)
        assertEquals("name", profile?.shelfSort)
        assertEquals(1.4, profile?.audioSpeed ?: 0.0, 0.001)
        assertEquals("list", profile?.shelfLayout)
    }

    @Test
    fun `source login exposes fields values and login result`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sources":[{"id":"s1","name":"书源一","source_url":"https://source",
                   "has_login":true,"needs_browser":false}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"source_id":"s1","source_name":"书源一","has_login_js":true,
                   "fields":[{"name":"account","type":"text","viewName":"账号","default":"a"},
                             {"name":"pwd","type":"password","viewName":"密码"},
                             {"name":"go","type":"button","action":"login(true)","viewName":"登录"}],
                   "values":{"account":"saved"},"cookies":{"source":"k=v"},"logged_in":true}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"toasts":["登录成功"],"browsers":[{"url":"https://source/login","title":"登录页"}],
                   "values":{"account":"saved"},"cookies":{"source":"k=v"},"logged_in":true}""",
            ),
        )

        val sources = client.readerSources("token")
        val login = client.readerSourceLogin("token", "s1")
        val action = client.readerRunSourceLogin(
            "token",
            "s1",
            action = "login(true)",
            fields = mapOf("account" to "saved", "pwd" to "pw"),
        )

        assertTrue(sources is ApiResult.Success)
        assertTrue((sources as ApiResult.Success).value.sources.orEmpty().single().hasLogin)
        assertTrue(login is ApiResult.Success)
        val info = (login as ApiResult.Success).value
        assertEquals("账号", info.fields.orEmpty().first().viewName)
        assertEquals("saved", info.values["account"])
        assertTrue(info.loggedIn)
        assertTrue(action is ApiResult.Success)
        assertEquals("登录成功", (action as ApiResult.Success).value.toasts.orEmpty().single())
        assertEquals(1, action.value.browsers.orEmpty().size)

        assertEquals("/api/reader/sources", server.takeRequest().path)
        assertEquals("/api/reader/sources/s1/login", server.takeRequest().path)
        val requested = server.takeRequest()
        assertEquals("POST", requested.method)
        val body = requested.body.readUtf8()
        assertTrue(body.contains("\"action\":\"login(true)\""))
        assertTrue(body.contains("\"account\":\"saved\""))
    }

    @Test
    fun `browser pending result and xhr round trip`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"pages":[{"id":"p1","title":"登录","mode":"wait","seq":2,
                   "page_url":"/api/reader/browser/page?id=p1&s=sig","source_id":"s1"}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"content_type":"application/json","body":"eyJvayI6dHJ1ZX0=","base64":true}""",
            ),
        )

        val pending = client.readerBrowserPending("token", "s1")
        val submitted = client.readerBrowserResult("token", "p1", "<html/>", "https://source/a", false)
        val xhr = client.readerBrowserXhr(
            "token",
            "p1",
            "https://source/api",
            "POST",
            mapOf("X-Test" to "1"),
            "{}",
        )

        assertTrue(pending is ApiResult.Success)
        assertEquals("p1", (pending as ApiResult.Success).value.pages.orEmpty().single().id)
        assertTrue(submitted is ApiResult.Success)
        assertTrue(xhr is ApiResult.Success)
        assertEquals(200, (xhr as ApiResult.Success).value.status)
        assertTrue(xhr.value.base64)

        assertEquals("/api/reader/browser/pending?source_id=s1", server.takeRequest().path)
        val resultRequest = server.takeRequest()
        assertEquals("/api/reader/browser/result", resultRequest.path)
        assertTrue(resultRequest.body.readUtf8().contains("\"body\":\"<html/>\""))
        val xhrRequest = server.takeRequest()
        assertEquals("/api/reader/browser/xhr", xhrRequest.path)
        assertTrue(xhrRequest.body.readUtf8().contains("\"method\":\"POST\""))
    }

    @Test
    fun `content batch and origin switch parse results`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"chapter_index":1,"type":"text","content":"正文","cached":false},
                            {"chapter_index":2,"error":"超时"}],
                   "stats":{"hit":1,"miss":1,"failed":1}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"b1","name":"书","origin":"https://new","book_url":"https://new/book"}""",
            ),
        )

        val batch = client.readerContentBatch("token", "b1", listOf(1, 2))
        val switched = client.readerSwitchOrigin(
            "token",
            "b1",
            ReaderSearchOriginDto(origin = "https://new", originName = "新源", bookUrl = "https://new/book"),
        )

        assertTrue(batch is ApiResult.Success)
        val value = (batch as ApiResult.Success).value
        assertEquals(2, value.items.orEmpty().size)
        assertEquals("超时", value.items.orEmpty()[1].error)
        assertEquals(1, value.stats.failed)
        assertTrue(switched is ApiResult.Success)
        assertEquals("https://new", (switched as ApiResult.Success).value.origin)

        assertEquals("/api/reader/books/b1/content-batch", server.takeRequest().path)
        val originRequest = server.takeRequest()
        assertEquals("/api/reader/books/b1/origin", originRequest.path)
        assertTrue(originRequest.body.readUtf8().contains("\"origin\":\"https://new\""))
    }

    @Test
    fun `comment page is returned as signed browser page`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"c1","title":"段评","mode":"open","page_url":"/api/reader/browser/page?id=c1&s=sig"}""",
            ),
        )

        val result = client.readerOpenComment("token", "b1", "https://source/comment", "段评")

        assertTrue(result is ApiResult.Success)
        assertEquals("c1", (result as ApiResult.Success).value.id)
        assertEquals("open", result.value.mode)
        val request = server.takeRequest()
        assertEquals("/api/reader/comments/open", request.path)
        assertTrue(request.body.readUtf8().contains("\"book_id\":\"b1\""))
    }

    @Test
    fun `book info and toc can be read before adding to shelf`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"name":"长夜","author":"作者","kind":"玄幻","word_count":"120 万字",
                   "latest_chapter":"第 120 章","intro":"简介","cover_url":"https://img/1.jpg",
                   "toc_url":"https://source/toc/1","book_url":"https://source/book/1"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"chapters":[{"index":0,"title":"第一章","url":"https://source/c/1"},
                                 {"index":1,"title":"第二章","url":"https://source/c/2"}]}""",
            ),
        )

        val info = client.readerBookInfo("token", "s1", "https://source", "https://source/book/1")
        val toc = client.readerToc(
            "token",
            sourceUrl = "https://source",
            bookUrl = "https://source/book/1",
            tocUrl = "https://source/book/1",
            sourceId = "s1",
        )

        assertTrue(info is ApiResult.Success)
        val detail = (info as ApiResult.Success).value
        assertEquals("长夜", detail.name)
        assertEquals("第 120 章", detail.latestChapter)
        assertEquals("https://source/toc/1", detail.tocUrl)
        assertTrue(toc is ApiResult.Success)
        assertEquals(2, (toc as ApiResult.Success).value.chapters.orEmpty().size)

        val infoRequest = server.takeRequest()
        assertEquals("/api/reader/book-info", infoRequest.requestUrl?.encodedPath)
        assertEquals("s1", infoRequest.requestUrl?.queryParameter("source_id"))
        assertEquals("https://source", infoRequest.requestUrl?.queryParameter("source_url"))
        assertEquals("https://source/book/1", infoRequest.requestUrl?.queryParameter("book_url"))

        val tocRequest = server.takeRequest()
        assertEquals("/api/reader/toc", tocRequest.requestUrl?.encodedPath)
        assertEquals("s1", tocRequest.requestUrl?.queryParameter("source_id"))
        assertEquals("https://source/book/1", tocRequest.requestUrl?.queryParameter("toc_url"))
    }
}
