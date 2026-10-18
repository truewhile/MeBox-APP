package app.mebox.android.core.reader

import android.content.Context
import app.mebox.android.core.model.AccountScope

/**
 * 阅读偏好的本地首屏缓存。
 *
 * 服务端偏好仍按账号同步；这里按 `serverId + userId` 分区保存一份，用于
 * 打开阅读页 / 书架时立即按上次的字号和布局展示，不必等网络返回。不是认证材料。
 *
 * 缓存格式是一串 `|` 分隔的字段。旧版本只写了前 6 项，读取时缺的字段用
 * [ReaderStyle] 的默认值补齐，升级后不会因为格式变化丢掉已有设置。
 */
class ReaderPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(scope: AccountScope): ReaderStyle? {
        val raw = prefs.getString(key(scope), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != LEGACY_FIELD_COUNT && parts.size != FIELD_COUNT) return null
        return ReaderStyle(
            themeId = parts[0],
            night = parts[1] == "1",
            fontSize = parts[2].toFloatOrNull() ?: return null,
            lineHeight = parts[3].toFloatOrNull() ?: return null,
            paragraphSpacing = parts[4].toIntOrNull() ?: return null,
            shelfSort = parts[5],
            shelfLayout = parts.getOrNull(6).orEmpty().ifBlank { ReaderShelf.DEFAULT_LAYOUT },
            shelfGridColumns = parts.getOrNull(7)?.toIntOrNull() ?: 0,
            shelfShowUnread = parts.getOrNull(8)?.let { it == "1" } ?: true,
            shelfShowUpdateTime = parts.getOrNull(9)?.let { it == "1" } ?: true,
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
                    value.shelfLayout,
                    value.shelfGridColumns.toString(),
                    if (value.shelfShowUnread) "1" else "0",
                    if (value.shelfShowUpdateTime) "1" else "0",
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
        const val LEGACY_FIELD_COUNT = 6
        const val FIELD_COUNT = 10
    }
}
