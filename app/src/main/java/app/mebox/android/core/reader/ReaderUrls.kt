package app.mebox.android.core.reader

/**
 * 阅读资源的地址解析。
 *
 * 服务端下发的封面、漫画图片与正文内嵌图片可能是绝对地址，也可能是
 * `/api/reader/media?...` 形式的同源签名代理路径。签名代理不需要 JWT，
 * 但仍要补上服务器来源才能被图片加载器访问。
 */
object ReaderUrls {

    fun isRenderable(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("file://") ||
            lower.startsWith("content://") ||
            lower.startsWith("data:image/") ||
            value.startsWith("/")
    }

    fun resolve(origin: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("file://") || value.startsWith("content://") -> value
            value.startsWith("data:") -> value
            value.startsWith("/") -> origin.trimEnd('/') + value
            else -> origin.trimEnd('/') + "/" + value
        }
    }
}
