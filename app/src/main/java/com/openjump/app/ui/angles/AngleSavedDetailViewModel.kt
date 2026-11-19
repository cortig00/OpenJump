package com.openjump.app.ui.angles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AngleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel del detalle guardado de un análisis de ángulos.
 *
 * Lee la raíz por la única puerta transaccional ([AngleRepository]) y NO abre vídeo, player ni
 * índice de frames: consultar un resultado guardado no depende de que el vídeo siga accesible.
 *
 * El borrado es la única escritura que ofrece: usa el repositorio existente (raíz + CASCADE, sin
 * tocar el fichero de vídeo) y sólo invalida el detalle cuando la fila se borró de verdad. Un fallo
 * deja la pantalla como estaba y muestra el reintento; nunca se presenta como éxito.
 */
class AngleSavedDetailViewModel(
    private val angleRepository: AngleRepository,
    /** Reconciliador de grants existente; se invoca sólo tras borrar, best-effort. */
    private val reconcileVideoUriGrants: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<AngleSavedDetailState>(AngleSavedDetailState.Loading)
    val state: StateFlow<AngleSavedDetailState> = _state.asStateFlow()

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

    private var analysisId: Long = 0L
    private var requestId = 0L

    /** Carga (o recarga) el análisis [analysisId]; ids no guardados se tratan como ausentes. */
    fun load(analysisId: Long) {
        // Una recreación de la pantalla no relee lo que el ViewModel ya tiene cargado: el detalle es
        // de solo lectura y no debe recargar datos que no han cambiado.
        if (analysisId == this.analysisId && _state.value is AngleSavedDetailState.Loaded) return
        this.analysisId = analysisId
        if (analysisId <= 0L) {
            _state.value = AngleSavedDetailState.Missing
            return
        }
        read(analysisId, silent = false)
    }

    /**
     * Relee la raíz guardada conservando a la vista el contenido ya cargado (por ejemplo al volver
     * del editor, que puede haber cambiado puntos o visibilidad). No abre vídeo ni recrea el editor.
     *
     * Si la relectura falla se mantiene la última foto leída con éxito: no se presenta un error como
     * éxito ni se vacía la pantalla, y la siguiente relectura con éxito la sustituye.
     */
    fun refresh() {
        val current = analysisId
        // Mientras la primera lectura está en curso no se duplica: no hay nada que refrescar aún.
        if (current <= 0L || _state.value is AngleSavedDetailState.Loading) return
        read(current, silent = _state.value is AngleSavedDetailState.Loaded)
    }

    private fun read(analysisId: Long, silent: Boolean) {
        val request = ++requestId
        if (!silent) _state.value = AngleSavedDetailState.Loading
        viewModelScope.launch {
            val next = try {
                withContext(Dispatchers.IO) {
                    angleSavedDetailState(angleRepository.snapshot(analysisId))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Lectura fallida: los datos siguen en Room, así que se ofrece reintento explícito.
                AngleSavedDetailState.Error
            }
            if (request != requestId) return@launch
            // Un fallo de la relectura silenciosa no borra lo que ya está en pantalla; un
            // "no existe" sí es un dato definitivo y se muestra.
            if (silent && next is AngleSavedDetailState.Error) return@launch
            _state.value = next
        }
    }

    fun retry() {
        if (analysisId > 0L) load(analysisId)
    }

    /**
     * Borra la raíz tras la confirmación existente. [onDeleted] sólo se invoca con la fila
     * realmente borrada; el fallo activa el diálogo de reintento.
     */
    fun delete(onDeleted: () -> Unit = {}) {
        if (_deleting.value || analysisId <= 0L) return
        _deleting.value = true
        _deleteFailed.value = false
        viewModelScope.launch {
            val deleted = try {
                withContext(Dispatchers.IO) { angleRepository.delete(analysisId) }
            } catch (cancellation: CancellationException) {
                _deleting.value = false
                throw cancellation
            } catch (_: Exception) {
                false
            }
            _deleting.value = false
            if (!deleted) {
                _deleteFailed.value = true
                return@launch
            }
            // El borrado ya está confirmado en Room; un fallo al reconciliar grants no lo revierte
            // ni debe presentarse como borrado fallido.
            try {
                reconcileVideoUriGrants()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Reconciliación diferible: se reintenta en el próximo arranque/resume.
            }
            onDeleted()
        }
    }

    fun acknowledgeDeleteFailure() {
        _deleteFailed.value = false
    }

    companion object {

        /** Factory de producción: la misma base Room y el reconciliador que usa HISTORY. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                AngleSavedDetailViewModel(
                    angleRepository = app.angleRepository,
                    reconcileVideoUriGrants = app::reconcileVideoUriGrants,
                )
            }
        }
    }
}
