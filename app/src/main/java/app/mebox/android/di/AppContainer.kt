package app.mebox.android.di

import android.content.Context
import android.os.SystemClock
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.log.LogShareServer
import app.mebox.android.core.network.MeBoxApiClient
import app.mebox.android.core.network.ServerAddress
import app.mebox.android.core.network.createMobileApi
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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 手动依赖组装；页面不直接创建网络或存储对象。 */
class AppContainer(context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 所有请求都经过这里：排查「连不上 / 401 / 超时 / 某个接口慢」时，
        // 导出的日志里能直接看到方法、路径、状态码和耗时。
        // 只记录查询参数名，不记录参数值，避免把 token 写进日志。
        .addInterceptor { chain ->
            val request = chain.request()
            val summary = "${request.method} ${request.url.forLog()}"
            val started = SystemClock.elapsedRealtime()
            try {
                val response = chain.proceed(request)
                val cost = SystemClock.elapsedRealtime() - started
                if (response.code >= 400) {
                    DiagnosticsLog.w("Net", "${response.code} ${cost}ms $summary")
                } else {
                    DiagnosticsLog.d("Net", "${response.code} ${cost}ms $summary")
                }
                response
            } catch (error: Throwable) {
                val cost = SystemClock.elapsedRealtime() - started
                DiagnosticsLog.e("Net", "请求失败 ${cost}ms $summary", error)
                throw error
            }
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

/**
 * 日志里只保留协议、主机、端口和路径：
 * 查询参数值可能包含访问令牌或用户搜索词，只保留参数名。
 */
private fun HttpUrl.forLog(): String = buildString {
    append(scheme).append("://").append(host)
    if (port != HttpUrl.defaultPort(scheme)) append(':').append(port)
    append(encodedPath)
    if (querySize > 0) {
        append('?')
        append(queryParameterNames.joinToString("&") { "$it=…" })
    }
}
