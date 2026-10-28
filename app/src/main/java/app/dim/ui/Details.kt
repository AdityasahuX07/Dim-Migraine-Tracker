package app.dim.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.DimApp
import app.dim.R
import app.dim.data.Episode
import app.dim.data.MedStore
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun csvSet(s: String) = s.split(",").filter { it.isNotBlank() }.toSet()
private fun Set<String>.flipped(x: String) = (if (x in this) this - x else this + x).joinToString(",")
private fun clock(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
private fun span(a: Long, b: Long) = TimeUnit.MILLISECONDS.toMinutes((b - a).coerceAtLeast(0)).let { "${it / 60}h ${it % 60}m" }

internal val SYMPTOM_LIST = listOf("Nausea", "Light sensitivity", "Vomiting", "Sound sensitivity", "Vision loss", "Worse pain if moving", "Numbness",
    "Tingling near eyes", "Muscle stiffness", "Yawning", "Weakness", "Irritability")

/**
 * Logo-disc colours, one per feature, chosen to suggest what the feature is (brown = caffeine, green = being out,
 * pink = stress, blue = water ...). No two features share a colour, on the detail page or on Insights.
 * Pain location / avg pain (purple), sleep and heart rate keep their original colours (see HealthInsights.kt).
 */
internal object Tint {
    val PainLocation = Color(0xFF6A55A8)   // purple (also Avg pain on Insights)
    val Stress = Color(0xFFC96C92)         // pink
    val Hydration = Color(0xFF4873BF)      // water blue
    val Meal = Color(0xFFC5AB63)           // golden
    val Caffeine = Color(0xFF774231)       // coffee brown
    val Aura = Color(0xFF51668F)           // slate blue
    val DurationRecent = Color(0xFF53916E) // teal green
    val DurationAllTime = Color(0xFF7876B2) // periwinkle
    val Hrv = Color(0xFF518F8A)            // teal
    val Steps = Color(0xFF4DB358)          // bright green
}

/** "Where were you when it started?" options. [label] is what is stored in Episode.onsetPlace. */
internal class Place(val label: String, @DrawableRes val res: Int, val tint: Color,
                     val cardW: Dp = 32.dp, val cardH: Dp = 32.dp, val rowW: Dp = 24.dp, val rowH: Dp = 24.dp)   // wide logos get a wider icon box
internal val PLACES = listOf(
    Place("Home", R.drawable.ic_place_home, Color(0xFF917555)),          // warm wood tan
    Place("Work", R.drawable.ic_place_work, Color(0xFF76989F)),           // office grey-blue
    Place("School", R.drawable.ic_place_school, Color(0xFFC4B44F)),      // pencil yellow
    Place("College", R.drawable.ic_place_college, Color(0xFFA6674D)),    // brick
    Place("Library", R.drawable.ic_place_library, Color(0xFFB3A474)),    // old paper
    Place("Travelling", R.drawable.ic_place_travelling, Color(0xFF71B2A7)), // lagoon
    Place("On bed", R.drawable.ic_place_on_bed, Color(0xFF303C73)),      // night navy
    Place("Out", R.drawable.ic_place_out, Color(0xFF5BA457)),            // green
    Place("Movie theater", R.drawable.ic_place_movie_theater, Color(0xFFA33D5A), 66.dp, 45.dp, 36.dp, 25.dp), // velvet raspberry
    Place("Transit", R.drawable.ic_place_transit, Color(0xFF5A7186), 46.dp, 32.dp, 30.dp, 11.dp),            // slate
)
private val ENV_LIST = listOf("Bright light exposure", "Noise exposure", "Screen glare", "Strong scents", "Smoke exposure", "Alcohol in last 24h", "Weather changes")
private val STRESS_LABELS = listOf("Low", "Medium", "High")
private val HYDRATION_LABELS = listOf("Less than 500 ml", "500 ml – 1 L", "More than 1 L")
private val MEAL_LABELS = listOf("Less than 2 h ago", "2–6 h ago", "More than 6 h ago")
private val CAFFEINE_LABELS = listOf("None", "1 cup", "More than 1")

/**
 * Opens the system date picker then time picker and returns the chosen moment. It is never in the future and, when
 * [notBefore] is given (an end time), never earlier than it. The date picker already greys out those days; a time
 * picked outside the range is snapped to the nearest valid moment with a toast, so a pick never silently does nothing.
 */
internal fun pickDateTime(ctx: Context, initial: Long, notBefore: Long? = null, onPicked: (Long) -> Unit) {
    val now = System.currentTimeMillis()
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert
    DatePickerDialog(ctx, theme, { _, y, m, day ->
        TimePickerDialog(ctx, theme, { _, h, min ->
            val picked = Calendar.getInstance().apply { set(y, m, day, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            onPicked(clampPicked(ctx, picked, notBefore))
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        .apply { datePicker.maxDate = now; if (notBefore != null) datePicker.minDate = notBefore }.show()
}

/**
 * Edit mode: only the date picker. OK applies the new date and keeps the time of day, with no time picker after it.
 * Same limits as [pickDateTime] (never in the future, an end never before [notBefore]).
 */
internal fun pickDate(ctx: Context, initial: Long, notBefore: Long? = null, onPicked: (Long) -> Unit) {
    val now = System.currentTimeMillis()
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, day ->
        val picked = Calendar.getInstance().apply { timeInMillis = initial; set(y, m, day); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        onPicked(clampPicked(ctx, picked, notBefore))
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        .apply { datePicker.maxDate = now; if (notBefore != null) datePicker.minDate = notBefore }.show()
}

/** Edit mode: only the time picker. OK applies the new time and keeps the date. Same limits as [pickDateTime]. */
internal fun pickTime(ctx: Context, initial: Long, notBefore: Long? = null, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    TimePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, min ->
        val picked = Calendar.getInstance().apply { timeInMillis = initial; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        onPicked(clampPicked(ctx, picked, notBefore))
    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
}

/** One rule for every picker: a moment is never in the future and an end is never before its start; adjusted picks say so in a toast. */
private fun clampPicked(ctx: Context, picked: Long, notBefore: Long?): Long {
    val thisMinute = System.currentTimeMillis() / 60000 * 60000
    return when {
        picked > thisMinute -> {
            Toast.makeText(ctx, "That time is in the future, so it was set to now", Toast.LENGTH_SHORT).show()
            maxOf(thisMinute, notBefore ?: thisMinute)
        }
        notBefore != null && picked < notBefore -> {
            if (picked < notBefore / 60000 * 60000) Toast.makeText(ctx, "The end can't be before the start, so it was set to the start time", Toast.LENGTH_SHORT).show()
            notBefore
        }
        else -> picked
    }
}

/**
 * Episode details (opened from History). Opens read-only; "Edit" unlocks every field, including start and end time.
 * Changes save automatically when you leave this screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Detail(e: Episode, onSave: (Episode) -> Unit, onDelete: (Episode) -> Unit, onBack: () -> Unit) {
    var d by remember(e.id) { mutableStateOf(e) }
    val latest by rememberUpdatedState(d)
    var deleted by remember { mutableStateOf(false) }
    DisposableEffect(e.id) { onDispose { if (!deleted) onSave(latest) } }
    var editing by remember(e.id) { mutableStateOf(false) }
    var open by remember { mutableStateOf<Int?>(null) }   // which Migraine Details row is expanded (edit mode)
    val ctx = LocalContext.current
    val app = remember { ctx.applicationContext as DimApp }
    val zone = remember { ZoneId.systemDefault() }
    val date = remember(d.startedAt) { Instant.ofEpochMilli(d.startedAt).atZone(zone).toLocalDate() }
    val dayEpoch = remember(date) { date.toEpochDay() }
    val healthDay by remember(dayEpoch) { app.health.day(dayEpoch) }.collectAsState(null)
    val hasHealth = healthDay?.let { it.sleepMinutes != null || it.steps != null || it.hrAvg != null || it.restingHr != null || it.hrvRmssd != null } == true
    val green = painColor(0)
    val accent = Dim.accent
    val end = d.endedAt

    Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ============ Section one ============
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Press(onBack, label = "Back to history") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(accent, Offset(w * .75f, h * .05f), Offset(w * .25f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(accent, Offset(w * .25f, h * .5f), Offset(w * .75f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                    Txt("History", Type.body.copy(color = accent))
                }
            }
            if (editing) Press({ editing = false; open = null }, label = "Done editing") {
                Box(Modifier.background(Dim.accent, RoundedCornerShape(50)).padding(20.dp, 10.dp)) {
                    Txt("Done", Type.caption.copy(color = Dim.onAccent, fontSize = 15.sp)) } }
            else Press({ editing = true }, label = "Edit") {
                Box(Modifier.background(Dim.fill, RoundedCornerShape(50)).padding(20.dp, 10.dp)) {
                    Txt("Edit", Type.caption.copy(color = Dim.accent, fontSize = 15.sp)) } }
        }
        // Global container for everything above Migraine Details (same look as the other section cards)
        HeroCard {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(SimpleDateFormat("d", Locale.getDefault()).format(Date(d.startedAt)), Type.display.copy(fontSize = 88.sp))
                Txt(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(d.startedAt)), Type.headline.copy(fontSize = 18.sp), sub = true)
            }

            Txt("Migraine Status", Type.headline.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            InnerCard {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    val pill: @Composable () -> Unit = {
                        Box(Modifier.background(if (end != null) green else Dim.accent, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                            Txt("I beat it", Type.headline.copy(color = Dim.onAccent, fontSize = 17.sp)) }
                    }
                    // Read-only: a status badge. Editing an ongoing episode: tap to end it now.
                    if (editing && end == null) Press({ d = d.copy(endedAt = System.currentTimeMillis()) }, label = "I beat it, end the episode now") { pill() }
                    else if (end != null) pill()
                    else Box(Modifier.background(Dim.fill, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Txt("Ongoing", Type.headline.copy(color = Dim.accent, fontSize = 17.sp)) }
                    Column(horizontalAlignment = Alignment.End) {
                        Txt(if (end != null) "Subsided" else "Ongoing since", Type.caption, sub = true)
                        Txt(clock(end ?: d.startedAt), Type.headline)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Canvas(Modifier.size(22.dp)) { clockGlyph(green) }
                        Txt("Duration:", Type.body, sub = true)
                    }
                    Txt(span(d.startedAt, end ?: System.currentTimeMillis()), Type.headline)
                }
            }
            InnerCard(pad = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Txt("Started during sleep", modifier = Modifier.weight(1f).padding(end = 12.dp))
                    if (editing) Switch(d.startedInSleep) { d = d.copy(startedInSleep = it) }
                    else Txt(if (d.startedInSleep) "Yes" else "No", Type.body.copy(color = Dim.sub))
                }
                Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                // Editing an entry: the date and the time are separate buttons, each opens only its own picker.
                val setStart: (Long) -> Unit = { t -> d = d.copy(startedAt = t, endedAt = d.endedAt?.let { if (it < t) t else it }) }
                SplitTimeRow("Started", d.startedAt, editing, accent,
                    onDate = { pickDate(ctx, d.startedAt, onPicked = setStart) },
                    onTime = { pickTime(ctx, d.startedAt, onPicked = setStart) })
                Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                if (end != null) SplitTimeRow("Ended", end, editing, accent,
                    onDate = { pickDate(ctx, end, notBefore = d.startedAt) { t -> d = d.copy(endedAt = t) } },
                    onTime = { pickTime(ctx, end, notBefore = d.startedAt) { t -> d = d.copy(endedAt = t) } })
                else // Ongoing has no date or time yet, so setting an end walks through date then time, like a new entry.
                    TimeRow("Ended", "Ongoing", editing, accent, chevron = false) {
                        pickDateTime(ctx, System.currentTimeMillis(), notBefore = d.startedAt) { t -> d = d.copy(endedAt = t) } }
            }
            if (editing && end != null) Press({ d = d.copy(endedAt = null) }, Modifier.fillMaxWidth(), "Mark as ongoing") {
                Txt("Mark as ongoing", Type.caption.copy(color = Dim.accent, textAlign = TextAlign.Center, fontSize = 14.sp), modifier = Modifier.fillMaxWidth()) }
            InnerCard {
                Txt("PAIN INTENSITY", Type.label, sub = true)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(d.pain?.toString() ?: "–", Type.display.copy(color = d.pain?.let { painColor(it) } ?: Dim.sub))
                    Txt(painLabel(d.pain), Type.headline, sub = true, modifier = Modifier.padding(bottom = 12.dp))
                }
                if (editing) {
                    Spacer(Modifier.height(8.dp)); PainSlider(d.pain) { d = d.copy(pain = it) }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Txt("No pain", Type.caption, sub = true); Txt("Worst", Type.caption, sub = true) }
                }
            }
          }
        }

        if (hasHealth) {
            Txt("Health data", Type.headline)
            HealthDayCard(healthDay)
        }

        // ============ Section two ============
        Spacer(Modifier.height(4.dp))
        Txt("Migraine Details", Type.headline)
        val loc = csvSet(d.location)
        val side = listOf("Left", "Right").firstOrNull { it in loc }
        val parts = loc.filter { it != "Left" && it != "Right" }.joinToString(", ")
        val locValue = if (loc.isEmpty()) "Not set" else listOfNotNull(side, parts.ifBlank { null }).joinToString(" · ")
        val stressSel = d.stress?.let { if (it <= 3) 0 else if (it <= 6) 1 else 2 }
        val sub = Dim.sub
        Card(pad = 0.dp) {
            DetailRow(0, open, editing, Tint.PainLocation, "Pain Location", locValue, { open = if (open == 0) null else 0 }, sub, R.drawable.ic_logo_pain_location) {
                Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PainHeadGraphic(d.location)
                    LocationPicker(d.location) { d = d.copy(location = it) }
                }
            }
            RowDivider()
            DetailRow(1, open, editing, Tint.Aura, "Aura", if (d.aura) "Yes" else "No", null, sub, R.drawable.ic_logo_aura, trailing = { Switch(d.aura) { d = d.copy(aura = it) } })
            RowDivider()
            DetailRow(2, open, editing, Tint.Stress, "Stress Level", stressSel?.let { STRESS_LABELS[it] } ?: "Not set", { open = if (open == 2) null else 2 }, sub, R.drawable.ic_logo_stress_level) {
                OptionChips(STRESS_LABELS, stressSel) { i -> d = d.copy(stress = i?.let { intArrayOf(2, 5, 8)[it] }) } }
            RowDivider()
            DetailRow(3, open, editing, Tint.Hydration, "Hydration", d.hydration?.let { HYDRATION_LABELS[it] } ?: "Not set", { open = if (open == 3) null else 3 }, sub, icon = { glyphDrop(it) }) {
                OptionChips(HYDRATION_LABELS, d.hydration) { i -> d = d.copy(hydration = i) } }
            RowDivider()
            DetailRow(4, open, editing, Tint.Meal, "Last Meal", d.meal?.let { MEAL_LABELS[it] } ?: "Not set", { open = if (open == 4) null else 4 }, sub, R.drawable.ic_logo_last_meal) {
                OptionChips(MEAL_LABELS, d.meal) { i -> d = d.copy(meal = i) } }
            RowDivider()
            DetailRow(5, open, editing, Tint.Caffeine, "Caffeine", d.caffeine?.let { CAFFEINE_LABELS[it] } ?: "Not set", { open = if (open == 5) null else 5 }, sub, R.drawable.ic_logo_caffeine) {
                OptionChips(CAFFEINE_LABELS, d.caffeine) { i -> d = d.copy(caffeine = i) } }
            RowDivider()
            val place = PLACES.firstOrNull { it.label == d.onsetPlace }
            DetailRow(6, open, editing, place?.tint ?: Color(0xFF7D8A94), "Where It Started", place?.label ?: "Not set", { open = if (open == 6) null else 6 }, sub,
                place?.res, if (place == null) ({ c -> glyphPin(c) }) else null, glyphW = place?.rowW ?: 24.dp, glyphH = place?.rowH ?: 24.dp) {
                OptionChips(PLACES.map { it.label }, PLACES.indexOfFirst { it.label == d.onsetPlace }.takeIf { it >= 0 }) { i -> d = d.copy(onsetPlace = i?.let { PLACES[it].label } ?: "") } }
        }

        val sy = csvSet(d.symptoms)
        if (editing) {
            Txt("Symptoms", Type.headline)
            SYMPTOM_LIST.chunked(2).forEach { r ->
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                    r.forEach { s -> CheckCard(s, s in sy, Modifier.weight(1f)) { d = d.copy(symptoms = sy.flipped(s)) } }
                }
            }
        } else {
            val shown = SYMPTOM_LIST.filter { it in sy }
            if (shown.isNotEmpty()) { Txt("Symptoms", Type.headline); ReadList(shown) }
        }

        val tr = csvSet(d.triggers)
        if (editing) {
            Txt("Environmental factors", Type.headline)
            Card(pad = 0.dp) {
                ENV_LIST.forEachIndexed { i, f ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Dim.line))
                    Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Txt(f, modifier = Modifier.weight(1f).padding(end = 12.dp)); Switch(f in tr) { d = d.copy(triggers = tr.flipped(f)) }
                    }
                }
            }
        } else {
            val shown = ENV_LIST.filter { it in tr }
            if (shown.isNotEmpty()) { Txt("Environmental factors", Type.headline); ReadList(shown) }
        }

        val medChosen = csvSet(d.medication)
        val medStore = remember { MedStore(ctx) }
        val meds = remember { medStore.occasional() }
        val allMeds = (meds + medChosen.filter { it !in meds })
        if (editing) {
            Txt("Medication", Type.headline)
            if (allMeds.isEmpty()) {
                Card {
                    Txt("No occasional medicines yet. Add them in Settings > Medication.", Type.caption, sub = true, modifier = Modifier.padding(horizontal = 4.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    allMeds.chunked(3).forEachIndexed { rowIndex, row ->
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { colIndex, m ->
                                val index = rowIndex * 3 + colIndex
                                val tint = medTint(index)
                                SquareCard(m, R.drawable.ic_logo_medication, null, m in medChosen, Modifier.weight(1f), accent = tint) {
                                    d = d.copy(medication = medChosen.flipped(m))
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        } else {
            val medList = medChosen.toList()
            if (medList.isNotEmpty()) {
                Txt("Medication", Type.headline)
                Card(pad = 0.dp) {
                    medList.forEachIndexed { i, m ->
                        if (i > 0) RowDivider()
                        val idx = allMeds.indexOf(m).let { if (it >= 0) it else i }
                        val tint = medTint(idx)
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp, 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(Modifier.size(48.dp).background(tint, CircleShape), Alignment.Center) {
                                LogoGlyph(R.drawable.ic_logo_medication)
                            }
                            Txt(m, Type.body, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Edit mode ends with two equal pills: Delete (tinted red) and Done (accent), same size and shape.
        if (editing) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            val pill = RoundedCornerShape(50)
            Press({ deleted = true; onDelete(e); onBack() }, Modifier.weight(1f), "Delete episode") {
                Box(Modifier.fillMaxWidth().height(58.dp).background(DangerRed.copy(alpha = .16f), pill), Alignment.Center) {
                    Txt("Delete", Type.headline.copy(color = DangerRed, fontSize = 17.sp), maxLines = 1) } }
            Press({ editing = false; open = null }, Modifier.weight(1f), "Done editing") {
                Box(Modifier.fillMaxWidth().height(58.dp).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), pill), Alignment.Center) {
                    Txt("Done", Type.headline.copy(color = Dim.onAccent, fontSize = 17.sp), maxLines = 1) } }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

/**
 * Started / Ended line for a moment that is edited as a whole (new entry: date picker, then time picker). When editable
 * the row is tappable and, unless [chevron] is false, ends in an arrow.
 */
@Composable
internal fun TimeRow(label: String, value: String, editable: Boolean, accent: Color, chevron: Boolean = true, onClick: () -> Unit) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(18.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Txt(label)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(value, Type.body.copy(color = if (editable) accent else Dim.sub))
                if (editable && chevron) Canvas(Modifier.size(12.dp)) {
                    val w = size.width; val h = size.height
                    drawLine(accent, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(accent, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
    if (editable) Press(onClick, Modifier.fillMaxWidth(), "Change $label time", content = { row() }) else row()
}

/**
 * Started / Ended line for editing an existing entry: "19 Oct 2026 • 03:25 PM" where the date and the time are two
 * separate buttons ([onDate] opens only the date picker, [onTime] only the time picker). Read-only it is one plain
 * string. No arrow. Row height and text position are the same in both modes.
 */
@Composable
private fun SplitTimeRow(label: String, t: Long, editable: Boolean, accent: Color, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = if (editable) 12.dp else 18.dp, top = if (editable) 8.dp else 18.dp, bottom = if (editable) 8.dp else 18.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label)
        if (editable) Row(verticalAlignment = Alignment.CenterVertically) {
            Press(onDate, label = "Change $label date") {
                Txt(dateStamp(t), Type.body.copy(color = accent), modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) }
            Txt("•", Type.body.copy(color = Dim.sub))
            Press(onTime, label = "Change $label time") {
                Txt(timeStamp(t), Type.body.copy(color = accent), modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) }
        } else Txt(dateTimeStamp(t), Type.body.copy(color = Dim.sub))
    }
}

/** Recipe for a round logo: disc colour plus the white glyph, an XML vector drawable from res (ic_logo_*). */
internal class Logo(val tint: Color, @DrawableRes val res: Int)

/** White 24 dp glyph inside a logo disc: the drawable when there is one, otherwise a glyph drawn in code ([icon]). */
@Composable
internal fun LogoGlyph(@DrawableRes res: Int?, icon: (DrawScope.(Color) -> Unit)? = null, w: Dp = 24.dp, h: Dp = 24.dp) {
    if (res != null) Image(painterResource(res), null, Modifier.size(w, h), colorFilter = ColorFilter.tint(Color.White))
    else if (icon != null) Canvas(Modifier.size(24.dp)) { icon(this, Color.White) }
}

internal val MED_TINTS = listOf(
    Color(0xFF9A5B94), // plum
    Color(0xFFA6BA78), // pistachio
    Color(0xFF8C9442), // olive
    Color(0xFFB47EA4), // mauve
    Color(0xFFBA8578), // clay
    Color(0xFF844294), // grape
    Color(0xFFB872C0), // orchid
    Color(0xFF8D4950), // garnet
)   // none of these is used by another feature

internal fun medTint(index: Int): Color = MED_TINTS[index % MED_TINTS.size]

internal val ITEM_LOGOS: Map<String, Logo> = mapOf(
    // symptoms
    "Nausea" to Logo(Color(0xFF8FB656), R.drawable.ic_logo_nausea),                       // queasy lime
    "Light sensitivity" to Logo(Color(0xFFD09D62), R.drawable.ic_logo_light_sensitivity), // glare amber
    "Vomiting" to Logo(Color(0xFF5F7F43), R.drawable.ic_logo_vomiting),                   // bile olive
    "Sound sensitivity" to Logo(Color(0xFF4C4BAF), R.drawable.ic_logo_sound_sensitivity), // indigo
    "Vision loss" to Logo(Color(0xFF537068), R.drawable.ic_logo_vision_loss),             // dim grey-green
    "Worse pain if moving" to Logo(Color(0xFFD2916B), R.drawable.ic_logo_worse_pain_moving), // warm peach
    "Numbness" to Logo(Color(0xFF96A8B6), R.drawable.ic_logo_numbness),                   // icy grey-blue
    "Tingling near eyes" to Logo(Color(0xFF59B87E), R.drawable.ic_logo_tingling_near_eyes), // spark green
    "Muscle stiffness" to Logo(Color(0xFF2F6F7A), R.drawable.ic_logo_muscle_stiffness),   // deep teal
    "Yawning" to Logo(Color(0xFF9F9289), R.drawable.ic_logo_yawning),                     // sleepy warm grey
    "Weakness" to Logo(Color(0xFF83A37D), R.drawable.ic_logo_weakness),                   // faded sage
    "Irritability" to Logo(Color(0xFFCC5940), R.drawable.ic_logo_irritability),           // flare red-orange
    // environmental factors
    "Bright light exposure" to Logo(Color(0xFFCEA546), R.drawable.ic_logo_bright_light),  // sun gold
    "Screen glare" to Logo(Color(0xFF53A1BD), R.drawable.ic_logo_screen_glare),           // screen cyan
    "Noise exposure" to Logo(Color(0xFFC4425A), R.drawable.ic_logo_noise_exposure),       // crimson
    "Strong scents" to Logo(Color(0xFFD05A78), R.drawable.ic_logo_strong_scents),         // rose
    "Smoke exposure" to Logo(Color(0xFF5B5E67), R.drawable.ic_logo_smoke_exposure),       // smoke grey
    "Alcohol in last 24h" to Logo(Color(0xFF7E2F5A), R.drawable.ic_logo_alcohol),         // wine
    "Weather changes" to Logo(Color(0xFF6B89AC), R.drawable.ic_logo_weather_changes),     // sky
)

/** Read-only list of the items that were ticked: round logo + name per row, same look as Migraine Details. */
@Composable
private fun ReadList(items: List<String>) {
    Card(pad = 0.dp) {
        items.forEachIndexed { i, name ->
            if (i > 0) RowDivider()
            val logo = ITEM_LOGOS[name]
            Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(48.dp).background(logo?.tint ?: Dim.accent, CircleShape), Alignment.Center) {
                    if (logo != null) LogoGlyph(logo.res)
                }
                Txt(name, Type.body, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Hero container: lighter shade than the regular cards. */
@Composable
internal fun HeroCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.fillMaxWidth().background(Dim.fill, shape).border(0.5.dp, Dim.line, shape).padding(12.dp), content = content)
}

/** Panel used inside the hero container: darker than the container, same shade as the other section cards. */
@Composable
internal fun InnerCard(pad: androidx.compose.ui.unit.Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().background(Dim.card, shape).border(0.5.dp, Dim.line, shape).padding(pad), content = content)
}

@Composable
internal fun RowDivider() = Box(Modifier.fillMaxWidth().padding(start = 80.dp).height(0.5.dp).background(Dim.line))

/** One Migraine Details row: round logo (drawable [res], or a code-drawn [icon]), small label, value. Tap a row with a body to edit it in place. */
@Composable
private fun DetailRow(index: Int, open: Int?, editable: Boolean, tint: Color, label: String, value: String,
                      onClick: (() -> Unit)?, chevron: Color,
                      @DrawableRes res: Int? = null, icon: (DrawScope.(Color) -> Unit)? = null,
                      trailing: @Composable () -> Unit = {}, glyphW: Dp = 24.dp, glyphH: Dp = 24.dp, body: (@Composable ColumnScope.() -> Unit)? = null) {
    val expanded = editable && open == index && body != null
    val tappable = editable && onClick != null
    val rowContent: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(48.dp).background(tint, CircleShape), Alignment.Center) { LogoGlyph(res, icon, glyphW, glyphH) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(label, Type.caption, sub = true)
                Txt(value, Type.body, maxLines = 2)
            }
            if (editable) trailing()
            if (tappable) Canvas(Modifier.size(14.dp)) {
                val w = size.width; val h = size.height; val a = if (expanded) .7f else .3f; val b = if (expanded) .3f else .7f
                drawLine(chevron, Offset(0f, h * a), Offset(w / 2, h * b), 2.dp.toPx(), StrokeCap.Round)
                drawLine(chevron, Offset(w / 2, h * b), Offset(w, h * a), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (tappable && onClick != null) Press(onClick, Modifier.fillMaxWidth(), label + ", " + value, content = { rowContent() }) else rowContent()
        if (expanded && body != null) body()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(labels: List<String>, sel: Int?, onSel: (Int?) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> Chip(l, sel == i) { onSel(if (sel == i) null else i) } }
    }
}

// ---- Glyphs still drawn in code (everything else comes from the ic_logo_* drawables) ----

internal fun DrawScope.clockGlyph(c: Color) {
    val w = size.width; val s = 2.dp.toPx()
    drawCircle(c, w / 2 - s, style = Stroke(s))
    drawLine(c, center, Offset(w / 2, w * .24f), s, StrokeCap.Round)
    drawLine(c, center, Offset(w * .68f, w * .6f), s, StrokeCap.Round)
}

internal fun DrawScope.glyphHeart(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply { moveTo(w * .5f, h * .88f); cubicTo(w * .02f, h * .52f, w * .18f, h * .08f, w * .5f, h * .32f)
        cubicTo(w * .82f, h * .08f, w * .98f, h * .52f, w * .5f, h * .88f); close() }, c)
}

/** Map pin: shown for "Where It Started" while no place is chosen. */
internal fun DrawScope.glyphPin(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply { moveTo(w * .5f, h * .94f); cubicTo(w * .2f, h * .6f, w * .18f, h * .46f, w * .18f, h * .38f)
        cubicTo(w * .18f, h * .2f, w * .32f, h * .06f, w * .5f, h * .06f); cubicTo(w * .68f, h * .06f, w * .82f, h * .2f, w * .82f, h * .38f)
        cubicTo(w * .82f, h * .46f, w * .8f, h * .6f, w * .5f, h * .94f); close() }, c)
    drawCircle(Color(0xFF7D8A94), w * .12f, Offset(w * .5f, h * .38f))
}

internal fun DrawScope.glyphDrop(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply { moveTo(w * .5f, h * .06f); cubicTo(w * .74f, h * .36f, w * .84f, h * .5f, w * .84f, h * .64f)
        cubicTo(w * .84f, h * .82f, w * .68f, h * .94f, w * .5f, h * .94f); cubicTo(w * .32f, h * .94f, w * .16f, h * .82f, w * .16f, h * .64f)
        cubicTo(w * .16f, h * .5f, w * .26f, h * .36f, w * .5f, h * .06f); close() }, c)
}

internal fun DrawScope.glyphBars(c: Color) {
    val w = size.width; val h = size.height; val s = 2.5.dp.toPx()
    listOf(.12f to .1f, .31f to .28f, .5f to .42f, .69f to .22f, .88f to .12f).forEach { (x, r) ->
        drawLine(c, Offset(w * x, h * (.5f - r)), Offset(w * x, h * (.5f + r)), s, StrokeCap.Round) }
}
