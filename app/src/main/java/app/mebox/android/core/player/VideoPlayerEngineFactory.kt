package app.mebox.android.core.player

import android.content.Context
import okhttp3.OkHttpClient

/**
 * 按用户选择创建播放内核。
 *
 * 新增内核时只需要在这里加一个分支；播放页与设置页都通过
 * [PlayerEngineKind] 枚举驱动，不需要改界面代码。
 *
 * [httpClient] 是 App 共享的 OkHttp 客户端：支持它的内核会复用其连接池
 * （起播前 API 已经建好的连接可以直接拿来拉流），传 null 时内核自行建连。
 */
fun createVideoPlayerEngine(
    kind: PlayerEngineKind,
    context: Context,
    httpClient: OkHttpClient? = null,
): VideoPlayerEngine =
    when (kind) {
        PlayerEngineKind.EXO -> ExoVideoPlayerEngine(context, httpClient)
        PlayerEngineKind.VLC -> VlcVideoPlayerEngine(context.applicationContext)
    }
