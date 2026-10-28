package com.example.wishpchatroom.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wishpchatroom.data.MissionRepository
import com.example.wishpchatroom.data.MissionTask
import com.example.wishpchatroom.data.ReminderScheduler
import com.example.wishpchatroom.data.Resource
import com.example.wishpchatroom.data.SavedNote
import com.example.wishpchatroom.data.reminderDelayMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MissionUiState(
    val tasks: List<MissionTask> = emptyList(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    // A local notification is scheduled for 10 minutes before the mission ends.
    val reminderOn: Boolean = false
)

@HiltViewModel
class MissionViewModel @Inject constructor(
    private val missions: MissionRepository,
    private val reminders: ReminderScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(MissionUiState())
    val uiState: StateFlow<MissionUiState> = _uiState.asStateFlow()

    private var tasksJob: Job? = null

    /** Follows the checklist while the mission panel is open. */
    fun startWatching(roomCode: String) {
        _uiState.update { it.copy(reminderOn = reminders.isScheduled(roomCode)) }
        tasksJob?.cancel()
        tasksJob = viewModelScope.launch {
            missions.observeTasks(roomCode).collect { tasks -> _uiState.update { it.copy(tasks = tasks) } }
        }
    }

    /**
     * Turns the 10 minute reminder on or off. [nowMillis] is the server time as the screen knows it, so
     * a wrong device clock does not move the reminder.
     */
    fun setReminder(roomCode: String, title: String, expiresAtMillis: Long?, nowMillis: Long, on: Boolean) {
        if (!on) {
            reminders.cancel(roomCode)
            _uiState.update { it.copy(reminderOn = false, errorMessage = null) }
            return
        }
        val delay = expiresAtMillis?.let { reminderDelayMs(it, nowMillis) }
        if (delay == null) {
            _uiState.update { it.copy(reminderOn = false, errorMessage = "Less than 10 minutes are left, too late for a reminder.") }
            return
        }
        reminders.schedule(roomCode, title, delay)
        _uiState.update { it.copy(reminderOn = true, errorMessage = null, infoMessage = "You will be reminded 10 minutes before it ends.") }
    }

    fun stopWatching() {
        tasksJob?.cancel()
        tasksJob = null
    }

    fun addTask(roomCode: String, text: String) {
        val next = (_uiState.value.tasks.maxOfOrNull { it.order } ?: 0L) + 1
        run { missions.addTask(roomCode, text, next) }
    }

    fun setTaskDone(roomCode: String, task: MissionTask, done: Boolean) =
        run { missions.setTaskDone(roomCode, task.id, done) }

    fun deleteTask(roomCode: String, task: MissionTask) = run { missions.deleteTask(roomCode, task.id) }

    fun setAnnouncement(roomCode: String, text: String) = run("Announcement posted.") { missions.setAnnouncement(roomCode, text) }

    fun setOutcome(roomCode: String, text: String) = run("Outcome saved.") { missions.setOutcome(roomCode, text) }

    fun saveNote(roomTitle: String, author: String, text: String, source: String) =
        run("Saved to your notes.") { missions.saveNote(roomTitle, author, text, source) }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private fun run(success: String? = null, action: suspend () -> Resource<Unit>) {
        viewModelScope.launch {
            when (val result = action()) {
                is Resource.Success -> _uiState.update { it.copy(errorMessage = null, infoMessage = success) }
                is Resource.Error -> _uiState.update { it.copy(errorMessage = result.message, infoMessage = null) }
            }
        }
    }
}

data class SavedNotesUiState(
    val notes: List<SavedNote> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class SavedNotesViewModel @Inject constructor(
    private val missions: MissionRepository
) : ViewModel() {

    private val error = MutableStateFlow<String?>(null)

    // The listener runs only while the notes screen is visible.
    val uiState: StateFlow<SavedNotesUiState> = kotlinx.coroutines.flow.combine(
        missions.observeSavedNotes(), error
    ) { notes, message -> SavedNotesUiState(notes, message) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedNotesUiState())

    fun delete(note: SavedNote) {
        viewModelScope.launch {
            val result = missions.deleteNote(note.id)
            if (result is Resource.Error) error.value = result.message
        }
    }
}
