package app.mebox.android.feature.reader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mebox.android.core.network.ReaderSourceDto
import app.mebox.android.core.ui.MeBoxButton
import app.mebox.android.core.ui.MeBoxCard
import app.mebox.android.core.ui.MeBoxDivider
import app.mebox.android.core.ui.MeBoxIconButton
import app.mebox.android.core.ui.MeBoxOutlinedButton
import app.mebox.android.core.ui.MeBoxTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 书源管理页：对齐网页端 `/reader/sources`。
 *
 * 服务端仍是书源规则的执行者，这里只做管理动作：导入（粘贴 / 文件 / 链接）、
 * 启停、删除、快速调试与书源登录。导入的 JSON 原样交给服务端解析，
 * 安卓端不复制 legado 规则。
 */
private const val SOURCE_IMPORT_MAX_BYTES = 32L * 1024 * 1024

/** 书源类型文案（bookSourceType，与后端 model.ReaderBookSource.Type 对齐）。 */
private val SOURCE_TYPE_LABELS = mapOf(
    0 to "文本",
    1 to "音频",
    2 to "图片",
    3 to "文件",
    4 to "视频",
)

@Composable
internal fun ReaderSourcesContent(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ReaderSourceDto?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { readSourceFile(context, uri) }
            result
                .onSuccess { (name, size, text) -> viewModel.onSourceImportFileLoaded(name, size, text) }
                .onFailure { viewModel.onSourceImportFileError(it.message ?: "读取文件失败") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MeBoxTopBar(
            title = "书源管理",
            subtitle = "${state.sources.count { it.enabled }} / ${state.sources.size} 个书源已启用",
            onBack = viewModel::closeSources,
            actions = {
                MeBoxIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "导入书源",
                    onClick = viewModel::toggleSourceImport,
                )
                Spacer(Modifier.width(8.dp))
                if (state.sourcesLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    MeBoxIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "刷新书源",
                        onClick = viewModel::refreshSources,
                    )
                }
            },
        )

        state.sourcesNotice?.let {
            SourceBanner(text = it, error = false, onClose = viewModel::clearSourcesNotice)
        }
        state.sourcesError?.let {
            SourceBanner(text = it, error = true, onClose = viewModel::clearSourcesNotice)
        }

        if (state.sourceImportOpen) {
            SourceImportPanel(
                state = state,
                viewModel = viewModel,
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
            )
        }

        when {
            state.sourcesLoading && state.sources.isEmpty() -> CenterBox {
                androidx.compose.material3.CircularProgressIndicator()
            }
            state.sources.isEmpty() && state.sourcesError != null -> CenterBox {
                PageMessage(state.sourcesError, action = "重试", onAction = viewModel::refreshSources)
            }
            state.sources.isEmpty() -> CenterBox {
                PageMessage(
                    "还没有书源。导入 legado 书源 JSON / 链接后，才能搜索和阅读网络书籍。",
                    action = "导入书源",
                    onAction = viewModel::toggleSourceImport,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.sources, key = { it.id }) { source ->
                    SourceRow(
                        source = source,
                        state = state,
                        viewModel = viewModel,
                        onRequestDelete = { pendingDelete = source },
                    )
                }
            }
        }
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除书源") },
            text = {
                Text(
                    "确定删除「${source.name}」？删除后该书源的规则与登录状态都会移除；" +
                        "已加入书架的书不会删除，但无法再从该书源更新目录和正文。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSource(source)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (state.sourceLoginOpen) {
        ReaderSourceLoginSheet(state, viewModel)
    }
    state.browserPage?.let { page ->
        ReaderBrowserPageDialog(page, origin, viewModel)
    }
}

@Composable
private fun SourceBanner(text: String, error: Boolean, onClose: () -> Unit) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 9.dp, bottom = 9.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭提示", modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun SourceImportPanel(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onPickFile: () -> Unit,
) {
    val hasPayload = state.sourceImportPayload != null || state.sourceImportText.isNotBlank()
    MeBoxCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("导入书源", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "粘贴书源 JSON / Base64，或填写一个书源链接（https://…）；也可以选择本地 .json / .txt 文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.sourceImportText,
                onValueChange = viewModel::onSourceImportTextChange,
                minLines = 3,
                maxLines = 7,
                placeholder = {
                    Text(
                        "支持书源数组批量导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            state.sourceImportFileName?.let { name ->
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$name（${formatBytes(state.sourceImportFileSize)}）",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::clearSourceImportFile, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "移除文件", modifier = Modifier.size(14.dp))
                    }
                }
            }
            state.sourceImportError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeBoxOutlinedButton(onClick = onPickFile, enabled = !state.sourceImporting) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择文件")
                }
                Spacer(Modifier.weight(1f))
                MeBoxButton(
                    onClick = viewModel::importSources,
                    enabled = hasPayload && !state.sourceImporting,
                    loading = state.sourceImporting,
                ) {
                    Text("开始导入")
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: ReaderSourceDto,
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onRequestDelete: () -> Unit,
) {
    val pending = source.id in state.sourcePendingIds
    val loggedIn = state.sourceLoggedIn[source.id] == true
    MeBoxCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            source.name.ifBlank { "未命名书源" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        SourceBadge(SOURCE_TYPE_LABELS[source.type] ?: "文本")
                        if (source.needsBrowser) {
                            Spacer(Modifier.width(4.dp))
                            SourceBadge("需浏览器", error = true)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOf(sourceHost(source.sourceUrl), source.group)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { viewModel.setSourceEnabled(source, it) },
                    enabled = !pending,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pending) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (source.hasLogin) {
                    SourceAction(
                        label = if (loggedIn) "已登录" else "登录",
                        icon = Icons.Default.Key,
                        tint = if (loggedIn) MaterialTheme.colorScheme.primary else null,
                        enabled = !pending,
                        onClick = { viewModel.openSourceLoginFor(source) },
                    )
                }
                SourceAction(
                    label = "调试",
                    icon = Icons.Default.BugReport,
                    enabled = !pending,
                    onClick = { viewModel.toggleSourceDebug(source.id) },
                )
                SourceAction(
                    label = "删除",
                    icon = Icons.Default.Delete,
                    tint = MaterialTheme.colorScheme.error,
                    enabled = !pending,
                    onClick = onRequestDelete,
                )
            }
            if (state.sourceDebugId == source.id) {
                SourceDebugPanel(source = source, state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SourceAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceBadge(text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

@Composable
private fun SourceDebugPanel(
    source: ReaderSourceDto,
    state: ReaderUiState,
    viewModel: ReaderViewModel,
) {
    Column {
        MeBoxDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.sourceDebugKey,
                onValueChange = viewModel::onSourceDebugKeyChange,
                singleLine = true,
                placeholder = { Text("搜索关键词（如：斗破苍穹）", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            MeBoxOutlinedButton(
                onClick = { viewModel.runSourceDebug(source) },
                enabled = state.sourceDebugKey.isNotBlank() && !state.sourceDebugRunning,
            ) {
                if (state.sourceDebugRunning) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("运行")
                }
            }
        }
        state.sourceDebugLogs?.let { logs ->
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (logs.isEmpty()) "（无日志）" else logs.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private fun sourceHost(url: String): String {
    if (url.isBlank()) return ""
    return runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format("%.1fMB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "${bytes shr 10}KB"
    else -> "${bytes}B"
}

/** 读取用户选中的书源文件：限制大小，去掉 UTF-8 BOM（Windows 导出的文件常见）。 */
private fun readSourceFile(context: Context, uri: Uri): Result<Triple<String, Long, String>> =
    runCatching {
        val name = queryDisplayName(context, uri)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > SOURCE_IMPORT_MAX_BYTES) {
                    throw IOException("文件过大（超过 32MB）")
                }
                out.write(buffer, 0, read)
            }
            val text = String(out.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
            if (text.isBlank()) throw IOException("文件内容为空")
            Triple(name, total, text)
        }
    }

private fun queryDisplayName(context: Context, uri: Uri): String {
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index) ?: "书源文件"
                    }
                }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "书源文件"
}
