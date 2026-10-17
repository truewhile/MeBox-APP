package app.mebox.android.core.network

import java.util.Locale

/**
 * 规范化后的 MeBox 服务器地址。
 *
 * 首版只支持根路径部署：地址只包含 scheme、host 与可选端口。反代子路径
 * 需要单独兼容，解析时明确拒绝而不是静默截断，否则所有后续请求都会打到
 * 错误的路径上。
 */
data class ServerAddress(
    val scheme: String,
    val host: String,
    val port: Int?,
) {
    val isCleartext: Boolean
        get() = scheme == "http"

    /** 规范化来源，例如 `https://example.com:8443` 或 `http://[fe80::1]:8080`。 */
    val origin: String
        get() = buildString {
            append(scheme)
            append("://")
            if (host.contains(':')) {
                append('[').append(host).append(']')
            } else {
                append(host)
            }
            port?.let { append(':').append(it) }
        }

    /** Retrofit/OkHttp 使用的根地址，始终以 `/` 结尾。 */
    val baseUrl: String
        get() = "$origin/"

    companion object {
        /**
         * 解析用户输入。无 scheme 时默认 HTTPS；解析失败不会隐式降级到 HTTP。
         */
        fun parse(raw: String): ServerAddressResult {
            val input = raw.trim()
            if (input.isEmpty()) {
                return ServerAddressResult.Invalid(ServerAddressError.EMPTY)
            }
            if (input.any { it.isWhitespace() || it.isISOControl() }) {
                return ServerAddressResult.Invalid(ServerAddressError.INVALID_ADDRESS)
            }
            if (input.contains('\\')) {
                return ServerAddressResult.Invalid(ServerAddressError.INVALID_ADDRESS)
            }

            val schemeSeparator = input.indexOf("://")
            val scheme: String
            val rest: String
            if (schemeSeparator >= 0) {
                scheme = input.substring(0, schemeSeparator).lowercase(Locale.ROOT)
                rest = input.substring(schemeSeparator + 3)
                if (scheme != "http" && scheme != "https") {
                    return ServerAddressResult.Invalid(ServerAddressError.UNSUPPORTED_SCHEME)
                }
            } else {
                scheme = "https"
                rest = input
            }

            if (rest.isEmpty()) {
                return ServerAddressResult.Invalid(ServerAddressError.INVALID_ADDRESS)
            }
            if (rest.contains('?') || rest.contains('#')) {
                return ServerAddressResult.Invalid(ServerAddressError.QUERY_OR_FRAGMENT)
            }

            val authorityAndPath = rest
            val slashIndex = authorityAndPath.indexOf('/')
            val authority: String
            if (slashIndex >= 0) {
                authority = authorityAndPath.substring(0, slashIndex)
                val path = authorityAndPath.substring(slashIndex)
                if (path.any { it != '/' }) {
                    return ServerAddressResult.Invalid(ServerAddressError.SUBPATH_UNSUPPORTED)
                }
            } else {
                authority = authorityAndPath
            }
            if (authority.isEmpty()) {
                return ServerAddressResult.Invalid(ServerAddressError.INVALID_HOST)
            }
            if (authority.contains('@')) {
                return ServerAddressResult.Invalid(ServerAddressError.EMBEDDED_CREDENTIALS)
            }
            if (!hasValidPortText(authority)) {
                return ServerAddressResult.Invalid(ServerAddressError.INVALID_PORT)
            }

            val parsedHostPort = parseHostAndPort(authority)
                ?: return ServerAddressResult.Invalid(ServerAddressError.INVALID_HOST)
            val (host, port) = parsedHostPort
            if (port != null && (port < 1 || port > 65535)) {
                return ServerAddressResult.Invalid(ServerAddressError.INVALID_PORT)
            }
            return ServerAddressResult.Valid(
                ServerAddress(scheme = scheme, host = host.lowercase(Locale.ROOT), port = port)
            )
        }

        private fun hasValidPortText(authority: String): Boolean {
            val bracket = authority.startsWith("[")
            val separator = if (bracket) {
                val close = authority.indexOf(']')
                if (close < 0) return true // 交给 host 校验给出明确错误
                if (close == authority.lastIndex) return true
                if (authority[close + 1] != ':') return true
                close + 1
            } else {
                val colonCount = authority.count { it == ':' }
                if (colonCount != 1) return true
                authority.lastIndexOf(':')
            }
            val portText = authority.substring(separator + 1)
            return portText.isNotEmpty() && portText.length <= 5 && portText.all { it in '0'..'9' }
        }

        private fun parseHostAndPort(authority: String): Pair<String, Int?>? {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close <= 1) return null
                val host = authority.substring(1, close)
                if (!isValidIpv6(host)) return null
                val remainder = authority.substring(close + 1)
                return when {
                    remainder.isEmpty() -> host to null
                    remainder.startsWith(":") -> {
                        val port = parsePort(remainder.substring(1)) ?: return null
                        host to port
                    }
                    else -> null
                }
            }

            val colonCount = authority.count { it == ':' }
            if (colonCount > 1) {
                // 未加括号的 IPv6 无法与端口区分，明确拒绝。
                return null
            }
            val host: String
            val port: Int?
            if (colonCount == 1) {
                val idx = authority.lastIndexOf(':')
                host = authority.substring(0, idx)
                port = parsePort(authority.substring(idx + 1)) ?: return null
            } else {
                host = authority
                port = null
            }
            if (!isValidHost(host)) return null
            return host to port
        }

        private fun parsePort(raw: String): Int? {
            if (raw.isEmpty() || raw.length > 5) return null
            if (!raw.all { it in '0'..'9' }) return null
            return raw.toIntOrNull()
        }

        private fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            if (isIpv4(host)) return true
            val labels = host.split('.')
            // 数字结尾的“主机名”是 IPv4 的典型写法；256.1.1.1 这类越界值
            // 不能被当作普通域名接受。
            if (labels.last().all { it in '0'..'9' }) {
                return false
            }
            if (labels.size < 2) {
                // 单标签主机名（localhost、容器名）在局域网场景合法，但仍需
                // 限制字符集，避免把任意字符串当主机。
                return isValidLabel(host)
            }
            return labels.all { isValidLabel(it) }
        }

        private fun isValidLabel(label: String): Boolean {
            if (label.isEmpty() || label.length > 63) return false
            if (!label.all { it.isAsciiLetterOrDigit() || it == '-' }) return false
            return !label.startsWith('-') && !label.endsWith('-')
        }

        private fun isIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 &&
                    part.all { it in '0'..'9' } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        private fun isValidIpv6(host: String): Boolean {
            if (host.isEmpty() || host.length > 45) return false
            if (!host.contains(':')) return false
            // 只接受十六进制、冒号与 IPv4 内嵌点；区域标识（%eth0）暂不支持。
            if (!host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return false
            }
            if (host.count { it == ':' } > 7) return false
            // 拒绝连续三个以上冒号。
            return !host.contains(":::")
        }

        private fun Char.isAsciiLetterOrDigit(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}

enum class ServerAddressError {
    EMPTY,
    INVALID_ADDRESS,
    UNSUPPORTED_SCHEME,
    INVALID_HOST,
    INVALID_PORT,
    EMBEDDED_CREDENTIALS,
    QUERY_OR_FRAGMENT,
    SUBPATH_UNSUPPORTED,
}

sealed interface ServerAddressResult {
    data class Valid(val address: ServerAddress) : ServerAddressResult
    data class Invalid(val error: ServerAddressError) : ServerAddressResult
}
