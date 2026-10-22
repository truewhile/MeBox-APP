package app.mebox.android.feature.media.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import app.mebox.android.core.player.EngineTrackType
import app.mebox.android.core.player.EngineTracks
import app.mebox.android.core.player.DecoderMode
import app.mebox.android.core.player.PlaybackSessionState
import app.mebox.android.core.player.PlaybackTrackRef
import app.mebox.android.core.player.PlaybackTrackRestorer
import app.mebox.android.core.player.PlaybackTrackSelection
import app.mebox.android.core.player.PlayerEngineKind
import app.mebox.android.core.player.SubtitlePreference
import app.mebox.android.core.player.SubtitleSelector
import app.mebox.android.core.player.VideoPlayerEngineListener
import app.mebox.android.core.player.createVideoPlayerEngine
import app.mebox.android.core.player.vr.VrSettings
import app.mebox.android.feature.media.DanmakuUiState
import app.mebox.android.feature.media.PlaybackRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    // 进入播放页自动横屏；退出时恢复到用户当前的旋转偏好。
    // Activity 已声明 configChanges，旋转不会重建页面、不会中断播放。
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val previousOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity, isTv) {
        if (!isTv) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (!isTv) {
                activity?.requestedOrientation =
                    if (previousOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        // UNSPECIFIED 在关闭自动旋转的设备上会停留在横屏；USER 会遵循
                        // 用户自己的旋转锁定，回到竖屏（或用户锁定的方向）。
                        ActivityInfo.SCREEN_ORIENTATION_USER
                    } else {
                        previousOrientation
                    }
            }
        }
    }

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

    PlayerSurface(
        request = request,
        title = request.title.ifBlank { title },
        danmaku = danmaku,
        initialEngine = engine,
        subtitlePreference = subtitlePreference,
        decoderMode = decoderMode,
        storedVrSettings = vrSettings,
        onVrSettingsChange = onVrSettingsChange,
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
    var moreSheetVisible by remember(initialKey) { mutableStateOf(false) }
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
     * 长按临时倍速：按住时以 2× 播放，横滑按 0.25× 步进调整；
     * 松手恢复用户原本选择的倍速，不写回偏好。
     */
    fun startBoost() {
        if (boostSpeed > 0f) return
        resetControlsTimer()
        boostSpeed = 2f
        engine.setSpeed(boostSpeed)
    }

    fun adjustBoost(delta: Float) {
        if (boostSpeed <= 0f) return
        boostSpeed = (boostSpeed + delta).coerceIn(0.5f, 4f)
        engine.setSpeed(boostSpeed)
    }

    fun endBoost() {
        if (boostSpeed <= 0f) return
        boostSpeed = 0f
        engine.setSpeed(playbackSpeed)
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


    // 进入播放页：隐藏系统栏，退出时恢复。
    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
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

    DisposableEffect(engine, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                report(force = true)
                // 退到后台属于用户可见的暂停：切回前台后仍保持暂停。
                session.onUserPause()
                engine.pause()
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
        settingsSection,
        moreSheetVisible,
    ) {
        if (controlsVisible &&
            isPlaying &&
            !isBuffering &&
            !playbackEnded &&
            !locked &&
            !seekDragging &&
            boostSpeed == 0f &&
            settingsSection == null &&
            !moreSheetVisible
        ) {
            delay(3_000)
            controlsVisible = false
        }
    }
    // 锁屏后只保留解锁按钮，3 秒后淡出；点按画面可再次唤出。
    LaunchedEffect(locked, lockBarVisible) {
        if (locked && lockBarVisible) {
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
    // 播放结束后自动接下一集；用户取消或没有下一集时不倒计时。
    LaunchedEffect(playbackEnded, autoNextCancelled, request.nextEpisode?.mediaId) {
        if (!playbackEnded || autoNextCancelled || request.nextEpisode == null) {
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
            !moreSheetVisible
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
        controlsTick++
        hint = "屏幕已锁定"
    }

    fun unlockScreen() {
        locked = false
        lockBarVisible = false
        controlsVisible = true
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
    val seekStep = 10_000L
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

    // ── 电视遥控器 ──
    // 焦点固定在播放器根节点上，方向键直接映射为播放操作；打开设置面板后
    // 不再拦截方向键，把焦点交给面板里的选项。
    val tvFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) runCatching { tvFocusRequester.requestFocus() }
    }
    LaunchedEffect(isTv, moreSheetVisible, settingsSection, failedMessage) {
        if (isTv && !moreSheetVisible && settingsSection == null && failedMessage == null) {
            runCatching { tvFocusRequester.requestFocus() }
        }
    }

    fun tvSeek(deltaMs: Long) {
        val target = PlaybackPlan.seekTarget(absolutePosition(), deltaMs, currentDuration())
        seekTo(target)
        seekFeedback = SeekFeedbackState(deltaMs >= 0, deltaMs, target)
        touchControls()
    }

    fun handleTvKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!isTv) return false
        val code = event.nativeKeyEvent.keyCode
        // 设置面板打开时把方向键/确认键完全还给面板，否则 KeyUp 被根节点吞掉会导致
        // 面板里的 clickable 选项无法触发。
        if (moreSheetVisible || settingsSection != null) return false
        // 播放失败时把按键完全交给错误页的按钮（重试/切换内核/返回）：
        // 根节点的预览阶段一旦吞掉确认键，焦点按钮就永远点不动。
        if (failedMessage != null) return false
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
                tvSeek(-seekStep)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                tvSeek(seekStep)
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                controlsVisible = true
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                controlsVisible = false
                true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                moreSheetVisible = true
                true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (request.nextEpisode != null) {
                    playbackEnded = false
                    autoNextCancelled = true
                    onPlayNext()
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

    BackHandler(enabled = locked) { unlockScreen() }

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
        // 手机横屏的可用高度通常只有 360–430dp，右侧竖排按钮会和底部控制栏
        // 抢空间；矮屏下把锁定入口并入操作行，只保留底部一体化控制栏。
        val compactControls = maxHeight < 600.dp
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
                            val delta = if (forward) seekStep else -seekStep
                            val target = PlaybackPlan.seekTarget(
                                absolutePosition(),
                                delta,
                                currentDuration(),
                            )
                            seekTo(target)
                            seekFeedback = SeekFeedbackState(forward, delta, target)
                            touchControls()
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
                        onVolumeDelta = { delta -> adjustVolume(delta) },
                        onBrightnessDelta = { delta -> adjustBrightness(delta) },
                        onBoostStart = { startBoost() },
                        onBoostChange = { delta -> adjustBoost(delta) },
                        onBoostEnd = { endBoost() },
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
            PlayerTopBar(
                title = title,
                subtitle = request.subtitle,
                pictureInPictureAvailable = pipAvailable && !isTv,
                onBack = ::leavePlayer,
                onPictureInPicture = { enterPictureInPicture() },
                onOpenSettings = { moreSheetVisible = true },
            )
        }

        if (isTv && controlsVisible && !locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 92.dp),
            ) {
                Text(
                    "OK 播放/暂停 · ◀ ▶ 快退/快进 · 菜单键 播放设置 · 返回键 退出",
                    color = PlayerSkin.TextSecondary,
                    style = PlayerSkin.Overline,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !compactControls,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
        ) {
            PlayerSideActions(
                danmakuEnabled = danmaku.enabled,
                pictureInPictureAvailable = pipAvailable && !isTv,
                onLock = ::lockScreen,
                onToggleDanmaku = ::toggleDanmakuFromControls,
                onPictureInPicture = { enterPictureInPicture() },
                onOpenSettings = { moreSheetVisible = true },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomControls(
                positionMs = positionMs,
                durationMs = duration,
                bufferedMs = bufferedMs,
                isPlaying = isPlaying && !playbackEnded,
                hasNext = request.nextEpisode != null,
                speedLabel = speedLabel,
                qualityLabel = qualityLabel,
                audioLabel = audioLabel,
                subtitleLabel = subtitleLabel,
                danmakuEnabled = danmaku.enabled,
                onSeek = { position ->
                    seekTo(position)
                    resetControlsTimer()
                },
                onSeekDragStateChange = { dragging ->
                    seekDragging = dragging
                    if (dragging) touchControls()
                },
                onTogglePlay = { togglePlayback() },
                onSeekBack = { tvSeek(-seekStep) },
                onPlayNext = ::playNextNow,
                onToggleDanmaku = ::toggleDanmakuFromControls,
                onOpenSubtitle = { settingsSection = PlayerSettingsSection.SUBTITLE },
                onOpenSpeed = { settingsSection = PlayerSettingsSection.SPEED },
                onOpenQuality = { settingsSection = PlayerSettingsSection.QUALITY },
                onOpenAudio = { settingsSection = PlayerSettingsSection.AUDIO },
                onOpenMore = { moreSheetVisible = true },
                vrActive = vrEnabled,
                onRecenterVr = ::recenterVr,
                onLock = if (compactControls) ::lockScreen else null,
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

        boostSpeed.takeIf { it > 0f }?.let { speed ->
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
            PlayerLockButton(onUnlock = ::unlockScreen)
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
            )
        }

        if (moreSheetVisible) {
            PlayerMoreSheet(
                entries = playerMenuEntries(
                    speed = speedLabel,
                    quality = qualityLabel,
                    audio = audioLabel,
                    subtitle = subtitleLabel,
                    scale = resizeModeLabel(resizeMode),
                    danmaku = danmakuLabel,
                    vr = if (vrEnabled) resolvedVr.summary() else "关闭",
                    engine = engineKind.shortLabel,
                ),
                onSelect = { section ->
                    moreSheetVisible = false
                    settingsSection = section
                },
                onDismiss = { moreSheetVisible = false },
                autoFocus = isTv,
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
    onBoostStart: (Float) -> Unit = {},
    onBoostChange: (Float) -> Unit = {},
    onBoostEnd: () -> Unit = {},
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
        val boostStepPx = 40.dp.toPx()

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
            var boostAccum = 0f
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
                        mode = GestureMode.Boost
                        onBoostStart(2f)
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
                        mode = GestureMode.Boost
                        onBoostStart(2f)
                    }
                }

                when (mode) {
                    GestureMode.Seek -> {
                        change.consume()
                        onSeekDrag(delta.x / size.width)
                    }

                    GestureMode.Level -> {
                        change.consume()
                        val value = -delta.y / size.height
                        if (levelRight) onVolumeDelta(value) else onBrightnessDelta(value)
                    }

                    GestureMode.Boost -> {
                        change.consume()
                        boostAccum += delta.x
                        val steps = (boostAccum / boostStepPx).toInt()
                        if (steps != 0) {
                            boostAccum -= steps * boostStepPx
                            onBoostChange(steps * 0.25f)
                        }
                    }

                    GestureMode.Pending -> Unit
                }
            }

            when (mode) {
                GestureMode.Seek -> onSeekDragEnd()
                GestureMode.Boost -> onBoostEnd()
                else -> Unit
            }
        }
    }
}

private enum class GestureMode { Pending, Seek, Level, Boost }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
