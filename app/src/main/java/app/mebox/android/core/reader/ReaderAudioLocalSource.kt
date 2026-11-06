package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * VLC 的私有本地音源。传入应用私有的专用目录，并在播放器内复用同一个实例。
 * 仅保存音频及最小归属信息：URL、Cookie、书源头和响应头都不写入文件或异常。
 * HLS 必须由播放器另行决定能否安全地通过 URI 播放，不能伪装成单个音频文件。
 */
class ReaderAudioLocalSource(
    private val cacheDir: File,
    httpClient: OkHttpClient,
) {
    // 保留共享连接池、拦截器和 CookieJar，但禁止 HTTP 缓存把签名 URL/头写入 journal。
    private val client = httpClient.newBuilder().cache(null).build()
    private val prepareMutex = Mutex()
    private val fileLock = Any()
    private var active: ActiveDownload? = null
    private var clearGeneration = 0L
    private var legacyGeneration = 0L
    private val accountGenerations = mutableMapOf<AccountScope, Long>()
    private val bookGenerations = mutableMapOf<Pair<AccountScope, String>, Long>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // 专用目录只由共享实例管理；清除上次进程退出遗留的半文件。
        synchronized(fileLock) {
            if (cacheDir.isDirectory) {
                cacheDir.walkTopDown().filter { it.isFile && it.extension == "part" }.forEach {
                    if (!it.delete()) throw IOException("无法清理本地音频")
                }
                prune(MAX_CACHE_BYTES)
            }
        }
    }

    /** 兼容旧调用：远程音源仍保存在未归属账号的平铺目录。 */
    suspend fun prepare(url: String): String = prepare(url, null)

    /** 本地 URI 原样返回，不复制已有缓存或手动下载，也不登记重复用量。 */
    suspend fun prepare(url: String, account: AccountScope, book: ReaderBookDto): String =
        prepare(url, account, book, book.durChapterIndex)

    /** 只下载当前请求；同书其他章节仅保留当前位置前后各五章，最多十一章。 */
    suspend fun prepare(
        url: String, account: AccountScope, book: ReaderBookDto, chapterIndex: Int, trackIndex: Int = 0,
    ): String = prepare(url, Ownership(account, book.id, book.name, chapterIndex.coerceAtLeast(0), trackIndex.coerceAtLeast(0)))

    private suspend fun prepare(url: String, owner: Ownership?): String {
        if (url.startsWith("file:", ignoreCase = true) ||
            url.startsWith("content://", ignoreCase = true)) return url
        // 在排队/切换 IO 调度器之前捕获代数，清理也会使尚未开始的旧任务失效。
        val expected = synchronized(fileLock) { generation(owner) }
        return withContext(Dispatchers.IO) {
            prepareMutex.withLock {
                try {
                    prepareRemote(url, owner, expected)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: UnsupportedOperationException) {
                    throw UnsupportedOperationException(HLS_MESSAGE, null)
                } catch (_: Exception) {
                    // 不保留 cause：OkHttp/文件系统异常可能带签名地址或私有路径。
                    ensureActive()
                    throw IOException("无法准备本地音频", null)
                }
            }
        }
    }

    /** 所有用量均为可自动清理的缓存；不猜测旧平铺文件的账号归属。 */
    fun listBookStorage(account: AccountScope): List<ReaderBookStorage> = synchronized(fileLock) {
        val books = accountDir(account).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val metadata = runCatching {
                json.decodeFromString(BookMetadata.serializer(), File(dir, METADATA_FILE).readText())
            }.getOrNull() ?: return@mapNotNull null
            if (metadata.serverId != account.serverId || metadata.userId != account.userId ||
                dir != bookDir(account, metadata.id)) return@mapNotNull null
            val completed = completedFiles(dir)
            val bytes = dir.walkTopDown().filter { it.isFile && it.extension != "part" }.sumOf { it.length() }
            if (bytes == 0L) return@mapNotNull null
            ReaderBookStorage(metadata.id, metadata.name, bytes, 0L,
                completed.map { file -> generateSequence(file.parentFile) { it.parentFile }
                    .firstOrNull { CHAPTER_NAME.matches(it.name) }?.name ?: file.parentFile.name }.distinct().size, 0)
        }.toMutableList()
        val legacy = legacyFiles()
        if (legacy.isNotEmpty()) {
            books += ReaderBookStorage(LEGACY_BOOK_ID, "旧版本地音频缓存（未归属账号）",
                legacy.sumOf { it.length() }, 0L, legacy.size, 0)
        }
        books.sortedBy { it.bookId }
    }

    fun clearBook(account: AccountScope, bookId: String): Boolean = synchronized(fileLock) {
        if (bookId == LEGACY_BOOK_ID) {
            // 历史文件没有账号归属；只在显式选择此条目时清理。
            legacyGeneration++
            active?.takeIf { it.owner == null }?.call?.cancel()
            legacyFiles().map { it.delete() }.all { it }
        } else {
            val key = account to bookId
            bookGenerations[key] = (bookGenerations[key] ?: 0L) + 1L
            active?.takeIf { it.owner?.account == account && it.owner.bookId == bookId }?.call?.cancel()
            val dir = bookDir(account, bookId)
            !dir.exists() || dir.deleteRecursively()
        }
    }

    /** 不触碰其他账号或没有归属的旧缓存。 */
    fun clearAccount(account: AccountScope): Boolean = synchronized(fileLock) {
        accountGenerations[account] = (accountGenerations[account] ?: 0L) + 1L
        active?.takeIf { it.owner?.account == account }?.call?.cancel()
        val dir = accountDir(account)
        !dir.exists() || dir.deleteRecursively()
    }

    /** 解码失败后移除同 URL 的完整文件（含已归属副本），下一次重新下载。 */
    fun invalidate(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val key = keyOf(parsed)
        synchronized(fileLock) {
            active?.takeIf { it.key == key }?.call?.cancel()
            completedFiles().filter { it.name.substringBefore('.') == key }.forEach {
                if (!it.delete()) throw IOException("无法移除本地音频")
                removeEmptyParents(it.parentFile)
            }
        }
    }

    /** 取消在途下载并清理本实例目录中的完整音源；临时文件由下载 finally 清理。 */
    fun clear() = synchronized(fileLock) {
        clearGeneration++
        active?.call?.cancel()
        completedFiles().forEach {
            if (!it.delete()) throw IOException("无法清理本地音频")
            removeEmptyParents(it.parentFile)
        }
    }

    private suspend fun prepareRemote(raw: String, owner: Ownership?, expected: Generation): String {
        val url = raw.toHttpUrlOrNull() ?: throw IOException("音频地址无效")
        rejectPlaylistPath(url)
        val target = targetOf(url, owner)
        val call = client.newCall(Request.Builder().url(url).build())
        synchronized(fileLock) {
            coroutineContext.ensureActive()
            checkGeneration(owner, expected)
            check(cacheDir.isDirectory || cacheDir.mkdirs())
            owner?.let { pruneBookWindow(it) }
            if (target.isFile && target.length() in 1..MAX_SOURCE_BYTES) {
                prune(MAX_CACHE_BYTES, target)
                owner?.let { writeMetadata(it) }
                target.setLastModified(System.currentTimeMillis())
                return target.toURI().toString()
            }
            if (target.exists() && !target.delete()) throw IOException("无法移除本地音频")
            active = ActiveDownload(keyOf(url), owner, call)
        }
        var temp: File? = null
        try {
            // execute/read 均是阻塞 IO。独立的取消观察协程会立即关闭 socket，
            // 外层结构化作用域则等待读取退出和 finally 删除临时文件后才结束。
            return coroutineScope {
                val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("音频请求失败")
                        rejectPlaylistPath(response.request.url)
                        val body = response.body ?: throw IOException("音频响应为空")
                        if (body.contentType()?.toString()?.contains("mpegurl", ignoreCase = true) == true) {
                            throw UnsupportedOperationException(HLS_MESSAGE)
                        }
                        val expectedBytes = body.contentLength()
                        if (expectedBytes == 0L || expectedBytes > MAX_SOURCE_BYTES) throw IOException("音频大小无效")
                        val source = body.source()
                        source.request(64)
                        val prefix = source.buffer.clone().readUtf8(minOf(64L, source.buffer.size))
                        if (prefix.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) {
                            throw UnsupportedOperationException(HLS_MESSAGE)
                        }
                        synchronized(fileLock) {
                            ensureActive()
                            if (call.isCanceled()) throw IOException("音频下载已取消")
                            checkGeneration(owner, expected)
                            // 提前为最坏情况预留空间，下载期间也不超过总额（128 MiB）。
                            prune(MAX_CACHE_BYTES - MAX_SOURCE_BYTES)
                            temp = File.createTempFile("reader-audio-", ".part", cacheDir)
                        }
                        val pending = checkNotNull(temp)
                        var written = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(pending).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    if (written > MAX_SOURCE_BYTES || (expectedBytes >= 0 && written > expectedBytes)) {
                                        throw IOException("音频大小无效")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                if (written == 0L || (expectedBytes >= 0 && written != expectedBytes)) {
                                    throw IOException("音频下载不完整")
                                }
                                output.fd.sync()
                            }
                        }
                        synchronized(fileLock) {
                            ensureActive()
                            if (call.isCanceled()) throw IOException("音频下载已取消")
                            checkGeneration(owner, expected)
                            check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                            owner?.let { writeMetadata(it) }
                            replaceFile(pending, target)
                            // 同章同音轨换票只保留新版本；不同音轨保留独立目录。
                            if (owner != null) completedFiles(target.parentFile).filter { it != target }.forEach { it.delete() }
                            target.setLastModified(System.currentTimeMillis())
                        }
                        target.toURI().toString()
                    }
                } finally {
                    cancellation.cancel()
                }
            }
        } finally {
            synchronized(fileLock) {
                temp?.delete()
                active = null
            }
        }
    }

    private fun prune(limit: Long, keep: File? = null) {
        val files = completedFiles()
        var total = files.sumOf { it.length() } + cacheDir.walkTopDown()
            .filter { it.isFile && it.name == METADATA_FILE }.sumOf { it.length() }
        files.filter { it != keep }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (total > limit) {
                    val size = file.length()
                    if (!file.delete()) throw IOException("本地音频缓存已满")
                    total -= size
                    removeEmptyParents(file.parentFile)
                    total = completedFiles().sumOf { it.length() } + cacheDir.walkTopDown()
                        .filter { it.isFile && it.name == METADATA_FILE }.sumOf { it.length() }
                }
            }
        if (total > limit) throw IOException("本地音频缓存已满")
    }

    private fun completedFiles(dir: File = cacheDir): List<File> =
        if (!dir.isDirectory) emptyList() else dir.walkTopDown()
            .filter { it.isFile && COMPLETED_NAME.matches(it.name) }.toList()

    private fun legacyFiles(): List<File> = cacheDir.listFiles().orEmpty()
        .filter { it.isFile && COMPLETED_NAME.matches(it.name) }

    private fun targetOf(url: HttpUrl, owner: Ownership?): File {
        val extension = url.pathSegments.lastOrNull().orEmpty()
            .substringAfterLast('.', "").lowercase(Locale.ROOT)
        val dir = owner?.let { File(File(bookDir(it.account, it.bookId), "c${it.chapterIndex}"), "t${it.trackIndex}") } ?: cacheDir
        return File(dir, "${keyOf(url)}.${extension.takeIf { it in EXTENSIONS } ?: "audio"}")
    }

    private fun accountDir(account: AccountScope): File =
        File(cacheDir, hash("${account.serverId.length}:${account.serverId}${account.userId}"))

    private fun bookDir(account: AccountScope, bookId: String): File = File(accountDir(account), hash(bookId))

    private fun keyOf(url: HttpUrl): String = hash(url.toString())

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun generation(owner: Ownership?): Generation = Generation(clearGeneration,
        if (owner == null) legacyGeneration else accountGenerations[owner.account] ?: 0L,
        owner?.let { bookGenerations[it.account to it.bookId] } ?: 0L)

    private fun checkGeneration(owner: Ownership?, expected: Generation) {
        if (generation(owner) != expected) throw CancellationException("缓存任务已失效")
    }

    private fun pruneBookWindow(owner: Ownership) {
        bookDir(owner.account, owner.bookId).listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val index = CHAPTER_NAME.matchEntire(dir.name)?.groupValues?.get(1)?.toLongOrNull()
                ?: return@forEach
            if (index !in (owner.chapterIndex.toLong() - 5)..(owner.chapterIndex.toLong() + 5) &&
                !dir.deleteRecursively()) throw IOException("无法清理本地音频")
        }
    }

    private fun writeMetadata(owner: Ownership) {
        val dir = bookDir(owner.account, owner.bookId)
        check(dir.isDirectory || dir.mkdirs())
        val metadata = BookMetadata(owner.bookId, owner.name, owner.account.serverId, owner.account.userId)
        val target = File(dir, METADATA_FILE)
        val text = json.encodeToString(BookMetadata.serializer(), metadata)
        if (target.isFile && target.readText() == text) return
        prune(MAX_CACHE_BYTES - (text.toByteArray().size.toLong() - target.length()).coerceAtLeast(0))
        val temp = File.createTempFile("metadata-", ".part", dir)
        try {
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replaceFile(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun removeEmptyParents(start: File) {
        var dir = start
        while (dir != cacheDir && dir.isDirectory) {
            val children = dir.listFiles() ?: return
            if (children.any { it.name != METADATA_FILE }) return
            children.forEach { if (!it.delete()) throw IOException("无法清理本地音频") }
            if (!dir.delete()) throw IOException("无法清理本地音频")
            dir = dir.parentFile ?: return
        }
    }

    private data class Ownership(val account: AccountScope, val bookId: String, val name: String, val chapterIndex: Int, val trackIndex: Int)
    private data class Generation(val all: Long, val account: Long, val book: Long)
    private data class ActiveDownload(val key: String, val owner: Ownership?, val call: Call)

    @Serializable
    private data class BookMetadata(val id: String, val name: String, val serverId: String, val userId: String)

    private fun rejectPlaylistPath(url: HttpUrl) {
        val path = url.encodedPath.lowercase(Locale.ROOT)
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) {
            throw UnsupportedOperationException(HLS_MESSAGE)
        }
    }

    private fun replaceFile(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** 仅用于显示和显式清理没有账号归属的历史平铺缓存。 */
        const val LEGACY_BOOK_ID = "reader-audio-local:legacy"
        private const val METADATA_FILE = "book.json"
        private val CHAPTER_NAME = Regex("c([0-9]+)")
        private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        private const val MAX_CACHE_BYTES = 128L * 1024 * 1024
        private const val HLS_MESSAGE = "HLS playlists require URI playback"
        private val EXTENSIONS = setOf("wma", "asf", "ape", "mp3", "m4a", "aac", "flac", "ogg", "oga", "wav", "opus", "mp4", "mka", "aiff", "aif", "amr", "ac3", "dts", "wv")
        private val COMPLETED_NAME = Regex("[a-f0-9]{64}\\.(?:${(EXTENSIONS + "audio").joinToString("|")})")
    }
}
