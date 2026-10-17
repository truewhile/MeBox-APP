package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto

/**
 * 书架的分组筛选与排序。
 *
 * 排序键与服务端阅读偏好（`shelf_sort`）一致，便于和网页端共用同一份设置。
 */
object ReaderShelf {

    val SORT_KEYS = listOf("recent", "update", "mixed", "name", "author", "manual")

    fun sortLabel(key: String): String = when (key) {
        "recent" -> "最近阅读"
        "update" -> "最近更新"
        "mixed" -> "综合"
        "name" -> "书名"
        "author" -> "作者"
        "manual" -> "自定义"
        else -> "最近阅读"
    }

    fun visible(
        books: List<ReaderBookDto>,
        groups: List<ReaderBookGroupDto>,
        groupName: String?,
        sort: String,
    ): List<ReaderBookDto> {
        val base = if (groupName.isNullOrBlank()) {
            books
        } else {
            val group = groups.firstOrNull { it.name == groupName }
                ?: return emptyList()
            val order = group.bookIds.orEmpty()
                .withIndex()
                .associate { it.value to it.index }
            books.filter { it.id in order }.sortedBy { order[it.id] }
        }
        return when (sort) {
            "update" -> base.sortedWith(
                compareByDescending<ReaderBookDto> { it.latestChapterTime }
                    .thenByDescending { it.durChapterTime },
            )
            "mixed" -> base.sortedWith(
                compareByDescending<ReaderBookDto> {
                    maxOf(it.latestChapterTime, it.durChapterTime)
                }.thenByDescending { it.durChapterTime },
            )
            "name" -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            "author" -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.author })
            "manual" -> if (!groupName.isNullOrBlank()) {
                // 分组内顺序就是用户在网页端拖出来的顺序，自定义排序不再覆盖它。
                base
            } else {
                base.sortedWith(compareBy<ReaderBookDto> { it.order }.thenBy { it.name })
            }
            else -> base.sortedWith(
                compareByDescending<ReaderBookDto> { it.durChapterTime }
                    .thenByDescending { it.latestChapterTime },
            )
        }
    }

    /** 书架卡片上的进度文案。 */
    fun progressLabel(book: ReaderBookDto): String = when {
        book.totalChapterNum <= 0 -> "目录待更新"
        book.durChapterIndex > 0 -> "读到第 ${book.durChapterIndex + 1} 章 · 共 ${book.totalChapterNum} 章"
        book.hasProgress -> "读到第 ${book.durChapterIndex + 1} 章"
        else -> "未开始 · 共 ${book.totalChapterNum} 章"
    }
}
