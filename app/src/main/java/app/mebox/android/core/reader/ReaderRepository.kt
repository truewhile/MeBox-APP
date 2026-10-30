package app.mebox.android.core.reader

import app.mebox.android.core.auth.AuthCoordinator
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.MeBoxApiClient
import app.mebox.android.core.network.ReaderAudioConfigRequest
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto
import app.mebox.android.core.network.ReaderBookInfoDto
import app.mebox.android.core.network.ReaderBrowserPageDto
import app.mebox.android.core.network.ReaderBrowserXhrResponseDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import app.mebox.android.core.network.ReaderContentBatchDto
import app.mebox.android.core.network.ReaderLoginResultDto
import app.mebox.android.core.network.ReaderProfileDto
import app.mebox.android.core.network.ReaderRefreshTocDto
import app.mebox.android.core.network.ReaderSearchBookDto
import app.mebox.android.core.network.ReaderSearchOriginDto
import app.mebox.android.core.network.ReaderSearchSkippedDto
import app.mebox.android.core.network.ReaderSourceDto
import app.mebox.android.core.network.ReaderSourceLoginDto
import app.mebox.android.core.network.ReaderTocChapterDto

/**
 * 阅读数据访问层。
 *
 * 所有请求都经过 [AuthCoordinator]，与影视模块共用单次刷新和会话撤销逻辑。
 * 书源解析、防盗链、解密和正文净化全部留在服务端。
 */
class ReaderRepository(
    private val api: MeBoxApiClient,
    private val coordinator: AuthCoordinator,
    private val scope: AccountScope,
    private val offlineOnly: Boolean = false,
) {
    suspend fun shelf(fresh: Boolean = false): ApiResult<List<ReaderBookDto>> =
        authorized { api.readerBooks(it, fresh) }.mapSuccess { it.books.orEmpty() }

    suspend fun chapters(bookId: String): ApiResult<List<ReaderChapterDto>> =
        authorized { api.readerChapters(it, bookId) }.mapSuccess { it.chapters.orEmpty() }

    /**
     * 取章节正文。
     *
     * [mediaDirect] 为真时请求服务端保留上游媒体地址，由 App 自己去下载音频/
     * 漫画图片字节。是否真的直连取决于书源开关：响应里的 `mediaDirect` 为 false
     * 时说明服务端仍给了代理地址，调用方按普通地址使用即可。
     */
    suspend fun content(
        bookId: String,
        chapterIndex: Int,
        mediaDirect: Boolean = false,
    ): ApiResult<ReaderChapterContentDto> =
        authorized { api.readerContent(it, bookId, chapterIndex, mediaDirect) }

    /**
     * 直连取媒体失败后的退路：让服务端代理这一次。
     *
     * 直连失败常见于防盗链头不足、票失效或客户端网络被上游限流；这时不该要求
     * 用户去改书源设置，直接按代理地址重取一章即可继续播放/阅读。
     */
    suspend fun contentViaProxy(
        bookId: String,
        chapterIndex: Int,
    ): ApiResult<ReaderChapterContentDto> =
        authorized { api.readerContent(it, bookId, chapterIndex, mediaDirect = false, forceProxy = true) }

    suspend fun saveProgress(
        bookId: String,
        chapterIndex: Int,
        pos: Int,
        chapterTitle: String,
    ): ApiResult<Unit> =
        authorized { api.readerSaveProgress(it, bookId, chapterIndex, pos, chapterTitle) }
            .mapSuccess { }

    suspend fun toc(
        sourceUrl: String,
        bookUrl: String,
        tocUrl: String,
        sourceId: String = "",
    ): ApiResult<List<ReaderTocChapterDto>> =
        authorized { api.readerToc(it, sourceUrl, bookUrl, tocUrl, sourceId) }
            .mapSuccess { it.chapters.orEmpty() }

    /** 书源详情：用于搜索结果的书籍详情页，不需要先把书加入书架。 */
    suspend fun bookInfo(
        sourceId: String,
        sourceUrl: String,
        bookUrl: String,
    ): ApiResult<ReaderBookInfoDto> =
        authorized { api.readerBookInfo(it, sourceId, sourceUrl, bookUrl) }

    suspend fun saveChapters(
        bookId: String,
        chapters: List<ReaderChapterDto>,
    ): ApiResult<Unit> =
        authorized { api.readerSaveChapters(it, bookId, chapters) }.mapSuccess { }

    suspend fun search(
        key: String,
        page: Int,
        sourceIds: List<String>? = null,
    ): ApiResult<ReaderSearchResult> = authorized { api.readerSearch(it, key, page, sourceIds) }
        .mapSuccess {
            ReaderSearchResult(
                books = it.books.orEmpty(),
                skipped = it.skipped.orEmpty(),
            )
        }

    suspend fun addBook(
        origin: ReaderSearchOriginDto,
        name: String,
        author: String,
        coverUrl: String,
    ): ApiResult<ReaderBookDto> =
        authorized { api.readerAddBook(it, origin, name, author, coverUrl) }

    suspend fun removeBook(bookId: String): ApiResult<Unit> =
        authorized { api.readerRemoveBook(it, bookId) }.mapSuccess { }

    suspend fun profile(): ApiResult<ReaderProfileDto?> =
        authorized { api.readerProfile(it) }.mapSuccess { it.profile }

    suspend fun saveProfile(profile: ReaderProfileDto): ApiResult<ReaderProfileDto?> =
        authorized { api.readerSaveProfile(it, profile) }.mapSuccess { it.profile }

    suspend fun bookGroups(): ApiResult<List<ReaderBookGroupDto>> =
        authorized { api.readerBookGroups(it) }.mapSuccess { it.groups.orEmpty() }

    suspend fun saveBookGroups(groups: List<ReaderBookGroupDto>): ApiResult<List<ReaderBookGroupDto>> =
        authorized { api.readerSaveBookGroups(it, groups) }.mapSuccess { it.groups.orEmpty() }

    suspend fun refreshToc(): ApiResult<ReaderRefreshTocDto> =
        authorized { api.readerRefreshToc(it) }

    suspend fun sources(refresh: Boolean = false): ApiResult<List<ReaderSourceDto>> =
        authorized { api.readerSources(it, refresh) }.mapSuccess { it.sources.orEmpty() }

    /** 导入书源：支持 JSON 数组 / 单对象 / Base64 / 书源链接，返回导入数量。 */
    suspend fun importSources(text: String): ApiResult<Int> =
        authorized { api.readerImportSources(it, text) }.mapSuccess { it.imported }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean): ApiResult<Unit> =
        authorized { api.readerUpdateSource(it, sourceId, enabled, null) }.mapSuccess { }

    /**
     * 切换书源「媒体直连」：开启后 App 自己去上游下载音频/漫画图片，
     * 服务端只解析地址与下发请求头。关闭时恢复服务端代理。
     */
    suspend fun setSourceMediaDirect(sourceId: String, direct: Boolean): ApiResult<Unit> =
        authorized { api.readerUpdateSource(it, sourceId, null, direct) }.mapSuccess { }

    suspend fun deleteSource(sourceId: String): ApiResult<Unit> =
        authorized { api.readerDeleteSource(it, sourceId) }.mapSuccess { }

    /** 书源调试：按关键词跑一次搜索规则，返回服务端收集的执行日志。 */
    suspend fun debugSource(sourceId: String, key: String): ApiResult<List<String>> =
        authorized { api.readerDebugSource(it, sourceId, key) }.mapSuccess { it.logs.orEmpty() }

    suspend fun saveSourceVariable(sourceId: String, variable: String): ApiResult<Unit> =
        authorized { api.readerSetSourceVariable(it, sourceId, variable) }.mapSuccess { }

    suspend fun saveSourceLoginInfo(sourceId: String, fields: Map<String, String>): ApiResult<Unit> =
        authorized { api.readerSetSourceLoginInfo(it, sourceId, fields) }.mapSuccess { }

    suspend fun sourceLogin(sourceId: String): ApiResult<ReaderSourceLoginDto> =
        authorized { api.readerSourceLogin(it, sourceId) }

    suspend fun runSourceLogin(
        sourceId: String,
        action: String,
        fields: Map<String, String>,
    ): ApiResult<ReaderLoginResultDto> =
        authorized { api.readerRunSourceLogin(it, sourceId, action, fields) }

    suspend fun sourceLogout(sourceId: String): ApiResult<Unit> =
        authorized { api.readerSourceLogout(it, sourceId) }.mapSuccess { }

    suspend fun browserPending(sourceId: String): ApiResult<List<ReaderBrowserPageDto>> =
        authorized { api.readerBrowserPending(it, sourceId) }
            .mapSuccess { it.pages.orEmpty() }

    suspend fun browserResult(
        id: String,
        body: String,
        url: String,
        cancelled: Boolean,
    ): ApiResult<Unit> =
        authorized { api.readerBrowserResult(it, id, body, url, cancelled) }.mapSuccess { }

    suspend fun browserXhr(
        id: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
    ): ApiResult<ReaderBrowserXhrResponseDto> =
        authorized { api.readerBrowserXhr(it, id, url, method, headers, body) }

    suspend fun openComment(
        bookId: String,
        url: String,
        title: String,
    ): ApiResult<ReaderBrowserPageDto> =
        authorized { api.readerOpenComment(it, bookId, url, title) }

    suspend fun switchOrigin(
        bookId: String,
        origin: ReaderSearchOriginDto,
    ): ApiResult<ReaderBookDto> =
        authorized { api.readerSwitchOrigin(it, bookId, origin) }

    suspend fun saveAudioConfig(
        bookId: String,
        openCredits: Int,
        closeCredits: Int,
    ): ApiResult<Unit> =
        authorized { api.readerSaveAudioConfig(it, bookId, openCredits, closeCredits) }
            .mapSuccess { }

    suspend fun contentBatch(
        bookId: String,
        chapterIndexes: List<Int>,
        mediaDirect: Boolean = false,
    ): ApiResult<ReaderContentBatchDto> =
        authorized { api.readerContentBatch(it, bookId, chapterIndexes, mediaDirect) }

    private suspend fun <T> authorized(block: suspend (String) -> ApiResult<T>): ApiResult<T> =
        if (offlineOnly) ApiResult.NetworkError("离线模式仅可读取本机缓存")
        else coordinator.authorized(scope, block)
}

data class ReaderSearchResult(
    val books: List<ReaderSearchBookDto> = emptyList(),
    val skipped: List<ReaderSearchSkippedDto> = emptyList(),
)

private inline fun <T, R> ApiResult<T>.mapSuccess(transform: (T) -> R): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> ApiResult.Success(transform(value))
        is ApiResult.Failure -> this
        is ApiResult.NetworkError -> this
        ApiResult.Stale -> ApiResult.Stale
    }
