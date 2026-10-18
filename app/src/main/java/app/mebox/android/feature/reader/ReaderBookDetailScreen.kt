package app.mebox.android.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mebox.android.core.network.ReaderSearchBookDto
import app.mebox.android.core.network.ReaderTocChapterDto
import app.mebox.android.core.reader.ReaderBookDetail
import app.mebox.android.core.ui.MeBoxButton
import app.mebox.android.core.ui.MeBoxCard
import app.mebox.android.core.ui.MeBoxIconButton
import app.mebox.android.core.ui.MeBoxOutlinedButton
import app.mebox.android.core.ui.MeBoxTopBar

/**
 * 搜索结果书籍详情页（仿 legado BookInfoActivity）：
 * 封面 + 书名/作者/分类/字数/最新章节 + 简介 + 加入书架/开始阅读/换源 + 完整目录。
 *
 * 与旧弹窗的区别：进入页面就向书源抓详情和目录，因此在加入书架之前就能看到
 * 「共 N 章」并直接点章节试读；已在书架的书保留进度，按钮变成继续阅读/移出书架。
 */
@Composable
internal fun ReaderBookDetailContent(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val detail = state.searchDetail ?: return
    val info = state.detailInfo
    val name = info?.name?.takeIf { it.isNotBlank() } ?: detail.name
    val author = info?.author?.takeIf { it.isNotBlank() } ?: detail.author
    val cover = info?.coverUrl?.takeIf { it.isNotBlank() } ?: detail.coverUrl
    val kind = info?.kind?.takeIf { it.isNotBlank() } ?: detail.kind
    val wordCount = info?.wordCount?.takeIf { it.isNotBlank() } ?: detail.wordCount
    val latest = info?.latestChapter?.takeIf { it.isNotBlank() } ?: detail.latestChapter
    val intro = info?.intro?.takeIf { it.isNotBlank() } ?: detail.intro
    val shelfBook = state.detailShelfBook
    val firstReadable = ReaderBookDetail.firstReadable(state.detailChapters)
    val canStartReading = shelfBook != null ||
        state.detailChapters.isEmpty() ||
        firstReadable != null
    val sourceName = state.detailOrigin
        ?.let { it.originName.ifBlank { it.origin } }
        ?.takeIf { it.isNotBlank() }
        ?: detail.origins?.firstOrNull()?.originName.orEmpty()
    var introExpanded by remember(name) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        MeBoxTopBar(
            title = name,
            subtitle = listOfNotNull(
                author.takeIf { it.isNotBlank() },
                state.detailChapters.size.takeIf { it > 0 }?.let { "共 $it 章" },
            ).joinToString(" · ").ifBlank { null },
            onBack = viewModel::dismissSearchDetail,
            actions = {
                if (state.detailChaptersLoading || state.detailInfoLoading) {
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
                        contentDescription = "刷新详情目录",
                        onClick = viewModel::refreshSearchDetail,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "detail-header") {
                MeBoxCard {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Box(
                            modifier = Modifier
                                .width(92.dp)
                                .height(128.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            ReaderCover(
                                url = cover,
                                origin = origin,
                                title = name,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                author.ifBlank { "未知作者" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (kind.isNotBlank() || wordCount.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOf(kind, wordCount)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (latest.isNotBlank()) {
                                Text(
                                    "最新：$latest",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (sourceName.isNotBlank()) {
                                Text(
                                    "书源：$sourceName",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (state.detailChaptersLoading && state.detailChapters.isEmpty()) {
                                    "章数加载中…"
                                } else {
                                    "共 ${state.detailChapters.size} 章"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (intro.isNotBlank() || state.detailInfoError != null) {
                item(key = "detail-intro") {
                    Column {
                        Text(
                            intro,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (introExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (intro.length > 120) {
                            TextButton(
                                onClick = { introExpanded = !introExpanded },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text(if (introExpanded) "收起" else "展开简介")
                            }
                        }
                        state.detailInfoError?.let { error ->
                            Text(
                                "详情加载失败：$error",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = viewModel::refreshSearchDetail,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("重试")
                            }
                        }
                    }
                }
            }

            item(key = "detail-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (shelfBook != null) {
                            MeBoxOutlinedButton(
                                onClick = viewModel::removeDetailBookFromShelf,
                                enabled = !state.addingBook,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("移出书架")
                            }
                        } else {
                            MeBoxOutlinedButton(
                                onClick = viewModel::addDetailBookToShelf,
                                enabled = !state.addingBook,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.addingBook) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("加入中…")
                                } else {
                                    Text("加入书架")
                                }
                            }
                        }
                        MeBoxButton(
                            onClick = { viewModel.startReadingFromDetail() },
                            enabled = !state.addingBook && canStartReading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (shelfBook?.hasProgress == true) "继续阅读" else "开始阅读")
                        }
                    }
                    MeBoxOutlinedButton(
                        onClick = viewModel::openDetailOriginPicker,
                        enabled = !state.addingBook,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (sourceName.isNotBlank()) "换源 · 当前 $sourceName" else "换源",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.addBookError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item(key = "detail-toc-header") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("目录", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                state.detailChaptersLoading && state.detailChapters.isEmpty() -> "加载中…"
                                else -> "共 ${state.detailChapters.size} 章"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        if (!state.detailChaptersLoading) {
                            TextButton(
                                onClick = viewModel::refreshSearchDetail,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("刷新")
                            }
                        }
                    }
                    // 目录已经有内容时，二次抓取失败不遮挡列表，只在标题下提示。
                    state.detailChaptersError
                        ?.takeIf { state.detailChapters.isNotEmpty() }
                        ?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                }
            }

            when {
                state.detailChaptersLoading && state.detailChapters.isEmpty() -> item(key = "detail-toc-loading") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在向书源获取目录…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.detailChaptersError != null && state.detailChapters.isEmpty() -> item(
                    key = "detail-toc-error",
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            state.detailChaptersError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::refreshSearchDetail) {
                            Text("重试")
                        }
                    }
                }

                state.detailChapters.isEmpty() -> item(key = "detail-toc-empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "书源没有返回目录，暂时无法统计章数",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::refreshSearchDetail) {
                            Text("重试")
                        }
                    }
                }

                else -> {
                    if (shelfBook == null) {
                        item(key = "detail-toc-hint") {
                            Text(
                                "点击章节可先读后加（会自动加入书架）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(
                        items = state.detailChapters,
                        key = { position, chapter -> "$position\u0000${chapter.index}\u0000${chapter.url}" },
                    ) { _, chapter ->
                        ReaderDetailChapterRow(
                            chapter = chapter,
                            enabled = !state.addingBook,
                            onClick = { viewModel.startReadingFromDetail(chapter) },
                        )
                    }
                }
            }
        }
    }

    if (state.detailOriginPickerOpen) {
        ReaderDetailOriginSheet(state = state, viewModel = viewModel)
    }
}

@Composable
private fun ReaderDetailChapterRow(
    chapter: ReaderTocChapterDto,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (chapter.isVolume) {
        Text(
            chapter.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        return
    }
    val clickable = enabled && chapter.url.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chapter.title.ifBlank { "第 ${chapter.index + 1} 章" },
            style = MaterialTheme.typography.bodySmall,
            color = if (clickable) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 详情页换源：候选来自按书名重新搜索的结果，选中后重新抓详情和目录。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderDetailOriginSheet(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val book: ReaderSearchBookDto? = state.searchDetail
    ModalBottomSheet(
        onDismissRequest = viewModel::closeDetailOriginPicker,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("换源", style = MaterialTheme.typography.titleMedium)
            Text(
                book?.name.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.detailOrigin?.let { origin ->
                Text(
                    "当前：${origin.originName.ifBlank { origin.origin }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.detailOriginLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.detailOrigins.isEmpty() -> Text(
                    state.detailOriginError ?: "没有找到其他可用书源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(
                        items = state.detailOrigins,
                        key = { "${it.origin}\u0000${it.bookUrl}" },
                    ) { origin ->
                        OriginRow(
                            origin = origin,
                            enabled = !state.addingBook,
                            onClick = { viewModel.selectDetailOrigin(origin) },
                        )
                    }
                }
            }
            if (state.addingBook) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在切换书源…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.detailOriginError
                ?.takeIf { state.detailOrigins.isNotEmpty() }
                ?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            Spacer(Modifier.height(16.dp))
        }
    }
}
