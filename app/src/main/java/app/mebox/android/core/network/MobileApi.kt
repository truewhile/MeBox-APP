package app.mebox.android.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** v1 移动端端点定义。地址由 Retrofit 的 baseUrl 决定。 */
interface MobileApi {
    @GET("api/v1/server-info")
    suspend fun serverInfo(): Response<ServerInfo>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<AuthResponse>

    @GET("api/v1/auth/session")
    suspend fun session(@Header("Authorization") authorization: String): Response<SessionResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<LogoutResponse>

    @GET("api/libraries")
    suspend fun libraries(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String?,
        @Query("with_preview") withPreview: Boolean,
        @Query("preview_limit") previewLimit: Int?,
        /** `false` 时服务端跳过每库 COUNT(*)；首页预览只需要卡片。 */
        @Query("include_total") includeTotal: Boolean?,
    ): Response<List<LibraryDto>>

    @GET("api/libraries/{id}/series")
    suspend fun librarySeries(
        @Header("Authorization") authorization: String,
        @Path("id") libraryId: String,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("genre") genres: List<String>?,
        @Query("year_min") yearMin: Int?,
        @Query("year_max") yearMax: Int?,
        @Query("rating_min") ratingMin: Float?,
        @Query("unwatched") unwatched: Int?,
        @Query("sort") sort: String?,
        @Query("order") order: String?,
    ): Response<SeriesPageDto>

    @GET("api/libraries/{id}/series/episodes")
    suspend fun seriesEpisodes(
        @Header("Authorization") authorization: String,
        @Path("id") libraryId: String,
        @Query("key") key: String,
    ): Response<MediaListDto>

    @GET("api/media/{id}")
    suspend fun mediaDetail(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<MediaDto>

    @GET("api/media/{id}/episodes")
    suspend fun mediaEpisodes(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<MediaListDto>

    @GET("api/media/{id}/subtitles")
    suspend fun subtitles(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
        @Query("include_embedded") includeEmbedded: Boolean,
    ): Response<SubtitleTracksDto>

    // ── 弹幕（复用服务端既有 /api/danmaku 端点，服务端负责识别与匹配） ──

    @GET("api/danmaku/{id}")
    suspend fun danmaku(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
        @Query("kw") keyword: String?,
        @Query("episodeId") episodeId: Long?,
    ): Response<DanmakuFetchDto>

    @GET("api/danmaku/config")
    suspend fun danmakuConfig(
        @Header("Authorization") authorization: String,
    ): Response<DanmakuConfigDto>

    @PUT("api/danmaku/settings")
    suspend fun updateDanmakuSettings(
        @Header("Authorization") authorization: String,
        @Body patch: DanmakuSettingsPatchDto,
    ): Response<DanmakuConfigDto>

    @GET("api/libraries/{id}/facets")
    suspend fun libraryFacets(
        @Header("Authorization") authorization: String,
        @Path("id") libraryId: String,
    ): Response<LibraryFacetsDto>

    @GET("api/libraries/{id}/random")
    suspend fun randomMedia(
        @Header("Authorization") authorization: String,
        @Path("id") libraryId: String,
        @Query("genre") genres: List<String>?,
        @Query("year_min") yearMin: Int?,
        @Query("year_max") yearMax: Int?,
        @Query("rating_min") ratingMin: Float?,
        @Query("unwatched") unwatched: Int?,
    ): Response<MediaDto>

    @GET("api/media")
    suspend fun searchMedia(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
    ): Response<MediaPageDto>

    @GET("api/media/recent")
    suspend fun recentMedia(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<List<SeriesCardDto>>

    @GET("api/watch-history/continue")
    suspend fun continueWatching(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<List<ContinueWatchingDto>>

    @GET("api/playback/{id}/info")
    suspend fun playbackInfo(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<PlaybackInfoDto>

    @GET("api/playback/{id}/resume")
    suspend fun playbackResume(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<ResumeDto>

    @POST("api/history")
    suspend fun reportProgress(
        @Header("Authorization") authorization: String,
        @Body request: ProgressRequest,
    ): Response<Unit>

    @GET("api/favourites")
    suspend fun favorites(
        @Header("Authorization") authorization: String,
    ): Response<FavoriteListDto>

    @GET("api/watch-history")
    suspend fun watchHistory(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<List<WatchHistoryDto>>

    @DELETE("api/watch-history")
    suspend fun clearWatchHistory(
        @Header("Authorization") authorization: String,
        @Query("media_id") mediaId: String?,
    ): Response<Unit>

    @GET("api/media/{id}/favorite/status")
    suspend fun favoriteStatus(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<FavoriteDto>

    @POST("api/media/{id}/favorite")
    suspend fun addFavorite(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<FavoriteDto>

    @DELETE("api/media/{id}/favorite")
    suspend fun removeFavorite(
        @Header("Authorization") authorization: String,
        @Path("id") mediaId: String,
    ): Response<FavoriteDto>

    // ── 阅读（复用服务端既有 /api/reader 端点，由服务端解析书源） ──

    @GET("api/reader/books")
    suspend fun readerBooks(
        @Header("Authorization") authorization: String,
    ): Response<ReaderBooksDto>

    @GET("api/reader/books/{id}/chapters")
    suspend fun readerChapters(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
    ): Response<ReaderChaptersDto>

    @GET("api/reader/books/{id}/content")
    suspend fun readerContent(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
        @Query("chapter") chapter: Int,
    ): Response<ReaderChapterContentDto>

    @PUT("api/reader/books/{id}/progress")
    suspend fun readerSaveProgress(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
        @Body request: ReaderProgressRequest,
    ): Response<ReaderOkResponse>

    @GET("api/reader/toc")
    suspend fun readerToc(
        @Header("Authorization") authorization: String,
        @Query("source_url") sourceUrl: String,
        @Query("book_url") bookUrl: String,
        @Query("toc_url") tocUrl: String,
    ): Response<ReaderTocDto>

    @POST("api/reader/books/{id}/chapters")
    suspend fun readerSaveChapters(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
        @Body request: ReaderSaveChaptersRequest,
    ): Response<ReaderOkResponse>

    @POST("api/reader/search")
    suspend fun readerSearch(
        @Header("Authorization") authorization: String,
        @Body request: ReaderSearchRequest,
    ): Response<ReaderSearchResponseDto>

    @POST("api/reader/books")
    suspend fun readerAddBook(
        @Header("Authorization") authorization: String,
        @Body request: ReaderAddBookRequest,
    ): Response<ReaderBookDto>

    @DELETE("api/reader/books/{id}")
    suspend fun readerRemoveBook(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
    ): Response<ReaderOkResponse>

    @GET("api/reader/profile")
    suspend fun readerProfile(
        @Header("Authorization") authorization: String,
    ): Response<ReaderProfileResponseDto>

    @PUT("api/reader/profile")
    suspend fun readerSaveProfile(
        @Header("Authorization") authorization: String,
        @Body profile: ReaderProfileDto,
    ): Response<ReaderProfileResponseDto>

    @GET("api/reader/book-groups")
    suspend fun readerBookGroups(
        @Header("Authorization") authorization: String,
    ): Response<ReaderBookGroupsResponseDto>

    @PUT("api/reader/book-groups")
    suspend fun readerSaveBookGroups(
        @Header("Authorization") authorization: String,
        @Body request: ReaderBookGroupsRequest,
    ): Response<ReaderBookGroupsResponseDto>

    @POST("api/reader/shelf/refresh-toc")
    suspend fun readerRefreshToc(
        @Header("Authorization") authorization: String,
    ): Response<ReaderRefreshTocDto>

    @GET("api/reader/sources")
    suspend fun readerSources(
        @Header("Authorization") authorization: String,
    ): Response<ReaderSourcesDto>

    @GET("api/reader/sources/{id}/login")
    suspend fun readerSourceLogin(
        @Header("Authorization") authorization: String,
        @Path("id") sourceId: String,
    ): Response<ReaderSourceLoginDto>

    @POST("api/reader/sources/{id}/login")
    suspend fun readerRunSourceLogin(
        @Header("Authorization") authorization: String,
        @Path("id") sourceId: String,
        @Body request: ReaderLoginActionRequest,
    ): Response<ReaderLoginResultDto>

    @DELETE("api/reader/sources/{id}/login")
    suspend fun readerSourceLogout(
        @Header("Authorization") authorization: String,
        @Path("id") sourceId: String,
    ): Response<ReaderOkResponse>

    @GET("api/reader/browser/pending")
    suspend fun readerBrowserPending(
        @Header("Authorization") authorization: String,
        @Query("source_id") sourceId: String,
    ): Response<ReaderBrowserPagesDto>

    @POST("api/reader/browser/result")
    suspend fun readerBrowserResult(
        @Header("Authorization") authorization: String,
        @Body request: ReaderBrowserResultRequest,
    ): Response<ReaderOkResponse>

    @POST("api/reader/browser/xhr")
    suspend fun readerBrowserXhr(
        @Header("Authorization") authorization: String,
        @Body request: ReaderBrowserXhrRequest,
    ): Response<ReaderBrowserXhrResponseDto>

    @POST("api/reader/comments/open")
    suspend fun readerOpenComment(
        @Header("Authorization") authorization: String,
        @Body request: ReaderOpenCommentRequest,
    ): Response<ReaderBrowserPageDto>

    @POST("api/reader/books/{id}/origin")
    suspend fun readerSwitchOrigin(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
        @Body request: ReaderSwitchOriginRequest,
    ): Response<ReaderBookDto>

    @PUT("api/reader/books/{id}/audio-config")
    suspend fun readerSaveAudioConfig(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
        @Body request: ReaderAudioConfigRequest,
    ): Response<ReaderOkResponse>

    @POST("api/reader/books/{id}/content-batch")
    suspend fun readerContentBatch(
        @Header("Authorization") authorization: String,
        @Path("id") bookId: String,
        @Body request: ReaderContentBatchRequest,
    ): Response<ReaderContentBatchDto>

    @Serializable
    data class LogoutResponse(val status: String = "ok")
}

fun createMobileApi(
    address: ServerAddress,
    client: OkHttpClient,
    json: Json,
): MobileApi {
    val contentType = "application/json".toMediaType()
    return Retrofit.Builder()
        .baseUrl(address.baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
        .create(MobileApi::class.java)
}
