package app.mebox.android.feature.media.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.feature.media.DanmakuUiState

/** 播放器设置面板分组。 */
enum class PlayerSettingsSection { SPEED, QUALITY, AUDIO, SUBTITLE, DANMAKU, SCALE, ENGINE }

data class PlayerChoice(
    val id: String,
    val label: String,
    val selected: Boolean,
)

/** 手势调节的对象：左侧亮度、右侧音量。 */
enum class PlayerLevel { BRIGHTNESS, VOLUME }

private val OverlayText = Color(0xFFF2F2F2)
private val OverlayMuted = Color(0xFF9E9E9E)
private val Accent = Color(0xFF4DA3FF)
private val IconButtonScrim = Color(0x33000000)

@Composable
fun PlayerTopBar(
    title: String,
    subtitle: String?,
    pictureInPictureAvailable: Boolean,
    onBack: () -> Unit,
    onPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = OverlayMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (pictureInPictureAvailable) {
            PlayerIconButton(
                icon = Icons.Default.PictureInPictureAlt,
                contentDescription = "画中画",
                onClick = onPictureInPicture,
            )
        }
    }
}

/** 锁定状态下的解锁按钮：贴在左侧中间，半透明圆底。 */
@Composable
fun PlayerLockButton(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(IconButtonScrim)
            .clickable(onClick = onUnlock),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = "解锁",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 播放器里的圆形图标按钮：统一 42dp 触摸区 + 半透明底。 */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 42.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(IconButtonScrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    hasNext: Boolean,
    speedLabel: String,
    subtitleLabel: String,
    danmakuEnabled: Boolean,
    danmakuLabel: String,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenMore: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xB3000000), Color(0xE6000000)),
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            onSeek = onSeek,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                PlaybackPlan.formatTime(positionMs),
                color = OverlayText,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "-${PlaybackPlan.formatTime(remainingMs)}",
                color = OverlayMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(IconButtonScrim)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseGlyph(isPlaying = isPlaying, modifier = Modifier.size(26.dp))
            }
            if (hasNext) {
                Spacer(Modifier.width(4.dp))
                PlayerIconButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "下一集",
                    onClick = onPlayNext,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(
                    icon = Icons.AutoMirrored.Filled.Comment,
                    contentDescription = danmakuLabel,
                    onClick = onToggleDanmaku,
                    tint = if (danmakuEnabled) Accent else Color.White,
                )
                PlayerIconButton(
                    icon = Icons.Default.Subtitles,
                    contentDescription = "字幕：$subtitleLabel",
                    onClick = onOpenSubtitle,
                )
                SpeedPill(label = speedLabel, onClick = onOpenSpeed)
                PlayerIconButton(
                    icon = Icons.Default.Tune,
                    contentDescription = "播放设置",
                    onClick = onOpenMore,
                )
                PlayerIconButton(
                    icon = Icons.Default.Lock,
                    contentDescription = "锁定屏幕",
                    onClick = onLock,
                )
            }
        }
    }
}

/** 倍速胶囊：文字标签比图标更直观，固定宽度避免 1.0x / 1.25x 切换时布局跳动。 */
@Composable
private fun SpeedPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(54.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(IconButtonScrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = OverlayText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun PlayPauseGlyph(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier) {
        if (isPlaying) {
            val barWidth = size.width * 0.24f
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.18f, size.height * 0.1f),
                size = Size(barWidth, size.height * 0.8f),
                cornerRadius = CornerRadius(3f),
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.58f, size.height * 0.1f),
                size = Size(barWidth, size.height * 0.8f),
                cornerRadius = CornerRadius(3f),
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.08f)
                lineTo(size.width * 0.9f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.92f)
                close()
            }
            drawPath(path, tint)
        }
    }
}

/**
 * 自绘进度条：同时显示缓冲与播放进度，拖动时显示目标时间。
 *
 * 使用自绘而不是 Material Slider，是为了让缓冲区间和细长轨道在视频上更
 * 清晰，也避免 Slider 默认的 48dp 触摸范围挤压控制栏。
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val shownFraction = if (dragging) dragFraction else PlaybackPlan.progressFraction(positionMs, safeDuration)
    val bufferedFraction = PlaybackPlan.progressFraction(bufferedMs, safeDuration)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
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
                            dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (dragging) {
                                onSeek((dragFraction * safeDuration).toLong())
                            }
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
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
            val trackHeight = 3.dp.toPx()
            val centerY = size.height / 2f
            val radius = trackHeight / 2f
            drawRoundRect(
                color = Color(0x40FFFFFF),
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(radius),
            )
            drawRoundRect(
                color = Color(0x8CFFFFFF),
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width * bufferedFraction.coerceIn(0f, 1f), trackHeight),
                cornerRadius = CornerRadius(radius),
            )
            drawRoundRect(
                color = Accent,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width * shownFraction.coerceIn(0f, 1f), trackHeight),
                cornerRadius = CornerRadius(radius),
            )
            drawCircle(
                color = Color.White,
                radius = (if (dragging) 7.dp else 5.dp).toPx(),
                center = Offset(size.width * shownFraction.coerceIn(0f, 1f), centerY),
            )
        }
        if (dragging && safeDuration > 0L) {
            Text(
                text = "${PlaybackPlan.formatTime((dragFraction * safeDuration).toLong())} / " +
                    PlaybackPlan.formatTime(safeDuration),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Color(0xE6000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 双击/拖动快进时的中央提示：方向 + 目标时间。 */
@Composable
fun PlayerSeekFeedback(
    forward: Boolean,
    deltaMs: Long,
    targetMs: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "${if (forward) "快进" else "后退"} ${formatDelta(deltaMs)}",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                PlaybackPlan.formatTime(targetMs),
                color = OverlayMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 亮度/音量手势的侧边指示器：图标 + 竖向进度条。 */
@Composable
fun PlayerLevelFeedback(
    level: PlayerLevel,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (level == PlayerLevel.BRIGHTNESS) {
                Icons.Default.Brightness6
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x40FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceIn(0f, 1f))
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
            color = OverlayText,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatDelta(ms: Long): String {
    val seconds = (kotlin.math.abs(ms) / 1000).coerceAtLeast(0)
    return when {
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }
}

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
    engine: String,
): List<PlayerMenuEntry> = listOf(
    PlayerMenuEntry(PlayerSettingsSection.SPEED, "播放速度", speed, Icons.Default.Speed),
    PlayerMenuEntry(PlayerSettingsSection.QUALITY, "清晰度", quality, Icons.Default.HighQuality),
    PlayerMenuEntry(PlayerSettingsSection.AUDIO, "音轨", audio, Icons.Default.Audiotrack),
    PlayerMenuEntry(PlayerSettingsSection.SUBTITLE, "字幕", subtitle, Icons.Default.Subtitles),
    PlayerMenuEntry(PlayerSettingsSection.SCALE, "画面缩放", scale, Icons.Default.AspectRatio),
    PlayerMenuEntry(PlayerSettingsSection.DANMAKU, "弹幕", danmaku, Icons.AutoMirrored.Filled.Comment),
    PlayerMenuEntry(PlayerSettingsSection.ENGINE, "播放内核", engine, Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMoreSheet(
    entries: List<PlayerMenuEntry>,
    onSelect: (PlayerSettingsSection) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                "播放设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(entry.section) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = OverlayMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        entry.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        entry.value,
                        color = OverlayMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = OverlayMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(
    section: PlayerSettingsSection,
    choices: List<PlayerChoice>,
    description: String? = null,
    onDismiss: () -> Unit,
    onSelect: (PlayerChoice) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = section.title(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    description,
                    color = OverlayMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (choices.isEmpty()) {
                Text(
                    "这个视频没有可切换的选项",
                    color = OverlayMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                choices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(choice) }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            choice.label,
                            color = if (choice.selected) Accent else Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (choice.selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmakuSettingsSheet(
    state: DanmakuUiState,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRenderChange: (opacity: Float, fontScale: Float, area: Float) -> Unit,
    onReload: () -> Unit,
    onSelectEpisode: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "弹幕",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Spacer(Modifier.height(6.dp))
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = status,
                    color = if (state.error != null) Color(0xFFFF8A80) else OverlayMuted,
                    style = MaterialTheme.typography.bodySmall,
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
                    text = if (state.candidates.isNotEmpty()) "选择弹幕库" else "切换同集来源",
                    color = OverlayMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                libraryGroups.forEach { anime ->
                    if (anime.animeTitle.isNotBlank()) {
                        Text(
                            text = anime.animeTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    anime.episodes.forEach { episode ->
                        if (episode.episodeId > 0L) {
                            Text(
                                text = episode.episodeTitle.ifBlank { "弹幕库 ${episode.episodeId}" },
                                color = OverlayText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectEpisode(episode.episodeId) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onReload,
                enabled = !state.loading,
            ) {
                Text("重新加载弹幕")
            }
        }
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
        text = title,
        color = OverlayMuted,
        style = MaterialTheme.typography.labelMedium,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            val active = kotlin.math.abs(selected - value) < 0.01f
            Text(
                text = label,
                color = if (active) Accent else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .background(
                        if (active) Color(0x334DA3FF) else Color(0x1AFFFFFF),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun PlayerSettingsSection.title(): String = when (this) {
    PlayerSettingsSection.SPEED -> "播放速度"
    PlayerSettingsSection.QUALITY -> "清晰度"
    PlayerSettingsSection.AUDIO -> "音轨"
    PlayerSettingsSection.SUBTITLE -> "字幕"
    PlayerSettingsSection.DANMAKU -> "弹幕"
    PlayerSettingsSection.SCALE -> "画面缩放"
    PlayerSettingsSection.ENGINE -> "播放内核"
}

@Composable
fun PlayerBufferingIndicator() {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text("缓冲中…", color = OverlayMuted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PlayerHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun PlayerCenterButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(isPlaying = isPlaying, modifier = Modifier.size(34.dp))
    }
}

/**
 * 播放结束浮层：有下一集时给出主按钮和自动播放倒计时，其余情况只有重播/返回。
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
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF2141414))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "播放结束",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!nextTitle.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onPlayNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "播放下一集",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    nextTitle,
                    color = OverlayMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (countdown != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$countdown 秒后自动播放",
                            color = OverlayMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = onCancelAutoNext) {
                            Text("取消", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onReplay,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("重播")
                }
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("返回", color = OverlayText)
                }
            }
        }
    }
}
