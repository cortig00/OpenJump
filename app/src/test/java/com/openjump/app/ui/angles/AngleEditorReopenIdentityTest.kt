package com.openjump.app.ui.angles

import androidx.media3.exoplayer.ExoPlayer
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleFrameConfirmation
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
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoUriGrantReconciler
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del **P1 de la tercera revisión**: identidad de apertura frente a identidad persistida
 * vigente en la reapertura del editor de ángulos.
 *
 * La ruta del editor transporta el `analysisId` de apertura (`A`) y la composición lo reemite al
 * recrear la Activity porque su `remember` se reinicia (`AppNav`), mientras el ViewModel sobrevive.
 * La guarda de idempotencia comparaba solo `savedAnalysisId`, que **se mueve** a la copia (`B`) cuando
 * el usuario conserva la edición con «guardar como análisis nuevo» tras un `Conflict`/`Unverified`.
 * En ese estado (`B` adoptada y el editor abierto por un borrador A/B pendiente) la recreación volvía
 * a ejecutar `reopen(A)`, releía la raíz original y reemplazaba editor, ancla y bindings de `B` con el
 * contenido de `A`: se perdía el borrador y el siguiente guardado habría escrito sobre `A`.
 *
 * El ViewModel separa ahora las dos identidades ([AngleEditorViewModel.openingAnalysisId] frente a
 * [AngleEditorViewModel.savedAnalysisId]) y solo reintenta de verdad mientras la edición no tenga un
 * destino persistido propio. Lo que se fija aquí:
 *
 * 1. tras adoptar la copia de un conflicto (borrador A/B pendiente), la repetición de la identidad de
 *    apertura no lee Room, no muta el editor, no reasigna bindings y `savedAnalysisId` sigue siendo la
 *    copia; el guardado posterior va por CAS **sobre la copia** y `A` queda intacta;
 * 2. lo mismo tras una escritura sin verificar (borrador A pendiente);
 * 3. una reapertura **deliberada** de otro análisis sigue funcionando, y su repetición tampoco relee;
 * 4. una sesión nueva tras muerte de proceso rehidrata lo que corresponde por la ruta (su identidad de
 *    apertura), sin crear raíces de más ni tocar la copia;
 * 5. el cableado de `AppNav` mantiene la reemisión (necesaria para la muerte de proceso) y no usa la
 *    identidad persistida como entrada de navegación.
 *
 * Los fakes reproducen la semántica observable de `AngleRepository` (idempotencia por `sessionKey`,
 * CAS por `revision`, snapshot completo) y el transporte confirma frame solo cuando la prueba lo
 * publica en pausa con el PTS canónico del índice. La composición real se valida en instrumentación;
 * aquí se fija el contrato de comportamiento del ViewModel y el de la fuente de la ruta.
 */
class AngleEditorReopenIdentityTest {

    // --- P1: recreación tras adoptar la copia de un conflicto ------------------------------------

    @Test
    fun `tras adoptar la copia de un conflicto la recreacion no relee ni pisa el borrador pendiente`() = runBlocking {
        val harness = Harness()
        val openingId = harness.store.seed(sessionKey = "sesion-previa", markNames = listOf("Cadera"))
        assertEquals(1L, openingId)

        harness.open()
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(openingId))
        assertEquals("la reapertura fija la identidad de apertura", openingId, harness.viewModel.openingAnalysisId)
        assertEquals(openingId, harness.viewModel.savedAnalysisId)

        // Otro editor guarda r+1 sobre la raíz abierta: el CAS de este editor fallará.
        harness.store.updateFromAnotherEditor(openingId, name = "Cadera otra")
        assertEquals(
            AngleEditorActionResult.Applied,
            harness.viewModel.rename(AngleId(1), "Rodilla"),
        )
        assertEquals(
            AngleSaveState.Conflict(analysisId = openingId, storedRevision = 1),
            harness.viewModel.saveInternal(),
        )
        assertEquals(
            AngleSaveRecovery.Conflict(analysisId = openingId, storedRevision = 1),
            harness.viewModel.saveRecovery.value,
        )

        // Borrador A/B pendiente + copia explícita: la copia se adopta y el editor NO se cierra.
        harness.placeDraftPoints()
        val copied = harness.viewModel.saveAsNewInternal()
        assertTrue("un borrador pendiente mantiene el editor abierto: $copied", copied is AngleSaveState.Failed)
        val copyId = requireNotNull(harness.viewModel.savedAnalysisId)
        assertEquals(2L, copyId)
        assertEquals("la copia es el destino vigente", copyId, harness.viewModel.savedAnalysisId)
        assertEquals("la identidad de apertura no se mueve a la copia", openingId, harness.viewModel.openingAnalysisId)
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
        assertEquals(listOf("Rodilla"), harness.viewModel.editor.value!!.angles.map { it.name })
        assertEquals(2, harness.viewModel.editor.value!!.draft.points.size)
        assertEquals(
            "el ángulo completo sigue enlazado a una fila persistida",
            setOf(ANGLE_ONE),
            harness.viewModel.editor.value!!.externallyPersistedAngleIds,
        )

        // RECREACIÓN de la Activity: el `remember` de la ruta se reinicia y reemite `open(A)`.
        val editorBefore = harness.viewModel.editor.value
        val readsBefore = harness.store.snapshotReads
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(openingId))

        assertEquals("no se relee la raíz original", readsBefore, harness.store.snapshotReads)
        assertSame("el editor vivo no se reemplaza", editorBefore, harness.viewModel.editor.value)
        assertEquals(
            "la edición sin guardar sigue viva",
            listOf("Rodilla"),
            harness.viewModel.editor.value!!.angles.map { it.name },
        )
        assertEquals(
            "el borrador A/B pendiente no se descarta",
            listOf(AnglePoint(20.0, 20.0), AnglePoint(20.0, 40.0)),
            harness.viewModel.editor.value!!.draft.points,
        )
        assertEquals("la retención del destino es la copia", copyId, harness.viewModel.savedAnalysisId)
        assertEquals(
            "el ángulo sigue enlazado a una fila persistida",
            setOf(ANGLE_ONE),
            harness.viewModel.editor.value!!.externallyPersistedAngleIds,
        )
        assertEquals("la raíz de apertura no se pisa", listOf("Cadera otra"), harness.store.markNames(openingId))
        assertEquals("la raíz de apertura no avanza de revisión", 1, harness.store.revisionOf(openingId))
        assertTrue("la reemisión no crea análisis nuevos", harness.store.analysisCount == 2)

        // El guardado posterior (borrador completado) va por CAS sobre la copia, no sobre A.
        harness.completeDraft()
        assertEquals(AngleSaveState.Saved(analysisId = copyId, revision = 1), harness.viewModel.saveInternal())
        val lastUpdate = harness.store.updateDrafts.last()
        assertEquals("el CAS apunta a la copia", copyId, lastUpdate.analysisId)
        assertEquals("la copia estaba en revisión 0", 0, lastUpdate.expectedRevision)
        assertEquals("se guardan los dos ángulos", 2, lastUpdate.marks.size)
        assertEquals(
            "el ángulo ya persistido actualiza la fila de la COPIA, no la de la raíz de apertura",
            harness.store.markIds(copyId).first(),
            lastUpdate.marks.first().id,
        )
        assertNotEquals(
            "la fila de la raíz de apertura queda intacta",
            harness.store.markIds(openingId).first(),
            lastUpdate.marks.first().id,
        )
        assertEquals(1, harness.store.revisionOf(copyId))
        assertEquals("A queda intacta", listOf("Cadera otra"), harness.store.markNames(openingId))
        assertEquals(1, harness.store.revisionOf(openingId))
        assertEquals("no se crea una tercera raíz", 2, harness.store.analysisCount)
    }

    // --- P1: recreación tras adoptar la copia de una escritura sin verificar ---------------------

    @Test
    fun `tras adoptar la copia de una escritura sin verificar la recreacion no relee ni pisa el borrador`() = runBlocking {
        val harness = Harness()
        val openingId = harness.store.seed(sessionKey = "sesion-previa", markNames = listOf("Cadera"))

        harness.open()
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(openingId))
        assertEquals(
            AngleEditorActionResult.Applied,
            harness.viewModel.rename(AngleId(1), "Rodilla"),
        )

        // La escritura se efectuó pero su foto no coincide con lo enviado: no se adopta ni se pisa.
        harness.store.forcedUpdateResult = AngleUpdateResult.Updated(
            harness.store.snapshotOf(openingId, revision = 9, names = listOf("Cadera otra")),
        )
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertEquals(
            AngleSaveRecovery.Unverified(analysisId = openingId, revision = 9),
            harness.viewModel.saveRecovery.value,
        )

        // Borrador A pendiente + copia explícita: la copia se adopta y el editor sigue abierto.
        harness.placeDraftPoint()
        val copied = harness.viewModel.saveAsNewInternal()
        assertTrue("un borrador pendiente mantiene el editor abierto: $copied", copied is AngleSaveState.Failed)
        val copyId = requireNotNull(harness.viewModel.savedAnalysisId)
        assertEquals(2L, copyId)
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
        assertEquals(1, harness.viewModel.editor.value!!.draft.points.size)

        // Recreación: la ruta reemite la identidad de apertura.
        val editorBefore = harness.viewModel.editor.value
        val readsBefore = harness.store.snapshotReads
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(openingId))

        assertEquals("no se relee la raíz original", readsBefore, harness.store.snapshotReads)
        assertSame("el editor vivo no se reemplaza", editorBefore, harness.viewModel.editor.value)
        assertEquals(
            listOf("Rodilla"),
            harness.viewModel.editor.value!!.angles.map { it.name },
        )
        assertEquals(
            "el borrador A pendiente no se descarta",
            listOf(AnglePoint(20.0, 20.0)),
            harness.viewModel.editor.value!!.draft.points,
        )
        assertEquals("la retención del destino es la copia", copyId, harness.viewModel.savedAnalysisId)

        // El usuario descarta el borrador y guarda: CAS sobre la copia.
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.cancelDraft())
        assertEquals(AngleSaveState.Saved(analysisId = copyId, revision = 1), harness.viewModel.saveInternal())
        assertEquals(copyId, harness.store.updateDrafts.last().analysisId)
        assertEquals(listOf("Rodilla"), harness.store.markNames(copyId))
        assertEquals("A nunca se sobrescribe", listOf("Cadera"), harness.store.markNames(openingId))
        assertEquals(2, harness.store.analysisCount)
    }

    // --- una navegación deliberada a otro análisis no se bloquea ---------------------------------

    @Test
    fun `una reapertura deliberada de otro analisis sigue funcionando tras adoptar la copia`() = runBlocking {
        val harness = Harness()
        val openingId = harness.store.seed(sessionKey = "sesion-previa", markNames = listOf("Cadera"))
        harness.open()
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(openingId))
        harness.store.updateFromAnotherEditor(openingId, name = "Cadera otra")
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.rename(AngleId(1), "Rodilla"))
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Conflict)
        harness.placeDraftPoints()
        assertTrue(harness.viewModel.saveAsNewInternal() is AngleSaveState.Failed)
        val copyId = requireNotNull(harness.viewModel.savedAnalysisId)
        assertEquals(2L, copyId)

        // Navegación deliberada a un análisis DISTINTO sobre el mismo vídeo.
        val otherId = harness.store.seed(sessionKey = "sesion-tercera", markNames = listOf("Tobillo"))
        assertEquals(3L, otherId)
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(otherId))
        assertEquals("el destino pasa al análisis deliberado", otherId, harness.viewModel.savedAnalysisId)
        assertEquals(listOf("Tobillo"), harness.viewModel.editor.value!!.angles.map { it.name })
        assertEquals(
            "la identidad de apertura sigue siendo la de la ruta",
            openingId,
            harness.viewModel.openingAnalysisId,
        )

        // Repetir la identidad ya servida no relee Room.
        val readsBefore = harness.store.snapshotReads
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(otherId))
        assertEquals(readsBefore, harness.store.snapshotReads)

        // Y una reemisión tardía de la identidad de apertura tampoco pisa el análisis deliberado.
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(openingId))
        assertEquals(readsBefore, harness.store.snapshotReads)
        assertEquals(otherId, harness.viewModel.savedAnalysisId)
        assertEquals(listOf("Tobillo"), harness.viewModel.editor.value!!.angles.map { it.name })
        assertEquals(listOf("Tobillo"), harness.store.markNames(otherId))
    }

    // --- muerte de proceso: la ruta vuelve a rehidratar lo que corresponde -----------------------

    @Test
    fun `una sesion nueva tras muerte de proceso rehidrata la identidad de apertura de la ruta`() = runBlocking {
        val store = RouteStore()
        val openingId = store.seed(sessionKey = "sesion-previa", markNames = listOf("Cadera"))
        val firstLife = Harness(store)
        firstLife.open()
        assertEquals(AngleReopenResult.Reopened, firstLife.viewModel.reopenInternal(openingId))

        // Muerte de proceso: ViewModel nuevo sobre la misma Room; la ruta sigue nombrando A.
        val secondLife = Harness(store)
        secondLife.open()
        assertEquals(AngleReopenResult.Reopened, secondLife.viewModel.reopenInternal(openingId))
        assertEquals("la sesión nueva fija su propia identidad de apertura", openingId, secondLife.viewModel.openingAnalysisId)
        assertEquals(openingId, secondLife.viewModel.savedAnalysisId)
        assertEquals(
            "la rehidratación trae el contenido persistido",
            listOf("Cadera"),
            secondLife.viewModel.editor.value!!.angles.map { it.name },
        )
        assertEquals("no se crea ninguna raíz", 1, store.analysisCount)
        assertTrue("la rehidratación no escribe", store.updateDrafts.isEmpty())
    }

    @Test
    fun `tras adoptar una copia, una sesion nueva rehidrata la ruta y no toca la copia`() = runBlocking {
        val store = RouteStore()
        val openingId = store.seed(sessionKey = "sesion-previa", markNames = listOf("Cadera"))
        val firstLife = Harness(store)
        firstLife.open()
        assertEquals(AngleReopenResult.Reopened, firstLife.viewModel.reopenInternal(openingId))
        store.updateFromAnotherEditor(openingId, name = "Cadera otra")
        assertEquals(AngleEditorActionResult.Applied, firstLife.viewModel.rename(AngleId(1), "Rodilla"))
        assertTrue(firstLife.viewModel.saveInternal() is AngleSaveState.Conflict)
        firstLife.placeDraftPoints()
        assertTrue(firstLife.viewModel.saveAsNewInternal() is AngleSaveState.Failed)
        val copyId = requireNotNull(firstLife.viewModel.savedAnalysisId)
        assertEquals(listOf("Rodilla"), store.markNames(copyId))

        // Muerte de proceso: el borrador no es durable y la ruta solo nombra la identidad de apertura.
        val writesBefore = store.updateDrafts.size + store.saveNewDrafts.size
        val secondLife = Harness(store)
        secondLife.open()
        assertEquals(AngleReopenResult.Reopened, secondLife.viewModel.reopenInternal(openingId))
        assertEquals(openingId, secondLife.viewModel.savedAnalysisId)
        assertEquals(listOf("Cadera otra"), secondLife.viewModel.editor.value!!.angles.map { it.name })
        assertEquals("la copia sigue intacta", listOf("Rodilla"), store.markNames(copyId))
        assertEquals("no se crea una tercera raíz", 2, store.analysisCount)
        assertEquals(
            "rehidratar no escribe nada nuevo",
            writesBefore,
            store.updateDrafts.size + store.saveNewDrafts.size,
        )
    }

    // --- contrato de la ruta (composición): reemite su identidad, no la persistida --------------

    @Test
    fun `la ruta reemite su identidad de apertura al recrear y no usa la persistida`() {
        val navSource = readSource("app/src/main/java/com/openjump/app/ui/AppNav.kt")
        val effect = navSource
            .substringAfter("LaunchedEffect(analysisId, angleVideoState, angleReopenRequested)")
            .substringBefore("LaunchedEffect(angleSaveState)")

        assertTrue(
            "la ruta pide la reapertura de SU analysisId: $effect",
            effect.contains("angleEditorViewModel.reopen(analysisId)"),
        )
        assertTrue(
            "solo se reabre un id guardado y con el vídeo listo: $effect",
            effect.contains("analysisId > 0L") && effect.contains("AngleVideoState.Ready"),
        )
        assertTrue(
            "el flag de petición debe reiniciarse al recrear (muerte de proceso rehidrata)",
            navSource.contains("var angleReopenRequested by remember { mutableStateOf(false) }"),
        )
        assertFalse(
            "el flag no puede sobrevivir a la recreación con rememberSaveable",
            navSource.contains("angleReopenRequested by rememberSaveable"),
        )
        assertFalse(
            "la identidad persistida vigente no es entrada de navegación: la separa el ViewModel",
            navSource.contains("angleEditorViewModel.savedAnalysisId"),
        )
        // La ruta de recuperación sigue existiendo y reemite ese id como identidad de apertura. La
        // inmutabilidad del detalle guardado (no reabrir el editor) la cubre su guard canónico.
        assertTrue(
            "la recuperación del análisis viaja con ese id como identidad de apertura",
            navSource.contains("return anglesEditorRoute(videoUri, athleteId, analysisId)"),
        )
    }

    // --- utilidades ------------------------------------------------------------------------------

    private class Harness(val store: RouteStore = RouteStore()) {
        val viewer = RouteViewer()
        val viewModel = AngleEditorViewModel(
            store = store,
            grants = NoopGrants(),
            requestReconcile = {},
            indexer = FixedIndexer(),
            defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = COLOR_ARGB),
            createViewer = { _, _ -> viewer },
        )

        /** Abre el vídeo de la ruta y deja el frame de apertura confirmado en pausa. */
        suspend fun open() {
            assertTrue(
                "la apertura tiene que quedar lista para confirmar el frame de apertura",
                viewModel.openInternal(REQUEST) is AngleVideoState.Ready,
            )
            pauseAt(frameIndex = REQUEST.initialFrameIndex)
        }

        fun pauseAt(frameIndex: Int) {
            viewer.display.value = FrameDisplayState(
                requestedIndex = frameIndex,
                renderedIndex = frameIndex,
                renderedPtsUs = VIDEO_INDEX.timeOf(frameIndex),
                mode = ViewerTransportMode.PAUSED,
            )
        }

        /** Punto A del borrador, como un toque interrumpido antes de B. */
        fun placeDraftPoint() {
            assertApplied(viewModel.placePoint(AnglePoint(20.0, 20.0), confirmation()))
            assertEquals(1, requireNotNull(viewModel.editor.value).draft.points.size)
        }

        /** Borrador A/B pendiente: dos de los tres puntos. */
        fun placeDraftPoints() {
            placeDraftPoint()
            assertApplied(viewModel.placePoint(AnglePoint(20.0, 40.0), confirmation()))
            assertEquals(2, requireNotNull(viewModel.editor.value).draft.points.size)
        }

        /** Cierra el borrador pendiente con el punto C: el siguiente guardado ya es persistible. */
        fun completeDraft() {
            assertApplied(viewModel.placePoint(AnglePoint(60.0, 60.0), confirmation()))
            assertEquals(0, requireNotNull(viewModel.editor.value).draft.points.size)
        }

        private fun confirmation(): AngleFrameConfirmation =
            requireNotNull(viewModel.currentConfirmation()) { "falta el frame confirmado en pausa" }

        private fun assertApplied(result: AngleEditorActionResult) {
            assertEquals(AngleEditorActionResult.Applied, result)
        }
    }

    /**
     * Almacén en memoria con la semántica observable de `AngleRepository` para este slice:
     * `saveNew` idempotente por `sessionKey` (nunca actualiza), `update` con CAS por `revision` y
     * snapshot con las marcas ordenadas. Cuenta las lecturas del ViewModel para poder afirmar que una
     * reemisión de la ruta no relee Room.
     */
    private class RouteStore : AngleAnalysisStore {

        private val analyses = linkedMapOf<Long, AngleAnalysisEntity>()
        private val marksByAnalysis = mutableMapOf<Long, MutableList<AngleMarkEntity>>()
        private var nextAnalysisId = 1L
        private var nextMarkId = 100L

        /** Lecturas de `snapshot` hechas por el ViewModel (las internas de `update` no cuentan). */
        var snapshotReads = 0
            private set
        val saveNewDrafts = mutableListOf<AngleAnalysisDraft>()
        val updateDrafts = mutableListOf<AngleUpdateDraft>()

        /** Corta la escritura y devuelve esta foto: escritura efectuada pero no verificable. */
        var forcedUpdateResult: AngleUpdateResult? = null

        val analysisCount: Int get() = analyses.size

        /** Siembra una raíz ya persistida en otra sesión (Room tiene datos previos). */
        fun seed(sessionKey: String, markNames: List<String>): Long = insert(
            AngleAnalysisDraft(
                sessionKey = sessionKey,
                athleteId = ATHLETE_ID,
                videoUri = VIDEO_URI,
                sourceIdentity = VIDEO_URI,
                encodedWidth = 640,
                encodedHeight = 360,
                presentationWidth = 640,
                presentationHeight = 360,
                rotationDegrees = 0,
                frameIndex = REQUEST.initialFrameIndex,
                sourcePtsUs = VIDEO_INDEX.timeOf(REQUEST.initialFrameIndex),
                marks = markNames.mapIndexed { ordinal, name -> markDraft(ordinal, name) },
            ),
        )

        /** Otra pantalla guarda una revisión nueva sobre [analysisId]: el CAS de este editor falla. */
        fun updateFromAnotherEditor(analysisId: Long, name: String) {
            val current = analyses.getValue(analysisId)
            marksByAnalysis[analysisId] = currentMarks(analysisId)
                .mapIndexed { ordinal, mark -> mark.copy(ordinal = ordinal, name = name) }
                .toMutableList()
            analyses[analysisId] = current.copy(revision = current.revision + 1, updatedAt = current.updatedAt + 1)
        }

        /** Foto con el contenido que la prueba decida, sin tocar la fila persistida. */
        fun snapshotOf(analysisId: Long, revision: Int, names: List<String>): AngleAnalysisSnapshot {
            val analysis = analyses.getValue(analysisId).copy(revision = revision)
            val marks = currentMarks(analysisId)
                .mapIndexed { ordinal, mark -> mark.copy(ordinal = ordinal, name = names[ordinal]) }
            return AngleAnalysisSnapshot(analysis = analysis, marks = marks)
        }

        override suspend fun saveNew(draft: AngleAnalysisDraft): Long {
            saveNewDrafts += draft
            return insert(draft)
        }

        override suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult {
            updateDrafts += draft
            forcedUpdateResult?.let { forced ->
                forcedUpdateResult = null
                return forced
            }
            val current = analyses[draft.analysisId] ?: return AngleUpdateResult.NotFound
            if (current.revision != draft.expectedRevision) {
                return AngleUpdateResult.Conflict(draft.analysisId, current.revision)
            }
            marksByAnalysis[draft.analysisId] = draft.marks.map { mark ->
                // Upsert como el repositorio: la marca existente conserva su id; las nuevas lo reciben.
                val id = if (mark.id == NEW_ANGLE_MARK_ID) nextMarkId++ else mark.id
                mark.toRow(draft.analysisId, id)
            }.toMutableList()
            analyses[draft.analysisId] =
                current.copy(revision = current.revision + 1, updatedAt = current.updatedAt + 1)
            return AngleUpdateResult.Updated(requireNotNull(read(draft.analysisId)))
        }

        override suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? {
            snapshotReads += 1
            return read(analysisId)
        }

        fun revisionOf(analysisId: Long): Int? = analyses[analysisId]?.revision

        fun markNames(analysisId: Long): List<String> =
            currentMarks(analysisId).sortedBy { it.ordinal }.map { it.name }

        fun markIds(analysisId: Long): List<Long> =
            currentMarks(analysisId).sortedBy { it.ordinal }.map { it.id }

        private fun read(analysisId: Long): AngleAnalysisSnapshot? {
            val analysis = analyses[analysisId] ?: return null
            return AngleAnalysisSnapshot(
                analysis = analysis,
                marks = currentMarks(analysisId).sortedBy { it.ordinal },
            )
        }

        private fun currentMarks(analysisId: Long): List<AngleMarkEntity> =
            marksByAnalysis[analysisId].orEmpty().toList()

        private fun insert(draft: AngleAnalysisDraft): Long {
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
            // `saveNew` inserta filas nuevas: los ids de marca los asigna el almacén, como Room.
            marksByAnalysis[id] = draft.marks.map { mark -> mark.toRow(id, nextMarkId++) }.toMutableList()
            return id
        }
    }

    private class NoopGrants : AngleVideoGrants {
        override fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease =
            VideoUriGrantReconciler.Lease {}

        override suspend fun takeReadGrant(uri: String) = Unit
    }

    private class FixedIndexer : AngleVideoIndexer {
        override suspend fun index(uri: String): VideoFrameIndex = VIDEO_INDEX
    }

    private class RouteViewer : AngleViewerViewer {

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
        const val SESSION_KEY = "angle-session-ruta"
        const val ATHLETE_ID = 3L
        const val COLOR_ARGB = 0xFF35D0FF.toInt()

        /** Id local del único ángulo rehidratado (la reapertura numera los ángulos desde 1). */
        val ANGLE_ONE = AngleId(1)

        val REQUEST = AngleEditorRequest(
            uri = VIDEO_URI,
            athleteId = ATHLETE_ID,
            sessionKey = SESSION_KEY,
            initialFrameIndex = 2,
        )

        val VIDEO_INDEX = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L),
            durationUs = 160_000L,
            width = 640,
            height = 360,
            rotationDegrees = 0,
            mimeType = "video/mp4",
        )

        fun markDraft(ordinal: Int, name: String): AngleMarkDraft = AngleMarkDraft(
            ordinal = ordinal,
            name = name,
            colorArgb = COLOR_ARGB,
            pointA = AnglePoint(10.0, 10.0),
            pointB = AnglePoint(10.0, 50.0),
            pointC = AnglePoint(50.0, 10.0),
        )

        fun readSource(relative: String): String {
            val direct = relative.removePrefix("app/")
            val tried = mutableListOf<File>()
            var directory: File? = File(".").absoluteFile
            while (directory != null) {
                for (candidate in listOf(File(directory, relative), File(directory, direct))) {
                    tried += candidate
                    if (candidate.isFile) return candidate.readText()
                }
                directory = directory.parentFile
            }
            throw AssertionError(
                "No se encuentra '$relative'. Directorio de trabajo: ${File(".").absolutePath}. " +
                    "Rutas probadas: ${tried.joinToString { it.absolutePath }}",
            )
        }
    }
}

/** Proyección del borrador a fila de `angle_marks` con el id que asigna el almacén falso. */
private fun AngleMarkDraft.toRow(analysisId: Long, id: Long): AngleMarkEntity = AngleMarkEntity(
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
