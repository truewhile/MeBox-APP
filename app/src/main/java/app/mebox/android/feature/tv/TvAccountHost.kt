package app.mebox.android.feature.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mebox.android.core.input.PhoneInputEvent
import app.mebox.android.core.network.ServerAddress
import app.mebox.android.feature.AppUiState
import app.mebox.android.feature.MainViewModel
import app.mebox.android.feature.media.MainTab
import app.mebox.android.feature.media.MediaScreen
import app.mebox.android.feature.media.MediaViewModel
import app.mebox.android.feature.media.player.PlayerScreen
import app.mebox.android.feature.reader.ReaderScreen
import app.mebox.android.feature.reader.ReaderSourcesContent
import app.mebox.android.feature.reader.ReaderViewModel
import coil.compose.LocalImageLoader

private enum class PhonePurpose { MEDIA_SEARCH, READER_SEARCH }

/**
 * 登录后的电视主界面。
 *
 * 页面结构沿用手机端的 ViewModel 与状态机，只替换导航与呈现：
 * 左侧固定导航栏（遥控器按右键进入内容区），右侧是当前模块。
 */
@Composable
fun TvAccountHost(
    account: AppUiState.Account,
    mainViewModel: MainViewModel,
    phoneUrl: String?,
    onRefreshQr: () -> Unit,
    onOpenLogShare: () -> Unit,
) {
    val factory = remember(account.scope, account.address.origin) {
        mainViewModel.mediaFactory(account.scope, account.address)
    }
    val readerFactory = remember(account.scope, account.address.origin) {
        mainViewModel.readerFactory(account.scope, account.address)
    }
    if (factory == null || readerFactory == null) {
        TvMessage("账号会话尚未准备好，请退出后重新登录")
        return
    }
    val mediaViewModel: MediaViewModel = viewModel(
        key = "tv-media-${account.scope.serverId}-${account.scope.userId}",
        factory = factory,
    )
    val readerViewModel: ReaderViewModel = viewModel(
        key = "tv-reader-${account.scope.serverId}-${account.scope.userId}",
        factory = readerFactory,
    )
    val state by mediaViewModel.state.collectAsStateWithLifecycle()
    val readerState by readerViewModel.state.collectAsStateWithLifecycle()
    val playerEngine by mainViewModel.playerEngine.collectAsStateWithLifecycle()
    val subtitlePreference by mainViewModel.subtitlePreference.collectAsStateWithLifecycle()
    val decoderMode by mainViewModel.decoderMode.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageLoader = remember(mediaViewModel) { mediaViewModel.createImageLoader(context) }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }

    var phonePurpose by remember { mutableStateOf<PhonePurpose?>(null) }

    LaunchedEffect(Unit) {
        mainViewModel.phoneInput.events.collect { event ->
            if (event !is PhoneInputEvent.Search) return@collect
            val purpose = phonePurpose ?: return@collect
            when (purpose) {
                PhonePurpose.MEDIA_SEARCH -> {
                    mediaViewModel.selectTab(MainTab.MEDIA)
                    mediaViewModel.onSearchQueryChange(event.query)
                    mediaViewModel.submitSearch()
                }

                PhonePurpose.READER_SEARCH -> {
                    readerViewModel.openSearch()
                    readerViewModel.onQueryChange(event.query)
                    readerViewModel.submitSearch()
                }
            }
            phonePurpose = null
        }
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        when {
            readerState.screen == ReaderScreen.Reading -> TvReaderReadingScreen(
                state = readerState,
                origin = account.address.origin,
                viewModel = readerViewModel,
            )

            readerState.screen == ReaderScreen.Search ||
                readerState.screen == ReaderScreen.BookDetail -> TvReaderSearchScreen(
                state = readerState,
                origin = account.address.origin,
                viewModel = readerViewModel,
                onOpenPhoneSearch = { phonePurpose = PhonePurpose.READER_SEARCH },
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
                    isTv = true,
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

                is MediaScreen.Detail -> TvDetailScreen(
                    state = state,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onPlay = mediaViewModel::playDetail,
                    onPlayEpisode = mediaViewModel::playEpisode,
                    onSelectVersion = mediaViewModel::selectVersion,
                    onRetry = mediaViewModel::retryDetail,
                    onToggleFavorite = mediaViewModel::toggleFavorite,
                )

                is MediaScreen.Library -> TvLibraryScreen(
                    state = state,
                    library = screen.library,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onOpenCard = mediaViewModel::openCard,
                    onLoadMore = mediaViewModel::loadMoreLibrary,
                    onPlayRandom = mediaViewModel::playRandom,
                )

                MediaScreen.Favorites -> TvFavoritesScreen(
                    state = state,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onOpen = mediaViewModel::openMedia,
                    onRetry = mediaViewModel::refreshFavorites,
                )

                MediaScreen.History -> TvHistoryScreen(
                    state = state,
                    origin = account.address.origin,
                    onBack = mediaViewModel::back,
                    onPlay = mediaViewModel::playHistory,
                    onRetry = mediaViewModel::refreshHistory,
                )

                MediaScreen.Tabs -> TvMainScaffold(
                    account = account,
                    state = state,
                    readerState = readerState,
                    mainViewModel = mainViewModel,
                    mediaViewModel = mediaViewModel,
                    readerViewModel = readerViewModel,
                    playerEngine = playerEngine,
                    subtitlePreference = subtitlePreference,
                    decoderMode = decoderMode,
                    onOpenPhoneSearch = { purpose ->
                        phonePurpose = purpose
                    },
                    onOpenLogShare = onOpenLogShare,
                )
            }
        }

        val purpose = phonePurpose
        if (purpose != null) {
            TvPhoneInputDialog(
                title = if (purpose == PhonePurpose.READER_SEARCH) {
                    "手机输入阅读搜索"
                } else {
                    "手机输入影视搜索"
                },
                url = phoneUrl,
                onRefresh = onRefreshQr,
                onDismiss = { phonePurpose = null },
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

@Composable
private fun TvMainScaffold(
    account: AppUiState.Account,
    state: app.mebox.android.feature.media.MediaUiState,
    readerState: app.mebox.android.feature.reader.ReaderUiState,
    mainViewModel: MainViewModel,
    mediaViewModel: MediaViewModel,
    readerViewModel: ReaderViewModel,
    playerEngine: app.mebox.android.core.player.PlayerEngineKind,
    subtitlePreference: app.mebox.android.core.player.SubtitlePreference,
    decoderMode: app.mebox.android.core.player.DecoderMode,
    onOpenPhoneSearch: (PhonePurpose) -> Unit,
    onOpenLogShare: () -> Unit,
) {
    val tabs = listOf(
        TvTab(MainTab.HOME, Icons.Default.Home, "首页"),
        TvTab(MainTab.MEDIA, Icons.Default.PlayArrow, "影视"),
        TvTab(MainTab.READER, Icons.AutoMirrored.Filled.List, "阅读"),
        TvTab(MainTab.PROFILE, Icons.Default.Person, "我的"),
    )
    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { firstTabFocus.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .width(196.dp)
                .fillMaxHeight()
                .padding(start = 20.dp, top = 22.dp, bottom = 20.dp, end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0A84FF), Color(0xFF58B5FF)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "M",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "MeBox",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(26.dp))
            tabs.forEachIndexed { index, tab ->
                TvNavItem(
                    tab = tab,
                    selected = state.tab == tab.id,
                    onClick = {
                        mediaViewModel.selectTab(tab.id)
                        if (tab.id == MainTab.READER) readerViewModel.ensureShelfLoaded()
                    },
                    focusRequester = if (index == 0) firstTabFocus else null,
                )
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.weight(1f))
            TvActionButton(
                text = "手机输入",
                onClick = {
                    val purpose = if (state.tab == MainTab.READER) {
                        PhonePurpose.READER_SEARCH
                    } else {
                        PhonePurpose.MEDIA_SEARCH
                    }
                    onOpenPhoneSearch(purpose)
                },
                primary = false,
                leading = {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                account.user.nickname.ifBlank { account.user.username },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 8.dp),
        ) {
            when (state.tab) {
                MainTab.HOME -> TvHomeScreen(
                    state = state,
                    origin = account.address.origin,
                    onContinue = mediaViewModel::openContinue,
                    onOpenCard = mediaViewModel::openCard,
                    onOpenLibrary = mediaViewModel::openLibrary,
                    onOpenHistory = mediaViewModel::openHistory,
                    onOpenMediaTab = { mediaViewModel.selectTab(MainTab.MEDIA) },
                    onSelectTag = mediaViewModel::selectLibraryTag,
                    onRefresh = mediaViewModel::refreshHome,
                )

                MainTab.MEDIA -> TvMediaTabScreen(
                    state = state,
                    origin = account.address.origin,
                    onOpenMedia = mediaViewModel::openMedia,
                    onOpenLibrary = mediaViewModel::openLibrary,
                    onOpenFavorites = mediaViewModel::openFavorites,
                    onOpenHistory = mediaViewModel::openHistory,
                    onLoadMoreSearch = mediaViewModel::loadMoreSearch,
                    onOpenPhoneSearch = { onOpenPhoneSearch(PhonePurpose.MEDIA_SEARCH) },
                    onSelectTag = mediaViewModel::selectLibraryTag,
                )

                MainTab.READER -> TvReaderShelfScreen(
                    state = readerState,
                    origin = account.address.origin,
                    viewModel = readerViewModel,
                    onOpenPhoneSearch = { onOpenPhoneSearch(PhonePurpose.READER_SEARCH) },
                )

                MainTab.PROFILE -> TvProfileScreen(
                    account = account,
                    engine = playerEngine,
                    onSelectEngine = mainViewModel::selectPlayerEngine,
                    subtitlePreference = subtitlePreference,
                    onToggleSubtitlesAutoLoad = mainViewModel::setSubtitlesAutoLoad,
                    onSelectSubtitlePriority = mainViewModel::setSubtitlePriority,
                    decoderMode = decoderMode,
                    onSelectDecoderMode = mainViewModel::selectDecoderMode,
                    onOpenFavorites = mediaViewModel::openFavorites,
                    onOpenHistory = mediaViewModel::openHistory,
                    onOpenPhoneInput = { onOpenPhoneSearch(PhonePurpose.MEDIA_SEARCH) },
                    onOpenLogShare = onOpenLogShare,
                    onLogout = mainViewModel::logout,
                )
            }
        }
    }
}

private data class TvTab(
    val id: MainTab,
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun TvNavItem(
    tab: TvTab,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            Modifier.fillMaxWidth().focusRequester(focusRequester)
        } else {
            Modifier.fillMaxWidth()
        },
        focusedScale = 1f,
        shape = RoundedCornerShape(12.dp),
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        focused -> MaterialTheme.colorScheme.surfaceVariant
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                tint = if (selected || focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Text(
                tab.label,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
