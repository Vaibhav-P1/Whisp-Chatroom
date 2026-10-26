package com.example.wishpchatroom.data

import com.example.wishpchatroom.data.local.MessageDao
import com.example.wishpchatroom.data.local.MessageEntity
import com.example.wishpchatroom.data.local.toDomain
import com.example.wishpchatroom.data.remote.ChatRemoteDataSource
import com.example.wishpchatroom.data.remote.RemoteMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat repository whose source of truth is the local database. The UI only ever reads the cache;
 * the network writes into it, so chat history is available offline and sent messages appear at once.
 */
@Singleton
class OfflineFirstChatRepository @Inject constructor(
    private val remote: ChatRemoteDataSource,
    private val messageDao: MessageDao
) : ChatRepository {

    override val currentUid: String? get() = remote.currentUid

    override fun observeMessages(roomCode: String, limit: Int): Flow<List<Message>> =
        messageDao.observeLatest(roomCode, limit).map { entities ->
            // The query is newest first (so LIMIT keeps the latest); the UI wants oldest first.
            entities.asReversed().map { it.toDomain() }
        }

    override suspend fun syncMessages(roomCode: String) {
        remote.observeLatestMessages(roomCode, SYNC_WINDOW).collect { messages ->
            messageDao.upsertAll(messages.map { it.toEntity(roomCode) })
        }
    }

    override suspend fun sendMessage(roomCode: String, messageText: String, username: String): Resource<Unit> {
        val uid = remote.currentUid ?: return Resource.Error("You must be logged in")
        val id = UUID.randomUUID().toString()

        messageDao.upsert(
            MessageEntity(
                id = id,
                roomCode = roomCode,
                uid = uid,
                username = username,
                messageText = messageText,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING
            )
        )
        return deliver(roomCode, id, username, messageText)
    }

    override suspend fun retryMessage(roomCode: String, messageId: String): Resource<Unit> {
        val message = messageDao.getById(messageId) ?: return Resource.Error("Message not found")
        if (message.status != MessageStatus.FAILED) return Resource.Success(Unit)

        messageDao.updateStatus(messageId, MessageStatus.SENDING)
        return deliver(roomCode, messageId, message.username, message.messageText)
    }

    override suspend fun getRoom(roomCode: String): Room? = remote.getRoom(roomCode)

    override suspend fun closeRoom(roomCode: String): Resource<Unit> = remote.closeRoom(roomCode)

    override suspend fun deleteRoom(roomCode: String): Resource<Unit> {
        val result = remote.deleteRoom(roomCode)
        if (result is Resource.Success) messageDao.deleteRoom(roomCode)
        return result
    }

    override suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) =
        remote.updateUserPresence(roomCode, username, isPresent)

    override suspend fun deleteIfTemporaryOwner(roomCode: String) {
        val uid = remote.currentUid ?: return
        val room = remote.getRoom(roomCode) ?: return
        if (room.temporary && room.creatorUid == uid) {
            deleteRoom(roomCode)
        }
    }

    private suspend fun deliver(
        roomCode: String,
        id: String,
        username: String,
        messageText: String
    ): Resource<Unit> {
        val result = remote.sendMessage(roomCode, id, username, messageText)
        messageDao.updateStatus(
            id,
            if (result is Resource.Success) MessageStatus.SENT else MessageStatus.FAILED
        )
        return result
    }

    private fun RemoteMessage.toEntity(roomCode: String) = MessageEntity(
        id = id,
        roomCode = roomCode,
        uid = uid,
        username = username,
        messageText = messageText,
        timestamp = timestamp,
        status = if (hasPendingWrites) MessageStatus.SENDING else MessageStatus.SENT
    )

    private companion object {
        // How many of the newest messages the server listener keeps in sync (also bounds Firestore reads).
        const val SYNC_WINDOW = 50L
    }
}
