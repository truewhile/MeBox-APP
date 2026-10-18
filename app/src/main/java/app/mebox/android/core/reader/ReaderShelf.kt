package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto
import java.util.Calendar

/**
 * 书架的分组筛选、排序与展示计算。
 *
 * 布局、排序键和显示项与服务端阅读偏好一致（`shelf_layout` / `shelf_sort` 等），
 * 便于和网页端共用同一份设置。
 */
object ReaderShelf {

    const val LAYOUT_GRID = "grid"
    const val LAYOUT_LIST = "list"
    const val LAYOUT_COMPACT = "compact"

    const val DEFAULT_LAYOUT = LAYOUT_GRID

    val LAYOUT_KEYS = listOf(LAYOUT_GRID, LAYOUT_LIST, LAYOUT_COMPACT)

    /** 网格列数选项，0 表示自适应（与网页端 `SHELF_GRID_COLUMNS` 一致）。 */
    val GRID_COLUMNS = listOf(0, 2, 3, 4, 5, 6)

    val SORT_KEYS = listOf("recent", "update", "mixed", "name", "author", "manual")

    fun layoutLabel(key: String): String = when (key) {
        LAYOUT_LIST -> "列表"
        LAYOUT_COMPACT -> "紧凑列表"
        else -> "网格"
    }

    fun sortLabel(key: String): String = when (key) {
        "recent" -> "最近阅读"
        "update" -> "最近更新"
        "mixed" -> "综合"
        "name" -> "按书名"
        "author" -> "按作者"
        "manual" -> "手动顺序"
        else -> "最近阅读"
    }

    /** 网格列数的展示文案：0 表示自适应。 */
    fun gridColumnsLabel(columns: Int): String =
        if (columns <= 0) "自适应" else "$columns 列"

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

    /**
     * 未读章数，语义与网页端 `bookshelfModel.unreadChapters` 一致。
     * 目录尚未缓存（总章数为 0）时返回 null，界面不显示徽标。
     */
    fun unreadChapters(book: ReaderBookDto): Int? {
        if (book.totalChapterNum <= 0) return null
        // durChapterTime 为 0 表示还没开始读，否则读到 durChapterIndex 为止。
        val read = if (book.durChapterTime > 0) book.durChapterIndex + 1 else 0
        return (book.totalChapterNum - read).coerceIn(0, book.totalChapterNum)
    }

    /** 书架卡片上的进度文案。 */
    fun progressLabel(book: ReaderBookDto): String = when {
        book.totalChapterNum <= 0 -> "目录待更新"
        book.durChapterIndex > 0 -> "读到第 ${book.durChapterIndex + 1} 章 · 共 ${book.totalChapterNum} 章"
        book.hasProgress -> "读到第 ${book.durChapterIndex + 1} 章"
        else -> "未开始 · 共 ${book.totalChapterNum} 章"
    }

    /** 最近读到的章节名；未开始时为空串（对齐网页端 `readProgressText`）。 */
    fun readChapterTitle(book: ReaderBookDto): String =
        if (book.durChapterTime > 0) book.durChapterTitle else ""

    /** 最近更新时间（毫秒）；没有检测到更新时为 0。 */
    fun updateTime(book: ReaderBookDto): Long =
        book.latestChapterTime.takeIf { it > 0 } ?: 0

    /** 绝对时间：当天只显示时分，同年显示月日，跨年带年份。 */
    fun formatBookTime(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return ""
        val date = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val pad = { value: Int -> value.toString().padStart(2, '0') }
        val sameDay = date.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        if (sameDay) {
            return "${pad(date.get(Calendar.HOUR_OF_DAY))}:${pad(date.get(Calendar.MINUTE))}"
        }
        if (date.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            return "${date.get(Calendar.MONTH) + 1}月${date.get(Calendar.DAY_OF_MONTH)}日"
        }
        return "${date.get(Calendar.YEAR)}-" +
            "${pad(date.get(Calendar.MONTH) + 1)}-${pad(date.get(Calendar.DAY_OF_MONTH))}"
    }

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前，超过 30 天退回日期。 */
    fun formatRelativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return ""
        val diff = now - ms
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000} 小时前"
            diff < 30L * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> formatBookTime(ms, now)
        }
    }
}
