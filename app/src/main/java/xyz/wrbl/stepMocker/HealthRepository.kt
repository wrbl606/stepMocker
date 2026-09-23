package xyz.wrbl.stepMocker

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class Bar(val start: Instant, val end: Instant, val steps: Long)

class HealthRepository(private val context: Context) {
    val status get() = HealthConnectClient.getSdkStatus(context)
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getWritePermission(StepsRecord::class))
    val supportsHistory get() = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    suspend fun granted() = client.permissionController.getGrantedPermissions()

    suspend fun bars(period: Period): List<Bar> {
        val results = client.aggregateGroupByDuration(AggregateGroupByDurationRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(period.start, period.end),
            timeRangeSlicer = Duration.ofHours(period.bucketHours),
        )).associateBy { it.startTime }
        return buildList {
            var start = period.start
            while (start < period.end) {
                val end = minOf(start.plus(Duration.ofHours(period.bucketHours)), period.end)
                add(Bar(start, end, results[start]?.result?.get(StepsRecord.COUNT_TOTAL) ?: 0))
                start = end
            }
        }
    }

    suspend fun records(period: Period): List<StepsRecord> {
        val records = mutableListOf<StepsRecord>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(period.start, period.end),
                ascendingOrder = false, pageSize = 1000, pageToken = token,
            ))
            records += page.records
            token = page.pageToken
        } while (!token.isNullOrEmpty())
        return records.sortedByDescending { it.startTime }
    }

    suspend fun insert(batches: List<StepBatch>, progress: (Int) -> Unit) {
        val runId = UUID.randomUUID().toString()
        val zone = ZoneId.systemDefault()
        val records = batches.mapIndexed { index, batch ->
            val device = Device(manufacturer = batch.device.manufacturer, model = batch.device.model,
                type = when (batch.device.type) { "Phone" -> Device.TYPE_PHONE; "Watch" -> Device.TYPE_WATCH; else -> Device.TYPE_FITNESS_BAND })
            val id = "step-mocker:$runId:$index"
            val metadata = when (batch.method) {
                Method.AUTOMATIC -> Metadata.autoRecorded(clientRecordId = id, device = device)
                Method.ACTIVE -> Metadata.activelyRecorded(clientRecordId = id, device = device)
                Method.MANUAL -> Metadata.manualEntry(clientRecordId = id, device = device)
            }
            StepsRecord(startTime = batch.start, endTime = batch.end,
                startZoneOffset = zone.rules.getOffset(batch.start), endZoneOffset = zone.rules.getOffset(batch.end),
                count = batch.count, metadata = metadata)
        }
        var written = 0
        for (chunk in records.chunked(500)) {
            client.insertRecords(chunk)
            written += chunk.size
            progress(written)
        }
    }

    suspend fun delete(records: List<StepsRecord>, progress: (Int) -> Unit) {
        require(records.all { it.metadata.dataOrigin.packageName == context.packageName }) { "Only this app's records can be deleted here." }
        var deleted = 0
        records.chunked(500).forEach { chunk ->
            client.deleteRecords(StepsRecord::class, chunk.map { it.metadata.id }, emptyList())
            deleted += chunk.size
            progress(deleted)
        }
    }
}
