package app.mebox.android.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto
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
import app.mebox.android.core.reader.ReaderAudioEngine
import app.mebox.android.core.reader.ReaderAudioState
import app.mebox.android.core.reader.ReaderAudioTuning
import app.mebox.android.core.reader.ReaderCacheStore
import app.mebox.android.core.reader.ReaderParagraph
import app.mebox.android.core.reader.ReaderPreferences
import app.mebox.android.core.reader.ReaderRepository
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
    data object Reading : ReaderScreen
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

    // 搜索
    val query: String = "",
    val searchBooks: List<ReaderSearchBookDto> = emptyList(),
    val searchSkipped: List<ReaderSearchSkippedDto> = emptyList(),
    val searchPage: Int = 0,
    val searchLoading: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val searchError: String? = null,
    val searchDetail: ReaderSearchBookDto? = null,
    val addingBook: Boolean = false,
    val addBookError: String? = null,

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
}

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
    private val origin: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var contentJob: Job? = null
    private var searchJob: Job? = null
    private var progressJob: Job? = null
    private var profileSaveJob: Job? = null
    private var browserPollJob: Job? = null
    private var cacheJob: Job? = null
    private var loadToken = 0L
    private var currentPage = 0
    private var styleTouched = false
    private var requestAudioPlay = false

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
        loadProfile()
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

    private fun loadShelf() {
        if (_state.value.shelfLoading) return
        _state.update { it.copy(shelfLoading = true, shelfError = null) }
        viewModelScope.launch {
            val booksResult = repo.shelf()
            val groupsResult = if (_state.value.groupsLoaded) null else repo.bookGroups()
            val cachedShelf = if (booksResult is ApiResult.Success) {
                withContext(Dispatchers.IO) { cache.saveShelf(scope, booksResult.value) }
                null
            } else {
                withContext(Dispatchers.IO) { cache.readShelf(scope) }
            }
            val error = when {
                cachedShelf != null -> null
                booksResult is ApiResult.Failure -> readerError(booksResult)
                booksResult is ApiResult.NetworkError -> "网络不可用，请稍后重试"
                else -> null
            }
            _state.update { current ->
                val groups = (groupsResult as? ApiResult.Success)?.value ?: current.groups
                val selectedGroup = current.selectedGroup
                    ?.takeIf { name -> groups.any { it.name == name } }
                current.copy(
                    shelfLoading = false,
                    shelfError = error,
                    shelfNotice = if (cachedShelf != null) "网络不可用，已显示离线书架" else current.shelfNotice,
                    books = (booksResult as? ApiResult.Success)?.value
                        ?: cachedShelf
                        ?: current.books,
                    groups = groups,
                    groupsLoaded = current.groupsLoaded || groupsResult is ApiResult.Success,
                    selectedGroup = selectedGroup,
                )
            }
        }
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
        updateStyle(ReaderStyle.DEFAULT.copy(shelfSort = _state.value.style.shelfSort))
    }

    // ── 搜索 ──

    fun openSearch() {
        _state.update {
            it.copy(screen = ReaderScreen.Search, searchError = null, addBookError = null)
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                screen = ReaderScreen.Shelf,
                searchDetail = null,
                addBookError = null,
                searchLoading = false,
                searchLoadingMore = false,
            )
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, searchError = null) }
    }

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
            when (val result = repo.search(query, page = 1)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        searchLoading = false,
                        searchBooks = result.value.books,
                        searchSkipped = result.value.skipped,
                        searchPage = 1,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(searchLoading = false, searchError = readerError(result))
                }
                is ApiResult.NetworkError -> _state.update {
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
            when (val result = repo.search(query, page = nextPage)) {
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

    fun openSearchDetail(book: ReaderSearchBookDto) {
        _state.update { it.copy(searchDetail = book, addBookError = null) }
    }

    fun dismissSearchDetail() {
        if (_state.value.addingBook) return
        _state.update { it.copy(searchDetail = null, addBookError = null) }
    }

    fun addSearchBookToShelf() {
        val current = _state.value
        val detail = current.searchDetail ?: return
        if (current.addingBook) return
        val origin = detail.origins
            ?.firstOrNull { it.originType == 0 }
            ?: detail.origins?.firstOrNull()
        if (origin == null) {
            _state.update { it.copy(addBookError = "该书没有可用书源，请换一个结果") }
            return
        }
        _state.update { it.copy(addingBook = true, addBookError = null) }
        viewModelScope.launch {
            when (val result = repo.addBook(origin, detail.name, detail.author, detail.coverUrl)) {
                is ApiResult.Success -> {
                    val saved = result.value
                    _state.update {
                        it.copy(
                            addingBook = false,
                            searchDetail = null,
                            books = it.books.filterNot { book -> book.id == saved.id } + saved,
                        )
                    }
                    openBook(saved)
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

    fun openExistingBook(book: ReaderSearchBookDto): ReaderBookDto? =
        _state.value.books.firstOrNull { saved ->
            saved.bookUrl == book.bookUrl || (saved.name == book.name && saved.author == book.author)
        }

    // ── 正文 ──

    fun openBook(book: ReaderBookDto) {
        loadToken += 1
        val token = loadToken
        contentJob?.cancel()
        cacheJob?.cancel()
        _state.update {
            it.copy(
                screen = ReaderScreen.Reading,
                book = book,
                chapters = emptyList(),
                chapterIndex = book.durChapterIndex.coerceAtLeast(0),
                content = null,
                paragraphs = emptyList(),
                chapterLoading = true,
                chapterError = null,
                chapterNotice = null,
                restorePage = if (book.hasProgress) book.durChapterPos else 0,
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
                audioCreditsOpen = false,
                audioCreditsError = null,
            )
        }
        refreshCacheStatus(book.id)
        viewModelScope.launch { openBookInternal(book, token) }
    }

    private suspend fun openBookInternal(book: ReaderBookDto, token: Long) {
        val loaded = loadChapters(book)
        if (token != loadToken) return
        if (loaded.chapters.isEmpty()) {
            _state.update {
                it.copy(
                    chapterLoading = false,
                    chapterError = loaded.error ?: "目录为空，请刷新目录后重试",
                )
            }
            return
        }
        withContext(Dispatchers.IO) { cache.saveChapters(scope, book.id, loaded.chapters) }
        audio.updateChapters(loaded.chapters)
        val index = ReaderChapters.initialIndex(book, loaded.chapters)
        val page = if (index == book.durChapterIndex) book.durChapterPos else 0
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
            audio.updateChapters(_state.value.chapters)
            val audioState = audio.state.value
            val sameBook = audioState.bookId == book.id
            val alreadyActive = sameBook &&
                audioState.chapterIndex == index &&
                (audioState.playing || audioState.loading)
            if (!alreadyActive) {
                val autoPlay = requestAudioPlay || (sameBook && audioState.playing)
                requestAudioPlay = false
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
                requestAudioPlay = false
            }
        } else if (audio.state.value.bookId == book.id) {
            // 同一本书从音频章节切到文本/漫画时停止音频，避免两个内容同时继续。
            audio.stop()
        }
    }

    private fun autoCacheText(book: ReaderBookDto, index: Int, content: ReaderChapterContentDto) {
        if (content.type != "text") return
        viewModelScope.launch {
            val already = withContext(Dispatchers.IO) {
                cache.hasChapter(scope, book.id, index)
            }
            if (!already) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        cache.save(scope, book.id, index, content, origin)
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
        if (audioState.bookId == book.id && audioState.chapterIndex == current.chapterIndex) {
            audio.toggle()
        } else {
            // 章节还没交给引擎（例如刚打开或刚切章）：加载后自动开始播放。
            requestAudioPlay = true
            loadChapterContent(current.chapterIndex, loadToken, current.restorePage)
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
                    withContext(Dispatchers.IO) { cache.clear(scope, book.id) }
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
                        val newest = result.value
                            .filter { it.id != active?.id }
                            .maxByOrNull { it.seq }
                        current.copy(
                            pendingBrowsers = result.value,
                            browserPage = active ?: newest,
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
            val source = current.currentSource
                ?: (repo.sources() as? ApiResult.Success)?.value
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

    private suspend fun loadSourceLoginInternal(sourceId: String) {
        when (val result = repo.sourceLogin(sourceId)) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    sourceLoginLoading = false,
                    sourceLogin = result.value,
                    sourceLoginFields = initialLoginFields(result.value),
                    sourceLoginError = result.value.error.takeIf { message -> message.isNotBlank() },
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
                        it.copy(sourceLoginBusy = false, sourceLoginNotice = "已清除书源登录状态")
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
            val bytes = withContext(Dispatchers.IO) { cache.sizeBytes(scope, target) }
            _state.update { current ->
                if (current.book?.id == target) {
                    current.copy(cachedChapters = indexes, cacheBytes = bytes)
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

    private fun downloadChapters(book: ReaderBookDto, targets: List<Int>) {
        cacheJob?.cancel()
        _state.update {
            it.copy(
                cacheBusy = true,
                cacheNotice = null,
                cacheProgress = "0/${targets.size}",
            )
        }
        cacheJob = viewModelScope.launch {
            var done = 0
            var failed = 0
            targets.chunked(20).forEach { chunk ->
                when (val result = repo.contentBatch(book.id, chunk)) {
                    is ApiResult.Success -> {
                        val items = result.value.items.orEmpty()
                            .associateBy { it.chapterIndex }
                        chunk.forEach { index ->
                            val item = items[index]
                            if (item == null || item.error.isNotBlank() || item.type.isBlank()) {
                                failed += 1
                            } else if (item.type == "audio" && item.hls) {
                                // HLS 播放列表依赖在线分片，客户端目前不做分片级离线缓存。
                                failed += 1
                            } else {
                                val content = item.toChapterContent()
                                val saved = runCatching {
                                    withContext(Dispatchers.IO) {
                                        cache.save(scope, book.id, index, content, origin)
                                    }
                                }.isSuccess
                                if (saved) done += 1 else failed += 1
                            }
                            _state.update {
                                it.copy(cacheProgress = "${done + failed}/${targets.size}")
                            }
                        }
                    }
                    else -> {
                        failed += chunk.size
                        _state.update {
                            it.copy(cacheProgress = "${done + failed}/${targets.size}")
                        }
                    }
                }
            }
            refreshCacheStatus(book.id)
            _state.update {
                it.copy(
                    cacheBusy = false,
                    cacheProgress = "",
                    cacheNotice = if (failed == 0) {
                        "已缓存 $done 章"
                    } else {
                        "已缓存 $done 章，失败 $failed 章（HLS 音轨与部分上游资源不支持离线）"
                    },
                )
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
            withContext(Dispatchers.IO) { cache.clear(scope, book.id) }
            refreshCacheStatus(book.id)
            _state.update { it.copy(cacheBusy = false, cacheNotice = "已清除本书缓存") }
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
                    withContext(Dispatchers.IO) { cache.saveChapters(scope, book.id, chapters) }
                    audio.updateChapters(chapters)
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
        scheduleProgressSave()
    }

    fun clearChapterNotice() {
        _state.update { it.copy(chapterNotice = null) }
    }

    private fun scheduleProgressSave() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            pushProgress(_state.value, currentPage)
        }
    }

    private fun saveProgressNow() {
        progressJob?.cancel()
        val snapshot = _state.value
        progressJob = viewModelScope.launch { pushProgress(snapshot, currentPage) }
    }

    private suspend fun pushProgress(snapshot: ReaderUiState, page: Int) {
        val book = snapshot.book ?: return
        if (snapshot.content == null) return
        // 音频进度由 ReaderAudioEngine 按秒上报；这里再用页码上报会把它覆盖成 0。
        if (snapshot.content.type == "audio") return
        val chapterTitle = snapshot.chapters.getOrNull(snapshot.chapterIndex)?.title.orEmpty()
        val result = repo.saveProgress(
            bookId = book.id,
            chapterIndex = snapshot.chapterIndex,
            pos = page.coerceAtLeast(0),
            chapterTitle = chapterTitle,
        )
        if (result is ApiResult.Success) {
            _state.update { current ->
                val currentBook = current.book
                if (currentBook == null || currentBook.id != book.id) {
                    current
                } else {
                    current.copy(
                        book = currentBook.copy(
                            durChapterIndex = snapshot.chapterIndex,
                            durChapterPos = page,
                            durChapterTitle = chapterTitle,
                            durChapterTime = System.currentTimeMillis(),
                        ),
                    )
                }
            }
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
        val opening = !_state.value.infoOpen
        _state.update { it.copy(infoOpen = opening, tocOpen = false, settingsOpen = false) }
        if (opening) loadCurrentSource()
    }

    fun closeInfo() {
        _state.update { it.copy(infoOpen = false, removeError = null) }
    }

    fun closeReading() {
        val snapshot = _state.value
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
                audioCreditsOpen = false,
                audioCreditsError = null,
            )
        }
        viewModelScope.launch {
            pushProgress(snapshot, currentPage)
            loadShelf()
        }
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
                    withContext(Dispatchers.IO) { cache.clear(scope, book.id) }
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
        private val origin: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderViewModel::class.java))
            return ReaderViewModel(repository, scope, preferences, cache, audio, origin) as T
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
