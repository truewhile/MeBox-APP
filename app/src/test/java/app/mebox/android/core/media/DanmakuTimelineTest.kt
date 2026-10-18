package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTimelineTest {

    private fun comment(
        timeMs: Long,
        text: String,
        mode: DanmakuMode = DanmakuMode.SCROLL,
    ) = DanmakuComment(timeMs = timeMs, text = text, mode = mode)

    private fun timeline(
        comments: List<DanmakuComment>,
        width: Float = 1000f,
        height: Float = 500f,
        font: Float = 20f,
        area: Float = 1f,
    ) = DanmakuTimeline(
        comments = comments,
        widthPx = width,
        heightPx = height,
        baseFontPx = font,
        areaFraction = area,
    )

    @Test
    fun `scroll comment travels from right edge to off screen`() {
        val timeline = timeline(listOf(comment(0L, "hello")))

        val start = timeline.activeAt(0L).single()
        assertEquals(1000f, start.x, 0.1f)

        // 8000ms 行程，半程时正好移动 (屏幕宽 + 文本宽) 的一半。
        val middle = timeline.activeAt(4_000L).single()
        assertEquals(472f, middle.x, 1f)

        assertTrue(timeline.activeAt(8_001L).isEmpty())
    }

    @Test
    fun `fixed comment stays centered for the hold duration`() {
        val timeline = timeline(listOf(comment(1_000L, "top", DanmakuMode.TOP)))

        val shown = timeline.activeAt(1_000L).single()
        val expectedX = (1000f - DanmakuTimeline.estimateWidthPx("top", 20f)) / 2f
        assertEquals(expectedX, shown.x, 0.1f)

        assertTrue(timeline.activeAt(1_000L + 4_500L).isNotEmpty())
        assertTrue(timeline.activeAt(1_000L + 4_501L).isEmpty())
    }

    @Test
    fun `busy lane pushes the next comment to another lane`() {
        // 20 个汉字约 400px；尾部完全进入屏幕需要约 2.3 秒，
        // 1 秒后的短弹幕不能挤在同一条弹道上。
        val timeline = timeline(
            listOf(
                comment(0L, "这是一条很长很长很长很长很长很长的弹幕"),
                comment(1_000L, "b"),
            ),
        )

        val at = timeline.activeAt(1_000L)
        assertEquals(2, at.size)
        val first = at.first { it.comment.text.startsWith("这是") }
        val second = at.first { it.comment.text == "b" }
        assertTrue(second.y > first.y + 10f)
    }

    @Test
    fun `crowded timestamp never staggers comments into the same lane`() {
        val comments = (1..10).map { comment(0L, "comment-$it") }

        val at = timeline(comments).activeAt(0L)

        // 500px 高、20px 字号大约有 17 条弹道；10 条都能放下。
        assertEquals(10, at.size)
        assertEquals(10, at.map { it.y }.distinct().size)
    }

    @Test
    fun `display area limits the number of lanes`() {
        val comments = (1..10).map { comment(0L, "c$it") }

        val at = timeline(comments, height = 500f, font = 20f, area = 0.25f).activeAt(0L)

        // 125px / 29px ≈ 4 条弹道。
        assertEquals(4, at.size)
    }

    @Test
    fun `top and bottom comments do not share lanes`() {
        val timeline = timeline(
            listOf(
                comment(0L, "top", DanmakuMode.TOP),
                comment(0L, "bottom", DanmakuMode.BOTTOM),
            ),
        )

        val at = timeline.activeAt(0L)

        assertEquals(2, at.size)
        assertTrue(at.first { it.comment.text == "top" }.y < 30f)
        assertTrue(at.first { it.comment.text == "bottom" }.y > 400f)
    }

    @Test
    fun `direct traversal draws mixed modes at their expected coordinates`() {
        val timeline = timeline(
            listOf(
                comment(1_000L, "ab"),
                comment(2_000L, "top", DanmakuMode.TOP),
                comment(4_000L, "底部", DanmakuMode.BOTTOM),
            ),
        )
        val drawn = mutableListOf<DanmakuFrameItem>()

        timeline.forEachActive(5_000L) { comment, x, y ->
            drawn += DanmakuFrameItem(comment, x, y)
        }

        assertEquals(listOf("ab", "top", "底部"), drawn.map { it.comment.text })
        assertEquals(488.8f, drawn[0].x, 0.1f)
        assertEquals(4.5f, drawn[0].y, 0.1f)
        assertEquals(483.2f, drawn[1].x, 0.1f)
        assertEquals(4.5f, drawn[1].y, 0.1f)
        assertEquals(480f, drawn[2].x, 0.1f)
        assertEquals(475.5f, drawn[2].y, 0.1f)
    }

    @Test
    fun `direct traversal respects time boundaries after seeks in either direction`() {
        val timeline = timeline(listOf(comment(1_000L, "scroll")))
        val timestamps = listOf(-1L, 999L, 1_000L, 9_000L, 9_001L, 4_000L, 999L, 1_000L)
        val expectedCounts = listOf(0, 0, 1, 1, 0, 1, 0, 1)

        timestamps.zip(expectedCounts).forEach { (position, expectedCount) ->
            var count = 0
            timeline.forEachActive(position) { _, _, _ -> count++ }
            assertEquals("at $position", expectedCount, count)
        }
    }

    @Test
    fun `direct traversal expires fixed comments at the same boundary`() {
        val timeline = timeline(listOf(comment(1_000L, "top", DanmakuMode.TOP)))
        val drawn = mutableListOf<String>()
        timeline.forEachActive(5_500L) { comment, _, _ -> drawn += comment.text }
        timeline.forEachActive(5_501L) { comment, _, _ -> drawn += comment.text }
        assertEquals(listOf("top"), drawn)
    }

    @Test
    fun `width estimate treats cjk as full width`() {
        val cjk = DanmakuTimeline.estimateWidthPx("弹幕", 20f)
        val ascii = DanmakuTimeline.estimateWidthPx("ab", 20f)

        assertTrue(cjk > ascii)
    }
}
