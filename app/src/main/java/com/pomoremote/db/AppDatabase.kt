// app/src/main/java/com/pomoremote/db/AppDatabase.kt
package com.pomoremote.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DayStatsEntity::class, SessionEntity::class],
    version = 3,
    exportSchema = false,
)
public abstract class AppDatabase : RoomDatabase() {

    public abstract fun historyDao(): HistoryDao

    public companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        public fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pomoremote.db",
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
