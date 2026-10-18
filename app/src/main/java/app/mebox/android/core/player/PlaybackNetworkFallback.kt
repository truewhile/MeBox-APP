package app.mebox.android.core.player

import java.net.URI

/**
 * 保留已验证有效的 115 CDN HTTP 兼容重试，但不能把服务器登录票据或任意
 * 第三方 HTTPS 链接降级为明文。显式端口不改写，避免 HTTP 请求误发到 TLS 端口。
 */
internal fun cdnHttpFallbackUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.port != -1 || uri.userInfo != null) return null
    if (!host.endsWith(".115cdn.net")) return null
    if (Regex("(?:^|&)(?:token|access_token|authorization)=", RegexOption.IGNORE_CASE)
            .containsMatchIn(uri.query.orEmpty())
    ) return null
    return "http://" + url.substring("https://".length)
}

/** VLC 的 native 调试输出会带完整签名 URL；保留参数名、HTTP 状态和 Range。 */
internal fun redactPlaybackLog(log: String): String =
    Regex("([?&][^\\s?&='\"`]+)=([^\\s&'\"`]+)").replace(log) { "${it.groupValues[1]}=…" }
