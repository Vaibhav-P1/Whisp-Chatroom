package com.example.wishpchatroom.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.wishpchatroom.LoginScreen
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.R
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.testing.FakeUserRepository
import com.example.wishpchatroom.ui.theme.WishpChatRoomTheme
import com.example.wishpchatroom.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LoginScreenTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val repo = FakeUserRepository()
    private var signUpClicks = 0

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showScreen() {
        composeRule.setContent {
            WishpChatRoomTheme(dynamicColor = false) {
                LoginScreen(authViewModel = AuthViewModel(repo), onNavigateToSignUp = { signUpClicks++ })
            }
        }
    }

    @Test
    fun `sign in is disabled until email and password are filled`() {
        showScreen()
        composeRule.onNodeWithText(string(R.string.sign_in)).assertIsNotEnabled()

        composeRule.onNodeWithText(string(R.string.email_address)).performTextInput("ada@x.com")
        composeRule.onNodeWithText(string(R.string.sign_in)).assertIsNotEnabled()

        composeRule.onNodeWithText(string(R.string.password)).performTextInput("secret1")
        composeRule.onNodeWithText(string(R.string.sign_in)).assertIsEnabled()
    }

    @Test
    fun `submitting sends the typed credentials to the view model`() {
        showScreen()
        composeRule.onNodeWithText(string(R.string.email_address)).performTextInput("ada@x.com")
        composeRule.onNodeWithText(string(R.string.password)).performTextInput("secret1")
        composeRule.onNodeWithText(string(R.string.sign_in)).performClick()

        composeRule.waitForIdle()
        assertEquals("ada@x.com" to "secret1", repo.lastLogin)
    }

    @Test
    fun `a failed login shows the error message`() {
        repo.loginResult = Resource.Error("Incorrect email or password.")
        showScreen()
        composeRule.onNodeWithText(string(R.string.email_address)).performTextInput("ada@x.com")
        composeRule.onNodeWithText(string(R.string.password)).performTextInput("wrong")
        composeRule.onNodeWithText(string(R.string.sign_in)).performClick()

        composeRule.onNodeWithText("Incorrect email or password.").assertExists()
    }

    @Test
    fun `the sign up link navigates`() {
        showScreen()
        composeRule.onNodeWithText(string(R.string.sign_up)).performScrollTo().performClick()
        assertEquals(1, signUpClicks)
        assertNull(repo.lastLogin)
    }

    @Test
    fun `the eye button toggles password visibility`() {
        showScreen()
        composeRule.onNodeWithContentDescription(string(R.string.show_password)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.hide_password)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.hide_password)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.show_password)).assertExists()
    }
}
