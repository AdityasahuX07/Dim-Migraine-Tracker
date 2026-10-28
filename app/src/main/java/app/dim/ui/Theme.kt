package app.dim.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable class DimColors(val bg: Color, val card: Color, val text: Color, val sub: Color,
                           val accent: Color, val accent2: Color, val onAccent: Color,
                           val fill: Color, val line: Color, val calm: Boolean)

/** Single hardcoded dark theme: deep violet-black surfaces, muted lavender accent, low stimulation. */
val DimPalette = DimColors(
    bg = Color(0xFF0E0B14), card = Color(0xFF1A1624), text = Color(0xFFE6E1EF), sub = Color(0xFF9A92A8),
    accent = Color(0xFF8F76CC), accent2 = Color(0xFF6A55A8), onAccent = Color(0xFFF4F0FA),
    fill = Color(0xFF2A2338), line = Color(0xFF2E2740), calm = true)

object Type {
    private val f = FontFamily.SansSerif
    val display = TextStyle(fontFamily = f, fontWeight = FontWeight.Light, fontSize = 60.sp, letterSpacing = (-1).sp)
    val title = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp)
    val headline = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
    val body = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 17.sp)
    val caption = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val label = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.8.sp)
}

val LocalDim = staticCompositionLocalOf { DimPalette }
val Dim: DimColors @Composable get() = LocalDim.current

@Composable
fun DimTheme(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDim provides DimPalette, LocalNavVisibility provides remember { NavVisibility() }, content = content)
