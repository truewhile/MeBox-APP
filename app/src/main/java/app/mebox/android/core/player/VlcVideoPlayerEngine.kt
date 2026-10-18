package app.mebox.android.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.network.APP_USER_AGENT
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
) : VideoPlayerEngine {

    override val kind: PlayerEngineKind = PlayerEngineKind.VLC

    override var listener: VideoPlayerEngineListener? = null

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
        ),
    )

    private val mediaPlayer = MediaPlayer(libVlc)

    private var host: FrameLayout? = null
    private var videoLayout: VLCVideoLayout? = null
    private var resizeMode: EngineResizeMode = EngineResizeMode.FIT
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
    private var softwareDecodeOnly = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 直连被 CDN 拒绝后是否已经尝试过服务端中转，避免来回重试。 */
    private var proxyAttempted = false

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
                    ended = true
                    listener?.onPlayingChanged(false)
                    listener?.onPlaybackStateChanged(EnginePlaybackState.ENDED)
                    listener?.onEnded()
                }

                MediaPlayer.Event.EncounteredError -> {
                    val media = pendingMedia
                    val stats = runCatching { mediaPlayer.media?.stats }.getOrNull()
                    val noData = (stats?.readBytes ?: 0) <= 0 && (stats?.demuxReadBytes ?: 0) <= 0
                    // 一个字节都没读到，基本是 CDN 403/404 或链接过期。改走服务端
                    // 同源转发（服务端到 CDN 的线路通常可用），不要在这里硬试软解。
                    if (!proxyAttempted && media?.proxyUrl != null && noData) {
                        proxyAttempted = true
                        val resume = positionMs().takeIf { it > 0L } ?: pendingStartMs
                        DiagnosticsLog.w(
                            "Player",
                            "LibVLC 直连未读到数据，改走服务端中转 host=${mediaHost(media.url)}",
                        )
                        val proxied = media.copy(url = media.proxyUrl, proxyUrl = null)
                        pendingMedia = proxied
                        listener?.onBufferingChanged(true)
                        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
                        listener?.onMediaReloaded()
                        mainHandler.post {
                            if (!released) load(proxied, resume, pendingAutoPlay)
                        }
                        return@setEventListener
                    }
                    // 第一次失败先怀疑硬解：常见于 Hi10P 这类没有硬件解码器的编码。
                    // 只对当前媒体改走 FFmpeg 软解重试一次，不把结论写死到全局。
                    if (!softwareDecodeOnly && media != null) {
                        softwareDecodeOnly = true
                        val resume = positionMs().takeIf { it > 0L } ?: pendingStartMs
                        DiagnosticsLog.w(
                            "Player",
                            "LibVLC 硬解失败，改用软件解码重试 host=${mediaHost(media.url)} " +
                                "read=${stats?.readBytes ?: -1} demux=${stats?.demuxReadBytes ?: -1} " +
                                "decoded=${stats?.decodedVideo ?: -1}",
                        )
                        playing = false
                        listener?.onBufferingChanged(true)
                        listener?.onPlaybackStateChanged(EnginePlaybackState.BUFFERING)
                        listener?.onMediaReloaded()
                        mainHandler.post {
                            if (!released) load(media, resume, pendingAutoPlay)
                        }
                        return@setEventListener
                    }
                    DiagnosticsLog.e(
                        "Player",
                        "LibVLC 播放失败（软解） " +
                            "host=${media?.let { mediaHost(it.url) }.orEmpty()} " +
                            "read=${stats?.readBytes ?: -1} demux=${stats?.demuxReadBytes ?: -1} " +
                            "decoded=${stats?.decodedVideo ?: -1}",
                    )
                    playing = false
                    listener?.onError(
                        message = if (softwareDecodeOnly) {
                            "LibVLC 无法播放该视频（已尝试软件解码）"
                        } else {
                            "LibVLC 无法播放该视频"
                        },
                        decodeRelated = false,
                    )
                }

                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted,
                MediaPlayer.Event.ESSelected,
                -> refreshTracks()

                MediaPlayer.Event.LengthChanged -> {
                    listener?.onPlaybackStateChanged(EnginePlaybackState.READY)
                }
            }
        }
    }

    override fun attach(host: FrameLayout) {
        if (released) return
        this.host = host
        val layout = videoLayout ?: VLCVideoLayout(context).also { created ->
            videoLayout = created
            // 第三个参数为 false：字幕直接叠加在视频输出上，不需要独立字幕面。
            mediaPlayer.attachViews(created, null, false, false)
        }
        host.attachPlayerView(layout)
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
            softwareDecodeOnly = false
        }
        if (media.mediaId != pendingMedia?.mediaId) {
            proxyAttempted = false
        }
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
        DiagnosticsLog.i(
            "Player",
            "LibVLC 打开 host=${mediaHost(media.url)} 软解=$softwareDecodeOnly",
        )
        val libVlcMedia = Media(libVlc, Uri.parse(media.url)).apply {
            if (softwareDecodeOnly) {
                // 硬解已经失败过一次：这一条媒体强制走 FFmpeg 软解。
                setHWDecoderEnabled(false, false)
                addOption(":avcodec-hw=none")
            } else {
                setHWDecoderEnabled(true, false)
            }
            addOption(":network-caching=3000")
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
            }.onFailure { error ->
                Log.w(TAG, "add subtitle failed: $source", error)
                DiagnosticsLog.w("Player", "LibVLC 加载字幕失败", error)
            }
        }
        pendingSeekMs = startPositionMs.coerceAtLeast(0L)
        pendingAutoPlay = autoPlay
        mediaPlayer.play()
    }

    override fun play() {
        if (released) return
        if (ended) {
            ended = false
            runCatching { mediaPlayer.setTime(0L) }
        }
        runCatching { mediaPlayer.play() }
    }

    override fun pause() {
        if (released) return
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
        val text = spuDescriptions
            .filter { it.id >= 0 }
            .map { description ->
                EngineTrack(
                    id = description.id.toString(),
                    label = description.name?.takeIf { it.isNotBlank() } ?: "字幕 ${description.id}",
                    type = EngineTrackType.TEXT,
                    selected = description.id == currentSpu,
                )
            }
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

        listener?.onTracksChanged(EngineTracks(video = video, audio = audio, text = text))
    }

    /** 只记录主机名，避免把带签名的完整直链写进日志。 */
    private fun mediaHost(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull().orEmpty()

    private companion object {
        const val TAG = "VlcVideoEngine"
    }
}
