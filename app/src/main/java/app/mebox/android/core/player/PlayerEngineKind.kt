package app.mebox.android.core.player

/**
 * 可选播放内核。
 *
 * App 默认使用系统解码（ExoPlayer），需要兼容更多容器/编码时可以切换到
 * LibVLC（内置 FFmpeg，软件解码为主）。选择结果保存在本机偏好里，作用范围
 * 是整台设备，不影响服务端。
 *
 * 这里保持纯 Kotlin，便于在 JVM 单元测试里验证持久化键的兼容性。
 */
enum class PlayerEngineKind(
    val key: String,
    /** 设置页里的完整名称。 */
    val label: String,
    /** 播放页等窄空间使用的短名称。 */
    val shortLabel: String,
    val description: String,
) {
    EXO(
        key = "exo",
        label = "系统解码（ExoPlayer）",
        shortLabel = "系统解码",
        description = "Media3 ExoPlayer，优先调用手机的硬件解码器；省电、启动快，" +
            "清晰度和字幕切换最完整。部分冷门编码/容器需要切换内核。",
    ),
    VLC(
        key = "vlc",
        label = "LibVLC（FFmpeg 解码）",
        shortLabel = "LibVLC",
        description = "内置 FFmpeg 解码器，兼容 RMVB、WMV、DTS 等系统不支持的格式；" +
            "默认优先硬件解码，必要时可回退软件解码，功耗和发热更高。",
    ),
    ;

    companion object {
        /** 新安装与旧版本升级后的默认内核。 */
        val default: PlayerEngineKind = EXO

        /** 读取持久化值时容错：未知或空值一律回落到默认内核。 */
        fun fromKey(key: String?): PlayerEngineKind =
            entries.firstOrNull { it.key == key } ?: default

        /**
         * 系统解码失败时应当自动换成哪个内核；没有可用备选时返回 null。
         *
         * 只走 ExoPlayer → LibVLC 一条路：设备对 H.264 High 10、10-bit HEVC
         * 这类编码不一定有硬解，系统软解也不一定覆盖，可以交给 LibVLC 内置的 FFmpeg。
         */
        fun automaticFallback(current: PlayerEngineKind): PlayerEngineKind? =
            when (current) {
                EXO -> VLC
                VLC -> null
            }

        /**
         * 是否要在这次错误后自动换内核。
         *
         * @param decodeRelated 播放器判定为解码/格式不支持，而不是网络问题。
         * @param alreadyAttempted 本次播放已经自动切换过一次，避免来回循环。
         */
        fun shouldAutoFallback(
            current: PlayerEngineKind,
            decodeRelated: Boolean,
            alreadyAttempted: Boolean,
        ): Boolean =
            decodeRelated && !alreadyAttempted && automaticFallback(current) != null
    }
}
