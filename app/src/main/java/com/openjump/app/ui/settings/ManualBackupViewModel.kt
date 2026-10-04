package com.openjump.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.backup.ManualBackupDocument
import com.openjump.app.data.backup.ManualBackupRepository
import com.openjump.app.data.backup.ManualBackupSummary
import com.openjump.app.video.AppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ManualBackupState {
    data object Idle : ManualBackupState
    data object Creating : ManualBackupState
    data class CreateSuccess(val summary: ManualBackupSummary) : ManualBackupState
    data object Reading : ManualBackupState
    data class ReadyToRestore(val summary: ManualBackupSummary) : ManualBackupState
    data object Restoring : ManualBackupState
    data class RestoreSuccess(
        val summary: ManualBackupSummary,
        val housekeepingFailed: Boolean = false,
    ) : ManualBackupState
    data class Error(val kind: ManualBackupError) : ManualBackupState
}

enum class ManualBackupError { INVALID_CONTRACT, UNSUPPORTED_VERSION, CORRUPT_OR_INCOHERENT, TOO_LARGE, READ_FAILED, WRITE_FAILED, PARTIAL, REVERTED }

class ManualBackupViewModel(private val repository: ManualBackupRepository, private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow<ManualBackupState>(ManualBackupState.Idle)
    val state: StateFlow<ManualBackupState> = _state.asStateFlow()
    private var pending: ManualBackupDocument? = null

    fun create(uri: Uri) {
        if (_state.value !is ManualBackupState.Idle && _state.value !is ManualBackupState.Error) return
        _state.value = ManualBackupState.Creating
        viewModelScope.launch(Dispatchers.IO) {
            var destinationOpened = false
            try {
                // Capture once and pass that exact document to the writer. A second DAO snapshot
                // could make the success summary differ from the bytes selected for SAF.
                val document = repository.snapshot()
                context.contentResolver.openOutputStream(uri)?.use {
                    destinationOpened = true
                    repository.create(document, it)
                } ?: throw IOException("Unable to open destination")
                _state.value = ManualBackupState.CreateSuccess(ManualBackupSummary.from(document))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                deleteBestEffort(uri); throw cancelled
            } catch (error: Exception) {
                deleteBestEffort(uri)
                val kind = if (error.message?.contains("large", true) == true) {
                    ManualBackupError.TOO_LARGE
                } else if (destinationOpened) {
                    ManualBackupError.PARTIAL
                } else {
                    ManualBackupError.WRITE_FAILED
                }
                _state.value = ManualBackupState.Error(kind)
            }
        }
    }

    fun read(uri: Uri) {
        if (_state.value !is ManualBackupState.Idle && _state.value !is ManualBackupState.Error) return
        _state.value = ManualBackupState.Reading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val descriptorLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
                val document = context.contentResolver.openInputStream(uri)?.use { repository.read(it, descriptorLength) }
                    ?: throw IOException("Unable to open source")
                pending = document
                _state.value = ManualBackupState.ReadyToRestore(ManualBackupSummary.from(document))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pending = null
                throw cancelled
            } catch (error: Exception) {
                pending = null
                _state.value = ManualBackupState.Error(error.toBackupError())
            }
        }
    }

    fun confirmRestore() {
        val document = pending ?: return
        if (_state.value !is ManualBackupState.ReadyToRestore) return
        _state.value = ManualBackupState.Restoring
        viewModelScope.launch(Dispatchers.IO) {
            val summary = try {
                repository.restore(document)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pending = null
                throw cancelled
            } catch (_: Exception) {
                // Only the transactional repository operation can report a rollback.
                pending = null
                _state.value = ManualBackupState.Error(ManualBackupError.REVERTED)
                return@launch
            }
            val housekeepingFailed = try {
                withContext(NonCancellable) {
                    AppSession.reset()
                    val app = context.applicationContext as OpenJumpApp
                    app.requestVideoUriReconciliation()
                    app.selectedAthleteStore.resolve(app.athleteRepository)
                }
                false
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pending = null
                throw cancelled
            } catch (_: Exception) {
                true
            }
            pending = null
            _state.value = ManualBackupState.RestoreSuccess(summary, housekeepingFailed)
        }
    }

    fun cancelRestore() { pending = null; if (_state.value is ManualBackupState.ReadyToRestore) _state.value = ManualBackupState.Idle }
    fun acknowledge() { if (_state.value !is ManualBackupState.Creating && _state.value !is ManualBackupState.Reading && _state.value !is ManualBackupState.Restoring) { pending = null; _state.value = ManualBackupState.Idle } }

    private suspend fun deleteBestEffort(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) { runCatching { context.contentResolver.delete(uri, null, null) } }
    private fun Exception.toBackupError() = when {
        message?.contains("large", true) == true -> ManualBackupError.TOO_LARGE
        // A negative declared SAF length keeps the codec's "Invalid manual backup length"
        // and stays corrupt; only a positive over-limit length reports "too large".
        message?.contains("Invalid manual backup length", true) == true -> ManualBackupError.CORRUPT_OR_INCOHERENT
        message?.contains("Unsupported", true) == true -> ManualBackupError.UNSUPPORTED_VERSION
        message?.contains("Not an OpenJump", true) == true -> ManualBackupError.INVALID_CONTRACT
        this is kotlinx.serialization.SerializationException -> ManualBackupError.CORRUPT_OR_INCOHERENT
        this is IOException -> ManualBackupError.READ_FAILED
        else -> ManualBackupError.CORRUPT_OR_INCOHERENT
    }

    override fun onCleared() { pending = null; super.onCleared() }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                ManualBackupViewModel(app.manualBackupRepository, app.applicationContext)
            }
        }
    }
}
