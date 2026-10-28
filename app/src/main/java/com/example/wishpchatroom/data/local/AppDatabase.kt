package com.example.wishpchatroom.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Messages waiting to be sent (SENDING or FAILED) exist only here, so from version 2 on the schema
// changes through real migrations that keep them. Version 1 was a pure cache and is still dropped.
@Database(entities = [MessageEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}

/** Version 3 adds the whisper columns. Existing rows are normal messages, so all three stay null. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN ttlSeconds INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN firstViewedAt INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN hideAt INTEGER")
    }
}
