package app.dim.data.health

import java.time.Instant
import java.time.LocalDate

/** Plain data handed from the Health Connect layer to the rest of the app, so nothing outside it needs the SDK. */
class RawStage(
    val start: Instant,
    val end: Instant,
    val asleep: Boolean,
    val stageType: Int = if (asleep) STAGE_SLEEPING else STAGE_AWAKE,
) {
    constructor(start: Instant, end: Instant, stageType: Int) : this(
        start = start,
        end = end,
        asleep = stageType !in AWAKE_STAGES,
        stageType = stageType,
    )

    companion object {
        const val STAGE_UNKNOWN = 0
        const val STAGE_AWAKE = 1
        const val STAGE_SLEEPING = 2
        const val STAGE_OUT_OF_BED = 3
        const val STAGE_LIGHT = 4
        const val STAGE_DEEP = 5
        const val STAGE_REM = 6
        const val STAGE_AWAKE_IN_BED = 7
        val AWAKE_STAGES: Set<Int> = setOf(STAGE_AWAKE, STAGE_OUT_OF_BED, STAGE_AWAKE_IN_BED)
    }
}

/** One sleep session as recorded by some app. [stages] may be empty (many apps only write start and end). */
class RawSleepSession(val start: Instant, val end: Instant, val stages: List<RawStage> = emptyList())

/** Steps, heart rate, resting heart rate, and HRV for one day; any field is null when Health Connect has nothing for that day. */
class DayValues(
    val sleepMinutes: Int? = null,
    val steps: Long? = null,
    val hrAvg: Long? = null,
    val hrMin: Long? = null,
    val hrMax: Long? = null,
    val restingHr: Long? = null,
    val hrvRmssd: Double? = null,
)

enum class Availability { UNKNOWN, AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

/** Thrown by a source when a read is refused because a permission is missing or was revoked. */
class HealthPermissionLost(cause: Throwable? = null) : Exception("Health Connect permission is missing", cause)

/** Thrown for anything else that stops a read (Health Connect not responding, rate limits, ...). [message] is safe to show. */
class HealthReadFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything the sync needs from Health Connect. The real implementation is [HealthConnectSource]; tests use a fake.
 * All functions that read data are read-only and may throw [HealthPermissionLost] or [HealthReadFailed].
 */
interface HealthSource {
    /** Permission strings to ask the user for (one read permission per metric). */
    val permissions: Set<String>
    /** Read permission strings corresponding to [metrics]. */
    fun permissionsFor(metrics: Set<HealthMetric>): Set<String> = emptySet()
    fun availability(): Availability
    /** Metrics whose read permission is currently granted. */
    suspend fun grantedMetrics(): Set<HealthMetric>
    /** Whether permission to read past health data history (>30 days) is granted. */
    suspend fun hasHistoryAccess(): Boolean = false
    /** Sleep sessions that overlap [from, to]. */
    suspend fun readSleep(from: Instant, to: Instant): List<RawSleepSession>
    /** Per-day metrics for [from]..[to] inclusive (local dates). Only the metrics in [metrics] are read. */
    suspend fun readActivity(from: LocalDate, to: LocalDate, metrics: Set<HealthMetric>): Map<LocalDate, DayValues>
}
