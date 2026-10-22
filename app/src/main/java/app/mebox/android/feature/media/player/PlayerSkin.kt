package app.mebox.android.feature.media.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放器设计 token。
 *
 * 视觉语言来自参考播放器：液态/毛玻璃浮层 + 线性图标 + 沉浸式画面 +
 * 底部一体化控制栏。所有颜色、字号、圆角都集中在这里，页面和组件不再
 * 写死色值；需要换主题色时只改 [Accent] / [AccentStrong]。
 */
object PlayerSkin {

    // ── 颜色 · 深色（播放器默认） ──
    val Accent = Color(0xFFF2A65A)
    val AccentStrong = Color(0xFFFFD39B)
    val AccentSoft = Color(0x29F2A65A)      // 16%
    val AccentLine = Color(0x57F2A65A)      // 34%

    val Bg = Color(0xFF08090B)
    val Surface = Color(0xFF101216)
    val SurfaceElevated = Color(0xFF15181E)

    val GlassFill = Color(0x85101216)       // 52%
    val GlassFillStrong = Color(0x8C101216) // 55%
    val GlassBorder = Color(0x24FFFFFF)     // 14%
    val Highlight = Color(0x24FFFFFF)       // inset 高光 14%

    val TextPrimary = Color(0xFFF4F5F7)
    val TextSecondary = Color(0x9EF4F5F7)   // 62%
    val TextTertiary = Color(0x61F4F5F7)    // 38%

    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF58D68D)

    val Track = Color(0x29FFFFFF)           // 16%
    val TrackBuffered = Color(0x52FFFFFF)   // 32%

    val IconRest = Color(0xD6FFFFFF)        // 84%
    val White10 = Color(0x1AFFFFFF)
    val White06 = Color(0x0FFFFFFF)
    val White08 = Color(0x14FFFFFF)
    val White14 = Color(0x24FFFFFF)
    val White18 = Color(0x2EFFFFFF)
    val White28 = Color(0x47FFFFFF)
    val White55 = Color(0x8CFFFFFF)
    val White78 = Color(0xC7FFFFFF)

    val ScrimTop = Color(0xBD000000)        // 74%
    val ScrimBottom = Color(0xDB000000)     // 86%
    val Scrim = Color(0x75000000)

    // ── 字体 ──
    val Title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val Headline = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val BodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val Time = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val Overline = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.44.sp,
    )
    val ActionLabel = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
    val ValueStrong = TextStyle(
        fontSize = 13.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )
    val Badge = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.2.sp,
    )

    // ── 尺寸 ──
    val RadiusSmall = 10.dp
    val RadiusControl = 12.dp
    val RadiusCard = 14.dp
    val RadiusSheet = 28.dp
    val TouchTarget = 44.dp
}

/**
 * 玻璃浮层：半透明深色填充 + 顶部白色渐变 + 1px 高光边框 + 外阴影。
 *
 * Compose 没有跨层的实时背景模糊，视频画面又由 SurfaceView/TextureView 绘制，
 * 因此这里采用规范里的 Frosted/Solid 近似配方：填充、渐变、边框和内高光
 * 都能在任意亮度的画面上保证可读性，也不会因为每帧模糊导致掉帧。
 */
@Composable
fun PlayerGlass(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clip(shape)
            .background(if (strong) PlayerSkin.GlassFillStrong else PlayerSkin.GlassFill)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x1AFFFFFF), Color(0x06FFFFFF)),
                ),
            )
            .border(1.dp, PlayerSkin.GlassBorder, shape)
            .drawWithContent {
                drawContent()
                val inset = 1.dp.toPx()
                drawLine(
                    color = PlayerSkin.Highlight,
                    start = Offset(inset, inset),
                    end = Offset(size.width - inset, inset),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        content = content,
    )
}

/** 按下缩放 0.94，120ms；与 clickable 共用同一个 InteractionSource。 */
@Composable
fun Modifier.playerPressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "playerPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 播放器图标按钮。
 *
 * - 默认 40×40、线性图标 22、透明底；
 * - [glass] 给侧边/播放键使用毛玻璃底；
 * - [active] 用强调色 16% 底 + 34% 边框；
 * - 电视遥控器获得焦点时补一层白色描边，避免 D-pad 操作没有反馈。
 */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PlayerSkin.IconRest,
    active: Boolean = false,
    glass: Boolean = false,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = CircleShape,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val background = when {
        active -> PlayerSkin.AccentSoft
        focused -> PlayerSkin.White10
        glass -> PlayerSkin.GlassFill
        else -> Color.Transparent
    }
    val border = when {
        active -> PlayerSkin.AccentLine
        focused -> PlayerSkin.White28
        glass -> PlayerSkin.GlassBorder
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (glass) {
                    Modifier.shadow(
                        elevation = 12.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                    )
                } else {
                    Modifier
                },
            )
            .playerPressScale(interaction)
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) PlayerSkin.Accent else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 底部操作项：上方图标/数值，下方 10sp 标签。
 *
 * [value] 用于倍速（1.0×）和画质（4K）这类文字型入口；[badgeStyle] 会加
 * 1px 边框，和图标入口区分开。
 */
@Composable
fun PlayerActionItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    active: Boolean = false,
    badgeStyle: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PlayerSkin.RadiusSmall)
    Column(
        modifier = modifier
            .heightIn(min = 46.dp)
            .playerPressScale(interaction)
            .clip(shape)
            .background(if (focused) PlayerSkin.White08 else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.height(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                value != null -> Box(
                    modifier = Modifier.then(
                        if (badgeStyle) {
                            Modifier
                                .border(
                                    1.dp,
                                    if (active) PlayerSkin.AccentLine else PlayerSkin.White28,
                                    RoundedCornerShape(5.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        } else {
                            Modifier
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        value,
                        style = if (badgeStyle) PlayerSkin.Badge else PlayerSkin.ValueStrong,
                        color = if (active) PlayerSkin.Accent else PlayerSkin.TextPrimary,
                        maxLines = 1,
                    )
                }

                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) PlayerSkin.Accent else PlayerSkin.White78,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = PlayerSkin.ActionLabel,
            color = if (active) PlayerSkin.Accent else PlayerSkin.White78,
            maxLines = 1,
        )
    }
}

/** 播放/暂停图标：自绘保证在任意尺寸下都保持同一套笔画比例。 */
@Composable
fun PlayPauseGlyph(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier) {
        if (isPlaying) {
            val barWidth = size.width * 0.24f
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.18f, size.height * 0.1f),
                size = Size(barWidth, size.height * 0.8f),
                cornerRadius = CornerRadius(3f),
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.58f, size.height * 0.1f),
                size = Size(barWidth, size.height * 0.8f),
                cornerRadius = CornerRadius(3f),
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.08f)
                lineTo(size.width * 0.9f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.92f)
                close()
            }
            drawPath(path, tint)
        }
    }
}
