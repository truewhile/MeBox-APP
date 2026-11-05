package app.mebox.android.core.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReaderAudioLocalSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clients = mutableListOf<OkHttpClient>()

    @After fun cleanUp() {
        ReaderMediaHeaders.clear()
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    private fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder.readTimeout(5, TimeUnit.SECONDS).build().also { clients += it }

    private suspend inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error is T)
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `local URIs pass through without even creating the cache directory`() = runBlocking {
        val unavailable = temporary.newFile("not-a-directory")
        val source = ReaderAudioLocalSource(File(unavailable, "cannot-create"), client())
        listOf("file:/does/not/exist.wma", "file:///does/not/exist.ape", "content://reader/42").forEach {
            assertEquals(it, source.prepare(it))
        }
        assertEquals(0L, unavailable.length())
    }

    @Test fun `completed audio is hashed private reused and invalidatable`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("first complete audio"))
            server.enqueue(MockResponse().setBody("replacement audio"))
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val url = server.url("/private-title.WMA?token=never-save-this").toString()
            val first = source.prepare(url)
            val file = File(URI(first))
            assertTrue(file.name.matches(Regex("[a-f0-9]{64}\\.wma")))
            assertEquals(root.canonicalFile, file.parentFile!!.canonicalFile)
            assertEquals("first complete audio", file.readText())
            assertEquals(first, source.prepare(url))
            assertEquals(1, server.requestCount)
            source.invalidate(url)
            assertFalse(file.exists())
            assertEquals(first, source.prepare(url))
            assertEquals("replacement audio", file.readText())
            assertEquals(2, server.requestCount)
            assertEquals(listOf(file.name), root.listFiles()!!.map { it.name })
            source.clear()
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun `known path extensions survive but query and untrusted extensions do not`() = runBlocking {
        MockWebServer().use { server ->
            val source = ReaderAudioLocalSource(temporary.newFolder(), client())
            val cases = mapOf(
                "/one.asf" to "asf", "/two.ape" to "ape", "/three.mp3" to "mp3",
                "/four.FLAC?token=abc" to "flac", "/media?name=a.wma" to "audio",
                "/audio.secret?token=abc.mp3" to "audio",
            )
            cases.forEach { (path, extension) ->
                server.enqueue(MockResponse().setBody("complete audio"))
                assertEquals(extension, File(URI(source.prepare(server.url(path).toString()))).extension)
            }
        }
    }

    @Test fun `shared media headers cookies and connection pool are used without saving credentials`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val token = "private-url-token"
            val cookieSecret = "private-cookie-secret"
            val referer = "https://private-source.example/secret"
            val url = server.url("/track.ape?token=$token").toString()
            val jar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = listOf(
                    Cookie.Builder().name("session").value(cookieSecret).domain(url.host).build(),
                )
            }
            ReaderMediaHeaders.register(listOf(url), mapOf("Referer" to referer, "User-Agent" to "reader-private-ua"))
            Cache(temporary.newFolder("http-cache"), 1_048_576L).use { cache ->
                val shared = client(OkHttpClient.Builder().cache(cache).cookieJar(jar)
                    .addInterceptor(ReaderMediaHeaderInterceptor()))
                val source = ReaderAudioLocalSource(root, shared)
                server.enqueue(MockResponse().setBody("complete audio")
                    .addHeader("Cache-Control", "max-age=3600")
                    .addHeader("X-Private-Response", "private-response-secret"))
                val result = File(URI(source.prepare(url)))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("session=$cookieSecret", request.getHeader("Cookie"))
                assertEquals(referer, request.getHeader("Referer"))
                assertEquals("reader-private-ua", request.getHeader("User-Agent"))
                assertEquals("/track.ape?token=$token", request.path)
                assertEquals("complete audio", result.readText())
                assertEquals(0, cache.writeSuccessCount())
                source.invalidate(url)
                server.enqueue(MockResponse().setBody("next audio"))
                source.prepare(url)
                assertEquals(1, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
                (root.walkTopDown() + cache.directory.walkTopDown()).filter { it.isFile }.forEach { file ->
                    val saved = file.name + file.readText()
                    listOf(token, cookieSecret, referer, "reader-private-ua", "private-response-secret").forEach {
                        assertFalse(saved.contains(it))
                    }
                }
            }
        }
    }

    @Test fun `playlist paths including redirects are never promoted to audio`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val pathError = failure<UnsupportedOperationException> {
                source.prepare(server.url("/live.M3U8?token=secret").toString())
            }
            assertEquals("HLS playlists require URI playback", pathError.message)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/live.m3u8"))
            server.enqueue(MockResponse().setBody("playlist even with misleading response body"))
            failure<UnsupportedOperationException> { source.prepare(server.url("/redirect").toString()) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun `HLS content type and sniffed magic including BOM are rejected`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val responses = listOf(
                MockResponse().addHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("playlist"),
                MockResponse().addHeader("Content-Type", "audio/x-mpegurl; charset=utf-8").setBody("playlist"),
                MockResponse().addHeader("Content-Type", "audio/mpeg").setChunkedBody("#EXTM3U\n#EXTINF:1\npart.ts", 1),
                MockResponse().setBody("\uFEFF \r\n#EXTM3U\npart.ts"),
            )
            responses.forEachIndexed { index, response ->
                server.enqueue(response)
                val error = failure<UnsupportedOperationException> {
                    source.prepare(server.url("/media-$index?token=secret").toString())
                }
                assertEquals("HLS playlists require URI playback", error.message)
                // 协程调试栈恢复可用已脱敏的原异常作 cause，检查整条链不含上游地址。
                generateSequence(error as Throwable?) { it.cause }.forEach {
                    assertFalse(it.toString().contains("secret"))
                }
                assertTrue(root.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun `unknown length chunked audio is read completely before promotion`() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(32_769) { (it % 251).toByte() }
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 313))
            val root = temporary.newFolder()
            val result = ReaderAudioLocalSource(root, client()).prepare(server.url("/audio.mp3").toString())
            assertArrayEquals(bytes, File(URI(result)).readBytes())
            assertEquals(1, root.listFiles()!!.size)
        }
    }

    @Test fun `declared oversized and empty audio are rejected without cache files`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            server.enqueue(MockResponse().setBody("too big").setHeader("Content-Length", MAX_BYTES + 1))
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setChunkedBody("", 1))
            repeat(3) {
                failure<IOException> { source.prepare(server.url("/audio-$it.mp3").toString()) }
                assertTrue(root.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun `streaming cap applies even without a declared length`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val data = Buffer()
            val block = ByteArray(8192) { 42 }
            repeat((MAX_BYTES / block.size).toInt()) { data.write(block) }
            data.writeByte(42)
            server.enqueue(MockResponse().setChunkedBody(data, block.size))
            val source = ReaderAudioLocalSource(root, client())
            failure<IOException> { source.prepare(server.url("/large.wma").toString()) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun `exact cap is accepted and oldest completed files are pruned to bound total disk`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val oldest = File(root, "${"a".repeat(64)}.mp3")
            val newer = File(root, "${"b".repeat(64)}.ape")
            listOf(oldest, newer).forEach { file -> RandomAccessFile(file, "rw").use { it.setLength(MAX_BYTES) } }
            assertTrue(oldest.setLastModified(1_000))
            assertTrue(newer.setLastModified(2_000))
            val data = Buffer()
            val block = ByteArray(8192) { 42 }
            repeat((MAX_BYTES / block.size).toInt()) { data.write(block) }
            server.enqueue(MockResponse().setBody(data))
            val source = ReaderAudioLocalSource(root, client())
            val result = File(URI(source.prepare(server.url("/complete.wma").toString())))
            assertEquals(MAX_BYTES, result.length())
            assertFalse(oldest.exists())
            assertTrue(newer.exists())
            assertEquals(2 * MAX_BYTES, root.listFiles()!!.sumOf { it.length() })
        }
    }

    @Test fun `length mismatch fails without promotion and subsequent retry starts fresh`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("truncated").setHeader("Content-Length", 100)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(MockResponse().setBody("complete replacement"))
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val url = server.url("/retry.asf?token=secret").toString()
            val error = failure<IOException> { source.prepare(url) }
            generateSequence(error as Throwable?) { it.cause }.forEach {
                assertFalse(it.toString().contains("secret"))
            }
            assertFalse(error.toString().contains("secret"))
            assertTrue(root.listFiles()!!.isEmpty())
            assertEquals("complete replacement", File(URI(source.prepare(url))).readText())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `HTTP errors and invalid URLs never expose addresses or causes`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            server.enqueue(MockResponse().setResponseCode(403).setBody("token=private-secret"))
            listOf(server.url("/private?token=private-secret").toString(), "ftp://host/private-secret", "private-secret").forEach {
                val error = failure<IOException> { source.prepare(it) }
                assertFalse(error.toString().contains("private-secret"))
                assertFalse(error.toString().contains("http"))
                generateSequence(error as Throwable?) { it.cause }.forEach {
                    assertFalse(it.toString().contains("private-secret"))
                    assertFalse(it.toString().contains("http"))
                }
                assertTrue(root.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun `cancellation while waiting for response cancels HTTP and leaves no files`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CountDownLatch(1)
            val shared = client(OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: okhttp3.Call) { cancelled.countDown() }
            }))
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, shared)
            val task = async(Dispatchers.Default) { source.prepare(server.url("/slow.mp3").toString()) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun `cancellation during body read removes unique temporary source and retry works`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(128 * 1024) { 42 }))
                .throttleBody(1024, 1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("retried successfully"))
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val url = server.url("/slow.wma").toString()
            val task = async(Dispatchers.Default) { source.prepare(url) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(3_000) {
                while (root.listFiles()!!.none { it.extension == "part" && it.length() > 0 }) delay(10)
            }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertTrue(root.listFiles()!!.isEmpty())
            assertEquals("retried successfully", File(URI(source.prepare(url))).readText())
        }
    }

    @Test fun `clear and invalidate during an active request prevent late promotion`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            repeat(2) { index ->
                server.enqueue(MockResponse().setBody("late audio").setBodyDelay(1, TimeUnit.SECONDS))
                val url = server.url("/late-$index.ape").toString()
                val task = async(Dispatchers.Default) { runCatching { source.prepare(url) } }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                if (index == 0) source.clear() else source.invalidate(url)
                assertTrue(withTimeout(2_000) { task.await() }.isFailure)
                assertTrue(root.listFiles()!!.isEmpty())
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
