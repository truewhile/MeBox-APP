package app.mebox.android.core.player

/**
 * 播放器双指/双击/长按等交互偏好。
 *
 * 参考 CapyPlayer 的播放器设置：双击左右半屏、左右两侧长按、滑动亮度音量、
 * 界面按钮显隐都可以独立配置。这里保持纯 Kotlin，便于 JVM 单元测试验证
 * 持久化键和默认值。
 */
enum class PlayerDoubleTapAction(val key: String, val label: String) {
    SEEK_BACK("seek_back", "快退"),
    SEEK_FORWARD("seek_forward", "快进"),
    PLAY_PAUSE("play_pause", "暂停/播放"),
    NONE("none", "无"),
    ;

    companion object {
        fun fromKey(key: String?): PlayerDoubleTapAction? =
            entries.firstOrNull { it.key == key }
    }
}

enum class PlayerLongPressAction(val key: String, val label: String) {
    FAST("fast", "长按倍速"),
    SLOW("slow", "长按慢速"),
    SEEK_FORWARD("seek_forward", "快进"),
    SEEK_BACK("seek_back", "快退"),
    NEXT_EPISODE("next", "下一集"),
    PREVIOUS_EPISODE("prev", "上一集"),
    ;

    companion object {
        fun fromKey(key: String?): PlayerLongPressAction? =
            entries.firstOrNull { it.key == key }
    }
}

enum class PlayerOrientation(val key: String, val label: String) {
    SENSOR("sensor", "传感器方向"),
    LANDSCAPE("landscape", "横屏"),
    PORTRAIT("portrait", "竖屏"),
    ;

    companion object {
        fun fromKey(key: String?): PlayerOrientation =
            entries.firstOrNull { it.key == key } ?: SENSOR
    }
}

/** 播放器界面/手势设置。字段都有稳定默认值，升级后不会重置用户体验。 */
data class PlayerUiSettings(
    val doubleTapLeft: PlayerDoubleTapAction = PlayerDoubleTapAction.SEEK_BACK,
    val doubleTapRight: PlayerDoubleTapAction = PlayerDoubleTapAction.SEEK_FORWARD,
    val longPressLeft: PlayerLongPressAction = PlayerLongPressAction.FAST,
    val longPressRight: PlayerLongPressAction = PlayerLongPressAction.FAST,
    /** 长按倍速时，横滑是否继续调节倍率（0.5×–4.0×）。 */
    val longPressSpeedAdjust: Boolean = true,
    /** 左半屏亮度、右半屏音量的竖向滑动。 */
    val slideBrightnessVolume: Boolean = true,
    val seekBackwardMs: Long = 10_000L,
    val seekForwardMs: Long = 10_000L,
    /** 隐藏服务器名与播放内核信息，只保留标题和操作。 */
    val simpleMode: Boolean = false,
    /** 顶栏额外的媒体信息入口；默认关闭，保持参考界面的五个图标。 */
    val showInfoIcon: Boolean = false,
    val showDanmakuIcon: Boolean = true,
    val showLockButton: Boolean = true,
    val showRotateButton: Boolean = true,
    val showPictureInPictureButton: Boolean = true,
    val showCastButton: Boolean = true,
    val showQualityButton: Boolean = false,
    /** 跨服资源切换需要多服务器聚合数据；没有数据时自动隐藏。 */
    val showCrossServerButton: Boolean = false,
    val showDownloadButton: Boolean = false,
    /** 播放时间按秒滚动过渡，而不是直接跳变。 */
    val rollingTime: Boolean = false,
    /** 长按倍速时显示当前倍率胶囊。 */
    val showLongPressHint: Boolean = true,
    /** 播放结束自动接下一集。 */
    val continuousPlay: Boolean = true,
    /** 回到桌面/切换应用时自动进入画中画。 */
    val autoPictureInPicture: Boolean = false,
    val orientation: PlayerOrientation = PlayerOrientation.SENSOR,
    /** 字幕垂直位置（0-100，95 表示贴近底部）。 */
    val subtitleVerticalPosition: Int = 95,
    /** 字幕缩放倍率。 */
    val subtitleScale: Float = 1f,
    /** 字幕文字颜色（ARGB）。 */
    val subtitleTextColor: Long = 0xFFFFFFFFL,
    /** 字幕描边粗细；0 表示关闭描边。 */
    val subtitleOutline: Float = 0f,
) {
    companion object {
        val Default = PlayerUiSettings()
    }
}
