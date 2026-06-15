package com.calyptra.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FR-13.8: v1 → v2 is additive — stats and whitelist data survive, and the
 *  new protection_events table is queryable. */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesDataAndAddsEventTable() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO BlockedStat (date, count) VALUES ('2026-06-10', 42)")
            execSQL(
                "INSERT INTO WhitelistedApp (packageName, appName, addedAt) " +
                    "VALUES ('com.sybo.subwaysurfers', 'Subway Surfers', 1718000000000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT count FROM BlockedStat WHERE date = '2026-06-10'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(42, cursor.getInt(0))
        }
        db.query("SELECT appName, addedAt FROM WhitelistedApp WHERE packageName = 'com.sybo.subwaysurfers'")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Subway Surfers", cursor.getString(0))
                assertEquals(1718000000000L, cursor.getLong(1))
            }

        // New table accepts writes and reads them back.
        db.execSQL(
            "INSERT INTO protection_events (timestampMillis, type) VALUES (1718000000001, 'ENABLED_USER')"
        )
        db.query("SELECT timestampMillis, type FROM protection_events").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1718000000001L, cursor.getLong(0))
            assertEquals("ENABLED_USER", cursor.getString(1))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
