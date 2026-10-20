package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

class ReaderCacheStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = AccountScope("server", "user")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun store(root: File) = ReaderCacheStore(root, OkHttpClient(), json)

    @Test fun `partial image download is not counted as fully cached and retry completes it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("image-one"))
            server.enqueue(MockResponse().setResponseCode(500))
            val cache = store(temporary.newFolder())
            val content = ReaderChapterContentDto(type = "image", images = listOf("/1.jpg", "/2.jpg"))
            cache.save(scope, "book", 0, content, server.url("/").toString())
            assertEquals(ReaderCacheStatus.PARTIAL, cache.status(scope, "book", 0))
            assertTrue(cache.cachedIndexes(scope, "book").isEmpty())
            assertEquals(setOf(0), cache.partialIndexes(scope, "book"))
            server.enqueue(MockResponse().setBody("image-two"))
            cache.save(scope, "book", 0, content, server.url("/").toString())
            assertEquals(3, server.requestCount)
            assertEquals(setOf(0), cache.cachedIndexes(scope, "book"))
            val saved = cache.read(scope, "book", 0)!!
            File(URI(saved.images!![0])).delete()
            assertEquals(ReaderCacheStatus.PARTIAL, cache.status(scope, "book", 0))
        }
    }

    @Test fun `text with inline image is partial when its asset download fails`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val cache = store(temporary.newFolder())
            val content = ReaderChapterContentDto(content = "正文\n[img]/inline.jpg")
            cache.save(scope, "book", 0, content, server.url("/").toString())
            assertEquals(ReaderCacheStatus.PARTIAL, cache.status(scope, "book", 0))
            server.enqueue(MockResponse().setBody("image"))
            cache.save(scope, "book", 0, content, server.url("/").toString())
            assertEquals(ReaderCacheStatus.COMPLETE, cache.status(scope, "book", 0))
            assertTrue(cache.read(scope, "book", 0)!!.content.contains("[img]file:"))
        }
    }

    @Test fun `empty text missing tracks and HLS never claim complete cache`() = runBlocking {
        val cache = store(temporary.newFolder())
        cache.save(scope, "book", 0, ReaderChapterContentDto(content = "  "), "https://example.com")
        cache.save(scope, "book", 1, ReaderChapterContentDto(type = "audio", tracks = emptyList()), "https://example.com")
        cache.save(scope, "book", 2, ReaderChapterContentDto(type = "audio", hls = true, tracks = listOf("/a.m3u8")), "https://example.com")
        assertTrue(cache.cachedIndexes(scope, "book").isEmpty())
        assertEquals(setOf(0, 1, 2), cache.partialIndexes(scope, "book"))
    }

    @Test fun `file outside private cache is not trusted as complete`() = runBlocking {
        val external = temporary.newFile().apply { writeText("private elsewhere") }
        val cache = store(temporary.newFolder())
        cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf(external.toURI().toString())), "https://example.com")
        assertEquals(ReaderCacheStatus.PARTIAL, cache.status(scope, "book", 0))
    }

    @Test fun `offline shelf only includes text and comics with complete cached chapters`() = runBlocking {
        val cache = store(temporary.newFolder())
        cache.saveShelf(scope, listOf(ReaderBookDto("text"), ReaderBookDto("uncached"), ReaderBookDto("audio", type = 1)))
        cache.save(scope, "text", 0, ReaderChapterContentDto(content = "正文"), "https://example.com")
        cache.save(scope, "audio", 0, ReaderChapterContentDto(content = "not audio"), "https://example.com")
        assertEquals(listOf("text"), cache.offlineShelf(scope).map { it.id })
        cache.saveShelf(scope, emptyList())
        assertTrue(cache.offlineShelf(scope).isEmpty())
    }

    @Test fun `cancelled download cancels HTTP and removes temporary files`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val root = temporary.newFolder()
            val cache = store(root)
            val download = async(kotlinx.coroutines.Dispatchers.Default) {
                cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/slow.jpg")), server.url("/").toString())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            download.cancelAndJoin()
            assertTrue(download.isCancelled)
            assertEquals(ReaderCacheStatus.MISSING, cache.status(scope, "book", 0))
            assertTrue(root.walkTopDown().none { it.extension == "tmp" })
        }
    }

    @Test fun `account cleared during download cannot be recreated by its late response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("late image").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val root = temporary.newFolder()
            val cache = store(root)
            val download = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/slow.jpg")), server.url("/").toString())
                }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            cache.clearAccount(scope)
            download.await()
            assertEquals(ReaderCacheStatus.MISSING, cache.status(scope, "book", 0))
            assertTrue(root.walkTopDown().none { it.isFile })
        }
    }

    @Test fun `book cleared during download stays missing while other books remain`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("late image").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val cache = store(temporary.newFolder())
            cache.save(scope, "other", 0, ReaderChapterContentDto(content = "保留正文"), server.url("/").toString())
            val download = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/slow.jpg")), server.url("/").toString())
                }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            cache.clear(scope, "book")
            download.await()
            assertEquals(ReaderCacheStatus.MISSING, cache.status(scope, "book", 0))
            assertEquals(ReaderCacheStatus.COMPLETE, cache.status(scope, "other", 0))
        }
    }

    @Test fun `revoked account generation rejects late shelf TOC and chapter writes`() = runBlocking {
        val root = temporary.newFolder()
        val cache = store(root)
        val accountGeneration = cache.generation(scope)
        val bookGeneration = cache.generation(scope, "book")
        cache.clearAccount(scope)
        runCatching { cache.saveShelf(scope, listOf(ReaderBookDto("book")), accountGeneration) }
        runCatching { cache.saveChapters(scope, "book", listOf(app.mebox.android.core.network.ReaderChapterDto(index = 0, title = "章")), bookGeneration) }
        runCatching { cache.save(scope, "book", 0, ReaderChapterContentDto(content = "旧正文"), "https://example.com", bookGeneration) }
        assertNull(cache.readShelf(scope))
        assertNull(cache.readChapters(scope, "book"))
        assertEquals(ReaderCacheStatus.MISSING, cache.status(scope, "book", 0))
        assertTrue(root.walkTopDown().none { it.isFile })
        cache.save(scope, "book", 0, ReaderChapterContentDto(content = "新会话正文"), "https://example.com")
        assertEquals(ReaderCacheStatus.COMPLETE, cache.status(scope, "book", 0))
    }

    @Test fun `failed download leaves no temporary file`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val root = temporary.newFolder()
            val cache = store(root)
            cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/denied.jpg")), server.url("/").toString())
            assertTrue(root.walkTopDown().none { it.extension == "tmp" })
            assertTrue(cache.cachedIndexes(AccountScope("server", "other"), "book").isEmpty())
        }
    }
}
