package app.mebox.android.core.media

import app.mebox.android.core.network.MediaDto

/**
 * 影视浏览页用到的纯文本/分组逻辑。
 *
 * 放在 core 层是为了能被单元测试直接覆盖：这些字符串会出现在首页、媒体库
 * 和详情页的多个位置，格式不一致时很难靠界面回归发现。
 *
 * 分季规则与网页端 `web/src/utils/groupSeries.ts` 保持一致：正片按
 * `season_num` 归组（0 视为特别篇），OVA / OAD / 剧场版等特殊分区按路径
 * 目录名识别，并使用负数季号占位，独立成一个分季标签。
 */
object MediaBrowseFormat {

    /** 特殊分区使用的季号，与网页端 `groupSeries.ts` 的常量一一对应。 */
    const val THEATRICAL_SEASON = -1
    const val OVA_SEASON = -2
    const val OAD_SEASON = -3
    const val OVD_SEASON = -4
    const val ONA_SEASON = -5
    const val EXTRA_SEASON = -6
    const val BONUS_SEASON = -7
    const val OMAKE_SEASON = -8
    const val PICTURE_DRAMA_SEASON = -9
    const val NCOP_SEASON = -10
    const val NCED_SEASON = -11

    /** 详情页的一个分季：季号（含负数特殊分区）+ 该季的剧集。 */
    data class SeasonGroup(val season: Int, val episodes: List<MediaDto>)

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

    /**
     * 按季归组，正片季在前、特殊分区按固定顺序排在后面。
     *
     * 与网页端一致：先按路径识别剧场版/OVA 等特殊分区，其余按
     * `season_num` 归组（有集号时 0 表示特别篇）；组内按集号升序。
     */
    fun seasonGroups(episodes: List<MediaDto>): List<SeasonGroup> {
        if (episodes.isEmpty()) return emptyList()
        return episodes.groupBy(::seasonOf)
            .map { (season, list) ->
                SeasonGroup(season, list.sortedBy(::episodeOrderValue))
            }
            .sortedBy { seasonSortOrder(it.season) }
    }

    /** 剧集的季号列表，按网页端的展示顺序排序；没有剧集时返回空列表。 */
    fun seasons(episodes: List<MediaDto>): List<Int> =
        seasonGroups(episodes).map { it.season }

    /**
     * 详情页进入时默认展开的分季。
     *
     * 多季并存时优先选最小的正片季（第一季、第二季……），而不是代表条目
     * 自身的季号：剧集卡片的代表条目可能来自 OVA、特别篇或剧场版目录，
     * 直接沿用它会让详情页一进来就停在特殊分区。没有正片季时才退回分组
     * 列表的第一项，保证只有 OVA/特别篇的条目也有内容可看。
     */
    fun defaultSeason(episodes: List<MediaDto>): Int? = defaultSeasonOf(seasonGroups(episodes))

    /** [defaultSeason] 的重载：详情页已经算好分季时直接复用，避免重复分组。 */
    fun defaultSeasonOf(groups: List<SeasonGroup>): Int? =
        groups.firstOrNull { it.season > 0 }?.season
            ?: groups.firstOrNull()?.season

    /** 单集所属季：优先按路径识别特殊分区，否则回落到 `season_num`。 */
    fun seasonOf(media: MediaDto): Int {
        specialSeason(media)?.let { return it }
        // 与网页端 `episode_num > 0 ? season_num ?? 0 : season_num || 1` 对齐：
        // 有集号时 0 是「特别篇」；没有集号时 0/缺省视为第一季。
        return if (media.episodeNum > 0) {
            media.seasonNum
        } else {
            media.seasonNum.takeIf { it != 0 } ?: 1
        }
    }

    /**
     * 分季标签：第一季、第二季 … → 特别篇 → 剧场版 → OVA → OAD → 其余特典。
     * 文案与网页端 `seasonLabel()` 完全一致。
     */
    fun seasonLabel(season: Int): String = when (season) {
        0 -> "特别篇"
        THEATRICAL_SEASON -> "剧场版"
        OVA_SEASON -> "OVA"
        OAD_SEASON -> "OAD"
        OVD_SEASON -> "OVD"
        ONA_SEASON -> "ONA"
        EXTRA_SEASON -> "Extra"
        BONUS_SEASON -> "Bonus"
        OMAKE_SEASON -> "Omake"
        PICTURE_DRAMA_SEASON -> "Picture Drama"
        NCOP_SEASON -> "NCOP"
        NCED_SEASON -> "NCED"
        else -> "第 $season 季"
    }

    /** 分季排序权重，与网页端 `seasonSortOrder()` 一致。 */
    fun seasonSortOrder(season: Int): Int = when {
        season > 0 -> season
        season == 0 -> 1000 // 特别篇紧随正片
        season == THEATRICAL_SEASON -> 1001
        season == OVA_SEASON -> 1002
        season == OAD_SEASON -> 1003
        season == OVD_SEASON -> 1004
        season == ONA_SEASON -> 1005
        season == EXTRA_SEASON -> 1006
        season == BONUS_SEASON -> 1007
        season == OMAKE_SEASON -> 1008
        season == PICTURE_DRAMA_SEASON -> 1009
        season == NCOP_SEASON -> 1010
        season == NCED_SEASON -> 1011
        else -> 2000 // 未知季兜底
    }

    /** 路径目录里能识别的特殊分区；普通剧集返回 null。 */
    fun specialSeason(media: MediaDto): Int? {
        if (isTheatricalFeature(media)) return THEATRICAL_SEASON
        val path = media.path
        if (path.isBlank()) return null
        for (part in path.split('/', '\\')) {
            if (part.isBlank()) continue
            for ((season, pattern) in specialSectionPatterns) {
                if (pattern.containsMatchIn(part)) return season
            }
        }
        return null
    }

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

    /** 含小数的集号（S01E11.5 → 11.5），用于组内排序，与网页端一致。 */
    private fun episodeOrderValue(media: MediaDto): Double {
        val fraction = media.episodeFraction
        return if (fraction > 0.0 && fraction < 1.0) {
            media.episodeNum + fraction
        } else {
            media.episodeNum.toDouble()
        }
    }

    /** 剧场版：标题命中「剧场版 / 电影版 / The Movie」，或路径里有这类目录。 */
    private fun isTheatricalFeature(media: MediaDto): Boolean {
        val path = media.path
        if (seriesFileEpisodeRegex.containsMatchIn(path)) return false
        return theatricalTitleRegex.containsMatchIn("${media.title} $path") ||
            theatricalFolderRegex.containsMatchIn(path)
    }

    private val seriesFileEpisodeRegex = Regex(
        "(?:^|[\\s._-])(?:s\\d{1,2}\\s*e\\d{1,3}|season\\s*\\d{1,2}\\s*(?:episode|ep)\\s*\\d{1,3}" +
            "|\\d{1,2}x\\d{1,3}|e(?:p(?:isode)?)?\\s*\\d{1,3})(?:\\s|[._-]|$)",
        RegexOption.IGNORE_CASE,
    )

    private val theatricalTitleRegex = Regex(
        "(?:剧场版|劇場版|动画电影|動畫電影|电影版|電影版|\\bthe\\s+movie\\b|\\bmovie\\s*\\d{1,2}\\b)",
        RegexOption.IGNORE_CASE,
    )

    private val theatricalFolderRegex = Regex(
        "[\\\\/][^\\\\/]*(?:剧场版|劇場版|动画电影|動畫電影|电影版|電影版)[^\\\\/]*[\\\\/]",
        RegexOption.IGNORE_CASE,
    )

    /** 与网页端 `SPECIAL_SECTION_PATTERNS` 同序：靠前的分区优先命中。 */
    private val specialSectionPatterns: List<Pair<Int, Regex>> = listOf(
        OVA_SEASON to Regex("(?:^|[^a-z0-9])ovas?(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        OAD_SEASON to Regex("(?:^|[^a-z0-9])oads?(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        OVD_SEASON to Regex("(?:^|[^a-z0-9])ovds?(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        ONA_SEASON to Regex("(?:^|[^a-z0-9])onas?(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        PICTURE_DRAMA_SEASON to Regex(
            "(?:^|[^a-z0-9])(?:picture[\\s._-]*drama|画像特典)(?:[^a-z0-9]|$)",
            RegexOption.IGNORE_CASE,
        ),
        NCOP_SEASON to Regex("(?:^|[^a-z0-9])ncop(?:\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        NCED_SEASON to Regex("(?:^|[^a-z0-9])nced(?:\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        EXTRA_SEASON to Regex("(?:^|[^a-z0-9])extras?(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        BONUS_SEASON to Regex(
            "(?:^|[^a-z0-9])bonus(?:es)?(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)",
            RegexOption.IGNORE_CASE,
        ),
        OMAKE_SEASON to Regex("(?:^|[^a-z0-9])omake(?:[\\s._-]*\\d+)?(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE),
    )
}
