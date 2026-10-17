package app.mebox.android.core.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.FrameLayout
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
            "--avcodec-hw=any",
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
                    playing = false
                    listener?.onError(
                        message = "LibVLC 无法播放该视频",
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
        val libVlcMedia = Media(libVlc, Uri.parse(media.url)).apply {
            // 先尝试硬件解码，失败时 VLC 会自动回退到 FFmpeg 软解。
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=3000")
        }
        mediaPlayer.media = libVlcMedia
        libVlcMedia.release()
        // 有 ASS 轨道时默认选中它：用户切到 LibVLC 通常就是为了看原始样式。
        val preferredIndex = media.subtitles
            .indexOfFirst { it.assUrl != null }
            .takeIf { it >= 0 } ?: 0
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

    private companion object {
        const val TAG = "VlcVideoEngine"
    }
}
