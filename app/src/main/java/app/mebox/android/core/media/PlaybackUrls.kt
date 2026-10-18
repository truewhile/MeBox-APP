package app.mebox.android.core.media

import java.net.URLEncoder

/** 播放地址处理。 */
object PlaybackUrls {

    /**
     * 把 MeBox 播放地址里的媒体级短票据替换成当前访问令牌。
     *
     * 远程 Emby 条目会重定向到同挂载下的另一个条目，短票据的 media_id
     * 范围校验会拒绝这次跳转；访问令牌按用户权限重新校验，行为与网页
     * 播放器一致。令牌只出现在服务器同源 URL 中，跨来源重定向不会带上。
     */
    fun withAccessToken(url: String, accessToken: String?): String {
        if (accessToken.isNullOrBlank()) return url
        val encoded = URLEncoder.encode(accessToken, "UTF-8")
        return if (url.contains("token=")) {
            url.replace(Regex("token=[^&]*"), "token=$encoded")
        } else {
            url + (if (url.contains('?')) "&" else "?") + "token=$encoded"
        }
    }

    /**
     * 把 `/api/stream/{id}` 改成服务端同源转发模式（`proxy=1`）。
     *
     * 网盘直链被 CDN 403（UA/地区/防盗链策略变化）时，由服务端代为拉流：
     * 服务端到 CDN 的线路通常可用，客户端只面对自己的 MeBox 服务器。
     * 只作为直连失败后的兜底，正常播放仍走直连以节省服务器带宽。
     */
    fun withSameOriginProxy(url: String): String =
        url + (if (url.contains('?')) "&" else "?") + "proxy=1"

    /**
     * 字幕加载地址。
     *
     * `/api/subtitles/:id` 需要鉴权；ExoPlayer 的字幕请求无法附加自定义头，
     * 因此与视频流一致，把访问令牌放进同源查询参数。`path` 可能是
     * `embedded:3` 或带空格/CJK 的绝对路径，必须完整编码。
     */
    fun subtitleUrl(
        origin: String,
        mediaId: String,
        path: String,
        accessToken: String?,
    ): String = subtitleEndpoint(origin, mediaId, path, accessToken, suffix = "")

    /**
     * ASS/SSA 原始字幕地址。
     *
     * 服务端的 `/api/subtitles/:id` 会把 ASS 压平成 WebVTT（丢弃字体、颜色、
     * 描边和定位），而 `/api/subtitles/:id/ass` 返回原始字节或 ffmpeg 重封装的
     * ASS。LibVLC 内置 libass，可以直接按文件自带的样式渲染，所以只有支持
     * 该格式的内核才使用这个地址。
     */
    fun assSubtitleUrl(
        origin: String,
        mediaId: String,
        path: String,
        accessToken: String?,
    ): String = subtitleEndpoint(origin, mediaId, path, accessToken, suffix = "/ass")

    private fun subtitleEndpoint(
        origin: String,
        mediaId: String,
        path: String,
        accessToken: String?,
        suffix: String,
    ): String {
        val base = origin.trimEnd('/')
        val encodedPath = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val builder = StringBuilder(base)
            .append("/api/subtitles/")
            .append(mediaId)
            .append(suffix)
            .append("?path=")
            .append(encodedPath)
        if (!accessToken.isNullOrBlank()) {
            builder.append("&token=").append(URLEncoder.encode(accessToken, "UTF-8"))
        }
        return builder.toString()
    }
}
