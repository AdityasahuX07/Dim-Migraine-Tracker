package app.dim.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dim.DimApp
import app.dim.R
import app.dim.data.Episode
import app.dim.data.health.HealthMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val SleepTint = Color(0xFF7A67B8)
private val DeepSleepTint = Color(0xFF5A4D8C)
private val RemSleepTint = Color(0xFF8F7BCC)
private val HeartTint = Color(0xFFA8697A)
private val RestingHeartTint = Color(0xFFB85D6B)
private val HrvTint = Tint.Hrv
private val StepsTint = Tint.Steps
// Sleep and heart-rate tints above are unchanged.

private fun nights(n: Int) = if (n == 1) "1 night" else "$n nights"
private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

/**
 * "Sleep & health metrics" block on Insights, from the copy of Health Connect data kept on this device.
 * Displays metrics across all synced data, divided into Sleep, Heart Rate, and Steps sub-sections.
 */
@Composable
internal fun HealthInsightsSection(eps: List<Episode>) {
    val app = LocalContext.current.applicationContext as DimApp
    val today = LocalDate.now()
    val rows by remember { app.health.allDays() }.collectAsState(emptyList())
    val ui by app.health.state.collectAsState()
    val summary = remember(rows, today) { HealthMath.summarize(rows, today) }
    if (!ui.enabled && !summary.hasData) return

    Section("Sleep & health metrics", "all synced data")
    if (!summary.hasData) {
        Card { Txt("No health data from Health Connect yet. It appears here after the next sync.", Type.body, sub = true) }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Sleep sub-section
        if (summary.hasSleep) {
            SubSectionCard(
                title = "Sleep",
                subtitle = nights(summary.sleepNights),
                tint = SleepTint,
                logoRes = R.drawable.ic_health_sleep_bed,
            ) {
                var first = true
                fun gap(): Boolean { val f = first; first = false; return !f }
                summary.sleepAvgMin?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Sleep duration", hm(it))
                }
                summary.sleepDeepAvgMin?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Deep sleep", hm(it))
                }
                summary.sleepRemAvgMin?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("REM sleep", hm(it))
                }
                summary.sleepLightAvgMin?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Light sleep", hm(it))
                }
                summary.sleepAwakeAvgMin?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Awake time", hm(it))
                }
            }
        }

        // 2. Heart Rate sub-section
        if (summary.hasHeart) {
            val hrDaysCount = summary.hrDays.takeIf { it > 0 } ?: summary.restingHrDays.takeIf { it > 0 } ?: summary.hrvDays
            SubSectionCard(
                title = "Heart Rate",
                subtitle = if (hrDaysCount > 0) days(hrDaysCount) else null,
                tint = HeartTint,
                logoRes = R.drawable.ic_health_heart_pulse,
            ) {
                var first = true
                fun gap(): Boolean { val f = first; first = false; return !f }
                summary.hrAvg?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Heart rate", "$it bpm")
                }
                summary.restingHrAvg?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Resting heart rate", "$it bpm")
                }
                summary.hrvAvg?.let {
                    if (gap()) MetricDivider()
                    MetricInfoRow("Heart rate variability (HRV)", "${Math.round(it)} ms")
                }
            }
        }

        // 3. Steps sub-section (in last order)
        if (summary.hasSteps) {
            SubSectionCard(
                title = "Steps",
                subtitle = days(summary.stepDays),
                tint = StepsTint,
                logoRes = R.drawable.ic_health_steps_shoe,
            ) {
                summary.stepsAvg?.let {
                    MetricInfoRow("Daily steps", "%,d / day".format(it))
                }
            }
        }
    }
    Txt("All values represent daily averages calculated across your synced Health Connect history.",
        Type.caption, sub = true, modifier = Modifier.padding(horizontal = 6.dp))

    val zone = ZoneId.systemDefault()
    val attackDays = remember(eps) { eps.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.toSet() }
    val sleepC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = false) { it.sleepMinutes } }
    val deepSleepC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = false) { it.sleepDeepMinutes } }
    val remSleepC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = false) { it.sleepRemMinutes } }
    val hrC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = true) { it.hrAvg } }
    val restingHrC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = true) { it.restingHr } }
    val hrvC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = true) { it.hrvRmssd } }
    val stepsC = remember(rows, attackDays, today) { HealthMath.compare(rows, attackDays, today, excludeToday = true) { it.steps } }

    if (sleepC != null || deepSleepC != null || remSleepC != null || hrC != null || restingHrC != null || hrvC != null || stepsC != null) {
        Section("Attack days vs other days", "last 90 days")
        Card(pad = 0.dp) {
            var first = true
            fun gap(): Boolean { val f = first; first = false; return !f }
            sleepC?.let { if (gap()) RowDivider()
                InsightRow(SleepTint, null, "Avg sleep · ${it.withEpisodeDays} vs ${it.withoutDays} days", "${hm(Math.round(it.withEpisode).toInt())} vs ${hm(Math.round(it.without).toInt())}", res = R.drawable.ic_insight_avg_sleep) }
            deepSleepC?.let { if (gap()) RowDivider()
                InsightRow(DeepSleepTint, null, "Avg deep sleep · ${it.withEpisodeDays} vs ${it.withoutDays} days", "${hm(Math.round(it.withEpisode).toInt())} vs ${hm(Math.round(it.without).toInt())}", res = R.drawable.ic_insight_deep_sleep) }
            remSleepC?.let { if (gap()) RowDivider()
                InsightRow(RemSleepTint, null, "Avg REM sleep · ${it.withEpisodeDays} vs ${it.withoutDays} days", "${hm(Math.round(it.withEpisode).toInt())} vs ${hm(Math.round(it.without).toInt())}", res = R.drawable.ic_insight_rem_sleep) }
            hrC?.let { if (gap()) RowDivider()
                InsightRow(HeartTint, { glyphPulse(it) }, "Avg heart rate · ${it.withEpisodeDays} vs ${it.withoutDays} days", "${Math.round(it.withEpisode)} vs ${Math.round(it.without)} bpm") }
            restingHrC?.let { if (gap()) RowDivider()
                InsightRow(RestingHeartTint, null, "Avg resting HR · ${it.withEpisodeDays} vs ${it.withoutDays} days", "${Math.round(it.withEpisode)} vs ${Math.round(it.without)} bpm", res = R.drawable.ic_insight_resting_heart) }
            hrvC?.let { if (gap()) RowDivider()
                InsightRow(HrvTint, { glyphHrv(it) }, "Avg HRV (RMSSD) · ${it.withEpisodeDays} vs ${it.withoutDays} days", "${Math.round(it.withEpisode)} vs ${Math.round(it.without)} ms") }
            stepsC?.let { if (gap()) RowDivider()
                InsightRow(StepsTint, null, "Avg steps · ${it.withEpisodeDays} vs ${it.withoutDays} days", "%,d vs %,d".format(Math.round(it.withEpisode), Math.round(it.without)), res = R.drawable.ic_health_steps_shoe) }
        }
        Txt("Days with an episode compared with days without, using data from Health Connect. This explores potential associations in your own data, not proven causes of attacks.",
            Type.caption, sub = true, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun SubSectionCard(
    title: String,
    subtitle: String?,
    tint: Color,
    @DrawableRes logoRes: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(pad = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).background(tint, CircleShape), Alignment.Center) {
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(title, Type.headline)
                if (subtitle != null) Txt(subtitle, Type.caption, sub = true)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Dim.line))
        content()
    }
}

@Composable
private fun MetricInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(label, Type.body, sub = true)
        Txt(value, Type.body.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun MetricDivider() = Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(Dim.line.copy(alpha = 0.5f)))

private fun DrawScope.glyphSteps(c: Color) {
    val w = size.width; val h = size.height
    drawOval(c, Offset(w * .16f, h * .14f), Size(w * .3f, h * .46f))
    drawOval(c, Offset(w * .54f, h * .4f), Size(w * .3f, h * .46f))
}

private fun DrawScope.glyphPulse(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply {
        moveTo(w * .04f, h * .55f); lineTo(w * .3f, h * .55f); lineTo(w * .42f, h * .2f)
        lineTo(w * .58f, h * .85f); lineTo(w * .7f, h * .5f); lineTo(w * .96f, h * .5f)
    }, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.glyphRestingHeart(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * .5f, h * .82f)
        cubicTo(w * .18f, h * .58f, w * .08f, h * .3f, w * .32f, h * .2f)
        cubicTo(w * .44f, h * .17f, w * .5f, h * .3f, w * .5f, h * .3f)
        cubicTo(w * .5f, h * .3f, w * .56f, h * .17f, w * .68f, h * .2f)
        cubicTo(w * .92f, h * .3f, w * .82f, h * .58f, w * .5f, h * .82f)
        close()
    }
    drawPath(p, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.glyphHrv(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply {
        moveTo(w * .05f, h * .55f)
        lineTo(w * .2f, h * .55f)
        lineTo(w * .28f, h * .22f)
        lineTo(w * .38f, h * .82f)
        lineTo(w * .45f, h * .55f)
        lineTo(w * .58f, h * .55f)
        lineTo(w * .68f, h * .35f)
        lineTo(w * .78f, h * .72f)
        lineTo(w * .84f, h * .55f)
        lineTo(w * .95f, h * .55f)
    }, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
