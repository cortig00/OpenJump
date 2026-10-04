package com.openjump.app.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.JumpRepository
import com.openjump.app.protocol.NoteNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SavedResultState {
    data object Loading : SavedResultState
    data class Content(
        val model: ResultUiModel,
        val noteState: ResultNotesState = ResultNotesState.fromSaved(model.note),
    ) : SavedResultState
    data object Missing : SavedResultState
    data class Error(val message: String) : SavedResultState
}

class SavedResultViewModel(
    private val assessmentId: Long,
    private val repository: JumpRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SavedResultState>(SavedResultState.Loading)
    val state: StateFlow<SavedResultState> = _state.asStateFlow()
    private val _noteState = MutableStateFlow(ResultNotesState())
    val noteState: StateFlow<ResultNotesState> = _noteState.asStateFlow()
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()
    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = SavedResultState.Loading
        viewModelScope.launch {
            try {
                val measurement = repository.measurementById(assessmentId)
                _state.value = if (measurement == null) {
                    SavedResultState.Missing
                } else {
                    SavedResultState.Content(ResultPresenter.presentStored(measurement)).also {
                        _noteState.value = ResultNotesState.fromSaved(it.model.note)
                    }
                }
            } catch (error: Exception) {
                _state.value = SavedResultState.Error(
                    error.message ?: "No se pudo abrir la medición guardada.",
                )
            }
        }
    }

    fun editNote(value: String) {
        _noteState.value = ResultNotesReducer.edit(_noteState.value, value)
    }

    fun saveNote(onSaved: () -> Unit = {}) {
        val content = _state.value as? SavedResultState.Content ?: return
        val request = NoteNormalizer.normalize(_noteState.value.draft.take(NoteNormalizer.MAX_LENGTH))
        if (_noteState.value.saving) return
        _noteState.value = ResultNotesReducer.saveRequested(_noteState.value)
        viewModelScope.launch {
            try {
                check(withContext(Dispatchers.IO) { repository.updateNotes(assessmentId, request) })
                _noteState.value = ResultNotesReducer.saveSucceeded(_noteState.value, request)
                _state.value = content.copy(model = content.model.copy(note = request), noteState = _noteState.value)
                onSaved()
            } catch (error: Exception) {
                _noteState.value = ResultNotesReducer.saveFailed(_noteState.value, error.message ?: "No se pudo guardar la nota.")
            }
        }
    }

    fun deleteNote(onDeleted: () -> Unit = {}) {
        if (_noteState.value.saving) return
        _noteState.value = ResultNotesReducer.deleteRequested(_noteState.value)
        viewModelScope.launch {
            try {
                check(withContext(Dispatchers.IO) { repository.updateNotes(assessmentId, null) })
                _noteState.value = ResultNotesReducer.deleteSucceeded(_noteState.value)
                (_state.value as? SavedResultState.Content)?.let { content ->
                    _state.value = content.copy(model = content.model.copy(note = null), noteState = _noteState.value)
                }
                onDeleted()
            } catch (error: Exception) {
                _noteState.value = ResultNotesReducer.deleteFailed(_noteState.value, error.message ?: "No se pudo eliminar la nota.")
            }
        }
    }

    fun retryNote() {
        when (_noteState.value.pendingAction) {
            ResultNotesAction.SAVE -> saveNote()
            ResultNotesAction.DELETE -> deleteNote()
            null -> Unit
        }
    }

    fun delete(onDeleted: () -> Unit) {
        if (_deleting.value) return
        _deleting.value = true
        _deleteFailed.value = false
        viewModelScope.launch {
            val deleted = runCatching {
                withContext(Dispatchers.IO) { repository.deleteMeasurement(assessmentId) }
            }.getOrDefault(false)
            _deleting.value = false
            if (deleted) onDeleted() else _deleteFailed.value = true
        }
    }

    fun acknowledgeDeleteFailure() {
        _deleteFailed.value = false
    }

    companion object {
        fun factory(assessmentId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SavedResultViewModel(
                    assessmentId = assessmentId,
                    repository = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp).repository,
                )
            }
        }
    }
}
