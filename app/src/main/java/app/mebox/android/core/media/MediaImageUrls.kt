package app.mebox.android.core.media

import java.net.URLEncoder

/**
 * 把媒体行里的本地路径或远程 URL 转成 MeBox 图片代理地址。
 *
 * 后端 `/api/img` 会读取允许范围内的本地文件并缓存缩略图；直接使用
 * `File` 路径无法被 Coil 访问，也会把服务器文件系统暴露给客户端逻辑。
 *
 * 尺寸档位刻意与 `web/src/api/client.ts` 的 `ARTWORK` 保持一致：服务端按
 * (源文件, maxWidth, maxHeight, quality) 缓存缩放结果，每多一个组合就是
 * 一次完整解码 + 一份额外的缓存文件。两端共用同一组尺寸，网页已经生成过
 * 的档位 App 可以直接命中。
 */
object MediaImageUrls {

    /** 与网页端 `ARTWORK.posterCard` 一致（480x600, q80）。 */
    fun poster(origin: String, remote: String?, width: Int = 480, height: Int = 600): String? =
        build(origin, remote, width, height, quality = 80)

    /**
     * 列表/卡片里的剧照，对齐网页端 `ARTWORK.backdropStrip`（480x320, q80）。
     *
     * 容器多是 16:9，这里按 3:2 取图再 `ContentScale.Crop`，与网页的裁切
     * 行为一致；比原来 720x405 / 640x400 / 480x270 三种尺寸省流量，也少
     * 两次服务端解码。
     */
    fun backdrop(origin: String, remote: String?, width: Int = 480, height: Int = 320): String? =
        build(origin, remote, width, height, quality = 80)

    /** 首页轮播、详情页与播放页背景：唯一需要高于 480 宽的档位。 */
    fun backdropHero(
        origin: String,
        remote: String?,
        width: Int = 1280,
        height: Int = 720,
    ): String? = build(origin, remote, width, height, quality = 80)

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
