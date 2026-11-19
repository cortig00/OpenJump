package com.openjump.app

import android.app.Application
import android.util.Log
import androidx.core.content.FileProvider
import com.openjump.app.data.AngleRepository
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.SelectedAthleteStore
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.angles.AndroidAngleImageFileIo
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.AngleMediaCoordination
import com.openjump.app.data.angles.AngleV2DeleteOwnership
import com.openjump.app.data.angles.AngleV2DeleteRepository
import com.openjump.app.data.angles.AngleV2SaveRepository
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.export.DataExportRepository
import com.openjump.app.data.backup.ManualBackupRepository
import com.openjump.app.data.backup.MediaBackupCoordinator
import com.openjump.app.settings.DisplayPreferencesStore
import com.openjump.app.settings.ExperienceModeStore
import com.openjump.app.settings.OnboardingPreferencesStore
import com.openjump.app.ui.angles.AngleMultiFrameSaveOwnership
import com.openjump.app.ui.angles.AngleV2FrameShareResult
import com.openjump.app.ui.angles.AngleV2FrameShareStager
import com.openjump.app.video.AndroidPersistedVideoGrantStore
import com.openjump.app.video.AppSession
import com.openjump.app.video.PersistedVideoGrantStore
import com.openjump.app.video.VideoUriGrantReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class OpenJumpApp : Application() {

    // Initialize in onCreate (after Context attachment), before any Activity can open Room.
    val onboardingPreferencesStore: OnboardingPreferencesStore by lazy { OnboardingPreferencesStore(this) }

    override fun onCreate() {
        super.onCreate()
        onboardingPreferencesStore // Synchronously persist PENDING before Room bootstrap.
    }

    val database: JumpDatabase by lazy { createDatabase() }

    /**
     * Fabrica de la base de datos de la aplicacion.
     *
     * Sigue siendo la unica puerta de Room en produccion ([JumpDatabase.get]); existe como costura
     * para que una subclase de prueba entregue una instancia aislada sin tocar el estado global ni
     * el esquema. No se llama de forma perezosa hasta el primer uso de [database].
     */
    protected open fun createDatabase(): JumpDatabase = JumpDatabase.get(this)

    /**
     * Fabrica del almacen persistente de permisos de URI de video.
     *
     * Costura de prueba: la implementacion de produccion es la unica que escribe en
     * `contentResolver`. El reconciliador se sigue construyendo igual (referencias guardadas del DAO
     * y referencias vivas de [AppSession]); solo cambia la fuente de permisos persistidos.
     */
    internal open fun createVideoGrantStore(): PersistedVideoGrantStore =
        AndroidPersistedVideoGrantStore(contentResolver)

    /** Constructing the manager is Room-lazy; its DAO lambda is first invoked after UI bootstrap. */
    internal val videoUriGrantReconciler: VideoUriGrantReconciler by lazy {
        VideoUriGrantReconciler(
            grants = createVideoGrantStore(),
            savedReferences = { database.jumpDao().savedVideoUris().toSet() },
            liveReferences = AppSession::videoUriReferences,
        )
    }
    private val grantCleanupScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * Ambito de aplicacion para operaciones de backup **ya confirmadas** por el usuario.
     *
     * No es `viewModelScope`: sobrevive a la destruccion del ViewModel que pidio la restauracion y
     * no la cancela, de modo que la operacion (publicacion de imagenes + transaccion + limpieza del
     * temporal + reconciliacion posterior) termina y aplica su resultado aunque el usuario salga de
     * Ajustes o el proceso de UI recree la pantalla.
     */
    private val confirmedBackupRestoreScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * Reconciliacion de aplicacion tras una restauracion **aplicada**: invalida la sesion de video,
     * solicita revisar los permisos persistentes de URI y re-resuelve el atleta seleccionado contra
     * los datos restaurados. Corre en el ambito de aplicacion del backup, no en el del ViewModel.
     */
    suspend fun reconcileAfterConfirmedRestore() {
        withContext(Dispatchers.IO) {
            AppSession.reset()
            requestVideoUriReconciliation()
            selectedAthleteStore.resolve(athleteRepository)
        }
    }

    suspend fun reconcileVideoUriGrants() = withContext(Dispatchers.IO) {
        videoUriGrantReconciler.reconcile()
    }

    fun requestVideoUriReconciliation() {
        grantCleanupScope.launch { videoUriGrantReconciler.reconcile() }
    }

    val repository: JumpRepository by lazy { JumpRepository(database.jumpDao(), ::reconcileVideoUriGrants) }
    val encoderRepository: EncoderRepository by lazy { EncoderRepository(database.encoderDao(), ::reconcileVideoUriGrants) }
    val athleteRepository: AthleteRepository by lazy { AthleteRepository(database.athleteDao(), ::reconcileVideoUriGrants) }
    val groupRepository: com.openjump.app.data.GroupRepository by lazy { com.openjump.app.data.GroupRepository(database.groupDao()) }
    /** Angle analyses are transactional over the shared database, like [repository] and [encoderRepository]. */
    val angleRepository: AngleRepository by lazy { AngleRepository(database) }
    val dataExportRepository: DataExportRepository by lazy { DataExportRepository(database) }

    /**
     * Almacen privado de imagenes V2 (raiz `filesDir`, subdirectorio propio `angle-images`) y
     * coordinador del backup con imagenes. El coordinador comparte [AngleMediaCoordination.mutex]
     * con los guardados V2, y guarda sus temporales en un directorio privado propio que nunca se
     * mezcla con el almacen ni con datos de usuario.
     */
    private val angleImageStore: PrivateAngleImageStore by lazy {
        PrivateAngleImageStore(rootDirectory = filesDir, fileIo = AndroidAngleImageFileIo())
    }

    /**
     * Única superficie **de lectura** del almacén privado de imágenes anotadas V2.
     *
     * El almacén sigue siendo privado: aquí no se expone el objeto ni ninguna operación de
     * escritura, borrado, recolección o backup. Esta función verifica tamaño y SHA-256 contra lo que
     * declara la fila del fotograma ([AngleImageIntegrity]) y **sólo si coincide** entrega los bytes
     * a un consumidor acotado; un fichero ausente o trocado se informa como fallo tipado
     * ([AngleImageReadResult.Failed]) y nunca como imagen válida. No toca la base ni el vídeo.
     *
     * Es bloqueante (lee y calcula el digest): el llamante debe invocarla fuera del hilo principal.
     */
    fun readVerifiedAngleImageBytes(
        imageKey: String,
        integrity: AngleImageIntegrity,
    ): AngleImageReadResult<ByteArray> =
        angleImageStore.readVerified(imageKey, integrity) { input -> input.readBytes() }

    /**
     * Raiz del directorio de comparticion de fotogramas ya verificados.
     *
     * Costura de prueba: el valor por defecto es el mismo de produccion ([cacheDir], la unica raiz
     * que `@xml/file_paths` declara para imagenes). Una subclase puede apuntar a una raiz aislada
     * para ejercitar el proveedor sin publicar ficheros fuera de su propio directorio.
     */
    internal open fun angleFrameShareCacheRoot(): java.io.File = cacheDir

    /**
     * URI del `FileProvider` para **un** fichero del directorio de compartición de fotogramas.
     *
     * Costura de prueba: `FileProvider.getUriForFile` resuelve las raíces de `@xml/file_paths`
     * contra el `Context` que recibe, asi que una subclase puede entregar la URI del proveedor real
     * cuando su app aislada redirige `cacheDir`. La autoridad y el proveedor son los mismos que en
     * produccion: no se registra ningun proveedor alternativo ni se amplia ninguna raiz declarada.
     */
    internal open fun angleFrameShareUri(file: java.io.File): android.net.Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    /**
     * Única superficie que publica una imagen **fuera** de la app: una copia PNG verificada por
     * fotograma en el directorio de compartición privado (`cacheDir/shared-angle-frames`), que es la
     * única ruta que `@xml/file_paths` declara para imágenes. Nunca se expone el almacén privado, ni
     * `filesDir`, ni el vídeo, ni una raíz de directorios: el llamante sólo recibe la URI de un
     * fichero ya verificado.
     *
     * Se construye de forma perezosa y no toca disco hasta la primera compartición real. Es bloqueante
     * (verifica, hashea y escribe la copia): el llamante debe invocarla fuera del hilo principal.
     */
    private val angleFrameShareStager: AngleV2FrameShareStager by lazy {
        AngleV2FrameShareStager(
            cacheRoot = angleFrameShareCacheRoot(),
            readVerifiedBytes = ::readVerifiedAngleImageBytes,
            uriForFile = ::angleFrameShareUri,
        )
    }

    /**
     * Prepara la copia compartible de la imagen de un fotograma guardado.
     *
     * Devuelve fallo tipado (nunca una imagen inválida) si la clave no es canónica, el PNG falta, no
     * coincide con el tamaño/SHA-256 declarados o la copia no se pudo publicar. La copia entregada no
     * se borra después: el receptor puede leerla más tarde.
     */
    fun prepareAngleFrameShare(
        imageKey: String,
        integrity: AngleImageIntegrity,
    ): AngleV2FrameShareResult = angleFrameShareStager.publish(imageKey, integrity)

    /**
     * Guardado V2 de análisis multi-fotograma: mismo almacén privado y mismo mutex de proceso que
     * el backup/restore con imágenes ([mediaBackupCoordinator]). Construirlo es perezoso y no toca
     * disco hasta el primer guardado real.
     */
    val angleV2SaveRepository: AngleV2SaveRepository by lazy {
        AngleV2SaveRepository(
            dao = database.angleAnalysisV2Dao(),
            imageStore = angleImageStore,
            coordinationMutex = AngleMediaCoordination.mutex,
        )
    }

    /**
     * Ámbito de aplicación del guardado multi-fotograma **ya iniciado**.
     *
     * No es `viewModelScope`: un llamante cancelado descarta el valor de un tramo no cancelable
     * (`withContext(NonCancellable)` protege la durabilidad, no la entrega), de modo que cerrar el
     * editor a mitad perdería el único reporte del intento —la fila podría existir sin que nadie lo
     * supiera y las imágenes publicadas sin fila quedarían sin rastro—. Con este ámbito el guardado
     * termina y su informe tipado (resultado + claves huérfanas) se **emite** aunque la pantalla ya
     * no exista: al log de diagnóstico de [angleMultiFrameSaveOwnership] y a su cola acotada en
     * memoria. No se persiste en disco ni se guarda ningún borrador: tras una muerte del proceso solo
     * quedan las filas reales ya confirmadas.
     */
    private val angleMultiFrameSaveScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Dueño de aplicación del guardado multi-fotograma (ver [AngleMultiFrameSaveOwnership]).
     *
     * El sumidero es el log de diagnóstico de Android (`Log.w`), que es el lector real del informe:
     * resultado tipado, id de raíz si la transacción terminó y claves de imagen publicadas sin fila.
     * Es lo que evita que el resultado de un guardado que sobrevive al cierre del editor acabe en un
     * campo que nadie lee. Las claves son identificadores internos del almacén privado, no datos de
     * usuario ni rutas, y este registro no se muestra en la interfaz.
     */
    internal val angleMultiFrameSaveOwnership: AngleMultiFrameSaveOwnership by lazy {
        AngleMultiFrameSaveOwnership(
            scope = angleMultiFrameSaveScope,
            reporter = { report -> Log.w(ANGLE_MULTI_FRAME_SAVE_LOG_TAG, report.diagnosticSummary()) },
        )
    }

    val mediaBackupCoordinator: MediaBackupCoordinator by lazy {
        MediaBackupCoordinator(
            dao = database.manualBackupDao(),
            imageStore = angleImageStore,
            stagingDirectory = java.io.File(filesDir, "media-backup-staging"),
            coordinationMutex = AngleMediaCoordination.mutex,
        )
    }

    val manualBackupRepository: ManualBackupRepository by lazy {
        ManualBackupRepository(
            dao = database.manualBackupDao(),
            media = mediaBackupCoordinator,
            confirmedRestoreScope = confirmedBackupRestoreScope,
            afterConfirmedRestore = { reconcileAfterConfirmedRestore() },
        )
    }

    /**
     * Borrado V2 confirmado: misma base, mismo almacén privado y **mismo mutex/generación de
     * restauración** que el guardado y el backup/restore con imágenes.
     *
     * Construirlo es perezoso y no toca disco ni abre transacciones hasta el primer borrado real. La
     * familia `ANGLE_V2` del historial lo usa a través de [angleV2DeleteOwnership]: la ejecución corre
     * en el ámbito de aplicación, nunca en `viewModelScope`, y la preparación de la prueba exacta es
     * de sólo lectura.
     */
    internal val angleV2DeleteRepository: AngleV2DeleteRepository by lazy {
        AngleV2DeleteRepository(
            dao = database.angleAnalysisV2Dao(),
            imageStore = angleImageStore,
            coordinationMutex = AngleMediaCoordination.mutex,
            restoreEpoch = AngleMediaCoordination.restoreEpoch,
        )
    }

    /**
     * Ámbito de aplicación del borrado V2 **ya iniciado**.
     *
     * No es `viewModelScope`: un llamante cancelado descarta el valor de `withContext(NonCancellable)`
     * (protege la durabilidad, no la entrega), así que cerrar la pantalla a mitad perdería el único
     * informe de un `DELETE` ya confirmado y de las imágenes que quedaron retenidas. Con este ámbito
     * el borrado termina y su informe tipado se emite aunque la pantalla ya no exista. No se persiste
     * en disco ni se guarda una cola de reintentos: sólo quedan las filas realmente borradas.
     */
    private val angleV2DeleteScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Dueño de aplicación del borrado V2 confirmado (ver [AngleV2DeleteOwnership]).
     *
     * El sumidero es el log de diagnóstico (`Log.w`): desenlace tipado, id de raíz y conteos del
     * cleanup. **No** contiene claves de imagen, rutas, URIs ni valores de usuario, y no se muestra en
     * la interfaz.
     */
    internal val angleV2DeleteOwnership: AngleV2DeleteOwnership by lazy {
        AngleV2DeleteOwnership(
            scope = angleV2DeleteScope,
            reporter = { report -> Log.w(ANGLE_V2_DELETE_LOG_TAG, report.diagnosticSummary()) },
        )
    }
    val testingRepository: com.openjump.app.data.TestingRepository by lazy {
        com.openjump.app.data.TestingRepository(database.testingDao(), database.groupDao())
    }
    val fvpRepository: FvpRepository by lazy { FvpRepository(database.fvpDao()) }
    val selectedAthleteStore: SelectedAthleteStore by lazy { SelectedAthleteStore(this) }

    val experienceModeStore: ExperienceModeStore by lazy { ExperienceModeStore(this) }
    val displayPreferencesStore: DisplayPreferencesStore by lazy { DisplayPreferencesStore(this) }
}

/**
 * Etiqueta del registro de diagnóstico del guardado multi-fotograma.
 *
 * Es un canal interno (`logcat -s OpenJumpAngleSave`), no interfaz: aquí es donde queda recuperable el
 * informe de un guardado que terminó después de cerrarse el editor.
 */
private const val ANGLE_MULTI_FRAME_SAVE_LOG_TAG: String = "OpenJumpAngleSave"

/**
 * Etiqueta del registro de diagnóstico del borrado V2 confirmado.
 *
 * Canal interno (`logcat -s OpenJumpAngleDelete`), no interfaz: aquí queda recuperable el informe de
 * un borrado que terminó después de cerrarse la pantalla que lo pidió.
 */
private const val ANGLE_V2_DELETE_LOG_TAG: String = "OpenJumpAngleDelete"
