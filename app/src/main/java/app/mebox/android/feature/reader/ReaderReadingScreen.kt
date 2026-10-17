package app.mebox.android.feature.reader

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import app.mebox.android.core.reader.ReaderPaging
import app.mebox.android.core.reader.ReaderStyle
import app.mebox.android.core.reader.ReaderUrls
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * 全屏阅读页：纵向正文/漫画、章节切换、目录与阅读设置。
 *
 * 界面只负责排版和把「当前页」上报给 ViewModel；进度落库、章节解析与
 * 目录重建都在 ViewModel/服务端完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderReadingScreen(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val palette = readerPalette(state.style)
    val audioMode = state.content?.type == "audio"
    val sheetPalette = if (audioMode) AudioPlayerPalette else palette
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val darkSystemBars = audioMode || state.style.night
    LaunchedEffect(darkSystemBars) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        // 夜间主题/播放页是深色背景，状态栏图标必须转成浅色，否则看不见。
        controller.isAppearanceLightStatusBars = !darkSystemBars
        controller.isAppearanceLightNavigationBars = !darkSystemBars
    }
    DisposableEffect(view, systemDark) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !systemDark
            controller.isAppearanceLightNavigationBars = !systemDark
        }
    }
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
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(state.content?.type) {
        if (state.content?.type == "audio" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.chapterNotice) {
        if (state.chapterNotice != null) {
            delay(4_000)
            viewModel.clearChapterNotice()
        }
    }

    if (audioMode) {
        AudiobookPlayerScreen(state, origin, viewModel)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                },
        ) {
            when {
                state.chapterLoading -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = palette.text)
                        Spacer(Modifier.height(12.dp))
                        Text("正在加载章节…", color = palette.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.chapterError != null && state.content == null -> ReadingErrorBody(state, palette, viewModel)
                state.content?.type == "image" -> ComicChapterBody(state, origin, viewModel)
                else -> TextChapterBody(state, origin, palette, charsPerPage, viewModel)
            }

            if (state.chapterNotice != null) {
                Surface(
                    color = palette.chrome,
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp, start = 24.dp, end = 24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.chapterNotice,
                            color = palette.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                        IconButton(onClick = viewModel::clearChapterNotice, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = palette.secondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(visible = chromeVisible, modifier = Modifier.align(Alignment.TopCenter)) {
                ReaderTopBar(state, palette, viewModel)
            }
            AnimatedVisibility(visible = chromeVisible, modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderBottomBar(state, palette, viewModel)
            }
        }
    }

    if (state.tocOpen) {
        ReaderTocSheet(state, sheetPalette, viewModel)
    }
    if (state.settingsOpen) {
        ReaderSettingsSheet(state, palette, viewModel)
    }
    if (state.infoOpen) {
        ReaderInfoSheet(state, origin, sheetPalette, viewModel)
    }
    state.browserPage?.let { page ->
        ReaderBrowserPageDialog(page, origin, viewModel)
    }
    if (state.sourceLoginOpen) {
        ReaderSourceLoginSheet(state, viewModel)
    }
    if (state.originPickerOpen) {
        ReaderOriginPickerDialog(state, viewModel)
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    palette: ReaderPalette,
    viewModel: ReaderViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.chrome)
            .border(0.5.dp, palette.text.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::closeReading) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架", tint = palette.text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.book?.name.orEmpty(),
                color = palette.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.chapterTitle,
                color = palette.secondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = viewModel::toggleInfo) {
            Icon(Icons.Default.Info, contentDescription = "书籍信息", tint = palette.text)
        }
        IconButton(onClick = viewModel::toggleToc) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录", tint = palette.text)
        }
        IconButton(onClick = viewModel::toggleSettings) {
            Icon(Icons.Default.Settings, contentDescription = "阅读设置", tint = palette.text)
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    palette: ReaderPalette,
    viewModel: ReaderViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.chrome)
            .border(0.5.dp, palette.text.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { viewModel.stepChapter(-1) },
            enabled = state.hasPreviousChapter,
            colors = ButtonDefaults.textButtonColors(
                contentColor = palette.text,
                disabledContentColor = palette.secondary.copy(alpha = 0.4f),
            ),
        ) {
            Text("上一章")
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.chapterTitle,
                color = palette.text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.chapters.isNotEmpty()) {
                val position = (state.chapterIndex + 1).coerceAtMost(state.chapters.size)
                Text(
                    "$position / ${state.chapters.size}",
                    color = palette.secondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        TextButton(
            onClick = { viewModel.stepChapter(1) },
            enabled = state.hasNextChapter,
            colors = ButtonDefaults.textButtonColors(
                contentColor = palette.text,
                disabledContentColor = palette.secondary.copy(alpha = 0.4f),
            ),
        ) {
            Text("下一章")
        }
    }
}

@Composable
private fun TextChapterBody(
    state: ReaderUiState,
    origin: String,
    palette: ReaderPalette,
    charsPerPage: Int,
    viewModel: ReaderViewModel,
) {
    val listState = rememberLazyListState()
    var ready by remember(state.chapterIndex, state.paragraphs) { mutableStateOf(false) }
    var baselineIndex by remember(state.chapterIndex, state.paragraphs) { mutableIntStateOf(-1) }
    val commentsByLine = remember(state.content) {
        state.content?.comments.orEmpty().groupBy { it.line }
    }

    LaunchedEffect(state.chapterIndex, state.paragraphs) {
        if (state.paragraphs.isEmpty()) {
            ready = true
            return@LaunchedEffect
        }
        val target = ReaderPaging.paragraphForPage(
            page = state.restorePage,
            paragraphs = state.paragraphs,
            charsPerPage = charsPerPage,
        ).coerceIn(0, state.paragraphs.lastIndex)
        // 正文从加载态切进来时 LazyColumn 还没完成第一次测量；此时立即 scrollToItem
        // 会被首帧布局覆盖回顶部。等列表真正有了可见项再滚动。
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(target)
        // 恢复位置本身会被换算回略早的页码（段落跨页），如果把它当成用户滚动立即上报，
        // 每次重开都会把进度往前挪一点。记住这个基准位置，只有真正滚动后才保存。
        baselineIndex = listState.firstVisibleItemIndex
        ready = true
    }

    LaunchedEffect(listState, ready, state.chapterIndex, state.paragraphs) {
        if (!ready || state.paragraphs.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index == baselineIndex) return@collect
                viewModel.onPageChanged(
                    ReaderPaging.pageForParagraph(index, state.paragraphs, charsPerPage),
                )
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 88.dp, bottom = 88.dp),
    ) {
        items(state.paragraphs, key = { paragraph -> "${state.chapterIndex}:${paragraph.line}" }) { paragraph ->
            if (paragraph.isImage) {
                AsyncImage(
                    model = ReaderUrls.resolve(origin, paragraph.imageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            } else {
                Text(
                    paragraph.text,
                    color = palette.text,
                    fontSize = state.style.fontSize.sp,
                    lineHeight = (state.style.fontSize * state.style.lineHeight).sp,
                )
                Spacer(Modifier.height(state.style.paragraphSpacing.dp))
            }
            commentsByLine[paragraph.line].orEmpty().forEach { comment ->
                val label = comment.label.ifBlank {
                    if (comment.count > 0) "段评 ${comment.count}" else "段评"
                }
                TextButton(
                    onClick = { viewModel.openComment(comment) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "💬 $label",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicChapterBody(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val images = state.content?.images.orEmpty()
    val listState = rememberLazyListState()
    var ready by remember(state.chapterIndex, state.content) { mutableStateOf(false) }
    var baselineIndex by remember(state.chapterIndex, state.content) { mutableIntStateOf(-1) }

    LaunchedEffect(state.chapterIndex, state.content) {
        if (images.isEmpty()) {
            ready = true
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(state.restorePage.coerceIn(0, images.lastIndex))
        baselineIndex = listState.firstVisibleItemIndex
        ready = true
    }

    LaunchedEffect(listState, ready, state.chapterIndex, images) {
        if (!ready || images.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index == baselineIndex) return@collect
                viewModel.onPageChanged(index)
            }
    }

    if (images.isEmpty()) {
        CenterBox { PageMessage("本章没有可显示的图片") }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 88.dp, bottom = 72.dp),
    ) {
        items(images) { url ->
            AsyncImage(
                model = ReaderUrls.resolve(origin, url),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            )
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun ReadingErrorBody(
    state: ReaderUiState,
    palette: ReaderPalette,
    viewModel: ReaderViewModel,
) {
    CenterBox {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.tocRebuilding) {
                CircularProgressIndicator(color = palette.text)
                Spacer(Modifier.height(12.dp))
                Text("正在向书源获取目录…", color = palette.secondary)
            } else {
                Text(
                    state.chapterError.orEmpty(),
                    color = palette.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::retryChapter) { Text("重试") }
                    if (state.book?.isLocal == false) {
                        TextButton(onClick = viewModel::rebuildToc) { Text("刷新目录") }
                    }
                }
                TextButton(onClick = viewModel::closeReading) {
                    Text("返回书架", color = palette.secondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTocSheet(
    state: ReaderUiState,
    palette: ReaderPalette,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    LaunchedEffect(state.tocOpen) {
        if (state.chapters.isNotEmpty()) {
            listState.scrollToItem(state.chapterIndex.coerceIn(0, state.chapters.lastIndex))
        }
    }
    ModalBottomSheet(
        onDismissRequest = viewModel::closeToc,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = palette.background,
        contentColor = palette.text,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = palette.secondary) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("目录", style = MaterialTheme.typography.titleMedium, color = palette.text)
            Spacer(Modifier.weight(1f))
            Text(
                "${state.chapters.size} 章",
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
        ) {
            itemsIndexed(state.chapters) { index, chapter ->
                if (chapter.isVolume) {
                    Text(
                        chapter.title,
                        color = palette.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                } else {
                    val selected = index == state.chapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { viewModel.jumpToChapter(index) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = palette.secondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(40.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                chapter.title,
                                color = if (selected) MaterialTheme.colorScheme.primary else palette.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (index in state.cachedChapters) {
                                Text(
                                    "已缓存",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    state: ReaderUiState,
    palette: ReaderPalette,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val style = state.style
    ModalBottomSheet(
        onDismissRequest = viewModel::closeSettings,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = palette.background,
        contentColor = palette.text,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = palette.secondary) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium, color = palette.text)
            Spacer(Modifier.height(16.dp))

            Text("主题", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReaderThemePresets.forEach { preset ->
                    val selected = !style.night && style.themeId == preset.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(preset.background)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    viewModel.updateStyle(style.copy(themeId = preset.id, night = false))
                                },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(preset.label, style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("夜间模式", color = palette.text, modifier = Modifier.weight(1f))
                Switch(
                    checked = style.night,
                    onCheckedChange = { checked ->
                        viewModel.updateStyle(style.copy(night = checked))
                    },
                )
            }

            SettingSlider(
                label = "字号",
                valueText = "${style.fontSize.roundToInt()}",
                value = style.fontSize,
                valueRange = ReaderStyle.FONT_MIN..ReaderStyle.FONT_MAX,
                steps = 17,
                palette = palette,
                onChange = { value ->
                    viewModel.updateStyle(style.copy(fontSize = value.roundToInt().toFloat()))
                },
            )
            SettingSlider(
                label = "行距",
                valueText = String.format("%.1f", style.lineHeight),
                value = style.lineHeight,
                valueRange = ReaderStyle.LINE_MIN..ReaderStyle.LINE_MAX,
                steps = 11,
                palette = palette,
                onChange = { value ->
                    viewModel.updateStyle(
                        style.copy(lineHeight = (value * 10).roundToInt() / 10f),
                    )
                },
            )
            SettingSlider(
                label = "段距",
                valueText = "${style.paragraphSpacing}",
                value = style.paragraphSpacing.toFloat(),
                valueRange = ReaderStyle.SPACING_MIN.toFloat()..ReaderStyle.SPACING_MAX.toFloat(),
                steps = 31,
                palette = palette,
                onChange = { value ->
                    viewModel.updateStyle(style.copy(paragraphSpacing = value.roundToInt()))
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = viewModel::resetStyle) {
                    Text("恢复默认")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    palette: ReaderPalette,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = palette.text, modifier = Modifier.weight(1f))
            Text(valueText, color = palette.secondary, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderInfoSheet(
    state: ReaderUiState,
    origin: String,
    palette: ReaderPalette,
    viewModel: ReaderViewModel,
) {
    val book = state.book ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = viewModel::closeInfo,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = palette.background,
        contentColor = palette.text,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = palette.secondary) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Row {
                Box(
                    modifier = Modifier
                        .width(88.dp)
                        .height(118.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    ReaderCover(
                        url = book.coverUrl,
                        origin = origin,
                        title = book.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.name, style = MaterialTheme.typography.titleMedium, color = palette.text)
                    Spacer(Modifier.height(4.dp))
                    Text(book.author.ifBlank { "未知作者" }, color = palette.secondary, style = MaterialTheme.typography.bodySmall)
                    if (book.kind.isNotBlank()) {
                        Text(book.kind, color = palette.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                    if (book.originName.isNotBlank()) {
                        Text("书源：${book.originName}", color = palette.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "共 ${book.totalChapterNum} 章",
                        color = palette.secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (book.latestChapterTitle.isNotBlank()) {
                        Text(
                            "最新：${book.latestChapterTitle}",
                            color = palette.secondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (book.intro.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("简介", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    book.intro,
                    color = palette.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::openOriginPicker,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("换源")
                }
                OutlinedButton(
                    onClick = viewModel::openSourceLogin,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("书源登录")
                }
            }
            if (state.currentSource?.needsBrowser == true) {
                Text(
                    "该源依赖浏览器，部分操作需要打开书源页面。",
                    color = palette.secondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("离线缓存", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
            Text(
                "已缓存 ${state.cachedChapters.size} 章 · ${formatCacheSize(state.cacheBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary,
            )
            if (state.cacheBusy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    if (state.cacheProgress.isBlank()) "正在缓存…" else "正在缓存 ${state.cacheProgress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::cacheCurrentChapter,
                    enabled = !state.cacheBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("缓存本章")
                }
                OutlinedButton(
                    onClick = { viewModel.cacheFollowingChapters(20) },
                    enabled = !state.cacheBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("后续 20 章")
                }
            }
            TextButton(
                onClick = viewModel::clearBookCache,
                enabled = !state.cacheBusy && state.cachedChapters.isNotEmpty(),
            ) {
                Text("清除本书缓存", color = MaterialTheme.colorScheme.error)
            }
            state.cacheNotice?.let { notice ->
                Text(notice, color = palette.secondary, style = MaterialTheme.typography.labelSmall)
            }

            state.removeError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = { confirmRemove = true },
                enabled = !state.removeBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.removeBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("移出书架", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("移出书架？") },
            text = { Text("《${book.name}》会从书架移除，服务器上的书源和阅读进度不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeCurrentBook()
                }) {
                    Text("移出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("取消") }
            },
        )
    }
}
