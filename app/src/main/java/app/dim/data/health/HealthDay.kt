package app.dim.data.health

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The kinds of Health Connect data Dim reads. Read-only: nothing is ever written back. */
enum class HealthMetric {
    SLEEP,
    STEPS,
    HEART_RATE,
    RESTING_HEART_RATE,
    HEART_RATE_VARIABILITY,
}

/**
 * One row per local calendar day, filled from Health Connect. The day is the primary key, so syncing the same day again
 * replaces the row instead of adding a second one. A null value means "no data for that day", never zero.
 *
 * - [sleepMinutes]: time asleep in the sleep that ended on this day (the night leading into it, plus any naps).
 * - [sleepAwakeMinutes] / [sleepLightMinutes] / [sleepDeepMinutes] / [sleepRemMinutes]: sleep stage durations.
 * - [steps]: total steps for the day (Health Connect de-duplicates between apps and devices).
 * - [hrAvg] / [hrMin] / [hrMax]: beats per minute over the day.
 * - [restingHr]: resting heart rate in bpm.
 * - [hrvRmssd]: heart rate variability (RMSSD) in milliseconds.
 */
@Entity(tableName = "health_days")
data class HealthDay(
    @PrimaryKey val day: Long,          // LocalDate.toEpochDay()
    val sleepMinutes: Int? = null,
    val steps: Int? = null,
    val hrAvg: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val syncedAt: Long,                 // when this row was last written
    val sleepAwakeMinutes: Int? = null,
    val sleepLightMinutes: Int? = null,
    val sleepDeepMinutes: Int? = null,
    val sleepRemMinutes: Int? = null,
    val restingHr: Int? = null,
    val hrvRmssd: Double? = null,
) {
    fun hasAny() = sleepMinutes != null || steps != null || hrAvg != null || hrMin != null || hrMax != null ||
        restingHr != null || hrvRmssd != null ||
        sleepAwakeMinutes != null || sleepLightMinutes != null || sleepDeepMinutes != null || sleepRemMinutes != null

    /** Same health values, ignoring when they were synced. */
    fun sameValues(o: HealthDay) = day == o.day &&
        sleepMinutes == o.sleepMinutes &&
        sleepAwakeMinutes == o.sleepAwakeMinutes &&
        sleepLightMinutes == o.sleepLightMinutes &&
        sleepDeepMinutes == o.sleepDeepMinutes &&
        sleepRemMinutes == o.sleepRemMinutes &&
        steps == o.steps &&
        hrAvg == o.hrAvg && hrMin == o.hrMin && hrMax == o.hrMax &&
        restingHr == o.restingHr &&
        hrvRmssd == o.hrvRmssd
}
