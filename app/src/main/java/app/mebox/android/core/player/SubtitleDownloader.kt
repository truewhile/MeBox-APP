package app.mebox.android.core.player

import app.mebox.android.core.network.APP_USER_AGENT
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 用 App 的 OkHttp 网络栈下载外挂字幕，原始 ASS 字节不做转换。
 * 返回的本地文件由播放会话持有并清理；取消、HTTP 失败和超大响应不会残留半文件。
 * 每条请求有独立期限，避免字幕占住 VLC 的输入线程或无限下载。
 */
internal class SubtitleDownloader(
    client: OkHttpClient,
    private val directory: File,
    private val maxBytes: Long = 8L * 1024 * 1024,
) {
    private val client = client.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun download(subtitle: EngineSubtitle): File = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(subtitle.assUrl ?: subtitle.url)
            .header("User-Agent", APP_USER_AGENT)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                var file: File? = null
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw IOException("字幕请求 HTTP ${it.code}")
                        val body = it.body ?: throw IOException("字幕响应为空")
                        if (body.contentLength() > maxBytes) throw IOException("字幕超过大小限制")
                        if (!directory.isDirectory && !directory.mkdirs()) {
                            throw IOException("无法创建字幕缓存目录")
                        }
                        val local = File.createTempFile(
                            "mebox-subtitle-", if (subtitle.assUrl != null) ".ass" else ".vtt", directory,
                        )
                        file = local
                        body.byteStream().use { input ->
                            local.outputStream().use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > maxBytes) throw IOException("字幕超过大小限制")
                                    if (!continuation.isActive) throw IOException("字幕请求已取消")
                                    output.write(buffer, 0, count)
                                }
                                if (total == 0L) throw IOException("字幕响应为空")
                            }
                        }
                        // 即使下载完成与换集取消同时发生，也不能遗留无人持有的缓存文件。
                        continuation.resume(local) { _, abandoned, _ -> abandoned.delete() }
                        file = null
                    }
                } catch (error: Exception) {
                    // 先完成清理再唤醒调用方，避免调用方立即换集/清理缓存时仍看到半文件。
                    file?.delete()
                    file = null
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    file?.delete()
                }
            }
        })
    }
}
