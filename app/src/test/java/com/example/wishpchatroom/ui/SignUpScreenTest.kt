package com.example.wishpchatroom.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.R
import com.example.wishpchatroom.SignUpScreen
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.testing.FakeUserRepository
import com.example.wishpchatroom.ui.theme.WishpChatRoomTheme
import com.example.wishpchatroom.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
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
class SignUpScreenTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val repo = FakeUserRepository()
    private var loginClicks = 0

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showScreen() {
        composeRule.setContent {
            WishpChatRoomTheme(dynamicColor = false) {
                SignUpScreen(authViewModel = AuthViewModel(repo), onNavigateToLogin = { loginClicks++ })
            }
        }
    }

    // "Create Account" is both the title and the button label; the button is the clickable one.
    private fun createButton() = composeRule.onAllNodesWithText(string(R.string.create_account)).let { nodes ->
        nodes[nodes.fetchSemanticsNodes().lastIndex]
    }

    private fun fillAll() {
        composeRule.onNodeWithText(string(R.string.first_name)).performTextInput("Grace")
        composeRule.onNodeWithText(string(R.string.last_name)).performTextInput("Hopper")
        composeRule.onNodeWithText(string(R.string.email_address)).performTextInput("grace@x.com")
        composeRule.onNodeWithText(string(R.string.password)).performTextInput("secret1")
    }

    @Test
    fun `create account stays disabled until every field is filled`() {
        showScreen()
        createButton().assertIsNotEnabled()

        composeRule.onNodeWithText(string(R.string.first_name)).performTextInput("Grace")
        composeRule.onNodeWithText(string(R.string.last_name)).performTextInput("Hopper")
        composeRule.onNodeWithText(string(R.string.email_address)).performTextInput("grace@x.com")
        createButton().assertIsNotEnabled()

        composeRule.onNodeWithText(string(R.string.password)).performTextInput("secret1")
        createButton().assertIsEnabled()
    }

    @Test
    fun `submitting sends all four values in order`() {
        showScreen()
        fillAll()
        createButton().performScrollTo().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("grace@x.com", "secret1", "Grace", "Hopper"), repo.lastSignUp)
    }

    @Test
    fun `a failed sign up shows the error`() {
        repo.signUpResult = Resource.Error("An account already exists for that email.")
        showScreen()
        fillAll()
        createButton().performScrollTo().performClick()

        composeRule.onNodeWithText("An account already exists for that email.").assertExists()
    }

    @Test
    fun `the sign in link navigates back`() {
        showScreen()
        composeRule.onNode(hasText(string(R.string.sign_in))).performScrollTo().performClick()
        assertEquals(1, loginClicks)
    }
}
