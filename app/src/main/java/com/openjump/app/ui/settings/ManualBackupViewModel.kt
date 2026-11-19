package com.openjump.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.backup.AngleV2DiscardWarning
import com.openjump.app.data.backup.ManualBackupRepository
import com.openjump.app.data.backup.ManualBackupRestoreRun
import com.openjump.app.data.backup.ManualBackupSummary
import com.openjump.app.data.backup.MediaBackupImportException
import com.openjump.app.data.backup.MediaBackupLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface ManualBackupState {
    data object Idle : ManualBackupState
    data object Creating : ManualBackupState
    data class CreateSuccess(val summary: ManualBackupSummary) : ManualBackupState
    data object Reading : ManualBackupState

    /**
     * Carga leida y verificada, lista para confirmar. [angleV2Warning] llega cuando la base tiene
     * ahora mismo filas V2 que la restauracion sustituiria, **con cualquier formato** (JSON v1 o
     * contenedor ZIP, con analisis dentro o sin ellos): es la materia de la **segunda** advertencia,
     * que necesita su propia confirmacion antes de restaurar. `null` significa que no habia agregado
     * que perder; en ese caso la comprobacion atomica del DAO sigue exigiendo que el agregado este
     * vacio justo antes de borrar.
     */
    data class ReadyToRestore(
        val summary: ManualBackupSummary,
        val angleV2Warning: AngleV2DiscardWarning? = null,
    ) : ManualBackupState
    data object Restoring : ManualBackupState
    data class RestoreSuccess(
        val summary: ManualBackupSummary,
        val housekeepingFailed: Boolean = false,
    ) : ManualBackupState

    /**
     * La restauracion se rechazo **despues** de publicar imagenes: no se sustituyo ninguna fila y
     * esas [imageCount] imagenes se conservan en el almacen privado sin ninguna fila que las
     * referencie. Solo se publica el numero: ni claves, ni rutas, ni ningun otro dato.
     */
    data class RestoreOrphans(val imageCount: Int) : ManualBackupState
    data class Error(val kind: ManualBackupError) : ManualBackupState
}

enum class ManualBackupError { INVALID_CONTRACT, UNSUPPORTED_VERSION, CORRUPT_OR_INCOHERENT, TOO_LARGE, READ_FAILED, WRITE_FAILED, PARTIAL, REVERTED, STATE_UNVERIFIED }

class ManualBackupViewModel(private val repository: ManualBackupRepository, private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow<ManualBackupState>(ManualBackupState.Idle)
    val state: StateFlow<ManualBackupState> = _state.asStateFlow()

    /**
     * Ciclo de vida de la carga leida, protegido contra carreras entre la UI y las corrutinas:
     * una carga con imagenes es duena de un temporal y debe cerrarse **exactamente una vez** y
     * nunca mientras se esta usando.
     */
    private val lifecycleLock = Any()

    /** Carga leida y verificada que aun no se restaura; su cierre es responsabilidad del ViewModel. */
    private var pending: MediaBackupLoad? = null

    /**
     * `true` mientras una restauracion confirmada esta en vuelo: en ese estado [pending] es `null`
     * y la carga pertenece a la operacion de aplicacion del repositorio, que la cierra siempre en
     * su `finally` no cancelable (tambien si este ViewModel se destruye).
     */
    private var restoreInFlight = false

    /**
     * Exporta al documento [uri] que el usuario acaba de elegir.
     *
     * **El documento destino no se borra NUNCA**, ni al fallar ni al cancelar. La URI no distingue
     * un documento recien creado por el selector de uno que ya existia (podria ser un backup previo
     * del usuario, de este u otro dispositivo): `contentResolver.delete` lo destruiria sin
     * posibilidad de recuperarlo y sin que el usuario lo haya pedido. Tampoco hay promesa de
     * atomicidad en SAF: `openOutputStream` puede truncar un documento existente y el contenido
     * escrito puede quedar incompleto si un `write`/`flush`/`close` del `OutputStream` (o un fallo
     * del proveedor) ocurre a mitad. Por eso un fallo se informa como fallo (o como
     * [ManualBackupError.PARTIAL] si ya se abrio el destino) y se deja el archivo tal como quedo, en
     * vez de afirmar un exito o una limpieza que no se han producido. La unica garantia es que
     * [ManualBackupState.CreateSuccess] solo se publica despues de que el `use` cierre el flujo sin
     * excepcion; cualquier otra cosa es un estado de error.
     */
    fun create(uri: Uri) {
        synchronized(lifecycleLock) {
            if (restoreInFlight) return
            if (_state.value !is ManualBackupState.Idle && _state.value !is ManualBackupState.Error) return
            discardPendingLocked()
            _state.value = ManualBackupState.Creating
        }
        viewModelScope.launch(Dispatchers.IO) {
            var destinationOpened = false
            try {
                // El export es siempre el contenedor ZIP del repositorio (con o sin imagenes),
                // coherente con el MIME y la extension con los que se creo el documento destino.
                val exported = context.contentResolver.openOutputStream(uri)?.use {
                    destinationOpened = true
                    repository.export(it)
                } ?: throw IOException("Unable to open destination")
                _state.value = ManualBackupState.CreateSuccess(exported.summary)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Cancelacion: el destino puede contener un documento del usuario y se deja como
                // esta. No se borra nada y no se publica ningun exito.
                throw cancelled
            } catch (error: Exception) {
                // Fallo (incluido `close()` del flujo de staging): no se borra el documento elegido
                // y el estado nunca afirma exito. Si el destino llego a abrirse se informa PARTIAL
                // porque el archivo puede quedar inservible, no porque se haya limpiado.
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
        synchronized(lifecycleLock) {
            if (restoreInFlight) return
            if (_state.value !is ManualBackupState.Idle && _state.value !is ManualBackupState.Error) return
            // Nunca dos cargas pendientes: la anterior se suelta antes de empezar otra, y el
            // dialogo de confirmacion siempre muestra la carga que se va a restaurar de verdad.
            discardPendingLocked()
            _state.value = ManualBackupState.Reading
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val descriptorLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
                val loaded = context.contentResolver.openInputStream(uri)?.use { repository.load(it, descriptorLength) }
                    ?: throw IOException("Unable to open source")
                // Advertencia de la segunda confirmacion: se calcula para CUALQUIER formato, porque
                // cualquier restore sustituye el agregado V2 actual. Se calcula ANTES de ceder la
                // carga y sin suspensiones posteriores hasta el traspaso, asi el dialogo que se
                // muestre siempre describe la carga que se va a restaurar.
                val warning = try {
                    repository.angleV2ReplaceWarning(loaded)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    closeQuietly(loaded)
                    throw cancelled
                } catch (_: Exception) {
                    // Sin poder leer el estado V2 no se puede advertir de lo que se perderia: no se
                    // ofrece restaurar y no se toca nada.
                    closeQuietly(loaded)
                    _state.value = ManualBackupState.Error(ManualBackupError.STATE_UNVERIFIED)
                    return@launch
                }
                synchronized(lifecycleLock) {
                    // Sin suspension entre la lectura y esta cesion: nadie pudo cerrarla antes.
                    // `Reading` sigue siendo el estado, asi que solo `read` puede llegar aqui.
                    pending = loaded
                    _state.value = ManualBackupState.ReadyToRestore(loaded.summary, warning)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                discardPending()
                throw cancelled
            } catch (error: Exception) {
                discardPending()
                _state.value = ManualBackupState.Error(error.toBackupError())
            }
        }
    }

    fun confirmRestore() {
        val transfer = synchronized(lifecycleLock) {
            if (restoreInFlight) return
            val ready = _state.value as? ManualBackupState.ReadyToRestore ?: return
            val load = pending ?: return
            // Traspaso de propiedad: desde aqui la carga ya no es "pendiente" y solo la cierra la
            // operacion confirmada (o este mismo metodo si el arranque se rechaza).
            // `cancelRestore`/`acknowledge`/`onCleared` no pueden cerrarla mientras se usa (eso
            // abortaba la importacion en vuelo con `ArchiveClosed`).
            pending = null
            restoreInFlight = true
            _state.value = ManualBackupState.Restoring
            load to ready.angleV2Warning
        }
        val (load, warning) = transfer
        // La restauracion confirmada NO corre en `viewModelScope`: se ejecuta en el ambito de
        // aplicacion del repositorio, con duracion completa no cancelable una vez arrancada. Asi
        // `onCleared` no la cancela, el resultado y la reconciliacion de AppSession/atleta/grants
        // se aplican aunque el ViewModel desaparezca, y la carga (con su temporal) se cierra
        // siempre. `withContext(NonCancellable + IO)` dentro del coordinador no bastaria: un
        // llamante cancelado descarta el valor devuelto.
        //
        // `warning` viaja con la confirmacion: es el objeto que el usuario acepto en la segunda
        // advertencia (existe si habia agregado V2 en la base, con cualquier formato de carga). El
        // repositorio lo revalida bajo el mutex y dentro de la transaccion; con `null` la via sigue
        // cerrada porque el DAO exige de forma atomica que el agregado este vacio.
        if (!repository.startConfirmedRestore(load, warning, ::onConfirmedRestoreFinished)) {
            // Rechazo antes de empezar: no se toco nada y la carga sigue siendo de este ViewModel.
            // Se cierra aqui: ni temporal filtrado ni estado `Restoring` colgado.
            closeQuietly(load)
            synchronized(lifecycleLock) { restoreInFlight = false }
            _state.value = ManualBackupState.Error(ManualBackupError.REVERTED)
        }
    }

    /**
     * Cierre de una restauracion confirmada. Se ejecuta en el ambito de aplicacion del repositorio,
     * tambien si este ViewModel ya fue destruido: entonces publicar el estado es inofensivo y la
     * propiedad de la carga ya la cerro el repositorio (aqui no se cierra nada).
     */
    private fun onConfirmedRestoreFinished(result: ManualBackupRestoreRun) {
        synchronized(lifecycleLock) { restoreInFlight = false }
        _state.value = when (result) {
            is ManualBackupRestoreRun.Applied -> ManualBackupState.RestoreSuccess(result.summary, result.housekeepingFailed)
            ManualBackupRestoreRun.Reverted -> ManualBackupState.Error(ManualBackupError.REVERTED)
            // Rechazo tras preparar imagenes: se conservan y se informan (solo el numero).
            is ManualBackupRestoreRun.NotApplied -> ManualBackupState.RestoreOrphans(
                (result.preservedImageKeys + result.unsyncedImageKeys).distinct().size,
            )
        }
    }

    /**
     * Suelta la carga pendiente que aun no se ha confirmado.
     *
     * Una restauracion en vuelo **no** se cancela por aqui: esta API no puede cerrar la carga que
     * la operacion de aplicacion esta usando, y una restauracion confirmada no se interrumpe una vez
     * arrancada (ver `MediaBackupCoordinator.restore`). Si producto quiere una restauracion
     * cancelable hace falta una via cooperativa dentro del coordinador (cancelacion entre pasos,
     * estado publicado y semantica de las imagenes ya publicadas); no se simula cerrando recursos
     * por debajo.
     */
    fun cancelRestore() {
        synchronized(lifecycleLock) {
            if (restoreInFlight) return
            discardPendingLocked()
            if (_state.value is ManualBackupState.ReadyToRestore) _state.value = ManualBackupState.Idle
        }
    }

    fun acknowledge() {
        synchronized(lifecycleLock) {
            if (restoreInFlight) return
            if (_state.value !is ManualBackupState.Creating && _state.value !is ManualBackupState.Reading && _state.value !is ManualBackupState.Restoring) {
                discardPendingLocked()
                _state.value = ManualBackupState.Idle
            }
        }
    }

    /**
     * Suelta la carga pendiente. Una carga con imagenes posee un temporal del lector: se cierra
     * siempre (tambien al cancelar o al fallar) y un fallo de limpieza no convierte el resultado en
     * exito ni se propaga desde aqui.
     */
    private fun discardPending() {
        synchronized(lifecycleLock) { discardPendingLocked() }
    }

    private fun discardPendingLocked() {
        val loaded = pending
        pending = null
        if (loaded != null) closeQuietly(loaded)
    }

    private fun closeQuietly(load: MediaBackupLoad) {
        runCatching { load.close() }
    }

    private fun Exception.toBackupError() = when {
        message?.contains("large", true) == true -> ManualBackupError.TOO_LARGE
        // A negative declared SAF length keeps the codec's "Invalid manual backup length"
        // and stays corrupt; only a positive over-limit length reports "too large".
        message?.contains("Invalid manual backup length", true) == true -> ManualBackupError.CORRUPT_OR_INCOHERENT
        message?.contains("Unsupported", true) == true -> ManualBackupError.UNSUPPORTED_VERSION
        message?.contains("Not an OpenJump", true) == true -> ManualBackupError.INVALID_CONTRACT
        this is kotlinx.serialization.SerializationException -> ManualBackupError.CORRUPT_OR_INCOHERENT
        this is MediaBackupImportException.LimitExceeded -> ManualBackupError.TOO_LARGE
        // Estructura, manifiesto, entradas o integridad del contenedor: el archivo esta danado o
        // no es coherente, no es un fallo de lectura del proveedor.
        this is MediaBackupImportException -> ManualBackupError.CORRUPT_OR_INCOHERENT
        this is IOException -> ManualBackupError.READ_FAILED
        else -> ManualBackupError.CORRUPT_OR_INCOHERENT
    }

    override fun onCleared() {
        // Si una restauracion posee la carga, no se cierra aqui: la cierra la operacion de
        // aplicacion en su `finally` no cancelable, aunque el viewModelScope se cancele al limpiar
        // el ViewModel. Su callback publica el resultado sobre este mismo objeto y no necesita que
        // el ViewModel siga vivo.
        synchronized(lifecycleLock) { if (!restoreInFlight) discardPendingLocked() }
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                ManualBackupViewModel(app.manualBackupRepository, app.applicationContext)
            }
        }
    }
}
