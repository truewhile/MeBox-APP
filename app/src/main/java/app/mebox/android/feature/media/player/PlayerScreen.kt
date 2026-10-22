package app.mebox.android.feature.media.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mebox.android.MeBoxApplication
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.player.EngineMedia
import app.mebox.android.core.player.EnginePlaybackState
import app.mebox.android.core.player.EngineResizeMode
import app.mebox.android.core.player.EngineSubtitle
import app.mebox.android.core.player.EngineTrack
import app.mebox.android.core.player.EngineSubtitleStyle
import app.mebox.android.core.player.EngineTrackType
import app.mebox.android.core.player.EngineTracks
import app.mebox.android.core.player.DecoderMode
import app.mebox.android.core.player.PlaybackSessionState
import app.mebox.android.core.player.PlaybackTrackRef
import app.mebox.android.core.player.PlaybackTrackRestorer
import app.mebox.android.core.player.PlaybackTrackSelection
import app.mebox.android.core.player.PlayerDoubleTapAction
import app.mebox.android.core.player.PlayerEngineKind
import app.mebox.android.core.player.PlayerLongPressAction
import app.mebox.android.core.player.PlayerOrientation
import app.mebox.android.core.player.PlayerUiSettings
import app.mebox.android.core.player.SubtitlePreference
import app.mebox.android.core.player.SubtitleSelector
import app.mebox.android.core.player.VideoPlayerEngineListener
import app.mebox.android.core.player.createVideoPlayerEngine
import app.mebox.android.core.player.vr.VrSettings
import app.mebox.android.feature.media.DanmakuUiState
import app.mebox.android.feature.media.PlaybackRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 原生播放页。
 *
 * 播放内核通过统一接口接入：默认 Media3 ExoPlayer（系统解码），可在设置页或
 * 页内「更多」面板切换到 LibVLC（内置 FFmpeg 解码）。界面完全由 Compose 自绘：
 * 手势、进度条、倍速、清晰度、音轨、字幕和画面缩放都不依赖默认控制器，
 * 便于后续统一视觉和电视端扩展。
 *
 * 进度策略：每 5 秒回调节奏写入本地缓存，由 ViewModel 决定每 15 秒同步
 * 服务端；暂停、播放结束和退出页面时强制上报。
 */
@Composable
fun PlayerScreen(
    request: PlaybackRequest?,
    loading: Boolean,
    error: String?,
    title: String,
    danmaku: DanmakuUiState,
    engine: PlayerEngineKind = PlayerEngineKind.EXO,
    subtitlePreference: SubtitlePreference = SubtitlePreference(),
    decoderMode: DecoderMode = DecoderMode.AUTO,
    vrSettings: VrSettings = VrSettings(),
    onVrSettingsChange: (VrSettings) -> Unit = {},
    uiSettings: PlayerUiSettings = PlayerUiSettings.Default,
    onUiSettingsChange: (PlayerUiSettings) -> Unit = {},
    serverLabel: String? = null,
    onSelectEpisode: (String) -> Unit = {},
    isTv: Boolean = false,
    onEngineSelected: (PlayerEngineKind) -> Unit = {},
    onBack: () -> Unit,
    onProgress: (mediaId: String, positionMs: Long, durationMs: Long, force: Boolean) -> Unit,
    onError: (String) -> Unit,
    onRetry: () -> Unit,
    onPlayNext: () -> Unit,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    onDanmakuRenderChange: (opacity: Float, fontScale: Float, area: Float) -> Unit,
    onDanmakuReload: () -> Unit,
    onDanmakuSelectEpisode: (Long) -> Unit,
    onDanmakuSearch: (String) -> Unit = {},
    onDanmakuResetAuto: () -> Unit = {},
    /** 电视端打开手机扫码输入弹幕关键词的对话框。 */
    onOpenDanmakuPhoneInput: (() -> Unit)? = null,
) {
    if (request == null) {
        BackHandler { onBack() }
        val retryFocus = remember { FocusRequester() }
        LaunchedEffect(isTv) {
            if (isTv) {
                kotlinx.coroutines.delay(100)
                runCatching { retryFocus.requestFocus() }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(
                    if (isTv) {
                        Modifier
                            .focusRequester(retryFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.keyCode in TV_CONFIRM_KEYS
                                ) {
                                    onRetry()
                                    true
                                } else {
                                    false
                                }
                            }
                            .focusable()
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loading) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("正在准备播放…", color = Color.White)
                } else {
                    Text(
                        error ?: "无法播放",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onBack) { Text("返回") }
                    if (isTv && !loading) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "遥控器按 OK 重试，返回键退出",
                            color = Color(0xCCFFFFFF),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            PlayerTopBar(
                title = title,
                subtitle = null,
                pictureInPictureAvailable = false,
                onBack = onBack,
                onPictureInPicture = {},
            )
        }
        return
    }

    // 播放器外壳、浮层、分集条通过这个 CompositionLocal 读取电视规格。
    CompositionLocalProvider(LocalPlayerIsTv provides isTv) {
        PlayerSurface(
            request = request,
            title = request.title.ifBlank { title },
            danmaku = danmaku,
            initialEngine = engine,
            subtitlePreference = subtitlePreference,
            decoderMode = decoderMode,
            storedVrSettings = vrSettings,
            onVrSettingsChange = onVrSettingsChange,
            uiSettings = uiSettings,
            onUiSettingsChange = onUiSettingsChange,
            serverLabel = serverLabel,
            onSelectEpisode = onSelectEpisode,
            isTv = isTv,
            onEngineSelected = onEngineSelected,
            onBack = onBack,
            onProgress = onProgress,
            onError = onError,
            onPlayNext = onPlayNext,
            onDanmakuEnabledChange = onDanmakuEnabledChange,
            onDanmakuRenderChange = onDanmakuRenderChange,
            onDanmakuReload = onDanmakuReload,
            onDanmakuSelectEpisode = onDanmakuSelectEpisode,
            onDanmakuSearch = onDanmakuSearch,
            onDanmakuResetAuto = onDanmakuResetAuto,
            onOpenDanmakuPhoneInput = onOpenDanmakuPhoneInput,
        )
    }
}

/** 双击/拖动快进时的提示数据；值为 null 时不显示浮层。 */
private data class SeekFeedbackState(
    val forward: Boolean,
    val deltaMs: Long,
    val targetMs: Long,
)

/** 播放结束后自动接下一集的等待秒数。 */
private const val AUTO_NEXT_SECONDS = 8

private val TV_CONFIRM_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
)

private data class TrackChoice(
    val id: String,
    val label: String,
    val type: EngineTrackType,
    val selected: Boolean,
    val lang: String? = null,
)

private data class PlayerTrackState(
    val video: List<TrackChoice> = emptyList(),
    val audio: List<TrackChoice> = emptyList(),
    val text: List<TrackChoice> = emptyList(),
    val selectedAudioLabel: String? = null,
    val selectedTextLabel: String? = null,
)

@Composable
private fun PlayerSurface(
    request: PlaybackRequest,
    title: String,
    danmaku: DanmakuUiState,
    initialEngine: PlayerEngineKind,
    subtitlePreference: SubtitlePreference,
    decoderMode: DecoderMode,
    storedVrSettings: VrSettings,
    onVrSettingsChange: (VrSettings) -> Unit,
    uiSettings: PlayerUiSettings,
    onUiSettingsChange: (PlayerUiSettings) -> Unit,
    serverLabel: String?,
    onSelectEpisode: (String) -> Unit,
    isTv: Boolean,
    onEngineSelected: (PlayerEngineKind) -> Unit,
    onBack: () -> Unit,
    onProgress: (String, Long, Long, Boolean) -> Unit,
    onError: (String) -> Unit,
    onPlayNext: () -> Unit,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    onDanmakuRenderChange: (Float, Float, Float) -> Unit,
    onDanmakuReload: () -> Unit,
    onDanmakuSelectEpisode: (Long) -> Unit,
    onDanmakuSearch: (String) -> Unit,
    onDanmakuResetAuto: () -> Unit,
    onOpenDanmakuPhoneInput: (() -> Unit)?,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val pipAvailable = remember(activity) {
        activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    // 影片方向：默认跟随传感器；设置页和右侧旋转按钮都可以临时改变。
    val previousOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    var orientationOverride by remember { mutableStateOf<PlayerOrientation?>(null) }
    val activeOrientation = orientationOverride ?: uiSettings.orientation
    DisposableEffect(activity, isTv) {
        onDispose {
            if (!isTv) {
                activity?.requestedOrientation =
                    if (previousOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        ActivityInfo.SCREEN_ORIENTATION_USER
                    } else {
                        previousOrientation
                    }
            }
        }
    }
    LaunchedEffect(activity, isTv, activeOrientation) {
        if (isTv) return@LaunchedEffect
        activity?.requestedOrientation = when (activeOrientation) {
            PlayerOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    LaunchedEffect(uiSettings.orientation) {
        orientationOverride = null
    }

    val initialKey = request.mediaId + "|" + request.streamUrl + "|" +
        request.startPositionMs

    var failedMessage by remember(initialKey) { mutableStateOf<String?>(null) }
    var errorIsDecode by remember(initialKey) { mutableStateOf(false) }
    var playbackEnded by remember(initialKey) { mutableStateOf(false) }

    var positionMs by remember(initialKey) { mutableLongStateOf(request.startPositionMs) }
    var danmakuDiscontinuity by remember(initialKey) { mutableIntStateOf(0) }
    var bufferedMs by remember(initialKey) { mutableLongStateOf(0L) }
    var durationMs by remember(initialKey) { mutableLongStateOf(request.durationMs) }
    var isPlaying by remember(initialKey) { mutableStateOf(false) }
    var isBuffering by remember(initialKey) { mutableStateOf(true) }
    var controlsVisible by remember(initialKey) { mutableStateOf(true) }
    var locked by remember(initialKey) { mutableStateOf(false) }
    var lockBarVisible by remember(initialKey) { mutableStateOf(false) }
    var hint by remember(initialKey) { mutableStateOf<String?>(null) }
    var settingsSection by remember(initialKey) { mutableStateOf<PlayerSettingsSection?>(null) }
    var settingsHubVisible by remember(initialKey) { mutableStateOf(false) }
    var mediaInfoVisible by remember(initialKey) { mutableStateOf(false) }
    var sleepTimerVisible by remember(initialKey) { mutableStateOf(false) }
    // 定时关闭是整次播放会话的行为，跨集数继续计时。
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var sleepTimerDeadline by remember { mutableStateOf<Long?>(null) }
    var episodeStripVisible by remember(initialKey) { mutableStateOf(false) }
    var quickMenu by remember(initialKey) { mutableStateOf<PlayerQuickMenu?>(null) }
    var osdVisible by remember(initialKey) { mutableStateOf(false) }
    var subtitleSettingsVisible by remember(initialKey) { mutableStateOf(false) }
    var longPressActive by remember(initialKey) { mutableStateOf(false) }
    var holdSeekJob by remember(initialKey) { mutableStateOf<Job?>(null) }
    val touchScope = rememberCoroutineScope()
    var seekFeedback by remember(initialKey) { mutableStateOf<SeekFeedbackState?>(null) }
    var levelFeedback by remember(initialKey) { mutableStateOf<PlayerLevel?>(null) }
    var levelFraction by remember(initialKey) { mutableFloatStateOf(0.5f) }
    var autoNextCancelled by remember(initialKey) { mutableStateOf(false) }
    var autoNextCountdown by remember(initialKey) { mutableIntStateOf(0) }
    var playbackSpeed by remember(initialKey) { mutableStateOf(1f) }
    var resizeMode by remember(initialKey) { mutableStateOf(EngineResizeMode.FIT) }
    // VR 是本次播放的开关，不跨片源保留；投影/立体/显示方式沿用上次选择。
    var vrEnabled by remember(initialKey) { mutableStateOf(false) }
    var vrSettings by remember(initialKey) { mutableStateOf(storedVrSettings) }
    val resolvedVr = remember(vrSettings, title, request.streamUrl) {
        vrSettings.resolve(title, request.streamUrl)
    }
    var pinnedQuality by remember(initialKey) { mutableStateOf<TrackChoice?>(null) }
    var subtitlesOff by remember(initialKey) { mutableStateOf(true) }
    var trackState by remember(initialKey) { mutableStateOf(PlayerTrackState()) }

    // 播放内核可以在页内切换；切换时记录当前位置，新内核从同一点起播。
    // 本次播放会话里自动回退过的内核会带到下一集，避免每集都先失败一次；
    // 离开播放页后失效，不写回全局偏好。
    var sessionFallbackEngine by remember { mutableStateOf<PlayerEngineKind?>(null) }
    var engineKind by remember(initialKey) {
        mutableStateOf(sessionFallbackEngine ?: initialEngine)
    }
    // 本次播放会话的用户意图与续播位置：缓冲、错误、换内核都不改变它。
    val session = remember(initialKey) {
        PlaybackSessionState(initialPositionMs = request.startPositionMs)
    }
    // 切换内核的目标与原因：新内核真正就绪后才提示成功，失败时清空。
    var switchTarget by remember(initialKey) { mutableStateOf<PlayerEngineKind?>(null) }
    var switchReason by remember(initialKey) { mutableStateOf<String?>(null) }
    // 用户手动选中的音轨/字幕。轨道 id 每次装载都会变，切换内核时按语义恢复。
    var audioSelection by remember(initialKey) {
        mutableStateOf<PlaybackTrackSelection>(PlaybackTrackSelection.Auto)
    }
    var subtitleSelection by remember(initialKey) {
        mutableStateOf<PlaybackTrackSelection>(PlaybackTrackSelection.Auto)
    }
    // 字幕"没找到"的提示要等外挂字幕异步加载完再判定，找到就取消。
    var subtitleMissToken by remember(initialKey, engineKind) { mutableIntStateOf(0) }
    var subtitleMissNotified by remember(initialKey, engineKind) { mutableStateOf(false) }
    var audioMissToken by remember(initialKey, engineKind) { mutableIntStateOf(0) }
    var audioMissNotified by remember(initialKey, engineKind) { mutableStateOf(false) }
    // 系统解码遇到本机不支持的编码（例如 Hi10P）时自动换到 LibVLC；
    // 同一次播放只尝试一次，避免两个内核来回失败刷屏。
    var autoFallbackAttempted by remember(initialKey) { mutableStateOf(false) }
    // 字幕自动选择只做一次；用户手动选过字幕（或手动关闭）后不再覆盖。
    var autoSubtitleApplied by remember(initialKey, engineKind) { mutableStateOf(false) }
    var userPickedSubtitles by remember(initialKey) { mutableStateOf(false) }

    var dragBaseMs by remember(initialKey) { mutableLongStateOf(0L) }
    var dragDeltaMs by remember(initialKey) { mutableLongStateOf(0L) }
    var seekDragging by remember(initialKey) { mutableStateOf(false) }
    // 长按临时倍速：>0 表示正在长按，松手后恢复到 playbackSpeed。
    var boostSpeed by remember(initialKey) { mutableFloatStateOf(0f) }
    // 任何交互都递增这个计数，用来重置 3 秒自动隐藏计时（控件本来就可见时，
    // 只改 controlsVisible 不会触发 LaunchedEffect 重启）。
    var controlsTick by remember(initialKey) { mutableIntStateOf(0) }
    // TV 焦点模式：true 时方向键交给焦点系统在控制栏按钮间移动。
    var tvFocusMode by remember(initialKey) { mutableStateOf(false) }

    // 一个内核实例绑定一集内容；切换内核会重建实例并从当前位置续播。
    val engine = remember(initialKey, engineKind, decoderMode) {
        // 复用 App 共享的 OkHttp 连接池：起播前刚请求过播放地址，TLS 连接
        // 还是热的，播放器拉流就不用再走一次跨境建连。
        createVideoPlayerEngine(
            kind = engineKind,
            context = context,
            httpClient = (context.applicationContext as? MeBoxApplication)?.container?.httpClient,
            decoderMode = decoderMode,
        )
    }

    val progressCallback by rememberUpdatedState(onProgress)

    // VR 开关/参数变化，以及切换内核后的新实例，都在这里统一下发。
    LaunchedEffect(engine, vrEnabled, resolvedVr) {
        engine.setVrSettings(if (vrEnabled) resolvedVr else null)
    }

    fun setVrEnabled(enabled: Boolean) {
        vrEnabled = enabled
        controlsVisible = true
        controlsTick++
        // 面板保持打开：开启后通常还要继续选投影/立体/显示方式。
        if (enabled) {
            engine.recenterVr()
            hint = "VR 已开启：转动手机改变视角，拖动画面可微调"
        } else {
            hint = "已退出 VR"
        }
    }

    fun updateVrSettings(next: VrSettings) {
        vrSettings = next
        onVrSettingsChange(next)
    }

    fun recenterVr() {
        engine.recenterVr()
        hint = "视角已重新居中"
    }

    fun absolutePosition(): Long = engine.positionMs()

    fun currentDuration(): Long {
        val engineDuration = engine.durationMs()
        return if (engineDuration > 0L) engineDuration else request.durationMs
    }

    fun report(force: Boolean) {
        if (engine.playbackState() == EnginePlaybackState.IDLE) return
        val position = absolutePosition()
        val duration = currentDuration()
        if (position > 0L && duration > 0L) {
            session.recordPosition(position)
            progressCallback(request.mediaId, position, duration, force)
        }
    }

    /**
     * 离开播放页：先把当前位置强制写进本地缓存再返回详情。
     *
     * 详情页会立刻拉一次观看历史来决定「继续播放」。如果这里不先同步上报，
     * 历史请求可能早于退出上报到达服务端，详情就会拿到上一集（甚至 OVA）
     * 的旧记录。本地缓存的写入是同步的，详情随后可以优先使用它。
     */
    fun leavePlayer() {
        report(force = true)
        onBack()
    }

    BackHandler { leavePlayer() }

    fun seekTo(absoluteMs: Long) {
        val duration = currentDuration()
        val target = if (duration > 0L) {
            absoluteMs.coerceIn(0L, duration)
        } else {
            absoluteMs.coerceAtLeast(0L)
        }
        engine.seekTo(target)
        danmakuDiscontinuity++
        positionMs = target
        // 0 是合法位置：用户主动拖回开头后切换内核要停在开头。
        session.recordPosition(target)
        playbackEnded = false
    }

    /**
     * 切换播放内核：保留进度、播放/暂停意图和倍速，从当前位置续播。
     *
     * 先摘掉旧内核的监听并暂停：避免切换过程中两个内核同时出声，也避免旧内核
     * 迟到的回调把新内核的界面状态改回去。旧实例的进度上报和释放由
     * [DisposableEffect] 收尾。
     */
    fun switchEngine(next: PlayerEngineKind, persist: Boolean = true, reason: String? = null) {
        if (next == engineKind) return
        session.beginHandoff(engine.positionMs())
        val handoffDuration = currentDuration()
        engine.listener = null
        runCatching { engine.pause() }
        // 缓冲/出错时 playbackState 可能已经是 IDLE；直接上报最后一次有效进度。
        val handoffPosition = session.lastKnownPositionMs
        if (handoffPosition > 0L && handoffDuration > 0L) {
            progressCallback(request.mediaId, handoffPosition, handoffDuration, true)
        }
        failedMessage = null
        errorIsDecode = false
        playbackEnded = false
        isBuffering = true
        isPlaying = false
        // 旧内核的轨道 id 在新内核里无效，先清空，等新轨道到达再按语义恢复。
        trackState = PlayerTrackState()
        pinnedQuality = null
        bufferedMs = 0L
        // 进度条在新内核报告时长前保持原值，避免瞬间归零。
        durationMs = handoffDuration
        autoSubtitleApplied = false
        switchTarget = next
        switchReason = reason
        hint = "正在切换到 ${next.shortLabel}…"
        engineKind = next
        sessionFallbackEngine = next
        // 自动切换只影响这一次播放，不写回全局偏好；手动切换才记住选择。
        if (persist) onEngineSelected(next)
    }

    /** 重置控制栏自动隐藏计时；控件已可见时也要生效。 */
    fun resetControlsTimer() {
        controlsTick++
    }

    /** 交互同时确保控制栏可见，并重置计时。 */
    fun touchControls() {
        controlsVisible = true
        controlsTick++
    }

    fun togglePlayback() {
        if (playbackEnded) {
            seekTo(0L)
            playbackEnded = false
            session.onUserPlay()
            engine.play()
        } else if (isPlaying) {
            session.onUserPause()
            engine.pause()
        } else {
            session.onUserPlay()
            engine.play()
        }
        touchControls()
    }

    /**
     * 长按临时倍速：默认 2×，长按慢速为 0.5×；长按后横滑按 0.25× 步进调整。
     * 松手恢复用户原本选择的倍速，不写回偏好。
     */
    fun startBoost(base: Float = 2f) {
        if (boostSpeed > 0f) return
        resetControlsTimer()
        boostSpeed = base
        engine.setSpeed(boostSpeed)
    }

    fun adjustBoost(delta: Float) {
        if (!uiSettings.longPressSpeedAdjust) return
        if (boostSpeed <= 0f) return
        boostSpeed = (boostSpeed + delta).coerceIn(0.5f, 4f)
        engine.setSpeed(boostSpeed)
    }

    fun endBoost() {
        if (boostSpeed <= 0f) return
        boostSpeed = 0f
        engine.setSpeed(playbackSpeed)
    }

    fun startHoldSeek(direction: Long) {
        holdSeekJob?.cancel()
        holdSeekJob = touchScope.launch {
            // 长按持续快进/快退：先走 1 秒，随后每 120ms 再走 1 秒。
            while (isActive) {
                seekTo(absolutePosition() + direction * 1_000L)
                delay(120)
            }
        }
    }

    /** 长按左右半屏：按设置执行倍速、慢速、连续快进/快退或上/下一集。 */
    fun beginLongPress(right: Boolean) {
        val action = if (right) uiSettings.longPressRight else uiSettings.longPressLeft
        longPressActive = true
        resetControlsTimer()
        when (action) {
            PlayerLongPressAction.FAST -> startBoost(2f)
            PlayerLongPressAction.SLOW -> startBoost(0.5f)
            PlayerLongPressAction.SEEK_FORWARD -> startHoldSeek(1L)
            PlayerLongPressAction.SEEK_BACK -> startHoldSeek(-1L)
            PlayerLongPressAction.NEXT_EPISODE -> {
                longPressActive = false
                if (request.nextEpisode != null) {
                    playbackEnded = false
                    autoNextCancelled = true
                    onPlayNext()
                } else {
                    hint = "没有下一集"
                }
            }
            PlayerLongPressAction.PREVIOUS_EPISODE -> {
                longPressActive = false
                if (absolutePosition() > 3_000L) {
                    seekTo(0L)
                    hint = "已回到本集开头"
                } else {
                    hint = "已经是本集开头"
                }
            }
        }
    }

    fun endLongPress() {
        longPressActive = false
        holdSeekJob?.cancel()
        holdSeekJob = null
        endBoost()
    }

    /** 错误页与遥控器共用的显式重试入口：重置本轮错误并继续播放。 */
    fun retryPlayback() {
        failedMessage = null
        errorIsDecode = false
        session.onExplicitRetry()
        isBuffering = true
        switchTarget = null
        switchReason = null
        engine.retry()
    }

    /**
     * 内核每次报告轨道时，把用户之前手动选中的音轨/字幕按语义恢复。
     *
     * 已经选中时不下发，避免 LibVLC 的 ESSelected 事件触发反复选择；
     * LibVLC 的选择接口又会同步回调轨道变化，这里再加一层重入保护。
     */
    var restoringTrackSelection = false
    fun restoreTrackSelections(tracks: EngineTracks) {
        if (restoringTrackSelection) return
        restoringTrackSelection = true
        try {
            if (audioSelection is PlaybackTrackSelection.Track) {
                val matched = PlaybackTrackRestorer.match(
                    tracks = tracks.audio,
                    type = EngineTrackType.AUDIO,
                    selection = audioSelection,
                )
                if (matched != null) {
                    audioMissToken = 0
                    if (!matched.selected) engine.selectAudioTrack(matched.id)
                } else if (tracks.audio.isNotEmpty() && !audioMissNotified && audioMissToken == 0) {
                    // 音轨可能随解析逐条上报，先给一点时间再判定没找到。
                    audioMissToken = 1
                }
            }
            when (val selection = subtitleSelection) {
                is PlaybackTrackSelection.Off -> {
                    if (tracks.text.any { it.selected }) engine.selectTextTrack(null)
                }
                is PlaybackTrackSelection.Track -> {
                    val matched = PlaybackTrackRestorer.match(
                        tracks = tracks.text,
                        type = EngineTrackType.TEXT,
                        selection = selection,
                    )
                    if (matched != null) {
                        // 找到目标字幕（可能是迟到的外挂字幕），取消待发提示。
                        subtitleMissToken = 0
                        if (!matched.selected) engine.selectTextTrack(matched.id)
                    } else if (tracks.text.isNotEmpty() && !subtitleMissNotified && subtitleMissToken == 0) {
                        // 外挂字幕还在异步下载时先不判定失败；稍后仍未匹配再提示。
                        subtitleMissToken = 1
                    }
                }
                PlaybackTrackSelection.Auto -> Unit
            }
        } finally {
            restoringTrackSelection = false
        }
    }


    // 播放页参考真实截图保留状态栏，只隐藏导航栏；退出时恢复。
    DisposableEffect(activity, view, isTv) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (isTv) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            window?.statusBarColor = android.graphics.Color.TRANSPARENT
            window?.navigationBarColor = android.graphics.Color.TRANSPARENT
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.hide(WindowInsetsCompat.Type.navigationBars())
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (!isTv) {
                window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
                controller?.isAppearanceLightStatusBars = true
                controller?.isAppearanceLightNavigationBars = true
            }
        }
    }

    // 播放器亮度只在播放页生效，退出时恢复系统亮度。
    val originalBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: -1f
    }
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val attributes = window.attributes
            attributes.screenBrightness = originalBrightness
            window.attributes = attributes
        }
    }

    val errorCallback by rememberUpdatedState(onError)

    DisposableEffect(engine) {
        onDispose {
            report(force = true)
            engine.release()
        }
    }

    val autoPipEnabled by rememberUpdatedState(uiSettings.autoPictureInPicture)
    val playingForPip by rememberUpdatedState(isPlaying)
    val lockedForPip by rememberUpdatedState(locked)
    DisposableEffect(engine, lifecycleOwner, activity, pipAvailable, isTv) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                report(force = true)
                // 参考 CapyPlayer 的「离开自动进入画中画」：退到后台前先切 PiP，
                // 再暂停本地播放会话，避免回来时状态错乱。
                var pipEntered = false
                if (autoPipEnabled && pipAvailable && !isTv && !lockedForPip && playingForPip) {
                    pipEntered = runCatching {
                        activity?.enterPictureInPictureMode(
                            PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build(),
                        ) == true
                    }.getOrDefault(false)
                }
                // 进入画中画时继续播放；普通退到后台仍按用户可见的暂停处理。
                if (!pipEntered) {
                    session.onUserPause()
                    engine.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(engine, request) {
        engine.listener = object : VideoPlayerEngineListener {
            override fun onMediaReloaded() {
                // 内核换中转地址/换解码方式后重新装载了媒体，轨道选择需要重做。
                autoSubtitleApplied = false
                danmakuDiscontinuity++
            }

            override fun onError(message: String, decodeRelated: Boolean) {
                // 系统解码器不支持该编码时，直接换到 LibVLC 续播，不把错误糊到用户脸上。
                if (PlayerEngineKind.shouldAutoFallback(engineKind, decodeRelated, autoFallbackAttempted)) {
                    val fallback = PlayerEngineKind.automaticFallback(engineKind) ?: return
                    autoFallbackAttempted = true
                    switchEngine(
                        next = fallback,
                        persist = false,
                        reason = "当前内核无法解码该片源",
                    )
                    return
                }
                switchTarget = null
                switchReason = null
                errorIsDecode = decodeRelated
                failedMessage = message
                errorCallback(message)
            }

            override fun onPlaybackStateChanged(state: EnginePlaybackState) {
                isBuffering = state == EnginePlaybackState.BUFFERING
                playbackEnded = state == EnginePlaybackState.ENDED
                if (state == EnginePlaybackState.READY) {
                    durationMs = currentDuration()
                    // 新内核真正进入就绪后才算切换成功。
                    if (switchTarget != null && switchTarget == engineKind) {
                        val reason = switchReason
                        switchTarget = null
                        switchReason = null
                        hint = reason?.let { "$it，已切换到 ${engineKind.shortLabel}" }
                            ?: "已切换到 ${engineKind.shortLabel}"
                    }
                }
                if (state == EnginePlaybackState.ENDED) {
                    report(force = true)
                    controlsVisible = true
                }
            }

            override fun onPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) report(force = true)
            }

            override fun onSpeedChanged(speed: Float) {
                playbackSpeed = speed
            }

            override fun onTracksChanged(tracks: EngineTracks) {
                val video = tracks.video.map { it.toChoice() }
                val audio = tracks.audio.map { it.toChoice() }
                val text = tracks.text.map { it.toChoice() }
                trackState = PlayerTrackState(
                    video = video,
                    audio = audio,
                    text = text,
                    selectedAudioLabel = audio.firstOrNull { it.selected }?.label,
                    selectedTextLabel = text.firstOrNull { it.selected }?.label,
                )
                pinnedQuality = pinnedQuality?.takeIf { pinned -> video.any { it.id == pinned.id } }
                // 换内核或内部重载后，按名称/语言找回用户选中的音轨和字幕。
                restoreTrackSelections(tracks)
                // 首次拿到字幕轨时按设置自动选一条；用户手动操作过就不再干预。
                if (!autoSubtitleApplied && !userPickedSubtitles && text.isNotEmpty()) {
                    autoSubtitleApplied = true
                    when {
                        engineKind == PlayerEngineKind.EXO && subtitlePreference.autoLoad -> {
                            SubtitleSelector.choose(tracks.text, subtitlePreference)?.let { chosen ->
                                subtitlesOff = false
                                engine.selectTextTrack(chosen.id)
                            }
                        }
                        engineKind == PlayerEngineKind.EXO -> {
                            // 关闭自动加载时明确禁用字幕轨，避免内核按默认标记自动选中。
                            subtitlesOff = true
                            engine.selectTextTrack(null)
                        }
                        engineKind == PlayerEngineKind.VLC && !subtitlePreference.autoLoad -> {
                            // LibVLC 会自己挑一条内嵌字幕；关闭自动加载时明确关掉。
                            subtitlesOff = true
                            engine.selectTextTrack(null)
                        }
                    }
                }
                // 用户没手动选过字幕时，内核自动选中的字幕要反映到界面；
                // 手动关闭的字幕不能被这里重新打开。
                if (!userPickedSubtitles && text.any { it.selected }) {
                    subtitlesOff = false
                }
            }
        }
        onDispose { engine.listener = null }
    }

    // 字幕轨可能比视频晚到（LibVLC 外挂字幕异步下载），给一点时间再提示没找到。
    LaunchedEffect(subtitleMissToken) {
        if (subtitleMissToken == 0) return@LaunchedEffect
        delay(2_000)
        if (!subtitleMissNotified) {
            subtitleMissNotified = true
            hint = "新内核没有找到原来的字幕，可在字幕菜单重新选择"
        }
        subtitleMissToken = 0
    }

    // 音轨同理：等本轮轨道全部上报完，仍未匹配才提示。
    LaunchedEffect(audioMissToken) {
        if (audioMissToken == 0) return@LaunchedEffect
        delay(2_000)
        if (!audioMissNotified) {
            audioMissNotified = true
            hint = "新内核没有找到原来的音轨，可在音轨菜单重新选择"
        }
        audioMissToken = 0
    }

    // 只按媒体/地址变化重新装载，后台补拉字幕不会打断正在播放的画面。
    LaunchedEffect(engine, initialKey) {
        // 切换内核时从会话记录的续播点开始；首次进入用请求里的进度。
        val startAt = session.consumeResumePosition()
        positionMs = startAt
        // 用户有明确字幕选择时，先禁止内核的异步自动选中，再由轨道事件恢复。
        if (subtitleSelection !is PlaybackTrackSelection.Auto) {
            engine.holdSubtitleAutoSelection()
        }
        engine.setMedia(
            request.toEngineMedia(subtitlePreference),
            startAt,
            autoPlay = session.playWhenReady,
        )
        // 切换内核后沿用用户已经选好的倍速和画面缩放。
        engine.setSpeed(playbackSpeed)
        engine.setResizeMode(resizeMode)
    }

    LaunchedEffect(
        engine,
        uiSettings.subtitleVerticalPosition,
        uiSettings.subtitleScale,
        uiSettings.subtitleTextColor,
        uiSettings.subtitleOutline,
    ) {
        engine.setSubtitleStyle(
            EngineSubtitleStyle(
                verticalPosition = uiSettings.subtitleVerticalPosition,
                scale = uiSettings.subtitleScale,
                textColor = uiSettings.subtitleTextColor.toInt(),
                outline = uiSettings.subtitleOutline,
            ),
        )
    }

    LaunchedEffect(engine, request.mediaId) {
        var lastTick = 0L
        while (isActive) {
            delay(500)
            val position = absolutePosition()
            val duration = currentDuration()
            val state = engine.playbackState()
            if (state != EnginePlaybackState.IDLE && position > 0L) {
                positionMs = position
                session.recordPosition(position)
            }
            durationMs = duration
            bufferedMs = engine.bufferedMs()
            val now = SystemClock.elapsedRealtime()
            if (now - lastTick >= 5_000L &&
                state != EnginePlaybackState.IDLE &&
                position > 0L &&
                duration > 0L
            ) {
                lastTick = now
                progressCallback(request.mediaId, position, duration, false)
            }
        }
    }

    // 控制栏自动隐藏：播放中 3 秒无操作后收起。暂停、结束、拖动进度、
    // 长按倍速和面板打开期间保持可见/不打断。
    LaunchedEffect(
        controlsVisible,
        controlsTick,
        isPlaying,
        isBuffering,
        playbackEnded,
        locked,
        seekDragging,
        boostSpeed,
        longPressActive,
        tvFocusMode,
        settingsSection,
        settingsHubVisible,
        mediaInfoVisible,
        sleepTimerVisible,
        episodeStripVisible,
        quickMenu,
        subtitleSettingsVisible,
    ) {
        if (controlsVisible &&
            isPlaying &&
            !isBuffering &&
            !playbackEnded &&
            !locked &&
            !seekDragging &&
            !tvFocusMode &&
            boostSpeed == 0f &&
            !longPressActive &&
            settingsSection == null &&
            !settingsHubVisible &&
            !mediaInfoVisible &&
            !sleepTimerVisible &&
            !episodeStripVisible &&
            quickMenu == null &&
            !subtitleSettingsVisible
        ) {
            delay(3_000)
            controlsVisible = false
        }
    }
    // 锁屏后只保留解锁按钮，3 秒后淡出；TV 端保持可见，保证遥控器随时能解锁。
    LaunchedEffect(locked, lockBarVisible, isTv) {
        if (locked && lockBarVisible && !isTv) {
            delay(3_000)
            lockBarVisible = false
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_800)
            hint = null
        }
    }
    // 定时关闭只作用于当前播放会话：到点暂停并清除计时。
    LaunchedEffect(sleepTimerDeadline) {
        val deadline = sleepTimerDeadline ?: return@LaunchedEffect
        while (isActive) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                sleepTimerDeadline = null
                sleepTimerMinutes = 0
                if (isPlaying) {
                    session.onUserPause()
                    engine.pause()
                }
                hint = "定时关闭已到，播放已暂停"
                return@LaunchedEffect
            }
            delay(remaining.coerceAtMost(1_000L))
        }
    }
    // 本机解码能力提示只在起播时出现一次，不阻塞播放；LibVLC 自带解码器，
    // 系统 MediaCodec 的结论不适用于它。
    LaunchedEffect(request.decoderNote, engineKind) {
        val note = request.decoderNote
        if (engineKind == PlayerEngineKind.EXO && !note.isNullOrBlank()) hint = note
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(900)
            seekFeedback = null
        }
    }
    LaunchedEffect(levelFeedback, levelFraction) {
        if (levelFeedback != null) {
            delay(1_200)
            levelFeedback = null
        }
    }
    // 播放结束后自动接下一集；关闭连续播放、用户取消或没有下一集时不倒计时。
    LaunchedEffect(
        playbackEnded,
        autoNextCancelled,
        request.nextEpisode?.mediaId,
        uiSettings.continuousPlay,
    ) {
        if (!uiSettings.continuousPlay || !playbackEnded || autoNextCancelled ||
            request.nextEpisode == null
        ) {
            autoNextCountdown = 0
            return@LaunchedEffect
        }
        for (remaining in AUTO_NEXT_SECONDS downTo 1) {
            autoNextCountdown = remaining
            delay(1_000)
        }
        autoNextCountdown = 0
        if (playbackEnded && !autoNextCancelled) {
            playbackEnded = false
            onPlayNext()
        }
    }

    // 自动匹配到多个作品时直接弹出候选面板，和 Web 播放页保持一致。
    // 以候选数量为 key，用户关闭后不会反复弹出。
    LaunchedEffect(danmaku.candidates.size, danmaku.loadedMediaId) {
        if (danmaku.candidates.isNotEmpty() &&
            settingsSection == null &&
            !settingsHubVisible &&
            !mediaInfoVisible &&
            !sleepTimerVisible &&
            !episodeStripVisible &&
            quickMenu == null &&
            !subtitleSettingsVisible
        ) {
            settingsSection = PlayerSettingsSection.DANMAKU
        }
    }

    fun adjustBrightness(delta: Float) {
        val window = activity?.window ?: return
        val attributes = window.attributes
        val current = if (attributes.screenBrightness < 0f) 0.5f else attributes.screenBrightness
        val next = (current + delta).coerceIn(0.05f, 1f)
        attributes.screenBrightness = next
        window.attributes = attributes
        levelFeedback = PlayerLevel.BRIGHTNESS
        levelFraction = next
        resetControlsTimer()
    }

    fun adjustVolume(delta: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (current + delta * max).roundToInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        levelFeedback = PlayerLevel.VOLUME
        levelFraction = next.toFloat() / max
        resetControlsTimer()
    }

    fun chooseTrack(choice: TrackChoice) {
        when (choice.type) {
            EngineTrackType.VIDEO -> engine.selectVideoTrack(choice.id)
            EngineTrackType.AUDIO -> {
                // 记住语义身份（名称+语言），切内核后按它找回同一条音轨。
                audioSelection = PlaybackTrackSelection.Track(
                    PlaybackTrackRef(label = choice.label, language = choice.lang),
                )
                engine.selectAudioTrack(choice.id)
            }
            EngineTrackType.TEXT -> {
                userPickedSubtitles = true
                subtitlesOff = false
                subtitleSelection = PlaybackTrackSelection.Track(
                    PlaybackTrackRef(label = choice.label, language = choice.lang),
                )
                // 本次内核之后的重载也保持这条手动选择。
                engine.holdSubtitleAutoSelection()
                engine.selectTextTrack(choice.id)
            }
        }
    }

    fun autoSelect(type: EngineTrackType) {
        when (type) {
            EngineTrackType.VIDEO -> engine.selectVideoTrack(null)
            EngineTrackType.AUDIO -> engine.selectAudioTrack(null)
            EngineTrackType.TEXT -> engine.selectTextTrack(null)
        }
    }

    fun disableSubtitles() {
        userPickedSubtitles = true
        subtitlesOff = true
        // 明确关闭是一个跨内核、跨内部重载都要保持的选择。
        subtitleSelection = PlaybackTrackSelection.Off
        engine.holdSubtitleAutoSelection()
        engine.selectTextTrack(null)
    }

    fun toggleDanmakuFromControls() {
        touchControls()
        if (danmaku.candidates.isNotEmpty()) {
            // 匹配到多个作品时，点击弹幕按钮直接打开候选面板。
            settingsSection = PlayerSettingsSection.DANMAKU
        } else {
            onDanmakuEnabledChange(!danmaku.enabled)
        }
    }

    fun playNextNow() {
        touchControls()
        playbackEnded = false
        autoNextCancelled = true
        onPlayNext()
    }

    fun lockScreen() {
        locked = true
        controlsVisible = false
        lockBarVisible = true
        tvFocusMode = false
        controlsTick++
        hint = "屏幕已锁定"
    }

    fun toggleOrientation() {
        val currentlyLandscape =
            activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        orientationOverride = if (currentlyLandscape) {
            PlayerOrientation.PORTRAIT
        } else {
            PlayerOrientation.LANDSCAPE
        }
        touchControls()
    }

    fun unlockScreen() {
        locked = false
        lockBarVisible = false
        controlsVisible = true
        tvFocusMode = false
        controlsTick++
        hint = "屏幕已解锁"
    }

    fun enterPictureInPicture() {
        if (!pipAvailable) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { activity?.enterPictureInPictureMode(params) }
    }

    val duration = durationMs.coerceAtLeast(request.durationMs)
    val seekBackStep = uiSettings.seekBackwardMs.coerceIn(1_000L, 60_000L)
    val seekForwardStep = uiSettings.seekForwardMs.coerceIn(1_000L, 60_000L)
    val speedLabel = playerSpeedLabel(playbackSpeed)
    val qualityLabel = pinnedQuality?.label ?: "自动"
    val subtitleLabel = when {
        subtitlesOff -> "关闭"
        trackState.selectedTextLabel != null -> trackState.selectedTextLabel!!
        else -> "默认"
    }
    val imageSubtitleNote = request.imageSubtitles.takeIf { it.isNotEmpty() }?.let { labels ->
        val note = if (engineKind == PlayerEngineKind.VLC) {
            "LibVLC 对图片字幕的显示取决于片源封装，可能无法完整还原。"
        } else {
            "系统解码内核无法渲染，可切换到 LibVLC 内核尝试。"
        }
        "检测到 ${labels.size} 条图片字幕（${labels.joinToString("、")}）。$note"
    }
    val assSubtitleCount = request.subtitles.count { it.hasAssStyle }
    val subtitleNote = buildList {
        imageSubtitleNote?.let(::add)
        if (assSubtitleCount > 0) {
            add(
                when (engineKind) {
                    PlayerEngineKind.VLC ->
                        "ASS 字幕由 LibVLC 的 libass 按文件自带样式渲染" +
                            "（字体、颜色、描边、定位和淡入淡出）。"

                    PlayerEngineKind.EXO ->
                        "ASS 字幕会加载原文件：系统内核算行内样式（颜色、粗体、斜体、位置），" +
                            "字体、描边和特效需要 LibVLC 内核。"
                },
            )
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    val danmakuLabel = when {
        !danmaku.enabled -> "已关闭"
        danmaku.loading -> "匹配中…"
        danmaku.comments.isNotEmpty() -> "${danmaku.comments.size} 条"
        danmaku.candidates.isNotEmpty() -> "待选择"
        else -> "自动匹配"
    }
    val audioLabel = trackState.selectedAudioLabel ?: "默认"
    // 参考播放器左下角的技术信息：内核 + 当前画质/码率 + 剧集。
    val engineTag = when (engineKind) {
        PlayerEngineKind.EXO -> "EXO"
        PlayerEngineKind.VLC -> "VLC"
    }
    val selectedVideo = trackState.video.firstOrNull { it.selected }
    val displayQuality = pinnedQuality?.label ?: selectedVideo?.label ?: qualityLabel
    val infoLine = buildList {
        add(engineTag)
        displayQuality.takeIf { it.isNotBlank() && it != "自动" }?.let(::add)
        request.subtitle?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
    // 参考播放器的技术信息是「内核 容器」两个短 token。
    val containerTag = remember(request.streamUrl) {
        val path = request.streamUrl.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext.uppercase() else "STRM"
    }
    val techInfo = "$engineTag $containerTag"
    val episodeTitle = request.subtitle
    val sleepRemainingMinutes = sleepTimerDeadline?.let { deadline ->
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        ((remaining + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    // ── 电视遥控器 ──
    // 两段式焦点：
    // - 根节点持有焦点时，方向键映射为快捷操作（左右 seek、上下显隐、OK 播放暂停）；
    // - 按方向上键进入控制栏焦点模式，焦点落到播放键，方向键在按钮间移动，OK 确认；
    //   此时按返回退出焦点模式，再按返回收起控制栏 / 退出播放页。
    val tvFocusRequester = remember { FocusRequester() }
    val tvPlayFocus = remember { FocusRequester() }
    val tvUnlockFocus = remember { FocusRequester() }

    // 遥控器操作提示只在控制栏刚出现或刚切到按钮焦点模式时显示几秒，
    // 不再长期叠在画面上方。
    var tvHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isTv, controlsVisible, tvFocusMode, locked) {
        if (isTv && controlsVisible && !locked) {
            tvHintVisible = true
            delay(6_000)
            tvHintVisible = false
        } else {
            tvHintVisible = false
        }
    }

    LaunchedEffect(
        isTv,
        controlsVisible,
        tvFocusMode,
        locked,
        settingsSection,
        settingsHubVisible,
        mediaInfoVisible,
        sleepTimerVisible,
        episodeStripVisible,
        quickMenu,
        subtitleSettingsVisible,
        failedMessage,
        playbackEnded,
    ) {
        if (!isTv) return@LaunchedEffect
        when {
            locked -> repeat(5) {
                delay(80)
                if (runCatching { tvUnlockFocus.requestFocus() }.isSuccess) {
                    return@LaunchedEffect
                }
            }

            // 错误页、结束页和面板有自己的焦点管理，不抢它们的焦点。
            failedMessage != null || playbackEnded || settingsSection != null ||
                settingsHubVisible || mediaInfoVisible || sleepTimerVisible ||
                episodeStripVisible || quickMenu != null ||
                subtitleSettingsVisible -> Unit

            tvFocusMode && controlsVisible -> repeat(5) {
                delay(60)
                if (runCatching { tvPlayFocus.requestFocus() }.isSuccess) {
                    return@LaunchedEffect
                }
            }

            else -> repeat(3) {
                delay(80)
                if (runCatching { tvFocusRequester.requestFocus() }.isSuccess) {
                    return@LaunchedEffect
                }
            }
        }
    }

    /** 相对当前位置快进/快退，并显示中央目标时间提示。 */
    fun seekBy(deltaMs: Long) {
        val target = PlaybackPlan.seekTarget(absolutePosition(), deltaMs, currentDuration())
        seekTo(target)
        seekFeedback = SeekFeedbackState(deltaMs >= 0, deltaMs, target)
        touchControls()
    }

    fun tvSeek(deltaMs: Long) = seekBy(deltaMs)

    fun handleTvKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!isTv) return false
        val code = event.nativeKeyEvent.keyCode
        // 设置面板打开时把方向键/确认键完全还给面板，否则 KeyUp 被根节点吞掉会导致
        // 面板里的 clickable 选项无法触发。
        if (settingsSection != null || settingsHubVisible ||
            mediaInfoVisible || sleepTimerVisible || episodeStripVisible ||
            quickMenu != null
        ) {
            return false
        }
        // 错误页 / 结束页 / 锁定态各自持有焦点，根节点不拦截按键。
        if (failedMessage != null || playbackEnded || locked) return false

        // 媒体键在两种焦点模式下都直接生效，不依赖当前焦点位置。
        if (event.type == KeyEventType.KeyDown) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlayback()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    tvSeek(-seekBackStep)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    tvSeek(seekForwardStep)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (request.nextEpisode != null) {
                        playNextNow()
                        return true
                    }
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    tvSeek(-absolutePosition())
                    return true
                }
            }
        }

        // 控制栏焦点模式：方向键交给焦点系统在按钮间移动，只拦截菜单键。
        // 注意不能再吞 KeyUp，否则焦点按钮收不到确认键的抬起事件。
        if (tvFocusMode) {
            if (event.type == KeyEventType.KeyDown &&
                (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO)
            ) {
                settingsHubVisible = true
                return true
            }
            return false
        }

        if (event.type == KeyEventType.KeyUp) {
            return code in TV_CONFIRM_KEYS
        }

        return when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayback()
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                tvSeek(-seekBackStep)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                tvSeek(seekForwardStep)
                true
            }

            // 上键：显示控制栏并进入按钮焦点模式，焦点落在播放键。
            KeyEvent.KEYCODE_DPAD_UP -> {
                controlsVisible = true
                tvFocusMode = true
                controlsTick++
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                controlsVisible = false
                tvFocusMode = false
                controlsTick++
                true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                settingsHubVisible = true
                true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (request.nextEpisode != null) {
                    playNextNow()
                    true
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                tvSeek(-absolutePosition())
                true
            }

            else -> false
        }
    }

    BackHandler(enabled = subtitleSettingsVisible) { subtitleSettingsVisible = false }
    BackHandler(enabled = quickMenu != null) { quickMenu = null }
    BackHandler(enabled = episodeStripVisible) { episodeStripVisible = false }
    BackHandler(enabled = locked) { unlockScreen() }
    // TV 焦点模式：返回先退出焦点模式并收起控制栏，回到方向键快捷操作。
    BackHandler(enabled = isTv && tvFocusMode && !locked) {
        tvFocusMode = false
        controlsVisible = false
        controlsTick++
        runCatching { tvFocusRequester.requestFocus() }
    }
    // TV：控制栏可见时先收起控制栏，再按一次才退出播放页。
    BackHandler(enabled = isTv && controlsVisible && !locked && !tvFocusMode) {
        controlsVisible = false
        tvFocusMode = false
        controlsTick++
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (isTv) {
                    Modifier
                        .focusRequester(tvFocusRequester)
                        .onPreviewKeyEvent { event -> handleTvKey(event) }
                        .focusable()
                } else {
                    Modifier
                },
            ),
    ) {
        // 横屏/竖屏两套控件布局：与 CapyPlayer 的播放器一致，宽屏把信息放在
        // 左下、操作胶囊放在右下；竖屏把标题放在左上、操作行沉到底部。
        val landscape = maxWidth > maxHeight
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boostStepPx = with(density) { 40.dp.toPx() }
        AndroidView(
            factory = { ctx -> FrameLayout(ctx) },
            update = { frame ->
                engine.setResizeMode(resizeMode)
                engine.attach(frame)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 弹幕层位于视频之上、手势与控制层之下，不拦截播放器点击。
        // VR 是沉浸式视图，弹幕会横跨两块镜片，开启 VR 时暂时隐藏。
        DanmakuOverlay(
            comments = danmaku.comments,
            enabled = danmaku.enabled && !vrEnabled,
            opacity = danmaku.opacity,
            fontScale = danmaku.fontScale,
            areaFraction = danmaku.area,
            positionProvider = { engine.positionMs() },
            playing = isPlaying && !isBuffering && !playbackEnded && failedMessage == null,
            playbackSpeed = playbackSpeed,
            playbackKey = engine,
            discontinuity = danmakuDiscontinuity,
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层位于控制层之下：控制栏可见时，按钮优先消费点击。
        if (!locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerTouch(
                        enabled = true,
                        vrMode = vrEnabled,
                        onVrDrag = { dx, dy -> engine.rotateVr(dx, dy) },
                        onTap = {
                            if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                touchControls()
                            }
                        },
                        onDoubleTap = { forward ->
                            val action = if (forward) {
                                uiSettings.doubleTapRight
                            } else {
                                uiSettings.doubleTapLeft
                            }
                            when (action) {
                                PlayerDoubleTapAction.SEEK_FORWARD -> seekBy(seekForwardStep)
                                PlayerDoubleTapAction.SEEK_BACK -> seekBy(-seekBackStep)
                                PlayerDoubleTapAction.PLAY_PAUSE -> togglePlayback()
                                PlayerDoubleTapAction.NONE -> touchControls()
                            }
                        },
                        onSeekDragStart = {
                            dragBaseMs = absolutePosition()
                            dragDeltaMs = 0L
                            seekDragging = true
                            touchControls()
                        },
                        onSeekDrag = { fraction ->
                            dragDeltaMs += (fraction * 120_000).toLong()
                            val target = PlaybackPlan.seekTarget(dragBaseMs, dragDeltaMs, currentDuration())
                            seekFeedback = SeekFeedbackState(dragDeltaMs >= 0, dragDeltaMs, target)
                        },
                        onSeekDragEnd = {
                            if (dragDeltaMs != 0L) {
                                seekTo(PlaybackPlan.seekTarget(dragBaseMs, dragDeltaMs, currentDuration()))
                            }
                            dragDeltaMs = 0L
                            seekDragging = false
                            resetControlsTimer()
                        },
                        levelEnabled = uiSettings.slideBrightnessVolume,
                        onVolumeDelta = { delta -> adjustVolume(delta) },
                        onBrightnessDelta = { delta -> adjustBrightness(delta) },
                        onLongPressStart = { right -> beginLongPress(right) },
                        onLongPressChange = { deltaPx ->
                            adjustBoost(deltaPx / boostStepPx * 0.25f)
                        },
                        onLongPressEnd = { endLongPress() },
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures { lockBarVisible = !lockBarVisible }
                    },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopChrome(
                landscape = landscape,
                title = title,
                serverLabel = serverLabel,
                settings = uiSettings,
                pictureInPictureAvailable = pipAvailable && !isTv,
                danmakuEnabled = danmaku.enabled,
                onBack = ::leavePlayer,
                onPictureInPicture = { enterPictureInPicture() },
                onEngine = {
                    touchControls()
                    quickMenu = PlayerQuickMenu.ENGINE
                },
                onOpenDanmaku = {
                    touchControls()
                    quickMenu = PlayerQuickMenu.DANMAKU
                },
                onOpenScale = { settingsSection = PlayerSettingsSection.SCALE },
                onSettings = { settingsHubVisible = true },
                onInfo = { mediaInfoVisible = true },
                onCast = { hint = "投屏功能尚未接入" },
            )
        }

        if (isTv && tvHintVisible && controlsVisible && !locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 128.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (tvFocusMode) {
                            "方向键选择按钮 · OK 确认"
                        } else {
                            "▲ 显示控制栏并选择按钮 · OK 播放/暂停"
                        },
                        color = PlayerSkin.TextSecondary,
                        style = PlayerSkin.Overline,
                    )
                    Text(
                        if (tvFocusMode) {
                            "返回 收起控制栏 · 菜单键 播放设置"
                        } else {
                            "未选中按钮时 ◀ ▶ 快退/快进 · 菜单键 播放设置"
                        },
                        color = PlayerSkin.TextTertiary,
                        style = PlayerSkin.Overline,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !isTv,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerSideChrome(
                landscape = landscape,
                settings = uiSettings,
                locked = false,
                onLock = ::lockScreen,
                onRotate = ::toggleOrientation,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomChrome(
                landscape = landscape,
                seriesTitle = title,
                episodeTitle = episodeTitle,
                techInfo = techInfo,
                positionMs = positionMs,
                durationMs = duration,
                bufferedMs = bufferedMs,
                isPlaying = isPlaying && !playbackEnded,
                hasNext = request.nextEpisode != null,
                speedLabel = speedLabel,
                settings = uiSettings,
                onSeek = { position ->
                    seekTo(position)
                    resetControlsTimer()
                },
                onSeekDragStateChange = { dragging ->
                    seekDragging = dragging
                    if (dragging) touchControls()
                },
                onTogglePlay = { togglePlayback() },
                onSeekBack = { seekBy(-seekBackStep) },
                onSeekForward = { seekBy(seekForwardStep) },
                onPlayNext = ::playNextNow,
                onOpenSpeed = {
                    touchControls()
                    quickMenu = PlayerQuickMenu.SPEED
                },
                onOpenQuality = { settingsSection = PlayerSettingsSection.QUALITY },
                onOpenAudio = {
                    touchControls()
                    quickMenu = PlayerQuickMenu.AUDIO
                },
                onOpenSubtitle = {
                    touchControls()
                    quickMenu = PlayerQuickMenu.SUBTITLE
                },
                onOpenEpisodes = {
                    touchControls()
                    if (request.episodes.isEmpty()) {
                        hint = "当前没有分集信息"
                    } else {
                        episodeStripVisible = !episodeStripVisible
                    }
                },
                onDownload = { hint = "当前版本暂不支持视频下载" },
                playFocusRequester = if (isTv) tvPlayFocus else null,
                modifier = Modifier.graphicsLayer {
                    alpha = if (episodeStripVisible) 0.35f else 1f
                },
            )
        }

        quickMenu?.let { menu ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 用 pointerInput 而不是 clickable：clickable 会变成可聚焦节点，
                    // 遥控器焦点可能落到整块透明遮罩上。
                    .pointerInput(Unit) {
                        detectTapGestures { quickMenu = null }
                    },
            )
            PlayerQuickMenuCard(
                menu = menu,
                engineLabel = engineKind.shortLabel,
                speed = playbackSpeed,
                audioTracks = trackState.audio.map {
                    PlayerQuickTrack(
                        id = it.id,
                        label = it.label,
                        selected = it.selected,
                        detail = it.lang,
                    )
                },
                subtitleOff = subtitlesOff,
                subtitleTracks = trackState.text.map {
                    PlayerQuickTrack(
                        id = it.id,
                        label = it.label,
                        selected = it.selected && !subtitlesOff,
                        detail = it.lang,
                    )
                },
                danmakuEnabled = danmaku.enabled,
                danmakuLoading = danmaku.loading,
                danmakuMatchLabel = danmaku.matchLabel,
                danmakuCount = danmaku.comments.size,
                osdVisible = osdVisible,
                onSelectSpeed = { value ->
                    playbackSpeed = value
                    engine.setSpeed(value)
                    quickMenu = null
                },
                onSelectAudio = { id ->
                    if (id == null) {
                        audioSelection = PlaybackTrackSelection.Auto
                        engine.selectAudioTrack(null)
                    } else {
                        trackState.audio.firstOrNull { it.id == id }?.let(::chooseTrack)
                    }
                    quickMenu = null
                },
                onSelectSubtitle = { id ->
                    if (id == null) {
                        disableSubtitles()
                    } else {
                        trackState.text.firstOrNull { it.id == id }?.let(::chooseTrack)
                    }
                    quickMenu = null
                },
                onOpenSubtitleSearch = {
                    quickMenu = null
                    settingsSection = PlayerSettingsSection.SUBTITLE
                },
                onOpenSubtitleSettings = {
                    quickMenu = null
                    subtitleSettingsVisible = true
                },
                onToggleDanmaku = { onDanmakuEnabledChange(it) },
                onOpenDanmakuSearch = {
                    quickMenu = null
                    settingsSection = PlayerSettingsSection.DANMAKU
                },
                onOpenDanmakuSettings = {
                    quickMenu = null
                    settingsSection = PlayerSettingsSection.DANMAKU
                },
                onOpenEngine = {
                    quickMenu = null
                    settingsSection = PlayerSettingsSection.ENGINE
                },
                onToggleOsd = {
                    osdVisible = !osdVisible
                    quickMenu = null
                },
                modifier = Modifier
                    .align(
                        if (menu == PlayerQuickMenu.ENGINE) {
                            Alignment.TopEnd
                        } else {
                            Alignment.BottomEnd
                        },
                    )
                    .padding(
                        end = if (landscape) 64.dp else 16.dp,
                        top = if (menu == PlayerQuickMenu.ENGINE) {
                            if (landscape) 84.dp else 78.dp
                        } else {
                            0.dp
                        },
                        bottom = if (menu == PlayerQuickMenu.ENGINE) {
                            0.dp
                        } else {
                            if (landscape) 132.dp else 150.dp
                        },
                    ),
            )
        }

        if (osdVisible) {
            PlayerOsdOverlay(
                engine = engineKind.shortLabel,
                quality = displayQuality,
                audio = audioLabel,
                subtitle = subtitleLabel,
                positionMs = positionMs,
                durationMs = duration,
                bufferedMs = bufferedMs,
                host = serverLabel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = if (landscape) 64.dp else 16.dp,
                        top = if (landscape) 140.dp else 100.dp,
                    ),
            )
        }

        if (episodeStripVisible) {
            // 点空白处关闭分集条；卡片自己消费点击。
            // 同样不能用 clickable，否则遥控器焦点会停在这层全屏遮罩上。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { episodeStripVisible = false }
                    },
            )
            PlayerEpisodeStrip(
                episodes = request.episodes,
                currentMediaId = request.mediaId,
                onSelect = { episode ->
                    episodeStripVisible = false
                    onSelectEpisode(episode.mediaId)
                },
                landscape = landscape,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (isBuffering && failedMessage == null) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                PlayerBufferingIndicator()
            }
        }

        seekFeedback?.let { feedback ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlayerSeekFeedback(
                    forward = feedback.forward,
                    deltaMs = feedback.deltaMs,
                    targetMs = feedback.targetMs,
                )
            }
        }

        boostSpeed.takeIf { it > 0f && uiSettings.showLongPressHint }?.let { speed ->
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                PlayerBoostFeedback(speed = speed)
            }
        }

        levelFeedback?.let { level ->
            Box(
                modifier = Modifier
                    .align(
                        if (level == PlayerLevel.BRIGHTNESS) {
                            Alignment.CenterStart
                        } else {
                            Alignment.CenterEnd
                        },
                    )
                    .padding(horizontal = 18.dp),
            ) {
                PlayerLevelFeedback(level = level, fraction = levelFraction)
            }
        }

        hint?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlayerHint(message)
            }
        }

        AnimatedVisibility(
            visible = locked && lockBarVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
        ) {
            PlayerLockButton(
                onUnlock = ::unlockScreen,
                focusRequester = if (isTv) tvUnlockFocus else null,
            )
        }

        failedMessage?.let { message ->
            val alternative = PlayerEngineKind.entries.firstOrNull { it != engineKind }
            PlayerErrorOverlay(
                message = message,
                explanation = if (errorIsDecode) {
                    "当前内核无法解码该片源，可切换到另一个内核重试"
                } else {
                    "检查网络或地址后重试，也可以切换播放内核"
                },
                alternativeLabel = alternative?.shortLabel,
                onRetry = { retryPlayback() },
                onSwitchAlternative = alternative?.let { next ->
                    {
                        // 手动切换会记住为默认内核；本次播放从当前位置续播。
                        switchEngine(next)
                    }
                },
                onBack = ::leavePlayer,
                isTv = isTv,
            )
        }

        if (playbackEnded && failedMessage == null) {
            PlayerEndedOverlay(
                nextTitle = request.nextEpisode?.title,
                countdown = autoNextCountdown.takeIf { it > 0 && !autoNextCancelled },
                onPlayNext = ::playNextNow,
                onReplay = {
                    seekTo(0L)
                    playbackEnded = false
                    session.onUserPlay()
                    engine.play()
                },
                onCancelAutoNext = {
                    autoNextCancelled = true
                    autoNextCountdown = 0
                },
                onBack = ::leavePlayer,
                countdownTotal = AUTO_NEXT_SECONDS,
                autoFocus = isTv,
            )
        }

        if (settingsHubVisible) {
            PlayerSettingsHubSheet(
                settings = uiSettings,
                onSettingsChange = onUiSettingsChange,
                entries = playerMenuEntries(
                    speed = speedLabel,
                    quality = displayQuality,
                    audio = audioLabel,
                    subtitle = subtitleLabel,
                    scale = resizeModeLabel(resizeMode),
                    danmaku = danmakuLabel,
                    vr = if (vrEnabled) resolvedVr.summary() else "关闭",
                    engine = engineKind.shortLabel,
                ),
                onSelectSection = { section ->
                    settingsHubVisible = false
                    settingsSection = section
                },
                onOpenInfo = {
                    settingsHubVisible = false
                    mediaInfoVisible = true
                },
                onOpenSleepTimer = {
                    settingsHubVisible = false
                    sleepTimerVisible = true
                },
                onDismiss = { settingsHubVisible = false },
                sleepTimerLabel = sleepTimerMinutes
                    .takeIf { it > 0 }
                    ?.let { "$it 分钟" }
                    ?: "当前会话",
                onLock = ::lockScreen,
                autoFocus = isTv,
                sideSheet = isTv,
            )
        }

        if (mediaInfoVisible) {
            PlayerMediaInfoSheet(
                title = title,
                subtitle = request.subtitle,
                engine = engineKind.label,
                quality = displayQuality,
                audio = audioLabel,
                subtitleTrack = subtitleLabel,
                durationMs = duration,
                server = serverLabel,
                onDismiss = { mediaInfoVisible = false },
                autoFocus = isTv,
                sideSheet = isTv,
            )
        }

        if (sleepTimerVisible) {
            PlayerSleepTimerSheet(
                remainingMinutes = sleepRemainingMinutes,
                selectedMinutes = sleepTimerMinutes.takeIf { it > 0 },
                onSelect = { minutes ->
                    if (minutes <= 0) {
                        sleepTimerMinutes = 0
                        sleepTimerDeadline = null
                        hint = "已关闭定时关闭"
                    } else {
                        sleepTimerMinutes = minutes
                        sleepTimerDeadline =
                            SystemClock.elapsedRealtime() + minutes * 60_000L
                        hint = "将在 $minutes 分钟后暂停播放"
                    }
                    sleepTimerVisible = false
                },
                onDismiss = { sleepTimerVisible = false },
                autoFocus = isTv,
                sideSheet = isTv,
            )
        }

        if (subtitleSettingsVisible) {
            PlayerSubtitleSettingsSheet(
                settings = uiSettings,
                onSettingsChange = onUiSettingsChange,
                tracks = trackState.text.map {
                    PlayerQuickTrack(
                        id = it.id,
                        label = it.label,
                        selected = it.selected && !subtitlesOff,
                        detail = it.lang,
                    )
                },
                subtitlesOff = subtitlesOff,
                onSelectTrack = { id ->
                    if (id == null) {
                        disableSubtitles()
                    } else {
                        trackState.text.firstOrNull { it.id == id }?.let(::chooseTrack)
                    }
                },
                onDismiss = { subtitleSettingsVisible = false },
                autoFocus = isTv,
                sideSheet = landscape || isTv,
            )
        }

        settingsSection?.let { section ->
            if (section == PlayerSettingsSection.VR) {
                PlayerVrSheet(
                    enabled = vrEnabled,
                    settings = vrSettings,
                    resolved = resolvedVr,
                    onEnabledChange = ::setVrEnabled,
                    onSettingsChange = ::updateVrSettings,
                    onRecenter = ::recenterVr,
                    onDismiss = { settingsSection = null },
                    autoFocus = isTv,
                    sideSheet = isTv,
                )
                return@let
            }
            if (section == PlayerSettingsSection.DANMAKU) {
                DanmakuSettingsSheet(
                    state = danmaku,
                    onDismiss = { settingsSection = null },
                    onEnabledChange = onDanmakuEnabledChange,
                    onRenderChange = onDanmakuRenderChange,
                    onReload = onDanmakuReload,
                    onSelectEpisode = onDanmakuSelectEpisode,
                    onSearch = onDanmakuSearch,
                    onResetAuto = onDanmakuResetAuto,
                    onOpenPhoneSearch = onOpenDanmakuPhoneInput,
                    autoFocus = isTv,
                    sideSheet = isTv,
                )
                return@let
            }
            val choices = settingsChoices(
                section = section,
                speed = playbackSpeed,
                trackState = trackState,
                pinnedQuality = pinnedQuality,
                subtitlesOff = subtitlesOff,
                resizeMode = resizeMode,
                engineKind = engineKind,
                hasAssSubtitle = assSubtitleCount > 0,
            )
            val sheetDescription = when (section) {
                PlayerSettingsSection.SUBTITLE -> subtitleNote
                PlayerSettingsSection.ENGINE ->
                    "切换后从当前位置续播，并保持播放/暂停状态；也可以在我的 → 播放设置里修改默认内核。"
                else -> null
            }
            PlayerSettingsSheet(
                section = section,
                choices = choices,
                description = sheetDescription,
                onDismiss = { settingsSection = null },
                autoFocus = isTv,
                sideSheet = isTv,
                onSelect = { choice ->
                    when (section) {
                        PlayerSettingsSection.DANMAKU -> Unit
                        PlayerSettingsSection.VR -> Unit
                        PlayerSettingsSection.SPEED -> {
                            playbackSpeed = choice.id.toFloatOrNull() ?: 1f
                            engine.setSpeed(playbackSpeed)
                        }
                        PlayerSettingsSection.QUALITY -> {
                            if (choice.id == "auto") {
                                pinnedQuality = null
                                autoSelect(EngineTrackType.VIDEO)
                            } else {
                                trackState.video.firstOrNull { it.id == choice.id }?.let { track ->
                                    pinnedQuality = track
                                    chooseTrack(track)
                                }
                            }
                        }
                        PlayerSettingsSection.AUDIO -> {
                            trackState.audio.firstOrNull { it.id == choice.id }?.let { track -> chooseTrack(track) }
                        }
                        PlayerSettingsSection.SUBTITLE -> when (choice.id) {
                            "off" -> disableSubtitles()
                            "switch-vlc" -> switchEngine(PlayerEngineKind.VLC)
                            else -> trackState.text
                                .firstOrNull { it.id == choice.id }
                                ?.let { track -> chooseTrack(track) }
                        }
                        PlayerSettingsSection.SCALE -> {
                            resizeMode = when (choice.id) {
                                "fill" -> EngineResizeMode.FILL
                                "zoom" -> EngineResizeMode.ZOOM
                                else -> EngineResizeMode.FIT
                            }
                            engine.setResizeMode(resizeMode)
                        }
                        PlayerSettingsSection.ENGINE -> {
                            switchEngine(PlayerEngineKind.fromKey(choice.id))
                        }
                    }
                    settingsSection = null
                },
            )
        }
    }
}

private fun settingsChoices(
    section: PlayerSettingsSection,
    speed: Float,
    trackState: PlayerTrackState,
    pinnedQuality: TrackChoice?,
    subtitlesOff: Boolean,
    resizeMode: EngineResizeMode,
    engineKind: PlayerEngineKind,
    hasAssSubtitle: Boolean,
): List<PlayerChoice> = when (section) {
    PlayerSettingsSection.DANMAKU -> emptyList()

    // VR 需要同时调整投影/立体/显示方式，使用独立面板而不是单选列表。
    PlayerSettingsSection.VR -> emptyList()

    PlayerSettingsSection.ENGINE ->
        PlayerEngineKind.entries.map { candidate ->
            PlayerChoice(
                id = candidate.key,
                label = candidate.label,
                selected = candidate == engineKind,
                badge = candidate.shortLabel.uppercase(),
            )
        }

    PlayerSettingsSection.SPEED ->
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).map { option ->
            PlayerChoice(
                id = option.toString(),
                label = playerSpeedLabel(option),
                selected = kotlin.math.abs(speed - option) < 0.01f,
            )
        }

    PlayerSettingsSection.QUALITY ->
        listOf(
            PlayerChoice(
                id = "auto",
                label = "自动",
                selected = pinnedQuality == null,
                badge = "AUTO",
                detail = "按网络与解码能力自动选择",
            ),
        ) + trackState.video.map { track ->
            PlayerChoice(
                id = track.id,
                label = track.label,
                selected = pinnedQuality?.id == track.id,
                badge = qualityBadgeFor(track.label),
            )
        }

    PlayerSettingsSection.AUDIO ->
        trackState.audio.mapIndexed { index, track ->
            PlayerChoice(
                id = track.id,
                label = track.label,
                selected = track.selected,
                badge = "A${index + 1}",
                detail = track.lang,
            )
        }

    PlayerSettingsSection.SUBTITLE -> buildList {
        add(
            PlayerChoice(
                id = "off",
                label = "关闭字幕",
                selected = subtitlesOff,
                badge = "OFF",
                detail = "不显示任何字幕轨",
            ),
        )
        trackState.text.forEachIndexed { index, track ->
            add(
                PlayerChoice(
                    id = track.id,
                    label = track.label,
                    selected = !subtitlesOff && track.selected,
                    badge = "S${index + 1}",
                    detail = track.lang,
                ),
            )
        }
        // ASS 样式只能在 LibVLC（libass）里还原；给一个显式入口，
        // 不让用户自己猜为什么样式没显示。
        if (engineKind == PlayerEngineKind.EXO && hasAssSubtitle) {
            add(
                PlayerChoice(
                    id = "switch-vlc",
                    label = "切换到 LibVLC 渲染 ASS 样式",
                    selected = false,
                    badge = "ASS",
                    detail = "保留字体、描边与特效",
                ),
            )
        }
    }

    PlayerSettingsSection.SCALE -> listOf(
        PlayerChoice(
            "fit",
            "适应屏幕（完整画面）",
            resizeMode == EngineResizeMode.FIT,
            badge = "FIT",
        ),
        PlayerChoice(
            "fill",
            "拉伸铺满",
            resizeMode == EngineResizeMode.FILL,
            badge = "FILL",
        ),
        PlayerChoice(
            "zoom",
            "裁剪铺满",
            resizeMode == EngineResizeMode.ZOOM,
            badge = "ZOOM",
        ),
    )
}

private fun qualityBadgeFor(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "HD"
    val matched = Regex("(\\d{3,4}[pPiI]|4K|8K|2K|HD|SD|FHD|UHD)").find(trimmed)?.value
    return (matched ?: trimmed).take(4).uppercase()
}

private fun resizeModeLabel(resizeMode: EngineResizeMode): String = when (resizeMode) {
    EngineResizeMode.FILL -> "拉伸铺满"
    EngineResizeMode.ZOOM -> "裁剪铺满"
    else -> "适应屏幕"
}

private fun EngineTrack.toChoice(): TrackChoice = TrackChoice(
    id = id,
    label = label,
    type = type,
    selected = selected,
    lang = language,
)

/** 服务端只提供直链；容器解析和字幕渲染交给当前播放内核。 */
private fun PlaybackRequest.toEngineMedia(
    subtitlePreference: SubtitlePreference,
): EngineMedia = EngineMedia(
    mediaId = mediaId,
    title = title,
    url = streamUrl,
    proxyUrl = proxyUrl,
    refreshUrl = refreshUrl,
    subtitles = subtitles
        .filter { it.url.isNotBlank() }
        .map { subtitle ->
            EngineSubtitle(
                url = subtitle.url,
                label = subtitle.label,
                language = subtitle.lang.takeIf { it.isNotBlank() },
                assUrl = subtitle.assUrl,
            )
        },
    subtitlePreference = subtitlePreference,
)


private fun Modifier.playerTouch(
    enabled: Boolean,
    onTap: () -> Unit,
    onDoubleTap: (forward: Boolean) -> Unit,
    onSeekDragStart: () -> Unit,
    onSeekDrag: (Float) -> Unit,
    onSeekDragEnd: () -> Unit,
    onVolumeDelta: (Float) -> Unit,
    onBrightnessDelta: (Float) -> Unit,
    vrMode: Boolean = false,
    onVrDrag: (Float, Float) -> Unit = { _, _ -> },
    levelEnabled: Boolean = true,
    onLongPressStart: (right: Boolean) -> Unit = {},
    onLongPressChange: (deltaX: Float) -> Unit = {},
    onLongPressEnd: () -> Unit = {},
): Modifier {
    if (!enabled) return this
    val tap = pointerInput(enabled) {
        // 屏幕左右边缘留给系统返回手势，不把边缘点击当作控件显隐。
        val edge = 16.dp.toPx()
        detectTapGestures(
            onTap = { offset ->
                if (offset.x >= edge && offset.x <= size.width - edge) onTap()
            },
            onDoubleTap = { offset ->
                if (offset.x >= edge && offset.x <= size.width - edge) {
                    onDoubleTap(offset.x >= size.width / 2f)
                }
            },
            // 长按由下面统一的手势识别器处理；这里只负责吞掉“长按后松手”
            // 产生的单击，避免退出倍速时顺带把控件栏切出来。
            onLongPress = { },
        )
    }
    if (vrMode) {
        // VR 下拖动用来偏转视线，不再触发快进/亮度/音量手势。
        return tap.pointerInput(vrMode) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onVrDrag(dragAmount.x, dragAmount.y)
            }
        }
    }
    return tap.pointerInput(enabled) {
        val edgePx = 16.dp.toPx()
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            // 屏幕左右边缘交给系统返回手势，不在这里横滑 seek。
            if (startX < edgePx || startX > size.width - edgePx) {
                waitForUpOrCancellation()
                return@awaitEachGesture
            }

            var last = down.position
            var total = androidx.compose.ui.geometry.Offset.Zero
            var mode = GestureMode.Pending
            var levelRight = startX >= size.width / 2f
            val downTime = down.uptimeMillis

            while (true) {
                // 手指按住不动时不会产生新事件，必须用超时来识别长按，
                // 否则 awaitPointerEvent 会一直挂起，倍速提示永远不出现。
                val event = if (mode == GestureMode.Pending) {
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    val remaining = (longPressTimeout - elapsed).coerceAtLeast(1L)
                    try {
                        withTimeout(remaining) { awaitPointerEvent() }
                    } catch (_: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                        null
                    }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    if (mode == GestureMode.Pending) {
                        mode = GestureMode.LongPress
                        onLongPressStart(startX >= size.width / 2f)
                    }
                    continue
                }

                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - last
                last = change.position
                total += delta
                val now = change.uptimeMillis

                if (mode == GestureMode.Pending) {
                    if (total.getDistance() > slop) {
                        mode = if (kotlin.math.abs(total.x) >= kotlin.math.abs(total.y)) {
                            GestureMode.Seek
                        } else {
                            GestureMode.Level
                        }
                        if (mode == GestureMode.Seek) onSeekDragStart()
                    } else if (now - downTime >= longPressTimeout) {
                        mode = GestureMode.LongPress
                        onLongPressStart(startX >= size.width / 2f)
                    }
                }

                when (mode) {
                    GestureMode.Seek -> {
                        change.consume()
                        onSeekDrag(delta.x / size.width)
                    }

                    GestureMode.Level -> {
                        change.consume()
                        if (levelEnabled) {
                            val value = -delta.y / size.height
                            if (levelRight) onVolumeDelta(value) else onBrightnessDelta(value)
                        }
                    }

                    GestureMode.LongPress -> {
                        change.consume()
                        if (delta.x != 0f) onLongPressChange(delta.x)
                    }

                    GestureMode.Pending -> Unit
                }
            }

            when (mode) {
                GestureMode.Seek -> onSeekDragEnd()
                GestureMode.LongPress -> onLongPressEnd()
                else -> Unit
            }
        }
    }
}

private enum class GestureMode { Pending, Seek, Level, LongPress }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
