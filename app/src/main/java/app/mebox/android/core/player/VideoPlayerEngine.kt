package app.mebox.android.core.player

import android.view.ViewGroup
import android.widget.FrameLayout
import app.mebox.android.core.player.vr.VrSettings

/** 播放器内部统一的轨道类型，避免界面层直接依赖某个内核的常量。 */
enum class EngineTrackType { VIDEO, AUDIO, TEXT }

/** 一条可选轨道；[id] 只在同一内核实例内有效。 */
data class EngineTrack(
    val id: String,
    val label: String,
    val type: EngineTrackType,
    val selected: Boolean,
    val language: String? = null,
)

data class EngineTracks(
    val video: List<EngineTrack> = emptyList(),
    val audio: List<EngineTrack> = emptyList(),
    val text: List<EngineTrack> = emptyList(),
)

data class EngineSubtitle(
    val url: String,
    val label: String,
    val language: String? = null,
    /**
     * ASS/SSA 原文件地址。支持 libass 的内核（LibVLC）优先使用它渲染原始样式；
     * 不支持的内核忽略该字段，退回 [url]（WebVTT）。
     */
    val assUrl: String? = null,
)

data class EngineMedia(
    val mediaId: String,
    val title: String,
    val url: String,
    /** 直连被 CDN 403/404 时改走同一节目的服务端同源转发地址。 */
    val proxyUrl: String? = null,
    /**
     * 直连被 CDN 403/404 时先试这一层：服务端丢掉过期的云盘直链缓存、重新换链
     * 后再 302 到新直链，播放器仍然直连 CDN。为空表示服务端条目不支持。
     */
    val refreshUrl: String? = null,
    val subtitles: List<EngineSubtitle> = emptyList(),
    /**
     * 字幕自动加载与优先语言。LibVLC 在装载媒体时据此选择默认字幕；
     * ExoPlayer 的轨道由播放页在 [VideoPlayerEngine.listener] 的
     * `onTracksChanged` 里按同一偏好选中。
     */
    val subtitlePreference: SubtitlePreference = SubtitlePreference(),
)

/** 画面缩放策略，两个内核各自映射到自己的实现。 */
enum class EngineResizeMode { FIT, FILL, ZOOM }

enum class EnginePlaybackState { IDLE, BUFFERING, READY, ENDED }

/**
 * 播放内核需要向界面汇报的状态。
 *
 * 全部回调都在主线程，界面可以直接写入 Compose 状态。
 */
interface VideoPlayerEngineListener {
    fun onPlaybackStateChanged(state: EnginePlaybackState) = Unit
    fun onPlayingChanged(playing: Boolean) = Unit
    fun onBufferingChanged(buffering: Boolean) = Unit
    fun onTracksChanged(tracks: EngineTracks) = Unit
    fun onSpeedChanged(speed: Float) = Unit
    fun onEnded() = Unit

    /**
     * 内核在内部重新装载了当前媒体（例如直连 403 后改用服务端中转、
     * LibVLC 硬解失败后改软解）。界面据此重新应用字幕选择、轨道状态等。
     */
    fun onMediaReloaded() = Unit

    /**
     * @param decodeRelated 是否更像“解码器不支持”而不是网络/地址问题；
     *   界面据此提示用户切换播放内核。
     */
    fun onError(message: String, decodeRelated: Boolean) = Unit
}

/**
 * 播放内核的统一门面。
 *
 * 界面只依赖这里的能力：挂载画面、装载媒体、控制播放、切换轨道和阅读进度。
 * 新增内核（例如 MPV）时实现这个接口并在 [createVideoPlayerEngine] 注册即可，
 * 播放页不需要再改。
 */
interface VideoPlayerEngine {
    val kind: PlayerEngineKind

    var listener: VideoPlayerEngineListener?

    /** 把画面挂到 [host]；重复调用必须幂等，便于 Compose 重组。 */
    fun attach(host: FrameLayout)

    /** 解绑画面并释放底层资源；调用后该实例不可再用。 */
    fun release()

    /** 装载媒体。引擎尚未挂载画面时先缓存，挂载后自动起播。 */
    fun setMedia(media: EngineMedia, startPositionMs: Long, autoPlay: Boolean)

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 出错后的原地重试，不重建内核实例。 */
    fun retry()

    fun setSpeed(speed: Float)
    fun setResizeMode(mode: EngineResizeMode)

    /**
     * 内核是否支持 VR / 全景渲染。
     *
     * ExoPlayer 和 LibVLC 都能把解码帧输出到 SurfaceTexture，因此复用同一个
     * OpenGL 渲染器；未来接入不支持纹理输出的内核时保持默认值即可。
     */
    val supportsVr: Boolean get() = false

    /**
     * 开启或关闭 VR。
     *
     * [settings] 需要是 `VrSettings.resolve` 后的具体值；传 null 恢复普通播放。
     * 允许在播放中切换，内核负责在普通视图与 VR 视图之间迁移画面。
     */
    fun setVrSettings(settings: VrSettings?) = Unit

    /** 把当前视线方向重设为正前方。 */
    fun recenterVr() = Unit

    /** 手指拖动微调视角，单位是像素位移。 */
    fun rotateVr(dx: Float, dy: Float) = Unit

    /** null 表示交给内核自动选择（清晰度）/[selectTextTrack] 中表示关闭字幕。 */
    fun selectVideoTrack(trackId: String?)
    fun selectAudioTrack(trackId: String?)
    fun selectTextTrack(trackId: String?)

    /**
     * 播放页正在恢复用户的手动字幕选择（含明确关闭）。
     *
     * 支持异步加载外挂字幕的内核（LibVLC）据此暂停自动选中，避免晚到的
     * 偏好字幕覆盖用户意图；同步加载的内核不需要处理。
     */
    fun holdSubtitleAutoSelection() = Unit

    fun playbackState(): EnginePlaybackState
    fun positionMs(): Long
    fun durationMs(): Long
    fun bufferedMs(): Long
    fun isPlaying(): Boolean
}

/** 把内核自绘的画面视图塞进容器；已经挂载过就直接复用。 */
internal fun ViewGroup.attachPlayerView(view: android.view.View) {
    if (view.parent === this) return
    (view.parent as? ViewGroup)?.removeView(view)
    addView(
        view,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
}
