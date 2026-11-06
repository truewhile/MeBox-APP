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
import kotlinx.serialization.Serializable
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
    private val automaticMaxBytes: Long = ReaderCachePolicy.AUTOMATIC_MAX_BYTES,
) {
    constructor(
        context: Context, httpClient: OkHttpClient, json: Json,
        automaticMaxBytes: Long = ReaderCachePolicy.AUTOMATIC_MAX_BYTES,
    ) : this(File(context.filesDir, "reader-cache"), httpClient, json, automaticMaxBytes)

    private val accountGenerations = mutableMapOf<AccountScope, Long>()
    private val bookGenerations = mutableMapOf<Pair<AccountScope, String>, Long>()
    private val activeCalls = mutableMapOf<Call, Pair<AccountScope, String>>()

    private val pinnedChapters = mutableMapOf<String, Pair<File, Int>>()
    private val automaticWindows = mutableMapOf<File, Set<Int>>()

    fun pinCurrent(owner: String, scope: AccountScope, bookId: String, index: Int) = synchronized(writeLock) {
        pinnedChapters[owner] = bookDir(scope, bookId) to index
    }

    fun releaseCurrent(owner: String) = synchronized(writeLock) { pinnedChapters.remove(owner); Unit }

    private fun inAutomaticWindow(dir: File, index: Int) = automaticWindows[dir]?.contains(index) != false

    init {
        require(automaticMaxBytes >= 0) { "automaticMaxBytes must not be negative" }
        // Only abandoned staging files are removed. A second store must not unlink a live transfer.
        synchronized(writeLock) {
            bookDirectories().forEach { dir ->
                payloadFiles(dir).filter { it.extension == "tmp" && !isActiveTemp(it) }.forEach { it.delete() }
                payloadFiles(dir).filter { asset ->
                    if (asset.parentFile != File(dir, "assets")) return@filter false
                    val index = assetIndex(asset)
                    index != null && !File(dir, "chapter-$index.json").isFile && !isActiveTemp(asset) &&
                        chapterKey(dir, index) !in activeSaves
                }.forEach { it.delete() }
                entries(dir).filter { chapterKey(dir, it.index) !in activeSaves }.forEach { entry ->
                    val content = runCatching {
                        json.decodeFromString(ReaderChapterContentDto.serializer(), File(dir, "chapter-${entry.index}.json").readText())
                    }.getOrNull()
                    if (content != null) removeUnreferencedAssets(dir, entry.index, content)
                }
            }
            enforceBudget(0)
        }
    }

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
        synchronized(writeLock) {
            readContent(scope, bookId, chapterIndex)?.also {
                val dir = bookDir(scope, bookId)
                val record = record(dir, chapterIndex)
                writeRecord(dir, chapterIndex, record.copy(lastAccess = System.currentTimeMillis()))
            }
        }

    private fun readContent(scope: AccountScope, bookId: String, chapterIndex: Int): ReaderChapterContentDto? =
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
            // 本机正文按章节位置保存。目录插章/删章/重排后不能把旧位置的正文当新章。
            val previous = readChapters(scope, bookId)
            val mappingChanged = previous != null && (previous.size != chapters.size ||
                previous.zip(chapters).any { (old, new) ->
                    old.url != new.url || old.isVolume != new.isVolume ||
                        (old.url.isBlank() && old.title != new.title)
                })
            if (mappingChanged) clearContent(scope, bookId, ReaderCacheKind.ALL)
            val dir = bookDir(scope, bookId)
            val encoded = json.encodeToString(ListSerializer(ReaderChapterDto.serializer()), chapters)
            val extra = (encoded.toByteArray().size.toLong() - File(dir, CHAPTERS_FILE).length()).coerceAtLeast(0)
            if (entries(dir).any { it.kind == ReaderCacheKind.DOWNLOAD } || enforceBudget(extra, dir to -1)) {
                writeAtomically(File(dir, CHAPTERS_FILE), encoded)
            }
        }
    }

    /** 明确重试时移除本章及其媒体文件，防止非空但损坏的音频被再次当成完整缓存。 */
    fun invalidateChapter(scope: AccountScope, bookId: String, chapterIndex: Int) = synchronized(writeLock) {
        val key = scope to bookId
        bookGenerations[key] = (bookGenerations[key] ?: 0L) + 1L
        activeCalls.filterValues { it == key }.keys.forEach { it.cancel() }
        deleteChapter(bookDir(scope, bookId), chapterIndex)
        Unit
    }

    fun readShelf(scope: AccountScope): List<ReaderBookDto>? = runCatching {
        json.decodeFromString(ListSerializer(ReaderBookDto.serializer()), File(accountDir(scope), SHELF_FILE).readText())
    }.getOrNull()

    fun saveShelf(
        scope: AccountScope, books: List<ReaderBookDto>,
        expected: ReaderCacheGeneration = generation(scope),
    ) = guardedWrite(scope, null, expected) {
        // Capture legacy identities before a refresh removes books from the shelf snapshot.
        readShelf(scope).orEmpty().forEach {
            if (readIdentity(bookDir(scope, it.id)) == null) registerBook(scope, it)
        }
        books.forEach { registerBook(scope, it) }
        // 空书架也必须覆盖旧快照，避免移出的书在断网后重新出现。
        writeAtomically(File(accountDir(scope), SHELF_FILE), json.encodeToString(ListSerializer(ReaderBookDto.serializer()), books))
    }

    fun offlineShelf(scope: AccountScope): List<ReaderBookDto> = readShelf(scope).orEmpty()
        .filter { it.type in setOf(0, 2) && cachedIndexes(scope, it.id).isNotEmpty() }
        // 离线宿主不会因封面而向服务器/书源发出请求。
        .map { it.copy(coverUrl = "") }

    fun status(scope: AccountScope, bookId: String, chapterIndex: Int): ReaderCacheStatus {
        val content = readContent(scope, bookId, chapterIndex) ?: return ReaderCacheStatus.MISSING
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

    /** Raw book directory size, including TOC/identity overhead; categorized stats exclude those. */
    fun sizeBytes(scope: AccountScope, bookId: String): Long = synchronized(writeLock) {
        payloadFiles(bookDir(scope, bookId)).sumOf { it.length() }
    }

    /** Small identity records survive shelf removal and selective content clearing. */
    fun registerBook(scope: AccountScope, book: ReaderBookDto) = synchronized(writeLock) {
        val dir = bookDir(scope, book.id)
        val identity = BookIdentity(book.id, book.name.ifBlank { book.id })
        if (readIdentity(dir) == identity) return@synchronized Unit
        val encoded = json.encodeToString(BookIdentity.serializer(), identity)
        val extra = (encoded.toByteArray().size.toLong() - File(dir, BOOK_FILE).length()).coerceAtLeast(0)
        if (entries(dir).any { it.kind == ReaderCacheKind.DOWNLOAD } || enforceBudget(extra, dir to -1)) {
            writeAtomically(File(dir, BOOK_FILE), encoded)
        }
        Unit
    }

    fun listBookStorage(scope: AccountScope): List<ReaderBookStorage> = synchronized(writeLock) {
        val shelf = readShelf(scope).orEmpty().associateBy { safeName(it.id) }
        accountDir(scope).listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.map { dir ->
            val identity = readIdentity(dir) ?: shelf[dir.name]?.let { BookIdentity(it.id, it.name) }
                ?: BookIdentity(dir.name, dir.name)
            val entries = entries(dir)
            val automatic = entries.filter { it.kind == ReaderCacheKind.AUTOMATIC }
            val downloads = entries.filter { it.kind == ReaderCacheKind.DOWNLOAD }
            ReaderBookStorage(identity.id, identity.name,
                automatic.sumOf { it.bytes } + if (downloads.isEmpty()) metadataBytes(dir) else 0L,
                downloads.sumOf { it.bytes } + unknownFiles(dir).sumOf { it.length() } + if (downloads.isNotEmpty()) metadataBytes(dir) else 0L,
                automatic.size, downloads.size)
        }.sortedBy { it.name }
    }

    /** Trim only automatic content; explicit downloads are never part of the rolling window. */
    fun maintainWindow(
        scope: AccountScope, book: ReaderBookDto, chapters: List<ReaderChapterDto>, current: Int,
        expected: ReaderCacheGeneration = generation(scope, book.id),
        owner: String = "${scope.serverId}:${scope.userId}:${book.id}",
    ): ReaderCacheGeneration = guardedWrite(scope, book.id, expected) {
            registerBook(scope, book)
            val dir = bookDir(scope, book.id)
            val keep = ReaderCachePolicy.window(chapters, current)
            automaticWindows[dir] = keep
            if (current in keep) pinnedChapters[owner] = dir to current
            val victims = entries(dir).filter { it.kind == ReaderCacheKind.AUTOMATIC && it.index !in keep }
            // 窗口滚动不能撤销同书的手动下载。自动写入按窗口逐次检查。
            victims.filter { chapterKey(dir, it.index) !in activeSaves }
                .forEach { deleteChapter(dir, it.index) }
            if (current in keep && chapterFile(scope, book.id, current).isFile) {
                val record = record(dir, current)
                writeRecord(dir, current, record.copy(lastAccess = System.currentTimeMillis()))
            }
            enforceBudget(0)
            generation(scope, book.id)
        }

    /** Unlike clear(), keep identity and TOC so profile management can still display the book. */
    fun clearContent(scope: AccountScope, bookId: String, kind: ReaderCacheKind): Boolean = synchronized(writeLock) {
        revokeBook(scope, bookId)
        val dir = bookDir(scope, bookId)
        pinnedChapters.entries.removeAll { it.value.first == dir }
        automaticWindows.remove(dir)
        var success = true
        entries(dir).filter { kind == ReaderCacheKind.ALL || it.kind == kind }.forEach {
            if (!deleteChapter(dir, it.index)) success = false
        }
        // 没有保留下载时可重建的目录/身份同属自动缓存，不能成为不可见的累计占用。
        if (entries(dir).isEmpty() && (kind != ReaderCacheKind.AUTOMATIC || unknownFiles(dir).isEmpty())) {
            listOf(BOOK_FILE, CHAPTERS_FILE).forEach { name ->
                val file = File(dir, name)
                if (file.exists() && !file.delete()) success = false
            }
        }
        // Unmarked legacy/unknown payloads are downloads: never silently erase them as automatic.
        if (kind != ReaderCacheKind.AUTOMATIC) unknownFiles(dir).forEach {
            if (!it.delete()) success = false
        }
        success
    }

    private fun revokeBook(scope: AccountScope, bookId: String) {
        val key = scope to bookId
        bookGenerations[key] = (bookGenerations[key] ?: 0L) + 1L
        activeCalls.filterValues { it == key }.keys.toList().forEach { it.cancel() }
    }

    fun clear(scope: AccountScope, bookId: String): Boolean = synchronized(writeLock) {
        revokeBook(scope, bookId)
        val dir = bookDir(scope, bookId)
        pinnedChapters.entries.removeAll { it.value.first == dir }
        automaticWindows.remove(dir)
        !dir.exists() || dir.deleteRecursively()
    }

    fun clearAccount(scope: AccountScope): Boolean = synchronized(writeLock) {
        accountGenerations[scope] = (accountGenerations[scope] ?: 0L) + 1L
        activeCalls.filterValues { it.first == scope }.keys.forEach { it.cancel() }
        val dir = accountDir(scope)
        pinnedChapters.entries.removeAll { it.value.first.parentFile == dir }
        automaticWindows.keys.removeAll { it.parentFile == dir }
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
        kind: ReaderCacheKind = ReaderCacheKind.DOWNLOAD,
    ): ReaderChapterContentDto = withContext(Dispatchers.IO) {
        require(kind != ReaderCacheKind.ALL) { "ALL is only a clearing filter" }
        saveMutex.withLock {
            val dir = bookDir(scope, bookId)
            activeSaves.add(chapterKey(dir, chapterIndex))
            try {
                val admitted = guardedWrite(scope, bookId, expected) {
                    val existing = entries(dir).firstOrNull { it.index == chapterIndex }
                    // Unmarked legacy caches must not be demoted to automatic.
                    if (kind == ReaderCacheKind.AUTOMATIC && existing?.kind == ReaderCacheKind.DOWNLOAD) return@guardedWrite false
                    if (kind == ReaderCacheKind.AUTOMATIC && (!hasAutomaticSpace() || !inAutomaticWindow(dir, chapterIndex))) return@guardedWrite false
                    val marker = ChapterRecord(kind, System.currentTimeMillis())
                    val markerBytes = json.encodeToString(ChapterRecord.serializer(), marker).toByteArray().size.toLong()
                    if (kind == ReaderCacheKind.AUTOMATIC && !enforceBudget(markerBytes, dir to chapterIndex)) return@guardedWrite false
                    if (readIdentity(dir) == null) {
                        val name = readShelf(scope)?.firstOrNull { it.id == bookId }?.name ?: bookId
                        writeIdentity(dir, BookIdentity(bookId, name))
                    }
                    writeRecord(dir, chapterIndex, marker)
                    true
                }
                if (!admitted) return@withLock readContent(scope, bookId, chapterIndex) ?: content.copy(mediaHeaders = null)
                val assets = guardedWrite(scope, bookId, expected) {
                    File(dir, "assets").apply { check(isDirectory || mkdirs()) }
                }
                suspend fun asset(url: String, name: String) =
                    downloadAsset(scope, bookId, chapterIndex, kind, expected, origin, url, assets, name)
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
                    val encoded = json.encodeToString(ReaderChapterContentDto.serializer(), cached)
                    if (kind != ReaderCacheKind.AUTOMATIC || (hasAutomaticSpace() && inAutomaticWindow(dir, chapterIndex) &&
                        enforceBudget(encoded.toByteArray().size.toLong(), dir to chapterIndex))) {
                        writeAtomically(chapterFile(scope, bookId, chapterIndex), encoded)
                        removeUnreferencedAssets(dir, chapterIndex, cached)
                        cached
                    } else {
                        val previous = readContent(scope, bookId, chapterIndex)
                        if (previous == null) deleteChapter(dir, chapterIndex)
                        else removeUnreferencedAssets(dir, chapterIndex, previous)
                        // Do not return file URLs whose staged assets were just discarded.
                        previous ?: content.copy(mediaHeaders = null)
                    }
                }
            } finally {
                synchronized(writeLock) {
                    activeSaves.remove(chapterKey(dir, chapterIndex))
                    if (kind == ReaderCacheKind.AUTOMATIC && !inAutomaticWindow(dir, chapterIndex) &&
                        record(dir, chapterIndex).kind == ReaderCacheKind.AUTOMATIC) deleteChapter(dir, chapterIndex)
                }
            }
        }
    }

    private suspend fun downloadAsset(
        scope: AccountScope, bookId: String, chapterIndex: Int, kind: ReaderCacheKind,
        expected: ReaderCacheGeneration, origin: String, rawUrl: String, dir: File, name: String,
    ): String? {
        if (rawUrl.startsWith("file:") || rawUrl.startsWith("content:")) return rawUrl
        val url = ReaderUrls.resolve(origin, rawUrl) ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val target = File(dir, "$name-${shortHash(url)}${extensionOf(url)}")
        if (target.isFile && target.length() > 0) return target.toURI().toString()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(Request.Builder().url(url).build())
            val temp = guardedWrite(scope, bookId, expected) {
                File.createTempFile("c$chapterIndex-asset-", ".tmp", dir).also {
                    activeCalls[call] = scope to bookId
                    activeTemps.add(it.absolutePath)
                }
            }
            continuation.invokeOnCancellation {
                call.cancel()
                synchronized(writeLock) { temp.delete(); activeTemps.remove(temp.absolutePath) }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(writeLock) { activeCalls.remove(call) }
                    synchronized(writeLock) { temp.delete(); activeTemps.remove(temp.absolutePath) }
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
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (continuation.isActive) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        written += count
                                        if (written > MAX_ASSET_BYTES) throw IOException("资源超过缓存大小上限")
                                        guardedWrite(scope, bookId, expected) {
                                            if (!continuation.isActive) throw IOException("缓存任务已取消")
                                            if (kind == ReaderCacheKind.AUTOMATIC && (!hasAutomaticSpace() || !inAutomaticWindow(bookDir(scope, bookId), chapterIndex) ||
                                                !enforceBudget(count.toLong(), bookDir(scope, bookId) to chapterIndex))) {
                                                throw IOException("自动缓存空间不足")
                                            }
                                            // Never hold a file open while waiting on the network: clear must
                                            // also be able to unlink partials on Windows, without late recreation.
                                            FileOutputStream(temp, true).use { it.write(buffer, 0, count) }
                                        }
                                    }
                                }
                                if (continuation.isActive && written > 0) {
                                    guardedWrite(scope, bookId, expected) {
                                        if (continuation.isActive) {
                                            FileOutputStream(temp, true).use { it.fd.sync() }
                                            replaceFile(temp, target)
                                            result = target.toURI().toString()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Generation revocation also fences the final asset rename.
                    } catch (_: IOException) {
                        // 下载失败保留其它已缓存资源；取消由 continuation 原样传播。
                    } finally {
                        synchronized(writeLock) { activeCalls.remove(call) }
                        synchronized(writeLock) { temp.delete(); activeTemps.remove(temp.absolutePath) }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            })
        }
    }

    @Serializable
    private data class BookIdentity(val id: String, val name: String)

    @Serializable
    private data class ChapterRecord(val kind: ReaderCacheKind, val lastAccess: Long)

    private data class Entry(
        val dir: File, val index: Int, val kind: ReaderCacheKind, val lastAccess: Long, val bytes: Long,
    )

    private fun readIdentity(dir: File): BookIdentity? = runCatching {
        json.decodeFromString(BookIdentity.serializer(), File(dir, BOOK_FILE).readText())
    }.getOrNull()

    private fun writeIdentity(dir: File, identity: BookIdentity) =
        writeAtomically(File(dir, BOOK_FILE), json.encodeToString(BookIdentity.serializer(), identity))

    private fun recordFile(dir: File, index: Int) = File(dir, "chapter-$index.cache.json")

    private fun record(dir: File, index: Int): ChapterRecord = runCatching {
        json.decodeFromString(ChapterRecord.serializer(), recordFile(dir, index).readText())
    }.getOrNull()?.takeIf { it.kind != ReaderCacheKind.ALL }
        // Migration deliberately retains users' historical downloads, including partials.
        ?: ChapterRecord(ReaderCacheKind.DOWNLOAD, File(dir, "chapter-$index.json").lastModified())

    private fun writeRecord(dir: File, index: Int, record: ChapterRecord) =
        writeAtomically(recordFile(dir, index), json.encodeToString(ChapterRecord.serializer(), record))

    private fun assetIndex(file: File): Int? = ASSET_PATTERN.find(file.name)?.groupValues?.get(1)?.toIntOrNull()

    private fun fileIndex(dir: File, file: File): Int? = if (file.parentFile == dir) {
        CHAPTER_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            ?: RECORD_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
    } else if (file.parentFile == File(dir, "assets")) assetIndex(file) else null

    // Do not follow symlinks while accounting or clearing unrecognized payloads.
    private fun payloadFiles(dir: File): List<File> {
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return emptyList()
        return Files.walk(dir.toPath()).use { paths ->
            paths.filter { !Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .map { it.toFile() }.toArray().map { it as File }
        }
    }

    private fun entries(dir: File): List<Entry> = payloadFiles(dir).mapNotNull { file ->
        fileIndex(dir, file)?.let { it to file }
    }.groupBy({ it.first }, { it.second }).map { (index, files) ->
        val record = record(dir, index)
        Entry(dir, index, record.kind, record.lastAccess, files.sumOf { it.length() })
    }

    private fun unknownFiles(dir: File): List<File> = payloadFiles(dir).filter {
        fileIndex(dir, it) == null && !(it.parentFile == dir && it.name in setOf(BOOK_FILE, CHAPTERS_FILE)) && !isActiveTemp(it)
    }

    private fun deleteChapter(dir: File, index: Int): Boolean {
        var success = true
        payloadFiles(dir).filter { fileIndex(dir, it) == index }.forEach {
            if (!it.delete()) success = false
        }
        return success
    }

    private fun removeUnreferencedAssets(dir: File, index: Int, content: ReaderChapterContentDto) {
        val urls = content.images.orEmpty() + content.tracks.orEmpty() +
            ReaderText.parse(content.content).filter { it.isImage }.mapNotNull { it.imageUrl }
        val referenced = urls.mapNotNull { runCatching { File(URI(it)).canonicalPath }.getOrNull() }.toSet()
        payloadFiles(dir).filter {
            it.parentFile == File(dir, "assets") && assetIndex(it) == index &&
                it.canonicalPath !in referenced && !isActiveTemp(it)
        }.forEach { it.delete() }
    }

    private fun bookDirectories(): List<File> = root.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        .flatMap { it.listFiles().orEmpty().filter { file -> file.isDirectory && !Files.isSymbolicLink(file.toPath()) } }
        .flatMap { it.listFiles().orEmpty().filter { file -> file.isDirectory && !Files.isSymbolicLink(file.toPath()) } }

    private fun hasAutomaticSpace(): Boolean {
        var existing: File? = root
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return (existing?.usableSpace ?: 0L) >= ReaderCachePolicy.MIN_AUTOMATIC_FREE_BYTES
    }

    /**
     * All accounts under this root share one budget. Reserve before every streamed chunk/write;
     * chapter markers and staged assets count, while tiny book identity/TOC records do not.
     * An already active chapter is retained if a later smaller budget cannot accommodate it.
     */
    private fun enforceBudget(reserve: Long, writing: Pair<File, Int>? = null): Boolean {
        val automatic = bookDirectories().flatMap(::entries).filter { it.kind == ReaderCacheKind.AUTOMATIC }
        var bytes = automatic.sumOf { it.bytes } + bookDirectories().filter { dir -> entries(dir).none { it.kind == ReaderCacheKind.DOWNLOAD } }.sumOf(::metadataBytes)
        if (bytes <= automaticMaxBytes - reserve) return true
        val bookAccess = automatic.groupBy { it.dir }.mapValues { (_, chapters) -> chapters.maxOf { it.lastAccess } }
        val candidates = automatic.filter {
            (it.dir to it.index) !in pinnedChapters.values && (it.dir to it.index) != writing &&
                chapterKey(it.dir, it.index) !in activeSaves
        }.sortedWith(compareBy<Entry> { bookAccess[it.dir] ?: 0L }.thenBy { it.lastAccess }.thenBy { it.index })
        for (entry in candidates) {
            if (deleteChapter(entry.dir, entry.index)) bytes -= entry.bytes
            if (bytes <= automaticMaxBytes - reserve) return true
        }
        // 无正文且无下载的旧书目录也可淘汰，避免只剩目录的书无限堆积。
        for (dir in bookDirectories().filter { candidate ->
            entries(candidate).isEmpty() && pinnedChapters.values.none { it.first == candidate } && writing?.first != candidate
        }.sortedBy { File(it, BOOK_FILE).lastModified() }) {
            val overhead = metadataBytes(dir)
            if (dir.deleteRecursively()) bytes -= overhead
            if (bytes <= automaticMaxBytes - reserve) return true
        }
        return bytes <= automaticMaxBytes - reserve
    }

    private fun metadataBytes(dir: File) = listOf(BOOK_FILE, CHAPTERS_FILE).sumOf { File(dir, it).length() }

    private fun chapterKey(dir: File, index: Int) = "${dir.absolutePath}/$index"
    private fun isActiveTemp(file: File) = file.absolutePath in activeTemps

    private fun writeAtomically(target: File, text: String) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temp = File.createTempFile("chapter-", ".tmp", target.parentFile)
        activeTemps.add(temp.absolutePath)
        try {
            FileOutputStream(temp).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            replaceFile(temp, target)
        } finally { temp.delete(); activeTemps.remove(temp.absolutePath) }
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
        return if (extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus", "wma", "asf", "ape", "wv", "ac3", "dts", "amr", "tta", "dsf", "dff")) ".$extension" else ""
    }
    private fun shortHash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private companion object {
        val RECORD_PATTERN = Regex("chapter-(\\d+)\\.cache\\.json")
        val ASSET_PATTERN = Regex("^c(\\d+)-")
        val saveMutex = Mutex()
        val writeLock = Any()
        val activeTemps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val activeSaves = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        const val BOOK_FILE = "book.json"
        val CHAPTER_PATTERN = Regex("chapter-(\\d+)\\.json")
        const val CHAPTERS_FILE = "chapters.json"
        const val SHELF_FILE = "shelf.json"
        const val MAX_ASSET_BYTES = 64L * 1024 * 1024
    }
}
