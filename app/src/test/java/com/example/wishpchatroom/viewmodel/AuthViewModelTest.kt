package com.example.wishpchatroom.viewmodel

import app.cash.turbine.test
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.data.UserRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakeUserRepository : UserRepository {
        var loggedIn = false
        var user: User? = null
        var loginResult: Resource<Boolean> = Resource.Success(true)
        var signUpResult: Resource<Boolean> = Resource.Success(true)
        var gate: CompletableDeferred<Unit>? = null
        var loginCalls = 0
        var cleared = false

        override suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Resource<Boolean> {
            gate?.await()
            if (signUpResult is Resource.Success) user = User(firstName, lastName, email)
            return signUpResult
        }

        override suspend fun login(email: String, password: String): Resource<Boolean> {
            loginCalls++
            gate?.await()
            if (loginResult is Resource.Success) loggedIn = true
            return loginResult
        }

        override fun isLoggedIn() = loggedIn
        override suspend fun getCurrentUser() = user
        override fun logout() { loggedIn = false; user = null }
        override suspend fun clearLocalData() { cleared = true }
    }

    @Test
    fun `loads the current user on creation`() {
        val repo = FakeUserRepository().apply { user = User("Ada", "Lovelace", "ada@x.com") }
        val vm = AuthViewModel(repo)
        assertEquals("Ada", vm.uiState.value.currentUser?.firstName)
    }

    @Test
    fun `successful login emits one authenticated event and clears loading`() = runTest {
        val repo = FakeUserRepository().apply { user = User("Ada", "L", "a@x.com") }
        val vm = AuthViewModel(repo)
        vm.authenticated.test {
            vm.login("a@x.com", "pw")
            awaitItem()
            expectNoEvents()
        }
        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `failed login exposes the message and does not navigate`() = runTest {
        val repo = FakeUserRepository().apply { loginResult = Resource.Error("Incorrect email or password.") }
        val vm = AuthViewModel(repo)
        vm.authenticated.test {
            vm.login("a@x.com", "bad")
            expectNoEvents()
        }
        assertEquals("Incorrect email or password.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `a second login while one is in flight is ignored`() {
        val repo = FakeUserRepository().apply { gate = CompletableDeferred() }
        val vm = AuthViewModel(repo)
        vm.login("a@x.com", "pw")
        assertTrue(vm.uiState.value.isLoading)
        vm.login("a@x.com", "pw")
        assertEquals(1, repo.loginCalls)
        repo.gate!!.complete(Unit)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `sign up loads the new profile into state`() {
        val repo = FakeUserRepository()
        val vm = AuthViewModel(repo)
        vm.signUp("g@x.com", "pw", "Grace", "Hopper")
        assertEquals("Grace", vm.uiState.value.currentUser?.firstName)
    }

    @Test
    fun `clearError removes the message`() {
        val repo = FakeUserRepository().apply { loginResult = Resource.Error("nope") }
        val vm = AuthViewModel(repo)
        vm.login("a", "b")
        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `logout resets state and clears local data`() {
        val repo = FakeUserRepository().apply { loggedIn = true; user = User("Ada", "L", "a@x.com") }
        val vm = AuthViewModel(repo)
        vm.logout()
        assertNull(vm.uiState.value.currentUser)
        assertFalse(vm.isLoggedIn())
        assertTrue(repo.cleared)
    }
}
