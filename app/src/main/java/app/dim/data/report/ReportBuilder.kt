package app.dim.data.report

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed
import app.dim.data.health.HealthDay
import app.dim.data.health.HealthMath
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter
import java.util.Locale

const val NOT_RECORDED = "Not recorded"

/** [label] is the chip text on the report screen; [title] is how the period is named inside the report. */
enum class ReportPeriod(val label: String, val title: String) {
    LAST_30("Last 30 days", "Last 30 days"), LAST_90("90 days", "Last 90 days"), LAST_6_MONTHS("6 months", "Last 6 months"),
    LAST_YEAR("1 year", "Last year"), ALL("All time", "All time"), CUSTOM("Custom range", "Custom range")
}

/** What the user picked on the report screen. Health Connect is off unless the user turns it on. */
data class ReportOptions(
    val period: ReportPeriod = ReportPeriod.LAST_30,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val includeProfile: Boolean = true,
    val includeMedications: Boolean = true,
    val includeHealth: Boolean = false,
)

/** Inclusive range of local dates. A null [from] means "from the beginning" (All time). */
data class ReportRange(val from: LocalDate?, val to: LocalDate)

class Count(val name: String, val count: Int)

class Overview(
    val totalEpisodes: Int,
    val ongoingEpisodes: Int,
    val migraineDays: Int,
    val avgPain: Double?, val painRecords: Int,
    val avgDurationMin: Long?, val durationRecords: Int,
    val maxPain: Int? = null, val maxPainDate: String? = null,
    val longestMin: Long? = null, val longestDate: String? = null,
)

/** One bar in a time chart. */
class Bucket(val label: String, val count: Int)
class TimeSeries(val title: String, val buckets: List<Bucket>)
class PainPoint(val epochMs: Long, val pain: Int)
class DayInfo(val count: Int, val maxPain: Int?)
/** One answer category (for example "Low" stress) and how many episodes recorded it; [recorded] = episodes that have any answer. */
class ContextGroup(val name: String, val segments: List<Count>, val recorded: Int)

/** Everything the charts draw, counted from the episodes in the period. */
class ChartData(
    val timeline: TimeSeries,
    val painPoints: List<PainPoint>,
    val painBands: List<Count>,
    val weekday: List<Count>,
    val timeOfDay: List<Count>,
    val durations: List<Count>,
    val context: List<ContextGroup>,
    val medicines: PatternList,
    val calendar: Map<LocalDate, DayInfo>,
    val calFrom: LocalDate, val calTo: LocalDate, val calTruncated: Boolean,
    val spanFrom: LocalDate, val spanTo: LocalDate,
)

class HealthPoint(val label: String, val sleepHours: Double?, val steps: Int?, val restingHr: Int?, val hrv: Double?, val migraine: Boolean)
class HealthSeries(val unit: String, val points: List<HealthPoint>)

/** Most frequently recorded items. [episodesWithData] = episodes that have at least one item of this kind. */
class PatternList(val items: List<Count>, val episodesWithData: Int)
class Patterns(val symptoms: PatternList, val triggers: PatternList, val locations: PatternList)

class ProfileSection(val rows: List<Pair<String, String>>, val hasAnyEntry: Boolean)

class RoutineEntry(val name: String, val time: String)
class MedicationSection(
    val routine: List<RoutineEntry>,
    val occasional: List<String>,
    val duringEpisodes: List<Count>,
    val episodesWithMedication: Int,
)

class HealthRow(val label: String, val value: String, val records: Int, val unit: String, val limited: Boolean)
class HealthSection(
    val rows: List<HealthRow>,
    val daysWithData: Int,
    val notes: List<String>,
    val hasData: Boolean,
    val series: HealthSeries? = null,
)

/** One episode, already formatted for display. Every missing value reads [NOT_RECORDED]. */
class EpisodeEntry(
    val number: Int,
    val date: String,
    val start: String,
    val end: String,
    val ongoing: Boolean,
    val duration: String,
    val pain: String,
    val aura: String,
    val stress: String,
    val hydration: String,
    val lastMeal: String,
    val caffeine: String,
    val beganInSleep: String,
    val onsetPlace: String,
    val symptoms: String,
    val triggers: String,
    val painLocation: String,
    val medication: String,
    val notes: String?,
)

class Report(
    val generatedAt: Long,
    val zone: ZoneId,
    val range: ReportRange,
    val periodLabel: String,
    val patientName: String?,
    val profile: ProfileSection?,
    val overview: Overview,
    val patterns: Patterns,
    val medications: MedicationSection?,
    val health: HealthSection?,
    val episodes: List<EpisodeEntry>,
    val charts: ChartData,
) {
    val isEmpty get() = overview.totalEpisodes == 0
    val generatedLabel: String get() = ReportBuilder.generatedLabel(generatedAt, zone)
    val fileName: String get() = "Dim-migraine-report-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(generatedAt)) + ".pdf"
}

/**
 * Builds the report from stored records only. Pure: no Android, no I/O, no network, no AI. The same inputs always give the same report.
 * Episodes are filtered by their START date in [zone]; the range is inclusive on both ends.
 */
object ReportBuilder {
    private val HYDRATION = listOf("Less than 500 ml", "500 ml – 1 L", "More than 1 L")   // same wording as the episode detail page
    private val MEAL = listOf("Less than 2 h ago", "2–6 h ago", "More than 6 h ago")
    private val CAFFEINE = listOf("None", "1 cup", "More than 1")
    private const val TOP_N = 10
    /** Below this share of the period's days, a Health Connect metric is flagged as limited coverage. */
    private const val LIMITED_COVERAGE = 0.5

    private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val DAY_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
    private val SHORT_DAY_TIME = DateTimeFormatter.ofPattern("d MMM, hh:mm a", Locale.ENGLISH)

    fun date(d: LocalDate): String = DATE_FMT.format(d)

    fun generatedLabel(at: Long, zone: ZoneId): String =
        DATE_FMT.format(Instant.ofEpochMilli(at).atZone(zone)) + ", " + TIME_FMT.format(Instant.ofEpochMilli(at).atZone(zone))

    /** Null if [o] is usable, otherwise a message for the user (custom range problems only). */
    fun problem(o: ReportOptions, today: LocalDate): String? {
        if (o.period != ReportPeriod.CUSTOM) return null
        val f = o.customFrom; val t = o.customTo
        return when {
            f == null || t == null -> "Choose a start and an end date."
            f.isAfter(t) -> "The start date must be on or before the end date."
            f.isAfter(today) -> "The start date can't be in the future."
            else -> null
        }
    }

    /** The dates covered. Last N days counts today as day 1, the same way Insights' "Last 30 days" does. Custom ends are capped at today. */
    fun range(o: ReportOptions, today: LocalDate): ReportRange = when (o.period) {
        ReportPeriod.LAST_30 -> ReportRange(today.minusDays(29), today)
        ReportPeriod.LAST_90 -> ReportRange(today.minusDays(89), today)
        ReportPeriod.LAST_6_MONTHS -> ReportRange(today.minusMonths(6).plusDays(1), today)
        ReportPeriod.LAST_YEAR -> ReportRange(today.minusYears(1).plusDays(1), today)
        ReportPeriod.ALL -> ReportRange(null, today)
        ReportPeriod.CUSTOM -> {
            val t = minOf(o.customTo ?: today, today)
            ReportRange(minOf(o.customFrom ?: t, t), t)
        }
    }

    fun build(
        episodes: List<Episode>,
        profile: Profile,
        routine: List<RoutineMed>,
        occasional: List<String>,
        healthDays: List<HealthDay>,
        options: ReportOptions,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Report {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val range = range(options, today)
        fun dayOf(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

        val inRange = episodes.filter { e -> dayOf(e.startedAt).let { d -> (range.from == null || !d.isBefore(range.from)) && !d.isAfter(range.to) } }
            .sortedWith(compareBy<Episode> { it.startedAt }.thenBy { it.id })   // oldest first

        val periodLabel = periodLabel(options, range, inRange.firstOrNull()?.let { dayOf(it.startedAt) })
        val spanFrom = range.from ?: inRange.firstOrNull()?.let { dayOf(it.startedAt) } ?: range.to

        return Report(
            generatedAt = now, zone = zone, range = range, periodLabel = periodLabel,
            patientName = if (options.includeProfile) profile.name.trim().ifBlank { null } else null,
            profile = if (options.includeProfile) profileSection(profile, today) else null,
            overview = overview(inRange, zone),
            charts = charts(inRange, spanFrom, range.to, zone),
            patterns = Patterns(
                pattern(inRange) { it.symptoms }, pattern(inRange) { it.triggers }, pattern(inRange) { it.location }),
            medications = if (options.includeMedications) medications(inRange, routine, occasional) else null,
            health = if (options.includeHealth) health(healthDays, range, today, inRange.map { dayOf(it.startedAt) }.toSet()) else null,
            episodes = inRange.mapIndexed { i, e -> entry(i + 1, e, zone) },
        )
    }

    private fun periodLabel(o: ReportOptions, r: ReportRange, firstEpisodeDay: LocalDate?): String = when (o.period) {
        ReportPeriod.ALL -> {
            val from = firstEpisodeDay
            if (from == null) "All time (no episodes recorded)" else "All time (${date(from)} – ${date(r.to)})"
        }
        else -> "${o.period.title} (${date(r.from!!)} – ${date(r.to)})"
    }

    // ───────────────────────── overview ─────────────────────────

    private fun overview(eps: List<Episode>, zone: ZoneId): Overview {
        val pains = eps.mapNotNull { it.pain }
        // Completed = has an end time that is not before the start. Ongoing episodes are never given an end time.
        val durations = eps.mapNotNull { e -> e.endedAt?.let { it - e.startedAt }?.takeIf { it >= 0 } }
        return Overview(
            totalEpisodes = eps.size,
            ongoingEpisodes = eps.count { it.endedAt == null },
            migraineDays = eps.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.toSet().size,
            avgPain = pains.takeIf { it.isNotEmpty() }?.average(), painRecords = pains.size,
            avgDurationMin = durations.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it / 60_000.0) }, durationRecords = durations.size,
            maxPain = pains.maxOrNull(),
            maxPainDate = pains.maxOrNull()?.let { m -> eps.last { it.pain == m } }?.let { date(Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()) },
            longestMin = durations.maxOrNull()?.let { Math.round(it / 60_000.0) },
            longestDate = eps.filter { it.endedAt != null && it.endedAt - it.startedAt >= 0 }.maxByOrNull { it.endedAt!! - it.startedAt }
                ?.let { date(Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()) },
        )
    }

    private fun items(s: String): List<String> = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun counts(lists: List<List<String>>): List<Count> =
        lists.flatten().groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase(Locale.ROOT) })
            .map { Count(it.key, it.value) }

    private fun pattern(eps: List<Episode>, pick: (Episode) -> String): PatternList {
        val lists = eps.map { items(pick(it)) }
        return PatternList(counts(lists).take(TOP_N), lists.count { it.isNotEmpty() })
    }

    // ───────────────────────── profile ─────────────────────────

    private fun profileSection(p: Profile, today: LocalDate): ProfileSection {
        fun text(s: String) = s.trim().ifBlank { NOT_RECORDED }
        fun list(s: String) = items(s).joinToString(", ").ifBlank { NOT_RECORDED }
        val age = p.birthYear?.let { today.year - it }?.takeIf { it in 0..130 }
        val rows = buildList {
            add("Name" to text(p.name))
            add("Age" to (age?.let { "$it years (from birth year)" } ?: NOT_RECORDED))
            add("Sex" to text(p.sex))
            add("Recorded diagnosis" to text(p.diagnosis))
            add("Aura" to text(p.aura))
            add("Age at first migraine" to (p.firstAge?.let { "$it years" } ?: NOT_RECORDED))
            add("Family history" to list(p.family))
            add("Other conditions" to list(p.conditions))
            if (p.notes.isNotBlank()) add("Profile notes" to p.notes.trim())
        }
        val any = listOf(p.name, p.sex, p.diagnosis, p.aura, p.family, p.conditions, p.notes).any { it.isNotBlank() } || p.birthYear != null || p.firstAge != null
        return ProfileSection(rows, any)
    }

    // ───────────────────────── medication ─────────────────────────

    private fun clock(minutes: Int): String {
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        return java.time.LocalTime.of(m / 60, m % 60).format(TIME_FMT)
    }

    private fun medications(eps: List<Episode>, routine: List<RoutineMed>, occasional: List<String>): MedicationSection {
        val lists = eps.map { items(it.medication) }
        return MedicationSection(
            routine = routine.filter { it.name.isNotBlank() }.map { RoutineEntry(it.name.trim(), clock(it.minutes)) },
            occasional = occasional.map { it.trim() }.filter { it.isNotEmpty() },
            duringEpisodes = counts(lists),
            episodesWithMedication = lists.count { it.isNotEmpty() },
        )
    }

    // ───────────────────────── Health Connect ─────────────────────────

    /**
     * Uses only the HealthDay rows already stored on this device, inside [range]. The averages come from [HealthMath.summarize], the same
     * calculation Insights uses. That function leaves out "today" for steps and heart rate (the day isn't over), so when the range ends
     * today it is called with today, and when it ended earlier it is called a day later so the last day counts.
     */
    private fun health(all: List<HealthDay>, range: ReportRange, today: LocalDate, migraineDays: Set<LocalDate>): HealthSection {
        val lo = range.from?.toEpochDay(); val hi = range.to.toEpochDay()
        val rows = all.filter { it.hasAny() && (lo == null || it.day >= lo) && it.day <= hi }
        val summary = HealthMath.summarize(rows, if (range.to >= today) today else range.to.plusDays(1), null)
        if (rows.isEmpty() || !summary.hasData) {
            val why = if (all.none { it.hasAny() }) "No Health Connect data is stored on this device (it may not be connected, or has not synced yet)."
                      else "No Health Connect data is stored for this reporting period."
            return HealthSection(emptyList(), 0, listOf(why), false)
        }

        val first = LocalDate.ofEpochDay(rows.minOf { it.day })
        val spanFrom = range.from ?: first
        val spanDays = (range.to.toEpochDay() - spanFrom.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val activityDays = if (range.to >= today) (spanDays - 1).coerceAtLeast(1) else spanDays   // today is not counted for steps / heart rate

        fun row(label: String, value: String?, n: Int, unit: String, eligible: Int): HealthRow? =
            if (value == null || n == 0) null
            else HealthRow(label, value, n, unit, n < Math.ceil(eligible * LIMITED_COVERAGE).toInt())
        fun hm(m: Int) = "${m / 60}h ${m % 60}m"
        val out = listOfNotNull(
            row("Average sleep duration", summary.sleepAvgMin?.let { hm(it) }, summary.sleepNights, if (summary.sleepNights == 1) "night" else "nights", spanDays),
            row("Average daily steps", summary.stepsAvg?.let { "%,d".format(Locale.ENGLISH, it) }, summary.stepDays, if (summary.stepDays == 1) "day" else "days", activityDays),
            row("Average heart rate", summary.hrAvg?.let { "$it bpm" }, summary.hrDays, if (summary.hrDays == 1) "day" else "days", activityDays),
            row("Average resting heart rate", summary.restingHrAvg?.let { "$it bpm" }, summary.restingHrDays, if (summary.restingHrDays == 1) "day" else "days", activityDays),
            row("Average HRV (RMSSD)", summary.hrvAvg?.let { "${Math.round(it)} ms" }, summary.hrvDays, if (summary.hrvDays == 1) "day" else "days", activityDays),
        )

        val notes = ArrayList<String>()
        if (first.isAfter(spanFrom) && range.from != null)
            notes.add("Limited coverage: stored Health Connect data begins on ${date(first)}, so earlier days in this period have no data.")
        if (out.any { it.limited })
            notes.add("Metrics marked “limited” have data for fewer than half of the days in the period, so their averages may not be representative.")
        if (range.to >= today) notes.add("Today is left out of steps and heart-rate averages because the day is not over.")
        notes.add("Averages use only the days that have a value; days without data are not counted as zero. Values come from Health Connect and were not entered by the patient.")
        return HealthSection(out, rows.size, notes, true, healthSeries(rows, spanFrom, range.to, today, migraineDays))
    }

    // ───────────────────────── charts ─────────────────────────

    private enum class Unit { DAY, WEEK, MONTH, QUARTER }
    private class Span(val label: String, val from: LocalDate, val to: LocalDate)
    private val LBL_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val LBL_MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)

    private fun spans(from: LocalDate, to: LocalDate, unit: Unit): List<Span> {
        val out = ArrayList<Span>()
        when (unit) {
            Unit.DAY -> { var d = from; while (!d.isAfter(to)) { out.add(Span(LBL_DAY.format(d), d, d)); d = d.plusDays(1) } }
            Unit.WEEK -> { var d = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); while (!d.isAfter(to)) { out.add(Span(LBL_DAY.format(d), d, d.plusDays(6))); d = d.plusDays(7) } }
            Unit.MONTH -> { var m = YearMonth.from(from); while (!m.atDay(1).isAfter(to)) { out.add(Span(LBL_MONTH.format(m), m.atDay(1), m.atEndOfMonth())); m = m.plusMonths(1) } }
            Unit.QUARTER -> {
                var m = YearMonth.of(from.year, (from.monthValue - 1) / 3 * 3 + 1)
                while (!m.atDay(1).isAfter(to)) { out.add(Span("Q${(m.monthValue - 1) / 3 + 1} ${m.year % 100}", m.atDay(1), m.plusMonths(2).atEndOfMonth())); m = m.plusMonths(3) }
            }
        }
        return out
    }

    private fun charts(eps: List<Episode>, from: LocalDate, to: LocalDate, zone: ZoneId): ChartData {
        fun day(e: Episode) = Instant.ofEpochMilli(e.startedAt).atZone(zone).toLocalDate()
        val perDay = eps.groupingBy { day(it) }.eachCount()
        val days = ChronoUnit.DAYS.between(from, to) + 1
        val months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1
        val unit = when { days <= 45 -> Unit.DAY; days <= 200 -> Unit.WEEK; months <= 48 -> Unit.MONTH; else -> Unit.QUARTER }
        val title = when (unit) { Unit.DAY -> "Episodes per day"; Unit.WEEK -> "Episodes per week (weeks start on Monday)"; Unit.MONTH -> "Episodes per month"; Unit.QUARTER -> "Episodes per quarter" }
        val timeline = TimeSeries(title, spans(from, to, unit).map { sp ->
            var n = 0; var d = sp.from; while (!d.isAfter(sp.to)) { n += perDay[d] ?: 0; d = d.plusDays(1) }
            Bucket(sp.label, n)
        })

        val pains = eps.mapNotNull { it.pain }
        val bands = listOf(
            Count("None (0)", pains.count { it == 0 }), Count("Mild (1–3)", pains.count { it in 1..3 }), Count("Moderate (4–6)", pains.count { it in 4..6 }),
            Count("Severe (7–8)", pains.count { it in 7..8 }), Count("Worst (9–10)", pains.count { it >= 9 }), Count(NOT_RECORDED, eps.size - pains.size))

        val wk = IntArray(7); val tod = IntArray(4)
        eps.forEach { e ->
            wk[day(e).dayOfWeek.value - 1]++
            tod[Instant.ofEpochMilli(e.startedAt).atZone(zone).hour / 6]++
        }
        val wkNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val todNames = listOf("Night 00–06", "Morning 06–12", "Afternoon 12–18", "Evening 18–24")

        val mins = eps.mapNotNull { e -> e.endedAt?.let { (it - e.startedAt) / 60_000 }?.takeIf { it >= 0 } }
        val durNames = listOf("< 1 h", "1–4 h", "4–12 h", "12–24 h", "> 24 h")
        val dur = durNames.mapIndexed { i, n -> Count(n, mins.count { m -> when (i) { 0 -> m < 60; 1 -> m in 60..239; 2 -> m in 240..719; 3 -> m in 720..1439; else -> m >= 1440 } }) }

        fun group(name: String, labels: List<String>, pick: (Episode) -> Int?): ContextGroup {
            val v = eps.mapNotNull { e -> pick(e)?.takeIf { it in labels.indices } }
            return ContextGroup(name, labels.mapIndexed { i, l -> Count(l, v.count { it == i }) }, v.size)
        }
        val context = listOf(
            group("Stress", listOf("Low", "Medium", "High")) { e -> e.stress?.let { if (it <= 3) 0 else if (it <= 6) 1 else 2 } },
            group("Hydration", HYDRATION) { it.hydration },
            group("Last meal", MEAL) { it.meal },
            group("Caffeine", CAFFEINE) { it.caffeine },
        )

        val info = eps.groupBy { day(it) }.mapValues { (_, l) -> DayInfo(l.size, l.mapNotNull { it.pain }.maxOrNull()) }
        val truncated = days > 371
        return ChartData(
            timeline = timeline,
            painPoints = eps.filter { it.pain != null }.map { PainPoint(it.startedAt, it.pain!!) },
            painBands = bands,
            weekday = wkNames.mapIndexed { i, n -> Count(n, wk[i]) },
            timeOfDay = todNames.mapIndexed { i, n -> Count(n, tod[i]) },
            durations = dur, context = context, medicines = pattern(eps) { it.medication },
            calendar = info, calFrom = if (truncated) to.minusDays(363) else from, calTo = to, calTruncated = truncated,
            spanFrom = from, spanTo = to,
        )
    }

    /** Daily values, or weekly / monthly averages when the period is long. Today is left out of steps and heart rate (the day isn't over). */
    private fun healthSeries(rows: List<HealthDay>, from: LocalDate, to: LocalDate, today: LocalDate, migraineDays: Set<LocalDate>): HealthSeries {
        val days = ChronoUnit.DAYS.between(from, to) + 1
        val unit = when { days <= 100 -> Unit.DAY; days <= 400 -> Unit.WEEK; else -> Unit.MONTH }
        val byDay = rows.associateBy { it.day }
        fun avg(v: List<Double>) = v.takeIf { it.isNotEmpty() }?.average()
        val pts = spans(from, to, unit).map { sp ->
            val inSpan = (sp.from.toEpochDay()..sp.to.toEpochDay()).mapNotNull { byDay[it] }
            val done = inSpan.filter { it.day < today.toEpochDay() }
            HealthPoint(
                label = sp.label,
                sleepHours = avg(inSpan.mapNotNull { it.sleepMinutes?.let { m -> m / 60.0 } }),
                steps = avg(done.mapNotNull { it.steps?.toDouble() })?.let { Math.round(it).toInt() },
                restingHr = avg(done.mapNotNull { it.restingHr?.toDouble() })?.let { Math.round(it).toInt() },
                hrv = avg(done.mapNotNull { it.hrvRmssd }),
                migraine = migraineDays.any { !it.isBefore(sp.from) && !it.isAfter(sp.to) },
            )
        }
        return HealthSeries(when (unit) { Unit.DAY -> "day"; Unit.WEEK -> "week"; else -> "month" }, pts)
    }

    // ───────────────────────── episode timeline ─────────────────────────

    private fun entry(n: Int, e: Episode, zone: ZoneId): EpisodeEntry {
        val s = Instant.ofEpochMilli(e.startedAt).atZone(zone)
        val en = e.endedAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val mins = e.endedAt?.let { (it - e.startedAt) / 60_000 }?.takeIf { it >= 0 }
        val ended = e.endedAt != null
        return EpisodeEntry(
            number = n,
            date = DAY_FMT.format(s),
            start = TIME_FMT.format(s),
            end = when {
                en == null -> "Ongoing (no end time)"
                en.toLocalDate() == s.toLocalDate() -> TIME_FMT.format(en)
                else -> SHORT_DAY_TIME.format(en)
            },
            ongoing = !ended,
            duration = when { !ended -> "Ongoing"; mins == null -> NOT_RECORDED; else -> duration(mins) },
            pain = e.pain?.let { "$it / 10" } ?: NOT_RECORDED,
            // Episode.aura and startedInSleep are plain yes/no flags: "off" can mean "no" or "never answered", so it is never printed as "No".
            aura = if (e.aura) "Yes" else "Not reported",
            stress = e.stress?.let { if (it <= 3) "Low" else if (it <= 6) "Medium" else "High" } ?: NOT_RECORDED,
            hydration = e.hydration?.let { HYDRATION.getOrNull(it) } ?: NOT_RECORDED,
            lastMeal = e.meal?.let { MEAL.getOrNull(it) } ?: NOT_RECORDED,
            caffeine = e.caffeine?.let { CAFFEINE.getOrNull(it) } ?: NOT_RECORDED,
            beganInSleep = if (e.startedInSleep) "Yes" else "Not reported",
            onsetPlace = e.onsetPlace.trim().ifBlank { NOT_RECORDED },
            symptoms = items(e.symptoms).joinToString(", ").ifBlank { NOT_RECORDED },
            triggers = items(e.triggers).joinToString(", ").ifBlank { NOT_RECORDED },
            painLocation = items(e.location).joinToString(", ").ifBlank { NOT_RECORDED },
            medication = items(e.medication).joinToString(", ").ifBlank { NOT_RECORDED },
            notes = e.notes.trim().ifBlank { null },
        )
    }

    fun duration(mins: Long): String = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}
