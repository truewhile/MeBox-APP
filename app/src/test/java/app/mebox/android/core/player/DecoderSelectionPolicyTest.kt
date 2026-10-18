package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderSelectionPolicyTest {

    @Test
    fun `hardware mode only constrains video decoders`() {
        assertEquals(
            DecoderConstraint.HARDWARE_VIDEO_ONLY,
            DecoderSelectionPolicy.constraintFor(DecoderMode.HARDWARE, isVideo = true),
        )
        assertEquals(
            DecoderConstraint.NONE,
            DecoderSelectionPolicy.constraintFor(DecoderMode.HARDWARE, isVideo = false),
        )
    }

    @Test
    fun `software mode constrains every track`() {
        assertEquals(
            DecoderConstraint.SOFTWARE_ONLY,
            DecoderSelectionPolicy.constraintFor(DecoderMode.SOFTWARE, isVideo = true),
        )
        assertEquals(
            DecoderConstraint.SOFTWARE_ONLY,
            DecoderSelectionPolicy.constraintFor(DecoderMode.SOFTWARE, isVideo = false),
        )
    }

    @Test
    fun `automatic mode leaves the platform choice alone`() {
        assertEquals(
            DecoderConstraint.NONE,
            DecoderSelectionPolicy.constraintFor(DecoderMode.AUTO, isVideo = true),
        )
        assertEquals(
            DecoderConstraint.NONE,
            DecoderSelectionPolicy.constraintFor(DecoderMode.AUTO, isVideo = false),
        )
    }

    @Test
    fun `mime type identifies video tracks`() {
        assertTrue(DecoderSelectionPolicy.isVideoMimeType("video/avc"))
        assertTrue(DecoderSelectionPolicy.isVideoMimeType("VIDEO/hevc"))
        assertFalse(DecoderSelectionPolicy.isVideoMimeType("audio/mp4a-latm"))
        assertFalse(DecoderSelectionPolicy.isVideoMimeType("application/ttml+xml"))
    }
}
