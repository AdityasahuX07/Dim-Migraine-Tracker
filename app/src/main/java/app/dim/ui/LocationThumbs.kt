package app.dim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dim.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Round, zoomed thumbnails for the "Where is the pain located?" option tiles.
 *
 * Each thumbnail is a square crop of the same gray head render the big diagram uses, zoomed in on that location, with
 * the region's traced outline washed in the accent colour exactly like the big diagram (same gradients, same soft cut at
 * the centre line for midline areas), clipped to a circle with a thin ring.
 *
 * Front areas use res/drawable-nodpi/pain_head_base.webp + HeadPaths. "Back of head" and "Upper Back" are not visible
 * from the front, so they use a matching back-view render (res/drawable-nodpi/pain_back_base.webp, same size, same
 * clay-gray look and groove lines) + BackPaths. Nothing is changed in PainHead.kt, HeadPaths.kt or the front image:
 * this file only reads them.
 */

/** Both renders, decoded once per picker and shared by every thumbnail. */
internal class ThumbArt(val front: ImageBitmap, val back: ImageBitmap)

@Composable
internal fun rememberThumbArt(): ThumbArt {
    val front = ImageBitmap.imageResource(R.drawable.pain_head_base)
    val back = ImageBitmap.imageResource(R.drawable.pain_back_base)
    return remember(front, back) { ThumbArt(front, back) }
}

/** Square crop in asset pixels, defined for the person's Right side (image-left); mirrored across the axis for Left. */
private class Crop(val cx: Float, val cy: Float, val size: Float, val back: Boolean = false)

private val CROPS: Map<String, Crop> = mapOf(
    "Forehead" to Crop(366f, 270f, 500f),
    "Temple" to Crop(110f, 320f, 300f),
    "Top of head" to Crop(366f, 170f, 480f),
    "Back of head" to Crop(366f, 367f, 734f, back = true),   // whole head from behind so its outline reads
    "Upper Area" to Crop(216f, 430f, 290f),
    "Lower Area" to Crop(216f, 500f, 290f),
    "Behind" to Crop(216f, 462f, 290f),
    "Nose" to Crop(366f, 560f, 330f),
    "Ear" to Crop(60f, 553f, 300f),
    "Cheek / Cheekbone" to Crop(180f, 590f, 380f),
    "Jaw" to Crop(260f, 790f, 470f),
    "Teeth" to Crop(367f, 760f, 380f),
    "Neck" to Crop(366f, 1040f, 470f),
    "Shoulder" to Crop(150f, 1060f, 400f),
    "Upper Back" to Crop(366f, 1010f, 520f, back = true),
)

private class Region(val path: Path) { val bounds: Rect = path.getBounds() }

// Parsed once, like the main head diagram.
private val REGIONS: Map<String, Region> =
    (HeadPaths.d + BackPaths.d).mapValues { (_, d) -> Region(PathParser().parsePathString(d).toPath()) }

/** Same grouping as the big diagram: midline areas are only lit on the selected side, "Behind" gets a soft glow. */
private val HALF_KEYS = setOf("scalp", "forehead", "nose", "mouth", "chin", "neck", "occiput", "upperback")
private val RADIAL_KEYS = setOf("eye_a", "eye_b")

/** Card name -> region keys; same mapping as the big head diagram. `v` is the image side: Right -> "a", Left -> "b". */
private fun thumbSegments(name: String, v: String): List<String> = when (name) {
    "Forehead" -> listOf("forehead")
    "Temple" -> listOf("temple_$v")
    "Top of head" -> listOf("scalp")
    "Back of head" -> listOf("occiput")
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
    "Upper Back" -> listOf("upperback")
    else -> emptyList()
}

// ---- Wash: identical recipe to PainHead.kt (copied because those helpers are private there) ----

private fun feather(key: String): Float = when (key) {
    "forehead", "scalp", "occiput" -> 90f
    "neck", "upperback" -> 85f
    "mouth" -> 50f
    else -> 40f
}

private fun halfFade(c: Color, litRight: Boolean, feather: Float): Brush {
    val n = 10
    val stops = Array(n + 1) { i ->
        val t = i / n.toFloat()
        val e = t * t * (3f - 2f * t)
        t to c.copy(alpha = c.alpha * (if (litRight) e else 1f - e))
    }
    return Brush.horizontalGradient(colorStops = stops, startX = HeadPaths.AXIS - feather, endX = HeadPaths.AXIS + feather)
}

private fun wash(key: String, r: Region, accent: Color, litRight: Boolean): Brush {
    val hi = lerp(accent, Color.White, .35f)
    return when {
        key in HALF_KEYS -> halfFade(lerp(accent, Color.White, .15f).copy(alpha = .6f), litRight, feather(key))
        key in RADIAL_KEYS -> Brush.radialGradient(listOf(accent.copy(alpha = .85f), accent.copy(alpha = .45f)),
            center = r.bounds.center, radius = max(r.bounds.width, r.bounds.height) * .6f)
        else -> Brush.verticalGradient(listOf(hi.copy(alpha = .66f), accent.copy(alpha = .56f)),
            startY = r.bounds.top, endY = r.bounds.bottom)
    }
}

/**
 * @param side "a" for the person's Right, "b" for Left (picks which side of the face is shown and lit).
 * @param selected rings the thumbnail in the accent colour.
 * Give it a width; it stays square.
 */
@Composable
internal fun LocationThumb(name: String, side: String, selected: Boolean, art: ThumbArt, modifier: Modifier = Modifier) {
    val accent = Dim.accent
    val ring = if (selected) accent else Dim.line
    val bg = Dim.fill
    // Front view: the person's Left is the image's right half. Seen from behind it flips: the person's Right is image-right.
    val back = CROPS[name]?.back == true
    val litRight = if (back) side == "a" else side == "b"
    Canvas(modifier.aspectRatio(1f)) {
        val d = size.minDimension
        val circle = Path().apply { addOval(Rect(0f, 0f, d, d)) }
        clipPath(circle) {
            drawRect(bg)
            val crop = CROPS[name]
            if (crop != null) {
                val s = crop.size
                val cx = if (side == "b" && !crop.back) 2f * HeadPaths.AXIS - crop.cx else crop.cx   // back crops are on the centre line
                val sx = (cx - s / 2f).coerceIn(0f, HeadPaths.W - s)
                val sy = (crop.cy - s / 2f).coerceIn(0f, HeadPaths.H - s)
                drawImage(
                    if (crop.back) art.back else art.front,
                    srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
                    srcSize = IntSize(s.roundToInt(), s.roundToInt()),
                    dstSize = IntSize(d.roundToInt(), d.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
                // Region outlines are in asset pixels: shift to the crop's corner, then scale the crop up to the circle.
                val k = d / s
                translate(-sx * k, -sy * k) {
                    scale(k, k, pivot = Offset.Zero) {
                        thumbSegments(name, side).forEach { key ->
                            REGIONS[key]?.let { drawPath(it.path, wash(key, it, accent, litRight)) }
                        }
                    }
                }
            }
        }
        drawCircle(ring, d / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
    }
}
