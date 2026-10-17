package app.mebox.android.core.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import app.mebox.android.core.network.MediaDto

/**
 * 查询本机 MediaCodec 能力，提前提示直连播放是否可行。
 *
 * ExoPlayer 默认优先硬件解码器，并会在硬件解码器初始化失败时尝试其他解码器
 * （播放器侧已开启 decoder fallback）。这里只做一次预判，用于在起播时提示
 * 设备可能不支持的编码；解码失败不会再回退到服务端转码。
 */
class DeviceCodecSupport {

    private val codecInfos: Array<MediaCodecInfo>? by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull()
    }

    fun assess(media: MediaDto): DecodeAssessment {
        val codecList = codecInfos
            ?: return DecodeAssessment(route = DecodeRoute.DIRECT)

        val videoMime = CodecMapping.videoMime(media.videoCodec)
        val audioMime = CodecMapping.audioMime(media.audioCodec)
        return DecodePolicy.assess(
            videoSupported = videoMime?.let { supportsVideo(codecList, it, media.width, media.height) },
            audioSupported = audioMime?.let { supportsMime(codecList, it) },
            containerSupported = CodecMapping.containerSupported(media.container),
            videoCodec = media.videoCodec,
            audioCodec = media.audioCodec,
            container = media.container,
        )
    }

    private fun supportsVideo(
        codecList: Array<MediaCodecInfo>,
        mime: String,
        width: Int,
        height: Int,
    ): Boolean {
        val decoders = decodersFor(codecList, mime)
        if (decoders.isEmpty()) return false
        if (width <= 0 || height <= 0) return true
        return decoders.any { decoder ->
            runCatching {
                val capabilities = decoder.getCapabilitiesForType(mime).videoCapabilities
                capabilities != null && capabilities.isSizeSupported(width, height)
            }.getOrDefault(false)
        }
    }

    private fun supportsMime(codecList: Array<MediaCodecInfo>, mime: String): Boolean =
        decodersFor(codecList, mime).isNotEmpty()

    private fun decodersFor(codecList: Array<MediaCodecInfo>, mime: String): List<MediaCodecInfo> =
        codecList.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
}
