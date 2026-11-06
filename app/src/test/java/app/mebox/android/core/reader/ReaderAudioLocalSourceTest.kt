package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import kotlinx.coroutines.CoroutineStart
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

    @Test fun `same chapter track ticket refresh replaces only that track variant`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("audio-$it")) }
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val account = app.mebox.android.core.model.AccountScope("server", "user")
            val book = app.mebox.android.core.network.ReaderBookDto("book", name = "书")
            val first = File(URI(source.prepare(server.url("/a.wma?ticket=one").toString(), account, book, 3, 0)))
            val other = File(URI(source.prepare(server.url("/b.wma").toString(), account, book, 3, 1)))
            val refreshed = File(URI(source.prepare(server.url("/a.wma?ticket=two").toString(), account, book, 3, 0)))
            assertFalse(first.exists())
            assertTrue(other.exists())
            assertTrue(refreshed.exists())
            assertEquals(1, source.listBookStorage(account).single().automaticChapters)
        }
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

    @Test fun `book sources are attributed privately and survive restart without secrets`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val shared = client()
            val source = ReaderAudioLocalSource(root, shared)
            val account = AccountScope("server/../one", "user/one")
            val book = ReaderBookDto("book/../one", name = "My book", origin = "secret-origin",
                bookUrl = "https://secret-book-url", coverUrl = "https://secret-cover", durChapterIndex = 7)
            val url = server.url("/track.wma?token=secret-url-token").toString()
            server.enqueue(MockResponse().setBody("complete audio"))
            val file = File(URI(source.prepare(url, account, book)))
            val trackDir = file.parentFile!!
            val chapterDir = trackDir.parentFile!!
            val bookDir = chapterDir.parentFile!!
            assertEquals("t0", trackDir.name)
            assertEquals("c7", chapterDir.name)
            assertTrue(bookDir.name.matches(Regex("[a-f0-9]{64}")))
            assertTrue(bookDir.parentFile!!.name.matches(Regex("[a-f0-9]{64}")))
            assertEquals(root.canonicalFile, bookDir.parentFile!!.parentFile!!.canonicalFile)
            assertEquals(file.toURI().toString(), source.prepare(url, account, book))
            assertEquals(1, server.requestCount)
            val metadata = File(bookDir, "book.json").readText()
            val fields = kotlinx.serialization.json.Json.parseToJsonElement(metadata)
                as kotlinx.serialization.json.JsonObject
            assertEquals(setOf("id", "name", "serverId", "userId"), fields.keys)
            root.walkTopDown().filter { it.isFile }.forEach {
                val text = it.name + it.readText()
                listOf("secret-url-token", "secret-origin", "secret-book-url", "secret-cover").forEach { secret ->
                    assertFalse(text.contains(secret))
                }
            }
            val restarted = ReaderAudioLocalSource(root, shared)
            val stats = restarted.listBookStorage(account).single()
            assertEquals(book.id, stats.bookId)
            assertEquals(book.name, stats.name)
            assertEquals(root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, stats.automaticBytes)
            assertEquals(1, stats.automaticChapters)
            assertEquals(0L, stats.downloadBytes)
            assertEquals(0, stats.downloadChapters)
            assertTrue(restarted.listBookStorage(account.copy(userId = "other")).isEmpty())
        }
    }

    @Test fun `attributed local URIs are not copied or counted twice`() = runBlocking {
        val root = temporary.newFolder()
        val existing = temporary.newFile("manual.wma").apply { writeText("manual download") }
        val source = ReaderAudioLocalSource(root, client())
        val account = AccountScope("server", "user")
        val book = ReaderBookDto("book")
        listOf(existing.toURI().toString(), "content://reader/manual/1").forEach {
            assertEquals(it, source.prepare(it, account, book))
            assertEquals(it, source.prepare(it, account, book, 42))
        }
        assertTrue(source.listBookStorage(account).isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(source.clearBook(account, book.id))
        assertTrue(source.clearAccount(account))
        assertTrue(existing.exists())
    }

    @Test fun `selective cleanup isolates accounts books and unowned legacy audio`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val account = AccountScope("server", "one")
            val other = AccountScope("server", "two")
            val otherServer = AccountScope("other-server", "one")
            val first = ReaderBookDto("first", name = "First")
            val second = ReaderBookDto("second", name = "Second")
            val url = server.url("/audio.ape").toString()
            repeat(5) { server.enqueue(MockResponse().setBody("audio")) }
            val legacy = File(URI(source.prepare(url)))
            val owned = File(URI(source.prepare(url, account, first)))
            val secondBook = File(URI(source.prepare(url, account, second)))
            val otherAccount = File(URI(source.prepare(url, other, first)))
            val anotherServer = File(URI(source.prepare(url, otherServer, first)))
            assertEquals(3, source.listBookStorage(account).size)
            val legacyStats = source.listBookStorage(account).single { it.bookId == ReaderAudioLocalSource.LEGACY_BOOK_ID }
            assertEquals(legacy.length(), legacyStats.automaticBytes)
            assertTrue(source.clearBook(account, first.id))
            assertFalse(owned.exists())
            assertTrue(secondBook.exists())
            assertTrue(otherAccount.exists())
            assertTrue(source.clearAccount(account))
            assertFalse(secondBook.exists())
            assertTrue(otherAccount.exists())
            assertTrue(anotherServer.exists())
            assertTrue(legacy.exists())
            assertTrue(source.clearBook(account, ReaderAudioLocalSource.LEGACY_BOOK_ID))
            assertFalse(legacy.exists())
            source.invalidate(url)
            assertFalse(otherAccount.exists())
            assertFalse(anotherServer.exists())
            assertTrue(source.listBookStorage(other).isEmpty())
        }
    }

    @Test fun `book and account clear cancel active attributed downloads without late files`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val account = AccountScope("server", "user")
            val book = ReaderBookDto("book")
            repeat(2) { index ->
                server.enqueue(MockResponse().setBody("late audio").setBodyDelay(1, TimeUnit.SECONDS))
                val task = async(Dispatchers.Default) {
                    runCatching { source.prepare(server.url("/late-$index.wma").toString(), account, book) }
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                if (index == 0) assertTrue(source.clearBook(account, book.id))
                else assertTrue(source.clearAccount(account))
                assertTrue(withTimeout(2_000) { task.await() }.isFailure)
                assertTrue(source.listBookStorage(account).isEmpty())
                assertTrue(root.walkTopDown().none { it.isFile })
            }
            server.enqueue(MockResponse().setBody("fresh"))
            assertEquals("fresh", File(URI(source.prepare(server.url("/fresh.wma").toString(), account, book))).readText())
            source.clear()
            assertTrue(source.listBookStorage(account).isEmpty())
        }
    }

    @Test fun `selective clear during attributed body streaming cleans partials and permits retry`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val account = AccountScope("server", "user")
            val book = ReaderBookDto("book")
            repeat(2) { index ->
                server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(128 * 1024) { 42 }))
                    .throttleBody(1024, 1, TimeUnit.SECONDS))
                val task = async(Dispatchers.Default) {
                    runCatching { source.prepare(server.url("/stream-$index.wma").toString(), account, book) }
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(3_000) {
                    while (root.walkTopDown().none { it.extension == "part" && it.length() > 0 }) delay(10)
                }
                // 清理无关账号/书不能中断当前请求。
                assertTrue(source.clearAccount(account.copy(userId = "other")))
                assertTrue(source.clearBook(account, "other-book"))
                assertFalse(task.isCompleted)
                if (index == 0) assertTrue(source.clearBook(account, book.id))
                else assertTrue(source.clearAccount(account))
                assertTrue(withTimeout(2_000) { task.await() }.isFailure)
                assertTrue(root.walkTopDown().none { it.isFile })
            }
            server.enqueue(MockResponse().setBody("retried"))
            assertEquals("retried", File(URI(source.prepare(server.url("/retry.wma").toString(), account, book))).readText())
        }
    }

    @Test fun `clear also invalidates attributed work queued behind another download`() = runBlocking {
        MockWebServer().use { server ->
            val source = ReaderAudioLocalSource(temporary.newFolder(), client())
            val account = AccountScope("server", "user")
            val first = ReaderBookDto("first")
            val queuedBook = ReaderBookDto("queued")
            server.enqueue(MockResponse().setBody("first audio").setBodyDelay(1, TimeUnit.SECONDS))
            val running = async(Dispatchers.Default) { source.prepare(server.url("/first.wma").toString(), account, first) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { source.prepare(server.url("/queued.wma").toString(), account, queuedBook) }
            }
            assertTrue(source.clearBook(account, queuedBook.id))
            withTimeout(3_000) { running.await() }
            assertTrue(withTimeout(2_000) { queued.await() }.isFailure)
            assertEquals(1, server.requestCount)
            assertEquals(listOf(first.id), source.listBookStorage(account).map { it.bookId })
        }
    }

    @Test fun `book window keeps only current plus minus five and counts chapters not tracks`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val source = ReaderAudioLocalSource(root, client())
            val account = AccountScope("server", "user")
            val book = ReaderBookDto("book")
            val files = (5..15).associateWith { index ->
                server.enqueue(MockResponse().setBody("audio $index"))
                File(URI(source.prepare(server.url("/$index.wma").toString(), account, book, index)))
            }
            (5..15).forEach { index ->
                server.enqueue(MockResponse().setBody("audio $index again"))
                source.prepare(server.url("/$index.wma").toString(), account, book, 10, index)
            }
            // 同一章多个分轨不能冒充多章。
            assertEquals(6, source.listBookStorage(account).single().automaticChapters)
            assertFalse(files.getValue(5).exists())
            assertFalse(files.getValue(9).exists())
            // 本轮为同章不同分轨，原始 t0 仍保留。
            assertTrue(files.getValue(10).exists())
            assertTrue(files.getValue(15).exists())
            // 模拟此前在窗口两侧播放过的缓存，验证完整十一章窗口的边界。
            val bookDir = files.getValue(10).parentFile!!.parentFile!!.parentFile!!
            (4..9).forEach { index ->
                val dir = File(bookDir, "c$index").apply { mkdirs() }
                files.getValue(15).copyTo(File(dir, files.getValue(15).name))
            }
            source.prepare(server.url("/10.wma").toString(), account, book, 10)
            assertEquals(11, source.listBookStorage(account).single().automaticChapters)
            assertFalse(File(bookDir, "c4").exists())
            assertTrue(File(bookDir, "c5").exists())
            server.enqueue(MockResponse().setBody("jump"))
            source.prepare(server.url("/jump.wma").toString(), account, book, 40)
            assertEquals(1, source.listBookStorage(account).single().automaticChapters)
            assertTrue(files.values.none { it.exists() })
        }
    }

    @Test fun `startup removes nested partials and global cap prunes across books and flat files`() = runBlocking {
        MockWebServer().use { server ->
            val root = temporary.newFolder()
            val oldest = File(root, "${"a".repeat(64)}.mp3")
            val nested = File(root, "account/book/c3").apply { mkdirs() }
            val newer = File(nested, "${"b".repeat(64)}.ape")
            val newest = File(nested, "${"c".repeat(64)}.wma")
            listOf(oldest, newer, newest).forEachIndexed { index, file ->
                RandomAccessFile(file, "rw").use { it.setLength(MAX_BYTES) }
                assertTrue(file.setLastModified((index + 1) * 1_000L))
            }
            File(nested, "interrupted.part").writeText("unfinished")
            File(root, "interrupted.part").writeText("unfinished")
            val source = ReaderAudioLocalSource(root, client())
            assertFalse(oldest.exists())
            assertTrue(newer.exists())
            assertTrue(newest.exists())
            assertTrue(root.walkTopDown().none { it.extension == "part" })
            server.enqueue(MockResponse().setBody("small audio"))
            source.prepare(server.url("/small.wma").toString(), AccountScope("server", "user"), ReaderBookDto("book"))
            assertFalse(newer.exists())
            assertTrue(newest.exists())
            val completedBytes = root.walkTopDown().filter { it.isFile && it.extension != "json" }.sumOf { it.length() }
            assertTrue(completedBytes <= 2 * MAX_BYTES)
            source.clear()
            assertTrue(root.walkTopDown().none { it.isFile })
        }
    }

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
