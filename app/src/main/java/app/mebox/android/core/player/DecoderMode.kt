package app.mebox.android.core.player

/**
 * 解码方式。用户可以手动指定走硬件解码还是软件解码，覆盖内核的默认策略。
 *
 * 纯 Kotlin，便于在 JVM 单元测试里验证持久化键的兼容性。
 */
enum class DecoderMode(
    val key: String,
    val label: String,
    val description: String,
) {
    AUTO(
        key = "auto",
        label = "自动",
        description = "优先硬件解码；失败或起播卡住时按内核规则自动回退软件解码",
    ),
    HARDWARE(
        key = "hardware",
        label = "硬件解码",
        description = "强制使用硬件解码，不自动回退软件；省电、适合 4K，但 10-bit H.264 可能不支持",
    ),
    SOFTWARE(
        key = "software",
        label = "软件解码",
        description = "跳过硬件解码器；LibVLC 用内置 FFmpeg，系统内核用 Android 软解；功耗更高",
    ),
    ;

    companion object {
        /** 新安装与旧版本升级后的默认策略。 */
        val default: DecoderMode = AUTO

        /** 读取持久化值时容错：未知或空值一律回落到默认值。 */
        fun fromKey(key: String?): DecoderMode =
            entries.firstOrNull { it.key == key } ?: default
    }
}
