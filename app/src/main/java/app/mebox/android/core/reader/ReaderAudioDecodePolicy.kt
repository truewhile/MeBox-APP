package app.mebox.android.core.reader

import androidx.media3.common.PlaybackException

/** 网络/鉴权错误不能靠换解码器解决；仅容器/解码器错误允许本机 FFmpeg 软解一次。 */
internal object ReaderAudioDecodePolicy {
    fun shouldUseLocalDecoder(errorCode: Int): Boolean = errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )
}
