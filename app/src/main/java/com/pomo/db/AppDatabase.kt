// app/src/main/java/com/pomo/db/AppDatabase.kt
package com.pomo.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pomo.sync.persistence.SyncDao
import com.pomo.sync.persistence.SyncDispositionEventEntity
import com.pomo.sync.persistence.SyncFeedHeadEntity
import com.pomo.sync.persistence.SyncOperationEntity
import com.pomo.sync.persistence.SyncOutboxEntity
import com.pomo.sync.persistence.SyncPreferenceProjectionEntity

@Database(
    entities = [
        DayStatsEntity::class,
        SessionEntity::class,
        CrewSnapshotEntity::class,
        CrewDailyAggregateEntity::class,
        CrewHiddenMemberEntity::class,
        CrewRelayStateEntity::class,
        SyncOperationEntity::class,
        SyncFeedHeadEntity::class,
        SyncPreferenceProjectionEntity::class,
        SyncOutboxEntity::class,
        SyncDispositionEventEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
public abstract class AppDatabase : RoomDatabase() {
    public abstract fun historyDao(): HistoryDao

    public abstract fun crewDao(): CrewDao

    internal abstract fun syncDao(): SyncDao

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
                "pomo.db",
            )
                .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .build()
        }

        public val MIGRATION_1_3: Migration =
            object : Migration(1, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migrateSessionsToStartPrimaryKey(db)
                }
            }

        public val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migrateSessionsToStartPrimaryKey(db)
                }
            }

        public val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    createCrewTables(db)
                }
            }

        public val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `crew_snapshots` ADD COLUMN `avatarBase64` TEXT")
                }
            }

        public val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sessions` ADD COLUMN `tag` TEXT")
                }
            }

        public val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `crew_snapshots` ADD COLUMN `statsJson` TEXT")
                }
            }

        public val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    createSyncTables(db)
                }
            }

        private fun createSyncTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_operations` (
                    `operationId` TEXT NOT NULL,
                    `memberId` TEXT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `incarnationId` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `previousOperationId` TEXT,
                    `signedWire` BLOB NOT NULL,
                    `preferenceKey` TEXT NOT NULL,
                    `preferenceValue` TEXT NOT NULL,
                    `disposition` TEXT NOT NULL,
                    `localAuthor` INTEGER NOT NULL,
                    PRIMARY KEY(`operationId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_operations_deviceId_incarnationId_sequence` " +
                    "ON `sync_operations` (`deviceId`, `incarnationId`, `sequence`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_operations_disposition` " +
                    "ON `sync_operations` (`disposition`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_feed_heads` (
                    `deviceId` TEXT NOT NULL,
                    `incarnationId` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `operationId` TEXT,
                    `forkedAt` INTEGER,
                    PRIMARY KEY(`deviceId`, `incarnationId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_preference_projection` (
                    `preferenceKey` TEXT NOT NULL,
                    `preferenceValue` TEXT NOT NULL,
                    `operationId` TEXT NOT NULL,
                    PRIMARY KEY(`preferenceKey`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_outbox` (
                    `operationId` TEXT NOT NULL,
                    `signedWire` BLOB NOT NULL,
                    `state` TEXT NOT NULL,
                    `attemptCount` INTEGER NOT NULL,
                    PRIMARY KEY(`operationId`),
                    FOREIGN KEY(`operationId`) REFERENCES `sync_operations`(`operationId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_state` ON `sync_outbox` (`state`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_disposition_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `operationId` TEXT,
                    `disposition` TEXT NOT NULL,
                    `signedWire` BLOB NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_disposition_events_disposition` " +
                    "ON `sync_disposition_events` (`disposition`)",
            )
        }

        private fun createCrewTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `crew_snapshots` (
                    `crewId` TEXT NOT NULL,
                    `identityPublicKey` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `allTimeFocusMinutes` INTEGER NOT NULL,
                    `publishedAtEpochSeconds` INTEGER NOT NULL,
                    `localDate` TEXT NOT NULL,
                    `utcOffsetMinutes` INTEGER NOT NULL,
                    `currentStreak` INTEGER NOT NULL,
                    `lastFocusedAtEpochSeconds` INTEGER NOT NULL,
                    `protocolVersion` INTEGER NOT NULL,
                    PRIMARY KEY(`crewId`, `identityPublicKey`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_crew_snapshots_crewId` ON `crew_snapshots` (`crewId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crew_snapshots_crewId_publishedAtEpochSeconds` " +
                    "ON `crew_snapshots` (`crewId`, `publishedAtEpochSeconds`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `crew_daily_aggregates` (
                    `crewId` TEXT NOT NULL,
                    `identityPublicKey` TEXT NOT NULL,
                    `localDate` TEXT NOT NULL,
                    `focusMinutes` INTEGER NOT NULL,
                    `completedWorkBlocks` INTEGER NOT NULL,
                    PRIMARY KEY(`crewId`, `identityPublicKey`, `localDate`),
                    FOREIGN KEY(`crewId`, `identityPublicKey`)
                        REFERENCES `crew_snapshots`(`crewId`, `identityPublicKey`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crew_daily_aggregates_crewId_identityPublicKey` " +
                    "ON `crew_daily_aggregates` (`crewId`, `identityPublicKey`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crew_daily_aggregates_crewId_localDate` " +
                    "ON `crew_daily_aggregates` (`crewId`, `localDate`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `crew_hidden_members` (
                    `crewId` TEXT NOT NULL,
                    `identityPublicKey` TEXT NOT NULL,
                    `hiddenAtEpochSeconds` INTEGER NOT NULL,
                    PRIMARY KEY(`crewId`, `identityPublicKey`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_crew_hidden_members_crewId` ON `crew_hidden_members` (`crewId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `crew_relay_state` (
                    `crewId` TEXT NOT NULL,
                    `relayUrl` TEXT NOT NULL,
                    `lastAttemptEpochSeconds` INTEGER NOT NULL,
                    `lastSuccessEpochSeconds` INTEGER,
                    `lastError` TEXT,
                    PRIMARY KEY(`crewId`, `relayUrl`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_crew_relay_state_crewId` ON `crew_relay_state` (`crewId`)")
        }

        private fun migrateSessionsToStartPrimaryKey(db: SupportSQLiteDatabase) {
            val sessionColumns = tableColumns(db, "sessions")
            if ("id" in sessionColumns) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions_new` (
                        `start` INTEGER NOT NULL,
                        `date` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `synced` INTEGER NOT NULL,
                        PRIMARY KEY(`start`),
                        FOREIGN KEY(`date`) REFERENCES `day_stats`(`date`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `sessions_new` (`start`, `date`, `type`, `duration`, `completed`, `synced`)
                    SELECT `start`, `date`, `type`, `duration`, `completed`, 1
                    FROM `sessions`
                    ORDER BY `id` ASC
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
            } else if ("synced" !in sessionColumns) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `synced` INTEGER NOT NULL DEFAULT 1")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_date` ON `sessions` (`date`)")
        }

        private fun tableColumns(
            db: SupportSQLiteDatabase,
            table: String,
        ): Set<String> {
            val cursor = db.query("PRAGMA table_info(`$table`)")
            return cursor.use {
                val nameIndex = it.getColumnIndexOrThrow("name")
                buildSet {
                    while (it.moveToNext()) {
                        add(it.getString(nameIndex))
                    }
                }
            }
        }
    }
}
