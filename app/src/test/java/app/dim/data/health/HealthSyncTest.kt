package app.dim.data.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HealthSyncTest {
    // 2026-10-22 12:00 UTC
    private val t0 = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 10, 22)

    private class FakeSource : HealthSource {
        override val permissions = setOf("sleep", "steps", "hr", "resting_hr", "hrv", "android.permission.health.READ_HEALTH_DATA_HISTORY")
        override fun permissionsFor(metrics: Set<HealthMetric>): Set<String> = metrics.map { it.name.lowercase() }.toSet()
        var avail = Availability.AVAILABLE
        var granted: Set<HealthMetric> = HealthMetric.values().toSet()
        var historyGranted = true
        override suspend fun hasHistoryAccess(): Boolean = historyGranted
        var sessions: List<RawSleepSession> = emptyList()
        var activity: Map<LocalDate, DayValues> = emptyMap()
        var failWith: Exception? = null; var lostOnRead = false
        var reads = 0; var active = 0; var maxActive = 0; var readDelay = 0L
        override fun availability() = avail
        override suspend fun grantedMetrics(): Set<HealthMetric> { failWith?.let { throw it }; return granted }
        private suspend fun <T> tracked(block: () -> T): T {
            reads++; active++; maxActive = maxOf(maxActive, active)
            try { if (readDelay > 0) delay(readDelay); if (lostOnRead) throw HealthPermissionLost(); failWith?.let { throw it }; return block() } finally { active-- }
        }
        override suspend fun readSleep(from: Instant, to: Instant) = tracked { sessions }
        override suspend fun readActivity(from: LocalDate, to: LocalDate, metrics: Set<HealthMetric>) = tracked { activity }
    }

    private class MemStore : HealthStore {
        val rows = HashMap<Long, HealthDay>(); var writes = 0; var clears = 0
        override fun day(day: Long): Flow<HealthDay?> = flowOf(rows[day])
        override fun allDays(): Flow<List<HealthDay>> = flowOf(rows.values.sortedBy { it.day })
        override fun daysSince(day: Long): Flow<List<HealthDay>> = flowOf(rows.values.filter { it.day >= day }.sortedBy { it.day })
        override suspend fun range(from: Long, to: Long) = rows.values.filter { it.day in from..to }
        override suspend fun all() = rows.values.toList()
        override suspend fun apply(plan: WritePlan, pruneBefore: Long?) {
            plan.upserts.forEach { rows[it.day] = it }; plan.deletes.forEach { rows.remove(it) }
            if (pruneBefore != null) {
                rows.keys.filter { it < pruneBefore }.toList().forEach { rows.remove(it) }
            }
            writes += plan.upserts.size + plan.deletes.size
        }
        override suspend fun clear() { rows.clear(); clears++ }
    }

    private class MemPrefs : HealthPrefs {
        override var enabled = false; override var lastSyncAt = 0L
        override var lastSyncedDay: Long? = null; override var knownGranted: Set<HealthMetric> = emptySet()
        override var historyGranted = false
    }

    private class Rig(val now: () -> Long) {
        val src = FakeSource(); val store = MemStore(); val prefs = MemPrefs()
        val sync = HealthSync(src, store, prefs, CoroutineScope(Dispatchers.Unconfined), now, { ZoneOffset.UTC })
    }

    private fun rig(now: () -> Long = { t0 }) = Rig(now).also {
        val st = listOf(
            RawStage(Instant.parse("2026-10-21T23:00:00Z"), Instant.parse("2026-10-22T01:00:00Z"), stageType = RawStage.STAGE_LIGHT),
            RawStage(Instant.parse("2026-10-22T01:00:00Z"), Instant.parse("2026-10-22T03:00:00Z"), stageType = RawStage.STAGE_DEEP),
            RawStage(Instant.parse("2026-10-22T03:00:00Z"), Instant.parse("2026-10-22T07:00:00Z"), stageType = RawStage.STAGE_REM),
        )
        it.src.sessions = listOf(RawSleepSession(Instant.parse("2026-10-21T23:00:00Z"), Instant.parse("2026-10-22T07:00:00Z"), st))
        it.src.activity = mapOf(
            today.minusDays(1) to DayValues(
                steps = 8000,
                hrAvg = 70,
                hrMin = 50,
                hrMax = 120,
                restingHr = 58,
                hrvRmssd = 46.2,
            )
        )
    }

    @Test fun `nothing is read before the user connects`() = runBlocking {
        val r = rig()
        assertEquals(SyncOutcome.SKIPPED, r.sync.sync(force = true))
        r.sync.onForeground()
        assertEquals(0, r.src.reads); assertTrue(r.store.rows.isEmpty()); assertFalse(r.sync.state.value.enabled)
    }

    @Test fun `granting permission connects and syncs all metrics`() = runBlocking {
        val r = rig()
        assertEquals(SyncOutcome.SYNCED, r.sync.onPermissionResult())
        val s = r.sync.state.value
        assertTrue(s.enabled && s.connected); assertEquals(t0, s.lastSyncAt); assertNull(s.error)

        // Today row: sleep and stages
        val todayRow = r.store.rows[today.toEpochDay()]
        assertNotNull(todayRow)
        assertEquals(480, todayRow?.sleepMinutes)
        assertEquals(120, todayRow?.sleepDeepMinutes)
        assertEquals(240, todayRow?.sleepRemMinutes)
        assertEquals(120, todayRow?.sleepLightMinutes)

        // Yesterday row: steps, heart rate, resting HR, HRV
        val yestRow = r.store.rows[today.minusDays(1).toEpochDay()]
        assertNotNull(yestRow)
        assertEquals(8000, yestRow?.steps)
        assertEquals(58, yestRow?.restingHr)
        assertEquals(46.2, yestRow?.hrvRmssd!!, 0.001)

        assertTrue(r.prefs.enabled); assertEquals(today.toEpochDay(), r.prefs.lastSyncedDay)
    }

    @Test fun `missing permissions returns only ungranted metrics and history`() = runBlocking {
        val r = rig()
        r.src.granted = setOf(HealthMetric.SLEEP, HealthMetric.STEPS, HealthMetric.HEART_RATE)
        r.src.historyGranted = false
        r.sync.refresh()
        val missing = r.sync.missingPermissions()
        assertTrue("resting_heart_rate" in missing)
        assertTrue("heart_rate_variability" in missing)
        assertTrue("android.permission.health.READ_HEALTH_DATA_HISTORY" in missing)
        assertFalse("sleep" in missing)
        assertFalse("steps" in missing)
    }

    @Test fun `granting history access expands backfill window and marks historyGranted`() = runBlocking {
        val r = rig()
        r.src.historyGranted = true
        assertEquals(SyncOutcome.SYNCED, r.sync.onPermissionResult())
        assertTrue(r.sync.state.value.historyGranted)
        assertTrue(r.prefs.historyGranted)
    }

    @Test fun `denying permission leaves the integration off`() = runBlocking {
        val r = rig(); r.src.granted = emptySet()
        assertEquals(SyncOutcome.NO_PERMISSION, r.sync.onPermissionResult())
        assertTrue(r.sync.state.value.permissionDenied); assertFalse(r.prefs.enabled); assertEquals(0, r.src.reads)
    }

    @Test fun `syncing twice does not duplicate or rewrite anything`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        val rowsBefore = r.store.rows.size; val writesBefore = r.store.writes
        assertEquals(SyncOutcome.SYNCED, r.sync.sync(force = true)); assertEquals(SyncOutcome.SYNCED, r.sync.sync(force = true))
        assertEquals(rowsBefore, r.store.rows.size); assertEquals(writesBefore, r.store.writes)
    }

    @Test fun `automatic syncs respect the minimum interval but the button does not`() = runBlocking {
        var now = t0; val r = rig { now }
        r.sync.onPermissionResult(); val reads = r.src.reads
        now = t0 + 60_000
        assertEquals(SyncOutcome.SKIPPED, r.sync.sync(force = false)); assertEquals(reads, r.src.reads)
        assertEquals(SyncOutcome.SYNCED, r.sync.sync(force = true))
        now = t0 + 60_000 + HealthSync.AUTO_INTERVAL_MS
        assertEquals(SyncOutcome.SYNCED, r.sync.sync(force = false))
    }

    @Test fun `the foreground hook syncs only when connected`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult(); r.prefs.lastSyncAt = 0
        r.sync.onForeground()
        assertEquals(t0, r.sync.state.value.lastSyncAt)
    }

    @Test fun `changed data in Health Connect replaces the day`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        r.src.activity = mapOf(today.minusDays(1) to DayValues(steps = 9500, hrAvg = 70, hrMin = 50, hrMax = 120, restingHr = 55))
        r.sync.sync(force = true)
        assertEquals(9500, r.store.rows[today.minusDays(1).toEpochDay()]?.steps)
        assertEquals(55, r.store.rows[today.minusDays(1).toEpochDay()]?.restingHr)
    }

    @Test fun `empty Health Connect is fine`() = runBlocking {
        val r = rig(); r.src.sessions = emptyList(); r.src.activity = emptyMap()
        assertEquals(SyncOutcome.SYNCED, r.sync.onPermissionResult())
        assertTrue(r.store.rows.isEmpty()); assertNull(r.sync.state.value.error)
    }

    @Test fun `a revoked permission deletes that metric and keeps the rest`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        r.src.granted = setOf(HealthMetric.SLEEP)
        r.sync.sync(force = true)
        assertNull(r.store.rows[today.minusDays(1).toEpochDay()])          // had only activity
        assertEquals(480, r.store.rows[today.toEpochDay()]?.sleepMinutes)
        assertEquals(setOf(HealthMetric.SLEEP), r.sync.state.value.granted)
    }

    @Test fun `everything revoked clears the data and asks for access`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        r.src.granted = emptySet()
        assertEquals(SyncOutcome.NO_PERMISSION, r.sync.sync(force = true))
        assertTrue(r.store.rows.isEmpty())
        assertFalse(r.sync.state.value.connected); assertTrue(r.sync.state.value.enabled)
    }

    @Test fun `permission lost in the middle of a read is handled`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        r.src.granted = setOf(HealthMetric.SLEEP); r.src.lostOnRead = true
        assertEquals(SyncOutcome.NO_PERMISSION, r.sync.sync(force = true))
        assertTrue(r.sync.state.value.error!!.contains("removed")); assertFalse(r.sync.state.value.syncing)
        assertEquals(setOf(HealthMetric.SLEEP), r.sync.state.value.granted)
        assertNull(r.store.rows[today.minusDays(1).toEpochDay()])
        assertEquals(480, r.store.rows[today.toEpochDay()]?.sleepMinutes)
    }

    @Test fun `a failed read writes nothing, reports an error, and the next sync recovers`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        val rows = r.store.rows.toMap(); val day = r.prefs.lastSyncedDay
        r.src.failWith = HealthReadFailed("Health Connect didn't respond.")
        assertEquals(SyncOutcome.FAILED, r.sync.sync(force = true))
        assertEquals("Health Connect didn't respond.", r.sync.state.value.error); assertFalse(r.sync.state.value.syncing)
        assertEquals(rows, r.store.rows); assertEquals(day, r.prefs.lastSyncedDay)
        r.src.failWith = null
        assertEquals(SyncOutcome.SYNCED, r.sync.sync(force = true)); assertNull(r.sync.state.value.error)
    }

    @Test fun `an unexpected exception shows a generic message without details`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult(); r.src.failWith = IllegalStateException("secret internal detail 12345")
        assertEquals(SyncOutcome.FAILED, r.sync.sync(force = true))
        assertFalse(r.sync.state.value.error!!.contains("12345"))
    }

    @Test fun `unavailable Health Connect is reported and nothing is read`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult(); val reads = r.src.reads
        r.src.avail = Availability.UNAVAILABLE
        assertEquals(SyncOutcome.UNAVAILABLE, r.sync.sync(force = true)); r.sync.refresh()
        assertEquals(Availability.UNAVAILABLE, r.sync.state.value.availability); assertEquals(reads, r.src.reads)
        assertTrue(r.sync.state.value.granted.isEmpty())
    }

    @Test fun `a provider that needs an update is reported`() = runBlocking {
        val r = rig(); r.src.avail = Availability.UPDATE_REQUIRED; r.sync.refresh()
        assertEquals(Availability.UPDATE_REQUIRED, r.sync.state.value.availability)
    }

    @Test fun `disconnect deletes the copied data and stops syncing`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        r.sync.disconnect()
        assertTrue(r.store.rows.isEmpty())
        assertFalse(r.prefs.enabled); assertNull(r.prefs.lastSyncedDay); assertNull(r.sync.state.value.lastSyncAt)
        val reads = r.src.reads
        assertEquals(SyncOutcome.SKIPPED, r.sync.sync(force = true)); assertEquals(reads, r.src.reads)
    }

    @Test fun `only one sync runs at a time`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult(); r.src.readDelay = 20; r.src.maxActive = 0
        (1..4).map { async { r.sync.sync(force = true) } }.awaitAll()
        assertEquals(1, r.src.maxActive)
    }

    @Test fun `days older than a year are pruned`() = runBlocking {
        val r = rig(); r.sync.onPermissionResult()
        r.store.rows[today.minusDays(400).toEpochDay()] = HealthDay(today.minusDays(400).toEpochDay(), steps = 1, syncedAt = 0)
        r.sync.sync(force = true)
        assertNull(r.store.rows[today.minusDays(400).toEpochDay()])
    }
}
