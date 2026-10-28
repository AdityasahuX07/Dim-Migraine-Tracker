package app.dim.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.data.Profile
import app.dim.data.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val SEX = listOf("Female", "Male", "Other")
private val DIAGNOSIS = listOf("Not diagnosed", "Episodic migraine", "Chronic migraine", "Vestibular migraine", "Other / not sure")
private val AURA = listOf("Never", "Sometimes", "Always", "Not sure")
private val FAMILY = listOf("Mother", "Father", "Sibling", "Grandparent", "Aunt", "Uncle", "None", "Not sure")
private val FAMILY_EXCLUSIVE = setOf("None", "Not sure")
/** Stored value to short on-screen label, grouped 3 per row. */
private val CONDITION_ROWS = listOf(
    listOf("Anxiety" to "Anxiety", "Depression" to "Depression", "Sleep problems" to "Sleep issues"),
    listOf("High blood pressure" to "High BP", "Epilepsy" to "Epilepsy", "Hormonal condition" to "Hormonal"),
    listOf("Asthma / allergies" to "Asthma / allergy", "Neck / back problems" to "Neck / back", "None" to "None"))
private val CONDITIONS_EXCLUSIVE = setOf("None")

private fun String.items() = split(",").filter { it.isNotBlank() }.toSet()
private fun toggleMulti(cur: String, item: String, exclusive: Set<String>): String {
    val s = cur.items()
    val next = when { item in s -> s - item; item in exclusive -> setOf(item); else -> (s - exclusive) + item }
    return next.joinToString(",")
}

/** Settings > Profile. Every change is saved immediately to the device; nothing leaves it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val store = remember { ProfileStore(ctx) }
    var p by remember { mutableStateOf(store.load()) }
    fun upd(n: Profile) { p = n; store.save(n) }
    val scope = rememberCoroutineScope()
    val thisYear = LocalDate.now().year

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { store.savePhoto(uri) }
            if (ok) upd(p.copy(photoRev = System.currentTimeMillis()))
        }
    }

    var yearText by remember { mutableStateOf(p.birthYear?.toString() ?: "") }
    var firstText by remember { mutableStateOf(p.firstAge?.toString() ?: "") }
    val age = p.birthYear?.let { thisYear - it }
    var editing by remember { mutableStateOf(false) }
    BackHandler(editing) { editing = false }   // Back leaves edit mode before it leaves the page

    Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Press(onBack, label = "Back") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val accent = Dim.accent
                    Canvas(Modifier.size(24.dp)) {
                        val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
                        drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .88f, h * .5f), sw, StrokeCap.Round)
                        drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .46f, h * .16f), sw, StrokeCap.Round)
                        drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .46f, h * .84f), sw, StrokeCap.Round)
                    }
                    Txt("Profile", Type.title)
                }
            }
            Press({ editing = !editing }, label = if (editing) "Done editing" else "Edit profile") {
                val c = Dim.onAccent
                Box(Modifier.size(40.dp).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), CircleShape), Alignment.Center) {
                    Canvas(Modifier.size(18.dp)) {
                        val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
                        if (editing) {   // check mark
                            drawLine(c, Offset(w * .1f, h * .55f), Offset(w * .4f, h * .85f), sw, StrokeCap.Round)
                            drawLine(c, Offset(w * .4f, h * .85f), Offset(w * .92f, h * .2f), sw, StrokeCap.Round)
                        } else {         // pencil
                            drawLine(c, Offset(w * .22f, h * .78f), Offset(w * .8f, h * .2f), 3.6.dp.toPx(), StrokeCap.Round)
                            drawLine(c, Offset(w * .1f, h * .94f), Offset(w * .45f, h * .94f), sw, StrokeCap.Round)
                        }
                    }
                }
            }
        }

        if (!editing) {
            Box(Modifier.fillMaxWidth(), Alignment.Center) { Avatar(store, p, 104.dp) }
            val rows = buildList {
                add("Name" to p.name)
                add("Age" to (p.birthYear?.let { "${thisYear - it} years" } ?: ""))
                add("Sex" to p.sex)
                if (p.sex == "Female") add("Cycle tracking" to if (p.cycleTracking) "On" else "Off")
                add("Diagnosis" to p.diagnosis)
                add("Aura" to p.aura)
                add("Age at first migraine" to (p.firstAge?.toString() ?: ""))
                add("Family history" to p.family.items().joinToString(", "))
                add("Other conditions" to p.conditions.items().joinToString(", "))
                if (p.notes.isNotBlank()) add("Notes" to p.notes.trim())
            }
            Column {
                rows.forEachIndexed { i, (q, a) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Dim.line))
                    InfoRow(q, a)
                }
            }
        } else {
        // ---------------- Personal Information ----------------
        Txt("PERSONAL INFORMATION", Type.label, sub = true)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(store, p, 76.dp)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("Profile photo", Type.headline)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Press({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, label = "Choose photo") {
                            Txt(if (p.photoRev > 0) "Change" else "Choose photo", Type.body.copy(color = Dim.accent)) }
                        if (p.photoRev > 0) Press({ store.deletePhoto(); upd(p.copy(photoRev = 0)) }, label = "Remove photo") {
                            Txt("Remove", Type.body.copy(color = Dim.sub)) }
                    }
                }
            }
        }
        Card {
            Txt("DISPLAY NAME", Type.label, sub = true); Spacer(Modifier.height(10.dp))
            Field(p.name, { upd(p.copy(name = it.take(40))) }, "Your name", singleLine = true)
        }
        Card {
            Txt("BIRTH YEAR", Type.label, sub = true); Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Field(yearText, { raw ->
                    val t = raw.filter { it.isDigit() }.take(4); yearText = t
                    upd(p.copy(birthYear = t.toIntOrNull()?.takeIf { t.length == 4 && it in 1900..thisYear }))
                }, "e.g. 1990", Modifier.weight(1f), number = true, singleLine = true)
                if (age != null) Txt("$age years", Type.headline, modifier = Modifier.padding(end = 4.dp))
            }
            if (yearText.length == 4 && p.birthYear == null) {
                Spacer(Modifier.height(8.dp)); Txt("Enter a year between 1900 and $thisYear.", Type.caption, sub = true) }
        }
        Card {
            Txt("SEX", Type.label, sub = true); Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SEX.forEach { s -> Chip(s, p.sex == s) {
                    val n = if (p.sex == s) "" else s
                    upd(p.copy(sex = n, cycleTracking = if (n == "Female") p.cycleTracking else false)) } }
            }
            if (p.sex == "Female") {
                Spacer(Modifier.height(16.dp)); Box(Modifier.fillMaxWidth().height(0.5.dp).background(Dim.line)); Spacer(Modifier.height(16.dp))
                Toggle(p.cycleTracking, "Menstrual cycle tracking", "Follow your cycle alongside your migraines") { upd(p.copy(cycleTracking = it)) }
            }
        }

        // ---------------- Migraine Information ----------------
        Spacer(Modifier.height(4.dp))
        Txt("MIGRAINE INFORMATION", Type.label, sub = true)
        Card {
            Txt("MIGRAINE DIAGNOSIS", Type.label, sub = true); Spacer(Modifier.height(12.dp))
            Column { DIAGNOSIS.forEach { s -> CheckRow(s, p.diagnosis == s) { upd(p.copy(diagnosis = if (p.diagnosis == s) "" else s)) } } }
        }
        Card {
            Txt("MIGRAINE AURA", Type.label, sub = true); Spacer(Modifier.height(4.dp))
            Txt("Do you get visual or sensory changes before or during an attack?", Type.caption, sub = true); Spacer(Modifier.height(12.dp))
            Column { AURA.forEach { s -> CheckRow(s, p.aura == s) { upd(p.copy(aura = if (p.aura == s) "" else s)) } } }
        }
        Card {
            Txt("AGE AT FIRST MIGRAINE", Type.label, sub = true); Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Field(firstText, { raw ->
                    val t = raw.filter { it.isDigit() }.take(3); firstText = t
                    upd(p.copy(firstAge = t.toIntOrNull()?.takeIf { it in 0..120 }))
                }, "e.g. 14", Modifier.weight(1f), number = true, singleLine = true)
                Txt("years", Type.body, sub = true, modifier = Modifier.padding(end = 4.dp))
            }
            val fa = p.firstAge
            if (fa != null && age != null && fa > age) {
                Spacer(Modifier.height(8.dp)); Txt("That is older than your current age.", Type.caption, sub = true) }
        }
        Card {
            Txt("FAMILY HISTORY", Type.label, sub = true); Spacer(Modifier.height(4.dp))
            Txt("Relatives who get migraines", Type.caption, sub = true); Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FAMILY.forEach { s -> Chip(s, s in p.family.items()) { upd(p.copy(family = toggleMulti(p.family, s, FAMILY_EXCLUSIVE))) } } }
        }
        Card {
            Txt("OTHER HEALTH CONDITIONS", Type.label, sub = true); Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CONDITION_ROWS.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (key, label) ->
                            ConditionChip(label, key in p.conditions.items(), Modifier.weight(label.length.coerceAtLeast(8).toFloat())) {
                                upd(p.copy(conditions = toggleMulti(p.conditions, key, CONDITIONS_EXCLUSIVE))) }
                        }
                    }
                }
            }
        }
        Card {
            Txt("ADDITIONAL HEALTH NOTES", Type.label, sub = true); Spacer(Modifier.height(10.dp))
            Field(p.notes, { upd(p.copy(notes = it.take(1000))) }, "Anything else worth remembering", minLines = 4)
        }
        val focus = LocalFocusManager.current
        Press({ focus.clearFocus(); editing = false }, Modifier.fillMaxWidth(), "Save") {
            Box(Modifier.fillMaxWidth().height(50.dp).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), RoundedCornerShape(50)), Alignment.Center) {
                Txt("Save", Type.headline.copy(color = Dim.onAccent)) }
        }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Single-select list row with a checkbox (tap the selected one again to clear it). */
@Composable
private fun CheckRow(text: String, on: Boolean, onClick: () -> Unit) {
    Press(onClick, Modifier.fillMaxWidth(), text + if (on) ", selected" else "") {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val box = RoundedCornerShape(6.dp)
            Box(Modifier.size(22.dp).background(if (on) Dim.accent else Color.Transparent, box)
                .border(1.5.dp, if (on) Dim.accent else Dim.sub.copy(alpha = .6f), box), Alignment.Center) {
                if (on) {
                    val c = Dim.onAccent
                    Canvas(Modifier.size(12.dp)) {
                        val w = size.width; val h = size.height; val sw = 2.dp.toPx()
                        drawLine(c, Offset(w * .05f, h * .55f), Offset(w * .38f, h * .88f), sw, StrokeCap.Round)
                        drawLine(c, Offset(w * .38f, h * .88f), Offset(w * .95f, h * .15f), sw, StrokeCap.Round)
                    }
                }
            }
            Txt(text, Type.body)
        }
    }
}

/** Compact pill that fills its slot in a row, so 3 short ones sit side by side. */
@Composable
private fun ConditionChip(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Press(onClick, modifier, text + if (on) ", selected" else "") {
        val shape = RoundedCornerShape(50)
        Box(Modifier.fillMaxWidth().background(if (on) Dim.accent.copy(alpha = .16f) else Dim.fill, shape)
            .border(1.dp, if (on) Dim.accent else Color.Transparent, shape).padding(horizontal = 6.dp, vertical = 9.dp), Alignment.Center) {
            Txt(text, Type.caption.copy(color = if (on) Dim.accent else Dim.text, fontSize = 14.sp), maxLines = 1)
        }
    }
}

/** One read-only line: the question on the left, the answer on the right in plain text. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Txt(label, Type.body, sub = true, modifier = Modifier.padding(end = 16.dp), maxLines = 1)   // label stays on one line
        Txt(value.ifBlank { "—" }, Type.body.copy(textAlign = TextAlign.End, color = if (value.isBlank()) Dim.sub else Dim.text),
            modifier = Modifier.weight(1f))
    }
}

/** Round photo, or a neutral person glyph when none has been chosen. */
@Composable
fun Avatar(store: ProfileStore, p: Profile, diameter: Dp) {
    val bmp = remember(p.photoRev) { if (p.photoRev > 0) BitmapFactory.decodeFile(store.photoFile().path)?.asImageBitmap() else null }
    if (bmp != null) Image(bmp, "Profile photo", Modifier.size(diameter).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(diameter).background(Dim.fill, CircleShape).semantics { contentDescription = "No profile photo" }, Alignment.Center) {
        val c = Dim.sub
        Canvas(Modifier.size(diameter * .56f)) {
            val w = size.width; val h = size.height
            drawCircle(c, w * .22f, Offset(w / 2, h * .3f))
            drawArc(c, 180f, 180f, true, Offset(w * .08f, h * .62f), Size(w * .84f, h * .84f))
        }
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
                  number: Boolean = false, singleLine: Boolean = false, minLines: Int = 1) {
    Box(modifier.fillMaxWidth().background(Dim.fill, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = Type.body.copy(color = Dim.text),
            cursorBrush = SolidColor(Dim.accent), singleLine = singleLine, minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            decorationBox = { inner -> if (value.isEmpty()) Txt(hint, Type.body, sub = true); inner() })
    }
}

/** Simple on/off switch; no animation (Migraine Mode). */
@Composable
private fun Toggle(on: Boolean, title: String, sub: String, onChange: (Boolean) -> Unit) {
    Press({ onChange(!on) }, Modifier.fillMaxWidth(), title + if (on) ", on" else ", off") {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) { Txt(title, Type.headline.copy(fontSize = 17.sp)); Txt(sub, Type.caption, sub = true) }
            Box(Modifier.width(52.dp).height(32.dp).background(if (on) Dim.accent else Dim.fill, CircleShape).padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(26.dp).background(Color.White, CircleShape)) }
        }
    }
}
