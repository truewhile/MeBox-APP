package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderBookDto

/**
 * 首页「继续阅读/收听」悬浮条的数据选择。
 *
 * 书架里同时有文本、漫画和有声书：优先展示正在播放的有声书，否则取最近一次
 * 有阅读进度（`durChapterTime` 最大）的书。没有任何进度的书不会出现在条上，
 * 避免首页出现一条「未开始」的入口。
 */
object ReaderContinue {

    fun pick(
        books: List<ReaderBookDto>,
        activeAudioBookId: String?,
        audioActive: Boolean,
    ): ReaderBookDto? {
        if (books.isEmpty()) return null
        val started = books.filter { it.durChapterTime > 0 || it.id == activeAudioBookId }
        if (started.isEmpty()) return null
        val activeBook = activeAudioBookId
            ?.let { id -> started.firstOrNull { it.id == id } }
        // 正在播放/缓冲的有声书就是用户此刻的上下文，即使它的进度时间戳
        // 还没追上刚读过的文本。
        if (audioActive && activeBook != null) return activeBook
        return started.maxByOrNull { it.durChapterTime } ?: activeBook
    }

    /** 悬浮条副标题里的章节定位，与服务端 `durChapterIndex` 的 0 基序号对齐。 */
    fun chapterLabel(book: ReaderBookDto): String =
        chapterLabel(book.durChapterIndex, book.durChapterTitle)

    fun chapterLabel(chapterIndex: Int, chapterTitle: String?): String {
        val index = chapterIndex.coerceAtLeast(0) + 1
        val title = chapterTitle.orEmpty().trim()
        return if (title.isBlank()) "第 $index 章" else "第 $index 章 · $title"
    }
}
