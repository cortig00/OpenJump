package com.openjump.app.ui.encoder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.EncoderRepository
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderAnalyzer
import com.openjump.app.encoder.StoredEncoderSession
import com.openjump.app.video.AppSession
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.app.ui.result.ResultNotesAction
import com.openjump.app.ui.result.ResultNotesReducer
import com.openjump.app.ui.result.ResultNotesState
import com.openjump.app.video.VideoFrameIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.openjump.app.video.launchWithLease
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EncoderResultState {
    data object Loading : EncoderResultState
    data class Content(
        val analysis: EncoderAnalysis,
        /** Immutable live-video inputs paired with this analysis snapshot. */
        val videoUri: String? = null,
        val videoIndex: VideoFrameIndex? = null,
        val noteDraft: String = "",
        val saving: Boolean = false,
        val savedId: Long? = null,
        val saveError: String? = null,
    ) : EncoderResultState
    data class Error(val message: String) : EncoderResultState
}

class EncoderResultViewModel internal constructor(
    private val repository: EncoderRepository,
    private val grants: com.openjump.app.video.VideoUriGrantReconciler,
    private val requestReconcile: () -> Unit,
) : ViewModel() {
    /** Snapshot paired with the analysis; never reread the mutable session during save. */
    private var analyzedDraft: com.openjump.app.encoder.EncoderDraft? = null
    private var analyzedDraftLease: com.openjump.app.video.VideoUriGrantReconciler.Lease? = null
    private var analyzedFrameIndex: VideoFrameIndex? = null
    private val _state = MutableStateFlow<EncoderResultState>(EncoderResultState.Loading)
    val state: StateFlow<EncoderResultState> = _state.asStateFlow()

    init { analyze() }

    fun analyze() {
        _state.value = EncoderResultState.Loading
        viewModelScope.launch {
            try {
                val draft = checkNotNull(AppSession.encoderDraft.value) { "No hay una sesión de encoder activa." }
                val timing = checkNotNull(draft.timingDecision) { "Confirma la cronología del vídeo." }
                val frameIndex = checkNotNull(AppSession.frameIndex.value) { "No hay índice de vídeo." }
                if (analyzedDraft?.videoUri != draft.videoUri) {
                    analyzedDraftLease?.close()
                    analyzedDraftLease = draft.videoUri?.let { grants.registerOwner(setOf(it)) }
                }
                analyzedDraft = draft.copy(tracking = draft.tracking.toList())
                analyzedFrameIndex = frameIndex
                val calibration = checkNotNull(draft.calibration) { "Completa la calibración A/B." }
                check(draft.tracking.isNotEmpty()) { "Selecciona y procesa un objetivo." }
                val analysis = withContext(Dispatchers.Default) {
                    EncoderAnalyzer.analyze(draft.setup, timing, calibration, draft.tracking)
                }
                AppSession.setEncoderAnalysis(analysis)
                _state.value = EncoderResultState.Content(
                    analysis = analysis,
                    videoUri = draft.videoUri,
                    videoIndex = frameIndex,
                    noteDraft = draft.notes.orEmpty(),
                )
            } catch (error: Exception) {
                _state.value = EncoderResultState.Error(error.message ?: "No se pudo analizar la trayectoria.")
            }
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        val content = _state.value as? EncoderResultState.Content ?: return
        if (content.saving || content.savedId != null) return
        val draft = analyzedDraft?.copy(notes = content.noteDraft) ?: return
        val index = analyzedFrameIndex ?: return
        _state.value = content.copy(saving = true, saveError = null)
        // Acquire before coroutine dispatch/withContext(IO): AppSession may reset before Room commits.
        val saveLease = draft.videoUri?.let { grants.registerOwner(setOf(it)) }
        viewModelScope.launchWithLease(saveLease, onCompletion = requestReconcile) {
            try {
                val id = withContext(Dispatchers.IO) { repository.save(draft, content.analysis, index) }
                _state.value = content.copy(savedId = id, saving = false, saveError = null)
                onSaved()
            } catch (error: Exception) {
                // Keep the rendered analysis and its exact inputs. A retry must only call save()
                // again, never rerun the potentially expensive analysis pipeline.
                val message = error.message ?: "No se pudo guardar la sesión."
                val current = _state.value as? EncoderResultState.Content ?: content
                _state.value = current.withSaveError(message)
            }
        }
    }

    override fun onCleared() {
        analyzedDraftLease?.close()
        requestReconcile()
        super.onCleared()
    }

    fun removeRepetition(ordinal: Int) {
        val content = _state.value as? EncoderResultState.Content ?: return
        if (!content.canRemoveRepetition(ordinal)) return
        val corrected = EncoderAnalyzer.removeRepetition(content.analysis, ordinal)
        AppSession.setEncoderAnalysis(corrected)
        _state.value = content.copy(analysis = corrected, saveError = null)
    }

    fun editNote(value: String) {
        val content = _state.value as? EncoderResultState.Content ?: return
        val valueLimited = value.take(NoteNormalizer.MAX_LENGTH)
        analyzedDraft = analyzedDraft?.copy(notes = valueLimited)
        AppSession.updateEncoderNote(valueLimited)
        _state.value = content.copy(noteDraft = valueLimited, saveError = null)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                EncoderResultViewModel(app.encoderRepository, app.videoUriGrantReconciler, app::requestVideoUriReconciliation)
            }
        }
    }
}

internal fun EncoderResultState.Content.withSaveError(message: String): EncoderResultState.Content =
    copy(saving = false, saveError = message)

internal fun EncoderResultState.Content.canRemoveRepetition(ordinal: Int): Boolean =
    !saving && savedId == null && analysis.repetitions.any { it.ordinal == ordinal }

/** Selects the successor after removal, or the previous repetition when removing the last one. */
internal fun selectionAfterRemoval(repetitions: List<com.openjump.app.encoder.EncoderRepetition>, ordinal: Int): Int? {
    val removedIndex = repetitions.indexOfFirst { it.ordinal == ordinal }
    if (removedIndex < 0) return null
    return if (repetitions.getOrNull(removedIndex + 1) != null) {
        removedIndex
    } else {
        repetitions.getOrNull(removedIndex - 1)?.let { removedIndex - 1 }
    }
}

sealed interface SavedEncoderState {
    data object Loading : SavedEncoderState
    data class Content(val stored: StoredEncoderSession) : SavedEncoderState
    data object Missing : SavedEncoderState
    data class Error(val message: String) : SavedEncoderState
}

class SavedEncoderResultViewModel(
    private val sessionId: Long,
    private val repository: EncoderRepository,
    private val retainVideoUri: (String) -> AutoCloseable? = { null },
    private val requestReconcile: () -> Unit = {},
) : ViewModel() {
    private var storedVideoLease: AutoCloseable? = null
    private val _state = MutableStateFlow<SavedEncoderState>(SavedEncoderState.Loading)
    val state: StateFlow<SavedEncoderState> = _state.asStateFlow()
    private val _noteState = MutableStateFlow(ResultNotesState())
    val noteState: StateFlow<ResultNotesState> = _noteState.asStateFlow()
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()
    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

    init { load() }

    fun load() {
        _state.value = SavedEncoderState.Loading
        viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { repository.sessionById(sessionId) }
                // Own the saved URI before publishing Content; delete-triggered reconciliation can
                // run before the navigation callback removes this result from composition.
                val nextLease = stored?.videoUri?.let(retainVideoUri)
                storedVideoLease?.close()
                storedVideoLease = nextLease
                _state.value = stored?.let { SavedEncoderState.Content(it) }?.also {
                    _noteState.value = ResultNotesState.fromSaved(it.stored.notes)
                } ?: SavedEncoderState.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = SavedEncoderState.Error(error.message ?: "No se pudo abrir la sesión.")
            }
        }
    }

    fun editNote(value: String) {
        _noteState.value = ResultNotesReducer.edit(_noteState.value, value)
    }

    fun saveNote(onSaved: () -> Unit = {}) {
        val content = _state.value as? SavedEncoderState.Content ?: return
        val request = NoteNormalizer.normalize(_noteState.value.draft.take(NoteNormalizer.MAX_LENGTH))
        if (_noteState.value.saving) return
        _noteState.value = ResultNotesReducer.saveRequested(_noteState.value)
        viewModelScope.launch {
            try {
                check(withContext(Dispatchers.IO) { repository.updateNotes(sessionId, request) })
                _noteState.value = ResultNotesReducer.saveSucceeded(_noteState.value, request)
                _state.value = SavedEncoderState.Content(content.stored.copy(notes = request))
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
                check(withContext(Dispatchers.IO) { repository.updateNotes(sessionId, null) })
                _noteState.value = ResultNotesReducer.deleteSucceeded(_noteState.value)
                (_state.value as? SavedEncoderState.Content)?.let { _state.value = SavedEncoderState.Content(it.stored.copy(notes = null)) }
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
                withContext(Dispatchers.IO) { repository.deleteSession(sessionId) }
            }.getOrDefault(false)
            _deleting.value = false
            if (deleted) onDeleted() else _deleteFailed.value = true
        }
    }

    override fun onCleared() {
        storedVideoLease?.close()
        storedVideoLease = null
        requestReconcile()
        super.onCleared()
    }

    fun acknowledgeDeleteFailure() {
        _deleteFailed.value = false
    }

    companion object {
        fun factory(sessionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                SavedEncoderResultViewModel(
                    sessionId,
                    app.encoderRepository,
                    retainVideoUri = { uri -> app.videoUriGrantReconciler.registerOwner(setOf(uri)) },
                    requestReconcile = app::requestVideoUriReconciliation,
                )
            }
        }
    }
}
