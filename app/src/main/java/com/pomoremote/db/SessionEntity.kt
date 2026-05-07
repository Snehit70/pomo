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
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("date")]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = false)
    val start: Long,        // Unix timestamp - used as Primary Key to prevent duplicates
    val date: String,
    val type: String,       // "work", "short", "long"
    val duration: Int,      // Duration in seconds
    val completed: Boolean,
    val synced: Boolean = true // Default to true for backward compatibility with server data
)
