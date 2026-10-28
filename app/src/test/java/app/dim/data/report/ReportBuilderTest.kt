package app.dim.data.report

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed
import app.dim.data.health.HealthDay
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReportBuilderTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 24)
    private val now = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(d: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun ep(daysAgo: Long, h: Int = 10, mins: Long? = 60, pain: Int? = 5, id: Long = 0, block: (Episode) -> Episode = { it }): Episode {
        val s = at(today.minusDays(daysAgo), h)
        return block(Episode(id = id, startedAt = s, endedAt = mins?.let { s + it * 60_000 }, pain = pain, uid = "u$daysAgo-$h-$id"))
    }
    private fun build(eps: List<Episode>, o: ReportOptions = ReportOptions(), p: Profile = Profile(), health: List<HealthDay> = emptyList(),
                      routine: List<RoutineMed> = emptyList(), occ: List<String> = emptyList()) =
        ReportBuilder.build(eps, p, routine, occ, health, o, now, utc)

    // ── date ranges ──

    @Test fun `last 30 days counts today as day one and filters by start date`() {
        val r = build(listOf(ep(0), ep(29), ep(30)))
        assertEquals(today.minusDays(29), r.range.from)
        assertEquals(2, r.overview.totalEpisodes)
    }

    @Test fun `an episode is placed by the day it started even if it ends the next day`() {
        val s = at(today.minusDays(30), 23, 30)               // starts the day before the window opens
        val e = Episode(startedAt = s, endedAt = s + 3 * 3_600_000, uid = "x")
        assertEquals(0, build(listOf(e)).overview.totalEpisodes)
        val late = Episode(startedAt = at(today.minusDays(29), 23, 30), endedAt = at(today.minusDays(28), 2), uid = "y")
        val r = build(listOf(late))
        assertEquals(1, r.overview.totalEpisodes)
        assertTrue(r.episodes[0].end.contains(","))             // end is on another day, so the date is shown
    }

    @Test fun `other periods`() {
        assertEquals(today.minusDays(89), ReportBuilder.range(ReportOptions(ReportPeriod.LAST_90), today).from)
        assertEquals(LocalDate.of(2026, 4, 25), ReportBuilder.range(ReportOptions(ReportPeriod.LAST_6_MONTHS), today).from)
        assertEquals(LocalDate.of(2025, 10, 25), ReportBuilder.range(ReportOptions(ReportPeriod.LAST_YEAR), today).from)
        assertNull(ReportBuilder.range(ReportOptions(ReportPeriod.ALL), today).from)
    }

    @Test fun `all time includes everything`() {
        val r = build(listOf(ep(0), ep(400), ep(2000)), ReportOptions(ReportPeriod.ALL))
        assertEquals(3, r.overview.totalEpisodes)
        assertTrue(r.periodLabel.startsWith("All time ("))
    }

    @Test fun `custom range is inclusive on both ends and ends are capped at today`() {
        val o = ReportOptions(ReportPeriod.CUSTOM, today.minusDays(10), today.minusDays(5))
        val r = build(listOf(ep(11), ep(10), ep(5), ep(4)), o)
        assertEquals(2, r.overview.totalEpisodes)
        val future = ReportOptions(ReportPeriod.CUSTOM, today.minusDays(3), today.plusDays(9))
        assertEquals(today, ReportBuilder.range(future, today).to)
    }

    @Test fun `bad custom ranges are reported`() {
        assertNotNull(ReportBuilder.problem(ReportOptions(ReportPeriod.CUSTOM), today))
        assertNotNull(ReportBuilder.problem(ReportOptions(ReportPeriod.CUSTOM, today, today.minusDays(1)), today))
        assertNotNull(ReportBuilder.problem(ReportOptions(ReportPeriod.CUSTOM, today.plusDays(1), today.plusDays(2)), today))
        assertNull(ReportBuilder.problem(ReportOptions(ReportPeriod.CUSTOM, today.minusDays(1), today), today))
        assertNull(ReportBuilder.problem(ReportOptions(ReportPeriod.LAST_30), today))
    }

    // ── overview ──

    @Test fun `overview counts only the records that exist`() {
        val eps = listOf(
            ep(1, 8, mins = 60, pain = 4), ep(1, 20, mins = 120, pain = 8),   // same day
            ep(3, mins = null, pain = null),                                   // ongoing, no pain
            ep(5, mins = 30, pain = null),
        )
        val o = build(eps).overview
        assertEquals(4, o.totalEpisodes); assertEquals(1, o.ongoingEpisodes)
        assertEquals(3, o.migraineDays)
        assertEquals(6.0, o.avgPain!!, 0.0001); assertEquals(2, o.painRecords)
        assertEquals(70L, o.avgDurationMin); assertEquals(3, o.durationRecords)   // (60 + 120 + 30) / 3
    }

    @Test fun `no records means null averages and zero counts, never zero values`() {
        val o = build(listOf(ep(2, mins = null, pain = null))).overview
        assertNull(o.avgPain); assertEquals(0, o.painRecords)
        assertNull(o.avgDurationMin); assertEquals(0, o.durationRecords)
        val empty = build(emptyList())
        assertTrue(empty.isEmpty); assertNull(empty.overview.avgPain); assertEquals(0, empty.overview.migraineDays)
        assertTrue(empty.episodes.isEmpty())
    }

    // ── patterns ──

    @Test fun `patterns count each item once per episode and sort by count`() {
        val eps = listOf(
            ep(1) { it.copy(symptoms = "Nausea, Nausea,Light sensitivity", triggers = "Stress", location = "Left,Temple") },
            ep(2) { it.copy(symptoms = "Nausea", triggers = "Stress, Alcohol in last 24h") },
            ep(3) { it.copy(symptoms = "Aura") },
        )
        val p = build(eps).patterns
        assertEquals(listOf("Nausea" to 2, "Aura" to 1, "Light sensitivity" to 1), p.symptoms.items.map { it.name to it.count })
        assertEquals(3, p.symptoms.episodesWithData)
        assertEquals("Stress", p.triggers.items[0].name); assertEquals(2, p.triggers.items[0].count)
        assertEquals(1, p.locations.episodesWithData)
    }

    // ── episode rows ──

    @Test fun `missing values read Not recorded and ongoing episodes get no invented end`() {
        val r = build(listOf(ep(1, mins = null, pain = null))).episodes.single()
        assertEquals(NOT_RECORDED, r.pain); assertEquals(NOT_RECORDED, r.stress)
        assertEquals(NOT_RECORDED, r.hydration); assertEquals(NOT_RECORDED, r.lastMeal); assertEquals(NOT_RECORDED, r.caffeine)
        assertEquals(NOT_RECORDED, r.symptoms); assertEquals(NOT_RECORDED, r.medication); assertEquals(NOT_RECORDED, r.onsetPlace)
        assertTrue(r.ongoing); assertEquals("Ongoing", r.duration); assertTrue(r.end.startsWith("Ongoing"))
        assertNull(r.notes)
    }

    @Test fun `episode fields are formatted from the stored values`() {
        val e = ep(2, 15, mins = 105, pain = 7) {
            it.copy(stress = 8, hydration = 0, meal = 2, caffeine = 1, aura = true, startedInSleep = true, onsetPlace = "Work",
                medication = "Ibuprofen, Sumatriptan", notes = "  Woke with it.  ")
        }
        val r = build(listOf(e)).episodes.single()
        assertEquals("03:00 PM", r.start); assertEquals("04:45 PM", r.end); assertEquals("1h 45m", r.duration)
        assertEquals("7 / 10", r.pain); assertEquals("High", r.stress)
        assertEquals("Less than 500 ml", r.hydration); assertEquals("More than 6 h ago", r.lastMeal); assertEquals("1 cup", r.caffeine)
        assertEquals("Yes", r.aura); assertEquals("Yes", r.beganInSleep); assertEquals("Work", r.onsetPlace)
        assertEquals("Ibuprofen, Sumatriptan", r.medication); assertEquals("Woke with it.", r.notes)
    }

    @Test fun `unset yes-no flags are not reported as No`() {
        val r = build(listOf(ep(1))).episodes.single()
        assertEquals("Not reported", r.aura); assertEquals("Not reported", r.beganInSleep)
    }

    @Test fun `timeline is oldest first and numbered`() {
        val r = build(listOf(ep(1, id = 3), ep(9, id = 1), ep(4, id = 2)))
        assertEquals(listOf(1, 2, 3), r.episodes.map { it.number })
        assertTrue(r.episodes[0].date.contains(today.minusDays(9).dayOfMonth.toString()))
    }

    @Test fun `an end before the start is not turned into a negative duration`() {
        val s = at(today.minusDays(1), 10)
        val r = build(listOf(Episode(startedAt = s, endedAt = s - 60_000, uid = "z")))
        assertEquals(NOT_RECORDED, r.episodes[0].duration); assertEquals(0, r.overview.durationRecords)
    }

    // ── profile ──

    @Test fun `profile section follows the toggle and shows Not recorded for gaps`() {
        val p = Profile(name = " Aditya ", birthYear = 1990, sex = "Male", diagnosis = "Episodic migraine", family = "Mother,Sibling", firstAge = 14)
        val on = build(emptyList(), p = p)
        assertEquals("Aditya", on.patientName)
        val rows = on.profile!!.rows.toMap()
        assertEquals("36 years (from birth year)", rows["Age"]); assertEquals("Mother, Sibling", rows["Family history"])
        assertEquals(NOT_RECORDED, rows["Aura"]); assertEquals(NOT_RECORDED, rows["Other conditions"]); assertFalse("Profile notes" in rows)
        val off = build(emptyList(), ReportOptions(includeProfile = false), p)
        assertNull(off.profile); assertNull(off.patientName)
    }

    @Test fun `an empty profile is flagged`() {
        assertFalse(build(emptyList()).profile!!.hasAnyEntry)
    }

    // ── medication ──

    @Test fun `current list and episode medicines are kept apart and no doses appear`() {
        val eps = listOf(ep(1) { it.copy(medication = "Ibuprofen") }, ep(2) { it.copy(medication = "Ibuprofen, Rizatriptan") }, ep(3))
        val m = build(eps, routine = listOf(RoutineMed("Propranolol", 8 * 60 + 30)), occ = listOf("Paracetamol")).medications!!
        assertEquals("Propranolol" to "08:30 AM", m.routine.single().let { it.name to it.time })
        assertEquals(listOf("Paracetamol"), m.occasional)
        assertEquals(listOf("Ibuprofen" to 2, "Rizatriptan" to 1), m.duringEpisodes.map { it.name to it.count })
        assertEquals(2, m.episodesWithMedication)
        assertNull(build(eps, ReportOptions(includeMedications = false)).medications)
    }

    // ── Health Connect ──

    private fun hd(daysAgo: Long, sleep: Int? = null, steps: Int? = null, hr: Int? = null, rhr: Int? = null, hrv: Double? = null) =
        HealthDay(day = today.minusDays(daysAgo).toEpochDay(), sleepMinutes = sleep, steps = steps, hrAvg = hr, restingHr = rhr, hrvRmssd = hrv, syncedAt = 0)

    @Test fun `health is absent unless turned on`() {
        assertNull(build(emptyList(), health = listOf(hd(1, sleep = 400))).health)
    }

    @Test fun `health uses only stored days inside the period and shows record counts`() {
        val days = listOf(hd(1, sleep = 420, steps = 8000, hr = 70, rhr = 55, hrv = 40.0), hd(2, sleep = 480, steps = 6000, hr = 74),
            hd(60, sleep = 100, steps = 100, hr = 100))       // outside the last 30 days
        val h = build(emptyList(), ReportOptions(includeHealth = true), health = days).health!!
        assertTrue(h.hasData)
        val rows = h.rows.associateBy { it.label }
        assertEquals("7h 30m", rows["Average sleep duration"]!!.value); assertEquals(2, rows["Average sleep duration"]!!.records)
        assertEquals("7,000", rows["Average daily steps"]!!.value)
        assertEquals("72 bpm", rows["Average heart rate"]!!.value)
        assertEquals("55 bpm", rows["Average resting heart rate"]!!.value); assertEquals(1, rows["Average resting heart rate"]!!.records)
        assertEquals("40 ms", rows["Average HRV (RMSSD)"]!!.value)
        assertEquals(2, h.daysWithData)
    }

    @Test fun `sparse health data is flagged as limited and today is left out of activity averages`() {
        val days = listOf(hd(0, sleep = 400, steps = 1500), hd(1, sleep = 420, steps = 9000))
        val h = build(emptyList(), ReportOptions(includeHealth = true), health = days).health!!
        val rows = h.rows.associateBy { it.label }
        assertEquals("9,000", rows["Average daily steps"]!!.value); assertEquals(1, rows["Average daily steps"]!!.records)   // today's partial steps ignored
        assertEquals(2, rows["Average sleep duration"]!!.records)                                                            // this morning's sleep counts
        assertTrue(rows.values.all { it.limited })
        assertTrue(h.notes.any { it.startsWith("Metrics marked") })
    }

    @Test fun `a period that ended before today counts its last day`() {
        val o = ReportOptions(ReportPeriod.CUSTOM, today.minusDays(10), today.minusDays(5), includeHealth = true)
        val h = build(emptyList(), o, health = listOf(hd(5, steps = 5000), hd(6, steps = 7000))).health!!
        assertEquals("6,000", h.rows.single { it.label.contains("steps") }.value)
    }

    @Test fun `late-starting health data is called out`() {
        val h = build(emptyList(), ReportOptions(ReportPeriod.LAST_90, includeHealth = true), health = (0L..29L).map { hd(it, sleep = 420) }).health!!
        assertTrue(h.notes.any { it.startsWith("Limited coverage") })
    }

    @Test fun `no health data gives a plain message and no numbers`() {
        val none = build(emptyList(), ReportOptions(includeHealth = true)).health!!
        assertFalse(none.hasData); assertTrue(none.rows.isEmpty()); assertTrue(none.notes.single().contains("No Health Connect data"))
        val outside = build(emptyList(), ReportOptions(includeHealth = true), health = listOf(hd(200, sleep = 400))).health!!
        assertFalse(outside.hasData); assertTrue(outside.notes.single().contains("reporting period"))
    }

    @Test fun `same inputs give the same report`() {
        val eps = listOf(ep(1) { it.copy(symptoms = "A,B") }, ep(2) { it.copy(symptoms = "B") })
        val a = build(eps); val b = build(eps)
        assertEquals(a.patterns.symptoms.items.map { it.name to it.count }, b.patterns.symptoms.items.map { it.name to it.count })
        assertEquals(a.episodes.map { it.date + it.start }, b.episodes.map { it.date + it.start })
    }

    // ── chart data ──

    @Test fun `timeline picks day week or month buckets and counts by start date`() {
        val d = build(listOf(ep(0), ep(0, 15), ep(2))).charts.timeline
        assertEquals(30, d.buckets.size); assertEquals(2, d.buckets.last().count); assertEquals(1, d.buckets[27].count); assertEquals(3, d.buckets.sumOf { it.count })
        val w = build(listOf(ep(0), ep(10), ep(40)), ReportOptions(ReportPeriod.LAST_90)).charts.timeline
        assertTrue(w.title.contains("week")); assertEquals(3, w.buckets.sumOf { it.count })
        val m = build(listOf(ep(0), ep(100), ep(300)), ReportOptions(ReportPeriod.LAST_YEAR)).charts.timeline
        assertTrue(m.title.contains("month")); assertEquals(3, m.buckets.sumOf { it.count })
    }

    @Test fun `pain bands weekday and time of day add up to the episodes`() {
        val eps = listOf(ep(1, 3, pain = 0), ep(1, 9, pain = 2), ep(2, 13, pain = 5), ep(3, 20, pain = 7), ep(4, 22, pain = 10), ep(5, 2, pain = null))
        val c = build(eps).charts
        assertEquals(listOf(1, 1, 1, 1, 1, 1), c.painBands.map { it.count })
        assertEquals(6, c.weekday.sumOf { it.count })
        assertEquals(listOf(2, 1, 1, 2), c.timeOfDay.map { it.count })   // 03 and 02 | 09 | 13 | 20 and 22
    }

    @Test fun `duration bands and context groups count only recorded values`() {
        val eps = listOf(ep(1, mins = 30), ep(2, mins = 90), ep(3, mins = 1500), ep(4, mins = null) { it.copy(stress = 2, hydration = 1) })
        val c = build(eps).charts
        assertEquals(listOf(1, 1, 0, 0, 1), c.durations.map { it.count })
        val stress = c.context.first { it.name == "Stress" }
        assertEquals(1, stress.recorded); assertEquals(listOf(1, 0, 0), stress.segments.map { it.count })
        assertEquals(0, c.context.first { it.name == "Caffeine" }.recorded)
    }

    @Test fun `highlights and calendar`() {
        val o = build(listOf(ep(1, pain = 4, mins = 60), ep(3, pain = 9, mins = 300), ep(5, pain = 9, mins = 30))).overview
        assertEquals(9, o.maxPain); assertEquals(300L, o.longestMin)
        assertEquals(ReportBuilder.date(today.minusDays(3)), o.longestDate)
        assertEquals(ReportBuilder.date(today.minusDays(3)), o.maxPainDate)   // ties go to the most recent
        val c = build(listOf(ep(1, pain = 4), ep(1, 15, pain = 8))).charts
        assertEquals(2, c.calendar[today.minusDays(1)]!!.count); assertEquals(8, c.calendar[today.minusDays(1)]!!.maxPain)
        assertFalse(c.calTruncated)
        assertTrue(build(emptyList(), ReportOptions(ReportPeriod.ALL)).charts.timeline.buckets.size >= 1)
    }

    @Test fun `health series averages weekly and marks migraine periods`() {
        val days = (0L..179L).map { hd(it, sleep = 420, steps = 5000, rhr = 60) }
        val h = build(listOf(ep(10)), ReportOptions(ReportPeriod.LAST_6_MONTHS, includeHealth = true), health = days).health!!.series!!
        assertEquals("week", h.unit); assertEquals(1, h.points.count { it.migraine })
        assertEquals(7.0, h.points[3].sleepHours!!, 0.0001)
        val daily = build(emptyList(), ReportOptions(includeHealth = true), health = listOf(hd(0, sleep = 400, steps = 900), hd(1, steps = 7000))).health!!.series!!
        assertEquals("day", daily.unit); assertNull(daily.points.last().steps)           // today's steps are not shown
        assertEquals(7000, daily.points[daily.points.size - 2].steps)
    }
}
