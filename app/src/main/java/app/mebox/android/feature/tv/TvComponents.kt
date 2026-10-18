package app.mebox.android.feature.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mebox.android.core.input.QrCodes
import app.mebox.android.core.media.LibraryTagTab
import app.mebox.android.core.ui.MeBoxTheme
import app.mebox.android.feature.media.MediaArtwork

/**
 * 电视组件统一使用「焦点可见」交互：
 * - 遥控器方向键移动焦点，OK/Enter 触发；
 * - 获得焦点时轻微放大并绘制强调色描边；
 * - 同时保留触摸点击，方便在平板/模拟器上调试。
 */
@Composable
fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.04f,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "tv-focus-scale",
    )
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                if (code in TV_CONFIRM_KEYS) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { onClick() }
                }
            }
            .semantics {
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            }
            .then(
                if (focused) {
                    Modifier
                        .border(2.5.dp, accent, shape)
                        .background(accent.copy(alpha = 0.10f), shape)
                } else {
                    Modifier
                },
            )
            .clip(shape),
        content = { content(focused) },
    )
}

/** 电视端主按钮：焦点时填充强调色。 */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    TvFocusableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = if (focusRequester != null) {
            modifier.focusRequester(focusRequester)
        } else {
            modifier
        },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.05f,
    ) { focused ->
        Row(
            modifier = Modifier
                .background(
                    when {
                        !enabled -> MaterialTheme.colorScheme.surfaceVariant
                        primary && focused -> MaterialTheme.colorScheme.primary
                        primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.86f)
                        focused -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    },
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    primary -> MaterialTheme.colorScheme.onPrimary
                    focused -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * 电视端媒体库标签栏：与手机端共用 `MediaUiState` 里的标签筛选，
 * 选中项用主色按钮表示，方向键左右切换。
 */
@Composable
fun TvLibraryTagRow(
    tabs: List<LibraryTagTab>,
    selectedTag: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.size <= 1) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tabs, key = { it.name ?: "__all__" }) { tab ->
            TvActionButton(
                text = if (tab.count > 0) "${tab.label} ${tab.count}" else tab.label,
                onClick = { onSelect(tab.name) },
                primary = tab.name == selectedTag,
            )
        }
    }
}

/** 圆形/胶囊图标按钮，用于返回、刷新等。 */
@Composable
fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
        shape = RoundedCornerShape(50),
        focusedScale = 1.08f,
    ) { focused ->
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (focused) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 海报卡：2:3 封面 + 两行标题。 */
@Composable
fun TvPosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    badge: String? = null,
    width: androidx.compose.ui.unit.Dp = 176.dp,
    focusRequester: FocusRequester? = null,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            modifier.width(width).focusRequester(focusRequester)
        } else {
            modifier.width(width)
        },
        shape = RoundedCornerShape(14.dp),
    ) { focused ->
        Column(Modifier.padding(3.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MediaArtwork(
                    url = posterUrl,
                    title = title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
                badge?.takeIf { it.isNotBlank() }?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            it,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (focused) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0x33000000)),
                                ),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 宽幅卡：16:9 横图，用于继续观看和搜索结果。 */
@Composable
fun TvWideCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    badge: String? = null,
    width: androidx.compose.ui.unit.Dp = 300.dp,
    focusRequester: FocusRequester? = null,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            modifier.width(width).focusRequester(focusRequester)
        } else {
            modifier.width(width)
        },
        shape = RoundedCornerShape(14.dp),
    ) { focused ->
        Column(Modifier.padding(3.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MediaArtwork(
                    url = imageUrl,
                    title = title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xB3000000)),
                            ),
                        )
                        .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 9.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                badge?.takeIf { it.isNotBlank() }?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun TvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TvActionButton(
                text = actionLabel,
                onClick = onAction,
                primary = false,
            )
        }
    }
}

@Composable
fun TvPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 20.dp else 32.dp, end = 32.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TvIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

@Composable
fun TvLoading(
    message: String = "正在加载…",
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TvMessage(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TvActionButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * 电视端的「手机扫码输入」面板：二维码 + 地址 + 刷新。
 * 手机端没有启动这个服务，因此这个面板只会在电视界面出现。
 */
@Composable
fun TvPhoneInputPanel(
    url: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    qrSize: androidx.compose.ui.unit.Dp? = null,
    refreshFocusRequester: FocusRequester? = null,
) {
    val resolvedQrSize = qrSize ?: if (compact) 168.dp else 208.dp
    val bitmap = remember(url, resolvedQrSize) {
        url?.let {
            QrCodes.bitmap(it, sizePx = if (resolvedQrSize.value >= 200f) 720 else 512)
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(resolvedQrSize)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "手机扫码输入二维码",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "未连接局域网",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            url ?: "请先连接 Wi-Fi 或有线网络",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Text(
                "手机扫码后在网页填写，电视会立即收到",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        TvActionButton(
            text = "刷新二维码",
            onClick = onRefresh,
            primary = false,
            focusRequester = refreshFocusRequester,
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

/** 电视端弹窗：用于首页搜索、阅读搜索等场景。 */
@Composable
fun TvPhoneInputDialog(
    title: String,
    url: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val refreshFocus = remember { FocusRequester() }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(220)
        runCatching { refreshFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MeBoxTheme(darkTheme = true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(24.dp),
                    )
                    .padding(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "用手机扫码，填写后自动推送到电视。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    TvPhoneInputPanel(
                        url = url,
                        onRefresh = onRefresh,
                        compact = true,
                        refreshFocusRequester = refreshFocus,
                    )
                    Spacer(Modifier.height(18.dp))
                    TvActionButton(text = "关闭", onClick = onDismiss, primary = false)
                }
            }
        }
    }
}

/** 电视端日志下载弹窗：二维码 + 地址，和手机扫码输入共用同一套二维码面板。 */
@Composable
fun TvLogShareDialog(
    url: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val refreshFocus = remember { FocusRequester() }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(220)
        runCatching { refreshFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MeBoxTheme(darkTheme = true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(24.dp),
                    )
                    .padding(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "局域网下载日志",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "用手机扫码或直接打开地址，下载包含日志和设备信息的 ZIP。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    TvPhoneInputPanel(
                        url = url,
                        onRefresh = onRefresh,
                        compact = true,
                        qrSize = 240.dp,
                        refreshFocusRequester = refreshFocus,
                    )
                    Spacer(Modifier.height(18.dp))
                    TvActionButton(text = "关闭", onClick = onDismiss, primary = false)
                }
            }
        }
    }
}

/** 电视端输入框：焦点边框更明显，方便外接键盘时定位。 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = event.nativeKeyEvent.keyCode
                    if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP) {
                        return@onPreviewKeyEvent false
                    }
                    // 单行输入框默认会把上下键当作光标移动；电视上应把焦点交给下一个控件。
                    focusManager.moveFocus(
                        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                            FocusDirection.Down
                        } else {
                            FocusDirection.Up
                        },
                    )
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            },
            visualTransformation = if (isPassword) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (isPassword) {
                    androidx.compose.ui.text.input.KeyboardType.Password
                } else {
                    keyboardType
                },
            ),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** 电视端确认弹窗：默认把焦点放在主操作上，避免遥控器找不到按钮。 */
@Composable
fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    val confirmFocus = remember { FocusRequester() }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        runCatching { confirmFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MeBoxTheme(darkTheme = true) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(22.dp),
                    )
                    .padding(28.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TvActionButton(
                        text = dismissLabel,
                        onClick = onDismiss,
                        primary = false,
                    )
                    TvActionButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        focusRequester = confirmFocus,
                    )
                }
            }
        }
    }
}

private val TV_CONFIRM_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
)
