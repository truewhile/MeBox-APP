package app.mebox.android.core.reader

import android.content.Context
import app.mebox.android.core.model.AccountScope

/**
 * 阅读排版的本地首屏缓存。
 *
 * 服务端偏好仍按账号同步；这里按 `serverId + userId` 分区保存一份，用于
 * 打开阅读页时立即按上次的字号排版，不必等网络返回。不是认证材料。
 */
class ReaderPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(scope: AccountScope): ReaderStyle? {
        val raw = prefs.getString(key(scope), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != 6) return null
        return ReaderStyle(
            themeId = parts[0],
            night = parts[1] == "1",
            fontSize = parts[2].toFloatOrNull() ?: return null,
            lineHeight = parts[3].toFloatOrNull() ?: return null,
            paragraphSpacing = parts[4].toIntOrNull() ?: return null,
            shelfSort = parts[5],
        ).sanitized()
    }

    fun save(scope: AccountScope, style: ReaderStyle) {
        val value = style.sanitized()
        prefs.edit()
            .putString(
                key(scope),
                listOf(
                    value.themeId,
                    if (value.night) "1" else "0",
                    value.fontSize.toString(),
                    value.lineHeight.toString(),
                    value.paragraphSpacing.toString(),
                    value.shelfSort,
                ).joinToString(SEPARATOR),
            )
            .apply()
    }

    fun clear(scope: AccountScope) {
        prefs.edit().remove(key(scope)).apply()
    }

    private fun key(scope: AccountScope): String = "${scope.serverId}:${scope.userId}"

    private companion object {
        const val PREFS_NAME = "mebox_reader_prefs"
        const val SEPARATOR = "|"
    }
}
