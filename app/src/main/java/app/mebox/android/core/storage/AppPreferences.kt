package app.mebox.android.core.storage

import android.content.Context
import java.util.UUID

/** 非敏感的本地偏好：最后地址、账号分区与设备标识。 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    var accountServerId: String?
        get() = prefs.getString(KEY_ACCOUNT_SERVER, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_SERVER, value).apply()

    var accountUserId: String?
        get() = prefs.getString(KEY_ACCOUNT_USER, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_USER, value).apply()

    var cleartextRiskAccepted: Boolean
        get() = prefs.getBoolean(KEY_CLEARTEXT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEARTEXT, value).apply()

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
            .apply()
    }

    fun clearServerInfoSnapshot() {
        prefs.edit()
            .remove(KEY_SERVER_SNAPSHOT_ADDRESS)
            .remove(KEY_SERVER_SNAPSHOT_INFO)
            .apply()
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

    /**
     * 设备 ID 由安装随机生成，不使用 IMEI/MAC 等硬件标识。
     */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun saveAccount(serverId: String, userId: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT_SERVER, serverId)
            .putString(KEY_ACCOUNT_USER, userId)
            .apply()
    }

    fun clearAccount() {
        prefs.edit()
            .remove(KEY_ACCOUNT_SERVER)
            .remove(KEY_ACCOUNT_USER)
            .apply()
    }

    private companion object {
        const val NAME = "mebox_app"
        const val KEY_ADDRESS = "last_address"
        const val KEY_ACCOUNT_SERVER = "account_server_id"
        const val KEY_ACCOUNT_USER = "account_user_id"
        const val KEY_CLEARTEXT = "cleartext_risk_accepted"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PLAYER_ENGINE = "player_engine"
        const val KEY_DECODER_MODE = "decoder_mode"
        const val KEY_SUBTITLES_AUTO_LOAD = "subtitles_auto_load"
        const val KEY_SUBTITLE_PRIORITY = "subtitle_priority"
        const val KEY_SERVER_SNAPSHOT_ADDRESS = "server_snapshot_address"
        const val KEY_SERVER_SNAPSHOT_INFO = "server_snapshot_info"
    }
}
