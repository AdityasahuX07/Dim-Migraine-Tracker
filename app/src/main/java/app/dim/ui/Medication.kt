package app.dim.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.data.MedStore
import app.dim.data.RoutineMed
import kotlin.math.roundToInt

/** Time of day as shown on the notes and in the add sheet: 24-hour, e.g. 00:00 or 20:30. */
internal fun fmtTime(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

/** Sticky-note colours: solid pastel cards with dark text and a darker accent bar beside the heading. */
private class NoteLook(val bg: Color, val bar: Color, val text: Color)
private val PreventiveLook = NoteLook(bg = Color(0xFFF0D4D9), bar = Color(0xFFA1513D), text = Color(0xFF441E20))
private val AcuteLook = NoteLook(bg = Color(0xFFF0E2BE), bar = Color(0xFFA5742B), text = Color(0xFF3F2F14))

/**
 * Settings > Medication. Two equal-size sticky notes filling the screen: Preventive (name + time) and Acute (name only).
 * The + button opens a bottom sheet; a time of 00:00 files the medicine under Acute, any other time under Preventive.
 * Saved immediately.
 */
@Composable
fun MedicationScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val store = remember { MedStore(ctx) }
    var preventive by remember { mutableStateOf(store.routine()) }
    var acute by remember { mutableStateOf(store.occasional()) }
    var adding by remember { mutableStateOf(false) }

    // The sheet covers the floating nav pill; Back closes the sheet before it leaves the screen.
    val nav = LocalNavVisibility.current
    DisposableEffect(adding) { if (adding) nav.hide(); onDispose { if (adding) nav.show() } }
    BackHandler(adding) { adding = false }

    fun save(name: String, minutes: Int) {
        val n = MedStore.clean(name)
        if (n.isEmpty()) return
        if (minutes == 0) {
            if (n !in acute) { acute = acute + n; store.saveOccasional(acute) }
        } else {
            val m = RoutineMed(n, minutes)
            if (m !in preventive) { preventive = (preventive + m).sortedBy { it.minutes }; store.saveRoutine(preventive) }
        }
    }

    Box(Modifier.fillMaxSize().background(Dim.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
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
                        Txt("Medication", Type.title)
                    }
                }
                Press({ adding = true }, label = "Add medicine") { PlusButton() }
            }
            Note("Preventive", "Taken on a schedule to reduce how often attacks happen.", PreventiveLook, preventive.map { it.name to fmtTime(it.minutes) }, Modifier.weight(1f)) { i ->
                preventive = preventive.filterIndexed { j, _ -> j != i }; store.saveRoutine(preventive) }
            Note("Acute", "Taken during an attack to relieve pain and symptoms.", AcuteLook, acute.map { it to null }, Modifier.weight(1f)) { i ->
                acute = acute.filterIndexed { j, _ -> j != i }; store.saveOccasional(acute) }
        }

        AnimatedVisibility(visible = adding, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
                .clickable(remember { MutableInteractionSource() }, null) { adding = false })
        }
        AnimatedVisibility(visible = adding, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160))) {
            AddMedicineSheet(onClose = { adding = false }, onSave = { n, m -> save(n, m); adding = false })
        }
    }
}

/**
 * One sticky note, sized by [modifier] (the screen gives both notes equal height). A small accent bar and the heading
 * sit top-left, the medicines are a plain list under it. A long list scrolls inside its note.
 */
@Composable
private fun Note(title: String, hint: String, look: NoteLook, items: List<Pair<String, String?>>, modifier: Modifier = Modifier, onRemove: (Int) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().background(look.bg, RoundedCornerShape(28.dp)).padding(start = 22.dp, end = 8.dp, top = 22.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp, 24.dp).background(look.bar, RoundedCornerShape(50)))
                Txt(title, Type.headline.copy(color = look.text, fontSize = 22.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(start = 10.dp), maxLines = 1)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 8.dp)) {
                items.forEachIndexed { i, (name, time) -> NoteLine(name, time, look) { onRemove(i) } }
            }
        }
        // Tip sits below the card, not inside it.
        Txt(hint, Type.caption, sub = true, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    }
}

@Composable
private fun NoteLine(name: String, time: String?, look: NoteLook, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(name, Type.body.copy(color = look.text, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f).padding(end = 10.dp))
        if (time != null) Txt(time, Type.caption.copy(color = look.text.copy(alpha = .6f), fontSize = 14.sp, fontFeatureSettings = "tnum"), maxLines = 1)
        Press(onRemove, Modifier.size(32.dp), "Remove $name") {
            val c = look.text.copy(alpha = .45f)
            Canvas(Modifier.align(Alignment.Center).size(10.dp)) {
                val w = size.width; val h = size.height; val sw = 1.8.dp.toPx()
                drawLine(c, Offset(0f, 0f), Offset(w, h), sw, StrokeCap.Round)
                drawLine(c, Offset(w, 0f), Offset(0f, h), sw, StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Add medicine sheet
// ---------------------------------------------------------------------------------------------------------------

/** Bottom sheet: type a name, tap the time to pick one (00:00 = Acute), OK. Drag down, tap outside or ✕ to dismiss. */
@Composable
private fun AddMedicineSheet(onClose: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(0) }
    var drag by remember { mutableStateOf(0f) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val ok = MedStore.clean(name).isNotEmpty()
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() { if (ok) { focusManager.clearFocus(); onSave(name, minutes) } }
    // Same system time picker the log flow uses. OK in the picker saves straight away when a name is already typed;
    // with no name yet it only sets the time.
    fun pickTime() {
        focusManager.clearFocus()
        TimePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, m ->
            minutes = h * 60 + m
            if (MedStore.clean(name).isNotEmpty()) onSave(name, minutes)
        },
            minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }

    Column(Modifier.fillMaxWidth()
        .offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }
        .imePadding()
        .background(Dim.card, shape).border(0.5.dp, Dim.line, shape)
        .clickable(remember { MutableInteractionSource() }, null) { }   // taps on the sheet must not reach the scrim
        .navigationBarsPadding()) {
        // Handle + header: dragging here pulls the sheet down.
        Column(Modifier.fillMaxWidth().pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { if (drag > 120.dp.toPx()) { onClose() } else { drag = 0f } },
                onDragCancel = { drag = 0f }) { _, dy -> drag += dy }
        }) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).background(Dim.sub.copy(alpha = .45f), CircleShape)) }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Add medicine", Type.title.copy(fontSize = 26.sp), modifier = Modifier.weight(1f), maxLines = 1)
                Press({ focusManager.clearFocus(); onClose() }, Modifier.size(44.dp), "Close") { CloseIcon(Modifier.align(Alignment.Center)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        NameField(name, { name = it }, focus, { submit() }, Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("Time", Type.caption, sub = true)
            Spacer(Modifier.height(8.dp))
            Press({ pickTime() }, label = "Set time, ${fmtTime(minutes)}") {
                Box(Modifier.background(Dim.fill, RoundedCornerShape(20.dp)).padding(horizontal = 30.dp, vertical = 10.dp)) {
                    Txt(fmtTime(minutes), Type.display.copy(fontSize = 52.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
                        fontFeatureSettings = "tnum", color = if (minutes == 0) Dim.sub else Dim.text))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), Arrangement.End) {
            Press({ submit() }, label = "OK") {
                val fg = if (ok) Dim.onAccent else Dim.sub
                Row(Modifier.background(Brush.horizontalGradient(if (ok) listOf(Dim.accent2, Dim.accent) else listOf(Dim.fill, Dim.fill)), RoundedCornerShape(50))
                    .padding(start = 14.dp, end = 26.dp, top = 13.dp, bottom = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckIcon(fg)
                    Txt("OK", Type.headline.copy(color = fg))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Dim.fill, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 15.dp)) {
        BasicTextField(value, { onChange(it.take(40)) }, Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = Type.body.copy(color = Dim.text), cursorBrush = SolidColor(Dim.accent), singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = { inner -> if (value.isEmpty()) Txt("Medicine name", Type.body, sub = true); inner() })
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Small drawn icons
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun BackArrow(modifier: Modifier = Modifier) {
    val c = Dim.text
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val sw = 2.4.dp.toPx()
        drawLine(c, Offset(w * .08f, h * .5f), Offset(w * .92f, h * .5f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * .08f, h * .5f), Offset(w * .46f, h * .12f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * .08f, h * .5f), Offset(w * .46f, h * .88f), sw, StrokeCap.Round)
    }
}

@Composable
private fun PlusButton() {
    val c = Dim.onAccent
    Box(Modifier.size(40.dp).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), CircleShape), Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height; val sw = 2.4.dp.toPx()
            drawLine(c, Offset(w / 2, 0f), Offset(w / 2, h), sw, StrokeCap.Round)
            drawLine(c, Offset(0f, h / 2), Offset(w, h / 2), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun CloseIcon(modifier: Modifier = Modifier) {
    val c = Dim.text
    Canvas(modifier.size(30.dp)) {
        val w = size.width; val h = size.height; val sw = 1.8.dp.toPx()
        drawCircle(c, w / 2 - sw / 2, style = Stroke(sw))
        drawLine(c, Offset(w * .34f, h * .34f), Offset(w * .66f, h * .66f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * .66f, h * .34f), Offset(w * .34f, h * .66f), sw, StrokeCap.Round)
    }
}

@Composable
private fun CheckIcon(c: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val sw = 1.8.dp.toPx()
        drawCircle(c, w / 2 - sw / 2, style = Stroke(sw))
        drawLine(c, Offset(w * .3f, h * .52f), Offset(w * .44f, h * .66f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * .44f, h * .66f), Offset(w * .71f, h * .36f), sw, StrokeCap.Round)
    }
}
