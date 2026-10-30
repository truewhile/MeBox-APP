package app.mebox.android.core.reader

import android.content.Context
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.resume

/** PARTIAL 同时涵盖旧缓存的资源丢失与不支持的 HLS，不冒充完整离线。 */
enum class ReaderCacheStatus { MISSING, PARTIAL, COMPLETE }

data class ReaderCacheGeneration(val account: Long, val book: Long = 0L)

/** 服务端处理好的阅读内容缓存；不保存书源凭据、不解析书源规则。 */
class ReaderCacheStore(
    private val root: File,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    constructor(context: Context, httpClient: OkHttpClient, json: Json) :
        this(File(context.filesDir, "reader-cache"), httpClient, json)

    private val saveMutex = Mutex()
    private val writeLock = Any()
    private val accountGenerations = mutableMapOf<AccountScope, Long>()
    private val bookGenerations = mutableMapOf<Pair<AccountScope, String>, Long>()
    private val activeCalls = mutableMapOf<Call, Pair<AccountScope, String>>()

    fun generation(scope: AccountScope, bookId: String? = null): ReaderCacheGeneration = synchronized(writeLock) {
        ReaderCacheGeneration(accountGenerations[scope] ?: 0L,
            bookId?.let { bookGenerations[scope to it] } ?: 0L)
    }

    private fun <T> guardedWrite(
        scope: AccountScope, bookId: String?, expected: ReaderCacheGeneration, block: () -> T,
    ): T = synchronized(writeLock) {
        if (generation(scope, bookId) != expected) throw CancellationException("缓存任务已失效")
        block()
    }

    fun read(scope: AccountScope, bookId: String, chapterIndex: Int): ReaderChapterContentDto? =
        runCatching {
            json.decodeFromString(ReaderChapterContentDto.serializer(), chapterFile(scope, bookId, chapterIndex).readText())
        }.getOrNull()

    fun readChapters(scope: AccountScope, bookId: String): List<ReaderChapterDto>? = runCatching {
        json.decodeFromString(ListSerializer(ReaderChapterDto.serializer()), File(bookDir(scope, bookId), CHAPTERS_FILE).readText())
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun saveChapters(
        scope: AccountScope, bookId: String, chapters: List<ReaderChapterDto>,
        expected: ReaderCacheGeneration = generation(scope, bookId),
    ) = guardedWrite(scope, bookId, expected) {
        if (chapters.isNotEmpty()) {
            writeAtomically(File(bookDir(scope, bookId), CHAPTERS_FILE), json.encodeToString(ListSerializer(ReaderChapterDto.serializer()), chapters))
        }
    }

    fun readShelf(scope: AccountScope): List<ReaderBookDto>? = runCatching {
        json.decodeFromString(ListSerializer(ReaderBookDto.serializer()), File(accountDir(scope), SHELF_FILE).readText())
    }.getOrNull()

    fun saveShelf(
        scope: AccountScope, books: List<ReaderBookDto>,
        expected: ReaderCacheGeneration = generation(scope),
    ) = guardedWrite(scope, null, expected) {
        // 空书架也必须覆盖旧快照，避免移出的书在断网后重新出现。
        writeAtomically(File(accountDir(scope), SHELF_FILE), json.encodeToString(ListSerializer(ReaderBookDto.serializer()), books))
    }

    fun offlineShelf(scope: AccountScope): List<ReaderBookDto> = readShelf(scope).orEmpty()
        .filter { it.type in setOf(0, 2) && cachedIndexes(scope, it.id).isNotEmpty() }
        // 离线宿主不会因封面而向服务器/书源发出请求。
        .map { it.copy(coverUrl = "") }

    fun status(scope: AccountScope, bookId: String, chapterIndex: Int): ReaderCacheStatus {
        val content = read(scope, bookId, chapterIndex) ?: return ReaderCacheStatus.MISSING
        val complete = when (content.type) {
            "text" -> content.content.isNotBlank() && ReaderText.parse(content.content)
                .filter { it.isImage }.all { isPrivateAsset(it.imageUrl.orEmpty()) }
            "image" -> content.images?.takeIf { it.isNotEmpty() }?.all(::isPrivateAsset) == true
            "audio" -> !content.hls && !content.transcoding &&
                content.tracks?.takeIf { it.isNotEmpty() }?.all(::isPrivateAsset) == true
            else -> false
        }
        return if (complete) ReaderCacheStatus.COMPLETE else ReaderCacheStatus.PARTIAL
    }

    private fun isPrivateAsset(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "file") return false
        val file = File(uri).canonicalFile
        file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile && file.length() > 0
    }.getOrDefault(false)

    fun hasChapter(scope: AccountScope, bookId: String, chapterIndex: Int): Boolean =
        status(scope, bookId, chapterIndex) == ReaderCacheStatus.COMPLETE

    fun cachedIndexes(scope: AccountScope, bookId: String): Set<Int> = indexes(scope, bookId)
        .filter { status(scope, bookId, it) == ReaderCacheStatus.COMPLETE }.toSet()

    fun partialIndexes(scope: AccountScope, bookId: String): Set<Int> = indexes(scope, bookId)
        .filter { status(scope, bookId, it) == ReaderCacheStatus.PARTIAL }.toSet()

    private fun indexes(scope: AccountScope, bookId: String): List<Int> = bookDir(scope, bookId)
        .listFiles().orEmpty().mapNotNull {
            CHAPTER_PATTERN.matchEntire(it.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

    fun sizeBytes(scope: AccountScope, bookId: String): Long = bookDir(scope, bookId)
        .takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    fun clear(scope: AccountScope, bookId: String): Boolean = synchronized(writeLock) {
        val key = scope to bookId
        bookGenerations[key] = (bookGenerations[key] ?: 0L) + 1L
        activeCalls.filterValues { it == key }.keys.forEach { it.cancel() }
        val dir = bookDir(scope, bookId)
        !dir.exists() || dir.deleteRecursively()
    }

    fun clearAccount(scope: AccountScope): Boolean = synchronized(writeLock) {
        accountGenerations[scope] = (accountGenerations[scope] ?: 0L) + 1L
        activeCalls.filterValues { it.first == scope }.keys.forEach { it.cancel() }
        val dir = accountDir(scope)
        !dir.exists() || dir.deleteRecursively()
    }

    /** 失败资源保留在线地址以便重试，完整性必须单独由 status 判定。 */
    suspend fun save(
        scope: AccountScope,
        bookId: String,
        chapterIndex: Int,
        content: ReaderChapterContentDto,
        origin: String,
        expected: ReaderCacheGeneration = generation(scope, bookId),
    ): ReaderChapterContentDto = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            val assets = guardedWrite(scope, bookId, expected) {
                File(bookDir(scope, bookId), "assets").apply { check(isDirectory || mkdirs()) }
            }
            suspend fun asset(url: String, name: String) =
                downloadAsset(scope, bookId, expected, origin, url, assets, name)
            val images = content.images?.mapIndexed { index, url ->
                asset(url, "c$chapterIndex-img-$index") ?: url
            }
            val tracks = if (content.hls || content.transcoding) content.tracks else {
                content.tracks?.mapIndexed { index, url ->
                    asset(url, "c$chapterIndex-audio-$index") ?: url
                }
            }
            ensureActive()
            val text = if (content.type == "text") {
                content.content.lines().mapIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[img]")) {
                        val raw = trimmed.removePrefix("[img]").trim()
                        "[img]${asset(raw, "c$chapterIndex-inline-$index") ?: raw}"
                    } else line
                }.joinToString("\n")
            } else content.content
            // 落盘前清掉直连取媒体用的请求头：里面可能带书源登录 Cookie，
            // 而缓存文件是本机明文；离线读取时资源已换成本地 file:// 地址，
            // 也不再需要这些头。
            val cached = content.copy(
                content = text,
                images = images,
                tracks = tracks,
                mediaHeaders = null,
            )
            ensureActive()
            guardedWrite(scope, bookId, expected) {
                writeAtomically(chapterFile(scope, bookId, chapterIndex), json.encodeToString(ReaderChapterContentDto.serializer(), cached))
            }
            cached
        }
    }

    private suspend fun downloadAsset(
        scope: AccountScope, bookId: String, expected: ReaderCacheGeneration,
        origin: String, rawUrl: String, dir: File, name: String,
    ): String? {
        if (rawUrl.startsWith("file:") || rawUrl.startsWith("content:")) return rawUrl
        val url = ReaderUrls.resolve(origin, rawUrl) ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val target = File(dir, "$name-${shortHash(url)}${extensionOf(url)}")
        if (target.isFile && target.length() > 0) return target.toURI().toString()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(Request.Builder().url(url).build())
            val temp = guardedWrite(scope, bookId, expected) {
                File.createTempFile("asset-", ".tmp", dir).also { activeCalls[call] = scope to bookId }
            }
            continuation.invokeOnCancellation { call.cancel(); temp.delete() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(writeLock) { activeCalls.remove(call) }
                    temp.delete()
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onResponse(call: Call, response: Response) {
                    var result: String? = null
                    try {
                        response.use {
                            val body = it.body
                            if (it.isSuccessful && body != null && body.contentLength() <= MAX_ASSET_BYTES) {
                                var written = 0L
                                body.byteStream().use { input ->
                                    FileOutputStream(temp).use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        while (continuation.isActive) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            written += count
                                            if (written > MAX_ASSET_BYTES) throw IOException("资源超过缓存大小上限")
                                            output.write(buffer, 0, count)
                                        }
                                        output.fd.sync()
                                    }
                                }
                                if (continuation.isActive && written > 0) {
                                    replaceFile(temp, target)
                                    result = target.toURI().toString()
                                }
                            }
                        }
                    } catch (_: IOException) {
                        // 下载失败保留其它已缓存资源；取消由 continuation 原样传播。
                    } finally {
                        synchronized(writeLock) { activeCalls.remove(call) }
                        temp.delete()
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            })
        }
    }

    private fun writeAtomically(target: File, text: String) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temp = File.createTempFile("chapter-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            replaceFile(temp, target)
        } finally { temp.delete() }
    }

    private fun replaceFile(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun chapterFile(scope: AccountScope, bookId: String, chapterIndex: Int) =
        File(bookDir(scope, bookId), "chapter-$chapterIndex.json")
    private fun bookDir(scope: AccountScope, bookId: String) = File(accountDir(scope), safeName(bookId))
    private fun accountDir(scope: AccountScope) = File(File(root, safeName(scope.serverId)), safeName(scope.userId))
    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned == value && value.isNotBlank() && value !in setOf(".", "..") && value.length <= 64) value
        else "${cleaned.take(40)}-${shortHash(value)}"
    }
    private fun extensionOf(url: String): String {
        val extension = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.').lowercase()
        return if (extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus")) ".$extension" else ""
    }
    private fun shortHash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private companion object {
        val CHAPTER_PATTERN = Regex("chapter-(\\d+)\\.json")
        const val CHAPTERS_FILE = "chapters.json"
        const val SHELF_FILE = "shelf.json"
        const val MAX_ASSET_BYTES = 64L * 1024 * 1024
    }
}
