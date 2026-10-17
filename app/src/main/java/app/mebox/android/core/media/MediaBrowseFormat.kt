package app.mebox.android.core.media

import app.mebox.android.core.network.MediaDto

/**
 * 影视浏览页用到的纯文本/分组逻辑。
 *
 * 放在 core 层是为了能被单元测试直接覆盖：这些字符串会出现在首页、媒体库
 * 和详情页的多个位置，格式不一致时很难靠界面回归发现。
 */
object MediaBrowseFormat {

    /** 详情页元数据行：年份 · 时长 · 分辨率 · 集数。 */
    fun detailMeta(media: MediaDto): String = listOfNotNull(
        media.year.takeIf { it > 0 }?.toString(),
        media.durationSec.takeIf { it > 0 }?.let { duration(it) },
        media.height.takeIf { it > 0 }?.let { "${it}p" },
        media.displayEpisode.ifBlank { null },
    ).joinToString(" · ")

    /** 卡片副标题：年份 · 集数。 */
    fun seriesSubtitle(media: MediaDto): String = listOfNotNull(
        media.year.takeIf { it > 0 }?.toString(),
        media.displayEpisode.ifBlank { null },
    ).joinToString(" · ")

    /** 剧集的季号列表，按升序去重；没有季信息时返回空列表。 */
    fun seasons(episodes: List<MediaDto>): List<Int> =
        episodes.asSequence()
            .map { it.seasonNum }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()

    /** 继续观看卡的副标题：集数 + 剩余时长。 */
    fun continueSubtitle(episodeLabel: String, positionMs: Long, durationMs: Long): String {
        val remaining = if (durationMs > 0) {
            "剩余 ${remaining(durationMs - positionMs)}"
        } else {
            ""
        }
        return listOfNotNull(episodeLabel.ifBlank { null }, remaining.ifBlank { null })
            .joinToString(" · ")
            .ifBlank { "继续观看" }
    }

    /** 剩余时长文案；不足 1 分钟时给出「不到 1 分钟」而不是 0。 */
    fun remaining(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
            minutes > 0 -> "$minutes 分钟"
            else -> "不到 1 分钟"
        }
    }

    /** 时长文案：超过 1 小时带小时位。 */
    fun duration(seconds: Int): String {
        val totalMinutes = seconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
    }
}
