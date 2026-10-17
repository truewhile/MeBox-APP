package app.mebox.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/api/danmaku/:id` 的响应。
 *
 * 服务端已经完成「识别视频 → 匹配弹幕库 → 拉取评论」三步，只把原始评论
 * （Bilibili XML 或弹弹play JSON）交给客户端解析，避免在服务端做与渲染
 * 无关的解析。渲染参数（透明度、字号、显示区域）来自当前用户的偏好。
 */
@Serializable
data class DanmakuFetchDto(
    val enabled: Boolean = true,
    @SerialName("source_type") val sourceType: String = "auto",
    val opacity: String = "1",
    @SerialName("font_size") val fontSize: String = "24",
    val area: String = "1",
    val raw: String = "",
    @SerialName("merged_sources") val mergedSources: Int = 0,
    val candidates: List<DanmakuAnimeDto> = emptyList(),
    val alternatives: List<DanmakuAnimeDto> = emptyList(),
    @SerialName("anime_title") val animeTitle: String = "",
    @SerialName("episode_title") val episodeTitle: String = "",
    @SerialName("episode_id") val episodeId: Long = 0,
    @SerialName("match_mode") val matchMode: String = "",
)

/** 弹幕搜索结果中的一个番剧及其可选的单集弹幕库。 */
@Serializable
data class DanmakuAnimeDto(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val episodes: List<DanmakuEpisodeDto> = emptyList(),
)

@Serializable
data class DanmakuEpisodeDto(
    val episodeId: Long = 0,
    val episodeTitle: String = "",
)

/** `/api/danmaku/config` 与 `/api/danmaku/settings` 的用户偏好。 */
@Serializable
data class DanmakuConfigDto(
    val enabled: Boolean = true,
    val opacity: String = "1",
    @SerialName("font_size") val fontSize: String = "24",
    val area: String = "1",
    @SerialName("merge_sources") val mergeSources: Boolean = false,
    @SerialName("app_key_configured") val appKeyConfigured: Boolean = false,
)

/**
 * `/api/danmaku/settings` 的部分更新。留空的字段服务端保持不变；
 * `explicitNulls = false` 保证 null 不会写入请求体。
 */
@Serializable
data class DanmakuSettingsPatchDto(
    val enabled: Boolean? = null,
    val opacity: Float? = null,
    @SerialName("font_size") val fontSize: Int? = null,
    val area: Float? = null,
)
