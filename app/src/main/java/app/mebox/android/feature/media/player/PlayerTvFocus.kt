package app.mebox.android.feature.media.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 当前播放页是否运行在电视（遥控器优先）模式。
 *
 * 播放器外壳、浮层和分集条都从这个 CompositionLocal 读取电视规格，
 * 避免把 isTv 一路透传到每个小组件。
 */
internal val LocalPlayerIsTv = staticCompositionLocalOf { false }

/**
 * 电视端焦点规格。
 *
 * 手机上的焦点态几乎不出现，电视上是唯一的位置反馈，所以描边比手机
 * 常规状态更粗、对比度更高，并配合轻微放大。
 */
internal object PlayerTvFocus {
    val RingWidth: Dp = 2.dp
    val CardRingWidth: Dp = 2.5.dp
    val Ring: Color = Color.White
    val RingSoft: Color = Color(0xE6FFFFFF)
    val Fill: Color = Color(0x1FFFFFFF)
    val Scale: Float = 1.04f
    val CardScale: Float = 1.05f
}

/**
 * 焦点视觉：提亮底色 + 高对比描边 + 轻微放大。
 *
 * 缩放通过 graphicsLayer 完成，不参与布局测量，焦点移动时不会引起重新排版。
 * 未获得焦点时（手机上恒为 false）不产生任何绘制，保持原外观。
 */
@Composable
internal fun Modifier.playerTvFocus(
    focused: Boolean,
    shape: Shape,
    scale: Float = PlayerTvFocus.Scale,
    ringWidth: Dp = PlayerTvFocus.RingWidth,
    ringColor: Color = PlayerTvFocus.Ring,
): Modifier {
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "player-tv-focus",
    )
    return this
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .background(if (focused) PlayerTvFocus.Fill else Color.Transparent, shape)
        .then(if (focused) Modifier.border(ringWidth, ringColor, shape) else Modifier)
}

/**
 * 面板打开后把焦点送进去。
 *
 * Compose 的首帧焦点树可能还没建好，直接 requestFocus 偶尔会失败，
 * 所以和页面里现有的做法一样做少量重试。
 */
internal suspend fun requestPlayerTvFocus(
    requester: FocusRequester,
    attempts: Int = 5,
    delayMs: Long = 70,
) {
    repeat(attempts) {
        kotlinx.coroutines.delay(delayMs)
        if (runCatching { requester.requestFocus() }.isSuccess) return
    }
}
