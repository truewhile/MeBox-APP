package app.mebox.android.core.reader

import android.content.Context
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 阅读离线缓存（应用私有目录）。
 *
 * 只缓存「服务端已经处理好的产物」：文本正文、图片与可直接播放的音频文件。
 * 书源解析、解密和替换规则仍在服务端执行，客户端不保存书源凭据。
 *
 * 目录按 serverId / userId / bookId 隔离；章节内容存成一个 JSON 文件，
 * 图片和音频下载到同目录的 assets 下，缓存 DTO 里的地址改写为 file://。
 * HLS/需要服务端转码的音轨不做离线缓存。
 */
class ReaderCacheStore(
    context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val root = File(context.filesDir, "reader-cache")

    fun read(scope: AccountScope, bookId: String, chapterIndex: Int): ReaderChapterContentDto? {
        val file = chapterFile(scope, bookId, chapterIndex)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ReaderChapterContentDto.serializer(), file.readText())
        }.getOrNull()
    }

    fun readChapters(scope: AccountScope, bookId: String): List<ReaderChapterDto>? {
        val file = File(bookDir(scope, bookId), CHAPTERS_FILE)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ReaderChapterDto.serializer()), file.readText())
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun saveChapters(scope: AccountScope, bookId: String, chapters: List<ReaderChapterDto>) {
        if (chapters.isEmpty()) return
        val dir = bookDir(scope, bookId)
        dir.mkdirs()
        writeAtomically(
            File(dir, CHAPTERS_FILE),
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ReaderChapterDto.serializer()),
                chapters,
            ),
        )
    }

    fun readShelf(scope: AccountScope): List<ReaderBookDto>? {
        val file = File(accountDir(scope), SHELF_FILE)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ReaderBookDto.serializer()), file.readText())
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun saveShelf(scope: AccountScope, books: List<ReaderBookDto>) {
        if (books.isEmpty()) return
        val dir = accountDir(scope)
        dir.mkdirs()
        writeAtomically(
            File(dir, SHELF_FILE),
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ReaderBookDto.serializer()),
                books,
            ),
        )
    }

    fun hasChapter(scope: AccountScope, bookId: String, chapterIndex: Int): Boolean =
        chapterFile(scope, bookId, chapterIndex).isFile

    fun cachedIndexes(scope: AccountScope, bookId: String): Set<Int> {
        val dir = bookDir(scope, bookId)
        if (!dir.isDirectory) return emptySet()
        return dir.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                CHAPTER_PATTERN.matchEntire(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .toSet()
    }

    fun sizeBytes(scope: AccountScope, bookId: String): Long {
        val dir = bookDir(scope, bookId)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clear(scope: AccountScope, bookId: String) {
        bookDir(scope, bookId).deleteRecursively()
    }

    fun clearAccount(scope: AccountScope) {
        accountDir(scope).deleteRecursively()
    }

    /**
     * 保存一章。图片/音频下载失败时保留原地址，仍然缓存文本与成功下载的资源；
     * 返回真正写入的 DTO，调用方可以直接用它离线渲染。
     */
    suspend fun save(
        scope: AccountScope,
        bookId: String,
        chapterIndex: Int,
        content: ReaderChapterContentDto,
        origin: String,
    ): ReaderChapterContentDto = withContext(Dispatchers.IO) {
        val dir = bookDir(scope, bookId)
        dir.mkdirs()
        val assets = File(dir, "assets").apply { mkdirs() }

        val images = content.images?.mapIndexed { index, url ->
            downloadAsset(origin, url, assets, "c$chapterIndex-img-$index") ?: url
        }
        val tracks = if (content.hls) {
            content.tracks
        } else {
            content.tracks?.mapIndexed { index, url ->
                downloadAsset(origin, url, assets, "c$chapterIndex-audio-$index") ?: url
            }
        }
        val cached = content.copy(images = images, tracks = tracks)
        writeAtomically(
            chapterFile(scope, bookId, chapterIndex),
            json.encodeToString(ReaderChapterContentDto.serializer(), cached),
        )
        cached
    }

    private fun downloadAsset(origin: String, rawUrl: String, dir: File, name: String): String? {
        if (rawUrl.startsWith("file:") || rawUrl.startsWith("content:")) return rawUrl
        val url = ReaderUrls.resolve(origin, rawUrl) ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val extension = extensionOf(url)
        val target = File(dir, "$name-${shortHash(url)}$extension")
        if (target.isFile && target.length() > 0) return target.toURI().toString()
        val temp = File(dir, "${target.name}.tmp")
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            try {
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_ASSET_BYTES) return null
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        if (!copyLimited(input, output)) return null
                        output.fd.sync()
                    }
                }
            } finally {
                response.close()
            }
            if (!temp.isFile || temp.length() <= 0) {
                temp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target.toURI().toString()
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: FileOutputStream): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            written += read
            if (written > MAX_ASSET_BYTES) return false
            output.write(buffer, 0, read)
        }
        return true
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray())
            output.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun chapterFile(scope: AccountScope, bookId: String, chapterIndex: Int): File =
        File(bookDir(scope, bookId), "chapter-$chapterIndex.json")

    private fun bookDir(scope: AccountScope, bookId: String): File =
        File(accountDir(scope), safeName(bookId))

    private fun accountDir(scope: AccountScope): File =
        File(File(root, safeName(scope.serverId)), safeName(scope.userId))

    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isBlank()) "unknown" else cleaned.take(64)
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.')
        return when (path.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> ".$path"
            "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus" -> ".$path"
            else -> ""
        }
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val CHAPTER_PATTERN = Regex("chapter-(\\d+)\\.json")
        const val CHAPTERS_FILE = "chapters.json"
        const val SHELF_FILE = "shelf.json"
        const val MAX_ASSET_BYTES = 64L * 1024 * 1024
    }
}
