package app.dim.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dim.data.ai.AiAnalysis
import app.dim.data.ai.AiChatMessage
import app.dim.data.ai.AiState
import app.dim.data.ai.AiStatus
import kotlinx.coroutines.delay

private val SparkBlue = Color(0xFFA8C7FA)
private val InkText = Color.White
private val InkDim = Color(0xFFA6A6A6)
private val InkFill = Color(0xFF1C1C1C)
private val InkSurface = Color(0xFF141414)
private val InkLine = Color(0xFF2E2E2E)

@Composable
private fun InkTxt(s: String, style: TextStyle = Type.body, modifier: Modifier = Modifier, sub: Boolean = false) =
    Txt(s, style.copy(color = if (style.color != Color.Unspecified) style.color else if (sub) InkDim else InkText), modifier = modifier)

private fun clockTime(t: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(t))

/** "**bold**" -> bold span, "* item" -> "• item". Everything else is shown as plain text. */
private fun richText(text: String, bold: Color): AnnotatedString {
    val cleaned = text.replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
    return buildAnnotatedString {
        var isBold = false
        cleaned.split("**").forEach { part ->
            if (isBold) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = bold)) { append(part) } else append(part)
            isBold = !isBold
        }
    }
}

@Composable
private fun RichTxt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    bold: Color = InkText
) {
    BasicText(
        text = richText(text, bold),
        modifier = modifier,
        style = style.copy(color = if (style.color != Color.Unspecified) style.color else InkText),
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = maxLines
    )
}

private fun headlineOf(a: AiAnalysis) = a.headline.ifBlank { "Your latest insight" }

// ───────────────────────── Home preview ─────────────────────────

/**
 * Flat insight preview on Home: sparkle + time, headline, a few lines of the summary and a Reply pill.
 * Tapping the preview opens the fullscreen insight; Reply opens it with the follow-up box ready.
 */
@Composable
fun AiInsightsCard(
    state: AiState,
    onConfigure: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    val analysis = state.analysis
    val hasAnalysis = analysis != null && state.status != AiStatus.DISABLED && state.status != AiStatus.NO_KEY

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                when {
                    hasAnalysis -> onOpen()
                    state.status == AiStatus.DISABLED || state.status == AiStatus.NO_KEY -> onConfigure()
                    state.status == AiStatus.LOADING -> Unit
                    else -> onRefresh()
                }
            }
    ) {
        if (hasAnalysis && analysis != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GeminiSparkle(26.dp)
                InkTxt(clockTime(analysis.generatedAt), Type.caption.copy(fontSize = 17.sp, color = InkDim))
                if (state.status == AiStatus.LOADING) LoadingPulseDot(SparkBlue)
            }
            Spacer(Modifier.height(8.dp))
            RichTxt(
                headlineOf(analysis),
                Type.title.copy(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f, fill = false).clipToBounds()) {
                RichTxt(
                    analysis.summary,
                    Type.body.copy(fontSize = 16.sp, lineHeight = 23.sp, color = InkText.copy(alpha = 0.74f)),
                    maxLines = 14
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ReplyPill(onReply)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GeminiSparkle(26.dp)
                InkTxt("AI Insights", Type.caption.copy(fontSize = 16.sp, color = InkDim))
                if (state.status == AiStatus.LOADING) LoadingPulseDot(SparkBlue)
            }
            Spacer(Modifier.height(10.dp))
            val (msg, hint) = when (state.status) {
                AiStatus.DISABLED -> "AI-powered migraine insights are turned off. Enable them in Settings to analyze patterns." to "Tap to configure in Settings →"
                AiStatus.NO_KEY -> "Set up your Gemini API key to receive evidence-based pattern detection and precautions." to "Configure Gemini in Settings →"
                AiStatus.LOADING -> "Analyzing migraine history and health metrics with Gemini…" to null
                AiStatus.ERROR -> (state.errorMessage ?: "Unable to refresh AI insights.") to "Tap to retry • Configure in Settings"
                else -> "Tap to generate insights from your logged data." to null
            }
            InkTxt(msg, Type.caption.copy(color = InkDim, lineHeight = 18.sp))
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                InkTxt(hint, Type.caption.copy(color = InkText.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun ReplyPill(onClick: () -> Unit) = Press(onClick, label = "Reply") {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(44.dp).border(1.dp, InkDim.copy(alpha = 0.55f), shape).padding(horizontal = 20.dp),
        Arrangement.spacedBy(10.dp),
        Alignment.CenterVertically
    ) {
        ReplyIcon(InkText)
        InkTxt("Reply", Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium))
    }
}

// ───────────────────────── Fullscreen insight + follow-up chat ─────────────────────────

/**
 * Fullscreen insight. Shows the complete analysis and, via Reply, a chat box to cross-question it.
 * [startInReply] opens with the input focused (used when Reply is tapped on Home).
 */
@Composable
fun AiInsightScreen(
    state: AiState,
    chat: List<AiChatMessage>,
    sending: Boolean,
    startInReply: Boolean,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit
) {
    var replying by remember { mutableStateOf(startInReply) }
    var draft by remember { mutableStateOf("") }
    val composing = replying || chat.isNotEmpty()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val analysis = state.analysis

    LaunchedEffect(replying) {
        if (replying) {
            delay(320) // let the open animation finish before the keyboard comes up
            try { focus.requestFocus() } catch (_: Exception) { }
        }
    }
    // Keep the newest message / typing indicator in view.
    LaunchedEffect(chat.size, sending) {
        if (chat.isNotEmpty() || sending) listState.animateScrollToItem(chat.size + if (sending) 1 else 0)
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || sending) return
        replying = true
        onSend(q)
        draft = ""
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar: sparkle + time on the left, Refresh and Close on the right (same height)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (analysis != null) {
                    GeminiSparkle(28.dp)
                    Spacer(Modifier.width(10.dp))
                    InkTxt(clockTime(analysis.generatedAt), Type.caption.copy(fontSize = 17.sp, color = InkDim))
                }
                Spacer(Modifier.weight(1f))
                val busy = state.status == AiStatus.LOADING || state.isRefreshing
                Press({ if (!busy) onRefresh() }, label = "Refresh insight") {
                    Row(
                        Modifier.height(40.dp).alpha(if (busy) 0.6f else 1f).background(InkFill, RoundedCornerShape(50)).padding(horizontal = 16.dp),
                        Arrangement.spacedBy(8.dp), Alignment.CenterVertically
                    ) {
                        if (busy) LoadingPulseDot(SparkBlue)
                        InkTxt(if (busy) "Updating" else "Refresh", Type.caption.copy(color = InkText, fontSize = 14.sp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Press(onClose, label = "Close") {
                    Box(Modifier.size(40.dp).background(InkFill, CircleShape), contentAlignment = Alignment.Center) {
                        val xColor = InkText
                        Canvas(Modifier.size(14.dp)) {
                            val sw = 2.dp.toPx()
                            drawLine(xColor, Offset(0f, 0f), Offset(size.width, size.height), sw, StrokeCap.Round)
                            drawLine(xColor, Offset(size.width, 0f), Offset(0f, size.height), sw, StrokeCap.Round)
                        }
                    }
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "insight") {
                    if (analysis != null) InsightBody(analysis)
                    else InkTxt("No insight yet. Tap Refresh to analyze your logged data.", Type.body.copy(color = InkDim))
                }
                items(chat, key = { it.id }) { m ->
                    ChatBubble(m, isLast = m === chat.last() && !sending, onRetry = onRetry)
                }
                if (sending) item(key = "thinking") { ThinkingRow() }
            }

            // Bottom: Reply pill, or the follow-up input
            if (!composing) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                    ReplyPill { replying = true }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    if (chat.isEmpty() && analysis != null) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Why this pattern?", "What should I avoid today?", "How can I lower my risk?").forEach { s ->
                                SuggestChip(s) { send(s) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp).background(InkFill, RoundedCornerShape(26.dp))
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                textStyle = Type.body.copy(fontSize = 16.sp, color = InkText),
                                cursorBrush = SolidColor(Color.White),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send(draft) }),
                                decorationBox = { inner ->
                                    if (draft.isEmpty()) InkTxt("Ask a follow-up question…", Type.body.copy(fontSize = 16.sp), sub = true)
                                    inner()
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val canSend = draft.isNotBlank() && !sending
                        Press({ send(draft) }, label = "Send") {
                            Box(
                                Modifier.size(48.dp).alpha(if (canSend) 1f else 0.4f)
                                    .background(Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val arrowColor = Color.Black
                                Canvas(Modifier.size(18.dp)) {
                                    val sw = 2.2.dp.toPx(); val c = arrowColor
                                    drawLine(c, Offset(size.width / 2, size.height), Offset(size.width / 2, 0f), sw, StrokeCap.Round)
                                    drawLine(c, Offset(size.width / 2, 0f), Offset(0f, size.height * 0.5f), sw, StrokeCap.Round)
                                    drawLine(c, Offset(size.width / 2, 0f), Offset(size.width, size.height * 0.5f), sw, StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightBody(a: AiAnalysis) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RichTxt(
            headlineOf(a),
            Type.title.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            maxLines = 1
        )
        RichTxt(a.summary, Type.body.copy(fontSize = 17.sp, lineHeight = 25.sp, color = InkText.copy(alpha = 0.78f)))

        if (a.reliefSteps.isNotEmpty()) {
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.fillMaxWidth().background(InkSurface, shape).border(1.dp, InkLine, shape).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader("WHEN AN EPISODE STARTS")
                a.reliefSteps.forEachIndexed { i, step -> StepItem(i + 1, step) }
            }
        }
        if (a.precautions.isNotEmpty()) {
            SectionHeader("HOW TO PREVENT IT")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                a.precautions.forEach { BulletItem(it, InkDim) }
            }
        }
        if (a.triggerPatterns.isNotEmpty()) {
            SectionHeader("TRIGGERS TO REDUCE")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                a.triggerPatterns.forEach { BulletItem(it, InkDim) }
            }
        }
        if (a.warningBulletPoints.isNotEmpty()) {
            SectionHeader("EARLY WARNING SIGNS")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                a.warningBulletPoints.forEach { BulletItem(it, InkDim) }
            }
        }
        InkTxt(
            "Based on ${a.episodesAnalyzed} episodes" +
                (if (a.healthDaysAnalyzed > 0) " & ${a.healthDaysAnalyzed} health days" else "") +
                ". AI insights aren't medical advice.",
            Type.caption.copy(fontSize = 12.sp, color = InkDim, lineHeight = 17.sp)
        )
    }
}

@Composable
private fun StepItem(n: Int, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            InkTxt("$n", Type.label.copy(color = InkText, fontSize = 12.sp, letterSpacing = 0.sp))
        }
        RichTxt(text, Type.caption.copy(fontSize = 16.sp, lineHeight = 23.sp, color = InkText), Modifier.weight(1f).padding(top = 1.dp))
    }
}

@Composable
private fun ChatBubble(m: AiChatMessage, isLast: Boolean, onRetry: () -> Unit) {
    when {
        m.fromUser -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .background(Color(0xFF262626), RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp)
            ) { InkTxt(m.text, Type.body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = InkText)) }
        }
        m.isError -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InkTxt(m.text, Type.caption.copy(fontSize = 14.sp, lineHeight = 20.sp, color = DangerRed))
            if (isLast) Press(onRetry, label = "Retry") {
                InkTxt("Tap to retry", Type.caption.copy(color = InkText.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold))
            }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 3.dp)) { GeminiSparkle(20.dp) }
            RichTxt(m.text, Type.body.copy(fontSize = 16.sp, lineHeight = 25.sp, color = InkText.copy(alpha = 0.85f)), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThinkingRow() {
    val t = rememberInfiniteTransition(label = "think")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(Modifier.alpha(a), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GeminiSparkle(20.dp)
        InkTxt("Thinking…", Type.caption.copy(fontSize = 15.sp, color = InkDim))
    }
}

// ───────────────────────── Small pieces ─────────────────────────

@Composable
private fun SuggestChip(text: String, onClick: () -> Unit) = Press(onClick, label = text) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.border(1.dp, InkLine, shape).background(InkFill, shape).padding(14.dp, 9.dp)) {
        InkTxt(text, Type.caption.copy(color = InkText, fontSize = 15.sp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    InkTxt(title, Type.label.copy(color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp))
}

@Composable
private fun BulletItem(text: String, bulletColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).background(bulletColor, CircleShape))
        RichTxt(text, Type.caption.copy(fontSize = 16.sp, lineHeight = 23.sp, color = InkText), Modifier.weight(1f))
    }
}

/** Concave four-point star centred on [c] with radius [r]. */
private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val k = r * 0.14f
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticBezierTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticBezierTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticBezierTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticBezierTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(p, color)
}

/** Sparkles logo (large diamond, small diamond, four-point star), drawn from the supplied SVG geometry. */
@Composable
private fun GeminiSparkle(size: Dp) {
    Canvas(Modifier.size(size)) {
        val sc = this.size.width / 56f
        fun pt(x: Float, y: Float) = Offset((x - 15.7f) * sc, (y - 14.4f) * sc)

        val star = Path().apply {
            val p0 = pt(58.246f, 15.698f)
            moveTo(p0.x, p0.y)
            quadraticBezierTo(pt(60.766f, 25.048f).x, pt(60.766f, 25.048f).y, pt(70.336f, 27.798f).x, pt(70.336f, 27.798f).y)
            quadraticBezierTo(pt(60.766f, 30.548f).x, pt(60.766f, 30.548f).y, pt(58.246f, 39.898f).x, pt(58.246f, 39.898f).y)
            quadraticBezierTo(pt(55.726f, 30.548f).x, pt(55.726f, 30.548f).y, pt(46.156f, 27.798f).x, pt(46.156f, 27.798f).y)
            quadraticBezierTo(pt(55.726f, 25.048f).x, pt(55.726f, 25.048f).y, p0.x, p0.y)
            close()
        }
        fun diamond(cx: Float, cy: Float, r: Float) = Path().apply {
            val t = pt(cx, cy - r); val rt = pt(cx + r, cy); val b = pt(cx, cy + r); val l = pt(cx - r, cy)
            moveTo(t.x, t.y); lineTo(rt.x, rt.y); lineTo(b.x, b.y); lineTo(l.x, l.y); close()
        }
        drawPath(star, SparkBlue)
        drawPath(diamond(32f, 46.515f, 15.0f), SparkBlue)
        drawPath(diamond(52f, 61.544f, 7.507f), SparkBlue)
    }
}

/** Speech bubble with a small sparkle in its corner. */
@Composable
private fun ReplyIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val bubble = Path().apply {
            moveTo(w * 0.52f, h * 0.10f)
            lineTo(w * 0.16f, h * 0.10f)
            quadraticBezierTo(w * 0.05f, h * 0.10f, w * 0.05f, h * 0.22f)
            lineTo(w * 0.05f, h * 0.95f)
            lineTo(w * 0.25f, h * 0.76f)
            lineTo(w * 0.80f, h * 0.76f)
            quadraticBezierTo(w * 0.93f, h * 0.76f, w * 0.93f, h * 0.64f)
            lineTo(w * 0.93f, h * 0.56f)
        }
        drawPath(bubble, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawStar(Offset(w * 0.80f, h * 0.22f), w * 0.21f, color)
    }
}

@Composable
private fun LoadingPulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "alpha"
    )
    Box(Modifier.size(8.dp).alpha(alpha).background(color, CircleShape))
}
