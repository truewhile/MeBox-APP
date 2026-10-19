package app.mebox.android.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderSearchBookDto
import app.mebox.android.core.reader.ReaderSearchScope
import app.mebox.android.core.reader.ReaderShelf
import app.mebox.android.core.reader.ReaderStyle
import app.mebox.android.core.reader.ReaderUrls
import app.mebox.android.core.ui.MeBoxBadge
import app.mebox.android.core.ui.MeBoxButton
import app.mebox.android.core.ui.MeBoxCard
import app.mebox.android.core.ui.MeBoxChip
import app.mebox.android.core.ui.MeBoxDimens
import app.mebox.android.core.ui.MeBoxEmptyState
import app.mebox.android.core.ui.MeBoxIconButton
import app.mebox.android.core.ui.MeBoxSearchField
import app.mebox.android.core.ui.MeBoxSegmentedBar
import app.mebox.android.core.ui.MeBoxTopBar

/**
 * 阅读 tab 的书架页：分组筛选、排序、目录更新与书籍入口。
 * 搜索是独立的全屏页，由 [ReaderScreen.Search] 切换。
 */
@Composable
internal fun ReaderShelfContent(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    val style = state.style
    val books = remember(state.books, state.groups, state.selectedGroup, style.shelfSort) {
        ReaderShelf.visible(
            books = state.books,
            groups = state.groups,
            groupName = state.selectedGroup,
            sort = style.shelfSort,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MeBoxTopBar(
            title = "阅读",
            subtitle = "${state.books.size} 本 · ${ReaderShelf.layoutLabel(style.shelfLayout)} · " +
                ReaderShelf.sortLabel(style.shelfSort),
            actions = {
                if (state.tocRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    MeBoxIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "更新目录",
                        onClick = viewModel::refreshToc,
                    )
                }
                Spacer(Modifier.width(8.dp))
                MeBoxChip(
                    text = "书源",
                    leadingIcon = Icons.Default.Bookmarks,
                    onClick = viewModel::openSources,
                )
                Spacer(Modifier.width(8.dp))
                MeBoxIconButton(
                    icon = Icons.Default.Search,
                    contentDescription = "搜索书籍",
                    onClick = viewModel::openSearch,
                )
            },
        )

        state.shelfNotice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(MeBoxDimens.PanelRadius),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(onClick = viewModel::clearShelfNotice),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭提示",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        if (state.groups.isNotEmpty()) {
            val groupLabels = listOf("全部") + state.groups.map { it.name }
            val selectedIndex = state.selectedGroup
                ?.let { name -> state.groups.indexOfFirst { it.name == name } + 1 }
                ?.takeIf { it > 0 }
                ?: 0
            MeBoxSegmentedBar(
                labels = groupLabels,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    viewModel.selectGroup(
                        if (index == 0) null else state.groups.getOrNull(index - 1)?.name,
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortMenu(current = state.style.shelfSort, onSelect = viewModel::setShelfSort)
            MeBoxChip(
                text = "书架设置",
                leadingIcon = Icons.Default.Tune,
                onClick = { settingsOpen = true },
            )
            Spacer(Modifier.weight(1f))
            if (state.shelfLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        when {
            state.books.isEmpty() && state.shelfLoading -> CenterBox {
                CircularProgressIndicator()
            }
            state.books.isEmpty() && state.shelfError != null -> CenterBox {
                PageMessage(state.shelfError, action = "重试", onAction = viewModel::refreshShelf)
            }
            state.books.isEmpty() -> CenterBox {
                PageMessage(
                    "书架还是空的。搜索一本书，加入后即可在这里阅读。",
                    action = "搜索书籍",
                    onAction = viewModel::openSearch,
                )
            }
            else -> Column(Modifier.fillMaxSize()) {
                state.shelfError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (books.isEmpty()) {
                    CenterBox {
                        PageMessage("这个分组还没有书")
                    }
                } else {
                    when (style.shelfLayout) {
                        ReaderShelf.LAYOUT_LIST -> LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(books, key = { it.id }) { book ->
                                ReaderBookRow(
                                    book = book,
                                    origin = origin,
                                    compact = false,
                                    showUnread = style.shelfShowUnread,
                                    showUpdateTime = style.shelfShowUpdateTime,
                                    onClick = { viewModel.openBook(book) },
                                    onRemove = { viewModel.requestShelfRemove(book) },
                                )
                            }
                        }
                        ReaderShelf.LAYOUT_COMPACT -> LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(books, key = { it.id }) { book ->
                                ReaderBookRow(
                                    book = book,
                                    origin = origin,
                                    compact = true,
                                    showUnread = style.shelfShowUnread,
                                    showUpdateTime = style.shelfShowUpdateTime,
                                    onClick = { viewModel.openBook(book) },
                                    onRemove = { viewModel.requestShelfRemove(book) },
                                )
                            }
                        }
                        else -> LazyVerticalGrid(
                            columns = if (style.shelfGridColumns > 0) {
                                GridCells.Fixed(style.shelfGridColumns)
                            } else {
                                GridCells.Adaptive(minSize = 104.dp)
                            },
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            gridItems(books, key = { it.id }) { book ->
                                ReaderBookCard(
                                    book = book,
                                    origin = origin,
                                    showUnread = style.shelfShowUnread,
                                    onClick = { viewModel.openBook(book) },
                                    onRemove = { viewModel.requestShelfRemove(book) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ShelfSettingsSheet(
            style = style,
            onDismiss = { settingsOpen = false },
            viewModel = viewModel,
        )
    }

    state.shelfRemoveTarget?.let { book ->
        ShelfRemoveDialog(state = state, book = book, viewModel = viewModel)
    }
}

/**
 * 移出书架确认框：本地导入 / 原地引用 / 书源书的后果不同，文案与服务端行为对齐。
 * 失败时弹窗保持打开并展示原因，用户可以直接重试。
 */
@Composable
private fun ShelfRemoveDialog(
    state: ReaderUiState,
    book: ReaderBookDto,
    viewModel: ReaderViewModel,
) {
    AlertDialog(
        onDismissRequest = {
            if (!state.shelfRemoveBusy) viewModel.cancelShelfRemove()
        },
        title = { Text("移出书架") },
        text = {
            Column {
                Text(
                    ReaderShelf.removeMessage(book),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.shelfRemoveError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.shelfRemoveBusy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在移出…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::confirmShelfRemove,
                enabled = !state.shelfRemoveBusy,
            ) {
                Text(if (state.shelfRemoveBusy) "移出中" else "移出", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::cancelShelfRemove,
                enabled = !state.shelfRemoveBusy,
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SortMenu(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MeBoxChip(
            text = "排序 · ${ReaderShelf.sortLabel(current)}",
            onClick = { expanded = true },
            trailingIcon = Icons.Default.ArrowDropDown,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(MeBoxDimens.PanelRadius),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ReaderShelf.SORT_KEYS.forEach { key ->
                DropdownMenuItem(
                    text = { Text(ReaderShelf.sortLabel(key), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderBookCard(
    book: ReaderBookDto,
    origin: String,
    showUnread: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(MeBoxDimens.PosterRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            ReaderCover(
                url = book.coverUrl,
                origin = origin,
                title = book.name,
                modifier = Modifier.fillMaxSize(),
            )
            if (book.durChapterTime > 0 && book.latestChapterTime > book.durChapterTime) {
                MeBoxBadge(
                    text = "更新",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
            if (showUnread && ReaderShelf.unreadChapters(book) != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    ShelfUnreadBadge(book)
                }
            }
            // 右下角操作入口：点封面进阅读，点这里打开「移出书架」。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { menuOpen = true },
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作：${book.name}",
                        modifier = Modifier
                            .padding(4.dp)
                            .size(15.dp),
                    )
                }
                ShelfBookMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onRemove = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author.ifBlank { "未知作者" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            ReaderShelf.progressLabel(book),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 列表 / 紧凑列表共用的行：大封面带进度和更新时间，紧凑布局只留书名与读到。 */
@Composable
private fun ReaderBookRow(
    book: ReaderBookDto,
    origin: String,
    compact: Boolean,
    showUnread: Boolean,
    showUpdateTime: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val readTitle = ReaderShelf.readChapterTitle(book)
    val updated = ReaderShelf.updateTime(book)
    var menuOpen by remember { mutableStateOf(false) }
    MeBoxCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(if (compact) 48.dp else 66.dp)
                    .height(if (compact) 64.dp else 90.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                ReaderCover(
                    url = book.coverUrl,
                    origin = origin,
                    title = book.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (showUnread) {
                        Spacer(Modifier.width(8.dp))
                        ShelfUnreadBadge(book)
                    }
                }
                if (compact) {
                    val line = listOfNotNull(
                        book.author.ifBlank { "未知作者" },
                        readTitle.takeIf { it.isNotBlank() }?.let { "读到 $it" },
                    ).joinToString(" · ")
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        listOfNotNull(
                            book.author.ifBlank { "未知作者" },
                            updated.takeIf { showUpdateTime && it > 0 }
                                ?.let(ReaderShelf::formatRelativeTime),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (readTitle.isBlank()) "尚未开始阅读" else "读到 $readTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "最新 ${book.latestChapterTitle.ifBlank { "未知" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box {
                MeBoxIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "更多操作：${book.name}",
                    onClick = { menuOpen = true },
                )
                ShelfBookMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onRemove = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** 书架卡片的操作菜单；目前只有「移出书架」，预留后续的详情 / 分组入口。 */
@Composable
private fun ShelfBookMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MeBoxDimens.PanelRadius),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    "移出书架",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onRemove,
        )
    }
}

/** 未读章数徽标；目录未缓存（总章数为 0）时不显示。 */
@Composable
private fun ShelfUnreadBadge(book: ReaderBookDto) {
    val unread = ReaderShelf.unreadChapters(book) ?: return
    MeBoxBadge(
        text = if (unread > 0) "$unread 章未读" else "已读完",
        color = if (unread > 0) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
        } else {
            Color(0xCC9AA0A6)
        },
    )
}

/** 书架设置弹层：布局、网格列数、排序与显示项，改动立即生效并同步到服务端。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfSettingsSheet(
    style: ReaderStyle,
    onDismiss: () -> Unit,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "书架设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "布局、排序与显示项，与网页端共用同一份设置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            ShelfSectionTitle("布局")
            Spacer(Modifier.height(8.dp))
            ReaderShelf.LAYOUT_KEYS.forEach { key ->
                ShelfRadioRow(
                    active = style.shelfLayout == key,
                    label = ReaderShelf.layoutLabel(key),
                    hint = when (key) {
                        ReaderShelf.LAYOUT_LIST -> "封面 + 书名 + 读到 / 最新章节"
                        ReaderShelf.LAYOUT_COMPACT -> "小封面单行，一屏放更多书"
                        else -> "封面宫格，可再选列数"
                    },
                    onClick = { viewModel.setShelfLayout(key) },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (style.shelfLayout == ReaderShelf.LAYOUT_GRID) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "列数",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ReaderShelf.GRID_COLUMNS.chunked(3).forEach { rowColumns ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowColumns.forEach { columns ->
                            MeBoxChip(
                                text = ReaderShelf.gridColumnsLabel(columns),
                                selected = style.shelfGridColumns == columns,
                                onClick = { viewModel.setShelfGridColumns(columns) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            ShelfSectionTitle("排序")
            Spacer(Modifier.height(8.dp))
            ReaderShelf.SORT_KEYS.chunked(3).forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowKeys.forEach { key ->
                        MeBoxChip(
                            text = ReaderShelf.sortLabel(key),
                            selected = style.shelfSort == key,
                            onClick = { viewModel.setShelfSort(key) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            ShelfSectionTitle("显示项")
            Spacer(Modifier.height(4.dp))
            ShelfToggleRow(
                label = "未读章数",
                hint = "封面右上角显示还剩多少章没读",
                active = style.shelfShowUnread,
                onToggle = viewModel::setShelfShowUnread,
            )
            ShelfToggleRow(
                label = "更新时间",
                hint = "列表布局显示最近一次检测到新章节的时间",
                active = style.shelfShowUpdateTime,
                onToggle = viewModel::setShelfShowUpdateTime,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ShelfSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun ShelfRadioRow(
    active: Boolean,
    label: String,
    hint: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MeBoxDimens.ActionRadius))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(MeBoxDimens.ActionRadius),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            0.5.dp,
            if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ShelfToggleRow(
    label: String,
    hint: String,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MeBoxDimens.ActionRadius))
            .clickable { onToggle(!active) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = active, onCheckedChange = onToggle)
    }
}

/** 搜索页：整页替换书架，返回时回到书架。 */
@Composable
internal fun ReaderSearchContent(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        MeBoxTopBar(
            title = "搜索书籍",
            onBack = viewModel::closeSearch,
        )
        MeBoxSearchField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = "搜索书名或作者",
            onSearch = {
                keyboard?.hide()
                viewModel.submitSearch()
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        SearchScopeBar(state = state, viewModel = viewModel)

        if (state.searchSkipped.isNotEmpty()) {
            val summary = state.searchSkipped
                .groupingBy { it.reason.ifBlank { "未知原因" } }
                .eachCount()
                .entries
                .joinToString("；") { "${it.key}（${it.value}）" }
            Text(
                "部分书源未返回：$summary",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            state.searchLoading -> CenterBox { CircularProgressIndicator() }
            state.searchError != null && state.searchBooks.isEmpty() -> CenterBox {
                PageMessage(state.searchError, action = "重试", onAction = viewModel::submitSearch)
            }
            state.searchBooks.isEmpty() -> CenterBox {
                PageMessage(
                    if (state.searchPage > 0) "没有找到匹配的书籍" else "输入书名或作者开始搜索",
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.searchBooks) { _, book ->
                    SearchResultRow(
                        book = book,
                        origin = origin,
                        onClick = {
                            keyboard?.hide()
                            viewModel.openSearchDetail(book)
                        },
                    )
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        state.searchError?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = viewModel::loadMoreSearch,
                            enabled = !state.searchLoadingMore,
                        ) {
                            if (state.searchLoadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("加载更多")
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.searchScopeOpen) {
        SearchScopeSheet(state = state, viewModel = viewModel)
    }
}

/** 搜索范围入口：显示当前范围与已启用书源数量。 */
@Composable
private fun SearchScopeBar(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
) {
    val enabledCount = state.sources.count { it.enabled }
    val scopeAll = ReaderSearchScope.isAll(state.searchScopeIds, state.sources)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeBoxChip(
            text = "搜索范围 · ${ReaderSearchScope.label(state.searchScopeIds, state.sources)}",
            leadingIcon = Icons.Default.Tune,
            onClick = viewModel::openSearchScope,
        )
        if (scopeAll && enabledCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "共 $enabledCount 个启用书源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 搜索范围弹窗：按分组列出书源，默认全选已启用书源。
 * 确认时若覆盖全部启用书源会存成空集合，表示「全部」，新增书源自动纳入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScopeSheet(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf("") }
    val enabledIds = remember(state.sources) {
        ReaderSearchScope.enabledIds(state.sources).toSet()
    }
    // draft 为 null 表示默认全选；用户第一次切换后才变成显式集合。
    val selected = state.searchScopeDraft ?: enabledIds
    val groups = remember(state.sources, filter) {
        ReaderSearchScope.groups(state.sources, filter)
    }
    val visibleSources = remember(groups) { groups.flatMap { it.sources } }
    val visibleEnabled = remember(visibleSources) {
        visibleSources.filter { it.enabled }
    }
    val pickedCount = visibleEnabled.count { it.id in selected }
    val pickedTotal = enabledIds.count { it in selected }
    val loading = state.sourcesLoading && state.sources.isEmpty()

    ModalBottomSheet(
        onDismissRequest = viewModel::closeSearchScope,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("搜索范围", style = MaterialTheme.typography.titleMedium)
            Text(
                "默认搜索全部启用书源 · 已启用 ${enabledIds.size} 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("过滤书源名 / 分组", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        viewModel.setSearchScopeDraft(
                            ReaderSearchScope.toggleVisible(selected, visibleSources, true),
                        )
                    },
                    enabled = visibleEnabled.isNotEmpty(),
                ) {
                    Text("全选")
                }
                TextButton(
                    onClick = {
                        viewModel.setSearchScopeDraft(
                            ReaderSearchScope.toggleVisible(selected, visibleSources, false),
                        )
                    },
                    enabled = pickedCount > 0,
                ) {
                    Text("清空")
                }
            }

            when {
                loading -> ScopeMessageBox {
                    CircularProgressIndicator()
                }
                state.sources.isEmpty() && state.sourcesError != null -> ScopeMessageBox {
                    PageMessage(
                        state.sourcesError,
                        action = "重试",
                        onAction = viewModel::refreshSources,
                    )
                }
                state.sources.isEmpty() -> ScopeMessageBox {
                    PageMessage(
                        "还没有书源，先去书源管理导入",
                        action = "去导入",
                        onAction = {
                            viewModel.closeSearchScope()
                            viewModel.openSources()
                        },
                    )
                }
                groups.isEmpty() -> ScopeMessageBox {
                    PageMessage("没有匹配「${filter.trim()}」的书源")
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    groups.forEach { group ->
                        val groupEnabled = group.sources.filter { it.enabled }
                        val groupChecked = groupEnabled.isNotEmpty() &&
                            groupEnabled.all { it.id in selected }
                        val groupPartial = !groupChecked && groupEnabled.any { it.id in selected }
                        item(key = "group-${group.name}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = groupEnabled.isNotEmpty()) {
                                        viewModel.setSearchScopeDraft(
                                            ReaderSearchScope.toggleGroup(selected, group),
                                        )
                                    }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TriStateCheckbox(
                                    state = when {
                                        groupChecked -> ToggleableState.On
                                        groupPartial -> ToggleableState.Indeterminate
                                        else -> ToggleableState.Off
                                    },
                                    onClick = if (groupEnabled.isEmpty()) {
                                        null
                                    } else {
                                        {
                                            viewModel.setSearchScopeDraft(
                                                ReaderSearchScope.toggleGroup(selected, group),
                                            )
                                        }
                                    },
                                )
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${group.sources.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(group.sources, key = { "source-${it.id}" }) { source ->
                            val checked = source.enabled && source.id in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = source.enabled) {
                                        viewModel.setSearchScopeDraft(
                                            if (source.id in selected) {
                                                selected - source.id
                                            } else {
                                                selected + source.id
                                            },
                                        )
                                    }
                                    .padding(start = 25.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = if (source.enabled) {
                                        {
                                            viewModel.setSearchScopeDraft(
                                                if (source.id in selected) {
                                                    selected - source.id
                                                } else {
                                                    selected + source.id
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    enabled = source.enabled,
                                )
                                Text(
                                    source.name.ifBlank { source.sourceUrl },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (source.enabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!source.enabled) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) {
                                        Text(
                                            "已禁用",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        enabledIds.isNotEmpty() && pickedTotal == enabledIds.size ->
                            ReaderSearchScope.ALL_LABEL
                        pickedTotal > 0 -> "已选 $pickedTotal / ${enabledIds.size} 个书源"
                        else -> "未选择书源"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::closeSearchScope) { Text("取消") }
                MeBoxButton(
                    onClick = viewModel::confirmSearchScope,
                    enabled = !loading && pickedTotal > 0,
                ) {
                    Text("确定")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SearchResultRow(
    book: ReaderSearchBookDto,
    origin: String,
    onClick: () -> Unit,
) {
    MeBoxCard(onClick = onClick) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                ReaderCover(
                    url = book.coverUrl,
                    origin = origin,
                    title = book.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(book.author.ifBlank { "未知作者" }, book.kind)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (book.latestChapter.isNotBlank()) {
                    Text(
                        "最新：${book.latestChapter}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.intro.isNotBlank()) {
                    Text(
                        book.intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${book.origins?.size ?: 0} 源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ReaderCover(
    url: String?,
    origin: String,
    title: String,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val resolved = if (ReaderUrls.isRenderable(url)) ReaderUrls.resolve(origin, url) else null
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                title.take(1).ifBlank { "书" },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScopeMessageBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun PageMessage(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            MeBoxButton(onClick = onAction) { Text(action) }
        }
    }
}
