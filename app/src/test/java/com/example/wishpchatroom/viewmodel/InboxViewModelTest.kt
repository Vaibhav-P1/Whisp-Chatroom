package com.example.wishpchatroom.viewmodel

import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.InboxItem
import com.example.wishpchatroom.testing.FakeContactsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InboxViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun item(
        code: String,
        unread: Boolean = false,
        muted: Boolean = false,
        archived: Boolean = false,
        pinned: Boolean = false
    ) = InboxItem(code, code, code, code, "hi", "bob", 5L, unread, pinned, muted, archived, false)

    private fun TestScope.vm(repo: FakeContactsRepository): InboxViewModel {
        val vm = InboxViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    @Test
    fun `archived conversations are kept out of the main list`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        repo.inbox.value = listOf(item("dm_a_b"), item("dm_a_c", archived = true))
        assertEquals(listOf("dm_a_b"), vm.uiState.value.items.map { it.roomCode })
        assertEquals(listOf("dm_a_c"), vm.uiState.value.archived.map { it.roomCode })
        assertFalse(vm.uiState.value.showArchived)
        vm.toggleArchivedSection()
        assertTrue(vm.uiState.value.showArchived)
    }

    @Test
    fun `unread count ignores muted conversations`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        repo.inbox.value = listOf(item("a", unread = true), item("b", unread = true, muted = true), item("c"))
        assertEquals(1, vm.uiState.value.unreadCount)
    }

    @Test
    fun `pin mute and archive are saved with the toggled value`() = runTest {
        val repo = FakeContactsRepository()
        val vm = vm(repo)
        val row = item("dm_a_b")
        vm.setPinned(row, true)
        vm.setMuted(row, true)
        vm.setArchived(row, true)
        assertEquals(listOf("dm_a_b" to true), repo.pins)
        assertEquals(listOf("dm_a_b" to true), repo.mutes)
        assertEquals(listOf("dm_a_b" to true), repo.archives)
    }

    @Test
    fun `nothing listens until the screen collects`() = runTest {
        val repo = FakeContactsRepository()
        val vm = InboxViewModel(repo)
        repo.inbox.value = listOf(item("a"))
        assertTrue(vm.uiState.value.items.isEmpty())
    }
}
