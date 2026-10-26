package com.example.wishpchatroom.di

import android.content.Context
import androidx.room.Room
import com.example.wishpchatroom.data.local.AppDatabase
import com.example.wishpchatroom.data.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "wishp.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()
}
