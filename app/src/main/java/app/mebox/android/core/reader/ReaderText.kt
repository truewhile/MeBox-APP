package app.mebox.android.core.reader

/**
 * 服务端正文按行下发（`\n` 分隔），图片是单独一行的 `[img]<签名地址>` 标记。
 *
 * [line] 保留服务端原始行号：段评锚点按行号挂靠，后续接入段评时不需要重新对齐。
 */
data class ReaderParagraph(
    val text: String,
    val imageUrl: String? = null,
    val line: Int = 0,
) {
    val isImage: Boolean get() = !imageUrl.isNullOrEmpty()
}

object ReaderText {

    private const val IMAGE_MARK = "[img]"

    /** 把正文拆成非空段落；空行丢弃，图片标记单独成段。 */
    fun parse(content: String): List<ReaderParagraph> {
        if (content.isBlank()) return emptyList()
        return content.split('\n').mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> null
                line.startsWith(IMAGE_MARK) -> {
                    val url = line.removePrefix(IMAGE_MARK).trim()
                    if (url.isEmpty()) {
                        null
                    } else {
                        ReaderParagraph(text = "", imageUrl = url, line = index)
                    }
                }
                else -> ReaderParagraph(text = line, line = index)
            }
        }
    }
}

/**
 * 文本「页码」换算。
 *
 * 服务端进度字段对文本存的是页码（与网页阅读器一致），但原生端采用纵向滚动，
 * 没有真实分页。这里按字号、行距和视口尺寸估算每页容纳的字符数，把
 * 「首个可见段落」换算成页码，反向再把页码换算成段落下标。
 *
 * 这是近似定位：字号变化或换设备后位置会有少量偏差，但不会越过章节边界，
 * 也能让网页与 App 交替阅读时落在同一段附近。精确的段落/字符锚点属于
 * M4-01 的新协议，服务端尚未提供时不能用客户端字段代替。
 */
object ReaderPaging {

    private const val HORIZONTAL_PADDING_DP = 40f
    private const val VERTICAL_CHROME_DP = 48f

    fun charsPerPage(
        fontSizeSp: Float,
        lineHeight: Float,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
    ): Int {
        val size = fontSizeSp.coerceAtLeast(10f)
        val line = lineHeight.coerceAtLeast(1f)
        val lineHeightDp = size * line
        val usableHeight = (viewportHeightDp - VERTICAL_CHROME_DP).coerceAtLeast(lineHeightDp)
        val usableWidth = (viewportWidthDp - HORIZONTAL_PADDING_DP).coerceAtLeast(size)
        val lines = (usableHeight / lineHeightDp).toInt().coerceAtLeast(1)
        val charsPerLine = (usableWidth / size).toInt().coerceAtLeast(1)
        return lines * charsPerLine
    }

    /** 首个可见段落所在的页码（0 起）。 */
    fun pageForParagraph(
        index: Int,
        paragraphs: List<ReaderParagraph>,
        charsPerPage: Int,
    ): Int {
        if (paragraphs.isEmpty() || charsPerPage <= 0) return 0
        val limit = index.coerceIn(0, paragraphs.size)
        var chars = 0
        for (i in 0 until limit) {
            chars += weight(paragraphs[i], charsPerPage)
        }
        return chars / charsPerPage
    }

    /** 页码对应段落的下标；页码超出范围时落到最后一段。 */
    fun paragraphForPage(
        page: Int,
        paragraphs: List<ReaderParagraph>,
        charsPerPage: Int,
    ): Int {
        if (paragraphs.isEmpty() || charsPerPage <= 0) return 0
        val target = page.coerceAtLeast(0) * charsPerPage
        var chars = 0
        for (i in paragraphs.indices) {
            val next = chars + weight(paragraphs[i], charsPerPage)
            if (next > target) return i
            chars = next
        }
        return paragraphs.lastIndex
    }

    /** 图片按一屏计，避免整章图片被换算到同一页。 */
    private fun weight(paragraph: ReaderParagraph, charsPerPage: Int): Int =
        if (paragraph.isImage) charsPerPage else paragraph.text.length.coerceAtLeast(1)
}
