package com.openjump.app.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.PersonalRecord
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.launchWithLease
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Saved : SaveState
    data class Error(val message: String) : SaveState
}

data class LiveResultPresentation(
    val result: ProtocolResult,
    val uiModel: ResultUiModel,
)

class ResultViewModel internal constructor(
    private val repository: JumpRepository,
    private val grants: com.openjump.app.video.VideoUriGrantReconciler,
    private val requestReconcile: () -> Unit,
) : ViewModel() {
    private var retainedUri: String? = null
    private var draftLease: com.openjump.app.video.VideoUriGrantReconciler.Lease? = null

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    fun presentLive(draft: MeasurementDraft, detectedFps: Int): LiveResultPresentation {
        if (retainedUri != draft.videoUri) {
            draftLease?.close()
            retainedUri = draft.videoUri
            draftLease = draft.videoUri?.let { grants.registerOwner(setOf(it)) }
        }
        val result = ProtocolCalculator.compute(draft)
        return LiveResultPresentation(result, ResultPresenter.presentLive(draft, result, detectedFps))
    }

    fun save(
        draft: MeasurementDraft,
        result: ProtocolResult,
        detectedFps: Int,
        frameIndex: VideoFrameIndex? = null,
        onSaved: (List<PersonalRecord>) -> Unit,
    ) {
        if (_saveState.value == SaveState.Saving || _saveState.value == SaveState.Saved) return
        _saveState.value = SaveState.Saving
        // Synchronous lease closes reset-before-Room-commit; live UI keeps its separate lease on failure.
        val saveLease = draft.videoUri?.let { grants.registerOwner(setOf(it)) }
        viewModelScope.launchWithLease(saveLease, onCompletion = requestReconcile) {
            try {
                val id = repository.save(draft, result, detectedFps, frameIndex = frameIndex)
                // A failed optional PR query must never turn a successful save into a retry.
                val records = runCatching { repository.recordsWonBy(draft.athleteId, id) }.getOrDefault(emptyList())
                _saveState.value = SaveState.Saved
                onSaved(records)
            } catch (error: Exception) {
                _saveState.value = SaveState.Error(
                    error.message ?: "No se pudo guardar la medición.",
                )
            }
        }
    }

    override fun onCleared() {
        draftLease?.close()
        requestReconcile()
        super.onCleared()
    }

    fun clearError() {
        if (_saveState.value is SaveState.Error) _saveState.value = SaveState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                ResultViewModel(app.repository, app.videoUriGrantReconciler, app::requestVideoUriReconciliation)
            }
        }
    }
}
