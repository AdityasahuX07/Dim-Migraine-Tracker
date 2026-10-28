package app.dim.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import java.time.temporal.ChronoUnit
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.window.Dialog
import app.dim.data.Episode
import app.dim.data.ProfileStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun time(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
private fun full(t: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))
private fun day(t: Long) = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(t))
private fun dur(a: Long, b: Long) = TimeUnit.MILLISECONDS.toMinutes(b - a).let { "${it / 60}h ${it % 60}m" }
private fun localDay(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()

private val HeroSky = Color(0xFF8F76CC)
private val HeroBack = Color(0xFF352872)
private val HeroFrontTop = Color(0xFF5E4AA3)
private val HeroFrontBottom = Color(0xFF4F3C95)
internal val HomeBlack = Color(0xFF0F0F0F)

/** Layered cloud hero, traced from the reference at 853 x 755 and scaled to the canvas. */
private fun DrawScope.drawHero() {
    drawRect(HeroSky)
    val k = size.width / 853f   // clouds keep their shape, anchored to the bottom; extra height is plain sky
    withTransform({ translate(0f, size.height - 755f * k); scale(k, k, Offset.Zero) }) {
        val backLeft = Path().apply {
            moveTo(0f, 755f); lineTo(0f, 420f)
            cubicTo(18f, 418f, 35f, 414f, 47f, 412f)
            cubicTo(52f, 340f, 105f, 243f, 200f, 243f)
            cubicTo(250f, 243f, 285f, 258f, 305f, 275f)
            lineTo(305f, 755f); close()
        }
        val backRight = Path().apply {
            moveTo(500f, 500f); lineTo(500f, 400f); lineTo(538f, 291f)
            cubicTo(565f, 289f, 595f, 292f, 612f, 298f)
            cubicTo(650f, 262f, 700f, 232f, 770f, 226f)
            cubicTo(810f, 222f, 840f, 235f, 853f, 246f)
            lineTo(853f, 500f); close()
        }
        val front = Path().apply {
            moveTo(0f, 755f); lineTo(0f, 660f)
            cubicTo(0f, 540f, 70f, 465f, 186f, 464f)
            cubicTo(188f, 350f, 250f, 243f, 390f, 243f)
            cubicTo(450f, 243f, 535f, 280f, 595f, 340f)
            cubicTo(640f, 322f, 700f, 322f, 760f, 355f)
            cubicTo(800f, 378f, 835f, 410f, 853f, 440f)
            lineTo(853f, 755f); close()
        }
        drawPath(backLeft, HeroBack)
        drawPath(backRight, HeroBack)
        drawPath(front, Brush.verticalGradient(listOf(HeroFrontTop, HeroFrontBottom), 243f, 755f))
    }
}

@Composable
fun Home(active: Episode?, eps: List<Episode>, onLog: () -> Unit, onOpen: () -> Unit, greeting: String,
         hideCard: Boolean = false, onEnd: () -> Unit = {}, onOpenAiSettings: () -> Unit = {},
         onOpenAi: (reply: Boolean) -> Unit = {}, onCardBounds: (Rect) -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as app.dim.DimApp
    val aiState by app.aiInsights.state.collectAsState()

    // Background runs full-bleed behind the floating nav pill; the layout itself stays above it.
    Column(Modifier.fillMaxSize().background(HomeBlack).navigationBarsPadding().padding(bottom = NavBarClearance)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.50f)) {
            Canvas(Modifier.fillMaxSize()) { drawHero() }
            Txt(greeting, Type.title.copy(fontSize = 26.sp, lineHeight = 32.sp, color = Dim.onAccent),
                modifier = Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 40.dp))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FreeDays(eps)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
            AiInsightsCard(
                modifier = Modifier.weight(1f),
                state = aiState,
                onConfigure = onOpenAiSettings,
                onRefresh = { app.aiInsights.refreshAnalysis() },
                onOpen = { onOpenAi(false) },
                onReply = { onOpenAi(true) }
            )
            Spacer(Modifier.height(16.dp))
            if (active == null) {
                Press(onLog, Modifier.align(Alignment.CenterHorizontally), "Log migraine") {
                    val on = Dim.onAccent
                    Row(Modifier.height(54.dp).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), RoundedCornerShape(50)).padding(horizontal = 28.dp),
                        Arrangement.Center, Alignment.CenterVertically) {
                        Canvas(Modifier.size(18.dp)) {
                            drawLine(on, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.5.dp.toPx(), StrokeCap.Round)
                            drawLine(on, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(), StrokeCap.Round)
                        }
                        Spacer(Modifier.width(10.dp)); Txt("Migraine", Type.headline.copy(color = on, fontSize = 18.sp))
                    }
                }
            } else OngoingCard(active, onOpen, onEnd, Modifier.graphicsLayer { alpha = if (hideCard) 0f else 1f }
                .onGloballyPositioned { onCardBounds(it.boundsInRoot()) })
        }
    }
}

@Composable
private fun FreeDays(eps: List<Episode>) {
    val last = eps.maxOfOrNull { it.endedAt ?: it.startedAt }
    val days = last?.let { ChronoUnit.DAYS.between(localDay(it), LocalDate.now()).toInt().coerceAtLeast(0) }
    val soft = Dim.onAccent.copy(alpha = .85f)
    val big = if (days != null && days >= 100) 84.sp else 112.sp
    Txt("MIGRAINE-FREE", Type.label.copy(color = soft, fontSize = 13.sp, letterSpacing = 1.2.sp))
    Txt(days?.toString() ?: "–", Type.display.copy(fontSize = big, lineHeight = big * .86f, fontWeight = FontWeight.Black,
        letterSpacing = (-2).sp, color = Color.White))
    Txt(if (days == 1) "DAY" else "DAYS", Type.label.copy(color = soft, fontSize = 13.sp, letterSpacing = 1.2.sp))
}

/** Small round-cornered button used in the History header and its selection bar. Dimmed and inert when [enabled] is false. */
@Composable
private fun HeaderPill(text: String, color: Color, bg: Color, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Press({ if (enabled) onClick() }, label = label) {
        Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else .4f }.background(bg, RoundedCornerShape(50)).padding(14.dp, 10.dp)) {
            Txt(text, Type.caption.copy(color = color, fontSize = 15.sp), maxLines = 1) } }
}

/**
 * Title block and actions. The number of entries sits in a small pill to the right of "History" (no label). Actions are
 * "+ Add"; while selecting (started by a long press on an entry) it becomes Cancel and Delete. While selecting, a bar below holds
 * Select all / Deselect all and how many are selected.
 */
@Composable
private fun HistoryHeader(count: Int, selecting: Boolean, selectedCount: Int,
                          onCancel: () -> Unit, onAdd: () -> Unit, onDelete: () -> Unit, onToggleAll: () -> Unit) {
    val allSelected = count > 0 && selectedCount == count
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).padding(end = 8.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                Txt("History", Type.title, maxLines = 1)
                Box(Modifier.background(Dim.fill, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)
                    .semantics { contentDescription = if (count == 1) "1 episode" else "$count episodes" }) {
                    Txt("$count", Type.label.copy(letterSpacing = 0.sp), sub = true) }
            }
            AnimatedContent(selecting, label = "historyActions",
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) }) { sel ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sel) {
                        HeaderPill("Cancel", Dim.accent, Dim.fill, "Cancel selection", onClick = onCancel)
                        HeaderPill("Delete", DangerRed, DangerRed.copy(alpha = .16f),
                            if (selectedCount == 0) "Delete, nothing selected" else "Delete $selectedCount selected",
                            enabled = selectedCount > 0, onClick = onDelete)
                    } else {
                        HeaderPill("+ Add", Dim.accent, Dim.fill, "Add a past episode", onClick = onAdd)
                    }
                }
            }
        }
        AnimatedVisibility(selecting) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                HeaderPill(if (allSelected) "Deselect all" else "Select all", Dim.accent, Dim.fill,
                    if (allSelected) "Deselect all episodes" else "Select all episodes", onClick = onToggleAll)
                Txt(if (selectedCount == 0) "Tap episodes to select" else "$selectedCount selected", Type.caption, sub = true)
            }
        }
    }
}

/** Round tick box shown at the start of each card while selecting. */
@Composable
private fun SelectDot(on: Boolean) {
    val tick = Dim.onAccent
    Box(Modifier.size(24.dp).background(if (on) Dim.accent else Color.Transparent, CircleShape)
        .border(1.5.dp, if (on) Dim.accent else Dim.sub, CircleShape), Alignment.Center) {
        if (on) Canvas(Modifier.size(12.dp)) {
            drawLine(tick, Offset(0f, size.height * .55f), Offset(size.width * .4f, size.height * .95f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(tick, Offset(size.width * .4f, size.height * .95f), Offset(size.width, size.height * .1f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Asks before deleting several episodes at once (this can't be undone). */
@Composable
private fun ConfirmDelete(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(26.dp)
        Column(Modifier.fillMaxWidth().background(Dim.card, shape).border(0.5.dp, Dim.line, shape).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(if (count == 1) "Delete 1 episode?" else "Delete $count episodes?", Type.headline)
            Txt("This can't be undone.", Type.body.copy(fontSize = 15.sp), sub = true)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                Press(onDismiss, Modifier.weight(1f), "Cancel") {
                    Box(Modifier.fillMaxWidth().height(50.dp).background(Dim.fill, RoundedCornerShape(50)), Alignment.Center) {
                        Txt("Cancel", Type.headline.copy(fontSize = 17.sp)) } }
                Press(onConfirm, Modifier.weight(1f), "Delete $count episodes") {
                    Box(Modifier.fillMaxWidth().height(50.dp).background(DangerRed.copy(alpha = .16f), RoundedCornerShape(50)), Alignment.Center) {
                        Txt("Delete", Type.headline.copy(color = DangerRed, fontSize = 17.sp)) } }
            }
        }
    }
}

/**
 * History list. Tap opens an entry; a long press on an entry starts selecting: tap entries to tick them,
 * Select all / Deselect all, and Delete removes the ticked ones after a confirmation. Back or Cancel leaves selecting.
 * The header stays pinned so those buttons are always reachable.
 */
@Composable
fun HistoryScreen(eps: List<Episode>, onEdit: (Long) -> Unit, onDelete: (List<Episode>) -> Unit, onAdd: () -> Unit) {
    val year = LocalDate.now().year
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    var confirming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(setOf<Long>()) }   // entries playing their delete animation; removed from the database once it ends
    val scope = rememberCoroutineScope()
    val chosen = remember(picked, eps) { picked.intersect(eps.map { it.id }.toSet()) }   // an entry that no longer exists is ignored
    val haptic = LocalHapticFeedback.current
    fun leaveSelecting() { selecting = false; picked = emptySet(); confirming = false }
    BackHandler(selecting) { leaveSelecting() }

    if (eps.isEmpty()) {
        // Nothing to scroll: header on top, the empty state centred in the space between it and the nav pill.
        Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
            HistoryHeader(0, false, 0, {}, onAdd, {}, {})
            Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(bottom = NavBarClearance), Alignment.Center) { HistoryEmpty() }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
            HistoryHeader(eps.size, selecting, chosen.size,
                onCancel = { leaveSelecting() },
                onAdd = onAdd,
                onDelete = { if (chosen.isNotEmpty()) confirming = true },
                onToggleAll = { picked = if (chosen.size == eps.size) emptySet() else eps.map { it.id }.toSet() })
            Spacer(Modifier.height(16.dp))
            // LazyColumn: only the rows on screen exist, so a long history stays smooth (the old Column built every row at once).
            val selectingNow by rememberUpdatedState(selecting)
            val onEditNow by rememberUpdatedState(onEdit)
            val onTap = remember { { id: Long ->
                if (selectingNow) { picked = if (id in picked) picked - id else picked + id } else { onEditNow(id) }
            } }
            val onLong = remember { { id: Long ->
                if (selectingNow) { picked = if (id in picked) picked - id else picked + id }
                else { selecting = true; picked = setOf(id); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(eps, key = { it.id }) { e ->
                    HistoryRow(e, year, selecting, e.id in picked, e.id in removing, onTap, onLong)
                }
                item { Spacer(Modifier.navigationBarsPadding().height(NavBarClearance)) }
            }
        }
        if (confirming) ConfirmDelete(chosen.size,
            onConfirm = {
                val victims = eps.filter { it.id in chosen }
                removing = removing + victims.map { it.id }
                leaveSelecting()
                scope.launch { delay(360); onDelete(victims); removing = removing - victims.map { it.id }.toSet() }
            },
            onDismiss = { confirming = false })
    }
}

/** One History row. Its own composable with plain parameters so Compose skips rows whose inputs did not change. [gone] plays the delete animation (fade + shrink, the 16 dp gap shrinks with it). */
@Composable
private fun HistoryRow(e: Episode, year: Int, selecting: Boolean, isSel: Boolean, gone: Boolean, onTap: (Long) -> Unit, onLong: (Long) -> Unit) {
    val date = remember(e.startedAt, year) {
        SimpleDateFormat(if (localDay(e.startedAt).year == year) "d MMM" else "d MMM yy", Locale.getDefault()).format(Date(e.startedAt)) }
    AnimatedVisibility(!gone, exit = fadeOut(tween(200)) + shrinkVertically(tween(320, easing = FastOutSlowInEasing))) {
        Box(Modifier.padding(bottom = 16.dp)) {
            Press({ onTap(e.id) }, Modifier.fillMaxWidth(),
                "Episode $date" + if (selecting) (if (isSel) ", selected" else ", not selected") else "",
                onLongClick = { onLong(e.id) }) {
                Card {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        AnimatedVisibility(selecting, enter = fadeIn(tween(150)) + expandHorizontally(tween(180)),
                            exit = fadeOut(tween(120)) + shrinkHorizontally(tween(150))) {
                            Row(verticalAlignment = Alignment.CenterVertically) { SelectDot(isSel); Spacer(Modifier.width(14.dp)) } }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Txt(date, Type.headline.copy(fontSize = 17.sp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Txt(time(e.startedAt), Type.body.copy(fontSize = 14.sp), sub = true)
                                Txt("·", Type.body.copy(fontSize = 14.sp), sub = true)
                                if (e.endedAt != null) Txt(dur(e.startedAt, e.endedAt), Type.body.copy(fontSize = 14.sp), sub = true)
                                else Txt("Ongoing", Type.body.copy(color = Dim.accent, fontSize = 14.sp))
                            }
                        }
                        if (e.pain != null) Box(Modifier.background(painColor(e.pain), RoundedCornerShape(50)).padding(12.dp, 4.dp)) {
                            Txt("${e.pain}", Type.caption.copy(color = Color.White, fontSize = 13.sp)) }
                        else Txt("–", Type.body.copy(fontSize = 14.sp), sub = true)
                    }
                }
                // Ticked entries get an accent wash and outline on top of the card (not clickable itself).
                if (isSel) Box(Modifier.matchParentSize().background(Dim.accent.copy(alpha = .14f), RoundedCornerShape(26.dp))
                    .border(1.5.dp, Dim.accent, RoundedCornerShape(26.dp)))
            }
        }
    }
}

@Composable
fun SettingsScreen(initialPage: String? = null, onClearInitialPage: () -> Unit = {}) {
    var page by remember { mutableStateOf<String?>(initialPage) }
    LaunchedEffect(initialPage) {
        if (initialPage != null) {
            page = initialPage
            onClearInitialPage()
        }
    }
    // Sub screens are full pages with their own back arrow, so the nav pill steps aside.
    val nav = LocalNavVisibility.current
    val inSub = page != null
    DisposableEffect(inSub) {
        if (inSub) nav.hide()
        onDispose { if (inSub) nav.show() }
    }
    if (page == "profile") { ProfileScreen { page = null }; return }
    if (page == "meds") { MedicationScreen { page = null }; return }
    if (page == "report") { DoctorReportScreen { page = null }; return }
    if (page == "backup") { BackupRestoreScreen { page = null }; return }
    if (page == "health") { HealthConnectScreen { page = null }; return }
    if (page == "ai") { AiInsightsSettingsScreen { page = null }; return }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as app.dim.DimApp
    val aiState by app.aiInsights.state.collectAsState()
    val store = remember { ProfileStore(ctx) }
    val profile = remember(page) { store.load() }   // reloaded each time we come back from the profile page
    Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt("Settings", Type.title)
        Card(pad = 0.dp) {
            Press({ page = "profile" }, Modifier.fillMaxWidth(), "Profile") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(store, profile, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Txt("Profile", Type.headline)
                        Txt(profile.name.ifBlank { "Personal and migraine information" }, Type.caption, sub = true, maxLines = 1)
                    }
                    val chevron = Dim.sub
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(chevron, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(chevron, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Card(pad = 0.dp) {
            Press({ page = "meds" }, Modifier.fillMaxWidth(), "Medication") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt("Medication", Type.headline)
                        Txt("Preventive and acute", Type.caption, sub = true, maxLines = 1)
                    }
                    val chevron = Dim.sub
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(chevron, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(chevron, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Card(pad = 0.dp) {
            Press({ page = "health" }, Modifier.fillMaxWidth(), "Health Connect") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt("Health Connect", Type.headline)
                        Txt("Sleep, steps and heart rate", Type.caption, sub = true, maxLines = 1)
                    }
                    val chevron = Dim.sub
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(chevron, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(chevron, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Card(pad = 0.dp) {
            Press({ page = "ai" }, Modifier.fillMaxWidth(), "AI integration") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt("AI integration", Type.headline)
                        val aiDesc = when {
                            !aiState.enabled -> "Disabled • Tap to configure"
                            !aiState.hasKey -> "API Key required"
                            aiState.analysis != null -> "Enabled • ${aiState.analysis!!.episodesAnalyzed} episodes analyzed"
                            else -> "Enabled • Gemini API"
                        }
                        Txt(aiDesc, Type.caption, sub = true, maxLines = 1)
                    }
                    val chevron = Dim.sub
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(chevron, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(chevron, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Card(pad = 0.dp) {
            Press({ page = "report" }, Modifier.fillMaxWidth(), "Doctor-Ready Report") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt("Doctor-Ready Report", Type.headline)
                        Txt("Create a PDF summary to share", Type.caption, sub = true, maxLines = 1)
                    }
                    val chevron = Dim.sub
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(chevron, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(chevron, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Card(pad = 0.dp) {
            Press({ page = "backup" }, Modifier.fillMaxWidth(), "Backup and restore") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt("Backup & Restore", Type.headline)
                        Txt("Save or load your data as a file", Type.caption, sub = true, maxLines = 1)
                    }
                    val chevron = Dim.sub
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(chevron, Offset(w * .25f, h * .05f), Offset(w * .75f, h * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(chevron, Offset(w * .75f, h * .5f), Offset(w * .25f, h * .95f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}
