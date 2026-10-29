package app.mebox.android.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto
import app.mebox.android.core.network.ReaderBookInfoDto
import app.mebox.android.core.network.ReaderBrowserPageDto
import app.mebox.android.core.network.ReaderBrowserXhrResponseDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import app.mebox.android.core.network.ReaderContentBatchItemDto
import app.mebox.android.core.network.ReaderContentCommentDto
import app.mebox.android.core.network.ReaderLoginFieldDto
import app.mebox.android.core.network.ReaderProfileDto
import app.mebox.android.core.network.ReaderSearchBookDto
import app.mebox.android.core.network.ReaderSearchOriginDto
import app.mebox.android.core.network.ReaderSearchSkippedDto
import app.mebox.android.core.network.ReaderSourceDto
import app.mebox.android.core.network.ReaderSourceLoginDto
import app.mebox.android.core.network.ReaderTocChapterDto
import app.mebox.android.core.reader.ReaderAudioCheckpointStore
import app.mebox.android.core.reader.ReaderAudioEngine
import app.mebox.android.core.reader.ReaderAudioResume
import app.mebox.android.core.reader.ReaderAudioState
import app.mebox.android.core.reader.ReaderAudioTuning
import app.mebox.android.core.reader.ReaderCacheStore
import app.mebox.android.core.reader.ReaderCacheStatus
import app.mebox.android.core.reader.ReaderProgress
import app.mebox.android.core.reader.ReaderProgressResume
import app.mebox.android.core.reader.ReaderProgressStore
import app.mebox.android.core.reader.ReaderProgressSync
import kotlinx.coroutines.CancellationException
import app.mebox.android.core.reader.ReaderParagraph
import app.mebox.android.core.reader.ReaderPreferences
import app.mebox.android.core.reader.ReaderRepository
import app.mebox.android.core.reader.ReaderSearchScope
import app.mebox.android.core.reader.ReaderShelf
import app.mebox.android.core.reader.ReaderStyle
import app.mebox.android.core.reader.ReaderText
import app.mebox.android.feature.ErrorMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderScreen {
    data object Shelf : ReaderScreen
    data object Search : ReaderScreen

    /** 搜索结果的书源详情（仿 legado 书籍信息页），加入书架前就能看目录和章数。 */
    data object BookDetail : ReaderScreen
    data object Reading : ReaderScreen

    /** 书源管理（仿网页端：「书源管理」页）：导入、启停、删除、登录与调试。 */
    data object Sources : ReaderScreen
}

data class ReaderUiState(
    val screen: ReaderScreen = ReaderScreen.Shelf,
    val style: ReaderStyle = ReaderStyle.DEFAULT,
    val serverProfile: ReaderProfileDto = ReaderProfileDto(),

    // 书架
    val books: List<ReaderBookDto> = emptyList(),
    val groups: List<ReaderBookGroupDto> = emptyList(),
    val groupsLoaded: Boolean = false,
    val selectedGroup: String? = null,
    val shelfLoading: Boolean = false,
    val shelfError: String? = null,
    val shelfNotice: String? = null,
    val tocRefreshing: Boolean = false,

    /** 书架「移出」二次确认的目标；非空时显示确认弹窗。 */
    val shelfRemoveTarget: ReaderBookDto? = null,
    val shelfRemoveBusy: Boolean = false,
    val shelfRemoveError: String? = null,

    // 搜索
    val query: String = "",
    val searchBooks: List<ReaderSearchBookDto> = emptyList(),
    val searchSkipped: List<ReaderSearchSkippedDto> = emptyList(),
    val searchPage: Int = 0,
    val searchLoading: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val searchError: String? = null,

    /** 已保存的搜索范围（已选书源 ID）；空集合表示全部启用书源。 */
    val searchScopeIds: Set<String> = emptySet(),
    val searchScopeOpen: Boolean = false,
    /** 弹层里的勾选；null 表示默认全选所有已启用书源。 */
    val searchScopeDraft: Set<String>? = null,

    val searchDetail: ReaderSearchBookDto? = null,
    val addingBook: Boolean = false,
    val addBookError: String? = null,

    // 书籍详情（搜索结果 → 详情页，加入书架前即可查看目录与章数）
    val detailOrigin: ReaderSearchOriginDto? = null,
    val detailInfo: ReaderBookInfoDto? = null,
    val detailInfoLoading: Boolean = false,
    val detailInfoError: String? = null,
    val detailChapters: List<ReaderTocChapterDto> = emptyList(),
    val detailChaptersLoading: Boolean = false,
    val detailChaptersError: String? = null,
    val detailOriginPickerOpen: Boolean = false,
    val detailOriginLoading: Boolean = false,
    val detailOriginError: String? = null,
    val detailOrigins: List<ReaderSearchOriginDto> = emptyList(),

    // 阅读
    val book: ReaderBookDto? = null,
    val chapters: List<ReaderChapterDto> = emptyList(),
    val chapterIndex: Int = 0,
    val content: ReaderChapterContentDto? = null,
    val paragraphs: List<ReaderParagraph> = emptyList(),
    val chapterLoading: Boolean = false,
    val chapterError: String? = null,
    val chapterNotice: String? = null,
    val restorePage: Int = 0,
    val tocOpen: Boolean = false,
    val settingsOpen: Boolean = false,
    val infoOpen: Boolean = false,
    val tocRebuilding: Boolean = false,
    val removeBusy: Boolean = false,
    val removeError: String? = null,

    // 听书
    val audio: ReaderAudioState = ReaderAudioState(),
    /** null 表示沿用当前播放意图；false 表示用户明确要求准备后暂停。 */
    val audioPlayRequested: Boolean? = null,
    val audioCreditsOpen: Boolean = false,
    val audioCreditsSaving: Boolean = false,
    val audioCreditsError: String? = null,

    // 换源
    val originPickerOpen: Boolean = false,
    val originLoading: Boolean = false,
    val originApplying: Boolean = false,
    val originCandidates: List<ReaderSearchOriginDto> = emptyList(),
    val originError: String? = null,

    // 段评与宿主浏览器
    val browserPage: ReaderBrowserPageDto? = null,
    val pendingBrowsers: List<ReaderBrowserPageDto> = emptyList(),

    // 书源管理
    val sources: List<ReaderSourceDto> = emptyList(),
    val sourcesLoaded: Boolean = false,
    val sourcesLoading: Boolean = false,
    val sourcesError: String? = null,
    val sourcesNotice: String? = null,
    /** 正在提交启停/删除的书源 ID：按钮与开关据此禁用，避免重复请求。 */
    val sourcePendingIds: Set<String> = emptySet(),
    val sourceImportOpen: Boolean = false,
    val sourceImportText: String = "",
    val sourceImportFileName: String? = null,
    val sourceImportFileSize: Long = 0,
    /** 从文件读入的原始内容（不放进可编辑输入框，避免大 JSON 卡顿）。 */
    val sourceImportPayload: String? = null,
    val sourceImporting: Boolean = false,
    val sourceImportError: String? = null,
    val sourceDebugId: String? = null,
    val sourceDebugKey: String = "",
    val sourceDebugRunning: Boolean = false,
    val sourceDebugLogs: List<String>? = null,
    /** 本次会话里确认过的书源登录态（id → 是否已登录），用于列表上的登录标记。 */
    val sourceLoggedIn: Map<String, Boolean> = emptyMap(),

    // 书源登录
    val currentSource: ReaderSourceDto? = null,
    val sourceLoginOpen: Boolean = false,
    val sourceLoginLoading: Boolean = false,
    val sourceLoginBusy: Boolean = false,
    val sourceLogin: ReaderSourceLoginDto? = null,
    val sourceLoginFields: Map<String, String> = emptyMap(),
    val sourceLoginError: String? = null,
    val sourceLoginNotice: String? = null,

    // 离线缓存
    val offlineOnly: Boolean = false,
    val progressNotice: String? = null,
    val partialCachedChapters: Set<Int> = emptySet(),
    val cacheFailedChapters: Set<Int> = emptySet(),
    val cachedChapters: Set<Int> = emptySet(),
    val cacheBytes: Long = 0,
    val cacheBusy: Boolean = false,
    val cacheNotice: String? = null,
    val cacheProgress: String = "",
) {
    val chapterTitle: String
        get() = chapters.getOrNull(chapterIndex)?.title.orEmpty()

    val hasPreviousChapter: Boolean
        get() = ReaderChapters.previousReadable(chapters, chapterIndex) != null

    val hasNextChapter: Boolean
        get() = ReaderChapters.nextReadable(chapters, chapterIndex) != null

    /**
     * 详情页当前书源对应的书架记录。换源前先按「书源 + 书本地址」精确匹配；
     * 只有缺少书源地址时才退回书名 + 作者，避免把另一个源的书当成同一本。
     */
    val detailShelfBook: ReaderBookDto?
        get() {
            val detail = searchDetail ?: return null
            val origin = detailOrigin
            if (origin != null && origin.bookUrl.isNotBlank()) {
                books.firstOrNull {
                    it.bookUrl == origin.bookUrl &&
                        (origin.origin.isBlank() || it.origin == origin.origin)
                }?.let { return it }
                return null
            }
            return books.firstOrNull {
                it.bookUrl == detail.bookUrl ||
                    (it.name == detail.name && it.author == detail.author)
            }
        }
}

/** 清空搜索结果详情相关的临时状态，离开详情页或回到书架时复用。 */
private fun ReaderUiState.clearDetail(): ReaderUiState = copy(
    searchDetail = null,
    detailOrigin = null,
    detailInfo = null,
    detailInfoLoading = false,
    detailInfoError = null,
    detailChapters = emptyList(),
    detailChaptersLoading = false,
    detailChaptersError = null,
    detailOriginPickerOpen = false,
    detailOriginLoading = false,
    detailOriginError = null,
    detailOrigins = emptyList(),
)

/**
 * 阅读模块状态机：书架 → 正文 → 目录/设置 → 退出恢复。
 *
 * 服务端仍是书源解析和进度的权威：章节缓存为空时会按书源重新抓目录再落库；
 * 阅读位置在滚动停止 1.5 秒、切章和退出时上报，单位与网页一致（文本页码、
 * 漫画图片序号）。
 */
class ReaderViewModel(
    private val repo: ReaderRepository,
    private val scope: AccountScope,
    private val preferences: ReaderPreferences,
    private val cache: ReaderCacheStore,
    private val audio: ReaderAudioEngine,
    private val checkpoints: ReaderAudioCheckpointStore,
    private val origin: String,
    private val progressStore: ReaderProgressStore,
    private val offlineOnly: Boolean = false,
) : ViewModel() {

    private val progressGeneration = progressStore.generation(scope)

    /** 退出登录会提升缓存代次；旧任务（含自动缓存）此后不得再写盘。 */
    private val cacheAccountGeneration = cache.generation(scope).account

    private fun cacheWritable(): Boolean = cache.generation(scope).account == cacheAccountGeneration
    private val progressSync = ReaderProgressSync(
        progressStore, scope,
        loadBooks = { repo.shelf(fresh = true) },
        send = {
            if (progressStore.generation(scope) != progressGeneration) ApiResult.Stale
            else repo.saveProgress(it.bookId, it.chapterIndex, it.position, it.chapterTitle)
        },
    )
    private var lastProgressTime = 0L

    private val _state = MutableStateFlow(
        ReaderUiState(searchScopeIds = preferences.loadSearchScope(scope.serverId), offlineOnly = offlineOnly),
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var contentJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var progressJob: Job? = null
    private var profileSaveJob: Job? = null
    private var browserPollJob: Job? = null
    private var cacheJob: Job? = null
    private var cacheTaskToken = 0L
    private var detailTocToken = 0
    private var pendingChapterUrl: String? = null
    private var pendingChapterIndex: Int? = null
    private var loadToken = 0L
    private var currentPage = 0
    private var styleTouched = false
    private var serverBooks: Map<String, ReaderBookDto> = emptyMap()

    init {
        preferences.load(scope)?.let { cached ->
            _state.update { it.copy(style = cached) }
        }
        viewModelScope.launch {
            audio.state.collect { audioState ->
                _state.update { current ->
                    val sameBook = current.book?.id == audioState.bookId
                    val chapterIndex = if (
                        sameBook &&
                        !current.chapterLoading &&
                        current.content?.type == "audio" &&
                        audioState.chapterIndex >= 0
                    ) {
                        audioState.chapterIndex
                    } else {
                        current.chapterIndex
                    }
                    current.copy(audio = audioState, chapterIndex = chapterIndex)
                }
            }
        }
        loadShelf()
        if (!offlineOnly) {
            loadProfile()
            viewModelScope.launch {
                while (isActive) {
                    syncPendingProgress()
                    delay(30_000L)
                }
            }
        }
    }

    // ── 书架 ──

    fun ensureShelfLoaded() {
        val current = _state.value
        if (current.books.isEmpty() && !current.shelfLoading && current.shelfError == null) {
            loadShelf()
        }
    }

    fun refreshShelf() {
        if (_state.value.shelfLoading) return
        loadShelf()
    }

    fun selectGroup(name: String?) {
        _state.update { it.copy(selectedGroup = name) }
    }

    fun setShelfSort(sort: String) {
        updateStyle(_state.value.style.copy(shelfSort = sort))
    }

    /** 书架布局：与服务端 `shelf_layout` 共用，网页端改过这里也会跟着变。 */
    fun setShelfLayout(layout: String) {
        updateStyle(_state.value.style.copy(shelfLayout = layout))
    }

    fun setShelfGridColumns(columns: Int) {
        updateStyle(_state.value.style.copy(shelfGridColumns = columns))
    }

    fun setShelfShowUnread(show: Boolean) {
        updateStyle(_state.value.style.copy(shelfShowUnread = show))
    }

    fun setShelfShowUpdateTime(show: Boolean) {
        updateStyle(_state.value.style.copy(shelfShowUpdateTime = show))
    }

    // ── 书架：移出书籍 ──

    /** 请求移出书架：先弹二次确认，文案按本地书 / 引用书 / 书源书区分。 */
    fun requestShelfRemove(book: ReaderBookDto) {
        if (_state.value.shelfRemoveBusy) return
        _state.update {
            it.copy(shelfRemoveTarget = book, shelfRemoveError = null)
        }
    }

    fun cancelShelfRemove() {
        if (_state.value.shelfRemoveBusy) return
        _state.update { it.copy(shelfRemoveTarget = null, shelfRemoveError = null) }
    }

    fun confirmShelfRemove() {
        val current = _state.value
        val book = current.shelfRemoveTarget ?: return
        if (current.shelfRemoveBusy) return
        _state.update { it.copy(shelfRemoveBusy = true, shelfRemoveError = null) }
        viewModelScope.launch {
            when (val result = repo.removeBook(book.id)) {
                is ApiResult.Success -> {
                    // 本地缓存、听书检查点和服务端进度一起清掉，重新加入时从头开始。
                    checkpoints.clear(scope, book.id)
                    withContext(Dispatchers.IO) {
                        progressStore.clear(scope, book.id)
                        cache.clear(scope, book.id)
                    }
                    if (audio.state.value.bookId == book.id) audio.stop()
                    _state.update { state ->
                        state.copy(
                            shelfRemoveBusy = false,
                            shelfRemoveTarget = null,
                            shelfRemoveError = null,
                            books = state.books.filterNot { it.id == book.id },
                            shelfNotice = "《${book.name}》已移出书架",
                        )
                    }
                    // 服务端还会同步清理分组引用与进度，重新拉一次保持两端一致。
                    loadShelf()
                }
                is ApiResult.Failure -> _state.update {
                    DiagnosticsLog.w(
                        "Reader",
                        "移出书架失败 book=${book.name} HTTP ${result.status} ${result.code}",
                    )
                    it.copy(shelfRemoveBusy = false, shelfRemoveError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(shelfRemoveBusy = false, shelfRemoveError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update {
                    it.copy(shelfRemoveBusy = false, shelfRemoveError = "请求已失效，请重试")
                }
            }
        }
    }

    private fun loadShelf() {
        if (_state.value.shelfLoading) return
        _state.update { it.copy(shelfLoading = true, shelfError = null) }
        viewModelScope.launch {
            if (offlineOnly) {
                val books = withContext(Dispatchers.IO) { cache.offlineShelf(scope) }
                serverBooks = books.associateBy { it.id }
                val merged = withContext(Dispatchers.IO) { mergeLocalAudioProgress(books) }
                _state.update { it.copy(
                    shelfLoading = false, books = merged,
                    shelfNotice = "离线模式：仅可阅读完整缓存的文本和漫画，进度保存在本机",
                ) }
                return@launch
            }
            val booksResult = repo.shelf(fresh = true)
            val groupsResult = if (_state.value.groupsLoaded) null else repo.bookGroups()
            val remoteBooks = (booksResult as? ApiResult.Success)?.value
            if (remoteBooks != null) serverBooks = remoteBooks.associateBy { it.id }
            val mergedShelf = remoteBooks?.let { books ->
                withContext(Dispatchers.IO) { mergeLocalAudioProgress(books) }
            }
            val cachedShelf = if (remoteBooks != null) {
                withContext(Dispatchers.IO) {
                    runCatching { cache.saveShelf(scope, remoteBooks) }
                        .onFailure { DiagnosticsLog.w("Reader", "离线书架快照保存失败", it) }
                }
                null
            } else if (booksResult is ApiResult.NetworkError ||
                (booksResult is ApiResult.Failure && booksResult.status >= 500)
            ) {
                withContext(Dispatchers.IO) { cache.readShelf(scope) }?.let { books ->
                    serverBooks = books.associateBy { it.id }
                    withContext(Dispatchers.IO) { mergeLocalAudioProgress(books) }
                }
            } else null
            val error = when {
                cachedShelf != null -> null
                booksResult is ApiResult.Failure -> readerError(booksResult)
                booksResult is ApiResult.NetworkError -> "网络不可用，请稍后重试"
                else -> null
            }
            if (error != null) {
                DiagnosticsLog.w(
                    "Reader",
                    "书架加载失败：" + when (booksResult) {
                        is ApiResult.Failure -> "HTTP ${booksResult.status} ${booksResult.code}"
                        is ApiResult.NetworkError -> "网络错误：${booksResult.message}"
                        else -> error
                    },
                )
            }
            _state.update { current ->
                val groups = (groupsResult as? ApiResult.Success)?.value ?: current.groups
                val selectedGroup = current.selectedGroup
                    ?.takeIf { name -> groups.any { it.name == name } }
                current.copy(
                    shelfLoading = false,
                    shelfError = error,
                    shelfNotice = if (cachedShelf != null) "网络不可用，已显示离线书架" else current.shelfNotice,
                    books = mergedShelf
                        ?: cachedShelf
                        ?: current.books,
                    groups = groups,
                    groupsLoaded = current.groupsLoaded || groupsResult is ApiResult.Success,
                    selectedGroup = selectedGroup,
                )
            }
        }
    }

    /**
     * 把本地听书检查点盖到书架进度上。
     *
     * 服务端进度只在每 5 秒上报成功一次；切后台后立即回到书架时，服务端可能
     * 还是旧位置。本地更新时以本地为准，保证书架与进入书籍后的续播位置一致。
     */
    private fun mergeLocalAudioProgress(books: List<ReaderBookDto>): List<ReaderBookDto> =
        books.map { book ->
            if (!book.isAudio) return@map ReaderProgressResume.merge(book, progressStore.load(scope, book.id))
            val local = checkpoints.load(scope, book.id) ?: return@map book
            if (local.updatedAtMs <= book.durChapterTime) return@map book
            book.copy(
                durChapterIndex = local.chapterIndex,
                durChapterPos = (local.positionMs / 1_000L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt(),
                durChapterTitle = local.chapterTitle,
                durChapterTime = local.updatedAtMs,
            )
        }

    fun refreshToc() {
        if (_state.value.tocRefreshing) return
        _state.update { it.copy(tocRefreshing = true, shelfNotice = null) }
        viewModelScope.launch {
            when (val result = repo.refreshToc()) {
                is ApiResult.Success -> {
                    val value = result.value
                    _state.update {
                        it.copy(
                            tocRefreshing = false,
                            shelfNotice = "目录已更新：${value.updated} 本有更新，" +
                                "${value.unchanged} 本无变化，${value.failed} 本失败",
                        )
                    }
                    loadShelf()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(tocRefreshing = false, shelfNotice = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(tocRefreshing = false, shelfNotice = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(tocRefreshing = false) }
            }
        }
    }

    fun clearShelfNotice() {
        _state.update { it.copy(shelfNotice = null) }
    }

    // ── 阅读偏好 ──

    private fun loadProfile() {
        viewModelScope.launch {
            val result = repo.profile()
            if (result is ApiResult.Success) {
                val profile = result.value ?: return@launch
                if (styleTouched) {
                    _state.update { it.copy(serverProfile = profile) }
                    return@launch
                }
                val style = ReaderStyle.fromProfile(profile)
                preferences.save(scope, style)
                _state.update { it.copy(style = style, serverProfile = profile) }
            }
        }
    }

    fun updateStyle(next: ReaderStyle) {
        val sanitized = next.sanitized()
        styleTouched = true
        preferences.save(scope, sanitized)
        _state.update { it.copy(style = sanitized) }
        if (offlineOnly) return
        profileSaveJob?.cancel()
        profileSaveJob = viewModelScope.launch {
            // 拖动滑块时连续提交会打满服务端；停顿后再同步一次完整偏好。
            delay(PROFILE_SYNC_DELAY_MS)
            val profile = sanitized.mergeInto(_state.value.serverProfile)
            when (val result = repo.saveProfile(profile)) {
                is ApiResult.Success -> _state.update {
                    it.copy(serverProfile = result.value ?: profile)
                }
                else -> Unit
            }
        }
    }

    fun resetStyle() {
        // 「恢复默认」只重置阅读排版；书架布局/排序/显示项是独立偏好，保持不动。
        val shelf = _state.value.style
        updateStyle(
            ReaderStyle.DEFAULT.copy(
                shelfLayout = shelf.shelfLayout,
                shelfGridColumns = shelf.shelfGridColumns,
                shelfSort = shelf.shelfSort,
                shelfShowUnread = shelf.shelfShowUnread,
                shelfShowUpdateTime = shelf.shelfShowUpdateTime,
            ),
        )
    }

    // ── 搜索 ──

    fun openSearch() {
        detailJob?.cancel()
        detailTocToken += 1
        _state.update {
            it.clearDetail().copy(screen = ReaderScreen.Search, searchError = null, addBookError = null)
        }
        // 搜索范围需要书源名与启用状态；已加载过则不会重复请求。
        loadSources()
    }

    fun closeSearch() {
        searchJob?.cancel()
        detailJob?.cancel()
        detailTocToken += 1
        _state.update {
            it.clearDetail().copy(
                screen = ReaderScreen.Shelf,
                addBookError = null,
                searchLoading = false,
                searchLoadingMore = false,
                searchScopeOpen = false,
                searchScopeDraft = null,
            )
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, searchError = null) }
    }

    // ── 搜索范围（对应网页端 SearchScopeDialog） ──

    fun openSearchScope() {
        if (_state.value.searchScopeOpen) return
        _state.update {
            it.copy(
                searchScopeOpen = true,
                searchScopeDraft = ReaderSearchScope.initialSelection(
                    it.searchScopeIds,
                    it.sources,
                ),
            )
        }
        // 书源列表用于分组与启用状态；已加载过就不重复请求。
        loadSources()
    }

    fun closeSearchScope() {
        _state.update { it.copy(searchScopeOpen = false, searchScopeDraft = null) }
    }

    /** 弹层里的勾选变化；null 以外的值都是用户实际勾选的结果。 */
    fun setSearchScopeDraft(ids: Set<String>) {
        _state.update { it.copy(searchScopeDraft = ids) }
    }

    /** 确认范围：全部启用书源时存空集合，与服务端「空 = 全部」语义一致。 */
    fun confirmSearchScope() {
        val current = _state.value
        val selected = current.searchScopeDraft
            ?: ReaderSearchScope.enabledIds(current.sources).toSet()
        val saved = ReaderSearchScope.confirmSelection(selected, current.sources)
        preferences.saveSearchScope(scope.serverId, saved)
        val rerun = current.searchPage > 0 && current.query.trim().isNotEmpty()
        _state.update {
            it.copy(
                searchScopeOpen = false,
                searchScopeDraft = null,
                searchScopeIds = saved,
            )
        }
        // 已经搜过一轮时立刻按新范围重搜，避免旧结果让人以为范围没生效。
        if (rerun) submitSearch()
    }

    /** 当前搜索应携带的 `source_ids`；全部范围返回 null（省略字段）。 */
    private fun searchSourceIds(state: ReaderUiState = _state.value): List<String>? =
        ReaderSearchScope.requestIds(state.searchScopeIds, state.sources)

    fun submitSearch() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(searchError = "请输入书名或作者") }
            return
        }
        searchJob?.cancel()
        _state.update {
            it.copy(
                searchLoading = true,
                searchError = null,
                searchBooks = emptyList(),
                searchSkipped = emptyList(),
                searchPage = 0,
            )
        }
        searchJob = viewModelScope.launch {
            when (val result = repo.search(query, page = 1, sourceIds = searchSourceIds())) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        searchLoading = false,
                        searchBooks = result.value.books,
                        searchSkipped = result.value.skipped,
                        searchPage = 1,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    DiagnosticsLog.w(
                        "Reader",
                        "书源搜索失败 query=$query HTTP ${result.status} ${result.code}",
                    )
                    it.copy(searchLoading = false, searchError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    DiagnosticsLog.w("Reader", "书源搜索网络错误 query=$query", result.cause)
                    it.copy(searchLoading = false, searchError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(searchLoading = false) }
            }
        }
    }

    fun loadMoreSearch() {
        val current = _state.value
        val query = current.query.trim()
        if (query.isEmpty() || current.searchLoading || current.searchLoadingMore) return
        if (current.searchPage <= 0) return
        val nextPage = current.searchPage + 1
        _state.update { it.copy(searchLoadingMore = true, searchError = null) }
        viewModelScope.launch {
            when (val result = repo.search(query, page = nextPage, sourceIds = searchSourceIds())) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(
                        searchLoadingMore = false,
                        searchPage = nextPage,
                        searchBooks = mergeSearchResults(state.searchBooks, result.value.books),
                        searchSkipped = mergeSkipped(state.searchSkipped, result.value.skipped),
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(searchLoadingMore = false, searchError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(searchLoadingMore = false, searchError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(searchLoadingMore = false) }
            }
        }
    }

    // ── 书籍详情（搜索结果，仿 legado BookInfoActivity） ──

    /**
     * 打开书籍详情：不加入书架也能看封面、简介、最新章节和完整目录。
     * 详情规则和目录并行抓取；详情返回的 tocUrl 与书本地址不同时再拉一次目录。
     */
    fun openSearchDetail(book: ReaderSearchBookDto) {
        ensureShelfLoaded()
        val origin = book.origins?.firstOrNull { it.originType == 0 }
            ?: book.origins?.firstOrNull()
        detailJob?.cancel()
        detailTocToken += 1
        _state.update {
            it.clearDetail().copy(
                screen = ReaderScreen.BookDetail,
                searchDetail = book,
                addBookError = null,
                detailOrigin = origin,
                detailInfoLoading = origin != null,
                detailChaptersLoading = origin != null,
                detailChaptersError = if (origin == null) "该书没有可用书源，请换一个结果" else null,
            )
        }
        if (origin != null) startDetailLoad(book, origin)
    }

    fun dismissSearchDetail() {
        if (_state.value.addingBook) return
        detailJob?.cancel()
        detailTocToken += 1
        _state.update {
            it.clearDetail().copy(screen = ReaderScreen.Search, addBookError = null)
        }
    }

    /** 详情页右上角刷新：重抓详情与目录，保留当前书源。 */
    fun refreshSearchDetail() {
        val current = _state.value
        val book = current.searchDetail ?: return
        val origin = current.detailOrigin ?: return
        detailJob?.cancel()
        _state.update {
            it.copy(
                detailInfoLoading = true,
                detailInfoError = null,
                detailChaptersLoading = true,
                detailChaptersError = null,
            )
        }
        startDetailLoad(book, origin)
    }

    fun closeDetailOriginPicker() {
        if (_state.value.addingBook) return
        _state.update { it.copy(detailOriginPickerOpen = false) }
    }

    /** 换源候选：按书名重新搜索，列出手机其它书源（与阅读页换源一致）。 */
    fun openDetailOriginPicker() {
        val current = _state.value
        val book = current.searchDetail ?: return
        if (current.detailOriginLoading) return
        _state.update {
            it.copy(
                detailOriginPickerOpen = true,
                detailOriginLoading = true,
                detailOrigins = emptyList(),
                detailOriginError = null,
            )
        }
        viewModelScope.launch {
            when (val result = repo.search(book.name, page = 1)) {
                is ApiResult.Success -> {
                    val author = (_state.value.detailInfo?.author ?: book.author).trim()
                    val hit = result.value.books.firstOrNull {
                        it.name.trim() == book.name.trim() && author.isNotEmpty() && it.author == author
                    } ?: result.value.books.firstOrNull { it.name.trim() == book.name.trim() }
                    val selected = _state.value.detailOrigin
                    val candidates = hit?.origins.orEmpty()
                        .filterNot {
                            selected != null &&
                                it.origin == selected.origin &&
                                it.bookUrl == selected.bookUrl
                        }
                        .distinctBy { "${it.origin}\u0000${it.bookUrl}" }
                    _state.update {
                        it.copy(
                            detailOriginLoading = false,
                            detailOrigins = candidates,
                            detailOriginError = if (candidates.isEmpty()) {
                                "没有找到其他可用书源"
                            } else {
                                null
                            },
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(detailOriginLoading = false, detailOriginError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(detailOriginLoading = false, detailOriginError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(detailOriginLoading = false) }
            }
        }
    }

    /**
     * 详情页换源：已在书架的书走服务端换源（保留进度、清空旧源缓存），
     * 没上架的书只切换详情页展示的书源。
     */
    fun selectDetailOrigin(origin: ReaderSearchOriginDto) {
        val current = _state.value
        val book = current.searchDetail ?: return
        if (current.addingBook) return
        val shelf = current.detailShelfBook
        _state.update { it.copy(detailOriginPickerOpen = false, detailOriginError = null) }
        if (shelf == null) {
            applyDetailOrigin(book, origin)
            return
        }
        _state.update { it.copy(addingBook = true, addBookError = null) }
        viewModelScope.launch {
            when (val result = repo.switchOrigin(shelf.id, origin)) {
                is ApiResult.Success -> {
                    val saved = result.value
                    withContext(Dispatchers.IO) { cache.clear(scope, shelf.id) }
                    _state.update {
                        it.copy(
                            addingBook = false,
                            books = it.books.map { item -> if (item.id == saved.id) saved else item },
                        )
                    }
                    applyDetailOrigin(book, origin)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(addingBook = false, addBookError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(addingBook = false, addBookError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(addingBook = false) }
            }
        }
    }

    /** 加入书架但不跳转阅读（详情页的「加入书架」）。 */
    fun addDetailBookToShelf() {
        addDetailBook(startReading = false, chapter = null)
    }

    /** 已在书架的书再点「加入书架」（电视端旧入口）直接进入阅读。 */
    fun addSearchBookToShelf() {
        startReadingFromDetail()
    }

    /**
     * 从详情页开始阅读：没上架就先加入书架；带 chapter 时按章节地址跳到该章。
     */
    fun startReadingFromDetail(chapter: ReaderTocChapterDto? = null) {
        val current = _state.value
        val shelf = current.detailShelfBook
        if (shelf != null) {
            detailJob?.cancel()
            openBook(
                shelf,
                startChapterUrl = chapter?.takeIf { !it.isVolume }?.url,
                startChapterIndex = chapter?.takeIf { !it.isVolume }?.index,
            )
            return
        }
        addDetailBook(startReading = true, chapter = chapter)
    }

    /** 从详情页移出书架，页面停留在详情，按钮随即变回「加入书架」。 */
    fun removeDetailBookFromShelf() {
        val current = _state.value
        val saved = current.detailShelfBook ?: return
        if (current.addingBook) return
        _state.update { it.copy(addingBook = true, addBookError = null) }
        viewModelScope.launch {
            when (val result = repo.removeBook(saved.id)) {
                is ApiResult.Success -> {
                    withContext(Dispatchers.IO) { cache.clear(scope, saved.id) }
                    _state.update {
                        it.copy(
                            addingBook = false,
                            books = it.books.filterNot { book -> book.id == saved.id },
                            shelfNotice = "《${saved.name}》已移出书架",
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(addingBook = false, addBookError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(addingBook = false, addBookError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(addingBook = false) }
            }
        }
    }

    private fun applyDetailOrigin(book: ReaderSearchBookDto, origin: ReaderSearchOriginDto) {
        detailJob?.cancel()
        detailTocToken += 1
        _state.update {
            it.copy(
                screen = ReaderScreen.BookDetail,
                searchDetail = book,
                detailOrigin = origin,
                detailInfo = null,
                detailInfoLoading = true,
                detailInfoError = null,
                detailChapters = emptyList(),
                detailChaptersLoading = true,
                detailChaptersError = null,
                detailOriginPickerOpen = false,
            )
        }
        startDetailLoad(book, origin)
    }

    private fun startDetailLoad(book: ReaderSearchBookDto, origin: ReaderSearchOriginDto) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            launch { loadDetailInfo(book, origin) }
            launch { loadDetailChapters(origin, origin.bookUrl) }
        }
    }

    private suspend fun loadDetailInfo(book: ReaderSearchBookDto, origin: ReaderSearchOriginDto) {
        _state.update { it.copy(detailInfoLoading = true, detailInfoError = null) }
        when (val result = repo.bookInfo(origin.sourceId, origin.origin, origin.bookUrl)) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        detailInfoLoading = false,
                        detailInfo = result.value,
                        detailInfoError = null,
                    )
                }
                val tocUrl = result.value.tocUrl.trim()
                // 详情里的目录地址更准确：与书本地址不同时用它重新拉一次目录。
                if (tocUrl.isNotEmpty() && tocUrl != origin.bookUrl) {
                    loadDetailChapters(origin, tocUrl)
                }
            }
            is ApiResult.Failure -> _state.update {
                DiagnosticsLog.w(
                    "Reader",
                    "书籍详情失败 book=${book.name} HTTP ${result.status} ${result.code}",
                )
                // 旧服务端没有 /book-info 时返回 404：搜索信息已经够用，不打断用户。
                it.copy(
                    detailInfoLoading = false,
                    detailInfoError = if (result.status == 404) null else readerError(result),
                )
            }
            is ApiResult.NetworkError -> _state.update {
                it.copy(detailInfoLoading = false, detailInfoError = "网络不可用，请稍后重试")
            }
            ApiResult.Stale -> _state.update { it.copy(detailInfoLoading = false) }
        }
    }

    private suspend fun loadDetailChapters(origin: ReaderSearchOriginDto, tocUrl: String) {
        detailTocToken += 1
        val token = detailTocToken
        _state.update { it.copy(detailChaptersLoading = true, detailChaptersError = null) }
        when (val result = repo.toc(origin.origin, origin.bookUrl, tocUrl, origin.sourceId)) {
            is ApiResult.Success -> if (token == detailTocToken) {
                _state.update {
                    it.copy(
                        detailChaptersLoading = false,
                        detailChapters = result.value,
                        detailChaptersError = null,
                    )
                }
            }
            is ApiResult.Failure -> if (token == detailTocToken) {
                DiagnosticsLog.w(
                    "Reader",
                    "详情目录失败 origin=${origin.originName} HTTP ${result.status} ${result.code}",
                )
                _state.update {
                    it.copy(detailChaptersLoading = false, detailChaptersError = readerError(result))
                }
            }
            is ApiResult.NetworkError -> if (token == detailTocToken) {
                _state.update {
                    it.copy(detailChaptersLoading = false, detailChaptersError = "网络不可用，请稍后重试")
                }
            }
            ApiResult.Stale -> if (token == detailTocToken) {
                _state.update { it.copy(detailChaptersLoading = false) }
            }
        }
    }

    private fun addDetailBook(startReading: Boolean, chapter: ReaderTocChapterDto?) {
        val current = _state.value
        val detail = current.searchDetail ?: return
        if (current.addingBook) return
        val origin = current.detailOrigin
            ?: detail.origins?.firstOrNull { it.originType == 0 }
            ?: detail.origins?.firstOrNull()
        if (origin == null) {
            _state.update { it.copy(addBookError = "该书没有可用书源，请换一个结果") }
            return
        }
        val info = current.detailInfo
        val name = info?.name?.takeIf { it.isNotBlank() } ?: detail.name
        val author = info?.author?.takeIf { it.isNotBlank() } ?: detail.author
        val cover = info?.coverUrl?.takeIf { it.isNotBlank() } ?: detail.coverUrl
        _state.update { it.copy(addingBook = true, addBookError = null) }
        viewModelScope.launch {
            when (val result = repo.addBook(origin, name, author, cover)) {
                is ApiResult.Success -> {
                    val saved = result.value
                    _state.update { state ->
                        // 详情页已经有目录时，顺手把章数写进本地书架记录，
                        // 回到书架不必等后台预热完成才显示「共 N 章」。
                        val merged = saved.copy(
                            totalChapterNum = maxOf(saved.totalChapterNum, state.detailChapters.size),
                            latestChapterTitle = saved.latestChapterTitle.ifBlank {
                                state.detailInfo?.latestChapter.orEmpty()
                            },
                        )
                        state.copy(
                            addingBook = false,
                            addBookError = null,
                            books = state.books.filterNot { book -> book.id == saved.id } + merged,
                            shelfNotice = if (startReading) {
                                state.shelfNotice
                            } else {
                                "《${saved.name}》已加入书架"
                            },
                        )
                    }
                    if (startReading) {
                        detailJob?.cancel()
                        openBook(
                            saved,
                            startChapterUrl = chapter
                                ?.takeIf { !it.isVolume && it.url.isNotBlank() }
                                ?.url,
                            startChapterIndex = chapter
                                ?.takeIf { !it.isVolume }
                                ?.index,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(addingBook = false, addBookError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(addingBook = false, addBookError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(addingBook = false) }
            }
        }
    }

    // ── 正文 ──

    fun openBook(
        book: ReaderBookDto,
        startChapterUrl: String? = null,
        startChapterIndex: Int? = null,
    ) {
        if (offlineOnly && book.type !in setOf(0, 2)) return
        saveProgressNow()
        cancelCacheDownload()
        loadToken += 1
        val token = loadToken
        pendingChapterUrl = startChapterUrl
        pendingChapterIndex = startChapterIndex
        contentJob?.cancel()
        cacheJob?.cancel()
        // 服务端进度可能落后于本地最后几秒；只在不指定起始章节时应用本地检查点。
        val localResume = if (
            book.isAudio &&
            startChapterUrl == null &&
            startChapterIndex == null
        ) {
            checkpoints.load(scope, book.id)
                ?.takeIf { it.updatedAtMs > book.durChapterTime }
        } else {
            null
        }
        val initialChapter = localResume?.chapterIndex ?: book.durChapterIndex.coerceAtLeast(0)
        val initialPage = when {
            localResume != null ->
                (localResume.positionMs / 1_000L).coerceAtLeast(0L).toInt()
            book.hasProgress -> book.durChapterPos
            else -> 0
        }
        _state.update {
            it.clearDetail().copy(
                screen = ReaderScreen.Reading,
                book = book,
                chapters = emptyList(),
                chapterIndex = initialChapter,
                content = null,
                paragraphs = emptyList(),
                chapterLoading = true,
                chapterError = null,
                chapterNotice = null,
                restorePage = initialPage,
                tocOpen = false,
                settingsOpen = false,
                infoOpen = false,
                removeError = null,
                originPickerOpen = false,
                originCandidates = emptyList(),
                originError = null,
                sourceLoginOpen = false,
                sourceLogin = null,
                browserPage = null,
                cacheNotice = null,
                cacheProgress = "",
                cacheBusy = false,
                cachedChapters = emptySet(),
                partialCachedChapters = emptySet(),
                cacheFailedChapters = emptySet(),
                cacheBytes = 0,
                audioPlayRequested = null,
                audioCreditsOpen = false,
                audioCreditsError = null,
            )
        }
        refreshCacheStatus(book.id)
        viewModelScope.launch { openBookInternal(book, token) }
    }

    /**
     * 首页悬浮条的有声书入口：打开播放页并直接开始播放。
     *
     * 这个意图要在 [openBook] 之后写入，因为 openBook 会重置 `audioPlayRequested`；
     * 章节加载完成时 `applyChapterContent` 会读取它并自动起播。
     */
    fun openBookAndListen(book: ReaderBookDto) {
        openBook(book)
        _state.update { it.copy(audioPlayRequested = true) }
    }

    private suspend fun openBookInternal(book: ReaderBookDto, token: Long) {
        val loaded = loadChapters(book)
        if (token != loadToken) return
        if (loaded.chapters.isEmpty()) {
            DiagnosticsLog.w(
                "Reader",
                "打开书籍失败 book=${book.id} title=${book.name} 原因=${loaded.error ?: "目录为空"}",
            )
            _state.update {
                it.copy(
                    chapterLoading = false,
                    chapterError = loaded.error ?: "目录为空，请刷新目录后重试",
                )
            }
            return
        }
        withContext(Dispatchers.IO) {
            runCatching { cache.saveChapters(scope, book.id, loaded.chapters) }
                .onFailure { DiagnosticsLog.w("Reader", "离线目录保存失败 book=${book.id}", it) }
        }
        audio.updateChapters(book.id, loaded.chapters)
        // 详情页目录点章：优先按章节地址定位，地址找不到时按详情目录序号，最后回到书架进度。
        val requestedUrl = pendingChapterUrl
        val requestedHint = pendingChapterIndex
        pendingChapterUrl = null
        pendingChapterIndex = null
        val urlIndex = requestedUrl
            ?.let { url -> loaded.chapters.indexOfFirst { it.url == url && !it.isVolume } }
            ?.takeIf { it >= 0 }
        val hintIndex = requestedHint
            ?.takeIf { it in loaded.chapters.indices && !loaded.chapters[it].isVolume }
        val requestedIndex = urlIndex ?: hintIndex
        // 本地检查点只在用户没有明确点某一章时参与，且必须比服务端进度新。
        val resume = if (requestedIndex == null && book.isAudio) {
            ReaderAudioResume.choose(
                bookId = book.id,
                serverChapterIndex = book.durChapterIndex,
                serverPositionSec = book.durChapterPos,
                serverUpdatedAtMs = book.durChapterTime,
                local = checkpoints.load(scope, book.id),
                chapters = loaded.chapters,
            )
        } else {
            null
        }
        val serverBook = serverBooks[book.id] ?: book
        val readingBook = if (book.isAudio) book else withContext(Dispatchers.IO) {
            ReaderProgressResume.merge(serverBook, progressStore.load(scope, book.id), loaded.chapters)
        }
        val index = requestedIndex
            ?: resume?.chapterIndex
            ?: ReaderChapters.initialIndex(readingBook, loaded.chapters)
        val page = when {
            requestedIndex != null -> 0
            resume != null -> resume.positionSec
            index == readingBook.durChapterIndex -> readingBook.durChapterPos
            else -> 0
        }
        _state.update {
            it.copy(
                chapters = loaded.chapters,
                chapterIndex = index,
                restorePage = page,
                chapterError = null,
                chapterNotice = loaded.error,
            )
        }
        loadChapterContent(index, token, page, loaded.error)
    }

    private suspend fun loadChapters(book: ReaderBookDto): ChapterLoad {
        var cachedEmptyMessage: String? = null
        val cachedChapters = withContext(Dispatchers.IO) { cache.readChapters(scope, book.id) }
        if (offlineOnly) return ChapterLoad(cachedChapters.orEmpty(),
            if (cachedChapters == null) "本机没有缓存目录，请联网后下载" else "已加载离线目录")
        when (val result = repo.chapters(book.id)) {
            is ApiResult.Success -> {
                if (result.value.isNotEmpty() || book.isLocal) {
                    return ChapterLoad(result.value)
                }
            }
            is ApiResult.Failure -> {
                if (result.status != 400) {
                    return if (cachedChapters != null) {
                        ChapterLoad(cachedChapters, "已加载离线目录")
                    } else {
                        ChapterLoad(emptyList(), readerError(result))
                    }
                }
                cachedEmptyMessage = readerError(result)
            }
            is ApiResult.NetworkError -> {
                return if (cachedChapters != null) {
                    ChapterLoad(cachedChapters, "网络不可用，已加载离线目录")
                } else {
                    ChapterLoad(emptyList(), "网络不可用，请稍后重试")
                }
            }
            ApiResult.Stale -> return ChapterLoad(emptyList(), "请求已失效，请重试")
        }
        if (book.isLocal || book.origin.isBlank()) {
            return ChapterLoad(emptyList(), cachedEmptyMessage ?: "本地书籍目录为空")
        }
        // 章节缓存为空：按书源重新抓目录并落库，之后正文接口才有章节可取。
        when (val toc = repo.toc(book.origin, book.bookUrl, book.tocUrl)) {
            is ApiResult.Success -> {
                if (toc.value.isEmpty()) {
                    return ChapterLoad(emptyList(), "书源没有返回目录，请稍后重试")
                }
                val chapters = toc.value.map {
                    ReaderChapterDto(
                        index = it.index,
                        title = it.title,
                        url = it.url,
                        isVolume = it.isVolume,
                    )
                }
                val saved = repo.saveChapters(book.id, chapters)
                val saveError = (saved as? ApiResult.Failure)?.let {
                    "目录已取回但保存失败：${readerError(it)}"
                }
                return ChapterLoad(chapters, saveError)
            }
            is ApiResult.Failure -> return ChapterLoad(
                emptyList(),
                readerError(toc).ifBlank { cachedEmptyMessage.orEmpty() },
            )
            is ApiResult.NetworkError -> return if (cachedChapters != null) {
                ChapterLoad(cachedChapters, "网络不可用，已加载离线目录")
            } else {
                ChapterLoad(emptyList(), "网络不可用，请稍后重试")
            }
            ApiResult.Stale -> return ChapterLoad(emptyList(), "请求已失效，请重试")
        }
    }

    private fun loadChapterContent(
        index: Int,
        token: Long,
        restorePage: Int,
        notice: String? = null,
    ) {
        val book = _state.value.book ?: return
        contentJob?.cancel()
        currentPage = restorePage
        _state.update {
            it.copy(
                chapterIndex = index,
                chapterLoading = true,
                chapterError = null,
                chapterNotice = notice,
                content = null,
                paragraphs = emptyList(),
                restorePage = restorePage,
                tocOpen = false,
            )
        }
        contentJob = viewModelScope.launch {
            if (offlineOnly) {
                val cached = withContext(Dispatchers.IO) {
                    if (cache.status(scope, book.id, index) == ReaderCacheStatus.COMPLETE)
                        cache.read(scope, book.id, index) else null
                }
                if (token != loadToken) return@launch
                if (cached != null && cached.type in setOf("text", "image")) {
                    applyChapterContent(book, index, cached.copy(comments = null), offline = true)
                } else _state.update { it.copy(chapterLoading = false, chapterError = "本章未完整缓存，请联网下载后再阅读") }
                return@launch
            }
            when (val result = repo.content(book.id, index)) {
                is ApiResult.Success -> {
                    if (token != loadToken) return@launch
                    applyChapterContent(book, index, result.value, offline = false)
                }
                is ApiResult.Failure -> {
                    if (token != loadToken) return@launch
                    val cached = withContext(Dispatchers.IO) {
                        cache.read(scope, book.id, index)
                    }
                    if (cached != null) {
                        applyChapterContent(book, index, cached, offline = true)
                    } else {
                        DiagnosticsLog.w(
                            "Reader",
                            "章节加载失败 book=${book.id} index=$index " +
                                "HTTP ${result.status} ${result.code}",
                        )
                        _state.update {
                            it.copy(chapterLoading = false, chapterError = readerError(result))
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    if (token != loadToken) return@launch
                    val cached = withContext(Dispatchers.IO) {
                        cache.read(scope, book.id, index)
                    }
                    if (cached != null) {
                        applyChapterContent(book, index, cached, offline = true)
                    } else {
                        DiagnosticsLog.w(
                            "Reader",
                            "章节加载网络错误 book=${book.id} index=$index ${result.message}",
                            result.cause,
                        )
                        _state.update {
                            it.copy(
                                chapterLoading = false,
                                chapterError = "网络不可用，请稍后重试",
                            )
                        }
                    }
                }
                ApiResult.Stale -> if (token == loadToken) {
                    _state.update { it.copy(chapterLoading = false) }
                }
            }
        }
    }

    private fun applyChapterContent(
        book: ReaderBookDto,
        index: Int,
        content: ReaderChapterContentDto,
        offline: Boolean,
    ) {
        val paragraphs = if (content.type == "text") {
            ReaderText.parse(content.content)
        } else {
            emptyList()
        }
        val previousNotice = _state.value.chapterNotice
        _state.update {
            it.copy(
                chapterLoading = false,
                content = content,
                paragraphs = paragraphs,
                chapterNotice = when {
                    offline -> "已加载离线缓存"
                    content.type == "text" && paragraphs.isEmpty() ->
                        "本章正文为空，可尝试切换章节或刷新目录"
                    else -> previousNotice
                },
            )
        }
        if (content.type == "text") {
            autoCacheText(book, index, content)
        }
        if (content.type == "audio") {
            audio.updateChapters(book.id, _state.value.chapters)
            val audioState = audio.state.value
            val sameBook = audioState.bookId == book.id
            val alreadyActive = sameBook &&
                audioState.chapterIndex == index &&
                (audioState.playing || audioState.loading)
            if (!alreadyActive) {
                val autoPlay = _state.value.audioPlayRequested ?: (sameBook && audioState.playWhenReady)
                _state.update { it.copy(audioPlayRequested = null) }
                audio.startChapter(
                    book = book,
                    chapters = _state.value.chapters,
                    chapterIndex = index,
                    startPositionSec = _state.value.restorePage.coerceAtLeast(0),
                    repository = repo,
                    scope = scope,
                    origin = origin,
                    autoPlay = autoPlay,
                    speed = ReaderAudioTuning.snapSpeed(
                        _state.value.serverProfile.audioSpeed.toFloat(),
                    ),
                    content = content,
                )
            } else {
                when (_state.value.audioPlayRequested) {
                    true -> if (!audioState.playWhenReady) audio.play()
                    false -> audio.pause()
                    null -> Unit
                }
                _state.update { it.copy(audioPlayRequested = null) }
            }
        } else if (audio.state.value.bookId == book.id) {
            // 同一本书从音频章节切到文本/漫画时停止音频，避免两个内容同时继续。
            audio.stop()
        }
    }

    private fun autoCacheText(book: ReaderBookDto, index: Int, content: ReaderChapterContentDto) {
        if (content.type != "text" || offlineOnly || !cacheWritable()) return
        val expected = cache.generation(scope, book.id)
        viewModelScope.launch {
            val already = withContext(Dispatchers.IO) {
                cache.hasChapter(scope, book.id, index)
            }
            if (!already) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        cache.save(scope, book.id, index, content, origin, expected)
                    }
                }
                refreshCacheStatus(book.id)
            }
        }
    }

    // ── 听书控制 ──

    fun toggleAudio() {
        val current = _state.value
        val book = current.book ?: return
        val audioState = audio.state.value
        if (audioState.bookId == book.id && audioState.chapterIndex == current.chapterIndex &&
            current.chapterError == null
        ) {
            audio.toggle()
            _state.update { it.copy(audioPlayRequested = null) }
        } else if (current.chapterLoading) {
            // 只改变加载完成后的意图，不取消并重启正在执行的目录/音轨请求。
            val requested = !current.audioPlayerFeedback().playRequested
            _state.update { it.copy(audioPlayRequested = requested) }
            if (!requested && audioState.bookId == book.id) audio.pause()
        } else {
            _state.update { it.copy(audioPlayRequested = true) }
            if (current.chapters.isEmpty()) {
                loadToken += 1
                val token = loadToken
                _state.update { it.copy(chapterLoading = true, chapterError = null) }
                viewModelScope.launch { openBookInternal(book, token) }
            } else if (current.content?.type == "audio") {
                applyChapterContent(book, current.chapterIndex, current.content, offline = false)
            } else {
                loadChapterContent(current.chapterIndex, loadToken, current.restorePage)
            }
        }
    }

    fun seekAudio(fraction: Float) {
        val duration = audio.state.value.durationMs
        if (duration <= 0) return
        audio.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
    }

    fun skipAudio(deltaMs: Long) {
        audio.seekBy(deltaMs)
    }

    fun audioNext() {
        audio.next()
    }

    fun audioPrevious() {
        audio.previous()
    }

    fun setAudioSpeed(speed: Float) {
        val safe = ReaderAudioTuning.snapSpeed(speed)
        audio.setSpeed(safe)
        saveAudioPreferences(speed = safe)
    }

    /** 与其他应用同时播放：不申请音频焦点，切换应用时不会被暂停。 */
    fun setAudioMixWithOtherApps(enabled: Boolean) {
        audio.setMixWithOtherApps(enabled)
    }

    fun setAudioTimer(minutes: Int) {
        val safe = ReaderAudioTuning.clampTimerMinutes(minutes)
        audio.setTimer(safe)
        saveAudioPreferences(timerMinutes = safe)
    }

    fun setAudioStopAtChapterEnd(enabled: Boolean) {
        audio.setStopAtChapterEnd(enabled)
    }

    fun openAudioCredits() {
        _state.update { it.copy(audioCreditsOpen = true, audioCreditsError = null) }
    }

    fun closeAudioCredits() {
        if (_state.value.audioCreditsSaving) return
        _state.update { it.copy(audioCreditsOpen = false, audioCreditsError = null) }
    }

    /** 保存本书的片头/片尾跳过秒数：服务端持久化，同时立刻作用于当前播放。 */
    fun saveAudioCredits(openSeconds: Int, closeSeconds: Int) {
        val current = _state.value
        val book = current.book ?: return
        if (current.audioCreditsSaving) return
        val open = ReaderAudioTuning.clampCreditsSeconds(openSeconds)
        val close = ReaderAudioTuning.clampCreditsSeconds(closeSeconds)
        _state.update { it.copy(audioCreditsSaving = true, audioCreditsError = null) }
        viewModelScope.launch {
            when (val result = repo.saveAudioConfig(book.id, open, close)) {
                is ApiResult.Success -> {
                    audio.updateCredits(open, close)
                    _state.update { state ->
                        val synced = state.book
                            ?.takeIf { it.id == book.id }
                            ?.copy(openCredits = open, closeCredits = close)
                            ?: state.book
                        state.copy(
                            audioCreditsSaving = false,
                            audioCreditsOpen = false,
                            audioCreditsError = null,
                            book = synced,
                            books = state.books.map {
                                if (it.id == book.id) {
                                    it.copy(openCredits = open, closeCredits = close)
                                } else {
                                    it
                                }
                            },
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(audioCreditsSaving = false, audioCreditsError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(audioCreditsSaving = false, audioCreditsError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update {
                    it.copy(audioCreditsSaving = false, audioCreditsError = "请求已失效，请重试")
                }
            }
        }
    }

    fun clearAudioMessages() {
        audio.clearMessages()
    }

    private fun saveAudioPreferences(speed: Float? = null, timerMinutes: Int? = null) {
        val base = _state.value.serverProfile
        val updated = base.copy(
            audioSpeed = speed?.let(ReaderAudioTuning::speedProfileValue) ?: base.audioSpeed,
            audioTimerMinutes = timerMinutes ?: base.audioTimerMinutes,
        )
        _state.update { it.copy(serverProfile = updated) }
        profileSaveJob?.cancel()
        profileSaveJob = viewModelScope.launch {
            delay(800)
            repo.saveProfile(updated)
        }
    }

    // ── 换源 ──

    fun openOriginPicker() {
        val current = _state.value
        val book = current.book ?: return
        if (current.originLoading) return
        _state.update {
            it.copy(
                originPickerOpen = true,
                originLoading = true,
                originCandidates = emptyList(),
                originError = null,
            )
        }
        viewModelScope.launch {
            when (val result = repo.search(book.name, page = 1)) {
                is ApiResult.Success -> {
                    val candidates = result.value.books
                        .filter {
                            it.name.trim() == book.name.trim() ||
                                (book.author.isNotBlank() && it.author == book.author)
                        }
                        .ifEmpty { result.value.books }
                        .flatMap { it.origins.orEmpty() }
                        .filter { it.origin != book.origin || it.bookUrl != book.bookUrl }
                        .distinctBy { "${it.origin}\u0000${it.bookUrl}" }
                    _state.update {
                        it.copy(
                            originLoading = false,
                            originCandidates = candidates,
                            originError = if (candidates.isEmpty()) "没有找到其他可用书源" else null,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(originLoading = false, originError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(originLoading = false, originError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(originLoading = false) }
            }
        }
    }

    fun closeOriginPicker() {
        if (_state.value.originApplying) return
        _state.update { it.copy(originPickerOpen = false, originError = null) }
    }

    fun applyOrigin(origin: ReaderSearchOriginDto) {
        val current = _state.value
        val book = current.book ?: return
        if (current.originApplying) return
        _state.update { it.copy(originApplying = true, originError = null) }
        viewModelScope.launch {
            when (val result = repo.switchOrigin(book.id, origin)) {
                is ApiResult.Success -> {
                    cacheJob?.cancel()
                    audio.stop()
                    withContext(Dispatchers.IO) {
                        progressStore.clear(scope, book.id)
                        cache.clear(scope, book.id)
                    }
                    _state.update {
                        it.copy(
                            originPickerOpen = false,
                            originApplying = false,
                            originCandidates = emptyList(),
                            cachedChapters = emptySet(),
                            cacheBytes = 0,
                        )
                    }
                    openBook(result.value)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(originApplying = false, originError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(originApplying = false, originError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(originApplying = false) }
            }
        }
    }

    // ── 段评与宿主浏览器 ──

    fun openComment(comment: ReaderContentCommentDto) {
        val current = _state.value
        val book = current.book ?: return
        if (comment.url.isBlank()) {
            _state.update { it.copy(chapterNotice = "这条评论没有可用地址") }
            return
        }
        val title = comment.label.ifBlank { "段评" }
        viewModelScope.launch {
            when (val result = repo.openComment(book.id, comment.url, title)) {
                is ApiResult.Success -> _state.update { it.copy(browserPage = result.value) }
                is ApiResult.Failure -> _state.update {
                    it.copy(chapterNotice = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(chapterNotice = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> Unit
            }
        }
    }

    fun closeBrowserPage(cancelled: Boolean = false) {
        val page = _state.value.browserPage ?: return
        if (cancelled) {
            // 取消也要回传：等待 DOM 的书源 JS 会在服务端阻塞到超时，主动解除更干净。
            viewModelScope.launch {
                repo.browserResult(page.id, "", page.targetUrl, cancelled = true)
            }
        }
        _state.update { it.copy(browserPage = null) }
    }

    fun submitBrowserResult(page: ReaderBrowserPageDto, html: String, url: String) {
        viewModelScope.launch {
            when (val result = repo.browserResult(page.id, html, url, cancelled = false)) {
                is ApiResult.Success -> _state.update {
                    it.copy(browserPage = null, sourceLoginNotice = "页面操作已提交")
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(browserPage = null, sourceLoginError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(browserPage = null, sourceLoginError = "网络不可用，页面结果未提交")
                }
                ApiResult.Stale -> _state.update { it.copy(browserPage = null) }
            }
        }
    }

    suspend fun browserXhr(
        pageId: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
    ): ApiResult<ReaderBrowserXhrResponseDto> =
        repo.browserXhr(pageId, url, method, headers, body)

    private fun startBrowserPolling(sourceId: String) {
        if (sourceId.isBlank()) return
        browserPollJob?.cancel()
        browserPollJob = viewModelScope.launch {
            while (isActive) {
                when (val result = repo.browserPending(sourceId)) {
                    is ApiResult.Success -> _state.update { current ->
                        val active = current.browserPage
                        // 服务端可能同时登记多个页面：上一个还在等用户操作，用户又点了
                        // 另一个按钮（先「切换线路」再「书源设置」就是这样）。按 seq 取
                        // 最新的那个展示——旧实现只要 active 非空就永不换页，新页面
                        // 永远打不开，表现是「书源设置」点了没反应。
                        val newest = result.value.maxByOrNull { it.seq }
                        current.copy(
                            pendingBrowsers = result.value,
                            browserPage = when {
                                newest == null -> active
                                active == null -> newest
                                newest.id == active.id -> active
                                newest.seq > active.seq -> newest
                                else -> active
                            },
                        )
                    }
                    else -> Unit
                }
                delay(1_500)
            }
        }
    }

    private fun stopBrowserPolling() {
        browserPollJob?.cancel()
        browserPollJob = null
        _state.update { it.copy(pendingBrowsers = emptyList()) }
    }

    // ── 书源管理 ──

    fun openSources() {
        _state.update {
            it.copy(
                screen = ReaderScreen.Sources,
                sourcesError = null,
                sourcesNotice = null,
                sourceImportError = null,
            )
        }
        loadSources(force = true)
    }

    fun closeSources() {
        stopBrowserPolling()
        _state.update {
            it.copy(
                screen = ReaderScreen.Shelf,
                sourceImportOpen = false,
                sourceDebugId = null,
                sourceDebugLogs = null,
                sourceLoginOpen = false,
                sourceLogin = null,
                browserPage = null,
            )
        }
    }

    fun refreshSources() {
        loadSources(force = true)
    }

    private fun loadSources(force: Boolean = false) {
        val current = _state.value
        if (current.sourcesLoading) return
        if (!force && current.sourcesLoaded) return
        _state.update { it.copy(sourcesLoading = true, sourcesError = null) }
        viewModelScope.launch {
            when (val result = repo.sources(refresh = force)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        sourcesLoading = false,
                        sourcesLoaded = true,
                        sources = result.value,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sourcesLoading = false, sourcesError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourcesLoading = false, sourcesError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(sourcesLoading = false) }
            }
        }
    }

    fun setSourceEnabled(source: ReaderSourceDto, enabled: Boolean) {
        if (source.id in _state.value.sourcePendingIds) return
        // 先切换界面再发请求：开关跟着手指走，失败时回滚并给出原因。
        _state.update { state ->
            state.copy(
                sourcePendingIds = state.sourcePendingIds + source.id,
                sourcesError = null,
                sourcesNotice = null,
                sources = state.sources.map {
                    if (it.id == source.id) it.copy(enabled = enabled) else it
                },
            )
        }
        viewModelScope.launch {
            when (val result = repo.setSourceEnabled(source.id, enabled)) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(
                        sourcePendingIds = state.sourcePendingIds - source.id,
                        sourcesNotice = if (enabled) "已启用「${source.name}」" else "已停用「${source.name}」",
                    )
                }
                is ApiResult.Failure -> rollbackSourceEnabled(source.id, !enabled, readerError(result))
                is ApiResult.NetworkError -> rollbackSourceEnabled(source.id, !enabled, "网络不可用，请稍后重试")
                ApiResult.Stale -> rollbackSourceEnabled(source.id, !enabled, "请求已失效，请重试")
            }
        }
    }

    private fun rollbackSourceEnabled(sourceId: String, enabled: Boolean, error: String) {
        _state.update { state ->
            state.copy(
                sourcePendingIds = state.sourcePendingIds - sourceId,
                sourcesError = error,
                sources = state.sources.map {
                    if (it.id == sourceId) it.copy(enabled = enabled) else it
                },
            )
        }
    }

    fun deleteSource(source: ReaderSourceDto) {
        if (source.id in _state.value.sourcePendingIds) return
        _state.update { state ->
            state.copy(
                sourcePendingIds = state.sourcePendingIds + source.id,
                sourcesError = null,
                sourcesNotice = null,
            )
        }
        viewModelScope.launch {
            when (val result = repo.deleteSource(source.id)) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(
                        sourcePendingIds = state.sourcePendingIds - source.id,
                        sources = state.sources.filterNot { it.id == source.id },
                        sourcesNotice = "已删除「${source.name}」",
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        sourcePendingIds = it.sourcePendingIds - source.id,
                        sourcesError = readerError(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(
                        sourcePendingIds = it.sourcePendingIds - source.id,
                        sourcesError = "网络不可用，请稍后重试",
                    )
                }
                ApiResult.Stale -> _state.update {
                    it.copy(
                        sourcePendingIds = it.sourcePendingIds - source.id,
                        sourcesError = "请求已失效，请重试",
                    )
                }
            }
        }
    }

    fun toggleSourceImport() {
        _state.update {
            it.copy(
                sourceImportOpen = !it.sourceImportOpen,
                sourceImportError = null,
            )
        }
    }

    fun onSourceImportTextChange(value: String) {
        _state.update {
            it.copy(
                sourceImportText = value,
                // 用户开始手输/粘贴后，之前选中的文件不再参与导入。
                sourceImportPayload = null,
                sourceImportFileName = null,
                sourceImportFileSize = 0,
                sourceImportError = null,
            )
        }
    }

    fun onSourceImportFileLoaded(name: String, size: Long, text: String) {
        _state.update {
            it.copy(
                sourceImportOpen = true,
                sourceImportPayload = text,
                sourceImportFileName = name,
                sourceImportFileSize = size,
                sourceImportText = "",
                sourceImportError = null,
            )
        }
    }

    fun onSourceImportFileError(message: String) {
        _state.update { it.copy(sourceImportOpen = true, sourceImportError = message) }
    }

    fun clearSourceImportFile() {
        _state.update {
            it.copy(sourceImportPayload = null, sourceImportFileName = null, sourceImportFileSize = 0)
        }
    }

    fun importSources() {
        val current = _state.value
        if (current.sourceImporting) return
        val payload = (current.sourceImportPayload ?: current.sourceImportText).trim()
        if (payload.isEmpty()) {
            _state.update { it.copy(sourceImportError = "请粘贴书源内容或选择书源文件") }
            return
        }
        _state.update { it.copy(sourceImporting = true, sourceImportError = null) }
        viewModelScope.launch {
            when (val result = repo.importSources(payload)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            sourceImporting = false,
                            sourceImportOpen = false,
                            sourceImportText = "",
                            sourceImportPayload = null,
                            sourceImportFileName = null,
                            sourceImportFileSize = 0,
                            sourcesNotice = "成功导入 ${result.value} 个书源",
                        )
                    }
                    loadSources(force = true)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sourceImporting = false, sourceImportError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourceImporting = false, sourceImportError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update {
                    it.copy(sourceImporting = false, sourceImportError = "请求已失效，请重试")
                }
            }
        }
    }

    fun toggleSourceDebug(sourceId: String) {
        _state.update {
            it.copy(
                sourceDebugId = if (it.sourceDebugId == sourceId) null else sourceId,
                sourceDebugLogs = null,
            )
        }
    }

    fun clearSourcesNotice() {
        _state.update { it.copy(sourcesNotice = null, sourcesError = null) }
    }

    fun onSourceDebugKeyChange(value: String) {
        _state.update { it.copy(sourceDebugKey = value) }
    }

    fun runSourceDebug(source: ReaderSourceDto) {
        val key = _state.value.sourceDebugKey.trim()
        if (key.isEmpty()) return
        if (_state.value.sourceDebugRunning) return
        _state.update { it.copy(sourceDebugRunning = true, sourceDebugLogs = null, sourcesError = null) }
        viewModelScope.launch {
            when (val result = repo.debugSource(source.id, key)) {
                is ApiResult.Success -> _state.update {
                    it.copy(sourceDebugRunning = false, sourceDebugLogs = result.value)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        sourceDebugRunning = false,
                        sourceDebugLogs = listOf("调试失败：" + readerError(result)),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourceDebugRunning = false, sourceDebugLogs = listOf("网络不可用，请稍后重试"))
                }
                ApiResult.Stale -> _state.update {
                    it.copy(sourceDebugRunning = false, sourceDebugLogs = listOf("请求已失效，请重试"))
                }
            }
        }
    }

    // ── 书源登录 ──

    fun loadCurrentSource() {
        val book = _state.value.book ?: return
        if (_state.value.currentSource != null) return
        viewModelScope.launch {
            when (val result = repo.sources()) {
                is ApiResult.Success -> {
                    val source = result.value.firstOrNull { it.sourceUrl == book.origin }
                    _state.update { it.copy(currentSource = source) }
                }
                else -> Unit
            }
        }
    }

    fun openSourceLogin() {
        val current = _state.value
        val book = current.book ?: return
        val known = current.currentSource
            ?: current.sources.firstOrNull { it.sourceUrl == book.origin }
        if (known != null) {
            openSourceLoginFor(known)
            return
        }
        if (current.sourceLoginOpen) return
        _state.update {
            it.copy(
                sourceLoginOpen = true,
                sourceLoginLoading = true,
                sourceLogin = null,
                sourceLoginFields = emptyMap(),
                sourceLoginError = null,
                sourceLoginNotice = null,
            )
        }
        viewModelScope.launch {
            val source = (repo.sources() as? ApiResult.Success)?.value
                ?.firstOrNull { it.sourceUrl == book.origin }
            if (source == null) {
                _state.update {
                    it.copy(sourceLoginLoading = false, sourceLoginError = "未找到这本书对应的书源")
                }
                return@launch
            }
            _state.update { it.copy(currentSource = source) }
            startBrowserPolling(source.id)
            loadSourceLoginInternal(source.id)
        }
    }

    /** 从书源管理列表直接登录某个书源（不依赖当前打开的书）。 */
    fun openSourceLoginFor(source: ReaderSourceDto) {
        if (_state.value.sourceLoginOpen) return
        _state.update {
            it.copy(
                currentSource = source,
                sourceLoginOpen = true,
                sourceLoginLoading = true,
                sourceLogin = null,
                sourceLoginFields = emptyMap(),
                sourceLoginError = null,
                sourceLoginNotice = null,
            )
        }
        viewModelScope.launch {
            startBrowserPolling(source.id)
            loadSourceLoginInternal(source.id)
        }
    }

    private suspend fun loadSourceLoginInternal(sourceId: String) {
        when (val result = repo.sourceLogin(sourceId)) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    sourceLoginLoading = false,
                    sourceLogin = result.value,
                    sourceLoginFields = initialLoginFields(result.value),
                    sourceLoginError = result.value.error.takeIf { message -> message.isNotBlank() },
                    sourceLoggedIn = it.sourceLoggedIn + (sourceId to result.value.loggedIn),
                )
            }
            is ApiResult.Failure -> _state.update {
                it.copy(sourceLoginLoading = false, sourceLoginError = readerError(result))
            }
            is ApiResult.NetworkError -> _state.update {
                it.copy(sourceLoginLoading = false, sourceLoginError = "网络不可用，请稍后重试")
            }
            ApiResult.Stale -> _state.update { it.copy(sourceLoginLoading = false) }
        }
    }

    private fun initialLoginFields(login: ReaderSourceLoginDto): Map<String, String> {
        val values = login.values.toMutableMap()
        login.fields.orEmpty().forEach { field ->
            if (field.name.isNotBlank() &&
                values[field.name].isNullOrBlank() &&
                field.default.isNotBlank()
            ) {
                values[field.name] = field.default
            }
        }
        return values
    }

    fun onSourceLoginFieldChange(name: String, value: String) {
        _state.update {
            it.copy(sourceLoginFields = it.sourceLoginFields + (name to value), sourceLoginError = null)
        }
    }

    fun submitSourceLogin(action: String? = null) {
        val current = _state.value
        val source = current.currentSource ?: return
        val login = current.sourceLogin ?: return
        if (current.sourceLoginBusy) return
        _state.update { it.copy(sourceLoginBusy = true, sourceLoginError = null, sourceLoginNotice = null) }
        viewModelScope.launch {
            when (val result = repo.runSourceLogin(source.id, action.orEmpty(), current.sourceLoginFields)) {
                is ApiResult.Success -> {
                    val value = result.value
                    _state.update { state ->
                        state.copy(
                            sourceLoginBusy = false,
                            sourceLoginError = value.error.takeIf { it.isNotBlank() },
                            sourceLoginNotice = value.toasts?.joinToString("；")
                                ?.takeIf { it.isNotBlank() }
                                ?: if (value.ok) "操作已完成" else null,
                            sourceLogin = state.sourceLogin?.copy(
                                values = value.values,
                                cookies = value.cookies,
                                loggedIn = value.loggedIn,
                            ),
                            sourceLoginFields = value.values.ifEmpty { state.sourceLoginFields },
                            sourceLoggedIn = state.sourceLoggedIn + (source.id to value.loggedIn),
                        )
                    }
                    if (value.uiRefresh || !value.ok) {
                        loadSourceLoginInternal(source.id)
                    }
                    if (value.browsers?.isNotEmpty() == true) {
                        startBrowserPolling(source.id)
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(sourceLoginBusy = false) }
            }
        }
    }

    fun logoutSource() {
        val source = _state.value.currentSource ?: return
        _state.update { it.copy(sourceLoginBusy = true, sourceLoginError = null) }
        viewModelScope.launch {
            when (val result = repo.sourceLogout(source.id)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            sourceLoginBusy = false,
                            sourceLoginNotice = "已清除书源登录状态",
                            sourceLoggedIn = it.sourceLoggedIn + (source.id to false),
                        )
                    }
                    loadSourceLoginInternal(source.id)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(sourceLoginBusy = false) }
            }
        }
    }

    /** 仅保存登录表单值，不触发书源的 login()（网页版「仅保存信息」）。 */
    fun saveSourceLoginInfo() {
        val current = _state.value
        val source = current.currentSource ?: return
        if (current.sourceLoginBusy) return
        _state.update { it.copy(sourceLoginBusy = true, sourceLoginError = null, sourceLoginNotice = null) }
        viewModelScope.launch {
            when (val result = repo.saveSourceLoginInfo(source.id, current.sourceLoginFields)) {
                is ApiResult.Success -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginNotice = "已保存登录信息")
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(sourceLoginBusy = false) }
            }
        }
    }

    /** 保存书源变量（bookSourceUrl 变量的 JSON，配合书源 JS 的 source.getVariable）。 */
    fun saveSourceVariable(variable: String) {
        val current = _state.value
        val source = current.currentSource ?: return
        if (current.sourceLoginBusy) return
        _state.update { it.copy(sourceLoginBusy = true, sourceLoginError = null, sourceLoginNotice = null) }
        viewModelScope.launch {
            when (val result = repo.saveSourceVariable(source.id, variable)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(sourceLoginBusy = false, sourceLoginNotice = "源变量已保存") }
                    loadSourceLoginInternal(source.id)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(sourceLoginBusy = false, sourceLoginError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(sourceLoginBusy = false) }
            }
        }
    }

    fun closeSourceLogin() {
        stopBrowserPolling()
        closeBrowserPage(cancelled = true)
        _state.update {
            it.copy(
                sourceLoginOpen = false,
                sourceLogin = null,
                sourceLoginFields = emptyMap(),
                sourceLoginError = null,
                sourceLoginNotice = null,
            )
        }
    }

    // ── 离线缓存 ──

    fun refreshCacheStatus(bookId: String? = null) {
        val target = bookId ?: _state.value.book?.id ?: return
        viewModelScope.launch {
            val indexes = withContext(Dispatchers.IO) { cache.cachedIndexes(scope, target) }
            val partial = withContext(Dispatchers.IO) { cache.partialIndexes(scope, target) }
            val bytes = withContext(Dispatchers.IO) { cache.sizeBytes(scope, target) }
            _state.update { current ->
                if (current.book?.id == target) {
                    current.copy(cachedChapters = indexes, partialCachedChapters = partial, cacheBytes = bytes)
                } else {
                    current
                }
            }
        }
    }

    fun clearCacheNotice() {
        _state.update { it.copy(cacheNotice = null) }
    }

    fun cacheFollowingChapters(count: Int = 20) {
        val current = _state.value
        val book = current.book ?: return
        if (current.cacheBusy) return
        val targets = buildList {
            for (index in (current.chapterIndex + 1) until current.chapters.size) {
                if (!current.chapters[index].isVolume) add(index)
                if (size >= count) break
            }
        }
        if (targets.isEmpty()) {
            _state.update { it.copy(cacheNotice = "后面没有可缓存的章节") }
            return
        }
        downloadChapters(book, targets)
    }

    fun cacheCurrentChapter() {
        val current = _state.value
        val book = current.book ?: return
        if (current.cacheBusy) return
        downloadChapters(book, listOf(current.chapterIndex))
    }

    fun cancelCacheDownload() {
        if (cacheJob?.isActive != true) return
        cacheTaskToken += 1
        cacheJob?.cancel()
        cacheJob = null
        _state.update { it.copy(cacheBusy = false, cacheProgress = "", cacheNotice = "下载已取消，已完成资源保留") }
        refreshCacheStatus()
    }

    fun retryFailedCache() {
        val current = _state.value
        val book = current.book ?: return
        if (!current.cacheBusy && current.cacheFailedChapters.isNotEmpty()) {
            downloadChapters(book, current.cacheFailedChapters.sorted())
        }
    }

    private fun downloadChapters(book: ReaderBookDto, targets: List<Int>) {
        if (offlineOnly || !cacheWritable()) return
        cacheJob?.cancel()
        val task = ++cacheTaskToken
        val indexes = targets.distinct()
        val expected = cache.generation(scope, book.id)
        _state.update { it.copy(cacheBusy = true, cacheNotice = null,
            cacheProgress = "0/${indexes.size}", cacheFailedChapters = indexes.toSet()) }
        cacheJob = viewModelScope.launch {
            var done = 0
            var processed = 0
            val failed = indexes.toMutableSet()
            try {
                indexes.chunked(20).forEach { chunk ->
                    if (cache.generation(scope, book.id) != expected) throw CancellationException("缓存代次已变化")
                    val result = repo.contentBatch(book.id, chunk)
                    val items = (result as? ApiResult.Success)?.value?.items.orEmpty().associateBy { it.chapterIndex }
                    chunk.forEach { index ->
                        val item = items[index]
                        if (item != null && item.error.isBlank() && item.type.isNotBlank()) {
                            if (!(item.type == "audio" && item.hls)) {
                                try {
                                    cache.save(scope, book.id, index, item.toChapterContent(), origin, expected)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    DiagnosticsLog.w("Reader", "章节缓存写入失败 book=${book.id} index=$index", failure)
                                }
                            }
                        }
                        val complete = withContext(Dispatchers.IO) {
                            cache.status(scope, book.id, index) == ReaderCacheStatus.COMPLETE
                        }
                        if (complete) { done++; failed.remove(index) }
                        processed++
                        if (task == cacheTaskToken) _state.update { it.copy(
                            cacheProgress = "$processed/${indexes.size}", cacheFailedChapters = failed.toSet(),
                        ) }
                    }
                }
                if (task == cacheTaskToken) _state.update { it.copy(cacheNotice =
                    "完整缓存 $done 章，未完成 ${failed.size} 章" +
                        if (failed.isEmpty()) "" else "（可重试失败章节；HLS 暂不支持离线）") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                DiagnosticsLog.w("Reader", "缓存任务失败 book=${book.id}", failure)
                if (task == cacheTaskToken) _state.update { it.copy(cacheNotice = "缓存未完成，请检查空间与网络后重试") }
            } finally {
                if (task == cacheTaskToken) {
                    _state.update { it.copy(cacheBusy = false, cacheProgress = "", cacheFailedChapters = failed.toSet()) }
                    refreshCacheStatus(book.id)
                }
            }
        }
    }

    fun clearBookCache() {
        val current = _state.value
        val book = current.book ?: return
        if (current.cacheBusy) return
        cacheJob?.cancel()
        _state.update { it.copy(cacheBusy = true, cacheNotice = null) }
        cacheJob = viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { runCatching { cache.clear(scope, book.id) }.getOrDefault(false) }
            refreshCacheStatus(book.id)
            _state.update {
                it.copy(
                    cacheBusy = false,
                    cacheFailedChapters = emptySet(),
                    cacheNotice = if (cleared) "已清除本书缓存" else "缓存清除失败，请检查存储后重试",
                )
            }
        }
    }

    private fun ReaderContentBatchItemDto.toChapterContent(): ReaderChapterContentDto =
        ReaderChapterContentDto(
            type = type,
            content = content,
            tracks = tracks,
            images = images,
            imageStyle = imageStyle,
            comments = comments,
            hls = hls,
        )

    fun rebuildToc() {
        val current = _state.value
        val book = current.book ?: return
        if (current.tocRebuilding || book.isLocal) return
        val token = loadToken
        _state.update { it.copy(tocRebuilding = true, chapterError = null) }
        viewModelScope.launch {
            when (val toc = repo.toc(book.origin, book.bookUrl, book.tocUrl)) {
                is ApiResult.Success -> {
                    if (token != loadToken) return@launch
                    if (toc.value.isEmpty()) {
                        _state.update {
                            it.copy(tocRebuilding = false, chapterError = "书源没有返回目录，请稍后重试")
                        }
                        return@launch
                    }
                    val chapters = toc.value.map {
                        ReaderChapterDto(
                            index = it.index,
                            title = it.title,
                            url = it.url,
                            isVolume = it.isVolume,
                        )
                    }
                    repo.saveChapters(book.id, chapters)
                    withContext(Dispatchers.IO) {
                        runCatching { cache.saveChapters(scope, book.id, chapters) }
                    }
                    audio.updateChapters(book.id, chapters)
                    if (token != loadToken) return@launch
                    val target = ReaderChapters.initialIndex(book, chapters)
                    _state.update {
                        it.copy(
                            tocRebuilding = false,
                            chapters = chapters,
                            chapterError = null,
                        )
                    }
                    loadChapterContent(target, token, 0)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(tocRebuilding = false, chapterError = readerError(toc))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(tocRebuilding = false, chapterError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(tocRebuilding = false) }
            }
        }
    }

    fun retryChapter() {
        val current = _state.value
        val book = current.book ?: return
        if (current.chapters.isEmpty()) {
            openBook(book)
            return
        }
        loadChapterContent(current.chapterIndex, loadToken, current.restorePage)
    }

    fun stepChapter(delta: Int) {
        val current = _state.value
        if (current.screen != ReaderScreen.Reading || current.chapterLoading) return
        val target = if (delta > 0) {
            ReaderChapters.nextReadable(current.chapters, current.chapterIndex)
        } else {
            ReaderChapters.previousReadable(current.chapters, current.chapterIndex)
        }
        if (target == null) {
            _state.update {
                it.copy(chapterNotice = if (delta > 0) "已经是最后一章" else "已经是第一章")
            }
            return
        }
        saveProgressNow()
        loadChapterContent(target, loadToken, 0)
    }

    fun jumpToChapter(index: Int) {
        val current = _state.value
        val target = current.chapters.getOrNull(index) ?: return
        if (target.isVolume || index == current.chapterIndex) {
            _state.update { it.copy(tocOpen = false) }
            return
        }
        saveProgressNow()
        loadChapterContent(index, loadToken, 0)
    }

    /** 滚动停下后由界面上报当前页（文本为估算页码，漫画为图片序号）。 */
    fun onPageChanged(page: Int) {
        if (_state.value.screen != ReaderScreen.Reading) return
        if (page < 0 || page == currentPage) return
        currentPage = page
        persistProgress(_state.value, page)
    }

    fun clearChapterNotice() {
        _state.update { it.copy(chapterNotice = null) }
    }

    fun persistReadingPosition() { saveProgressNow() }

    private fun scheduleProgressSave() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            syncPendingProgress()
        }
    }

    private fun saveProgressNow() {
        persistProgress(_state.value, currentPage, syncImmediately = true)
    }

    /** 翻页即落本机，网络防抖与持久化分开，取消网络请求不会取消本地保存。 */
    private fun persistProgress(snapshot: ReaderUiState, page: Int, syncImmediately: Boolean = false) {
        val book = snapshot.book ?: return
        val content = snapshot.content ?: return
        if (snapshot.chapterLoading || content.type !in setOf("text", "image")) return
        // 退出登录后旧实例的写入必须整体丢弃，不能再落本机文件。
        if (progressStore.generation(scope) != progressGeneration) return
        lastProgressTime = maxOf(System.currentTimeMillis(), lastProgressTime + 1)
        val event = ReaderProgress(
            bookId = book.id, chapterIndex = snapshot.chapterIndex,
            position = page.coerceAtLeast(0), chapterTitle = snapshot.chapterTitle,
            chapterUrl = snapshot.chapters.getOrNull(snapshot.chapterIndex)?.url.orEmpty(),
            updatedAtMs = lastProgressTime,
        )
        viewModelScope.launch {
            try {
                val local = withContext(Dispatchers.IO) {
                    val previous = progressStore.load(scope, book.id)
                    if (previous != null && previous.chapterIndex == event.chapterIndex &&
                        previous.position == event.position && previous.chapterUrl == event.chapterUrl
                    ) previous else {
                        progressStore.save(scope, event, progressGeneration)
                        progressStore.load(scope, book.id)
                    }
                } ?: return@launch
                _state.update { current ->
                    current.copy(
                        book = current.book?.let {
                            if (it.id == book.id) ReaderProgressResume.merge(it, local) else it
                        },
                        books = current.books.map {
                            if (it.id == book.id) ReaderProgressResume.merge(it, local) else it
                        },
                        progressNotice = if (offlineOnly) "进度已保存本机，联网后同步" else "进度已保存本机，等待同步",
                    )
                }
                if (syncImmediately) syncPendingProgress() else scheduleProgressSave()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                DiagnosticsLog.w("Reader", "保存阅读进度失败 book=${book.id}", failure)
                _state.update { it.copy(progressNotice = "本机进度保存失败，请检查可用空间") }
            }
        }
    }

    private suspend fun syncPendingProgress() {
        if (offlineOnly || progressStore.generation(scope) != progressGeneration) return
        try {
            val remaining = withContext(Dispatchers.IO) {
                progressSync.flush()
                progressStore.pending(scope).size
            }
            _state.update { it.copy(progressNotice =
                if (remaining == 0) null else "进度已保存本机，${remaining} 本书等待同步") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            DiagnosticsLog.w("Reader", "同步阅读进度失败", failure)
            _state.update { it.copy(progressNotice = "进度已保存本机，网络恢复后重试同步") }
        }
    }

    fun toggleToc() {
        _state.update { it.copy(tocOpen = !it.tocOpen, settingsOpen = false, infoOpen = false) }
    }

    fun closeToc() {
        _state.update { it.copy(tocOpen = false) }
    }

    fun toggleSettings() {
        _state.update { it.copy(settingsOpen = !it.settingsOpen, tocOpen = false, infoOpen = false) }
    }

    fun closeSettings() {
        _state.update { it.copy(settingsOpen = false) }
    }

    fun toggleInfo() {
        if (offlineOnly) return
        val opening = !_state.value.infoOpen
        _state.update { it.copy(infoOpen = opening, tocOpen = false, settingsOpen = false) }
        if (opening) loadCurrentSource()
    }

    fun closeInfo() {
        _state.update { it.copy(infoOpen = false, removeError = null) }
    }

    fun closeReading() {
        cancelCacheDownload()
        val snapshot = _state.value
        persistProgress(snapshot, currentPage, syncImmediately = true)
        // 听书可能在离开阅读页后继续播放；先落一次本地位置。
        audio.persistCheckpoint(synchronous = true)
        loadToken += 1
        contentJob?.cancel()
        progressJob?.cancel()
        stopBrowserPolling()
        _state.update {
            it.copy(
                screen = ReaderScreen.Shelf,
                book = null,
                chapters = emptyList(),
                content = null,
                paragraphs = emptyList(),
                chapterLoading = false,
                chapterError = null,
                chapterNotice = null,
                restorePage = 0,
                tocOpen = false,
                settingsOpen = false,
                infoOpen = false,
                removeError = null,
                originPickerOpen = false,
                sourceLoginOpen = false,
                sourceLogin = null,
                browserPage = null,
                cacheNotice = null,
                cacheProgress = "",
                cacheBusy = false,
                cachedChapters = emptySet(),
                partialCachedChapters = emptySet(),
                cacheFailedChapters = emptySet(),
                cacheBytes = 0,
                audioPlayRequested = null,
                audioCreditsOpen = false,
                audioCreditsError = null,
            )
        }
        loadShelf()
    }

    fun removeCurrentBook() {
        val current = _state.value
        val book = current.book ?: return
        if (current.removeBusy) return
        _state.update { it.copy(removeBusy = true, removeError = null) }
        viewModelScope.launch {
            when (val result = repo.removeBook(book.id)) {
                is ApiResult.Success -> {
                    loadToken += 1
                    contentJob?.cancel()
                    progressJob?.cancel()
                    if (audio.state.value.bookId == book.id) audio.stop()
                    checkpoints.clear(scope, book.id)
                    withContext(Dispatchers.IO) {
                        progressStore.clear(scope, book.id)
                        cache.clear(scope, book.id)
                    }
                    _state.update {
                        it.copy(
                            screen = ReaderScreen.Shelf,
                            books = it.books.filterNot { item -> item.id == book.id },
                            book = null,
                            chapters = emptyList(),
                            content = null,
                            paragraphs = emptyList(),
                            chapterLoading = false,
                            infoOpen = false,
                            removeBusy = false,
                            removeError = null,
                            tocOpen = false,
                            settingsOpen = false,
                        )
                    }
                    loadShelf()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(removeBusy = false, removeError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(removeBusy = false, removeError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update {
                    it.copy(removeBusy = false, removeError = "请求已失效，请重试")
                }
            }
        }
    }

    // ── 工具 ──

    private fun readerError(result: ApiResult<*>): String = when (result) {
        is ApiResult.Failure -> readerFailureMessage(result)
        is ApiResult.NetworkError -> "网络不可用，请稍后重试"
        ApiResult.Stale -> "请求已失效，请重试"
        is ApiResult.Success -> ""
    }

    /**
     * 阅读端点沿用旧错误格式，服务端会返回可直接展示的中文原因；
     * 只有框架级英文短语才退回统一文案。
     */
    private fun readerFailureMessage(failure: ApiResult.Failure): String {
        val raw = failure.message.trim()
        val generic = raw.isEmpty() ||
            raw.equals("Bad Request", ignoreCase = true) ||
            raw.equals("Internal Server Error", ignoreCase = true) ||
            raw.equals("Not Found", ignoreCase = true) ||
            raw.equals("Unauthorized", ignoreCase = true)
        return if (generic) ErrorMessages.action(failure) else raw
    }

    private fun mergeSearchResults(
        existing: List<ReaderSearchBookDto>,
        incoming: List<ReaderSearchBookDto>,
    ): List<ReaderSearchBookDto> {
        val seen = existing.mapTo(mutableSetOf()) { "${it.name}\u0000${it.author}" }
        val merged = existing.toMutableList()
        incoming.forEach { book ->
            if (seen.add("${book.name}\u0000${book.author}")) merged += book
        }
        return merged
    }

    private fun mergeSkipped(
        existing: List<ReaderSearchSkippedDto>,
        incoming: List<ReaderSearchSkippedDto>,
    ): List<ReaderSearchSkippedDto> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapTo(mutableSetOf()) { "${it.sourceId}\u0000${it.reason}" }
        val merged = existing.toMutableList()
        incoming.forEach { item ->
            if (seen.add("${item.sourceId}\u0000${item.reason}")) merged += item
        }
        return merged
    }

    private data class ChapterLoad(
        val chapters: List<ReaderChapterDto>,
        val error: String? = null,
    )

    class Factory(
        private val repository: ReaderRepository,
        private val scope: AccountScope,
        private val preferences: ReaderPreferences,
        private val cache: ReaderCacheStore,
        private val audio: ReaderAudioEngine,
        private val checkpoints: ReaderAudioCheckpointStore,
        private val origin: String,
        private val progressStore: ReaderProgressStore,
        private val offlineOnly: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderViewModel::class.java))
            return ReaderViewModel(
                repository,
                scope,
                preferences,
                cache,
                audio,
                checkpoints,
                origin,
                progressStore,
                offlineOnly,
            ) as T
        }
    }

    private companion object {
        const val PROGRESS_DEBOUNCE_MS = 1_500L
        const val PROFILE_SYNC_DELAY_MS = 1_200L
    }
}

/** 章节跳转规则：目录里的「卷」只作分隔，不进入正文。 */
object ReaderChapters {

    fun initialIndex(book: ReaderBookDto, chapters: List<ReaderChapterDto>): Int {
        val saved = book.durChapterIndex
        if (saved in chapters.indices && !chapters[saved].isVolume) return saved
        return chapters.indexOfFirst { !it.isVolume }.takeIf { it >= 0 } ?: 0
    }

    fun nextReadable(chapters: List<ReaderChapterDto>, from: Int): Int? {
        if (chapters.isEmpty()) return null
        val start = (from + 1).coerceAtLeast(0)
        for (index in start..chapters.lastIndex) {
            if (!chapters[index].isVolume) return index
        }
        return null
    }

    fun previousReadable(chapters: List<ReaderChapterDto>, from: Int): Int? {
        if (chapters.isEmpty()) return null
        val start = (from - 1).coerceAtMost(chapters.lastIndex)
        for (index in start downTo 0) {
            if (!chapters[index].isVolume) return index
        }
        return null
    }
}
