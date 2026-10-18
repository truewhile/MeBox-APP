package app.mebox.android.core.player

import app.mebox.android.core.network.APP_USER_AGENT
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SubtitleDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        directory = Files.createTempDirectory("mebox-subtitles-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun subtitle(ass: Boolean = true) = EngineSubtitle(
        url = server.url("/subtitle?token=private").toString(),
        label = "简体中文",
        assUrl = if (ass) server.url("/subtitle/ass?token=private").toString() else null,
    )

    @Test
    fun `ASS is downloaded unchanged with the app user agent and a local extension`() = runBlocking {
        val original = "[Script Info]\nTitle: 测试\n[Events]\nDialogue: {\\c&H00FFFF&}字幕"
        server.enqueue(MockResponse().setBody(original))
        val file = SubtitleDownloader(client, directory).download(subtitle())
        assertEquals(original, file.readText())
        assertEquals("ass", file.extension)
        assertFalse(file.name.contains("private"))
        val request = server.takeRequest()
        assertEquals("/subtitle/ass?token=private", request.path)
        assertEquals(APP_USER_AGENT, request.getHeader("User-Agent"))
    }

    @Test
    fun `failed subtitles leave no file and do not require a player reload`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val result = runCatching { SubtitleDownloader(client, directory).download(subtitle()) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test
    fun `oversized chunked subtitles are rejected without leaving partial files`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(100), 10))
        val result = runCatching {
            SubtitleDownloader(client, directory, maxBytes = 32).download(subtitle())
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test
    fun `a pending subtitle request can be cancelled when switching media`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { SubtitleDownloader(client, directory).download(subtitle()) }
        // Let the coroutine enqueue the asynchronous network call before waiting for the server.
        kotlinx.coroutines.yield()
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
        withTimeout(2_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test
    fun `WebVTT retains the correct file type`() = runBlocking {
        server.enqueue(MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHello"))
        val file = SubtitleDownloader(client, directory).download(subtitle(ass = false))
        assertEquals("vtt", file.extension)
        assertTrue(file.readText().startsWith("WEBVTT"))
    }
}
