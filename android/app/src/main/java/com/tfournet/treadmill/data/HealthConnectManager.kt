package com.tfournet.treadmill.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(context: Context) {
    private val client = HealthConnectClient.getOrCreate(context)

    val permissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    suspend fun hasPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return permissions.all { it in granted }
    }

    suspend fun writeSteps(interval: StepInterval) {
        val startInstant = Instant.parse(interval.period_start)
        val endInstant = Instant.parse(interval.period_end)
        val zone = ZoneOffset.systemDefault().rules.getOffset(startInstant)

        val record = StepsRecord(
            count = interval.step_count.toLong(),
            startTime = startInstant,
            endTime = endInstant,
            startZoneOffset = zone,
            endZoneOffset = zone,
            metadata = Metadata(
                dataOrigin = DataOrigin("com.tfournet.treadmill"),
                clientRecordId = "interval-${interval.id}",
            ),
        )
        client.insertRecords(listOf(record))
    }

    companion object {
        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
}
