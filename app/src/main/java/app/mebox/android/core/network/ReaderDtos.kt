package app.mebox.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 阅读子系统（legado 书源兼容）的传输对象。
 *
 * 这些端点沿用服务端既有的 `/api/reader/...` 接口：书架、目录、正文与进度都由
 * 服务端解析书源后下发，安卓端不复制书源规则、Cookie 或解密逻辑。错误响应是
 * 旧格式 `{"error": "..."}`，由 [MeBoxApiClient] 统一兼容。
 */
@Serializable
data class ReaderBookDto(
    val id: String,
    val origin: String = "",
    @SerialName("origin_name") val originName: String = "",
    @SerialName("book_url") val bookUrl: String = "",
    @SerialName("toc_url") val tocUrl: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val intro: String = "",
    val charset: String = "",
    /** 0 文本 / 1 音频 / 2 漫画 / 3 视频。 */
    val type: Int = 0,
    @SerialName("latest_chapter_title") val latestChapterTitle: String = "",
    @SerialName("latest_chapter_time") val latestChapterTime: Long = 0,
    @SerialName("total_chapter_num") val totalChapterNum: Int = 0,
    @SerialName("dur_chapter_index") val durChapterIndex: Int = 0,
    @SerialName("dur_chapter_pos") val durChapterPos: Int = 0,
    @SerialName("dur_chapter_title") val durChapterTitle: String = "",
    @SerialName("dur_chapter_time") val durChapterTime: Long = 0,
    val order: Int = 0,
    @SerialName("is_local") val isLocal: Boolean = false,
    @SerialName("local_external") val localExternal: Boolean = false,
    @SerialName("open_credits") val openCredits: Int = 0,
    @SerialName("close_credits") val closeCredits: Int = 0,
) {
    val isAudio: Boolean get() = type == 1
    val isComic: Boolean get() = type == 2

    /** 服务端 `dur_chapter_pos` 对文本是页码、漫画是图片序号。 */
    val hasProgress: Boolean get() = durChapterIndex > 0 || durChapterPos > 0
}

@Serializable
data class ReaderBooksDto(val books: List<ReaderBookDto>? = null)

/**
 * 书源详情（对应 legado 的书籍信息页，`GET /api/reader/book-info`）。
 * 搜索结果已经带了书名、作者和简介，这里补抓详情规则，主要是拿到更准确的
 * 封面、最新章节和目录地址；[tocUrl] 与书籍地址不同时要用它重新拉目录。
 */
@Serializable
data class ReaderBookInfoDto(
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    @SerialName("word_count") val wordCount: String = "",
    @SerialName("latest_chapter") val latestChapter: String = "",
    val intro: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("toc_url") val tocUrl: String = "",
    @SerialName("book_url") val bookUrl: String = "",
)

@Serializable
data class ReaderChapterDto(
    val index: Int = 0,
    val title: String = "",
    val url: String = "",
    @SerialName("is_volume") val isVolume: Boolean = false,
)

@Serializable
data class ReaderChaptersDto(val chapters: List<ReaderChapterDto>? = null)

@Serializable
data class ReaderTocChapterDto(
    val index: Int = 0,
    val title: String = "",
    val url: String = "",
    @SerialName("is_volume") val isVolume: Boolean = false,
    @SerialName("update_time") val updateTime: String = "",
)

@Serializable
data class ReaderTocDto(val chapters: List<ReaderTocChapterDto>? = null)

@Serializable
data class ReaderContentCommentDto(
    val line: Int = 0,
    val count: Int = 0,
    val url: String = "",
    val label: String = "",
    val block: Boolean = false,
)

@Serializable
data class ReaderChapterContentDto(
    /** text / audio / image。 */
    val type: String = "text",
    val content: String = "",
    val tracks: List<String>? = null,
    val images: List<String>? = null,
    @SerialName("image_style") val imageStyle: String = "",
    val comments: List<ReaderContentCommentDto>? = null,
    val transcoding: Boolean = false,
    val hls: Boolean = false,
    /**
     * 媒体直连：`tracks`/`images` 是上游原始地址，由 App 自己下载。
     * 为 false（默认）时这些地址是服务端的签名代理，App 直接当播放地址用。
     */
    @SerialName("media_direct") val mediaDirect: Boolean = false,
    /** 直连取媒体所需的请求头（防盗链 Referer/UA、登录 Cookie 等）。 */
    @SerialName("media_headers") val mediaHeaders: Map<String, String>? = null,
) {
    /** 直连书源下取媒体的请求头；代理模式为空，此时不该附带任何书源头。 */
    val directHeaders: Map<String, String>
        get() = if (mediaDirect) mediaHeaders.orEmpty() else emptyMap()
}

@Serializable
data class ReaderSearchRequest(
    val key: String,
    @SerialName("source_ids") val sourceIds: List<String>? = null,
    val page: Int = 1,
)

@Serializable
data class ReaderSearchOriginDto(
    @SerialName("source_id") val sourceId: String = "",
    val origin: String = "",
    @SerialName("origin_name") val originName: String = "",
    @SerialName("origin_type") val originType: Int = 0,
    @SerialName("book_url") val bookUrl: String = "",
    @SerialName("latest_chapter") val latestChapter: String = "",
)

@Serializable
data class ReaderSearchBookDto(
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    @SerialName("word_count") val wordCount: String = "",
    @SerialName("latest_chapter") val latestChapter: String = "",
    val intro: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("book_url") val bookUrl: String = "",
    val origins: List<ReaderSearchOriginDto>? = null,
)

@Serializable
data class ReaderSearchSkippedDto(
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("origin_name") val originName: String = "",
    val reason: String = "",
)

@Serializable
data class ReaderSearchResponseDto(
    val books: List<ReaderSearchBookDto>? = null,
    val skipped: List<ReaderSearchSkippedDto>? = null,
)

@Serializable
data class ReaderAddBookRequest(
    val origin: ReaderSearchOriginDto,
    val name: String,
    val author: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
)

@Serializable
data class ReaderSaveChaptersRequest(val chapters: List<ReaderChapterDto>)

@Serializable
data class ReaderProgressRequest(
    @SerialName("chapter_index") val chapterIndex: Int,
    val pos: Int,
    @SerialName("chapter_title") val chapterTitle: String,
)

@Serializable
data class ReaderOkResponse(val ok: Boolean = true)

@Serializable
data class ReaderProfileDto(
    @SerialName("theme_id") val themeId: String = "preset1",
    val night: Boolean = false,
    @SerialName("page_mode") val pageMode: String = "scroll",
    @SerialName("font_size") val fontSize: Int = 18,
    @SerialName("line_height") val lineHeight: Double = 1.8,
    @SerialName("paragraph_spacing") val paragraphSpacing: Int = 8,
    @SerialName("audio_speed") val audioSpeed: Double = 1.0,
    @SerialName("audio_timer_minutes") val audioTimerMinutes: Int = 0,
    @SerialName("shelf_layout") val shelfLayout: String = "grid",
    @SerialName("shelf_grid_columns") val shelfGridColumns: Int = 0,
    @SerialName("shelf_sort") val shelfSort: String = "recent",
    @SerialName("shelf_show_unread") val shelfShowUnread: Boolean = true,
    @SerialName("shelf_show_update_time") val shelfShowUpdateTime: Boolean = true,
)

@Serializable
data class ReaderProfileResponseDto(val profile: ReaderProfileDto? = null)

@Serializable
data class ReaderBookGroupDto(
    val name: String = "",
    @SerialName("book_ids") val bookIds: List<String>? = null,
)

@Serializable
data class ReaderBookGroupsResponseDto(val groups: List<ReaderBookGroupDto>? = null)

@Serializable
data class ReaderBookGroupsRequest(val groups: List<ReaderBookGroupDto>)

@Serializable
data class ReaderRefreshTocDto(
    val total: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
)

// ── 书源、登录与宿主浏览器 ──

@Serializable
data class ReaderSourceDto(
    val id: String,
    val name: String = "",
    val group: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
    val type: Int = 0,
    val enabled: Boolean = true,
    @SerialName("enabled_explore") val enabledExplore: Boolean = true,
    @SerialName("custom_order") val customOrder: Int = 0,
    val weight: Int = 0,
    @SerialName("concurrent_rate") val concurrentRate: String = "",
    @SerialName("last_update_time") val lastUpdateTime: Long = 0,
    @SerialName("respond_time") val respondTime: Long = 0,
    @SerialName("has_login") val hasLogin: Boolean = false,
    @SerialName("needs_browser") val needsBrowser: Boolean = false,
    val comment: String = "",
    /**
     * 媒体直连：允许 App 自己去上游下载音频/漫画图片，服务端只解析地址与
     * 下发请求头。默认关闭，关闭时仍由服务端代理字节。
     */
    @SerialName("media_direct") val mediaDirect: Boolean = false,
)

@Serializable
data class ReaderSourcesDto(val sources: List<ReaderSourceDto>? = null)

@Serializable
data class ReaderSourceImportRequest(val text: String)

@Serializable
data class ReaderSourceImportResponseDto(val imported: Int = 0)

@Serializable
data class ReaderSourceEnabledRequest(val enabled: Boolean)

/** 书源开关补丁：只提交需要变更的字段。 */
@Serializable
data class ReaderSourcePatchRequest(
    val enabled: Boolean? = null,
    @SerialName("media_direct") val mediaDirect: Boolean? = null,
)

@Serializable
data class ReaderSourceDebugRequest(val key: String)

@Serializable
data class ReaderSourceDebugResponseDto(val logs: List<String>? = null)

@Serializable
data class ReaderSourceVariableRequest(val variable: String)

@Serializable
data class ReaderSourceLoginInfoRequest(val fields: Map<String, String> = emptyMap())

@Serializable
data class ReaderLoginFieldDto(
    val name: String = "",
    /** text / password / button / toggle / select。 */
    val type: String = "text",
    val action: String = "",
    val chars: List<String>? = null,
    val default: String = "",
    @SerialName("viewName") val viewName: String = "",
)

@Serializable
data class ReaderSourceLoginDto(
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("has_login_js") val hasLoginJs: Boolean = false,
    @SerialName("login_js") val loginJs: String = "",
    val fields: List<ReaderLoginFieldDto>? = null,
    val values: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val variable: String = "",
    @SerialName("variable_comment") val variableComment: String = "",
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val error: String = "",
)

@Serializable
data class ReaderLoginActionRequest(
    val action: String = "",
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
data class ReaderBrowserRequestDto(
    val url: String = "",
    val title: String = "",
)

@Serializable
data class ReaderLoginResultDto(
    val ok: Boolean = false,
    val error: String = "",
    val toasts: List<String>? = null,
    val browsers: List<ReaderBrowserRequestDto>? = null,
    @SerialName("ui_refresh") val uiRefresh: Boolean = false,
    val values: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    @SerialName("logged_in") val loggedIn: Boolean = false,
)

@Serializable
data class ReaderBrowserPageDto(
    val id: String = "",
    val title: String = "",
    /** wait = 需要回传 DOM；open = 仅展示。 */
    val mode: String = "open",
    val seq: Long = 0,
    @SerialName("page_url") val pageUrl: String = "",
    val refetch: Boolean = false,
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("target_url") val targetUrl: String = "",
)

@Serializable
data class ReaderBrowserPagesDto(val pages: List<ReaderBrowserPageDto>? = null)

@Serializable
data class ReaderBrowserResultRequest(
    val id: String,
    val body: String = "",
    val url: String = "",
    val cancelled: Boolean = false,
)

@Serializable
data class ReaderBrowserXhrRequest(
    val id: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

@Serializable
data class ReaderBrowserXhrResponseDto(
    val status: Int = 0,
    @SerialName("content_type") val contentType: String = "text/plain",
    val body: String = "",
    val base64: Boolean = false,
)

@Serializable
data class ReaderOpenCommentRequest(
    @SerialName("book_id") val bookId: String,
    val url: String,
    val title: String = "段评",
)

// ── 换源、听书配置与批量正文 ──

@Serializable
data class ReaderSwitchOriginRequest(val origin: ReaderSearchOriginDto)

@Serializable
data class ReaderAudioConfigRequest(
    @SerialName("open_credits") val openCredits: Int = 0,
    @SerialName("close_credits") val closeCredits: Int = 0,
)

@Serializable
data class ReaderContentBatchRequest(
    @SerialName("chapter_indexes") val chapterIndexes: List<Int>,
    @SerialName("apply_replace") val applyReplace: Boolean = false,
)

@Serializable
data class ReaderContentBatchItemDto(
    @SerialName("chapter_index") val chapterIndex: Int = 0,
    val type: String = "",
    val content: String = "",
    val tracks: List<String>? = null,
    val images: List<String>? = null,
    @SerialName("image_style") val imageStyle: String = "",
    val hls: Boolean = false,
    val comments: List<ReaderContentCommentDto>? = null,
    val cached: Boolean = false,
    val error: String = "",
    /** 同 [ReaderChapterContentDto.mediaDirect]：本章媒体地址是否由 App 直连。 */
    @SerialName("media_direct") val mediaDirect: Boolean = false,
    @SerialName("media_headers") val mediaHeaders: Map<String, String>? = null,
)

@Serializable
data class ReaderContentBatchStatsDto(
    val hit: Int = 0,
    val miss: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class ReaderContentBatchDto(
    val items: List<ReaderContentBatchItemDto>? = null,
    val stats: ReaderContentBatchStatsDto = ReaderContentBatchStatsDto(),
)
