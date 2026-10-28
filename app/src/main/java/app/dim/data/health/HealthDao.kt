package app.dim.data.health

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Query("SELECT * FROM health_days WHERE day = :day LIMIT 1") fun day(day: Long): Flow<HealthDay?>
    @Query("SELECT * FROM health_days WHERE day = :day LIMIT 1") suspend fun getDay(day: Long): HealthDay?
    @Query("SELECT * FROM health_days ORDER BY day") fun allDays(): Flow<List<HealthDay>>
    @Query("SELECT * FROM health_days WHERE day >= :since ORDER BY day") fun since(since: Long): Flow<List<HealthDay>>
    @Query("SELECT * FROM health_days WHERE day BETWEEN :from AND :to ORDER BY day") suspend fun range(from: Long, to: Long): List<HealthDay>
    /** Keyed by day: an existing day is replaced, a new day is added. Never creates a second row for a day. */
    @Upsert suspend fun upsertAll(rows: List<HealthDay>)
    @Query("DELETE FROM health_days WHERE day IN (:days)") suspend fun deleteDays(days: List<Long>)
    @Query("DELETE FROM health_days WHERE day < :before") suspend fun deleteBefore(before: Long): Int
    @Query("SELECT * FROM health_days") suspend fun allNow(): List<HealthDay>
    @Query("DELETE FROM health_days") suspend fun clear()
}
