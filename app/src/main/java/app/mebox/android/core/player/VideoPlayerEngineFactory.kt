package app.mebox.android.core.player

import android.content.Context

/**
 * 按用户选择创建播放内核。
 *
 * 新增内核时只需要在这里加一个分支；播放页与设置页都通过
 * [PlayerEngineKind] 枚举驱动，不需要改界面代码。
 */
fun createVideoPlayerEngine(kind: PlayerEngineKind, context: Context): VideoPlayerEngine =
    when (kind) {
        PlayerEngineKind.EXO -> ExoVideoPlayerEngine(context)
        PlayerEngineKind.VLC -> VlcVideoPlayerEngine(context.applicationContext)
    }
