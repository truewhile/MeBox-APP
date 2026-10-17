package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderProfileDto
import kotlin.math.roundToInt

/**
 * 阅读器的设备侧排版状态。
 *
 * 服务端 `reader/profile` 是跨设备权威值；这里只是它在客户端的投影，外加
 * 少量设备级偏好（例如本地缓存的排序）。写回时用 [mergeInto] 保留服务端上
 * 本客户端没有暴露的字段，避免把网页设置重置成默认值。
 */
data class ReaderStyle(
    val themeId: String = "preset1",
    val night: Boolean = false,
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.8f,
    val paragraphSpacing: Int = 8,
    val shelfSort: String = "recent",
) {
    fun sanitized(): ReaderStyle = copy(
        themeId = themeId.trim().take(32).ifBlank { "preset1" },
        fontSize = fontSize.coerceIn(FONT_MIN, FONT_MAX),
        lineHeight = lineHeight.coerceIn(LINE_MIN, LINE_MAX),
        paragraphSpacing = paragraphSpacing.coerceIn(SPACING_MIN, SPACING_MAX),
        shelfSort = shelfSort.takeIf { it in ReaderShelf.SORT_KEYS } ?: "recent",
    )

    fun mergeInto(base: ReaderProfileDto): ReaderProfileDto = base.copy(
        themeId = themeId,
        night = night,
        fontSize = fontSize.roundToInt(),
        lineHeight = (lineHeight * 10).roundToInt() / 10.0,
        paragraphSpacing = paragraphSpacing,
        shelfSort = shelfSort,
    )

    companion object {
        const val FONT_MIN = 14f
        const val FONT_MAX = 32f
        const val LINE_MIN = 1.4f
        const val LINE_MAX = 2.6f
        const val SPACING_MIN = 0
        const val SPACING_MAX = 32

        val DEFAULT = ReaderStyle()

        fun fromProfile(profile: ReaderProfileDto): ReaderStyle = ReaderStyle(
            themeId = profile.themeId,
            night = profile.night,
            fontSize = profile.fontSize.toFloat(),
            lineHeight = profile.lineHeight.toFloat(),
            paragraphSpacing = profile.paragraphSpacing,
            shelfSort = profile.shelfSort,
        ).sanitized()
    }
}
