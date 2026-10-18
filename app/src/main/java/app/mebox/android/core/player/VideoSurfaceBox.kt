package app.mebox.android.core.player

import kotlin.math.roundToInt

/** 画面框：视频画面在宿主里占据的像素尺寸。 */
internal data class VideoSurfaceBox(val width: Int, val height: Int)

/**
 * 由轨道元数据算显示宽高比（含 SAR 与 90°/270° 旋转）。
 *
 * @param rotated 轨道带 90°/270° 旋转时宽高需要互换。
 * @return 尺寸无效或算出来不像视频比例时返回 null，调用方退回铺满宿主。
 */
internal fun videoDisplayAspect(
    width: Int,
    height: Int,
    sarNum: Int = 1,
    sarDen: Int = 1,
    rotated: Boolean = false,
): Float? {
    if (width <= 0 || height <= 0) return null
    var w = width.toFloat()
    var h = height.toFloat()
    if (rotated) {
        val swap = w
        w = h
        h = swap
    }
    val num = if (sarNum > 0) sarNum else 1
    val den = if (sarDen > 0) sarDen else 1
    val aspect = w * num / den / h
    return aspect.takeIf { it.isFinite() && it in 0.2f..5f }
}

/**
 * 计算画面框在宿主里的尺寸。
 *
 * - [EngineResizeMode.FIT]：整幅画面可见，短边方向留黑边（画面框比宿主窄或矮）。
 * - [EngineResizeMode.ZOOM]：填满宿主，超出的部分由宿主裁掉。
 * - [EngineResizeMode.FILL]：和宿主一样大，由内核拉伸。
 *
 * [aspect] 未知时一律铺满宿主，保持内核自己的默认行为。
 */
internal fun videoSurfaceBox(
    hostWidth: Int,
    hostHeight: Int,
    aspect: Float?,
    mode: EngineResizeMode,
): VideoSurfaceBox? {
    if (hostWidth <= 0 || hostHeight <= 0) return null
    if (aspect == null || mode == EngineResizeMode.FILL) {
        return VideoSurfaceBox(hostWidth, hostHeight)
    }
    val hostWider = hostWidth.toFloat() / hostHeight > aspect
    return when (mode) {
        EngineResizeMode.FIT -> if (hostWider) {
            VideoSurfaceBox((hostHeight * aspect).roundToInt().coerceAtLeast(1), hostHeight)
        } else {
            VideoSurfaceBox(hostWidth, (hostWidth / aspect).roundToInt().coerceAtLeast(1))
        }

        EngineResizeMode.ZOOM -> if (hostWider) {
            VideoSurfaceBox(hostWidth, (hostWidth / aspect).roundToInt().coerceAtLeast(1))
        } else {
            VideoSurfaceBox((hostHeight * aspect).roundToInt().coerceAtLeast(1), hostHeight)
        }

        EngineResizeMode.FILL -> VideoSurfaceBox(hostWidth, hostHeight)
    }
}

/** LibVLC 的 track orientation：4..7 表示 90°/270° 旋转，宽高需要互换。 */
internal fun isQuarterTurn(orientation: Int): Boolean = orientation in 4..7
