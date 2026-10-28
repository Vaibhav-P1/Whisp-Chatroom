package com.example.wishpchatroom.viewmodel

import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.DeviceServerClock
import com.example.wishpchatroom.data.Message
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.data.WHISPER_CAP_MS
import com.example.wishpchatroom.data.whisperHideAt
import com.example.wishpchatroom.testing.FakeChatRepository
import com.example.wishpchatroom.testing.FakeContactsRepository
import com.example.wishpchatroom.testing.FakeModerationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WhisperTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val sent = 1_000_000L
    private val code = "ABC123"

    private fun whisper(uid: String = "u2", ttl: Long = 60, viewedAt: Long? = null) = Message(
        id = "w1", uid = uid, username = "bob", messageText = "secret", timestamp = sent,
        ttlSeconds = ttl, firstViewedAt = viewedAt
    )

    // --- timing rules (mirror firestore.rules) ---

    @Test
    fun `a normal message never hides`() {
        val normal = Message("m1", "u2", "bob", "hi", sent)
        assertNull(normal.hideAt)
        assertTrue(normal.isVisibleAt(Long.MAX_VALUE))
        assertFalse(normal.isWhisper)
    }

    @Test
    fun `an unopened whisper hides at the 24 hour cap`() {
        assertEquals(sent + WHISPER_CAP_MS, whisper().hideAt)
        assertTrue(whisper().isVisibleAt(sent + WHISPER_CAP_MS - 1))
        assertFalse(whisper().isVisibleAt(sent + WHISPER_CAP_MS))
    }

    @Test
    fun `an opened whisper hides ttl seconds after it was first opened`() {
        val opened = sent + 5_000
        val m = whisper(ttl = 10, viewedAt = opened)
        assertEquals(opened + 10_000, m.hideAt)
        assertTrue(m.isVisibleAt(opened + 9_999))
        assertFalse(m.isVisibleAt(opened + 10_000))
    }

    @Test
    fun `the cap wins when a whisper is opened late with a long lifetime`() {
        val m = whisper(ttl = 3_600, viewedAt = sent + WHISPER_CAP_MS - 1_000)
        assertEquals(sent + WHISPER_CAP_MS, m.hideAt)
        assertEquals(null, whisperHideAt(sent, null, null))
    }

    @Test
    fun `only others see a sealed whisper, never the author`() {
        assertTrue(whisper(uid = "u2").isSealedFor("me"))
        assertFalse(whisper(uid = "me").isSealedFor("me"))
        assertFalse(whisper(uid = "u2", viewedAt = sent + 1).isSealedFor("me"))
    }

    // --- view model ---

    private fun vm(repo: FakeChatRepository): ChatViewModel {
        repo.room.value = Room(roomCode = code, creatorUid = "me", members = mapOf("me" to "ada", "u2" to "bob"))
        val vm = ChatViewModel(repo, FakeModerationRepository(), FakeContactsRepository())
        vm.loadRoom(code)
        vm.loadMessages(code)
        return vm
    }

    @Test
    fun `opening a sealed whisper from someone else asks the repository to start it`() {
        val repo = FakeChatRepository()
        val vm = vm(repo)
        vm.revealWhisper(code, whisper(uid = "u2"))
        assertEquals(listOf("w1"), repo.revealed)
    }

    @Test
    fun `your own whisper and an already opened one are not revealed again`() {
        val repo = FakeChatRepository()
        val vm = vm(repo)
        vm.revealWhisper(code, whisper(uid = "me"))
        vm.revealWhisper(code, whisper(uid = "u2", viewedAt = sent + 1))
        assertTrue(repo.revealed.isEmpty())
    }

    @Test
    fun `a failed reveal shows the error`() {
        val repo = FakeChatRepository().apply {
            revealResult = com.example.wishpchatroom.data.Resource.Error("Could not open the whisper.")
        }
        val vm = vm(repo)
        vm.revealWhisper(code, whisper(uid = "u2"))
        assertEquals("Could not open the whisper.", vm.uiState.value.errorMessage)
    }

    @Test
    fun `a whisper is sent with its lifetime and drops a pending reply`() {
        val repo = FakeChatRepository()
        val vm = vm(repo)
        vm.startReply(Message("m0", "u2", "bob", "question", sent))
        assertTrue(vm.sendMessage(code, "secret", whisperTtlSeconds = 60))
        assertEquals(listOf<Long?>(60L), repo.sentTtls)
        assertNull(repo.sent.single().third)
        assertNull(vm.uiState.value.replyingTo)
    }

    @Test
    fun `a normal message has no lifetime`() {
        val repo = FakeChatRepository()
        val vm = vm(repo)
        vm.sendMessage(code, "hello")
        assertEquals(listOf<Long?>(null), repo.sentTtls)
    }

    @Test
    fun `cleanup of hidden whispers runs when a chat is opened`() {
        val repo = FakeChatRepository()
        vm(repo)
        assertTrue(repo.purgeCalls >= 1)
    }

    // --- server clock ---

    @Test
    fun `the clock follows the server offset measured from a quick round trip`() {
        val clock = DeviceServerClock()
        assertTrue(clock.needsCalibration())
        val local = System.currentTimeMillis()
        clock.calibrate(serverMillis = local + 60_000, localBefore = local, localAfter = local + 100)
        assertFalse(clock.needsCalibration())
        val skew = clock.now() - System.currentTimeMillis()
        assertTrue("offset was $skew", skew in 59_000..61_000)
    }

    @Test
    fun `a slow round trip is not trusted`() {
        val clock = DeviceServerClock()
        val local = System.currentTimeMillis()
        clock.calibrate(serverMillis = local + 60_000, localBefore = local, localAfter = local + 10_000)
        assertTrue(clock.needsCalibration())
        assertTrue(kotlin.math.abs(clock.now() - System.currentTimeMillis()) < 1_000)
    }
}
