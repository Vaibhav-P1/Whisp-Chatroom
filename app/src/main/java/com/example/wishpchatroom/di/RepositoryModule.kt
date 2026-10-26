package com.example.wishpchatroom.di

import com.example.wishpchatroom.data.ChatRepository
import com.example.wishpchatroom.data.FirestoreRoomRepository
import com.example.wishpchatroom.data.FirestoreUserRepository
import com.example.wishpchatroom.data.OfflineFirstChatRepository
import com.example.wishpchatroom.data.RoomRepository
import com.example.wishpchatroom.data.UserRepository
import com.example.wishpchatroom.data.remote.ChatRemoteDataSource
import com.example.wishpchatroom.data.remote.FirestoreChatRemoteDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FirestoreUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindRoomRepository(impl: FirestoreRoomRepository): RoomRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: OfflineFirstChatRepository): ChatRepository

    @Binds
    @Singleton
    abstract fun bindChatRemoteDataSource(impl: FirestoreChatRemoteDataSource): ChatRemoteDataSource
}
