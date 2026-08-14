package com.pomo.sync.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOperation(operation: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_operations WHERE operationId = :operationId")
    fun operation(operationId: String): SyncOperationEntity?

    @Query(
        "SELECT * FROM sync_operations " +
            "WHERE deviceId = :deviceId AND incarnationId = :incarnationId AND sequence = :sequence " +
            "ORDER BY operationId ASC",
    )
    fun operationsAt(deviceId: String, incarnationId: String, sequence: Long): List<SyncOperationEntity>

    @Query(
        "SELECT * FROM sync_operations " +
            "ORDER BY deviceId ASC, incarnationId ASC, sequence ASC, operationId ASC",
    )
    fun allOperations(): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_operations WHERE disposition = 'ACCEPTED' ORDER BY operationId ASC")
    fun acceptedOperations(): List<SyncOperationEntity>

    @Query(
        "SELECT * FROM sync_operations " +
            "WHERE deviceId = :deviceId AND incarnationId = :incarnationId " +
            "AND sequence = :sequence AND disposition = 'ACCEPTED' ORDER BY operationId ASC LIMIT 1",
    )
    fun acceptedAt(deviceId: String, incarnationId: String, sequence: Long): SyncOperationEntity?

    @Query(
        "UPDATE sync_operations SET disposition = 'QUARANTINED_FORK' " +
            "WHERE deviceId = :deviceId AND incarnationId = :incarnationId AND sequence >= :sequence",
    )
    fun quarantineTail(deviceId: String, incarnationId: String, sequence: Long)

    @Query("UPDATE sync_operations SET disposition = :disposition WHERE operationId = :operationId")
    fun updateDisposition(operationId: String, disposition: String): Int

    @Query("DELETE FROM sync_operations WHERE operationId = :operationId")
    fun deleteOperation(operationId: String): Int

    @Upsert
    fun upsertHead(head: SyncFeedHeadEntity)

    @Query("SELECT * FROM sync_feed_heads WHERE deviceId = :deviceId AND incarnationId = :incarnationId")
    fun head(deviceId: String, incarnationId: String): SyncFeedHeadEntity?

    @Query("SELECT * FROM sync_feed_heads ORDER BY deviceId ASC, incarnationId ASC")
    fun allHeads(): List<SyncFeedHeadEntity>

    @Upsert
    fun upsertProjection(projection: SyncPreferenceProjectionEntity)

    @Query("DELETE FROM sync_preference_projection")
    fun clearProjection()

    @Query("SELECT * FROM sync_preference_projection ORDER BY preferenceKey ASC")
    fun projection(): List<SyncPreferenceProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOutbox(outbox: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox WHERE state = 'PENDING' ORDER BY operationId ASC")
    fun pendingOutbox(): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE operationId = :operationId")
    fun deleteOutbox(operationId: String): Int

    @Insert
    fun insertDisposition(event: SyncDispositionEventEntity)

    @Query(
        "SELECT disposition, COUNT(*) AS count FROM sync_disposition_events " +
            "GROUP BY disposition ORDER BY disposition ASC",
    )
    fun dispositionCounts(): List<SyncDispositionCount>

    @Query("SELECT COUNT(*) FROM sync_operations")
    fun operationCount(): Int

    @Query("SELECT COUNT(*) FROM sync_feed_heads")
    fun headCount(): Int

    @Query("SELECT COUNT(*) FROM sync_preference_projection")
    fun projectionCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun outboxCount(): Int

    @Query("SELECT COUNT(*) FROM sync_disposition_events")
    fun dispositionEventCount(): Int
}
