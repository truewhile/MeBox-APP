package app.mebox.android.core.reader

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
 * 只保存音频字节：URL、Cookie、书源头和响应头都不写入文件或异常。
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
    private var active: Pair<String, Call>? = null

    /** 本地 URI 原样返回且不访问磁盘；远程音源完整下载后才返回 file: URI。 */
    suspend fun prepare(url: String): String {
        if (url.startsWith("file:", ignoreCase = true) ||
            url.startsWith("content://", ignoreCase = true)) return url
        return withContext(Dispatchers.IO) {
            prepareMutex.withLock {
                try {
                    prepareRemote(url)
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

    /** 解码失败后显式移除完整文件，下一次 prepare 将重新下载。 */
    fun invalidate(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val key = keyOf(parsed)
        synchronized(fileLock) {
            active?.takeIf { it.first == key }?.second?.cancel()
            if (targetOf(parsed).exists() && !targetOf(parsed).delete()) {
                throw IOException("无法移除本地音频")
            }
        }
    }

    /** 取消在途下载并清理本实例目录中的完整音源；临时文件由下载 finally 清理。 */
    fun clear() = synchronized(fileLock) {
        active?.second?.cancel()
        completedFiles().forEach {
            if (!it.delete()) throw IOException("无法清理本地音频")
        }
    }

    private suspend fun prepareRemote(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: throw IOException("音频地址无效")
        rejectPlaylistPath(url)
        val target = targetOf(url)
        val call = client.newCall(Request.Builder().url(url).build())
        synchronized(fileLock) {
            coroutineContext.ensureActive()
            check(cacheDir.isDirectory || cacheDir.mkdirs())
            if (target.isFile && target.length() in 1..MAX_SOURCE_BYTES) {
                prune(MAX_CACHE_BYTES, target)
                target.setLastModified(System.currentTimeMillis())
                return target.toURI().toString()
            }
            if (target.exists() && !target.delete()) throw IOException("无法移除本地音频")
            active = keyOf(url) to call
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
                        val expected = body.contentLength()
                        if (expected == 0L || expected > MAX_SOURCE_BYTES) throw IOException("音频大小无效")
                        val source = body.source()
                        source.request(64)
                        val prefix = source.buffer.clone().readUtf8(minOf(64L, source.buffer.size))
                        if (prefix.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) {
                            throw UnsupportedOperationException(HLS_MESSAGE)
                        }
                        synchronized(fileLock) {
                            ensureActive()
                            if (call.isCanceled()) throw IOException("音频下载已取消")
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
                                    if (written > MAX_SOURCE_BYTES || (expected >= 0 && written > expected)) {
                                        throw IOException("音频大小无效")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                if (written == 0L || (expected >= 0 && written != expected)) {
                                    throw IOException("音频下载不完整")
                                }
                                output.fd.sync()
                            }
                        }
                        synchronized(fileLock) {
                            ensureActive()
                            if (call.isCanceled()) throw IOException("音频下载已取消")
                            replaceFile(pending, target)
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
        var total = files.sumOf { it.length() }
        files.filter { it != keep }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (total > limit) {
                    val size = file.length()
                    if (!file.delete()) throw IOException("本地音频缓存已满")
                    total -= size
                }
            }
        if (total > limit) throw IOException("本地音频缓存已满")
    }

    private fun completedFiles(): List<File> = cacheDir.listFiles().orEmpty()
        .filter { it.isFile && COMPLETED_NAME.matches(it.name) }

    private fun targetOf(url: HttpUrl): File {
        val extension = url.pathSegments.lastOrNull().orEmpty()
            .substringAfterLast('.', "").lowercase(Locale.ROOT)
        return File(cacheDir, "${keyOf(url)}.${extension.takeIf { it in EXTENSIONS } ?: "audio"}")
    }

    private fun keyOf(url: HttpUrl): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

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

    private companion object {
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        const val MAX_CACHE_BYTES = 128L * 1024 * 1024
        const val HLS_MESSAGE = "HLS playlists require URI playback"
        val EXTENSIONS = setOf("wma", "asf", "ape", "mp3", "m4a", "aac", "flac", "ogg", "oga", "wav", "opus", "mp4", "mka", "aiff", "aif", "amr", "ac3", "dts", "wv")
        val COMPLETED_NAME = Regex("[a-f0-9]{64}\\.(?:${(EXTENSIONS + "audio").joinToString("|")})")
    }
}
