package app.dim.data.ai

/**
 * Structured result of an AI analysis performed by the Gemini API.
 */
data class AiAnalysis(
    val summary: String,
    val detailedExplanation: String,
    val triggerPatterns: List<String>,
    val precautions: List<String>,
    val warningBulletPoints: List<String>,
    val hasEnoughData: Boolean,
    val generatedAt: Long,
    val episodesAnalyzed: Int,
    val healthDaysAnalyzed: Int = 0,
    val modelUsed: String = "",
    val headline: String = "",
    val reliefSteps: List<String> = emptyList()
)

/** One message in the follow-up ("Reply") conversation about the current insight. */
data class AiChatMessage(
    val fromUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    val id: Long = System.nanoTime()
)

/**
 * State representing the readiness of the AI insights subsystem.
 */
enum class AiStatus {
    DISABLED,           // User toggled AI insights off
    NO_KEY,             // Enabled, but no API key configured
    INSUFFICIENT_DATA,  // Not enough historical records (<2 completed episodes)
    LOADING,            // Calling Gemini / analyzing data
    READY,              // Analysis available and up to date
    ERROR               // API or network failure
}

/**
 * Observable UI state for AI Insights.
 */
data class AiState(
    val status: AiStatus = AiStatus.DISABLED,
    val analysis: AiAnalysis? = null,
    val errorMessage: String? = null,
    val enabled: Boolean = false,
    val hasKey: Boolean = false,
    val lastGeneratedAt: Long? = null,
    val isRefreshing: Boolean = false,
    val selectedModel: String? = null
)
