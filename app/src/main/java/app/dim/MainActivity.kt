package app.dim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.lifecycleScope
import app.dim.data.Episode
import app.dim.data.typicalDurationMillis
import app.dim.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openOngoing by mutableIntStateOf(0)   // bumped when the notification is tapped
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied: app works, notification just may not show */ }

    override fun onStart() {
        super.onStart()
        (application as DimApp).health.onForeground()   // no-op unless the user connected Health Connect
        (application as DimApp).aiInsights.checkAndRefreshIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == OngoingNotification.ACTION_OPEN) openOngoing++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as DimApp).repo
        if (intent?.action == OngoingNotification.ACTION_OPEN && savedInstanceState == null) openOngoing++
        // Launcher shortcut: log immediately, then show the app.
        if (intent?.action == "app.dim.LOG" && savedInstanceState == null) lifecycleScope.launch { repo.toggle() }
        setContent {
            var editing by remember { mutableStateOf<Episode?>(null) }   // full editor (from History)
            var logging by remember { mutableStateOf<Episode?>(null) }   // 3-page flow
            var tab by remember { mutableStateOf(0) } // 0 Home, 1 History, 2 Insights, 3 Settings
            var settingsSubPage by remember { mutableStateOf<String?>(null) }
            var aiOpen by remember { mutableStateOf(false) }       // fullscreen AI insight
            var aiReply by remember { mutableStateOf(false) }      // ...opened via Reply (input focused)
            val greeting = rememberSaveable { Greetings.pick(this@MainActivity) }
            val active by repo.active.collectAsState(null)
            var minimized by remember { mutableStateOf(false) }          // ongoing screen collapsed to the card on Home
            var lastActive by remember { mutableStateOf<Episode?>(null) } // keeps the screen drawn while it animates out
            val all by repo.episodes.collectAsState(emptyList())
            val edit: (Long) -> Unit = { id -> lifecycleScope.launch { editing = repo.get(id) } }
            val details: (Long) -> Unit = { id -> lifecycleScope.launch { logging = repo.get(id) } }
            // One tap starts the episode immediately, then the optional 3-page flow opens; tapping again ends it.
            val onLog: () -> Unit = { lifecycleScope.launch {
                if (active == null) { repo.toggle(); logging = repo.activeNow() } else repo.toggle() } }
            LaunchedEffect(active?.id) { if (active == null) minimized = false else lastActive = active }
            // Notification tap: drop any editor/flow/tab and show the full ongoing screen.
            LaunchedEffect(openOngoing) { if (openOngoing > 0) { editing = null; logging = null; tab = 0; minimized = false } }
            LaunchedEffect(active?.id) {
                if (active != null && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            DimTheme {
                val e = editing; val l = logging
                val navHidden = LocalNavVisibility.current.hidden   // a screen's bottom sheet asks the pill to step aside
                val full = e == null && l == null && tab == 0 && active != null && !minimized
                val homeVisible = e == null && l == null && tab == 0 && active != null
                // 0 = collapsed onto the Home card, 1 = full screen; drives the expand/collapse morph.
                val p by animateFloatAsState(if (full) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "ongoing")
                var cardBounds by remember { mutableStateOf(Rect.Zero) }
                val overlay = p > 0f || full
                BackHandler(e != null) { editing = null }
                BackHandler(full) { minimized = true }
                BackHandler(aiOpen) { aiOpen = false }
                Box(Modifier.fillMaxSize().background(Dim.bg)) {
                    // Content fills the screen and the nav pill floats over it, so scrolling screens run behind the pill.
                    Box(Modifier.fillMaxSize()) {
                        when {
                            l != null -> LogFlow(l, { lifecycleScope.launch { repo.save(it) } }, { logging = null })
                            e != null -> Detail(e, { lifecycleScope.launch { repo.save(it) } }, { lifecycleScope.launch { repo.delete(it) } }, { editing = null })
                            else -> when (tab) {
                                0 -> Home(active, all, onLog, { minimized = false }, greeting, overlay,
                                    { lifecycleScope.launch { repo.toggle() } },
                                    onOpenAiSettings = { tab = 3; settingsSubPage = "ai" },
                                    onOpenAi = { reply -> aiReply = reply; aiOpen = true }) { if (it != cardBounds) cardBounds = it }
                                1 -> HistoryScreen(all, edit, { list -> lifecycleScope.launch { list.forEach { repo.delete(it) } } }) {
                                    // Past entry: not saved until Save. Ends now and starts one typical episode length earlier
                                    // (average duration of past episodes, 1 hour if there are none); adjusted on the first page.
                                    val end = System.currentTimeMillis() / 60000 * 60000
                                    logging = Episode(startedAt = end - typicalDurationMillis(all, end), endedAt = end)
                                }
                                2 -> Insights(all)
                                else -> SettingsScreen(initialPage = settingsSubPage, onClearInitialPage = { settingsSubPage = null })
                            }
                        }
                        if (e == null && l == null && !navHidden) NavBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
                    }
                    // Ongoing migraine: morphs between the Home card and the full screen.
                    if (overlay) (active ?: lastActive)?.let { a ->
                        OngoingScreen(a, all, p, cardBounds, homeVisible && !cardBounds.isEmpty,
                            { minimized = true }, { details(a.id) }, { lifecycleScope.launch { repo.toggle() } })
                    }
                    // Fullscreen AI insight (covers the nav bar); Reply opens the follow-up chat.
                    val ai = (application as DimApp).aiInsights
                    val aiState by ai.state.collectAsState()
                    val aiChat by ai.chat.collectAsState()
                    val aiSending by ai.chatSending.collectAsState()
                    AnimatedVisibility(
                        visible = aiOpen,
                        enter = fadeIn(tween(220)) + slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 10 },
                        exit = fadeOut(tween(180)) + slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 10 }
                    ) {
                        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { }) {
                            AiInsightScreen(
                                state = aiState,
                                chat = aiChat,
                                sending = aiSending,
                                startInReply = aiReply,
                                onClose = { aiOpen = false },
                                onRefresh = { ai.refreshAnalysis() },
                                onSend = { ai.askFollowUp(it) },
                                onRetry = { ai.retryFollowUp() }
                            )
                        }
                    }
                }
            }
        }
    }
}
