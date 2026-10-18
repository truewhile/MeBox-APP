package app.mebox.android.core.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeBoxApiClientTest {

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
    fun `server info is parsed including capabilities`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "name":"MeBox",
                  "server_id":"srv-1",
                  "server_version":"MeBox-v1.2.3",
                  "api_version":"v1",
                  "min_supported_app_version_code":1,
                  "capabilities":{
                    "device_sessions":true,
                    "playback_sessions":false,
                    "reader_progress_v1":false,
                    "reader_content_batch":false,
                    "local_transcode":false
                  }
                }
                """.trimIndent()
            )
        )

        val result = client.serverInfo()

        assertTrue(result is ApiResult.Success)
        val info = (result as ApiResult.Success).value
        assertEquals("srv-1", info.serverId)
        assertEquals("MeBox-v1.2.3", info.serverVersion)
        assertTrue(info.capabilities.deviceSessions)
        assertEquals(false, info.capabilities.playbackSessions)
        assertEquals("/api/v1/server-info", server.takeRequest().path)
    }

    @Test
    fun `login sends device identity and parses tokens`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "auth_session_id":"session-1",
                  "user":{"id":"u1","username":"alice","role":"user","tier":"free","is_active":true},
                  "tokens":{"access_token":"a1","refresh_token":"r1","expires_in":3600,"token_type":"Bearer"}
                }
                """.trimIndent()
            )
        )

        val result = client.login(
            LoginRequest(
                username = "alice",
                password = "pw",
                deviceId = "device-1",
                deviceName = "Pixel",
                clientName = "MeBox Android",
                clientVersion = "0.1.0",
            )
        )

        assertTrue(result is ApiResult.Success)
        val auth = (result as ApiResult.Success).value
        assertEquals("session-1", auth.authSessionId)
        assertEquals("a1", auth.tokens.accessToken)
        assertEquals(3600L, auth.tokens.expiresIn)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/login", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"device_id\":\"device-1\""))
        assertTrue(body.contains("\"client_name\":\"MeBox Android\""))
        // 密码只出现在登录请求体里，不会被附加到查询串。
        assertTrue(recorded.requestUrl?.query == null)
    }

    @Test
    fun `library preview request carries carousel flags and cards`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {
                    "id":"lib-1",
                    "name":"电影",
                    "type":"movie",
                    "total":42,
                    "carousel_enabled":true,
                    "cards":[
                      {
                        "key":"k1",
                        "rep":{
                          "id":"m1",
                          "title":"Movie",
                          "poster_url":"/p.jpg",
                          "backdrop_url":"/b.jpg"
                        },
                        "count":1,
                        "is_series":false
                      }
                    ]
                  }
                ]
                """.trimIndent()
            )
        )

        val result = client.libraries(
            accessToken = "token-1",
            ids = "lib-1",
            withPreview = true,
            previewLimit = 10,
        )

        assertTrue(result is ApiResult.Success)
        val library = (result as ApiResult.Success).value.single()
        assertTrue(library.carouselEnabled)
        assertEquals(1, library.cards.size)
        assertEquals("m1", library.cards.single().rep.id)

        val recorded = server.takeRequest()
        assertEquals(
            "/api/libraries?ids=lib-1&with_preview=true&preview_limit=10",
            recorded.path,
        )
        assertEquals("Bearer token-1", recorded.getHeader("Authorization"))
    }

    @Test
    fun `favorites and watch history are parsed and deletable`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"items":[{"id":"m1","title":"Movie","poster_url":"/p.jpg","year":2024}]}
                """.trimIndent()
            )
        )
        val favorites = client.favorites("token-1")
        assertTrue(favorites is ApiResult.Success)
        assertEquals("m1", (favorites as ApiResult.Success).value.items.single().id)
        assertEquals("/api/favourites", server.takeRequest().path)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {
                    "id":"h1",
                    "media_id":"m1",
                    "position_ms":65000,
                    "duration_ms":7200000,
                    "watched_at":"2026-10-17T08:00:00Z",
                    "completed":false,
                    "media":{"id":"m1","title":"Movie","year":2024}
                  }
                ]
                """.trimIndent()
            )
        )
        val history = client.watchHistory("token-1", limit = 200)
        assertTrue(history is ApiResult.Success)
        val item = (history as ApiResult.Success).value.single()
        assertEquals("h1", item.id)
        assertEquals("m1", item.mediaId)
        assertEquals(65_000L, item.positionMs)
        assertEquals(7_200_000L, item.durationMs)
        assertEquals("Movie", item.media?.title)
        assertEquals("/api/watch-history?limit=200", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"removed":1}"""))
        val removed = client.clearWatchHistory("token-1", mediaId = "m1")
        assertTrue(removed is ApiResult.Success)
        val deleteRequest = server.takeRequest()
        assertEquals("DELETE", deleteRequest.method)
        assertEquals("/api/watch-history?media_id=m1", deleteRequest.path)
    }

    @Test
    fun `error contract exposes code request id and retry metadata`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "60")
                .setBody(
                    """
                    {"code":"RATE_LIMITED","message":"slow down","request_id":"req-1","retryable":true,"retry_after_sec":60}
                    """.trimIndent()
                )
        )

        val result = client.login(
            LoginRequest(
                username = "alice",
                password = "pw",
                deviceId = "device-1",
                deviceName = "Pixel",
                clientName = "MeBox Android",
                clientVersion = "0.1.0",
            )
        )

        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals(429, failure.status)
        assertEquals("RATE_LIMITED", failure.code)
        assertEquals("req-1", failure.requestId)
        assertTrue(failure.retryable)
        assertEquals(60, failure.retryAfterSec)
    }

    @Test
    fun `session request carries bearer token`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"auth_session_id":"session-1","user":{"id":"u1","username":"alice"},"session":{"device_id":"device-1"}}
                """.trimIndent()
            )
        )

        val result = client.session("access-token")

        assertTrue(result is ApiResult.Success)
        assertEquals("Bearer access-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `non json error body falls back to a stable code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>nope</html>"))

        val result = client.session("token")

        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals(401, failure.status)
        assertEquals("TOKEN_EXPIRED", failure.code)
    }

    @Test
    fun `connection failure is classified as network error`() = runTest {
        server.shutdown()

        val result = client.serverInfo()

        assertTrue(result is ApiResult.NetworkError)
    }

    @Test
    fun `danmaku request passes keyword and episode id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "enabled":true,
                  "source_type":"json",
                  "opacity":"0.8",
                  "font_size":"30",
                  "area":"0.6",
                  "raw":"{\"comments\":[]}",
                  "anime_title":"测试番",
                  "episode_title":"第 1 话",
                  "episode_id":42
                }
                """.trimIndent()
            )
        )

        val result = client.danmaku(
            accessToken = "access-token",
            mediaId = "m1",
            keyword = "关键字",
            episodeId = 42L,
        )

        assertTrue(result is ApiResult.Success)
        val value = (result as ApiResult.Success).value
        assertEquals(true, value.enabled)
        assertEquals("json", value.sourceType)
        assertEquals("测试番", value.animeTitle)
        assertEquals(42L, value.episodeId)

        val request = server.takeRequest()
        assertEquals("/api/danmaku/m1", request.requestUrl?.encodedPath)
        assertEquals("关键字", request.requestUrl?.queryParameter("kw"))
        assertEquals("42", request.requestUrl?.queryParameter("episodeId"))
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
    }

    @Test
    fun `pinned libraries and tags use the shared user profile endpoints`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"library_ids":["lib-2","lib-1"]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tags":[
                    {"name":"动画","library_ids":["lib-1"]},
                    {"name":"电影","library_ids":["lib-2","lib-3"]}
                  ]
                }
                """.trimIndent()
            )
        )

        val pinned = client.pinnedLibraries("access-token")
        val tags = client.libraryTags("access-token")

        assertTrue(pinned is ApiResult.Success)
        assertEquals(listOf("lib-2", "lib-1"), (pinned as ApiResult.Success).value.libraryIds)
        assertTrue(tags is ApiResult.Success)
        val parsedTags = (tags as ApiResult.Success).value.tags
        assertEquals(2, parsedTags.size)
        assertEquals("动画", parsedTags[0].name)
        assertEquals(listOf("lib-1"), parsedTags[0].libraryIds)
        assertEquals(listOf("lib-2", "lib-3"), parsedTags[1].libraryIds)

        val pinnedRequest = server.takeRequest()
        assertEquals("/api/me/pinned-libraries", pinnedRequest.requestUrl?.encodedPath)
        assertEquals("Bearer access-token", pinnedRequest.getHeader("Authorization"))
        val tagsRequest = server.takeRequest()
        assertEquals("/api/me/library-tags", tagsRequest.requestUrl?.encodedPath)
        assertEquals("Bearer access-token", tagsRequest.getHeader("Authorization"))
    }
}
