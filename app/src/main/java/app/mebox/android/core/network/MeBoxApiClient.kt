package app.mebox.android.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/**
 * Retrofit 调用的薄封装：把 HTTP 状态码、错误契约和网络异常统一成
 * [ApiResult]，页面与协调器不需要理解 Retrofit 类型。
 */
class MeBoxApiClient(
    private val api: MobileApi,
    private val json: Json,
) {
    suspend fun serverInfo(): ApiResult<ServerInfo> = call { api.serverInfo() }

    suspend fun login(request: LoginRequest): ApiResult<AuthResponse> = call { api.login(request) }

    suspend fun refresh(refreshToken: String): ApiResult<AuthResponse> =
        call { api.refresh(RefreshRequest(refreshToken)) }

    suspend fun session(accessToken: String): ApiResult<SessionResponse> =
        call { api.session(bearer(accessToken)) }

    suspend fun logout(accessToken: String): ApiResult<MobileApi.LogoutResponse> =
        call { api.logout(bearer(accessToken)) }

    suspend fun libraries(
        accessToken: String,
        ids: String? = null,
        withPreview: Boolean = false,
        previewLimit: Int? = null,
        includeTotal: Boolean = true,
    ): ApiResult<List<LibraryDto>> =
        call {
            api.libraries(
                bearer(accessToken),
                ids,
                withPreview,
                previewLimit,
                includeTotal.takeIf { !it },
            )
        }

    suspend fun pinnedLibraries(accessToken: String): ApiResult<PinnedLibrariesDto> =
        call { api.pinnedLibraries(bearer(accessToken)) }

    suspend fun libraryTags(accessToken: String): ApiResult<LibraryTagsDto> =
        call { api.libraryTags(bearer(accessToken)) }

    suspend fun librarySeries(
        accessToken: String,
        libraryId: String,
        page: Int,
        pageSize: Int = 100,
        genres: List<String>? = null,
        yearMin: Int? = null,
        yearMax: Int? = null,
        ratingMin: Float? = null,
        unwatched: Boolean = false,
        sort: String? = null,
        order: String? = null,
    ): ApiResult<SeriesPageDto> =
        call {
            api.librarySeries(
                bearer(accessToken),
                libraryId,
                page,
                pageSize,
                genres?.takeIf { it.isNotEmpty() },
                yearMin,
                yearMax,
                ratingMin,
                if (unwatched) 1 else null,
                sort,
                order,
            )
        }

    suspend fun libraryFacets(
        accessToken: String,
        libraryId: String,
    ): ApiResult<LibraryFacetsDto> =
        call { api.libraryFacets(bearer(accessToken), libraryId) }

    suspend fun randomMedia(
        accessToken: String,
        libraryId: String,
        genres: List<String>? = null,
        yearMin: Int? = null,
        yearMax: Int? = null,
        ratingMin: Float? = null,
        unwatched: Boolean = false,
    ): ApiResult<MediaDto> =
        call {
            api.randomMedia(
                bearer(accessToken),
                libraryId,
                genres?.takeIf { it.isNotEmpty() },
                yearMin,
                yearMax,
                ratingMin,
                if (unwatched) 1 else null,
            )
        }

    suspend fun subtitles(
        accessToken: String,
        mediaId: String,
        includeEmbedded: Boolean = true,
    ): ApiResult<SubtitleTracksDto> =
        call { api.subtitles(bearer(accessToken), mediaId, includeEmbedded) }

    suspend fun danmaku(
        accessToken: String,
        mediaId: String,
        keyword: String? = null,
        episodeId: Long? = null,
    ): ApiResult<DanmakuFetchDto> =
        call {
            api.danmaku(
                bearer(accessToken),
                mediaId,
                keyword?.trim()?.takeIf { it.isNotEmpty() },
                episodeId?.takeIf { it > 0L },
            )
        }

    suspend fun danmakuConfig(accessToken: String): ApiResult<DanmakuConfigDto> =
        call { api.danmakuConfig(bearer(accessToken)) }

    suspend fun updateDanmakuSettings(
        accessToken: String,
        patch: DanmakuSettingsPatchDto,
    ): ApiResult<DanmakuConfigDto> =
        call { api.updateDanmakuSettings(bearer(accessToken), patch) }

    suspend fun seriesEpisodes(
        accessToken: String,
        libraryId: String,
        key: String,
    ): ApiResult<MediaListDto> =
        call { api.seriesEpisodes(bearer(accessToken), libraryId, key) }

    suspend fun mediaDetail(accessToken: String, mediaId: String): ApiResult<MediaDto> =
        call { api.mediaDetail(bearer(accessToken), mediaId) }

    suspend fun mediaEpisodes(accessToken: String, mediaId: String): ApiResult<MediaListDto> =
        call { api.mediaEpisodes(bearer(accessToken), mediaId) }

    suspend fun searchMedia(
        accessToken: String,
        query: String,
        page: Int,
        pageSize: Int = 50,
    ): ApiResult<MediaPageDto> =
        call { api.searchMedia(bearer(accessToken), query, page, pageSize) }

    suspend fun recentMedia(accessToken: String, limit: Int = 12): ApiResult<List<SeriesCardDto>> =
        call { api.recentMedia(bearer(accessToken), limit) }

    suspend fun continueWatching(
        accessToken: String,
        limit: Int = 10,
    ): ApiResult<List<ContinueWatchingDto>> =
        call { api.continueWatching(bearer(accessToken), limit) }

    suspend fun playbackInfo(accessToken: String, mediaId: String): ApiResult<PlaybackInfoDto> =
        call { api.playbackInfo(bearer(accessToken), mediaId) }

    suspend fun playbackResume(accessToken: String, mediaId: String): ApiResult<ResumeDto> =
        call { api.playbackResume(bearer(accessToken), mediaId) }

    suspend fun reportProgress(
        accessToken: String,
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
    ): ApiResult<Unit> = callEmpty {
        api.reportProgress(bearer(accessToken), ProgressRequest(mediaId, positionMs, durationMs))
    }

    suspend fun favorites(accessToken: String): ApiResult<FavoriteListDto> =
        call { api.favorites(bearer(accessToken)) }

    /**
     * 拉取观看历史。
     *
     * [includeMedia] 决定服务端是否在每条记录里内嵌整条 media：
     * - 观看历史页面要渲染封面、标题、时长，必须为 `true`（服务端默认值）；
     * - 详情页只按 `media_id` 挑续播目标，传 `false` 可以省掉十倍以上的响应体积。
     *
     * 这是一个由调用方决定的参数，不能写死成 `false`：那样历史页面拿到的
     * 每条记录 `media` 都是 null，列表会被整体过滤成空。
     */
    suspend fun watchHistory(
        accessToken: String,
        limit: Int = 200,
        includeMedia: Boolean = true,
    ): ApiResult<List<WatchHistoryDto>> =
        call { api.watchHistory(bearer(accessToken), limit, includeMedia = includeMedia) }

    suspend fun clearWatchHistory(accessToken: String, mediaId: String? = null): ApiResult<Unit> =
        callEmpty { api.clearWatchHistory(bearer(accessToken), mediaId) }

    suspend fun favoriteStatus(accessToken: String, mediaId: String): ApiResult<FavoriteDto> =
        call { api.favoriteStatus(bearer(accessToken), mediaId) }

    suspend fun setFavorite(
        accessToken: String,
        mediaId: String,
        favorite: Boolean,
    ): ApiResult<FavoriteDto> = call {
        if (favorite) {
            api.addFavorite(bearer(accessToken), mediaId)
        } else {
            api.removeFavorite(bearer(accessToken), mediaId)
        }
    }

    // ── 阅读 ──

    suspend fun readerBooks(accessToken: String, fresh: Boolean = false): ApiResult<ReaderBooksDto> =
        call { api.readerBooks(bearer(accessToken), if (fresh) "no-cache" else null) }

    suspend fun readerChapters(
        accessToken: String,
        bookId: String,
    ): ApiResult<ReaderChaptersDto> =
        call { api.readerChapters(bearer(accessToken), bookId) }

    /**
     * 取章节正文。
     *
     * [mediaDirect] 为真时请求服务端保留上游媒体地址（音频/漫画图片），
     * 由 App 自己下载字节；服务端是否同意还取决于该书源是否开启「媒体直连」，
     * 未开启时响应里的 `media_direct` 仍为 false，调用方按代理地址处理即可。
     * [forceProxy] 本次强制走服务端代理（直连失败后的退路）。
     */
    suspend fun readerContent(
        accessToken: String,
        bookId: String,
        chapterIndex: Int,
        mediaDirect: Boolean = false,
        forceProxy: Boolean = false,
    ): ApiResult<ReaderChapterContentDto> =
        call {
            api.readerContent(
                bearer(accessToken),
                bookId,
                chapterIndex,
                when {
                    mediaDirect -> MEDIA_DIRECT
                    forceProxy -> MEDIA_PROXY
                    else -> null
                },
            )
        }

    suspend fun readerSaveProgress(
        accessToken: String,
        bookId: String,
        chapterIndex: Int,
        pos: Int,
        chapterTitle: String,
    ): ApiResult<ReaderOkResponse> = call {
        api.readerSaveProgress(
            bearer(accessToken),
            bookId,
            ReaderProgressRequest(chapterIndex, pos, chapterTitle),
        )
    }

    suspend fun readerToc(
        accessToken: String,
        sourceUrl: String,
        bookUrl: String,
        tocUrl: String,
        sourceId: String = "",
    ): ApiResult<ReaderTocDto> = call {
        api.readerToc(bearer(accessToken), sourceId, sourceUrl, bookUrl, tocUrl)
    }

    suspend fun readerBookInfo(
        accessToken: String,
        sourceId: String,
        sourceUrl: String,
        bookUrl: String,
    ): ApiResult<ReaderBookInfoDto> = call {
        api.readerBookInfo(bearer(accessToken), sourceId, sourceUrl, bookUrl)
    }

    suspend fun readerSaveChapters(
        accessToken: String,
        bookId: String,
        chapters: List<ReaderChapterDto>,
    ): ApiResult<ReaderOkResponse> = call {
        api.readerSaveChapters(
            bearer(accessToken),
            bookId,
            ReaderSaveChaptersRequest(chapters),
        )
    }

    suspend fun readerSearch(
        accessToken: String,
        key: String,
        page: Int,
        sourceIds: List<String>? = null,
    ): ApiResult<ReaderSearchResponseDto> = call {
        api.readerSearch(bearer(accessToken), ReaderSearchRequest(key, sourceIds, page))
    }

    suspend fun readerAddBook(
        accessToken: String,
        origin: ReaderSearchOriginDto,
        name: String,
        author: String,
        coverUrl: String,
    ): ApiResult<ReaderBookDto> = call {
        api.readerAddBook(
            bearer(accessToken),
            ReaderAddBookRequest(origin, name, author, coverUrl),
        )
    }

    suspend fun readerRemoveBook(
        accessToken: String,
        bookId: String,
    ): ApiResult<ReaderOkResponse> = call {
        api.readerRemoveBook(bearer(accessToken), bookId)
    }

    suspend fun readerProfile(accessToken: String): ApiResult<ReaderProfileResponseDto> =
        call { api.readerProfile(bearer(accessToken)) }

    suspend fun readerSaveProfile(
        accessToken: String,
        profile: ReaderProfileDto,
    ): ApiResult<ReaderProfileResponseDto> =
        call { api.readerSaveProfile(bearer(accessToken), profile) }

    suspend fun readerBookGroups(accessToken: String): ApiResult<ReaderBookGroupsResponseDto> =
        call { api.readerBookGroups(bearer(accessToken)) }

    suspend fun readerSaveBookGroups(
        accessToken: String,
        groups: List<ReaderBookGroupDto>,
    ): ApiResult<ReaderBookGroupsResponseDto> = call {
        api.readerSaveBookGroups(bearer(accessToken), ReaderBookGroupsRequest(groups))
    }

    suspend fun readerRefreshToc(accessToken: String): ApiResult<ReaderRefreshTocDto> =
        call { api.readerRefreshToc(bearer(accessToken)) }

    suspend fun readerSources(
        accessToken: String,
        refresh: Boolean = false,
    ): ApiResult<ReaderSourcesDto> =
        // refresh=1 会被 HttpCachePolicy 视为不缓存，导入/删除后强制重读，
        // 避免 30 秒的客户端缓存把旧列表顶回来。
        call { api.readerSources(bearer(accessToken), if (refresh) "1" else null) }

    suspend fun readerImportSources(
        accessToken: String,
        text: String,
    ): ApiResult<ReaderSourceImportResponseDto> =
        call { api.readerImportSources(bearer(accessToken), ReaderSourceImportRequest(text)) }

    suspend fun readerUpdateSource(
        accessToken: String,
        sourceId: String,
        enabled: Boolean?,
        mediaDirect: Boolean? = null,
    ): ApiResult<ReaderOkResponse> = call {
        api.readerUpdateSource(
            bearer(accessToken),
            sourceId,
            ReaderSourcePatchRequest(enabled = enabled, mediaDirect = mediaDirect),
        )
    }

    suspend fun readerDeleteSource(
        accessToken: String,
        sourceId: String,
    ): ApiResult<ReaderOkResponse> =
        call { api.readerDeleteSource(bearer(accessToken), sourceId) }

    suspend fun readerDebugSource(
        accessToken: String,
        sourceId: String,
        key: String,
    ): ApiResult<ReaderSourceDebugResponseDto> =
        call { api.readerDebugSource(bearer(accessToken), sourceId, ReaderSourceDebugRequest(key)) }

    suspend fun readerSetSourceVariable(
        accessToken: String,
        sourceId: String,
        variable: String,
    ): ApiResult<ReaderOkResponse> =
        call { api.readerSetSourceVariable(bearer(accessToken), sourceId, ReaderSourceVariableRequest(variable)) }

    suspend fun readerSetSourceLoginInfo(
        accessToken: String,
        sourceId: String,
        fields: Map<String, String>,
    ): ApiResult<ReaderOkResponse> =
        call { api.readerSetSourceLoginInfo(bearer(accessToken), sourceId, ReaderSourceLoginInfoRequest(fields)) }

    suspend fun readerSourceLogin(
        accessToken: String,
        sourceId: String,
    ): ApiResult<ReaderSourceLoginDto> =
        call { api.readerSourceLogin(bearer(accessToken), sourceId) }

    suspend fun readerRunSourceLogin(
        accessToken: String,
        sourceId: String,
        action: String,
        fields: Map<String, String>,
    ): ApiResult<ReaderLoginResultDto> = call {
        api.readerRunSourceLogin(
            bearer(accessToken),
            sourceId,
            ReaderLoginActionRequest(action, fields),
        )
    }

    suspend fun readerSourceLogout(
        accessToken: String,
        sourceId: String,
    ): ApiResult<ReaderOkResponse> =
        call { api.readerSourceLogout(bearer(accessToken), sourceId) }

    suspend fun readerBrowserPending(
        accessToken: String,
        sourceId: String,
    ): ApiResult<ReaderBrowserPagesDto> =
        call { api.readerBrowserPending(bearer(accessToken), sourceId) }

    suspend fun readerBrowserResult(
        accessToken: String,
        id: String,
        body: String,
        url: String,
        cancelled: Boolean,
    ): ApiResult<ReaderOkResponse> = call {
        api.readerBrowserResult(
            bearer(accessToken),
            ReaderBrowserResultRequest(id, body, url, cancelled),
        )
    }

    suspend fun readerBrowserXhr(
        accessToken: String,
        id: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
    ): ApiResult<ReaderBrowserXhrResponseDto> = call {
        api.readerBrowserXhr(
            bearer(accessToken),
            ReaderBrowserXhrRequest(id, url, method, headers, body),
        )
    }

    suspend fun readerOpenComment(
        accessToken: String,
        bookId: String,
        url: String,
        title: String,
    ): ApiResult<ReaderBrowserPageDto> = call {
        api.readerOpenComment(bearer(accessToken), ReaderOpenCommentRequest(bookId, url, title))
    }

    suspend fun readerSwitchOrigin(
        accessToken: String,
        bookId: String,
        origin: ReaderSearchOriginDto,
    ): ApiResult<ReaderBookDto> = call {
        api.readerSwitchOrigin(bearer(accessToken), bookId, ReaderSwitchOriginRequest(origin))
    }

    suspend fun readerSaveAudioConfig(
        accessToken: String,
        bookId: String,
        openCredits: Int,
        closeCredits: Int,
    ): ApiResult<ReaderOkResponse> = call {
        api.readerSaveAudioConfig(
            bearer(accessToken),
            bookId,
            ReaderAudioConfigRequest(openCredits, closeCredits),
        )
    }

    suspend fun readerContentBatch(
        accessToken: String,
        bookId: String,
        chapterIndexes: List<Int>,
        mediaDirect: Boolean = false,
    ): ApiResult<ReaderContentBatchDto> = call {
        api.readerContentBatch(
            bearer(accessToken),
            bookId,
            ReaderContentBatchRequest(chapterIndexes),
            if (mediaDirect) MEDIA_DIRECT else null,
        )
    }

    private fun bearer(token: String): String = "Bearer $token"

    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> {
        val response = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            return ApiResult.NetworkError(error.message ?: "network error", error)
        } catch (error: Exception) {
            return ApiResult.NetworkError(error.message ?: "network error", error)
        }
        if (response.isSuccessful) {
            val body = response.body()
            return if (body == null) {
                ApiResult.Failure(
                    status = response.code(),
                    code = "EMPTY_RESPONSE",
                    message = "server returned an empty response",
                )
            } else {
                ApiResult.Success(body)
            }
        }
        return failureFrom(response)
    }

    private suspend fun callEmpty(block: suspend () -> Response<Unit>): ApiResult<Unit> {
        val response = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            return ApiResult.NetworkError(error.message ?: "network error", error)
        } catch (error: Exception) {
            return ApiResult.NetworkError(error.message ?: "network error", error)
        }
        return if (response.isSuccessful) {
            ApiResult.Success(Unit)
        } else {
            failureFrom(response)
        }
    }

    private fun failureFrom(response: Response<*>): ApiResult.Failure {
        val raw = try {
            response.errorBody()?.string().orEmpty()
        } catch (_: IOException) {
            ""
        } catch (_: IllegalStateException) {
            ""
        }
        val parsed = parseError(raw)
        return ApiResult.Failure(
            status = response.code(),
            code = parsed?.code?.takeIf { it.isNotBlank() } ?: fallbackCode(response.code()),
            // 阅读等旧接口用 {"error": "..."} 返回可读原因，没有 v1 的 message 字段。
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: parsed?.error?.takeIf { it.isNotBlank() }
                ?: response.message(),
            retryable = parsed?.retryable ?: (response.code() >= 500),
            retryAfterSec = parsed?.retryAfterSec,
            requestId = parsed?.requestId.orEmpty(),
        )
    }

    private fun parseError(raw: String): MobileApiError? {
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString(MobileApiError.serializer(), raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun fallbackCode(status: Int): String = when (status) {
        401 -> "TOKEN_EXPIRED"
        403 -> "PERMISSION_DENIED"
        409 -> "PROGRESS_CONFLICT"
        429 -> "RATE_LIMITED"
        else -> if (status >= 500) "SERVICE_UNAVAILABLE" else "INVALID_ARGUMENT"
    }

    companion object {
        /** 正文接口 `media` 参数取值：媒体地址直连，由 App 自行下载字节。 */
        const val MEDIA_DIRECT = "direct"

        /** 正文接口 `media` 参数取值：本次强制走服务端代理。 */
        const val MEDIA_PROXY = "proxy"
    }
}
