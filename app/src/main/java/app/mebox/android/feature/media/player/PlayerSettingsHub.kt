package app.mebox.android.feature.media.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.mebox.android.core.player.PlayerDoubleTapAction
import app.mebox.android.core.player.PlayerLongPressAction
import app.mebox.android.core.player.PlayerOrientation
import app.mebox.android.core.player.PlayerUiSettings
import app.mebox.android.core.media.PlaybackPlan

/**
 * CapyPlayer 风格播放器设置中心。
 *
 * 参考它的分组：快捷设置 / 手势操作 / 界面布局 / 播放行为。选项用「点击循环」
 * 的方式切换，遥控器只需要 OK 键，触屏也不用再叠一层弹窗。
 */
@Composable
fun PlayerSettingsHubSheet(
    settings: PlayerUiSettings,
    onSettingsChange: (PlayerUiSettings) -> Unit,
    entries: List<PlayerMenuEntry>,
    onSelectSection: (PlayerSettingsSection) -> Unit,
    onOpenInfo: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onDismiss: () -> Unit,
    sleepTimerLabel: String = "当前会话",
    onLock: (() -> Unit)? = null,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = "播放器设置",
        subtitle = "手势、界面与播放行为",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        entries.forEachIndexed { index, entry ->
            PlayerMenuActionRow(
                icon = entry.icon,
                label = entry.label,
                value = entry.value,
                onClick = { onSelectSection(entry.section) },
                modifier = if (autoFocus && index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
            if (index != entries.lastIndex) Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(4.dp))
        PlayerMenuActionRow(
            icon = Icons.Outlined.Info,
            label = "媒体信息",
            value = "编码 · 轨道 · 时长",
            onClick = onOpenInfo,
        )
        Spacer(Modifier.height(2.dp))
        PlayerMenuActionRow(
            icon = Icons.Outlined.Timer,
            label = "定时关闭",
            value = sleepTimerLabel,
            onClick = onOpenSleepTimer,
        )
        if (onLock != null) {
            Spacer(Modifier.height(2.dp))
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

        HubSectionTitle("手势操作")
        HubChoiceRow(
            label = "双击屏幕左边",
            value = settings.doubleTapLeft.label,
            onClick = {
                onSettingsChange(
                    settings.copy(doubleTapLeft = settings.doubleTapLeft.next()),
                )
            },
        )
        HubChoiceRow(
            label = "双击屏幕右边",
            value = settings.doubleTapRight.label,
            onClick = {
                onSettingsChange(
                    settings.copy(doubleTapRight = settings.doubleTapRight.next()),
                )
            },
        )
        HubChoiceRow(
            label = "左侧长按动作",
            value = settings.longPressLeft.label,
            onClick = {
                onSettingsChange(
                    settings.copy(longPressLeft = settings.longPressLeft.next()),
                )
            },
        )
        HubChoiceRow(
            label = "右侧长按动作",
            value = settings.longPressRight.label,
            onClick = {
                onSettingsChange(
                    settings.copy(longPressRight = settings.longPressRight.next()),
                )
            },
        )
        HubToggleRow(
            label = "长按倍速横滑调节",
            detail = "按住后左右滑动，在 0.5×–4.0× 间调整",
            checked = settings.longPressSpeedAdjust,
            onCheckedChange = { onSettingsChange(settings.copy(longPressSpeedAdjust = it)) },
        )
        HubToggleRow(
            label = "滑动调节亮度与音量",
            detail = "左半屏上下滑动调亮度，右半屏调音量",
            checked = settings.slideBrightnessVolume,
            onCheckedChange = { onSettingsChange(settings.copy(slideBrightnessVolume = it)) },
        )
        HubChoiceRow(
            label = "快进时间",
            value = seekStepLabel(settings.seekForwardMs),
            onClick = {
                onSettingsChange(
                    settings.copy(seekForwardMs = nextSeekStep(settings.seekForwardMs)),
                )
            },
        )
        HubChoiceRow(
            label = "快退时间",
            value = seekStepLabel(settings.seekBackwardMs),
            onClick = {
                onSettingsChange(
                    settings.copy(seekBackwardMs = nextSeekStep(settings.seekBackwardMs)),
                )
            },
        )

        HubSectionTitle("界面布局")
        HubToggleRow(
            label = "简洁模式",
            detail = "隐藏服务器信息和播放内核信息",
            checked = settings.simpleMode,
            onCheckedChange = { onSettingsChange(settings.copy(simpleMode = it)) },
        )
        HubToggleRow(
            label = "播放时间数字滚动",
            checked = settings.rollingTime,
            onCheckedChange = { onSettingsChange(settings.copy(rollingTime = it)) },
        )
        HubToggleRow(
            label = "显示长按倍速提示",
            checked = settings.showLongPressHint,
            onCheckedChange = { onSettingsChange(settings.copy(showLongPressHint = it)) },
        )
        HubToggleRow(
            label = "显示信息图标",
            checked = settings.showInfoIcon,
            onCheckedChange = { onSettingsChange(settings.copy(showInfoIcon = it)) },
        )
        HubToggleRow(
            label = "显示弹幕图标",
            checked = settings.showDanmakuIcon,
            onCheckedChange = { onSettingsChange(settings.copy(showDanmakuIcon = it)) },
        )
        HubToggleRow(
            label = "显示屏幕锁定按钮",
            checked = settings.showLockButton,
            onCheckedChange = { onSettingsChange(settings.copy(showLockButton = it)) },
        )
        HubToggleRow(
            label = "显示屏幕旋转按钮",
            checked = settings.showRotateButton,
            onCheckedChange = { onSettingsChange(settings.copy(showRotateButton = it)) },
        )
        HubToggleRow(
            label = "显示画中画按钮",
            checked = settings.showPictureInPictureButton,
            onCheckedChange = { onSettingsChange(settings.copy(showPictureInPictureButton = it)) },
        )
        HubToggleRow(
            label = "显示投屏图标",
            checked = settings.showCastButton,
            onCheckedChange = { onSettingsChange(settings.copy(showCastButton = it)) },
        )
        HubToggleRow(
            label = "显示画质/版本选择按钮",
            checked = settings.showQualityButton,
            onCheckedChange = { onSettingsChange(settings.copy(showQualityButton = it)) },
        )
        HubToggleRow(
            label = "显示下载按钮",
            checked = settings.showDownloadButton,
            onCheckedChange = { onSettingsChange(settings.copy(showDownloadButton = it)) },
        )

        HubSectionTitle("播放行为")
        HubToggleRow(
            label = "连续播放",
            detail = "播放结束后自动接下一集",
            checked = settings.continuousPlay,
            onCheckedChange = { onSettingsChange(settings.copy(continuousPlay = it)) },
        )
        HubToggleRow(
            label = "离开自动进入画中画",
            detail = "回到桌面或切换应用时以画中画继续播放",
            checked = settings.autoPictureInPicture,
            onCheckedChange = { onSettingsChange(settings.copy(autoPictureInPicture = it)) },
        )
        HubChoiceRow(
            label = "影片方向",
            value = settings.orientation.label,
            onClick = {
                onSettingsChange(settings.copy(orientation = settings.orientation.next()))
            },
        )
        Spacer(Modifier.height(8.dp))
        PlayerMenuActionRow(
            icon = Icons.Outlined.Restore,
            label = "恢复默认设置",
            value = "",
            onClick = { onSettingsChange(PlayerUiSettings.Default) },
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun PlayerMediaInfoSheet(
    title: String,
    subtitle: String?,
    engine: String,
    quality: String,
    audio: String,
    subtitleTrack: String,
    durationMs: Long,
    server: String?,
    onDismiss: () -> Unit,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = "媒体信息",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        InfoRow("标题", title, if (autoFocus) Modifier.focusRequester(firstFocus) else Modifier)
        if (!subtitle.isNullOrBlank()) InfoRow("剧集", subtitle)
        InfoRow("播放内核", engine)
        InfoRow("画质", quality)
        InfoRow("音轨", audio)
        InfoRow("字幕", subtitleTrack)
        InfoRow("时长", PlaybackPlan.formatTime(durationMs))
        if (!server.isNullOrBlank()) InfoRow("服务器", server)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun PlayerSleepTimerSheet(
    remainingMinutes: Int?,
    selectedMinutes: Int? = remainingMinutes,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    val options = listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时", 90 to "1.5 小时")
    PlayerSheetScaffold(
        title = "定时关闭",
        subtitle = remainingMinutes?.let { "将在 $it 分钟后暂停播放" } ?: "到点后自动暂停播放",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        options.forEachIndexed { index, (minutes, label) ->
            val selected = if (minutes == 0) {
                selectedMinutes == null
            } else {
                selectedMinutes == minutes
            }
            HubChoiceRow(
                label = label,
                value = if (selected) "已选" else "",
                showChevron = false,
                selected = selected,
                onClick = { onSelect(minutes) },
                modifier = if (autoFocus && index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .focusable()
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = PlayerSkin.TextSecondary,
            style = PlayerSkin.Overline,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            color = PlayerSkin.TextPrimary,
            style = PlayerSkin.BodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HubSectionTitle(text: String) {
    Text(
        text,
        color = PlayerSkin.TextSecondary,
        style = PlayerSkin.Overline.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun HubToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(PlayerSkin.RadiusControl)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 60.dp else 52.dp)
            .clip(shape)
            .background(if (focused) PlayerSkin.White06 else Color.Transparent)
            .border(
                1.dp,
                if (focused) PlayerSkin.White28 else Color.Transparent,
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.BodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    detail,
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        PlayerSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HubChoiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = true,
    selected: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(PlayerSkin.RadiusControl)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 58.dp else 50.dp)
            .clip(shape)
            .background(
                when {
                    selected -> PlayerSkin.AccentSoft
                    focused -> PlayerSkin.White06
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    selected -> PlayerSkin.AccentLine
                    focused -> PlayerSkin.White28
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
        Text(
            label,
            color = PlayerSkin.TextPrimary,
            style = PlayerSkin.BodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            value,
            color = if (selected) PlayerSkin.Accent else PlayerSkin.TextSecondary,
            style = PlayerSkin.Overline,
            maxLines = 1,
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = PlayerSkin.Accent,
                modifier = Modifier.size(17.dp),
            )
        } else if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Text(
                "›",
                color = PlayerSkin.TextTertiary,
                style = PlayerSkin.BodySmall,
            )
        }
    }
}

private fun PlayerDoubleTapAction.next(): PlayerDoubleTapAction {
    val values = PlayerDoubleTapAction.entries
    return values[(ordinal + 1) % values.size]
}

private fun PlayerLongPressAction.next(): PlayerLongPressAction {
    val values = PlayerLongPressAction.entries
    return values[(ordinal + 1) % values.size]
}

private fun PlayerOrientation.next(): PlayerOrientation {
    val values = PlayerOrientation.entries
    return values[(ordinal + 1) % values.size]
}

private fun seekStepLabel(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(1L)
    return "${seconds} 秒"
}

private fun nextSeekStep(ms: Long): Long {
    val steps = listOf(5_000L, 10_000L, 15_000L, 30_000L)
    val current = steps.indexOfFirst { it == ms }.takeIf { it >= 0 } ?: 1
    return steps[(current + 1) % steps.size]
}


private val SubtitleAccent = Color(0xFF7C5CBF)
private val SubtitleTrackColor = Color(0xFFD8CCEE)
private val SubtitleColorOptions = listOf(
    0xFFFFFFFFL to "白色",
    0xFFFFE082L to "暖黄",
    0xFF80DEEAL to "青色",
    0xFFA5D6A7L to "绿色",
)

/**
 * 字幕设置侧栏：对应真实截图的「字幕设置」。
 *
 * 垂直位置/缩放/颜色/描边都会写入播放器偏好；ExoPlayer 内核会即时应用
 * 字号、底部留白、颜色和描边开关，LibVLC 内核由 libass 自己渲染。
 */
@Composable
fun PlayerSubtitleSettingsSheet(
    settings: PlayerUiSettings,
    onSettingsChange: (PlayerUiSettings) -> Unit,
    tracks: List<PlayerQuickTrack>,
    subtitlesOff: Boolean,
    onSelectTrack: (String?) -> Unit,
    onDismiss: () -> Unit,
    autoFocus: Boolean = false,
    sideSheet: Boolean = false,
) {
    val firstFocus = rememberFirstFocus(autoFocus)
    PlayerSheetScaffold(
        title = "字幕设置",
        onDismiss = onDismiss,
        sideSheet = sideSheet,
    ) {
        Text(
            "ASS/SSA 字幕按脚本原样显示，样式调整仅对 SRT、VTT 等普通文本字幕生效。",
            color = PlayerSkin.TextSecondary,
            style = PlayerSkin.Overline.copy(fontSize = 12.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(bottom = 14.dp),
        )

        SubtitleSliderRow(
            label = "垂直位置",
            valueText = settings.subtitleVerticalPosition.toString(),
            value = settings.subtitleVerticalPosition.toFloat(),
            onValueChange = {
                onSettingsChange(
                    settings.copy(subtitleVerticalPosition = it.roundToInt().coerceIn(0, 100)),
                )
            },
            valueRange = 0f..100f,
            modifier = if (autoFocus) Modifier.focusRequester(firstFocus) else Modifier,
        )
        SubtitleSliderRow(
            label = "字幕缩放",
            valueText = String.format("%.2f", settings.subtitleScale),
            value = settings.subtitleScale,
            onValueChange = {
                onSettingsChange(settings.copy(subtitleScale = (it * 100f).roundToInt() / 100f))
            },
            valueRange = 0.5f..2.5f,
        )
        SubtitleColorRow(
            color = settings.subtitleTextColor,
            onChange = { onSettingsChange(settings.copy(subtitleTextColor = it)) },
        )
        SubtitleSliderRow(
            label = "描边粗细",
            valueText = String.format("%.1f", settings.subtitleOutline),
            value = settings.subtitleOutline,
            onValueChange = {
                onSettingsChange(
                    settings.copy(subtitleOutline = (it * 10f).roundToInt() / 10f),
                )
            },
            valueRange = 0f..4f,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "字幕轨道",
            color = PlayerSkin.TextSecondary,
            style = PlayerSkin.Overline.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SubtitleTrackRow(
            label = "关闭",
            selected = subtitlesOff,
            onClick = { onSelectTrack(null) },
        )
        tracks.forEach { track ->
            SubtitleTrackRow(
                label = track.label,
                detail = track.detail,
                selected = !subtitlesOff && track.selected,
                onClick = { onSelectTrack(track.id) },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .focusable()
            .padding(bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.BodySmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.Time.copy(fontSize = 14.sp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = SubtitleAccent,
                activeTrackColor = SubtitleAccent,
                inactiveTrackColor = SubtitleTrackColor,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            thumb = {
                Box(
                    Modifier
                        .size(width = 5.dp, height = 26.dp)
                        .background(SubtitleAccent, RoundedCornerShape(3.dp)),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(18.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = SubtitleAccent,
                        inactiveTrackColor = SubtitleTrackColor,
                    ),
                )
            },
        )
    }
}

@Composable
private fun SubtitleColorRow(
    color: Long,
    onChange: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(PlayerSkin.RadiusControl)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 58.dp else 52.dp)
            .clip(shape)
            .background(if (focused) PlayerSkin.White06 else Color.Transparent)
            .border(
                1.dp,
                if (focused) PlayerSkin.White14 else Color.Transparent,
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable {
                val index = SubtitleColorOptions.indexOfFirst { it.first == color }
                val next = if (index < 0) 0 else (index + 1) % SubtitleColorOptions.size
                onChange(SubtitleColorOptions[next].first)
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "文字颜色",
            color = PlayerSkin.TextPrimary,
            style = PlayerSkin.BodySmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(color))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(7.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = PlayerSkin.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SubtitleTrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalPlayerIsTv.current
    val shape = RoundedCornerShape(PlayerSkin.RadiusControl)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 56.dp else 48.dp)
            .clip(shape)
            .background(if (focused) PlayerSkin.White06 else Color.Transparent)
            .border(
                1.dp,
                if (focused) PlayerSkin.White14 else Color.Transparent,
                shape,
            )
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = PlayerSkin.TextPrimary,
                style = PlayerSkin.BodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = SubtitleAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
