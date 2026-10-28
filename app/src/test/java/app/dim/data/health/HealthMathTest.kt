package app.dim.data.health

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HealthMathTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(day: String, hm: String) = Instant.parse("${day}T$hm:00Z")
    private fun s(from: String, fh: String, to: String, th: String, stages: List<RawStage> = emptyList()) =
        RawSleepSession(at(from, fh), at(to, th), stages)
    private val d = LocalDate.of(2026, 10, 20)

    // ── sleep ──

    @Test fun `a night belongs to the day it ended on`() {
        val m = HealthMath.sleepMinutesByWakeDay(listOf(s("2026-10-19", "23:00", "2026-10-20", "07:00")), utc)
        assertEquals(mapOf(d to 480), m)
    }

    @Test fun `the same night written by two apps is counted once`() {
        val a = s("2026-10-19", "23:00", "2026-10-20", "07:00")
        val b = s("2026-10-19", "23:10", "2026-10-20", "06:50")
        assertEquals(mapOf(d to 480), HealthMath.sleepMinutesByWakeDay(listOf(a, b), utc))
    }

    @Test fun `awake time inside a session is left out`() {
        val st = listOf(
            RawStage(at("2026-10-19", "23:00"), at("2026-10-20", "02:00"), true),
            RawStage(at("2026-10-20", "02:00"), at("2026-10-20", "02:30"), false),
            RawStage(at("2026-10-20", "02:30"), at("2026-10-20", "07:00"), true))
        val m = HealthMath.sleepMinutesByWakeDay(listOf(s("2026-10-19", "23:00", "2026-10-20", "07:00", st)), utc)
        assertEquals(450, m[d])
    }

    @Test fun `waking in the middle of the night does not split the night over two dates`() {
        val st = listOf(
            RawStage(at("2026-10-19", "22:00"), at("2026-10-19", "23:50"), true),
            RawStage(at("2026-10-19", "23:50"), at("2026-10-20", "00:20"), false),
            RawStage(at("2026-10-20", "00:20"), at("2026-10-20", "06:00"), true))
        val m = HealthMath.sleepMinutesByWakeDay(listOf(s("2026-10-19", "22:00", "2026-10-20", "06:00", st)), utc)
        assertEquals(setOf(d), m.keys); assertEquals(110 + 340, m[d])
    }

    @Test fun `night and nap on the same date add up`() {
        val m = HealthMath.sleepMinutesByWakeDay(listOf(
            s("2026-10-19", "23:00", "2026-10-20", "06:00"), s("2026-10-20", "14:00", "2026-10-20", "14:30")), utc)
        assertEquals(450, m[d])
    }

    @Test fun `empty or backwards sessions are ignored`() {
        assertTrue(HealthMath.sleepMinutesByWakeDay(emptyList(), utc).isEmpty())
        assertTrue(HealthMath.sleepMinutesByWakeDay(listOf(s("2026-10-20", "07:00", "2026-10-20", "07:00"), s("2026-10-20", "08:00", "2026-10-20", "07:00")), utc).isEmpty())
    }

    @Test fun `the local time zone decides the date`() {
        val tz = ZoneId.of("Asia/Kolkata")   // UTC+5:30: 23:00Z is already the next morning
        val m = HealthMath.sleepMinutesByWakeDay(listOf(s("2026-10-19", "21:00", "2026-10-19", "23:00")), tz)
        assertEquals(setOf(LocalDate.of(2026, 10, 20)), m.keys)
    }

    @Test fun `sleep summary computes awake light deep and rem stages`() {
        val st = listOf(
            RawStage(at("2026-10-19", "23:00"), at("2026-10-19", "23:30"), stageType = RawStage.STAGE_AWAKE),
            RawStage(at("2026-10-19", "23:30"), at("2026-10-20", "01:30"), stageType = RawStage.STAGE_LIGHT),
            RawStage(at("2026-10-20", "01:30"), at("2026-10-20", "03:00"), stageType = RawStage.STAGE_DEEP),
            RawStage(at("2026-10-20", "03:00"), at("2026-10-20", "05:00"), stageType = RawStage.STAGE_REM),
            RawStage(at("2026-10-20", "05:00"), at("2026-10-20", "07:00"), stageType = RawStage.STAGE_LIGHT),
        )
        val sum = HealthMath.sleepSummaryByWakeDay(listOf(s("2026-10-19", "23:00", "2026-10-20", "07:00", st)), utc)
        val day = sum[d]
        assertNotNull(day)
        assertEquals(450, day!!.totalMinutes)   // 8 hours minus 30 min awake = 7h 30m = 450m
        assertEquals(30, day.awakeMinutes)
        assertEquals(240, day.lightMinutes)     // 120 + 120 = 240m
        assertEquals(90, day.deepMinutes)       // 1h 30m = 90m
        assertEquals(120, day.remMinutes)       // 2h = 120m
    }

    // ── window ──

    @Test fun `first sync reads the whole allowed thirty days`() {
        val (from, to) = HealthMath.syncWindow(d, null)
        assertEquals(d.minusDays(29), from); assertEquals(d, to)
    }

    @Test fun `sync window with history access backfills up to 365 days`() {
        val (from, to) = HealthMath.syncWindow(d, null, HealthMath.KEEP_DAYS)
        assertEquals(d.minusDays(364), from); assertEquals(d, to)
    }

    @Test fun `later syncs re-read only the last few days`() {
        val (from, to) = HealthMath.syncWindow(d, d.minusDays(1))
        assertEquals(d.minusDays(4), from); assertEquals(d, to)
    }

    @Test fun `a very old last sync never reaches past thirty days`() {
        assertEquals(d.minusDays(29), HealthMath.syncWindow(d, d.minusDays(200)).first)
    }

    @Test fun `a last sync dated in the future does not break the window`() {
        val (from, to) = HealthMath.syncWindow(d, d.plusDays(10))
        assertTrue(!from.isAfter(to))
    }

    // ── write plan ──

    private val all = HealthMetric.values().toSet()
    private fun plan(
        existing: List<HealthDay> = emptyList(),
        sleep: Map<LocalDate, Int> = emptyMap(),
        act: Map<LocalDate, DayValues> = emptyMap(),
        granted: Set<HealthMetric> = all,
        now: Long = 1000,
        sleepStages: Map<LocalDate, SleepDaySummary> = emptyMap(),
    ) = HealthMath.planWrite(existing, d, d, sleep, act, granted, now, sleepStages, utc)

    @Test fun `new data becomes one row for the day`() {
        val p = plan(sleep = mapOf(d to 400), act = mapOf(d to DayValues(steps = 8000, hrAvg = 70, hrMin = 50, hrMax = 130)))
        assertEquals(1, p.upserts.size); assertTrue(p.deletes.isEmpty())
        val r = p.upserts[0]
        assertEquals(d.toEpochDay(), r.day); assertEquals(400, r.sleepMinutes); assertEquals(8000, r.steps)
        assertEquals(70, r.hrAvg); assertEquals(50, r.hrMin); assertEquals(130, r.hrMax)
    }

    @Test fun `planWrite stores resting heart rate, HRV, and sleep stages`() {
        val stg = mapOf(d to SleepDaySummary(totalMinutes = 420, awakeMinutes = 20, lightMinutes = 200, deepMinutes = 100, remMinutes = 100))
        val act = mapOf(d to DayValues(restingHr = 58, hrvRmssd = 45.5))
        val p = plan(sleep = mapOf(d to 420), act = act, sleepStages = stg)

        assertEquals(1, p.upserts.size)
        val r = p.upserts[0]
        assertEquals(58, r.restingHr)
        assertEquals(45.5, r.hrvRmssd!!, 0.001)
        assertEquals(20, r.sleepAwakeMinutes)
        assertEquals(200, r.sleepLightMinutes)
        assertEquals(100, r.sleepDeepMinutes)
        assertEquals(100, r.sleepRemMinutes)
    }

    @Test fun `syncing the same data again writes nothing`() {
        val first = plan(sleep = mapOf(d to 400), act = mapOf(d to DayValues(steps = 8000)))
        val again = plan(existing = first.upserts, sleep = mapOf(d to 400), act = mapOf(d to DayValues(steps = 8000)), now = 99_999)
        assertTrue(again.isEmpty)
    }

    @Test fun `changed data replaces the day instead of adding another`() {
        val first = plan(act = mapOf(d to DayValues(steps = 3000)))
        val next = plan(existing = first.upserts, act = mapOf(d to DayValues(steps = 9000)))
        assertEquals(1, next.upserts.size); assertEquals(9000, next.upserts[0].steps); assertEquals(d.toEpochDay(), next.upserts[0].day)
    }

    @Test fun `a day with no data is not stored`() {
        assertTrue(plan().isEmpty)
    }

    @Test fun `data removed in Health Connect is removed here`() {
        val first = plan(sleep = mapOf(d to 400))
        val p = plan(existing = first.upserts)
        assertEquals(listOf(d.toEpochDay()), p.deletes); assertTrue(p.upserts.isEmpty())
    }

    @Test fun `zero steps and impossible heart rates count as no data`() {
        val p = plan(act = mapOf(d to DayValues(steps = 0, hrAvg = 5, hrMin = 5, hrMax = 900, restingHr = 10, hrvRmssd = 0.5)))
        assertTrue(p.isEmpty)
    }

    @Test fun `a metric without permission is left alone`() {
        val old = HealthDay(d.toEpochDay(), sleepMinutes = 420, steps = 5000, syncedAt = 1, restingHr = 60, hrvRmssd = 40.0)
        val p = plan(
            existing = listOf(old),
            sleep = mapOf(d to 999),
            act = mapOf(d to DayValues(steps = 7000, restingHr = 80, hrvRmssd = 55.0)),
            granted = setOf(HealthMetric.STEPS),
        )
        assertEquals(1, p.upserts.size)
        assertEquals(420, p.upserts[0].sleepMinutes)
        assertEquals(7000, p.upserts[0].steps)
        assertEquals(60, p.upserts[0].restingHr)
        assertEquals(40.0, p.upserts[0].hrvRmssd!!, 0.001)
    }

    @Test fun `revoking a permission clears that metric and drops emptied rows`() {
        val rows = listOf(
            HealthDay(1, sleepMinutes = 400, steps = 100, syncedAt = 1, restingHr = 60, hrvRmssd = 42.0),
            HealthDay(2, sleepMinutes = 300, syncedAt = 1),
        )
        val pSleep = HealthMath.withoutMetrics(rows, setOf(HealthMetric.SLEEP))
        assertEquals(listOf(2L), pSleep.deletes)
        assertEquals(listOf(1L), pSleep.upserts.map { it.day })
        assertNull(pSleep.upserts[0].sleepMinutes)
        assertEquals(100, pSleep.upserts[0].steps)

        val pHrv = HealthMath.withoutMetrics(rows, setOf(HealthMetric.HEART_RATE_VARIABILITY))
        assertNull(pHrv.upserts[0].hrvRmssd)
        assertEquals(400, pHrv.upserts[0].sleepMinutes)
    }

    // ── insights ──

    private fun row(
        offset: Int,
        sleep: Int? = null,
        steps: Int? = null,
        hr: Int? = null,
        restingHr: Int? = null,
        hrv: Double? = null,
        deep: Int? = null,
        rem: Int? = null,
    ) = HealthDay(
        day = d.minusDays(offset.toLong()).toEpochDay(),
        sleepMinutes = sleep,
        steps = steps,
        hrAvg = hr,
        hrMin = null,
        hrMax = null,
        syncedAt = 0,
        sleepAwakeMinutes = null,
        sleepLightMinutes = null,
        sleepDeepMinutes = deep,
        sleepRemMinutes = rem,
        restingHr = restingHr,
        hrvRmssd = hrv,
    )

    @Test fun `summary averages only days that have data`() {
        val rows = listOf(
            row(1, sleep = 360, steps = 4000, restingHr = 60, hrv = 50.0, deep = 60, rem = 90),
            row(2, sleep = 480, steps = 8000, hr = 60, restingHr = 64, hrv = 40.0, deep = 80, rem = 110),
            row(3),
        )
        val s = HealthMath.summarize(rows, d)
        assertEquals(420, s.sleepAvgMin); assertEquals(2, s.sleepNights)
        assertEquals(6000, s.stepsAvg); assertEquals(2, s.stepDays); assertEquals(60, s.hrAvg); assertEquals(1, s.hrDays)
        assertEquals(62, s.restingHrAvg); assertEquals(2, s.restingHrDays)
        assertEquals(45.0, s.hrvAvg!!, 0.001); assertEquals(2, s.hrvDays)
        assertEquals(70, s.sleepDeepAvgMin)
        assertEquals(100, s.sleepRemAvgMin)
    }

    @Test fun `today is left out of steps and activity but not out of sleep`() {
        val s = HealthMath.summarize(listOf(
            row(0, sleep = 420, steps = 300, hr = 90, restingHr = 75, hrv = 30.0),
            row(1, sleep = 420, steps = 9000, hr = 60, restingHr = 60, hrv = 50.0),
        ), d)
        assertEquals(2, s.sleepNights)
        assertEquals(1, s.stepDays); assertEquals(9000, s.stepsAvg); assertEquals(60, s.hrAvg)
        assertEquals(60, s.restingHrAvg)
        assertEquals(50.0, s.hrvAvg!!, 0.001)
    }

    @Test fun `summary of nothing has no data`() {
        val s = HealthMath.summarize(emptyList(), d)
        assertFalse(s.hasData); assertNull(s.sleepAvgMin); assertNull(s.stepsAvg); assertNull(s.hrAvg)
        assertNull(s.restingHrAvg); assertNull(s.hrvAvg)
    }

    @Test fun `windowed summary limits days, all-time summary includes all history`() {
        val rows = listOf(row(45, sleep = 400))
        assertFalse(HealthMath.summarize(rows, d, windowDays = 30).hasData)
        assertTrue(HealthMath.summarize(rows, d, windowDays = null).hasData)
        assertEquals(400, HealthMath.summarize(rows, d, windowDays = null).sleepAvgMin)
    }

    @Test fun `comparison needs enough days on both sides`() {
        val hit = setOf(d.minusDays(1), d.minusDays(2))
        val rows = (1..10).map { row(it, sleep = if (d.minusDays(it.toLong()) in hit) 300 else 450) }
        assertNull(HealthMath.compare(rows, hit, d, excludeToday = false) { it.sleepMinutes })   // only 2 attack days
    }

    @Test fun `comparison splits days with and without an attack`() {
        val hit = (1..3).map { d.minusDays(it.toLong()) }.toSet()
        val rows = (1..10).map { row(it, sleep = if (d.minusDays(it.toLong()) in hit) 300 else 450) }
        val c = HealthMath.compare(rows, hit, d, excludeToday = false) { it.sleepMinutes }!!
        assertEquals(300.0, c.withEpisode, 0.001); assertEquals(3, c.withEpisodeDays)
        assertEquals(450.0, c.without, 0.001); assertEquals(7, c.withoutDays)
    }

    @Test fun `comparison supports HRV, resting HR, and deep sleep`() {
        val hit = (1..3).map { d.minusDays(it.toLong()) }.toSet()
        val rows = (1..10).map {
            val isHit = d.minusDays(it.toLong()) in hit
            row(
                offset = it,
                restingHr = if (isHit) 68 else 60,
                hrv = if (isHit) 35.0 else 55.0,
                deep = if (isHit) 45 else 90,
            )
        }

        val rhrC = HealthMath.compare(rows, hit, d, excludeToday = true) { it.restingHr }!!
        assertEquals(68.0, rhrC.withEpisode, 0.001)
        assertEquals(60.0, rhrC.without, 0.001)

        val hrvC = HealthMath.compare(rows, hit, d, excludeToday = true) { it.hrvRmssd }!!
        assertEquals(35.0, hrvC.withEpisode, 0.001)
        assertEquals(55.0, hrvC.without, 0.001)

        val deepC = HealthMath.compare(rows, hit, d, excludeToday = false) { it.sleepDeepMinutes }!!
        assertEquals(45.0, deepC.withEpisode, 0.001)
        assertEquals(90.0, deepC.without, 0.001)
    }

    @Test fun `days without data for the measure are skipped in a comparison`() {
        val hit = (1..6).map { d.minusDays(it.toLong()) }.toSet()
        val rows = (1..12).map { row(it, steps = if (it % 2 == 0) null else 1000) }
        val c = HealthMath.compare(rows, hit, d, excludeToday = true) { it.steps }
        assertNotNull(c); assertEquals(1000.0, c!!.withEpisode, 0.001)
        assertEquals(c.withEpisodeDays + c.withoutDays, 6)
    }
}
