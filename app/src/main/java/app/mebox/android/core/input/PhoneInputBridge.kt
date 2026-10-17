package app.mebox.android.core.input

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机扫码后从网页推送到电视的一次操作。
 *
 * 电视端没有方便的文字输入方式，因此连接服务器、登录和搜索都由同一个
 * 局域网网页完成。事件本身不区分来源页面，订阅方只处理自己关心的类型。
 */
sealed interface PhoneInputEvent {
    data class Server(val address: String) : PhoneInputEvent
    data class Login(val username: String, val password: String) : PhoneInputEvent
    data class Search(val query: String) : PhoneInputEvent
}

/**
 * 电视端内嵌的局域网输入服务。
 *
 * 设计约束：
 * - 只在电视界面可见时启动，退出电视界面立即关闭；
 * - 二维码 URL 携带随机 token，同一局域网内不知道 token 的设备无法访问；
 * - 页面只做三件事：填写服务器、登录账号、搜索/推送关键词；
 * - 不记录、不落盘任何密码，提交后只保留在内存事件里。
 */
class PhoneInputServer {

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var token: String = newToken()

    /** 登录后只保留搜索表单，避免手机页面出现点了没反应的服务器/登录表单。 */
    @Volatile
    private var searchOnly: Boolean = false

    fun setSearchOnly(value: Boolean) {
        searchOnly = value
    }

    private val _url = MutableStateFlow<String?>(null)

    /** 形如 `http://192.168.1.20:37651/t/AbC123xYz9`；未启动或没有局域网地址时为 null。 */
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _events = MutableSharedFlow<PhoneInputEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PhoneInputEvent> = _events.asSharedFlow()

    @Synchronized
    fun start(): Boolean {
        if (serverSocket != null) return true
        val host = localIpv4() ?: run {
            _url.value = null
            return false
        }
        return startWithHost(host)
    }

    /** 仅用于 JVM 单元测试：绑定回环地址，避免依赖测试机的局域网网卡。 */
    @Synchronized
    internal fun startForTest(host: String): Boolean {
        if (serverSocket != null) return true
        return startWithHost(host)
    }

    private fun startWithHost(host: String): Boolean {
        val socket = try {
            // 端口交给系统分配，避免和用户其它应用冲突。
            ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        } catch (_: Exception) {
            _url.value = null
            return false
        }
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = workScope
        serverSocket = socket
        _url.value = "http://$host:${socket.localPort}/t/$token"
        acceptJob = workScope.launch { acceptLoop(socket) }
        return true
    }

    /** 网络地址变化后重新绑定端口并换发 token。 */
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
                socket.soTimeout = 5_000
                val input = socket.getInputStream().buffered()
                val reader = input.reader(Charsets.UTF_8).buffered()
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(' ')
                if (requestParts.size < 2) return
                val method = requestParts[0].uppercase()
                val path = requestParts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator <= 0) continue
                    if (line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength in 1..MAX_BODY_CHARS) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(buffer, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    String(buffer, 0, read)
                } else {
                    ""
                }

                val base = "/t/$token"
                when {
                    method == "GET" && path == base -> respond(
                        socket,
                        "200 OK",
                        PhoneInputProtocol.page(token, searchOnly = searchOnly),
                    )

                    method == "POST" && path == "$base/submit" -> {
                        PhoneInputProtocol.eventFromForm(PhoneInputProtocol.parseForm(body))
                            ?.let { event -> _events.tryEmit(event) }
                        respond(socket, "200 OK", PhoneInputProtocol.successPage())
                    }

                    else -> respond(socket, "404 Not Found", PhoneInputProtocol.notFoundPage())
                }
            }.onFailure {
                // 局域网请求失败不需要打断电视使用；连接已关闭时直接忽略。
            }
        }
    }

    private fun respond(socket: Socket, status: String, html: String) {
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

    private fun newToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..TOKEN_LENGTH)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }

    private companion object {
        const val TOKEN_LENGTH = 12
        const val MAX_BODY_CHARS = 16 * 1024
    }
}

/** 纯函数部分单独抽出来，便于单元测试。 */
object PhoneInputProtocol {

    fun parseForm(body: String): Map<String, String> =
        body.split('&')
            .asSequence()
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decode(pair.substring(0, separator))
                val value = decode(pair.substring(separator + 1))
                key to value
            }
            .toMap()

    fun eventFromForm(form: Map<String, String>): PhoneInputEvent? = when (form["type"]) {
        "server" -> form["address"]?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(PhoneInputEvent::Server)

        "login" -> {
            val username = form["username"]?.trim().orEmpty()
            val password = form["password"].orEmpty()
            if (username.isNotEmpty() && password.isNotEmpty()) {
                PhoneInputEvent.Login(username, password)
            } else {
                null
            }
        }

        "search" -> form["query"]?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(PhoneInputEvent::Search)

        else -> null
    }

    fun page(token: String, searchOnly: Boolean = false): String {
        val template = if (searchOnly) PAGE_SEARCH_ONLY else PAGE
        return template.replace("%ACTION%", "/t/$token/submit")
    }

    fun successPage(): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>已推送到电视</title>
        <style>${STYLE}</style></head>
        <body><main class="card">
          <div class="ok">✓</div>
          <h1>已推送到电视</h1>
          <p class="hint">电视端会立即执行。你可以返回继续填写其它内容，或直接关闭此页。</p>
          <a class="button ghost" href="javascript:history.back()">继续填写</a>
        </main></body></html>
    """.trimIndent()

    fun notFoundPage(): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>链接已失效</title>
        <style>${STYLE}</style></head>
        <body><main class="card">
          <h1>链接已失效</h1>
          <p class="hint">请在电视上重新打开二维码页面，再用手机扫码。</p>
        </main></body></html>
    """.trimIndent()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private const val STYLE = """
        :root{color-scheme:dark}
        *{box-sizing:border-box}
        body{margin:0;min-height:100vh;background:#0b0d12;color:#f4f6fb;
             font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
             display:flex;justify-content:center;padding:24px 16px 48px}
        main{width:100%;max-width:520px}
        .card{background:#151922;border:1px solid #242b38;border-radius:20px;padding:24px 20px;
              box-shadow:0 20px 60px rgba(0,0,0,.35)}
        .brand{display:flex;align-items:center;gap:12px;margin-bottom:4px}
        .logo{width:44px;height:44px;border-radius:13px;display:grid;place-items:center;
              background:linear-gradient(135deg,#0a84ff,#58b5ff);font-weight:800;font-size:22px;color:#fff}
        h1{font-size:22px;margin:0}
        h2{font-size:15px;margin:0 0 12px;color:#c9d2e3;font-weight:650}
        .sub{color:#8f9bb3;font-size:13px;margin:2px 0 0}
        .hint{color:#8f9bb3;font-size:13px;margin:10px 0 0}
        section{margin-top:26px}
        label{display:block;font-size:13px;color:#aab5c8;margin:12px 0 6px}
        input{width:100%;height:48px;border-radius:12px;border:1px solid #2b3444;background:#0e1219;
              color:#f4f6fb;padding:0 14px;font-size:16px;outline:none}
        input:focus{border-color:#0a84ff;box-shadow:0 0 0 3px rgba(10,132,255,.18)}
        button{width:100%;height:48px;margin-top:16px;border:0;border-radius:12px;background:#0a84ff;
               color:#fff;font-size:16px;font-weight:650}
        button:active{transform:scale(.99)}
        .button{display:block;text-align:center;text-decoration:none;line-height:48px;border-radius:12px;
                color:#fff;background:#0a84ff;font-weight:650;margin-top:18px}
        .ghost{background:#222b39}
        .ok{width:56px;height:56px;border-radius:50%;background:rgba(52,199,89,.15);color:#34c759;
            display:grid;place-items:center;font-size:30px;font-weight:800;margin-bottom:14px}
        .foot{color:#6f7b90;font-size:12px;margin-top:20px;text-align:center}
    """

    private val PAGE: String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <meta name="theme-color" content="#0b0d12">
        <title>MeBox 电视助手</title>
        <style>$STYLE</style></head>
        <body><main>
          <div class="card">
            <div class="brand">
              <div class="logo">M</div>
              <div><h1>MeBox 电视助手</h1><p class="sub">在手机上填写，电视立即收到</p></div>
            </div>
            <p class="hint">请确认手机和电视连接同一个 Wi-Fi。页面关闭后链接失效。</p>
          </div>

          <section class="card">
            <h2>连接服务器</h2>
            <form method="post" action="%ACTION%">
              <input type="hidden" name="type" value="server">
              <label>服务器地址</label>
              <input name="address" inputmode="url" autocapitalize="off" spellcheck="false"
                     placeholder="https://example.com:8443" autocomplete="url" required>
              <button type="submit">连接电视</button>
            </form>
          </section>

          <section class="card">
            <h2>账号登录</h2>
            <form method="post" action="%ACTION%">
              <input type="hidden" name="type" value="login">
              <label>用户名</label>
              <input name="username" autocapitalize="off" spellcheck="false" autocomplete="username" required>
              <label>密码</label>
              <input name="password" type="password" autocomplete="current-password" required>
              <button type="submit">登录电视</button>
            </form>
          </section>

          <section class="card">
            <h2>搜索 / 推送关键词</h2>
            <form method="post" action="%ACTION%">
              <input type="hidden" name="type" value="search">
              <label>关键词</label>
              <input name="query" enterkeyhint="search" placeholder="输入片名、演员或关键词" required>
              <button type="submit">推送到电视搜索</button>
            </form>
          </section>

          <p class="foot">仅局域网可用 · 不保存密码 · MeBox</p>
        </main></body></html>
    """.trimIndent()

    /** 登录后只显示搜索表单，页面更短，也不会出现“点了没反应”的登录入口。 */
    private val PAGE_SEARCH_ONLY: String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <meta name="theme-color" content="#0b0d12">
        <title>MeBox 电视搜索</title>
        <style>$STYLE</style></head>
        <body><main>
          <div class="card">
            <div class="brand">
              <div class="logo">M</div>
              <div><h1>MeBox 电视搜索</h1><p class="sub">已登录 · 在手机上输入关键词</p></div>
            </div>
            <p class="hint">请确认手机和电视连接同一个 Wi-Fi。页面关闭后链接失效。</p>
          </div>

          <section class="card">
            <h2>搜索 / 推送关键词</h2>
            <form method="post" action="%ACTION%">
              <input type="hidden" name="type" value="search">
              <label>关键词</label>
              <input name="query" enterkeyhint="search" placeholder="输入片名、演员或关键词" required autofocus>
              <button type="submit">推送到电视搜索</button>
            </form>
          </section>

          <p class="foot">仅局域网可用 · 不保存密码 · MeBox</p>
        </main></body></html>
    """.trimIndent()
}

/**
 * 找出当前设备在局域网里的 IPv4 地址。电视通常用 Wi-Fi 或有线网，
 * 优先选择 wlan/eth 接口，找不到时退回任意站点本地地址。
 */
internal fun localIpv4(): String? {
    val candidates = mutableListOf<Pair<String, Inet4Address>>()
    val interfaces: List<NetworkInterface> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    }.getOrElse { emptyList() }
    for (network in interfaces) {
        val up = runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)
        if (!up) continue
        val addresses: List<InetAddress> = runCatching {
            Collections.list(network.inetAddresses)
        }.getOrElse { emptyList() }
        for (address in addresses) {
            if (address is Inet4Address && address.isSiteLocalAddress) {
                candidates += network.name.orEmpty() to address
            }
        }
    }
    return candidates.firstOrNull {
        it.first.startsWith("wlan") || it.first.startsWith("eth")
    }?.second?.hostAddress
        ?: candidates.firstOrNull()?.second?.hostAddress
}
