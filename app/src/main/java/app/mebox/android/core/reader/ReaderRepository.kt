package app.mebox.android.core.reader

import app.mebox.android.core.auth.AuthCoordinator
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.MeBoxApiClient
import app.mebox.android.core.network.ReaderAudioConfigRequest
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto
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
) {
    suspend fun shelf(): ApiResult<List<ReaderBookDto>> =
        authorized { api.readerBooks(it) }.mapSuccess { it.books.orEmpty() }

    suspend fun chapters(bookId: String): ApiResult<List<ReaderChapterDto>> =
        authorized { api.readerChapters(it, bookId) }.mapSuccess { it.chapters.orEmpty() }

    suspend fun content(bookId: String, chapterIndex: Int): ApiResult<ReaderChapterContentDto> =
        authorized { api.readerContent(it, bookId, chapterIndex) }

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
    ): ApiResult<List<ReaderTocChapterDto>> =
        authorized { api.readerToc(it, sourceUrl, bookUrl, tocUrl) }
            .mapSuccess { it.chapters.orEmpty() }

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

    suspend fun sources(): ApiResult<List<ReaderSourceDto>> =
        authorized { api.readerSources(it) }.mapSuccess { it.sources.orEmpty() }

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
    ): ApiResult<ReaderContentBatchDto> =
        authorized { api.readerContentBatch(it, bookId, chapterIndexes) }

    private suspend fun <T> authorized(block: suspend (String) -> ApiResult<T>): ApiResult<T> =
        coordinator.authorized(scope, block)
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
