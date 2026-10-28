package app.dim.ui

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.dim.DimApp
import app.dim.data.MedStore
import app.dim.data.ProfileStore
import app.dim.data.health.Availability
import app.dim.data.health.HealthDay
import app.dim.data.report.Report
import app.dim.data.report.ReportBuilder
import app.dim.data.report.ReportOptions
import app.dim.data.report.ReportPdf
import app.dim.data.report.ReportPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ReportStatus {
    data object Idle : ReportStatus
    data class Working(val text: String) : ReportStatus
    data class Done(val text: String) : ReportStatus
    data class Failed(val text: String) : ReportStatus
}

private const val SHARE_DIR = "reports"   // matches res/xml/report_file_paths.xml
private fun shareAuthority(ctx: Context) = ctx.packageName + ".reports"

/**
 * Settings > Doctor-Ready Report. Everything is built on this device from the stored records. Nothing leaves the device until the
 * user taps Save PDF (document picker) or Share PDF (system share sheet).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoctorReportScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DimApp
    val scope = rememberCoroutineScope()
    val episodes by app.repo.episodes.collectAsState(emptyList())
    val today = LocalDate.now()

    var period by rememberSaveable { mutableStateOf(ReportPeriod.LAST_30.name) }
    var fromDay by rememberSaveable { mutableStateOf<Long?>(null) }   // LocalDate.toEpochDay()
    var toDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var includeProfile by rememberSaveable { mutableStateOf(true) }
    var includeMeds by rememberSaveable { mutableStateOf(true) }
    var includeHealth by rememberSaveable { mutableStateOf(false) }   // Health Connect data is opt-in, every time
    var status by remember { mutableStateOf<ReportStatus>(ReportStatus.Idle) }

    val options = ReportOptions(
        period = ReportPeriod.valueOf(period),
        customFrom = fromDay?.let { LocalDate.ofEpochDay(it) }, customTo = toDay?.let { LocalDate.ofEpochDay(it) },
        includeProfile = includeProfile, includeMedications = includeMeds, includeHealth = includeHealth,
    )
    val problem = ReportBuilder.problem(options, today)
    val busy = status is ReportStatus.Working

    // Small inputs for the preview. Read once; the export below re-reads everything so the PDF is never stale.
    val profile = remember { ProfileStore(ctx).load() }
    val meds = remember { MedStore(ctx) }
    val routine = remember { meds.routine() }
    val occasional = remember { meds.occasional() }
    var healthRows by remember { mutableStateOf<List<HealthDay>>(emptyList()) }
    LaunchedEffect(includeHealth) {
        healthRows = if (includeHealth) withContext(Dispatchers.IO) { runCatching { app.repo.healthAll() }.getOrDefault(emptyList()) } else emptyList()
    }
    val hcState by app.health.state.collectAsState()
    val preview = remember(options, episodes, healthRows, profile, routine, occasional) {
        if (problem != null) null
        else runCatching { ReportBuilder.build(episodes, profile, routine, occasional, healthRows, options) }.getOrNull()
    }

    /** Fresh copy of every input, read off the main thread. */
    suspend fun buildNow(o: ReportOptions): Report = withContext(Dispatchers.IO) {
        val health = if (o.includeHealth) app.repo.healthAll() else emptyList()
        ReportBuilder.build(app.repo.snapshot(), ProfileStore(ctx).load(), meds.routine(), meds.occasional(), health, o)
    }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        status = ReportStatus.Working(label)
        scope.launch {
            try { block() }
            catch (e: CancellationException) { status = ReportStatus.Idle; throw e }
            catch (e: Exception) { status = ReportStatus.Failed("The report couldn't be created. Nothing was saved or shared.") }
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) { status = ReportStatus.Idle; return@rememberLauncherForActivityResult }
        val o = options
        run("Creating PDF…") {
            try {
                val report = buildNow(o)
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("Could not open the file")
                    out.use { ReportPdf.write(report, it) }
                }
                status = ReportStatus.Done("PDF saved (${report.episodes.size} ${if (report.episodes.size == 1) "episode" else "episodes"}).")
            } catch (e: Throwable) {
                // Don't leave an empty or half-written file behind in the folder the user picked (also when the screen is left mid-export).
                withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) } }
                throw e
            }
        }
    }

    fun share() {
        val o = options
        run("Creating PDF…") {
            val (file, name) = withContext(Dispatchers.IO) {
                val report = buildNow(o)
                val dir = File(ctx.cacheDir, SHARE_DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }   // temporary files from earlier shares
                val f = File(dir, report.fileName)
                try { f.outputStream().use { ReportPdf.write(report, it) } } catch (e: Exception) { f.delete(); throw e }
                f to report.fileName
            }
            val uri = FileProvider.getUriForFile(ctx, shareAuthority(ctx), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Migraine report")
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { ctx.startActivity(Intent.createChooser(send, "Share report")); status = ReportStatus.Idle }
            catch (e: ActivityNotFoundException) { status = ReportStatus.Failed("No app on this device can receive a PDF. Try Save PDF instead.") }
        }
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
                Txt("Doctor-Ready Report", Type.title.copy(fontSize = 28.sp), maxLines = 1)
            }
        }
        Txt("A clear PDF summary of what you've recorded, to share at an appointment. It's created on this device from your own entries.",
            Type.body.copy(fontSize = 15.sp), sub = true)

        // ── period ──
        Txt("REPORTING PERIOD", Type.label, sub = true)
        Card {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportPeriod.values().forEach { p ->
                    Chip(p.label, p.name == period) {
                        period = p.name
                        if (p == ReportPeriod.CUSTOM && (fromDay == null || toDay == null)) {   // start from the last 30 days so there is always a valid range
                            fromDay = today.minusDays(29).toEpochDay(); toDay = today.toEpochDay() }
                    }
                }
            }
            if (options.period == ReportPeriod.CUSTOM) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField("From", options.customFrom, Modifier.weight(1f)) { fromDay = it.toEpochDay() }
                    DateField("To", options.customTo, Modifier.weight(1f)) { toDay = it.toEpochDay() }
                }
            }
            problem?.let {
                Spacer(Modifier.height(10.dp))
                Txt(it, Type.caption.copy(color = DangerRed, fontSize = 14.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.height(12.dp))
            Txt("Episodes are counted by the day they started, in this device's time zone (${ZoneId.systemDefault().id}).", Type.caption, sub = true)
        }

        // ── contents ──
        Txt("WHAT'S INCLUDED", Type.label, sub = true)
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Txt("Migraine summary and episode history", Type.headline.copy(fontSize = 17.sp))
                    Txt("Always included: overview, reported patterns and every episode in the period.", Type.caption, sub = true)
                }
                ReportToggle(includeProfile, "Patient profile", "Name, age, sex, diagnosis, aura, family history and other profile details.") { includeProfile = it }
                ReportToggle(includeMeds, "Current medication list", "Your routine and occasional medicines, kept separate from medicines logged on episodes.") { includeMeds = it }
                ReportToggle(includeHealth, "Health Connect summary", "Off by default. Average sleep, steps, heart rate, resting heart rate and HRV from data stored on this device.") { includeHealth = it }
            }
            if (includeHealth) {
                Spacer(Modifier.height(12.dp))
                val hs = preview?.health
                val note = when {
                    hcState.availability == Availability.UNAVAILABLE -> "Health Connect isn't available on this device, so the report will say there is no data."
                    hs != null && !hs.hasData -> hs.notes.firstOrNull()
                    hs != null && hs.notes.any { it.startsWith("Limited coverage") || it.startsWith("Metrics marked") } ->
                        "Health Connect data covers only part of this period. The report will flag the limited coverage."
                    else -> null
                }
                note?.let { Txt(it, Type.caption, sub = true) }
            }
        }

        // ── preview ──
        Txt("PREVIEW", Type.label, sub = true)
        Card {
            if (preview == null) {
                Txt("Choose a valid date range to see what the report will contain.", Type.body.copy(fontSize = 15.sp), sub = true)
            } else {
                val o = preview.overview
                Txt(preview.periodLabel, Type.headline.copy(fontSize = 17.sp))
                Spacer(Modifier.height(8.dp))
                if (preview.isEmpty) Txt("No episodes were recorded in this period. You can still create a report; it will say so.", Type.body.copy(fontSize = 15.sp), sub = true)
                else {
                    Txt("${o.totalEpisodes} ${if (o.totalEpisodes == 1) "episode" else "episodes"} on ${o.migraineDays} ${if (o.migraineDays == 1) "day" else "days"}" +
                        if (o.ongoingEpisodes > 0) " (${o.ongoingEpisodes} ongoing)" else "", Type.body.copy(fontSize = 15.sp))
                    Txt("Average pain: " + (o.avgPain?.let { "%.1f / 10".format(Locale.ENGLISH, it) + " (${o.painRecords} recorded)" } ?: "Not recorded"), Type.body.copy(fontSize = 15.sp), sub = true)
                    Txt("Average duration: " + (o.avgDurationMin?.let { ReportBuilder.duration(it) + " (${o.durationRecords} completed)" } ?: "Not recorded"), Type.body.copy(fontSize = 15.sp), sub = true)
                    if (o.totalEpisodes > 150) {   // roughly 5 episodes per page
                        Spacer(Modifier.height(8.dp))
                        Txt("This is a long report (about ${o.totalEpisodes / 5 + 2} pages). A shorter period is usually easier to read at an appointment.", Type.caption, sub = true)
                    }
                }
            }
        }

        Txt("SHARE", Type.label, sub = true)
        Card {
            Txt("The PDF contains sensitive health information. It is created only when you tap one of these buttons, and it goes only where you send it.",
                Type.body.copy(fontSize = 15.sp), sub = true)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReportButton("Save PDF", problem == null && !busy) {
                    save.launch("Dim-migraine-report-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date()) + ".pdf") }
                ReportButton("Share PDF", problem == null && !busy) { share() }
            }
        }

        when (val s = status) {
            ReportStatus.Idle -> {}
            is ReportStatus.Working -> ReportStatusCard(s.text, null) { Spacer(Modifier.height(12.dp)); ReportBar() }
            is ReportStatus.Done -> ReportStatusCard(s.text, Dim.accent) {}
            is ReportStatus.Failed -> ReportStatusCard(s.text, DangerRed) {}
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun DateField(label: String, value: LocalDate?, modifier: Modifier, onPicked: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    Press({
        val d = value ?: LocalDate.now()
        DatePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, day -> onPicked(LocalDate.of(y, m + 1, day)) },
            d.year, d.monthValue - 1, d.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }, modifier, "$label date, " + (value?.let { ReportBuilder.date(it) } ?: "not set")) {
        Column(Modifier.fillMaxWidth().background(Dim.fill, RoundedCornerShape(16.dp)).padding(14.dp, 10.dp)) {
            Txt(label.uppercase(), Type.label, sub = true)
            Txt(value?.let { ReportBuilder.date(it) } ?: "Select", Type.body.copy(fontSize = 16.sp), maxLines = 1)
        }
    }
}

/** Same look as the toggles in Profile. */
@Composable
private fun ReportToggle(on: Boolean, title: String, sub: String, onChange: (Boolean) -> Unit) =
    Press({ onChange(!on) }, Modifier.fillMaxWidth(), title + if (on) ", on" else ", off") {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) { Txt(title, Type.headline.copy(fontSize = 17.sp)); Txt(sub, Type.caption, sub = true) }
            Box(Modifier.width(52.dp).height(32.dp).background(if (on) Dim.accent else Dim.fill, CircleShape).padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(26.dp).background(Color.White, CircleShape)) }
        }
    }

@Composable
private fun ReportButton(text: String, enabled: Boolean, onClick: () -> Unit) =
    Press({ if (enabled) onClick() }, Modifier.fillMaxWidth(), text) {
        Box(Modifier.fillMaxWidth().height(50.dp).background(
            if (enabled) Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)) else Brush.horizontalGradient(listOf(Dim.fill, Dim.fill)),
            RoundedCornerShape(50)), Alignment.Center) {
            Txt(text, Type.headline.copy(color = if (enabled) Dim.onAccent else Dim.sub), maxLines = 1) }
    }

@Composable
private fun ReportStatusCard(text: String, color: Color?, extra: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Txt(text, Type.body.copy(fontSize = 15.sp, color = color ?: Dim.text))
        extra()
    }

/** Indeterminate progress bar; still in Migraine Mode. */
@Composable
private fun ReportBar() {
    val calm = Dim.calm
    val x by rememberInfiniteTransition(label = "bar").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).background(Dim.fill, RoundedCornerShape(50))) {
        val w = maxWidth * .3f
        Box(Modifier.offset(x = if (calm) maxWidth * .35f else (maxWidth - w) * x).width(w).fillMaxHeight().background(Dim.accent, RoundedCornerShape(50)))
    }
}
