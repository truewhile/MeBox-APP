package app.mebox.android.core.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.media.SubtitleLabel
import app.mebox.android.core.network.APP_USER_AGENT
import app.mebox.android.core.player.vr.VrSettings
import app.mebox.android.core.player.vr.VrVideoView
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
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
    /** 字幕复用 App 的代理、TLS、连接池；不再让 VLC 同步打开远程字幕。 */
    httpClient: OkHttpClient? = null,
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
            // 强制 IPv4。移动数据下（尤其是 IPv6 优先 / DNS64 的运营商）经常
            // 解析出一个实际不通的 IPv6 地址：OkHttp 有 Happy Eyeballs 会回落
            // 到 IPv4，LibVLC 会一直等这个地址，表现就是 10 秒 read=0。
            "--ipv4",
            "--verbose=2",
            "--file-logging",
            "--logfile=${vlcLogFile.absolutePath}",
        ),
    ).also { truncateVlcLog() }

    private val mediaPlayer = MediaPlayer(libVlc)

    private var host: FrameLayout? = null
    private var videoLayout: VLCVideoLayout? = null

    /** VR 模式下的全景视图；进入 VR 时普通画面布局会被拆掉，退出时重建。 */
    private var vrView: VrVideoView? = null
    private var vrSettings: VrSettings? = null
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
    private var pendingSeekMs: Long = 0L
    private var ended: Boolean = false
    /** 用户播放意图；内部换地址/换软解时不变，避免暂停中被重新开播。 */
    private val intent = PlaybackIntent()
    private var released: Boolean = false
    private var playing: Boolean = false
    private var speed: Float = 1f

    /**
     * 硬解失败后只对当前媒体改走 FFmpeg 软解。
     *
     * 设备硬解能力按芯片和系统版本差异很大；部分编码（例如 10-bit 视频）
     * 触发 EncounteredError 时，VLC 不一定能自己回退，表现为“LibVLC 无法
     * 播放该视频”且时长 0。自动模式下这里主动用软件解码重试一次；
     * 用户强制硬件解码时尊重该选择，不隐式软解。
     */
    private var softwareDecodeOnly = decoderMode == DecoderMode.SOFTWARE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subtitleDownloader = SubtitleDownloader(
        httpClient ?: OkHttpClient(), File(context.cacheDir, "vlc-subtitles"),
    )
    private val subtitleLoader = PlaybackSubtitleLoader(
        scope = subtitleScope,
        download = subtitleDownloader::download,
        attach = { subtitle, file, select ->
            val added = !released && mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), select)
            if (added) {
                slaveLabels = slaveLabels + subtitle.label
                DiagnosticsLog.d("Player", "LibVLC 本地字幕已挂载 selected=$select")
            }
            added
        },
        onFailure = { _, error ->
            // 异常文本可能携带带 token 的 URL，只记录类型，字幕失败不升级成视频失败。
            DiagnosticsLog.w("Player", "LibVLC 外挂字幕加载失败，视频继续播放 type=${error.javaClass.simpleName}")
        },
    )

    /** 每轮装载独立计时；重试已排队时忽略同一轮的重复错误。 */
    private var startupWatchdog = PlaybackStartupWatchdog(SystemClock.elapsedRealtime())
    private var startupComplete = false
    private var lastBufferPercent = 0f
    private var loadGeneration = 0
    private var recoveryScheduled = false

    /** 直连被 CDN 拒绝后是否已经尝试过服务端中转，避免来回重试。 */
    private var proxyAttempted = false

    /** 直连被 CDN 拒绝后是否已经用「服务端重新换链」的地址重试过。 */
    private var refreshAttempted = false

    /** 是否已经试过把 https 直链降级成 http；每次换媒体重置。 */
    private var httpAttempted = false

    /**
     * 起播看门狗：LibVLC 出错时可能既不报错也不进入 Playing。
     * 根据轨道、缓冲和读取的进展判断停滞，并设置整体等待上限。
     */
    private var stallToken = 0

    /** 轨道 id 的默认值：用于「自动」清晰度/音轨。 */
    private var defaultVideoId: Int? = null
    private var defaultAudioId: Int? = null

    private fun installPlayerListener() {
        mediaPlayer.setEventListener { event ->
            if (released) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    DiagnosticsLog.d("Player", "LibVLC 事件 Opening")
                    listener?.onBufferingChanged(true)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
                }

                MediaPlayer.Event.Buffering -> {
                    if (event.buffering > lastBufferPercent) {
                        lastBufferPercent = event.buffering
                        startupWatchdog.recordProgress(SystemClock.elapsedRealtime())
                    }
                    val buffering = event.buffering < 100f
                    listener?.onBufferingChanged(buffering)
                    listener?.onPlaybackStateChanged(
                        if (buffering) EnginePlaybackState.BUFFERING else EnginePlaybackState.READY,
                    )
                }

                MediaPlayer.Event.Playing -> {
                    playing = true
                    startupComplete = true
                    DiagnosticsLog.i("Player", "LibVLC 事件 Playing")
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
                    if (!intent.playWhenReady) mediaPlayer.pause()
                    refreshTracks()
                    // 先有画面，再异步准备外挂字幕；VLC 永远不在起播时等待远端字幕。
                    subtitleLoader.onPlaying()
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
                    val stats = readMediaStats()
                    DiagnosticsLog.w("Player", "LibVLC 事件 EncounteredError")
                    handleOpenFailure(pendingMedia, stats, "LibVLC 播放失败")
                }

                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted,
                MediaPlayer.Event.ESSelected,
                // 视频输出建立后才知道画面尺寸：拿到宽高比重新算一次画面框。
                MediaPlayer.Event.Vout,
                -> {
                    if (event.type == MediaPlayer.Event.ESAdded ||
                        (event.type == MediaPlayer.Event.Vout && event.voutCount > 0)
                    ) {
                        startupWatchdog.recordProgress(SystemClock.elapsedRealtime())
                    }
                    DiagnosticsLog.d("Player", "LibVLC 事件 type=${event.type}")
                    refreshTracks()
                }

                MediaPlayer.Event.LengthChanged -> {
                    if (event.lengthChanged > 0L) startupWatchdog.recordProgress(SystemClock.elapsedRealtime())
                    // 识别到时长不等于已起播，保持缓冲状态，直到 Playing。
                    if (startupComplete) listener?.onPlaybackStateChanged(EnginePlaybackState.READY)
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
        if (vrSettings != null) {
            mountVrView(host)
        } else {
            mountNormalView(host)
        }
        val media = pendingMedia
        if (media != null && !mediaPlayer.hasMedia()) {
            load(media, pendingStartMs)
        }
    }

    override val supportsVr: Boolean = true

    override fun setVrSettings(settings: VrSettings?) {
        if (released || settings == vrSettings) return
        vrSettings = settings
        val host = host ?: return
        if (settings == null) {
            mountNormalView(host)
        } else if (vrView != null) {
            vrView?.setSettings(settings)
        } else {
            mountVrView(host)
        }
    }

    override fun recenterVr() {
        vrView?.recenter()
    }

    override fun rotateVr(dx: Float, dy: Float) {
        vrView?.dragBy(dx, dy)
    }

    private fun mountNormalView(host: FrameLayout) {
        // 从 VR 切回来 / 全新挂载：先拆掉 VR 视图并停止纹理输出。
        if (vrView != null) {
            runCatching { mediaPlayer.vlcVout.detachViews() }
        }
        vrView?.let { view ->
            view.onSurfaceTextureReady = null
            view.release()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        vrView = null

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
    }

    private fun mountVrView(host: FrameLayout) {
        val settings = vrSettings ?: return
        val existing = vrView
        if (existing != null) {
            existing.setSettings(settings)
            if (existing.parent !== host) {
                host.removeAllViews()
                host.addView(
                    existing,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            return
        }

        // 进入 VR 前必须让普通画面布局退出视频输出，否则 VLC 会继续往
        // SurfaceView 写帧，纹理拿不到数据。
        videoLayout?.let { layout ->
            runCatching { mediaPlayer.vlcVout.detachViews() }
            (layout.parent as? ViewGroup)?.removeView(layout)
        }
        videoLayout = null
        host.removeAllViews()

        val view = VrVideoView(context).also { created ->
            vrView = created
            created.setSettings(settings)
            created.onSurfaceTextureReady = ::attachVrSurface
        }
        host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * LibVLC 3.6 的 IVLCVout 支持直接把 OpenGL 输出写进 SurfaceTexture，
     * 与 TextureView 内部走的是同一条路径；这里不再需要 VLCVideoLayout。
     */
    private fun attachVrSurface(surfaceTexture: SurfaceTexture) {
        if (released || vrSettings == null) return
        runCatching {
            if (mediaPlayer.vlcVout.areViewsAttached()) {
                mediaPlayer.vlcVout.detachViews()
            }
            mediaPlayer.vlcVout.setVideoSurface(surfaceTexture)
            mediaPlayer.vlcVout.attachViews()
        }.onFailure { error ->
            DiagnosticsLog.w(
                "Player",
                "LibVLC VR 纹理输出初始化失败 type=${error.javaClass.simpleName}",
            )
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
            refreshAttempted = false
            httpAttempted = false
        }
        stallToken++
        pendingMedia = media
        pendingStartMs = startPositionMs.coerceAtLeast(0L)
        intent.onMediaLoaded(autoPlay)
        ended = false
        pendingSeekMs = pendingStartMs
        if (videoLayout != null) {
            load(media, pendingStartMs)
        }
    }

    private fun load(media: EngineMedia, startPositionMs: Long) {
        loadGeneration++
        stallToken++
        recoveryScheduled = false
        startupComplete = false
        startupWatchdog = PlaybackStartupWatchdog(SystemClock.elapsedRealtime())
        lastBufferPercent = 0f
        // setEventListener(null) 也会清空 VLC Java Handler 已排队的旧事件。
        // 否则上一轮迟到的 Playing/Stopped 可能改写新媒体的起播状态。
        mediaPlayer.setEventListener(null)
        runCatching { mediaPlayer.stop() }
        pendingMedia = media
        pendingStartMs = startPositionMs.coerceAtLeast(0L)
        playing = false
        videoAspect = null
        defaultVideoId = null
        defaultAudioId = null
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
        // 远程字幕不挂进初始媒体：它们曾在 VLC 输入线程逐条拉取，拖住 Playing。
        // prepare() 只登记列表，Playing 后才用 OkHttp 下载并挂载本地字幕。
        slaveLabels = emptyList()
        subtitleLoader.prepare(media)
        pendingSeekMs = startPositionMs.coerceAtLeast(0L)
        installPlayerListener()
        mediaPlayer.play()
        armStallWatchdog()
    }

    /**
     * 起播看门狗。正常起播由 [MediaPlayer.Event.Playing] 作废；
     * 超时仍未起播则按“打开失败”走软解/中转/报错三个分支。
     */
    private fun armStallWatchdog() {
        val token = ++stallToken
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (released || token != stallToken || !intent.playWhenReady || startupComplete || recoveryScheduled) return
                if (playing || runCatching { mediaPlayer.isPlaying }.getOrDefault(false)) return
                val stats = readMediaStats()
                val now = SystemClock.elapsedRealtime()
                startupWatchdog.observeBytes(
                    now, stats?.readBytes?.toLong() ?: -1L, stats?.demuxReadBytes?.toLong() ?: -1L,
                )
                if (startupWatchdog.hasTimedOut(now)) {
                    handleOpenFailure(pendingMedia, stats, "LibVLC 起播超时（持续无进展或达到等待上限）")
                } else {
                    mainHandler.postDelayed(this, STARTUP_POLL_MS)
                }
            }
        }, STARTUP_POLL_MS)
    }

    /** getMedia() 会 retain，统计读取后必须 release，避免每秒泄漏一个原生引用。 */
    private fun readMediaStats(): IMedia.Stats? = runCatching {
        val media = mediaPlayer.media ?: return@runCatching null
        try { media.stats } finally { media.release() }
    }.getOrNull()

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
        if (released || recoveryScheduled) return
        recoveryScheduled = true
        stallToken++
        startupWatchdog.observeBytes(
            SystemClock.elapsedRealtime(), stats?.readBytes?.toLong() ?: -1L,
            stats?.demuxReadBytes?.toLong() ?: -1L,
        )
        // 字节统计在 Opening 阶段不可靠；只要解析过轨道/缓冲推进，就不能说没读到数据。
        val noData = !startupWatchdog.hasProgress
        val detail = "host=${media?.let { mediaHost(it.url) }.orEmpty()} " +
            "read=${stats?.readBytes ?: -1} demux=${stats?.demuxReadBytes ?: -1} " +
            "decoded=${stats?.decodedVideo ?: -1} progress=${startupWatchdog.hasProgress}"
        // 把 VLC 自己的日志一起留下来：网络失败时只有它能说明是 DNS、
        // 建连还是 HTTP 状态码的问题。
        logDnsAddresses(media?.url)
        dumpVlcLog(reason, detail)
        when {
            // 115 直链同时支持 http/https。个别网络（运营商侧）会把 LibVLC
            // 这种非浏览器 TLS 握手卡住，OkHttp/ExoPlayer 却正常；降到明文
            // http 往往能直接绕开，先试一次再谈中转。
            noData && !httpAttempted && media != null && httpVariantOf(media.url) != null -> {
                retryWithHttp(media, reason, detail)
            }
            // 一个字节都没读到、且手上是服务端发的直链时，先让服务端重新换链
            // （115 直链 30 分钟过期，403 是典型表现），比直接压到服务器中转省带宽。
            noData && !refreshAttempted && media?.refreshUrl != null -> {
                retryWithFreshLink(media, reason, detail)
            }
            noData && !proxyAttempted && media?.proxyUrl != null -> {
                switchToProxy(media, reason, detail)
            }
            !noData && !softwareDecodeOnly && decoderMode != DecoderMode.HARDWARE && media != null -> {
                retryWithSoftware(media, reason, detail)
            }
            !proxyAttempted && media?.proxyUrl != null -> {
                switchToProxy(media, reason, detail)
            }
            else -> {
                DiagnosticsLog.e("Player", "$reason（可用回退已用尽） $detail")
                playing = false
                startupComplete = false
                mediaPlayer.setEventListener(null)
                runCatching { mediaPlayer.stop() }
                subtitleLoader.close()
                listener?.onPlayingChanged(false)
                listener?.onBufferingChanged(false)
                listener?.onPlaybackStateChanged(EnginePlaybackState.IDLE)
                listener?.onError(
                    message = if (noData) "视频网络访问失败，请检查网络或重试"
                        else "LibVLC 无法打开或解码该视频，请重试或切换内核",
                    decodeRelated = !noData,
                )
            }
        }
    }

    /** 只允许已知 115 CDN 直链的兼容重试，不降级服务器的 HTTPS 地址。 */
    private fun httpVariantOf(url: String): String? = cdnHttpFallbackUrl(url)

    private fun retryWithHttp(media: EngineMedia, reason: String, detail: String) {
        val httpUrl = httpVariantOf(media.url) ?: return
        httpAttempted = true
        DiagnosticsLog.w("Player", "$reason：改用 http 重试 $detail")
        val resume = resumeTargetMs()
        val plain = media.copy(url = httpUrl)
        pendingMedia = plain
        playing = false
        listener?.onBufferingChanged(true)
        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
        listener?.onMediaReloaded()
        postReload(plain, resume)
    }

    /**
     * 用「服务端重新换链」的地址重试：请求 /api/stream/{id}?refresh=1，服务端
     * 丢掉过期的云盘直链缓存、重新换链后 302 到新直链，VLC 跟随 302 仍然直连 CDN。
     */
    private fun retryWithFreshLink(media: EngineMedia, reason: String, detail: String) {
        val refreshUrl = media.refreshUrl ?: return
        refreshAttempted = true
        DiagnosticsLog.w("Player", "$reason：改走服务端重新换链 $detail")
        val resume = resumeTargetMs()
        val refreshed = media.copy(url = refreshUrl, refreshUrl = null)
        pendingMedia = refreshed
        playing = false
        listener?.onBufferingChanged(true)
        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
        listener?.onMediaReloaded()
        postReload(refreshed, resume)
    }

    private fun switchToProxy(media: EngineMedia, reason: String, detail: String) {
        val proxyUrl = media.proxyUrl ?: return
        proxyAttempted = true
        DiagnosticsLog.w("Player", "$reason：改走服务端中转 $detail")
        val resume = resumeTargetMs()
        val proxied = media.copy(url = proxyUrl, proxyUrl = null)
        pendingMedia = proxied
        playing = false
        listener?.onBufferingChanged(true)
        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
        listener?.onMediaReloaded()
        postReload(proxied, resume)
    }

    private fun retryWithSoftware(media: EngineMedia, reason: String, detail: String) {
        softwareDecodeOnly = true
        DiagnosticsLog.w("Player", "$reason：改用软件解码重试 $detail")
        val resume = resumeTargetMs()
        playing = false
        listener?.onBufferingChanged(true)
        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
        listener?.onMediaReloaded()
        postReload(media, resume)
    }

    private fun postReload(media: EngineMedia, resume: Long) {
        val generation = loadGeneration
        mainHandler.post {
            if (!released && generation == loadGeneration && pendingMedia?.mediaId == media.mediaId) {
                load(media, resume)
            }
        }
    }

    override fun play() {
        if (released) return
        intent.onPlay()
        if (!startupComplete && !recoveryScheduled) {
            startupWatchdog = PlaybackStartupWatchdog(SystemClock.elapsedRealtime())
            armStallWatchdog()
        }
        if (ended) {
            ended = false
            runCatching { mediaPlayer.setTime(0L) }
        }
        runCatching { mediaPlayer.play() }
    }

    override fun pause() {
        if (released) return
        intent.onPause()
        stallToken++
        runCatching { mediaPlayer.pause() }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        ended = false
        val target = positionMs.coerceAtLeast(0L)
        // 尚未起播时 libVLC 的 setTime 可能被后续解析覆盖；记下来，Playing 时再定位。
        if (!startupComplete) pendingSeekMs = target
        runCatching { mediaPlayer.setTime(target) }
    }

    override fun retry() {
        val media = pendingMedia ?: return
        if (released) return
        intent.onPlay()
        load(media, resumeTargetMs())
    }

    /**
     * 重新装载时的续播点：优先当前播放位置，其次尚未生效的 seek 目标，
     * 最后才是本轮装载的起始位置。
     */
    private fun resumeTargetMs(): Long =
        positionMs().takeIf { it > 0L }
            ?: pendingSeekMs.takeIf { it > 0L }
            ?: pendingStartMs

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
        if (released || vrView != null) return
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
        if (released || vrView != null) return
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
        subtitleLoader.onUserSelection()
        runCatching { mediaPlayer.setSpuTrack(trackId?.toIntOrNull() ?: -1) }
        refreshTracks()
    }

    /**
     * 播放页正在恢复用户之前选中的字幕（或用户明确关闭了字幕）。
     * 外挂字幕稍后才下载，先禁止自动选中，避免短暂覆盖用户意图。
     */
    override fun holdSubtitleAutoSelection() {
        subtitleLoader.holdAutoSelection()
    }

    override fun playbackState(): EnginePlaybackState = when {
        ended -> EnginePlaybackState.ENDED
        startupComplete -> EnginePlaybackState.READY
        !recoveryScheduled && pendingMedia != null -> EnginePlaybackState.BUFFERING
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
        stallToken++
        loadGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        listener = null
        host?.removeOnLayoutChangeListener(hostLayoutListener)
        videoLayout?.removeOnLayoutChangeListener(layoutGuardListener)
        slaveLabels = emptyList()
        truncateVlcLog()
        mediaPlayer.setEventListener(null)
        runCatching { mediaPlayer.stop() }
        subtitleLoader.close()
        subtitleScope.cancel()
        vrView?.let { view ->
            view.onSurfaceTextureReady = null
            view.release()
            (view.parent as? android.view.ViewGroup)?.removeView(view)
        }
        vrView = null
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
        // TrackDescription 只有显示名称，语言标签要从 IMedia 的轨道列表补。
        val trackLanguages = mediaTrackLanguages()

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
                    language = trackLanguages[description.id],
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
                        language = trackLanguages[description.id],
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
                    language = trackLanguages[description.id],
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

    /**
     * 当前媒体的轨道语言（ES id -> 语言标签）。
     *
     * `MediaPlayer.TrackDescription` 只带显示名称；跨内核恢复音轨/字幕时
     * 语言标签比名称更稳定。`mediaPlayer.media` 会 retain，读完必须 release。
     */
    private fun mediaTrackLanguages(): Map<Int, String> {
        val media = runCatching { mediaPlayer.media }.getOrNull() ?: return emptyMap()
        return try {
            buildMap {
                for (index in 0 until media.trackCount) {
                    val track = runCatching { media.getTrack(index) }.getOrNull() ?: continue
                    val language = track.language?.takeIf { it.isNotBlank() } ?: continue
                    put(track.id, language)
                }
            }
        } finally {
            runCatching { media.release() }
        }
    }

    /** 清空 VLC 日志，避免 verbose 日志无限增长。 */
    private fun truncateVlcLog() {
        runCatching { if (vlcLogFile.length() > 0) vlcLogFile.writeText("") }
    }

    /** 记录媒体主机的解析结果（含 IPv6），用于确认是否踩到不通的地址。 */
    private fun logDnsAddresses(url: String?) {
        val host = url?.let { mediaHost(it) }.orEmpty()
        if (host.isEmpty()) return
        Thread {
            val resolved = runCatching {
                InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: "?" }
            }.getOrElse { "解析失败：${it.javaClass.simpleName}" }
            DiagnosticsLog.w("Player", "LibVLC DNS $host → $resolved")
        }.start()
    }

    /**
     * 把 VLC 自己的日志写进诊断日志。
     *
     * 文件日志（`--file-logging`）在 VLC 运行期间不一定刷盘，实测拿不到内容；
     * 这里直接从 logcat 读本进程的日志——VLC 的 native 日志走
     * `__android_log_print`，tag 是 `VLC*`。App 只能读到自己 UID 的记录，
     * 正好覆盖本进程的 VLC 输出。
     */
    private fun dumpVlcLog(reason: String, detail: String) {
        val lines = runCatching {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "brief", "-t", "8000", "--pid=${android.os.Process.myPid()}",
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.lineSequence()
                // 只取 native VLC tag，避免把上一次诊断快照再次嵌套进来。
                .filter { Regex("^[VDIWEF]/VLC").containsMatchIn(it) }
                .toList()
        }.getOrNull().orEmpty()
        truncateVlcLog()
        if (lines.isEmpty()) {
            DiagnosticsLog.w("VlcLog", "$reason $detail（未取到 VLC 日志）")
            return
        }
        val tail = redactPlaybackLog(lines.takeLast(VLC_LOG_MAX_LINES).joinToString("\n"))
            .takeLast(VLC_LOG_MAX_CHARS)
        DiagnosticsLog.w("VlcLog", "$reason $detail\n$tail")
    }

    private companion object {
        const val TAG = "VlcVideoEngine"

        /** 定期观察进展，而不是固定十秒后中断。 */
        const val STARTUP_POLL_MS = 1_000L

        /** 留在单条诊断消息的 8 KB 上限内，并优先保留最新错误。 */
        const val VLC_LOG_MAX_LINES = 160
        const val VLC_LOG_MAX_CHARS = 7 * 1024
    }
}
