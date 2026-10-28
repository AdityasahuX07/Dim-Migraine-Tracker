package app.dim.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import app.dim.DimApp
import app.dim.data.health.Availability
import app.dim.data.health.HealthMetric

/** Also shown by Health Connect's "privacy policy" link (see HealthRationaleActivity). */
internal const val HEALTH_PRIVACY_TEXT =
    "Dim can read sleep, steps, heart rate, resting heart rate, and heart rate variability (HRV) from Health Connect. It only reads; it never writes to or " +
    "changes anything in Health Connect.\n\n" +
    "The data is used to show your health patterns in Insights, and to explore potential associations on days with and " +
    "without a migraine. Only daily totals and averages are copied to this device. They stay on this device, are not shared, and " +
    "are not included in Dim backups, unless you explicitly export or share a Doctor-Ready Report that includes them. Health Connect data " +
    "is left out of reports by default, and nothing is exported until you tap Save PDF or Share PDF.\n\n" +
    "You choose what Dim can read, and you can change it at any time in Health Connect. Disconnecting in Dim deletes the copy."

private const val PROVIDER = "com.google.android.apps.healthdata"
private fun metricName(m: HealthMetric) = when (m) {
    HealthMetric.SLEEP -> "Sleep"
    HealthMetric.STEPS -> "Steps"
    HealthMetric.HEART_RATE -> "Heart rate"
    HealthMetric.RESTING_HEART_RATE -> "Resting heart rate"
    HealthMetric.HEART_RATE_VARIABILITY -> "Heart rate variability (HRV)"
}

/** Settings > Health Connect. Nothing is requested or read until the user taps Connect. */
@Composable
fun HealthConnectScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val health = remember { (ctx.applicationContext as DimApp).health }
    val st by health.state.collectAsState()
    LaunchedEffect(Unit) { health.requestRefresh() }   // availability and permissions only; reads no health data
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { health.requestPermissionResult() }

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
                Txt("Health Connect", Type.title)
            }
        }

        when (st.availability) {
            Availability.UNAVAILABLE -> Card {
                Txt("Health Connect isn't available on this device, so there is nothing to connect. Everything else in Dim works as usual.", Type.body.copy(fontSize = 15.sp))
            }
            Availability.UPDATE_REQUIRED -> Card {
                Txt("Health Connect needs an update before Dim can use it.", Type.body.copy(fontSize = 15.sp))
                Spacer(Modifier.height(14.dp))
                HcButton("Update Health Connect", true) { openStore(ctx) }
            }
            Availability.UNKNOWN -> Unit
            Availability.AVAILABLE -> {
                Txt("STATUS", Type.label, sub = true)
                Card {
                    val lost = st.enabled && st.granted.isEmpty()
                    Txt(when { st.connected -> "Connected"; lost -> "Access was removed"; else -> "Not connected" }, Type.headline)
                    Spacer(Modifier.height(8.dp))
                    HealthMetric.values().forEach { m ->
                        Txt(metricName(m) + ": " + if (m in st.granted) "allowed" else "not allowed", Type.body.copy(fontSize = 15.sp), sub = m !in st.granted)
                    }
                    Txt("Past history (>30 days): " + if (st.historyGranted) "allowed" else "not allowed", Type.body.copy(fontSize = 15.sp), sub = !st.historyGranted)
                    if (st.connected) {
                        Spacer(Modifier.height(8.dp))
                        Txt("Last synced: " + (st.lastSyncAt?.let { dateTimeStamp(it) } ?: "not yet"), Type.caption, sub = true)
                    }
                    st.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Txt(it, Type.body.copy(fontSize = 15.sp, color = Dim.text), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (st.permissionDenied) {
                        Spacer(Modifier.height(10.dp))
                        Txt("Nothing was allowed. If no permission screen appeared, allow Dim in Health Connect's settings instead.", Type.body.copy(fontSize = 15.sp), sub = true)
                    }
                    Spacer(Modifier.height(14.dp))
                    val hasMissing = st.granted.size < HealthMetric.values().size || !st.historyGranted
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!st.connected || hasMissing)
                            HcButton(if (st.connected) "Allow the rest" else "Connect Health Connect", !st.syncing) {
                                request.launch(if (st.connected) health.missingPermissions() else health.permissions)
                            }
                        if (st.connected) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HcButton(if (st.syncing) "Syncing…" else "Sync now", !st.syncing, Modifier.weight(1f)) { health.requestSync() }
                                HcButton("Open settings", true, Modifier.weight(1f)) { openHealthConnect(ctx) }
                            }
                        } else if (st.permissionDenied || lost) {
                            HcButton("Open Health Connect", true) { openHealthConnect(ctx) }
                        }
                    }
                }
                if (st.enabled) {
                    Txt("DISCONNECT", Type.label, sub = true)
                    Card {
                        Txt("Stops syncing and deletes the sleep, steps and heart-rate data Dim copied to this device. Your migraine entries are not affected. " +
                            "To also take away Dim's access, open Health Connect.", Type.body.copy(fontSize = 15.sp), sub = true)
                        Spacer(Modifier.height(14.dp))
                        HcButton("Disconnect and delete copy", !st.syncing) { health.requestDisconnect() }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun HcButton(text: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Press({ if (enabled) onClick() }, modifier, text) {
        Box(Modifier.fillMaxWidth().height(50.dp).background(
            if (enabled) Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent)) else Brush.horizontalGradient(listOf(Dim.fill, Dim.fill)),
            RoundedCornerShape(50)), Alignment.Center) {
            Txt(text, Type.headline.copy(color = if (enabled) Dim.onAccent else Dim.sub), maxLines = 1) }
    }
}

private fun openHealthConnect(ctx: Context) {
    try { ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } catch (_: ActivityNotFoundException) { openStore(ctx) }
}

private fun openStore(ctx: Context) {
    for (uri in listOf("market://details?id=$PROVIDER", "https://play.google.com/store/apps/details?id=$PROVIDER")) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) { }
    }
}
