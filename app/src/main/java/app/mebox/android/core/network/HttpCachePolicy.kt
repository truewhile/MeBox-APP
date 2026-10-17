package app.mebox.android.core.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * 给「读多写少」的 GET 响应补上客户端缓存策略。
 *
 * 服务端只对图片下发 `Cache-Control`，JSON 接口没有缓存头，于是 OkHttp 的
 * 磁盘缓存永远不会命中：每次启动、每次切页都要把整页数据重新下载一遍
 * （首页第二波就有近 0.5MB gzip）。这里按路径给出明确 TTL，让「首屏立即
 * 有内容、后台再刷新」成为可能，而不是每次都等网络。
 *
 * 只在响应没有自带 `Cache-Control` 时补充，服务端一旦显式声明（例如
 * `no-store`）以服务端为准。`Vary: Authorization` 保证换账号后不会读到上
 * 个账号的缓存。
 */
internal class HttpCachePolicyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 200) return response
        if (response.header("Cache-Control") != null) return response
        val ttl = HttpCachePolicy.ttlSeconds(request) ?: return response
        val vary = response.header("Vary")
        val varyValue = when {
            vary.isNullOrBlank() -> "Authorization"
            vary.contains("Authorization", ignoreCase = true) -> vary
            else -> "$vary, Authorization"
        }
        return response.newBuilder()
            .header("Cache-Control", "private, max-age=$ttl")
            .header("Vary", varyValue)
            .build()
    }
}

/** 各接口的客户端缓存时长（秒）；返回 null 表示不缓存。 */
internal object HttpCachePolicy {

    /** 媒体库列表 / 首页预览 / 最近添加：随扫描变化，短缓存即可。 */
    private const val LIBRARY_TTL = 30

    /** 继续观看：刚看完一集就要能立刻看到新进度。 */
    private const val CONTINUE_TTL = 20

    /** 媒体详情与剧集：稳定数据，但刮削会更新。 */
    private const val DETAIL_TTL = 120
    private const val EPISODES_TTL = 300

    /** 字幕轨与筛选面板：只随文件/库内容变化。 */
    private const val STATIC_TTL = 3600
    private const val FACETS_TTL = 300

    /** 弹幕：服务端本身缓存 6 小时，识别结果不会频繁变化。 */
    private const val DANMAKU_TTL = 1800

    /** 阅读：书架、分组、阅读设置。 */
    private const val READER_TTL = 60
    private const val READER_CHAPTERS_TTL = 600

    fun ttlSeconds(request: Request): Int? {
        if (request.method != "GET") return null
        val url = request.url
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "api") return null
        if (url.queryParameter("refresh") != null || url.queryParameter("retry") != null) return null

        val resource = segments[1]
        val last = segments.last()
        return when {
            segments.size == 2 && resource == "libraries" -> LIBRARY_TTL

            resource == "media" && segments.size == 3 ->
                if (last == "recent") LIBRARY_TTL else DETAIL_TTL
            resource == "media" && segments.size == 4 ->
                when (last) {
                    "subtitles" -> STATIC_TTL
                    // 电影只有一条"剧集"行，详情页也用它渲染播放列表。
                    "episodes" -> EPISODES_TTL
                    else -> null
                }

            resource == "libraries" && segments.size == 4 && last == "facets" -> FACETS_TTL
            resource == "libraries" && segments.size == 4 && last == "series" -> LIBRARY_TTL
            resource == "libraries" && segments.size == 5 && last == "episodes" -> EPISODES_TTL

            resource == "danmaku" && segments.size == 3 ->
                if (last == "config") LIBRARY_TTL else DANMAKU_TTL

            resource == "watch-history" && segments.size == 3 && last == "continue" -> CONTINUE_TTL

            resource == "reader" && segments.size == 3 -> when (last) {
                "books", "profile", "book-groups" -> READER_TTL
                "sources" -> LIBRARY_TTL
                else -> null
            }
            resource == "reader" && segments.size == 5 && last == "chapters" -> READER_CHAPTERS_TTL

            else -> null
        }
    }
}
