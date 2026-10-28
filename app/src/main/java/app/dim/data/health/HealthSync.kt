package app.dim.data.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where synced days are kept (Room in the app, in memory in tests). */
interface HealthStore {
    fun day(day: Long): Flow<HealthDay?>
    fun allDays(): Flow<List<HealthDay>>
    fun daysSince(day: Long): Flow<List<HealthDay>>
    suspend fun range(from: Long, to: Long): List<HealthDay>
    suspend fun all(): List<HealthDay>
    /** Applies [plan] and, if [pruneBefore] is set, removes older days, all in one transaction. */
    suspend fun apply(plan: WritePlan, pruneBefore: Long?)
    suspend fun clear()
}

/** Small on-device settings for the integration. Not part of backups. */
interface HealthPrefs {
    var enabled: Boolean
    /** 0 = never synced. */
    var lastSyncAt: Long
    var lastSyncedDay: Long?
    /** Metrics that were readable at the last sync; used to notice a revoked permission. */
    var knownGranted: Set<HealthMetric>
    var historyGranted: Boolean
}

/** Everything the UI needs. [error] is a message written for the user and never contains health values. */
data class HealthUiState(
    val availability: Availability = Availability.UNKNOWN,
    val enabled: Boolean = false,
    val granted: Set<HealthMetric> = emptySet(),
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    /** The last permission request ended with nothing granted. */
    val permissionDenied: Boolean = false,
    /** Whether history access (>30 days) is granted. */
    val historyGranted: Boolean = false,
) {
    val connected get() = enabled && granted.isNotEmpty()
}

enum class SyncOutcome { SKIPPED, SYNCED, UNAVAILABLE, NO_PERMISSION, FAILED }

/**
 * Keeps the health_days and exercise_sessions tables in step with Health Connect. Read-only: nothing is ever written to Health Connect.
 *
 * - Nothing is read until the user has connected (permission granted from the Settings screen).
 * - Syncs run only while the app is in use (on start, and from the Sync button); there is no background job, so no extra
 *   background permission and no battery cost. Auto syncs are limited to one every [AUTO_INTERVAL_MS].
 * - One sync at a time ([mutex]); a sync is all-or-nothing: if any read fails, nothing is written and the next sync retries.
 * - Permissions are checked every time. If a permission was revoked, the stored data for that metric is deleted.
 */
class HealthSync(
    private val source: HealthSource,
    private val store: HealthStore,
    private val prefs: HealthPrefs,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        HealthUiState(
            enabled = prefs.enabled,
            lastSyncAt = prefs.lastSyncAt.takeIf { it > 0 },
            historyGranted = prefs.historyGranted,
        )
    )
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    /** The permission strings to hand to Health Connect's permission screen. */
    val permissions: Set<String> get() = source.permissions

    /** Read permissions for any data types not yet granted. */
    fun missingPermissions(): Set<String> {
        val ungranted = HealthMetric.values().toSet() - _state.value.granted
        val perms = source.permissionsFor(ungranted).toMutableSet()
        if (!_state.value.historyGranted) {
            perms.addAll(source.permissions.filter { it.endsWith("READ_HEALTH_DATA_HISTORY") })
        }
        return if (perms.isNotEmpty()) perms else permissions
    }

    fun day(day: Long): Flow<HealthDay?> = store.day(day)
    fun allDays(): Flow<List<HealthDay>> = store.allDays()
    fun daysSince(day: Long): Flow<List<HealthDay>> = store.daysSince(day)

    // ── entry points for the UI (fire and forget, safe to call from the main thread) ──

    /** App came to the foreground. Does nothing at all (not even a permission check) unless the user has connected; otherwise refreshes and syncs if due. */
    fun onForeground() { scope.launch { if (prefs.enabled) { refresh(); sync(force = false) } } }
    fun requestRefresh() { scope.launch { refresh() } }
    fun requestSync() { scope.launch { sync(force = true) } }
    fun requestPermissionResult() { scope.launch { onPermissionResult() } }
    fun requestDisconnect() { scope.launch { disconnect() } }

    // ── the work ──

    /** Re-checks availability and which permissions are granted. Reads no health data. */
    suspend fun refresh() {
        val av = source.availability()
        if (av != Availability.AVAILABLE) { _state.update { it.copy(availability = av, granted = emptySet(), historyGranted = false) }; return }
        try {
            val g = source.grantedMetrics()
            val hist = source.hasHistoryAccess()
            _state.update { it.copy(availability = av, granted = g, historyGranted = hist) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { _state.update { it.copy(availability = av, error = messageFor(e)) } }
    }

    /** Called when the Health Connect permission screen closes. Enables the integration if anything was granted. */
    suspend fun onPermissionResult(): SyncOutcome {
        val g = try { source.grantedMetrics() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(error = messageFor(e)) }; return SyncOutcome.FAILED }
        val hist = source.hasHistoryAccess()
        _state.update { it.copy(granted = g, permissionDenied = g.isEmpty(), error = null, historyGranted = hist) }
        if (g.isEmpty()) return SyncOutcome.NO_PERMISSION
        prefs.enabled = true
        _state.update { it.copy(enabled = true) }
        return sync(force = true)
    }

    suspend fun sync(force: Boolean = false): SyncOutcome = syncLocked(force)

    private suspend fun syncLocked(force: Boolean): SyncOutcome = mutex.withLock {
        if (!prefs.enabled) return@withLock SyncOutcome.SKIPPED
        val now = clock()
        if (!force && prefs.lastSyncAt > 0 && now - prefs.lastSyncAt in 0 until AUTO_INTERVAL_MS) return@withLock SyncOutcome.SKIPPED
        val av = source.availability()
        _state.update { it.copy(availability = av) }
        if (av != Availability.AVAILABLE) return@withLock SyncOutcome.UNAVAILABLE
        _state.update { it.copy(syncing = true, error = null) }
        try {
            val granted = source.grantedMetrics()
            reconcile(granted)
            if (granted.isEmpty()) return@withLock SyncOutcome.NO_PERMISSION
            val z = zone(); val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()

            val hasHistory = source.hasHistoryAccess()
            val needsFullBackfill = hasHistory && !prefs.historyGranted
            val backfillDays = if (hasHistory) HealthMath.KEEP_DAYS else HealthMath.BACKFILL_DAYS
            val lastDay = if (needsFullBackfill) null else prefs.lastSyncedDay?.let { LocalDate.ofEpochDay(it) }
            val (from, to) = HealthMath.syncWindow(today, lastDay, backfillDays)

            // Read sleep (including stage breakdowns)
            val sleepStages = if (HealthMetric.SLEEP in granted)
                HealthMath.sleepSummaryByWakeDay(source.readSleep(from.minusDays(1).atStartOfDay(z).toInstant(), to.plusDays(1).atStartOfDay(z).toInstant()), z)
            else emptyMap()
            val sleep = sleepStages.mapValues { it.value.totalMinutes }

            val activityMetrics = granted - HealthMetric.SLEEP
            val activity = if (activityMetrics.isNotEmpty()) source.readActivity(from, to, activityMetrics) else emptyMap()

            val plan = HealthMath.planWrite(
                existing = store.range(from.toEpochDay(), to.toEpochDay()),
                from = from,
                to = to,
                sleep = sleep,
                activity = activity,
                granted = granted,
                now = now,
                sleepStages = sleepStages,
                zone = z,
            )
            store.apply(plan, today.minusDays(HealthMath.KEEP_DAYS.toLong()).toEpochDay())
            prefs.lastSyncAt = now; prefs.lastSyncedDay = today.toEpochDay()
            if (hasHistory) prefs.historyGranted = true
            _state.update { it.copy(lastSyncAt = now, historyGranted = hasHistory) }
            SyncOutcome.SYNCED
        } catch (e: CancellationException) { throw e
        } catch (e: HealthPermissionLost) {
            runCatching { reconcile(source.grantedMetrics()) }
            _state.update { it.copy(error = "Access to Health Connect was removed. Allow it again to keep syncing.") }
            SyncOutcome.NO_PERMISSION
        } catch (e: Exception) {
            _state.update { it.copy(error = messageFor(e)) }
            SyncOutcome.FAILED
        } finally { _state.update { it.copy(syncing = false) } }
    }

    /** Stops syncing and deletes everything copied from Health Connect. Health Connect itself is untouched. */
    suspend fun disconnect() = mutex.withLock {
        prefs.enabled = false; prefs.lastSyncAt = 0; prefs.lastSyncedDay = null; prefs.knownGranted = emptySet(); prefs.historyGranted = false
        store.clear()
        _state.update { it.copy(enabled = false, lastSyncAt = null, syncing = false, error = null, permissionDenied = false, historyGranted = false) }
    }

    /** Updates the shown permissions and deletes stored data for any metric whose permission has been taken away. */
    private suspend fun reconcile(granted: Set<HealthMetric>) {
        val hasHistory = source.hasHistoryAccess()
        if (!hasHistory) prefs.historyGranted = false
        _state.update { it.copy(granted = granted, historyGranted = hasHistory) }
        val removed = prefs.knownGranted - granted
        if (removed.isNotEmpty()) {
            store.apply(HealthMath.withoutMetrics(store.all(), removed), null)
        }
        prefs.knownGranted = granted
    }

    private fun messageFor(e: Exception) =
        (e as? HealthReadFailed)?.message ?: "Couldn't read from Health Connect. Try again later."

    companion object {
        /** Automatic syncs (app start) are skipped if the last one was more recent than this. The Sync button ignores it. */
        const val AUTO_INTERVAL_MS = 3 * 60 * 60 * 1000L
    }
}
