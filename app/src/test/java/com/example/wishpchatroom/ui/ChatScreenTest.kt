package com.example.wishpchatroom.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.wishpchatroom.ChatScreen
import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.R
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.testing.FakeChatRepository
import com.example.wishpchatroom.testing.FakeContactsRepository
import com.example.wishpchatroom.testing.FakeMissionRepository
import com.example.wishpchatroom.testing.FakeModerationRepository
import com.example.wishpchatroom.ui.theme.WishpChatRoomTheme
import com.example.wishpchatroom.viewmodel.ChatViewModel
import com.example.wishpchatroom.viewmodel.MissionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class ChatScreenTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val code = "ABC123"
    private val repo = FakeChatRepository().apply {
        room.value = Room(code, creatorUid = "me", members = mapOf("me" to "ada", "u2" to "bob"))
    }
    private var leftChat = 0

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showChat() {
        composeRule.setContent {
            WishpChatRoomTheme(dynamicColor = false) {
                ChatScreen(
                    roomCode = code,
                    onNavigateBack = { leftChat++ },
                    chatViewModel = ChatViewModel(repo, FakeModerationRepository(), FakeContactsRepository()),
                    missionViewModel = MissionViewModel(FakeMissionRepository())
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun message(id: String, uid: String, name: String, text: String) =
        Message(id = id, uid = uid, username = name, messageText = text, timestamp = 1_000L)

    @Test
    fun `cached messages are shown with the sender name for others`() {
        repo.messages.value = listOf(
            message("m1", "u2", "bob", "hi there"),
            message("m2", "me", "ada", "hello bob")
        )
        showChat()
        composeRule.onNodeWithText("hi there").assertExists()
        composeRule.onNodeWithText("hello bob").assertExists()
        composeRule.onNodeWithText("bob").assertExists()
    }

    @Test
    fun `send is disabled for an empty box and enabled after typing`() {
        showChat()
        composeRule.onNodeWithContentDescription(string(R.string.send)).assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("hello")
        composeRule.onNodeWithContentDescription(string(R.string.send)).assertIsEnabled()
    }

    @Test
    fun `sending hands the text to the repository and clears the box`() {
        showChat()
        composeRule.onNode(hasSetTextAction()).performTextInput("  hello bob  ")
        composeRule.onNodeWithContentDescription(string(R.string.send)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, repo.sent.size)
        assertEquals("hello bob", repo.sent.single().first)
        assertEquals("ada", repo.sent.single().second)
        composeRule.onNodeWithContentDescription(string(R.string.send)).assertIsNotEnabled()
    }

    @Test
    fun `a rejected send shows the error and keeps nothing hidden`() {
        repo.sendResult = Resource.Error("You don't have permission to do that.")
        showChat()
        composeRule.onNode(hasSetTextAction()).performTextInput("hello")
        composeRule.onNodeWithContentDescription(string(R.string.send)).performClick()

        composeRule.onNodeWithText("You don't have permission to do that.").assertExists()
    }

    private fun missionRoom(createdAgoMs: Long, durationSeconds: Long = 1_800) = Room(
        roomCode = code, creatorUid = "me", members = mapOf("me" to "ada", "u2" to "bob"),
        timestamp = java.util.Date(System.currentTimeMillis() - createdAgoMs),
        mission = com.example.wishpchatroom.data.Mission(
            title = "Ship it", goal = "Demo", announcement = "Stand-up at ten", durationSeconds = durationSeconds
        )
    )

    @Test
    fun `an active mission shows its title, countdown and announcement and allows typing`() {
        repo.room.value = missionRoom(createdAgoMs = 60_000)
        showChat()
        composeRule.onNodeWithText("🎯 Ship it").assertExists()
        composeRule.onNodeWithText("Stand-up at ten").assertExists()
        composeRule.onNode(hasSetTextAction()).assertIsEnabled()
    }

    @Test
    fun `an ended mission shows the read-only banner and locks the input`() {
        repo.room.value = missionRoom(createdAgoMs = 2 * 60 * 60 * 1000)
        showChat()
        composeRule.onNodeWithText(string(R.string.mission_ended_short)).assertExists()
        composeRule.onNodeWithText(string(R.string.mission_ended_banner)).assertExists()
        composeRule.onNode(hasSetTextAction()).assertIsNotEnabled()
    }

    @Test
    fun `tapping the banner opens the mission details`() {
        repo.room.value = missionRoom(createdAgoMs = 60_000)
        showChat()
        composeRule.onNodeWithText("🎯 Ship it").performClick()
        composeRule.onNodeWithText(string(R.string.mission_checklist)).assertExists()
        composeRule.onNodeWithText(string(R.string.mission_outcome)).assertExists()
    }

    private fun whisper(uid: String, viewedAt: Long? = null, ttl: Long = 60) = Message(
        id = "w1", uid = uid, username = if (uid == "me") "ada" else "bob", messageText = "the secret plan",
        timestamp = System.currentTimeMillis() - 1_000, ttlSeconds = ttl, firstViewedAt = viewedAt
    )

    @Test
    fun `a whisper from someone else is sealed until it is opened`() {
        repo.messages.value = listOf(whisper("u2"))
        showChat()
        composeRule.onNodeWithText("the secret plan").assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.whisper_sealed, "1 minute")
        ).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("w1"), repo.revealed)
    }

    @Test
    fun `your own whisper shows its text and says the timer starts when it is opened`() {
        repo.messages.value = listOf(whisper("me"))
        showChat()
        composeRule.onNodeWithText("the secret plan").assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.whisper_waiting, "1 minute")
        ).assertExists()
    }

    @Test
    fun `an opened whisper shows the text with a live countdown`() {
        repo.messages.value = listOf(whisper("u2", viewedAt = System.currentTimeMillis() - 5_000))
        showChat()
        composeRule.onNodeWithText("the secret plan").assertExists()
    }

    @Test
    fun `a whisper whose timer ran out is not shown`() {
        repo.messages.value = listOf(whisper("u2", viewedAt = System.currentTimeMillis() - 120_000, ttl = 10))
        showChat()
        composeRule.onNodeWithText("the secret plan").assertDoesNotExist()
    }

    @Test
    fun `choosing a whisper lifetime sends the message as a whisper`() {
        showChat()
        composeRule.onNodeWithText("🔒 1 min").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("psst")
        composeRule.onNodeWithContentDescription(string(R.string.send)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf<Long?>(60L), repo.sentTtls)
    }

    @Test
    fun `a closed room shows the banner and disables the input`() {
        repo.room.value = repo.room.value!!.copy(roomOpen = false)
        showChat()
        composeRule.onNodeWithText(string(R.string.room_closed_banner)).assertExists()
        composeRule.onNode(hasSetTextAction()).assertIsNotEnabled()
    }

    @Test
    fun `when the owner deletes the room the user is told and can leave`() {
        showChat()
        repo.room.value = null
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        composeRule.waitForIdle()
        assertTrue(repo.forgotten)
        assertEquals(1, leftChat)
    }
}
