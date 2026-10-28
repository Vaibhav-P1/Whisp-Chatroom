package com.example.wishpchatroom.data.local

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Messages that are SENDING or FAILED live only in this database, so a schema change must keep them.
 * The test opens a real version 2 database (from the exported schema), migrates it and checks the rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val dbName = "migration-test"

    private fun insertV2(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, status: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("roomCode", "ABC123")
            put("uid", "me")
            put("username", "ada")
            put("messageText", "text $id")
            put("timestamp", 1_000L)
            put("status", status)
            put("edited", 0)
            put("deleted", 0)
            put("reactionsJson", "{}")
        }
        db.insert("messages", 0, values)
    }

    @Test
    fun `migrating from 2 to 3 keeps queued and sent messages and adds empty whisper columns`() {
        helper.createDatabase(dbName, 2).apply {
            insertV2(this, "queued", "SENDING")
            insertV2(this, "failed", "FAILED")
            insertV2(this, "sent", "SENT")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        migrated.query("SELECT id, status, ttlSeconds, firstViewedAt, hideAt FROM messages ORDER BY id").use { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.moveToNext()) {
                rows += listOf(
                    cursor.getString(0), cursor.getString(1),
                    if (cursor.isNull(2)) null else cursor.getLong(2).toString(),
                    if (cursor.isNull(3)) null else cursor.getLong(3).toString(),
                    if (cursor.isNull(4)) null else cursor.getLong(4).toString()
                )
            }
            assertEquals(3, rows.size)
            assertEquals(listOf("failed", "queued", "sent"), rows.map { it[0] })
            // Statuses survive, so nothing waiting to be sent is lost.
            assertEquals(listOf("FAILED", "SENDING", "SENT"), rows.map { it[1] })
            // Old rows are normal messages: no whisper data.
            assertTrue(rows.all { it[2] == null && it[3] == null && it[4] == null })
        }
    }

    @Test
    fun `the migrated database accepts whisper rows`() {
        helper.createDatabase(dbName, 2).close()
        val migrated = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        val values = ContentValues().apply {
            put("id", "w1")
            put("roomCode", "ABC123")
            put("uid", "me")
            put("username", "ada")
            put("messageText", "secret")
            put("timestamp", 1_000L)
            put("status", "SENT")
            put("edited", 0)
            put("deleted", 0)
            put("reactionsJson", "{}")
            put("ttlSeconds", 60L)
            put("hideAt", 90_000_000L)
        }
        assertFalse(migrated.insert("messages", 0, values) == -1L)
        migrated.query("SELECT ttlSeconds, hideAt FROM messages WHERE id = 'w1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(60L, it.getLong(0))
            assertEquals(90_000_000L, it.getLong(1))
        }
    }
}
