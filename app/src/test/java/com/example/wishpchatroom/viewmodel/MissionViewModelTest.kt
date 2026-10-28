package com.example.wishpchatroom.viewmodel

import com.example.wishpchatroom.MainDispatcherRule
import com.example.wishpchatroom.data.Mission
import com.example.wishpchatroom.data.MissionDuration
import com.example.wishpchatroom.data.MissionPhase
import com.example.wishpchatroom.data.MissionSpec
import com.example.wishpchatroom.data.MissionTask
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.Room
import com.example.wishpchatroom.data.SavedNote
import com.example.wishpchatroom.data.expiresAtMillis
import com.example.wishpchatroom.data.formatRemaining
import com.example.wishpchatroom.data.missionPhase
import com.example.wishpchatroom.data.phaseAt
import com.example.wishpchatroom.data.validateMission
import com.example.wishpchatroom.testing.FakeChatRepository
import com.example.wishpchatroom.testing.FakeContactsRepository
import com.example.wishpchatroom.testing.FakeMissionRepository
import com.example.wishpchatroom.testing.FakeReminderScheduler
import com.example.wishpchatroom.testing.FakeModerationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class MissionViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val minute = 60_000L
    private val hour = 60 * minute
    private val created = 1_000_000_000L

    private fun missionRoom(durationSeconds: Long = 1_800, createdAt: Long? = created) = Room(
        roomCode = "MSN123", creatorUid = "me", members = mapOf("me" to "ada", "u2" to "bob"),
        timestamp = createdAt?.let { Date(it) },
        mission = Mission(title = "Ship it", goal = "Demo", durationSeconds = durationSeconds)
    )

    // --- phases and formatting (pure) ---

    @Test
    fun `expiry is the creation time plus the duration`() {
        assertEquals(created + 30 * minute, missionRoom().expiresAtMillis())
        assertNull(Room(roomCode = "ABC123").expiresAtMillis())
        assertNull(missionRoom(createdAt = null).expiresAtMillis())
    }

    @Test
    fun `a mission is active, then read-only for 24 hours, then gone`() {
        val room = missionRoom()
        val end = created + 30 * minute
        assertEquals(MissionPhase.ACTIVE, room.missionPhase(created))
        assertEquals(MissionPhase.ACTIVE, room.missionPhase(end - 1))
        assertEquals(MissionPhase.READ_ONLY, room.missionPhase(end))
        assertEquals(MissionPhase.READ_ONLY, room.missionPhase(end + 24 * hour - 1))
        assertEquals(MissionPhase.GONE, room.missionPhase(end + 24 * hour))
    }

    @Test
    fun `a plain room and a mission with a pending timestamp never end`() {
        assertEquals(MissionPhase.ACTIVE, Room(roomCode = "ABC123").missionPhase(Long.MAX_VALUE))
        assertEquals(MissionPhase.ACTIVE, missionRoom(createdAt = null).missionPhase(Long.MAX_VALUE))
        assertEquals(MissionPhase.ACTIVE, phaseAt(null, Long.MAX_VALUE))
    }

    @Test
    fun `remaining time is formatted and never negative`() {
        assertEquals("1h 05m", formatRemaining(65 * minute))
        assertEquals("12m 30s", formatRemaining(12 * minute + 30_000))
        assertEquals("45s", formatRemaining(45_000))
        assertEquals("0s", formatRemaining(-5_000))
    }

    @Test
    fun `a mission needs a title and one of the allowed durations`() {
        val ok = MissionSpec("Ship it", "Demo", MissionDuration.SIX_HOURS.seconds)
        assertNull(validateMission(ok))
        assertNotNull(validateMission(ok.copy(title = "  ")))
        assertNotNull(validateMission(ok.copy(title = "x".repeat(61))))
        assertNotNull(validateMission(ok.copy(goal = "x".repeat(201))))
        assertNotNull(validateMission(ok.copy(durationSeconds = 12345)))
    }

    // --- chat view model with a mission room ---

    private fun chatVm(room: Room): ChatViewModel {
        val repo = FakeChatRepository().apply { this.room.value = room }
        val vm = ChatViewModel(repo, FakeModerationRepository(), FakeContactsRepository())
        vm.loadRoom(room.roomCode)
        return vm
    }

    @Test
    fun `the chat state carries the mission and its expiry`() {
        val now = System.currentTimeMillis()
        val vm = chatVm(missionRoom(durationSeconds = 21_600, createdAt = now))
        val state = vm.uiState.value
        assertEquals("Ship it", state.mission?.title)
        assertEquals(now + 6 * hour, state.missionExpiresAt)
    }

    @Test
    fun `a plain room has no mission state`() {
        val vm = chatVm(Room(roomCode = "ABC123", creatorUid = "me", members = mapOf("me" to "ada")))
        assertNull(vm.uiState.value.mission)
        assertNull(vm.uiState.value.missionExpiresAt)
    }

    @Test
    fun `a mission long past its grace period is treated as a deleted room`() {
        val longAgo = System.currentTimeMillis() - 3 * 24 * hour
        val vm = chatVm(missionRoom(createdAt = longAgo))
        assertEquals(RoomStatus.DELETED, vm.uiState.value.roomStatus)
    }

    @Test
    fun `a mission in its grace period is still readable`() {
        val twoHoursAgo = System.currentTimeMillis() - 2 * hour
        val vm = chatVm(missionRoom(createdAt = twoHoursAgo))
        assertEquals(RoomStatus.OPEN, vm.uiState.value.roomStatus)
    }

    // --- mission actions ---

    @Test
    fun `the checklist follows the repository and new tasks get the next order`() = runTest {
        val repo = FakeMissionRepository()
        val vm = MissionViewModel(repo, FakeReminderScheduler())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        vm.startWatching("MSN123")
        repo.tasks.value = listOf(MissionTask("t1", "Write", false, "", 4))
        assertEquals(1, vm.uiState.value.tasks.size)
        vm.addTask("MSN123", "Deploy")
        assertEquals(listOf("Deploy" to 5L), repo.added)
    }

    @Test
    fun `ticking and deleting a task go to the repository`() = runTest {
        val repo = FakeMissionRepository()
        val vm = MissionViewModel(repo, FakeReminderScheduler())
        val task = MissionTask("t1", "Write", false, "", 1)
        vm.setTaskDone("MSN123", task, true)
        vm.deleteTask("MSN123", task)
        assertEquals(listOf("t1" to true), repo.toggled)
        assertEquals(listOf("t1"), repo.deleted)
    }

    @Test
    fun `announcement and outcome confirm on success and show the error on failure`() = runTest {
        val repo = FakeMissionRepository()
        val vm = MissionViewModel(repo, FakeReminderScheduler())
        vm.setAnnouncement("MSN123", "Stand-up at 10")
        assertEquals("Announcement posted.", vm.uiState.value.infoMessage)
        repo.result = Resource.Error("Could not save the outcome.")
        vm.setOutcome("MSN123", "Done")
        assertEquals("Could not save the outcome.", vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.infoMessage)
        vm.clearMessages()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `saving a note passes the room title author text and source`() = runTest {
        val repo = FakeMissionRepository()
        val vm = MissionViewModel(repo, FakeReminderScheduler())
        vm.saveNote("Ship it", "bob", "remember this", "message")
        assertEquals(listOf(listOf("Ship it", "bob", "remember this", "message")), repo.saved)
        assertEquals("Saved to your notes.", vm.uiState.value.infoMessage)
    }

    @Test
    fun `saved notes can be deleted`() = runTest {
        val repo = FakeMissionRepository()
        val vm = SavedNotesViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        val note = SavedNote("n1", "Ship it", "bob", "text", "message", 1L)
        repo.notes.value = listOf(note)
        assertEquals(1, vm.uiState.value.notes.size)
        vm.delete(note)
        assertEquals(listOf("n1"), repo.deletedNotes)
    }

    // --- reminders ---

    @Test
    fun `the reminder fires 10 minutes before the end and never in the past`() {
        val end = 10_000_000L
        assertEquals(end - 10 * minute - 1_000_000L, com.example.wishpchatroom.data.reminderDelayMs(end, 1_000_000L))
        assertNull(com.example.wishpchatroom.data.reminderDelayMs(end, end - 10 * minute))
        assertNull(com.example.wishpchatroom.data.reminderDelayMs(end, end - 5 * minute))
        assertNull(com.example.wishpchatroom.data.reminderDelayMs(end, end + 1))
    }

    @Test
    fun `turning a reminder on schedules it with the room title and the delay`() {
        val reminders = FakeReminderScheduler()
        val vm = MissionViewModel(FakeMissionRepository(), reminders)
        val now = 1_000_000L
        vm.setReminder("MSN123", "Ship it", now + 30 * minute, now, on = true)
        assertEquals("Ship it" to 20 * minute, reminders.scheduled["MSN123"])
        assertTrue(vm.uiState.value.reminderOn)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `a reminder is refused when less than 10 minutes are left`() {
        val reminders = FakeReminderScheduler()
        val vm = MissionViewModel(FakeMissionRepository(), reminders)
        vm.setReminder("MSN123", "Ship it", 1_000_000L + 5 * minute, 1_000_000L, on = true)
        assertTrue(reminders.scheduled.isEmpty())
        assertFalse(vm.uiState.value.reminderOn)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `turning a reminder off cancels it and the switch reflects a stored reminder`() {
        val reminders = FakeReminderScheduler().apply { schedule("MSN123", "Ship it", 1L) }
        val vm = MissionViewModel(FakeMissionRepository(), reminders)
        vm.startWatching("MSN123")
        assertTrue(vm.uiState.value.reminderOn)
        vm.setReminder("MSN123", "Ship it", null, 0L, on = false)
        assertEquals(listOf("MSN123"), reminders.cancelled)
        assertFalse(vm.uiState.value.reminderOn)
    }
}
