package app.dim.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.DimApp
import app.dim.data.ai.AiStatus

@Composable
fun AiInsightsSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val ai = remember { (ctx.applicationContext as DimApp).aiInsights }
    val state by ai.state.collectAsState()

    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var keySavedNotice by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dim.bg)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Back Button
        Press(onBack, label = "Back") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val accent = Dim.accent
                Canvas(Modifier.size(24.dp)) {
                    val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
                    drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .88f, h * .5f), sw, StrokeCap.Round)
                    drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .46f, h * .16f), sw, StrokeCap.Round)
                    drawLine(accent, Offset(w * .12f, h * .5f), Offset(w * .46f, h * .84f), sw, StrokeCap.Round)
                }
                Txt("AI integration", Type.title)
            }
        }


        // Feature Toggle Card
        Txt("ENABLEMENT", Type.label, sub = true)
        Card {
            Row(
                Modifier.fillMaxWidth(),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Txt("AI integration", Type.headline.copy(fontSize = 17.sp))
                    Txt(
                        if (state.enabled) "Enabled • Analyzing logged patterns" else "Disabled • Tap toggle to turn on",
                        Type.caption,
                        sub = true
                    )
                }
                Press(
                    onClick = { ai.setEnabled(!state.enabled) },
                    modifier = Modifier.size(52.dp, 32.dp),
                    label = "Toggle AI Insights"
                ) {
                    Box(
                        Modifier
                            .width(52.dp)
                            .height(32.dp)
                            .background(if (state.enabled) Dim.accent else Dim.fill, CircleShape)
                            .padding(3.dp),
                        contentAlignment = if (state.enabled) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(Modifier.size(26.dp).background(Color.White, CircleShape))
                    }
                }
            }
        }

        // Gemini API Key Card
        Txt("GEMINI API CONFIGURATION", Type.label, sub = true)
        Card {
            Txt("Gemini API Key", Type.headline)
            Spacer(Modifier.height(4.dp))
            Txt(
                if (state.hasKey) "Saved and encrypted on this device."
                else "Paste your key from Google AI Studio.",
                Type.caption,
                sub = true
            )
            Spacer(Modifier.height(12.dp))

            // Key Input Box
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Dim.fill, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it
                            keySavedNotice = false
                        },
                        modifier = Modifier.weight(1f),
                        textStyle = Type.body.copy(color = Dim.text),
                        cursorBrush = SolidColor(Dim.accent),
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        decorationBox = { inner ->
                            if (keyInput.isEmpty()) {
                                Txt(if (state.hasKey) "••••••••••••••••••••••••••••••••" else "Enter API Key", Type.body, sub = true)
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Press(
                        onClick = { showKey = !showKey },
                        label = if (showKey) "Hide key" else "Show key"
                    ) {
                        Txt(if (showKey) "Hide" else "Show", Type.caption.copy(color = Dim.accent, fontWeight = FontWeight.SemiBold))
                    }
                }
            }

            if (keySavedNotice) {
                Spacer(Modifier.height(6.dp))
                Txt("API Key saved securely.", Type.caption.copy(color = Color(0xFF6E9484)))
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AiActionButton(
                    text = "Save key",
                    enabled = keyInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    ai.saveApiKey(keyInput)
                    keyInput = ""
                    keySavedNotice = true
                }

                if (state.hasKey) {
                    AiActionButton(
                        text = "Clear key",
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        ai.clearApiKey()
                        keySavedNotice = false
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Press(
                onClick = { openAiStudio(ctx) },
                label = "Get Gemini API Key"
            ) {
                Txt(
                    "Get a free Gemini API key at ai.google.dev ↗",
                    Type.caption.copy(color = Dim.accent, fontWeight = FontWeight.SemiBold)
                )
            }
        }

        // Analysis & Refresh Section
        Txt("ANALYSIS STATUS", Type.label, sub = true)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val dot = when (state.status) {
                    AiStatus.READY -> Color(0xFF6E9484)
                    AiStatus.ERROR -> Color(0xFFFF8080)
                    AiStatus.LOADING -> Dim.accent
                    else -> Dim.sub
                }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Txt(
                    when (state.status) {
                        AiStatus.LOADING -> "Analyzing…"
                        AiStatus.READY -> "Up to date"
                        AiStatus.INSUFFICIENT_DATA -> "Insufficient data"
                        AiStatus.ERROR -> "Analysis error"
                        AiStatus.NO_KEY -> "API key required"
                        AiStatus.DISABLED -> "Insights disabled"
                    },
                    Type.headline
                )
            }

            Spacer(Modifier.height(6.dp))

            state.lastGeneratedAt?.let {
                Txt("Last updated: ${dateTimeStamp(it)}", Type.caption, sub = true)
            }

            state.analysis?.let {
                Txt(
                    "Episodes analyzed: ${it.episodesAnalyzed}" +
                        if (it.healthDaysAnalyzed > 0) " • Health days: ${it.healthDaysAnalyzed}" else "",
                    Type.caption,
                    sub = true
                )
            }

            state.selectedModel?.let { modelName ->
                val clean = modelName.removePrefix("models/")
                Txt("Model: $clean", Type.caption, sub = true)
            }

            // Error display if failed
            state.errorMessage?.let { err ->
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A151D), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Txt(
                        "Error: $err",
                        Type.caption.copy(color = Color(0xFFFF8080)),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }

            // Loading indicator
            if (state.isRefreshing) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsLoadingPulse(Dim.accent)
                    Txt("Generating insights with Gemini API…", Type.caption.copy(color = Dim.sub))
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AiActionButton(
                    text = if (state.isRefreshing) "Refreshing…" else "Refresh",
                    enabled = state.enabled && state.hasKey && !state.isRefreshing,
                    modifier = Modifier.weight(1f)
                ) { ai.refreshAnalysis() }
                if (state.analysis != null) {
                    AiActionButton(
                        text = "Clear cache",
                        enabled = !state.isRefreshing,
                        modifier = Modifier.weight(1f)
                    ) { ai.clearAnalysis() }
                }
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun AiActionButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Press(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth(),
        label = text
    ) {
        val bgBrush = if (enabled) Brush.horizontalGradient(listOf(Dim.accent2, Dim.accent))
            else Brush.horizontalGradient(listOf(Dim.fill, Dim.fill))

        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(bgBrush, RoundedCornerShape(50)),
            Alignment.Center
        ) {
            Txt(
                text,
                Type.headline.copy(
                    fontSize = 16.sp,
                    color = if (enabled) Dim.onAccent else Dim.sub
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SettingsLoadingPulse(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

private fun openAiStudio(ctx: Context) {
    val uri = "https://aistudio.google.com/app/apikey"
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
