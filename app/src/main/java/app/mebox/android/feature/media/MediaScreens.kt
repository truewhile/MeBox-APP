package app.mebox.android.feature.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.LocalImageLoader
import app.mebox.android.core.media.LibraryFilter
import app.mebox.android.core.media.LibrarySort
import app.mebox.android.core.media.LibrarySortSelection
import app.mebox.android.core.media.MediaBrowseFormat
import app.mebox.android.core.media.MediaImageUrls
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.network.ContinueWatchingDto
import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.LibraryFacetsDto
import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.SeriesCardDto
import app.mebox.android.core.network.WatchHistoryDto
import app.mebox.android.core.reader.ReaderContinue
import app.mebox.android.core.ui.MeBoxButton
import app.mebox.android.core.ui.MeBoxCard
import app.mebox.android.core.ui.MeBoxChip
import app.mebox.android.core.ui.MeBoxDimens
import app.mebox.android.core.ui.MeBoxEmptyState
import app.mebox.android.core.ui.MeBoxFloatingNavigation
import app.mebox.android.core.ui.MeBoxIconButton
import app.mebox.android.core.ui.MeBoxOutlinedButton
import app.mebox.android.core.ui.MeBoxSearchField
import app.mebox.android.core.ui.MeBoxSegmentedBar
import app.mebox.android.core.ui.MeBoxTopBar
import app.mebox.android.feature.AppUiState
import app.mebox.android.feature.MainViewModel
import app.mebox.android.feature.ProfileContent
import app.mebox.android.feature.media.player.PlayerScreen
import app.mebox.android.feature.reader.ReaderReadingScreen
import app.mebox.android.feature.reader.ReaderScreen
import app.mebox.android.feature.reader.ReaderBookDetailContent
import app.mebox.android.feature.reader.ReaderSearchContent
import app.mebox.android.feature.reader.ReaderShelfContent
import app.mebox.android.feature.reader.ReaderSourcesContent
import app.mebox.android.feature.reader.ReaderUiState
import app.mebox.android.feature.reader.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/**
 * 登录后的主界面：底部四个入口（首页 / 影视 / 阅读 / 我的）。
 *
 * 影视与阅读分别由独立 ViewModel 持有，均按账号分区：切换账号后不会复用旧账号的
 * 列表、播放或阅读状态。阅读页为全屏界面，打开时覆盖底部导航。
 */
@Composable
fun AccountHost(account: AppUiState.Account, mainViewModel: MainViewModel) {
    val factory = remember(account.scope, account.address.origin) {
        mainViewModel.mediaFactory(account.scope, account.address)
    }
    if (factory == null) {
        ProfileContent(account, mainViewModel)
        return
    }
    val mediaViewModel: MediaViewModel = viewModel(
        key = "media-${account.scope.serverId}-${account.scope.userId}",
        factory = factory,
    )
    val readerFactory = remember(account.scope, account.address.origin) {
        mainViewModel.readerFactory(account.scope, account.address)
    }
    if (readerFactory == null) {
        ProfileContent(account, mainViewModel)
        return
    }
    val readerViewModel: ReaderViewModel = viewModel(
        key = "reader-${account.scope.serverId}-${account.scope.userId}",
        factory = readerFactory,
    )
    val state by mediaViewModel.state.collectAsStateWithLifecycle()
    val readerState by readerViewModel.state.collectAsStateWithLifecycle()
    val playerEngine by mainViewModel.playerEngine.collectAsStateWithLifecycle()
    val subtitlePreference by mainViewModel.subtitlePreference.collectAsStateWithLifecycle()
    val decoderMode by mainViewModel.decoderMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader = remember(mediaViewModel) { mediaViewModel.createImageLoader(context) }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        when {
            readerState.screen == ReaderScreen.Reading -> ReaderReadingScreen(
                state = readerState,
                origin = account.address.origin,
                viewModel = readerViewModel,
            )
            readerState.screen == ReaderScreen.Search -> ReaderSearchContent(
                state = readerState,
                origin = account.address.origin,
                viewModel = readerViewModel,
            )
            readerState.screen == ReaderScreen.BookDetail -> ReaderBookDetailContent(
                state = readerState,
                origin = account.address.origin,
                viewModel = readerViewModel,
            )
            readerState.screen == ReaderScreen.Sources -> ReaderSourcesContent(
                state = readerState,
                origin = account.address.origin,
                viewModel = readerViewModel,
            )
            else -> when (val screen = state.screen) {
                is MediaScreen.Player -> PlayerScreen(
                    request = state.playerRequest,
                    loading = state.playerLoading,
                    error = state.playerError,
                    title = screen.title,
                    danmaku = state.danmaku,
                    engine = playerEngine,
                    subtitlePreference = subtitlePreference,
                    decoderMode = decoderMode,
                    onEngineSelected = mainViewModel::selectPlayerEngine,
                    onBack = mediaViewModel::back,
                    onProgress = mediaViewModel::reportProgress,
                    onError = mediaViewModel::onPlayerFailed,
                    onRetry = mediaViewModel::retryPlayback,
                    onPlayNext = mediaViewModel::playNextEpisode,
                    onDanmakuEnabledChange = mediaViewModel::setDanmakuEnabled,
                    onDanmakuRenderChange = mediaViewModel::setDanmakuRenderOptions,
                    onDanmakuReload = mediaViewModel::reloadDanmaku,
                    onDanmakuSelectEpisode = mediaViewModel::selectDanmakuLibrary,
                )
                is MediaScreen.Detail -> DetailScreen(
                    state = state,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onPlay = mediaViewModel::playDetail,
                    onPlayEpisode = mediaViewModel::playEpisode,
                    onSelectVersion = mediaViewModel::selectVersion,
                    onRetry = mediaViewModel::retryDetail,
                    onToggleFavorite = mediaViewModel::toggleFavorite,
                )
                is MediaScreen.Library -> LibraryScreen(
                    state = state,
                    library = screen.library,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onOpenCard = mediaViewModel::openCard,
                    onLoadMore = mediaViewModel::loadMoreLibrary,
                    onApplyBrowse = mediaViewModel::applyLibraryBrowse,
                    onResetBrowse = mediaViewModel::resetLibraryBrowse,
                    onPlayRandom = mediaViewModel::playRandom,
                )
                MediaScreen.Favorites -> FavoritesScreen(
                    state = state,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onOpen = mediaViewModel::openMedia,
                    onRemove = mediaViewModel::removeFavorite,
                    onRetry = mediaViewModel::refreshFavorites,
                )
                MediaScreen.History -> HistoryScreen(
                    state = state,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onPlay = mediaViewModel::playHistory,
                    onRemove = mediaViewModel::removeHistory,
                    onClear = mediaViewModel::clearHistory,
                    onRetry = mediaViewModel::refreshHistory,
                )
                MediaScreen.Tabs -> TabsScaffold(
                    state = state,
                    readerState = readerState,
                    account = account,
                    mainViewModel = mainViewModel,
                    mediaViewModel = mediaViewModel,
                    readerViewModel = readerViewModel,
                )
            }
        }
        BackHandler(
            enabled = readerState.screen != ReaderScreen.Shelf ||
                (state.screen != MediaScreen.Tabs && state.screen !is MediaScreen.Player),
        ) {
            when (readerState.screen) {
                ReaderScreen.Reading -> readerViewModel.closeReading()
                ReaderScreen.BookDetail -> readerViewModel.dismissSearchDetail()
                ReaderScreen.Search -> readerViewModel.closeSearch()
                ReaderScreen.Sources -> readerViewModel.closeSources()
                ReaderScreen.Shelf -> mediaViewModel.back()
            }
        }
    }
}

@Composable
private fun TabsScaffold(
    state: MediaUiState,
    readerState: ReaderUiState,
    account: AppUiState.Account,
    mainViewModel: MainViewModel,
    mediaViewModel: MediaViewModel,
    readerViewModel: ReaderViewModel,
) {
    val tabs = listOf(
        Triple(MainTab.HOME, Icons.Default.Home, "首页"),
        Triple(MainTab.MEDIA, Icons.Default.PlayArrow, "影视"),
        Triple(MainTab.READER, Icons.AutoMirrored.Filled.List, "阅读"),
        Triple(MainTab.PROFILE, Icons.Default.Person, "我的"),
    )
    val readingBarEnabled by mainViewModel.homeReadingBar.collectAsStateWithLifecycle()
    val audio = readerState.audio
    val continueBook = remember(
        readerState.books,
        audio.bookId,
        audio.playing,
        audio.loading,
        audio.playWhenReady,
    ) {
        ReaderContinue.pick(
            books = readerState.books,
            activeAudioBookId = audio.bookId,
            audioActive = audio.bookId != null &&
                (audio.playing || audio.loading || audio.playWhenReady),
        )
    }
    val showReadingBar = readingBarEnabled && state.tab == MainTab.HOME
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            MeBoxFloatingNavigation(
                icons = tabs.map { it.second },
                labels = tabs.map { it.third },
                selectedIndex = tabs.indexOfFirst { it.first == state.tab }.coerceAtLeast(0),
                onSelect = { index ->
                    val tab = tabs.getOrNull(index)?.first ?: return@MeBoxFloatingNavigation
                    mediaViewModel.selectTab(tab)
                    if (tab == MainTab.READER) {
                        readerViewModel.ensureShelfLoaded()
                    }
                },
                onSearch = {
                    if (state.tab == MainTab.READER) {
                        readerViewModel.openSearch()
                    } else {
                        mediaViewModel.selectTab(MainTab.MEDIA)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.tab) {
                MainTab.HOME -> HomeScreen(
                    state = state,
                    origin = account.address.origin,
                    onContinue = mediaViewModel::openContinue,
                    onOpenCard = mediaViewModel::openCard,
                    onOpenLibrary = mediaViewModel::openLibrary,
                    onOpenHistory = mediaViewModel::openHistory,
                    onOpenMediaTab = { mediaViewModel.selectTab(MainTab.MEDIA) },
                    onSelectTag = mediaViewModel::selectLibraryTag,
                    onLoadMoreSections = mediaViewModel::loadMoreHomeSections,
                    onRefresh = mediaViewModel::refreshHome,
                    bottomContentPadding = if (showReadingBar && continueBook != null) {
                        100.dp
                    } else {
                        32.dp
                    },
                )
                MainTab.MEDIA -> MediaTabScreen(
                    state = state,
                    origin = account.address.origin,
                    onQueryChange = mediaViewModel::onSearchQueryChange,
                    onSubmitSearch = mediaViewModel::submitSearch,
                    onLoadMoreSearch = mediaViewModel::loadMoreSearch,
                    onOpenLibrary = mediaViewModel::openLibrary,
                    onOpenMedia = mediaViewModel::openMedia,
                    onOpenFavorites = mediaViewModel::openFavorites,
                    onOpenHistory = mediaViewModel::openHistory,
                    onSelectTag = mediaViewModel::selectLibraryTag,
                )
                MainTab.READER -> ReaderShelfContent(
                    state = readerState,
                    origin = account.address.origin,
                    viewModel = readerViewModel,
                )
                MainTab.PROFILE -> ProfileContent(
                    state = account,
                    viewModel = mainViewModel,
                    onOpenFavorites = mediaViewModel::openFavorites,
                    onOpenHistory = mediaViewModel::openHistory,
                )
            }
            if (showReadingBar) {
                continueBook?.let { book ->
                    HomeReadingBar(
                        book = book,
                        origin = account.address.origin,
                        listening = audio.bookId == book.id &&
                            (audio.playing || audio.loading || audio.playWhenReady),
                        chapterLabel = if (audio.bookId == book.id && audio.chapterIndex >= 0) {
                            ReaderContinue.chapterLabel(audio.chapterIndex, audio.chapterTitle)
                        } else {
                            ReaderContinue.chapterLabel(book)
                        },
                        onClick = {
                            if (book.isAudio) {
                                readerViewModel.openBookAndListen(book)
                            } else {
                                readerViewModel.openBook(book)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: MediaUiState,
    origin: String,
    onContinue: (ContinueWatchingDto) -> Unit,
    onOpenCard: (SeriesCardDto) -> Unit,
    onOpenLibrary: (LibraryDto) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMediaTab: () -> Unit,
    onSelectTag: (String?) -> Unit,
    onLoadMoreSections: () -> Unit,
    onRefresh: () -> Unit,
    bottomContentPadding: Dp = 32.dp,
) {
    val visibleLibraries = state.visibleLibraries
    val hasContent = state.continueItems.isNotEmpty() ||
        state.recentItems.isNotEmpty() ||
        state.libraries.isNotEmpty() ||
        state.carouselItems.isNotEmpty() ||
        state.librarySections.isNotEmpty()
    if (state.homeLoading && !hasContent) {
        HomeLoading()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            MeBoxTopBar(
                title = "首页",
                subtitle = homeSubtitle(state),
                actions = {
                    MeBoxIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = if (state.homeError != null) "重试" else "刷新",
                        onClick = onRefresh,
                    )
                },
            )
        }
        state.homeError?.let { error ->
            item {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.carouselItems.isNotEmpty()) {
            item {
                HomeCarousel(
                    items = state.carouselItems,
                    origin = origin,
                    onOpen = onOpenCard,
                )
            }
        }
        if (state.continueItems.isNotEmpty()) {
            item {
                MediaSection(title = "继续观看", subtitle = "接着上次的进度") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.continueItems, key = { it.media.id }) { item ->
                            ContinueWatchingCard(item, origin) { onContinue(item) }
                        }
                        item {
                            HistoryTile(onClick = onOpenHistory)
                        }
                    }
                }
            }
        }
        if (state.libraryTagTabs.size > 1) {
            item {
                LibraryTagBar(
                    tabs = state.libraryTagTabs,
                    selectedTag = state.selectedLibraryTag,
                    onSelect = onSelectTag,
                )
            }
        }
        if (visibleLibraries.isNotEmpty()) {
            item {
                MediaSection(
                    title = "媒体库",
                    subtitle = "${visibleLibraries.size} 个库",
                    actionLabel = "全部",
                    onAction = onOpenMediaTab,
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visibleLibraries, key = { it.id }) { library ->
                            LibrarySpotlightCard(
                                library = library,
                                origin = origin,
                                modifier = Modifier.width(214.dp),
                                pinned = library.id in state.pinnedLibraryIds,
                                onClick = { onOpenLibrary(library) },
                            )
                        }
                    }
                }
            }
        } else if (state.selectedLibraryTag != null) {
            item {
                Text(
                    "「${state.selectedLibraryTag}」标签下还没有媒体库，可在网页端「管理标签」里归类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.librarySections.isNotEmpty()) {
            items(state.librarySections, key = { it.library.id }) { section ->
                MediaSection(
                    title = section.library.name,
                    subtitle = "${section.cards.size} 部最近更新",
                    actionLabel = "更多",
                    onAction = { onOpenLibrary(section.library) },
                ) {
                    HomeCardRail(section.cards, origin, onOpenCard)
                }
            }
            if (state.homeSectionsRemaining > 0) {
                // 只在滑到底部、这个尾部条目真正被组合时才请求下一批，
                // 首屏不必为几十个库一次拉完预览。
                item(key = "home-sections-more") {
                    LaunchedEffect(state.librarySections.size) {
                        onLoadMoreSections()
                    }
                    MediaGridSkeleton(columns = 2, rows = 1, landscape = true)
                }
            }
        } else if (state.recentItems.isNotEmpty()) {
            item {
                MediaSection(
                    title = "最近添加",
                    subtitle = null,
                    actionLabel = "更多",
                    onAction = onOpenMediaTab,
                ) {
                    HomeCardRail(state.recentItems, origin, onOpenCard)
                }
            }
        }
        if (!hasContent && state.librarySections.isEmpty() &&
            !state.homeLoading && state.homeError == null
        ) {
            item { MeBoxEmptyState("还没有可显示的影视内容") }
        }
    }
}

private fun homeSubtitle(state: MediaUiState): String? {
    val libraries = state.visibleLibraries.size
    val recent = state.recentItems.size
    return when {
        libraries > 0 && recent > 0 -> "$libraries 个媒体库 · $recent 部最近更新"
        libraries > 0 -> "$libraries 个媒体库"
        recent > 0 -> "$recent 部最近更新"
        else -> null
    }
}

@Composable
private fun HomeCardRail(
    cards: List<SeriesCardDto>,
    origin: String,
    onOpen: (SeriesCardDto) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.key.ifBlank { it.rep.id } }) { card ->
            MediaPosterCard(
                title = seriesCardTitle(card),
                subtitle = seriesCardSubtitle(card),
                posterUrl = seriesCardPoster(origin, card),
                rating = card.rep.rating,
                badge = card.count.takeIf { it > 1 }?.let { "$it 集" },
                modifier = Modifier.width(MediaDimens.PosterWidth),
                onClick = { onOpen(card) },
            )
        }
    }
}

@Composable
private fun MediaSection(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MeBoxDimens.ActionRadius))
                        .clickable(onClick = onAction)
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun HistoryTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(MediaDimens.ContinueWidth)
            .clip(RoundedCornerShape(MediaDimens.WideRadius))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MediaDimens.WideRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "查看全部历史",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            "观看历史",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "按时间回看最近播放",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeLoading() {
    Column(Modifier.fillMaxSize()) {
        MeBoxTopBar(title = "首页", subtitle = null)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MediaDimens.WideRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.height(24.dp))
        MediaGridSkeleton(columns = 3, rows = 2)
    }
}


@Composable
private fun HomeCarousel(
    items: List<SeriesCardDto>,
    origin: String,
    onOpen: (SeriesCardDto) -> Unit,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })

    // 5 秒自动切换；用户拖动期间暂停，动画结束后继续。
    LaunchedEffect(pagerState, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(5_000)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
            }
        }
    }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pagerState.currentPage >= items.size) {
            pagerState.scrollToPage(0)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val card = items[page]
            CarouselHero(
                card = card,
                origin = origin,
                onClick = { onOpen(card) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(5.dp)
                            .width(if (selected) 20.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CarouselHero(
    card: SeriesCardDto,
    origin: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = card.rep
    val backdrop = MediaImageUrls.backdropHero(
        origin = origin,
        remote = media.backdropUrl.ifBlank { media.posterUrl },
    )
    val library = media.libraryName
    val genres = media.genres
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(" · ")
    val meta = listOfNotNull(
        media.year.takeIf { it > 0 }?.toString(),
        media.rating.takeIf { it > 0 }?.let { "%.1f".format(it) },
        card.count.takeIf { it > 1 }?.let { "$it 集" },
        media.displayEpisode.ifBlank { null },
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        MediaArtwork(
            url = backdrop,
            title = media.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x1A000000),
                        0.45f to Color(0x40000000),
                        1f to Color(0xF0000000),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    "焦点推荐",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (library.isNotBlank()) {
                Text(
                    library,
                    color = Color(0xFFE6E6E6),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                media.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (media.rating > 0f) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD166),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        meta.joinToString(" · "),
                        color = Color(0xFFE0E0E0),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (genres.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    genres,
                    color = Color(0xFFBFBFBF),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "查看详情",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun MediaTabScreen(
    state: MediaUiState,
    origin: String,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onLoadMoreSearch: () -> Unit,
    onOpenLibrary: (LibraryDto) -> Unit,
    onOpenMedia: (MediaDto) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onSelectTag: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MeBoxTopBar(
            title = "影视",
            subtitle = if (state.visibleLibraries.isNotEmpty()) {
                "${state.visibleLibraries.size} 个媒体库"
            } else {
                null
            },
            actions = {
                MeBoxIconButton(
                    icon = Icons.Default.Favorite,
                    contentDescription = "我的收藏",
                    onClick = onOpenFavorites,
                )
                Spacer(Modifier.width(8.dp))
                MeBoxIconButton(
                    icon = Icons.Default.DateRange,
                    contentDescription = "观看历史",
                    onClick = onOpenHistory,
                )
            },
        )
        MeBoxSearchField(
            value = state.searchQuery,
            onValueChange = onQueryChange,
            placeholder = "搜索影视",
            onSearch = onSubmitSearch,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        )
        when {
            state.searchQuery.isBlank() -> LibraryIndex(state, origin, onOpenLibrary, onSelectTag)
            state.searchLoading && state.searchResults.isEmpty() -> {
                Spacer(Modifier.height(8.dp))
                MediaGridSkeleton(columns = 3, rows = 3)
            }
            state.searchError != null && state.searchResults.isEmpty() ->
                ErrorState(state.searchError) { onSubmitSearch() }
            state.searchResults.isEmpty() -> EmptyState("没有找到相关内容")
            else -> {
                val gridState = rememberLazyGridState()
                LaunchedEffect(gridState, state.searchResults.size, state.searchTotal) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { lastVisible ->
                            if (lastVisible >= state.searchResults.size - 6) {
                                onLoadMoreSearch()
                            }
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "搜索结果",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                state.searchTotal.takeIf { it > 0 }?.let { "$it 部" } ?: "${state.searchResults.size} 部",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.searchResults, key = { it.id }) { media ->
                        MediaPosterCard(
                            title = media.title,
                            subtitle = media.displayEpisode.ifBlank { cardYear(media) },
                            posterUrl = mediaPosterImage(origin, media),
                            rating = media.rating,
                            onClick = { onOpenMedia(media) },
                        )
                    }
                    if (state.searchLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 媒体库索引：两列宽幅封面，和 Yamby 的 Library 区一致。 */
@Composable
private fun LibraryIndex(
    state: MediaUiState,
    origin: String,
    onOpenLibrary: (LibraryDto) -> Unit,
    onSelectTag: (String?) -> Unit,
) {
    val libraries = state.visibleLibraries
    if (state.libraries.isEmpty()) {
        when {
            state.homeLoading -> {
                Spacer(Modifier.height(8.dp))
                MediaGridSkeleton(columns = 2, rows = 2, landscape = true)
            }
            else -> EmptyState("没有可访问的媒体库")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.libraryTagTabs.size > 1) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "library-tags") {
                LibraryTagBar(
                    tabs = state.libraryTagTabs,
                    selectedTag = state.selectedLibraryTag,
                    onSelect = onSelectTag,
                    modifier = Modifier.padding(bottom = 2.dp),
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("媒体库", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.selectedLibraryTag == null) {
                            "选择一个库开始浏览"
                        } else {
                            "「${state.selectedLibraryTag}」标签下的媒体库"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${libraries.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (libraries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "「${state.selectedLibraryTag}」标签下还没有媒体库，可在网页端「管理标签」里归类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 24.dp),
                )
            }
        } else {
            items(libraries, key = { it.id }) { library ->
                LibrarySpotlightCard(
                    library = library,
                    origin = origin,
                    pinned = library.id in state.pinnedLibraryIds,
                    onClick = { onOpenLibrary(library) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: MediaUiState,
    library: LibraryDto,
    origin: String,
    onBack: () -> Unit,
    onOpenCard: (SeriesCardDto) -> Unit,
    onLoadMore: () -> Unit,
    onApplyBrowse: (LibraryFilter, LibrarySortSelection) -> Unit,
    onResetBrowse: () -> Unit,
    onPlayRandom: () -> Unit,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    // 默认海报网格：部分条目没有横版缩略图，直接用竖版封面裁成宽幅会切掉主体。
    var landscape by rememberSaveable(library.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        MeBoxTopBar(
            title = library.name,
            subtitle = listOfNotNull(
                libraryTypeName(library.type),
                state.libraryTotal.takeIf { it > 0 }?.let { "$it 项" },
            ).joinToString(" · "),
            onBack = onBack,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    state.libraryTotal > 0 -> "${state.libraryTotal} 部"
                    state.libraryLoading -> "加载中…"
                    else -> "全部内容"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            MeBoxIconButton(
                icon = if (landscape) Icons.Default.GridView else Icons.Default.ViewAgenda,
                contentDescription = if (landscape) "切换为海报网格" else "切换为宽幅网格",
                onClick = { landscape = !landscape },
            )
            Spacer(Modifier.width(8.dp))
            MeBoxIconButton(
                icon = Icons.Default.Shuffle,
                contentDescription = if (state.libraryRandomLoading) "抽取中" else "随机播放",
                enabled = !state.libraryRandomLoading,
                onClick = onPlayRandom,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MeBoxChip(
                text = state.librarySort.label(),
                selected = state.librarySort.option != LibrarySort.entries.first(),
                onClick = { showFilterSheet = true },
            )
            MeBoxChip(
                text = if (state.libraryFilter.isActive) state.libraryFilter.summary() else "筛选",
                selected = state.libraryFilter.isActive,
                leadingIcon = Icons.Default.Tune,
                onClick = { showFilterSheet = true },
            )
            if (state.libraryFilter.isActive) {
                TextButton(onClick = onResetBrowse) {
                    Text("清除", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        when {
            state.libraryLoading && state.libraryCards.isEmpty() -> {
                Spacer(Modifier.height(8.dp))
                MediaGridSkeleton(
                    columns = if (landscape) 2 else 3,
                    rows = 3,
                    landscape = landscape,
                )
            }
            state.libraryError != null && state.libraryCards.isEmpty() ->
                ErrorState(state.libraryError) { onLoadMore() }
            state.libraryCards.isEmpty() -> EmptyState("这个媒体库还没有符合条件的内容")
            else -> {
                val gridState = rememberLazyGridState()
                LaunchedEffect(gridState, state.libraryCards.size, state.libraryTotal) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { lastVisible ->
                            if (lastVisible >= state.libraryCards.size - 5) {
                                onLoadMore()
                            }
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (landscape) GridCells.Fixed(2) else GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.libraryCards, key = { it.key.ifBlank { it.rep.id } }) { card ->
                        if (landscape) {
                            MediaLandscapeCard(
                                title = seriesCardTitle(card),
                                imageUrl = seriesCardImage(origin, card),
                                subtitle = cardYear(card.rep),
                                rating = card.rep.rating,
                                badge = card.count.takeIf { it > 1 }?.let { "$it 集" },
                                onClick = { onOpenCard(card) },
                            )
                        } else {
                            MediaPosterCard(
                                title = seriesCardTitle(card),
                                subtitle = cardYear(card.rep),
                                posterUrl = seriesCardPoster(origin, card),
                                rating = card.rep.rating,
                                badge = card.count.takeIf { it > 1 }?.let { "$it 集" },
                                onClick = { onOpenCard(card) },
                            )
                        }
                    }
                    if (state.libraryCards.size < state.libraryTotal || state.libraryLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.libraryLoading) {
                                    CircularProgressIndicator()
                                } else {
                                    OutlinedButton(onClick = onLoadMore) { Text("加载更多") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        LibraryFilterSheet(
            filter = state.libraryFilter,
            sort = state.librarySort,
            facets = state.libraryFacets,
            onDismiss = { showFilterSheet = false },
            onApply = { filter, sort ->
                showFilterSheet = false
                onApplyBrowse(filter, sort)
            },
            onReset = {
                showFilterSheet = false
                onResetBrowse()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterSheet(
    filter: LibraryFilter,
    sort: LibrarySortSelection,
    facets: LibraryFacetsDto?,
    onDismiss: () -> Unit,
    onApply: (LibraryFilter, LibrarySortSelection) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val facetsMin = facets?.yearMin ?: 0
    val facetsMax = facets?.yearMax ?: 0
    val hasYearRange = facets?.hasYearRange == true && facetsMax > facetsMin

    var genres by remember(filter, facets) { mutableStateOf(filter.genres) }
    var rating by remember(filter) { mutableStateOf(filter.ratingMin ?: 0f) }
    var unwatched by remember(filter) { mutableStateOf(filter.unwatched) }
    var option by remember(sort) { mutableStateOf(sort.option) }
    var descending by remember(sort) { mutableStateOf(sort.descending) }
    var yearRange by remember(filter, facets) {
        mutableStateOf(
            (filter.yearMin ?: facetsMin).toFloat()..(filter.yearMax ?: facetsMax).toFloat(),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("筛选与排序", style = MaterialTheme.typography.titleMedium)

            Text("类型", style = MaterialTheme.typography.labelLarge)
            if (facets == null || facets.genres.isEmpty()) {
                Text(
                    "服务器没有返回类型信息，仍可按年份与评分筛选",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    facets.genres.forEach { item ->
                        MeBoxChip(
                            text = "${item.name} ${item.count}",
                            selected = item.name in genres,
                            onClick = {
                                genres = if (item.name in genres) genres - item.name else genres + item.name
                            },
                        )
                    }
                }
            }

            if (hasYearRange) {
                Text(
                    "年份：${yearRange.start.toInt()} — ${yearRange.endInclusive.toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                )
                RangeSlider(
                    value = yearRange,
                    onValueChange = { yearRange = it },
                    valueRange = facetsMin.toFloat()..facetsMax.toFloat(),
                )
            }

            Text("评分", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0f, 6f, 7f, 8f, 9f).forEach { value ->
                    MeBoxChip(
                        text = if (value == 0f) "不限" else "≥ ${value.toInt()}",
                        selected = rating == value,
                        onClick = { rating = value },
                    )
                }
            }

            MeBoxChip(
                text = "仅未看完",
                selected = unwatched,
                onClick = { unwatched = !unwatched },
            )

            HorizontalDivider()

            Text("排序", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySort.entries.forEach { entry ->
                    MeBoxChip(
                        text = entry.label,
                        selected = option == entry,
                        onClick = {
                            option = entry
                            descending = entry.defaultDescending
                        },
                    )
                }
            }
            MeBoxChip(
                text = if (descending) "降序" else "升序",
                selected = descending,
                onClick = { descending = !descending },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MeBoxOutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                    Text("重置")
                }
                MeBoxButton(
                    onClick = {
                        val allYears = hasYearRange &&
                            yearRange.start.toInt() <= facetsMin &&
                            yearRange.endInclusive.toInt() >= facetsMax
                        onApply(
                            LibraryFilter(
                                genres = genres,
                                yearMin = if (hasYearRange && !allYears) yearRange.start.toInt() else null,
                                yearMax = if (hasYearRange && !allYears) yearRange.endInclusive.toInt() else null,
                                ratingMin = rating.takeIf { it > 0f },
                                unwatched = unwatched,
                            ),
                            LibrarySortSelection(option, descending),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("应用")
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(
    state: MediaUiState,
    origin: String,
    onBack: () -> Unit,
    onOpen: (MediaDto) -> Unit,
    onRemove: (MediaDto) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        PageHeader(
            title = "我的收藏",
            subtitle = state.favorites.size.takeIf { it > 0 }?.let { "$it 部" },
            onBack = onBack,
        )
        if (state.favoritesError != null && state.favorites.isNotEmpty()) {
            Text(
                state.favoritesError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            state.favoritesLoading && state.favorites.isEmpty() -> {
                Spacer(Modifier.height(8.dp))
                MediaGridSkeleton(columns = 3, rows = 3)
            }
            state.favoritesError != null && state.favorites.isEmpty() ->
                ErrorState(state.favoritesError) { onRetry() }
            state.favorites.isEmpty() -> EmptyState("还没有收藏的影视")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.favorites, key = { it.id }) { media ->
                    FavoriteCard(
                        media = media,
                        origin = origin,
                        onClick = { onOpen(media) },
                        onRemove = { onRemove(media) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(
    media: MediaDto,
    origin: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val subtitle = listOfNotNull(
        media.year.takeIf { it > 0 }?.toString(),
        media.displayEpisode.ifBlank { null },
    ).joinToString(" · ")
    MediaPosterCard(
        title = media.title,
        subtitle = subtitle,
        posterUrl = mediaPosterImage(origin, media),
        rating = media.rating,
        action = {
            Surface(
                color = Color(0xB3000000),
                shape = CircleShape,
            ) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "取消收藏",
                        tint = Color(0xFFFF7A90),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun HistoryScreen(
    state: MediaUiState,
    origin: String,
    onBack: () -> Unit,
    onPlay: (WatchHistoryDto) -> Unit,
    onRemove: (WatchHistoryDto) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        MeBoxTopBar(
            title = "观看历史",
            subtitle = state.historyItems.size.takeIf { it > 0 }?.let { "$it 条记录" },
            onBack = onBack,
            actions = {
                if (state.historyItems.isNotEmpty()) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text("清空", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
        )
        if (state.historyError != null && state.historyItems.isNotEmpty()) {
            Text(
                state.historyError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            state.historyLoading && state.historyItems.isEmpty() -> LoadingBox()
            state.historyError != null && state.historyItems.isEmpty() ->
                ErrorState(state.historyError) { onRetry() }
            state.historyItems.isEmpty() -> EmptyState("还没有观看记录")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(
                    state.historyItems,
                    key = { it.id.ifBlank { it.mediaId } },
                ) { item ->
                    HistoryRow(
                        item = item,
                        origin = origin,
                        onPlay = { onPlay(item) },
                        onRemove = { onRemove(item) },
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空观看历史？") },
            text = { Text("将删除当前账号的全部观看记录，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    item: WatchHistoryDto,
    origin: String,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val media = item.media ?: return
    val duration = item.durationMs.takeIf { it > 0 } ?: (media.durationSec * 1000L)
    MeBoxCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onPlay,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MediaArtwork(
                    url = mediaArtworkImage(origin, media),
                    title = media.title,
                    modifier = Modifier.fillMaxSize(),
                )
                if (duration > 0L) {
                    LinearProgressIndicator(
                        progress = { PlaybackPlan.progressFraction(item.positionMs, duration) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    media.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    media.displayEpisode.ifBlank { null },
                    media.year.takeIf { it > 0 }?.toString(),
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        item.completed -> "已看完"
                        duration > 0L ->
                            "看到 ${PlaybackPlan.formatTime(item.positionMs)} / " +
                                PlaybackPlan.formatTime(duration)
                        else -> "继续观看"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                val watchedAt = formatWatchedAt(item.watchedAt)
                if (watchedAt.isNotBlank()) {
                    Text(
                        "观看于 $watchedAt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatWatchedAt(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        java.time.OffsetDateTime.parse(raw)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrElse {
        raw.take(16).replace('T', ' ')
    }
}

@Composable
private fun DetailScreen(
    state: MediaUiState,
    origin: String,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayEpisode: (MediaDto) -> Unit,
    onSelectVersion: (MediaDto?) -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    if (state.detailLoading && state.detail == null) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            PageHeader(title = "详情", subtitle = null, onBack = onBack)
            LoadingBox()
        }
        return
    }
    val media = state.detail
    if (media == null) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            PageHeader(title = "详情", subtitle = null, onBack = onBack)
            ErrorState(state.detailError ?: "加载失败", onRetry)
        }
        return
    }
    val duration = media.durationSec * 1000L
    val resumeMs = state.detailResumeMs.takeIf {
        it > 0 && (duration <= 0 || it < duration - PlaybackPlan.FINISHED_MARGIN_MS)
    }
    val seasonGroups = remember(state.episodes, media.id) { MediaBrowseFormat.seasonGroups(state.episodes) }
    val defaultSeason = MediaBrowseFormat.seasonOf(media)
        .takeIf { season -> seasonGroups.any { it.season == season } }
        ?: seasonGroups.firstOrNull()?.season
        ?: 0
    var selectedSeason by rememberSaveable(media.id) { mutableIntStateOf(defaultSeason) }
    // 重新加载后原来的分季可能不存在（例如整季被删除），回落到默认季。
    val currentSeason = selectedSeason
        .takeIf { season -> seasonGroups.any { it.season == season } }
        ?: defaultSeason
    val visibleEpisodes = seasonGroups.firstOrNull { it.season == currentSeason }?.episodes
        ?: state.episodes
    val showEpisodes = state.episodes.size > 1 ||
        state.episodes.any { episode -> episode.id != media.id }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        item {
            DetailHero(
                media = media,
                origin = origin,
                favorite = state.favorite,
                onBack = onBack,
                onToggleFavorite = onToggleFavorite,
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (resumeMs != null) {
                            "继续播放 · ${PlaybackPlan.formatTime(resumeMs)}"
                        } else {
                            "播放"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                state.favoriteError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (media.versions.size > 1) {
                    Column {
                        Text("版本", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MeBoxChip(
                                text = "默认",
                                selected = state.selectedVersionId == null,
                                onClick = { onSelectVersion(null) },
                            )
                            media.versions.forEachIndexed { index, version ->
                                MeBoxChip(
                                    text = versionLabel(version, index),
                                    selected = state.selectedVersionId == version.id,
                                    onClick = { onSelectVersion(version) },
                                )
                            }
                        }
                    }
                }
                Column {
                    Text("简介", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    ExpandableOverview(media.overview)
                }
                MediaTechInfo(media)
            }
        }
        if (showEpisodes) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "选集",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${state.episodes.size} 集",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (seasonGroups.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        MeBoxSegmentedBar(
                            labels = seasonGroups.map { MediaBrowseFormat.seasonLabel(it.season) },
                            selectedIndex = seasonGroups.indexOfFirst { it.season == currentSeason }.coerceAtLeast(0),
                            onSelect = { index -> seasonGroups.getOrNull(index)?.let { selectedSeason = it.season } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            items(visibleEpisodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    imageUrl = episodeArtworkImage(origin, episode, media),
                    selected = episode.id == media.id,
                    onClick = { onPlayEpisode(episode) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 沉浸式详情头图：大图、渐隐、标题与元数据。 */
@Composable
private fun DetailHero(
    media: MediaDto,
    origin: String,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val backdrop = MediaImageUrls.backdropHero(
        origin = origin,
        remote = media.backdropUrl.ifBlank { media.posterUrl },
    )
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        MediaArtwork(
            url = backdrop,
            title = media.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x30000000),
                        0.42f to Color(0x33000000),
                        0.78f to background.copy(alpha = 0.82f),
                        1f to background,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeroIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            HeroIconButton(
                icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                onClick = onToggleFavorite,
                tint = if (favorite) Color(0xFFFF7A90) else Color.White,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
        ) {
            Text(
                media.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (media.rating > 0f) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFD166),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "%.1f".format(media.rating),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFD166),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    detailMeta(media),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD8D8D8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val genres = media.genres.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    genres.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBFBFBF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeroIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun MediaTechInfo(media: MediaDto) {
    val tech = listOfNotNull(
        media.width.takeIf { it > 0 }?.let { width ->
            media.height.takeIf { it > 0 }?.let { height -> "${width}×$height" }
        },
        media.videoCodec.takeIf { it.isNotBlank() }?.uppercase(),
        media.audioCodec.takeIf { it.isNotBlank() }?.uppercase(),
        media.container.takeIf { it.isNotBlank() }?.uppercase(),
    ).joinToString(" · ")
    if (tech.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            tech,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: MediaDto,
    imageUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val episodeLabel = episode.episodeNum.takeIf { it > 0 }?.toString() ?: "正片"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EpisodeThumbnail(
            url = imageUrl,
            episodeLabel = episodeLabel,
            selected = selected,
            modifier = Modifier.size(width = 96.dp, height = 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                episode.episodeTitle.ifBlank { episode.title },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (episode.durationSec > 0) formatDuration(episode.durationSec) else "时长未知",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun detailMeta(media: MediaDto): String = MediaBrowseFormat.detailMeta(media)

@Composable
private fun PageHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MeBoxTopBar(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeBoxEmptyState(message)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeBoxEmptyState(
            message = message,
            actionLabel = "重试",
            onAction = onRetry,
        )
    }
}

private fun cardYear(media: MediaDto): String =
    media.year.takeIf { it > 0 }?.toString().orEmpty()

private fun versionLabel(version: MediaDto, index: Int): String {
    val height = version.height.takeIf { it > 0 }?.let { "${it}p" }
    val codec = version.videoCodec.takeIf { it.isNotBlank() }?.uppercase()
    val container = version.container.takeIf { it.isNotBlank() }?.uppercase()
    return listOfNotNull(height, codec, container).joinToString(" · ").ifBlank { "版本 ${index + 1}" }
}

internal fun libraryTypeName(type: String): String = when (type) {
    "movie" -> "电影"
    "tv" -> "电视剧"
    "anime" -> "动漫"
    "music" -> "音乐"
    else -> "媒体"
}

private fun formatDuration(seconds: Int): String = MediaBrowseFormat.duration(seconds)
