package com.openjump.app.ui.angles

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.AngleAnalysisDraft
import com.openjump.app.data.AngleAnalysisSnapshot
import com.openjump.app.data.AngleUpdateDraft
import com.openjump.app.data.AngleUpdateResult
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.angles.AndroidAngleImageFileIo
import com.openjump.app.data.angles.AngleV2SaveRepository
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.testsupport.awaitValueBlocking
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoUriGrantReconciler
import com.openjump.app.video.angles.FrozenFrameImage
import com.openjump.app.video.angles.FrozenFrameSourceOpen
import com.openjump.app.video.angles.FrozenFrameSourceSession
import com.openjump.app.video.angles.FrozenFrameSources
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * Arnés instrumentado del flujo multi-fotograma sobre el **`AngleEditorViewModel` real**.
 *
 * Este arnés no sustituye la lógica del editor: construye la instancia real con sus costuras de
 * dependencias (`store`, `grants`, `indexer`, `createViewer`, `frozenSourceOpener`, coordinador de
 * guardado) y solo falsifica lo que la frontera Android no permite conducir con determinismo:
 *
 * - **fuente**: se abre la fuente congelada **real** ([FrozenFrameSources.openFromUri]) sobre una
 *   copia propia del fixture sintético `angles/ordinal_cfr_bframes.mp4`. El índice que consume el
 *   visor falso se deriva de una apertura de sonda del mismo fichero, de modo que el PTS confirmado
 *   por el visor es **exactamente** el del índice sellado (requisito de `ConfirmationPtsMismatch`).
 * - **visor**: [RecordingViewer] publica un `FrameDisplayState` en pausa con el frame pedido ==
 *   renderizado y el PTS canónico del índice; no hay ExoPlayer ni superficie.
 * - **Room**: base **en memoria** ([Room.inMemoryDatabaseBuilder]); nunca se abre la base de la app.
 * - **almacén de imágenes**: [PrivateAngleImageStore] real con `AndroidAngleImageFileIo` real sobre
 *   un raíz aislado con UUID dentro de `cacheDir`; se borra al cerrar. No se toca la raíz de usuario.
 *
 * El coordinador de guardado ([AngleMultiFrameSaveCoordinator]) es siempre el real. Su renderer
 * depende de [productionRenderer]:
 *
 * - **por defecto** ([productionRenderer] = `false`) se niega a trabajar: con el gate de producto
 *   apagado la pantalla no debe invocarlo, así que cualquier llamada es un fallo del test, no un
 *   éxito silencioso (lo usa el test del gate);
 * - con [productionRenderer] = `true` construye el **renderer real de producción**
 *   ([AndroidAngleMultiFrameImageRenderer]) con la misma densidad y los mismos textos localizados
 *   que la `factory` de la app. El arnés solo lo envuelve en un contador de paso directo
 *   ([CountingRenderer]) que anota cuántas veces lo invocó el coordinador; ningún byte del PNG sale
 *   del contador.
 *
 * El arnés **no** modifica código de producción, no cambia el gate
 * ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) y no escribe ninguna fila por su cuenta: el guardado solo
 * ocurre cuando la pantalla real invoca la acción que el test cablea a la entrada pública del
 * `ViewModel` ([AngleEditorViewModel.saveMultiFrame]). El arnés no expone ninguna vía alternativa
 * para pedir el guardado: el resultado se observa en [saveState] y con [awaitSaveSettled].
 */
internal class AngleMultiFrameFlowHarness(
    private val root: File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "angle-multi-frame-flow-${UUID.randomUUID()}",
    ),
    /**
     * `true` usa el renderer real de producción (ver el KDoc de la clase). `false` conserva el
     * renderer que rechaza para el contrato del gate apagado.
     */
    productionRenderer: Boolean = false,
) : AutoCloseable {

    companion object {
        /** Fixture sintético de 24 frames CFR 320x240; no son datos de usuario. */
        private const val FIXTURE_ASSET = "angles/ordinal_cfr_bframes.mp4"
        private const val SOURCE_DIRECTORY = "source"
        private const val IMAGE_DIRECTORY = "images"
        private const val TIMEOUT_MILLIS = 30_000L

        /** Tiempo máximo del guardado real: render PNG + almacén + transacción de Room. */
        private const val SAVE_TIMEOUT_MILLIS = 30_000L

        /** Puntos de un tercer ángulo completo sobre un frame de 320x240. */
        val THIRD_ANGLE = listOf(
            AnglePoint(60.0, 20.0),
            AnglePoint(140.0, 40.0),
            AnglePoint(100.0, 120.0),
        )

        /** Puntos de dos ángulos completos sobre un frame de 320x240. */
        val FIRST_ANGLE = listOf(
            AnglePoint(20.0, 20.0),
            AnglePoint(80.0, 20.0),
            AnglePoint(80.0, 80.0),
        )
        val SECOND_ANGLE = listOf(
            AnglePoint(40.0, 40.0),
            AnglePoint(100.0, 60.0),
            AnglePoint(60.0, 100.0),
        )
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    /** Copia privada del fixture: es la única fuente de vídeo que este arnés abre y borra. */
    val sourceFile: File

    /** Índice derivado del índice sellado de la fuente real. */
    val videoIndex: VideoFrameIndex

    val database: JumpDatabase

    /** Raíz de imágenes aislada; el almacén real nunca la crea, la crea y la borra este arnés. */
    val imageRoot: File

    val imageStore: PrivateAngleImageStore

    val v2Repository: AngleV2SaveRepository

    val athleteId: Long

    val viewer = RecordingViewer()

    private val grants = RecordingGrants()

    private val store = RecordingAnalysisStore()

    private val indexer = StaticIndexer()

    /**
     * Renderer que recibe el coordinador real. Con `productionRenderer` delega **todo** el trabajo en
     * [AndroidAngleMultiFrameImageRenderer]; el contador solo acredita cuántas invocaciones hizo el
     * coordinador (una por fotograma guardado).
     */
    private val countingRenderer: CountingRenderer = CountingRenderer(
        if (productionRenderer) {
            AndroidAngleMultiFrameImageRenderer(
                context = context,
                metrics = AngleAnnotationMetrics(
                    densityScale = context.resources.displayMetrics.density,
                ),
                formatLabel = { _, degrees ->
                    val numberFormat = NumberFormat.getNumberInstance(
                        context.resources.configuration.locales[0],
                    ).apply {
                        minimumFractionDigits = 1
                        maximumFractionDigits = 1
                    }
                    context.getString(
                        R.string.angle_degrees_value,
                        numberFormat.format(degrees),
                    )
                },
            )
        } else {
            RefusingRenderer()
        },
    )

    private var openedSession: AngleViewerSession? = null

    /**
     * Última versión de sesión que una operación aceptada devolvió como aplicada.
     *
     * `AngleMultiFrameSession` publica su estado de forma síncrona, pero el `ViewModel` lo replica
     * en `multiFrameState` con un colector propio en `viewModelScope`
     * (`AngleEditorViewModel.kt`: `multiFrameBridgeJob = viewModelScope.launch { session.state
     * .collect { … } }`). Un test que muta la sesión desde el hilo de instrumentación y lee
     * `multiFrameState.value` justo después puede observar una réplica anterior a su propia
     * operación. Esta marca permite esperar exactamente esa versión, sin relaxation de ninguna
     * aserción: el contenido se sigue comprobando y ahora se comprueba sobre un estado que ya
     * incluye la operación aceptada (o el test falla por timeout con el último estado observado).
     */
    private var lastAppliedVersion = Long.MIN_VALUE

    private val frozenSourceOpener = AngleFrozenSourceOpener { uri, cancellationCheck ->
        FrozenFrameSources.openFromUri(
            context = context,
            uri = Uri.parse(uri),
            cancellationCheck = cancellationCheck,
        )
    }

    val viewModel: AngleEditorViewModel

    val request: AngleEditorRequest

    init {
        root.mkdirs()
        val sourceDirectory = File(root, SOURCE_DIRECTORY).apply { mkdirs() }
        imageRoot = File(root, IMAGE_DIRECTORY).apply { mkdirs() }
        sourceFile = File(sourceDirectory, "fixture.mp4")
        instrumentation.context.assets.open(FIXTURE_ASSET).use { input ->
            FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
        }

        // Sonda del índice sellado: de aquí salen los PTS canónicos que el visor falso confirmará.
        videoIndex = when (val probe = FrozenFrameSources.openFromUri(context, Uri.fromFile(sourceFile))) {
            is FrozenFrameSourceOpen.Opened -> {
                val session: FrozenFrameSourceSession = probe.session
                try {
                    val index = session.index
                    check(index.frameCount >= 2) {
                        "El fixture debe contener al menos 2 frames; tenía ${index.frameCount}."
                    }
                    VideoFrameIndex(
                        frameTimesUs = LongArray(index.frameCount) { index.ptsAt(it) },
                        durationUs = index.durationUs,
                        width = index.encodedWidth,
                        height = index.encodedHeight,
                        rotationDegrees = index.declaredRotationDegrees,
                        mimeType = "video/avc",
                    )
                } finally {
                    session.close()
                }
            }

            is FrozenFrameSourceOpen.Unavailable ->
                throw AssertionError(
                    "El fixture del arnés no se pudo sellar: " +
                        "${probe.failure} ${probe.detail}",
                )
        }

        database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        athleteId = runBlocking {
            database.athleteDao().insert(AthleteEntity(displayName = "Atleta multi-fotograma"))
        }
        imageStore = PrivateAngleImageStore(
            rootDirectory = imageRoot,
            fileIo = AndroidAngleImageFileIo(),
        )
        v2Repository = AngleV2SaveRepository(
            dao = database.angleAnalysisV2Dao(),
            imageStore = imageStore,
            coordinationMutex = Mutex(),
        )
        request = AngleEditorRequest(
            uri = Uri.fromFile(sourceFile).toString(),
            athleteId = athleteId,
            sessionKey = "angle-multi-frame-flow-${UUID.randomUUID()}",
            initialFrameIndex = 0,
        )
        viewModel = AngleEditorViewModel(
            store = store,
            grants = grants,
            requestReconcile = {},
            indexer = indexer,
            defaults = AngleEditorDefaults(namePrefix = "Ángulo", colorArgb = 0xFFE53935.toInt()),
            createViewer = { session, _ ->
                openedSession = session
                viewer
            },
            frozenSourceOpener = frozenSourceOpener,
            multiFrameSaveCoordinator = AngleMultiFrameSaveCoordinator(
                repository = v2Repository,
                renderer = countingRenderer,
            ),
            // Sin dueño de aplicación: el guardado, si algún slice futuro lo pide, corre en el
            // viewModelScope de esta instancia de test y su resultado se publica aquí.
            multiFrameSaveOwnership = null,
        )
    }

    // --- apertura y captura ---------------------------------------------------------------------

    /** Abre el vídeo y exige el estado listo del editor (no un fallo silencioso). */
    fun openVideo() {
        val state = runBlocking { viewModel.openInternal(request) }
        check(state is AngleVideoState.Ready) { "El editor no abrió el vídeo: $state" }
        check(openedSession?.frameIndex === videoIndex) {
            "El índice compartido con el transporte no es el derivado del fixture."
        }
    }

    /** Abre la sesión multi-fotograma real y deja el estado `Ready` observable. */
    fun openMultiFrameSession(): AngleMultiFrameSessionState {
        val state = runBlocking { viewModel.openMultiFrameSessionInternal() }
        check(state is AngleMultiFrameState.Ready) { "La sesión no abrió: $state" }
        return state.state
    }

    /** Publica la salida del visor como un frame confirmado en pausa con su PTS sellado. */
    fun pauseViewerAt(frameIndex: Int) {
        viewer.display.value = FrameDisplayState(
            requestedIndex = frameIndex,
            renderedIndex = frameIndex,
            renderedPtsUs = videoIndex.timeOf(frameIndex),
            mode = ViewerTransportMode.PAUSED,
        )
    }

    /**
     * Captura [frameIndex] con la API real del `ViewModel` y espera a que la imagen congelada esté
     * adoptada. Un rechazo o un fallo de captura se propaga como fallo del test con su motivo.
     */
    fun captureFrame(frameIndex: Int): AngleFrameId {
        val framesBefore = sessionState().frames.size
        pauseViewerAt(frameIndex)
        applied(viewModel.captureMultiFrame(), "captura del frame $frameIndex")
        val state = awaitSession("captura del frame $frameIndex") { current ->
            val item = current.frames.firstOrNull { it.anchor.frameIndex == frameIndex }
            item != null && (item.canEdit || item.captureFailure != null)
        }
        val item = state.frames.first { it.anchor.frameIndex == frameIndex }
        item.captureFailure?.let { failure ->
            throw AssertionError(
                "La captura del frame $frameIndex se rechazó: ${failure.reason} ${failure.detail}",
            )
        }
        check(item.canEdit) { "El frame $frameIndex no tiene imagen editable: ${item.capture}." }
        check(state.frames.size == framesBefore + 1) {
            "La captura debía añadir un frame; antes $framesBefore, ahora ${state.frames.size}."
        }
        return item.frameId
    }

    // --- edición --------------------------------------------------------------------------------

    /** Selecciona [frameId] y coloca una terna completa A → B → C; devuelve el ángulo creado. */
    fun markAngle(frameId: AngleFrameId, points: List<AnglePoint> = FIRST_ANGLE): AngleId {
        applied(viewModel.selectMultiFrame(frameId), "selección del frame $frameId")
        val before = frame(frameId).editor.angles.size
        points.forEachIndexed { index, point ->
            applied(
                viewModel.placeMultiFramePoint(frameId, point),
                "punto ${index + 1} del frame $frameId",
            )
        }
        val angles = frame(frameId).editor.angles
        check(angles.size == before + 1) {
            "Se esperaba un ángulo nuevo en $frameId; había $before y hay ${angles.size}."
        }
        return angles.last().id
    }

    /** Renombra un ángulo con la API real y exige que el estado lo refleje. */
    fun renameAngle(frameId: AngleFrameId, angleId: AngleId, name: String) {
        applied(viewModel.renameMultiFrameAngle(frameId, angleId, name), "renombrado de $angleId")
        check(frame(frameId).editor.angles.first { it.id == angleId }.name == name) {
            "El nombre del ángulo no se aplicó."
        }
    }

    /** Oculta o vuelve a mostrar un ángulo con la API real. */
    fun setAngleVisible(frameId: AngleFrameId, angleId: AngleId, visible: Boolean) {
        applied(viewModel.setMultiFrameAngleVisible(frameId, angleId, visible), "visibilidad de $angleId")
        check(frame(frameId).editor.angles.first { it.id == angleId }.visible == visible) {
            "La visibilidad del ángulo no se aplicó."
        }
    }

    /** Mueve una marca de un ángulo con la API real (coordenadas dentro del frame 320x240). */
    fun moveMarker(
        frameId: AngleFrameId,
        angleId: AngleId,
        marker: AngleMarker,
        point: AnglePoint,
    ) {
        applied(viewModel.moveMultiFrameMarker(frameId, angleId, marker, point), "movimiento de $marker")
    }

    /** Descarta los puntos A/B pendientes de [frameId] (sin tocar ángulos ni imagen). */
    fun cancelDraft(frameId: AngleFrameId) {
        applied(viewModel.cancelMultiFrameDraft(frameId), "descarte del borrador de $frameId")
    }

    fun frame(frameId: AngleFrameId): AngleMultiFrameItem =
        sessionState().frames.firstOrNull { it.frameId == frameId }
            ?: throw AssertionError("El frame $frameId no está en la sesión.")

    /**
     * Estado observable actual de la sesión multi-fotograma, exigiendo que esté listo y que la
     * réplica publicada por el `ViewModel` incluya la última operación aceptada.
     */
    fun sessionState(): AngleMultiFrameSessionState {
        val expectedVersion = lastAppliedVersion
        val current = viewModel.multiFrameState.value
        if (expectedVersion == Long.MIN_VALUE) {
            check(current is AngleMultiFrameState.Ready) { "La sesión no está lista: $current" }
            return current.state
        }
        if (current is AngleMultiFrameState.Ready && current.state.version >= expectedVersion) {
            return current.state
        }
        return awaitSession("publicación de la versión $expectedVersion") { it.version >= expectedVersion }
    }

    /** Estado de guardado publicado por el `ViewModel` en este instante. */
    fun saveState(): AngleMultiFrameSaveState = viewModel.multiFrameSaveState.value

    /**
     * Espera a que el guardado publicado deje de estar `Idle`/`Saving` y devuelve el resultado real
     * (`Saved`, `AlreadySaved` o `Failed`). Un `Failed` se devuelve tal cual: **nunca** se convierte
     * en éxito. Si no termina dentro de [timeoutMillis], falla con el último estado observado.
     */
    fun awaitSaveSettled(timeoutMillis: Long = SAVE_TIMEOUT_MILLIS): AngleMultiFrameSaveState =
        viewModel.multiFrameSaveState.awaitValueBlocking(
            what = "el resultado del guardado",
            timeoutMillis = timeoutMillis,
        ) { current ->
            current is AngleMultiFrameSaveState.Saved ||
                current is AngleMultiFrameSaveState.AlreadySaved ||
                current is AngleMultiFrameSaveState.Failed
        }

    /**
     * Invocaciones reales al renderer (una por fotograma guardado). Con el renderer de producción
     * acredita que el coordinador pasó por él; el PNG lo produce siempre el renderer real.
     */
    val renderCallCount: Int get() = countingRenderer.renderCalls

    /**
     * Pide prestado el bitmap congelado **real** de [frameId] (mismo objeto inmutable que retiene la
     * sesión) y lo cierra siempre al salir, también si [block] falla.
     */
    fun <T> withFrozenBitmap(frameId: AngleFrameId, block: (Bitmap) -> T): T {
        val lease = when (val outcome = viewModel.acquireMultiFrameBitmap(frameId)) {
            is AngleMultiFrameBitmapResult.Granted -> outcome.lease

            is AngleMultiFrameBitmapResult.Rejected -> throw AssertionError(
                "No se pudo obtener el bitmap congelado de $frameId: ${outcome.rejection}",
            )
        }
        return try {
            block(lease.bitmap)
        } finally {
            lease.close()
        }
    }

    /**
     * Acciones reales de la pantalla, cableadas al `ViewModel`. La de guardado se entrega desde el
     * test porque el gate de producto está apagado: el test pasa como acción la entrada pública del
     * `ViewModel` (`viewModel::saveMultiFrame`), nunca un doble que evite la escritura real.
     */
    fun editorActions(onSaveMultiFrame: () -> Unit): AngleMultiFrameEditorActions =
        AngleMultiFrameEditorActions(
            onOpen = { viewModel.openMultiFrameSession() },
            onCapture = { viewModel.captureMultiFrame() },
            onRetryCapture = { viewModel.retryMultiFrameCapture(it) },
            onCancelCapture = { viewModel.cancelMultiFrameCapture(it) },
            onSelectFrame = { viewModel.selectMultiFrame(it) },
            onRemoveFrame = { frameId, confirmation ->
                viewModel.removeMultiFrame(frameId, confirmation)
            },
            onAcquireBitmap = { viewModel.acquireMultiFrameBitmap(it) },
            onPlacePoint = { frameId, point ->
                viewModel.placeMultiFramePoint(frameId, point)
            },
            onMoveMarker = { frameId, angleId, marker, point ->
                viewModel.moveMultiFrameMarker(frameId, angleId, marker, point)
            },
            onMoveDraftMarker = { frameId, marker, point ->
                viewModel.moveMultiFrameDraftMarker(frameId, marker, point)
            },
            onCancelDraft = { viewModel.cancelMultiFrameDraft(it) },
            onSelectAngle = { frameId, angleId ->
                viewModel.selectMultiFrameAngle(frameId, angleId)
            },
            onSetAngleVisible = { frameId, angleId, visible ->
                viewModel.setMultiFrameAngleVisible(frameId, angleId, visible)
            },
            onRenameAngle = { frameId, angleId, name ->
                viewModel.renameMultiFrameAngle(frameId, angleId, name)
            },
            onSetAngleColor = { frameId, angleId, colorArgb ->
                viewModel.setMultiFrameAngleColor(frameId, angleId, colorArgb)
            },
            onDeleteAngle = { frameId, angleId ->
                viewModel.deleteMultiFrameAngle(frameId, angleId)
            },
            requiresRemovalConfirmation = { viewModel.multiFrameRequiresConfirmation(it) },
            onSaveMultiFrame = onSaveMultiFrame,
        )

    /** Filas de raíces V2 en la base **en memoria** (nunca la del usuario). */
    fun v2RootCount(): Long = count("angle_analysis_v2_roots")

    /** Filas de fotogramas V2 en la base **en memoria** (nunca la del usuario). */
    fun v2FrameCount(): Long = count("angle_analysis_v2_frames")

    /** Filas de marcas V2 en la base **en memoria** (nunca la del usuario). */
    fun v2MarkCount(): Long = count("angle_analysis_v2_marks")

    /** Ficheros publicados bajo el raíz de imágenes aislado (debe seguir vacío sin guardado). */
    fun publishedImageFileCount(): Int =
        imageRoot.walkTopDown().count { it.isFile }

    private fun count(table: String): Long =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun applied(result: AngleMultiFrameResult, what: String): AngleMultiFrameSessionState =
        when (result) {
            is AngleMultiFrameResult.Applied -> result.state.also { state ->
                if (state.version > lastAppliedVersion) lastAppliedVersion = state.version
            }

            is AngleMultiFrameResult.Rejected ->
                throw AssertionError("La operación «$what» se rechazó: ${result.rejection}")
        }

    /**
     * Espera (hilo de instrumentación, nunca el principal) a que el estado publicado cumpla
     * [predicate]. Observa las emisiones reales del `StateFlow` en vez de sondear; los fallos del
     * arnés son AssertionError con el último estado observado, no timeouts genéricos sin contexto.
     */
    private fun awaitSession(
        what: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        predicate: (AngleMultiFrameSessionState) -> Boolean,
    ): AngleMultiFrameSessionState {
        val published = viewModel.multiFrameState.awaitValueBlocking(
            what = what,
            timeoutMillis = timeoutMillis,
        ) { current -> current is AngleMultiFrameState.Ready && predicate(current.state) }
        val ready = published as? AngleMultiFrameState.Ready
            ?: throw AssertionError("Timeout esperando «$what»; último estado: $published")
        return ready.state
    }

    override fun close() {
        viewModel.close()
        database.close()
        root.deleteRecursively()
    }

    // --- dobles de las costuras no-Android ------------------------------------------------------

    /** `AngleAnalysisStore` que no persiste nada: este slice no ejerce el guardado mono-fotograma. */
    private class RecordingAnalysisStore : AngleAnalysisStore {

        var saveNewCalls = 0
        var updateCalls = 0
        var snapshotCalls = 0

        override suspend fun saveNew(draft: AngleAnalysisDraft): Long {
            saveNewCalls += 1
            throw AssertionError("El flujo multi-fotograma no debe guardar el análisis mono-fotograma.")
        }

        override suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult {
            updateCalls += 1
            throw AssertionError("El flujo multi-fotograma no debe actualizar el análisis mono-fotograma.")
        }

        override suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? {
            snapshotCalls += 1
            throw AssertionError("El flujo multi-fotograma no debe leer análisis persistidos.")
        }
    }

    /** Grants en memoria: registra propietario y permiso sin tocar `ContentResolver`. */
    private class RecordingGrants : AngleVideoGrants {

        val registeredOwners = mutableListOf<Set<String>>()
        val takenGrants = mutableListOf<String>()
        var closedLeases = 0

        override fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease {
            registeredOwners += uris
            return VideoUriGrantReconciler.Lease { closedLeases += 1 }
        }

        override suspend fun takeReadGrant(uri: String) {
            takenGrants += uri
        }
    }

    /** Indexer que devuelve el índice real del fixture sin volver a decodificar el vídeo. */
    private inner class StaticIndexer : AngleVideoIndexer {

        var indexCalls = 0

        override suspend fun index(uri: String): VideoFrameIndex {
            indexCalls += 1
            check(uri == request.uri) { "El indexer recibió una URI inesperada: $uri" }
            return videoIndex
        }
    }

    /**
     * Envoltorio de **paso directo** del renderer: cuenta invocaciones y devuelve sin tocar el
     * resultado del delegado. No sustituye el render: el PNG lo produce siempre el delegado real.
     */
    private class CountingRenderer(
        private val delegate: AngleMultiFrameImageRenderer,
    ) : AngleMultiFrameImageRenderer {

        var renderCalls = 0

        override fun render(
            image: FrozenFrameImage,
            state: AngleEditorState,
        ): AngleMultiFrameRenderedImage {
            renderCalls += 1
            return delegate.render(image, state)
        }
    }

    /** Renderer que falla si se invoca: con el gate apagado la pantalla no debe guardar. */
    private class RefusingRenderer : AngleMultiFrameImageRenderer {

        var renderCalls = 0

        override fun render(
            image: com.openjump.app.video.angles.FrozenFrameImage,
            state: com.openjump.app.angles.AngleEditorState,
        ): AngleMultiFrameRenderedImage {
            renderCalls += 1
            throw AssertionError(
                "El guardado multi-fotograma no debe renderizar con el gate de producto apagado.",
            )
        }
    }

    /** Visor sin ExoPlayer: publica el estado que el conductor del test decide. */
    internal class RecordingViewer : AngleViewerViewer {

        val display = MutableStateFlow(FrameDisplayState())
        override val player: StateFlow<ExoPlayer?> = MutableStateFlow<ExoPlayer?>(null)
        override val displayState: StateFlow<FrameDisplayState> = display
        override val loading: StateFlow<Boolean> = MutableStateFlow(false)
        val errorState = MutableStateFlow<String?>(null)
        override val error: StateFlow<String?> = errorState

        var starts = 0
        var collections = 0
        var releases = 0

        override fun start() {
            starts += 1
        }

        override fun startSessionCollection() {
            collections += 1
        }

        override fun seekToFrame(frameIndex: Int) {
            display.value = display.value.request(frameIndex, ViewerTransportMode.SEEKING)
        }

        override fun reopenFrame(frameIndex: Int) {
            val current = display.value
            if (current.canMark && current.requestedIndex == frameIndex) return
            display.value = current.request(frameIndex, ViewerTransportMode.SEEKING)
        }

        override fun refreshCurrentFrame() = Unit

        override fun stepFrames(delta: Int) = Unit

        override fun togglePlayback() = Unit

        override fun pausePlayback() = Unit

        override fun beginScrubbing() = Unit

        override fun scrubToFrame(frameIndex: Int) = Unit

        override fun endScrubbing() = Unit

        override fun cancelPendingScrubRelease() = Unit

        override fun release() {
            releases += 1
        }
    }
}
