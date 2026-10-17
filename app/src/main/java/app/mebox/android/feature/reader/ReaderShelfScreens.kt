package app.mebox.android.feature.reader

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderSearchBookDto
import app.mebox.android.core.reader.ReaderShelf
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
    val books = remember(state.books, state.groups, state.selectedGroup, state.style.shelfSort) {
        ReaderShelf.visible(
            books = state.books,
            groups = state.groups,
            groupName = state.selectedGroup,
            sort = state.style.shelfSort,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MeBoxTopBar(
            title = "阅读",
            subtitle = "${state.books.size} 本 · ${ReaderShelf.sortLabel(state.style.shelfSort)}",
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
        ) {
            SortMenu(current = state.style.shelfSort, onSelect = viewModel::setShelfSort)
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
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(books, key = { it.id }) { book ->
                            ReaderBookCard(
                                book = book,
                                origin = origin,
                                onClick = { viewModel.openBook(book) },
                            )
                        }
                    }
                }
            }
        }
    }
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
private fun ReaderBookCard(book: ReaderBookDto, origin: String, onClick: () -> Unit) {
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
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
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
                        onClick = { viewModel.openSearchDetail(book) },
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

    state.searchDetail?.let { detail ->
        SearchDetailDialog(
            detail = detail,
            state = state,
            origin = origin,
            viewModel = viewModel,
        )
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
private fun SearchDetailDialog(
    detail: ReaderSearchBookDto,
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val existing = state.books.firstOrNull { saved ->
        saved.bookUrl == detail.bookUrl ||
            (saved.name == detail.name && saved.author == detail.author)
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissSearchDetail,
        shape = RoundedCornerShape(16.dp),
        title = { Text(detail.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row {
                    Box(
                        modifier = Modifier
                            .width(84.dp)
                            .height(112.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
                    ) {
                        ReaderCover(
                            url = detail.coverUrl,
                            origin = origin,
                            title = detail.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        InfoLine("作者", detail.author.ifBlank { "未知作者" })
                        InfoLine("类型", detail.kind.ifBlank { "未分类" })
                        if (detail.wordCount.isNotBlank()) InfoLine("字数", detail.wordCount)
                        if (detail.latestChapter.isNotBlank()) InfoLine("最新", detail.latestChapter)
                        InfoLine("书源", "${detail.origins?.size ?: 0} 个")
                    }
                }
                if (detail.intro.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(detail.intro, style = MaterialTheme.typography.bodySmall)
                }
                state.addBookError?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (existing != null) {
                MeBoxButton(onClick = {
                    viewModel.dismissSearchDetail()
                    viewModel.openBook(existing)
                }) {
                    Text("开始阅读")
                }
            } else {
                MeBoxButton(onClick = viewModel::addSearchBookToShelf, enabled = !state.addingBook) {
                    if (state.addingBook) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("加入书架")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSearchDetail, enabled = !state.addingBook) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
