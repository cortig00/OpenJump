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

sealed interface SavedBilateralState {
    data object Loading : SavedBilateralState
    data class Content(
        val model: BilateralResultUiModel,
        val noteState: ResultNotesState = ResultNotesState.fromSaved(model.note),
    ) : SavedBilateralState
    data object Missing : SavedBilateralState
    data object Error : SavedBilateralState
}

class SavedBilateralResultViewModel(private val id: Long, private val repository: JumpRepository) : ViewModel() {
    private val _state = MutableStateFlow<SavedBilateralState>(SavedBilateralState.Loading)
    val state: StateFlow<SavedBilateralState> = _state.asStateFlow()
    private val _noteState = MutableStateFlow(ResultNotesState())
    val noteState: StateFlow<ResultNotesState> = _noteState.asStateFlow()
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()
    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                when (val measurement = repository.measurementById(id)) {
                    null -> SavedBilateralState.Missing
                    else -> SavedBilateralState.Content(BilateralResultPresenter.presentStored(measurement)).also {
                        _noteState.value = ResultNotesState.fromSaved(it.model.note)
                    }
                }
            } catch (error: Exception) {
                SavedBilateralState.Error
            }
        }
    }

    fun editNote(value: String) {
        _noteState.value = ResultNotesReducer.edit(_noteState.value, value)
    }

    fun saveNote(onSaved: () -> Unit = {}) {
        val content = _state.value as? SavedBilateralState.Content ?: return
        val request = NoteNormalizer.normalize(_noteState.value.draft.take(NoteNormalizer.MAX_LENGTH))
        if (_noteState.value.saving) return
        _noteState.value = ResultNotesReducer.saveRequested(_noteState.value)
        viewModelScope.launch {
            try {
                check(withContext(Dispatchers.IO) { repository.updateNotes(id, request) })
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
                check(withContext(Dispatchers.IO) { repository.updateNotes(id, null) })
                _noteState.value = ResultNotesReducer.deleteSucceeded(_noteState.value)
                (_state.value as? SavedBilateralState.Content)?.let { content ->
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
                withContext(Dispatchers.IO) { repository.deleteMeasurement(id) }
            }.getOrDefault(false)
            _deleting.value = false
            if (deleted) onDeleted() else _deleteFailed.value = true
        }
    }

    fun acknowledgeDeleteFailure() {
        _deleteFailed.value = false
    }

    companion object {
        fun factory(id: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SavedBilateralResultViewModel(
                    id,
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp).repository,
                )
            }
        }
    }
}
