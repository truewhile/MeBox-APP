package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSurfaceBoxTest {

    private val landscape = 16f / 9f

    @Test
    fun `aspect uses size and sample aspect ratio`() {
        assertEquals(16f / 9f, videoDisplayAspect(1920, 1080)!!, 0.001f)
        // 4:3 像素 + 1.5 的 SAR = 2:1
        assertEquals(2f, videoDisplayAspect(720, 540, sarNum = 3, sarDen = 2)!!, 0.001f)
    }

    @Test
    fun `aspect swaps size for rotated tracks`() {
        assertEquals(9f / 16f, videoDisplayAspect(1920, 1080, rotated = true)!!, 0.001f)
        assertEquals(true, isQuarterTurn(4))
        assertEquals(true, isQuarterTurn(7))
        assertEquals(false, isQuarterTurn(0))
        assertEquals(false, isQuarterTurn(3))
    }

    @Test
    fun `invalid size has no aspect`() {
        assertNull(videoDisplayAspect(0, 1080))
        assertNull(videoDisplayAspect(1920, 0))
        // 宽高比 100:1 明显不是正常视频，不要用它去缩画面
        assertNull(videoDisplayAspect(1920, 19))
    }

    @Test
    fun `fit letterboxes a wide host horizontally`() {
        val box = videoSurfaceBox(2400, 1080, landscape, EngineResizeMode.FIT)!!
        assertEquals(1920, box.width)
        assertEquals(1080, box.height)
    }

    @Test
    fun `fit letterboxes a tall host vertically`() {
        val box = videoSurfaceBox(1080, 2400, landscape, EngineResizeMode.FIT)!!
        assertEquals(1080, box.width)
        assertEquals(608, box.height)
    }

    @Test
    fun `zoom fills a wide host and overshoots in height`() {
        val box = videoSurfaceBox(2400, 1080, landscape, EngineResizeMode.ZOOM)!!
        assertEquals(2400, box.width)
        assertEquals(1350, box.height)
    }

    @Test
    fun `zoom fills a tall host and overshoots in width`() {
        val box = videoSurfaceBox(1080, 2400, landscape, EngineResizeMode.ZOOM)!!
        assertEquals(4267, box.width)
        assertEquals(2400, box.height)
    }

    @Test
    fun `fill always matches the host`() {
        val box = videoSurfaceBox(2400, 1080, landscape, EngineResizeMode.FILL)!!
        assertEquals(2400, box.width)
        assertEquals(1080, box.height)
    }

    @Test
    fun `unknown aspect keeps the host size`() {
        val box = videoSurfaceBox(2400, 1080, null, EngineResizeMode.FIT)!!
        assertEquals(2400, box.width)
        assertEquals(1080, box.height)
    }

    @Test
    fun `zero sized host has no box`() {
        assertNull(videoSurfaceBox(0, 1080, landscape, EngineResizeMode.FIT))
        assertNull(videoSurfaceBox(2400, 0, landscape, EngineResizeMode.FIT))
    }
}
