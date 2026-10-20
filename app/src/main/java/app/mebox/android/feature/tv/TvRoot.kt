package app.mebox.android.feature.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mebox.android.core.network.ServerAddress
import app.mebox.android.core.ui.MeBoxTheme
import app.mebox.android.feature.AppUiState
import app.mebox.android.feature.MainViewModel

/**
 * 电视端入口。
 *
 * 与手机端共享同一个 MainViewModel 和登录状态机，只替换呈现方式：
 * - 连接/登录页左侧显示说明与可选手动输入，右侧固定显示手机扫码面板；
 * - 登录后进入以遥控器为第一输入方式的 10-foot 界面。
 */
@Composable
fun TvRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phoneUrl by viewModel.phoneInput.url.collectAsStateWithLifecycle()
    val canOpenOffline by viewModel.canOpenOffline.collectAsStateWithLifecycle()
    val searchOnly = state is AppUiState.Account

    var showLogShare by remember { mutableStateOf(false) }
    val logShareUrl by viewModel.logShare.url.collectAsStateWithLifecycle()
    LaunchedEffect(showLogShare) {
        if (showLogShare) viewModel.logShare.start() else viewModel.logShare.stop()
    }

    LaunchedEffect(searchOnly) {
        viewModel.phoneInput.setSearchOnly(searchOnly)
    }

    DisposableEffect(Unit) {
        viewModel.phoneInput.start()
        onDispose { viewModel.phoneInput.stop() }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.logShare.stop() }
    }

    MeBoxTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val current = state) {
                AppUiState.Loading -> {
                    Box(Modifier.fillMaxSize()) {
                        TvSplash()
                        if (canOpenOffline) TvActionButton(
                            text = "进入离线书架", onClick = viewModel::openOfflineReader,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                        )
                    }
                }
                is AppUiState.Offline -> app.mebox.android.feature.reader.OfflineReaderHost(current, viewModel, television = true)
                is AppUiState.Connection -> TvConnectionScreen(
                    state = current,
                    phoneUrl = phoneUrl,
                    onRefreshQr = { viewModel.phoneInput.restart() },
                    onOpenLogShare = { showLogShare = true },
                    viewModel = viewModel,
                )

                is AppUiState.Login -> TvLoginScreen(
                    state = current,
                    phoneUrl = phoneUrl,
                    onRefreshQr = { viewModel.phoneInput.restart() },
                    onOpenLogShare = { showLogShare = true },
                    viewModel = viewModel,
                )

                is AppUiState.Account -> TvAccountHost(
                    account = current,
                    mainViewModel = viewModel,
                    phoneUrl = phoneUrl,
                    onRefreshQr = { viewModel.phoneInput.restart() },
                    onOpenLogShare = { showLogShare = true },
                )
            }
        }

        if (showLogShare) {
            TvLogShareDialog(
                url = logShareUrl,
                onRefresh = { viewModel.logShare.restart() },
                onDismiss = { showLogShare = false },
            )
        }
    }
}

@Composable
private fun TvSplash() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvBrandMark(size = 76.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            "MeBox",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "电视版 · 正在恢复会话",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun TvBrandMark(size: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(
                Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF58B5FF))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "M",
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TvConnectionScreen(
    state: AppUiState.Connection,
    phoneUrl: String?,
    onRefreshQr: () -> Unit,
    onOpenLogShare: () -> Unit,
    viewModel: MainViewModel,
) {
    val connectFocus = remember { FocusRequester() }
    val canOpenOffline by viewModel.canOpenOffline.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { connectFocus.requestFocus() }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 620.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvBrandMark(size = 58.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "MeBox",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "连接你的私人媒体库",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "第一次使用",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "推荐用手机扫码填写服务器地址和账号，电视端不需要用遥控器输入长文本。" +
                    "也可以外接键盘，在下面手动填写。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
            )

            Spacer(Modifier.height(20.dp))
            TvTextField(
                value = state.addressText,
                onValueChange = viewModel::onAddressChange,
                label = "服务器地址",
                placeholder = "https://example.com:8443",
                enabled = !state.testing,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "无协议时默认 HTTPS；只支持根路径部署，例如 https://example.com。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }
            state.notice?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = if (state.testing) "正在连接…" else "测试连接",
                    onClick = viewModel::connect,
                    enabled = !state.testing,
                    focusRequester = connectFocus,
                    leading = {
                        Icon(
                            Icons.Default.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
                TvActionButton(
                    text = "清除地址",
                    onClick = { viewModel.onAddressChange("") },
                    primary = false,
                    enabled = state.addressText.isNotBlank() && !state.testing,
                )
            }
            if (canOpenOffline) {
                Spacer(Modifier.height(12.dp))
                TvActionButton(text = "进入离线书架", onClick = viewModel::openOfflineReader, primary = false)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "服务器地址和登录状态会保存在电视上，下次开机自动恢复；只有密码不会保存。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "手机扫码输入",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "手机和电视连接同一个 Wi-Fi",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                TvPhoneInputPanel(
                    url = phoneUrl,
                    onRefresh = onRefreshQr,
                )
            }
            Spacer(Modifier.height(12.dp))
            TvActionButton(
                text = "扫码下载日志",
                onClick = onOpenLogShare,
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                },
            )
        }
    }

    val pending = state.pendingCleartext
    if (pending != null) {
        TvConfirmDialog(
            title = "使用 HTTP 连接？",
            message = "你输入的地址使用明文 HTTP（${pending.origin}）。" +
                "同一网络中的其他人可能看到你的账号、密码和播放内容。" +
                "只有在信任该局域网时才继续。",
            confirmLabel = "我了解风险，继续",
            onConfirm = viewModel::confirmCleartext,
            dismissLabel = "取消",
            onDismiss = viewModel::dismissCleartext,
        )
    }
}

@Composable
private fun TvLoginScreen(
    state: AppUiState.Login,
    phoneUrl: String?,
    onRefreshQr: () -> Unit,
    onOpenLogShare: () -> Unit,
    viewModel: MainViewModel,
) {
    val loginFocus = remember { FocusRequester() }
    val password = remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { loginFocus.requestFocus() }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 620.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvBrandMark(size = 58.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        state.serverInfo.name.ifBlank { "MeBox" },
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${state.address.origin} · ${state.serverInfo.serverVersion}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "登录账号",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "用手机扫码填写用户名和密码，电视会直接登录；也可以在下面手动输入。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
            )

            Spacer(Modifier.height(20.dp))
            TvTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = "用户名",
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                value = password.value,
                onValueChange = { password.value = it },
                label = "密码",
                placeholder = "输入登录密码",
                isPassword = true,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = if (state.submitting) "正在登录…" else "登录",
                    onClick = { viewModel.login(password.value) },
                    enabled = !state.submitting,
                    focusRequester = loginFocus,
                )
                TvActionButton(
                    text = "更换服务器",
                    onClick = viewModel::backToConnection,
                    primary = false,
                    enabled = !state.submitting,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "登录成功后会保存登录状态，下次开机自动恢复；密码本身不会保存。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Start,
            )
        }

        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "手机扫码登录",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "扫码后在网页填写账号密码",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                TvPhoneInputPanel(
                    url = phoneUrl,
                    onRefresh = onRefreshQr,
                )
            }
            Spacer(Modifier.height(12.dp))
            TvActionButton(
                text = "扫码下载日志",
                onClick = onOpenLogShare,
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                },
            )
        }
    }
}

/** 连接页共用的服务器信息展示（登录页也用它，避免布局漂移）。 */
@Composable
internal fun TvServerLine(address: ServerAddress, serverName: String) {
    Text(
        "$serverName · ${address.origin}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
