package app.dim.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.dim.DimApp
import app.dim.data.backup.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Status {
    data object Idle : Status
    data class Working(val text: String) : Status
    data class Done(val text: String) : Status
    data class Failed(val text: String) : Status
}

/** Settings > Backup & Restore. Everything stays on this device or in the location the user picks; nothing is uploaded. */
@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DimApp
    val manager = remember { BackupManager(ctx, app.repo) }
    val scope = rememberCoroutineScope()
    val active by app.repo.active.collectAsState(null)
    val episodes by app.repo.episodes.collectAsState(emptyList())
    var status by remember { mutableStateOf<Status>(Status.Idle) }
    var lastBackup by remember { mutableStateOf(manager.lastBackupAt()) }
    var pending by remember { mutableStateOf<RestoreContents?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = status is Status.Working

    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        status = Status.Working(label)
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { status = Status.Idle; throw e }
            catch (e: BackupException) { status = Status.Failed(e.message ?: "Something went wrong.") }
            catch (e: Exception) { status = Status.Failed("Something went wrong. Nothing was changed.") }
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run("Creating backup…") {
            val s = manager.createBackup(uri)
            lastBackup = manager.lastBackupAt()
            status = Status.Done("Backup saved: ${s.episodes} ${if (s.episodes == 1) "episode" else "episodes"}" +
                (if (s.hasProfile) ", medicines and profile" else "") + ".")
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run("Checking backup…") { pending = manager.inspect(uri); status = Status.Idle }
    }

    Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Press(onBack, label = "Back") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val accent = Dim.accent
                Canvas(Modifier.size(24.dp)) {
                    val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
                    drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .88f, h * .5f), sw, StrokeCap.Round)
                    drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .46f, h * .16f), sw, StrokeCap.Round)
                    drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .46f, h * .84f), sw, StrokeCap.Round)
                }
                Txt("Backup & Restore", Type.title)
            }
        }

        Txt("WHAT'S INCLUDED", Type.label, sub = true)
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "All episodes: times, duration, pain, stress, symptoms, triggers, medication, location, aura, hydration, meals, caffeine, where it started",
                    "Your medicine lists",
                    "Your profile and photo"
                ).forEach { Bullet(it) }
            }
            Spacer(Modifier.height(10.dp))
            Txt("An episode that is running right now is not included.", Type.caption, sub = true)
        }

        Txt("BACKUP", Type.label, sub = true)
        Card {
            Txt("Save a copy of your data to a file. You choose where it goes.", Type.body.copy(fontSize = 15.sp), sub = true)
            Spacer(Modifier.height(6.dp))
            Txt("Last backup: " + (lastBackup?.let { dateTimeStamp(it) } ?: "never"), Type.caption, sub = true)
            Spacer(Modifier.height(14.dp))
            Button("Create Backup", !busy) {
                create.launch("dim-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date()) + ".zip") }
        }

        Txt("RESTORE", Type.label, sub = true)
        Card {
            Txt("Load a backup file. You'll choose Merge or Replace before anything changes.", Type.body.copy(fontSize = 15.sp), sub = true)
            Spacer(Modifier.height(14.dp))
            Button("Restore Backup", !busy) { open.launch(arrayOf("*/*")) }
        }

        when (val s = status) {
            Status.Idle -> {}
            is Status.Working -> StatusCard(s.text, null) { Spacer(Modifier.height(12.dp)); Bar(); Spacer(Modifier.height(12.dp))
                Press({ job?.cancel() }, label = "Cancel") { Txt("Cancel", Type.body.copy(color = Dim.accent, fontSize = 15.sp), modifier = Modifier.padding(4.dp)) } }
            is Status.Done -> StatusCard(s.text, Dim.accent) {}
            is Status.Failed -> StatusCard(s.text, DangerRed) {}
        }
        Spacer(Modifier.navigationBarsPadding())
    }

    pending?.let { c ->
        RestoreDialog(c, running = active != null, localCount = episodes.count { it.endedAt != null }, onDismiss = { pending = null }) { mode ->
            pending = null
            run("Restoring…") {
                val r = manager.restore(c, mode)
                status = Status.Done(
                    (if (r.replaced) "Replaced your data with the backup: " else "Merged: ") +
                    "${r.inserted} ${if (r.inserted == 1) "episode" else "episodes"} added" +
                    (if (r.duplicatesSkipped > 0) ", ${r.duplicatesSkipped} already here" else "") + "." +
                    (if (r.ongoingKept) " Your running episode was not changed." else ""))
            }
        }
    }
}

@Composable
private fun RestoreDialog(c: RestoreContents, running: Boolean, localCount: Int, onDismiss: () -> Unit, onRestore: (RestoreMode) -> Unit) {
    val s = c.summary
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var confirming by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(26.dp)
        Column(Modifier.fillMaxWidth().background(Dim.card, shape).border(0.5.dp, Dim.line, shape).padding(22.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!confirming) {
                Txt("Restore this backup?", Type.headline)
                Txt("Created " + (if (s.createdAt > 0) dateTimeStamp(s.createdAt) else "at an unknown time"), Type.caption, sub = true)
                val lines = buildList {
                    add("${s.episodes} ${if (s.episodes == 1) "episode" else "episodes"}" +
                        if (s.firstEpisode != null && s.lastEpisode != null) ", ${dateStamp(s.firstEpisode)} to ${dateStamp(s.lastEpisode)}" else "")
                    add("${s.routineMeds} routine and ${s.occasionalMeds} occasional medicines")
                    add(if (s.hasProfile) "Profile" + if (s.hasPhoto) " and photo" else "" else "No profile")
                }
                lines.forEach { Txt(it, Type.body.copy(fontSize = 15.sp)) }
                s.warnings.forEach { Txt(it, Type.caption.copy(color = DangerRed)) }
                if (running) Txt("A migraine is being tracked right now. Restoring will not change it.", Type.caption, sub = true)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Merge", mode == RestoreMode.MERGE) { mode = RestoreMode.MERGE }
                    Chip("Replace", mode == RestoreMode.REPLACE) { mode = RestoreMode.REPLACE }
                }
                Txt(if (mode == RestoreMode.MERGE) "Adds what's missing and keeps everything you already have. Restoring again won't create duplicates."
                    else "Deletes the data on this device and uses the backup instead.", Type.caption, sub = true)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    Press(onDismiss, Modifier.weight(1f), "Cancel") {
                        Box(Modifier.fillMaxWidth().height(50.dp).background(Dim.fill, RoundedCornerShape(50)), Alignment.Center) {
                            Txt("Cancel", Type.headline.copy(fontSize = 17.sp)) } }
                    Press({ if (mode == RestoreMode.MERGE) onRestore(mode) else confirming = true }, Modifier.weight(1f), "Continue") {
                        Box(Modifier.fillMaxWidth().height(50.dp).background(Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)), RoundedCornerShape(50)), Alignment.Center) {
                            Txt(if (mode == RestoreMode.MERGE) "Merge" else "Continue", Type.headline.copy(color = Dim.onAccent, fontSize = 17.sp)) } }
                }
            } else {
                Txt("Replace all data?", Type.headline)
                Txt("$localCount ${if (localCount == 1) "episode" else "episodes"} on this device, your medicine lists and your profile will be deleted and replaced " +
                    "with the backup (${s.episodes} ${if (s.episodes == 1) "episode" else "episodes"}). This can't be undone.", Type.body.copy(fontSize = 15.sp), sub = true)
                if (s.invalidRecords > 0) Txt("${s.invalidRecords} unreadable ${if (s.invalidRecords == 1) "record" else "records"} in the backup will be lost.", Type.caption.copy(color = DangerRed))
                if (running) Txt("Your running migraine is kept.", Type.caption, sub = true)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    Press({ confirming = false }, Modifier.weight(1f), "Back") {
                        Box(Modifier.fillMaxWidth().height(50.dp).background(Dim.fill, RoundedCornerShape(50)), Alignment.Center) {
                            Txt("Back", Type.headline.copy(fontSize = 17.sp)) } }
                    Press({ onRestore(RestoreMode.REPLACE) }, Modifier.weight(1f), "Replace all data") {
                        Box(Modifier.fillMaxWidth().height(50.dp).background(DangerRed.copy(alpha = .16f), RoundedCornerShape(50)), Alignment.Center) {
                            Txt("Replace", Type.headline.copy(color = DangerRed, fontSize = 17.sp)) } }
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).background(Dim.accent, androidx.compose.foundation.shape.CircleShape))
        Txt(text, Type.body.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusCard(text: String, color: androidx.compose.ui.graphics.Color?, extra: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Txt(text, Type.body.copy(fontSize = 15.sp, color = color ?: Dim.text))
        extra()
    }
}

/** Indeterminate progress bar; still in Migraine Mode. */
@Composable
private fun Bar() {
    val calm = Dim.calm
    val x by rememberInfiniteTransition(label = "bar").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).background(Dim.fill, RoundedCornerShape(50))) {
        val w = maxWidth * .3f
        Box(Modifier.offset(x = if (calm) maxWidth * .35f else (maxWidth - w) * x).width(w).fillMaxHeight().background(Dim.accent, RoundedCornerShape(50)))
    }
}

@Composable
private fun Button(text: String, enabled: Boolean, onClick: () -> Unit) {
    Press({ if (enabled) onClick() }, Modifier.fillMaxWidth(), text) {
        Box(Modifier.fillMaxWidth().height(50.dp).background(
            if (enabled) Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)) else Brush.horizontalGradient(listOf(Dim.fill, Dim.fill)),
            RoundedCornerShape(50)), Alignment.Center) {
            Txt(text, Type.headline.copy(color = if (enabled) Dim.onAccent else Dim.sub)) }
    }
}
