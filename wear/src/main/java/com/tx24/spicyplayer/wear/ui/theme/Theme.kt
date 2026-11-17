package com.tx24.spicyplayer.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import com.tx24.spicyplayer.wear.R

/**
 * The app's look: black, since a watch is mostly black anyway (OLED), with the song's own colour
 * for the parts that should stand out. The lyrics keep the font the phone draws them with.
 */
internal object WearColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF15161A)
    val SurfaceHigh = Color(0xFF23252B)
    val Text = Color(0xFFF6F6F8)
    val TextDim = Color(0xFF8D9099)
    val TextFaint = Color(0xFF5A5D66)
    val Accent = Color(0xFF8B5CF6)
    val Unsung = Color(0x66FFFFFF)
}

/** The song's colour, for the transport and the accents. */
internal val LocalAccent = staticCompositionLocalOf { WearColors.Accent }

/** The lyrics font the phone app bundles, at the weights the renderer asks for. */
internal val LyricsFont = FontFamily(
    Font(R.font.lyrics_regular, FontWeight.Normal),
    Font(R.font.lyrics_medium, FontWeight.Medium),
    Font(R.font.lyrics_semibold, FontWeight.SemiBold),
    Font(R.font.lyrics_bold, FontWeight.Bold),
)

private val SpicyTypography = Typography(
    displayMedium = TextStyle(fontFamily = LyricsFont, fontWeight = FontWeight.Bold, fontSize = 34.sp),
    titleLarge = TextStyle(fontFamily = LyricsFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = LyricsFont, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = LyricsFont, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = LyricsFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = LyricsFont, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)

@Composable
internal fun SpicyWearTheme(accent: Color = WearColors.Accent, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(typography = SpicyTypography, content = content)
    }
}

/** The song's own accent, from the colours the phone read off the cover. */
internal fun songAccent(colors: List<Int>): Color {
    val argb = colors.firstOrNull() ?: return WearColors.Accent
    val color = Color(argb)
    val luminance = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    if (luminance >= 0.4f) return color
    val amount = 0.5f
    return Color(
        red = color.red + (1f - color.red) * amount,
        green = color.green + (1f - color.green) * amount,
        blue = color.blue + (1f - color.blue) * amount,
    )
}
