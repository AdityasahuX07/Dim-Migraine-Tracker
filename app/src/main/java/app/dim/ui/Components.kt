package app.dim.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.R
import kotlin.math.roundToInt

@Composable
fun Card(modifier: Modifier = Modifier, pad: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.fillMaxWidth().background(Dim.card, shape).border(0.5.dp, Dim.line, shape).padding(pad), content = content)
}

@Composable
fun Txt(s: String, style: TextStyle = Type.body, sub: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    androidx.compose.foundation.text.BasicText(s, modifier,
        style.copy(color = if (style.color != Color.Unspecified) style.color else if (sub) Dim.sub else Dim.text),
        softWrap = maxLines == Int.MAX_VALUE, maxLines = maxLines)

/** Delete / destructive actions. */
internal val DangerRed = Color(0xFFFF453A)

/** Pressable surface with a gentle spring; no motion in Migraine Mode. Pass [onLongClick] to also react to a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Press(onClick: () -> Unit, modifier: Modifier = Modifier, label: String, onLongClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val calm = Dim.calm
    val s by animateFloatAsState(if (down && !calm) 0.96f else 1f, spring(dampingRatio = 0.6f), label = "press")
    Box(modifier.scale(s).semantics { contentDescription = label; role = Role.Button }
        .then(if (onLongClick != null) Modifier.combinedClickable(src, null, onClick = onClick, onLongClick = onLongClick)
              else Modifier.clickable(src, null, onClick = onClick)), content = content)
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) = Press(onClick, label = text + if (on) ", selected" else "") {
    val shape = RoundedCornerShape(50)
    Box(Modifier.background(if (on) Dim.accent.copy(alpha = .16f) else Dim.fill, shape)
        .border(1.dp, if (on) Dim.accent else Color.Transparent, shape).padding(14.dp, 9.dp)) {
        Txt(text, Type.caption.copy(color = if (on) Dim.accent else Dim.text, fontSize = 15.sp))
    }
}

/** Muted sage -> sand -> rose; kept soft on purpose. */
@Composable
fun painColor(v: Int): Color {
    val g = Color(0xFF6E9484); val a = Color(0xFFB8A06A); val r = Color(0xFFB5607E)
    return if (v <= 5) lerp(g, a, v / 5f) else lerp(a, r, (v - 5) / 5f)
}

/** Start / end time as shown to the user: "19 Oct 2026 • 03:25 PM" (English month names and AM/PM on every device). */
internal fun dateStamp(t: Long): String = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(t))
internal fun timeStamp(t: Long): String = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date(t))
internal fun dateTimeStamp(t: Long): String = dateStamp(t) + " • " + timeStamp(t)

fun painLabel(v: Int?) = when (v) { null -> "Not set"; 0 -> "None"; in 1..3 -> "Mild"; in 4..6 -> "Moderate"; in 7..8 -> "Severe"; else -> "Worst" }

/** Custom 0–10 slider with a severity gradient. Tap or drag; accessible via semantics. */
@Composable
fun PainSlider(value: Int?, track: Color = Dim.fill, onChange: (Int) -> Unit) {
    val v = value ?: 0
    val r = with(LocalDensity.current) { 15.dp.toPx() }
    var w by remember { mutableStateOf(1f) }
    fun at(x: Float) = (((x - r) / (w - 2 * r).coerceAtLeast(1f)) * 10f).roundToInt().coerceIn(0, 10)
    val brush = Brush.horizontalGradient(listOf(painColor(0), painColor(5), painColor(10)), startX = r, endX = (w - r).coerceAtLeast(r + 1))
    Canvas(Modifier.fillMaxWidth().height(44.dp).onSizeChanged { w = it.width.toFloat() }
        .pointerInput(w) { detectTapGestures { onChange(at(it.x)) } }
        .pointerInput(w) { detectHorizontalDragGestures { c, _ -> onChange(at(c.position.x)) } }
        .semantics {
            contentDescription = "Intensity"
            progressBarRangeInfo = ProgressBarRangeInfo(v.toFloat(), 0f..10f, 9)
            setProgress { onChange(it.roundToInt().coerceIn(0, 10)); true }
        }) {
        val th = 12.dp.toPx(); val cy = size.height / 2
        val x = r + (size.width - 2 * r) * v / 10f
        drawRoundRect(track, Offset(r, cy - th / 2), Size(size.width - 2 * r, th), CornerRadius(th / 2))
        if (value != null) drawRoundRect(brush, Offset(r, cy - th / 2), Size((x - r).coerceAtLeast(th), th), CornerRadius(th / 2))
        drawCircle(Color.Black.copy(alpha = .12f), r + 2.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
        drawCircle(Color.White, r, Offset(x, cy))
    }
}

private val NavPillHeight = 68.dp
private val NavPillTopGap = 6.dp
private val NavPillBottomGap = 14.dp

/**
 * Space the floating nav pill covers above the system navigation bar. The pill is drawn over the screen
 * (no backing strip), so any screen whose content reaches the bottom adds this after its last item.
 */
val NavBarClearance = NavPillHeight + NavPillTopGap + NavPillBottomGap

/** Lets a screen hide the floating nav pill while it shows a bottom sheet (the pill is drawn above screen content). */
@Stable
class NavVisibility {
    private var count by mutableStateOf(0)   // several screens may ask at once (sub screen + its sheet)
    val hidden: Boolean get() = count > 0
    fun hide() { count++ }
    fun show() { if (count > 0) count-- }
}
val LocalNavVisibility = staticCompositionLocalOf { NavVisibility() }

/** Floating dark glass pill, drawn over the content with nothing behind it; the selected tab sits in a lighter gray capsule. */
@Composable
fun NavBar(tab: Int, modifier: Modifier = Modifier, onTab: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = NavPillTopGap, bottom = NavPillBottomGap)) {
        Row(Modifier.fillMaxWidth().height(NavPillHeight)
            .shadow(16.dp, shape)
            .clip(shape).background(Color(0xFF2B2B2F)).border(0.5.dp, Color.White.copy(alpha = .1f), shape)
            .padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Home", "History", "Insights", "Settings").forEachIndexed { i, l ->
                val on = i == tab; val c = if (on) Color.White else Color(0xFFA6A6AD)
                Press({ onTab(i) }, Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .then(if (on) Modifier.background(Color(0xFF4A4A50), shape) else Modifier),
                    "$l tab" + if (on) ", selected" else "") {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        when (i) {
                            // Home and Insights use the XML vector drawables (white, tinted with the tab colour).
                            0 -> NavDrawable(R.drawable.ic_nav_home, c)
                            2 -> NavDrawable(R.drawable.ic_nav_insights, c)
                            else -> Canvas(Modifier.size(24.dp)) { navIcon(i, c) }
                        }
                        Spacer(Modifier.height(3.dp))
                        Txt(l, Type.caption.copy(color = c, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
                    }
                }
            }
        }
    }
}

/** A white vector drawable from res, recoloured to [tint] (the tab's selected / unselected colour). */
@Composable
private fun NavDrawable(@DrawableRes id: Int, tint: Color) =
    Image(painterResource(id), contentDescription = null, modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))

/** Canvas-drawn tab icons still in use: History (1) and Settings (anything else). Home and Insights are XML drawables. */
private fun DrawScope.navIcon(i: Int, c: Color) {
    val w = size.width; val h = size.height; val sw = 2.dp.toPx()
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (i) {
        1 -> { drawCircle(c, w / 2 - sw, style = st)
            drawLine(c, Offset(w / 2, h / 2), Offset(w / 2, h * .28f), sw, StrokeCap.Round)
            drawLine(c, Offset(w / 2, h / 2), Offset(w * .68f, h * .6f), sw, StrokeCap.Round) }
        else -> listOf(.25f to .65f, .5f to .35f, .75f to .7f).forEach { (y, x) ->
            drawLine(c, Offset(w * .1f, h * y), Offset(w * .9f, h * y), sw, StrokeCap.Round)
            drawCircle(c, 3.5.dp.toPx(), Offset(w * x, h * y)) }
    }
}
