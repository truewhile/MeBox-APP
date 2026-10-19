package app.mebox.android.feature.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mebox.android.core.media.LibraryArtwork
import app.mebox.android.core.media.MediaBrowseFormat
import app.mebox.android.core.media.MediaImageUrls
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.network.ContinueWatchingDto
import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.SeriesCardDto
import app.mebox.android.core.network.WatchHistoryDto
import app.mebox.android.core.player.DecoderMode
import app.mebox.android.core.player.PlayerEngineKind
import app.mebox.android.core.player.SubtitlePreference
import app.mebox.android.core.player.SubtitlePriority
import app.mebox.android.feature.AppUiState
import app.mebox.android.feature.diagnostics.rememberLogExport
import app.mebox.android.feature.media.EpisodeThumbnail
import app.mebox.android.feature.media.MediaArtwork
import app.mebox.android.feature.media.MainTab
import app.mebox.android.feature.media.MediaUiState
import app.mebox.android.feature.media.continueWatchingSubtitle
import app.mebox.android.feature.media.episodeArtworkImage
import app.mebox.android.feature.media.libraryTypeName
import app.mebox.android.feature.media.mediaArtworkImage
import app.mebox.android.feature.media.mediaPosterImage
import app.mebox.android.feature.media.seriesCardPoster
import app.mebox.android.feature.media.seriesCardSubtitle
import app.mebox.android.feature.media.seriesCardTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 详情页尚未由用户手动选择分季时的占位季号。 */
private const val NO_SEASON = Int.MIN_VALUE

/* ─────────────────────────── 首页 ─────────────────────────── */

@Composable
fun TvHomeScreen(
    state: MediaUiState,
    origin: String,
    onContinue: (ContinueWatchingDto) -> Unit,
    onOpenCard: (SeriesCardDto) -> Unit,
    onOpenLibrary: (LibraryDto) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMediaTab: () -> Unit,
    onSelectTag: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val hasContent = state.continueItems.isNotEmpty() ||
        state.recentItems.isNotEmpty() ||
        state.libraries.isNotEmpty() ||
        state.carouselItems.isNotEmpty() ||
        state.librarySections.isNotEmpty()

    if (state.homeLoading && !hasContent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TvLoading("正在加载首页…")
        }
        return
    }
    if (!hasContent && state.homeError != null) {
        TvMessage(
            message = state.homeError,
            actionLabel = "重试",
            onAction = onRefresh,
        )
        return
    }
    if (!hasContent) {
        TvMessage(
            message = "媒体库里还没有内容",
            actionLabel = "刷新",
            onAction = onRefresh,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val navigationJob = remember { JobSlot() }

    val hero = state.carouselItems.firstOrNull() ?: state.recentItems.firstOrNull()
    val showContinue = state.continueItems.isNotEmpty()
    val showTags = state.libraryTagTabs.size > 1
    val showRecent = state.recentItems.isNotEmpty()
    val sectionIds = state.librarySections.map { it.library.id }
    val sectionFocus = remember(sectionIds) {
        sectionIds.associateWith { FocusRequester() }
    }
    val sectionListStates = remember(sectionIds) {
        sectionIds.associateWith { LazyListState() }
    }
    val continueFocus = remember { FocusRequester() }
    val continueListState = rememberLazyListState()
    val recentFocus = remember { FocusRequester() }
    val recentListState = rememberLazyListState()
    val refreshFocus = remember { FocusRequester() }

    // LazyColumn 的条目序号：hero / 继续观看(标题+横排) / 标签行 / 各媒体库(标题+横排) /
    // 最近添加(标题+横排) / 刷新。显式记录横向列表所在的序号，按下键时先滚到
    // 目标横排再请求焦点，避免目标行尚未组合时焦点搜索落到标签或“查看全部”上。
    var nextItemIndex = 0
    if (hero != null) nextItemIndex += 1
    if (showContinue) nextItemIndex += 2
    if (showTags) nextItemIndex += 1
    val sectionRows = state.librarySections.map { section ->
        nextItemIndex += 1
        val row = TvHomeRow(
            itemIndex = nextItemIndex,
            focus = sectionFocus.getValue(section.library.id),
            listState = sectionListStates.getValue(section.library.id),
        )
        nextItemIndex += 1
        row
    }
    val recentRow = if (showRecent) {
        nextItemIndex += 1
        val row = TvHomeRow(nextItemIndex, recentFocus, recentListState)
        nextItemIndex += 1
        row
    } else {
        null
    }
    val refreshRow = TvHomeRow(nextItemIndex, refreshFocus, null)
    val firstContentRow = sectionRows.firstOrNull() ?: recentRow ?: refreshRow

    fun goToRow(row: TvHomeRow) {
        navigationJob.job?.cancel()
        navigationJob.job = scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == row.itemIndex }
            if (!visible) {
                listState.scrollToItem(row.itemIndex)
                withFrameNanos { }
            }
            // 目标横排可能保留着上次的水平滚动位置，先回到第一张再请求焦点。
            runCatching { row.listState?.scrollToItem(0) }
            if (runCatching { row.focus.requestFocus() }.isFailure) {
                listState.scrollToItem(row.itemIndex)
                withFrameNanos { }
                runCatching { row.listState?.scrollToItem(0) }
                withFrameNanos { }
                runCatching { row.focus.requestFocus() }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        if (hero != null) {
            item(key = "hero-${hero.key}") {
                TvHero(
                    card = hero,
                    origin = origin,
                    onOpen = { onOpenCard(hero) },
                    onContinue = state.continueItems.firstOrNull()?.let { item ->
                        { onContinue(item) }
                    },
                    onOpenMediaTab = onOpenMediaTab,
                )
            }
        }

        if (showContinue) {
            item(key = "continue-header") {
                Spacer(Modifier.height(22.dp))
                TvSectionHeader(
                    title = "继续观看",
                    subtitle = "从上次停下的位置继续",
                    actionLabel = "观看历史",
                    onAction = onOpenHistory,
                )
            }
            item(key = "continue-rail") {
                Spacer(Modifier.height(10.dp))
                TvHomeRail(
                    state = continueListState,
                    onDown = { goToRow(firstContentRow) },
                ) {
                    itemsIndexed(
                        state.continueItems,
                        key = { index, item -> "continue-${item.media.id}-$index" },
                    ) { index, item ->
                        TvWideCard(
                            title = item.media.title,
                            imageUrl = mediaArtworkImage(origin, item.media),
                            subtitle = continueWatchingSubtitle(item),
                            badge = item.media.displayEpisode.ifBlank { null },
                            onClick = { onContinue(item) },
                            focusRequester = if (index == 0) continueFocus else null,
                        )
                    }
                }
            }
        }

        if (showTags) {
            item(key = "library-tags") {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        ) {
                            goToRow(firstContentRow)
                            true
                        } else {
                            false
                        }
                    },
                ) {
                    TvLibraryTagRow(
                        tabs = state.libraryTagTabs,
                        selectedTag = state.selectedLibraryTag,
                        onSelect = onSelectTag,
                    )
                }
            }
        }

        state.librarySections.forEachIndexed { sectionIndex, section ->
            val row = sectionRows[sectionIndex]
            item(key = "bar-${section.library.id}") {
                Spacer(Modifier.height(22.dp))
                TvSectionHeader(
                    title = section.library.name.ifBlank { libraryTypeName(section.library.type) },
                    subtitle = "${section.library.total} 个条目",
                    actionLabel = "查看全部",
                    onAction = { onOpenLibrary(section.library) },
                )
            }
            item(key = "bar-rail-${section.library.id}") {
                Spacer(Modifier.height(10.dp))
                TvHomeRail(
                    state = sectionListStates.getValue(section.library.id),
                    onDown = {
                        goToRow(sectionRows.getOrNull(sectionIndex + 1) ?: recentRow ?: refreshRow)
                    },
                ) {
                    itemsIndexed(
                        items = section.cards,
                        key = { index, card ->
                            "${section.library.id}-${card.key.ifBlank { card.rep.id }}-$index"
                        },
                    ) { index, card ->
                        TvPosterCard(
                            title = seriesCardTitle(card),
                            posterUrl = seriesCardPoster(origin, card),
                            subtitle = seriesCardSubtitle(card),
                            onClick = { onOpenCard(card) },
                            focusRequester = if (index == 0) row.focus else null,
                        )
                    }
                }
            }
        }

        if (showRecent) {
            item(key = "recent-header") {
                Spacer(Modifier.height(22.dp))
                TvSectionHeader(
                    title = "最近添加",
                    subtitle = "按入库时间排序",
                    actionLabel = "全部影视",
                    onAction = onOpenMediaTab,
                )
            }
            item(key = "recent-rail") {
                Spacer(Modifier.height(10.dp))
                TvHomeRail(
                    state = recentListState,
                    onDown = { goToRow(refreshRow) },
                ) {
                    itemsIndexed(
                        items = state.recentItems,
                        key = { index, card ->
                            "recent-${card.key.ifBlank { card.rep.id }}-$index"
                        },
                    ) { index, card ->
                        TvPosterCard(
                            title = seriesCardTitle(card),
                            posterUrl = seriesCardPoster(origin, card),
                            subtitle = seriesCardSubtitle(card),
                            onClick = { onOpenCard(card) },
                            focusRequester = if (index == 0) recentFocus else null,
                        )
                    }
                }
            }
        }

        item(key = "refresh") {
            Spacer(Modifier.height(26.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TvActionButton(
                    text = "刷新首页",
                    onClick = onRefresh,
                    primary = false,
                    focusRequester = refreshFocus,
                    leading = {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

/** 首页横向列表的焦点目标：LazyColumn 条目序号 + 首张卡片 + 横向滚动状态。 */
private data class TvHomeRow(
    val itemIndex: Int,
    val focus: FocusRequester,
    val listState: LazyListState?,
)

/** 保存当前焦点跳转任务，避免连续按键时多个滚动/请求焦点协程互相覆盖。 */
private class JobSlot {
    var job: Job? = null
}

/**
 * 首页横向媒体行。按下键时不再交给几何焦点搜索，否则夹在中间的标签行
 * 或下一节的“查看全部”按钮会被选为最近目标，表现为焦点向右跳一格。
 */
@Composable
private fun TvHomeRail(
    state: LazyListState,
    onDown: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onDown()
                    true
                } else {
                    false
                }
            },
        contentPadding = PaddingValues(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
    }
}

@Composable
private fun TvHero(
    card: SeriesCardDto,
    origin: String,
    onOpen: () -> Unit,
    onContinue: (() -> Unit)?,
    onOpenMediaTab: () -> Unit,
) {
    val image = MediaImageUrls.backdropHero(
        origin = origin,
        remote = card.rep.backdropUrl.ifBlank { card.rep.posterUrl },
    )
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        MediaArtwork(
            url = image,
            title = seriesCardTitle(card),
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to background.copy(alpha = 0.96f),
                        0.46f to background.copy(alpha = 0.68f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1f to background,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp, end = 48.dp)
                .fillMaxWidth(0.62f),
        ) {
            Text(
                "焦点推荐",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                seriesCardTitle(card),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                seriesCardSubtitle(card),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = "查看详情",
                    onClick = onOpen,
                    leading = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
                if (onContinue != null) {
                    TvActionButton(
                        text = "继续观看",
                        onClick = onContinue,
                        primary = false,
                    )
                }
                TvActionButton(
                    text = "浏览影视",
                    onClick = onOpenMediaTab,
                    primary = false,
                )
            }
        }
    }
}

/* ─────────────────────────── 影视 tab ─────────────────────────── */

@Composable
fun TvMediaTabScreen(
    state: MediaUiState,
    origin: String,
    onOpenMedia: (MediaDto) -> Unit,
    onOpenLibrary: (LibraryDto) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onLoadMoreSearch: () -> Unit,
    onOpenPhoneSearch: () -> Unit,
    onSelectTag: (String?) -> Unit,
) {
    val searching = state.searchQuery.isNotBlank()
    Column(Modifier.fillMaxSize()) {
        TvPageHeader(
            title = if (searching) "搜索结果" else "影视",
            subtitle = if (searching) {
                "“${state.searchQuery}” · ${state.searchTotal} 个结果"
            } else {
                "${state.visibleLibraries.size} 个媒体库"
            },
            actions = {
                TvActionButton(
                    text = "手机搜索",
                    onClick = onOpenPhoneSearch,
                    primary = false,
                    leading = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                TvActionButton(
                    text = "收藏",
                    onClick = onOpenFavorites,
                    primary = false,
                    leading = {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                TvActionButton(
                    text = "历史",
                    onClick = onOpenHistory,
                    primary = false,
                    leading = {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            },
        )

        if (!searching && state.libraryTagTabs.size > 1) {
            TvLibraryTagRow(
                tabs = state.libraryTagTabs,
                selectedTag = state.selectedLibraryTag,
                onSelect = onSelectTag,
            )
            Spacer(Modifier.height(12.dp))
        }

        when {
            searching && state.searchLoading && state.searchResults.isEmpty() ->
                TvLoading("正在搜索…")

            searching && state.searchError != null && state.searchResults.isEmpty() ->
                TvMessage(state.searchError, actionLabel = "重新搜索", onAction = onOpenPhoneSearch)

            searching && state.searchResults.isEmpty() ->
                TvMessage("没有找到匹配的内容", actionLabel = "换个关键词", onAction = onOpenPhoneSearch)

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (searching) {
                    items(state.searchResults, key = { it.id }) { media ->
                        TvPosterCard(
                            title = media.title,
                            posterUrl = mediaPosterImage(origin, media),
                            subtitle = listOfNotNull(
                                media.displayEpisode.ifBlank { null },
                                media.year.takeIf { it > 0 }?.toString(),
                            ).joinToString(" · "),
                            badge = media.rating.takeIf { it > 0f }?.let { "★ %.1f".format(it) },
                            onClick = { onOpenMedia(media) },
                        )
                    }
                    if (state.searchResults.size < state.searchTotal) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TvActionButton(
                                    text = if (state.searchLoadingMore) "加载中…" else "加载更多",
                                    onClick = onLoadMoreSearch,
                                    primary = false,
                                    enabled = !state.searchLoadingMore,
                                )
                            }
                        }
                    }
                } else {
                    if (state.visibleLibraries.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TvMessage(
                                if (state.selectedLibraryTag == null) {
                                    "没有可见的媒体库"
                                } else {
                                    "「${state.selectedLibraryTag}」标签下还没有媒体库"
                                },
                            )
                        }
                    } else {
                        items(state.visibleLibraries, key = { it.id }) { library ->
                            TvLibraryTile(
                                library = library,
                                origin = origin,
                                onClick = { onOpenLibrary(library) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvLibraryTile(
    library: LibraryDto,
    origin: String,
    onClick: () -> Unit,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) { focused ->
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MediaArtwork(
                    url = LibraryArtwork.cover(origin, library, width = 1280, height = 720),
                    title = library.name,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xB3000000)),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                ) {
                    Text(
                        library.name.ifBlank { libraryTypeName(library.type) },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${library.type.let(::libraryTypeName)} · ${library.total} 个条目",
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                    )
                }
            }
            if (focused) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "按 OK 打开媒体库",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/* ─────────────────────────── 媒体库 ─────────────────────────── */

@Composable
fun TvLibraryScreen(
    state: MediaUiState,
    library: LibraryDto,
    origin: String,
    onBack: () -> Unit,
    onOpenCard: (SeriesCardDto) -> Unit,
    onLoadMore: () -> Unit,
    onPlayRandom: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TvPageHeader(
            title = library.name.ifBlank { libraryTypeName(library.type) },
            subtitle = "${state.libraryCards.size} / ${state.libraryTotal} 个条目",
            onBack = onBack,
            actions = {
                TvActionButton(
                    text = if (state.libraryRandomLoading) "准备中…" else "随机播放",
                    onClick = onPlayRandom,
                    primary = false,
                    enabled = !state.libraryRandomLoading,
                    leading = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            },
        )

        when {
            state.libraryLoading && state.libraryCards.isEmpty() ->
                TvLoading("正在加载媒体库…")

            state.libraryError != null && state.libraryCards.isEmpty() ->
                TvMessage(state.libraryError)

            state.libraryCards.isEmpty() ->
                TvMessage("这个媒体库还没有内容")

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(
                    items = state.libraryCards,
                    key = { index, card ->
                        "lib-${card.key.ifBlank { card.rep.id }}-$index"
                    },
                ) { _, card ->
                    TvPosterCard(
                        title = seriesCardTitle(card),
                        posterUrl = seriesCardPoster(origin, card),
                        subtitle = seriesCardSubtitle(card),
                        onClick = { onOpenCard(card) },
                    )
                }
                if (state.libraryCards.size < state.libraryTotal) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TvActionButton(
                                text = if (state.libraryLoading) "加载中…" else "加载更多",
                                onClick = onLoadMore,
                                primary = false,
                                enabled = !state.libraryLoading,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ─────────────────────────── 详情 ─────────────────────────── */

@Composable
fun TvDetailScreen(
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
        Column(Modifier.fillMaxSize()) {
            TvPageHeader(title = "详情", onBack = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TvLoading("正在加载详情…")
            }
        }
        return
    }
    val media = state.detail
    if (media == null) {
        Column(Modifier.fillMaxSize()) {
            TvPageHeader(title = "详情", onBack = onBack)
            TvMessage(state.detailError ?: "加载失败", actionLabel = "重试", onAction = onRetry)
        }
        return
    }

    val seasonGroups = remember(state.episodes, media.id) { MediaBrowseFormat.seasonGroups(state.episodes) }
    // 多季并存时默认展开第一季；代表条目是 OVA/特别篇时不能沿用它自己的季号。
    val defaultSeason = MediaBrowseFormat.defaultSeasonOf(seasonGroups) ?: 0
    // 先不预设季号：剧集列表到达前 episodes 为空，提前写入 0 会在数据到达后
    // 被当成「特别篇」而被选中。NO_SEASON 表示仍跟随默认季，用户点过分季才固定。
    var selectedSeason by rememberSaveable(media.id) { mutableIntStateOf(NO_SEASON) }
    // 重新加载后原来的分季可能不存在（例如整季被删除），回落到默认季。
    val currentSeason = selectedSeason
        .takeIf { it != NO_SEASON }
        ?.takeIf { season -> seasonGroups.any { it.season == season } }
        ?: defaultSeason
    val visibleEpisodes = seasonGroups.firstOrNull { it.season == currentSeason }?.episodes
        ?: state.episodes
    val showEpisodes = state.episodes.size > 1 || state.episodes.any { it.id != media.id }
    // 「继续播放」可能指向本剧最新看的那一集，而不是详情代表条目。
    val resumeMedia = state.detailResumeMedia ?: media
    val resumeDuration = resumeMedia.durationSec * 1000L
    val resumeMs = state.detailResumeMs.takeIf {
        !state.detailResumeCompleted &&
            it > 0 &&
            (resumeDuration <= 0 || it < resumeDuration - PlaybackPlan.FINISHED_MARGIN_MS)
    }
    val resumeEpisode = resumeMedia.displayEpisode.ifBlank { resumeMedia.episodeTitle }
    val playLabel = buildString {
        append(if (resumeMs != null) "继续播放" else "播放")
        if (resumeEpisode.isNotBlank()) append(" · ").append(resumeEpisode)
        if (resumeMs != null) append(" · ").append(PlaybackPlan.formatTime(resumeMs))
    }
    val background = MaterialTheme.colorScheme.background
    val playFocus = remember(media.id) { FocusRequester() }
    LaunchedEffect(media.id) {
        kotlinx.coroutines.delay(120)
        runCatching { playFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "detail-hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                MediaArtwork(
                    url = MediaImageUrls.backdropHero(
                        origin = origin,
                        remote = media.backdropUrl.ifBlank { media.posterUrl },
                    ),
                    title = media.title,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x22000000),
                                0.62f to background.copy(alpha = 0.72f),
                                1f to background,
                            ),
                        ),
                )
                TvIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 20.dp),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 32.dp, end = 32.dp, bottom = 12.dp)
                        .fillMaxWidth(0.72f),
                ) {
                    Text(
                        media.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        MediaBrowseFormat.detailMeta(media),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "detail-actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvActionButton(
                    text = playLabel,
                    onClick = onPlay,
                    focusRequester = playFocus,
                    leading = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
                TvActionButton(
                    text = if (state.favorite) "已收藏" else "收藏",
                    onClick = onToggleFavorite,
                    primary = false,
                    leading = {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                state.favoriteError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (media.versions.size > 1) {
            item(key = "detail-versions") {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 6.dp)) {
                    Text(
                        "版本",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            TvActionButton(
                                text = "默认",
                                onClick = { onSelectVersion(null) },
                                primary = state.selectedVersionId == null,
                            )
                        }
                        items(media.versions.size) { index ->
                            val version = media.versions[index]
                            TvActionButton(
                                text = "版本 ${index + 1}",
                                onClick = { onSelectVersion(version) },
                                primary = state.selectedVersionId == version.id,
                            )
                        }
                    }
                }
            }
        }

        if (media.overview.isNotBlank()) {
            item(key = "detail-overview") {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) {
                    Text(
                        "简介",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        media.overview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
        }

        if (showEpisodes) {
            item(key = "episodes-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "选集",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.episodes.size} 集",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            if (seasonGroups.size > 1) {
                item(key = "seasons") {
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(seasonGroups, key = { "season-${it.season}" }) { group ->
                            TvActionButton(
                                text = MediaBrowseFormat.seasonLabel(group.season),
                                onClick = { selectedSeason = group.season },
                                primary = currentSeason == group.season,
                            )
                        }
                    }
                }
            }
            items(visibleEpisodes, key = { "ep-${it.id}" }) { episode ->
                TvEpisodeRow(
                    episode = episode,
                    imageUrl = episodeArtworkImage(origin, episode, media),
                    selected = episode.id == media.id,
                    onClick = { onPlayEpisode(episode) },
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeRow(
    episode: MediaDto,
    imageUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val episodeLabel = episode.episodeNum.takeIf { it > 0 }?.toString() ?: "正片"
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 3.dp),
        focusedScale = 1.01f,
        shape = RoundedCornerShape(12.dp),
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        focused -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeThumbnail(
                url = imageUrl,
                episodeLabel = episodeLabel,
                selected = selected || focused,
                modifier = Modifier.size(width = 128.dp, height = 72.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.episodeTitle.ifBlank { episode.title },
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (episode.durationSec > 0) {
                        MediaBrowseFormat.duration(episode.durationSec)
                    } else {
                        "时长未知"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (selected) {
                Text(
                    "正在看",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/* ─────────────────────────── 收藏 / 历史 ─────────────────────────── */

@Composable
fun TvFavoritesScreen(
    state: MediaUiState,
    origin: String,
    onBack: () -> Unit,
    onOpen: (MediaDto) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TvPageHeader(
            title = "我的收藏",
            subtitle = "${state.favorites.size} 个条目",
            onBack = onBack,
        )
        when {
            state.favoritesLoading && state.favorites.isEmpty() -> TvLoading("正在加载收藏…")
            state.favoritesError != null && state.favorites.isEmpty() ->
                TvMessage(state.favoritesError, actionLabel = "重试", onAction = onRetry)

            state.favorites.isEmpty() -> TvMessage("还没有收藏的内容")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.favorites, key = { it.id }) { media ->
                    TvPosterCard(
                        title = media.title,
                        posterUrl = mediaPosterImage(origin, media),
                        subtitle = listOfNotNull(
                            media.displayEpisode.ifBlank { null },
                            media.year.takeIf { it > 0 }?.toString(),
                        ).joinToString(" · "),
                        badge = media.rating.takeIf { it > 0f }?.let { "★ %.1f".format(it) },
                        onClick = { onOpen(media) },
                    )
                }
            }
        }
    }
}

@Composable
fun TvHistoryScreen(
    state: MediaUiState,
    origin: String,
    onBack: () -> Unit,
    onPlay: (WatchHistoryDto) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TvPageHeader(
            title = "观看历史",
            subtitle = "${state.historyItems.size} 条记录",
            onBack = onBack,
        )
        when {
            state.historyLoading && state.historyItems.isEmpty() -> TvLoading("正在加载历史…")
            state.historyError != null && state.historyItems.isEmpty() ->
                TvMessage(state.historyError, actionLabel = "重试", onAction = onRetry)

            state.historyItems.isEmpty() -> TvMessage("还没有观看记录")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = state.historyItems,
                    key = { index, item -> "history-${item.id.ifBlank { item.mediaId }}-$index" },
                ) { _, item ->
                    TvHistoryRow(item = item, origin = origin, onClick = { onPlay(item) })
                }            }
        }
    }
}

@Composable
private fun TvHistoryRow(
    item: WatchHistoryDto,
    origin: String,
    onClick: () -> Unit,
) {
    val media = item.media ?: return
    val progress = if (item.durationMs > 0) {
        (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1.01f,
        shape = RoundedCornerShape(14.dp),
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (focused) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 168.dp, height = 95.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MediaArtwork(
                    url = mediaArtworkImage(origin, media),
                    title = media.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    media.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        media.displayEpisode.ifBlank { null },
                        if (item.durationMs > 0) {
                            "${PlaybackPlan.formatTime(item.positionMs)} / ${PlaybackPlan.formatTime(item.durationMs)}"
                        } else {
                            null
                        },
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/* ─────────────────────────── 我的 ─────────────────────────── */

@Composable
fun TvProfileScreen(
    account: AppUiState.Account,
    engine: PlayerEngineKind,
    onSelectEngine: (PlayerEngineKind) -> Unit,
    decoderMode: DecoderMode,
    onSelectDecoderMode: (DecoderMode) -> Unit,
    subtitlePreference: SubtitlePreference,
    onToggleSubtitlesAutoLoad: (Boolean) -> Unit,
    onSelectSubtitlePriority: (SubtitlePriority) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPhoneInput: () -> Unit,
    onOpenLogShare: () -> Unit,
    onLogout: () -> Unit,
) {
    val exportLogs = rememberLogExport()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "profile-title") {
            Text(
                "我的",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
        }

        item(key = "profile-account") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (account.user.nickname.ifBlank { account.user.username }).take(1).ifBlank { "M" },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.user.nickname.ifBlank { account.user.username },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${account.user.username} · ${account.user.role.ifBlank { "用户" }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        account.serverInfo.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                    Text(
                        account.address.origin,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "profile-shortcuts") {
            Spacer(Modifier.height(6.dp))
            TvSectionHeader(title = "内容", modifier = Modifier.padding(horizontal = 0.dp))
        }
        item(key = "profile-fav") {
            TvProfileRow(
                icon = Icons.Default.Favorite,
                title = "我的收藏",
                subtitle = "查看收藏的影视",
                onClick = onOpenFavorites,
            )
        }
        item(key = "profile-history") {
            TvProfileRow(
                icon = Icons.Default.DateRange,
                title = "观看历史",
                subtitle = "继续上次的进度",
                onClick = onOpenHistory,
            )
        }
        item(key = "profile-phone") {
            TvProfileRow(
                icon = Icons.Default.QrCode2,
                title = "手机扫码输入",
                subtitle = "用手机填写搜索词或账号信息",
                onClick = onOpenPhoneInput,
            )
        }

        item(key = "profile-engine-title") {
            Spacer(Modifier.height(8.dp))
            TvSectionHeader(title = "播放内核", modifier = Modifier.padding(horizontal = 0.dp))
            Spacer(Modifier.height(2.dp))
        }
        items(PlayerEngineKind.entries, key = { it.key }) { kind ->
            TvFocusableSurface(
                onClick = { onSelectEngine(kind) },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.01f,
                shape = RoundedCornerShape(14.dp),
            ) { focused ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (focused) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (kind == engine) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            kind.label,
                            color = if (kind == engine) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            kind.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    if (kind == engine) {
                        Text(
                            "当前",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        item(key = "profile-decoder-title") {
            Spacer(Modifier.height(8.dp))
            TvSectionHeader(title = "解码方式", modifier = Modifier.padding(horizontal = 0.dp))
            Spacer(Modifier.height(2.dp))
        }
        items(DecoderMode.entries, key = { "decoder-${it.key}" }) { mode ->
            TvFocusableSurface(
                onClick = { onSelectDecoderMode(mode) },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.01f,
                shape = RoundedCornerShape(14.dp),
            ) { focused ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (focused) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (mode == decoderMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            mode.label,
                            color = if (mode == decoderMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            mode.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    if (mode == decoderMode) {
                        Text(
                            "当前",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        item(key = "profile-subtitle-title") {
            Spacer(Modifier.height(8.dp))
            TvSectionHeader(title = "字幕", modifier = Modifier.padding(horizontal = 0.dp))
            Spacer(Modifier.height(2.dp))
        }
        item(key = "profile-subtitle-auto") {
            TvFocusableSurface(
                onClick = { onToggleSubtitlesAutoLoad(!subtitlePreference.autoLoad) },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.01f,
                shape = RoundedCornerShape(14.dp),
            ) { focused ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (focused) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自动加载字幕",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "开播时按优先语言自动选中一条字幕",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        if (subtitlePreference.autoLoad) "已开启" else "已关闭",
                        color = if (subtitlePreference.autoLoad) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (subtitlePreference.autoLoad) {
            items(SubtitlePriority.entries, key = { "subtitle-${it.key}" }) { priority ->
                TvFocusableSurface(
                    onClick = { onSelectSubtitlePriority(priority) },
                    modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1.01f,
                    shape = RoundedCornerShape(14.dp),
                ) { focused ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (focused) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (priority == subtitlePreference.priority) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                priority.label,
                                color = if (priority == subtitlePreference.priority) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                priority.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        if (priority == subtitlePreference.priority) {
                            Text(
                                "当前",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        item(key = "profile-logs-title") {
            Spacer(Modifier.height(8.dp))
            TvSectionHeader(title = "诊断与日志", modifier = Modifier.padding(horizontal = 0.dp))
            Spacer(Modifier.height(2.dp))
        }
        item(key = "profile-logs-export") {
            TvProfileRow(
                icon = Icons.Default.Description,
                title = "导出日志到文件夹",
                subtitle = "生成 ZIP，保存到系统选择的文件夹",
                onClick = exportLogs,
            )
        }
        item(key = "profile-logs-share") {
            TvProfileRow(
                icon = Icons.Default.Wifi,
                title = "局域网下载日志",
                subtitle = "用手机扫码下载，无需数据线",
                onClick = onOpenLogShare,
            )
        }

        item(key = "profile-logout") {
            Spacer(Modifier.height(18.dp))
            TvActionButton(
                text = if (account.busy) "正在退出…" else "退出登录",
                onClick = onLogout,
                primary = false,
                enabled = !account.busy,
                leading = {
                    Icon(
                        Icons.Default.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun TvProfileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1.01f,
        shape = RoundedCornerShape(14.dp),
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (focused) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
