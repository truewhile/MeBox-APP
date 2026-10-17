package app.mebox.android.core.media

import android.content.Context
import app.mebox.android.core.model.AccountScope

data class CachedProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/**
 * 本地播放进度缓存。
 *
 * 服务端进度仍是权威值；这里保存最近一次成功播放的位置，用于：
 * - 网络中断导致上报失败时，下次进入仍能续播；
 * - 进入播放页时先按本地位置起播，等服务端 resume 返回后再校正。
 *
 * 键包含 serverId + userId，同一台手机的不同账号不会互相覆盖。
 */
class WatchProgressStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(scope: AccountScope, mediaId: String): CachedProgress? {
        val raw = prefs.getString(key(scope, mediaId), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != 3) return null
        val position = parts[0].toLongOrNull() ?: return null
        val duration = parts[1].toLongOrNull() ?: return null
        val updatedAt = parts[2].toLongOrNull() ?: return null
        if (position <= 0) return null
        return CachedProgress(position, duration, updatedAt)
    }

    fun save(
        scope: AccountScope,
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (mediaId.isBlank() || positionMs <= 0) return
        val editor = prefs.edit()
        editor.putString(key(scope, mediaId), "$positionMs$SEPARATOR$durationMs$SEPARATOR$nowMs")
        if (prefs.all.size >= MAX_ENTRIES) {
            prune(editor)
        }
        editor.apply()
    }

    fun clear(scope: AccountScope) {
        val prefix = "${scope.serverId}:${scope.userId}:"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun prune(editor: android.content.SharedPreferences.Editor) {
        val oldest = prefs.all.entries
            .mapNotNull { (key, value) ->
                val updatedAt = (value as? String)
                    ?.split(SEPARATOR)
                    ?.getOrNull(2)
                    ?.toLongOrNull()
                if (updatedAt == null) null else key to updatedAt
            }
            .sortedBy { it.second }
            .take((MAX_ENTRIES * 0.2).toInt().coerceAtLeast(1))
        oldest.forEach { (key, _) -> editor.remove(key) }
    }

    private fun key(scope: AccountScope, mediaId: String): String =
        "${scope.serverId}:${scope.userId}:$mediaId"

    private companion object {
        const val PREFS_NAME = "mebox_watch_progress"
        const val SEPARATOR = "|"
        const val MAX_ENTRIES = 500
    }
}
