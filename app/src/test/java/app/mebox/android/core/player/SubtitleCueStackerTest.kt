package app.mebox.android.core.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCueStackerTest {

    /** 单元测试里避开 Android 的 SpannableStringBuilder，只验证合并与分组逻辑。 */
    private val joinWithNewline: (List<CharSequence>) -> CharSequence = { texts ->
        texts.joinToString("\n")
    }

    @Test
    fun `merges bilingual cues that share the default position`() {
        val chinese = Cue.Builder().setText("你好").build()
        val japanese = Cue.Builder().setText("こんにちは").build()

        val stacked = stackSubtitleCues(listOf(chinese, japanese), joinWithNewline)

        assertEquals(1, stacked.size)
        assertEquals("你好\nこんにちは", stacked.single().text.toString())
    }

    @Test
    fun `keeps cues with different line positions apart`() {
        val dialogue = Cue.Builder().setText("对白").build()
        val sign = Cue.Builder()
            .setText("注释")
            .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()

        val stacked = stackSubtitleCues(listOf(dialogue, sign), joinWithNewline)

        assertEquals(2, stacked.size)
    }

    @Test
    fun `keeps top comment cue separate from bottom dialogue`() {
        val centered = Cue.Builder().setText("居中对白").build()
        val top = Cue.Builder()
            .setText("顶部注释")
            .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()

        assertEquals(2, stackSubtitleCues(listOf(centered, top), joinWithNewline).size)
    }

    @Test
    fun `drops duplicated cue text instead of repeating lines`() {
        val first = Cue.Builder().setText("同一句").build()
        val second = Cue.Builder().setText("同一句").build()

        val stacked = stackSubtitleCues(listOf(first, second), joinWithNewline)

        assertEquals(1, stacked.size)
        assertEquals("同一句", stacked.single().text.toString())
    }

    @Test
    fun `returns single cue unchanged`() {
        val cue = Cue.Builder().setText("一句话").build()

        val stacked = stackSubtitleCues(listOf(cue), joinWithNewline)

        assertEquals(1, stacked.size)
        assertEquals("一句话", stacked.single().text.toString())
    }

    @Test
    fun `returns empty list for empty input`() {
        assertEquals(0, stackSubtitleCues(emptyList(), joinWithNewline).size)
    }
}
