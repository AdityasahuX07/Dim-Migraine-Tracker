package app.dim.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.annotation.DrawableRes
import app.dim.DimApp
import app.dim.R
import app.dim.data.Episode
import app.dim.data.MedStore
import app.dim.data.health.HealthDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun csv(s: String) = s.split(",").filter { it.isNotBlank() }.toSet()
private fun Set<String>.flip(x: String) = (if (x in this) this - x else this + x).joinToString(",")

/**
 * Three-page details flow. For a running episode it already exists (started with one tap) and edits autosave when
 * you leave. A past entry added from History (id 0) is not in the database yet: it asks for start and end first,
 * can be cancelled, and is stored only when you tap Save.
 */
@Composable
fun LogFlow(e: Episode, onSave: (Episode) -> Unit, onClose: () -> Unit) {
    val isNew = e.id == 0L
    var d by remember(e.id) { mutableStateOf(e) }
    val latest by rememberUpdatedState(d)
    DisposableEffect(e.id) { onDispose { if (!isNew) onSave(latest) } }
    var page by remember { mutableStateOf(0) }
    // 0 = single full-width "Next", 1 = "Back" + "Next" side by side; animated so the button splits smoothly.
    val split by animateFloatAsState(if (page > 0) 1f else 0f, tween(300, easing = FastOutSlowInEasing), label = "split")
    BackHandler { if (page > 0) page-- else if (isNew) onClose() } // a running episode is finished only via Done; a new entry can be cancelled
    val lastLabel = if (isNew) "Save" else "Done"

    Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(20.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Txt(listOf(if (isNew) "New entry" else "Pain", "Lifestyle", "Health")[page], Type.headline.copy(fontSize = 17.sp, textAlign = TextAlign.Center), sub = true, modifier = Modifier.fillMaxWidth())
                if (isNew) Press(onClose, Modifier.align(Alignment.CenterStart), "Cancel, discard this entry") {
                    Txt("Cancel", Type.body.copy(color = Dim.accent, fontSize = 16.sp)) }
            }
            PageDots(page)
        }
        // The buttons float over the scrolling content (no backing strip), like the nav pill; content runs behind them.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Push transition: going forward the new page slides in from the right while the current one slides out to the
            // left; going back it is the reverse. Each page keeps its own scroll state, so a page starts at the top and the
            // outgoing one doesn't jump while it slides away.
            AnimatedContent(page, Modifier.fillMaxSize(), label = "page", transitionSpec = {
                val forward = targetState > initialState
                slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { w -> if (forward) w else -w } togetherWith
                    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { w -> if (forward) -w else w }
            }) { p ->
                Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), Arrangement.spacedBy(16.dp)) {
                    when (p) { 0 -> PageOne(d, isNew) { d = it }; 1 -> PageTwo(d) { d = it }; else -> PageThree(d) { d = it } }
                    Spacer(Modifier.navigationBarsPadding().height(96.dp))   // keeps the last item clear of the floating buttons and their fade
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                // Soft dark fade behind the buttons (same colour as the screen, transparent at the top) so they read clearly over the content.
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Dim.bg.copy(alpha = 0f), Dim.bg.copy(alpha = .92f)))))
                BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 12.dp)) {
                    val gap = 12.dp
                    val backW = (maxWidth - gap) / 2 * split
                    Row {
                        if (split > 0.001f) {
                            Press({ page-- }, Modifier.width(backW), "Back") {
                                Box(Modifier.fillMaxWidth().height(58.dp).shadow(16.dp, RoundedCornerShape(50)).background(Dim.fill, RoundedCornerShape(50)), Alignment.Center) {
                                    Txt("Back", Type.headline.copy(color = Dim.text.copy(alpha = split)), maxLines = 1) } }
                            Spacer(Modifier.width(gap * split))
                        }
                        Press({ if (page < 2) page++ else { if (isNew) onSave(d); onClose() } }, Modifier.weight(1f), if (page < 2) "Next" else lastLabel) {
                            Box(Modifier.fillMaxWidth().height(58.dp).shadow(16.dp, RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), RoundedCornerShape(50)), Alignment.Center) {
                                Txt(if (page < 2) "Next" else lastLabel, Type.headline.copy(color = Dim.onAccent)) } }
                    }
                }
            }
        }
    }
}

private fun lasted(a: Long, b: Long) = ((b - a) / 60000).coerceAtLeast(0).let { "${it / 60}h ${it % 60}m" }

@Composable
private fun PageOne(d: Episode, isNew: Boolean, set: (Episode) -> Unit) {
    val ctx = LocalContext.current
    if (isNew) {
        // Past entry: when did it start and end. Start can't be in the future; end can't be before start or in the future.
        val end = d.endedAt ?: d.startedAt
        Txt("When did it happen?", Type.headline)
        Card(pad = 0.dp) {
            Box(Modifier.fillMaxWidth().padding(18.dp, 14.dp)) { SleepOnsetRow("Started during sleep", d.startedInSleep) { set(d.copy(startedInSleep = it)) } }
            Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
            TimeRow("Started", dateTimeStamp(d.startedAt), true, Dim.accent) {
                pickDateTime(ctx, d.startedAt) { t -> set(d.copy(startedAt = t, endedAt = maxOf(end, t))) } }
            Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
            TimeRow("Ended", dateTimeStamp(end), true, Dim.accent) {
                pickDateTime(ctx, end, notBefore = d.startedAt) { t -> set(d.copy(endedAt = t)) } }
        }
        Txt("Duration: ${lasted(d.startedAt, end)}", Type.caption, sub = true, modifier = Modifier.padding(horizontal = 4.dp))
    } else {
        // Running episode: the very first question on the page.
        Txt("Did it start during your sleep?", Type.headline)
        Card { SleepOnsetRow(if (d.startedInSleep) "Yes" else "No", d.startedInSleep) { set(d.copy(startedInSleep = it)) } }
    }
    // When it started during sleep, the questions that can't apply are greyed out and locked (the location stays active).
    val asleep = d.startedInSleep
    val meds = remember { MedStore(ctx).occasional() }
    var tookMed by remember { mutableStateOf(d.medication.isNotBlank()) }
    val chosen = csv(d.medication)
    Locked(asleep, "Pain severity") {
        Txt("How severe is your pain?", Type.headline)
        Card {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(d.pain?.toString() ?: "–", Type.display.copy(color = d.pain?.let { painColor(it) } ?: Dim.sub))
                Txt(painLabel(d.pain), Type.headline, sub = true)
            }
            Spacer(Modifier.height(8.dp)); PainSlider(d.pain) { set(d.copy(pain = it)) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Txt("No pain", Type.caption, sub = true); Txt("Worst", Type.caption, sub = true) }
        }
    }
    Txt("Where is the pain located?", Type.headline)
    PainHeadGraphic(d.location)
    LocationPicker(d.location) { set(d.copy(location = it)) }
    Locked(asleep, "Aura") {
        Txt("Do you have aura?", Type.headline)
        Card {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(if (d.aura) "Yes, I have aura" else "No, I don't have aura", modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(d.aura) { set(d.copy(aura = it)) }
            }
        }
    }
    Locked(asleep, "Medicine") {
        Txt("Did you take medicine?", Type.headline)
        Card {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(if (tookMed) "Yes, I took medicine" else "No, I didn't take medicine", modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(tookMed) { on -> tookMed = on; if (!on) set(d.copy(medication = "")) }
            }
        }
        if (tookMed) {
            val allMeds = (meds + chosen.filter { it !in meds })
            if (allMeds.isEmpty()) {
                Txt("No occasional medicines yet. Add them in Settings > Medication.", Type.caption, sub = true, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    allMeds.chunked(3).forEachIndexed { rowIndex, row ->
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { colIndex, m ->
                                val index = rowIndex * 3 + colIndex
                                val tint = medTint(index)
                                SquareCard(m, R.drawable.ic_logo_medication, null, m in chosen, Modifier.weight(1f), accent = tint) {
                                    set(d.copy(medication = chosen.flip(m)))
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Greys out a block and blocks taps and drags on it while [off] (questions that don't apply when the attack started
 * during sleep). Scrolling still works over it. Nothing is cleared: values already entered are kept, so switching the
 * toggle back restores the block exactly as it was. Screen readers hear one "turned off" item instead of the controls.
 */
@Composable
private fun Locked(off: Boolean, label: String, content: @Composable ColumnScope.() -> Unit) {
    val a = animateFloatAsState(if (off) .38f else 1f, tween(180), label = "locked")
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = a.value }
        .then(if (off) Modifier.clearAndSetSemantics { contentDescription = "$label, turned off because it started during sleep"; disabled() } else Modifier)) {
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp), content = content)
        if (off) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })   // swallows taps/drags meant for the controls underneath
    }
}

/** One quiet line at the top of a page whose questions are all locked. */
@Composable
private fun SleepNote(on: Boolean) {
    if (on) Txt("Skipped because it started during your sleep.", Type.caption, sub = true, modifier = Modifier.padding(horizontal = 4.dp))
}

/** "Started during sleep" switch; used at the top of the running-episode form and inside "When did it happen?". Label stays on one line. */
@Composable
private fun SleepOnsetRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label, modifier = Modifier.weight(1f).padding(end = 12.dp), maxLines = 1)
        Switch(on, onChange)
    }
}

@Composable
private fun PageTwo(d: Episode, set: (Episode) -> Unit) {
    val stressSel = d.stress?.let { if (it <= 3) 0 else if (it <= 6) 1 else 2 }
    SleepNote(d.startedInSleep)
    Locked(d.startedInSleep, "Lifestyle questions") {
        Choice3("Stress level", listOf("Low", "Medium", "High"),
            listOf<DrawScope.(Color) -> Unit>({ gauge(0, it) }, { gauge(1, it) }, { gauge(2, it) }), stressSel) {
            set(d.copy(stress = it?.let { i -> intArrayOf(2, 5, 8)[i] })) }
        Choice3("Hydration today", listOf("Less than\n500 ml", "500 ml –\n1 L", "More than\n1 L"),
            sel = d.hydration, res = listOf(R.drawable.ic_hydration_glass, R.drawable.ic_hydration_small_bottle, R.drawable.ic_hydration_big_bottle)) { set(d.copy(hydration = it)) }
        Choice3("Last meal", listOf("Less than\n2 h ago", "2–6 h\nago", "More than\n6 h ago"),
            sel = d.meal, res = listOf(R.drawable.ic_meal_under_2h, R.drawable.ic_meal_2_to_6h, R.drawable.ic_meal_over_6h)) { set(d.copy(meal = it)) }
        Choice3("Caffeine today", listOf("None", "1 cup", "More than\n1"),
            sel = d.caffeine, res = listOf(R.drawable.ic_caffeine_none, R.drawable.ic_caffeine_one_cup, R.drawable.ic_caffeine_more_than_one)) { set(d.copy(caffeine = it)) }
        Txt("Where were you when it started?", Type.headline)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PLACES.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                    row.forEach { p -> SquareCard(p.label, p.res, null, d.onsetPlace == p.label, Modifier.weight(1f), iconW = p.cardW, iconH = p.cardH) { set(d.copy(onsetPlace = if (d.onsetPlace == p.label) "" else p.label)) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal fun hm(mins: Int) = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"

@Composable
internal fun HealthLogRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(label, Type.body, sub = true)
        Txt(value, Type.body.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
internal fun HealthDayCard(healthDay: HealthDay?, emptyMessage: String? = null) {
    val hasSleep = healthDay?.sleepMinutes != null
    val hasSteps = healthDay?.steps != null
    val hasHr = healthDay?.hrAvg != null
    val hasRestingHr = healthDay?.restingHr != null
    val hasHrv = healthDay?.hrvRmssd != null
    val hasAny = hasSleep || hasSteps || hasHr || hasRestingHr || hasHrv

    if (hasAny) {
        Card(pad = 0.dp) {
            var first = true
            fun gap(): Boolean { val f = first; first = false; return !f }
            if (hasSleep) {
                healthDay?.sleepMinutes?.let {
                    if (gap()) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                    HealthLogRow("Sleep", hm(it))
                }
            }
            if (hasSteps) {
                healthDay?.steps?.let {
                    if (gap()) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                    HealthLogRow("Steps", "%,d".format(it))
                }
            }
            if (hasHr) {
                healthDay?.hrAvg?.let {
                    if (gap()) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                    HealthLogRow("Heart rate", "$it bpm")
                }
            }
            if (hasRestingHr) {
                healthDay?.restingHr?.let {
                    if (gap()) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                    HealthLogRow("Resting heart rate", "$it bpm")
                }
            }
            if (hasHrv) {
                healthDay?.hrvRmssd?.let {
                    if (gap()) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                    HealthLogRow("Heart rate variability (HRV)", "${Math.round(it)} ms")
                }
            }
        }
    } else if (emptyMessage != null) {
        Card {
            Txt(emptyMessage, Type.body, sub = true)
        }
    }
}

@Composable
private fun PageThree(d: Episode, set: (Episode) -> Unit) {
    val ctx = LocalContext.current
    val app = remember { ctx.applicationContext as DimApp }
    val zone = remember { ZoneId.systemDefault() }
    val date = remember(d.startedAt) { Instant.ofEpochMilli(d.startedAt).atZone(zone).toLocalDate() }
    val dayEpoch = remember(date) { date.toEpochDay() }
    val healthDay by remember(dayEpoch) { app.health.day(dayEpoch) }.collectAsState(null)
    val ui by app.health.state.collectAsState()

    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt("HEALTH DATA", Type.label, sub = true)
        val dateFmt = remember(date) { date.format(DateTimeFormatter.ofPattern("MMM d")) }
        Txt(dateFmt, Type.caption, sub = true)
    }

    HealthDayCard(
        healthDay = healthDay,
        emptyMessage = if (ui.enabled) "No health data recorded for this date in Health Connect."
                       else "Health Connect is not connected. Connect in Settings to see health data here."
    )

    SleepNote(d.startedInSleep)
    val sy = csv(d.symptoms)
    val tr = csv(d.triggers)
    val env = listOf("Bright light exposure", "Noise exposure", "Screen glare", "Strong scents", "Smoke exposure", "Alcohol in last 24h", "Weather changes")
    Locked(d.startedInSleep, "Symptoms and environmental factors") {
        Txt("Symptoms", Type.headline)
        SYMPTOM_LIST.chunked(2).forEach { r ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                r.forEach { s -> CheckCard(s, s in sy, Modifier.weight(1f)) { set(d.copy(symptoms = sy.flip(s))) } }
            }
        }
        Txt("Environmental factors", Type.headline)
        Card(pad = 0.dp) {
            env.forEachIndexed { i, f ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Txt(f, modifier = Modifier.weight(1f).padding(end = 12.dp)); Switch(f in tr) { set(d.copy(triggers = tr.flip(f))) }
                }
            }
        }
    }
    Txt("Notes", Type.headline)
    Field(d.notes, { set(d.copy(notes = it.take(500))) }, "Anything else about this attack", minLines = 3)
}

@Composable
private fun Choice3(title: String, labels: List<String>, icons: List<DrawScope.(Color) -> Unit> = emptyList(), sel: Int?,
                    @DrawableRes res: List<Int> = emptyList(), onSel: (Int?) -> Unit) {
    Txt(title, Type.headline)
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { i, l -> SquareCard(l, res.getOrNull(i), icons.getOrNull(i), sel == i, Modifier.weight(1f)) { onSel(if (sel == i) null else i) } }
    }
}

@Composable
internal fun SquareCard(
    text: String,
    @DrawableRes res: Int?,
    icon: (DrawScope.(Color) -> Unit)?,
    on: Boolean,
    modifier: Modifier,
    accent: Color = Dim.accent,
    iconW: Dp = 32.dp,
    iconH: Dp = 32.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val tint = if (on) accent else Dim.sub
    Press(onClick, modifier, text.replace("\n", " ") + if (on) ", selected" else "") {
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .background(if (on) accent.copy(alpha = .2f) else Dim.card, shape)
                .border(if (on) 1.5.dp else 0.5.dp, if (on) accent else Dim.line, shape)
                .padding(6.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            if (res != null) Image(painterResource(res), null, Modifier.size(iconW, iconH), colorFilter = ColorFilter.tint(tint))
            else if (icon != null) Canvas(Modifier.size(32.dp)) { icon(this, tint) }
            Spacer(Modifier.height(6.dp))
            Txt(text, Type.caption.copy(fontSize = 12.5.sp, textAlign = TextAlign.Center), maxLines = 2)
        }
    }
}

@Composable
internal fun CheckCard(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp); val box = RoundedCornerShape(6.dp)
    Press(onClick, modifier, text + if (on) ", checked" else ", not checked") {
        Row(Modifier.fillMaxWidth().height(60.dp).background(if (on) Dim.accent.copy(alpha = .2f) else Dim.card, shape)
            .border(if (on) 1.5.dp else 0.5.dp, if (on) Dim.accent else Dim.line, shape).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(18.dp).background(if (on) Dim.accent else Color.Transparent, box).border(1.5.dp, if (on) Dim.accent else Dim.sub, box), Alignment.Center) {
                if (on) { val c = Dim.onAccent
                    Canvas(Modifier.size(10.dp)) {
                        drawLine(c, Offset(0f, size.height * .55f), Offset(size.width * .4f, size.height * .95f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(c, Offset(size.width * .4f, size.height * .95f), Offset(size.width, size.height * .1f), 2.dp.toPx(), StrokeCap.Round)
                    } }
            }
            Txt(text, Type.caption.copy(fontSize = 13.5.sp), maxLines = 2)
        }
    }
}

@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(52.dp, 32.dp).background(if (on) Dim.accent else Dim.fill, RoundedCornerShape(50))
        .semantics { stateDescription = if (on) "On" else "Off" }
        .clickable(null, null, role = Role.Switch) { onChange(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(26.dp).background(Color.White, CircleShape))
    }
}

/** Line with three dots; the current step is a large ringed dot. */
@Composable
private fun PageDots(page: Int) {
    val on = Dim.accent; val off = Dim.fill; val dot = Dim.sub.copy(alpha = .6f); val inner = Dim.onAccent
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Step ${page + 1} of 3" }) {
        val r = 11.dp.toPx(); val cy = size.height / 2
        val xs = listOf(r, size.width / 2, size.width - r)
        drawLine(off, Offset(xs[0], cy), Offset(xs[2], cy), 2.dp.toPx(), StrokeCap.Round)
        drawLine(on, Offset(xs[0], cy), Offset(xs[page], cy), 2.dp.toPx(), StrokeCap.Round)
        xs.forEachIndexed { i, x ->
            if (i == page) { drawCircle(on, r, Offset(x, cy)); drawCircle(inner, r * .45f, Offset(x, cy)) }
            else drawCircle(if (i < page) on else dot, 4.5.dp.toPx(), Offset(x, cy))
        }
    }
}

// ---- Option icons (vector, drawn in code) ----

/** Stress: gauge with the needle at low / medium / high. */
private fun DrawScope.gauge(level: Int, c: Color) {
    val w = size.width; val s = 3.dp.toPx(); val r = w * .42f; val cx = w / 2; val cy = size.height * .72f
    drawArc(c.copy(alpha = .35f), 180f, 180f, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(s, cap = StrokeCap.Round))
    val a = Math.toRadians((210 + level * 60).toDouble())
    drawLine(c, Offset(cx, cy), Offset(cx + (r * .8f * cos(a)).toFloat(), cy + (r * .8f * sin(a)).toFloat()), s, StrokeCap.Round)
    drawCircle(c, s * 1.2f, Offset(cx, cy))
}

// ================= Pain location =================
// Stored in Episode.location as comma-separated names: "Left", "Right", "Forehead", ...

private val SECTIONS = listOf(
    "Head" to listOf("Forehead", "Temple", "Top of head", "Back of head"),
    "Eyes" to listOf("Upper Area", "Lower Area", "Behind"),
    "Face" to listOf("Nose", "Ear", "Cheek / Cheekbone", "Jaw", "Teeth"),
    "Neck & nearby" to listOf("Neck", "Shoulder", "Upper Back"))

private val KNOWN: Set<String> = setOf("Left", "Right") + SECTIONS.flatMap { it.second }

/** Side cards on top, then every location as a 3-column grid of cards (zoomed round thumbnail + name), grouped by section. All multi-select. */
@Composable
internal fun LocationPicker(value: String, onChange: (String) -> Unit) {
    val sel = csv(value).filter { it in KNOWN }.toSet()   // unknown legacy values are dropped on the next edit
    val side = if ("Left" in sel) "b" else "a"             // which side of the face the thumbnails show: Left -> "b", Right -> "a"
    val art = rememberThumbArt()                           // front + back head renders, decoded once for all thumbnails
    fun flip(t: String) = onChange((if (t in sel) sel - t else sel + t).joinToString(","))
    var openSec by remember { mutableStateOf<Int?>(null) }   // one section expanded at a time; picks are kept when collapsing
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
            listOf("Right", "Left").forEach { n ->
                Tile(n in sel, n, Modifier.weight(1f), Modifier.height(64.dp), {
                    val next = if (n in sel) sel - n else sel - setOf("Left", "Right") + n   // one side at a time
                    // No side left -> the sections below disappear, so their picks are cleared too.
                    if (next.none { it == "Left" || it == "Right" }) openSec = null
                    onChange((if (next.none { it == "Left" || it == "Right" }) emptySet() else next).joinToString(","))
                }) { Txt(n, Type.headline.copy(fontSize = 17.sp)) }
            }
        }
        // Collapsed section rows appear once a side is chosen; tap one to expand its 3-column grid.
        if ("Left" in sel || "Right" in sel) SECTIONS.forEachIndexed { i, (title, items) ->
            val n = items.count { it in sel }
            val isOpen = openSec == i
            val tint = Dim.sub
            Tile(n > 0, title, Modifier.fillMaxWidth(), Modifier.height(56.dp), { openSec = if (isOpen) null else i }, solid = true) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Txt(title, Type.headline.copy(fontSize = 17.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (n > 0) Txt("$n selected", Type.caption, sub = true)
                        Canvas(Modifier.size(14.dp)) {
                            val w = size.width; val h = size.height; val a = if (isOpen) .7f else .3f; val b = if (isOpen) .3f else .7f
                            drawLine(tint, Offset(0f, h * a), Offset(w / 2, h * b), 2.dp.toPx(), StrokeCap.Round)
                            drawLine(tint, Offset(w / 2, h * b), Offset(w, h * a), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
            if (isOpen) items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                    row.forEach { n2 ->
                        // Slightly taller than square: zoomed round thumbnail of the area on top, name underneath.
                        Tile(n2 in sel, n2, Modifier.weight(1f), Modifier.aspectRatio(0.88f), { flip(n2) }) {
                            Column(Modifier.fillMaxSize().padding(6.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                                LocationThumb(n2, side, n2 in sel, art, Modifier.fillMaxWidth(0.58f))
                                Spacer(Modifier.height(6.dp))
                                Txt(n2, Type.caption.copy(fontSize = 12.sp, textAlign = TextAlign.Center))
                            } }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(on: Boolean, label: String, modifier: Modifier, size: Modifier, onClick: () -> Unit, solid: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Press(onClick, modifier, label + if (on) ", selected" else "") {
        // solid = wide section rows: selected state is a filled card (no stroke); square/side tiles keep the stroke.
        Box(Modifier.fillMaxWidth().then(size).background(if (on) Dim.accent.copy(alpha = if (solid) .38f else .2f) else Dim.card, shape)
            .border(if (on && !solid) 1.5.dp else 0.5.dp, if (on) (if (solid) Color.Transparent else Dim.accent) else Dim.line, shape), Alignment.Center, content = content)
    }
}
