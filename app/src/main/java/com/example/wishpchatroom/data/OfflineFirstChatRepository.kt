package com.example.wishpchatroom.data

import com.example.wishpchatroom.data.local.MessageDao
import com.example.wishpchatroom.data.local.MessageEntity
import com.example.wishpchatroom.data.local.encodeReactions
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

    override fun searchMessages(roomCode: String, query: String): Flow<List<Message>> {
        // Escape LIKE wildcards so that searching for "50%" or "a_b" matches those characters literally.
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return messageDao.search(roomCode, "%$escaped%", SEARCH_LIMIT).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncMessages(roomCode: String) {
        remote.observeLatestMessages(roomCode, SYNC_WINDOW).collect { messages ->
            messageDao.upsertAll(messages.map { it.toEntity(roomCode) })
        }
    }

    override suspend fun sendMessage(
        roomCode: String,
        messageText: String,
        username: String,
        replyTo: ReplyRef?
    ): Resource<Unit> {
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
                status = MessageStatus.SENDING,
                replyToId = replyTo?.messageId,
                replyToUsername = replyTo?.username,
                replyToText = replyTo?.text
            )
        )
        return deliver(roomCode, id, username, messageText, replyTo)
    }

    override suspend fun retryMessage(roomCode: String, messageId: String): Resource<Unit> {
        val message = messageDao.getById(messageId) ?: return Resource.Error("Message not found")
        if (message.status != MessageStatus.FAILED) return Resource.Success(Unit)

        messageDao.updateStatus(messageId, MessageStatus.SENDING)
        return deliver(roomCode, messageId, message.username, message.messageText, message.toDomain().replyTo)
    }

    override suspend fun editMessage(roomCode: String, messageId: String, newText: String): Resource<Unit> {
        val result = remote.editMessage(roomCode, messageId, newText)
        if (result is Resource.Success) messageDao.applyEdit(messageId, newText)
        return result
    }

    override suspend fun deleteMessage(roomCode: String, messageId: String): Resource<Unit> {
        val result = remote.deleteMessage(roomCode, messageId)
        if (result is Resource.Success) messageDao.markDeleted(messageId)
        return result
    }

    override suspend fun toggleReaction(roomCode: String, messageId: String, emoji: String): Resource<Unit> {
        val uid = remote.currentUid ?: return Resource.Error("You must be logged in")
        val message = messageDao.getById(messageId) ?: return Resource.Error("Message not found")

        val reactions = message.toDomain().reactions.toMutableMap()
        val current = reactions[emoji].orEmpty()
        val add = uid !in current

        val result = remote.setReaction(roomCode, messageId, emoji, add)
        if (result is Resource.Success) {
            val updated = if (add) current + uid else current - uid
            if (updated.isEmpty()) reactions.remove(emoji) else reactions[emoji] = updated
            messageDao.updateReactions(messageId, encodeReactions(reactions))
        }
        return result
    }

    override suspend fun hasCachedOlder(roomCode: String, shownCount: Int): Boolean =
        messageDao.count(roomCode) > shownCount

    override suspend fun fetchOlderMessages(roomCode: String): Resource<Boolean> {
        val oldest = messageDao.oldestSentTimestamp(roomCode) ?: return Resource.Success(false)
        return try {
            val older = remote.fetchOlderMessages(roomCode, oldest, PAGE_SIZE)
            messageDao.upsertAll(older.map { it.toEntity(roomCode) })
            // The page always contains the message we started from, so a full page means more may follow.
            Resource.Success(older.size >= PAGE_SIZE)
        } catch (e: Exception) {
            Resource.Error(e.toUserMessage("Could not load earlier messages."))
        }
    }

    override suspend fun getRoom(roomCode: String): Room? = remote.getRoom(roomCode)

    override fun observeRoom(roomCode: String): Flow<Room?> = remote.observeRoom(roomCode)

    override fun observePresence(roomCode: String): Flow<List<Presence>> = remote.observePresence(roomCode)

    override suspend fun leaveRoom(roomCode: String): Resource<Unit> {
        val result = remote.leaveRoom(roomCode)
        if (result is Resource.Success) messageDao.deleteRoom(roomCode)
        return result
    }

    override suspend fun forgetRoom(roomCode: String) = messageDao.deleteRoom(roomCode)

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
        messageText: String,
        replyTo: ReplyRef?
    ): Resource<Unit> {
        val result = remote.sendMessage(roomCode, id, username, messageText, replyTo)
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
        status = if (hasPendingWrites) MessageStatus.SENDING else MessageStatus.SENT,
        replyToId = replyTo?.messageId,
        replyToUsername = replyTo?.username,
        replyToText = replyTo?.text,
        edited = edited,
        deleted = deleted,
        reactionsJson = encodeReactions(reactions)
    )

    private companion object {
        // How many of the newest messages the server listener keeps in sync (also bounds Firestore reads).
        const val SYNC_WINDOW = 50L

        // Messages fetched per request when scrolling back through history.
        const val PAGE_SIZE = 30L

        // The most matches a search shows.
        const val SEARCH_LIMIT = 100
    }
}
