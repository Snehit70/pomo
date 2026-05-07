// app/src/main/java/com/pomoremote/db/SessionEntity.kt
package com.pomoremote.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Individual session record cached from server.
 * Linked to DayStatsEntity via date foreign key.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = DayStatsEntity::class,
            parentColumns = ["date"],
            childColumns = ["date"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("date")],
)
public data class SessionEntity(
    @PrimaryKey(autoGenerate = false)
    val start: Long,
    val date: String,
    val type: String,
    val duration: Int,
    val completed: Boolean,
    val synced: Boolean = true,
)
