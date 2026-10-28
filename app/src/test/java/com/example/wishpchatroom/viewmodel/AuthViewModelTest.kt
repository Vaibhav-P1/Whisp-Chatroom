package com.example.wishpchatroom.viewmodel

import app.cash.turbine.test
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.User
import com.example.wishpchatroom.testing.FakeUserRepository
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
        vm.signUp("g@x.com", "secret1", "Grace", "Hopper")
        assertEquals("Grace", vm.uiState.value.currentUser?.firstName)
    }

    @Test
    fun `clearError removes the message`() {
        val repo = FakeUserRepository().apply { loginResult = Resource.Error("nope") }
        val vm = AuthViewModel(repo)
        vm.login("a@x.com", "b")
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

    @Test
    fun `login and sign up reject a malformed email without calling the repository`() {
        val repo = FakeUserRepository()
        val vm = AuthViewModel(repo)
        vm.login("not-an-email", "secret1")
        assertEquals("Enter a valid email address.", vm.uiState.value.errorMessage)
        assertEquals(0, repo.loginCalls)
        vm.signUp("also bad@", "secret1", "Ada", "L")
        assertNull(repo.lastSignUp)
    }

    @Test
    fun `sign up rejects a short password locally`() {
        val repo = FakeUserRepository()
        val vm = AuthViewModel(repo)
        vm.signUp("a@x.com", "123", "Ada", "L")
        assertEquals("Password must be at least 6 characters.", vm.uiState.value.errorMessage)
        assertNull(repo.lastSignUp)
    }

    @Test
    fun `password reset validates locally then confirms`() {
        val repo = FakeUserRepository()
        val vm = AuthViewModel(repo)
        vm.sendPasswordReset("nope")
        assertNull(repo.resetEmail)
        assertEquals("Enter a valid email address.", vm.uiState.value.errorMessage)

        vm.sendPasswordReset("a@x.com")
        assertEquals("a@x.com", repo.resetEmail)
        assertTrue(vm.uiState.value.resetEmailSent)
        assertFalse(vm.uiState.value.isLoading)

        vm.dismissResetConfirmation()
        assertFalse(vm.uiState.value.resetEmailSent)
    }

    @Test
    fun `password reset failure shows the message and no confirmation`() {
        val repo = FakeUserRepository().apply { resetResult = Resource.Error("No internet connection.") }
        val vm = AuthViewModel(repo)
        vm.sendPasswordReset("a@x.com")
        assertEquals("No internet connection.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.resetEmailSent)
    }

    @Test
    fun `deleting the account needs a password and emits one event on success`() = runTest {
        val repo = FakeUserRepository().apply { loggedIn = true; user = User("Ada", "L", "a@x.com") }
        val vm = AuthViewModel(repo)
        vm.deleteAccount("  ")
        assertNull(repo.deletePassword)
        assertEquals("Enter your password to confirm.", vm.uiState.value.deleteError)

        vm.accountDeleted.test {
            vm.deleteAccount("secret1")
            awaitItem()
            expectNoEvents()
        }
        assertEquals("secret1", repo.deletePassword)
        assertNull(vm.uiState.value.currentUser)
        assertFalse(vm.isLoggedIn())
    }

    @Test
    fun `a failed account deletion keeps the user and shows the error`() = runTest {
        val repo = FakeUserRepository().apply {
            loggedIn = true
            deleteResult = Resource.Error("Incorrect email or password.")
        }
        val vm = AuthViewModel(repo)
        vm.accountDeleted.test {
            vm.deleteAccount("wrong")
            expectNoEvents()
        }
        assertEquals("Incorrect email or password.", vm.uiState.value.deleteError)
        assertFalse(vm.uiState.value.isDeleting)
        assertTrue(vm.isLoggedIn())
    }
}
