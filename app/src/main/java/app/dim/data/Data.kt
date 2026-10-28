package app.dim.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.dim.data.health.HealthDao
import app.dim.data.health.HealthDay
import app.dim.data.health.WritePlan
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "episodes", indices = [Index(value = ["uid"], unique = true)])
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val pain: Int? = null,
    val stress: Int? = null,
    val symptoms: String = "",   // comma-separated
    val triggers: String = "",   // comma-separated
    val medication: String = "",
    @ColumnInfo(defaultValue = "''") val location: String = "",   // comma-separated
    @ColumnInfo(defaultValue = "0") val aura: Boolean = false,
    val hydration: Int? = null,  // 0: <500 ml, 1: 500 ml-1 L, 2: >1 L
    val meal: Int? = null,       // 0: <2 h ago, 1: 2-6 h, 2: >6 h
    val caffeine: Int? = null,   // 0: none, 1: 1 cup, 2: more than 1
    @ColumnInfo(defaultValue = "0") val startedInSleep: Boolean = false,   // the attack began while asleep (woke up with it)
    /** Stable identity that survives backup / restore (the row [id] is device-local). Never changes after creation. */
    val uid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "''") val notes: String = "",   // optional free-text note from the log flow
    @ColumnInfo(defaultValue = "''") val onsetPlace: String = "",   // where the user was when it started: one of PLACES (Home, Work, ...), "" = not set
)

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes ORDER BY startedAt DESC") fun all(): Flow<List<Episode>>
    @Query("SELECT * FROM episodes WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1") fun active(): Flow<Episode?>
    @Query("SELECT * FROM episodes WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun activeNow(): Episode?
    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun get(id: Long): Episode?
    /** Atomic and idempotent: only rows still running are touched, so repeated calls can't change an end time or add rows. */
    @Query("UPDATE episodes SET endedAt = :now WHERE endedAt IS NULL") suspend fun endActive(now: Long): Int
    @Query("SELECT * FROM episodes ORDER BY startedAt") suspend fun allNow(): List<Episode>
    @Upsert suspend fun upsert(e: Episode): Long
    /** Restore only: rows must have id = 0. A uid clash is ignored (-1) rather than failing the whole restore. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(l: List<Episode>): List<Long>
    /** Restore (Replace) only: a running episode is never touched. */
    @Query("DELETE FROM episodes WHERE endedAt IS NOT NULL") suspend fun deleteFinished(): Int
    @Delete suspend fun delete(e: Episode)
}

@Database(entities = [Episode::class, HealthDay::class], version = 8, exportSchema = false)
abstract class DimDb : RoomDatabase() { abstract fun dao(): EpisodeDao; abstract fun health(): HealthDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE episodes ADD COLUMN location TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE episodes ADD COLUMN aura INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE episodes ADD COLUMN hydration INTEGER")
        db.execSQL("ALTER TABLE episodes ADD COLUMN meal INTEGER")
        db.execSQL("ALTER TABLE episodes ADD COLUMN caffeine INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE episodes ADD COLUMN startedInSleep INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds [Episode.uid]: existing rows get a random unique id. The table is rebuilt so it matches what Room expects exactly. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cols = "id, startedAt, endedAt, pain, stress, symptoms, triggers, medication, location, aura, hydration, meal, caffeine, startedInSleep"
        db.execSQL("CREATE TABLE `episodes_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, " +
            "`endedAt` INTEGER, `pain` INTEGER, `stress` INTEGER, `symptoms` TEXT NOT NULL, `triggers` TEXT NOT NULL, " +
            "`medication` TEXT NOT NULL, `location` TEXT NOT NULL DEFAULT '', `aura` INTEGER NOT NULL DEFAULT 0, " +
            "`hydration` INTEGER, `meal` INTEGER, `caffeine` INTEGER, `startedInSleep` INTEGER NOT NULL DEFAULT 0, `uid` TEXT NOT NULL)")
        db.execSQL("INSERT INTO episodes_new ($cols, uid) SELECT $cols, lower(hex(randomblob(16))) FROM episodes")
        db.execSQL("DROP TABLE episodes")
        db.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
        db.execSQL("CREATE UNIQUE INDEX `index_episodes_uid` ON `episodes` (`uid`)")
    }
}

/** Adds the health_days table (daily sleep / steps / heart rate copied from Health Connect). Existing tables and rows are not touched. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `health_days` (`day` INTEGER NOT NULL, `sleepMinutes` INTEGER, `steps` INTEGER, " +
            "`hrAvg` INTEGER, `hrMin` INTEGER, `hrMax` INTEGER, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`day`))")
    }
}

/** Adds HRV, resting HR, and sleep stages. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `health_days` ADD COLUMN `sleepAwakeMinutes` INTEGER")
        db.execSQL("ALTER TABLE `health_days` ADD COLUMN `sleepLightMinutes` INTEGER")
        db.execSQL("ALTER TABLE `health_days` ADD COLUMN `sleepDeepMinutes` INTEGER")
        db.execSQL("ALTER TABLE `health_days` ADD COLUMN `sleepRemMinutes` INTEGER")
        db.execSQL("ALTER TABLE `health_days` ADD COLUMN `restingHr` INTEGER")
        db.execSQL("ALTER TABLE `health_days` ADD COLUMN `hrvRmssd` REAL")
    }
}

/** Adds the optional per-episode note. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''")
    }
}

/** Adds the optional "where were you when it started" answer. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `onsetPlace` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Default length for a past entry added from History: the average finished episode of the last 30 days (same window as
 * Insights), else the average of all finished episodes, else 1 hour. Whole minutes; zero-length episodes are ignored.
 */
fun typicalDurationMillis(all: List<Episode>, now: Long = System.currentTimeMillis()): Long {
    fun avg(list: List<Episode>): Long? = list.mapNotNull { e -> e.endedAt?.let { it - e.startedAt } }
        .filter { it >= 60_000 }.takeIf { it.isNotEmpty() }?.average()?.toLong()?.let { it / 60_000 * 60_000 }
    val zone = java.time.ZoneId.systemDefault()
    val firstDay = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(29)
    val recent = all.filter { java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() >= firstDay }
    return avg(recent)?.takeIf { it >= 60_000 } ?: avg(all)?.takeIf { it >= 60_000 } ?: 60 * 60_000L
}

class Repo(ctx: Context) {
    private val db = Room.databaseBuilder(ctx.applicationContext, DimDb::class.java, "dim.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
    private val dao = db.dao()
    private val healthDao = db.health()
    val episodes: Flow<List<Episode>> = dao.all()
    val active: Flow<Episode?> = dao.active()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun activeNow() = dao.activeNow()
    suspend fun save(e: Episode) = dao.upsert(e)
    suspend fun delete(e: Episode) = dao.delete(e)
    /** Runs [block] as one all-or-nothing database transaction (used by restore). */
    suspend fun <R> transaction(block: suspend EpisodeDao.() -> R): R = db.withTransaction { dao.block() }
    /** Every finished episode plus the running one, oldest first. */
    suspend fun snapshot(): List<Episode> = dao.allNow()
    /** Ends the running episode if there is one (never starts one). Returns true if an episode was ended. */
    suspend fun endActive(now: Long = System.currentTimeMillis()) = dao.endActive(now) > 0

    // ---- Health Connect copy (see app.dim.data.health). Never touches the episodes table. ----
    fun healthDay(day: Long): Flow<HealthDay?> = healthDao.day(day)
    suspend fun healthDayNow(day: Long): HealthDay? = healthDao.getDay(day)
    fun healthAllDays(): Flow<List<HealthDay>> = healthDao.allDays()
    fun healthDaysSince(day: Long): Flow<List<HealthDay>> = healthDao.since(day)
    suspend fun healthRange(from: Long, to: Long): List<HealthDay> = healthDao.range(from, to)
    suspend fun healthAll(): List<HealthDay> = healthDao.allNow()
    /** One all-or-nothing transaction: the planned rows, the planned deletions, and (optionally) removal of days older than [pruneBefore]. */
    suspend fun applyHealth(plan: WritePlan, pruneBefore: Long?) = db.withTransaction {
        if (plan.upserts.isNotEmpty()) healthDao.upsertAll(plan.upserts)
        if (plan.deletes.isNotEmpty()) healthDao.deleteDays(plan.deletes)
        if (pruneBefore != null) {
            healthDao.deleteBefore(pruneBefore)
        }
        Unit
    }
    suspend fun clearHealth() = db.withTransaction {
        healthDao.clear()
    }

    /** One tap: ends the active episode if one is running, otherwise starts a new one. */
    suspend fun toggle(now: Long = System.currentTimeMillis()) {
        val cur = dao.activeNow()
        if (cur != null) dao.upsert(cur.copy(endedAt = now)) else dao.upsert(Episode(startedAt = now))
    }
}
