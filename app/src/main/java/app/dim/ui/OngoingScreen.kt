package app.dim.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.data.Episode
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private fun clock(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
internal fun elapsedText(ms: Long): String { val m = (ms / 60_000).coerceAtLeast(0); return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m" }

@Composable
private fun rememberNow(): State<Long> {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now.value = System.currentTimeMillis(); delay(1_000L) } }
    return now
}

/** Dark card with a soft purple glow bleeding in from the edges. */
@Composable
private fun GlowCard(modifier: Modifier = Modifier, radius: Dp = 52.dp, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    val glow = Dim.accent2; val hi = Dim.accent; val none = glow.copy(alpha = 0f)
    Box(modifier.clip(shape).background(Color(0xFF0D0A1F)).drawBehind {
        val e = 90.dp.toPx(); val s = 60.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(glow.copy(alpha = .85f), none), 0f, e))
        drawRect(Brush.verticalGradient(listOf(none, glow.copy(alpha = .55f)), size.height - e * .8f, size.height))
        drawRect(Brush.horizontalGradient(listOf(glow.copy(alpha = .6f), none), 0f, s))
        drawRect(Brush.horizontalGradient(listOf(none, glow.copy(alpha = .6f)), size.width - s, size.width))
    }.border(1.dp, Brush.verticalGradient(listOf(hi.copy(alpha = .55f), hi.copy(alpha = .12f))), shape), content = content)
}

/** Circular halftone of white dots; a slow ripple of size and brightness drifts outward from the middle. */
@Composable
private fun DotField(modifier: Modifier, centerY: Float) {
    val phase = rememberInfiniteTransition(label = "dots").animateFloat(0f, 1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val w = size.width; val step = w * .0293f; val radius = w * .43f
        val cx = w / 2; val cy = size.height * centerY
        val n = (radius / step).toInt() + 1
        val ph = phase.value * 2f * PI.toFloat()
        for (i in -n..n) for (j in -n..n) {
            val dx = i * step; val dy = j * step
            val d = sqrt(dx * dx + dy * dy) / radius
            if (d > 1f) continue
            val wave = .5f + .5f * sin(ph - d * 2.4f * PI.toFloat())
            val r = 1.6.dp.toPx() * (1f - .6f * d) * (.75f + .5f * wave)
            val a = (.15f + .85f * (1f - d).pow(.7f)) * (.55f + .45f * wave)
            drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), r, Offset(cx + dx, cy + dy))
        }
    }
}

@Composable
private fun DotsIcon() = Canvas(Modifier.size(24.dp, 16.dp)) {
    val gap = 3.6.dp.toPx(); val r = .9.dp.toPx()
    for (row in 0..3) {
        val cnt = row * 2 + 1
        for (k in 0 until cnt) drawCircle(Color.White.copy(alpha = .85f),  r,
            Offset(size.width / 2 + (k - (cnt - 1) / 2f) * gap, r + row * gap))
    }
}

/**
 * Full-screen migraine view that morphs to/from the Home card.
 * [progress] 0 = collapsed onto the Home card ([cardBounds]), 1 = full screen.
 * When [morph] is false (no card to land on, e.g. episode ended or details opened) it just fades and slides instead.
 */
@Suppress("UNUSED_PARAMETER")   // eps is kept so callers don't change; "Ends at" no longer predicts from history
@Composable
fun OngoingScreen(a: Episode, eps: List<Episode>, progress: Float, cardBounds: Rect, morph: Boolean,
                  onMinimize: () -> Unit, onDetails: () -> Unit, onEnd: () -> Unit) {
    val now by rememberNow()
    val elapsed = (now - a.startedAt).coerceAtLeast(0)
    val white = Color.White; val soft = Color.White.copy(alpha = .6f)
    val g = if (morph) progress else 1f
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
        if (!morph) { alpha = progress; translationY = (1f - progress) * size.height / 8f }
    }) {
        val w = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        // Full-screen target rect of the glow card.
        val fullH = with(density) { (maxHeight * .31f).coerceAtLeast(232.dp).toPx() }
        val fb = hPx - WindowInsets.navigationBars.getBottom(density) - with(density) { 8.dp.toPx() }
        val fl = with(density) { 16.dp.toPx() }
        val full = Rect(fl, fb - fullH, w - fl, fb)
        val from = if (morph && !cardBounds.isEmpty) cardBounds else full
        val l = lerp(from.left, full.left, g); val t = lerp(from.top, full.top, g)
        val r = lerp(from.right, full.right, g); val b = lerp(from.bottom, full.bottom, g)
        val cw = (r - l).roundToInt().coerceAtLeast(0); val ch = (b - t).roundToInt().coerceAtLeast(0)

        // Backdrop (black, dots, title) fades in/out so Home shows through while collapsing.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = g }.background(HomeBlack)) {
            DotField(Modifier.fillMaxSize(), .38f)
            Txt("Ongoing Migraine", Type.display.copy(fontSize = 26.sp, color = white),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 44.dp))
        }

        GlowCard(Modifier.offset { IntOffset(l.roundToInt(), t.roundToInt()) }
            .layout { m, _ -> val p = m.measure(Constraints.fixed(cw, ch)); layout(cw, ch) { p.place(0, 0) } },
            radius = lerp(44f, 52f, g).dp) {
            // Collapsed layer: same content as the Home card, laid out at the card's own size.
            val cardA = ((.45f - g) / .45f).coerceIn(0f, 1f)
            if (morph && cardA > 0f) Box(Modifier.align(Alignment.TopCenter)
                .requiredSize(with(density) { from.width.toDp() }, with(density) { from.height.toDp() })
                .graphicsLayer { alpha = cardA }) { OngoingCardBody(a, now, null, Modifier.fillMaxSize()) }
            // Expanded layer: laid out at the full size and clipped by the shrinking card.
            val fullA = ((g - .55f) / .45f).coerceIn(0f, 1f)
            if (fullA > 0f) Box(Modifier.align(Alignment.TopCenter)
                .requiredSize(with(density) { full.width.toDp() }, with(density) { full.height.toDp() })
                .graphicsLayer { alpha = fullA }) {
                Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Txt(clock(a.startedAt), Type.body.copy(fontSize = 16.sp, color = white))
                            Txt("Started at", Type.caption.copy(color = soft))
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            DotsIcon(); Spacer(Modifier.height(4.dp))
                            Txt("End now", Type.body.copy(fontSize = 16.sp, color = white))
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Txt(clock(now), Type.body.copy(fontSize = 16.sp, color = white))   // the time the tick button will save as the end
                            Txt("Ends at", Type.caption.copy(color = soft))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Txt(elapsedText(elapsed), Type.display.copy(fontSize = 40.sp, color = white))
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                        Press(onMinimize, Modifier.size(52.dp), "Minimize") {
                            Box(Modifier.fillMaxSize().background(white.copy(alpha = .10f), CircleShape), Alignment.Center) {
                                Box(Modifier.size(20.dp, 3.dp).background(white, RoundedCornerShape(2.dp)))
                            }
                        }
                        Press(onDetails, Modifier.width(150.dp).height(52.dp), "Add details") {
                            Box(Modifier.fillMaxSize().background(white.copy(alpha = .10f), RoundedCornerShape(50)), Alignment.Center) {
                                Txt("Add details", Type.body.copy(fontSize = 16.sp, color = white))
                            }
                        }
                        Press(onEnd, Modifier.size(52.dp), "End migraine") { TickCircle(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

/** Minimised migraine: the glow card that sits at the bottom of Home. Tap to open the full screen. */
@Composable
fun OngoingCard(a: Episode, onOpen: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val now by rememberNow()
    Press(onOpen, modifier.fillMaxWidth(), "Ongoing migraine, open") {
        GlowCard(Modifier.fillMaxWidth(), radius = 44.dp) { OngoingCardBody(a, now, onEnd, Modifier.fillMaxWidth()) }
    }
}

/** Contents of the minimised card; shared by [OngoingCard] and the morphing [OngoingScreen]. */
@Composable
private fun OngoingCardBody(a: Episode, now: Long, onEnd: (() -> Unit)?, modifier: Modifier) {
    val white = Color.White; val soft = Color.White.copy(alpha = .6f)
    Row(modifier.padding(horizontal = 28.dp, vertical = 22.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column {
            Txt("ONGOING MIGRAINE", Type.label.copy(color = soft, letterSpacing = 1.2.sp))
            Txt(elapsedText(now - a.startedAt), Type.display.copy(fontSize = 38.sp, color = white), modifier = Modifier.padding(vertical = 2.dp))
            Txt("Started at ${clock(a.startedAt)}", Type.caption.copy(color = soft))
        }
        if (onEnd != null) Press(onEnd, Modifier.size(52.dp), "End migraine") { TickCircle(Modifier.fillMaxSize()) }
        else TickCircle(Modifier.size(52.dp))
    }
}

/** Translucent circle with a white tick; the "end migraine" button on both the full screen and the Home card. */
@Composable
private fun TickCircle(modifier: Modifier) {
    Box(modifier.background(Color.White.copy(alpha = .10f), CircleShape), Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val p = Path().apply {
                moveTo(size.width * .1f, size.height * .55f)
                lineTo(size.width * .38f, size.height * .82f)
                lineTo(size.width * .92f, size.height * .2f)
            }
            drawPath(p, Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
