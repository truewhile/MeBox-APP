package app.mebox.android.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.media.SubtitleLabel
import app.mebox.android.core.network.APP_USER_AGENT
import java.io.File
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * 第三方解码内核：LibVLC（内置 FFmpeg）。
 *
 * 用途是补足系统 MediaCodec 不支持的容器/编码（RMVB、WMV、DTS 音轨等）。
 * 画面由 [VLCVideoLayout] 自己渲染，字幕也由 VLC 叠加，因此界面层不需要
 * 再提供字幕样式；其余控制（进度、倍速、轨道、画面缩放）通过统一接口暴露。
 *
 * 注意：LibVLC 初始化较重，创建实例会占用几百毫秒到一秒，建议只在用户
 * 显式选择该内核时创建。
 */
class VlcVideoPlayerEngine(
    private val context: Context,
    /** 解码方式：自动 / 强制硬件 / 强制软件。 */
    private val decoderMode: DecoderMode = DecoderMode.AUTO,
) : VideoPlayerEngine {

    override val kind: PlayerEngineKind = PlayerEngineKind.VLC

    override var listener: VideoPlayerEngineListener? = null

    /**
     * libVLC 的运行日志。VLC 的网络栈不经过 OkHttp，出问题时 App 日志里
     * 只有 `read=0`，看不到原因；把 VLC 自己的日志落到缓存目录，失败时
     * 摘一段进诊断日志，下一次就能直接看到 DNS/建连/HTTP 状态。
     */
    private val vlcLogFile = File(context.cacheDir, "mebox-vlc.log")

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            // 丢帧会导致音画不同步，这里让 VLC 自己控制节奏。
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
            // 硬解由每条 Media 单独控制：失败后可以只对这一条改走软解，
            // 不要把全局配置钉死在硬解或软解上。
            "--network-caching=3000",
            "--verbose=2",
            "--file-logging",
            "--logfile=${vlcLogFile.absolutePath}",
        ),
    ).also { truncateVlcLog() }

    private val mediaPlayer = MediaPlayer(libVlc)

    private var host: FrameLayout? = null
    private var videoLayout: VLCVideoLayout? = null
    private var resizeMode: EngineResizeMode = EngineResizeMode.FIT
    /** 当前视频的显示宽高比；拿到轨道信息前为 null，按铺满宿主处理。 */
    private var videoAspect: Float? = null
    /**
     * 当前媒体实际挂上的外挂字幕名称，顺序与 `addSlave` 一致。
     * LibVLC 只会把外挂字幕命名为「Track N」，面板里用这个列表覆盖。
     */
    private var slaveLabels: List<String> = emptyList()
    private var pendingMedia: EngineMedia? = null
    private var pendingStartMs: Long = 0L
    private var pendingAutoPlay: Boolean = true
    private var pendingSeekMs: Long = 0L
    private var ended: Boolean = false
    private var released: Boolean = false
    private var playing: Boolean = false
    private var speed: Float = 1f

    /**
     * 硬解失败后只对当前媒体改走 FFmpeg 软解。
     *
     * 手机硬解只覆盖 8-bit H.264/HEVC，Hi10P（H.264 High 10）、10-bit HEVC
     * 这类编码触发 EncounteredError 时，VLC 不一定能自己回退，表现为
     * “LibVLC 无法播放该视频”且时长 0。这里主动用软件解码重试一次。
     */
    private var softwareDecodeOnly = decoderMode == DecoderMode.SOFTWARE
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 直连被 CDN 拒绝后是否已经尝试过服务端中转，避免来回重试。 */
    private var proxyAttempted = false

    /**
     * 起播看门狗：LibVLC 在硬解初始化失败时可能既不报错也不进入 Playing，
     * 表现为一直转圈。超过 [STALL_TIMEOUT_MS] 仍未起播就按失败处理。
     */
    private var stallToken = 0

    /** 用户主动暂停时不触发起播看门狗。 */
    private var userPaused = false

    /** 轨道 id 的默认值：用于「自动」清晰度/音轨。 */
    private var defaultVideoId: Int? = null
    private var defaultAudioId: Int? = null

    init {
        mediaPlayer.setEventListener { event ->
            if (released) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    listener?.onBufferingChanged(true)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
                }

                MediaPlayer.Event.Buffering -> {
                    val buffering = event.buffering < 100f
                    listener?.onBufferingChanged(buffering)
                    listener?.onPlaybackStateChanged(
                        if (buffering) EnginePlaybackState.BUFFERING else EnginePlaybackState.READY,
                    )
                }

                MediaPlayer.Event.Playing -> {
                    playing = true
                    // 已起播，作废还在等待的起播看门狗。
                    stallToken++
                    ended = false
                    listener?.onPlayingChanged(true)
                    listener?.onBufferingChanged(false)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.READY)
                    // libVLC 在起播时可能重置倍速，这里按用户选择重新应用。
                    if (speed != 1f) runCatching { mediaPlayer.setRate(speed) }
                    if (pendingSeekMs > 0L) {
                        mediaPlayer.setTime(pendingSeekMs)
                        pendingSeekMs = 0L
                    }
                    if (!pendingAutoPlay) mediaPlayer.pause()
                    refreshTracks()
                }

                MediaPlayer.Event.Paused -> {
                    playing = false
                    listener?.onPlayingChanged(false)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.READY)
                }

                MediaPlayer.Event.Stopped -> {
                    playing = false
                    listener?.onPlayingChanged(false)
                    listener?.onBufferingChanged(false)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.IDLE)
                }

                MediaPlayer.Event.EndReached -> {
                    playing = false
                    stallToken++
                    ended = true
                    listener?.onPlayingChanged(false)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.ENDED)
                    listener?.onEnded()
                }

                MediaPlayer.Event.EncounteredError -> {
                    val stats = runCatching { mediaPlayer.media?.stats }.getOrNull()
                    handleOpenFailure(pendingMedia, stats, "LibVLC 播放失败")
                }

                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted,
                MediaPlayer.Event.ESSelected,
                // 视频输出建立后才知道画面尺寸：拿到宽高比重新算一次画面框。
                MediaPlayer.Event.Vout,
                -> refreshTracks()

                MediaPlayer.Event.LengthChanged -> {
                    listener?.onPlaybackStateChanged(EnginePlaybackState.READY)
                }
            }
        }
    }

    /** 宿主（Compose 给的 Framelayout）尺寸变化时重算画面框。 */
    private val hostLayoutListener = View.OnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
        if (right != oldRight || bottom != oldBottom) applySurfaceBox()
    }

    /**
     * 兜底：VLCVideoLayout 一旦重新挂到窗口，会把自己的 LayoutParams 改回
     * MATCH_PARENT；自己身上的布局变化是无条件信号，比等宿主再布局更可靠。
     */
    private val layoutGuardListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        applySurfaceBox()
    }

    override fun attach(host: FrameLayout) {
        if (released) return
        if (this.host !== host) {
            this.host?.removeOnLayoutChangeListener(hostLayoutListener)
            host.addOnLayoutChangeListener(hostLayoutListener)
            this.host = host
        }
        val layout = videoLayout ?: VLCVideoLayout(context).also { created ->
            videoLayout = created
            created.addOnLayoutChangeListener(layoutGuardListener)
            // 第三个参数为 false：字幕直接叠加在视频输出上，不需要独立字幕面。
            mediaPlayer.attachViews(created, null, false, false)
        }
        host.attachPlayerView(layout)
        // VLCVideoLayout 挂到窗口时会把自己的 LayoutParams 改成 MATCH_PARENT，
        // 所以每次 attach 都重新把画面框算一遍（重复调用只会比对、不会反复布局）。
        applySurfaceBox()
        applyResizeMode()
        val media = pendingMedia
        if (media != null && !mediaPlayer.hasMedia()) {
            load(media, pendingStartMs, pendingAutoPlay)
        }
    }

    override fun setMedia(media: EngineMedia, startPositionMs: Long, autoPlay: Boolean) {
        if (released) return
        // 换集/换地址时重新从硬解开始尝试；同一集重试则沿用上次的软解结论。
        if (media.mediaId != pendingMedia?.mediaId || media.url != pendingMedia?.url) {
            softwareDecodeOnly = decoderMode == DecoderMode.SOFTWARE
        }
        if (media.mediaId != pendingMedia?.mediaId) {
            proxyAttempted = false
        }
        stallToken++
        pendingMedia = media
        pendingStartMs = startPositionMs.coerceAtLeast(0L)
        pendingAutoPlay = autoPlay
        ended = false
        pendingSeekMs = pendingStartMs
        if (videoLayout != null) {
            load(media, pendingStartMs, autoPlay)
        }
    }

    private fun load(media: EngineMedia, startPositionMs: Long, autoPlay: Boolean) {
        runCatching { mediaPlayer.stop() }
        playing = false
        userPaused = false
        truncateVlcLog()
        // 系统代理：手机挂代理（fake-IP）时，VLC 直连解析出来的地址是不通的，
        // 必须和 OkHttp 一样把请求交给系统代理。
        val systemProxy = systemHttpProxyFor(media.url)
        DiagnosticsLog.i(
            "Player",
            "LibVLC 打开 host=${mediaHost(media.url)} 软解=$softwareDecodeOnly " +
                "代理=${systemProxy?.substringAfter("://") ?: "无"}",
        )
        val libVlcMedia = Media(libVlc, Uri.parse(media.url)).apply {
            when {
                softwareDecodeOnly -> {
                    // 用户选择软件解码，或自动模式已经失败过一次：
                    // 这一条媒体强制走 FFmpeg 软解。
                    setHWDecoderEnabled(false, false)
                    addOption(":avcodec-hw=none")
                }
                decoderMode == DecoderMode.HARDWARE -> {
                    // 显式硬件解码：force=true，禁止 VLC 自动回退软解。
                    setHWDecoderEnabled(true, true)
                }
                else -> setHWDecoderEnabled(true, false)
            }
            addOption(":network-caching=3000")
            if (systemProxy != null) addOption(":http-proxy=$systemProxy")
            // 115 直链绑定换取时的 UA：服务端 /api/playback/{id}/info 预置的
            // 直链是按 App 统一 UA 换的，LibVLC 默认 UA 会被 CDN 403。
            addOption(":http-user-agent=${APP_USER_AGENT}")
        }
        mediaPlayer.media = libVlcMedia
        libVlcMedia.release()
        // 按用户偏好选一条默认字幕：ASS 原文件优先用 libass 渲染，
        // 其余外挂字幕按「中文/英文/日文优先」匹配；关闭自动加载时一条都不选。
        // 「跟随片源」保持旧行为：优先挑一条 ASS，让 libass 还原原始样式。
        val preferred = when {
            !media.subtitlePreference.autoLoad -> null
            media.subtitlePreference.priority == SubtitlePriority.FOLLOW_SOURCE ->
                media.subtitles.firstOrNull { it.assUrl != null } ?: media.subtitles.firstOrNull()

            else -> SubtitleSelector.choose(media.subtitles, media.subtitlePreference)
        }
        val preferredIndex = preferred?.let { media.subtitles.indexOf(it) } ?: -1
        // LibVLC 3 没有给外挂字幕起名的接口，面板里只会显示「Track N」。
        // 记住实际挂上去的字幕顺序，等 VLC 报出轨道后用这些名字覆盖。
        val addedLabels = mutableListOf<String>()
        media.subtitles.forEachIndexed { index, subtitle ->
            // ASS/SSA 优先用原文件地址：libVLC 的 libass 会保留字体、颜色、
            // 描边、定位与特效；服务端转出的 WebVTT 已经把这些信息剥掉了。
            // 只自动选中一条，其余留给字幕面板手动切换。
            val source = subtitle.assUrl ?: subtitle.url
            runCatching {
                mediaPlayer.addSlave(
                    IMedia.Slave.Type.Subtitle,
                    Uri.parse(source),
                    index == preferredIndex,
                )
            }.onSuccess { added ->
                if (added) {
                    addedLabels += subtitle.label.ifBlank { "字幕 ${index + 1}" }
                }
            }.onFailure { error ->
                Log.w(TAG, "add subtitle failed: $source", error)
                DiagnosticsLog.w("Player", "LibVLC 加载字幕失败", error)
            }
        }
        slaveLabels = addedLabels
        pendingSeekMs = startPositionMs.coerceAtLeast(0L)
        pendingAutoPlay = autoPlay
        mediaPlayer.play()
        armStallWatchdog()
    }

    /**
     * 起播看门狗。正常起播由 [MediaPlayer.Event.Playing] 作废；
     * 超时仍未起播则按“打开失败”走软解/中转/报错三个分支。
     */
    private fun armStallWatchdog() {
        val token = ++stallToken
        mainHandler.postDelayed({
            if (released || token != stallToken || userPaused) return@postDelayed
            if (playing || runCatching { mediaPlayer.isPlaying }.getOrDefault(false)) return@postDelayed
            val stats = runCatching { mediaPlayer.media?.stats }.getOrNull()
            handleOpenFailure(
                media = pendingMedia,
                stats = stats,
                reason = "LibVLC ${STALL_TIMEOUT_MS / 1000} 秒未起播",
            )
        }, STALL_TIMEOUT_MS)
    }

    /**
     * 打开/起播失败后的三级降级：
     * 1. 一个字节都没读到 → 先改走服务端中转（链接/网络被拒）；
     * 2. 读到了但起不来 → 先改软件解码（Hi10P 硬解可能直接卡住）；
     * 3. 软解也不行 → 再试一次中转；
     * 4. 都不行才报错。
     */
    private fun handleOpenFailure(
        media: EngineMedia?,
        stats: IMedia.Stats?,
        reason: String,
    ) {
        val noData = (stats?.readBytes ?: 0) <= 0 && (stats?.demuxReadBytes ?: 0) <= 0
        val detail = "host=${media?.let { mediaHost(it.url) }.orEmpty()} " +
            "read=${stats?.readBytes ?: -1} demux=${stats?.demuxReadBytes ?: -1} " +
            "decoded=${stats?.decodedVideo ?: -1}"
        // 把 VLC 自己的日志一起留下来：网络失败时只有它能说明是 DNS、
        // 建连还是 HTTP 状态码的问题。
        dumpVlcLog(reason, detail)
        when {
            noData && !proxyAttempted && media?.proxyUrl != null -> {
                switchToProxy(media, reason, detail)
            }
            !softwareDecodeOnly && decoderMode != DecoderMode.HARDWARE && media != null -> {
                retryWithSoftware(media, reason, detail)
            }
            !proxyAttempted && media?.proxyUrl != null -> {
                switchToProxy(media, reason, detail)
            }
            else -> {
                DiagnosticsLog.e("Player", "$reason（软解+中转均失败） $detail")
                playing = false
                listener?.onError(
                    message = "LibVLC 无法播放该视频（已尝试软件解码）",
                    decodeRelated = false,
                )
            }
        }
    }

    private fun switchToProxy(media: EngineMedia, reason: String, detail: String) {
        val proxyUrl = media.proxyUrl ?: return
        proxyAttempted = true
        DiagnosticsLog.w("Player", "$reason：改走服务端中转 $detail")
        val resume = positionMs().takeIf { it > 0L } ?: pendingStartMs
        val proxied = media.copy(url = proxyUrl, proxyUrl = null)
        pendingMedia = proxied
        playing = false
        listener?.onBufferingChanged(true)
        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
        listener?.onMediaReloaded()
        mainHandler.post { if (!released) load(proxied, resume, pendingAutoPlay) }
    }

    private fun retryWithSoftware(media: EngineMedia, reason: String, detail: String) {
        softwareDecodeOnly = true
        DiagnosticsLog.w("Player", "$reason：改用软件解码重试 $detail")
        val resume = positionMs().takeIf { it > 0L } ?: pendingStartMs
        playing = false
        listener?.onBufferingChanged(true)
        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
        listener?.onMediaReloaded()
        mainHandler.post { if (!released) load(media, resume, pendingAutoPlay) }
    }

    override fun play() {
        if (released) return
        userPaused = false
        if (ended) {
            ended = false
            runCatching { mediaPlayer.setTime(0L) }
        }
        runCatching { mediaPlayer.play() }
    }

    override fun pause() {
        if (released) return
        userPaused = true
        runCatching { mediaPlayer.pause() }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        ended = false
        runCatching { mediaPlayer.setTime(positionMs.coerceAtLeast(0L)) }
    }

    override fun retry() {
        val media = pendingMedia ?: return
        if (released) return
        load(media, positionMs(), true)
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        this.speed = speed
        runCatching { mediaPlayer.setRate(speed) }
        listener?.onSpeedChanged(speed)
    }

    override fun setResizeMode(mode: EngineResizeMode) {
        if (mode == resizeMode) return
        resizeMode = mode
        applySurfaceBox()
        applyResizeMode()
    }

    private fun applyResizeMode() {
        if (released) return
        val scale = when (resizeMode) {
            EngineResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            EngineResizeMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            EngineResizeMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        }
        runCatching { mediaPlayer.setVideoScale(scale) }
    }

    /**
     * 自己决定画面在宿主里的尺寸和位置。
     *
     * LibVLC 3.6 的 `VideoHelper.updateVideoSurfaces()` 只把 SurfaceView 的
     * 绝对宽高按视频比例写死，不会去纠正输出区域相对宿主的落点：宿主比
     * 画面框宽时，画面就贴着左上角、右边留一整条黑边（软解、硬解都一样）。
     * 所以这里按视频宽高比把 [VLCVideoLayout] 本身缩到画面框并居中，
     * VLC 再按这个尺寸算出来的输出区域与画面框完全重合，两侧黑边等宽。
     */
    private fun applySurfaceBox() {
        if (released) return
        val layout = videoLayout ?: return
        val host = host ?: return
        val box = videoSurfaceBox(host.width, host.height, videoAspect, resizeMode) ?: return
        val current = layout.layoutParams as? FrameLayout.LayoutParams
        if (current != null &&
            current.width == box.width &&
            current.height == box.height &&
            current.gravity == Gravity.CENTER
        ) {
            return
        }
        layout.layoutParams = FrameLayout.LayoutParams(box.width, box.height, Gravity.CENTER)
    }

    /** 从当前视频轨道读显示宽高比；轨道还没就绪时返回 null。 */
    private fun refreshVideoAspect() {
        val track = runCatching { mediaPlayer.currentVideoTrack }.getOrNull()
        val aspect = track?.let {
            videoDisplayAspect(
                width = it.width,
                height = it.height,
                sarNum = it.sarNum,
                sarDen = it.sarDen,
                rotated = isQuarterTurn(it.orientation),
            )
        }
        if (aspect != videoAspect) {
            videoAspect = aspect
            applySurfaceBox()
        }
    }

    override fun selectVideoTrack(trackId: String?) {
        if (released) return
        val id = trackId?.toIntOrNull() ?: defaultVideoId ?: return
        runCatching { mediaPlayer.setVideoTrack(id) }
        refreshTracks()
    }

    override fun selectAudioTrack(trackId: String?) {
        if (released) return
        val id = trackId?.toIntOrNull() ?: defaultAudioId ?: return
        runCatching { mediaPlayer.setAudioTrack(id) }
        refreshTracks()
    }

    override fun selectTextTrack(trackId: String?) {
        if (released) return
        // -1 是 libVLC 表示“关闭字幕”的固定 id。
        runCatching { mediaPlayer.setSpuTrack(trackId?.toIntOrNull() ?: -1) }
        refreshTracks()
    }

    override fun playbackState(): EnginePlaybackState = when {
        ended -> EnginePlaybackState.ENDED
        playing -> EnginePlaybackState.READY
        else -> EnginePlaybackState.IDLE
    }

    override fun positionMs(): Long = runCatching {
        mediaPlayer.time.coerceAtLeast(0L)
    }.getOrDefault(0L)

    override fun durationMs(): Long = runCatching {
        mediaPlayer.length.coerceAtLeast(0L)
    }.getOrDefault(0L)

    override fun bufferedMs(): Long {
        val duration = durationMs()
        if (duration <= 0L) return 0L
        val position = runCatching { mediaPlayer.position }.getOrDefault(0f)
        return (position.coerceIn(0f, 1f) * duration).toLong()
    }

    override fun isPlaying(): Boolean = !released && runCatching { mediaPlayer.isPlaying }.getOrDefault(false)

    override fun release() {
        if (released) return
        released = true
        listener = null
        host?.removeOnLayoutChangeListener(hostLayoutListener)
        videoLayout?.removeOnLayoutChangeListener(layoutGuardListener)
        slaveLabels = emptyList()
        truncateVlcLog()
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        (videoLayout?.parent as? android.view.ViewGroup)?.removeView(videoLayout)
        videoLayout = null
        host = null
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun refreshTracks() {
        if (released) return
        val audioDescriptions = runCatching { mediaPlayer.audioTracks }.getOrNull().orEmpty()
        val spuDescriptions = runCatching { mediaPlayer.spuTracks }.getOrNull().orEmpty()
        val videoDescriptions = runCatching { mediaPlayer.videoTracks }.getOrNull().orEmpty()

        val currentAudio = runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
        val currentSpu = runCatching { mediaPlayer.spuTrack }.getOrDefault(-1)
        val currentVideo = runCatching { mediaPlayer.videoTrack }.getOrDefault(-1)

        val audio = audioDescriptions
            .filter { it.id >= 0 }
            .map { description ->
                EngineTrack(
                    id = description.id.toString(),
                    label = description.name?.takeIf { it.isNotBlank() } ?: "音轨 ${description.id}",
                    type = EngineTrackType.AUDIO,
                    selected = description.id == currentAudio,
                )
            }
        val text = labelTextTracks(
            spuDescriptions
                .filter { it.id >= 0 }
                .map { description ->
                    EngineTrack(
                        id = description.id.toString(),
                        label = description.name?.takeIf { it.isNotBlank() } ?: "字幕 ${description.id}",
                        type = EngineTrackType.TEXT,
                        selected = description.id == currentSpu,
                    )
                },
        )
        val activeVideoHeight = runCatching { mediaPlayer.currentVideoTrack?.height }
            .getOrNull() ?: 0
        val video = videoDescriptions
            .filter { it.id >= 0 }
            .mapIndexed { index, description ->
                EngineTrack(
                    id = description.id.toString(),
                    label = if (description.id == currentVideo && activeVideoHeight > 0) {
                        "${activeVideoHeight}p"
                    } else {
                        description.name?.takeIf { it.isNotBlank() } ?: "画面 ${index + 1}"
                    },
                    type = EngineTrackType.VIDEO,
                    selected = description.id == currentVideo,
                )
            }

        if (video.isNotEmpty()) defaultVideoId = video.first().id.toIntOrNull()
        if (audio.isNotEmpty()) defaultAudioId = audio.first().id.toIntOrNull()

        // 轨道就绪后才知道画面宽高比，用来收紧画面框；切清晰度也会走到这里。
        refreshVideoAspect()

        listener?.onTracksChanged(EngineTracks(video = video, audio = audio, text = text))
    }

    /**
     * 把 LibVLC 的字幕轨命名换成能看懂的名称。
     *
     * VLC 对内嵌轨给的是 `Track 1 - [Chinese]`，对 `addSlave` 挂上去的外挂轨
     * 只给 `Track 2`；外挂轨永远排在内嵌轨之后，且顺序与添加顺序一致，
     * 因此数量对得上时用 [slaveLabels] 逐个覆盖，对不上就只美化内嵌轨，
     * 避免某条字幕加载失败时把名字张冠李戴。
     */
    private fun labelTextTracks(tracks: List<EngineTrack>): List<EngineTrack> {
        // nativeTrackName 对不是「Track N」的命名会原样返回，这里可以无条件过一遍。
        val trimmed = tracks.mapIndexed { index, track ->
            track.copy(label = SubtitleLabel.nativeTrackName(track.label, index))
        }
        if (slaveLabels.isEmpty() || trimmed.size < slaveLabels.size) return trimmed
        val firstSlave = trimmed.size - slaveLabels.size
        return trimmed.mapIndexed { index, track ->
            if (index >= firstSlave) track.copy(label = slaveLabels[index - firstSlave]) else track
        }
    }

    /** 只记录主机名，避免把带签名的完整直链写进日志。 */
    private fun mediaHost(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull().orEmpty()

    /** 清空 VLC 日志，避免 verbose 日志无限增长。 */
    private fun truncateVlcLog() {
        runCatching { if (vlcLogFile.length() > 0) vlcLogFile.writeText("") }
    }

    /**
     * 把 VLC 日志的尾部写进诊断日志。
     *
     * `--verbose=2` 里能直接看到 `[http]`/`[access]` 模块的报错（DNS 失败、
     * 建连超时、HTTP 403 等），比 `read=0` 有意义得多；只取最后 16 KB。
     */
    private fun dumpVlcLog(reason: String, detail: String) {
        runCatching {
            if (!vlcLogFile.exists()) return
            val bytes = vlcLogFile.readBytes()
            if (bytes.isEmpty()) return
            val from = (bytes.size - VLC_LOG_TAIL_BYTES).coerceAtLeast(0)
            val tail = String(bytes, from, bytes.size - from, Charsets.UTF_8)
            DiagnosticsLog.w("VlcLog", "$reason $detail\n$tail")
            truncateVlcLog()
        }
    }

    private companion object {
        const val TAG = "VlcVideoEngine"

        /** 起播超过这个时间还没进入 Playing，就按打开失败处理。 */
        const val STALL_TIMEOUT_MS = 10_000L

        /** 失败时最多摘取这么多 VLC 日志（约 16 KB）。 */
        const val VLC_LOG_TAIL_BYTES = 16 * 1024
    }
}
