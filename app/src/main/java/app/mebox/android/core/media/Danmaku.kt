package app.mebox.android.core.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 弹幕的三种显示方式：滚动、顶部固定、底部固定。 */
enum class DanmakuMode { SCROLL, TOP, BOTTOM }

/** 一条已经归一化的弹幕，时间相对于视频起点。 */
data class DanmakuComment(
    val timeMs: Long,
    val text: String,
    val mode: DanmakuMode = DanmakuMode.SCROLL,
    /** 不透明 ARGB 颜色，默认白色。 */
    val color: Int = WHITE,
    /** 相对标准字号的缩放比例。 */
    val sizeScale: Float = 1f,
) {
    companion object {
        const val WHITE: Int = -1
    }
}

/**
 * 解析服务端返回的弹幕原始载荷。
 *
 * 服务端复用弹弹play 协议，可能返回两种格式：
 * - Bilibili XML：`<d p="time,mode,fontSize,color,...">text</d>`；
 * - 弹弹play JSON：`{"comments":[{"p":"time,mode,color,user","m":"text"}]}`，
 *   也兼容 `{time,text,mode,color,size}` 对象和 CCL 元组数组。
 *
 * 解析只依赖 kotlinx.serialization，不依赖 Android，便于单元测试。
 */
object DanmakuParser {

    private const val MAX_TEXT_LENGTH = 200

    private val xmlTag = Regex("""<d\s+p="([^"]*)"[^>]*>([\s\S]*?)</d>""", RegexOption.IGNORE_CASE)
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String, sourceType: String = "auto"): List<DanmakuComment> {
        val body = raw.removePrefix("\uFEFF").trim()
        if (body.isEmpty()) return emptyList()
        val looksLikeJson = body.startsWith("{") || body.startsWith("[")
        val fromJson = when {
            sourceType.equals("json", ignoreCase = true) -> parseJson(body) ?: emptyList()
            sourceType.equals("auto", ignoreCase = true) && looksLikeJson -> parseJson(body)
            else -> null
        }
        val comments = fromJson ?: parseXml(body)
        return comments
            .asSequence()
            .filter { it.timeMs >= 0L && it.text.isNotBlank() }
            .sortedBy { it.timeMs }
            .toList()
    }

    private fun parseXml(raw: String): List<DanmakuComment> {
        val result = ArrayList<DanmakuComment>()
        xmlTag.findAll(raw).forEach { match ->
            val fields = match.groupValues[1].split(',')
            val text = unescape(match.groupValues[2]).trim()
            if (text.isEmpty() || fields.isEmpty()) return@forEach

            val seconds = fields[0].trim().toDoubleOrNull() ?: return@forEach
            if (!seconds.isFinite() || seconds < 0) return@forEach

            // Bilibili 的 p 至少有 5 个字段；弹弹play 只有 4 个（无字号）。
            val bilibili = fields.size >= 5
            val colorIndex = if (bilibili) 3 else 2
            result += DanmakuComment(
                timeMs = (seconds * 1000.0).toLong(),
                text = text.take(MAX_TEXT_LENGTH),
                mode = modeOf(fields.getOrNull(1)),
                color = colorOf(fields.getOrNull(colorIndex)),
                sizeScale = if (bilibili) sizeScaleOf(fields.getOrNull(2)) else 1f,
            )
        }
        return result
    }

    private fun parseJson(raw: String): List<DanmakuComment>? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["comments"] as? JsonArray) ?: (root["danmaku"] as? JsonArray)
            else -> null
        } ?: return emptyList()
        return list.mapNotNull(::parseJsonItem)
    }

    private fun parseJsonItem(element: JsonElement): DanmakuComment? = when (element) {
        is JsonArray -> parseTuple(element)
        is JsonObject -> {
            val packed = element.string("p")
            val packedText = element.string("m")
            if (packed != null && packedText != null) {
                parseDandanplay(packed, packedText, element)
            } else {
                parseKeyed(element)
            }
        }
        else -> null
    }

    /** 弹弹play JSON：p="time,mode,color,user"，m=正文，t=秒。 */
    private fun parseDandanplay(
        packed: String,
        text: String,
        object_: JsonObject,
    ): DanmakuComment? {
        if (text.isBlank()) return null
        val fields = packed.split(',')
        val seconds = fields.getOrNull(0)?.trim()?.toDoubleOrNull()
            ?: object_.number("t")
            ?: return null
        if (!seconds.isFinite() || seconds < 0) return null
        return DanmakuComment(
            timeMs = (seconds * 1000.0).toLong(),
            text = text.trim().take(MAX_TEXT_LENGTH),
            mode = modeOf(fields.getOrNull(1)),
            color = colorOf(fields.getOrNull(2)),
            sizeScale = 1f,
        )
    }

    /** 通用 JSON：{time,text,mode,color,size}。 */
    private fun parseKeyed(object_: JsonObject): DanmakuComment? {
        val text = object_.string("text")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val seconds = object_.number("time") ?: object_.number("t") ?: return null
        if (!seconds.isFinite() || seconds < 0) return null
        val modeRaw = object_.string("mode")
            ?: object_.number("mode")?.toString()
        return DanmakuComment(
            timeMs = (seconds * 1000.0).toLong(),
            text = text.take(MAX_TEXT_LENGTH),
            mode = modeOf(modeRaw),
            color = colorOf(object_.string("color") ?: object_.number("color")?.toString()),
            sizeScale = sizeScaleOf(object_.number("size")?.toString()),
        )
    }

    /** 经典 CCL 元组：[time, mode, size, color, text]。 */
    private fun parseTuple(array: JsonArray): DanmakuComment? {
        val seconds = array.getOrNull(0)?.asText()?.toDoubleOrNull() ?: return null
        val text = array.getOrNull(4)?.asText()?.trim().orEmpty()
        if (text.isEmpty() || !seconds.isFinite() || seconds < 0) return null
        return DanmakuComment(
            timeMs = (seconds * 1000.0).toLong(),
            text = text.take(MAX_TEXT_LENGTH),
            mode = modeOf(array.getOrNull(1)?.asText()),
            color = colorOf(array.getOrNull(3)?.asText()),
            sizeScale = sizeScaleOf(array.getOrNull(2)?.asText()),
        )
    }

    private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.string(key: String): String? = this[key]?.asText()

    private fun JsonObject.number(key: String): Double? = string(key)?.toDoubleOrNull()

    private fun modeOf(raw: String?): DanmakuMode = when (raw?.trim()?.lowercase()) {
        "4", "bottom" -> DanmakuMode.BOTTOM
        "5", "top" -> DanmakuMode.TOP
        else -> DanmakuMode.SCROLL
    }

    private fun sizeScaleOf(raw: String?): Float {
        val size = raw?.trim()?.toFloatOrNull() ?: return 1f
        if (!size.isFinite() || size <= 0f) return 1f
        // Bilibili 的字号是绝对值 12-64，折算成约 0.6-1.6 的比例。
        return (size / 25f).coerceIn(0.6f, 1.6f)
    }

    private fun colorOf(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return DanmakuComment.WHITE
        if (value.startsWith("#")) {
            val hex = value.drop(1)
            return when (hex.length) {
                3 -> {
                    val expanded = hex.map { "$it$it" }.joinToString("")
                    rgb(expanded.toLongOrNull(16) ?: return DanmakuComment.WHITE, alpha = false)
                }
                6 -> rgb(hex.toLongOrNull(16) ?: return DanmakuComment.WHITE, alpha = false)
                8 -> hex.toLongOrNull(16)?.toInt() ?: DanmakuComment.WHITE
                else -> DanmakuComment.WHITE
            }
        }
        val number = value.toDoubleOrNull() ?: return DanmakuComment.WHITE
        return rgb(number.toLong(), alpha = false)
    }

    private fun rgb(value: Long, alpha: Boolean): Int {
        val argb = if (alpha) value else (0xFF000000L or (value and 0xFFFFFFL))
        return argb.toInt()
    }

    private fun unescape(raw: String): String {
        if (!raw.contains('&')) return raw
        return entity.replace(raw) { match ->
            val token = match.groupValues[1]
            val codePoint = when {
                token.startsWith("#x", ignoreCase = true) ->
                    token.drop(2).toIntOrNull(16)
                token.startsWith("#") -> token.drop(1).toIntOrNull()
                else -> null
            }
            if (codePoint != null) {
                runCatching { String(Character.toChars(codePoint)) }.getOrElse { match.value }
            } else {
                when (token.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
        }
    }
}

/** 某一帧需要绘制的一条弹幕。 */
data class DanmakuFrameItem(
    val comment: DanmakuComment,
    val x: Float,
    val y: Float,
)

/** 绘制回调使用原生 Float 参数，避免通用 Function3 对每条弹幕的坐标装箱。 */
fun interface DanmakuFrameConsumer {
    fun draw(comment: DanmakuComment, x: Float, y: Float)
}

/**
 * 预计算弹幕弹道，避免每帧做碰撞检测。
 *
 * 滚动弹幕按「上一条完全进入屏幕的时间」分配弹道；固定弹幕各自占用
 * 顶部/底部弹道。渲染时用二分查找定位当前时间附近的项目。
 */
class DanmakuTimeline(
    comments: List<DanmakuComment>,
    private val widthPx: Float,
    private val heightPx: Float,
    private val baseFontPx: Float,
    areaFraction: Float = 1f,
    private val scrollDurationMs: Long = 8_000L,
    private val fixedHoldMs: Long = 4_500L,
) {

    private val laneHeightPx = (baseFontPx * 1.45f).coerceAtLeast(1f)
    private val areaHeightPx = heightPx * areaFraction.coerceIn(0.1f, 1f)
    private val laneCount = (areaHeightPx / laneHeightPx).toInt().coerceAtLeast(1)

    private val scrollItems: List<Placement>
    private val topItems: List<Placement>
    private val bottomItems: List<Placement>

    init {
        val ordered = comments
            .filter { it.text.isNotBlank() && it.timeMs >= 0L }
            .sortedBy { it.timeMs }

        val scrollFreeAt = LongArray(laneCount) { Long.MIN_VALUE }
        val topFreeAt = LongArray(laneCount) { Long.MIN_VALUE }
        val bottomFreeAt = LongArray(laneCount) { Long.MIN_VALUE }

        val scroll = ArrayList<Placement>()
        val top = ArrayList<Placement>()
        val bottom = ArrayList<Placement>()

        ordered.forEach { comment ->
            val fontSize = baseFontPx * comment.sizeScale.coerceIn(0.5f, 2f)
            val width = estimateWidthPx(comment.text, fontSize)
            when (comment.mode) {
                DanmakuMode.SCROLL -> {
                    // 弹幕尾部完全进入屏幕后，该弹道才能安全放入下一条。
                    val speed = (widthPx + width) / scrollDurationMs.toFloat()
                    val tailEnterMs = (width / speed).toLong()
                    val lane = nextLane(scrollFreeAt, comment.timeMs)
                        ?: return@forEach
                    scrollFreeAt[lane] = comment.timeMs + tailEnterMs + 120L
                    scroll += Placement(comment, lane, width)
                }
                DanmakuMode.TOP -> {
                    val lane = nextLane(topFreeAt, comment.timeMs) ?: return@forEach
                    topFreeAt[lane] = comment.timeMs + fixedHoldMs + 120L
                    top += Placement(comment, lane, width)
                }
                DanmakuMode.BOTTOM -> {
                    val lane = nextLane(bottomFreeAt, comment.timeMs) ?: return@forEach
                    bottomFreeAt[lane] = comment.timeMs + fixedHoldMs + 120L
                    bottom += Placement(comment, lane, width)
                }
            }
        }

        scrollItems = scroll
        topItems = top
        bottomItems = bottom
    }

    fun activeAt(positionMs: Long): List<DanmakuFrameItem> {
        val result = ArrayList<DanmakuFrameItem>()
        forEachActive(positionMs) { comment, x, y ->
            result += DanmakuFrameItem(comment, x, y)
        }
        return result
    }

    /** 绘制热路径：直接遍历可见弹幕，不创建逐帧列表和每条弹幕的位置对象。 */
    fun forEachActive(positionMs: Long, action: DanmakuFrameConsumer) {
        if (positionMs < 0L) return

        var index = lowerBound(scrollItems, positionMs - scrollDurationMs)
        while (index < scrollItems.size) {
            val item = scrollItems[index]
            if (item.comment.timeMs > positionMs) break
            val elapsed = positionMs - item.comment.timeMs
            if (elapsed <= scrollDurationMs) {
                val progress = elapsed.toFloat() / scrollDurationMs.toFloat()
                val x = widthPx - progress * (widthPx + item.widthPx)
                val y = item.lane * laneHeightPx +
                    (laneHeightPx - baseFontPx * item.comment.sizeScale) / 2f
                action.draw(item.comment, x, y)
            }
            index++
        }

        forEachFixed(topItems, positionMs, top = true, action = action)
        forEachFixed(bottomItems, positionMs, top = false, action = action)
    }

    private fun forEachFixed(
        items: List<Placement>,
        positionMs: Long,
        top: Boolean,
        action: DanmakuFrameConsumer,
    ) {
        var index = lowerBound(items, positionMs - fixedHoldMs)
        while (index < items.size) {
            val item = items[index]
            if (item.comment.timeMs > positionMs) break
            if (positionMs - item.comment.timeMs <= fixedHoldMs) {
                val fontSize = baseFontPx * item.comment.sizeScale
                val x = ((widthPx - item.widthPx) / 2f).coerceAtLeast(2f)
                val y = if (top) {
                    item.lane * laneHeightPx + (laneHeightPx - fontSize) / 2f
                } else {
                    areaHeightPx - (item.lane + 1) * laneHeightPx +
                        (laneHeightPx - fontSize) / 2f
                }
                action.draw(item.comment, x, y)
            }
            index++
        }
    }

    /** 返回可用弹道；全部被占用时返回 null（宁可少画也不重叠）。 */
    private fun nextLane(freeAt: LongArray, timeMs: Long): Int? {
        for (lane in freeAt.indices) {
            if (freeAt[lane] <= timeMs) return lane
        }
        return null
    }

    private fun lowerBound(items: List<Placement>, thresholdMs: Long): Int {
        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (items[mid].comment.timeMs < thresholdMs) low = mid + 1 else high = mid
        }
        return low
    }

    private data class Placement(
        val comment: DanmakuComment,
        val lane: Int,
        val widthPx: Float,
    )

    companion object {
        /**
         * 估算文本宽度。弹道分配不需要像素级精确，按中日韩全角/ASCII
         * 半角折算即可，实际绘制仍由 Compose 精确测量。
         */
        fun estimateWidthPx(text: String, fontPx: Float): Float {
            var units = 0f
            for (char in text) {
                units += when {
                    char == ' ' -> 0.35f
                    char.code < 0x80 -> 0.56f
                    else -> 1f
                }
            }
            return (units * fontPx).coerceAtLeast(fontPx)
        }
    }
}
