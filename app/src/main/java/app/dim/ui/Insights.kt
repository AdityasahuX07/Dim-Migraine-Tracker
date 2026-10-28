package app.dim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import app.dim.R
import app.dim.data.Episode
import java.time.*
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private val zone get() = ZoneId.systemDefault()
private fun Episode.day() = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
private fun String.items() = split(",").filter { it.isNotBlank() }

/** Episodes per week for the last [weeks] weeks, oldest first. */
fun weeklyCounts(eps: List<Episode>, weeks: Int = 12, today: LocalDate = LocalDate.now()): List<Int> {
    val start = today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(weeks - 1L) // Monday-based
    val out = IntArray(weeks)
    eps.forEach { val i = (java.time.temporal.ChronoUnit.DAYS.between(start, it.day()) / 7).toInt(); if (i in 0 until weeks) out[i]++ }
    return out.toList()
}

private fun top(eps: List<Episode>, pick: (Episode) -> String) =
    eps.flatMap { pick(it).items() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)

private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"
private fun dur(mins: Long?) = when { mins == null -> "–"; mins >= 60 -> "${mins / 60}h ${mins % 60}m"; else -> "${mins}m" }

// Disc tints come from [Tint] (Details.kt): one colour per feature, shared with the episode detail page.
private val Purple = Tint.PainLocation   // Avg pain: unchanged

// ───────────────────────── building blocks ─────────────────────────

@Composable
internal fun Section(title: String, sub: String? = null) =
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(title, Type.headline)
        if (sub != null) Txt(sub, Type.caption, sub = true, modifier = Modifier.padding(bottom = 1.dp))
    }

/** Round logo, small label, value; optional thin fill bar underneath. Same look as Migraine Details rows. */
@Composable
internal fun InsightRow(tint: Color, icon: (DrawScope.(Color) -> Unit)?, label: String, value: String, frac: Float? = null, @DrawableRes res: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(48.dp).background(tint, CircleShape), Alignment.Center) {
            if (res != null) LogoGlyph(res) else if (icon != null) Canvas(Modifier.size(24.dp)) { icon(this, Color.White) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(label, Type.caption, sub = true)
            Txt(value, Type.body, maxLines = 1)
            if (frac != null) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).background(Dim.fill, CircleShape)) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(Dim.accent, CircleShape))
            }
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) = Column(Modifier.weight(1f)) {
    Txt(label, Type.label, sub = true)
    Txt(value, Type.display.copy(fontSize = 44.sp))
}

/** Thirty ticks, oldest to today; days with an episode are tall and accent-coloured. */
@Composable
private fun DayRuler(hit: List<Boolean>) {
    val on = Dim.accent; val off = Dim.sub.copy(alpha = .4f)
    Canvas(Modifier.fillMaxWidth().height(34.dp).clearAndSetSemantics { contentDescription = "${hit.count { it }} days with episodes in the last 30 days" }) {
        val step = size.width / hit.size.coerceAtLeast(1)
        val base = size.height; val sw = 1.5.dp.toPx()
        hit.forEachIndexed { i, h ->
            val x = step * (i + .5f)
            drawLine(if (h) on else off, Offset(x, base), Offset(x, base - size.height * (if (h) .72f else .4f)), sw, StrokeCap.Round)
        }
    }
}

/** Smooth curve through [pts] (Catmull-Rom converted to cubic Béziers). */
private fun smoothPath(pts: List<Offset>, minY: Float = Float.NEGATIVE_INFINITY, maxY: Float = Float.POSITIVE_INFINITY): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, pts.size - 1)]
        cubicTo(p1.x + (p2.x - p0.x) / 6f, (p1.y + (p2.y - p0.y) / 6f).coerceIn(minY, maxY),
            p2.x - (p3.x - p1.x) / 6f, (p2.y - (p3.y - p1.y) / 6f).coerceIn(minY, maxY), p2.x, p2.y)
    }
}

/** Episodes per day for one week, with a day picker and week navigation. */
@Composable
private fun WeekChart(eps: List<Episode>, today: LocalDate) {
    var offset by remember { mutableStateOf(0) }            // 0 = this week, -1 = last week …
    var sel by remember { mutableStateOf(today.dayOfWeek.value - 1) } // Monday = 0
    val start = today.minusDays(today.dayOfWeek.value - 1L).plusWeeks(offset.toLong())
    val days = (0..6).map { start.plusDays(it.toLong()) }
    val counts = days.map { d -> eps.count { it.day() == d } }
    val yMax = maxOf(4, ((counts.maxOrNull() ?: 0) + 1) / 2 * 2)
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val inset = 44.dp; val chartH = 150.dp
    val accent = Dim.accent; val grid = Dim.line; val sub = Dim.sub

    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt("EPISODES PER DAY", Type.label, sub = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(chartH)) {
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = "Episodes per day, ${days.first().format(fmt)} to ${days.last().format(fmt)}: ${counts.joinToString()}" }) {
                val top = 16.dp.toPx(); val bottom = size.height - 8.dp.toPx()
                val gl = 30.dp.toPx(); val gr = size.width - 14.dp.toPx()
                fun y(v: Float) = bottom - (bottom - top) * (v / yMax)
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                for (g in listOf(0f, yMax / 2f, yMax.toFloat())) drawLine(grid, Offset(gl, y(g)), Offset(gr, y(g)), 1.dp.toPx(), pathEffect = dash)
                val step = (size.width - 2 * inset.toPx()) / 6f
                val pts = counts.mapIndexed { i, c -> Offset(inset.toPx() + step * i, y(c.toFloat())) }
                val full = listOf(Offset(gl, pts.first().y)) + pts + Offset(gr, pts.last().y)
                drawPath(smoothPath(full, top, bottom), accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val p = pts[sel]
                drawLine(sub.copy(alpha = .5f), p, Offset(p.x, size.height), 1.dp.toPx(), pathEffect = dash)
                drawCircle(accent.copy(alpha = .22f), 14.dp.toPx(), p)
                drawCircle(accent, 4.dp.toPx(), p)
            }
            fun yDp(v: Float) = 16.dp + (chartH - 24.dp) * (1f - v / yMax)
            listOf(0f, yMax / 2f, yMax.toFloat()).forEach { g ->
                Box(Modifier.offset(x = 10.dp, y = yDp(g) - 10.dp).size(18.dp, 20.dp).clearAndSetSemantics {}, Alignment.Center) {
                    Txt("${g.toInt()}", Type.caption.copy(fontSize = 13.sp), sub = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = inset - 16.dp), Arrangement.SpaceBetween) {
            days.forEachIndexed { i, d ->
                val on = i == sel
                Press({ sel = i }, Modifier.size(32.dp)
                    .then(if (on) Modifier.background(Dim.accent, CircleShape) else Modifier.border(1.dp, Dim.line, CircleShape)),
                    "${d.dayOfWeek}, ${counts[i]} ${plural(counts[i], "episode")}" + if (on) ", selected" else "") {
                    Txt(d.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()),
                        Type.caption.copy(color = if (on) Dim.onAccent else Dim.sub, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Press({ offset-- }, Modifier.size(32.dp), "Previous week") { Txt("‹", Type.headline.copy(color = Dim.accent, fontSize = 24.sp), modifier = Modifier.align(Alignment.Center)) }
            Txt("${start.format(fmt)} - ${start.plusDays(6).format(fmt)}", Type.body.copy(fontSize = 16.sp))
            Press({ if (offset < 0) offset++ }, Modifier.size(32.dp).alpha(if (offset < 0) 1f else .35f), "Next week") {
                Txt("›", Type.headline.copy(color = Dim.accent, fontSize = 24.sp), modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

/** Bars: episodes on each of the last 7 days. */
@Composable
private fun LastWeekBars(days: List<LocalDate>, per: List<Int>, today: LocalDate) {
    val mx = (per.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(130.dp), Arrangement.SpaceBetween) {
        days.forEachIndexed { i, d ->
            val n = per[i]; val isToday = d == today
            Column(Modifier.fillMaxHeight().semantics(mergeDescendants = true) {
                contentDescription = "${d.dayOfWeek}" + (if (isToday) ", today" else "") + ", $n ${plural(n, "episode")}" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints(Modifier.weight(1f).width(28.dp), Alignment.BottomCenter) {
                    val h = if (n == 0) 4.dp else maxOf(10.dp, maxHeight * (n / mx.toFloat()))
                    Box(Modifier.fillMaxWidth().height(h).background(if (n > 0) Dim.accent else Dim.fill, RoundedCornerShape(50)))
                }
                Txt(d.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()),
                    Type.caption.copy(color = if (isToday) Dim.text else Dim.sub, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
}

/** 7 × 20 grid, one cell per day. Top-left is today, down = earlier days, right = earlier weeks (each column is a week). */
@Composable
private fun HeatGrid(hit: Set<LocalDate>, today: LocalDate) {
    val cols = 20
    val total = (0 until cols * 7).count { today.minusDays(it.toLong()) in hit }
    val on = Dim.accent; val off = Dim.fill
    Canvas(Modifier.fillMaxWidth().aspectRatio(24.75f / 8.5f).clearAndSetSemantics {
        contentDescription = "Heat map, last ${cols * 7} days: $total days with episodes" }) {
        val c = size.width / 24.75f; val g = c * .25f
        for (col in 0 until cols) for (row in 0 until 7) {
            val h = today.minusDays((col * 7 + row).toLong()) in hit
            drawRoundRect(if (h) on else off, Offset(col * (c + g), row * (c + g)), Size(c, c), CornerRadius(c * .28f))
        }
    }
}

/** Horizontal bars: episodes per weekday, all time. */
@Composable
private fun WeekdayBars(eps: List<Episode>) {
    val counts = IntArray(7); eps.forEach { counts[it.day().dayOfWeek.value - 1]++ }
    val mx = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        counts.forEachIndexed { i, n ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Txt(DayOfWeek.of(i + 1).getDisplayName(DayStyle.SHORT, Locale.getDefault()), Type.caption, sub = true, modifier = Modifier.width(44.dp))
                Box(Modifier.weight(1f).height(8.dp).background(Dim.fill, CircleShape)) {
                    Box(Modifier.fillMaxWidth(n / mx.toFloat()).fillMaxHeight().background(Dim.accent.copy(alpha = if (n == mx) 1f else .55f), CircleShape))
                }
                Txt("$n", Type.caption, modifier = Modifier.width(34.dp).padding(start = 12.dp))
            }
        }
    }
}

/** Ranked list in the same look as the detail page lists: round logo, name, count and a thin bar. */
@Composable
private fun RankList(rows: List<Map.Entry<String, Int>>) {
    val mx = (rows.maxOfOrNull { it.value } ?: 1).coerceAtLeast(1)
    Card(pad = 0.dp) {
        rows.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            val logo = ITEM_LOGOS[r.key]
            Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(48.dp).background(logo?.tint ?: Dim.accent2, CircleShape), Alignment.Center) {
                    if (logo != null) LogoGlyph(logo.res)
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Txt(r.key, Type.body, modifier = Modifier.weight(1f))
                        Txt("${r.value}×", Type.caption, sub = true)
                    }
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(Dim.fill, CircleShape)) {
                        Box(Modifier.fillMaxWidth(r.value / mx.toFloat()).fillMaxHeight().background(Dim.accent, CircleShape))
                    }
                }
            }
        }
    }
}

// ───────────────────────── screen ─────────────────────────

@Composable
fun Insights(eps: List<Episode>) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()
    val last30 = eps.filter { it.day() >= today.minusDays(29) }
    val pains = last30.mapNotNull { it.pain }
    val durs = last30.mapNotNull { e -> e.endedAt?.let { (it - e.startedAt) / 60000 } }
    val monthCount = eps.count { e -> e.day().let { it.year == today.year && it.month == today.month } }
    val done = eps.filter { it.endedAt != null }
    val avg = if (done.isEmpty()) null else done.map { (it.endedAt!! - it.startedAt) / 60000 }.average().toLong()
    val avgPain = if (pains.isEmpty()) null else pains.average()
    val stresses = last30.mapNotNull { it.stress }
    val avgStress = if (stresses.isEmpty()) null else stresses.average()
    val hydros = last30.mapNotNull { it.hydration }
    val avgHydro = if (hydros.isEmpty()) null else hydros.average()   // 0..2 bucket index
    val avgLen = if (durs.isEmpty()) null else durs.average().toLong()
    val hitDays = eps.map { it.day() }.toSet()
    val ruler = (29 downTo 0).map { today.minusDays(it.toLong()) in hitDays }
    val tr = top(eps, { it.triggers }); val sy = top(eps, { it.symptoms })   // triggers and symptoms: all time
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val weekPer = week.map { d -> eps.count { it.day() == d } }

    Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt("Insights", Type.title, modifier = Modifier.padding(horizontal = 6.dp))

        HeroCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("$monthCount", Type.display.copy(fontSize = 88.sp))
                    Txt("${plural(monthCount, "episode")} in ${today.month.getDisplayName(DayStyle.FULL, Locale.getDefault())}",
                        Type.headline.copy(fontSize = 18.sp), sub = true)
                }
                InnerCard(pad = 0.dp) { WeekChart(eps, today) }
                InnerCard {
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Stat("ALL TIME", "${eps.size}")
                        Stat("LAST 30 DAYS", "${last30.size}")
                    }
                    Spacer(Modifier.height(16.dp))
                    DayRuler(ruler)
                }
            }
        }

        Section("Last 30 days")
        Card(pad = 0.dp) {
            InsightRow(Purple, null, "Avg pain", avgPain?.let { "%.1f / 10".format(it) } ?: "–", avgPain?.let { (it / 10).toFloat() }, R.drawable.ic_insight_head_brain)
            RowDivider()
            InsightRow(Tint.Stress, null, "Avg stress", avgStress?.let { "%.1f / 10".format(it) } ?: "–", avgStress?.let { (it / 10).toFloat() }, R.drawable.ic_insight_stress)
            RowDivider()
            InsightRow(Tint.Hydration, { glyphDrop(it) }, "Avg hydration (est.)", avgHydro?.let { "%.1f L".format(0.25 + 0.5 * it) } ?: "–", avgHydro?.let { (it / 2).toFloat() })
            RowDivider()
            InsightRow(Tint.DurationRecent, null, "Avg Duration",dur(avgLen), res = R.drawable.ic_insight_avg_duration)
            RowDivider()
            InsightRow(Tint.DurationAllTime, null, "Avg Duration · all time", dur(avg), res = R.drawable.ic_insight_all_time)
        }

        HealthInsightsSection(eps)   // only when Health Connect is connected or has data

        Section("Heat map", "last 140 days")
        // Equal 16 dp padding on all four sides (tighter than the default 20); the grid fills the card's width exactly.
        Card(pad = 16.dp) { HeatGrid(hitDays, today) }

        Section("Last 7 days", "${weekPer.count { it > 0 }} with episodes")
        Card { LastWeekBars(week, weekPer, today) }

        Section("Calendar")
        Card {
            // Arrows at the edges, "Month Year" centred between them; every item is centred on the row's vertical middle.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NavButton(false, "Previous month") { month = month.minusMonths(1) }
                Row(Modifier.weight(1f), Arrangement.Center, Alignment.CenterVertically) {
                    Txt(month.month.getDisplayName(DayStyle.FULL, Locale.getDefault()), Type.title.copy(fontSize = 28.sp), maxLines = 1)
                    Txt(" ${month.year}", Type.title.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal), sub = true, maxLines = 1)
                }
                NavButton(true, "Next month") { month = month.plusMonths(1) }
            }
            Spacer(Modifier.height(18.dp)); Calendar(month, eps)
        }

        Section("Attacks by weekday", "all time")
        Card { WeekdayBars(eps) }

        if (sy.isNotEmpty()) { Section("Most logged symptoms", "all time"); RankList(sy) }
        if (tr.isNotEmpty()) { Section("Most logged triggers", "all time"); RankList(tr) }
        Spacer(Modifier.navigationBarsPadding().height(NavBarClearance))
    }
}

@Composable
private fun NavButton(next: Boolean, label: String, onClick: () -> Unit) =
    Press(onClick, Modifier.size(32.dp), label) {
        // Drawn chevron (a text glyph sits off-centre vertically), centred in the 32 dp target.
        val c = Dim.accent
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            val tip = if (next) w * .62f else w * .38f; val back = if (next) w * .42f else w * .58f
            drawPath(Path().apply { moveTo(back, h * .27f); lineTo(tip, h * .5f); lineTo(back, h * .73f) }, c,
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

@Composable
fun Calendar(month: YearMonth, eps: List<Episode>) {
    val byDay = eps.groupBy { it.day() }
    val today = LocalDate.now()
    val lead = month.atDay(1).dayOfWeek.value - 1 // Monday first
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Row(Modifier.fillMaxWidth()) { for (d in 1..7) Box(Modifier.weight(1f), Alignment.Center) {
        Txt(DayOfWeek.of(d).getDisplayName(DayStyle.SHORT, Locale.getDefault()), Type.label, sub = true) } }
    Spacer(Modifier.height(12.dp))
    cells.chunked(7).forEach { wk ->
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Dim.line))
        Row(Modifier.fillMaxWidth().height(50.dp)) {
            for (i in 0 until 7) Box(Modifier.weight(1f).fillMaxHeight(), Alignment.Center) {
                val d = wk.getOrNull(i) ?: return@Box
                val es = byDay[d]
                val isToday = d == today
                Box(Modifier.size(42.dp).then(if (isToday) Modifier.border(1.5.dp, Dim.text.copy(alpha = .7f), CircleShape) else Modifier)
                    .semantics { contentDescription = "${d.dayOfMonth}" + (if (isToday) ", today" else "") +
                        (if (es != null) ", ${es.size} episode(s)" else "") },
                    Alignment.Center) {
                    Box(Modifier.size(35.dp).then(if (es != null) Modifier.background(Dim.accent, CircleShape) else Modifier), Alignment.Center) {
                        Txt("${d.dayOfMonth}", Type.body.copy(fontSize = 18.sp, color = if (es != null) Dim.onAccent else Dim.text,
                            fontWeight = if (es != null) FontWeight.SemiBold else FontWeight.Normal))
                    }
                }
            }
        }
    }
}
