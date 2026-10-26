package com.example.wishpchatroom.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

// The database is only a cache of what is on Firestore, so a destructive migration is acceptable
// when the schema changes: the data is simply downloaded again.
@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
