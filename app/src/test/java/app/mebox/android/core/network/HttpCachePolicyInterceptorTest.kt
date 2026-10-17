package app.mebox.android.core.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 端到端验证：命中策略的接口第二次请求真的不会走网络，实时接口则每次都
 * 回源。这类「策略写了但 OkHttp 不认」的问题只有在真实缓存链路上才看得出来。
 */
class HttpCachePolicyInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("mebox-http-cache").toFile()
        client = OkHttpClient.Builder()
            .cache(Cache(cacheDir, 4L * 1024L * 1024L))
            .addNetworkInterceptor(HttpCachePolicyInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun get(path: String, token: String = "test-token"): String {
        val request = Request.Builder()
            .url(server.url(path))
            .header("Authorization", "Bearer $token")
            .build()
        return client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            response.body?.string().orEmpty()
        }
    }

    @Test
    fun `library list is served from cache on the second call`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"libraries":[]}"""))

        get("/api/libraries?with_preview=1&include_total=0")
        get("/api/libraries?with_preview=1&include_total=0")

        assertEquals("second call must not hit the network", 1, server.requestCount)
    }

    @Test
    fun `realtime endpoints always hit the network`() {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"position_ms":1}"""))
        }

        get("/api/playback/media-1/resume")
        get("/api/playback/media-1/resume")

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cache entries are scoped to the authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"libraries":["a"]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"libraries":["b"]}"""))

        // 换账号/换令牌必须重新拉取，不能读到上一个账号的缓存。
        val first = get("/api/libraries", token = "token-a")
        val second = get("/api/libraries", token = "token-b")

        assertEquals(2, server.requestCount)
        assertTrue(first.contains("\"a\""))
        assertTrue(second.contains("\"b\""))
    }
}
