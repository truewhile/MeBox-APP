package app.mebox.android.core.player.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrModeTest {

    @Test
    fun `unknown keys fall back to auto`() {
        assertEquals(VrProjection.AUTO, VrProjection.fromKey(null))
        assertEquals(VrProjection.AUTO, VrProjection.fromKey("nope"))
        assertEquals(VrProjection.EQUIRECT_360, VrProjection.fromKey("360"))
        assertEquals(VrStereo.SIDE_BY_SIDE, VrStereo.fromKey("sbs"))
        assertEquals(VrDisplayMode.HEADSET, VrDisplayMode.fromKey("headset"))
    }

    @Test
    fun `detects 360 and 180 from common file names`() {
        assertEquals(
            VrProjection.EQUIRECT_360,
            VrAutoDetect.projection("my_trip_360_vr.mp4"),
        )
        assertEquals(
            VrProjection.HEMISPHERE_180,
            VrAutoDetect.projection("clip.VR180.3D.mkv"),
        )
        assertEquals(
            VrProjection.HEMISPHERE_180,
            VrAutoDetect.projection("演示 180° 全景.mp4"),
        )
        // 分辨率里的 1080/360p 不能被当成全景标记。
        assertEquals(
            VrProjection.EQUIRECT_360,
            VrAutoDetect.projection("animation.1080p.360p.mkv"),
        )
    }

    @Test
    fun `detects stereo layouts`() {
        assertEquals(VrStereo.SIDE_BY_SIDE, VrAutoDetect.stereo("movie_3D_SBS.mp4"))
        assertEquals(VrStereo.SIDE_BY_SIDE, VrAutoDetect.stereo("左右格式.mp4"))
        assertEquals(VrStereo.TOP_BOTTOM, VrAutoDetect.stereo("movie_TB.mp4"))
        assertEquals(VrStereo.TOP_BOTTOM, VrAutoDetect.stereo("over-under.mp4"))
        assertEquals(VrStereo.MONO, VrAutoDetect.stereo("plain_2d.mp4"))
        assertEquals(VrStereo.MONO, VrAutoDetect.stereo("travel_360.mp4"))
    }

    @Test
    fun `resolve fills concrete values and chooses display mode`() {
        val mono = VrSettings(projection = VrProjection.EQUIRECT_360, stereo = VrStereo.MONO)
            .resolve("nothing")
        assertFalse(mono.stereo == VrStereo.AUTO)
        assertEquals(VrDisplayMode.FULLSCREEN, mono.display)

        val stereo = VrSettings(projection = VrProjection.HEMISPHERE_180, stereo = VrStereo.SIDE_BY_SIDE)
            .resolve("nothing")
        assertEquals(VrDisplayMode.HEADSET, stereo.display)
    }

    @Test
    fun `manual choices are never overridden by detection`() {
        val settings = VrSettings(
            projection = VrProjection.EQUIRECT_360,
            stereo = VrStereo.TOP_BOTTOM,
            display = VrDisplayMode.FULLSCREEN,
        )
        val resolved = settings.resolve("VR180_SBS_3D.mkv")
        assertEquals(VrProjection.EQUIRECT_360, resolved.projection)
        assertEquals(VrStereo.TOP_BOTTOM, resolved.stereo)
        assertEquals(VrDisplayMode.FULLSCREEN, resolved.display)
    }

    @Test
    fun `auto detection reads the title`() {
        val resolved = VrSettings().resolve("Holiday_VR180_OU.mp4")
        assertEquals(VrProjection.HEMISPHERE_180, resolved.projection)
        assertEquals(VrStereo.TOP_BOTTOM, resolved.stereo)
        assertEquals(VrDisplayMode.HEADSET, resolved.display)
    }

    @Test
    fun `summary shows the resolved mode`() {
        val summary = VrSettings(
            projection = VrProjection.HEMISPHERE_180,
            stereo = VrStereo.SIDE_BY_SIDE,
            display = VrDisplayMode.HEADSET,
        ).summary()
        assertTrue(summary.contains("180"))
        assertTrue(summary.contains("左右"))
        assertTrue(summary.contains("双眼"))
    }
}
