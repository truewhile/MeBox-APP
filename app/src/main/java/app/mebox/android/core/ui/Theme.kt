package app.mebox.android.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 视觉基调参考开源阅读应用「阅读 R / legado」：浅灰底、纯白卡片、iOS 蓝强调色，
 * 圆角克制（面板 10dp、卡片 14dp、动作 9dp），正文偏紧凑。
 */
object MeBoxColor {
    val Accent = Color(0xFF0A84FF)

    val LightBackground = Color(0xFFF7F7FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightMenu = Color(0xFFF1F2F6)
    val LightPrimaryText = Color(0xDE000000)
    val LightSecondaryText = Color(0x8A000000)
    val LightDivider = Color(0x1F000000)

    val DarkBackground = Color(0xFF111113)
    val DarkSurface = Color(0xFF1C1C1E)
    val DarkMenu = Color(0xFF252528)
    val DarkPrimaryText = Color(0xFFFFFFFF)
    val DarkSecondaryText = Color(0xB3FFFFFF)
    val DarkDivider = Color(0x1FFFFFFF)

    val Error = Color(0xFFEB4333)
    val Success = Color(0xFF439B53)
}

/** 常用尺寸，和阅读 R 的 ui_panel_radius / ui_action_radius 对齐。 */
object MeBoxDimens {
    val ScreenPadding = 16.dp
    val PanelRadius = 10.dp
    val ActionRadius = 9.dp
    val CardRadius = 14.dp
    val PosterRadius = 8.dp
    val BottomBarHeight = 52.dp
    val ContentBottomPadding = 24.dp
}

private val LightColors = lightColorScheme(
    primary = MeBoxColor.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F0FF),
    onPrimaryContainer = Color(0xFF00315C),
    inversePrimary = Color(0xFF8ABBFF),
    secondary = Color(0xFF5B6472),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF0F5),
    onSecondaryContainer = Color(0xFF1E2733),
    tertiary = Color(0xFF4F616E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAF1F6),
    onTertiaryContainer = Color(0xFF0E1D27),
    background = MeBoxColor.LightBackground,
    onBackground = MeBoxColor.LightPrimaryText,
    surface = MeBoxColor.LightSurface,
    onSurface = MeBoxColor.LightPrimaryText,
    surfaceVariant = MeBoxColor.LightMenu,
    onSurfaceVariant = MeBoxColor.LightSecondaryText,
    surfaceTint = MeBoxColor.Accent,
    inverseSurface = Color(0xFF252528),
    inverseOnSurface = Color(0xFFF4F4F6),
    error = MeBoxColor.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E1),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFC6C9D0),
    outlineVariant = Color(0xFFE4E5EA),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFEDEEF2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F2F6),
    surfaceContainerHighest = Color(0xFFE9EBF0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3F97FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B3A66),
    onPrimaryContainer = Color(0xFFCFE5FF),
    inversePrimary = Color(0xFF005FAF),
    secondary = Color(0xFFB4BCC8),
    onSecondary = Color(0xFF161C24),
    secondaryContainer = Color(0xFF2A2F38),
    onSecondaryContainer = Color(0xFFE2E5EA),
    tertiary = Color(0xFFB7C8D4),
    onTertiary = Color(0xFF0F1F2A),
    tertiaryContainer = Color(0xFF2A343D),
    onTertiaryContainer = Color(0xFFD5E4EE),
    background = MeBoxColor.DarkBackground,
    onBackground = MeBoxColor.DarkPrimaryText,
    surface = MeBoxColor.DarkSurface,
    onSurface = MeBoxColor.DarkPrimaryText,
    surfaceVariant = MeBoxColor.DarkMenu,
    onSurfaceVariant = MeBoxColor.DarkSecondaryText,
    surfaceTint = Color(0xFF3F97FF),
    inverseSurface = Color(0xFFE6E6E9),
    inverseOnSurface = Color(0xFF1C1C1E),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF330000),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A4A50),
    outlineVariant = Color(0xFF2C2C30),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2A2D),
    surfaceDim = Color(0xFF111113),
    surfaceContainerLowest = Color(0xFF0D0D0F),
    surfaceContainerLow = Color(0xFF191A1D),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF252528),
    surfaceContainerHighest = Color(0xFF303034),
)

private val MeBoxTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

private val MeBoxShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(MeBoxDimens.PanelRadius),
    large = RoundedCornerShape(MeBoxDimens.CardRadius),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun MeBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MeBoxTypography,
        shapes = MeBoxShapes,
        content = content,
    )
}
