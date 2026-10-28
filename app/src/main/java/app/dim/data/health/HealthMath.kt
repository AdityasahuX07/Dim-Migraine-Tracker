package app.dim.data.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Changes to apply to the health_days table in one transaction. */
class WritePlan(
    val upserts: List<HealthDay>,
    val deletes: List<Long>,
) {
    val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
}

/** Sleep duration and stage breakdown for one local date. */
class SleepDaySummary(
    val totalMinutes: Int,
    val awakeMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val remMinutes: Int? = null,
)

/** Averages shown on Insights. A null average means no usable days, never "zero". */
class HealthSummary(
    val sleepAvgMin: Int?, val sleepNights: Int,
    val stepsAvg: Int?, val stepDays: Int,
    val hrAvg: Int?, val hrDays: Int,
    val restingHrAvg: Int? = null, val restingHrDays: Int = 0,
    val hrvAvg: Double? = null, val hrvDays: Int = 0,
    val sleepDeepAvgMin: Int? = null,
    val sleepRemAvgMin: Int? = null,
    val sleepLightAvgMin: Int? = null,
    val sleepAwakeAvgMin: Int? = null,
) {
    val hasData get() = sleepNights > 0 || stepDays > 0 || hrDays > 0 ||
        restingHrDays > 0 || hrvDays > 0
    val hasSleep get() = sleepAvgMin != null || sleepNights > 0
    val hasHeart get() = hrAvg != null || restingHrAvg != null || hrvAvg != null
    val hasSteps get() = stepsAvg != null
}

/** The same measure on days with an attack versus days without one. */
class Comparison(val withEpisode: Double, val withEpisodeDays: Int, val without: Double, val withoutDays: Int)

/** Pure decisions for sync and insights: no Android, no database, no Health Connect, so they can be unit tested. */
object HealthMath {
    /** Health Connect lets an app read 30 days before the first permission grant (without the history permission). */
    const val BACKFILL_DAYS = 30
    /** Wearables upload late, so the last few days are re-read on every sync. */
    const val RESYNC_DAYS = 3
    /** Older rows are dropped; Insights never looks further back than this. */
    const val KEEP_DAYS = 365
    /** A comparison needs at least this many days on each side before it is shown. */
    const val MIN_DAYS_PER_GROUP = 3

    // ───────────────────────── sleep ─────────────────────────

    /**
     * Calculates sleep duration and stage breakdown per local date, where a sleep belongs to the date it ENDED on.
     * Sessions that overlap or touch are merged into one group, and overlapping asleep intervals are unioned so the
     * same night is never counted twice.
     */
    fun sleepSummaryByWakeDay(sessions: List<RawSleepSession>, zone: ZoneId): Map<LocalDate, SleepDaySummary> {
        val valid = sessions.filter { it.end.isAfter(it.start) }.sortedBy { it.start }
        if (valid.isEmpty()) return emptyMap()

        val results = HashMap<LocalDate, SleepDaySummary>()

        fun flush(group: List<RawSleepSession>, groupEnd: Instant) {
            val total = unionMillis(group.flatMap { asleepIntervals(it) })
            if (total <= 0) return
            val d = groupEnd.atZone(zone).toLocalDate()
            val totalMins = ((total + 30_000) / 60_000).toInt().coerceAtMost(24 * 60)
            if (totalMins < 1) return

            val hasStages = group.any { it.stages.isNotEmpty() }
            val (awakeMin, lightMin, deepMin, remMin) = if (hasStages) {
                fun stageMins(pred: (RawStage) -> Boolean): Int {
                    val intervals = group.flatMap { s ->
                        s.stages.filter(pred).map { maxOf(it.start.toEpochMilli(), s.start.toEpochMilli()) to minOf(it.end.toEpochMilli(), s.end.toEpochMilli()) }
                            .filter { it.second > it.first }
                    }
                    val ms = unionMillis(intervals)
                    return ((ms + 30_000) / 60_000).toInt()
                }
                val awk = stageMins { it.stageType in RawStage.AWAKE_STAGES || !it.asleep }
                val lgt = stageMins { it.stageType == RawStage.STAGE_LIGHT }
                val dp = stageMins { it.stageType == RawStage.STAGE_DEEP }
                val rm = stageMins { it.stageType == RawStage.STAGE_REM }
                listOf(awk, lgt, dp, rm)
            } else {
                listOf(null, null, null, null)
            }

            val prev = results[d]
            results[d] = if (prev == null) {
                SleepDaySummary(totalMins, awakeMin, lightMin, deepMin, remMin)
            } else {
                SleepDaySummary(
                    totalMinutes = (prev.totalMinutes + totalMins).coerceAtMost(24 * 60),
                    awakeMinutes = plusOrNull(prev.awakeMinutes, awakeMin),
                    lightMinutes = plusOrNull(prev.lightMinutes, lightMin),
                    deepMinutes = plusOrNull(prev.deepMinutes, deepMin),
                    remMinutes = plusOrNull(prev.remMinutes, remMin),
                )
            }
        }

        var group = ArrayList<RawSleepSession>()
        var groupEnd = valid[0].end
        for (s in valid) {
            if (group.isEmpty() || !s.start.isAfter(groupEnd)) {
                group.add(s)
                if (s.end.isAfter(groupEnd)) groupEnd = s.end
            } else {
                flush(group, groupEnd)
                group = arrayListOf(s)
                groupEnd = s.end
            }
        }
        flush(group, groupEnd)
        return results
    }

    private fun plusOrNull(a: Int?, b: Int?): Int? =
        if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    /**
     * Minutes asleep per local date, where a sleep belongs to the date it ENDED on (the morning you woke up).
     */
    fun sleepMinutesByWakeDay(sessions: List<RawSleepSession>, zone: ZoneId): Map<LocalDate, Int> =
        sleepSummaryByWakeDay(sessions, zone).mapValues { it.value.totalMinutes }

    private fun asleepIntervals(s: RawSleepSession): List<Pair<Long, Long>> {
        val a = s.start.toEpochMilli(); val b = s.end.toEpochMilli()
        if (s.stages.isEmpty()) return listOf(a to b)
        return s.stages.filter { it.asleep }
            .map { maxOf(it.start.toEpochMilli(), a) to minOf(it.end.toEpochMilli(), b) }
            .filter { it.second > it.first }
    }

    private fun unionMillis(intervals: List<Pair<Long, Long>>): Long {
        var total = 0L; var curS = 0L; var curE = 0L; var open = false
        for ((s, e) in intervals.sortedBy { it.first }) {
            if (!open) { curS = s; curE = e; open = true }
            else if (s <= curE) { if (e > curE) curE = e }
            else { total += curE - curS; curS = s; curE = e }
        }
        if (open) total += curE - curS
        return total
    }

    // ───────────────────────── sync window and writes ─────────────────────────

    /** First and last local date to (re)read. First sync: backfill window ([backfillDays], up to 365 days if history is allowed). Later: from a few days before the last sync. */
    fun syncWindow(today: LocalDate, lastSyncedDay: LocalDate?, backfillDays: Int = BACKFILL_DAYS): Pair<LocalDate, LocalDate> {
        val earliest = today.minusDays(backfillDays - 1L)
        val from = if (lastSyncedDay == null) earliest else maxOf(earliest, lastSyncedDay.minusDays(RESYNC_DAYS.toLong()))
        return minOf(from, today) to today
    }

    // Values outside these ranges are treated as "no data" rather than shown.
    private fun steps(v: Long?) = v?.takeIf { it in 1..500_000 }?.toInt()
    private fun bpm(v: Long?) = v?.takeIf { it in 20..300 }?.toInt()
    private fun hrv(v: Double?) = v?.takeIf { it in 1.0..300.0 }

    /**
     * Works out what to write for the days [from]..[to]. Only the [granted] metrics are updated; a metric that is not granted
     * keeps whatever the row already holds. A day is added or replaced only if its values differ from what is stored
     * (so a repeated sync writes nothing), and a day that no longer has any data is deleted.
     */
    fun planWrite(
        existing: List<HealthDay>, from: LocalDate, to: LocalDate,
        sleep: Map<LocalDate, Int>, activity: Map<LocalDate, DayValues>,
        granted: Set<HealthMetric>, now: Long,
        sleepStages: Map<LocalDate, SleepDaySummary> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WritePlan {
        val old = existing.associateBy { it.day }
        val ups = ArrayList<HealthDay>(); val del = ArrayList<Long>()

        var d = from
        while (!d.isAfter(to)) {
            val key = d.toEpochDay()
            val prev = old[key]
            val act = activity[d]
            val stg = sleepStages[d]

            val next = HealthDay(
                day = key,
                sleepMinutes = if (HealthMetric.SLEEP in granted) (stg?.totalMinutes ?: sleep[d])?.takeIf { it >= 1 } else prev?.sleepMinutes,
                sleepAwakeMinutes = if (HealthMetric.SLEEP in granted) stg?.awakeMinutes else prev?.sleepAwakeMinutes,
                sleepLightMinutes = if (HealthMetric.SLEEP in granted) stg?.lightMinutes else prev?.sleepLightMinutes,
                sleepDeepMinutes = if (HealthMetric.SLEEP in granted) stg?.deepMinutes else prev?.sleepDeepMinutes,
                sleepRemMinutes = if (HealthMetric.SLEEP in granted) stg?.remMinutes else prev?.sleepRemMinutes,
                steps = if (HealthMetric.STEPS in granted) steps(act?.steps) else prev?.steps,
                hrAvg = if (HealthMetric.HEART_RATE in granted) bpm(act?.hrAvg) else prev?.hrAvg,
                hrMin = if (HealthMetric.HEART_RATE in granted) bpm(act?.hrMin) else prev?.hrMin,
                hrMax = if (HealthMetric.HEART_RATE in granted) bpm(act?.hrMax) else prev?.hrMax,
                restingHr = if (HealthMetric.RESTING_HEART_RATE in granted) bpm(act?.restingHr) else prev?.restingHr,
                hrvRmssd = if (HealthMetric.HEART_RATE_VARIABILITY in granted) hrv(act?.hrvRmssd) else prev?.hrvRmssd,
                syncedAt = now,
            )
            when {
                !next.hasAny() -> if (prev != null) del.add(key)
                prev == null || !prev.sameValues(next) -> ups.add(next)
            }
            d = d.plusDays(1)
        }

        return WritePlan(ups, del)
    }

    /** Clears metrics from stored rows (used when permissions were revoked) and drops rows left empty. */
    fun withoutMetrics(rows: List<HealthDay>, removed: Set<HealthMetric>): WritePlan {
        val ups = ArrayList<HealthDay>(); val del = ArrayList<Long>()
        for (r in rows) {
            val n = r.copy(
                sleepMinutes = if (HealthMetric.SLEEP in removed) null else r.sleepMinutes,
                sleepAwakeMinutes = if (HealthMetric.SLEEP in removed) null else r.sleepAwakeMinutes,
                sleepLightMinutes = if (HealthMetric.SLEEP in removed) null else r.sleepLightMinutes,
                sleepDeepMinutes = if (HealthMetric.SLEEP in removed) null else r.sleepDeepMinutes,
                sleepRemMinutes = if (HealthMetric.SLEEP in removed) null else r.sleepRemMinutes,
                steps = if (HealthMetric.STEPS in removed) null else r.steps,
                hrAvg = if (HealthMetric.HEART_RATE in removed) null else r.hrAvg,
                hrMin = if (HealthMetric.HEART_RATE in removed) null else r.hrMin,
                hrMax = if (HealthMetric.HEART_RATE in removed) null else r.hrMax,
                restingHr = if (HealthMetric.RESTING_HEART_RATE in removed) null else r.restingHr,
                hrvRmssd = if (HealthMetric.HEART_RATE_VARIABILITY in removed) null else r.hrvRmssd,
            )
            if (!n.hasAny()) del.add(r.day) else if (!n.sameValues(r)) ups.add(n)
        }
        return WritePlan(ups, del)
    }

    // ───────────────────────── insights ─────────────────────────

    /**
     * Averages over the given [windowDays] (or all available days up to today if null). Today is left out of activity measures
     * because the day isn't over yet and would pull the average down; sleep that ended this morning is complete, so it counts.
     */
    fun summarize(days: List<HealthDay>, today: LocalDate, windowDays: Int? = null): HealthSummary {
        val t = today.toEpochDay()
        val inWindow = if (windowDays != null) {
            val first = today.minusDays(windowDays - 1L).toEpochDay()
            days.filter { it.day in first..t }
        } else {
            days.filter { it.day <= t }
        }
        val finished = inWindow.filter { it.day < t }

        val sl = inWindow.mapNotNull { it.sleepMinutes }
        val slDeep = inWindow.mapNotNull { it.sleepDeepMinutes }
        val slRem = inWindow.mapNotNull { it.sleepRemMinutes }
        val slLight = inWindow.mapNotNull { it.sleepLightMinutes }
        val slAwake = inWindow.mapNotNull { it.sleepAwakeMinutes }

        val st = finished.mapNotNull { it.steps }
        val hr = finished.mapNotNull { it.hrAvg }
        val rhr = finished.mapNotNull { it.restingHr }
        val hrv = finished.mapNotNull { it.hrvRmssd }

        return HealthSummary(
            sleepAvgMin = sl.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            sleepNights = sl.size,
            stepsAvg = st.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            stepDays = st.size,
            hrAvg = hr.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            hrDays = hr.size,
            restingHrAvg = rhr.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            restingHrDays = rhr.size,
            hrvAvg = hrv.takeIf { it.isNotEmpty() }?.average(),
            hrvDays = hrv.size,
            sleepDeepAvgMin = slDeep.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            sleepRemAvgMin = slRem.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            sleepLightAvgMin = slLight.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            sleepAwakeAvgMin = slAwake.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
        )
    }

    /**
     * Average of [pick] on days with an attack versus days without, over the last [windowDays] days. Only days that have data for
     * the measure count. Null unless both sides have at least [MIN_DAYS_PER_GROUP] days, so a couple of nights never look like a pattern.
     */
    fun compare(
        days: List<HealthDay>, attackDays: Set<LocalDate>, today: LocalDate, windowDays: Int = 90,
        excludeToday: Boolean, pick: (HealthDay) -> Number?,
    ): Comparison? {
        val first = today.minusDays(windowDays - 1L).toEpochDay(); val last = today.toEpochDay() - if (excludeToday) 1 else 0
        val hit = ArrayList<Double>(); val miss = ArrayList<Double>()
        for (r in days) {
            if (r.day !in first..last) continue
            val v = pick(r)?.toDouble() ?: continue
            if (LocalDate.ofEpochDay(r.day) in attackDays) hit.add(v) else miss.add(v)
        }
        if (hit.size < MIN_DAYS_PER_GROUP || miss.size < MIN_DAYS_PER_GROUP) return null
        return Comparison(hit.average(), hit.size, miss.average(), miss.size)
    }
}
