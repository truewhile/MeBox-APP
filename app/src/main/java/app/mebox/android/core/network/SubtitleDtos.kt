package app.mebox.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务端 `/api/media/:id/subtitles` 的字幕轨描述。
 *
 * [delivery] 决定客户端能否渲染：
 * - `webvtt` / `ass`：服务端会转成 WebVTT 供 ExoPlayer 直接加载；
 * - `burn`：图片字幕（PGS/DVD），需要位图字幕渲染，App 直接解码模式不支持，仅用于提示。
 */
@Serializable
data class SubtitleTrackDto(
    val lang: String = "",
    val label: String = "",
    val path: String = "",
    val url: String = "",
    val codec: String = "",
    val source: String = "",
    val delivery: String = "",
    @SerialName("stream_index") val streamIndex: Int = 0,
) {
    /** 图片字幕（PGS/DVD 等）：ExoPlayer 无法直接渲染，App 会忽略并提示。 */
    val burnIn: Boolean
        get() = delivery.equals("burn", ignoreCase = true)

    val renderable: Boolean
        get() = delivery.isNotBlank() && !burnIn
}

@Serializable
data class SubtitleTracksDto(
    val tracks: List<SubtitleTrackDto> = emptyList(),
)

@Serializable
data class GenreCountDto(
    val name: String = "",
    val count: Int = 0,
)

@Serializable
data class LibraryFacetsDto(
    val genres: List<GenreCountDto> = emptyList(),
    @SerialName("year_min") val yearMin: Int = 0,
    @SerialName("year_max") val yearMax: Int = 0,
) {
    val hasYearRange: Boolean
        get() = yearMin > 0 && yearMax > 0 && yearMin <= yearMax
}
