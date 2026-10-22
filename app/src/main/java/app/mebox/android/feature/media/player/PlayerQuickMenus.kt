package app.mebox.android.feature.media.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mebox.android.core.media.PlaybackPlan

/** 播放页右下角/顶部图标打开的贴边浮层菜单。 */
enum class PlayerQuickMenu { ENGINE, SPEED, AUDIO, SUBTITLE, DANMAKU }

/** 浮层里的音轨/字幕轨道。 */
data class PlayerQuickTrack(
    val id: String,
    val label: String,
    val selected: Boolean,
    val detail: String? = null,
)

private val MenuBg = Color(0xFF2B2B2B)
private val MenuText = Color(0xFFF2F2F2)
private val MenuMuted = Color(0xB3FFFFFF)
private val MenuDivider = Color(0xFF3A3A3A)
private val MenuAccent = Color(0xFF4A90E2)

@Composable
fun PlayerQuickMenuCard(
    menu: PlayerQuickMenu,
    engineLabel: String,
    speed: Float,
    audioTracks: List<PlayerQuickTrack>,
    subtitleOff: Boolean,
    subtitleTracks: List<PlayerQuickTrack>,
    danmakuEnabled: Boolean,
    danmakuLoading: Boolean,
    danmakuMatchLabel: String?,
    danmakuCount: Int,
    osdVisible: Boolean,
    onSelectSpeed: (Float) -> Unit,
    onSelectAudio: (String?) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onOpenSubtitleSearch: () -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    onToggleDanmaku: (Boolean) -> Unit,
    onOpenDanmakuSearch: () -> Unit,
    onOpenDanmakuSettings: () -> Unit,
    onOpenEngine: () -> Unit,
    onToggleOsd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalPlayerIsTv.current
    // 面板本身只负责挡住点击。这里不能用 clickable：它会变成可聚焦节点，
    // 遥控器方向键可能把焦点送到整块空白上，按 OK 反而把浮层关掉。
    Column(
        modifier = modifier
            .width(if (tv) 288.dp else 232.dp)
            // 音轨/字幕轨道多时不能超出屏幕：电视可用高度约 400dp。
            .heightIn(max = if (tv) 384.dp else 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MenuBg)
            .pointerInput(Unit) {
                detectTapGestures { }
            }
            .verticalScroll(rememberScrollState())
            .padding(vertical = if (tv) 8.dp else 6.dp),
    ) {
        val firstFocus = remember { FocusRequester() }
        // 打开浮层后把焦点送进第一项，避免焦点还停在原来的入口按钮上。
        LaunchedEffect(tv, menu) {
            if (tv) requestPlayerTvFocus(firstFocus)
        }
        val firstFocusModifier = Modifier.focusRequester(firstFocus)
        when (menu) {
            PlayerQuickMenu.ENGINE -> {
                MenuRow(
                    icon = Icons.Outlined.Settings,
                    label = "播放内核",
                    detail = "当前：$engineLabel",
                    onClick = onOpenEngine,
                    modifier = firstFocusModifier,
                )
                MenuDivider()
                MenuRow(
                    icon = Icons.Outlined.Info,
                    label = "OSD 信息",
                    selected = osdVisible,
                    onClick = onToggleOsd,
                )
            }

            PlayerQuickMenu.SPEED -> {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEachIndexed { index, value ->
                    val selected = kotlin.math.abs(speed - value) < 0.01f
                    MenuRow(
                        icon = Icons.Outlined.Speed,
                        label = playerSpeedLabel(value),
                        selected = selected,
                        onClick = { onSelectSpeed(value) },
                        modifier = if (index == 0) firstFocusModifier else Modifier,
                    )
                }
            }

            PlayerQuickMenu.AUDIO -> {
                MenuRow(
                    icon = Icons.Outlined.GraphicEq,
                    label = "自动",
                    selected = audioTracks.none { it.selected },
                    onClick = { onSelectAudio(null) },
                    modifier = firstFocusModifier,
                )
                if (audioTracks.isNotEmpty()) MenuDivider()
                audioTracks.forEach { track ->
                    MenuRow(
                        icon = Icons.Outlined.GraphicEq,
                        label = track.label,
                        detail = track.detail,
                        selected = track.selected,
                        onClick = { onSelectAudio(track.id) },
                    )
                }
            }

            PlayerQuickMenu.SUBTITLE -> {
                MenuRow(
                    icon = Icons.Outlined.VisibilityOff,
                    label = "关闭",
                    selected = subtitleOff,
                    onClick = { onSelectSubtitle(null) },
                    modifier = firstFocusModifier,
                )
                MenuDivider()
                MenuRow(
                    icon = Icons.Outlined.Search,
                    label = "搜索在线字幕",
                    onClick = onOpenSubtitleSearch,
                )
                MenuDivider()
                MenuRow(
                    icon = Icons.Outlined.Settings,
                    label = "字幕设置",
                    onClick = onOpenSubtitleSettings,
                )
            }

            PlayerQuickMenu.DANMAKU -> {
                var toggleFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .heightIn(min = if (tv) 52.dp else 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .playerTvFocus(toggleFocused, RoundedCornerShape(8.dp))
                        .focusRequester(firstFocus)
                        .onFocusChanged { toggleFocused = it.isFocused }
                        // 整行可确认，开关本身不再单独抢焦点。
                        .clickable { onToggleDanmaku(!danmakuEnabled) }
                        .padding(
                            horizontal = if (tv) 14.dp else 18.dp,
                            vertical = if (tv) 10.dp else 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "启用弹幕",
                        color = MenuText,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = if (tv) 18.sp else 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    PlayerSwitch(
                        checked = danmakuEnabled,
                        onCheckedChange = onToggleDanmaku,
                        checkedTrackColor = MenuAccent,
                    )
                }
                MenuDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    val status = when {
                        danmakuLoading -> "正在匹配弹幕…"
                        danmakuMatchLabel != null && danmakuCount > 0 ->
                            "$danmakuMatchLabel · $danmakuCount 条"
                        else -> "未找到匹配，请尝试手动搜索"
                    }
                    Text(
                        status,
                        color = MenuText,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                        ),
                    )
                    if (danmakuMatchLabel == null && !danmakuLoading) {
                        Text(
                            "点击搜索",
                            color = MenuMuted,
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            ),
                        )
                    }
                }
                MenuDivider()
                MenuRow(
                    icon = Icons.Outlined.Search,
                    label = "搜索弹幕",
                    onClick = onOpenDanmakuSearch,
                )
                MenuDivider()
                MenuRow(
                    icon = Icons.Outlined.Settings,
                    label = "显示设置",
                    onClick = onOpenDanmakuSettings,
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    selected: Boolean = false,
) {
    val tv = LocalPlayerIsTv.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .heightIn(min = if (tv) 52.dp else 44.dp)
            .clip(shape)
            .playerTvFocus(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (tv) 14.dp else 18.dp,
                vertical = if (tv) 10.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MenuAccent else MenuText,
            modifier = Modifier.size(if (tv) 26.dp else 24.dp),
        )
        Spacer(Modifier.width(if (tv) 16.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (selected) MenuAccent else MenuText,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = if (tv) 18.sp else 16.sp,
                    lineHeight = if (tv) 24.sp else 21.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    color = MenuMuted,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = if (tv) 15.sp else 13.sp,
                        lineHeight = if (tv) 20.sp else 18.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MenuAccent,
                modifier = Modifier.size(if (tv) 26.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MenuDivider),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        color = MenuMuted,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** OSD 信息浮层：显示内核、轨道、进度和缓冲等技术信息。 */
@Composable
fun PlayerOsdOverlay(
    engine: String,
    quality: String,
    audio: String,
    subtitle: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    host: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC101010))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        OsdLine("内核", engine)
        OsdLine("画质", quality)
        OsdLine("音轨", audio)
        OsdLine("字幕", subtitle)
        OsdLine("进度", PlaybackPlan.formatTime(positionMs))
        OsdLine("时长", PlaybackPlan.formatTime(durationMs))
        OsdLine("缓冲", PlaybackPlan.formatTime(bufferedMs))
        if (!host.isNullOrBlank()) OsdLine("服务器", host)
    }
}

@Composable
private fun OsdLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Text(
            label,
            color = Color(0x99FFFFFF),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFeatureSettings = "tnum",
            ),
            modifier = Modifier.width(56.dp),
        )
        Text(
            value,
            color = Color(0xFFE6E6E6),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
