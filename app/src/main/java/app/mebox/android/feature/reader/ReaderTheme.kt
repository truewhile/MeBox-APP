package app.mebox.android.feature.reader

import androidx.compose.ui.graphics.Color
import app.mebox.android.core.reader.ReaderStyle

/** 阅读界面配色：正文、次级文字和浮层背景。 */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val chrome: Color,
)

data class ReaderThemePreset(
    val id: String,
    val label: String,
    val background: Color,
    val text: Color,
)

/** 与网页阅读器同名的预设主题；只影响正文配色，夜间模式单独覆盖。 */
val ReaderThemePresets = listOf(
    ReaderThemePreset("preset1", "米黄", Color(0xFFF6EFE0), Color(0xFF2C2A26)),
    ReaderThemePreset("preset2", "豆绿", Color(0xFFE4EFE4), Color(0xFF233127)),
    ReaderThemePreset("preset3", "浅灰", Color(0xFFEBEBEB), Color(0xFF222222)),
    ReaderThemePreset("preset4", "青灰", Color(0xFFE3EAEC), Color(0xFF1F2B2E)),
)

private val NightBackground = Color(0xFF111113)
private val NightText = Color(0xFFE6E6E6)
private val NightSecondary = Color(0xFFB3B3B3)

fun readerPalette(style: ReaderStyle): ReaderPalette {
    if (style.night) {
        return ReaderPalette(
            background = NightBackground,
            text = NightText,
            secondary = NightSecondary,
            chrome = Color(0xF21C1C1E),
        )
    }
    val preset = ReaderThemePresets.firstOrNull { it.id == style.themeId } ?: ReaderThemePresets.first()
    return ReaderPalette(
        background = preset.background,
        text = preset.text,
        secondary = preset.text.copy(alpha = 0.62f),
        chrome = preset.background.copy(alpha = 0.97f),
    )
}
