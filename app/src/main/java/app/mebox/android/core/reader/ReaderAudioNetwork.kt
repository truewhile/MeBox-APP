package app.mebox.android.core.reader

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 复用 API 预热的连接池/DNS/统一 UA；音轨不进入应用的接口磁盘缓存。 */
internal fun createReaderAudioHttpClient(shared: OkHttpClient): OkHttpClient =
    shared.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
