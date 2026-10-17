package app.mebox.android.feature.media

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.mebox.android.core.media.DeviceCodecSupport
import app.mebox.android.core.media.DanmakuComment
import app.mebox.android.core.media.DanmakuParser
import app.mebox.android.core.media.LibraryFilter
import app.mebox.android.core.media.LibrarySort
import app.mebox.android.core.media.LibrarySortSelection
import app.mebox.android.core.media.MediaRepository
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.media.PlaybackUrls
import app.mebox.android.core.media.WatchProgressStore
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ContinueWatchingDto
import app.mebox.android.core.network.DanmakuAnimeDto
import app.mebox.android.core.network.DanmakuSettingsPatchDto
import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.LibraryFacetsDto
import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.PlaybackInfoDto
import app.mebox.android.core.network.SeriesCardDto
import app.mebox.android.core.network.SubtitleTrackDto
import app.mebox.android.core.network.WatchHistoryDto
import app.mebox.android.core.security.CredentialStore
import app.mebox.android.feature.ErrorMessages
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class MainTab { HOME, MEDIA, READER, PROFILE }

sealed interface MediaScreen {
    data object Tabs : MediaScreen
    data object Favorites : MediaScreen
    data object History : MediaScreen
    data class Library(val library: LibraryDto) : MediaScreen
    data class Detail(
        val mediaId: String,
        val seriesKey: String? = null,
        val libraryId: String? = null,
    ) : MediaScreen

    data class Player(val mediaId: String, val title: String) : MediaScreen
}

data class PlaybackSubtitle(
    val lang: String,
    val label: String,
    /** 服务端转成 WebVTT 后的字幕地址；不支持 ASS 的内核使用它。 */
    val url: String,
    /**
     * ASS/SSA 原始文件地址。LibVLC 内置 libass，会用这个地址按字幕文件自带的
     * 样式（字体、颜色、描边、定位、特效）渲染；其它内核退回 [url]。
     */
    val assUrl: String? = null,
    /** 服务端识别出的字幕编码，例如 ass、ssa、srt、vtt。 */
    val codec: String = "",
) {
    val key: String
        get() = url

    /** 该字幕是否带可还原的 ASS 样式。 */
    val hasAssStyle: Boolean
        get() = assUrl != null
}

data class PlaybackNext(
    val mediaId: String,
    val title: String,
)

data class PlaybackRequest(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    /** 服务器返回的直链播放地址；App 始终直接播放，不再请求服务端转码。 */
    val streamUrl: String,
    val startPositionMs: Long,
    val durationMs: Long,
    val subtitles: List<PlaybackSubtitle> = emptyList(),
    /** PGS/DVD 等图片字幕：ExoPlayer 不能渲染，只用于在设置面板里说明。 */
    val imageSubtitles: List<String> = emptyList(),
    val nextEpisode: PlaybackNext? = null,
    /** 设备解码能力说明，例如“本机没有 HEVC 解码器”。 */
    val decoderNote: String? = null,
)

/**
 * 播放页的弹幕状态。渲染参数来自服务端返回的用户偏好；开关和面板调整会
 * 通过 `/api/danmaku/settings` 写回，做到跨设备一致。
 */
data class DanmakuUiState(
    val loading: Boolean = false,
    val enabled: Boolean = true,
    val opacity: Float = 1f,
    /** 相对标准字号（服务端 24）的比例。 */
    val fontScale: Float = 1f,
    /** 弹幕占用画面上部高度的比例（0.1-1）。 */
    val area: Float = 1f,
    val comments: List<DanmakuComment> = emptyList(),
    /** 自动匹配命中多个番剧时，需要用户选择具体弹幕库。 */
    val candidates: List<DanmakuAnimeDto> = emptyList(),
    /** 已自动加载弹幕时，同一集的其它可选来源。 */
    val alternatives: List<DanmakuAnimeDto> = emptyList(),
    /** 已匹配的番剧/单集说明，如“某某番 · 第 1 话”。 */
    val matchLabel: String? = null,
    val mergedSources: Int = 0,
    val error: String? = null,
    /** 已成功拉取过弹幕的媒体 ID，避免用户重复开关时重复请求。 */
    val loadedMediaId: String? = null,
) {
    val hasLibraryChoices: Boolean
        get() = candidates.isNotEmpty() || alternatives.isNotEmpty()
}

/**
 * 首页的媒体库分区：每个媒体库最近更新的条目。
 *
 * 服务端一次 `with_preview` 请求就能按库返回预览卡片，首页因此可以像
 * Yamby 一样按媒体库铺开横向海报栏，而不是只有一个全局「最近添加」。
 */
data class LibrarySection(
    val library: LibraryDto,
    val cards: List<SeriesCardDto> = emptyList(),
)

data class MediaUiState(
    val tab: MainTab = MainTab.HOME,
    val screen: MediaScreen = MediaScreen.Tabs,
    val libraries: List<LibraryDto> = emptyList(),
    val librarySections: List<LibrarySection> = emptyList(),
    val carouselItems: List<SeriesCardDto> = emptyList(),
    val continueItems: List<ContinueWatchingDto> = emptyList(),
    val recentItems: List<SeriesCardDto> = emptyList(),
    val favorites: List<MediaDto> = emptyList(),
    val favoritesLoading: Boolean = false,
    val favoritesError: String? = null,
    val historyItems: List<WatchHistoryDto> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val homeLoading: Boolean = false,
    val homeError: String? = null,
    val libraryCards: List<SeriesCardDto> = emptyList(),
    val libraryTotal: Int = 0,
    val libraryPage: Int = 0,
    val libraryLoading: Boolean = false,
    val libraryError: String? = null,
    val libraryFilter: LibraryFilter = LibraryFilter(),
    val librarySort: LibrarySortSelection = LibrarySortSelection(),
    val libraryFacets: LibraryFacetsDto? = null,
    val libraryFacetsLoading: Boolean = false,
    val libraryRandomLoading: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<MediaDto> = emptyList(),
    val searchLoading: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val searchError: String? = null,
    val searchPage: Int = 0,
    val searchTotal: Int = 0,
    val detail: MediaDto? = null,
    val episodes: List<MediaDto> = emptyList(),
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val detailResumeMs: Long = 0L,
    val selectedVersionId: String? = null,
    val favorite: Boolean = false,
    val favoriteError: String? = null,
    val playerRequest: PlaybackRequest? = null,
    val playerLoading: Boolean = false,
    val playerError: String? = null,
    val danmaku: DanmakuUiState = DanmakuUiState(),
    val playerReturn: MediaScreen = MediaScreen.Tabs,
    val detailReturn: MediaScreen = MediaScreen.Tabs,
)

/**
 * 影视模块状态。所有播放地址都在这里统一拼装：
 * - 播放与字幕 URL 使用当前访问令牌（服务端同源）；
 * - 续播位置合并服务端进度与本地缓存；
 * - 进度按 5 秒本地写入、15 秒服务端同步的节奏节流。
 */
class MediaViewModel(
    private val repo: MediaRepository,
    private val origin: String,
    private val scope: AccountScope,
    private val credentialStore: CredentialStore,
    private val progressStore: WatchProgressStore,
    private val codecSupport: DeviceCodecSupport,
) : ViewModel() {

    private val _state = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var libraryLoadJob: Job? = null
    private var danmakuRequestId = 0L
    private var danmakuSaveJob: Job? = null
    private var lastPlayedMedia: MediaDto? = null
    private var lastNextMedia: MediaDto? = null
    private var lastServerSyncAt = 0L
    private var playbackRequestId = 0L
    private var detailRequestId = 0L
    private var homeRequestId = 0L

    init {
        refreshHome()
    }

    fun selectTab(tab: MainTab) {
        _state.update { it.copy(tab = tab, screen = MediaScreen.Tabs) }
        if (tab == MainTab.HOME && _state.value.continueItems.isEmpty() &&
            _state.value.recentItems.isEmpty() && !_state.value.homeLoading
        ) {
            refreshHome()
        }
        if (tab == MainTab.MEDIA && _state.value.libraries.isEmpty() && !_state.value.homeLoading) {
            refreshHome()
        }
    }

    fun refreshHome() {
        val requestId = ++homeRequestId
        _state.update { it.copy(homeLoading = true, homeError = null) }
        viewModelScope.launch {
            val (libs, cont, rec) = coroutineScope {
                val libraries = async { repo.libraries() }
                val continueWatching = async { repo.continueWatching(12) }
                val recent = async { repo.recent(18) }
                Triple(libraries.await(), continueWatching.await(), recent.await())
            }
            if (requestId != homeRequestId) return@launch

            val error = firstError(libs, cont, rec)
            val libraries = (libs as? ApiResult.Success)?.value ?: _state.value.libraries
            val recent = (rec as? ApiResult.Success)?.value ?: _state.value.recentItems
            _state.update {
                it.copy(
                    homeLoading = false,
                    homeError = error,
                    libraries = libraries,
                    continueItems = (cont as? ApiResult.Success)?.value ?: it.continueItems,
                    recentItems = recent,
                )
            }

            // 一次预览请求同时服务于「焦点轮播」和「按媒体库分区」。
            // 失败时保留上一次的分区，轮播退回「最近添加」。
            val previewLibraries = libraries.filter { it.enabled }
            var carousel = emptyList<SeriesCardDto>()
            var sections = emptyList<LibrarySection>()
            if (previewLibraries.isNotEmpty()) {
                val previews = repo.libraries(
                    ids = previewLibraries.map { it.id },
                    withPreview = true,
                    previewLimit = HOME_PREVIEW_LIMIT,
                )
                if (previews is ApiResult.Success) {
                    val byId = previews.value.associateBy { it.id }
                    sections = previewLibraries.mapNotNull { library ->
                        val cards = previewCards(byId[library.id]?.cards.orEmpty())
                        cards.takeIf { it.isNotEmpty() }?.let { LibrarySection(library, it) }
                    }
                    carousel = previewCards(
                        previews.value
                            .filter { it.carouselEnabled }
                            .flatMap { it.cards },
                        limit = CAROUSEL_MAX_ITEMS,
                    )
                }
            }
            if (carousel.isEmpty()) {
                carousel = previewCards(recent, limit = CAROUSEL_MAX_ITEMS)
            }
            if (requestId != homeRequestId) return@launch
            _state.update {
                it.copy(
                    carouselItems = carousel,
                    librarySections = sections.ifEmpty { it.librarySections },
                )
            }
        }
    }

    private fun previewCards(
        cards: List<SeriesCardDto>,
        limit: Int = HOME_PREVIEW_LIMIT,
    ): List<SeriesCardDto> =
        cards.asSequence()
            .filter { it.rep.backdropUrl.isNotBlank() || it.rep.posterUrl.isNotBlank() }
            .distinctBy { it.key.ifBlank { it.rep.id } }
            .take(limit)
            .toList()

    private fun refreshContinueWatching() {
        viewModelScope.launch {
            val result = repo.continueWatching(12)
            if (result is ApiResult.Success) {
                _state.update { it.copy(continueItems = result.value) }
            }
        }
    }

    fun openFavorites() {
        _state.update { it.copy(screen = MediaScreen.Favorites, favoritesError = null) }
        refreshFavorites()
    }

    fun refreshFavorites() {
        _state.update { it.copy(favoritesLoading = true, favoritesError = null) }
        viewModelScope.launch {
            when (val result = repo.favorites()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        favoritesLoading = false,
                        favorites = result.value.items.distinctBy { media -> media.id },
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        favoritesLoading = false,
                        favoritesError = ErrorMessages.action(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(favoritesLoading = false, favoritesError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(favoritesLoading = false) }
            }
        }
    }

    fun removeFavorite(media: MediaDto) {
        val previous = _state.value.favorites
        _state.update {
            it.copy(favorites = previous.filterNot { item -> item.id == media.id })
        }
        viewModelScope.launch {
            when (val result = repo.setFavorite(media.id, false)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        favorites = previous,
                        favoritesError = ErrorMessages.action(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(favorites = previous, favoritesError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> Unit
            }
        }
    }

    fun openHistory() {
        _state.update { it.copy(screen = MediaScreen.History, historyError = null) }
        refreshHistory()
    }

    fun refreshHistory() {
        _state.update { it.copy(historyLoading = true, historyError = null) }
        viewModelScope.launch {
            when (val result = repo.watchHistory(HISTORY_LIMIT)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        historyLoading = false,
                        historyItems = result.value.filter { item -> item.media != null },
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        historyLoading = false,
                        historyError = ErrorMessages.action(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(historyLoading = false, historyError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(historyLoading = false) }
            }
        }
    }

    fun playHistory(item: WatchHistoryDto) {
        item.media?.let(::preparePlayback)
    }

    fun removeHistory(item: WatchHistoryDto) {
        // 删除接口按 media_id 工作；缺少 ID 时绝不能退化成“清空全部历史”。
        val mediaId = item.mediaId.ifBlank { item.media?.id.orEmpty() }
        if (mediaId.isBlank()) {
            _state.update { it.copy(historyError = "这条记录缺少媒体信息，无法删除") }
            return
        }
        val previous = _state.value.historyItems
        _state.update {
            it.copy(
                historyItems = previous.filterNot { row ->
                    row.id == item.id || row.mediaId == mediaId
                },
            )
        }
        viewModelScope.launch {
            when (val result = repo.clearWatchHistory(mediaId)) {
                is ApiResult.Success -> refreshContinueWatching()
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        historyItems = previous,
                        historyError = ErrorMessages.action(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(historyItems = previous, historyError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> Unit
            }
        }
    }

    fun clearHistory() {
        val previous = _state.value.historyItems
        _state.update { it.copy(historyItems = emptyList()) }
        viewModelScope.launch {
            when (val result = repo.clearWatchHistory(null)) {
                is ApiResult.Success -> {
                    refreshContinueWatching()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        historyItems = previous,
                        historyError = ErrorMessages.action(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(historyItems = previous, historyError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> Unit
            }
        }
    }

    fun openLibrary(library: LibraryDto) {        libraryLoadJob?.cancel()
        _state.update {
            it.copy(
                screen = MediaScreen.Library(library),
                libraryCards = emptyList(),
                libraryTotal = 0,
                libraryPage = 0,
                libraryLoading = false,
                libraryError = null,
                libraryFilter = LibraryFilter(),
                librarySort = LibrarySortSelection(),
                libraryFacets = null,
                libraryFacetsLoading = true,
            )
        }
        loadLibraryFacets(library.id)
        loadMoreLibrary()
    }

    private fun loadLibraryFacets(libraryId: String) {
        viewModelScope.launch {
            when (val result = repo.libraryFacets(libraryId)) {
                is ApiResult.Success -> _state.update {
                    if ((it.screen as? MediaScreen.Library)?.library?.id == libraryId) {
                        it.copy(libraryFacets = result.value, libraryFacetsLoading = false)
                    } else {
                        it
                    }
                }
                else -> _state.update { it.copy(libraryFacetsLoading = false) }
            }
        }
    }

    fun applyLibraryBrowse(filter: LibraryFilter, sort: LibrarySortSelection) {
        libraryLoadJob?.cancel()
        _state.update {
            it.copy(
                libraryFilter = filter,
                librarySort = sort,
                libraryCards = emptyList(),
                libraryTotal = 0,
                libraryPage = 0,
                libraryLoading = false,
                libraryError = null,
            )
        }
        loadMoreLibrary()
    }

    fun resetLibraryBrowse() {
        applyLibraryBrowse(LibraryFilter(), LibrarySortSelection())
    }

    fun toggleLibrarySortDirection() {
        val current = _state.value
        applyLibraryBrowse(
            current.libraryFilter,
            current.librarySort.copy(descending = !current.librarySort.descending),
        )
    }

    fun selectLibrarySort(option: LibrarySort) {
        val current = _state.value
        if (current.librarySort.option == option) {
            toggleLibrarySortDirection()
        } else {
            applyLibraryBrowse(current.libraryFilter, LibrarySortSelection(option, option.defaultDescending))
        }
    }

    fun loadMoreLibrary() {
        val current = _state.value
        val screen = current.screen as? MediaScreen.Library ?: return
        if (current.libraryLoading) return
        if (current.libraryPage > 0 && current.libraryCards.size >= current.libraryTotal) return
        val nextPage = current.libraryPage + 1
        _state.update { it.copy(libraryLoading = true, libraryError = null) }
        libraryLoadJob = viewModelScope.launch {
            val result = repo.librarySeries(
                libraryId = screen.library.id,
                page = nextPage,
                filter = current.libraryFilter,
                sort = current.librarySort,
            )
            if ((_state.value.screen as? MediaScreen.Library)?.library?.id != screen.library.id) {
                return@launch
            }
            when (result) {
                is ApiResult.Success -> {
                    val page = result.value
                    _state.update {
                        it.copy(
                            libraryLoading = false,
                            libraryPage = nextPage,
                            libraryTotal = page.total,
                            libraryCards = if (nextPage == 1) {
                                page.items
                            } else {
                                it.libraryCards + page.items.filterNot { card ->
                                    card.key.isNotBlank() &&
                                        it.libraryCards.any { existing -> existing.key == card.key }
                                }
                            },
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(libraryLoading = false, libraryError = ErrorMessages.action(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(libraryLoading = false, libraryError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> _state.update { it.copy(libraryLoading = false) }
            }
        }
    }

    fun playRandom() {
        val current = _state.value
        val screen = current.screen as? MediaScreen.Library ?: return
        if (current.libraryRandomLoading) return
        _state.update { it.copy(libraryRandomLoading = true, libraryError = null) }
        viewModelScope.launch {
            val result = repo.randomMedia(screen.library.id, current.libraryFilter)
            _state.update { it.copy(libraryRandomLoading = false) }
            when (result) {
                is ApiResult.Success -> preparePlayback(result.value)
                is ApiResult.Failure -> _state.update {
                    it.copy(libraryError = ErrorMessages.action(result))
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(libraryError = "网络不可用，请稍后重试")
                }
                ApiResult.Stale -> Unit
            }
        }
    }

    fun onSearchQueryChange(value: String) {
        _state.update {
            it.copy(
                searchQuery = value,
                searchResults = emptyList(),
                searchLoading = value.isNotBlank(),
                searchLoadingMore = false,
                searchError = null,
                searchPage = 0,
                searchTotal = 0,
            )
        }
        searchJob?.cancel()
        if (value.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(350)
            searchNow(value, page = 1, append = false)
        }
    }

    fun submitSearch() {
        searchJob?.cancel()
        val query = _state.value.searchQuery
        if (query.isBlank()) return
        searchJob = viewModelScope.launch { searchNow(query, page = 1, append = false) }
    }

    fun loadMoreSearch() {
        val current = _state.value
        val query = current.searchQuery
        if (query.isBlank() || current.searchLoading || current.searchLoadingMore) return
        if (current.searchPage > 0 && current.searchResults.size >= current.searchTotal) return
        searchJob = viewModelScope.launch {
            searchNow(query, page = current.searchPage + 1, append = true)
        }
    }

    private suspend fun searchNow(query: String, page: Int, append: Boolean) {
        _state.update {
            it.copy(
                searchLoading = !append,
                searchLoadingMore = append,
                searchError = null,
            )
        }
        when (val result = repo.search(query, page)) {
            is ApiResult.Success -> _state.update {
                val merged = if (append) {
                    it.searchResults + result.value.items.filterNot { item ->
                        it.searchResults.any { existing -> existing.id == item.id }
                    }
                } else {
                    result.value.items
                }
                it.copy(
                    searchLoading = false,
                    searchLoadingMore = false,
                    searchResults = merged,
                    searchPage = page,
                    searchTotal = result.value.total,
                )
            }
            is ApiResult.Failure -> _state.update {
                it.copy(searchLoading = false, searchLoadingMore = false, searchError = ErrorMessages.action(result))
            }
            is ApiResult.NetworkError -> _state.update {
                it.copy(searchLoading = false, searchLoadingMore = false, searchError = "网络不可用，请稍后重试")
            }
            ApiResult.Stale -> _state.update {
                it.copy(searchLoading = false, searchLoadingMore = false)
            }
        }
    }

    fun openCard(card: SeriesCardDto) {
        val seriesKey = card.key.takeIf { card.isSeries || card.count > 1 }
        openDetail(card.rep.id, seriesKey, card.libraryId)
    }

    fun openContinue(item: ContinueWatchingDto) {
        preparePlayback(item.media)
    }

    fun openMedia(media: MediaDto) {
        openDetail(media.id, null, media.libraryId)
    }

    fun playMedia(media: MediaDto) {
        preparePlayback(media)
    }

    fun retryDetail() {
        val screen = _state.value.screen as? MediaScreen.Detail ?: return
        loadDetail(screen.mediaId, screen.seriesKey, screen.libraryId)
    }

    private fun openDetail(mediaId: String, seriesKey: String?, libraryId: String?) {
        val returnScreen = _state.value.screen
        _state.update {
            it.copy(
                screen = MediaScreen.Detail(mediaId, seriesKey, libraryId),
                detailReturn = returnScreen,
                detail = null,
                episodes = emptyList(),
                favorite = false,
                favoriteError = null,
                detailLoading = true,
                detailError = null,
                detailResumeMs = 0L,
                selectedVersionId = null,
            )
        }
        loadDetail(mediaId, seriesKey, libraryId)
    }

    private fun loadDetail(mediaId: String, seriesKey: String?, libraryId: String?) {
        val requestId = ++detailRequestId
        _state.update { it.copy(detailLoading = true, detailError = null) }
        viewModelScope.launch {
            val detail = repo.mediaDetail(mediaId)
            if (requestId != detailRequestId) return@launch
            if (detail !is ApiResult.Success) {
                _state.update {
                    it.copy(
                        detailLoading = false,
                        detailError = when (detail) {
                            is ApiResult.Failure -> ErrorMessages.action(detail)
                            is ApiResult.NetworkError -> "网络不可用，请稍后重试"
                            else -> "加载失败"
                        },
                    )
                }
                return@launch
            }
            coroutineScope {
                val episodesDeferred = async {
                    if (seriesKey != null && libraryId != null) {
                        repo.seriesEpisodes(libraryId, seriesKey)
                    } else {
                        repo.mediaEpisodes(mediaId)
                    }
                }
                val favoriteDeferred = async { repo.favoriteStatus(mediaId) }
                val resumeDeferred = async { repo.resume(mediaId) }
                val episodes = episodesDeferred.await()
                val favorite = favoriteDeferred.await()
                val resume = resumeDeferred.await()
                if (requestId != detailRequestId) return@coroutineScope
                _state.update {
                    it.copy(
                        detailLoading = false,
                        detail = detail.value,
                        episodes = (episodes as? ApiResult.Success)?.value?.items ?: emptyList(),
                        favorite = (favorite as? ApiResult.Success)?.value?.favourite ?: false,
                        detailResumeMs = (resume as? ApiResult.Success)?.value?.positionMs ?: 0L,
                    )
                }
            }
        }
    }

    fun selectVersion(media: MediaDto?) {
        _state.update { it.copy(selectedVersionId = media?.id) }
    }

    fun playDetail() {
        val detail = _state.value.detail ?: return
        val version = detail.versions.firstOrNull { it.id == _state.value.selectedVersionId } ?: detail
        preparePlayback(version)
    }

    fun playEpisode(episode: MediaDto) {
        preparePlayback(episode)
    }

    private fun preparePlayback(media: MediaDto) {
        val requestId = ++playbackRequestId
        lastPlayedMedia = media
        val episodes = _state.value.episodes
        val currentIndex = episodes.indexOfFirst { it.id == media.id }
        val next = if (currentIndex >= 0) episodes.getOrNull(currentIndex + 1) else null
        lastNextMedia = next
        val current = _state.value
        // 从播放页切下一集时保留最初的返回目标，避免返回一个已经不存在的播放页。
        val returnScreen = if (current.screen is MediaScreen.Player) {
            current.playerReturn
        } else {
            current.screen
        }
        _state.update {
            it.copy(
                screen = MediaScreen.Player(media.id, media.title),
                playerReturn = returnScreen,
                playerRequest = null,
                playerLoading = true,
                playerError = null,
                danmaku = it.danmaku.copy(
                    loading = true,
                    comments = emptyList(),
                    candidates = emptyList(),
                    alternatives = emptyList(),
                    matchLabel = null,
                    mergedSources = 0,
                    error = null,
                    loadedMediaId = null,
                ),
            )
        }
        viewModelScope.launch {
            val accessToken = credentialStore.read(scope)?.accessToken
            val result = coroutineScope {
                val info = async { repo.playbackInfo(media.id) }
                val resume = async { repo.resume(media.id) }
                // 直连模式只查询外挂字幕文件；内嵌文本字幕由 ExoPlayer 直接从
                // 视频容器里解出并渲染，不再让服务端跑 ffprobe/字幕转换。
                val subtitles = async {
                    repo.subtitles(media.id, includeEmbedded = false)
                }
                PlaybackBundle(info.await(), resume.await(), subtitles.await())
            }
            if (requestId != playbackRequestId) return@launch

            val info = result.info
            if (info !is ApiResult.Success) {
                _state.update {
                    it.copy(
                        playerLoading = false,
                        playerError = when (info) {
                            is ApiResult.Failure -> ErrorMessages.action(info)
                            is ApiResult.NetworkError -> "网络不可用，请稍后重试"
                            else -> "无法获取播放地址"
                        },
                    )
                }
                return@launch
            }

            val resumeValue = (result.resume as? ApiResult.Success)?.value
            val cached = progressStore.read(scope, media.id)
            val duration = resumeValue?.durationMs
                ?.takeIf { it > 0 }
                ?: (media.durationSec * 1000L)
            val start = PlaybackPlan.startPosition(
                serverPositionMs = resumeValue?.positionMs ?: 0L,
                cachedPositionMs = cached?.positionMs ?: 0L,
                durationMs = duration,
                completed = resumeValue?.completed == true,
            )

            val infoValue = info.value
            // App 只使用直链：不请求 HLS/转码地址，解码全部交给本机。
            val streamUrl = absolute(infoValue.streamUrl)
                ?.let { PlaybackUrls.withAccessToken(it, accessToken) }
            if (streamUrl == null) {
                _state.update {
                    it.copy(
                        playerLoading = false,
                        playerError = "服务器没有返回直链播放地址（App 仅使用本机解码）",
                    )
                }
                return@launch
            }

            // 只做能力提示，不再据此切换服务端转码。
            val decodeAssessment = withContext(Dispatchers.Default) {
                codecSupport.assess(media)
            }
            val decoderNote = decodeAssessment.note

            val rawTracks = (result.subtitles as? ApiResult.Success)?.value?.tracks.orEmpty()
            val subtitles = rawTracks
                .asSequence()
                .mapNotNull { track -> toPlaybackSubtitle(media.id, track, accessToken) }
                .distinctBy { it.key }
                .toList()
            val imageSubtitles = imageSubtitleLabels(rawTracks)

            _state.update {
                it.copy(
                    playerLoading = false,
                    playerRequest = PlaybackRequest(
                        mediaId = media.id,
                        title = media.title,
                        subtitle = media.displayEpisode.ifBlank { media.episodeTitle }.ifBlank { null },
                        streamUrl = streamUrl,
                        startPositionMs = start,
                        durationMs = duration,
                        subtitles = subtitles,
                        imageSubtitles = imageSubtitles,
                        decoderNote = decoderNote,
                        nextEpisode = next?.let { item ->
                            PlaybackNext(
                                mediaId = item.id,
                                title = item.displayEpisode.ifBlank { item.episodeTitle }.ifBlank { item.title },
                            )
                        },
                    ),
                )
            }

            // 弹幕与字幕互不阻塞：先起播，再在后台请求识别与匹配。
            loadDanmaku(media.id)
        }
    }

    fun playNextEpisode() {
        lastNextMedia?.let(::preparePlayback)
    }

    // ── 弹幕 ──

    /**
     * 通过服务端 `/api/danmaku/:id` 识别并拉取弹幕。服务端会依次尝试
     * 哈希匹配、元数据搜索和文件名搜索；`episodeId` 用于用户从候选里选定
     * 具体弹幕库后直接拉取。
     */
    private fun loadDanmaku(mediaId: String, episodeId: Long? = null) {
        val requestId = ++danmakuRequestId
        _state.update {
            it.copy(
                danmaku = it.danmaku.copy(
                    loading = true,
                    error = null,
                    // 手动选库时旧的候选列表立即失效，避免面板里重复点击。
                    candidates = if (episodeId != null) emptyList() else it.danmaku.candidates,
                ),
            )
        }
        viewModelScope.launch {
            val result = repo.danmaku(mediaId, episodeId = episodeId)
            if (requestId != danmakuRequestId) return@launch
            if ((_state.value.screen as? MediaScreen.Player)?.mediaId != mediaId) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val payload = result.value
                    val comments = if (payload.enabled && payload.raw.isNotBlank()) {
                        withContext(Dispatchers.Default) {
                            DanmakuParser.parse(payload.raw, payload.sourceType)
                        }
                    } else {
                        emptyList()
                    }
                    val label = listOf(payload.animeTitle, payload.episodeTitle)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { null }
                    _state.update { current ->
                        if ((current.screen as? MediaScreen.Player)?.mediaId != mediaId) {
                            current
                        } else {
                            current.copy(
                                danmaku = current.danmaku.copy(
                                    loading = false,
                                    enabled = payload.enabled,
                                    opacity = payload.opacity.toFloatOrNull()
                                        ?.coerceIn(0.1f, 1f)
                                        ?: current.danmaku.opacity,
                                    fontScale = ((payload.fontSize.toFloatOrNull() ?: 24f) / 24f)
                                        .coerceIn(0.6f, 2f),
                                    area = payload.area.toFloatOrNull()
                                        ?.coerceIn(0.1f, 1f)
                                        ?: current.danmaku.area,
                                    comments = comments,
                                    candidates = payload.candidates,
                                    alternatives = payload.alternatives,
                                    matchLabel = label,
                                    mergedSources = payload.mergedSources,
                                    error = null,
                                    loadedMediaId = mediaId,
                                ),
                            )
                        }
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        danmaku = it.danmaku.copy(
                            loading = false,
                            error = when {
                                result.status == 404 -> "没有找到匹配的弹幕"
                                result.status == 401 || result.status == 403 ->
                                    "弹幕加载失败，请检查登录状态"
                                else -> "弹幕加载失败（HTTP ${result.status}）"
                            },
                        ),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(
                        danmaku = it.danmaku.copy(
                            loading = false,
                            error = "网络不可用，弹幕加载失败",
                        ),
                    )
                }
                ApiResult.Stale -> _state.update {
                    it.copy(danmaku = it.danmaku.copy(loading = false))
                }
            }
        }
    }

    /** 重新识别并加载当前视频的弹幕（面板里的“重新加载”）。 */
    fun reloadDanmaku() {
        val mediaId = (_state.value.screen as? MediaScreen.Player)?.mediaId ?: return
        loadDanmaku(mediaId)
    }

    /** 用户从候选列表里选定一个具体弹幕库。 */
    fun selectDanmakuLibrary(episodeId: Long) {
        if (episodeId <= 0L) return
        val mediaId = (_state.value.screen as? MediaScreen.Player)?.mediaId ?: return
        loadDanmaku(mediaId, episodeId = episodeId)
    }

    /** 显示/隐藏弹幕；打开时若还没有数据会先保存开关再重新拉取。 */
    fun setDanmakuEnabled(enabled: Boolean) {
        _state.update { it.copy(danmaku = it.danmaku.copy(enabled = enabled)) }
        danmakuSaveJob?.cancel()
        danmakuSaveJob = viewModelScope.launch {
            delay(350)
            val snapshot = _state.value.danmaku
            val saved = repo.updateDanmakuSettings(snapshot.toSettingsPatch())
            if (saved !is ApiResult.Success) {
                _state.update {
                    it.copy(
                        danmaku = it.danmaku.copy(
                            error = "弹幕开关保存失败，请稍后重试",
                        ),
                    )
                }
                return@launch
            }
            if (!enabled) return@launch
            val current = _state.value
            val mediaId = (current.screen as? MediaScreen.Player)?.mediaId ?: return@launch
            if (current.danmaku.comments.isEmpty() &&
                current.danmaku.candidates.isEmpty() &&
                !current.danmaku.loading
            ) {
                loadDanmaku(mediaId)
            }
        }
    }

    /** 调整弹幕透明度、字号或显示区域，并节流写回服务端偏好。 */
    fun setDanmakuRenderOptions(opacity: Float, fontScale: Float, area: Float) {
        _state.update {
            it.copy(
                danmaku = it.danmaku.copy(
                    opacity = opacity.coerceIn(0.1f, 1f),
                    fontScale = fontScale.coerceIn(0.6f, 2f),
                    area = area.coerceIn(0.1f, 1f),
                ),
            )
        }
        danmakuSaveJob?.cancel()
        danmakuSaveJob = viewModelScope.launch {
            delay(700)
            repo.updateDanmakuSettings(_state.value.danmaku.toSettingsPatch())
        }
    }

    private fun DanmakuUiState.toSettingsPatch(): DanmakuSettingsPatchDto =
        DanmakuSettingsPatchDto(
            enabled = enabled,
            opacity = opacity,
            fontSize = (STANDARD_DANMAKU_FONT * fontScale).roundToInt().coerceIn(10, 96),
            area = area,
        )

    /**
     * 直连播放模式下只接受文本字幕。普通格式走服务端转出的 WebVTT；
     * ASS/SSA 额外保留原文件地址，让内核按字幕自带样式渲染。
     * PGS/DVD 等图片字幕需要位图渲染器，Media3 不支持，直接忽略。
     */
    private fun toPlaybackSubtitle(
        mediaId: String,
        track: SubtitleTrackDto,
        accessToken: String?,
    ): PlaybackSubtitle? {
        if (track.burnIn) return null
        if (!track.renderable || track.path.isBlank()) return null
        val label = track.label.ifBlank { track.lang }.ifBlank { "字幕" }
        val suffix = when (track.source) {
            "embedded" -> "（内嵌）"
            "external" -> "（外挂）"
            else -> ""
        }
        val codec = track.codec.trim().lowercase()
        // ASS/SSA 的样式只存在于原文件里：服务端的 VTT 接口会把标签全部剥掉，
        // 因此额外给出 /ass 地址，交给内置 libass 的 LibVLC 内核渲染。
        val assStyled = codec == "ass" || codec == "ssa" ||
            track.delivery.equals("ass", ignoreCase = true)
        return PlaybackSubtitle(
            lang = track.lang.ifBlank { "und" },
            label = "$label$suffix" + if (assStyled) " · ASS 样式" else "",
            url = PlaybackUrls.subtitleUrl(origin, mediaId, track.path, accessToken),
            assUrl = if (assStyled) {
                PlaybackUrls.assSubtitleUrl(origin, mediaId, track.path, accessToken)
            } else {
                null
            },
            codec = codec,
        )
    }

    private fun imageSubtitleLabels(tracks: List<SubtitleTrackDto>): List<String> =
        tracks.asSequence()
            .filter { it.burnIn }
            .map { it.label.ifBlank { it.lang }.ifBlank { "图片字幕" } }
            .distinct()
            .toList()

    fun retryPlayback() {
        lastPlayedMedia?.let(::preparePlayback)
    }

    fun onPlayerFailed(message: String) {
        _state.update { it.copy(playerError = message) }
    }

    /**
     * 播放进度回调。播放器每 5 秒调用一次，这里先落本地，再按 15 秒节流
     * 同步服务端；暂停、结束、退出时 [force] 为 true 立即同步。
     */
    fun reportProgress(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false,
    ) {
        if (positionMs <= 0L || mediaId.isBlank()) return
        progressStore.save(scope, mediaId, positionMs, durationMs)
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastServerSyncAt < SERVER_SYNC_INTERVAL_MS) return
        lastServerSyncAt = now
        viewModelScope.launch {
            repo.reportProgress(mediaId, positionMs, durationMs)
        }
    }

    fun toggleFavorite() {
        val media = _state.value.detail ?: return
        val target = !_state.value.favorite
        _state.update { it.copy(favorite = target, favoriteError = null) }
        viewModelScope.launch {
            when (val result = repo.setFavorite(media.id, target)) {
                is ApiResult.Success -> {
                    val favorite = result.value.favourite
                    _state.update { current ->
                        current.copy(
                            favorite = favorite,
                            favoriteError = null,
                            favorites = when {
                                favorite && current.favorites.none { it.id == media.id } ->
                                    listOf(media) + current.favorites
                                !favorite ->
                                    current.favorites.filterNot { it.id == media.id }
                                else -> current.favorites
                            },
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        favorite = !target,
                        favoriteError = ErrorMessages.action(result),
                    )
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(
                        favorite = !target,
                        favoriteError = "网络不可用，请稍后重试",
                    )
                }
                ApiResult.Stale -> _state.update { it.copy(favorite = !target) }
            }
        }
    }

    fun back() {
        val current = _state.value
        val leavingPlayer = current.screen is MediaScreen.Player
        if (leavingPlayer) {
            playbackRequestId++
            danmakuRequestId++
            refreshContinueWatching()
        }
        if (current.screen is MediaScreen.Detail) {
            detailRequestId++
        }
        val next = when (current.screen) {
            is MediaScreen.Player -> {
                refreshContinueWatching()
                current.playerReturn
            }
            is MediaScreen.Detail -> current.detailReturn
            is MediaScreen.Library -> MediaScreen.Tabs
            MediaScreen.Favorites -> MediaScreen.Tabs
            MediaScreen.History -> MediaScreen.Tabs
            MediaScreen.Tabs -> MediaScreen.Tabs
        }
        _state.update {
            it.copy(
                screen = next,
                playerRequest = null,
                playerError = null,
                playerLoading = false,
                // 保留用户的开关/外观偏好，清掉属于上一部视频的弹幕内容。
                danmaku = if (leavingPlayer) {
                    it.danmaku.copy(
                        loading = false,
                        comments = emptyList(),
                        candidates = emptyList(),
                        alternatives = emptyList(),
                        matchLabel = null,
                        mergedSources = 0,
                        error = null,
                        loadedMediaId = null,
                    )
                } else {
                    it.danmaku
                },
            )
        }
        // 从详情/播放返回收藏或历史时刷新，保证收藏状态和进度是最新的。
        if (next == MediaScreen.Favorites) refreshFavorites()
        if (next == MediaScreen.History) refreshHistory()
    }

    fun createImageLoader(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val token = if (request.url.toString().startsWith(origin)) {
                    credentialStore.read(scope)?.accessToken
                } else {
                    null
                }
                if (token.isNullOrBlank()) {
                    chain.proceed(request)
                } else {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    )
                }
            }
            .build()
        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }

    private fun absolute(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            origin + (if (value.startsWith("/")) value else "/$value")
        }
    }

    private fun firstError(vararg results: ApiResult<*>): String? {
        for (result in results) {
            when (result) {
                is ApiResult.Failure -> return ErrorMessages.action(result)
                is ApiResult.NetworkError -> return "网络不可用，请稍后重试"
                else -> Unit
            }
        }
        return null
    }

    private data class PlaybackBundle(
        val info: ApiResult<PlaybackInfoDto>,
        val resume: ApiResult<app.mebox.android.core.network.ResumeDto>,
        val subtitles: ApiResult<app.mebox.android.core.network.SubtitleTracksDto>?,
    )

    class Factory(
        private val repository: MediaRepository,
        private val origin: String,
        private val scope: AccountScope,
        private val credentialStore: CredentialStore,
        private val progressStore: WatchProgressStore,
        private val codecSupport: DeviceCodecSupport,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MediaViewModel::class.java))
            return MediaViewModel(
                repository,
                origin,
                scope,
                credentialStore,
                progressStore,
                codecSupport,
            ) as T
        }
    }

    private companion object {
        /** 服务端进度同步间隔；播放器按 5 秒回调，这里做节流。 */
        const val SERVER_SYNC_INTERVAL_MS = 15_000L

        /** 首页轮播最多展示的条目数。 */
        const val CAROUSEL_MAX_ITEMS = 10

        /** 首页每个媒体库滑轨的预览条数。 */
        const val HOME_PREVIEW_LIMIT = 12

        /** 观看历史一次加载的条数；服务端上限为 500。 */
        const val HISTORY_LIMIT = 200

        /** 服务端默认弹幕字号；本地缩放比例以它为基准。 */
        const val STANDARD_DANMAKU_FONT = 24f
    }
}
