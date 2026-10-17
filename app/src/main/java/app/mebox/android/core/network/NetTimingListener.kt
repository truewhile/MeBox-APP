package app.mebox.android.core.network

import app.mebox.android.core.log.DiagnosticsLog
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 把每次请求拆成 DNS / 建连 / TLS / 等服务端四段写进 `Net` 日志。
 *
 * 之前只记录总耗时，遇到「某个接口 5 秒」时无法区分是服务端算得慢，还是
 * 手机到服务器的链路在建连（境外单机 + 国内网络时，这种差距通常是 5 秒
 * 量级，而 handler 本身只有 0.15 秒）。
 *
 * 输出示例：
 * ```
 * 200 5712ms GET https://host/api/libraries?with_preview=… \
 *     dns=12ms connect=812ms tls=4100ms server=180ms reuse=false
 * ```
 * `connect` / `tls` 为空表示复用了已有连接（这是快路径）。
 */
class NetTimingListener : EventListener() {

    private class Trace(val startNanos: Long) {
        var dnsStartNanos = NO_TIMING
        var dnsEndNanos = NO_TIMING
        var connectStartNanos = NO_TIMING
        var connectEndNanos = NO_TIMING
        var tlsStartNanos = NO_TIMING
        var tlsEndNanos = NO_TIMING
        var requestEndNanos = NO_TIMING
        var headersStartNanos = NO_TIMING
        var reused = false
        var attempts = 0
    }

    private val traces = ConcurrentHashMap<Call, Trace>()

    private fun now(): Long = System.nanoTime()

    private fun traceOf(call: Call): Trace {
        val existing = traces[call]
        if (existing != null) return existing
        val created = Trace(now())
        traces[call] = created
        return created
    }

    override fun callStart(call: Call) {
        traces[call] = Trace(now())
    }

    override fun dnsStart(call: Call, domainName: String) {
        traceOf(call).dnsStartNanos = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        traceOf(call).dnsEndNanos = now()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        traceOf(call).apply {
            attempts += 1
            connectStartNanos = now()
        }
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        traceOf(call).connectEndNanos = now()
    }

    override fun secureConnectStart(call: Call) {
        traceOf(call).tlsStartNanos = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        traceOf(call).tlsEndNanos = now()
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        traceOf(call).reused = true
    }

    override fun requestHeadersStart(call: Call) {
        traceOf(call).requestEndNanos = now()
    }

    override fun responseHeadersStart(call: Call) {
        traceOf(call).headersStartNanos = now()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val trace = traces.remove(call) ?: return
        val summary = "${requestLabel(call)} ${timingSummary(trace, response.receivedResponseAtMillis)}"
        if (response.code >= 400) {
            DiagnosticsLog.w("Net", "${response.code} $summary")
        } else {
            DiagnosticsLog.d("Net", "${response.code} $summary")
        }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val trace = traces.remove(call)
        val label = requestLabel(call)
        val detail = trace?.let { timingSummary(it, 0L) }.orEmpty()
        DiagnosticsLog.e("Net", "请求失败 $label $detail", ioe)
    }

    override fun callEnd(call: Call) {
        traces.remove(call)
    }

    override fun canceled(call: Call) {
        traces.remove(call)
    }

    private fun requestLabel(call: Call): String =
        try {
            val request = call.request()
            "${request.method} ${request.url.forNetLog()}"
        } catch (_: Exception) {
            "?"
        }

    private fun timingSummary(trace: Trace, _receivedAtMillis: Long): String {
        val total = millisSince(trace.startNanos)
        val dns = span(trace.dnsStartNanos, trace.dnsEndNanos)
        val connect = span(trace.connectStartNanos, trace.connectEndNanos)
        val tls = span(trace.tlsStartNanos, trace.tlsEndNanos)
        val server = span(trace.requestEndNanos, trace.headersStartNanos)
        val reuse = if (trace.reused && connect == null) "true" else "false"
        return buildString {
            append(total).append("ms")
            append(" dns=").append(dns ?: "-")
            append(" connect=").append(connect ?: "-")
            append(" tls=").append(tls ?: "-")
            append(" server=").append(server ?: "-")
            append(" reuse=").append(reuse)
            if (trace.attempts > 1) append(" attempts=").append(trace.attempts)
        }
    }

    private fun millisSince(startNanos: Long): Long =
        if (startNanos == NO_TIMING) 0L else (now() - startNanos) / 1_000_000L

    private fun span(startNanos: Long, endNanos: Long): String? {
        if (startNanos == NO_TIMING || endNanos == NO_TIMING) return null
        val millis = (endNanos - startNanos) / 1_000_000L
        return "${millis}ms"
    }

    private companion object {
        const val NO_TIMING = 0L
    }
}

/**
 * 日志里只保留协议、主机、端口和路径：查询参数值可能包含访问令牌或用户
 * 搜索词，只保留参数名。
 */
internal fun HttpUrl.forNetLog(): String = buildString {
    append(scheme).append("://").append(host)
    if (port != HttpUrl.defaultPort(scheme)) append(':').append(port)
    append(encodedPath)
    if (querySize > 0) {
        append('?')
        append(queryParameterNames.joinToString("&") { "$it=…" })
    }
}
