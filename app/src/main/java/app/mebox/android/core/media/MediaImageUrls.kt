package app.mebox.android.core.media

import java.net.URLEncoder

/**
 * 把媒体行里的本地路径或远程 URL 转成 MeBox 图片代理地址。
 *
 * 后端 `/api/img` 会读取允许范围内的本地文件并缓存缩略图；直接使用
 * `File` 路径无法被 Coil 访问，也会把服务器文件系统暴露给客户端逻辑。
 */
object MediaImageUrls {

    fun poster(origin: String, remote: String?, width: Int = 480, height: Int = 600): String? =
        build(origin, remote, width, height, quality = 80)

    fun backdrop(origin: String, remote: String?, width: Int = 1280, height: Int = 720): String? =
        build(origin, remote, width, height, quality = 78)

    private fun build(
        origin: String,
        remote: String?,
        width: Int,
        height: Int,
        quality: Int,
    ): String? {
        val value = remote?.trim().orEmpty()
        if (value.isEmpty()) return null
        val base = when {
            value.startsWith("/api/") -> origin + value
            value.startsWith("http://") || value.startsWith("https://") ->
                "$origin/api/img?url=${encodePath(value)}"
            else -> "$origin/api/img?url=${encodePath(value)}"
        }
        val separator = if (base.contains('?')) "&" else "?"
        return "$base${separator}maxWidth=$width&maxHeight=$height&quality=$quality"
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
