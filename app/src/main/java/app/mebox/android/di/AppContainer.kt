package app.mebox.android.di

import android.content.Context
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.log.LogShareServer
import app.mebox.android.core.network.APP_USER_AGENT
import app.mebox.android.core.network.HttpCachePolicyInterceptor
import app.mebox.android.core.network.MeBoxApiClient
import app.mebox.android.core.network.NetTimingListener
import app.mebox.android.core.network.ServerAddress
import app.mebox.android.core.network.createMobileApi
import app.mebox.android.core.network.forNetLog
import app.mebox.android.core.media.WatchProgressStore
import app.mebox.android.core.reader.ReaderAudioEngine
import app.mebox.android.core.reader.ReaderCacheStore
import app.mebox.android.core.reader.ReaderPreferences
import app.mebox.android.core.security.CredentialStore
import app.mebox.android.core.security.KeystoreCredentialStore
import app.mebox.android.core.storage.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** 手动依赖组装；页面不直接创建网络或存储对象。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * 统一的网络客户端。
     *
     * - 带磁盘缓存：JSON 接口按路径给定 TTL（见 [HttpCachePolicy]），
     *   所以切回首页/详情页可以先出内容再后台刷新，而不是每次等网络；
     * - [NetTimingListener] 把耗时拆成 DNS / 建连 / TLS / 服务端四段，
     *   日志里能直接看出慢在链路还是服务端；
     * - Coil 的图片加载复用同一个连接池（见 createImageLoader），避免为
     *   图片再建一套 TLS 连接。
     */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 手机到境外服务器的链路经常被 NAT/代理提前回收空闲连接，导致下一次
        // 请求被迫重新 DNS+TCP+TLS（实测常是 5 秒级，正好压在起播路径上）。
        // HTTP/2 PING 让连接保持活跃，也能提前发现死连接。
        .pingInterval(20, TimeUnit.SECONDS)
        .cache(Cache(File(appContext.cacheDir, "http-cache"), HTTP_CACHE_BYTES))
        .eventListener(NetTimingListener())
        // 统一 UA：115 直链绑定换取时的 UA，播放地址解析与播放器拉流必须
        // 一致，否则信息接口预置的直链在播放时会被 403。
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", APP_USER_AGENT)
                    .build(),
            )
        }
        // 网络拦截器：在写缓存之前补齐 Cache-Control，应用拦截器做不到
        // 这一点（CacheInterceptor 在网络链更内层）。
        .addNetworkInterceptor(HttpCachePolicyInterceptor())
        .addInterceptor { chain ->
            // 命中磁盘缓存时不会产生网络事件，单独记一行便于确认「这次没
            // 走网络」。查询参数只记参数名，不把 token 写进日志。
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.networkResponse == null && response.cacheResponse != null) {
                DiagnosticsLog.d(
                    "Net",
                    "${response.code} CACHE ${request.method} ${request.url.forNetLog()}",
                )
            }
            response
        }
        .addInterceptor { chain ->
            // 书源搜索/抓目录/正文由服务端同步访问上游，慢源可能远超普通接口。
            // 只对阅读端点放宽读取超时，不把全部请求的超时无限放大。
            val request = chain.request()
            val path = request.url.encodedPath
            val readTimeout = when {
                // 登录动作可能通过 startBrowserAwait 等用户操作，服务端上限 10 分钟。
                path.contains("/reader/sources/") && path.endsWith("/login") -> 660L
                path.contains("/reader/") -> 120L
                // 弹幕识别是「16MB 前缀哈希 → 上游匹配 → 评论库」串行请求，
                // 每段上游超时 20 秒；客户端必须比服务端更耐心，否则会在服务端
                // 正要降级返回时撤掉请求。
                path.contains("/danmaku/") -> 90L
                else -> null
            }
            if (readTimeout != null) {
                chain
                    .withReadTimeout(readTimeout.toInt(), TimeUnit.SECONDS)
                    .proceed(request)
            } else {
                chain.proceed(request)
            }
        }
        .build()

    /** 退出登录/切换服务器时清掉 HTTP 缓存，避免读到上一个账号的数据。 */
    fun clearHttpCache() {
        runCatching { httpClient.cache?.evictAll() }
    }

    val credentialStore: CredentialStore = KeystoreCredentialStore(context, json)

    val preferences = AppPreferences(context)

    val watchProgress = app.mebox.android.core.media.WatchProgressStore(context)

    val readerPreferences = ReaderPreferences(context)

    /** 应用级作用域：听书引擎在界面销毁后仍要能自动下一章与上报进度。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val readerCache = ReaderCacheStore(context.applicationContext, httpClient, json)

    val readerAudio = ReaderAudioEngine(context.applicationContext, appScope, readerCache)

    val deviceCodecSupport = app.mebox.android.core.media.DeviceCodecSupport()

    /** 电视端的手机扫码输入服务；手机端不会启动它。 */
    val phoneInput = app.mebox.android.core.input.PhoneInputServer()

    /** 日志局域网下载服务；只在「分享日志」对话框打开期间运行。 */
    val logShare = LogShareServer(context.applicationContext)

    fun apiFor(address: ServerAddress): MeBoxApiClient =
        MeBoxApiClient(createMobileApi(address, httpClient, json), json)
}

private const val HTTP_CACHE_BYTES = 48L * 1024L * 1024L
