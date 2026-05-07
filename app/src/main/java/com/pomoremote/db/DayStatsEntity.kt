// app/src/main/java/com/pomoremote/db/DayStatsEntity.kt
package com.pomoremote.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached daily statistics from the server.
 * Date format: "yyyy-MM-dd"
 */
@Entity(tableName = "day_stats")
public data class DayStatsEntity(
    @PrimaryKey
    val date: String,
    val completed: Int,
    val workMinutes: Int,
    val breakMinutes: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
)
