package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** 文本页码/漫画图片序号检查点；事件 ID 防止迟到回执确认了后来的位置。 */
@Serializable
data class ReaderProgress(
    val bookId: String,
    val chapterIndex: Int,
    val position: Int,
    val chapterTitle: String = "",
    val chapterUrl: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
    val pending: Boolean = true,
    val eventId: String = UUID.randomUUID().toString(),
)

/** 文件均在应用私有目录；调用方在 IO 调度器执行磁盘操作。 */
class ReaderProgressStore(private val root: File, private val json: Json) {
    private val generations = mutableMapOf<AccountScope, Long>()

    @Synchronized
    fun generation(scope: AccountScope): Long = generations[scope] ?: 0L

    @Synchronized
    fun load(scope: AccountScope, bookId: String): ReaderProgress? = read(file(scope, bookId))

    @Synchronized
    fun save(scope: AccountScope, progress: ReaderProgress, generation: Long = generation(scope)) {
        if (generation != generation(scope)) return
        require(progress.bookId.isNotBlank() && progress.chapterIndex >= 0 && progress.position >= 0)
        val existing = load(scope, progress.bookId)
        if (existing != null && existing.updatedAtMs >= progress.updatedAtMs) return
        write(file(scope, progress.bookId), progress)
    }

    @Synchronized
    fun pending(scope: AccountScope): List<ReaderProgress> = accountDir(scope).listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull(::read).filter { it.pending }
        .sortedBy { it.updatedAtMs }

    @Synchronized
    fun acknowledge(scope: AccountScope, sent: ReaderProgress) {
        val current = load(scope, sent.bookId) ?: return
        if (current.eventId == sent.eventId) write(file(scope, sent.bookId), current.copy(pending = false))
    }

    @Synchronized
    fun discard(scope: AccountScope, event: ReaderProgress) {
        if (load(scope, event.bookId)?.eventId == event.eventId) file(scope, event.bookId).delete()
    }

    @Synchronized
    fun clear(scope: AccountScope, bookId: String) { file(scope, bookId).delete() }

    @Synchronized
    fun clearAccount(scope: AccountScope) {
        generations[scope] = generation(scope) + 1L
        accountDir(scope).deleteRecursively()
    }

    private fun read(file: File): ReaderProgress? = runCatching {
        json.decodeFromString(ReaderProgress.serializer(), file.readText())
    }.getOrNull()

    private fun write(target: File, progress: ReaderProgress) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temp = File.createTempFile("progress-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use {
                it.write(json.encodeToString(ReaderProgress.serializer(), progress).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }

    private fun accountDir(scope: AccountScope) = File(File(root, hash(scope.serverId)), hash(scope.userId))
    private fun file(scope: AccountScope, bookId: String) = File(accountDir(scope), "${hash(bookId)}.json")
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object ReaderProgressResume {
    fun merge(
        book: ReaderBookDto,
        local: ReaderProgress?,
        chapters: List<ReaderChapterDto>? = null,
    ): ReaderBookDto {
        if (book.isAudio || local == null || local.bookId != book.id ||
            local.updatedAtMs <= book.durChapterTime
        ) return book
        val index = if (chapters == null) local.chapterIndex else {
            if (local.chapterUrl.isNotBlank()) {
                chapters.indexOfFirst { !it.isVolume && it.url == local.chapterUrl }
                    .takeIf { it >= 0 } ?: return book
            } else local.chapterIndex.takeIf { it in chapters.indices && !chapters[it].isVolume } ?: return book
        }
        return book.copy(
            durChapterIndex = index, durChapterPos = local.position,
            durChapterTitle = local.chapterTitle, durChapterTime = local.updatedAtMs,
        )
    }
}
