package com.example.wishpchatroom.data

interface RoomRepository {
    suspend fun createRoom(username: String, isTemporary: Boolean): Result<String>

    suspend fun joinRoom(roomCode: String, username: String): Result<Boolean>
}
