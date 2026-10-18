package app.mebox.android.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mebox.android.core.network.ServerAddress
import app.mebox.android.core.network.ServerInfo
import app.mebox.android.core.player.PlayerEngineKind
import app.mebox.android.core.player.SubtitlePriority
import app.mebox.android.core.ui.MeBoxButton
import app.mebox.android.core.ui.MeBoxCard
import app.mebox.android.core.ui.MeBoxColor
import app.mebox.android.core.ui.MeBoxDimens
import app.mebox.android.core.ui.MeBoxDivider
import app.mebox.android.core.ui.MeBoxEmptyState
import app.mebox.android.core.ui.MeBoxOutlinedButton
import app.mebox.android.core.ui.MeBoxTextField
import app.mebox.android.feature.diagnostics.LogShareDialog
import app.mebox.android.feature.diagnostics.rememberLogExport
import app.mebox.android.feature.media.AccountHost

@Composable
fun MeBoxRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val current = state) {
            AppUiState.Loading -> LoadingScreen()
            is AppUiState.Connection -> ConnectionScreen(current, viewModel)
            is AppUiState.Login -> LoginScreen(current, viewModel)
            is AppUiState.Account -> AccountHost(current, viewModel)
        }
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(
                Brush.linearGradient(
                    listOf(MeBoxColor.Accent, Color(0xFF58B5FF)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "M",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark()
        Spacer(Modifier.height(18.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "正在恢复会话…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionScreen(state: AppUiState.Connection, viewModel: MainViewModel) {
    val exportLogs = rememberLogExport()
    var showLogShare by remember { mutableStateOf(false) }
    val logShareUrl by viewModel.logShare.url.collectAsStateWithLifecycle()
    LaunchedEffect(showLogShare) {
        if (showLogShare) viewModel.logShare.start() else viewModel.logShare.stop()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.logShare.stop() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("MeBox", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "连接你的私人媒体库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        MeBoxCard {
            Column(Modifier.padding(18.dp)) {
                Text("连接服务器", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                MeBoxTextField(
                    value = state.addressText,
                    onValueChange = viewModel::onAddressChange,
                    label = "服务器地址",
                    placeholder = "https://example.com:8443",
                    enabled = !state.testing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "无协议时默认 HTTPS；首版只支持根路径部署（不支持 https://host/mebox 这类子路径）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.notice?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))
                MeBoxButton(
                    onClick = viewModel::connect,
                    enabled = !state.testing,
                    loading = state.testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("测试连接")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = exportLogs) {
                Text(
                    "导出日志",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showLogShare = true }) {
                Text(
                    "局域网下载日志",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "账号密码只在本次登录中使用，不会写入手机存储。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val pending = state.pendingCleartext
    if (pending != null) {
        CleartextRiskDialog(
            address = pending,
            onConfirm = viewModel::confirmCleartext,
            onDismiss = viewModel::dismissCleartext,
        )
    }

    if (showLogShare) {
        LogShareDialog(
            url = logShareUrl,
            onRefresh = { viewModel.logShare.restart() },
            onDismiss = { showLogShare = false },
        )
    }
}

@Composable
private fun CleartextRiskDialog(
    address: ServerAddress,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        shape = RoundedCornerShape(16.dp),
        onDismissRequest = onDismiss,
        title = { Text("使用 HTTP 连接？", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "你输入的地址使用明文 HTTP（${address.origin}）。" +
                    "同一网络中的其他人可能看到你的账号、密码和播放内容。" +
                    "只有在信任该局域网时才继续。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("我了解风险，继续") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LoginScreen(state: AppUiState.Login, viewModel: MainViewModel) {
    // 密码只保留在内存中，不写入 ViewModel 或 saved state。
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    state.serverInfo.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${state.address.origin} · ${state.serverInfo.serverVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        MeBoxCard {
            Column(Modifier.padding(18.dp)) {
                Text("登录账号", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                MeBoxTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = "用户名",
                    enabled = !state.submitting,
                )
                Spacer(Modifier.height(12.dp))
                MeBoxTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    placeholder = "输入登录密码",
                    enabled = !state.submitting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(18.dp))
                MeBoxButton(
                    onClick = { viewModel.login(password) },
                    enabled = !state.submitting,
                    loading = state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录")
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = viewModel::backToConnection,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "更换服务器",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "密码只用于本次登录，不会保存在手机上。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ProfileContent(
    state: AppUiState.Account,
    viewModel: MainViewModel,
    onOpenFavorites: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
) {
    val currentEngine by viewModel.playerEngine.collectAsStateWithLifecycle()
    val subtitlePreference by viewModel.subtitlePreference.collectAsStateWithLifecycle()
    val exportLogs = rememberLogExport()
    var showLogShare by remember { mutableStateOf(false) }
    val logShareUrl by viewModel.logShare.url.collectAsStateWithLifecycle()
    LaunchedEffect(showLogShare) {
        if (showLogShare) viewModel.logShare.start() else viewModel.logShare.stop()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.logShare.stop() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MeBoxDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        MeBoxCard {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (state.user.nickname.ifBlank { state.user.username })
                            .take(1)
                            .ifBlank { "M" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.user.nickname.ifBlank { state.user.username },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.user.username,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        ProfileBadge(state.user.role.ifBlank { "用户" })
                        Spacer(Modifier.width(6.dp))
                        state.user.tier.takeIf { it.isNotBlank() }?.let { ProfileBadge(it) }
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "账号与服务器",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        MeBoxCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                InfoRow("服务器", state.serverInfo.name)
                MeBoxDivider()
                InfoRow("版本", state.serverInfo.serverVersion)
                MeBoxDivider()
                InfoRow("地址", state.address.origin)
                MeBoxDivider()
                InfoRow("设备", state.deviceLabel)
            }
        }

        if (onOpenFavorites != null || onOpenHistory != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                "我的影视",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            MeBoxCard {
                if (onOpenFavorites != null) {
                    ProfileMenuRow(
                        icon = Icons.Default.Favorite,
                        title = "我的收藏",
                        onClick = onOpenFavorites,
                    )
                }
                if (onOpenFavorites != null && onOpenHistory != null) {
                    MeBoxDivider(modifier = Modifier.padding(start = 52.dp))
                }
                if (onOpenHistory != null) {
                    ProfileMenuRow(
                        icon = Icons.Default.DateRange,
                        title = "观看历史",
                        onClick = onOpenHistory,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "播放设置",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        MeBoxCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PlayerEngineKind.entries.forEachIndexed { index, engine ->
                    if (index > 0) MeBoxDivider()
                    PlayerEngineRow(
                        engine = engine,
                        selected = engine == currentEngine,
                        onClick = { viewModel.selectPlayerEngine(engine) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "字幕",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        MeBoxCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动加载字幕", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (subtitlePreference.autoLoad) {
                                "开播时按下面的语言偏好自动选中一条字幕"
                            } else {
                                "起播不选字幕，需要时在播放页手动打开"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = subtitlePreference.autoLoad,
                        onCheckedChange = viewModel::setSubtitlesAutoLoad,
                    )
                }
                if (subtitlePreference.autoLoad) {
                    MeBoxDivider()
                    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        Text(
                            "优先语言",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                        SubtitlePriority.entries.forEach { priority ->
                            SubtitlePriorityRow(
                                priority = priority,
                                selected = priority == subtitlePreference.priority,
                                onClick = { viewModel.setSubtitlePriority(priority) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "诊断与日志",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        MeBoxCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ProfileMenuRow(
                    icon = Icons.Default.Description,
                    title = "导出日志到文件夹",
                    subtitle = "生成 ZIP，保存到你选择的文件夹",
                    onClick = exportLogs,
                )
                MeBoxDivider(modifier = Modifier.padding(start = 52.dp))
                ProfileMenuRow(
                    icon = Icons.Default.Wifi,
                    title = "局域网下载日志",
                    subtitle = "同一网络下用手机或电脑扫码下载",
                    onClick = { showLogShare = true },
                )
            }
        }

        state.notice?.let { notice ->
            Spacer(Modifier.height(16.dp))
            MeBoxCard {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "更多功能会在后续版本继续完善。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        MeBoxOutlinedButton(
            onClick = viewModel::logout,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showLogShare) {
        LogShareDialog(
            url = logShareUrl,
            onRefresh = { viewModel.logShare.restart() },
            onDismiss = { showLogShare = false },
        )
    }
}

@Composable
private fun ProfileBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PlayerEngineRow(
    engine: PlayerEngineKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.padding(end = 4.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 6.dp),
        ) {
            Text(
                engine.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(3.dp))
            Text(
                engine.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SubtitlePriorityRow(
    priority: SubtitlePriority,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.padding(end = 4.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 6.dp),
        ) {
            Text(
                priority.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(3.dp))
            Text(
                priority.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Suppress("unused")
private fun previewInfo(): ServerInfo = ServerInfo(
    name = "MeBox",
    serverId = "preview",
    serverVersion = "MeBox-v1.0.0",
)
