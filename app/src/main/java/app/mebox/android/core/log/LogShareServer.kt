package app.mebox.android.core.log

import android.content.Context
import app.mebox.android.core.input.localIpv4
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机/电视上的临时日志下载服务。
 *
 * 排查问题时用户不必找数据线：同一局域网内用手机或电脑打开地址就能下载
 * ZIP。URL 带随机 token，且只在用户主动打开分享对话框期间运行，关闭即停止。
 */
class LogShareServer(cacheDir: File) {

    constructor(context: Context) : this(context.applicationContext.cacheDir)

    private val cacheDirectory = cacheDir

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var token: String = newToken()

    private val _url = MutableStateFlow<String?>(null)

    /** 形如 `http://192.168.1.20:37651/d/AbC123xYz9`；未启动时为 null。 */
    val url: StateFlow<String?> = _url.asStateFlow()

    @Synchronized
    fun start(): Boolean {
        if (serverSocket != null) return true
        val host = localIpv4() ?: run {
            _url.value = null
            return false
        }
        return startWithHost(host)
    }

    /** 仅供 JVM 单元测试：绑定回环地址，避免依赖局域网网卡。 */
    @Synchronized
    internal fun startForTest(host: String): Boolean {
        if (serverSocket != null) return true
        return startWithHost(host)
    }

    private fun startWithHost(host: String): Boolean {
        val socket = try {
            ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        } catch (_: Exception) {
            _url.value = null
            return false
        }
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = workScope
        serverSocket = socket
        _url.value = "http://$host:${socket.localPort}/d/$token"
        acceptJob = workScope.launch { acceptLoop(socket) }
        return true
    }

    /** 网络地址变化或用户手动刷新时换发 token。 */
    @Synchronized
    fun restart(): Boolean {
        stop()
        token = newToken()
        return start()
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        _url.value = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope?.isActive == true && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                break
            }
            scope?.launch { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 10_000
                val reader = socket.getInputStream()
                    .bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                // 只处理请求头，忽略可能存在的请求体（本服务没有 POST）。
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                if (method != "GET") {
                    respondHtml(socket, "405 Method Not Allowed", notFoundPage("只支持直接下载"))
                    return
                }

                val base = "/d/$token"
                when {
                    path == base || path == "$base/" ->
                        respondHtml(socket, "200 OK", page(base))

                    path == "$base/bundle.zip" -> serveBundle(socket)

                    path.startsWith("$base/logs/") -> {
                        val name = path.removePrefix("$base/logs/")
                        val file = DiagnosticsLog.file(name)
                        if (file == null || !file.isFile) {
                            respondHtml(socket, "404 Not Found", notFoundPage("日志文件不存在"))
                        } else {
                            respondFile(
                                socket,
                                file = file,
                                contentType = "text/plain; charset=utf-8",
                                downloadName = file.name,
                            )
                        }
                    }

                    else -> respondHtml(socket, "404 Not Found", notFoundPage("链接已失效"))
                }
            }.onFailure {
                // 局域网下载失败无需打断应用；连接已关闭时直接忽略。
            }
        }
    }

    private fun serveBundle(socket: Socket) {
        val zip = runCatching { LogBundle.createBundle(cacheDirectory) }.getOrNull()
        if (zip == null) {
            respondHtml(socket, "500 Internal Server Error", notFoundPage("日志打包失败"))
            return
        }
        try {
            respondFile(
                socket,
                file = zip,
                contentType = "application/zip",
                downloadName = zip.name,
            )
        } finally {
            runCatching { zip.delete() }
        }
    }

    private fun respondHtml(socket: Socket, status: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun respondFile(
        socket: Socket,
        file: File,
        contentType: String,
        downloadName: String,
    ) {
        // 日志可能正在追加；先读成快照，保证 Content-Length 与实际内容一致。
        val body = runCatching { file.readBytes() }.getOrElse {
            respondHtml(socket, "500 Internal Server Error", notFoundPage("日志读取失败"))
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Content-Disposition: attachment; filename=\"").append(downloadName).append("\"\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun page(base: String): String {
        val files = DiagnosticsLog.files()
        val list = if (files.isEmpty()) {
            "<p class=\"hint\">暂无日志文件。</p>"
        } else {
            files.joinToString(separator = "") { info ->
                "<li><a href=\"$base/logs/${info.name}\">${info.name}</a>" +
                    "<span>${LogBundle.readableSize(info.sizeBytes)}</span></li>"
            }.let { "<ul class=\"files\">$it</ul>" }
        }
        return """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="theme-color" content="#0b0d12">
            <title>MeBox 诊断日志</title>
            <style>$STYLE</style></head>
            <body><main>
              <div class="card">
                <div class="brand">
                  <div class="logo">M</div>
                  <div><h1>MeBox 诊断日志</h1><p class="sub">排查问题用的运行记录</p></div>
                </div>
                <p class="hint">同一 Wi-Fi 下才能访问。关闭应用里的分享对话框后本页立即失效。</p>
              </div>
              <section class="card">
                <h2>下载</h2>
                <a class="button" href="$base/bundle.zip">下载全部日志（ZIP）</a>
                $list
              </section>
              <section class="card">
                <h2>运行环境</h2>
                <pre>${escape(DiagnosticsLog.environment())}</pre>
              </section>
              <p class="foot">仅局域网可用 · 不包含账号密码 · MeBox</p>
            </main></body></html>
        """.trimIndent()
    }

    private fun notFoundPage(message: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>链接已失效</title>
        <style>$STYLE</style></head>
        <body><main class="card">
          <h1>链接已失效</h1>
          <p class="hint">$message。请在应用里重新打开日志分享。</p>
        </main></body></html>
    """.trimIndent()

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun newToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..TOKEN_LENGTH)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }

    private companion object {
        const val TOKEN_LENGTH = 12

        val STYLE = """
            :root{color-scheme:dark}
            *{box-sizing:border-box}
            body{margin:0;min-height:100vh;background:#0b0d12;color:#f4f6fb;
                 font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
                 display:flex;justify-content:center;padding:24px 16px 48px}
            main{width:100%;max-width:640px}
            .card{background:#151922;border:1px solid #242b38;border-radius:20px;padding:22px 20px;margin-bottom:16px;
                  box-shadow:0 20px 60px rgba(0,0,0,.35)}
            .brand{display:flex;align-items:center;gap:12px}
            .logo{width:44px;height:44px;border-radius:13px;display:grid;place-items:center;
                  background:linear-gradient(135deg,#0a84ff,#58b5ff);font-weight:800;font-size:22px;color:#fff}
            h1{font-size:22px;margin:0}
            h2{font-size:15px;margin:0 0 12px;color:#c9d2e3;font-weight:650}
            .sub{color:#8f9bb3;font-size:13px;margin:2px 0 0}
            .hint{color:#8f9bb3;font-size:13px;margin:10px 0 0}
            .button{display:block;text-align:center;text-decoration:none;line-height:48px;border-radius:12px;
                    color:#fff;background:#0a84ff;font-weight:650;margin:4px 0 16px}
            .files{list-style:none;margin:0;padding:0}
            .files li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid #242b38}
            .files a{color:#58b5ff;text-decoration:none}
            .files span{color:#8f9bb3;font-size:13px}
            pre{white-space:pre-wrap;word-break:break-all;background:#0e1219;border:1px solid #242b38;
                border-radius:12px;padding:12px;font-size:12px;color:#c9d2e3;margin:0}
            .foot{color:#6f7b90;font-size:12px;margin-top:2px;text-align:center}
        """
    }
}
