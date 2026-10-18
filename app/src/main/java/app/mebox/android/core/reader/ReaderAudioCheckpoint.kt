package app.mebox.android.core.reader

import android.content.Context
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 有声书最后一次播放位置。
 *
 * 服务端进度是跨端权威，但上报是异步的：切到其它应用、系统回收进程或网络
 * 暂时不可用时，最后几秒可能还没写进服务端。这里在本地按账号 + 书籍保存
 * 一份检查点，回到 App 时优先用较新的那份，避免「点播放从头开始」。
 */
@Serializable
data class ReaderAudioCheckpoint(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAtMs: Long = 0,
    val playing: Boolean = false,
)

/** 打开书籍时最终采用的章节与秒数。 */
data class ReaderAudioResumePoint(
    val chapterIndex: Int,
    val positionSec: Int,
    /** true 表示采用了比服务端更新的本地检查点。 */
    val fromLocalCheckpoint: Boolean,
)

/**
 * 本地检查点与服务端进度的合并规则。
 *
 * 只比较同一本书：
 * - 本地不存在，或本地不比服务端更新：使用服务端进度；
 * - 本地更新但章节号已经失效（目录变化 / 卷标题）：退回服务端进度；
 * - 本地更新且章节有效：使用本地的章节与秒数，包括用户主动往回听的情况。
 */
object ReaderAudioResume {

    fun choose(
        bookId: String,
        serverChapterIndex: Int,
        serverPositionSec: Int,
        serverUpdatedAtMs: Long,
        local: ReaderAudioCheckpoint?,
        chapters: List<ReaderChapterDto>,
    ): ReaderAudioResumePoint {
        val serverChapter = when {
            chapters.isEmpty() -> serverChapterIndex.coerceAtLeast(0)
            serverChapterIndex in chapters.indices && !chapters[serverChapterIndex].isVolume ->
                serverChapterIndex
            else -> chapters.indexOfFirst { !it.isVolume }.takeIf { it >= 0 } ?: 0
        }
        val server = ReaderAudioResumePoint(
            chapterIndex = serverChapter,
            positionSec = serverPositionSec.coerceAtLeast(0),
            fromLocalCheckpoint = false,
        )
        if (local == null || local.bookId != bookId) return server
        if (local.updatedAtMs <= serverUpdatedAtMs) return server
        val index = local.chapterIndex
        if (index !in chapters.indices || chapters[index].isVolume) return server
        return ReaderAudioResumePoint(
            chapterIndex = index,
            positionSec = (local.positionMs / 1_000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt(),
            fromLocalCheckpoint = true,
        )
    }
}

/** 按 `serverId + userId + bookId` 保存听书检查点，不写入任何认证材料。 */
class ReaderAudioCheckpointStore(
    context: Context,
    private val json: Json,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(scope: AccountScope, bookId: String): ReaderAudioCheckpoint? {
        val raw = prefs.getString(key(scope, bookId), null) ?: return null
        return runCatching {
            json.decodeFromString(ReaderAudioCheckpoint.serializer(), raw)
        }.getOrNull()
    }

    /**
     * [synchronous] 用于暂停、退到后台、服务销毁等「之后可能没有机会再写」的
     * 时机；周期性的 5 秒保存使用 apply()，避免频繁阻塞主线程。
     */
    fun save(scope: AccountScope, checkpoint: ReaderAudioCheckpoint, synchronous: Boolean = false) {
        val editor = prefs.edit().putString(
            key(scope, checkpoint.bookId),
            json.encodeToString(ReaderAudioCheckpoint.serializer(), checkpoint),
        )
        if (synchronous) editor.commit() else editor.apply()
    }

    fun clear(scope: AccountScope, bookId: String) {
        prefs.edit().remove(key(scope, bookId)).apply()
    }

    fun clearAccount(scope: AccountScope) {
        val prefix = "${scope.serverId}:${scope.userId}:"
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach { editor.remove(it) }
        editor.commit()
    }

    private fun key(scope: AccountScope, bookId: String): String =
        "${scope.serverId}:${scope.userId}:$bookId"

    private companion object {
        const val PREFS_NAME = "mebox_reader_audio_checkpoints"
    }
}
