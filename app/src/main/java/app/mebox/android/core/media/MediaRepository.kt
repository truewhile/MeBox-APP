package app.mebox.android.core.media

import app.mebox.android.core.auth.AuthCoordinator
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ContinueWatchingDto
import app.mebox.android.core.network.DanmakuConfigDto
import app.mebox.android.core.network.DanmakuFetchDto
import app.mebox.android.core.network.DanmakuSettingsPatchDto
import app.mebox.android.core.network.FavoriteDto
import app.mebox.android.core.network.FavoriteListDto
import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.LibraryFacetsDto
import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.MediaListDto
import app.mebox.android.core.network.MediaPageDto
import app.mebox.android.core.network.MeBoxApiClient
import app.mebox.android.core.network.PlaybackInfoDto
import app.mebox.android.core.network.ResumeDto
import app.mebox.android.core.network.SeriesCardDto
import app.mebox.android.core.network.SeriesPageDto
import app.mebox.android.core.network.SubtitleTracksDto
import app.mebox.android.core.network.WatchHistoryDto

/**
 * 影视数据访问层。所有请求都通过 AuthCoordinator，复用单次刷新和会话
 * 撤销逻辑；页面不直接接触令牌。
 */
class MediaRepository(
    private val api: MeBoxApiClient,
    private val coordinator: AuthCoordinator,
    private val scope: AccountScope,
) {
    suspend fun libraries(
        ids: List<String>? = null,
        withPreview: Boolean = false,
        previewLimit: Int? = null,
    ): ApiResult<List<LibraryDto>> = authorized {
        api.libraries(
            accessToken = it,
            ids = ids?.takeIf { list -> list.isNotEmpty() }?.joinToString(","),
            withPreview = withPreview,
            previewLimit = previewLimit,
        )
    }

    suspend fun librarySeries(
        libraryId: String,
        page: Int,
        pageSize: Int = 100,
        filter: LibraryFilter = LibraryFilter(),
        sort: LibrarySortSelection = LibrarySortSelection(),
    ): ApiResult<SeriesPageDto> = authorized {
        api.librarySeries(
            accessToken = it,
            libraryId = libraryId,
            page = page,
            pageSize = pageSize,
            genres = filter.genres.toList().sorted(),
            yearMin = filter.yearMin?.takeIf { value -> value > 0 },
            yearMax = filter.yearMax?.takeIf { value -> value > 0 },
            ratingMin = filter.ratingMin?.takeIf { value -> value > 0f },
            unwatched = filter.unwatched,
            sort = sort.option.field,
            order = sort.order,
        )
    }

    suspend fun libraryFacets(libraryId: String): ApiResult<LibraryFacetsDto> =
        authorized { api.libraryFacets(it, libraryId) }

    suspend fun randomMedia(
        libraryId: String,
        filter: LibraryFilter = LibraryFilter(),
    ): ApiResult<MediaDto> = authorized {
        api.randomMedia(
            accessToken = it,
            libraryId = libraryId,
            genres = filter.genres.toList().sorted(),
            yearMin = filter.yearMin?.takeIf { value -> value > 0 },
            yearMax = filter.yearMax?.takeIf { value -> value > 0 },
            ratingMin = filter.ratingMin?.takeIf { value -> value > 0f },
            unwatched = filter.unwatched,
        )
    }

    suspend fun subtitles(mediaId: String, includeEmbedded: Boolean = true): ApiResult<SubtitleTracksDto> =
        authorized { api.subtitles(it, mediaId, includeEmbedded) }

    /**
     * 拉取弹幕。服务端按视频名/哈希匹配弹幕库；[keyword] 可覆盖搜索词，
     * [episodeId] 用于用户从多个候选里手动选定弹幕库。
     */
    suspend fun danmaku(
        mediaId: String,
        keyword: String? = null,
        episodeId: Long? = null,
    ): ApiResult<DanmakuFetchDto> =
        authorized { api.danmaku(it, mediaId, keyword, episodeId) }

    suspend fun updateDanmakuSettings(
        patch: DanmakuSettingsPatchDto,
    ): ApiResult<DanmakuConfigDto> =
        authorized { api.updateDanmakuSettings(it, patch) }

    suspend fun seriesEpisodes(libraryId: String, key: String): ApiResult<MediaListDto> =
        authorized { api.seriesEpisodes(it, libraryId, key) }

    suspend fun mediaDetail(mediaId: String): ApiResult<MediaDto> =
        authorized { api.mediaDetail(it, mediaId) }

    suspend fun mediaEpisodes(mediaId: String): ApiResult<MediaListDto> =
        authorized { api.mediaEpisodes(it, mediaId) }

    suspend fun search(query: String, page: Int = 1, pageSize: Int = 50): ApiResult<MediaPageDto> =
        authorized { api.searchMedia(it, query, page, pageSize) }

    suspend fun recent(limit: Int = 12): ApiResult<List<SeriesCardDto>> =
        authorized { api.recentMedia(it, limit) }

    suspend fun continueWatching(limit: Int = 10): ApiResult<List<ContinueWatchingDto>> =
        authorized { api.continueWatching(it, limit) }

    suspend fun playbackInfo(mediaId: String): ApiResult<PlaybackInfoDto> =
        authorized { api.playbackInfo(it, mediaId) }

    suspend fun resume(mediaId: String): ApiResult<ResumeDto> =
        authorized { api.playbackResume(it, mediaId) }

    suspend fun reportProgress(mediaId: String, positionMs: Long, durationMs: Long): ApiResult<Unit> =
        authorized { api.reportProgress(it, mediaId, positionMs, durationMs) }

    suspend fun favorites(): ApiResult<FavoriteListDto> =
        authorized { api.favorites(it) }

    suspend fun watchHistory(limit: Int = 200): ApiResult<List<WatchHistoryDto>> =
        authorized { api.watchHistory(it, limit) }

    suspend fun clearWatchHistory(mediaId: String? = null): ApiResult<Unit> =
        authorized { api.clearWatchHistory(it, mediaId) }

    suspend fun favoriteStatus(mediaId: String): ApiResult<FavoriteDto> =
        authorized { api.favoriteStatus(it, mediaId) }

    suspend fun setFavorite(mediaId: String, favorite: Boolean): ApiResult<FavoriteDto> =
        authorized { api.setFavorite(it, mediaId, favorite) }

    private suspend fun <T> authorized(block: suspend (String) -> ApiResult<T>): ApiResult<T> =
        coordinator.authorized(scope, block)
}
