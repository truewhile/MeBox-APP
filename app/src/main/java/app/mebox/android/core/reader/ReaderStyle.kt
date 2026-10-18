package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderProfileDto
import kotlin.math.roundToInt

/**
 * 阅读器的设备侧排版状态。
 *
 * 服务端 `reader/profile` 是跨设备权威值；这里只是它在客户端的投影，外加
 * 少量设备级偏好（例如本地缓存的排序）。写回时用 [mergeInto] 保留服务端上
 * 本客户端没有暴露的字段，避免把网页设置重置成默认值。
 *
 * 书架布局 / 列数 / 排序 / 显示项与网页端共用同一份服务端字段
 * （`shelf_layout`、`shelf_grid_columns`、`shelf_sort`、`shelf_show_unread`、
 * `shelf_show_update_time`），任意一端改动都会同步到另一端。
 */
data class ReaderStyle(
    val themeId: String = "preset1",
    val night: Boolean = false,
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.8f,
    val paragraphSpacing: Int = 8,

    /** 书架布局：grid 网格 / list 列表 / compact 紧凑列表。 */
    val shelfLayout: String = ReaderShelf.DEFAULT_LAYOUT,
    /** 网格列数，0 表示按屏幕自适应。 */
    val shelfGridColumns: Int = 0,
    val shelfSort: String = "recent",
    /** 网格 / 列表卡片是否显示未读章数。 */
    val shelfShowUnread: Boolean = true,
    /** 列表布局是否显示最近更新时间。 */
    val shelfShowUpdateTime: Boolean = true,
) {
    fun sanitized(): ReaderStyle = copy(
        themeId = themeId.trim().take(32).ifBlank { "preset1" },
        fontSize = fontSize.coerceIn(FONT_MIN, FONT_MAX),
        lineHeight = lineHeight.coerceIn(LINE_MIN, LINE_MAX),
        paragraphSpacing = paragraphSpacing.coerceIn(SPACING_MIN, SPACING_MAX),
        shelfLayout = shelfLayout.takeIf { it in ReaderShelf.LAYOUT_KEYS } ?: ReaderShelf.DEFAULT_LAYOUT,
        shelfGridColumns = shelfGridColumns.coerceIn(GRID_COLUMNS_MIN, GRID_COLUMNS_MAX),
        shelfSort = shelfSort.takeIf { it in ReaderShelf.SORT_KEYS } ?: "recent",
    )

    fun mergeInto(base: ReaderProfileDto): ReaderProfileDto = base.copy(
        themeId = themeId,
        night = night,
        fontSize = fontSize.roundToInt(),
        lineHeight = (lineHeight * 10).roundToInt() / 10.0,
        paragraphSpacing = paragraphSpacing,
        shelfLayout = shelfLayout,
        shelfGridColumns = shelfGridColumns,
        shelfSort = shelfSort,
        shelfShowUnread = shelfShowUnread,
        shelfShowUpdateTime = shelfShowUpdateTime,
    )

    companion object {
        const val FONT_MIN = 14f
        const val FONT_MAX = 32f
        const val LINE_MIN = 1.4f
        const val LINE_MAX = 2.6f
        const val SPACING_MIN = 0
        const val SPACING_MAX = 32
        const val GRID_COLUMNS_MIN = 0
        const val GRID_COLUMNS_MAX = 6

        val DEFAULT = ReaderStyle()

        fun fromProfile(profile: ReaderProfileDto): ReaderStyle = ReaderStyle(
            themeId = profile.themeId,
            night = profile.night,
            fontSize = profile.fontSize.toFloat(),
            lineHeight = profile.lineHeight.toFloat(),
            paragraphSpacing = profile.paragraphSpacing,
            shelfLayout = profile.shelfLayout,
            shelfGridColumns = profile.shelfGridColumns,
            shelfSort = profile.shelfSort,
            shelfShowUnread = profile.shelfShowUnread,
            shelfShowUpdateTime = profile.shelfShowUpdateTime,
        ).sanitized()
    }
}
