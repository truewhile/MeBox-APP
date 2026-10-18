package app.mebox.android.core.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.network.APP_USER_AGENT
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 系统解码内核：AndroidX Media3 ExoPlayer。
 *
 * 优先使用设备硬件解码器，初始化失败时允许回退到系统软件解码器；
 * 解码全部在本机完成，App 不请求服务端转码。
 */
@OptIn(UnstableApi::class)
class ExoVideoPlayerEngine(
    private val context: Context,
    /** App 共享的网络客户端；传入后会复用它的连接池与已有的 TLS 连接。 */
    private val httpClient: OkHttpClient? = null,
    /** 解码方式：自动 / 强制硬件 / 强制软件。 */
    private val decoderMode: DecoderMode = DecoderMode.AUTO,
) : VideoPlayerEngine {

    override val kind: PlayerEngineKind = PlayerEngineKind.EXO

    override var listener: VideoPlayerEngineListener? = null

    private var host: FrameLayout? = null
    private var playerView: PlayerView? = null

    /**
     * 自带的字幕视图。Media3 的 `PlayerView` 对同一时刻的多条 cue 是逐条绘制，
     * 双语字幕会重叠，所以这里用额外的 [SubtitleView] 接管渲染。
     */
    private var subtitleOverlay: SubtitleView? = null
    private var resizeMode: EngineResizeMode = EngineResizeMode.FIT
    private var pendingMedia: EngineMedia? = null
    private var pendingStartMs: Long = 0L
    private var pendingAutoPlay: Boolean = true
    private var released: Boolean = false
    private var mediaLoaded: Boolean = false

    /** 直连被 CDN 拒绝后是否已经尝试过服务端中转，避免来回重试。 */
    private var proxyAttempted = false

    /** 轨道 id -> ExoPlayer 的覆盖规则；`onTracksChanged` 时重建。 */
    private var overrides: Map<String, TrackSelectionOverride> = emptyMap()

    // 用 lazy 延迟到构造完成后再创建 ExoPlayer：playerListener 在下方声明，
    // 若在属性初始化阶段直接 addListener(playerListener)，此时它仍是 null，
    // Media3 的 addListener 会立刻抛 NullPointerException。
    private val player: ExoPlayer by lazy(LazyThreadSafetyMode.NONE) {
        // 播放器本身只持有 Application Context；PlayerView 仍用 Activity Context，
        // 保证 ExoPlayer 的默认样式能解析主题属性。
        val appContext = context.applicationContext
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory())
        val renderersFactory = DefaultRenderersFactory(appContext).apply {
            // 强制硬件时不再回退其它解码器；自动/软件模式保留回退。
            setEnableDecoderFallback(decoderMode != DecoderMode.HARDWARE)
            when (decoderMode) {
                DecoderMode.SOFTWARE -> setMediaCodecSelector(SoftwareOnlySelector)
                DecoderMode.HARDWARE -> setMediaCodecSelector(HardwareOnlySelector)
                DecoderMode.AUTO -> Unit
            }
        }
        ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
            }
    }

    /**
     * 播放器的 HTTP 数据源。
     *
     * 传入共享 OkHttpClient 时复用它（以及它的连接池）：起播前刚请求完播放
     * 地址，TLS 连接还是热的，播放器打开视频流就不用再走一遍 DNS+TCP+TLS。
     * 手机到境外服务器的链路上，这一次建连实测常是数秒，正好压在起播上。
     *
     * 只去掉磁盘缓存：视频分片不应该写进 48MB 的 HTTP 缓存。
     */
    private fun httpDataSourceFactory(): HttpDataSource.Factory {
        val shared = httpClient
        if (shared != null) {
            val playbackClient = shared.newBuilder()
                .cache(null)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return OkHttpDataSource.Factory(playbackClient)
                .setUserAgent(APP_USER_AGENT)
        }
        // 没有共享客户端时（测试/独立使用）退回 Media3 自带实现。
        return DefaultHttpDataSource.Factory()
            // MeBox 会把网盘/远程 Emby 的播放重定向到 HTTPS 上游；默认
            // 不允许跨协议跳转，会表现为 302 播放失败。
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(APP_USER_AGENT)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val media = pendingMedia
            // CDN 直连被拒（403/404 等）时改走服务端同源转发：服务端到 CDN
            // 的线路通常没问题，客户端只需要连自己的 MeBox 服务器。
            if (!proxyAttempted &&
                media?.proxyUrl != null &&
                isDirectPlaybackFailure(error.errorCode)
            ) {
                proxyAttempted = true
                val resume = player.currentPosition.takeIf { it > 0L } ?: pendingStartMs
                DiagnosticsLog.w(
                    "Player",
                    "ExoPlayer 直连返回 ${error.errorCodeName}，改走服务端中转",
                )
                listener?.onMediaReloaded()
                setMedia(media.copy(url = media.proxyUrl, proxyUrl = null), resume, pendingAutoPlay)
                return
            }
            DiagnosticsLog.e(
                "Player",
                "ExoPlayer 播放失败 ${error.errorCodeName} (${error.errorCode})",
                error,
            )
            val message = when {
                isDecodeError(error.errorCode) -> "本机解码失败：${error.errorCodeName}"
                isParsingError(error.errorCode) -> "格式无法解析：${error.errorCodeName}"
                isIoError(error.errorCode) -> "网络或文件访问失败：${error.errorCodeName}"
                else -> "播放失败：${error.errorCodeName}"
            }
            listener?.onError(
                message = message,
                decodeRelated = isDecodeError(error.errorCode) || isParsingError(error.errorCode),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val state = when (playbackState) {
                Player.STATE_BUFFERING -> EnginePlaybackState.BUFFERING
                Player.STATE_READY -> EnginePlaybackState.READY
                Player.STATE_ENDED -> EnginePlaybackState.ENDED
                else -> EnginePlaybackState.IDLE
            }
            listener?.onBufferingChanged(state == EnginePlaybackState.BUFFERING)
            listener?.onPlaybackStateChanged(state)
            if (state == EnginePlaybackState.ENDED) listener?.onEnded()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            listener?.onPlayingChanged(playing)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            listener?.onSpeedChanged(playbackParameters.speed)
        }

        override fun onTracksChanged(tracks: Tracks) {
            publishTracks(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            // 双语字幕会把中文、日文各下发成一条 cue，同一位置逐条绘制会重叠；
            // 合并成一条多行 cue 后再交给 SubtitleView 排版。
            subtitleOverlay?.setCues(stackSubtitleCues(cueGroup.cues))
        }
    }

    override fun attach(host: FrameLayout) {
        if (released) return
        this.host = host
        val view = playerView ?: PlayerView(context).apply {
            useController = false
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            playerView = this
        }
        mountSubtitleOverlay(view)
        view.player = player
        view.resizeMode = resizeMode.toExoValue()
        host.attachPlayerView(view)
        startIfReady()
    }

    /**
     * 用自建的字幕视图替换 `PlayerView` 自带的那一个。
     *
     * 自带视图的 cue 由 `PlayerView` 内部直接写入，外部无法干预；这里把它隐藏，
     * 再在同一个父容器（视频画面框）里放一个新的 [SubtitleView]，由
     * [playerListener] 的 `onCues` 喂入合并后的 cue。新视图沿用相同的布局参数，
     * 字号、底部留白和宽高比行为与原来一致。
     */
    private fun mountSubtitleOverlay(view: PlayerView) {
        if (subtitleOverlay != null) return
        val builtIn = view.subtitleView ?: return
        val parent = builtIn.parent as? ViewGroup ?: view
        val params = builtIn.layoutParams
        val layoutParams = if (params != null) {
            FrameLayout.LayoutParams(params.width, params.height)
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val overlay = SubtitleView(context).apply {
            // 与 PlayerView 自带字幕视图一致：先套系统字幕字号/样式偏好，
            // 再用 App 固定的白字黑描边覆盖。
            setUserDefaultTextSize()
            setStyle(subtitleCaptionStyle())
            setLayoutParams(layoutParams)
        }
        builtIn.visibility = View.GONE
        // 放在自带字幕视图后面，保持绘制顺序与原来一致（视频 → 字幕 → 控制层）。
        val builtInIndex = parent.indexOfChild(builtIn)
        if (builtInIndex >= 0) {
            parent.addView(overlay, builtInIndex + 1)
        } else {
            parent.addView(overlay)
        }
        subtitleOverlay = overlay
        // 画面挂载时可能刚好播到一条字幕中间，先把当前的 cue 补上。
        overlay.setCues(stackSubtitleCues(player.currentCues.cues))
    }

    private fun subtitleCaptionStyle(): CaptionStyleCompat = CaptionStyleCompat(
        Color.WHITE,
        Color.TRANSPARENT,
        Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        Color.BLACK,
        null,
    )

    override fun setMedia(media: EngineMedia, startPositionMs: Long, autoPlay: Boolean) {
        // 换集时重置中转尝试；同一集内部切换到中转地址不会重置，避免循环。
        if (media.mediaId != pendingMedia?.mediaId) proxyAttempted = false
        pendingMedia = media
        pendingStartMs = startPositionMs.coerceAtLeast(0L)
        pendingAutoPlay = autoPlay
        mediaLoaded = false
        // 换成新集时旧轨道覆盖不再有效。
        overrides = emptyMap()
        startIfReady()
    }

    private fun startIfReady() {
        val media = pendingMedia ?: return
        // 画面视图还没挂上时先等待 attach，避免起播后又被重新 prepare。
        if (released || playerView == null || mediaLoaded) return
        mediaLoaded = true
        player.setMediaItem(media.toMediaItem(), pendingStartMs)
        player.prepare()
        player.playWhenReady = pendingAutoPlay
    }

    override fun play() {
        if (released) return
        player.play()
    }

    override fun pause() {
        if (released) return
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun retry() {
        if (released) return
        player.prepare()
        player.play()
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        player.setPlaybackSpeed(speed)
    }

    override fun setResizeMode(mode: EngineResizeMode) {
        resizeMode = mode
        playerView?.resizeMode = mode.toExoValue()
    }

    override fun selectVideoTrack(trackId: String?) {
        if (released) return
        val parameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        val override = trackId?.let { overrides[it] }
        if (override != null) parameters.setOverrideForType(override)
        player.trackSelectionParameters = parameters.build()
    }

    override fun selectAudioTrack(trackId: String?) {
        if (released) return
        val parameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        val override = trackId?.let { overrides[it] }
        if (override != null) parameters.setOverrideForType(override)
        player.trackSelectionParameters = parameters.build()
    }

    override fun selectTextTrack(trackId: String?) {
        if (released) return
        val parameters = player.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            overrides[trackId]?.let { parameters.setOverrideForType(it) }
        }
        player.trackSelectionParameters = parameters.build()
    }

    override fun playbackState(): EnginePlaybackState = when (player.playbackState) {
        Player.STATE_BUFFERING -> EnginePlaybackState.BUFFERING
        Player.STATE_READY -> EnginePlaybackState.READY
        Player.STATE_ENDED -> EnginePlaybackState.ENDED
        else -> EnginePlaybackState.IDLE
    }

    override fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    override fun durationMs(): Long {
        val duration = player.duration
        return if (duration > 0L && duration != C.TIME_UNSET) duration else 0L
    }

    override fun bufferedMs(): Long = player.bufferedPosition.coerceAtLeast(0L)

    override fun isPlaying(): Boolean = player.isPlaying

    override fun release() {
        if (released) return
        released = true
        player.removeListener(playerListener)
        playerView?.player = null
        subtitleOverlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        subtitleOverlay = null
        (playerView?.parent as? android.view.ViewGroup)?.removeView(playerView)
        playerView = null
        host = null
        player.release()
        listener = null
    }

    private fun publishTracks(tracks: Tracks) {
        val video = mutableListOf<EngineTrack>()
        val audio = mutableListOf<EngineTrack>()
        val text = mutableListOf<EngineTrack>()
        val newOverrides = mutableMapOf<String, TrackSelectionOverride>()

        tracks.groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val id = "${group.mediaTrackGroup.id}:$index"
                newOverrides[id] = TrackSelectionOverride(group.mediaTrackGroup, index)
                val choice = EngineTrack(
                    id = id,
                    label = when (group.type) {
                        C.TRACK_TYPE_VIDEO -> videoLabel(format, index)
                        C.TRACK_TYPE_AUDIO -> audioLabel(format, index)
                        else -> textLabel(format, index)
                    },
                    type = when (group.type) {
                        C.TRACK_TYPE_VIDEO -> EngineTrackType.VIDEO
                        C.TRACK_TYPE_AUDIO -> EngineTrackType.AUDIO
                        else -> EngineTrackType.TEXT
                    },
                    selected = group.isTrackSelected(index),
                    language = format.language,
                )
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> video += choice
                    C.TRACK_TYPE_AUDIO -> audio += choice
                    C.TRACK_TYPE_TEXT -> text += choice
                }
            }
        }

        overrides = newOverrides
        listener?.onTracksChanged(
            EngineTracks(video = video, audio = audio, text = text),
        )
    }

    private fun EngineResizeMode.toExoValue(): Int = when (this) {
        EngineResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        EngineResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        EngineResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
}

private fun EngineMedia.toMediaItem(): MediaItem {
    val subtitleConfigurations = subtitles
        .filter { it.url.isNotBlank() || it.assUrl != null }
        .map { subtitle ->
            // ASS/SSA 直接交给 Media3 的 SsaParser：它能还原行内颜色、粗体、
            // 斜体、下划线、删除线和位置；服务端压平的 WebVTT 只剩纯文本。
            val assUrl = subtitle.assUrl
            val source = assUrl ?: subtitle.url
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(source))
                .setMimeType(if (assUrl != null) MimeTypes.TEXT_SSA else MimeTypes.TEXT_VTT)
                .setLanguage(subtitle.language?.takeIf { it.isNotBlank() })
                .setLabel(subtitle.label)
                .build()
        }
    return MediaItem.Builder()
        .setUri(url)
        .apply {
            // 网盘直链的播放地址常量可能没有 .m3u8 后缀；显式标注类型，
            // 避免 DefaultMediaSourceFactory 把 HLS 当作普通渐进式文件解析。
            if (url.contains(".m3u8", ignoreCase = true)) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setSubtitleConfigurations(subtitleConfigurations)
        .build()
}

private fun videoLabel(format: Format, index: Int): String {
    val height = format.height
    return if (height > 0) "${height}p" else "画面 ${index + 1}"
}

private fun audioLabel(format: Format, index: Int): String {
    val channels = when (format.channelCount) {
        1 -> "单声道"
        2 -> "立体声"
        6 -> "5.1 声道"
        8 -> "7.1 声道"
        else -> null
    }
    val base = format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.takeIf { it.isNotBlank() }
    return listOfNotNull(base, channels).joinToString(" · ").ifBlank { "音轨 ${index + 1}" }
}

private fun textLabel(format: Format, index: Int): String =
    format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.takeIf { it.isNotBlank() }
        ?: "字幕 ${index + 1}"

private fun isDecodeError(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    -> true

    else -> false
}

private fun isParsingError(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> true

    else -> false
}

/**
 * 直连 CDN 失败、值得改走服务端中转的错误。
 *
 * 403/404 等 HTTP 状态是 115 防盗链/风控的典型表现；连接失败/超时在部分
 * 网络下也会出现（DNS、运营商、VPN 分流），同样交给服务端再试一次。
 */
private fun isDirectPlaybackFailure(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> true

    else -> false
}

private fun isIoError(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    -> true

    else -> false
}

/** 只用 Android 自带的软件解码器（不含硬件 MediaCodec）。 */
@OptIn(UnstableApi::class)
private object SoftwareOnlySelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> =
        MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .filter { it.softwareOnly }
}

/** 只用硬件解码器；显式选择“硬件解码”时生效。 */
@OptIn(UnstableApi::class)
private object HardwareOnlySelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> =
        MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .filter { it.hardwareAccelerated }
}
