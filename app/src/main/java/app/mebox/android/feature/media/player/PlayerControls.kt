package app.mebox.android.feature.media.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vrpano
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.mebox.android.core.input.QrCodes
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.player.vr.VrDisplayMode
import app.mebox.android.core.player.vr.VrProjection
import app.mebox.android.core.player.vr.VrSettings
import app.mebox.android.core.player.vr.VrStereo
import app.mebox.android.feature.media.DanmakuUiState

/** 播放器设置面板分组。 */
enum class PlayerSettingsSection { SPEED, QUALITY, AUDIO, SUBTITLE, DANMAKU, SCALE, VR, ENGINE }

/**
 * 面板里的一行选项。
 *
 * [badge] 是参考设计里的短码徽章（4K / A1 / S1 / EXO），[detail] 是副标题
 * （码率、语言等）；没有对应信息时留空，行会自然收窄成单行。
 */
data class PlayerChoice(
    val id: String,
    val label: String,
    val selected: Boolean,
    val badge: String? = null,
    val detail: String? = null,
)

/** 手势调节的对象：左侧亮度、右侧音量。 */
enum class PlayerLevel { BRIGHTNESS, VOLUME }

/** 倍速显示：整数补一位小数（1.0×），非整数去掉末尾 0（1.25×）。 */
fun playerSpeedLabel(speed: Float): String {
    val rounded = Math.round(speed * 100f) / 100f
    val text = if (rounded == rounded.toInt().toFloat()) {
        "%.1f".format(rounded)
    } else {
        "%.2f".format(rounded).trimEnd('0').trimEnd('.')
    }
    return "$text×"
}

private fun qualityActionValue(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty() || trimmed == "自动" || trimmed.equals("auto", ignoreCase = true)) {
        return "AUTO"
    }
    val matched = Regex("(\\d{3,4}[pPiI]|4K|8K|2K|HD|SD|FHD|UHD)").find(trimmed)?.value
    return (matched ?: trimmed).take(4).uppercase()
}

// ─────────────────────────────────────────────────────────────────────────────
// 顶部栏
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 顶部栏：150dp 压暗渐变 + 返回按钮 + 居中标题。
 *
 * 横屏时标题居中，左右各留 72dp 给图标，避免长标题压到按钮；电视端也沿用
 * 同一套结构，方向键焦点由根节点统一转发。
 */
@Composable
fun PlayerTopBar(
    title: String,
    subtitle: String?,
    pictureInPictureAvailable: Boolean,
    onBack: () -> Unit,
    onPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(
                Brush.verticalGradient(
                    0f to PlayerSkin.ScrimTop,
                    0.55f to Color(0x57000000),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                shape = RoundedCornerShape(PlayerSkin.RadiusControl),
            )
            Spacer(Modifier.weight(1f))
            if (pictureInPictureAvailable) {
                PlayerIconButton(
                    icon = Icons.Outlined.PictureInPictureAlt,
                    contentDescription = "画中画",
                    onClick = onPictureInPicture,
                )
            }
            if (onOpenSettings != null) {
                PlayerIconButton(
                    icon = Icons.Outlined.Tune,
                    contentDescription = "播放设置",
                    onClick = onOpenSettings,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 72.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 侧边操作 / 锁定
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 右侧竖排快捷操作：锁定 · 画中画 · 弹幕 · 播放设置。
 *
 * 整组共用一块玻璃，内部用 1px 分隔线；锁定后整组隐藏，只保留左侧解锁按钮。
 */
@Composable
fun PlayerSideActions(
    danmakuEnabled: Boolean,
    pictureInPictureAvailable: Boolean,
    onLock: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onPictureInPicture: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerGlass(radius = 24.dp, modifier = modifier) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerIconButton(
                icon = Icons.Outlined.Lock,
                contentDescription = "锁定屏幕",
                onClick = onLock,
                size = 40.dp,
                iconSize = 19.dp,
            )
            if (pictureInPictureAvailable) {
                SideActionDivider()
                PlayerIconButton(
                    icon = Icons.Outlined.PictureInPictureAlt,
                    contentDescription = "画中画",
                    onClick = onPictureInPicture,
                    size = 40.dp,
                    iconSize = 19.dp,
                )
            }
            SideActionDivider()
            PlayerIconButton(
                icon = Icons.AutoMirrored.Outlined.Comment,
                contentDescription = if (danmakuEnabled) "关闭弹幕" else "开启弹幕",
                onClick = onToggleDanmaku,
                active = danmakuEnabled,
                size = 40.dp,
                iconSize = 19.dp,
            )
            SideActionDivider()
            PlayerIconButton(
                icon = Icons.Outlined.Tune,
                contentDescription = "播放设置",
                onClick = onOpenSettings,
                size = 40.dp,
                iconSize = 19.dp,
            )
        }
    }
}

@Composable
private fun SideActionDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .width(24.dp)
            .height(1.dp)
            .background(PlayerSkin.White10),
    )
}

/** 锁定状态下的解锁按钮：贴在左侧中间，玻璃圆底。 */
@Composable
fun PlayerLockButton(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlass(
            radius = 22.dp,
            strong = true,
            modifier = if (focused) {
                Modifier.border(2.dp, PlayerSkin.White55, CircleShape)
            } else {
                Modifier
            },
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onUnlock,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.LockOpen,
                    contentDescription = "解锁",
                    tint = PlayerSkin.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 底部控制栏
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 底部一体化控制栏：进度条 → 时间 → 传输行 → 操作行。
 *
 * 按压/拖动的自动隐藏抑制由外部通过 [onSeekDragStateChange] 接管；这里只
 * 负责把拖动开始/结束上报，避免控制栏在拖动进度时突然消失。
 */
@Composable
fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    hasNext: Boolean,
    speedLabel: String,
    qualityLabel: String,
    audioLabel: String,
    subtitleLabel: String,
    danmakuEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekDragStateChange: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
    vrActive: Boolean = false,
    onRecenterVr: (() -> Unit)? = null,
    /** 矮屏（手机横屏）没有空间放右侧竖排按钮时，把锁定入口并入操作行。 */
    onLock: (() -> Unit)? = null,
    /** TV 端进入控制栏焦点模式时，把焦点放到播放键上。 */
    playFocusRequester: FocusRequester? = null,
) {
    val qualityValue = remember(qualityLabel) { qualityActionValue(qualityLabel) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.34f to Color(0x66000000),
                    1f to PlayerSkin.ScrimBottom,
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 8.dp),
    ) {
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            onSeek = onSeek,
            onDraggingChange = onSeekDragStateChange,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                PlaybackPlan.formatTime(positionMs),
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Time,
            )
            Spacer(Modifier.weight(1f))
            Text(
                PlaybackPlan.formatTime(durationMs),
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Time.copy(fontWeight = FontWeight.Medium),
            )
        }
        Spacer(Modifier.height(2.dp))

        // 传输行：快退 10 秒 / 播放暂停 / 快进 10 秒 / 下一集。
        // 快进/快退同时承担 TV 遥控器在焦点模式下的 seek 入口。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                icon = Icons.Outlined.FastRewind,
                contentDescription = "快退 10 秒",
                onClick = onSeekBack,
                size = 44.dp,
                iconSize = 24.dp,
                tint = PlayerSkin.White78,
            )
            Spacer(Modifier.width(20.dp))
            PlayPauseButton(
                isPlaying = isPlaying,
                onClick = onTogglePlay,
                focusRequester = playFocusRequester,
            )
            Spacer(Modifier.width(20.dp))
            PlayerIconButton(
                icon = Icons.Outlined.FastForward,
                contentDescription = "快进 10 秒",
                onClick = onSeekForward,
                size = 44.dp,
                iconSize = 24.dp,
                tint = PlayerSkin.White78,
            )
            if (hasNext) {
                Spacer(Modifier.width(20.dp))
                PlayerIconButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = "下一集",
                    onClick = onPlayNext,
                    size = 44.dp,
                    iconSize = 24.dp,
                    tint = PlayerSkin.White78,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // 操作行：倍速 · 画质 · 音轨 · 字幕 · 弹幕 · 更多。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerActionItem(
                label = "倍速",
                value = speedLabel,
                onClick = onOpenSpeed,
                modifier = Modifier.weight(1f),
            )
            PlayerActionItem(
                label = "画质",
                value = qualityValue,
                badgeStyle = true,
                onClick = onOpenQuality,
                modifier = Modifier.weight(1f),
            )
            PlayerActionItem(
                label = "音轨",
                icon = Icons.Outlined.Audiotrack,
                onClick = onOpenAudio,
                modifier = Modifier.weight(1f),
            )
            PlayerActionItem(
                label = "字幕",
                icon = Icons.Outlined.Subtitles,
                active = subtitleLabel != "关闭",
                onClick = onOpenSubtitle,
                modifier = Modifier.weight(1f),
            )
            PlayerActionItem(
                label = "弹幕",
                icon = Icons.AutoMirrored.Outlined.Comment,
                active = danmakuEnabled,
                onClick = onToggleDanmaku,
                modifier = Modifier.weight(1f),
            )
            if (vrActive && onRecenterVr != null) {
                PlayerActionItem(
                    label = "视角",
                    icon = Icons.Outlined.Vrpano,
                    active = true,
                    onClick = onRecenterVr,
                    modifier = Modifier.weight(1f),
                )
            }
            PlayerActionItem(
                label = "更多",
                icon = Icons.Outlined.Tune,
                onClick = onOpenMore,
                modifier = Modifier.weight(1f),
            )
            if (onLock != null) {
                PlayerActionItem(
                    label = "锁定",
                    icon = Icons.Outlined.Lock,
                    onClick = onLock,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    PlayerGlass(
        radius = 28.dp,
        strong = true,
        modifier = Modifier
            .size(56.dp)
            .then(
                if (focused) {
                    Modifier.border(2.dp, PlayerSkin.White55, CircleShape)
                } else {
                    Modifier
                },
            )
            .playerPressScale(interaction),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseGlyph(isPlaying = isPlaying, modifier = Modifier.size(26.dp))
        }
    }
}

/** 自绘进度条：缓冲 + 已播放渐变 + 拖动胶囊；拖动中只更新 UI，松手才 seek。 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onDraggingChange: (Boolean) -> Unit = {},
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val shownFraction = if (dragging) dragFraction else PlaybackPlan.progressFraction(positionMs, safeDuration)
    val bufferedFraction = PlaybackPlan.progressFraction(bufferedMs, safeDuration)
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 6.dp else 4.dp,
        animationSpec = tween(durationMillis = 120),
        label = "seekTrackHeight",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(safeDuration) {
                    if (safeDuration <= 0L) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            onDraggingChange(true)
                            dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (dragging) {
                                onSeek((dragFraction * safeDuration).toLong())
                            }
                            dragging = false
                            onDraggingChange(false)
                        },
                        onDragCancel = {
                            dragging = false
                            onDraggingChange(false)
                        },
                    ) { change, _ ->
                        dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                        change.consume()
                    }
                }
                .pointerInput(safeDuration) {
                    if (safeDuration <= 0L) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek((offset.x / widthPx * safeDuration).toLong())
                    }
                },
        ) {
            val trackPx = trackHeight.toPx()
            val centerY = size.height / 2f
            val radius = trackPx / 2f
            drawRoundRect(
                color = PlayerSkin.Track,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, trackPx),
                cornerRadius = CornerRadius(radius),
            )
            drawRoundRect(
                color = PlayerSkin.TrackBuffered,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width * bufferedFraction.coerceIn(0f, 1f), trackPx),
                cornerRadius = CornerRadius(radius),
            )
            if (shownFraction > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(PlayerSkin.Accent, PlayerSkin.AccentStrong),
                        startX = 0f,
                        endX = size.width,
                    ),
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(size.width * shownFraction.coerceIn(0f, 1f), trackPx),
                    cornerRadius = CornerRadius(radius),
                )
            }
            val thumbRadius = (if (dragging) 8.dp else 6.dp).toPx()
            val thumbX = (size.width * shownFraction.coerceIn(0f, 1f))
                .coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
            drawCircle(
                color = PlayerSkin.Accent.copy(alpha = 0.22f),
                radius = thumbRadius + 4.dp.toPx(),
                center = Offset(thumbX, centerY),
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
        }
        if (dragging && safeDuration > 0L) {
            PlayerGlass(
                radius = 999.dp,
                strong = true,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-32).dp),
            ) {
                Text(
                    text = "${PlaybackPlan.formatTime((dragFraction * safeDuration).toLong())} / " +
                        PlaybackPlan.formatTime(safeDuration),
                    color = PlayerSkin.TextPrimary,
                    style = PlayerSkin.Time.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 手势反馈
// ─────────────────────────────────────────────────────────────────────────────

/** 双击/拖动快进时的中央提示：方向 + 增量 + 目标时间。 */
@Composable
fun PlayerSeekFeedback(
    forward: Boolean,
    deltaMs: Long,
    targetMs: Long,
    modifier: Modifier = Modifier,
) {
    PlayerGlass(radius = 999.dp, strong = true, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (forward) Icons.Outlined.FastForward else Icons.Outlined.FastRewind,
                contentDescription = null,
                tint = PlayerSkin.TextPrimary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${if (forward) "+" else "-"}${formatDelta(deltaMs)}",
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Caption.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                PlaybackPlan.formatTime(targetMs),
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Time.copy(fontSize = 12.sp, lineHeight = 16.sp),
            )
        }
    }
}

/** 长按倍速胶囊：显示当前临时倍速，横滑调节时实时更新。 */
@Composable
fun PlayerBoostFeedback(speed: Float, modifier: Modifier = Modifier) {
    PlayerGlass(radius = 999.dp, strong = true, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = PlayerSkin.Accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${playerSpeedLabel(speed)} 倍速播放",
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Caption.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

/** 亮度/音量手势的侧边指示器：图标 + 竖向进度条 + 百分比。 */
@Composable
fun PlayerLevelFeedback(
    level: PlayerLevel,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    PlayerGlass(
        radius = 20.dp,
        strong = true,
        modifier = modifier.width(64.dp),
    ) {
        Column(
            modifier = Modifier
                .height(168.dp)
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (level == PlayerLevel.BRIGHTNESS) {
                    Icons.Outlined.Brightness6
                } else {
                    Icons.AutoMirrored.Outlined.VolumeUp
                },
                contentDescription = null,
                tint = PlayerSkin.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(PlayerSkin.White18),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .background(
                            Brush.verticalGradient(
                                listOf(PlayerSkin.AccentStrong, PlayerSkin.Accent),
                            ),
                        ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Overline.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

private fun formatDelta(ms: Long): String {
    val seconds = (kotlin.math.abs(ms) / 1000).coerceAtLeast(0)
    return when {
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 面板
// ─────────────────────────────────────────────────────────────────────────────

/** 「更多」面板的条目：图标 + 名称 + 当前值。 */
data class PlayerMenuEntry(
    val section: PlayerSettingsSection,
    val label: String,
    val value: String,
    val icon: ImageVector,
)

/**
 * 组装播放设置面板。顺序固定为「常用 → 进阶」，当前值直接显示在行尾，
 * 用户不用逐层点开就能确认倍速、清晰度等状态。
 */
fun playerMenuEntries(
    speed: String,
    quality: String,
    audio: String,
    subtitle: String,
    scale: String,
    danmaku: String,
    vr: String,
    engine: String,
): List<PlayerMenuEntry> = listOf(
    PlayerMenuEntry(PlayerSettingsSection.SPEED, "播放速度", speed, Icons.Outlined.Speed),
    PlayerMenuEntry(PlayerSettingsSection.QUALITY, "清晰度", quality, Icons.Outlined.HighQuality),
    PlayerMenuEntry(PlayerSettingsSection.AUDIO, "音轨", audio, Icons.Outlined.Audiotrack),
    PlayerMenuEntry(PlayerSettingsSection.SUBTITLE, "字幕", subtitle, Icons.Outlined.Subtitles),
    PlayerMenuEntry(PlayerSettingsSection.SCALE, "画面缩放", scale, Icons.Outlined.AspectRatio),
    PlayerMenuEntry(PlayerSettingsSection.VR, "VR / 全景", vr, Icons.Outlined.Vrpano),
    PlayerMenuEntry(PlayerSettingsSection.DANMAKU, "弹幕", danmaku, Icons.AutoMirrored.Outlined.Comment),
    PlayerMenuEntry(PlayerSettingsSection.ENGINE, "播放内核", engine, Icons.Outlined.Settings),
)

/**
 * 统一的面板容器。
 *
 * - 手机：底部 Sheet，28dp 顶部圆角 + 拖拽手柄；
 * - TV/平板：右侧 420dp Side Sheet，避免在 16:9 大屏上把面板拉成整屏宽。
 *
 * 同一时间只允许一个面板，面板打开期间由页面暂停控制栏自动隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheetScaffold(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    sideSheet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (sideSheet) {
        // 用可聚焦 Popup 承载侧边面板：它拥有独立窗口焦点，遥控器方向键不会
        // 逃逸到后面的播放器控件，返回键也由 Popup 自己分发。
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            val shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PlayerSkin.Scrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Column(
                        modifier = Modifier
                            .width(420.dp)
                            .fillMaxHeight()
                            .shadow(
                                elevation = 24.dp,
                                shape = shape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.5f),
                                spotColor = Color.Black.copy(alpha = 0.6f),
                            )
                            .clip(shape)
                            .background(Color(0xFF0F1114))
                            .border(1.dp, PlayerSkin.White14, shape)
                            // 面板空白区域的点击不穿透到遮罩，避免误关。
                            .pointerInput(Unit) { detectTapGestures { } }
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        PlayerSheetContent(
                            title = title,
                            subtitle = subtitle,
                            onDismiss = onDismiss,
                            content = content,
                        )
                    }
                }
            }
        }
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F1114),
        contentColor = PlayerSkin.TextPrimary,
        scrimColor = PlayerSkin.Scrim,
        shape = RoundedCornerShape(topStart = PlayerSkin.RadiusSheet, topEnd = PlayerSkin.RadiusSheet),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(PlayerSkin.White28),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp),
        ) {
            PlayerSheetContent(
                title = title,
                subtitle = subtitle,
                onDismiss = onDismiss,
                content = content,
            )
        }
    }
}

@Composable
private fun PlayerSheetContent(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = PlayerSkin.TextPrimary,
                    style = PlayerSkin.Headline,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        color = PlayerSkin.TextSecondary,
                        style = PlayerSkin.Overline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PlayerIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "关闭",
                onClick = onDismiss,
                size = 40.dp,
                iconSize = 20.dp,
            )
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
internal fun rememberFirstFocus(autoFocus: Boolean): FocusRequester {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        repeat(4) {
            kotlinx.coroutines.delay(160)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    return firstFocus
}

@Composable
fun PlayerMoreSheet(
    entries: List<PlayerMenuEntry>,
    onSelect: (PlayerSettingsSection) -> Unit,
    onDismiss: () -> Unit,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
    /** TV 端可以从面板里进入锁屏，避免侧边按钮不可达。 */
    onLock: (() -> Unit)? = null,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = "播放设置",
        subtitle = "仅影响当前播放器",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        entries.forEachIndexed { index, entry ->
            PlayerMenuRow(
                entry = entry,
                onClick = { onSelect(entry.section) },
                modifier = if (autoFocus && index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
            if (index != entries.lastIndex) {
                Spacer(Modifier.height(2.dp))
            }
        }
        if (onLock != null) {
            Spacer(Modifier.height(10.dp))
            PlayerMenuActionRow(
                icon = Icons.Outlined.Lock,
                label = "锁定屏幕",
                value = "仅保留解锁按钮",
                onClick = {
                    onDismiss()
                    onLock()
                },
            )
        }
    }
}

@Composable
private fun PlayerMenuRow(
    entry: PlayerMenuEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerMenuActionRow(
        icon = entry.icon,
        label = entry.label,
        value = entry.value,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
internal fun PlayerMenuActionRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(PlayerSkin.RadiusControl)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 60.dp else 54.dp)
            .clip(shape)
            .background(if (focused) PlayerSkin.White06 else Color.Transparent)
            .border(
                1.dp,
                if (focused) PlayerSkin.White28 else Color.Transparent,
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(PlayerSkin.White06),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = PlayerSkin.TextSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = PlayerSkin.TextPrimary,
            style = PlayerSkin.BodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            value,
            color = PlayerSkin.TextSecondary,
            style = PlayerSkin.Overline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = PlayerSkin.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun PlayerVrSheet(
    enabled: Boolean,
    settings: VrSettings,
    resolved: VrSettings,
    onEnabledChange: (Boolean) -> Unit,
    onSettingsChange: (VrSettings) -> Unit,
    onRecenter: () -> Unit,
    onDismiss: () -> Unit,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = "VR / 全景",
        subtitle = "把 360°/180° 全景视频投影到球面，支持立体格式与陀螺仪头部追踪",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PlayerSkin.White06),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Vrpano,
                    contentDescription = null,
                    tint = if (enabled) PlayerSkin.Accent else PlayerSkin.TextSecondary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("启用 VR", color = PlayerSkin.TextPrimary, style = PlayerSkin.BodySmall)
                Text(
                    if (enabled) "已开启，画面切换为全景视图" else "关闭，保持普通播放",
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline,
                )
            }
            PlayerSwitch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = if (autoFocus) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }

        if (enabled) {
            Spacer(Modifier.height(18.dp))
            VrSectionTitle("投影方式")
            VrChoiceRow(
                entries = VrProjection.entries,
                selected = settings.projection,
                label = { it.label },
                onSelect = { onSettingsChange(settings.copy(projection = it)) },
            )
            Spacer(Modifier.height(14.dp))
            VrSectionTitle("立体格式")
            VrChoiceRow(
                entries = VrStereo.entries,
                selected = settings.stereo,
                label = { it.label },
                onSelect = { onSettingsChange(settings.copy(stereo = it)) },
            )
            Spacer(Modifier.height(14.dp))
            VrSectionTitle("显示方式")
            VrChoiceRow(
                entries = VrDisplayMode.entries,
                selected = settings.display,
                label = { it.label },
                onSelect = { onSettingsChange(settings.copy(display = it)) },
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("纸盒镜头校正", color = PlayerSkin.TextPrimary, style = PlayerSkin.BodySmall)
                    Text(
                        "适配 Cardboard 一类镜片的桶形畸变，仅头显分屏时生效",
                        color = PlayerSkin.TextSecondary,
                        style = PlayerSkin.Overline,
                    )
                }
                PlayerSwitch(
                    checked = settings.lensCorrection,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(lensCorrection = it))
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "当前识别：${resolved.summary()}",
                color = PlayerSkin.Accent,
                style = PlayerSkin.Overline,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRecenter,
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF17120C),
                ),
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text("重新居中", style = PlayerSkin.BodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "陀螺仪不可用时可以拖动画面调整视角；画面歪了或想回到初始方向时点「重新居中」。",
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Overline,
            )
        }
    }
}

@Composable
private fun VrSectionTitle(text: String) {
    Text(
        text,
        color = PlayerSkin.TextSecondary,
        style = PlayerSkin.Overline,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun <T> VrChoiceRow(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { option ->
            PlayerChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
fun PlayerSettingsSheet(
    section: PlayerSettingsSection,
    choices: List<PlayerChoice>,
    description: String? = null,
    onDismiss: () -> Unit,
    onSelect: (PlayerChoice) -> Unit,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = section.title(),
        subtitle = description,
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        when {
            choices.isEmpty() -> Text(
                "这个视频没有可切换的选项",
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.BodySmall,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            section == PlayerSettingsSection.SPEED -> SpeedChoiceGrid(
                choices = choices,
                onSelect = onSelect,
                firstFocus = if (autoFocus) firstFocus else null,
            )

            else -> choices.forEachIndexed { index, choice ->
                PlayerChoiceRow(
                    choice = choice,
                    onClick = { onSelect(choice) },
                    modifier = if (autoFocus && index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
                if (index != choices.lastIndex) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SpeedChoiceGrid(
    choices: List<PlayerChoice>,
    onSelect: (PlayerChoice) -> Unit,
    firstFocus: FocusRequester?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(3).forEachIndexed { rowIndex, rowChoices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowChoices.forEachIndexed { columnIndex, choice ->
                    val focus = if (rowIndex == 0 && columnIndex == 0 && firstFocus != null) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    }
                    GridChoice(
                        choice = choice,
                        onClick = { onSelect(choice) },
                        modifier = Modifier
                            .weight(1f)
                            .then(focus),
                    )
                }
                repeat(3 - rowChoices.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GridChoice(
    choice: PlayerChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .height(if (tv) 52.dp else 44.dp)
            .clip(shape)
            .background(if (choice.selected) PlayerSkin.AccentSoft else PlayerSkin.White06)
            .border(
                1.dp,
                when {
                    choice.selected -> PlayerSkin.AccentLine
                    focused -> PlayerSkin.White28
                    else -> PlayerSkin.White14
                },
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            choice.label,
            color = if (choice.selected) PlayerSkin.Accent else PlayerSkin.TextPrimary,
            style = PlayerSkin.BodySmall.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayerChoiceRow(
    choice: PlayerChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(PlayerSkin.RadiusControl)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 60.dp else 54.dp)
            .clip(shape)
            .background(
                when {
                    choice.selected -> PlayerSkin.AccentSoft
                    focused -> PlayerSkin.White06
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    choice.selected -> PlayerSkin.AccentLine
                    focused -> PlayerSkin.White14
                    else -> Color.Transparent
                },
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        choice.badge?.let { badge ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (choice.selected) PlayerSkin.AccentSoft else PlayerSkin.White06),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    color = if (choice.selected) PlayerSkin.Accent else PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                choice.label,
                color = if (choice.selected) PlayerSkin.TextPrimary else PlayerSkin.TextPrimary,
                style = PlayerSkin.BodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!choice.detail.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    choice.detail,
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (choice.selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = PlayerSkin.Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 弹幕面板
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 电视端弹幕搜索的手机扫码入口。
 *
 * 二维码直接内嵌在弹幕面板里，不再经过「先点按钮、再弹一层对话框」：
 * [url] 为空表示当前没有局域网地址（例如电视没联网），[onRefresh] 用于
 * 网络切换后重新绑定端口并换发 token。
 */
data class DanmakuPhoneInput(
    val url: String?,
    val onRefresh: () -> Unit,
)

@Composable
fun DanmakuSettingsSheet(
    state: DanmakuUiState,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRenderChange: (opacity: Float, fontScale: Float, area: Float) -> Unit,
    onReload: () -> Unit,
    onSelectEpisode: (Long) -> Unit,
    onSearch: (String) -> Unit = {},
    onResetAuto: () -> Unit = {},
    /** 电视端没有键盘：非空时在面板里直接显示手机扫码二维码。 */
    phoneInput: DanmakuPhoneInput? = null,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = "弹幕",
        subtitle = "开关、字号与显示区域即时生效",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("弹幕显示", color = PlayerSkin.TextPrimary, style = PlayerSkin.BodySmall)
                Text(
                    "关闭后不加载也不渲染弹幕",
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline,
                )
            }
            PlayerSwitch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                modifier = if (autoFocus) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }

        Spacer(Modifier.height(8.dp))
        val status = when {
            !state.enabled -> "弹幕已关闭"
            state.loading -> "正在匹配弹幕…"
            state.error != null -> state.error.orEmpty()
            state.candidates.isNotEmpty() -> "匹配到多个作品，请选择要加载的弹幕库"
            state.comments.isNotEmpty() -> buildString {
                append("已加载 ${state.comments.size} 条")
                state.matchLabel?.let { append(" · ").append(it) }
                if (state.mergedSources > 1) {
                    append(" · 合并 ${state.mergedSources} 个来源")
                }
            }
            state.loadedMediaId != null -> "没有找到匹配的弹幕"
            else -> "打开弹幕后会自动按片名匹配"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.loading) {
                BufferRing(size = 14.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                status,
                color = if (state.error != null) PlayerSkin.Danger else PlayerSkin.TextSecondary,
                style = PlayerSkin.Overline,
            )
        }

        if (state.enabled) {
            Spacer(Modifier.height(14.dp))
            DanmakuManualMatch(
                state = state,
                onSearch = onSearch,
                onResetAuto = onResetAuto,
                phoneInput = phoneInput,
            )
        }

        DanmakuOptionGroup(
            title = "字号",
            options = listOf("小" to 0.8f, "标准" to 1f, "大" to 1.25f, "特大" to 1.5f),
            selected = state.fontScale,
            onSelect = { value -> onRenderChange(state.opacity, value, state.area) },
        )
        DanmakuOptionGroup(
            title = "透明度",
            options = listOf("30%" to 0.3f, "60%" to 0.6f, "85%" to 0.85f, "100%" to 1f),
            selected = state.opacity,
            onSelect = { value -> onRenderChange(value, state.fontScale, state.area) },
        )
        DanmakuOptionGroup(
            title = "显示区域",
            options = listOf("1/4 屏" to 0.25f, "半屏" to 0.5f, "3/4 屏" to 0.75f, "全屏" to 1f),
            selected = state.area,
            onSelect = { value -> onRenderChange(state.opacity, state.fontScale, value) },
        )

        val libraryGroups = if (state.candidates.isNotEmpty()) {
            state.candidates
        } else {
            state.alternatives
        }
        if (libraryGroups.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                if (state.candidates.isNotEmpty()) "选择弹幕库" else "切换同集来源",
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Overline,
            )
            Spacer(Modifier.height(4.dp))
            libraryGroups.forEach { anime ->
                if (anime.animeTitle.isNotBlank()) {
                    Text(
                        anime.animeTitle,
                        color = PlayerSkin.TextPrimary,
                        style = PlayerSkin.BodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                anime.episodes.forEach { episode ->
                    if (episode.episodeId > 0L) {
                        var focused by remember(episode.episodeId) { mutableStateOf(false) }
                        val shape = RoundedCornerShape(10.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (focused) PlayerSkin.White06 else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (focused) PlayerSkin.White28 else Color.Transparent,
                                    shape,
                                )
                                .playerTvFocus(focused, shape)
                                .onFocusChanged { focused = it.isFocused }
                                .clickable { onSelectEpisode(episode.episodeId) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                episode.episodeTitle.ifBlank { "弹幕库 ${episode.episodeId}" },
                                color = PlayerSkin.TextPrimary,
                                style = PlayerSkin.BodySmall,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onReload, enabled = !state.loading) {
            Text("重新加载弹幕", color = if (state.loading) PlayerSkin.TextTertiary else PlayerSkin.Accent)
        }
    }
}

@Composable
private fun DanmakuManualMatch(
    state: DanmakuUiState,
    onSearch: (String) -> Unit,
    onResetAuto: () -> Unit,
    phoneInput: DanmakuPhoneInput?,
) {
    // 只把外部关键词的变化同步回输入框；自动匹配完成时不要清掉用户正在
    // 输入但还没提交的文字。
    var draft by remember(state.manualKeyword) {
        mutableStateOf(state.manualKeyword.orEmpty())
    }
    Text(
        if (phoneInput != null) {
            "手动匹配（电视上用手机扫码输入关键词）"
        } else {
            "手动匹配（留空则恢复自动匹配）"
        },
        color = PlayerSkin.TextSecondary,
        style = PlayerSkin.Overline,
    )
    Spacer(Modifier.height(6.dp))
    if (phoneInput != null) {
        DanmakuPhoneQrBlock(phoneInput)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = PlayerSkin.BodySmall.copy(color = PlayerSkin.TextPrimary),
                placeholder = {
                    Text("输入番剧或电影名", color = PlayerSkin.TextTertiary, style = PlayerSkin.BodySmall)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(draft) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PlayerSkin.TextPrimary,
                    unfocusedTextColor = PlayerSkin.TextPrimary,
                    focusedContainerColor = PlayerSkin.White06,
                    unfocusedContainerColor = PlayerSkin.White06,
                    focusedBorderColor = PlayerSkin.AccentLine,
                    unfocusedBorderColor = PlayerSkin.White14,
                    cursorColor = PlayerSkin.Accent,
                    focusedPlaceholderColor = PlayerSkin.TextTertiary,
                    unfocusedPlaceholderColor = PlayerSkin.TextTertiary,
                ),
                shape = RoundedCornerShape(10.dp),
            )
            Button(
                onClick = { onSearch(draft) },
                enabled = !state.loading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF17120C),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(44.dp),
            ) {
                Text("搜索", style = PlayerSkin.BodySmall)
            }
        }
    }
    val keyword = state.manualKeyword
    if (!keyword.isNullOrBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前关键词：$keyword",
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Overline,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onResetAuto, enabled = !state.loading) {
                Text("恢复自动匹配", color = PlayerSkin.Accent, style = PlayerSkin.Overline)
            }
        }
    }
}

/**
 * 弹幕面板里的手机扫码区。
 *
 * 二维码直接铺在面板内，遥控器不用再多点一层；扫码后手机网页提交的关键词
 * 会立刻回到这个面板触发 `/api/danmaku/:id?kw=` 搜索，候选弹幕库仍然在
 * 面板里用遥控器选择。
 */
@Composable
private fun DanmakuPhoneQrBlock(phoneInput: DanmakuPhoneInput) {
    val qrSize = 148.dp
    val shape = RoundedCornerShape(12.dp)
    // 二维码内容只随 token/地址变化，重扫一次即可复用同一张 bitmap。
    val bitmap = remember(phoneInput.url) {
        phoneInput.url?.let { QrCodes.bitmap(it, sizePx = 512) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PlayerSkin.White06)
            .border(1.dp, PlayerSkin.White14, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(qrSize)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "手机扫码输入弹幕关键词二维码",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "未连接局域网",
                    color = Color(0xFF666666),
                    style = PlayerSkin.Overline,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "手机扫码输入关键词",
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.BodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "扫码后在网页里填写番剧或电影名，提交后电视立即开始搜索",
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Overline,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                phoneInput.url ?: "请先连接 Wi-Fi 或有线网络",
                color = PlayerSkin.TextTertiary,
                style = PlayerSkin.Overline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            DanmakuQrRefreshButton(onClick = phoneInput.onRefresh)
        }
    }
}

/** 二维码刷新入口：换网后重新绑定端口并换发 token。 */
@Composable
private fun DanmakuQrRefreshButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) PlayerSkin.White08 else Color.Transparent)
            .border(
                1.dp,
                if (focused) PlayerSkin.White28 else PlayerSkin.White14,
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Refresh,
            contentDescription = null,
            tint = PlayerSkin.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("刷新二维码", color = PlayerSkin.TextPrimary, style = PlayerSkin.Overline)
    }
}

@Composable
private fun DanmakuOptionGroup(
    title: String,
    options: List<Pair<String, Float>>,
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    Text(
        title,
        color = PlayerSkin.TextSecondary,
        style = PlayerSkin.Overline,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val active = kotlin.math.abs(selected - value) < 0.01f
            PlayerChip(
                label = label,
                selected = active,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlayerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) PlayerSkin.AccentSoft else PlayerSkin.White06)
            .border(
                1.dp,
                if (selected) PlayerSkin.AccentLine else PlayerSkin.White14,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) PlayerSkin.Accent else PlayerSkin.TextPrimary,
            style = PlayerSkin.Overline.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlayerSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    checkedTrackColor: Color = PlayerSkin.Accent,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        // 开关总是和整行点击绑在一起：遥控器只应停在行上，
        // 否则焦点会落到开关上，按 OK 只切换开关而不是执行整行动作。
        modifier = modifier.focusProperties { canFocus = false },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = checkedTrackColor,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color(0xFFB9BCC3),
            uncheckedTrackColor = PlayerSkin.White18,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

private fun PlayerSettingsSection.title(): String = when (this) {
    PlayerSettingsSection.SPEED -> "播放速度"
    PlayerSettingsSection.QUALITY -> "清晰度"
    PlayerSettingsSection.AUDIO -> "音轨"
    PlayerSettingsSection.SUBTITLE -> "字幕"
    PlayerSettingsSection.DANMAKU -> "弹幕"
    PlayerSettingsSection.SCALE -> "画面缩放"
    PlayerSettingsSection.VR -> "VR / 全景"
    PlayerSettingsSection.ENGINE -> "播放内核"
}

// ─────────────────────────────────────────────────────────────────────────────
// 状态层
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BufferRing(size: Dp = 44.dp) {
    val transition = rememberInfiniteTransition(label = "bufferRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bufferAngle",
    )
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        drawCircle(
            color = PlayerSkin.White18,
            radius = (this.size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = PlayerSkin.Accent,
            startAngle = angle - 90f,
            sweepAngle = 110f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(this.size.width - stroke, this.size.height - stroke),
        )
    }
}

@Composable
fun PlayerBufferingIndicator() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BufferRing()
        Spacer(Modifier.height(12.dp))
        Text(
            "正在缓冲…",
            color = PlayerSkin.TextSecondary,
            style = PlayerSkin.Overline.copy(fontSize = 11.sp, lineHeight = 16.sp),
        )
    }
}

@Composable
fun PlayerHint(text: String, modifier: Modifier = Modifier) {
    PlayerGlass(radius = 999.dp, strong = true, modifier = modifier) {
        Text(
            text,
            color = PlayerSkin.TextPrimary,
            style = PlayerSkin.BodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * TV 全屏状态层：Popup 拥有独立窗口焦点，避免遥控器焦点逃逸到后面的控制栏。
 * 返回键统一回到调用方提供的退出逻辑。
 */
@Composable
private fun PlayerTvOverlay(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Popup(
        onDismissRequest = onBack,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        content()
    }
}

/**
 * 错误状态层：图标 + 标题 + 说明 + 主/次按钮。
 *
 * 技术原因（解码失败等）放在第二行说明里，主按钮始终是「重试」。
 */
@Composable
fun PlayerErrorOverlay(
    message: String,
    explanation: String,
    alternativeLabel: String?,
    onRetry: () -> Unit,
    onSwitchAlternative: (() -> Unit)?,
    onBack: () -> Unit,
    isTv: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) {
            repeat(5) {
                kotlinx.coroutines.delay(80)
                if (runCatching { retryFocus.requestFocus() }.isSuccess) {
                    return@LaunchedEffect
                }
            }
        }
    }
    val content: @Composable () -> Unit = {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = PlayerSkin.Danger,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "无法播放该视频",
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Headline,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                explanation,
                color = PlayerSkin.TextSecondary,
                style = PlayerSkin.Caption.copy(lineHeight = 18.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                color = PlayerSkin.TextTertiary,
                style = PlayerSkin.Overline.copy(fontSize = 10.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .height(40.dp)
                        .focusRequester(retryFocus),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF17120C),
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Text("重试", style = PlayerSkin.Caption.copy(fontWeight = FontWeight.Bold))
                }
                if (alternativeLabel != null && onSwitchAlternative != null) {
                    OutlinedButton(
                        onClick = onSwitchAlternative,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, PlayerSkin.White18),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PlayerSkin.TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            "切换到 $alternativeLabel",
                            style = PlayerSkin.Caption.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onBack) {
                Text("返回", color = PlayerSkin.TextSecondary, style = PlayerSkin.Overline)
            }
        }
    }
    }
    if (isTv) {
        PlayerTvOverlay(onBack = onBack) { content() }
    } else {
        content()
    }
}

/**
 * 播放结束 / 下一集浮层。
 *
 * 有下一集时显示倒计时圆环和「立即播放」；没有下一集时只保留重播/返回。
 */
@Composable
fun PlayerEndedOverlay(
    nextTitle: String?,
    countdown: Int?,
    onPlayNext: () -> Unit,
    onReplay: () -> Unit,
    onCancelAutoNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    countdownTotal: Int = 8,
    autoFocus: Boolean = false,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        repeat(4) {
            kotlinx.coroutines.delay(80)
            if (runCatching { primaryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val content: @Composable () -> Unit = {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlass(
            radius = PlayerSkin.RadiusCard,
            strong = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                if (!nextTitle.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(PlayerSkin.Accent.copy(alpha = 0.45f), Color(0xFF2A1C10)),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "播放结束",
                                color = PlayerSkin.TextSecondary,
                                style = PlayerSkin.Overline.copy(fontSize = 10.sp),
                            )
                            Text(
                                nextTitle,
                                color = PlayerSkin.TextPrimary,
                                style = PlayerSkin.Caption.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        countdown?.let { remaining ->
                            CountdownRing(value = remaining, total = countdownTotal)
                        }
                    }
                    if (countdown != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "下一集将在 $countdown 秒后自动播放",
                            color = PlayerSkin.TextSecondary,
                            style = PlayerSkin.Overline.copy(fontSize = 10.sp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = if (countdown != null) onCancelAutoNext else onReplay,
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PlayerSkin.White18),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = PlayerSkin.TextPrimary,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Text(
                                if (countdown != null) "取消" else "重播",
                                style = PlayerSkin.Overline,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onPlayNext,
                            modifier = Modifier
                                .height(34.dp)
                                .focusRequester(primaryFocus),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF17120C),
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(
                                "立即播放",
                                style = PlayerSkin.Overline.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                } else {
                    Text(
                        "播放结束",
                        color = PlayerSkin.TextPrimary,
                        style = PlayerSkin.Headline,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PlayerSkin.White18),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = PlayerSkin.TextPrimary,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Text("返回", style = PlayerSkin.Overline)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onReplay,
                            modifier = Modifier
                                .height(34.dp)
                                .focusRequester(primaryFocus),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF17120C),
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Replay,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "重播",
                                style = PlayerSkin.Overline.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
    }
    if (autoFocus) {
        PlayerTvOverlay(onBack = onBack) { content() }
    } else {
        content()
    }
}

@Composable
private fun CountdownRing(value: Int, total: Int) {
    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = PlayerSkin.Accent.copy(alpha = 0.34f),
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )
            if (value > 0 && total > 0) {
                drawArc(
                    color = PlayerSkin.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * (value.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                )
            }
        }
        Text(
            value.toString(),
            color = PlayerSkin.Accent,
            style = PlayerSkin.Overline.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
    }
}
