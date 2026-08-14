package com.pomo.sync.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["deviceId", "incarnationId", "sequence"]),
        Index(value = ["disposition"]),
    ],
)
internal data class SyncOperationEntity(
    @PrimaryKey
    val operationId: String,
    val memberId: String,
    val deviceId: String,
    val incarnationId: String,
    val sequence: Long,
    val previousOperationId: String?,
    val signedWire: ByteArray,
    val preferenceKey: String,
    val preferenceValue: String,
    val disposition: String,
    val localAuthor: Boolean,
)

@Entity(
    tableName = "sync_feed_heads",
    primaryKeys = ["deviceId", "incarnationId"],
)
internal data class SyncFeedHeadEntity(
    val deviceId: String,
    val incarnationId: String,
    val sequence: Long,
    val operationId: String?,
    val forkedAt: Long?,
)

@Entity(tableName = "sync_preference_projection")
internal data class SyncPreferenceProjectionEntity(
    @PrimaryKey
    val preferenceKey: String,
    val preferenceValue: String,
    val operationId: String,
)

@Entity(
    tableName = "sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = SyncOperationEntity::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["state"])],
)
internal data class SyncOutboxEntity(
    @PrimaryKey
    val operationId: String,
    val signedWire: ByteArray,
    val state: String = "PENDING",
    val attemptCount: Int = 0,
)

@Entity(
    tableName = "sync_disposition_events",
    indices = [Index(value = ["disposition"])],
)
internal data class SyncDispositionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val operationId: String?,
    val disposition: String,
    val signedWire: ByteArray,
)

internal data class SyncDispositionCount(
    val disposition: String,
    val count: Int,
)
