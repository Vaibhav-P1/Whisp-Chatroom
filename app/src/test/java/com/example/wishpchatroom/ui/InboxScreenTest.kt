package com.example.wishpchatroom.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.example.wishpchatroom.InboxScreen
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.R
import com.example.wishpchatroom.data.InboxItem
import com.example.wishpchatroom.testing.FakeContactsRepository
import com.example.wishpchatroom.ui.theme.WishpChatRoomTheme
import com.example.wishpchatroom.viewmodel.InboxViewModel
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
class InboxScreenTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val repo = FakeContactsRepository()
    private var opened: String? = null

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun row(name: String, last: String? = "see you", locked: Boolean = false) = InboxItem(
        roomCode = "dm_me_$name", friendUid = name, friendName = name, friendHandle = name,
        lastText = last, lastSenderUid = name, lastAt = 1_000L, unread = false, pinned = false,
        muted = false, archived = false, locked = locked
    )

    private fun showScreen() {
        composeRule.setContent {
            WishpChatRoomTheme(dynamicColor = false) {
                InboxScreen(
                    onBack = {},
                    onOpenChat = { opened = it },
                    onOpenFriends = {},
                    viewModel = InboxViewModel(repo)
                )
            }
        }
    }

    @Test
    fun `an empty inbox invites the user to add a friend`() {
        showScreen()
        composeRule.onNodeWithText(string(R.string.inbox_empty)).assertExists()
    }

    @Test
    fun `conversations show a preview and open the chat on tap`() {
        repo.inbox.value = listOf(row("Bob"), row("Cy", last = null))
        showScreen()
        composeRule.onNodeWithText("see you").assertExists()
        composeRule.onNodeWithText(string(R.string.inbox_no_messages)).assertExists()
        composeRule.onNodeWithText("Bob").performClick()
        assertEquals("dm_me_Bob", opened)
    }

    @Test
    fun `an unread chat shows its count, capped at 99 plus`() {
        repo.inbox.value = listOf(
            row("Bob").copy(unread = true, unreadCount = 3),
            row("Cy").copy(unread = true, unreadCount = 100)
        )
        showScreen()
        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithText("99+").assertExists()
    }

    @Test
    fun `a muted unread chat shows no number`() {
        repo.inbox.value = listOf(row("Bob").copy(unread = true, muted = true, unreadCount = 7))
        showScreen()
        composeRule.onNodeWithText("7").assertDoesNotExist()
    }

    @Test
    fun `a locked conversation says so instead of showing the preview`() {
        repo.inbox.value = listOf(row("Bob", locked = true))
        showScreen()
        composeRule.onNodeWithText(string(R.string.inbox_locked)).assertExists()
    }

    @Test
    fun `long press offers pin and saves it`() {
        repo.inbox.value = listOf(row("Bob"))
        showScreen()
        composeRule.onNodeWithText("Bob").performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.pin)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("dm_me_Bob" to true), repo.pins)
    }
}
