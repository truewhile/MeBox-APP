package app.mebox.android.core.media

/**
 * 服务端媒体编码名称到 Android MediaCodec MIME 的映射，以及“本机能否解码”
 * 的播放策略。
 *
 * 这里保持纯 Kotlin，不依赖 Android 类，便于在 JVM 单元测试里验证：
 * 播放器只做直连解码，这里的结果仅用于提前提示设备可能不支持的编码。
 */
object CodecMapping {

    const val VIDEO_H264 = "video/avc"
    const val VIDEO_HEVC = "video/hevc"
    const val VIDEO_AV1 = "video/av01"
    const val VIDEO_VP9 = "video/x-vnd.on2.vp9"
    const val VIDEO_VP8 = "video/x-vnd.on2.vp8"
    const val VIDEO_MPEG4 = "video/mp4v-es"
    const val VIDEO_MPEG2 = "video/mpeg2"

    const val AUDIO_AAC = "audio/mp4a-latm"
    const val AUDIO_AC3 = "audio/ac3"
    const val AUDIO_EAC3 = "audio/eac3"
    const val AUDIO_DTS = "audio/vnd.dts"
    const val AUDIO_TRUEHD = "audio/true-hd"
    const val AUDIO_FLAC = "audio/flac"
    const val AUDIO_OPUS = "audio/opus"
    const val AUDIO_VORBIS = "audio/vorbis"
    const val AUDIO_MP3 = "audio/mpeg"
    const val AUDIO_PCM = "audio/raw"

    fun videoMime(codec: String): String? = when (normalize(codec)) {
        "h264", "avc", "avc1", "x264" -> VIDEO_H264
        "hevc", "h265", "h.265", "x265" -> VIDEO_HEVC
        "av1", "av01" -> VIDEO_AV1
        "vp9", "vp09" -> VIDEO_VP9
        "vp8", "vp08" -> VIDEO_VP8
        "mpeg4", "mp4v", "xvid", "divx" -> VIDEO_MPEG4
        "mpeg2video", "mpeg2", "m2v" -> VIDEO_MPEG2
        else -> null
    }

    fun audioMime(codec: String): String? = when (normalize(codec)) {
        "aac", "mp4a", "aac_latm" -> AUDIO_AAC
        "ac3", "a52" -> AUDIO_AC3
        "eac3", "ec3" -> AUDIO_EAC3
        "dts", "dca" -> AUDIO_DTS
        "truehd", "mlp" -> AUDIO_TRUEHD
        "flac" -> AUDIO_FLAC
        "opus" -> AUDIO_OPUS
        "vorbis" -> AUDIO_VORBIS
        "mp3", "mpeg" -> AUDIO_MP3
        "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le" -> AUDIO_PCM
        else -> null
    }

    /**
     * `true` 表示 Media3 能解析；`false` 表示已知不支持；`null` 表示不确定，
     * 仍应先尝试直链，让 Extractor 自行嗅探。
     */
    fun containerSupported(container: String): Boolean? = when (normalize(container)) {
        "mkv", "matroska", "webm", "mp4", "m4v", "mov", "ts", "m2ts", "mpegts",
        "flv", "avi", "ogv", "ogg", "wav", "mp3", "flac",
        -> true

        "rm", "rmvb", "wmv", "asf", "iso", "img" -> false

        else -> null
    }

    fun videoLabel(codec: String): String = when (normalize(codec)) {
        "h264", "avc", "avc1", "x264" -> "H.264"
        "hevc", "h265", "h.265", "x265" -> "HEVC"
        "av1", "av01" -> "AV1"
        "vp9", "vp09" -> "VP9"
        "vp8", "vp08" -> "VP8"
        "mpeg4", "mp4v", "xvid", "divx" -> "MPEG-4"
        "mpeg2video", "mpeg2", "m2v" -> "MPEG-2"
        else -> codec.trim().uppercase()
    }

    fun audioLabel(codec: String): String = when (normalize(codec)) {
        "aac", "mp4a", "aac_latm" -> "AAC"
        "ac3", "a52" -> "AC-3"
        "eac3", "ec3" -> "E-AC-3"
        "dts", "dca" -> "DTS"
        "truehd", "mlp" -> "TrueHD"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "mp3", "mpeg" -> "MP3"
        "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le" -> "PCM"
        else -> codec.trim().uppercase()
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().removePrefix(".")
}

enum class DecodeRoute {
    /** 本机可以尝试直接解码。 */
    DIRECT,

    /** 设备没有对应解码器或容器不受支持；App 不会回退到服务端转码。 */
    UNSUPPORTED,
}

data class DecodeAssessment(
    val route: DecodeRoute,
    val note: String? = null,
    val videoSupported: Boolean? = null,
    val audioSupported: Boolean? = null,
) {
    /** 只有明确不支持时返回 false；未知编码仍交给 ExoPlayer 嗅探。 */
    val supported: Boolean
        get() = route == DecodeRoute.DIRECT
}

object DecodePolicy {

    /**
     * 根据设备解码能力给出播放建议。
     *
     * App 只做直连播放，因此这里不再决定是否转码，只用于提前提示：
     * - 容器或视频编码确定不支持：很可能播放失败；
     * - 音频编码不支持：可能没有声音，但视频仍可尝试；
     * - 编码未知：交给 Media3/Extractor 自己判断。
     */
    fun assess(
        videoSupported: Boolean?,
        audioSupported: Boolean?,
        containerSupported: Boolean?,
        videoCodec: String,
        audioCodec: String,
        container: String,
    ): DecodeAssessment {
        val containerLabel = container.trim().uppercase()
        val videoLabel = CodecMapping.videoLabel(videoCodec).ifBlank { "视频编码" }
        val audioLabel = CodecMapping.audioLabel(audioCodec).ifBlank { "音频编码" }

        if (containerSupported == false) {
            return DecodeAssessment(
                route = DecodeRoute.UNSUPPORTED,
                note = "本机不支持 ${containerLabel.ifBlank { "该" }} 容器，可能无法播放",
                videoSupported = videoSupported,
                audioSupported = audioSupported,
            )
        }
        if (videoSupported == false) {
            return DecodeAssessment(
                route = DecodeRoute.UNSUPPORTED,
                note = "本机没有 $videoLabel 解码器，可能无法播放",
                videoSupported = false,
                audioSupported = audioSupported,
            )
        }
        val note = if (audioSupported == false) {
            "本机没有 $audioLabel 音频解码器，可能没有声音"
        } else {
            null
        }
        return DecodeAssessment(
            route = DecodeRoute.DIRECT,
            note = note,
            videoSupported = videoSupported,
            audioSupported = audioSupported,
        )
    }
}
