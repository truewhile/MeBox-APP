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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import app.mebox.android.core.media.PlaybackPlan
import app.mebox.android.core.player.EngineMedia
import app.mebox.android.core.player.EnginePlaybackState
import app.mebox.android.core.player.EngineResizeMode
import app.mebox.android.core.player.EngineSubtitle
import app.mebox.android.core.player.EngineTrack
import app.mebox.android.core.player.EngineTrackType
import app.mebox.android.core.player.EngineTracks
import app.mebox.android.core.player.PlayerEngineKind
import app.mebox.android.core.player.VideoPlayerEngineListener
import app.mebox.android.core.player.createVideoPlayerEngine
import app.mebox.android.feature.media.DanmakuUiState
import app.mebox.android.feature.media.PlaybackRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * 原生播放页。
 *
 * 播放内核通过统一接口接入：默认 Media3 ExoPlayer（系统解码），可在设置页或
 * 页内「更多」面板切换到 LibVLC（FFmpeg 软解）。界面完全由 Compose 自绘：
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

    BackHandler { onBack() }

    if (request == null) {
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
    var pinnedQuality by remember(initialKey) { mutableStateOf<TrackChoice?>(null) }
    var subtitlesOff by remember(initialKey) { mutableStateOf(true) }
    var trackState by remember(initialKey) { mutableStateOf(PlayerTrackState()) }

    // 播放内核可以在页内切换；切换时记录当前位置，新内核从同一点起播。
    var engineKind by remember(initialKey) { mutableStateOf(initialEngine) }
    var resumePositionMs by remember(initialKey) { mutableStateOf<Long?>(null) }

    var dragBaseMs by remember(initialKey) { mutableLongStateOf(0L) }
    var dragDeltaMs by remember(initialKey) { mutableLongStateOf(0L) }
    var lastKnownPosition by remember(initialKey) { mutableLongStateOf(request.startPositionMs) }

    // 一个内核实例绑定一集内容；切换内核会重建实例并从当前位置续播。
    val engine = remember(initialKey, engineKind) {
        createVideoPlayerEngine(engineKind, context)
    }

    val progressCallback by rememberUpdatedState(onProgress)

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
            lastKnownPosition = position
            progressCallback(request.mediaId, position, duration, force)
        }
    }

    fun disableTextTracks() {
        subtitlesOff = true
        engine.selectTextTrack(null)
    }

    /** 切换播放内核：保留进度并立即用新内核重新起播。 */
    fun switchEngine(next: PlayerEngineKind) {
        if (next == engineKind) return
        resumePositionMs = engine.positionMs().takeIf { it > 0L } ?: lastKnownPosition
        failedMessage = null
        errorIsDecode = false
        playbackEnded = false
        isBuffering = true
        engineKind = next
        onEngineSelected(next)
        hint = "已切换播放内核：${next.shortLabel}"
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
                engine.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(engine, request) {
        engine.listener = object : VideoPlayerEngineListener {
            override fun onError(message: String, decodeRelated: Boolean) {
                errorIsDecode = decodeRelated
                failedMessage = message
                errorCallback(message)
            }

            override fun onPlaybackStateChanged(state: EnginePlaybackState) {
                isBuffering = state == EnginePlaybackState.BUFFERING
                playbackEnded = state == EnginePlaybackState.ENDED
                if (state == EnginePlaybackState.READY) {
                    durationMs = currentDuration()
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
                if (subtitlesOff && text.any { it.selected }) {
                    subtitlesOff = false
                }
            }
        }
        onDispose { engine.listener = null }
    }

    // 只按媒体/地址变化重新装载，后台补拉字幕不会打断正在播放的画面。
    LaunchedEffect(engine, initialKey) {
        val startAt = resumePositionMs ?: request.startPositionMs
        positionMs = startAt
        engine.setMedia(request.toEngineMedia(), startAt, autoPlay = true)
        // 切换内核后沿用用户已经选好的倍速，不回到 1x。
        engine.setSpeed(playbackSpeed)
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
                lastKnownPosition = position
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

    // 控制栏自动隐藏：播放中 4.5 秒无操作后收起。暂停、结束和拖动时保持可见。
    LaunchedEffect(controlsVisible, isPlaying, playbackEnded, locked) {
        if (controlsVisible && isPlaying && !playbackEnded && !locked) {
            delay(4_500)
            controlsVisible = false
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
        if (danmaku.candidates.isNotEmpty() && settingsSection == null) {
            settingsSection = PlayerSettingsSection.DANMAKU
        }
    }

    fun seekTo(absoluteMs: Long) {
        val duration = currentDuration()
        val target = if (duration > 0L) {
            absoluteMs.coerceIn(0L, duration)
        } else {
            absoluteMs.coerceAtLeast(0L)
        }
        engine.seekTo(target)
        positionMs = target
        lastKnownPosition = target
        playbackEnded = false
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
    }

    fun adjustVolume(delta: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (current + delta * max).roundToInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        levelFeedback = PlayerLevel.VOLUME
        levelFraction = next.toFloat() / max
    }

    fun chooseTrack(choice: TrackChoice) {
        when (choice.type) {
            EngineTrackType.VIDEO -> engine.selectVideoTrack(choice.id)
            EngineTrackType.AUDIO -> engine.selectAudioTrack(choice.id)
            EngineTrackType.TEXT -> {
                subtitlesOff = false
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
        subtitlesOff = true
        engine.selectTextTrack(null)
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
    val speedLabel = PlaybackPlan.formatSpeed(playbackSpeed)
    val qualityLabel = pinnedQuality?.label ?: "自动"
    val subtitleLabel = when {
        subtitlesOff -> "关闭"
        trackState.selectedTextLabel != null -> trackState.selectedTextLabel!!
        else -> "默认"
    }
    val imageSubtitleNote = request.imageSubtitles.takeIf { it.isNotEmpty() }?.let { labels ->
        "检测到 ${labels.size} 条图片字幕（${labels.joinToString("、")}）。" +
            "系统解码内核无法渲染，可切换到 LibVLC 内核尝试。"
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
    LaunchedEffect(isTv, moreSheetVisible, settingsSection) {
        if (isTv && !moreSheetVisible && settingsSection == null) {
            runCatching { tvFocusRequester.requestFocus() }
        }
    }

    fun togglePlayback() {
        if (playbackEnded) {
            seekTo(0L)
            playbackEnded = false
            engine.play()
        } else if (isPlaying) {
            engine.pause()
        } else {
            engine.play()
        }
        controlsVisible = true
    }

    fun tvSeek(deltaMs: Long) {
        val target = PlaybackPlan.seekTarget(absolutePosition(), deltaMs, currentDuration())
        seekTo(target)
        seekFeedback = SeekFeedbackState(deltaMs >= 0, deltaMs, target)
        controlsVisible = true
    }

    fun handleTvKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!isTv) return false
        val code = event.nativeKeyEvent.keyCode
        // 设置面板打开时把方向键/确认键完全还给面板，否则 KeyUp 被根节点吞掉会导致
        // 面板里的 clickable 选项无法触发。
        if (moreSheetVisible || settingsSection != null) return false
        if (event.type == KeyEventType.KeyUp) {
            return code in TV_CONFIRM_KEYS
        }

        // 播放失败时 OK 直接重试，保证遥控器不会卡在没有焦点的错误页。
        if (failedMessage != null) {
            if (code in TV_CONFIRM_KEYS) {
                failedMessage = null
                errorIsDecode = false
                engine.retry()
                return true
            }
            return false
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

    BackHandler(enabled = locked) {
        locked = false
        lockBarVisible = false
        controlsVisible = true
    }

    Box(
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
        AndroidView(
            factory = { ctx -> FrameLayout(ctx) },
            update = { frame ->
                engine.setResizeMode(resizeMode)
                engine.attach(frame)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 弹幕层位于视频之上、手势与控制层之下，不拦截播放器点击。
        DanmakuOverlay(
            comments = danmaku.comments,
            enabled = danmaku.enabled,
            opacity = danmaku.opacity,
            fontScale = danmaku.fontScale,
            areaFraction = danmaku.area,
            positionProvider = { engine.positionMs() },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层位于控制层之下：控制栏可见时，按钮优先消费点击。
        if (!locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerTouch(
                        enabled = true,
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { forward ->
                            val delta = if (forward) seekStep else -seekStep
                            val target = PlaybackPlan.seekTarget(
                                absolutePosition(),
                                delta,
                                currentDuration(),
                            )
                            seekTo(target)
                            seekFeedback = SeekFeedbackState(forward, delta, target)
                            controlsVisible = true
                        },
                        onSeekDragStart = {
                            dragBaseMs = absolutePosition()
                            dragDeltaMs = 0L
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
                        },
                        onVolumeDelta = { delta -> adjustVolume(delta) },
                        onBrightnessDelta = { delta -> adjustBrightness(delta) },
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

        if (controlsVisible && !locked) {
            PlayerTopBar(
                title = title,
                subtitle = request.subtitle,
                pictureInPictureAvailable = pipAvailable && !isTv,
                onBack = onBack,
                onPictureInPicture = { enterPictureInPicture() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (isTv && controlsVisible && !locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 86.dp),
            ) {
                Text(
                    "OK 播放/暂停 · ◀ ▶ 快退/快进 · 菜单键 播放设置 · 返回键 退出",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // 播放中不显示中央按钮，避免遮挡画面；暂停或播放结束时才出现。
        if (controlsVisible && !locked && !isBuffering && seekFeedback == null &&
            (!isPlaying || playbackEnded)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 32.dp),
            ) {
                PlayerCenterButton(
                    isPlaying = isPlaying && !playbackEnded,
                    onClick = {
                        if (playbackEnded) {
                            seekTo(0L)
                            playbackEnded = false
                            engine.play()
                        } else if (isPlaying) {
                            engine.pause()
                        } else {
                            engine.play()
                        }
                        controlsVisible = true
                    },
                )
            }
        }

        if (controlsVisible && !locked) {
            PlayerBottomControls(
                positionMs = positionMs,
                durationMs = duration,
                bufferedMs = bufferedMs,
                isPlaying = isPlaying && !playbackEnded,
                hasNext = request.nextEpisode != null,
                speedLabel = speedLabel,
                subtitleLabel = subtitleLabel,
                danmakuEnabled = danmaku.enabled,
                danmakuLabel = "弹幕：$danmakuLabel",
                onSeek = { position -> seekTo(position) },
                onTogglePlay = {
                    if (playbackEnded) {
                        seekTo(0L)
                        playbackEnded = false
                        engine.play()
                    } else if (isPlaying) {
                        engine.pause()
                    } else {
                        engine.play()
                    }
                    controlsVisible = true
                },
                onPlayNext = {
                    playbackEnded = false
                    autoNextCancelled = true
                    onPlayNext()
                },
                onToggleDanmaku = {
                    if (danmaku.candidates.isNotEmpty()) {
                        // 匹配到多个作品时，点击弹幕按钮直接打开候选面板。
                        settingsSection = PlayerSettingsSection.DANMAKU
                    } else {
                        onDanmakuEnabledChange(!danmaku.enabled)
                    }
                },
                onOpenSubtitle = { settingsSection = PlayerSettingsSection.SUBTITLE },
                onOpenSpeed = { settingsSection = PlayerSettingsSection.SPEED },
                onOpenMore = { moreSheetVisible = true },
                onLock = {
                    locked = true
                    controlsVisible = false
                    lockBarVisible = true
                    hint = "已锁定"
                },
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

        if (locked && lockBarVisible) {
            PlayerLockButton(
                onUnlock = {
                    locked = false
                    lockBarVisible = false
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
            )
        }

        failedMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法播放该视频", color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        color = Color(0xFFB0B0B0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (errorIsDecode) {
                            "本机解码不支持该格式，可切换播放内核后重试"
                        } else {
                            "检查网络或地址后重试，也可以切换播放内核"
                        },
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = {
                            failedMessage = null
                            errorIsDecode = false
                            engine.retry()
                        },
                    ) { Text("重试") }
                    TextButton(onClick = onBack) { Text("返回") }
                }
            }
        }

        if (playbackEnded && failedMessage == null) {
            PlayerEndedOverlay(
                nextTitle = request.nextEpisode?.title,
                countdown = autoNextCountdown.takeIf { it > 0 && !autoNextCancelled },
                onPlayNext = {
                    playbackEnded = false
                    autoNextCancelled = true
                    onPlayNext()
                },
                onReplay = {
                    seekTo(0L)
                    playbackEnded = false
                    engine.play()
                },
                onCancelAutoNext = {
                    autoNextCancelled = true
                    autoNextCountdown = 0
                },
                onBack = onBack,
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
            if (section == PlayerSettingsSection.DANMAKU) {
                DanmakuSettingsSheet(
                    state = danmaku,
                    onDismiss = { settingsSection = null },
                    onEnabledChange = onDanmakuEnabledChange,
                    onRenderChange = onDanmakuRenderChange,
                    onReload = onDanmakuReload,
                    onSelectEpisode = onDanmakuSelectEpisode,
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
                    "切换后立即用新内核从当前位置继续播放；也可以在我的 → 播放设置里修改默认内核。"
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

    PlayerSettingsSection.ENGINE ->
        PlayerEngineKind.entries.map { candidate ->
            PlayerChoice(
                id = candidate.key,
                label = candidate.label,
                selected = candidate == engineKind,
            )
        }

    PlayerSettingsSection.SPEED ->
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { option ->
            PlayerChoice(
                id = option.toString(),
                label = PlaybackPlan.formatSpeed(option),
                selected = kotlin.math.abs(speed - option) < 0.01f,
            )
        }

    PlayerSettingsSection.QUALITY ->
        listOf(PlayerChoice("auto", "自动", pinnedQuality == null)) +
            trackState.video.map { track ->
                PlayerChoice(track.id, track.label, pinnedQuality?.id == track.id)
            }

    PlayerSettingsSection.AUDIO ->
        trackState.audio.map { track -> PlayerChoice(track.id, track.label, track.selected) }

    PlayerSettingsSection.SUBTITLE -> buildList {
        add(PlayerChoice("off", "关闭字幕", subtitlesOff))
        trackState.text.forEach { track ->
            add(
                PlayerChoice(
                    track.id,
                    track.label,
                    !subtitlesOff && track.selected,
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
                ),
            )
        }
    }

    PlayerSettingsSection.SCALE -> listOf(
        PlayerChoice("fit", "适应屏幕（完整画面）", resizeMode == EngineResizeMode.FIT),
        PlayerChoice("fill", "拉伸铺满", resizeMode == EngineResizeMode.FILL),
        PlayerChoice("zoom", "裁剪铺满", resizeMode == EngineResizeMode.ZOOM),
    )
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
private fun PlaybackRequest.toEngineMedia(): EngineMedia = EngineMedia(
    mediaId = mediaId,
    title = title,
    url = streamUrl,
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
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(enabled) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { offset -> onDoubleTap(offset.x >= size.width / 2f) },
            )
        }
        .pointerInput(enabled) {
            detectHorizontalDragGestures(
                onDragStart = { onSeekDragStart() },
                onDragEnd = { onSeekDragEnd() },
                onDragCancel = { onSeekDragEnd() },
            ) { change, dragAmount ->
                change.consume()
                onSeekDrag(dragAmount / size.width)
            }
        }
        .pointerInput(enabled) {
            var rightHalf = false
            detectVerticalDragGestures(
                onDragStart = { offset -> rightHalf = offset.x >= size.width / 2f },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    val delta = -dragAmount / size.height
                    if (rightHalf) onVolumeDelta(delta) else onBrightnessDelta(delta)
                },
            )
        }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
