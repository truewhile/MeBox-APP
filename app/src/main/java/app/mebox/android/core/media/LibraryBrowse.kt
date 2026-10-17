package app.mebox.android.core.media

/**
 * 媒体库浏览条件。与 `/api/libraries/:id/series` 的查询参数一一对应，
 * 不直接依赖 UI 或网络类型，便于单元测试筛选摘要与参数收敛。
 */
data class LibraryFilter(
    val genres: Set<String> = emptySet(),
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val ratingMin: Float? = null,
    val unwatched: Boolean = false,
) {
    val isActive: Boolean
        get() = genres.isNotEmpty() ||
            (yearMin != null && yearMin > 0) ||
            (yearMax != null && yearMax > 0) ||
            (ratingMin != null && ratingMin > 0f) ||
            unwatched

    /** 年份区间必须成对且有序，非法时按未筛选处理。 */
    fun normalizedYears(facetsMin: Int = 0, facetsMax: Int = 0): IntRange? {
        val min = yearMin ?: facetsMin.takeIf { it > 0 }
        val max = yearMax ?: facetsMax.takeIf { it > 0 }
        if (min == null || max == null) return null
        if (min <= 0 || max <= 0 || min > max) return null
        return min..max
    }

    fun summary(limit: Int = 3): String {
        val parts = mutableListOf<String>()
        if (genres.isNotEmpty()) {
            parts += genres.take(limit).joinToString("、")
            if (genres.size > limit) parts += "等 ${genres.size} 个类型"
        }
        val min = yearMin ?: 0
        val max = yearMax ?: 0
        when {
            min > 0 && max > 0 -> parts += "$min-$max 年"
            min > 0 -> parts += "$min 年后"
            max > 0 -> parts += "$max 年前"
        }
        if (ratingMin != null && ratingMin > 0f) {
            parts += "评分 ≥ ${trimFloat(ratingMin)}"
        }
        if (unwatched) parts += "仅未看完"
        return parts.joinToString(" · ")
    }

    private fun trimFloat(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}

enum class LibrarySort(
    val field: String,
    val label: String,
    val defaultDescending: Boolean,
) {
    RECENT("release_date", "最近添加", true),
    TITLE("title", "名称", false),
    YEAR("year", "年份", true),
    RATING("rating", "评分", true),
    LAST_PLAYED("last_played", "最近播放", true),
    DURATION("duration", "时长", true),
}

data class LibrarySortSelection(
    val option: LibrarySort = LibrarySort.RECENT,
    val descending: Boolean = LibrarySort.RECENT.defaultDescending,
) {
    val order: String
        get() = if (descending) "desc" else "asc"

    fun label(): String = "${option.label} ${if (descending) "↓" else "↑"}"
}
