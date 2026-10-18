package app.mebox.android.core.player

import android.text.Layout
import android.text.SpannableStringBuilder
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * 把同一时刻、落在同一字幕框里的多条 cue 合并成一条多行 cue。
 *
 * Media3 的 `SubtitleView` 会为每条 cue 单独计算位置并逐条绘制。中日双语
 * 字幕通常是两条独立 cue（中文一条、日文一条），位置都是默认的底部居中，
 * 于是两条文字被画在同一个坐标上互相重叠。合并成一条 cue 后由
 * `SubtitleView` 正常排版，中文和日文各占一行。
 *
 * 位置不同（例如对白在底部、注释在顶部）的 cue 仍然分开绘制，不会破坏
 * ASS 自带的定位。
 *
 * [mergeText] 只为单元测试留出注入点；生产路径使用带样式 span 的
 * [joinCueTexts]，保证合并后的行内颜色、粗体等样式不丢失。
 */
internal fun stackSubtitleCues(
    cues: List<Cue>,
    mergeText: (List<CharSequence>) -> CharSequence = ::joinCueTexts,
): List<Cue> {
    if (cues.size < 2) return cues

    val grouped = LinkedHashMap<CueSlot, MutableList<Cue>>()
    for (cue in cues) {
        if (!cue.isStackable()) continue
        grouped.getOrPut(cue.toSlot()) { mutableListOf() } += cue
    }
    if (grouped.isEmpty()) return cues

    val stacked = grouped.mapValues { (_, group) -> group.stack(mergeText) }
    // 按原始顺序输出：每条槽位只在第一次出现的位置放合并后的 cue，
    // 图片字幕等无法合并的 cue 保持原位，避免打乱叠放顺序。
    val emitted = HashSet<CueSlot>(grouped.size)
    val result = ArrayList<Cue>(cues.size)
    for (cue in cues) {
        if (!cue.isStackable()) {
            result += cue
            continue
        }
        val slot = cue.toSlot()
        if (emitted.add(slot)) result += stacked.getValue(slot)
    }
    return result
}

private fun Cue.isStackable(): Boolean = !text.isNullOrEmpty() && bitmap == null

@OptIn(UnstableApi::class)
private fun List<Cue>.stack(
    mergeText: (List<CharSequence>) -> CharSequence,
): Cue {
    val first = first()
    if (size < 2) return first
    // 同一句被重复下发时只保留一份，避免出现重复行。
    val texts = mapNotNull { it.text }.distinctBy { it.toString() }
    if (texts.size < 2) return first
    return first.buildUpon().setText(mergeText(texts)).build()
}

/**
 * 两条 cue 只有在“字幕框位置完全一致”时才可能画在同一处：
 * 行位置、行/位置锚点、文字对齐方式和竖排方向都相同。
 */
private data class CueSlot(
    val verticalType: Int,
    val line: Float,
    val lineType: Int,
    val lineAnchor: Int,
    val position: Float,
    val positionAnchor: Int,
    val textAlignment: Layout.Alignment?,
    val windowColorSet: Boolean,
)

private fun Cue.toSlot(): CueSlot = CueSlot(
    verticalType = verticalType,
    line = line,
    lineType = lineType,
    lineAnchor = lineAnchor,
    position = position,
    positionAnchor = positionAnchor,
    textAlignment = textAlignment,
    windowColorSet = windowColorSet,
)

/** 保留每条 cue 自带的样式 span，用换行把多语言字幕排成上下两行。 */
private fun joinCueTexts(texts: List<CharSequence>): CharSequence {
    val builder = SpannableStringBuilder()
    texts.forEachIndexed { index, text ->
        if (index > 0) builder.append('\n')
        builder.append(text)
    }
    return builder
}
