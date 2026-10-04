package com.openjump.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.export.DataExportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets

internal suspend fun runDataExportOperation(
    openOutputStream: suspend () -> java.io.OutputStream?,
    write: suspend (Writer) -> Unit,
    delete: suspend () -> Unit,
) {
    try {
        val stream = openOutputStream() ?: throw IOException("The selected document cannot be opened.")
        stream.use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer -> write(writer) }
        }
        currentCoroutineContext().ensureActive()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { delete() } }
        throw cancelled
    } catch (error: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { delete() } }
        throw error
    }
}

enum class DataExportFormat(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
}

sealed interface DataExportState {
    data object Idle : DataExportState
    data class Exporting(val format: DataExportFormat) : DataExportState
    data class Success(val format: DataExportFormat) : DataExportState
    data class Error(val format: DataExportFormat) : DataExportState
}

class DataExportViewModel(
    private val repository: DataExportRepository,
    private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<DataExportState>(DataExportState.Idle)
    val state: StateFlow<DataExportState> = _state.asStateFlow()

    fun export(format: DataExportFormat, uri: Uri) {
        if (_state.value is DataExportState.Exporting) return
        _state.value = DataExportState.Exporting(format)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runDataExportOperation(
                    openOutputStream = { context.contentResolver.openOutputStream(uri) },
                    write = { writer ->
                        when (format) {
                            DataExportFormat.JSON -> repository.writeJson(writer)
                            DataExportFormat.CSV -> repository.writeCsv(writer)
                        }
                    },
                    delete = { context.contentResolver.delete(uri, null, null) },
                )
                _state.value = DataExportState.Success(format)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = DataExportState.Error(format)
            }
        }
    }

    fun acknowledge() {
        if (_state.value !is DataExportState.Exporting) _state.value = DataExportState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                DataExportViewModel(app.dataExportRepository, app.applicationContext)
            }
        }
    }
}
