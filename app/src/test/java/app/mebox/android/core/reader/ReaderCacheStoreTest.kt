package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterDto
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

    @Test fun `changed TOC index mapping invalidates cached chapter content`() = runBlocking {
        val cache = store(temporary.newFolder())
        val old = listOf(
            app.mebox.android.core.network.ReaderChapterDto(index = 0, title = "一", url = "/one"),
            app.mebox.android.core.network.ReaderChapterDto(index = 1, title = "二", url = "/two"),
        )
        cache.saveChapters(scope, "book", old)
        cache.save(scope, "book", 1, ReaderChapterContentDto(content = "第二章"), "https://server")
        val before = cache.generation(scope, "book")
        cache.saveChapters(scope, "book", old.map { it.copy(title = "改名" + it.title) })
        assertEquals(before, cache.generation(scope, "book"))
        assertTrue(cache.hasChapter(scope, "book", 1))
        val inserted = listOf(old[0], old[1].copy(url = "/inserted"), old[1].copy(index = 2))
        cache.saveChapters(scope, "book", inserted)
        assertFalse(cache.hasChapter(scope, "book", 1))
        assertNotEquals(before, cache.generation(scope, "book"))
        assertEquals(inserted, cache.readChapters(scope, "book"))
    }

    @Test fun `invalidating damaged audio removes asset so retry can download replacement`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("not-really-audio"))
            server.enqueue(MockResponse().setBody("replacement-audio"))
            val cache = store(temporary.newFolder())
            val audio = ReaderChapterContentDto(type = "audio", tracks = listOf("/track.mp3"))
            cache.save(scope, "book", 0, audio, server.url("/").toString())
            val damaged = File(URI(cache.read(scope, "book", 0)!!.tracks!!.single()))
            assertTrue(damaged.exists())
            cache.invalidateChapter(scope, "book", 0)
            assertFalse(damaged.exists())
            assertFalse(cache.hasChapter(scope, "book", 0))
            cache.save(scope, "book", 0, audio, server.url("/").toString())
            assertEquals(2, server.requestCount)
            val replaced = File(URI(cache.read(scope, "book", 0)!!.tracks!!.single()))
            assertEquals("replacement-audio", replaced.readText())
        }
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

    @Test fun `automatic window trims readable neighbors but never downloads`() = runBlocking {
        val cache = store(temporary.newFolder())
        val chapters = (0..30).map { ReaderChapterDto(index = it, isVolume = it % 2 == 0) }
        val book = ReaderBookDto("book", name = "Window")
        for (index in chapters.indices) {
            cache.save(scope, book.id, index, ReaderChapterContentDto(content = "chapter $index"), "https://server",
                kind = ReaderCacheKind.AUTOMATIC)
        }
        cache.save(scope, book.id, 0, ReaderChapterContentDto(content = "explicit"), "https://server")
        cache.maintainWindow(scope, book, chapters, 15)
        assertEquals(ReaderCachePolicy.window(chapters, 15) + 0, cache.cachedIndexes(scope, book.id))
        val stats = cache.listBookStorage(scope).single()
        assertEquals(11, stats.automaticChapters)
        assertEquals(1, stats.downloadChapters)
        assertEquals("Window", stats.name)
    }

    @Test fun `promotion survives automatic clear and shelf removal across restart`() = runBlocking {
        val root = temporary.newFolder()
        val cache = store(root)
        val book = ReaderBookDto("https://server/book/中文", name = "Persisted name")
        cache.saveShelf(scope, listOf(book))
        cache.saveChapters(scope, book.id, listOf(ReaderChapterDto(title = "one")))
        cache.save(scope, book.id, 0, ReaderChapterContentDto(content = "automatic"), "https://server", kind = ReaderCacheKind.AUTOMATIC)
        cache.save(scope, book.id, 0, ReaderChapterContentDto(content = "manual"), "https://server")
        cache.save(scope, book.id, 0, ReaderChapterContentDto(content = "do not demote"), "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertEquals("manual", cache.read(scope, book.id, 0)!!.content)
        cache.saveShelf(scope, emptyList())
        val restarted = store(root)
        assertTrue(restarted.clearContent(scope, book.id, ReaderCacheKind.AUTOMATIC))
        assertTrue(restarted.hasChapter(scope, book.id, 0))
        val stats = restarted.listBookStorage(scope).single()
        assertEquals(book.id, stats.bookId)
        assertEquals(book.name, stats.name)
        assertEquals(1, stats.downloadChapters)
        assertEquals(0L, stats.automaticBytes)
        assertTrue(restarted.clearContent(scope, book.id, ReaderCacheKind.ALL))
        assertNull(restarted.readChapters(scope, book.id))
        assertEquals(0L, restarted.listBookStorage(scope).singleOrNull()?.totalBytes ?: 0L)
        assertTrue(restarted.clear(scope, book.id))
        assertNull(restarted.readChapters(scope, book.id))
        assertTrue(restarted.listBookStorage(scope).isEmpty())
    }

    @Test fun `legacy unknown payloads are protected downloads and clear handles orphan assets`() = runBlocking {
        val root = temporary.newFolder()
        val cache = store(root)
        cache.save(scope, "book", 0, ReaderChapterContentDto(content = "legacy"), "https://server")
        val dir = File(root, "server/user/book")
        File(dir, "chapter-0.cache.json").delete()
        val orphan = File(dir, "assets/c9-img-0.jpg").apply { writeText("orphan") }
        val unknown = File(dir, "old-format.bin").apply { writeText("unknown") }
        assertEquals(2, cache.listBookStorage(scope).single().downloadChapters)
        assertTrue(cache.listBookStorage(scope).single().downloadBytes >= orphan.length() + unknown.length())
        cache.clearContent(scope, "book", ReaderCacheKind.AUTOMATIC)
        assertTrue(cache.hasChapter(scope, "book", 0))
        assertTrue(orphan.exists())
        assertTrue(unknown.exists())
        cache.clearContent(scope, "book", ReaderCacheKind.DOWNLOAD)
        assertFalse(orphan.exists())
        assertFalse(unknown.exists())
        assertEquals(0L, cache.listBookStorage(scope).single().totalBytes)
    }


    @Test fun `global budget evicts oldest across accounts and protects active chapter and downloads`() = runBlocking {
        val root = temporary.newFolder()
        val cache = ReaderCacheStore(root, OkHttpClient(), json, automaticMaxBytes = 1900)
        val text = ReaderChapterContentDto(content = "x".repeat(600))
        cache.save(scope, "old", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        Thread.sleep(10)
        cache.save(scope, "active", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        cache.maintainWindow(scope, ReaderBookDto("active"), listOf(ReaderChapterDto()), 0)
        cache.save(scope, "manual", 0, ReaderChapterContentDto(content = "m".repeat(4000)), "https://server")
        val other = AccountScope("different-server", "different-user")
        cache.save(other, "new", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertFalse(cache.hasChapter(scope, "old", 0))
        assertTrue(cache.hasChapter(scope, "active", 0))
        assertTrue(cache.hasChapter(scope, "manual", 0))
        val total = (cache.listBookStorage(scope) + cache.listBookStorage(other)).sumOf { it.automaticBytes }
        assertTrue(total <= 1900)
        cache.save(other, "oversized", 0, ReaderChapterContentDto(content = "x".repeat(5000)), "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertTrue(cache.hasChapter(scope, "active", 0))
        assertTrue(cache.hasChapter(scope, "manual", 0))
        assertFalse(cache.hasChapter(other, "oversized", 0))
    }

    @Test fun `automatic streaming obeys budget and partial stats include staged bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(32768)).throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val root = temporary.newFolder()
            val cache = ReaderCacheStore(root, OkHttpClient(), json, automaticMaxBytes = 4096)
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/large.jpg")),
                    server.url("/").toString(), kind = ReaderCacheKind.AUTOMATIC)
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            val during = cache.listBookStorage(scope).single()
            assertTrue(during.automaticBytes > 0)
            assertTrue(during.automaticBytes <= 4096)
            assertEquals(1, during.automaticChapters)
            job.await()
            assertTrue(cache.listBookStorage(scope).single().automaticBytes <= 4096)
            assertEquals(ReaderCacheStatus.PARTIAL, cache.status(scope, "book", 0))
            assertTrue(root.walkTopDown().none { it.extension == "tmp" })
        }
    }

    @Test fun `startup cleans abandoned staging and orphan assets but not active downloads`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("live").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val root = temporary.newFolder()
            val cache = store(root)
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/live.jpg")), server.url("/").toString())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val dir = File(root, "server/user/book")
            val stale = File(dir, "abandoned.tmp").apply { writeText("stale") }
            val orphan = File(dir, "assets/c99-img-0.jpg").apply { writeText("orphan") }
            store(root)
            assertFalse(stale.exists())
            assertFalse(orphan.exists())
            job.await()
            assertTrue(cache.hasChapter(scope, "book", 0))
        }
    }


    @Test fun `selective clear revokes in flight writes and preserves TOC and downloads`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("late").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val root = temporary.newFolder()
            val cache = store(root)
            cache.saveChapters(scope, "book", listOf(ReaderChapterDto()))
            cache.save(scope, "book", 1, ReaderChapterContentDto(content = "manual"), "https://server")
            val generation = cache.generation(scope, "book")
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/late.jpg")),
                        server.url("/").toString(), kind = ReaderCacheKind.AUTOMATIC)
                }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(cache.clearContent(scope, "book", ReaderCacheKind.AUTOMATIC))
            job.await()
            assertNotEquals(generation, cache.generation(scope, "book"))
            assertFalse(cache.hasChapter(scope, "book", 0))
            assertTrue(cache.hasChapter(scope, "book", 1))
            assertNotNull(cache.readChapters(scope, "book"))
            assertEquals(0L, cache.listBookStorage(scope).single().automaticBytes)
            assertTrue(root.walkTopDown().none { it.extension == "tmp" })
        }
    }

    @Test fun `window trim never cancels same book manual download`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("manual-image").setBodyDelay(250, TimeUnit.MILLISECONDS))
            val cache = store(temporary.newFolder())
            val chapters = (0..20).map { ReaderChapterDto(index = it, url = "/$it") }
            cache.save(scope, "book", 0, ReaderChapterContentDto(content = "old-auto"), "https://server", kind = ReaderCacheKind.AUTOMATIC)
            val before = cache.generation(scope, "book")
            val download = async(kotlinx.coroutines.Dispatchers.Default) {
                cache.save(scope, "book", 15, ReaderChapterContentDto(type = "image", images = listOf("/manual.jpg")),
                    server.url("/").toString(), before, kind = ReaderCacheKind.DOWNLOAD)
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            cache.maintainWindow(scope, ReaderBookDto("book"), chapters, 10)
            download.await()
            assertEquals(before, cache.generation(scope, "book"))
            assertTrue(cache.hasChapter(scope, "book", 15))
            assertFalse(cache.hasChapter(scope, "book", 0))
        }
    }

    @Test fun `foreground reader and background audio have separate eviction pins`() = runBlocking {
        val cache = ReaderCacheStore(temporary.newFolder(), OkHttpClient(), json, automaticMaxBytes = 1900)
        val content = ReaderChapterContentDto(content = "x".repeat(600))
        cache.save(scope, "audio", 0, content, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        cache.pinCurrent("audio", scope, "audio", 0)
        cache.save(scope, "text", 0, content, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        cache.maintainWindow(scope, ReaderBookDto("text"), listOf(ReaderChapterDto()), 0, owner = "page")
        cache.save(scope, "third", 0, content, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertTrue(cache.hasChapter(scope, "audio", 0))
        assertTrue(cache.hasChapter(scope, "text", 0))
        assertFalse(cache.hasChapter(scope, "third", 0))
        cache.releaseCurrent("audio")
        cache.save(scope, "third", 0, content, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertFalse(cache.hasChapter(scope, "audio", 0))
        assertTrue(cache.hasChapter(scope, "text", 0))
        assertTrue(cache.hasChapter(scope, "third", 0))
    }

    @Test fun `metadata only books are visible bounded and fully clearable`() = runBlocking {
        val cache = ReaderCacheStore(temporary.newFolder(), OkHttpClient(), json, automaticMaxBytes = 500)
        repeat(20) { cache.registerBook(scope, ReaderBookDto("book-$it", name = "Book $it")) }
        val books = cache.listBookStorage(scope)
        assertTrue(books.isNotEmpty())
        assertTrue(books.size < 20)
        assertTrue(books.sumOf { it.automaticBytes } <= 500)
        val target = books.first()
        assertTrue(target.automaticBytes > 0)
        cache.clearContent(scope, target.bookId, ReaderCacheKind.ALL)
        assertEquals(0L, cache.listBookStorage(scope).firstOrNull { it.bookId == target.bookId }?.totalBytes ?: 0L)
    }

    @Test fun `zero automatic budget skips automatic but allows explicit downloads`() = runBlocking {
        val cache = ReaderCacheStore(temporary.newFolder(), OkHttpClient(), json, automaticMaxBytes = 0)
        val text = ReaderChapterContentDto(content = "text")
        cache.save(scope, "book", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertFalse(cache.hasChapter(scope, "book", 0))
        cache.save(scope, "book", 0, text, "https://server")
        assertTrue(cache.hasChapter(scope, "book", 0))
    }

    @Test fun `recent reads update persisted eviction order without stats refreshing every book`() = runBlocking {
        val root = temporary.newFolder()
        val cache = ReaderCacheStore(root, OkHttpClient(), json, automaticMaxBytes = 1900)
        val text = ReaderChapterContentDto(content = "x".repeat(600))
        cache.save(scope, "first", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        Thread.sleep(10)
        cache.save(scope, "second", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        Thread.sleep(10)
        assertNotNull(cache.read(scope, "first", 0))
        val restarted = ReaderCacheStore(root, OkHttpClient(), json, automaticMaxBytes = 1900)
        restarted.listBookStorage(scope)
        restarted.cachedIndexes(scope, "second")
        restarted.save(scope, "third", 0, text, "https://server", kind = ReaderCacheKind.AUTOMATIC)
        assertTrue(restarted.hasChapter(scope, "first", 0))
        assertFalse(restarted.hasChapter(scope, "second", 0))
    }


    @Test fun `oversized resource is rejected without storing its body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("small").setHeader("Content-Length", 64L * 1024 * 1024 + 1))
            val root = temporary.newFolder()
            val cache = store(root)
            cache.save(scope, "book", 0, ReaderChapterContentDto(type = "image", images = listOf("/huge.jpg")), server.url("/").toString())
            assertEquals(ReaderCacheStatus.PARTIAL, cache.status(scope, "book", 0))
            assertTrue(File(root, "server/user/book/assets").listFiles().orEmpty().isEmpty())
        }
    }

}
