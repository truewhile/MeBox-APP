package app.mebox.android.di

import android.content.Context
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

    fun apiFor(address: ServerAddress): MeBoxApiClient =
        MeBoxApiClient(createMobileApi(address, httpClient, json), json)
}
