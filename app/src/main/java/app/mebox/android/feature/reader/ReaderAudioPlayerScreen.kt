package app.mebox.android.feature.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.mebox.android.core.reader.ReaderAudioState
import app.mebox.android.core.reader.ReaderAudioTuning
import app.mebox.android.core.reader.ReaderUrls
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val PlayerBackground = Color(0xFF0B0E14)
private val PlayerSurface = Color(0xFF141A23)
private val PlayerAccent = Color(0xFF8FD8C7)
private val PlayerText = Color(0xFFF2F5F8)
private val PlayerSecondary = Color(0xFFB6C2CE)

internal val AudioPlayerPalette = ReaderPalette(
    background = PlayerBackground,
    text = PlayerText,
    secondary = PlayerSecondary,
    chrome = Color(0xE60B0E14),
)

/**
 * 仿 Legado 的有声书播放页：
 * 封面模糊背景 + 大圆形封面 + 章节目录，底部围绕白色圆形播放键控制
 * 上一章/下一章、快退/快进、倍速、定时与离线缓存。
 * 倍速与定时支持滑杆自由设定，片头/片尾跳过可在「更多」里按本书配置。
 */
@Composable
internal fun AudiobookPlayerScreen(
    state: ReaderUiState,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val audio = state.audio.takeIf {
        it.bookId == state.book?.id && it.chapterIndex == state.chapterIndex
    } ?: ReaderAudioState()
    val book = state.book
    var speedSheetOpen by remember { mutableStateOf(false) }
    var timerSheetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(PlayerBackground)) {
        BookCoverBackground(
            coverUrl = book?.coverUrl,
            origin = origin,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight
            val landscape = screenWidth > screenHeight
            if (landscape) {
                Column(Modifier.fillMaxSize()) {
                    PlayerTopBar(
                        state = state,
                        menuOpen = menuOpen,
                        onMenuOpenChange = { menuOpen = it },
                        onBack = viewModel::closeReading,
                        onInfo = viewModel::toggleInfo,
                        onToc = viewModel::toggleToc,
                        onOrigin = viewModel::openOriginPicker,
                        onLogin = viewModel::openSourceLogin,
                        onCredits = viewModel::openAudioCredits,
                        onMixChanged = viewModel::setAudioMixWithOtherApps,
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            PlayerCover(
                                coverUrl = book?.coverUrl,
                                origin = origin,
                                title = book?.name.orEmpty(),
                                size = minOf(screenHeight * 0.52f, 240.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .padding(start = 24.dp, bottom = 12.dp),
                        ) {
                            PlayerStatusChips(
                                audio = audio,
                                onTimer = { timerSheetOpen = true },
                                onSpeed = { speedSheetOpen = true },
                                onCredits = viewModel::openAudioCredits,
                            )
                            PlayerChapterInfo(state, audio)
                            Spacer(Modifier.height(20.dp))
                            PlayerProgress(state, audio, viewModel)
                            Spacer(Modifier.height(10.dp))
                            AudioControls(
                                state = state,
                                audio = audio,
                                onTimer = { timerSheetOpen = true },
                                onSpeed = { speedSheetOpen = true },
                                onCache = viewModel::cacheCurrentChapter,
                                onToc = viewModel::toggleToc,
                                onPlay = viewModel::toggleAudio,
                                onPrevious = viewModel::audioPrevious,
                                onNext = viewModel::audioNext,
                            )
                            PlayerFooter(state, audio, viewModel)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlayerTopBar(
                        state = state,
                        menuOpen = menuOpen,
                        onMenuOpenChange = { menuOpen = it },
                        onBack = viewModel::closeReading,
                        onInfo = viewModel::toggleInfo,
                        onToc = viewModel::toggleToc,
                        onOrigin = viewModel::openOriginPicker,
                        onLogin = viewModel::openSourceLogin,
                        onCredits = viewModel::openAudioCredits,
                        onMixChanged = viewModel::setAudioMixWithOtherApps,
                    )
                    PlayerStatusChips(
                        audio = audio,
                        onTimer = { timerSheetOpen = true },
                        onSpeed = { speedSheetOpen = true },
                        onCredits = viewModel::openAudioCredits,
                    )
                    Spacer(Modifier.weight(0.4f))
                    PlayerCover(
                        coverUrl = book?.coverUrl,
                        origin = origin,
                        title = book?.name.orEmpty(),
                        size = minOf(screenWidth * 0.64f, screenHeight * 0.36f, 280.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    PlayerChapterInfo(state, audio)
                    Spacer(Modifier.weight(0.7f))
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        PlayerProgress(state, audio, viewModel)
                        Spacer(Modifier.height(8.dp))
                        AudioControls(
                            state = state,
                            audio = audio,
                            onTimer = { timerSheetOpen = true },
                            onSpeed = { speedSheetOpen = true },
                            onCache = viewModel::cacheCurrentChapter,
                            onToc = viewModel::toggleToc,
                            onPlay = viewModel::toggleAudio,
                            onPrevious = viewModel::audioPrevious,
                            onNext = viewModel::audioNext,
                        )
                        PlayerFooter(state, audio, viewModel)
                    }
                }
            }
        }
    }

    if (speedSheetOpen) {
        AudioSpeedSheet(
            current = audio.speed,
            onSelect = viewModel::setAudioSpeed,
            onDismiss = { speedSheetOpen = false },
        )
    }
    if (timerSheetOpen) {
        AudioTimerSheet(
            audio = audio,
            onTimer = viewModel::setAudioTimer,
            onStopAtChapterEnd = { viewModel.setAudioStopAtChapterEnd(true) },
            onDismiss = { timerSheetOpen = false },
        )
    }
    if (state.audioCreditsOpen) {
        AudioCreditsSheet(
            openSeconds = state.book?.openCredits ?: audio.openCreditsSec,
            closeSeconds = state.book?.closeCredits ?: audio.closeCreditsSec,
            saving = state.audioCreditsSaving,
            error = state.audioCreditsError,
            onSave = viewModel::saveAudioCredits,
            onDismiss = viewModel::closeAudioCredits,
        )
    }
}

@Composable
private fun BookCoverBackground(coverUrl: String?, origin: String) {
    val resolved = ReaderUrls.resolve(origin, coverUrl)
    if (resolved != null) {
        AsyncImage(
            model = resolved,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.15f)
                .blur(30.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .alpha(0.62f),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0x99000000),
                        Color(0xD00B0E14),
                        PlayerBackground,
                    ),
                ),
            ),
    )
}

@Composable
private fun PlayerTopBar(
    state: ReaderUiState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onToc: () -> Unit,
    onOrigin: () -> Unit,
    onLogin: () -> Unit,
    onCredits: () -> Unit,
    onMixChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // 每次打开菜单时重新查询系统设置，用户从授权页返回后状态不会停留在旧值。
    val batteryProtected = remember(menuOpen) {
        menuOpen && context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = PlayerText,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                when (state.audioPlayerFeedback().phase) {
                    AudioPlayerPhase.PREPARING, AudioPlayerPhase.BUFFERING -> "正在加载"
                    AudioPlayerPhase.PLAYING -> "正在播放"
                    AudioPlayerPhase.FAILED -> "播放失败"
                    AudioPlayerPhase.PAUSED -> "有声书"
                },
                color = PlayerSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                state.book?.name.orEmpty(),
                color = PlayerText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = PlayerText)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
                containerColor = PlayerSurface,
            ) {
                DropdownMenuItem(
                    text = { Text("书籍信息与缓存", color = PlayerText) },
                    onClick = {
                        onMenuOpenChange(false)
                        onInfo()
                    },
                )
                DropdownMenuItem(
                    text = { Text("目录", color = PlayerText) },
                    onClick = {
                        onMenuOpenChange(false)
                        onToc()
                    },
                )
                DropdownMenuItem(
                    text = { Text("跳过片头片尾", color = PlayerText) },
                    onClick = {
                        onMenuOpenChange(false)
                        onCredits()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (state.audio.mixWithOtherApps) {
                                "与其他应用同时播放：开"
                            } else {
                                "与其他应用同时播放：关"
                            },
                            color = PlayerText,
                        )
                    },
                    onClick = { onMixChanged(!state.audio.mixWithOtherApps) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (batteryProtected) {
                                "后台播放保护：已开启"
                            } else {
                                "后台播放保护"
                            },
                            color = PlayerText,
                        )
                    },
                    onClick = {
                        onMenuOpenChange(false)
                        requestBackgroundPlaybackProtection(context, batteryProtected)
                    },
                )
                DropdownMenuItem(
                    text = { Text("换源", color = PlayerText) },
                    onClick = {
                        onMenuOpenChange(false)
                        onOrigin()
                    },
                )
                DropdownMenuItem(
                    text = { Text("书源登录", color = PlayerText) },
                    onClick = {
                        onMenuOpenChange(false)
                        onLogin()
                    },
                )
            }
        }
    }
}

/**
 * 打开系统的电池优化豁免授权页。
 *
 * 直接请求页在部分厂商 ROM 上不可用，失败时退回电池优化列表，并提示用户
 * 手动把本应用设为「不优化」；不承诺系统一定不会回收后台服务。
 */
private fun requestBackgroundPlaybackProtection(context: Context, alreadyProtected: Boolean) {
    if (alreadyProtected) {
        Toast.makeText(context, "后台播放保护已开启", Toast.LENGTH_SHORT).show()
        return
    }
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    val opened = runCatching { context.startActivity(request) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
    Toast.makeText(context, "请允许本应用在后台运行", Toast.LENGTH_LONG).show()
}

@Composable
private fun PlayerStatusChips(
    audio: ReaderAudioState,
    onTimer: () -> Unit,
    onSpeed: () -> Unit,
    onCredits: () -> Unit,
) {
    val speedChanged = abs(audio.speed - 1f) >= 0.01f
    val hasCredits = audio.openCreditsSec > 0 || audio.closeCreditsSec > 0
    if (!speedChanged && !hasCredits && audio.timerMinutes <= 0 && !audio.stopAtChapterEnd) {
        Spacer(Modifier.height(4.dp))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            audio.timerMinutes > 0 -> PlayerChip(
                text = timerRemainingLabel(audio),
                icon = Icons.Rounded.AccessTime,
                onClick = onTimer,
            )
            audio.stopAtChapterEnd -> PlayerChip(
                text = "播完本章暂停",
                icon = Icons.Rounded.AccessTime,
                onClick = onTimer,
            )
        }
        Spacer(Modifier.weight(1f))
        if (hasCredits) {
            PlayerChip(
                text = creditsLabel(audio),
                icon = Icons.Rounded.FastForward,
                onClick = onCredits,
            )
        }
        if (speedChanged) {
            PlayerChip(
                text = "${formatSpeed(audio.speed)}×",
                icon = null,
                onClick = onSpeed,
            )
        }
    }
}

@Composable
private fun PlayerChip(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.14f),
            shape = RoundedCornerShape(50),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = PlayerAccent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = PlayerText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlayerCover(coverUrl: String?, origin: String, title: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(24.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .border(2.dp, PlayerAccent.copy(alpha = 0.7f), CircleShape),
    ) {
        ReaderCover(
            url = coverUrl,
            origin = origin,
            title = title,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayerChapterInfo(state: ReaderUiState, audio: ReaderAudioState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            audio.chapterTitle.ifBlank { state.chapterTitle },
            color = PlayerText,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        val author = state.book?.author?.takeIf { it.isNotBlank() } ?: "未知作者"
        val chapterCount = state.chapters.size
        val label = if (chapterCount > 0) {
            "$author · 第 ${state.chapterIndex + 1} / $chapterCount 章"
        } else {
            author
        }
        Text(
            label,
            color = PlayerSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerProgress(
    state: ReaderUiState,
    audio: ReaderAudioState,
    viewModel: ReaderViewModel,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val progress = if (dragging) dragValue else audio.progress
    val position = if (dragging) {
        (dragValue * audio.durationMs).toLong()
    } else {
        audio.positionMs
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(
                icon = Icons.Rounded.FastRewind,
                contentDescription = "后退 15 秒",
                onClick = { viewModel.skipAudio(-15_000) },
            )
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { value ->
                    dragging = true
                    dragValue = value
                },
                onValueChangeFinished = {
                    viewModel.seekAudio(dragValue)
                    dragging = false
                },
                enabled = audio.durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = PlayerText,
                    activeTrackColor = PlayerText,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                    disabledThumbColor = PlayerText.copy(alpha = 0.4f),
                    disabledActiveTrackColor = PlayerText.copy(alpha = 0.3f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp),
            )
            RoundControl(
                icon = Icons.Rounded.FastForward,
                contentDescription = "快进 15 秒",
                onClick = { viewModel.skipAudio(15_000) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 44.dp, vertical = 2.dp),
        ) {
            Text(
                formatPlayerTime(position),
                color = PlayerText.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatPlayerTime(audio.durationMs),
                color = PlayerText.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = PlayerText.copy(alpha = 0.85f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AudioControls(
    state: ReaderUiState,
    audio: ReaderAudioState,
    onTimer: () -> Unit,
    onSpeed: () -> Unit,
    onCache: () -> Unit,
    onToc: () -> Unit,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ControlIcon(
            icon = Icons.Rounded.AccessTime,
            contentDescription = "定时关闭",
            onClick = onTimer,
        )
        SpeedControl(
            speed = audio.speed,
            onClick = onSpeed,
        )
        ControlIcon(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "上一章",
            onClick = onPrevious,
            enabled = state.hasPreviousChapter,
        )
        PlayPauseButton(
            feedback = state.audioPlayerFeedback(),
            onClick = onPlay,
        )
        ControlIcon(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "下一章",
            onClick = onNext,
            enabled = state.hasNextChapter,
        )
        ControlIcon(
            icon = Icons.Rounded.Download,
            contentDescription = "缓存本章",
            onClick = onCache,
            enabled = !state.cacheBusy,
            loading = state.cacheBusy,
        )
        ControlIcon(
            icon = Icons.Rounded.FormatListBulleted,
            contentDescription = "目录",
            onClick = onToc,
        )
    }
}

@Composable
private fun ControlIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.03f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = PlayerAccent,
            )
        } else {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) PlayerText.copy(alpha = 0.82f) else PlayerSecondary.copy(alpha = 0.3f),
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun SpeedControl(speed: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${formatSpeed(speed)}×",
            color = PlayerText.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlayPauseButton(
    feedback: AudioPlayerFeedback,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(70.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = PlayerText,
            modifier = Modifier
                .size(64.dp)
                .shadow(12.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .semantics(mergeDescendants = true) {
                    contentDescription = feedback.actionDescription
                    stateDescription = feedback.statusMessage
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (feedback.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = PlayerBackground,
                    )
                    // 加载中仍可暂停/继续，角标明确下一次点击的动作。
                    Icon(
                        if (feedback.playRequested) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = PlayerBackground,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).size(14.dp),
                    )
                } else {
                    Icon(
                        if (feedback.playRequested) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = PlayerBackground,
                        modifier = if (feedback.playRequested) {
                            Modifier.size(34.dp)
                        } else {
                            Modifier.size(34.dp).offset(x = 2.dp)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerFooter(
    state: ReaderUiState,
    audio: ReaderAudioState,
    viewModel: ReaderViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val feedback = state.audioPlayerFeedback()
        val error = feedback.error
        when {
            error != null -> {
                Text(
                    error,
                    color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = viewModel::toggleAudio) {
                    Text("重试播放", color = PlayerAccent, style = MaterialTheme.typography.labelMedium)
                }
            }
            feedback.loading -> {
                Text(
                    feedback.statusMessage,
                    color = PlayerAccent,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
            state.cacheBusy -> {
                Text(
                    if (state.cacheProgress.isBlank()) "正在缓存本章…" else "正在缓存 ${state.cacheProgress}",
                    color = PlayerAccent,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            !state.cacheNotice.isNullOrBlank() -> {
                Text(
                    state.cacheNotice,
                    color = PlayerAccent,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
            !audio.notice.isNullOrBlank() -> {
                Text(
                    audio.notice,
                    color = PlayerSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                Text(
                    feedback.statusMessage,
                    color = PlayerSecondary.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableFloatStateOf(ReaderAudioTuning.snapSpeed(current)) }
    val presets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    fun apply(raw: Float) {
        val snapped = ReaderAudioTuning.snapSpeed(raw)
        if (abs(snapped - draft) < 0.001f) return
        draft = snapped
        onSelect(snapped)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlayerSurface,
        contentColor = PlayerText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("播放速度", style = MaterialTheme.typography.titleMedium, color = PlayerText)
            Text(
                "滑杆可按 0.05 倍自由微调，也可以直接点常用倍速；设置会同步到账号，下次继续使用。",
                style = MaterialTheme.typography.labelSmall,
                color = PlayerSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { apply(draft - ReaderAudioTuning.SPEED_STEP) }) {
                    Text("− 0.05", color = PlayerSecondary, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatSpeed(draft)}×",
                    color = PlayerAccent,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { apply(draft + ReaderAudioTuning.SPEED_STEP) }) {
                    Text("+ 0.05", color = PlayerSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Slider(
                value = draft,
                onValueChange = { apply(it) },
                valueRange = ReaderAudioTuning.SPEED_MIN..ReaderAudioTuning.SPEED_MAX,
                colors = playerSliderColors(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${formatSpeed(ReaderAudioTuning.SPEED_MIN)}×",
                    color = PlayerSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatSpeed(ReaderAudioTuning.SPEED_MAX)}×",
                    color = PlayerSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(14.dp))
            presets.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { speed ->
                        SheetChoice(
                            text = "${formatSpeed(speed)}×",
                            selected = abs(speed - draft) < 0.01f,
                            onClick = { apply(speed) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("完成")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioTimerSheet(
    audio: ReaderAudioState,
    onTimer: (Int) -> Unit,
    onStopAtChapterEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = listOf(15, 30, 45, 60, 90, 120)
    var minutes by remember {
        mutableIntStateOf(
            audio.timerMinutes
                .takeIf { it > 0 }
                ?.coerceIn(ReaderAudioTuning.TIMER_MIN_MINUTES, ReaderAudioTuning.TIMER_MAX_MINUTES)
                ?: 30,
        )
    }
    val active = audio.timerMinutes > 0 || audio.stopAtChapterEnd

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlayerSurface,
        contentColor = PlayerText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("定时关闭", style = MaterialTheme.typography.titleMedium, color = PlayerText)
            Text(
                "可自由设定 1–${ReaderAudioTuning.TIMER_MAX_MINUTES} 分钟，也可以选择播完本章后暂停。",
                style = MaterialTheme.typography.labelSmall,
                color = PlayerSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                timerStatusLabel(audio),
                color = if (active) PlayerAccent else PlayerSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("计时时长", color = PlayerText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "$minutes 分钟",
                    color = PlayerAccent,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Slider(
                value = minutes.toFloat(),
                onValueChange = {
                    minutes = it.roundToInt().coerceIn(
                        ReaderAudioTuning.TIMER_MIN_MINUTES,
                        ReaderAudioTuning.TIMER_MAX_MINUTES,
                    )
                },
                valueRange = ReaderAudioTuning.TIMER_MIN_MINUTES.toFloat()..
                    ReaderAudioTuning.TIMER_MAX_MINUTES.toFloat(),
                colors = playerSliderColors(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${ReaderAudioTuning.TIMER_MIN_MINUTES} 分钟",
                    color = PlayerSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${ReaderAudioTuning.TIMER_MAX_MINUTES} 分钟",
                    color = PlayerSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(14.dp))
            presets.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { preset ->
                        SheetChoice(
                            text = "$preset 分钟",
                            selected = minutes == preset,
                            onClick = { minutes = preset },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onTimer(minutes)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始计时（$minutes 分钟）")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onStopAtChapterEnd()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (audio.stopAtChapterEnd) "已开启：播完本章暂停" else "播完本章后暂停")
            }
            TextButton(
                onClick = {
                    onTimer(0)
                    onDismiss()
                },
                enabled = active,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "关闭定时",
                    color = if (active) PlayerSecondary else PlayerSecondary.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCreditsSheet(
    openSeconds: Int,
    closeSeconds: Int,
    saving: Boolean,
    error: String?,
    onSave: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var openDraft by remember {
        mutableIntStateOf(ReaderAudioTuning.clampCreditsSeconds(openSeconds))
    }
    var closeDraft by remember {
        mutableIntStateOf(ReaderAudioTuning.clampCreditsSeconds(closeSeconds))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlayerSurface,
        contentColor = PlayerText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("跳过片头片尾", style = MaterialTheme.typography.titleMedium, color = PlayerText)
            Text(
                "只对本书生效，设置会保存到账号。片头在进入新章节时自动跳过；片尾在临近结束时直接进入下一章。",
                style = MaterialTheme.typography.labelSmall,
                color = PlayerSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            CreditSlider(
                label = "片头",
                seconds = openDraft,
                onValueChange = { openDraft = ReaderAudioTuning.snapCreditsSeconds(it) },
            )
            CreditSlider(
                label = "片尾",
                seconds = closeDraft,
                onValueChange = { closeDraft = ReaderAudioTuning.snapCreditsSeconds(it) },
            )
            Text(
                "片头 + 片尾超过单章时长时不会执行跳过。",
                color = PlayerSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            error?.let { message ->
                Text(
                    message,
                    color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onSave(0, 0) },
                    enabled = !saving && (openSeconds > 0 || closeSeconds > 0),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清除")
                }
                Button(
                    onClick = { onSave(openDraft, closeDraft) },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = PlayerBackground,
                        )
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditSlider(
    label: String,
    seconds: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = PlayerText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                creditsValueLabel(seconds),
                color = PlayerAccent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..ReaderAudioTuning.CREDITS_MAX_SECONDS.toFloat(),
            colors = playerSliderColors(),
        )
    }
}

@Composable
private fun playerSliderColors() = SliderDefaults.colors(
    thumbColor = PlayerText,
    activeTrackColor = PlayerAccent,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
)

@Composable
private fun SheetChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) PlayerAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
        modifier = modifier.border(
            width = 1.dp,
            color = if (selected) PlayerAccent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
        ),
    ) {
        Text(
            text,
            color = if (selected) PlayerAccent else PlayerText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    }
}

private fun timerRemainingLabel(audio: ReaderAudioState): String {
    if (audio.timerEndsAtMs <= 0) return "定时 ${audio.timerMinutes} 分"
    val remainingMs = (audio.timerEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
    return "定时 $minutes 分"
}

private fun timerStatusLabel(audio: ReaderAudioState): String = when {
    audio.timerMinutes > 0 && audio.timerEndsAtMs > 0 -> {
        val remainingMs = (audio.timerEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val minutes = ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
        "当前：剩余 $minutes 分钟后暂停"
    }
    audio.timerMinutes > 0 -> "当前：${audio.timerMinutes} 分钟后暂停"
    audio.stopAtChapterEnd -> "当前：播完本章后暂停"
    else -> "当前：未开启"
}

private fun creditsLabel(audio: ReaderAudioState): String = when {
    audio.openCreditsSec > 0 && audio.closeCreditsSec > 0 ->
        "片头 ${audio.openCreditsSec}s · 片尾 ${audio.closeCreditsSec}s"
    audio.openCreditsSec > 0 -> "片头 ${audio.openCreditsSec}s"
    else -> "片尾 ${audio.closeCreditsSec}s"
}

private fun creditsValueLabel(seconds: Int): String {
    if (seconds <= 0) return "不跳过"
    val minutes = seconds / 60
    val rest = seconds % 60
    return when {
        minutes <= 0 -> "跳过 $rest 秒"
        rest == 0 -> "跳过 $minutes 分钟"
        else -> "跳过 $minutes 分 $rest 秒"
    }
}

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt() / 100f
    return if (abs(rounded - rounded.toInt()) < 0.001f) {
        rounded.toInt().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}

private fun formatPlayerTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
