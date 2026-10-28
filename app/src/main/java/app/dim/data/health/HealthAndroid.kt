package app.dim.data.health

import android.content.Context
import app.dim.data.Repo
import kotlinx.coroutines.flow.Flow

/** [HealthStore] on top of the app's Room database. */
class RepoHealthStore(private val repo: Repo) : HealthStore {
    override fun day(day: Long): Flow<HealthDay?> = repo.healthDay(day)
    override fun allDays(): Flow<List<HealthDay>> = repo.healthAllDays()
    override fun daysSince(day: Long): Flow<List<HealthDay>> = repo.healthDaysSince(day)
    override suspend fun range(from: Long, to: Long) = repo.healthRange(from, to)
    override suspend fun all() = repo.healthAll()
    override suspend fun apply(plan: WritePlan, pruneBefore: Long?) { repo.applyHealth(plan, pruneBefore) }
    override suspend fun clear() { repo.clearHealth() }
}

/** [HealthPrefs] in SharedPreferences. A separate file from the profile and backup settings, and not part of backups. */
class SharedHealthPrefs(ctx: Context) : HealthPrefs {
    private val p = ctx.applicationContext.getSharedPreferences("dim_health", Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) { p.edit().putBoolean("enabled", v).apply() }
    override var lastSyncAt: Long
        get() = p.getLong("lastSyncAt", 0)
        set(v) { p.edit().putLong("lastSyncAt", v).apply() }
    override var lastSyncedDay: Long?
        get() = if (p.contains("lastSyncedDay")) p.getLong("lastSyncedDay", 0) else null
        set(v) { val e = p.edit(); if (v == null) e.remove("lastSyncedDay") else e.putLong("lastSyncedDay", v); e.apply() }
    override var knownGranted: Set<HealthMetric>
        get() = (p.getString("knownGranted", "") ?: "").split(",").mapNotNull { n -> HealthMetric.values().firstOrNull { it.name == n } }.toSet()
        set(v) { p.edit().putString("knownGranted", v.joinToString(",") { it.name }).apply() }
    override var historyGranted: Boolean
        get() = p.getBoolean("historyGranted", false)
        set(v) { p.edit().putBoolean("historyGranted", v).apply() }
}
