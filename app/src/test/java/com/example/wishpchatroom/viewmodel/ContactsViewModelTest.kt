package com.example.wishpchatroom.viewmodel

import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.Friend
import com.example.wishpchatroom.data.FriendRequest
import com.example.wishpchatroom.data.InboxItem
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.dmRoomId
import com.example.wishpchatroom.data.sortInbox
import com.example.wishpchatroom.data.validateHandle
import com.example.wishpchatroom.testing.FakeContactsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val request = FriendRequest("bob_me", "bob", "me", "bob_bee", "Bob B.")

    // The state only runs while collected, like the real screen does.
    private fun kotlinx.coroutines.test.TestScope.vm(repo: FakeContactsRepository): ContactsViewModel {
        val vm = ContactsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    // --- pure helpers ---

    @Test
    fun `handle validation mirrors the rules`() {
        assertNull(validateHandle("ann_lee"))
        assertNull(validateHandle("a123"))
        listOf("Ann", "abc", "1abc", "ann_", "admin", "has space", "a".repeat(21), "ann-lee").forEach {
            assertNotNull(it, validateHandle(it))
        }
    }

    @Test
    fun `the dm id is the same for both people`() {
        assertEquals("dm_a_b", dmRoomId("a", "b"))
        assertEquals("dm_a_b", dmRoomId("b", "a"))
    }

    @Test
    fun `inbox is sorted pinned first then newest then by name`() {
        fun item(code: String, at: Long?, pinned: Boolean = false, name: String = code) = InboxItem(
            code, code, name, code, null, null, at, false, pinned, false, false, false
        )
        val sorted = sortInbox(
            listOf(item("old", 1), item("none", null), item("new", 9), item("pin", 0, pinned = true))
        )
        assertEquals(listOf("pin", "new", "old", "none"), sorted.map { it.roomCode })
    }

    // --- view model ---

    @Test
    fun `state follows the repository flows`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        repo.handle.value = "ann_lee"
        repo.incoming.value = listOf(request)
        repo.friends.value = listOf(Friend("bob", "bob_bee"))
        val state = vm.uiState.value
        assertEquals("ann_lee", state.myHandle)
        assertEquals(1, state.incoming.size)
        assertEquals(1, state.friends.size)
    }

    @Test
    fun `an invalid handle is rejected locally`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        vm.claimHandle("Admin")
        assertTrue(repo.claimed.isEmpty())
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `a handle is normalised before it is claimed`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        vm.claimHandle("  @Ann_Lee ")
        assertEquals(listOf("ann_lee"), repo.claimed)
        assertEquals("Handle saved.", vm.uiState.value.infoMessage)
    }

    @Test
    fun `a failed claim shows the error`() = runTest {
        val repo = FakeContactsRepository().apply { claimResult = Resource.Error("That handle is taken.") }
        val vm = vm(repo)
        vm.claimHandle("ann_lee")
        assertEquals("That handle is taken.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `sending a request passes the handle and reports success or the generic failure`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        vm.sendRequest("bob_bee")
        assertEquals(listOf("bob_bee"), repo.requested)
        assertEquals("Request sent.", vm.uiState.value.infoMessage)

        repo.requestResult = Resource.Error("Couldn't send request.")
        vm.sendRequest("bob_bee")
        assertEquals("Couldn't send request.", vm.uiState.value.errorMessage)
    }

    @Test
    fun `an empty handle is not sent`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        vm.sendRequest("  ")
        assertTrue(repo.requested.isEmpty())
    }

    @Test
    fun `accepting declining and cancelling go to the repository`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        vm.accept(request)
        assertEquals(listOf(request), repo.accepted)
        assertEquals("You are now friends with Bob B..", vm.uiState.value.infoMessage)
        vm.decline(request)
        vm.cancel(request)
        assertEquals(2, repo.removedRequests.size)
    }

    @Test
    fun `a failed accept shows the error`() = runTest {
        val repo = FakeContactsRepository().apply { acceptResult = Resource.Error("Could not accept") }
        val vm = vm(repo)
        vm.accept(request)
        assertEquals("Could not accept", vm.uiState.value.errorMessage)
    }

    @Test
    fun `removing a friend and opening their chat use the shared dm id`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        val friend = Friend("bob", "bob_bee")
        assertEquals("dm_bob_me", vm.dmRoomFor(friend))
        vm.removeFriend(friend)
        assertEquals(listOf("bob"), repo.removedFriends)
    }

    @Test
    fun `messages can be cleared`() = runTest {
        val repo = FakeContactsRepository().apply { claimResult = Resource.Error("nope") }
        val vm = vm(repo)
        vm.claimHandle("ann_lee")
        vm.clearMessages()
        assertNull(vm.uiState.value.errorMessage)
    }
}
