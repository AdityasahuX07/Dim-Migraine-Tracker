package app.dim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** History tab before the first episode is logged: a quiet clock icon, a title and one line of text. The caller centres it. */
@Composable
fun HistoryEmpty() {
    val glyph = Dim.sub
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(72.dp).background(Dim.fill, CircleShape).clearAndSetSemantics {}, Alignment.Center) {
            Canvas(Modifier.size(32.dp)) {
                val w = size.width; val h = size.height; val sw = 2.dp.toPx()
                drawCircle(glyph, w / 2 - sw, style = Stroke(sw, cap = StrokeCap.Round))
                drawLine(glyph, Offset(w / 2, h / 2), Offset(w / 2, h * .28f), sw, StrokeCap.Round)
                drawLine(glyph, Offset(w / 2, h / 2), Offset(w * .68f, h * .6f), sw, StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt("No episodes yet", Type.headline.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
        Txt("Logged migraines will appear here.", Type.body.copy(fontSize = 15.sp, textAlign = TextAlign.Center), sub = true,
            modifier = Modifier.fillMaxWidth())
    }
}
