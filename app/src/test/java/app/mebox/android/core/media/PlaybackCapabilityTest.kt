package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCapabilityTest {

    @Test
    fun `video codec names map to android mime types`() {
        assertEquals(CodecMapping.VIDEO_H264, CodecMapping.videoMime("h264"))
        assertEquals(CodecMapping.VIDEO_H264, CodecMapping.videoMime("AVC"))
        assertEquals(CodecMapping.VIDEO_HEVC, CodecMapping.videoMime("hevc"))
        assertEquals(CodecMapping.VIDEO_HEVC, CodecMapping.videoMime("h265"))
        assertEquals(CodecMapping.VIDEO_AV1, CodecMapping.videoMime("av1"))
        assertEquals(CodecMapping.VIDEO_VP9, CodecMapping.videoMime("vp9"))
        assertEquals(CodecMapping.VIDEO_MPEG4, CodecMapping.videoMime("xvid"))
        assertEquals(CodecMapping.VIDEO_MPEG2, CodecMapping.videoMime("mpeg2video"))
        assertNull(CodecMapping.videoMime("vc1"))
    }

    @Test
    fun `audio codec names map to android mime types`() {
        assertEquals(CodecMapping.AUDIO_AAC, CodecMapping.audioMime("aac"))
        assertEquals(CodecMapping.AUDIO_EAC3, CodecMapping.audioMime("ec3"))
        assertEquals(CodecMapping.AUDIO_DTS, CodecMapping.audioMime("dca"))
        assertEquals(CodecMapping.AUDIO_TRUEHD, CodecMapping.audioMime("mlp"))
        assertEquals(CodecMapping.AUDIO_OPUS, CodecMapping.audioMime("opus"))
        assertNull(CodecMapping.audioMime("speex"))
    }

    @Test
    fun `known containers are classified`() {
        assertEquals(true, CodecMapping.containerSupported("mkv"))
        assertEquals(true, CodecMapping.containerSupported("MP4"))
        assertEquals(true, CodecMapping.containerSupported("ts"))
        assertEquals(false, CodecMapping.containerSupported("rmvb"))
        assertEquals(false, CodecMapping.containerSupported("wmv"))
        assertNull(CodecMapping.containerSupported("weird"))
    }

    @Test
    fun `unsupported video codec is reported as unplayable`() {
        val assessment = DecodePolicy.assess(
            videoSupported = false,
            audioSupported = true,
            containerSupported = true,
            videoCodec = "hevc",
            audioCodec = "aac",
            container = "mkv",
        )
        assertEquals(DecodeRoute.UNSUPPORTED, assessment.route)
        assertFalse(assessment.supported)
        assertTrue(assessment.note!!.contains("HEVC"))
        assertTrue(assessment.note!!.contains("可能无法播放"))
    }

    @Test
    fun `unsupported container is reported as unplayable`() {
        val assessment = DecodePolicy.assess(
            videoSupported = true,
            audioSupported = true,
            containerSupported = false,
            videoCodec = "h264",
            audioCodec = "aac",
            container = "rmvb",
        )
        assertEquals(DecodeRoute.UNSUPPORTED, assessment.route)
        assertTrue(assessment.note!!.contains("RMVB"))
    }

    @Test
    fun `unsupported audio keeps direct play with a warning`() {
        val assessment = DecodePolicy.assess(
            videoSupported = true,
            audioSupported = false,
            containerSupported = true,
            videoCodec = "h264",
            audioCodec = "dts",
            container = "mkv",
        )
        assertEquals(DecodeRoute.DIRECT, assessment.route)
        assertTrue(assessment.supported)
        assertTrue(assessment.note!!.contains("DTS"))
        assertTrue(assessment.note!!.contains("可能没有声音"))
    }

    @Test
    fun `supported or unknown codecs stay on direct play`() {
        val supported = DecodePolicy.assess(
            videoSupported = true,
            audioSupported = true,
            containerSupported = true,
            videoCodec = "h264",
            audioCodec = "aac",
            container = "mp4",
        )
        assertEquals(DecodeRoute.DIRECT, supported.route)
        assertTrue(supported.supported)
        assertNull(supported.note)

        val unknown = DecodePolicy.assess(
            videoSupported = null,
            audioSupported = null,
            containerSupported = null,
            videoCodec = "",
            audioCodec = "",
            container = "",
        )
        assertEquals(DecodeRoute.DIRECT, unknown.route)
        assertNull(unknown.note)
    }
}
