package app.dim.data.ai

import android.content.Context
import app.dim.data.ProfileStore
import app.dim.data.Repo
import app.dim.data.health.HealthSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates AI-powered migraine insights:
 * - Coordinates secure key storage and caching.
 * - Enforces minimum-data safety checks.
 * - Builds prompts from recorded episodes, symptoms, triggers, and Health Connect metrics.
 * - Dispatches generation requests to Gemini and manages reactive UI state.
 */
class AiInsightsManager(
    private val store: AiSecureStore,
    private val geminiClient: GeminiClient = GeminiClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val episodeProvider: suspend () -> List<app.dim.data.Episode>,
    private val healthDayProvider: suspend () -> List<app.dim.data.health.HealthDay>,
    private val profileProvider: () -> app.dim.data.Profile,
    private val medsProvider: () -> MedLists = { MedLists() }
) {
    constructor(
        context: Context,
        repo: Repo,
        health: HealthSync,
        profileStore: ProfileStore = ProfileStore(context),
        store: AiSecureStore = AiSecureStore(context),
        geminiClient: GeminiClient = GeminiClient(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        store = store,
        geminiClient = geminiClient,
        scope = scope,
        ioDispatcher = ioDispatcher,
        episodeProvider = { repo.snapshot() },
        healthDayProvider = { repo.healthAll() },
        profileProvider = { profileStore.load() },
        medsProvider = { app.dim.data.MedStore(context).let { MedLists(it.routine(), it.occasional()) } }
    )
    private val _state = MutableStateFlow(computeInitialState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    private val _chat = MutableStateFlow<List<AiChatMessage>>(emptyList())
    /** Follow-up conversation about the current insight (kept for the app session). */
    val chat: StateFlow<List<AiChatMessage>> = _chat.asStateFlow()

    private val _chatSending = MutableStateFlow(false)
    val chatSending: StateFlow<Boolean> = _chatSending.asStateFlow()

    private fun computeInitialState(): AiState {
        val enabled = store.isEnabled
        val hasKey = store.hasApiKey()
        val cached = store.getCachedAnalysis()
        val lastErr = store.getLastError()

        val status = when {
            !enabled -> AiStatus.DISABLED
            !hasKey -> AiStatus.NO_KEY
            cached != null -> if (!cached.hasEnoughData) AiStatus.INSUFFICIENT_DATA else AiStatus.READY
            lastErr != null -> AiStatus.ERROR
            else -> AiStatus.INSUFFICIENT_DATA
        }

        return AiState(
            status = status,
            analysis = cached,
            errorMessage = lastErr,
            enabled = enabled,
            hasKey = hasKey,
            lastGeneratedAt = cached?.generatedAt ?: store.getLastAnalyzedAt(),
            isRefreshing = false,
            selectedModel = cached?.modelUsed?.ifBlank { null } ?: store.selectedModel
        )
    }

    fun isEnabled(): Boolean = store.isEnabled

    fun setEnabled(enabled: Boolean) {
        store.isEnabled = enabled
        val hasKey = store.hasApiKey()
        val cached = store.getCachedAnalysis()

        val status = when {
            !enabled -> AiStatus.DISABLED
            !hasKey -> AiStatus.NO_KEY
            cached != null -> if (!cached.hasEnoughData) AiStatus.INSUFFICIENT_DATA else AiStatus.READY
            else -> AiStatus.INSUFFICIENT_DATA
        }

        _state.value = _state.value.copy(
            enabled = enabled,
            hasKey = hasKey,
            status = status
        )

        if (enabled && hasKey && cached == null) {
            refreshAnalysis()
        }
    }

    fun saveApiKey(apiKey: String) {
        store.saveApiKey(apiKey)
        val hasKey = store.hasApiKey()
        val enabled = store.isEnabled
        val cached = store.getCachedAnalysis()

        val status = when {
            !enabled -> AiStatus.DISABLED
            !hasKey -> AiStatus.NO_KEY
            cached != null -> if (!cached.hasEnoughData) AiStatus.INSUFFICIENT_DATA else AiStatus.READY
            else -> AiStatus.INSUFFICIENT_DATA
        }

        _state.value = _state.value.copy(
            hasKey = hasKey,
            status = status,
            errorMessage = null
        )

        if (enabled && hasKey) {
            refreshAnalysis()
        }
    }

    fun getApiKey(): String = store.getApiKey()

    fun hasApiKey(): Boolean = store.hasApiKey()

    fun clearApiKey() {
        store.clearApiKey()
        store.selectedModel = null
        _state.value = _state.value.copy(
            hasKey = false,
            status = if (store.isEnabled) AiStatus.NO_KEY else AiStatus.DISABLED,
            selectedModel = null
        )
    }

    /**
     * Checks if new data was recorded since the last analysis, and triggers a background refresh
     * only when appropriate (refresh condition met).
     */
    fun checkAndRefreshIfNeeded() {
        if (!store.isEnabled || !store.hasApiKey() || _state.value.isRefreshing) return

        scope.launch(ioDispatcher) {
            val episodes = episodeProvider()
            val healthDays = healthDayProvider()
            val profile = profileProvider()

            val currentHash = AiPromptBuilder.computeDataFingerprint(episodes, healthDays, profile, medsProvider())
            val lastHash = store.getLastAnalyzedHash()

            if (store.getCachedAnalysis() == null || currentHash != lastHash) {
                performAnalysis(episodes, healthDays, profile, currentHash)
            }
        }
    }

    /**
     * Explicitly refreshes the analysis from Gemini API.
     */
    fun refreshAnalysis() {
        if (!store.isEnabled || !store.hasApiKey()) return
        if (_state.value.isRefreshing) return

        scope.launch(ioDispatcher) {
            val episodes = episodeProvider()
            val healthDays = healthDayProvider()
            val profile = profileProvider()
            val hash = AiPromptBuilder.computeDataFingerprint(episodes, healthDays, profile, medsProvider())
            performAnalysis(episodes, healthDays, profile, hash)
        }
    }

    private suspend fun performAnalysis(
        episodes: List<app.dim.data.Episode>,
        healthDays: List<app.dim.data.health.HealthDay>,
        profile: app.dim.data.Profile,
        dataHash: String
    ) {
        val apiKey = store.getApiKey()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                status = AiStatus.NO_KEY,
                isRefreshing = false
            )
            return
        }

        _state.value = _state.value.copy(
            status = AiStatus.LOADING,
            isRefreshing = true,
            errorMessage = null
        )

        val finished = episodes.filter { it.endedAt != null }

        // Gracefully handle insufficient historical data without calling API unnecessarily
        if (finished.size < 2) {
            val emptyAnalysis = AiAnalysis(
                headline = "Log a few more episodes",
                summary = "More migraine records are needed before personalized patterns can be identified.",
                detailedExplanation = "AI-Powered Insights analyzes trends across your logged attacks, triggers, and sleep data. Log at least two completed migraine episodes with details to unlock evidence-supported insights.",
                triggerPatterns = emptyList(),
                precautions = listOf(
                    "Continue recording attacks with onset times, pain levels, and known triggers.",
                    "Log sleep and hydration habits to help identify personal correlations."
                ),
                warningBulletPoints = listOf(
                    "Track early warning symptoms (like neck stiffness or light sensitivity) during your next attack."
                ),
                hasEnoughData = false,
                generatedAt = System.currentTimeMillis(),
                episodesAnalyzed = finished.size,
                healthDaysAnalyzed = healthDays.size
            )

            store.saveAnalysis(emptyAnalysis, dataHash)
            _state.value = _state.value.copy(
                status = AiStatus.INSUFFICIENT_DATA,
                analysis = emptyAnalysis,
                errorMessage = null,
                isRefreshing = false,
                lastGeneratedAt = emptyAnalysis.generatedAt
            )
            return
        }

        try {
            val prompt = AiPromptBuilder.buildPrompt(episodes, healthDays, profile, medsProvider())
            val analysis = geminiClient.generateAnalysis(
                apiKey = apiKey,
                prompt = prompt,
                episodesAnalyzed = finished.size,
                healthDaysAnalyzed = healthDays.size,
                preferredModel = store.selectedModel,
                onModelChosen = { chosen ->
                    store.selectedModel = chosen
                }
            )

            store.saveAnalysis(analysis, dataHash)
            _chat.value = emptyList()
            _state.value = _state.value.copy(
                status = if (analysis.hasEnoughData) AiStatus.READY else AiStatus.INSUFFICIENT_DATA,
                analysis = analysis,
                errorMessage = null,
                isRefreshing = false,
                lastGeneratedAt = analysis.generatedAt,
                selectedModel = analysis.modelUsed.ifBlank { store.selectedModel }
            )
        } catch (e: GeminiException) {
            store.saveLastError(e.message)
            _state.value = _state.value.copy(
                status = if (_state.value.analysis != null) AiStatus.READY else AiStatus.ERROR,
                errorMessage = e.message,
                isRefreshing = false
            )
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Unexpected error during AI analysis."
            store.saveLastError(msg)
            _state.value = _state.value.copy(
                status = if (_state.value.analysis != null) AiStatus.READY else AiStatus.ERROR,
                errorMessage = msg,
                isRefreshing = false
            )
        }
    }

    /**
     * Cross-question the current insight. Appends the question, asks Gemini with the full recorded
     * data + insight as context, then appends the reply (or an error bubble the user can retry).
     */
    fun askFollowUp(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _chatSending.value) return
        val apiKey = store.getApiKey()
        if (!store.isEnabled || apiKey.isBlank()) {
            _chat.value = _chat.value + AiChatMessage(false, "Set up your Gemini API key in Settings to ask follow-up questions.", isError = true)
            return
        }

        _chat.value = _chat.value.filter { !it.isError } + AiChatMessage(true, q)
        _chatSending.value = true
        val history = _chat.value

        scope.launch(ioDispatcher) {
            try {
                val episodes = episodeProvider()
                val healthDays = healthDayProvider()
                val profile = profileProvider()
                val system = AiPromptBuilder.buildChatSystemPrompt(episodes, healthDays, profile, _state.value.analysis, medsProvider())
                val reply = geminiClient.generateChatReply(
                    apiKey = apiKey,
                    systemPrompt = system,
                    history = history,
                    preferredModel = store.selectedModel,
                    onModelChosen = { store.selectedModel = it }
                )
                _chat.value = _chat.value + AiChatMessage(false, reply)
            } catch (e: GeminiException) {
                _chat.value = _chat.value + AiChatMessage(false, e.message ?: "Couldn't get a reply.", isError = true)
            } catch (e: Exception) {
                _chat.value = _chat.value + AiChatMessage(false, e.localizedMessage ?: "Couldn't get a reply.", isError = true)
            } finally {
                _chatSending.value = false
            }
        }
    }

    /** Re-sends the last user question after a failed reply. */
    fun retryFollowUp() {
        val lastQ = _chat.value.lastOrNull { it.fromUser }?.text ?: return
        if (_chatSending.value) return
        _chat.value = _chat.value.dropLastWhile { !it.fromUser }.dropLast(1)
        askFollowUp(lastQ)
    }

    fun clearChat() { _chat.value = emptyList() }

    fun clearAnalysis() {
        store.clearAnalysis()
        _chat.value = emptyList()
        _state.value = _state.value.copy(
            analysis = null,
            errorMessage = null,
            status = if (store.isEnabled) (if (store.hasApiKey()) AiStatus.INSUFFICIENT_DATA else AiStatus.NO_KEY) else AiStatus.DISABLED,
            lastGeneratedAt = null
        )
    }
}
