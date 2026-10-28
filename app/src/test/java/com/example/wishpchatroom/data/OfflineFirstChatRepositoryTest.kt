package com.example.wishpchatroom.data

import com.example.wishpchatroom.data.local.MessageDao
import com.example.wishpchatroom.data.local.MessageEntity
import com.example.wishpchatroom.data.local.encodeReactions
import com.example.wishpchatroom.data.remote.ChatRemoteDataSource
import com.example.wishpchatroom.data.remote.RemoteMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFirstChatRepositoryTest {

    /** In-memory stand-in for the Room DAO (the SQL itself is not what these tests are about). */
    private class FakeMessageDao : MessageDao {
        val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
        var lastSearchPattern: String? = null

        private fun change(transform: (List<MessageEntity>) -> List<MessageEntity>) {
            rows.value = transform(rows.value)
        }

        private fun update(id: String, f: (MessageEntity) -> MessageEntity) =
            change { list -> list.map { if (it.id == id) f(it) else it } }

        override fun observeLatest(roomCode: String, limit: Int): Flow<List<MessageEntity>> =
            rows.map { list ->
                list.filter { it.roomCode == roomCode }
                    .sortedWith(compareByDescending<MessageEntity> { it.timestamp }.thenByDescending { it.id })
                    .take(limit)
            }

        override fun search(roomCode: String, pattern: String, limit: Int, nowMillis: Long, myUid: String): Flow<List<MessageEntity>> {
            lastSearchPattern = pattern
            return flowOf(emptyList())
        }

        override suspend fun markViewed(id: String, viewedAt: Long, hideAt: Long?) =
            update(id) { it.copy(firstViewedAt = viewedAt, hideAt = hideAt) }

        override suspend fun hiddenIds(roomCode: String, nowMillis: Long, limit: Int) =
            rows.value.filter { it.roomCode == roomCode && (it.hideAt ?: Long.MAX_VALUE) <= nowMillis }
                .take(limit).map { it.id }

        override suspend fun purgeHidden(nowMillis: Long): Int {
            val before = rows.value.size
            change { list -> list.filterNot { (it.hideAt ?: Long.MAX_VALUE) <= nowMillis } }
            return before - rows.value.size
        }

        override suspend fun count(roomCode: String) = rows.value.count { it.roomCode == roomCode }

        override suspend fun oldestSentTimestamp(roomCode: String) =
            rows.value.filter { it.roomCode == roomCode && it.status == MessageStatus.SENT }
                .minOfOrNull { it.timestamp }

        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(message: MessageEntity) =
            change { list -> list.filterNot { it.id == message.id } + message }

        override suspend fun upsertAll(messages: List<MessageEntity>) {
            val ids = messages.map { it.id }.toSet()
            change { list -> list.filterNot { it.id in ids } + messages }
        }

        override suspend fun updateStatus(id: String, status: MessageStatus) = update(id) { it.copy(status = status) }
        override suspend fun applyEdit(id: String, messageText: String) =
            update(id) { it.copy(messageText = messageText, edited = true) }
        override suspend fun markDeleted(id: String) = update(id) { it.copy(messageText = "", deleted = true) }
        override suspend fun updateReactions(id: String, reactionsJson: String) =
            update(id) { it.copy(reactionsJson = reactionsJson) }
        override suspend fun deleteRoom(roomCode: String) = change { list -> list.filterNot { it.roomCode == roomCode } }
    }

    private class FakeRemote : ChatRemoteDataSource {
        override var currentUid: String? = "me"
        val snapshots = MutableStateFlow<List<RemoteMessage>>(emptyList())
        var sendResult: Resource<Unit> = Resource.Success(Unit)
        var editResult: Resource<Unit> = Resource.Success(Unit)
        var deleteMsgResult: Resource<Unit> = Resource.Success(Unit)
        var reactionResult: Resource<Unit> = Resource.Success(Unit)
        var leaveResult: Resource<Unit> = Resource.Success(Unit)
        var deleteRoomResult: Resource<Unit> = Resource.Success(Unit)
        var room: Room? = null
        var olderPage: List<RemoteMessage> = emptyList()
        var olderError: Exception? = null
        val sentIds = mutableListOf<String>()
        val sentTtls = mutableListOf<Long?>()
        val sentReplies = mutableListOf<ReplyRef?>()
        val revealCalls = mutableListOf<String>()
        val deletedRemote = mutableListOf<String>()
        var revealResult: Resource<Unit> = Resource.Success(Unit)
        val reactionCalls = mutableListOf<Boolean>()
        var deleteRoomCalls = 0

        override fun observeLatestMessages(roomCode: String, limit: Long): Flow<List<RemoteMessage>> = snapshots

        override suspend fun fetchOlderMessages(roomCode: String, beforeTimestamp: Long, limit: Long): List<RemoteMessage> {
            olderError?.let { throw it }
            return olderPage
        }

        override suspend fun sendMessage(
            roomCode: String, id: String, username: String, messageText: String, replyTo: ReplyRef?, ttlSeconds: Long?
        ): Resource<Unit> {
            sentIds += id
            sentTtls += ttlSeconds
            sentReplies += replyTo
            return sendResult
        }

        override suspend fun revealWhisper(roomCode: String, messageId: String): Resource<Unit> {
            revealCalls += messageId
            return revealResult
        }

        override suspend fun deleteMessages(roomCode: String, messageIds: List<String>) {
            deletedRemote += messageIds
        }

        override suspend fun editMessage(roomCode: String, messageId: String, newText: String) = editResult
        override suspend fun deleteMessage(roomCode: String, messageId: String) = deleteMsgResult
        override suspend fun setReaction(roomCode: String, messageId: String, emoji: String, add: Boolean): Resource<Unit> {
            reactionCalls += add
            return reactionResult
        }

        override suspend fun getRoom(roomCode: String) = room
        override fun observeRoom(roomCode: String): Flow<Room?> = flowOf(room)
        override fun observePresence(roomCode: String): Flow<List<Presence>> = flowOf(emptyList())
        override suspend fun leaveRoom(roomCode: String) = leaveResult
        override suspend fun closeRoom(roomCode: String) = Resource.Success(Unit)
        override suspend fun deleteRoom(roomCode: String): Resource<Unit> {
            deleteRoomCalls++
            return deleteRoomResult
        }
        override suspend fun updateUserPresence(roomCode: String, username: String, isPresent: Boolean) {}
    }

    private val code = "ABC123"
    private val dao = FakeMessageDao()
    private val remote = FakeRemote()
    private class FakeClock(var time: Long = 1_000_000L) : ServerClock {
        override fun now() = time
        override fun needsCalibration() = false
        override fun calibrate(serverMillis: Long, localBefore: Long, localAfter: Long) {}
    }

    private val clock = FakeClock()
    private val reminders = com.example.wishpchatroom.testing.FakeReminderScheduler()
    private val repo = OfflineFirstChatRepository(remote, dao, clock, reminders)

    private fun entity(
        id: String,
        ts: Long,
        status: MessageStatus = MessageStatus.SENT,
        room: String = code,
        reactionsJson: String = "{}"
    ) = MessageEntity(id, room, "u2", "bob", "text $id", ts, status, reactionsJson = reactionsJson)

    private fun remoteMessage(id: String, ts: Long, pending: Boolean = false) =
        RemoteMessage(id, "u2", "bob", "text $id", ts, pending)

    // --- sending ---

    @Test
    fun `a delivered message is cached as SENT under the id sent to the server`() = runTest {
        remote.sendResult = Resource.Success(Unit)
        repo.sendMessage(code, "hello", "ada")
        val stored = dao.rows.value.single()
        assertEquals("hello", stored.messageText)
        assertEquals("me", stored.uid)
        assertEquals(MessageStatus.SENT, stored.status)
        assertEquals(stored.id, remote.sentIds.single())
    }

    @Test
    fun `failed send leaves the message as FAILED in the cache`() = runTest {
        remote.sendResult = Resource.Error("offline")
        val result = repo.sendMessage(code, "hello", "ada")
        assertTrue(result is Resource.Error)
        assertEquals(MessageStatus.FAILED, dao.rows.value.single().status)
    }

    @Test
    fun `send requires a logged in user`() = runTest {
        remote.currentUid = null
        assertTrue(repo.sendMessage(code, "hi", "ada") is Resource.Error)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `retry resends under the same id and marks it SENT`() = runTest {
        remote.sendResult = Resource.Error("offline")
        repo.sendMessage(code, "hello", "ada")
        val id = dao.rows.value.single().id

        remote.sendResult = Resource.Success(Unit)
        assertTrue(repo.retryMessage(code, id) is Resource.Success)

        assertEquals(listOf(id, id), remote.sentIds)
        assertEquals(1, dao.rows.value.size)
        assertEquals(MessageStatus.SENT, dao.rows.value.single().status)
    }

    @Test
    fun `retry of a message that is not FAILED does nothing`() = runTest {
        dao.upsert(entity("m1", 1))
        repo.retryMessage(code, "m1")
        assertTrue(remote.sentIds.isEmpty())
    }

    @Test
    fun `retry of an unknown message is an error`() = runTest {
        assertTrue(repo.retryMessage(code, "nope") is Resource.Error)
    }

    // --- reminders are cancelled when the room is gone for this user ---

    @Test
    fun `leaving deleting closing or forgetting a room cancels its reminder`() = runTest {
        for (action in listOf<suspend (String) -> Unit>(
            { repo.leaveRoom(it) }, { repo.deleteRoom(it) }, { repo.closeRoom(it) }, { repo.forgetRoom(it) }
        )) {
            reminders.cancelled.clear()
            action(code)
            assertEquals(listOf(code), reminders.cancelled)
        }
    }

    @Test
    fun `a failed leave keeps the reminder`() = runTest {
        remote.leaveResult = Resource.Error("offline")
        repo.leaveRoom(code)
        assertTrue(reminders.cancelled.isEmpty())
    }

    // --- sync ---

    @Test
    fun `server snapshots are written into the cache`() = runTest {
        remote.snapshots.value = listOf(remoteMessage("m1", 10), remoteMessage("m2", 20, pending = true))
        val job = launchSync()
        val byId = dao.rows.value.associateBy { it.id }
        assertEquals(MessageStatus.SENT, byId.getValue("m1").status)
        assertEquals(MessageStatus.SENDING, byId.getValue("m2").status)
        job.cancel()
    }

    @Test
    fun `a confirmed snapshot flips a sending message to SENT`() = runTest {
        dao.upsert(entity("m1", 10, MessageStatus.SENDING))
        remote.snapshots.value = listOf(remoteMessage("m1", 10, pending = false))
        launchSync().cancel()
        assertEquals(MessageStatus.SENT, dao.rows.value.single().status)
    }

    private fun TestScope.launchSync() =
        launch(UnconfinedTestDispatcher(testScheduler)) { repo.syncMessages(code) }

    // --- observing ---

    @Test
    fun `observeMessages returns the newest window oldest first`() = runTest {
        dao.upsertAll(listOf(entity("m1", 1), entity("m2", 2), entity("m3", 3), entity("other", 9, room = "ZZZ999")))
        val seen = repo.observeMessages(code, 2).first().map { it.id }
        assertEquals(listOf("m2", "m3"), seen)
    }

    // --- edit / delete / reactions ---

    @Test
    fun `successful edit updates the cache and failed edit does not`() = runTest {
        dao.upsert(entity("m1", 1))
        remote.editResult = Resource.Error("denied")
        repo.editMessage(code, "m1", "new")
        assertFalse(dao.rows.value.single().edited)

        remote.editResult = Resource.Success(Unit)
        repo.editMessage(code, "m1", "new")
        assertEquals("new", dao.rows.value.single().messageText)
        assertTrue(dao.rows.value.single().edited)
    }

    @Test
    fun `delete clears the text locally only when the server accepted it`() = runTest {
        dao.upsert(entity("m1", 1))
        remote.deleteMsgResult = Resource.Error("denied")
        repo.deleteMessage(code, "m1")
        assertFalse(dao.rows.value.single().deleted)

        remote.deleteMsgResult = Resource.Success(Unit)
        repo.deleteMessage(code, "m1")
        assertTrue(dao.rows.value.single().deleted)
        assertEquals("", dao.rows.value.single().messageText)
    }

    @Test
    fun `reaction is added then removed on the second toggle`() = runTest {
        dao.upsert(entity("m1", 1))
        repo.toggleReaction(code, "m1", "👍")
        assertEquals(encodeReactions(mapOf("👍" to listOf("me"))), dao.rows.value.single().reactionsJson)

        repo.toggleReaction(code, "m1", "👍")
        assertEquals(listOf(true, false), remote.reactionCalls)
        assertEquals("{}", dao.rows.value.single().reactionsJson)
    }

    @Test
    fun `reaction keeps other users and is not cached when the server rejects it`() = runTest {
        dao.upsert(entity("m1", 1, reactionsJson = encodeReactions(mapOf("👍" to listOf("u2")))))
        remote.reactionResult = Resource.Error("denied")
        repo.toggleReaction(code, "m1", "👍")
        assertEquals(encodeReactions(mapOf("👍" to listOf("u2"))), dao.rows.value.single().reactionsJson)

        remote.reactionResult = Resource.Success(Unit)
        repo.toggleReaction(code, "m1", "👍")
        assertEquals(encodeReactions(mapOf("👍" to listOf("u2", "me"))), dao.rows.value.single().reactionsJson)
    }

    // --- search ---

    @Test
    fun `search escapes LIKE wildcards`() = runTest {
        repo.searchMessages(code, "  50%_a\\b ").collect { }
        assertEquals("%50\\%\\_a\\\\b%", dao.lastSearchPattern)
    }

    // --- pagination ---

    @Test
    fun `hasCachedOlder compares the cache size with the shown count`() = runTest {
        dao.upsertAll(listOf(entity("m1", 1), entity("m2", 2), entity("m3", 3)))
        assertTrue(repo.hasCachedOlder(code, 2))
        assertFalse(repo.hasCachedOlder(code, 3))
    }

    @Test
    fun `fetching older with an empty cache reports nothing more`() = runTest {
        val result = repo.fetchOlderMessages(code) as Resource.Success
        assertFalse(result.data)
    }

    @Test
    fun `a full page means there may be more, a short page means the end`() = runTest {
        dao.upsert(entity("m100", 100))
        remote.olderPage = (1..30).map { remoteMessage("o$it", it.toLong()) }
        assertTrue((repo.fetchOlderMessages(code) as Resource.Success).data)
        assertEquals(31, dao.rows.value.size)

        remote.olderPage = (1..5).map { remoteMessage("p$it", it.toLong()) }
        assertFalse((repo.fetchOlderMessages(code) as Resource.Success).data)
    }

    @Test
    fun `network failure while paging becomes a friendly error`() = runTest {
        dao.upsert(entity("m1", 1))
        remote.olderError = RuntimeException("boom")
        val result = repo.fetchOlderMessages(code)
        assertEquals("Could not load earlier messages.", (result as Resource.Error).message)
    }

    @Test
    fun `pending messages do not set the pagination anchor`() = runTest {
        dao.upsertAll(listOf(entity("pending", 1, MessageStatus.SENDING), entity("sent", 50)))
        assertEquals(50L, dao.oldestSentTimestamp(code))
    }

    // --- rooms ---

    @Test
    fun `leaving purges the cache only on success`() = runTest {
        dao.upsertAll(listOf(entity("m1", 1), entity("x", 1, room = "ZZZ999")))
        remote.leaveResult = Resource.Error("fail")
        repo.leaveRoom(code)
        assertEquals(2, dao.rows.value.size)

        remote.leaveResult = Resource.Success(Unit)
        repo.leaveRoom(code)
        assertEquals(listOf("x"), dao.rows.value.map { it.id })
    }

    @Test
    fun `forgetRoom purges the cache`() = runTest {
        dao.upsert(entity("m1", 1))
        repo.forgetRoom(code)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `deleteIfTemporaryOwner deletes only your own temporary room`() = runTest {
        remote.room = Room(code, creatorUid = "me", temporary = true)
        repo.deleteIfTemporaryOwner(code)
        assertEquals(1, remote.deleteRoomCalls)

        remote.room = Room(code, creatorUid = "other", temporary = true)
        repo.deleteIfTemporaryOwner(code)
        remote.room = Room(code, creatorUid = "me", temporary = false)
        repo.deleteIfTemporaryOwner(code)
        remote.room = null
        repo.deleteIfTemporaryOwner(code)
        assertEquals(1, remote.deleteRoomCalls)
    }

}
