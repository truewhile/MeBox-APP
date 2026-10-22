package app.mebox.android.feature.media.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.player.PlayerUiSettings

/**
 * 参考 CapyPlayer 真实播放器的界面外壳。
 *
 * 与早期营销图不同，真实界面没有玻璃胶囊：
 * - 左上：关闭 + 媒体标题，下一行是服务器徽标和名称；
 * - 右上：一排线性图标（画中画 / 内核 / 弹幕 / 全屏 / 设置）；
 * - 左右中部：锁定 / 旋转；
 * - 左下：系列名、单集名、内核与容器、时间进度、传输键；
 * - 右下：速度 / 字幕 / 音轨 / 弹幕四个入口。
 */
private val ChromeText = Color(0xFFF7F8FA)
private val ChromeTextDim = Color(0xD9FFFFFF)
private val ChromeTextMuted = Color(0x99FFFFFF)
private val ChromeShadow = Shadow(
    color = Color(0x99000000),
    offset = Offset(0f, 2f),
    blurRadius = 8f,
)

private const val CHROME_TITLE_SIZE = 20f
private const val CHROME_SERIES_SIZE = 15f
private const val CHROME_EPISODE_SIZE = 18f
private const val CHROME_TECH_SIZE = 13f

@Composable
fun PlayerTopChrome(
    landscape: Boolean,
    title: String,
    serverLabel: String?,
    settings: PlayerUiSettings,
    pictureInPictureAvailable: Boolean,
    danmakuEnabled: Boolean,
    onBack: () -> Unit,
    onPictureInPicture: () -> Unit,
    onEngine: () -> Unit,
    onOpenDanmaku: () -> Unit,
    onOpenScale: () -> Unit,
    onSettings: () -> Unit,
    onInfo: () -> Unit,
    onCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalPlayerIsTv.current
    val iconButtonSize = if (tv) 52.dp else if (landscape) 44.dp else 42.dp
    val iconGlyphSize = if (tv) 28.dp else if (landscape) 24.dp else 21.dp
    val closeIconSize = if (tv) 29.dp else 25.dp
    val circleButtonSize = if (tv) 52.dp else if (landscape) 44.dp else 42.dp
    val circleSize = if (tv) 36.dp else if (landscape) 30.dp else 28.dp
    val circleGlyphSize = if (tv) 19.dp else if (landscape) 16.dp else 15.dp
    val titleSize = if (tv) 24f else CHROME_TITLE_SIZE
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = when {
                    tv -> 60.dp
                    landscape -> 52.dp
                    else -> 14.dp
                },
                end = if (tv) 72.dp else if (landscape) 72.dp else 16.dp,
                top = if (tv) 24.dp else 0.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        PlayerChromeIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = "关闭播放器",
            onClick = onBack,
            iconSize = closeIconSize,
            size = iconButtonSize,
        )
        Spacer(Modifier.width(if (tv) 20.dp else if (landscape) 16.dp else 8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 7.dp),
        ) {
            Text(
                title,
                color = ChromeText,
                style = TextStyle(
                    fontSize = titleSize.sp,
                    lineHeight = (titleSize * 1.3f).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (landscape && !settings.simpleMode && !serverLabel.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerBadge("E")
                    Spacer(Modifier.width(10.dp))
                    Text(
                        serverLabel,
                        color = ChromeTextDim,
                        style = TextStyle(
                            fontSize = if (tv) 17.sp else 15.sp,
                            lineHeight = if (tv) 22.sp else 20.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 右上：一排线性图标，没有胶囊背景。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                when {
                    tv -> 16.dp
                    landscape -> 14.dp
                    else -> 1.dp
                },
            ),
        ) {
            if (settings.showPictureInPictureButton && pictureInPictureAvailable) {
                PlayerChromeIconButton(
                    icon = Icons.Outlined.PictureInPictureAlt,
                    contentDescription = "画中画",
                    onClick = onPictureInPicture,
                    size = iconButtonSize,
                    iconSize = iconGlyphSize,
                )
            }
            PlayerCircleGlyphButton(
                contentDescription = "播放内核",
                onClick = onEngine,
                size = circleButtonSize,
                circleSize = circleSize,
            ) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = ChromeText,
                    modifier = Modifier.size(circleGlyphSize),
                )
            }
            if (settings.showInfoIcon) {
                PlayerChromeIconButton(
                    icon = Icons.Outlined.Info,
                    contentDescription = "媒体信息",
                    onClick = onInfo,
                    size = iconButtonSize,
                    iconSize = iconGlyphSize,
                )
            }
            if (settings.showDanmakuIcon) {
                PlayerCircleGlyphButton(
                    contentDescription = "弹幕",
                    onClick = onOpenDanmaku,
                    size = circleButtonSize,
                    circleSize = circleSize,
                ) {
                    if (landscape) {
                        Text(
                            "弹",
                            color = if (danmakuEnabled) ChromeText else ChromeTextMuted,
                            style = TextStyle(
                                fontSize = if (tv) 17.sp else 14.sp,
                                lineHeight = if (tv) 19.sp else 16.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.Comment,
                            contentDescription = null,
                            tint = ChromeText,
                            modifier = Modifier.size(circleGlyphSize),
                        )
                    }
                }
            }
            if (landscape) {
                PlayerChromeIconButton(
                    icon = Icons.Outlined.OpenInFull,
                    contentDescription = "全屏/画面比例",
                    onClick = onOpenScale,
                    size = iconButtonSize,
                    iconSize = iconGlyphSize,
                )
            } else if (settings.showCastButton) {
                PlayerChromeIconButton(
                    icon = Icons.Outlined.Cast,
                    contentDescription = "投屏",
                    onClick = onCast,
                    size = iconButtonSize,
                    iconSize = iconGlyphSize,
                )
            }
            PlayerChromeIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = "播放器设置",
                onClick = onSettings,
                size = iconButtonSize,
                iconSize = iconGlyphSize,
            )
        }
    }
}

@Composable
fun PlayerBottomChrome(
    landscape: Boolean,
    seriesTitle: String,
    episodeTitle: String?,
    techInfo: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    hasNext: Boolean,
    speedLabel: String,
    settings: PlayerUiSettings,
    onSeek: (Long) -> Unit,
    onSeekDragStateChange: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayNext: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onDownload: () -> Unit,
    playFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val tv = LocalPlayerIsTv.current
    // 电视有 3%–5% 的过扫描，底栏不能直接贴屏幕底边。
    val bottomInset = if (tv) 24.dp else 8.dp
    val seriesSize = if (tv) 17f else CHROME_SERIES_SIZE
    val episodeSize = if (tv) 21f else CHROME_EPISODE_SIZE
    val techSize = if (tv) 15f else CHROME_TECH_SIZE
    val entrySize = if (tv) 52.dp else 44.dp
    val entryIconSize = if (tv) 28.dp else 25.dp
    if (landscape) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = if (tv) 64.dp else 56.dp,
                    end = if (tv) 72.dp else 64.dp,
                    bottom = bottomInset,
                ),
        ) {
            if (!settings.simpleMode) {
                Text(
                    seriesTitle,
                    color = ChromeTextMuted,
                    style = TextStyle(
                        fontSize = seriesSize.sp,
                        lineHeight = (seriesSize * 1.1f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!episodeTitle.isNullOrBlank()) {
                Spacer(Modifier.height(0.dp))
                Text(
                    episodeTitle,
                    color = ChromeText,
                    style = TextStyle(
                        fontSize = episodeSize.sp,
                        lineHeight = (episodeSize * 1.15f).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!settings.simpleMode && techInfo.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    techInfo,
                    color = ChromeTextMuted,
                    style = TextStyle(
                        fontSize = techSize.sp,
                        lineHeight = (techSize * 1.15f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (tv) 6.dp else 3.dp))
            PlayerTimeRow(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                rolling = settings.rollingTime,
                onSeek = onSeek,
                onDraggingChange = onSeekDragStateChange,
            )
            Spacer(Modifier.height(if (tv) 6.dp else 3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerTransport(
                    isPlaying = isPlaying,
                    hasNext = hasNext,
                    onTogglePlay = onTogglePlay,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onPlayNext = onPlayNext,
                    playFocusRequester = playFocusRequester,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 10.dp),
                ) {
                    if (settings.showDownloadButton) {
                        PlayerChromeIconButton(
                            icon = Icons.Outlined.Download,
                            contentDescription = "下载",
                            onClick = onDownload,
                            size = entrySize,
                            iconSize = entryIconSize,
                        )
                    }
                    if (settings.showQualityButton) {
                        PlayerChromeIconButton(
                            icon = Icons.Outlined.HighQuality,
                            contentDescription = "画质/版本",
                            onClick = onOpenQuality,
                            size = entrySize,
                            iconSize = entryIconSize,
                        )
                    }
                    PlayerSpeedButton(
                        contentDescription = "播放速度",
                        onClick = onOpenSpeed,
                        size = entrySize,
                        iconSize = entryIconSize,
                    )
                    PlayerEpisodeCircleButton(
                        contentDescription = "分集",
                        onClick = onOpenEpisodes,
                        size = entrySize,
                        iconSize = entryIconSize,
                    )
                    PlayerAudioCircleButton(
                        contentDescription = "音轨",
                        onClick = onOpenAudio,
                        size = entrySize,
                        iconSize = entryIconSize,
                    )
                    PlayerSubtitleBubbleButton(
                        contentDescription = "字幕",
                        onClick = onOpenSubtitle,
                        size = entrySize,
                        iconSize = entryIconSize,
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        ) {
            PlayerTimeRow(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                rolling = settings.rollingTime,
                onSeek = onSeek,
                onDraggingChange = onSeekDragStateChange,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerChromeTextButton(
                    text = compactSpeedLabel(speedLabel),
                    contentDescription = "播放速度",
                    onClick = onOpenSpeed,
                    size = 46.dp,
                    fontSize = 13.sp,
                )
                PlayerEpisodeCircleButton(
                    contentDescription = "分集",
                    onClick = onOpenEpisodes,
                    size = 46.dp,
                    iconSize = 23.dp,
                )
                PlayerChromeIconButton(
                    icon = Icons.Outlined.GraphicEq,
                    contentDescription = "音轨",
                    onClick = onOpenAudio,
                    size = 46.dp,
                    iconSize = 23.dp,
                )
                PlayerSubtitleBubbleButton(
                    contentDescription = "字幕",
                    onClick = onOpenSubtitle,
                    size = 46.dp,
                    iconSize = 23.dp,
                )
                if (settings.showQualityButton) {
                    PlayerChromeIconButton(
                        icon = Icons.Outlined.HighQuality,
                        contentDescription = "画质/版本",
                        onClick = onOpenQuality,
                        size = 46.dp,
                        iconSize = 23.dp,
                    )
                }
            }
        }
    }
}

/** 横屏左右两侧的锁定与旋转按钮。 */
@Composable
fun PlayerSideChrome(
    landscape: Boolean,
    settings: PlayerUiSettings,
    locked: Boolean,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    unlockFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    if (locked) {
        PlayerLockButton(
            onUnlock = onLock,
            focusRequester = unlockFocusRequester,
            modifier = modifier,
        )
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (settings.showLockButton) {
            PlayerChromeIconButton(
                icon = Icons.Outlined.LockOpen,
                contentDescription = "锁定屏幕",
                onClick = onLock,
                size = 44.dp,
                iconSize = 26.dp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(y = (-12).dp)
                    .padding(start = 52.dp),
            )
        }
        if (landscape && settings.showRotateButton) {
            PlayerChromeIconButton(
                icon = Icons.Outlined.ScreenRotation,
                contentDescription = "旋转屏幕",
                onClick = onRotate,
                size = 44.dp,
                iconSize = 26.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 52.dp),
            )
        }
    }
}

@Composable
private fun PlayerTransport(
    isPlaying: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayNext: () -> Unit,
    playFocusRequester: FocusRequester?,
) {
    val tv = LocalPlayerIsTv.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerCircleTransportButton(
            icon = Icons.Outlined.Replay,
            contentDescription = "快退",
            onClick = onSeekBack,
        )
        Spacer(Modifier.width(if (tv) 20.dp else 14.dp))
        PlayerChromePlayButton(
            isPlaying = isPlaying,
            onClick = onTogglePlay,
            focusRequester = playFocusRequester,
        )
        Spacer(Modifier.width(if (tv) 20.dp else 14.dp))
        PlayerCircleTransportButton(
            icon = Icons.Outlined.Refresh,
            contentDescription = "快进",
            onClick = onSeekForward,
        )
    }
}

/** 白底圆形传输键：与参考播放器的快退/快进一致。 */
@Composable
private fun PlayerCircleTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val outerSize = if (tv) 54.dp else 44.dp
    val innerSize = if (tv) 38.dp else 30.dp
    val iconSize = if (tv) 20.dp else 16.dp
    Box(
        modifier = Modifier
            .size(outerSize)
            .clip(CircleShape)
            .playerTvFocus(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Color(0xFF161616),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun PlayerChromePlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val size = if (tv) 54.dp else 44.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .playerTvFocus(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(
            isPlaying = isPlaying,
            modifier = Modifier.size(if (tv) 34.dp else 28.dp),
            tint = Color.White,
            shadow = true,
        )
    }
}

@Composable
private fun PlayerTimeRow(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    rolling: Boolean,
    onSeek: (Long) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val tv = LocalPlayerIsTv.current
    val timeWidth = if (tv) 76.dp else 64.dp
    val displayedPosition = if (dragging && durationMs > 0L) {
        (dragFraction * durationMs).toLong()
    } else {
        positionMs
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerTimeText(
            ms = displayedPosition,
            rolling = rolling,
            modifier = Modifier.width(timeWidth),
        )
        Spacer(Modifier.width(if (tv) 16.dp else 12.dp))
        PlayerCapySeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            onSeek = onSeek,
            onDraggingChange = { active ->
                dragging = active
                onDraggingChange(active)
            },
            onPreviewFraction = { fraction -> dragFraction = fraction },
            modifier = Modifier
                .weight(1f)
                .height(if (tv) 32.dp else 24.dp),
        )
        Spacer(Modifier.width(if (tv) 16.dp else 12.dp))
        PlayerTimeText(
            ms = durationMs,
            rolling = rolling,
            modifier = Modifier.width(timeWidth),
            alignEnd = true,
        )
    }
}

@Composable
private fun PlayerTimeText(
    ms: Long,
    rolling: Boolean,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    val text = formatPlayerTime(ms)
    val tv = LocalPlayerIsTv.current
    val style = TextStyle(
        fontSize = if (tv) 16.sp else 14.sp,
        lineHeight = if (tv) 20.sp else 18.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    Box(modifier, contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        if (rolling) {
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
                },
                label = "playerTimeRoll",
            ) { value ->
                Text(value, color = Color.White, style = style, maxLines = 1)
            }
        } else {
            Text(text, color = Color.White, style = style, maxLines = 1)
        }
    }
}

@Composable
private fun PlayerCapySeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onPreviewFraction: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val shownFraction = if (dragging) {
        dragFraction
    } else {
        PlaybackPlan.progressFraction(positionMs, safeDuration)
    }
    val bufferedFraction = PlaybackPlan.progressFraction(bufferedMs, safeDuration)
    val trackHeight = 6.dp

    Box(modifier, contentAlignment = Alignment.Center) {
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
                            onPreviewFraction(dragFraction)
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
                        onPreviewFraction(dragFraction)
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
                color = Color(0x29FFFFFF),
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, trackPx),
                cornerRadius = CornerRadius(radius),
            )
            drawRoundRect(
                color = Color(0x3DFFFFFF),
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width * bufferedFraction.coerceIn(0f, 1f), trackPx),
                cornerRadius = CornerRadius(radius),
            )
            if (shownFraction > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(size.width * shownFraction.coerceIn(0f, 1f), trackPx),
                    cornerRadius = CornerRadius(radius),
                )
            }
            if (safeDuration > 0L) {
                val thumbRadius = if (dragging) 6.dp.toPx() else 4.5.dp.toPx()
                val thumbX = (size.width * shownFraction.coerceIn(0f, 1f))
                    .coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(thumbX, centerY),
                )
            }
        }
    }
}

/** 保留：少数场景（例如未来的浮动面板）仍可复用的玻璃胶囊。 */
@Composable
fun PlayerLiquidPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x3DFFFFFF), Color(0x14FFFFFF)),
                ),
            )
            .border(1.dp, Color(0x66FFFFFF), CircleShape)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 白色线性图标按钮，带按压/遥控器焦点反馈。 */
@Composable
fun PlayerChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = ChromeText,
    active: Boolean = false,
) {
    PlayerIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier.chromeIconHalo(),
        tint = tint,
        active = active,
        size = size,
        iconSize = iconSize,
    )
}

/** 圆形描边图标按钮：闪电、弹幕等参考播放器里的圆形入口。 */
@Composable
fun PlayerCircleGlyphButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    circleSize: Dp = 30.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .chromeIconHalo()
            .clip(CircleShape)
            .playerTvFocus(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** 字幕入口：圆形描边 + 三条横线，对应参考播放器右下角的字幕图标。 */
@Composable
fun PlayerSubtitleCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 25.dp,
) {
    PlayerCircleGlyphButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        circleSize = iconSize,
    ) {
        Canvas(Modifier.size(iconSize)) {
            val inset = this.size.minDimension * 0.22f
            val lineWidth = this.size.minDimension * 0.105f
            val gap = this.size.minDimension * 0.15f
            val centerY = this.size.height / 2f
            drawLine(
                Color.White,
                Offset(inset, centerY - gap),
                Offset(this.size.width - inset, centerY - gap),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                Color.White,
                Offset(inset, centerY),
                Offset(this.size.width - inset, centerY),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                Color.White,
                Offset(inset, centerY + gap),
                Offset(this.size.width - inset, centerY + gap),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 分集入口：圆形描边 + 两条粗横线。 */
@Composable
fun PlayerEpisodeCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 25.dp,
) {
    PlayerCircleGlyphButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        circleSize = iconSize,
    ) {
        Canvas(Modifier.size(iconSize)) {
            val lineWidth = this.size.minDimension * 0.13f
            val left = this.size.width * 0.27f
            val right = this.size.width * 0.73f
            val centerY = this.size.height / 2f
            val gap = this.size.height * 0.15f
            drawLine(
                Color.White,
                Offset(left, centerY - gap),
                Offset(right, centerY - gap),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                Color.White,
                Offset(left, centerY + gap),
                Offset(right, centerY + gap),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 音轨入口：圆形描边 + 波形线。 */
@Composable
fun PlayerAudioCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 25.dp,
) {
    PlayerCircleGlyphButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        circleSize = iconSize,
    ) {
        Canvas(Modifier.size(iconSize * 0.62f)) {
            val bars = 5
            val gap = this.size.width / (bars + 1)
            val heights = floatArrayOf(0.35f, 0.62f, 1f, 0.62f, 0.35f)
            heights.forEachIndexed { index, fraction ->
                val x = gap * (index + 1)
                val half = this.size.height * fraction / 2f
                drawLine(
                    Color.White,
                    Offset(x, this.size.height / 2f - half),
                    Offset(x, this.size.height / 2f + half),
                    strokeWidth = this.size.width * 0.09f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 播放速度：圆形仪表 + 指针，对应参考播放器右下的速度图标。 */
@Composable
fun PlayerSpeedButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 25.dp,
) {
    PlayerChromeCanvasButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        iconSize = iconSize,
    ) {
        val stroke = this.size.minDimension * 0.09f
        val r = this.size.minDimension / 2f - stroke
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(
                this.size.width / 2f - r,
                this.size.height / 2f - r,
            ),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawLine(
            color = Color.White,
            start = center,
            end = Offset(center.x - r * 0.42f, center.y - r * 0.30f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 字幕：圆角气泡 + 三条横线，对应参考播放器右下的字幕图标。 */
@Composable
fun PlayerSubtitleBubbleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 25.dp,
) {
    PlayerChromeCanvasButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        iconSize = iconSize,
    ) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.8.dp.toPx()
        val left = w * 0.12f
        val top = h * 0.12f
        val right = w * 0.88f
        val bottom = h * 0.70f
        val radius = w * 0.16f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
        val tail = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.34f, bottom - stroke / 2f)
            lineTo(w * 0.30f, h * 0.88f)
            lineTo(w * 0.50f, bottom - stroke / 2f)
            close()
        }
        drawPath(tail, Color.White)
        val lineLeft = w * 0.30f
        val lineRight = w * 0.70f
        val lineStroke = 1.6.dp.toPx()
        listOf(0.33f, 0.47f, 0.61f).forEach { y ->
            drawLine(
                color = Color.White,
                start = Offset(lineLeft, h * y),
                end = Offset(lineRight, h * y),
                strokeWidth = lineStroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 任意 Canvas 图形的图标按钮，供速度/字幕这类参考图标使用。 */
@Composable
fun PlayerChromeCanvasButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 25.dp,
    draw: DrawScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .chromeIconHalo()
            .clip(CircleShape)
            .playerTvFocus(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(iconSize), onDraw = draw)
    }
}

/** 服务器类型徽标：绿色圆底 + 字母。 */
@Composable
private fun ServerBadge(letter: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(Color(0xFF43A047), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = Color.White,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/** 「弹」/倍速这类文字型图标按钮。 */
@Composable
fun PlayerChromeTextButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .chromeIconHalo()
            .clip(CircleShape)
            .background(if (active) PlayerSkin.White18 else Color.Transparent)
            .playerTvFocus(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) PlayerSkin.AccentStrong else ChromeText,
            style = TextStyle(
                fontSize = fontSize,
                lineHeight = (fontSize.value * 1.2f).sp,
                fontWeight = FontWeight.SemiBold,
                shadow = ChromeShadow,
            ),
            maxLines = 1,
        )
    }
}

/** 亮色画面上给白色图标一层柔和暗晕，接近参考里的图标阴影。 */
private fun Modifier.chromeIconHalo(): Modifier = drawBehind {
    val radius = size.minDimension * 0.52f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x40000000), Color(0x00000000)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** 播放器时间：小时存在时 H:MM:SS，否则固定两位分钟 MM:SS。 */
private fun formatPlayerTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun compactSpeedLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "1x"
    return when {
        trimmed.endsWith("0×") -> trimmed.dropLast(2).trimEnd('.') + "x"
        trimmed.endsWith("×") -> trimmed.dropLast(1) + "x"
        else -> trimmed
    }
}
