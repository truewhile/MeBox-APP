package app.mebox.android.feature.media.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.mebox.android.core.media.DanmakuComment
import app.mebox.android.core.media.DanmakuTimeline
import kotlinx.coroutines.isActive

/**
 * 弹幕渲染层。
 *
 * 位置完全由播放器的绝对播放时间推导，因此拖动进度或倍速播放都不会与画面
 * 脱节。绘制发生在 Canvas 的绘制阶段，每帧只重绘而不重组；
 * 弹道在尺寸/样式变化时预计算一次。
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    enabled: Boolean,
    opacity: Float,
    fontScale: Float,
    areaFraction: Float,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    if (!enabled || comments.isEmpty()) return

    val density = LocalDensity.current
    val baseFontSp = BASE_FONT_SP * fontScale
    val baseFontPx = with(density) { baseFontSp.sp.toPx() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val timeline = remember(comments, viewport, baseFontPx, areaFraction) {
        if (viewport.width <= 0 || viewport.height <= 0) {
            null
        } else {
            DanmakuTimeline(
                comments = comments,
                widthPx = viewport.width.toFloat(),
                heightPx = viewport.height.toFloat(),
                baseFontPx = baseFontPx,
                areaFraction = areaFraction,
            )
        }
    }

    val textMeasurer = rememberTextMeasurer(cacheSize = 1024)
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(timeline) {
        if (timeline == null) return@LaunchedEffect
        // 帧时钟只用于驱动重绘；弹幕位置读取播放器时间，暂停时自然停住。
        while (isActive) {
            withFrameNanos { frameNanos = it }
        }
    }
    val currentPosition by rememberUpdatedState(positionProvider)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        // 在绘制阶段读取帧时钟：只触发重绘，不触发重组。
        val frame = frameNanos
        if (frame < 0L) return@Canvas
        val positionMs = currentPosition().coerceAtLeast(0L)
        val items = timeline?.activeAt(positionMs) ?: return@Canvas
        val alpha = opacity.coerceIn(0.1f, 1f)
        items.forEach { item ->
            val comment = item.comment
            val style = TextStyle(
                color = Color(comment.color),
                fontSize = (baseFontSp * comment.sizeScale).sp,
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(
                    color = Color(0xCC000000),
                    offset = Offset(0f, 2f),
                    blurRadius = 3f,
                ),
            )
            val layout = textMeasurer.measure(comment.text, style)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(item.x, item.y),
                alpha = alpha,
            )
        }
    }
}

private const val BASE_FONT_SP = 18f
