package com.example.wishpchatroom.viewmodel

import app.cash.turbine.test
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.RoomRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class RoomViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakeRoomRepository : RoomRepository {
        var createResult: Resource<String> = Resource.Success("ABC123")
        var joinResult: Resource<Boolean> = Resource.Success(true)
        var joinedCode: String? = null
        var createdTemporary: Boolean? = null

        override suspend fun createRoom(username: String, isTemporary: Boolean): Resource<String> {
            createdTemporary = isTemporary
            return createResult
        }

        override suspend fun joinRoom(roomCode: String, username: String): Resource<Boolean> {
            joinedCode = roomCode
            return joinResult
        }
    }

    @Test
    fun `create room emits the new code once`() = runTest {
        val repo = FakeRoomRepository()
        val vm = RoomViewModel(repo)
        vm.roomEntered.test {
            vm.createRoom("ada", isTemporary = true)
            assertEquals("ABC123", awaitItem())
            expectNoEvents()
        }
        assertEquals(true, repo.createdTemporary)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `join normalises the code and emits it`() = runTest {
        val repo = FakeRoomRepository()
        val vm = RoomViewModel(repo)
        vm.roomEntered.test {
            vm.joinRoom("  ab12cd ", "ada")
            assertEquals("AB12CD", awaitItem())
        }
        assertEquals("AB12CD", repo.joinedCode)
    }

    @Test
    fun `failed join shows the error and does not navigate`() = runTest {
        val repo = FakeRoomRepository().apply { joinResult = Resource.Error("Room is closed.") }
        val vm = RoomViewModel(repo)
        vm.roomEntered.test {
            vm.joinRoom("ABC123", "ada")
            expectNoEvents()
        }
        assertEquals("Room is closed.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `a new attempt clears the previous error`() {
        val repo = FakeRoomRepository().apply { createResult = Resource.Error("boom") }
        val vm = RoomViewModel(repo)
        vm.createRoom("ada", false)
        assertEquals("boom", vm.uiState.value.errorMessage)
        repo.createResult = Resource.Success("XYZ789")
        vm.createRoom("ada", false)
        assertEquals(null, vm.uiState.value.errorMessage)
    }
}
