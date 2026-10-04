package com.openjump.app.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.PersonalRecord
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.app.video.launchWithLease
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BilateralSaveState {
    data object Idle : BilateralSaveState
    data object Saving : BilateralSaveState
    data object Saved : BilateralSaveState
    data object Error : BilateralSaveState
}

class BilateralResultViewModel internal constructor(
    private val repository: JumpRepository,
    private val grants: com.openjump.app.video.VideoUriGrantReconciler,
    private val requestReconcile: () -> Unit,
) : ViewModel() {
    private val _saveState = MutableStateFlow<BilateralSaveState>(BilateralSaveState.Idle)
    val saveState: StateFlow<BilateralSaveState> = _saveState.asStateFlow()

    fun save(comparison: BilateralComparison, onSaved: (List<PersonalRecord>) -> Unit) {
        save(comparison, comparison.notes, onSaved)
    }

    fun save(comparison: BilateralComparison, note: String?, onSaved: (List<PersonalRecord>) -> Unit) {
        if (_saveState.value == BilateralSaveState.Saving || _saveState.value == BilateralSaveState.Saved) return
        _saveState.value = BilateralSaveState.Saving
        val saveLease = grants.registerOwner(comparison.attempts.mapNotNull { it.draft.videoUri }.toSet())
        viewModelScope.launchWithLease(saveLease, onCompletion = requestReconcile) {
            try {
                val id = repository.saveBilateral(comparison.copy(notes = NoteNormalizer.normalize(note)))
                val records = runCatching { repository.recordsWonBy(comparison.athleteId, id) }.getOrDefault(emptyList())
                _saveState.value = BilateralSaveState.Saved
                onSaved(records)
            } catch (error: Exception) {
                _saveState.value = BilateralSaveState.Error
            }
        }
    }

    fun clearError() {
        if (_saveState.value is BilateralSaveState.Error) _saveState.value = BilateralSaveState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                BilateralResultViewModel(app.repository, app.videoUriGrantReconciler, app::requestVideoUriReconciliation)
            }
        }
    }
}
