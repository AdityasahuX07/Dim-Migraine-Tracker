package app.dim.data.health

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * The only place that talks to the Health Connect SDK. Strictly read-only: it requests read permissions
 * for sleep, steps, heart rate, resting heart rate, and HRV, and never writes or deletes anything in Health Connect.
 *
 * Requires in the app module: implementation("androidx.health.connect:connect-client:1.1.0")
 */
class HealthConnectSource(context: Context) : HealthSource {
    private val appContext: Context = context.applicationContext

    private var cachedClient: HealthConnectClient? = null
    private fun client(): HealthConnectClient {
        val existing: HealthConnectClient? = cachedClient
        if (existing != null) return existing
        val created: HealthConnectClient = HealthConnectClient.getOrCreate(appContext)
        cachedClient = created
        return created
    }

    private val readPermission: Map<HealthMetric, String> = mapOf(
        HealthMetric.SLEEP to HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthMetric.STEPS to HealthPermission.getReadPermission(StepsRecord::class),
        HealthMetric.HEART_RATE to HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthMetric.RESTING_HEART_RATE to HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthMetric.HEART_RATE_VARIABILITY to HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    )
    override val permissions: Set<String>
        get() = buildSet {
            addAll(readPermission.values)
            if (isHistoryFeatureAvailable()) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }

    override fun permissionsFor(metrics: Set<HealthMetric>): Set<String> = buildSet {
        metrics.forEach { readPermission[it]?.let { p -> add(p) } }
        if (isHistoryFeatureAvailable()) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    override suspend fun hasHistoryAccess(): Boolean = try {
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in client().permissionController.getGrantedPermissions()
    } catch (_: Exception) { false }

    private fun isHistoryFeatureAvailable(): Boolean = try {
        client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Exception) { false }

    override fun availability(): Availability {
        val status: Int = HealthConnectClient.getSdkStatus(appContext, PROVIDER)
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
            else -> Availability.UNAVAILABLE
        }
    }

    override suspend fun grantedMetrics(): Set<HealthMetric> {
        try {
            val granted: Set<String> = client().permissionController.getGrantedPermissions()
            val result = HashSet<HealthMetric>()
            for ((metric, permission) in readPermission) if (permission in granted) result.add(metric)
            return result
        } catch (e: Exception) { throw translate(e) }
    }

    override suspend fun readSleep(from: Instant, to: Instant): List<RawSleepSession> {
        try {
            val out = ArrayList<RawSleepSession>()
            var token: String? = null
            var pages = 0
            do {
                val response = client().readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageToken = token,
                    )
                )
                for (rec in response.records) {
                    val stages: List<RawStage> = rec.stages.map { st ->
                        RawStage(st.startTime, st.endTime, st.stage)
                    }
                    out.add(RawSleepSession(rec.startTime, rec.endTime, stages))
                }
                token = response.pageToken
                pages++
            } while (!token.isNullOrEmpty() && pages < MAX_PAGES)
            return out
        } catch (e: Exception) { throw translate(e) }
    }

    /**
     * Steps and heart rate are requested as one-day totals / statistics. Health Connect then removes duplicates
     * between apps and devices (per the user's app priorities), which raw records would double count.
     */
    override suspend fun readActivity(from: LocalDate, to: LocalDate, metrics: Set<HealthMetric>): Map<LocalDate, DayValues> {
        try {
            val range = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
            val steps = HashMap<LocalDate, Long>()
            val hrAvg = HashMap<LocalDate, Long>()
            val hrMin = HashMap<LocalDate, Long>()
            val hrMax = HashMap<LocalDate, Long>()
            val restingHr = HashMap<LocalDate, Long>()
            val hrvRmssd = HashMap<LocalDate, Double>()

            if (HealthMetric.STEPS in metrics) {
                val buckets = client().aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = range,
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
                for (b in buckets) {
                    val total: Long? = b.result[StepsRecord.COUNT_TOTAL]
                    if (total != null) steps[b.startTime.toLocalDate()] = total
                }
            }
            if (HealthMetric.HEART_RATE in metrics) {
                val buckets = client().aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                        timeRangeFilter = range,
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
                for (b in buckets) {
                    val day: LocalDate = b.startTime.toLocalDate()
                    val avg: Long? = b.result[HeartRateRecord.BPM_AVG]
                    val min: Long? = b.result[HeartRateRecord.BPM_MIN]
                    val max: Long? = b.result[HeartRateRecord.BPM_MAX]
                    if (avg != null) hrAvg[day] = avg
                    if (min != null) hrMin[day] = min
                    if (max != null) hrMax[day] = max
                }
            }
            if (HealthMetric.RESTING_HEART_RATE in metrics) {
                try {
                    val buckets = client().aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = setOf(RestingHeartRateRecord.BPM_AVG),
                            timeRangeFilter = range,
                            timeRangeSlicer = Period.ofDays(1),
                        )
                    )
                    for (b in buckets) {
                        val day: LocalDate = b.startTime.toLocalDate()
                        val avg: Long? = b.result[RestingHeartRateRecord.BPM_AVG]
                        if (avg != null) restingHr[day] = avg
                    }
                } catch (e: Exception) {
                    // Fallback to reading raw records if aggregate is not supported by the provider
                    val zone = ZoneId.systemDefault()
                    val startInst = from.atStartOfDay(zone).toInstant()
                    val endInst = to.plusDays(1).atStartOfDay(zone).toInstant()
                    val dayValues = HashMap<LocalDate, MutableList<Long>>()
                    var token: String? = null
                    var pages = 0
                    do {
                        val response = client().readRecords(
                            ReadRecordsRequest(
                                recordType = RestingHeartRateRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(startInst, endInst),
                                pageToken = token,
                            )
                        )
                        for (rec in response.records) {
                            val d = rec.time.atZone(zone).toLocalDate()
                            dayValues.getOrPut(d) { ArrayList() }.add(rec.beatsPerMinute)
                        }
                        token = response.pageToken
                        pages++
                    } while (!token.isNullOrEmpty() && pages < MAX_PAGES)
                    for ((d, list) in dayValues) {
                        if (list.isNotEmpty()) restingHr[d] = Math.round(list.average()).toLong()
                    }
                }
            }
            if (HealthMetric.HEART_RATE_VARIABILITY in metrics) {
                val zone = ZoneId.systemDefault()
                val startInst = from.atStartOfDay(zone).toInstant()
                val endInst = to.plusDays(1).atStartOfDay(zone).toInstant()
                val dayValues = HashMap<LocalDate, MutableList<Double>>()
                var token: String? = null
                var pages = 0
                do {
                    val response = client().readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateVariabilityRmssdRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startInst, endInst),
                            pageToken = token,
                        )
                    )
                    for (rec in response.records) {
                        val d = rec.time.atZone(zone).toLocalDate()
                        if (rec.heartRateVariabilityMillis in 1.0..200.0) {
                            dayValues.getOrPut(d) { ArrayList() }.add(rec.heartRateVariabilityMillis)
                        }
                    }
                    token = response.pageToken
                    pages++
                } while (!token.isNullOrEmpty() && pages < MAX_PAGES)
                for ((d, list) in dayValues) {
                    if (list.isNotEmpty()) hrvRmssd[d] = list.average()
                }
            }

            val days: Set<LocalDate> = steps.keys + hrAvg.keys + hrMin.keys + hrMax.keys + restingHr.keys + hrvRmssd.keys
            val out = HashMap<LocalDate, DayValues>()
            for (d in days) out[d] = DayValues(
                steps = steps[d],
                hrAvg = hrAvg[d],
                hrMin = hrMin[d],
                hrMax = hrMax[d],
                restingHr = restingHr[d],
                hrvRmssd = hrvRmssd[d],
            )
            return out
        } catch (e: Exception) { throw translate(e) }
    }

    /** Turns SDK failures into the two exceptions the rest of the app understands. Messages never contain health data. */
    private fun translate(e: Exception): Exception = when (e) {
        is CancellationException -> e                       // must propagate unchanged
        is HealthPermissionLost, is HealthReadFailed -> e
        is SecurityException -> HealthPermissionLost(e)
        is IOException, is RemoteException -> HealthReadFailed("Health Connect didn't respond. Try again in a moment.", e)
        is IllegalStateException -> HealthReadFailed("Health Connect isn't ready right now. Try again later.", e)
        else -> e
    }

    private companion object {
        const val PROVIDER: String = "com.google.android.apps.healthdata"
        const val MAX_PAGES: Int = 50
    }
}
