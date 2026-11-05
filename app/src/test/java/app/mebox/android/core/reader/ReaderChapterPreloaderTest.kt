package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ReaderChapterPreloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = AccountScope("server", "user")
    private val book = ReaderBookDto("book", origin = "source", bookUrl = "https://source/book")
    private val chapters = (0..12).map { ReaderChapterDto(index = it, title = "第${it}章", url = "/$it") }
    private val text = ReaderChapterContentDto(content = "正文")
    private fun cache() = ReaderCacheStore(temporary.newFolder(), OkHttpClient(), Json { encodeDefaults = true })

    @Test fun `window includes current and exactly five following readable chapters`() {
        assertEquals((2..7).toList(), readerPreloadIndexes(chapters, 2))
        val withVolumes = chapters.map { if (it.index in setOf(3, 5)) it.copy(isVolume = true) else it }
        assertEquals(listOf(2, 4, 6, 7, 8, 9), readerPreloadIndexes(withVolumes, 2))
        assertEquals(listOf(11, 12), readerPreloadIndexes(chapters, 11))
        assertTrue(readerPreloadIndexes(chapters, -1).isEmpty())
        assertTrue(readerPreloadIndexes(chapters, 13).isEmpty())
    }

    @Test fun `current is not requested twice and rolling window only fetches new chapter`() = runBlocking {
        val cache = cache()
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, index ->
            calls += index
            ApiResult.Success(text.copy(content = "正文$index"))
        })
        preloader.preload(this, book, chapters, 0, text)!!.join()
        assertEquals((1..5).toList(), calls.sorted())
        assertEquals((0..5).toSet(), cache.cachedIndexes(account, book.id))
        val next = preloader.get(book, 1)!!
        assertEquals("正文1", next.content)
        preloader.preload(this, book, chapters, 1, next)!!.join()
        assertEquals((1..6).toList(), calls.sorted())
        assertEquals((0..6).toSet(), cache.cachedIndexes(account, book.id))
    }

    @Test fun `one failed chapter does not stop rest and parsing concurrency is bounded`() = runBlocking {
        val cache = cache()
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, index ->
            val count = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            kotlinx.coroutines.delay(10)
            running.decrementAndGet()
            if (index == 2) ApiResult.NetworkError("failed") else ApiResult.Success(text)
        })
        preloader.preload(this, book, chapters, 0, text)!!.join()
        assertEquals(setOf(0, 1, 3, 4, 5), cache.cachedIndexes(account, book.id))
        assertTrue(peak.get() <= 2)
    }

    @Test fun `ordinary audio current plus following chapters are really downloaded and reused locally`() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setBody("audio-bytes")) }
            val cache = cache()
            fun audio(index: Int) = ReaderChapterContentDto(type = "audio", tracks = listOf("/$index.mp3"))
            val preloader = ReaderChapterPreloader(cache, account, server.url("/").toString(), { _, index ->
                ApiResult.Success(audio(index))
            })
            preloader.preload(this, book, chapters, 0, audio(0))!!.join()
            assertEquals(6, server.requestCount)
            assertEquals((0..5).toSet(), cache.cachedIndexes(account, book.id))
            val localTrack = preloader.get(book, 1)!!.tracks!!.single()
            assertTrue(localTrack.startsWith("file:"))
            assertEquals(localTrack, ReaderUrls.resolve(server.url("/").toString(), localTrack))
            assertNull(preloader.get(book, 6))
            assertNull(preloader.get(book, 1)!!.mediaHeaders)
        }
    }

    @Test fun `comic assets for current and following chapters are downloaded`() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setBody("image-bytes")) }
            val cache = cache()
            fun image(index: Int) = ReaderChapterContentDto(type = "image", images = listOf("/$index.jpg"))
            val preloader = ReaderChapterPreloader(cache, account, server.url("/").toString(), { _, index ->
                ApiResult.Success(image(index))
            })
            preloader.preload(this, book, chapters, 0, image(0))!!.join()
            assertEquals(6, server.requestCount)
            val localImage = preloader.get(book, 5)!!.images!!.single()
            assertTrue(localImage.startsWith("file:"))
            assertEquals(localImage, ReaderUrls.resolve(server.url("/").toString(), localImage))
        }
    }

    @Test fun `HLS only preloads addresses and never claims complete offline audio`() = runBlocking {
        MockWebServer().use { server ->
            val cache = cache()
            val audio = ReaderChapterContentDto(type = "audio", hls = true, tracks = listOf("/audio.m3u8"),
                mediaDirect = true, mediaHeaders = mapOf("Cookie" to "private-cookie"))
            val preloader = ReaderChapterPreloader(cache, account, server.url("/").toString(), { _, _ -> ApiResult.Success(audio) })
            preloader.preload(this, book, chapters, 0, audio)!!.join()
            assertEquals(0, server.requestCount)
            assertTrue(cache.cachedIndexes(account, book.id).isEmpty())
            assertEquals(audio, preloader.get(book, 1))
            assertNull(cache.read(account, book.id, 1)!!.mediaHeaders)
        }
    }

    @Test fun `cancelled old book cannot publish late results after switching book`() = runBlocking {
        val cache = cache()
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { id, _ ->
            if (id == book.id) { entered.complete(Unit); blocked.await() }
            ApiResult.Success(text)
        })
        val old = preloader.preload(this, book, chapters, 0, text)!!
        entered.await()
        val other = book.copy(id = "other", origin = "other-source")
        preloader.preload(this, other, chapters, 0, text)!!.join()
        old.join()
        assertTrue(old.isCancelled)
        assertEquals((0..5).toSet(), cache.cachedIndexes(account, other.id))
        assertNull(preloader.get(book, 1))
    }

    @Test fun `clearing cache during preload blocks late response writes`() = runBlocking {
        val cache = cache()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, _ ->
            entered.complete(Unit)
            proceed.await()
            ApiResult.Success(text)
        })
        val task = preloader.preload(this, book, chapters, 0, text)!!
        entered.await()
        cache.clear(account, book.id)
        proceed.complete(Unit)
        task.join()
        assertTrue(cache.cachedIndexes(account, book.id).isEmpty())
        assertNull(preloader.get(book, 1))
    }

    @Test fun `account revocation and offline mode prevent starting preload`() = runBlocking {
        val cache = cache()
        var calls = 0
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, _ -> calls++; ApiResult.Success(text) })
        cache.clearAccount(account)
        assertNull(preloader.preload(this, book, chapters, 0, text))
        val offline = ReaderChapterPreloader(cache, account, "https://server", { _, _ -> calls++; ApiResult.Success(text) }, enabled = false)
        assertNull(offline.preload(this, book, chapters, 0, text))
        assertNull(offline.get(book, 0))
        assertEquals(0, calls)
    }

    @Test fun `expired online audio and revoked session are not reused`() = runBlocking {
        val cache = cache()
        var now = 0L
        var current = true
        val audio = ReaderChapterContentDto(type = "audio", hls = true, tracks = listOf("/audio.m3u8"))
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, _ -> ApiResult.Success(audio) },
            isSessionCurrent = { current }, clock = { now })
        preloader.preload(this, book, chapters, 0, audio)!!.join()
        assertNotNull(preloader.get(book, 1))
        now = 5 * 60 * 1000L
        assertNull(preloader.get(book, 1))
        current = false
        assertNull(preloader.preload(this, book, chapters, 0, audio))
    }

    @Test fun `current is cached without waiting for a slow future chapter`() = runBlocking {
        val cache = cache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, index ->
            if (index == 1) { entered.complete(Unit); release.await() }
            ApiResult.Success(text)
        })
        val task = preloader.preload(this, book, chapters, 0, text)!!
        entered.await()
        kotlinx.coroutines.withTimeout(5_000) {
            while (!cache.hasChapter(account, book.id, 0)) kotlinx.coroutines.yield()
        }
        assertFalse(task.isCompleted)
        release.complete(Unit)
        task.join()
    }

    @Test fun `direct media downloads carry source headers but never persist credentials`() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setBody("audio")) }
            val cache = ReaderCacheStore(temporary.newFolder(),
                OkHttpClient.Builder().addInterceptor(ReaderMediaHeaderInterceptor()).build(), Json { encodeDefaults = true })
            val audio = ReaderChapterContentDto(type = "audio", tracks = listOf(server.url("/direct.mp3").toString()),
                mediaDirect = true, mediaHeaders = mapOf("Referer" to "https://source/book", "Cookie" to "private-cookie"))
            val preloader = ReaderChapterPreloader(cache, account, server.url("/").toString(), { _, _ -> ApiResult.Success(audio) })
            try {
                preloader.preload(this, book, chapters, 0, audio)!!.join()
                assertEquals(6, server.requestCount)
                repeat(6) {
                    val request = server.takeRequest()
                    assertEquals("https://source/book", request.getHeader("Referer"))
                    assertEquals("private-cookie", request.getHeader("Cookie"))
                }
                assertNull(cache.read(account, book.id, 1)!!.mediaHeaders)
            } finally { ReaderMediaHeaders.clear() }
        }
    }

    @Test fun `same book id with new source discards previous memory window`() = runBlocking {
        val cache = cache()
        val audio = ReaderChapterContentDto(type = "audio", hls = true, tracks = listOf("/old.m3u8"))
        var result = audio
        val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, _ -> ApiResult.Success(result) })
        preloader.preload(this, book, chapters, 0, audio)!!.join()
        assertEquals(audio, preloader.get(book, 1))
        val switched = book.copy(origin = "new-source")
        result = audio.copy(tracks = listOf("/new.m3u8"))
        preloader.preload(this, switched, chapters, 0, result)!!.join()
        assertNull(preloader.get(book, 1))
        assertEquals(result, preloader.get(switched, 1))
    }

    @Test fun `application owned preload survives unrelated page scope disposal`() = runBlocking {
        val cache = cache()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val preloader = ReaderChapterPreloader(cache, account, "https://server", { _, _ -> ApiResult.Success(text) })
            val task = preloader.preload(appScope, book, chapters, 0, text)!!
            pageScope.cancel()
            task.join()
            assertEquals((0..5).toSet(), cache.cachedIndexes(account, book.id))
            preloader.cancel()
        } finally { appScope.cancel(); pageScope.cancel() }
    }
}
