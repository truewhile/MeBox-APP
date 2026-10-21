package app.mebox.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryDto(
    val id: String,
    val name: String = "",
    val type: String = "movie",
    @SerialName("cover_url") val coverUrl: String = "",
    /**
     * 服务端生成的媒体库封面（成员海报拼图）。
     *
     * 管理员自定义过封面时等于 `cover_url`；否则指向 `/api/libraries/{id}/cover`。
     * 客户端不再自己拿预览卡片拼封面，直接用它即可，Web / App / Emby 外观一致。
     */
    @SerialName("cover_image_url") val coverImageUrl: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val total: Int = 0,
    /** 是否参与首页海报轮播。 */
    @SerialName("carousel_enabled") val carouselEnabled: Boolean = false,
    /** `with_preview=1` 时返回的预览卡片。 */
    val cards: List<SeriesCardDto> = emptyList(),
)

/**
 * `/api/me/pinned-libraries`：当前用户置顶的媒体库 ID，顺序即置顶优先级。
 * 与网页端共用同一份用户偏好。
 */
@Serializable
data class PinnedLibrariesDto(
    @SerialName("library_ids") val libraryIds: List<String> = emptyList(),
)

/** 用户自定义的媒体库标签分组，一个媒体库同时只属于一个标签。 */
@Serializable
data class LibraryTagDto(
    val name: String = "",
    @SerialName("library_ids") val libraryIds: List<String> = emptyList(),
)

@Serializable
data class LibraryTagsDto(
    val tags: List<LibraryTagDto> = emptyList(),
)

@Serializable
data class MediaDto(
    val id: String,
    @SerialName("library_id") val libraryId: String = "",
    @SerialName("series_id") val seriesId: String = "",
    val title: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("episode_title") val episodeTitle: String = "",
    @SerialName("library_name") val libraryName: String = "",
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("poster_url") val posterUrl: String = "",
    @SerialName("backdrop_url") val backdropUrl: String = "",
    val overview: String = "",
    val rating: Float = 0f,
    val year: Int = 0,
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("season_num") val seasonNum: Int = 0,
    @SerialName("episode_num") val episodeNum: Int = 0,
    @SerialName("episode_fraction") val episodeFraction: Double = 0.0,
    val genres: String = "",
    val container: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("video_codec") val videoCodec: String = "",
    @SerialName("audio_codec") val audioCodec: String = "",
    val path: String = "",
    val versions: List<MediaDto> = emptyList(),
) {
    val displayEpisode: String
        get() = when {
            seasonNum > 0 && episodeNum > 0 -> "S${seasonNum.toString().padStart(2, '0')}E${episodeNum.toString().padStart(2, '0')}"
            episodeNum > 0 -> "第 ${episodeNum} 集"
            else -> ""
        }
}

@Serializable
data class SeriesCardDto(
    val key: String = "",
    val rep: MediaDto,
    val linkMedia: MediaDto? = null,
    val linkLibraryId: String? = null,
    val count: Int = 1,
    @SerialName("is_series") val isSeries: Boolean = false,
    @SerialName("last_added_at") val lastAddedAt: String? = null,
) {
    val libraryId: String
        get() = linkLibraryId
            ?: linkMedia?.libraryId?.takeIf { it.isNotBlank() }
            ?: rep.libraryId
}

@Serializable
data class SeriesPageDto(
    val items: List<SeriesCardDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 100,
)

@Serializable
data class MediaPageDto(
    val items: List<MediaDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 50,
)

@Serializable
data class MediaListDto(
    val items: List<MediaDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class HistoryDto(
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val completed: Boolean = false,
)

@Serializable
data class ContinueWatchingDto(
    val history: HistoryDto = HistoryDto(),
    val media: MediaDto,
)

@Serializable
data class PlaybackInfoDto(
    val media: MediaDto? = null,
    @SerialName("stream_url") val streamUrl: String = "",
    /**
     * 服务端预置换好的最终直链（115/网盘 CDN）。
     *
     * 有值时播放器可以直接连 CDN，省掉 `/api/stream` 的 302 往返——在高延迟
     * 链路上那是一次完整的 DNS+TCP+TLS。服务端无法预换链（本地文件、需要
     * 服务端加头的网盘）时不返回该字段，客户端继续用 [streamUrl]。
     */
    @SerialName("direct_url") val directUrl: String? = null,
    /** 服务端仍会返回 HLS 地址供网页/第三方客户端使用；App 忽略它，只走直链。 */
    @SerialName("hls_url") val hlsUrl: String = "",
    /**
     * 续播进度。新服务端直接在播放地址响应里带回，App 起播只需一个请求；
     * 老服务端没有该字段，客户端会退回 `/api/playback/{id}/resume`。
     */
    val resume: ResumeDto? = null,
    /**
     * 外挂字幕轨（不含内嵌字幕）。同样用于把起播路径的三个请求合并成一个；
     * 缺省时客户端退回 `/api/media/{id}/subtitles?include_embedded=false`。
     */
    val subtitles: List<SubtitleTrackDto>? = null,
)

@Serializable
data class ResumeDto(
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val completed: Boolean = false,
)

@Serializable
data class FavoriteDto(val favourite: Boolean = false)

@Serializable
data class FavoriteListDto(
    val items: List<MediaDto> = emptyList(),
)

/**
 * `/api/watch-history` 的单条记录：历史字段与媒体信息平铺在同一个对象里。
 * `media` 可能为空（条目已被删除或当前账号不可见）。
 */
@Serializable
data class WatchHistoryDto(
    val id: String = "",
    @SerialName("media_id") val mediaId: String = "",
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("watched_at") val watchedAt: String = "",
    val completed: Boolean = false,
    val media: MediaDto? = null,
)

@Serializable
data class ProgressRequest(
    @SerialName("media_id") val mediaId: String,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
)
