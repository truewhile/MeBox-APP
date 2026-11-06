package app.mebox.android.feature.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mebox.android.core.reader.ReaderBookStorage
import app.mebox.android.core.reader.ReaderCacheKind
import app.mebox.android.feature.MainViewModel
import java.util.Locale

/** 手机和电视共用的阅读存储管理；扫描和清理的生命周期均由 ViewModel 管理。 */
@Composable
fun ReaderStorageDialog(
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val state by mainViewModel.readerStorage.collectAsStateWithLifecycle()
    LaunchedEffect(mainViewModel) { mainViewModel.refreshReaderStorage() }
    ReaderStorageContent(state, mainViewModel::refreshReaderStorage, mainViewModel::clearReaderBookStorage, onDismiss)
}

/** 独立状态入口用于界面测试；不依赖登录、不直接访问存储。 */
@Composable
internal fun ReaderStorageContent(
    state: ReaderStorageUiState,
    onRefresh: () -> Unit,
    onClear: (String, ReaderCacheKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember { mutableStateOf<StorageSelection?>(null) }
    val closeFocus = remember { FocusRequester() }
    val enabled = !state.loading && !state.busy
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 840.dp)
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("缓存与下载") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "阅读存储 · ${state.books.size} 本书",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text("总占用：${storageBytes(state.books.sumOf { it.totalBytes })}")
                        Text(
                            "自动缓存：${storageBytes(state.books.sumOf { it.automaticBytes })}" +
                                " · ${state.books.sumOf { it.automaticChapters }} 项",
                        )
                        Text(
                            "手动下载：${storageBytes(state.books.sumOf { it.downloadBytes })}" +
                                " · ${state.books.sumOf { it.downloadChapters }} 项",
                        )
                        Text(
                            "自动缓存上限为 512 MiB，超出后会自动淘汰旧缓存。" +
                                "手动下载受保护，不计入自动缓存上限，也不会被自动淘汰；" +
                                "只有主动选择“删除下载”或“清空全部”才会删除。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "自动缓存保留每本书前 5 章、当前章、后 5 章；旧版本未分类内容按历史下载保护。" +
                                "统计项含未完成资源；同章可能同时有预加载与软解音源。清理正在收听的书会先停止播放。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.loading || state.busy) {
                    item(key = "progress") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if (state.busy) "正在清理，请稍候…" else "正在统计阅读存储…")
                        }
                    }
                }
                state.error?.let { message ->
                    item(key = "error") {
                        Column {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(
                                onClick = onRefresh,
                                enabled = enabled,
                            ) { Text("重试") }
                        }
                    }
                }
                state.notice?.let { message ->
                    item(key = "notice") {
                        Text(message, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (state.books.isEmpty() && !state.loading && !state.busy && state.error == null) {
                    item(key = "empty") {
                        Text("暂无阅读缓存或下载。阅读时的自动缓存和手动下载会显示在这里。")
                    }
                }
                items(state.books, key = { "book-${it.bookId}" }) { book ->
                    ReaderStorageBook(
                        book = book,
                        enabled = enabled,
                        onSelect = { kind -> selection = StorageSelection(book, kind) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRefresh,
                enabled = enabled,
            ) { Text("刷新") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeFocus),
            ) { Text("关闭") }
        },
    )

    selection?.let { pending ->
        ReaderStorageConfirmation(
            selection = pending,
            enabled = enabled && state.books.any { it.bookId == pending.book.bookId },
            onDismiss = { selection = null },
            onConfirm = {
                selection = null
                onClear(pending.book.bookId, pending.kind)
            },
        )
    }
}

@Composable
private fun ReaderStorageBook(
    book: ReaderBookStorage,
    enabled: Boolean,
    onSelect: (ReaderCacheKind) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                book.name.ifBlank { book.bookId },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("合计：${storageBytes(book.totalBytes)}")
            Text("自动缓存：${storageBytes(book.automaticBytes)} · ${book.automaticChapters} 项")
            Text("手动下载：${storageBytes(book.downloadBytes)} · ${book.downloadChapters} 项")
            ReaderCacheKind.entries.forEach { kind ->
                val hasContent = when (kind) {
                    ReaderCacheKind.AUTOMATIC -> book.automaticBytes > 0 || book.automaticChapters > 0
                    ReaderCacheKind.DOWNLOAD -> book.downloadBytes > 0 || book.downloadChapters > 0
                    ReaderCacheKind.ALL -> book.totalBytes > 0 ||
                        book.automaticChapters > 0 || book.downloadChapters > 0
                }
                OutlinedButton(
                    onClick = { onSelect(kind) },
                    enabled = enabled && hasContent,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(kind.actionLabel()) }
            }
        }
    }
}

@Composable
private fun ReaderStorageConfirmation(
    selection: StorageSelection,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { cancelFocus.requestFocus() }
    }
    val book = selection.book
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认${selection.kind.actionLabel()}？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("书籍：${book.name.ifBlank { book.bookId }}")
                Text("书籍 ID：${book.bookId}", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (selection.kind) {
                        ReaderCacheKind.AUTOMATIC ->
                            "仅清除自动缓存：${storageBytes(book.automaticBytes)}，${book.automaticChapters} 项。手动下载保留。"
                        ReaderCacheKind.DOWNLOAD ->
                            "仅删除手动下载：${storageBytes(book.downloadBytes)}，${book.downloadChapters} 项。自动缓存保留。"
                        ReaderCacheKind.ALL ->
                            "清除该书全部自动缓存和手动下载：${storageBytes(book.totalBytes)}。\n" +
                                "自动缓存：${storageBytes(book.automaticBytes)}，${book.automaticChapters} 项；\n" +
                                "手动下载：${storageBytes(book.downloadBytes)}，${book.downloadChapters} 项。"
                    },
                )
                Text("如果正在播放（听书）这本书，将先停止播放，以便安全清理。删除后无法撤销，离线时将无法使用已删除的章节。")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    "确认${selection.kind.actionLabel()}",
                    color = if (enabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                Text("取消")
            }
        },
    )
}

private data class StorageSelection(val book: ReaderBookStorage, val kind: ReaderCacheKind)

private fun ReaderCacheKind.actionLabel(): String = when (this) {
    ReaderCacheKind.AUTOMATIC -> "清除自动缓存"
    ReaderCacheKind.DOWNLOAD -> "删除下载"
    ReaderCacheKind.ALL -> "清空全部"
}

private fun storageBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    val unit = when {
        safeBytes >= 1024L * 1024 * 1024 -> "GiB" to (1024L * 1024 * 1024)
        safeBytes >= 1024L * 1024 -> "MiB" to (1024L * 1024)
        safeBytes >= 1024L -> "KiB" to 1024L
        else -> return "$safeBytes B"
    }
    return String.format(Locale.ROOT, "%.1f %s", safeBytes.toDouble() / unit.second, unit.first)
}
