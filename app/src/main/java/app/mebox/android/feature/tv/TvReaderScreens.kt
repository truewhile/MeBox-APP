package app.mebox.android.feature.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.reader.ReaderParagraph
import app.mebox.android.core.reader.ReaderPaging
import app.mebox.android.core.reader.ReaderShelf
import app.mebox.android.core.reader.ReaderUrls
import app.mebox.android.core.ui.MeBoxTheme
import app.mebox.android.feature.media.MediaArtwork
import app.mebox.android.feature.reader.ReaderUiState
import app.mebox.android.feature.reader.ReaderViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/* ─────────────────────────── 书架 ─────────────────────────── */

@Composable
fun TvReaderShelfScreen(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
    onOpenPhoneSearch: () -> Unit,
) {
    var group by remember { mutableStateOf<String?>(null) }
    val books = remember(state.books, state.groups, group, state.style.shelfSort) {
        ReaderShelf.visible(
            books = state.books,
            groups = state.groups,
            groupName = group,
            sort = state.style.shelfSort,
        )
    }

    Column(Modifier.fillMaxSize()) {
        TvPageHeader(
            title = "阅读",
            subtitle = "${state.books.size} 本 · ${ReaderShelf.sortLabel(state.style.shelfSort)}",
            actions = {
                TvActionButton(
                    text = "手机搜索",
                    onClick = onOpenPhoneSearch,
                    primary = false,
                    leading = {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                TvActionButton(
                    text = if (state.tocRefreshing) "更新中…" else "更新目录",
                    onClick = viewModel::refreshToc,
                    primary = false,
                    enabled = !state.tocRefreshing,
                    leading = {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            },
        )

        if (state.groups.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "group-all") {
                    TvActionButton(
                        text = "全部",
                        onClick = { group = null },
                        primary = group == null,
                    )
                }
                itemsIndexed(
                    items = state.groups,
                    key = { index, _ -> "reader-group-$index" },
                ) { _, option ->
                    TvActionButton(
                        text = option.name,
                        onClick = { group = option.name },
                        primary = group == option.name,
                    )
                }
            }
        }

        when {
            state.shelfLoading && state.books.isEmpty() -> TvLoading("正在加载书架…")
            state.shelfError != null && state.books.isEmpty() ->
                TvMessage(state.shelfError, actionLabel = "重试", onAction = viewModel::refreshShelf)

            books.isEmpty() -> TvMessage(
                message = "书架还是空的",
                actionLabel = "用手机搜索书籍",
                onAction = onOpenPhoneSearch,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    TvBookCard(
                        book = book,
                        origin = origin,
                        onClick = { viewModel.openBook(book) },
                    )
                }
            }
        }
    }

    state.shelfNotice?.let { notice ->
        LaunchedEffect(notice) {
            delay(3600)
            viewModel.clearShelfNotice()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 28.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                notice,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TvBookCard(
    book: ReaderBookDto,
    origin: String,
    onClick: () -> Unit,
) {
    TargetCard(
        title = book.name.ifBlank { "未命名" },
        author = book.author,
        coverUrl = ReaderUrls.resolve(origin, book.coverUrl),
        badge = book.durChapterIndex.takeIf { book.totalChapterNum > 0 }
            ?.let { "${it + 1}/${book.totalChapterNum}" },
        onClick = onClick,
    )
}

@Composable
private fun TargetCard(
    title: String,
    author: String,
    coverUrl: String?,
    badge: String?,
    onClick: () -> Unit,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) { focused ->
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MediaArtwork(
                    url = coverUrl,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                )
                badge?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(it, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (author.isNotBlank()) {
                Text(
                    author,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ─────────────────────────── 阅读搜索 ─────────────────────────── */

@Composable
fun TvReaderSearchScreen(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
    onOpenPhoneSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TvPageHeader(
            title = "阅读搜索",
            subtitle = state.query.takeIf { it.isNotBlank() }?.let { "“$it” · ${state.searchBooks.size} 个结果" },
            onBack = viewModel::closeSearch,
            actions = {
                TvActionButton(
                    text = "重新扫码搜索",
                    onClick = onOpenPhoneSearch,
                    primary = false,
                    leading = {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            },
        )

        when {
            state.query.isBlank() -> TvMessage(
                message = "用手机扫码输入书名或作者，电视会显示搜索结果",
                actionLabel = "手机扫码搜索",
                onAction = onOpenPhoneSearch,
            )

            state.searchLoading && state.searchBooks.isEmpty() -> TvLoading("正在搜索书源…")

            state.searchError != null && state.searchBooks.isEmpty() ->
                TvMessage(state.searchError, actionLabel = "重新搜索", onAction = onOpenPhoneSearch)

            state.searchBooks.isEmpty() -> TvMessage(
                message = "没有找到可用的书籍",
                actionLabel = "换个关键词",
                onAction = onOpenPhoneSearch,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(
                    items = state.searchBooks,
                    key = { index, book -> "reader-search-${book.bookUrl}-$index" },
                ) { _, book ->
                    TvFocusableSurface(
                        onClick = { viewModel.openSearchDetail(book) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { focused ->
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                MediaArtwork(
                                    url = ReaderUrls.resolve(origin, book.coverUrl),
                                    title = book.name,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                book.name,
                                color = if (focused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onBackground
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    book.author.takeIf { it.isNotBlank() },
                                    book.origins?.size?.takeIf { it > 0 }?.let { "$it 个书源" },
                                ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (state.searchBooks.size < 200) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TvActionButton(
                                text = if (state.searchLoadingMore) "加载中…" else "加载更多",
                                onClick = viewModel::loadMoreSearch,
                                primary = false,
                                enabled = !state.searchLoadingMore,
                            )
                        }
                    }
                }
            }
        }
    }

    state.searchDetail?.let { book ->
        TvReaderBookDialog(
            state = state,
            origin = origin,
            book = book,
            onDismiss = viewModel::dismissSearchDetail,
            onAdd = viewModel::addSearchBookToShelf,
        )
    }
}

@Composable
private fun TvReaderBookDialog(
    state: ReaderUiState,
    origin: String,
    book: app.mebox.android.core.network.ReaderSearchBookDto,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(180)
        runCatching { addFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MeBoxTheme(darkTheme = true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(28.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(148.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    MediaArtwork(
                        url = ReaderUrls.resolve(origin, book.coverUrl),
                        title = book.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            book.author.takeIf { it.isNotBlank() },
                            book.kind.takeIf { it.isNotBlank() },
                            book.wordCount.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Text(
                        if (state.detailChaptersLoading && state.detailChapters.isEmpty()) {
                            "章数加载中…"
                        } else {
                            "共 ${state.detailChapters.size} 章"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        book.intro.ifBlank { "暂无简介" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.addBookError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            text = if (state.addingBook) "正在加入…" else "加入书架",
                            onClick = onAdd,
                            enabled = !state.addingBook,
                            focusRequester = addFocus,
                        )
                        TvActionButton(
                            text = "取消",
                            onClick = onDismiss,
                            primary = false,
                            enabled = !state.addingBook,
                        )
                    }
                }
            }
        }
    }
}

/* ─────────────────────────── 正文阅读 ─────────────────────────── */

@Composable
fun TvReaderReadingScreen(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val isAudio = state.content?.type == "audio"
    if (isAudio) {
        val audioBackFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(100)
            runCatching { audioBackFocus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "这是一本音频书",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "电视端暂不提供听书播放器，请回到书架选择文字书籍，或在手机上继续收听。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            TvActionButton(
                text = "返回书架",
                onClick = viewModel::closeReading,
                focusRequester = audioBackFocus,
            )
        }
        return
    }

    val book = state.book
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val charsPerPage = remember(
        state.style.fontSize,
        state.style.lineHeight,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        ReaderPaging.charsPerPage(
            fontSizeSp = state.style.fontSize,
            lineHeight = state.style.lineHeight,
            viewportWidthDp = configuration.screenWidthDp.toFloat(),
            viewportHeightDp = configuration.screenHeightDp.toFloat(),
        )
    }
    var chromeVisible by remember { mutableStateOf(true) }
    // 漫画按图片序号恢复/上报进度；文本按段落换算页码。两者共用同一个列表，
    // 用基准位置避免刚恢复就回写进度导致每次打开都偏移。
    var baselineIndex by remember(state.chapterIndex, state.content) { mutableIntStateOf(-1) }
    var positionReady by remember(state.chapterIndex, state.content) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(state.tocOpen) {
        if (!state.tocOpen) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(state.chapterNotice) {
        if (state.chapterNotice != null) {
            delay(3600)
            viewModel.clearChapterNotice()
        }
    }
    LaunchedEffect(state.book?.id, state.chapterIndex, state.chapterLoading, state.content) {
        positionReady = false
        baselineIndex = -1
        if (state.chapterLoading) return@LaunchedEffect
        if (state.content?.type == "image") {
            val images = state.content.images.orEmpty()
            if (images.isEmpty()) return@LaunchedEffect
            val target = state.restorePage.coerceIn(0, images.lastIndex)
            runCatching { listState.scrollToItem(target) }
        } else {
            if (state.paragraphs.isEmpty()) return@LaunchedEffect
            val target = ReaderPaging.paragraphForPage(
                page = state.restorePage,
                paragraphs = state.paragraphs,
                charsPerPage = charsPerPage,
            )
            runCatching { listState.scrollToItem(target.coerceIn(0, state.paragraphs.lastIndex)) }
        }
        baselineIndex = listState.firstVisibleItemIndex
        positionReady = true
    }
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        positionReady,
        state.chapterIndex,
        state.content,
    ) {
        if (!positionReady || listState.firstVisibleItemIndex == baselineIndex) {
            return@LaunchedEffect
        }
        if (state.content?.type == "image") {
            val images = state.content.images.orEmpty()
            if (images.isEmpty()) return@LaunchedEffect
            viewModel.onPageChanged(listState.firstVisibleItemIndex.coerceIn(0, images.lastIndex))
        } else {
            if (state.paragraphs.isEmpty()) return@LaunchedEffect
            val page = ReaderPaging.pageForParagraph(
                index = listState.firstVisibleItemIndex,
                paragraphs = state.paragraphs,
                charsPerPage = charsPerPage,
            )
            viewModel.onPageChanged(page)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101013))
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (state.tocOpen) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                if (event.type == KeyEventType.KeyDown) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            listState.dispatchRawDelta(460f)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_UP -> {
                            listState.dispatchRawDelta(-460f)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            viewModel.stepChapter(1)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            viewModel.stepChapter(-1)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            chromeVisible = !chromeVisible
                            true
                        }

                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_INFO -> {
                            viewModel.toggleToc()
                            true
                        }

                        else -> false
                    }
                } else {
                    code == KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_NUMPAD_ENTER
                }
            }
            .focusable(),
    ) {
        when {
            state.chapterLoading -> TvLoading("正在加载章节…")
            state.chapterError != null && state.content == null -> TvMessage(
                message = state.chapterError,
                actionLabel = "重试",
                onAction = viewModel::retryChapter,
            )

            state.content?.type == "image" -> {
                val images = state.content.images.orEmpty()
                if (images.isEmpty()) {
                    TvMessage("本章没有可显示的图片")
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 96.dp,
                            end = 96.dp,
                            top = if (chromeVisible) 92.dp else 40.dp,
                            bottom = 80.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(
                            items = images,
                            key = { index, _ -> "${state.chapterIndex}:$index" },
                        ) { index, url ->
                            AsyncImage(
                                model = ReaderUrls.resolve(origin, url),
                                contentDescription = "第 ${index + 1} 页",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            state.paragraphs.isEmpty() -> TvMessage("本章没有可显示的正文")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 96.dp,
                    end = 96.dp,
                    top = if (chromeVisible) 92.dp else 40.dp,
                    bottom = 80.dp,
                ),
            ) {
                items(state.paragraphs, key = { it.line }) { paragraph ->
                    TvReaderParagraph(
                        paragraph = paragraph,
                        origin = origin,
                        fontSize = state.style.fontSize,
                        lineHeight = state.style.lineHeight,
                        paragraphSpacing = state.style.paragraphSpacing,
                    )
                }
            }
        }

        if (chromeVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6101013))
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.chapterTitle,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        book?.name.orEmpty(),
                        color = Color(0xB3FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${state.chapterIndex + 1} / ${state.chapters.size}",
                    color = Color(0xB3FFFFFF),
                    fontSize = 13.sp,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xE6101013))
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "上下键滚动 · 左右键切换章节 · OK 隐藏栏 · 菜单键目录",
                    color = Color(0xB3FFFFFF),
                    fontSize = 13.sp,
                )
                Text(
                    "返回键退出阅读",
                    color = Color(0xB3FFFFFF),
                    fontSize = 13.sp,
                )
            }
        }

        state.chapterNotice?.let { notice ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp),
            ) {
                Text(
                    notice,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xD9242B38))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (state.tocOpen) {
        TvReaderTocDialog(
            state = state,
            onSelect = { index ->
                viewModel.jumpToChapter(index)
                viewModel.closeToc()
            },
            onDismiss = viewModel::closeToc,
        )
    }
}

@Composable
private fun TvReaderTocDialog(
    state: ReaderUiState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.chapterIndex.coerceAtLeast(0),
    )
    LaunchedEffect(Unit) {
        delay(180)
        runCatching { currentFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MeBoxTheme(darkTheme = true) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "目录",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.chapters.size} 章",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(state.chapters, key = { _, chapter -> chapter.index }) { _, chapter ->
                        TvFocusableSurface(
                            onClick = { onSelect(chapter.index) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (chapter.index == state.chapterIndex) {
                                        Modifier.focusRequester(currentFocus)
                                    } else {
                                        Modifier
                                    },
                                ),
                            focusedScale = 1.01f,
                            shape = RoundedCornerShape(10.dp),
                        ) { focused ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        when {
                                            chapter.index == state.chapterIndex ->
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            focused -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> Color.Transparent
                                        },
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${chapter.index + 1}",
                                    color = if (chapter.index == state.chapterIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(42.dp),
                                )
                                Text(
                                    chapter.title.ifBlank { "第 ${chapter.index + 1} 章" },
                                    color = if (chapter.index == state.chapterIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TvActionButton(
                    text = "关闭",
                    onClick = onDismiss,
                    primary = false,
                )
            }
        }
    }
}

@Composable
private fun TvReaderParagraph(
    paragraph: ReaderParagraph,
    origin: String,
    fontSize: Float,
    lineHeight: Float,
    paragraphSpacing: Int,
) {
    if (paragraph.isImage) {
        AsyncImage(
            model = ReaderUrls.resolve(origin, paragraph.imageUrl),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    } else {
        val spacing = if (paragraphSpacing < 6) 6 else paragraphSpacing
        Text(
            paragraph.text,
            color = Color(0xE6FFFFFF),
            fontSize = (fontSize * 1.22f).sp,
            lineHeight = (fontSize * 1.22f * lineHeight).sp,
            modifier = Modifier.padding(bottom = spacing.dp),
        )
    }
}
