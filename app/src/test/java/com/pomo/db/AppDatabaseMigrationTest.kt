package com.pomo.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class AppDatabaseMigrationTest {
    @Test
    public fun migrationSevenToEightPreservesTimerAndCrewRowsWhileAddingDormantSyncTables() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(7) {
                            override fun onCreate(db: SupportSQLiteDatabase) {}

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {}
                        },
                    )
                    .build(),
            )
        helper.writableDatabase.use { database ->
            database.execSQL(
                "CREATE TABLE day_stats (date TEXT NOT NULL PRIMARY KEY, completed INTEGER NOT NULL, " +
                    "workMinutes INTEGER NOT NULL, breakMinutes INTEGER NOT NULL, lastUpdated INTEGER NOT NULL)",
            )
            database.execSQL(
                "CREATE TABLE crew_snapshots (crewId TEXT NOT NULL, identityPublicKey TEXT NOT NULL, " +
                    "displayName TEXT NOT NULL, PRIMARY KEY(crewId, identityPublicKey))",
            )
            database.execSQL("INSERT INTO day_stats VALUES ('2026-08-14', 2, 50, 10, 123)")
            database.execSQL("INSERT INTO crew_snapshots VALUES ('crew', 'identity', 'Asha')")

            AppDatabase.MIGRATION_7_8.migrate(database)

            assertEquals(2, scalarInt(database, "SELECT completed FROM day_stats WHERE date = '2026-08-14'"))
            assertEquals("Asha", scalarText(database, "SELECT displayName FROM crew_snapshots"))
            assertTrue(tableExists(database, "sync_operations"))
            assertTrue(tableExists(database, "sync_feed_heads"))
            assertTrue(tableExists(database, "sync_preference_projection"))
            assertTrue(tableExists(database, "sync_outbox"))
            assertTrue(tableExists(database, "sync_disposition_events"))
        }
        helper.close()
    }

    private fun scalarInt(
        database: SupportSQLiteDatabase,
        sql: String,
    ): Int =
        database.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun scalarText(
        database: SupportSQLiteDatabase,
        sql: String,
    ): String =
        database.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun tableExists(
        database: SupportSQLiteDatabase,
        table: String,
    ): Boolean =
        database.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
            cursor.moveToFirst()
        }
}
