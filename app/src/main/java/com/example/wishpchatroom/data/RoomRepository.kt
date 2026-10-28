package com.example.wishpchatroom.data

interface RoomRepository {
    suspend fun createRoom(username: String, isTemporary: Boolean, mission: MissionSpec? = null): Resource<String>

    suspend fun joinRoom(roomCode: String, username: String): Resource<Boolean>
}
