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

    /** 播放内核的持久化键；具体取值见 PlayerEngineKind.key。 */
    var playerEngine: String?
        get() = prefs.getString(KEY_PLAYER_ENGINE, null)
        set(value) = prefs.edit().putString(KEY_PLAYER_ENGINE, value).apply()

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
    }
}
