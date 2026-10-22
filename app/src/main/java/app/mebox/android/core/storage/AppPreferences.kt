package app.mebox.android.core.storage

import android.content.Context
import app.mebox.android.core.player.PlayerDoubleTapAction
import app.mebox.android.core.player.PlayerLongPressAction
import app.mebox.android.core.player.PlayerOrientation
import app.mebox.android.core.player.PlayerUiSettings
import java.util.UUID

/** 非敏感的本地偏好：最后地址、账号分区与设备标识。 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        // 服务器地址、账号分区这些「下次启动要靠它恢复」的键用同步写入：
        // 电视盒子经常直接断电/被系统强杀，apply() 的异步落盘有丢数据的可能。
        set(value) {
            prefs.edit().putString(KEY_ADDRESS, value).commit()
        }

    /**
     * 上次登录成功的用户名（不保存密码）。
     *
     * 电视端冷启动时如果恢复会话失败，登录页可以直接带出用户名，
     * 用户只需要补密码或用手机扫码，不用重新输入整段信息。
     */
    var lastUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).commit()
        }

    var accountServerId: String?
        get() = prefs.getString(KEY_ACCOUNT_SERVER, null)
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT_SERVER, value).commit()
        }

    var accountUserId: String?
        get() = prefs.getString(KEY_ACCOUNT_USER, null)
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT_USER, value).commit()
        }

    var cleartextRiskAccepted: Boolean
        get() = prefs.getBoolean(KEY_CLEARTEXT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLEARTEXT, value).commit()
        }

    /**
     * 上次确认过的服务器身份（`/api/v1/server-info` 的原始 JSON）。
     *
     * 冷启动时先用它恢复界面，把「确认还是同一台实例」的校验放到后台：
     * 跨境链路上这个请求经常要 5 秒以上，而它只用于版本检查与实例识别。
     * 快照按地址隔离，地址变了就自然失效。
     */
    fun serverInfoSnapshot(address: String): String? {
        if (prefs.getString(KEY_SERVER_SNAPSHOT_ADDRESS, null) != address) return null
        return prefs.getString(KEY_SERVER_SNAPSHOT_INFO, null)
    }

    fun saveServerInfoSnapshot(address: String, infoJson: String) {
        prefs.edit()
            .putString(KEY_SERVER_SNAPSHOT_ADDRESS, address)
            .putString(KEY_SERVER_SNAPSHOT_INFO, infoJson)
            .commit()
    }

    fun clearServerInfoSnapshot() {
        prefs.edit()
            .remove(KEY_SERVER_SNAPSHOT_ADDRESS)
            .remove(KEY_SERVER_SNAPSHOT_INFO)
            .commit()
    }

    /** 播放内核的持久化键；具体取值见 PlayerEngineKind.key。 */
    var playerEngine: String?
        get() = prefs.getString(KEY_PLAYER_ENGINE, null)
        set(value) = prefs.edit().putString(KEY_PLAYER_ENGINE, value).apply()

    /** 解码方式的持久化键；具体取值见 DecoderMode.key。 */
    var decoderMode: String?
        get() = prefs.getString(KEY_DECODER_MODE, null)
        set(value) = prefs.edit().putString(KEY_DECODER_MODE, value).apply()

    /** 起播时是否自动选中一条字幕。 */
    var subtitlesAutoLoad: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_AUTO_LOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLES_AUTO_LOAD, value).apply()

    /** 字幕优先语言的持久化键；具体取值见 SubtitlePriority.key。 */
    var subtitlePriority: String?
        get() = prefs.getString(KEY_SUBTITLE_PRIORITY, null)
        set(value) = prefs.edit().putString(KEY_SUBTITLE_PRIORITY, value).apply()

    /** VR 投影方式；具体取值见 VrProjection.key。 */
    var vrProjection: String?
        get() = prefs.getString(KEY_VR_PROJECTION, null)
        set(value) = prefs.edit().putString(KEY_VR_PROJECTION, value).apply()

    /** VR 立体格式；具体取值见 VrStereo.key。 */
    var vrStereo: String?
        get() = prefs.getString(KEY_VR_STEREO, null)
        set(value) = prefs.edit().putString(KEY_VR_STEREO, value).apply()

    /** VR 显示方式；具体取值见 VrDisplayMode.key。 */
    var vrDisplay: String?
        get() = prefs.getString(KEY_VR_DISPLAY, null)
        set(value) = prefs.edit().putString(KEY_VR_DISPLAY, value).apply()

    /** 头显模式下的纸盒镜头畸变校正。 */
    var vrLensCorrection: Boolean
        get() = prefs.getBoolean(KEY_VR_LENS_CORRECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_VR_LENS_CORRECTION, value).apply()

    /**
     * 首页底部是否显示「继续阅读/收听」悬浮条。
     *
     * 默认开启：首页此前只有影视内容，书籍进度需要用户先进阅读 tab 才能看到。
     * 关闭后首页不再出现悬浮条，但阅读工具本身不受影响。
     */
    var homeReadingBar: Boolean
        get() = prefs.getBoolean(KEY_HOME_READING_BAR, true)
        set(value) = prefs.edit().putBoolean(KEY_HOME_READING_BAR, value).apply()

    /**
     * 听书时是否与其他应用同时出声。
     *
     * 默认开启：不申请音频焦点，切到微信/视频等应用时本应用不会被暂停。
     * 关闭后恢复系统的焦点抢占与通话自动暂停。
     */
    var audioMixWithOtherApps: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_MIX, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_MIX, value).apply()

    /** 播放器界面与手势偏好，字段含义见 [PlayerUiSettings]。 */
    var playerUiSettings: PlayerUiSettings
        get() = PlayerUiSettings(
            doubleTapLeft = PlayerDoubleTapAction.fromKey(prefs.getString(KEY_PLAYER_DOUBLE_TAP_LEFT, null))
                ?: PlayerUiSettings.Default.doubleTapLeft,
            doubleTapRight = PlayerDoubleTapAction.fromKey(prefs.getString(KEY_PLAYER_DOUBLE_TAP_RIGHT, null))
                ?: PlayerUiSettings.Default.doubleTapRight,
            longPressLeft = PlayerLongPressAction.fromKey(prefs.getString(KEY_PLAYER_LONG_PRESS_LEFT, null))
                ?: PlayerUiSettings.Default.longPressLeft,
            longPressRight = PlayerLongPressAction.fromKey(prefs.getString(KEY_PLAYER_LONG_PRESS_RIGHT, null))
                ?: PlayerUiSettings.Default.longPressRight,
            longPressSpeedAdjust = prefs.getBoolean(
                KEY_PLAYER_LONG_PRESS_ADJUST,
                PlayerUiSettings.Default.longPressSpeedAdjust,
            ),
            slideBrightnessVolume = prefs.getBoolean(
                KEY_PLAYER_SLIDE_LEVEL,
                PlayerUiSettings.Default.slideBrightnessVolume,
            ),
            seekBackwardMs = prefs.getLong(
                KEY_PLAYER_SEEK_BACK_MS,
                PlayerUiSettings.Default.seekBackwardMs,
            ),
            seekForwardMs = prefs.getLong(
                KEY_PLAYER_SEEK_FORWARD_MS,
                PlayerUiSettings.Default.seekForwardMs,
            ),
            simpleMode = prefs.getBoolean(KEY_PLAYER_SIMPLE_MODE, PlayerUiSettings.Default.simpleMode),
            showInfoIcon = prefs.getBoolean(KEY_PLAYER_SHOW_INFO, PlayerUiSettings.Default.showInfoIcon),
            showDanmakuIcon = prefs.getBoolean(
                KEY_PLAYER_SHOW_DANMAKU,
                PlayerUiSettings.Default.showDanmakuIcon,
            ),
            showLockButton = prefs.getBoolean(KEY_PLAYER_SHOW_LOCK, PlayerUiSettings.Default.showLockButton),
            showRotateButton = prefs.getBoolean(
                KEY_PLAYER_SHOW_ROTATE,
                PlayerUiSettings.Default.showRotateButton,
            ),
            showPictureInPictureButton = prefs.getBoolean(
                KEY_PLAYER_SHOW_PIP,
                PlayerUiSettings.Default.showPictureInPictureButton,
            ),
            showCastButton = prefs.getBoolean(KEY_PLAYER_SHOW_CAST, PlayerUiSettings.Default.showCastButton),
            showQualityButton = prefs.getBoolean(
                KEY_PLAYER_SHOW_QUALITY,
                PlayerUiSettings.Default.showQualityButton,
            ),
            showCrossServerButton = prefs.getBoolean(
                KEY_PLAYER_SHOW_CROSS_SERVER,
                PlayerUiSettings.Default.showCrossServerButton,
            ),
            showDownloadButton = prefs.getBoolean(
                KEY_PLAYER_SHOW_DOWNLOAD,
                PlayerUiSettings.Default.showDownloadButton,
            ),
            rollingTime = prefs.getBoolean(KEY_PLAYER_ROLLING_TIME, PlayerUiSettings.Default.rollingTime),
            showLongPressHint = prefs.getBoolean(
                KEY_PLAYER_SHOW_LONG_PRESS_HINT,
                PlayerUiSettings.Default.showLongPressHint,
            ),
            continuousPlay = prefs.getBoolean(
                KEY_PLAYER_CONTINUOUS_PLAY,
                PlayerUiSettings.Default.continuousPlay,
            ),
            autoPictureInPicture = prefs.getBoolean(
                KEY_PLAYER_AUTO_PIP,
                PlayerUiSettings.Default.autoPictureInPicture,
            ),
            orientation = PlayerOrientation.fromKey(
                prefs.getString(KEY_PLAYER_ORIENTATION, null),
            ),
            subtitleVerticalPosition = prefs.getInt(
                KEY_PLAYER_SUBTITLE_POSITION,
                PlayerUiSettings.Default.subtitleVerticalPosition,
            ),
            subtitleScale = prefs.getFloat(
                KEY_PLAYER_SUBTITLE_SCALE,
                PlayerUiSettings.Default.subtitleScale,
            ),
            subtitleTextColor = prefs.getLong(
                KEY_PLAYER_SUBTITLE_COLOR,
                PlayerUiSettings.Default.subtitleTextColor,
            ),
            subtitleOutline = prefs.getFloat(
                KEY_PLAYER_SUBTITLE_OUTLINE,
                PlayerUiSettings.Default.subtitleOutline,
            ),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_PLAYER_DOUBLE_TAP_LEFT, value.doubleTapLeft.key)
                .putString(KEY_PLAYER_DOUBLE_TAP_RIGHT, value.doubleTapRight.key)
                .putString(KEY_PLAYER_LONG_PRESS_LEFT, value.longPressLeft.key)
                .putString(KEY_PLAYER_LONG_PRESS_RIGHT, value.longPressRight.key)
                .putBoolean(KEY_PLAYER_LONG_PRESS_ADJUST, value.longPressSpeedAdjust)
                .putBoolean(KEY_PLAYER_SLIDE_LEVEL, value.slideBrightnessVolume)
                .putLong(KEY_PLAYER_SEEK_BACK_MS, value.seekBackwardMs)
                .putLong(KEY_PLAYER_SEEK_FORWARD_MS, value.seekForwardMs)
                .putBoolean(KEY_PLAYER_SIMPLE_MODE, value.simpleMode)
                .putBoolean(KEY_PLAYER_SHOW_INFO, value.showInfoIcon)
                .putBoolean(KEY_PLAYER_SHOW_DANMAKU, value.showDanmakuIcon)
                .putBoolean(KEY_PLAYER_SHOW_LOCK, value.showLockButton)
                .putBoolean(KEY_PLAYER_SHOW_ROTATE, value.showRotateButton)
                .putBoolean(KEY_PLAYER_SHOW_PIP, value.showPictureInPictureButton)
                .putBoolean(KEY_PLAYER_SHOW_CAST, value.showCastButton)
                .putBoolean(KEY_PLAYER_SHOW_QUALITY, value.showQualityButton)
                .putBoolean(KEY_PLAYER_SHOW_CROSS_SERVER, value.showCrossServerButton)
                .putBoolean(KEY_PLAYER_SHOW_DOWNLOAD, value.showDownloadButton)
                .putBoolean(KEY_PLAYER_ROLLING_TIME, value.rollingTime)
                .putBoolean(KEY_PLAYER_SHOW_LONG_PRESS_HINT, value.showLongPressHint)
                .putBoolean(KEY_PLAYER_CONTINUOUS_PLAY, value.continuousPlay)
                .putBoolean(KEY_PLAYER_AUTO_PIP, value.autoPictureInPicture)
                .putString(KEY_PLAYER_ORIENTATION, value.orientation.key)
                .putInt(KEY_PLAYER_SUBTITLE_POSITION, value.subtitleVerticalPosition)
                .putFloat(KEY_PLAYER_SUBTITLE_SCALE, value.subtitleScale)
                .putLong(KEY_PLAYER_SUBTITLE_COLOR, value.subtitleTextColor)
                .putFloat(KEY_PLAYER_SUBTITLE_OUTLINE, value.subtitleOutline)
                .apply()
        }

    /**
     * 设备 ID 由安装随机生成，不使用 IMEI/MAC 等硬件标识。
     */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        // 设备 ID 变了会被服务端当成新设备，登录会话也可能因此失效，
        // 所以这里同样同步落盘。
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    fun saveAccount(serverId: String, userId: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT_SERVER, serverId)
            .putString(KEY_ACCOUNT_USER, userId)
            .commit()
    }

    fun clearAccount() {
        prefs.edit()
            .remove(KEY_ACCOUNT_SERVER)
            .remove(KEY_ACCOUNT_USER)
            .commit()
    }

    private companion object {
        const val NAME = "mebox_app"
        const val KEY_ADDRESS = "last_address"
        const val KEY_USERNAME = "last_username"
        const val KEY_ACCOUNT_SERVER = "account_server_id"
        const val KEY_ACCOUNT_USER = "account_user_id"
        const val KEY_CLEARTEXT = "cleartext_risk_accepted"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PLAYER_ENGINE = "player_engine"
        const val KEY_DECODER_MODE = "decoder_mode"
        const val KEY_SUBTITLES_AUTO_LOAD = "subtitles_auto_load"
        const val KEY_SUBTITLE_PRIORITY = "subtitle_priority"
        const val KEY_VR_PROJECTION = "vr_projection"
        const val KEY_VR_STEREO = "vr_stereo"
        const val KEY_VR_DISPLAY = "vr_display"
        const val KEY_VR_LENS_CORRECTION = "vr_lens_correction"
        const val KEY_HOME_READING_BAR = "home_reading_bar"
        const val KEY_AUDIO_MIX = "reader_audio_mix_other_apps"
        const val KEY_PLAYER_DOUBLE_TAP_LEFT = "player_double_tap_left"
        const val KEY_PLAYER_DOUBLE_TAP_RIGHT = "player_double_tap_right"
        const val KEY_PLAYER_LONG_PRESS_LEFT = "player_long_press_left"
        const val KEY_PLAYER_LONG_PRESS_RIGHT = "player_long_press_right"
        const val KEY_PLAYER_LONG_PRESS_ADJUST = "player_long_press_adjust"
        const val KEY_PLAYER_SLIDE_LEVEL = "player_slide_level"
        const val KEY_PLAYER_SEEK_BACK_MS = "player_seek_back_ms"
        const val KEY_PLAYER_SEEK_FORWARD_MS = "player_seek_forward_ms"
        const val KEY_PLAYER_SIMPLE_MODE = "player_simple_mode"
        const val KEY_PLAYER_SHOW_INFO = "player_show_info"
        const val KEY_PLAYER_SHOW_DANMAKU = "player_show_danmaku"
        const val KEY_PLAYER_SHOW_LOCK = "player_show_lock"
        const val KEY_PLAYER_SHOW_ROTATE = "player_show_rotate"
        const val KEY_PLAYER_SHOW_PIP = "player_show_pip"
        const val KEY_PLAYER_SHOW_CAST = "player_show_cast"
        const val KEY_PLAYER_SHOW_QUALITY = "player_show_quality"
        const val KEY_PLAYER_SHOW_CROSS_SERVER = "player_show_cross_server"
        const val KEY_PLAYER_SHOW_DOWNLOAD = "player_show_download"
        const val KEY_PLAYER_ROLLING_TIME = "player_rolling_time"
        const val KEY_PLAYER_SHOW_LONG_PRESS_HINT = "player_show_long_press_hint"
        const val KEY_PLAYER_CONTINUOUS_PLAY = "player_continuous_play"
        const val KEY_PLAYER_AUTO_PIP = "player_auto_pip"
        const val KEY_PLAYER_ORIENTATION = "player_orientation"
        const val KEY_PLAYER_SUBTITLE_POSITION = "player_subtitle_position"
        const val KEY_PLAYER_SUBTITLE_SCALE = "player_subtitle_scale"
        const val KEY_PLAYER_SUBTITLE_COLOR = "player_subtitle_color"
        const val KEY_PLAYER_SUBTITLE_OUTLINE = "player_subtitle_outline"
        const val KEY_SERVER_SNAPSHOT_ADDRESS = "server_snapshot_address"
        const val KEY_SERVER_SNAPSHOT_INFO = "server_snapshot_info"
    }
}
