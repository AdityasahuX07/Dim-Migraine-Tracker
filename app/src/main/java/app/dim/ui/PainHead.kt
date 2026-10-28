package app.dim.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dim.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Front-facing head diagram for "Where is the pain located?".
 * A transparent gray base render (res/drawable-nodpi/pain_head_base.webp) with one traced vector region per
 * anatomical segment drawn on top. Highlights follow the same comma-separated `Episode.location` string the
 * selection cards edit, so they update the moment a card is toggled. Read-only: no selection logic lives here.
 */

/** One highlightable segment. [half] regions straddle the midline and are only lit on the selected side. */
private class Seg(val key: String, val path: Path, val half: Boolean, val radial: Boolean) {
    val bounds: Rect = path.getBounds()
}

private val HALF_KEYS = setOf("scalp", "forehead", "nose", "mouth", "chin", "neck")
private val RADIAL_KEYS = setOf("eye_a", "eye_b")   // "Behind" the eye: soft glow instead of a flat fill

// Parsed once at class load (no lazy work while the page is on screen).
private val SEGS: List<Seg> = HeadPaths.d.map { (k, d) ->
    Seg(k, PathParser().parsePathString(d).toPath(), k in HALF_KEYS, k in RADIAL_KEYS)
}

/** Locations that live behind the head from this camera angle, so there is nothing to light up. */
private val HIDDEN = listOf("Back of head", "Upper Back")

/**
 * Card name -> segment keys. `v` is the image side that matches the chosen body side: the person's Left is the
 * right half of a front-facing picture, so Left -> "b" and Right -> "a".
 */
private fun segmentsFor(name: String, v: String): List<String> = when (name) {
    "Forehead" -> listOf("forehead")
    "Temple" -> listOf("temple_$v")
    "Top of head" -> listOf("scalp")
    "Upper Area" -> listOf("eyeup_$v")
    "Lower Area" -> listOf("eyelo_$v")
    "Behind" -> listOf("eye_$v")
    "Nose" -> listOf("nose")
    "Ear" -> listOf("ear_$v")
    "Cheek / Cheekbone" -> listOf("cheek_$v")
    "Jaw" -> listOf("jaw_$v", "chin")
    "Teeth" -> listOf("mouth")
    "Neck" -> listOf("neck")
    "Shoulder" -> listOf("shoulder_$v")
    else -> emptyList()
}

@Composable
fun PainHeadGraphic(location: String, modifier: Modifier = Modifier) {
    val base: ImageBitmap = ImageBitmap.imageResource(R.drawable.pain_head_base)
    val sel = location.split(",").filter { it.isNotBlank() }.toSet()
    val side = when { "Left" in sel -> "b"; "Right" in sel -> "a"; else -> null }
    val active: Set<String> = if (side == null) emptySet() else sel.flatMap { segmentsFor(it, side) }.toSet()
    val hidden = if (side == null) emptyList() else HIDDEN.filter { it in sel }

    // Which half of a midline segment is lit; remembered so a fade-out keeps its direction.
    var litRight by remember { mutableStateOf(true) }
    if (side != null) litRight = side == "b"

    val calm = Dim.calm
    val spec: AnimationSpec<Float> = if (calm) snap() else tween(160)   // no motion in Migraine Mode
    val alphas = SEGS.map { s -> animateFloatAsState(if (s.key in active) 1f else 0f, spec, label = "hl_" + s.key).value }

    val accent = Dim.accent
    val desc = if (active.isEmpty() && hidden.isEmpty()) "Head diagram, no area highlighted"
               else "Head diagram, highlighted: " + (if (side == "b") "left side, " else "right side, ") + sel.filter { it != "Left" && it != "Right" }.joinToString(", ")

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth(0.62f).aspectRatio(HeadPaths.W / HeadPaths.H).semantics { contentDescription = desc }) {
            drawImage(base, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High)
            scale(size.width / HeadPaths.W, size.height / HeadPaths.H, pivot = Offset.Zero) {
                SEGS.forEachIndexed { i, s ->
                    val a = alphas[i]
                    if (a > 0.01f) drawPath(s.path, fill(s, accent, litRight), alpha = a)
                }
            }
        }
        if (hidden.isNotEmpty()) Txt("Not visible from the front: " + hidden.joinToString(", "),
            Type.caption.copy(textAlign = TextAlign.Center), sub = true, modifier = Modifier.fillMaxWidth())
    }
}

/** Half-width (asset px) of the soft edge where each midline segment is cut at the head's centre line. */
private fun feather(key: String): Float = when (key) {
    "forehead", "scalp" -> 90f
    "neck" -> 85f
    "mouth" -> 50f
    "nose", "chin" -> 40f
    else -> 40f
}

/** Horizontal fade across the centre line, eased (smoothstep) so there is no visible band or seam. */
private fun halfFade(c: Color, litRight: Boolean, feather: Float): Brush {
    val n = 10
    val stops = Array(n + 1) { i ->
        val t = i / n.toFloat()
        val e = t * t * (3f - 2f * t)
        t to c.copy(alpha = c.alpha * (if (litRight) e else 1f - e))
    }
    return Brush.horizontalGradient(colorStops = stops, startX = HeadPaths.AXIS - feather, endX = HeadPaths.AXIS + feather)
}

/** Translucent accent wash, lighter toward the top like the reference's studio-lit glow. */
private fun fill(s: Seg, accent: Color, litRight: Boolean): Brush {
    val hi = lerp(accent, Color.White, .35f)
    return when {
        s.half -> halfFade(lerp(accent, Color.White, .15f).copy(alpha = .6f), litRight, feather(s.key))
        s.radial -> Brush.radialGradient(listOf(accent.copy(alpha = .85f), accent.copy(alpha = .45f)),
            center = s.bounds.center, radius = max(s.bounds.width, s.bounds.height) * .6f)
        else -> Brush.verticalGradient(listOf(hi.copy(alpha = .66f), accent.copy(alpha = .56f)),
            startY = s.bounds.top, endY = s.bounds.bottom)
    }
}
