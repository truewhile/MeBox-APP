package app.mebox.android.core.player

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * 系统为某个地址配置的 HTTP 代理。
 *
 * ExoPlayer 走 OkHttp，会自动用系统代理（OkHttp 读取 `ProxySelector`）；
 * LibVLC 是一套独立的网络栈，完全不看系统代理。手机挂着本地代理
 * （Clash/代理 App 的 fake-IP + 系统代理模式）时，域名解析出来的是代理
 * 的假地址，只有把请求交给代理才能连通——LibVLC 直连会一直卡在建连阶段，
 * 表现为 `read=0` 然后超时。
 *
 * 这里按和 OkHttp 相同的方式取一次代理，交给 LibVLC 的 `:http-proxy`。
 */
internal fun systemHttpProxyFor(url: String): String? {
    val proxy = runCatching {
        ProxySelector.getDefault()?.select(URI(url))?.firstOrNull()
    }.getOrNull()
    return httpProxyUrlOf(proxy)
}

/** 把 [Proxy] 折成 libVLC 认的 `http://host:port`；非 HTTP 代理返回 null。 */
internal fun httpProxyUrlOf(proxy: Proxy?): String? {
    if (proxy == null || proxy.type() != Proxy.Type.HTTP) return null
    val address = proxy.address() as? InetSocketAddress ?: return null
    val host = address.hostString ?: return null
    if (host.isBlank() || address.port <= 0) return null
    return "http://$host:${address.port}"
}
