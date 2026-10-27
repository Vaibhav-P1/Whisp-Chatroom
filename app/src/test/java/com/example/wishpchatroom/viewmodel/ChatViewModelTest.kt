package com.example.wishpchatroom.viewmodel

import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Presence
import com.example.wishpchatroom.data.ReplyRef
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.testing.FakeChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val code = "ABC123"

    private fun room(open: Boolean = true) = Room(
        roomCode = code,
        creatorUid = "me",
        roomOpen = open,
        members = mapOf("me" to "ada", "u2" to "bob", "u3" to "cy")
    )

    private fun message(id: String, text: String = "hi") =
        Message(id = id, uid = "u2", username = "bob", messageText = text, timestamp = 1L)

    private fun loadedVm(repo: FakeChatRepository = FakeChatRepository()): Pair<ChatViewModel, FakeChatRepository> {
        repo.room.value = room()
        val vm = ChatViewModel(repo)
        vm.loadRoom(code)
        vm.loadMessages(code)
        return vm to repo
    }

    // --- room ---

    @Test
    fun `room details fill owner flag username and members`() {
        val (vm, _) = loadedVm()
        val state = vm.uiState.value
        assertTrue(state.isOwner)
        assertEquals("ada", state.roomUsername)
        assertEquals(RoomStatus.OPEN, state.roomStatus)
        assertEquals(3, state.members.size)
    }

    @Test
    fun `members list puts you first then online members`() {
        val (vm, repo) = loadedVm()
        repo.presence.value = listOf(Presence("u3", isPresent = true, lastSeen = System.currentTimeMillis()))
        val members = vm.uiState.value.members
        assertEquals(listOf("me", "u3", "u2"), members.map { it.uid })
        assertTrue(members[0].isYou && members[0].isOnline)
        assertTrue(members[1].isOnline)
        assertFalse(members[2].isOnline)
    }

    @Test
    fun `stale presence counts as offline`() {
        val (vm, repo) = loadedVm()
        repo.presence.value = listOf(Presence("u2", isPresent = true, lastSeen = System.currentTimeMillis() - 400_000L))
        assertFalse(vm.uiState.value.members.first { it.uid == "u2" }.isOnline)
    }

    @Test
    fun `closed room is reported`() {
        val (vm, repo) = loadedVm()
        repo.room.value = room(open = false)
        assertEquals(RoomStatus.CLOSED, vm.uiState.value.roomStatus)
    }

    @Test
    fun `room removed by the owner marks it deleted`() {
        val (vm, repo) = loadedVm()
        repo.room.value = null
        assertEquals(RoomStatus.DELETED, vm.uiState.value.roomStatus)
    }

    // --- sending ---

    @Test
    fun `message is trimmed and sent with the room username`() {
        val (vm, repo) = loadedVm()
        assertTrue(vm.sendMessage(code, "  hello  "))
        assertEquals(Triple("hello", "ada", null), repo.sent.single())
    }

    @Test
    fun `sending before the username is known is rejected`() {
        val repo = FakeChatRepository()
        val vm = ChatViewModel(repo)
        assertFalse(vm.sendMessage(code, "hello"))
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun `message over 2000 characters is rejected`() {
        val (vm, repo) = loadedVm()
        assertFalse(vm.sendMessage(code, "a".repeat(2001)))
        assertTrue(repo.sent.isEmpty())
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `a send error is shown`() {
        val (vm, repo) = loadedVm()
        repo.sendResult = Resource.Error("No connection")
        vm.sendMessage(code, "hello")
        assertEquals("No connection", vm.uiState.value.errorMessage)
        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }

    // --- reply and edit ---

    @Test
    fun `reply attaches a shortened quote and then resets`() {
        val (vm, repo) = loadedVm()
        vm.startReply(message("m1", "x".repeat(150)))
        vm.sendMessage(code, "answer")
        val ref = repo.sent.single().third!!
        assertEquals("m1", ref.messageId)
        assertEquals("bob", ref.username)
        assertEquals(100, ref.text.length)
        assertNull(vm.uiState.value.replyingTo)
    }

    @Test
    fun `starting an edit cancels a reply and the other way round`() {
        val (vm, _) = loadedVm()
        vm.startReply(message("m1"))
        vm.startEdit(message("m2"))
        assertNull(vm.uiState.value.replyingTo)
        assertEquals("m2", vm.uiState.value.editing?.id)
        vm.startReply(message("m3"))
        assertNull(vm.uiState.value.editing)
    }

    @Test
    fun `saving an edited message calls edit and not send`() {
        val (vm, repo) = loadedVm()
        vm.startEdit(message("m1", "old"))
        assertTrue(vm.sendMessage(code, "new"))
        assertEquals(listOf("m1" to "new"), repo.edits)
        assertTrue(repo.sent.isEmpty())
        assertNull(vm.uiState.value.editing)
    }

    @Test
    fun `saving an unchanged edit does nothing`() {
        val (vm, repo) = loadedVm()
        vm.startEdit(message("m1", "same"))
        vm.sendMessage(code, "same")
        assertTrue(repo.edits.isEmpty())
        assertNull(vm.uiState.value.editing)
    }

    // --- messages and pagination ---

    @Test
    fun `cached messages reach the ui state`() {
        val (vm, repo) = loadedVm()
        repo.messages.value = listOf(message("m1"), message("m2"))
        assertEquals(listOf("m1", "m2"), vm.uiState.value.messages.map { it.id })
    }

    @Test
    fun `older messages come from the cache before the server`() {
        val (vm, repo) = loadedVm()
        repo.cachedOlder = true
        vm.loadOlderMessages(code)
        assertEquals(0, repo.fetchOlderCalls)
        assertFalse(vm.uiState.value.isLoadingOlder)
    }

    @Test
    fun `server is asked once the cache is exhausted and can report the end`() {
        val (vm, repo) = loadedVm()
        repo.serverOlderResult = Resource.Success(false)
        vm.loadOlderMessages(code)
        assertEquals(1, repo.fetchOlderCalls)
        assertFalse(vm.uiState.value.hasMoreOlder)
        vm.loadOlderMessages(code)
        assertEquals(1, repo.fetchOlderCalls)
    }

    @Test
    fun `server failure while paging shows an error and keeps paging possible`() {
        val (vm, repo) = loadedVm()
        repo.serverOlderResult = Resource.Error("offline")
        vm.loadOlderMessages(code)
        assertEquals("offline", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.hasMoreOlder)
    }

    // --- search ---

    @Test
    fun `blank query gives no results and a query filters the cache`() {
        val (vm, repo) = loadedVm()
        repo.messages.value = listOf(message("m1", "hello world"), message("m2", "bye"))
        vm.startSearch(code)
        assertTrue(vm.uiState.value.isSearching)
        vm.closeSearch()
        assertFalse(vm.uiState.value.isSearching)
        assertEquals("", vm.uiState.value.searchQuery)
    }

    // --- leaving ---

    @Test
    fun `leaving deletes a temporary room it owns and updates presence`() {
        val (vm, repo) = loadedVm()
        var done = false
        vm.leaveRoom(code) { done = true }
        assertTrue(done)
        assertTrue(repo.tempDeleteChecked)
        assertEquals(listOf(false), repo.presenceWrites)
    }

    @Test
    fun `deleting your own room does not show the deleted dialog`() {
        val (vm, repo) = loadedVm()
        var done = false
        vm.deleteRoom(code) { done = true }
        repo.room.value = null
        assertTrue(done)
        assertEquals(RoomStatus.OPEN, vm.uiState.value.roomStatus)
    }

    @Test
    fun `failed delete keeps the user in the room and shows the error`() {
        val (vm, repo) = loadedVm()
        repo.deleteRoomResult = Resource.Error("denied")
        var done = false
        vm.deleteRoom(code) { done = true }
        assertFalse(done)
        assertEquals("denied", vm.uiState.value.errorMessage)
        repo.room.value = room(open = false)
        assertEquals(RoomStatus.CLOSED, vm.uiState.value.roomStatus)
    }

    @Test
    fun `leaving permanently calls the repository and then finishes`() {
        val (vm, _) = loadedVm()
        var done = false
        vm.leaveRoomPermanently(code) { done = true }
        assertTrue(done)
    }

    @Test
    fun `failed permanent leave reports the error and resumes following the room`() {
        val (vm, repo) = loadedVm()
        repo.leaveResult = Resource.Error("no network")
        var done = false
        vm.leaveRoomPermanently(code) { done = true }
        assertFalse(done)
        assertEquals("no network", vm.uiState.value.errorMessage)
        repo.room.value = room(open = false)
        assertEquals(RoomStatus.CLOSED, vm.uiState.value.roomStatus)
    }

    @Test
    fun `acknowledging a deleted room forgets its cache`() {
        val (vm, repo) = loadedVm()
        var done = false
        vm.acknowledgeRoomDeleted(code) { done = true }
        assertTrue(done)
        assertTrue(repo.forgotten)
    }

    @Test
    fun `presence updates are ignored once leaving`() {
        val (vm, repo) = loadedVm()
        vm.leaveRoom(code) {}
        repo.presenceWrites.clear()
        vm.updateUserPresence(code, true)
        assertTrue(repo.presenceWrites.isEmpty())
    }
}
