package com.openjump.app.ui.angles

import androidx.media3.exoplayer.ExoPlayer
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.AngleAnalysisDraft
import com.openjump.app.data.AngleAnalysisEntity
import com.openjump.app.data.AngleAnalysisSnapshot
import com.openjump.app.data.AngleMarkDraft
import com.openjump.app.data.AngleMarkEntity
import com.openjump.app.data.AngleUpdateDraft
import com.openjump.app.data.AngleUpdateResult
import com.openjump.app.data.NEW_ANGLE_MARK_ID
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.video.PersistedVideoGrantStore
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoUriGrantReconciler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM de los dos bloqueos de la segunda revisión de la fase de ángulos.
 *
 * **P1 — conflicto de actualización (CAS).** Dos editores abren la misma revisión `r`; el primero
 * guarda `r+1` y el segundo, que sigue editando sobre `r`, recibe `Conflict`. El conflicto es
 * correcto (no hubo escritura y no se sobrescribe nada), pero antes se quedaba sin salida: ni el
 * estado publicado ni la pantalla ofrecían conservar el borrador. Ahora el conflicto publica además
 * [AngleSaveRecovery.Conflict], la revisión ajena **no** se adopta (el reintento normal reenvía la
 * misma `expectedRevision`) y la copia explícita guarda la edición del segundo editor como análisis
 * nuevo, de un solo uso por intención.
 *
 * **P2 — lease del vídeo.** Guardar ya no libera el `Lease` de la URI: el editor sigue vivo
 * mostrando ese vídeo, y si la raíz guardada se borra en otra pantalla, el reconciliador no puede
 * revocar el último grant mientras el editor exista. Se valida con el
 * [VideoUriGrantReconciler] **real** sobre una frontera de grants falsa y un almacén tipo Room
 * compartido, que es el ciclo completo: registro → toma → guardado → borrado ajeno → reconciliación
 * → copia de recuperación → cierre → reconciliación.
 *
 * Los fakes son deterministas: el almacén reproduce la idempotencia por `sessionKey` y el CAS por
 * `revision` del repositorio real, y el reconciliador consulta la misma foto que Room
 * (`videoUri` de las raíces guardadas) sin `AppSession` (el editor no registra URIs allí).
 */
class AngleEditorConflictRecoveryTest {

    private val request = AngleEditorRequest(
        uri = VIDEO_URI,
        athleteId = ATHLETE_ID,
        sessionKey = SESSION_KEY,
        initialFrameIndex = 2,
    )

    // --- P1: la pantalla ofrece la copia también ante un conflicto de CAS -------------------------

    @Test
    fun `un conflicto de CAS habilita la copia explicita en la pantalla`() {
        assertTrue(
            "sin salida el borrador del segundo editor se perdería",
            angleSaveAsNewOffered(AngleSaveRecovery.Conflict(analysisId = 7L, storedRevision = 3)),
        )
        assertFalse(
            "sin recuperación pendiente la copia sería un duplicado no solicitado",
            angleSaveAsNewOffered(AngleSaveRecovery.None),
        )
    }

    // --- P1: dos editores sobre la misma revisión ------------------------------------------------

    @Test
    fun `dos editores en conflicto no se pisan y el segundo conserva su edicion como copia`() = runBlocking {
        val store = SharedAngleStore()
        val first = Editor(store = store)
        val second = Editor(store = store)

        first.viewModel.openInternal(request.copy(sessionKey = "sesion-primero"))
        first.pauseAt(frameIndex = 2)
        first.placeAngle()
        assertEquals(AngleSaveState.Saved(analysisId = 1L, revision = 0), first.viewModel.saveInternal())

        // El segundo editor abre el MISMO análisis cuando está en la revisión 0.
        second.viewModel.openInternal(request.copy(sessionKey = "sesion-segundo"))
        second.pauseAt(frameIndex = 2)
        assertEquals(AngleReopenResult.Reopened, second.viewModel.reopenInternal(1L))
        assertEquals(0, store.revisionOf(1L))

        // El primero guarda r+1.
        assertEquals(AngleEditorActionResult.Applied, first.viewModel.rename(AngleId(1), "Cadera primera"))
        assertEquals(AngleSaveState.Saved(analysisId = 1L, revision = 1), first.viewModel.saveInternal())

        // El segundo edita y su CAS falla: no se sobrescribe ni se adopta la revisión ajena.
        assertEquals(AngleEditorActionResult.Applied, second.viewModel.rename(AngleId(1), "Rodilla segunda"))
        val conflict = second.viewModel.saveInternal()
        assertEquals(AngleSaveState.Conflict(analysisId = 1L, storedRevision = 1), conflict)
        assertEquals(
            "el conflicto publica la recuperación que la pantalla necesita",
            AngleSaveRecovery.Conflict(analysisId = 1L, storedRevision = 1),
            second.viewModel.saveRecovery.value,
        )
        assertTrue(angleSaveAsNewOffered(second.viewModel.saveRecovery.value))
        assertEquals("la fila del primer editor no se pisa", listOf("Cadera primera"), store.markNames(1L))
        assertEquals("la revisión ajena no se adopta", 1, store.revisionOf(1L))
        assertEquals(
            "la edición del segundo editor sigue viva en pantalla",
            listOf("Rodilla segunda"),
            second.viewModel.editor.value!!.angles.map { it.name },
        )

        // Reintento normal: misma expectedRevision (0), otra vez conflicto y ninguna escritura nueva
        // sobre la fila de 1 (la revisión ajena no se adoptó).
        assertEquals(
            AngleSaveState.Conflict(analysisId = 1L, storedRevision = 1),
            second.viewModel.saveInternal(),
        )
        assertEquals(
            "todos los intentos (primero y los dos del segundo) reenvían la revisión de su edición, " +
                "nunca la del otro editor",
            listOf(0, 0, 0),
            store.updatesFor(1L).map { it.expectedRevision },
        )
        assertEquals(listOf("Cadera primera"), store.markNames(1L))

        // Salida sin pérdida: copia con clave derivada, edición del segundo intacta.
        assertEquals(AngleSaveState.Saved(analysisId = 2L, revision = 0), second.viewModel.saveAsNewInternal())
        assertEquals(2L, second.viewModel.savedAnalysisId)
        assertEquals(listOf("Rodilla segunda"), store.markNames(2L))
        assertEquals(
            "la copia conserva el contenido del primero sin tocarlo",
            listOf("Cadera primera"),
            store.markNames(1L),
        )
        assertEquals(1, store.revisionOf(1L))
        assertEquals(
            "la copia usa una clave derivada de la sesión del segundo editor",
            recoverySessionKey("sesion-segundo", 0),
            store.sessionKeyOf(2L),
        )
        assertEquals(
            "la clave del análisis en conflicto no se reutiliza ni se recrea",
            "sesion-primero",
            store.sessionKeyOf(1L),
        )
        assertEquals(
            "resuelta la recuperación, la pantalla deja de ofrecer la copia",
            AngleSaveRecovery.None,
            second.viewModel.saveRecovery.value,
        )

        // Doble pulsación: la copia ya se adoptó, así que no se crea otra raíz.
        val obsolete = second.viewModel.saveAsNewInternal()
        assertTrue("una intención obsoleta no se informa guardada: $obsolete", obsolete is AngleSaveState.Failed)
        assertEquals("no se crea una segunda copia", 2, store.analysisCount)
        assertEquals(listOf("Rodilla segunda"), store.markNames(2L))
    }

    // --- P2: el lease sobrevive a la vida del editor ---------------------------------------------

    @Test
    fun `el reconciliador no revoca el video de un editor vivo aunque su raiz se borre`() = runBlocking {
        val store = SharedAngleStore()
        val grantStore = FakeGrantStore()
        val reconciler = reconciler(grantStore, store)
        val editor = Editor(store = store, grants = ReconcilerAngleVideoGrants(reconciler))

        editor.viewModel.openInternal(request)
        assertEquals("el editor toma el permiso del vídeo al abrir", setOf(VIDEO_URI), grantStore.persisted)
        editor.pauseAt(frameIndex = 2)
        editor.placeAngle()
        // Borrador A/B sin completar: la fila se adopta pero el editor NO se cierra (Failed).
        editor.placeDraftPoints()

        val adopted = editor.viewModel.saveInternal()
        assertTrue("un borrador pendiente no puede informarse Saved: $adopted", adopted is AngleSaveState.Failed)
        assertEquals(1L, editor.viewModel.savedAnalysisId)
        assertEquals("la fila guardada es la referencia durable", setOf(VIDEO_URI), store.savedVideoUris())

        // Otra pantalla borra la raíz: la URI deja de estar referenciada en Room.
        store.deleteAnalysis(1L)
        assertTrue(store.savedVideoUris().isEmpty())
        reconciler.reconcile()

        assertEquals(
            "el reconciliador no puede revocar el vídeo que el editor sigue mostrando",
            0,
            grantStore.releases,
        )
        assertEquals(setOf(VIDEO_URI), grantStore.persisted)

        // El guardado normal publica Deleted y la recuperación: la copia es la salida.
        assertEquals(AngleSaveState.Deleted, editor.viewModel.saveInternal())
        assertEquals(
            AngleSaveRecovery.Deleted(analysisId = 1L),
            editor.viewModel.saveRecovery.value,
        )

        // La copia de recuperación se guarda con el vídeo aún legible (grant conservado).
        val copied = editor.viewModel.saveAsNewInternal()
        assertTrue("el borrador pendiente mantiene el editor abierto: $copied", copied is AngleSaveState.Failed)
        assertEquals(2L, editor.viewModel.savedAnalysisId)
        assertEquals(setOf(VIDEO_URI), store.savedVideoUris())
        assertEquals(setOf(VIDEO_URI), grantStore.persisted)
        assertEquals(0, grantStore.releases)
        assertEquals(
            "la copia no pierde el vídeo: el grant nunca se revocó mientras el editor vivió",
            AngleSaveRecovery.None,
            editor.viewModel.saveRecovery.value,
        )

        // Al cerrar, la copia persistida mantiene el permiso: la referencia durable ya es la copia.
        editor.viewModel.close()
        editor.viewModel.close()
        assertEquals("solo el cierre pide reconciliación", 1, editor.reconciles)
        reconciler.reconcile()
        assertEquals(0, grantStore.releases)
        assertEquals(setOf(VIDEO_URI), grantStore.persisted)
    }

    @Test
    fun `cerrar sin raiz persistida libera el grant una sola vez y no deja fugas`() = runBlocking {
        val store = SharedAngleStore()
        val grantStore = FakeGrantStore()
        val reconciler = reconciler(grantStore, store)
        val editor = Editor(store = store, grants = ReconcilerAngleVideoGrants(reconciler))

        editor.viewModel.openInternal(request)
        assertEquals(setOf(VIDEO_URI), grantStore.persisted)

        editor.viewModel.close()
        editor.viewModel.close()

        assertEquals("el cierre libera el lease una sola vez", 1, editor.reconciles)
        reconciler.reconcile()

        assertEquals("sin raíz guardada ni editor vivo el permiso se revoca", 1, grantStore.releases)
        assertTrue(grantStore.persisted.isEmpty())
        assertTrue(store.savedVideoUris().isEmpty())
    }

    // --- utilidades ------------------------------------------------------------------------------

    private fun reconciler(
        grantStore: FakeGrantStore,
        store: SharedAngleStore,
    ): VideoUriGrantReconciler = VideoUriGrantReconciler(
        grants = grantStore,
        // Misma foto que Room: la URI de cada raíz de `angle_analyses`.
        savedReferences = { store.savedVideoUris() },
        // El editor de ángulos no publica su URI en AppSession: aquí no hay otra referencia viva.
        liveReferences = { emptySet() },
        diagnostic = {},
    )

    /**
     * Editor de ángulos con transporte falso, sobre el almacén compartido. El visor solo confirma
     * cuando la prueba publica un frame renderizado en pausa con el PTS canónico del índice.
     */
    private class Editor(
        store: AngleAnalysisStore,
        grants: AngleVideoGrants = NoopGrants(),
    ) {
        val viewer = FakeViewer()
        var reconciles = 0
        val viewModel = AngleEditorViewModel(
            store = store,
            grants = grants,
            requestReconcile = { reconciles += 1 },
            indexer = FakeIndexer(),
            defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = COLOR_ARGB),
            createViewer = { _, _ -> viewer },
        )

        fun pauseAt(frameIndex: Int) {
            viewer.display.value = FrameDisplayState(
                requestedIndex = frameIndex,
                renderedIndex = frameIndex,
                renderedPtsUs = VIDEO_INDEX.timeOf(frameIndex),
                mode = ViewerTransportMode.PAUSED,
            )
        }

        fun placeAngle() {
            val confirmation = requireNotNull(viewModel.currentConfirmation())
            listOf(
                AnglePoint(10.0, 10.0),
                AnglePoint(10.0, 50.0),
                AnglePoint(50.0, 10.0),
            ).forEach { point ->
                assertEquals(AngleEditorActionResult.Applied, viewModel.placePoint(point, confirmation))
            }
        }

        /** Borrador A/B sin completar (dos de los tres puntos). */
        fun placeDraftPoints() {
            val confirmation = requireNotNull(viewModel.currentConfirmation())
            listOf(AnglePoint(20.0, 20.0), AnglePoint(20.0, 40.0)).forEach { point ->
                assertEquals(AngleEditorActionResult.Applied, viewModel.placePoint(point, confirmation))
            }
            assertEquals(2, requireNotNull(viewModel.editor.value).draft.points.size)
        }
    }

    /**
     * Almacén en memoria con la misma semántica observable que [com.openjump.app.data.AngleRepository]
     * para este slice: `saveNew` idempotente por `sessionKey` (no actualiza), `update` con CAS por
     * `revision` y snapshot con las marcas ordenadas. Dos editores comparten la MISMA instancia, igual
     * que dos pantallas comparten Room.
     */
    private class SharedAngleStore : AngleAnalysisStore {

        private val analyses = linkedMapOf<Long, AngleAnalysisEntity>()
        private val marksByAnalysis = mutableMapOf<Long, MutableList<AngleMarkEntity>>()
        private val updates = mutableListOf<AngleUpdateDraft>()
        private var nextAnalysisId = 1L
        private var nextMarkId = 100L

        val analysisCount: Int get() = analyses.size

        override suspend fun saveNew(draft: AngleAnalysisDraft): Long {
            analyses.values.firstOrNull { it.sessionKey == draft.sessionKey }?.let { return it.id }
            val id = nextAnalysisId++
            analyses[id] = AngleAnalysisEntity(
                id = id,
                sessionKey = draft.sessionKey,
                athleteId = draft.athleteId,
                createdAt = 1_000L + id,
                updatedAt = 1_000L + id,
                revision = 0,
                videoUri = draft.videoUri,
                sourceIdentity = draft.sourceIdentity,
                sourceHash = draft.sourceHash,
                encodedWidth = draft.encodedWidth,
                encodedHeight = draft.encodedHeight,
                presentationWidth = draft.presentationWidth,
                presentationHeight = draft.presentationHeight,
                rotationDegrees = draft.rotationDegrees,
                frameIndex = draft.frameIndex,
                sourcePtsUs = draft.sourcePtsUs,
                geometryVersion = draft.geometryVersion,
            )
            marksByAnalysis[id] = draft.marks.map { mark -> mark.toEntity(id, nextMarkId++) }.toMutableList()
            return id
        }

        override suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult {
            updates += draft
            val current = analyses[draft.analysisId] ?: return AngleUpdateResult.NotFound
            // CAS: sin escritura si la revisión no coincide (conflicto exacto, como el repositorio).
            if (current.revision != draft.expectedRevision) {
                return AngleUpdateResult.Conflict(draft.analysisId, current.revision)
            }
            marksByAnalysis[draft.analysisId] = draft.marks.map { mark ->
                val id = if (mark.id == NEW_ANGLE_MARK_ID) nextMarkId++ else mark.id
                mark.toEntity(draft.analysisId, id)
            }.toMutableList()
            analyses[draft.analysisId] = current.copy(
                revision = current.revision + 1,
                updatedAt = current.updatedAt + 1,
            )
            return AngleUpdateResult.Updated(requireNotNull(snapshot(draft.analysisId)))
        }

        override suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? {
            val analysis = analyses[analysisId] ?: return null
            return AngleAnalysisSnapshot(
                analysis = analysis,
                marks = marksByAnalysis[analysisId].orEmpty().toList(),
            )
        }

        /** Borrado desde otra pantalla: la URI deja de estar referenciada por Room. */
        fun deleteAnalysis(analysisId: Long) {
            analyses.remove(analysisId)
            marksByAnalysis.remove(analysisId)
        }

        /** Foto equivalente a `JumpDao.savedVideoUris()` para las raíces de ángulos. */
        fun savedVideoUris(): Set<String> = analyses.values.mapNotNullTo(linkedSetOf()) { it.videoUri }

        fun revisionOf(analysisId: Long): Int? = analyses[analysisId]?.revision

        fun sessionKeyOf(analysisId: Long): String? = analyses[analysisId]?.sessionKey

        fun markNames(analysisId: Long): List<String> =
            marksByAnalysis[analysisId].orEmpty().sortedBy { it.ordinal }.map { it.name }

        fun updatesFor(analysisId: Long): List<AngleUpdateDraft> =
            updates.filter { it.analysisId == analysisId }
    }

    /** Frontera de grants persistibles falsa: un `set` que representa `persistedUriPermissions`. */
    private class FakeGrantStore : PersistedVideoGrantStore {
        val persisted = linkedSetOf<String>()
        var releases = 0

        override fun persistedReadUris(): Set<String> = persisted.toSet()

        override fun takeReadGrant(uri: String) {
            persisted += uri
        }

        override fun releaseReadGrant(uri: String) {
            releases += 1
            persisted -= uri
        }
    }

    private class NoopGrants : AngleVideoGrants {
        override fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease =
            VideoUriGrantReconciler.Lease {}

        override suspend fun takeReadGrant(uri: String) = Unit
    }

    private class FakeIndexer : AngleVideoIndexer {
        override suspend fun index(uri: String): VideoFrameIndex = VIDEO_INDEX
    }

    private class FakeViewer : AngleViewerViewer {

        val display = MutableStateFlow(FrameDisplayState())
        override val player: StateFlow<ExoPlayer?> = MutableStateFlow<ExoPlayer?>(null)
        override val displayState: StateFlow<FrameDisplayState> = display
        override val loading: StateFlow<Boolean> = MutableStateFlow(false)
        override val error: StateFlow<String?> = MutableStateFlow<String?>(null)

        override fun start() = Unit

        override fun startSessionCollection() = Unit

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

        override fun release() = Unit
    }

    private companion object {
        const val VIDEO_URI = "content://openjump/angle-video"
        const val SESSION_KEY = "angle-session-1"
        const val ATHLETE_ID = 3L
        const val COLOR_ARGB = 0xFF35D0FF.toInt()

        val VIDEO_INDEX = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L),
            durationUs = 160_000L,
            width = 640,
            height = 360,
            rotationDegrees = 0,
            mimeType = "video/mp4",
        )
    }
}

/** Proyección del borrador a fila de `angle_marks` con un id ya asignado por el almacén falso. */
private fun AngleMarkDraft.toEntity(analysisId: Long, id: Long): AngleMarkEntity = AngleMarkEntity(
    id = id,
    analysisId = analysisId,
    ordinal = ordinal,
    name = name,
    colorArgb = colorArgb,
    visible = visible,
    pointAX = pointA.x,
    pointAY = pointA.y,
    pointBX = pointB.x,
    pointBY = pointB.y,
    pointCX = pointC.x,
    pointCY = pointC.y,
)
