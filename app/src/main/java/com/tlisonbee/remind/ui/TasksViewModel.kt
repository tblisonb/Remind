package com.tlisonbee.remind.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tlisonbee.remind.RemindApp
import com.tlisonbee.remind.data.Task
import com.tlisonbee.remind.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(
    private val repository: TaskRepository,
    /** Writes go here so they finish even if the screen goes away mid-save. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    val tasks: StateFlow<List<Task>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The task open in the editor, or null when showing the list. Edits are saved on close. */
    var draft by mutableStateOf<Task?>(null)
        private set

    fun newTask() {
        draft = Task(title = "")
    }

    fun openEditor(id: String) {
        if (draft?.id == id) return
        closeEditor() // e.g. a notification was tapped while another task was open
        viewModelScope.launch {
            repository.get(id)?.takeUnless { it.deleted }?.let { draft = it }
        }
    }

    fun updateDraft(task: Task) {
        draft = task
    }

    fun closeEditor() {
        val task = draft ?: return
        draft = null
        appScope.launch {
            // Like Keep: clearing out a task deletes it, and an empty new task is discarded.
            if (task.isBlank()) repository.delete(task.id) else repository.save(task.trimmed())
        }
    }

    /** Persists the open task without closing the editor, e.g. when the app goes to the background. */
    fun saveDraft() {
        val task = draft ?: return
        if (task.isBlank()) return
        appScope.launch { repository.save(task.trimmed()) }
    }

    fun deleteDraft() {
        val task = draft ?: return
        draft = null
        appScope.launch { repository.delete(task.id) }
    }

    fun setDone(id: String, done: Boolean) {
        appScope.launch { repository.setDone(id, done) }
    }

    private fun Task.isBlank() = title.isBlank() && notes.isBlank()

    private fun Task.trimmed() = copy(title = title.trim(), notes = notes.trim())

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RemindApp
                TasksViewModel(app.repository, app.appScope)
            }
        }
    }
}
