package app.mebox.android.feature.media.player

import android.util.LruCache
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.mebox.android.core.media.DanmakuComment
import app.mebox.android.core.media.DanmakuPlaybackClock
import app.mebox.android.core.media.DanmakuTimeline
import kotlinx.coroutines.isActive

/**
 * 弹幕渲染层：用帧时钟平滑推进移动，与播放器进度校准。
 *
 * 每帧只更新 Canvas 的绘制状态，不触发重组；弹道在尺寸/样式变化时预计算，
 * 文字排版按需放入有界缓存，绘制时不创建逐条弹幕的位置对象。
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    enabled: Boolean,
    opacity: Float,
    fontScale: Float,
    areaFraction: Float,
    positionProvider: () -> Long,
    playing: Boolean,
    playbackSpeed: Float,
    playbackKey: Any,
    discontinuity: Int,
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

    // 命中时直接复用排版，不再逐帧创建 TextStyle 或查询 TextMeasurer 的布局缓存。
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val layouts = remember(textMeasurer, baseFontSp) {
        LruCache<DanmakuComment, TextLayoutResult>(TEXT_CACHE_SIZE)
    }
    val clock = remember(playbackKey) { DanmakuPlaybackClock() }
    var positionMs by remember(clock) { mutableLongStateOf(-1L) }
    val currentPosition by rememberUpdatedState(positionProvider)
    LaunchedEffect(timeline, clock, playing, playbackSpeed, discontinuity) {
        if (timeline == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { frameNanos ->
                positionMs = clock.positionMs(
                    frameNanos = frameNanos,
                    sampledPositionMs = currentPosition(),
                    playing = playing,
                    speed = playbackSpeed,
                    discontinuity = discontinuity,
                )
            }
            // 暂停时相同位置不会触发重绘；继续采样以接收异步 seek 的最终落点。
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        // 仅在绘制阶段读取位置：逐帧移动不触发 Compose 重组。
        val position = positionMs
        if (position < 0L) return@Canvas
        val alpha = opacity.coerceIn(0.1f, 1f)
        timeline?.forEachActive(position) { comment, x, y ->
            val layout = layouts.get(comment) ?: textMeasurer.measure(
                text = comment.text,
                style = TextStyle(
                    color = Color(comment.color),
                    fontSize = (baseFontSp * comment.sizeScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = Shadow(
                        color = Color(0xCC000000),
                        offset = Offset(0f, 2f),
                        blurRadius = 3f,
                    ),
                ),
                softWrap = false,
                maxLines = 1,
            ).also { layouts.put(comment, it) }
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x, y),
                alpha = alpha,
            )
        }
    }
}

private const val BASE_FONT_SP = 18f
private const val TEXT_CACHE_SIZE = 1024
