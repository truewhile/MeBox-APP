package app.mebox.android.core.reader

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 直连媒体请求头的本地登记表。
 *
 * 书源直连时，音频/漫画图片由 App 自己去上游取，而防盗链需要的 Referer/UA
 * 与登录 Cookie 只有服务端知道（见 `GET /api/reader/books/:id/content?media=direct`
 * 下发的 `media_headers`）。这里把「哪个地址该带哪些头」暂存起来，由共享
 * HTTP 客户端上的 [ReaderMediaHeaderInterceptor] 在真正发请求时套用。
 *
 * 为什么不直接把头塞进播放器/图片请求：ExoPlayer 的 OkHttpDataSource 与 Coil
 * 各自构造请求，逐个调用点传头会漏（离线缓存、预取、图片加载器各一套）。
 * 集中到拦截器后，只要地址能对上，任何通道取媒体都会带上正确的头。
 *
 * 只在直连模式下有条目：代理地址（`/api/reader/media`）走服务端，不需要任何
 * 书源头，也不会命中这里。
 */
object ReaderMediaHeaders {

    /** 单条登记最多保留的头数量，避免异常书源把内存撑大。 */
    private const val MAX_HEADERS = 24

    /** 请求头总量上限：超过就整条丢弃（宁可不带，也不要拼出畸形请求）。 */
    private const val MAX_ENTRIES = 256

    private val lock = Any()

    /** 绝对地址 → 请求头。按完整 URL 对齐，避免不同源的同名地址互相串味。 */
    private val byUrl = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>) =
            size > MAX_ENTRIES
    }

    /**
     * 为一批媒体地址登记请求头。
     *
     * [urls] 是服务端下发的直连地址（可能带 `,{headers:...}` 选项段），
     * 这里只登记去掉选项段后的基础地址 —— 请求时 URL 也是基础地址。
     */
    fun register(urls: Iterable<String>, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val cleaned = headers.entries
            .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
            .take(MAX_HEADERS)
            .associate { (name, value) -> name to value }
        if (cleaned.isEmpty()) return
        synchronized(lock) {
            urls.forEach { raw ->
                val url = baseUrl(raw) ?: return@forEach
                byUrl[url] = cleaned
            }
        }
    }

    /** 某个地址该带的头；没有登记时返回空表。 */
    fun headersFor(url: String): Map<String, String> {
        val key = baseUrl(url) ?: return emptyMap()
        return synchronized(lock) { byUrl[key] ?: emptyMap() }
    }

    /** 换书/换账号时清空：旧书的防盗链头不该继续发给新书。 */
    fun clear() = synchronized(lock) { byUrl.clear() }

    /**
     * 登记一章正文里的全部直连媒体地址。
     *
     * 代理模式下 `media_direct` 为 false，这里什么都不做：服务端地址自带
     * 签名，不需要也不应该带上书源请求头。
     */
    fun registerChapter(
        mediaDirect: Boolean,
        headers: Map<String, String>,
        tracks: Iterable<String>?,
        images: Iterable<String>?,
        content: String = "",
    ) {
        if (!mediaDirect || headers.isEmpty()) return
        val urls = buildList {
            tracks?.let { addAll(it) }
            images?.let { addAll(it) }
            // 文本型漫画源的正文用 [img] 标记内嵌图片地址。
            content.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith(IMG_MARK) }
                .forEach { add(it.removePrefix(IMG_MARK).trim()) }
        }
        register(urls, headers)
    }

    private const val IMG_MARK = "[img]"

    /**
     * 去掉 legado 的 URL 尾部选项段（`,{headers:{...}}`）。
     *
     * 与服务端 `rule.ParseMediaOptions` 的切分规则一致：只有逗号后面紧跟
     * `{` 才当选项段，正文里带逗号的正常地址不会被误切。
     */
    fun baseUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        var index = value.indexOf(',')
        while (index >= 0) {
            var next = index + 1
            while (next < value.length && value[next].isWhitespace()) next++
            if (next < value.length && value[next] == '{') {
                return value.substring(0, index).trim().ifEmpty { null }
            }
            index = value.indexOf(',', index + 1)
        }
        return value
    }
}

/**
 * 给命中的媒体请求补上直连所需的书源头。
 *
 * 只在 [ReaderMediaHeaders] 里登记过的地址生效，其余请求完全不受影响；
 * 已显式设置的同名头不覆盖（调用方更清楚自己要什么）。
 */
class ReaderMediaHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = ReaderMediaHeaders.headersFor(request.url.toString())
        if (headers.isEmpty()) return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (name, value) ->
            if (request.header(name) == null) builder.header(name, value)
        }
        return chain.proceed(builder.build())
    }
}
