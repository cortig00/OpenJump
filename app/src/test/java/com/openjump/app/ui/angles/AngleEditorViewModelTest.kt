package com.openjump.app.ui.angles

import androidx.media3.exoplayer.ExoPlayer
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorRejection
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
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
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoUriGrantReconciler
import java.io.FileNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del [AngleEditorViewModel] sin emulador, sin ExoPlayer real y sin Room.
 *
 * Fija el contrato de integración del slice: orden registro-de-grant → toma de permiso →
 * indexación → arranque del transporte; confirmación de gestos a partir de una única instantánea
 * `canMark` con PTS canónico; guardado idempotente (`saveNew`) y CAS (`update`) con mapeo
 * `AngleId` ↔ `angle_marks`; rehidratación por frame/PTS/geometría; URI no disponible sin tocar
 * datos; y liberación de lifecycle.
 */
class AngleEditorViewModelTest {

    private val request = AngleEditorRequest(
        uri = VIDEO_URI,
        athleteId = ATHLETE_ID,
        sessionKey = SESSION_KEY,
        initialFrameIndex = 2,
    )

    // --- apertura, grants y transporte ----------------------------------------------------------

    @Test
    fun `abrir registra el grant antes de tomar permiso indexar y arrancar el transporte`() = runBlocking {
        val harness = Harness()

        val state = harness.viewModel.openInternal(request)

        assertEquals(AngleVideoState.Ready(frameCount = 4, frameIndex = 2, sourcePtsUs = 80_000L), state)
        assertEquals(
            listOf("register:$VIDEO_URI", "take:$VIDEO_URI", "index:$VIDEO_URI", "viewer-start"),
            harness.events,
        )
        assertEquals(1, harness.viewer.collections)
        // El índice real se comparte con la sesión: no se vuelve a indexar en el transporte.
        assertEquals(VIDEO_INDEX, harness.session?.frameIndex)
        assertEquals(0, harness.grants.closedLeases)
    }

    @Test
    fun `un segundo video exige un ViewModel nuevo y no toca grants ni player`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)

        val second = harness.viewModel.openInternal(
            request.copy(uri = "content://openjump/otro-video", sessionKey = "otra-clave"),
        )

        assertEquals(AngleVideoState.AlreadyOpen, second)
        assertEquals(1, harness.events.count { it.startsWith("register:") })
        assertEquals(1, harness.viewer.starts)
        assertEquals(0, harness.grants.closedLeases)
    }

    @Test
    fun `una apertura redundante devuelve el estado vivo sin republicar Opening`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        val ready = harness.viewModel.videoState.value
        assertEquals(AngleVideoState.Ready(frameCount = 4, frameIndex = 2, sourcePtsUs = 80_000L), ready)

        assertEquals(ready, harness.viewModel.openInternal(request))

        // Misma instancia publicada: la segunda apertura no vuelve a pisar Ready con Opening.
        assertSame(ready, harness.viewModel.videoState.value)
        assertEquals(1, harness.events.count { it.startsWith("index:") })
        assertEquals(1, harness.viewer.starts)
    }

    @Test
    fun `abrir en paralelo comparte un solo lease indice y player`() = runBlocking {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.grants.takeGate = gate

        val first = async { harness.viewModel.openInternal(request) }
        harness.grants.takeEntered.await()
        val second = async { harness.viewModel.openInternal(request) }
        yield()
        gate.complete(Unit)

        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals(
            AngleVideoState.Ready(frameCount = 4, frameIndex = 2, sourcePtsUs = 80_000L),
            firstResult,
        )
        assertEquals(firstResult, secondResult)
        assertEquals(1, harness.events.count { it.startsWith("register:") })
        assertEquals(1, harness.events.count { it.startsWith("take:") })
        assertEquals(1, harness.events.count { it.startsWith("index:") })
        assertEquals(1, harness.viewer.starts)
        assertEquals(1, harness.viewer.collections)
        assertEquals(0, harness.grants.closedLeases)
        assertEquals(firstResult, harness.viewModel.videoState.value)
    }

    @Test
    fun `abrir otro video en paralelo devuelve AlreadyOpen sin otro lease ni player`() = runBlocking {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.grants.takeGate = gate
        val other = request.copy(uri = "content://openjump/otro-video", sessionKey = "otra-clave")

        val first = async { harness.viewModel.openInternal(request) }
        harness.grants.takeEntered.await()
        val second = async { harness.viewModel.openInternal(other) }
        yield()
        gate.complete(Unit)

        assertEquals(
            AngleVideoState.Ready(frameCount = 4, frameIndex = 2, sourcePtsUs = 80_000L),
            first.await(),
        )
        assertEquals(AngleVideoState.AlreadyOpen, second.await())
        assertEquals(1, harness.events.count { it.startsWith("register:") })
        assertEquals(1, harness.events.count { it.startsWith("index:") })
        assertEquals(1, harness.viewer.starts)
        assertEquals(0, harness.grants.closedLeases)
    }

    @Test
    fun `uri no disponible deja el editor cerrado sin escribir ni borrar nada`() = runBlocking {
        val harness = Harness()
        harness.indexer.failure = AngleVideoUnavailableException(AngleVideoUnavailableReason.URI_UNAVAILABLE)

        val state = harness.viewModel.openInternal(request)

        assertEquals(
            AngleVideoState.Unavailable(AngleVideoUnavailableReason.URI_UNAVAILABLE),
            state,
        )
        assertNull(harness.viewModel.editor.value)
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals(0, harness.viewer.starts)
        assertTrue(harness.store.saveNewDrafts.isEmpty())
        assertTrue(harness.store.updateDrafts.isEmpty())
        // El lease se registró antes de fallar y se cierra pidiendo reconciliación.
        assertEquals(1, harness.grants.closedLeases)
        assertEquals(1, harness.reconciles)
        assertEquals(AngleSaveState.NoVideo, harness.viewModel.saveInternal())
    }

    // --- clasificación de la URI no disponible --------------------------------------------------

    @Test
    fun `un fallo de indexacion se clasifica por tipo de excepcion`() = runBlocking {
        val missing = Harness()
        missing.indexer.failure = FileNotFoundException("no existe")
        assertEquals(
            AngleVideoState.Unavailable(AngleVideoUnavailableReason.URI_UNAVAILABLE),
            missing.viewModel.openInternal(request),
        )
        assertEquals(1, missing.grants.closedLeases)

        val revoked = Harness()
        revoked.indexer.failure = SecurityException("sin permiso")
        assertEquals(
            AngleVideoState.Unavailable(AngleVideoUnavailableReason.URI_UNAVAILABLE),
            revoked.viewModel.openInternal(request),
        )

        // Un `require` del indexador o un contrato de índice roto NO es un permiso revocado.
        val brokenContract = Harness()
        brokenContract.indexer.failure = IllegalArgumentException("El vídeo no contiene frames analizables.")
        assertEquals(
            AngleVideoState.Unavailable(AngleVideoUnavailableReason.INDEX_FAILED),
            brokenContract.viewModel.openInternal(request),
        )

        val damaged = Harness()
        damaged.indexer.failure = IllegalStateException("contenedor dañado")
        assertEquals(
            AngleVideoState.Unavailable(AngleVideoUnavailableReason.INDEX_FAILED),
            damaged.viewModel.openInternal(request),
        )

        // Un fallo ya tipado conserva el motivo que decidió el adaptador.
        val typed = Harness()
        typed.indexer.failure = AngleVideoUnavailableException(AngleVideoUnavailableReason.INDEX_FAILED)
        assertEquals(
            AngleVideoState.Unavailable(AngleVideoUnavailableReason.INDEX_FAILED),
            typed.viewModel.openInternal(request),
        )
    }

    @Test
    fun `el clasificador solo trata permiso o fichero ausente como uri no disponible`() {
        assertEquals(
            AngleVideoUnavailableReason.URI_UNAVAILABLE,
            angleVideoUnavailableReasonFor(FileNotFoundException("no existe")),
        )
        assertEquals(
            AngleVideoUnavailableReason.URI_UNAVAILABLE,
            angleVideoUnavailableReasonFor(SecurityException("sin permiso")),
        )
        assertEquals(
            AngleVideoUnavailableReason.INDEX_FAILED,
            angleVideoUnavailableReasonFor(IllegalArgumentException("rotación no compatible")),
        )
        assertEquals(
            AngleVideoUnavailableReason.INDEX_FAILED,
            angleVideoUnavailableReasonFor(IllegalStateException("extractor")),
        )
    }

    // --- clave de recuperación -----------------------------------------------------------------

    @Test
    fun `la clave de recuperacion es derivada estable y distinta de la original`() {
        assertEquals("$SESSION_KEY#copy-1", recoverySessionKey(SESSION_KEY, 0))
        assertEquals("$SESSION_KEY#copy-2", recoverySessionKey(SESSION_KEY, 1))
        assertEquals(
            "el reintento del mismo intento reutiliza la misma clave (idempotencia)",
            recoverySessionKey(SESSION_KEY, 0),
            recoverySessionKey(SESSION_KEY, 0),
        )
        assertNotEquals(SESSION_KEY, recoverySessionKey(SESSION_KEY, 0))
        assertTrue(
            "la clave derivada conserva el prefijo original",
            recoverySessionKey(SESSION_KEY, 0).startsWith(SESSION_KEY),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `la clave de recuperacion rechaza una base vacia`() {
        recoverySessionKey("   ", 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `la clave de recuperacion rechaza un intento negativo`() {
        recoverySessionKey(SESSION_KEY, -1)
    }

    // --- geometría y transporte -----------------------------------------------------------------

    @Test
    fun `la geometria de presentacion sale del indice real y no del player`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = ROTATED_VIDEO_INDEX

        assertNull("sin vídeo abierto no hay geometría", harness.viewModel.videoGeometry.value)

        harness.viewModel.openInternal(request)

        // El fake no publica player: la geometría no puede venir de los Tracks de Media3.
        assertNull(harness.viewModel.player.value)
        assertEquals(
            VideoPresentationGeometry(width = 360, height = 640),
            harness.viewModel.videoGeometry.value,
        )

        harness.viewModel.close()
        assertNull(harness.viewModel.videoGeometry.value)
    }

    // --- timeline del índice real ----------------------------------------------------------------

    @Test
    fun `abrir publica la timeline del indice real y republica la misma instancia`() = runBlocking {
        val harness = Harness()

        assertNull("sin vídeo abierto no hay timeline", harness.viewModel.timelineMapper.value)

        harness.viewModel.openInternal(request)

        val timeline = requireNotNull(harness.viewModel.timelineMapper.value)
        assertEquals(VIDEO_INDEX.frameCount, timeline.frameCount)
        assertEquals(0f, timeline.fractionAtIndex(0), 0.0001f)
        assertEquals(1f, timeline.fractionAtIndex(3), 0.0001f)
        // Punto medio exacto entre 40 ms y 80 ms: empate, y el snap elige el frame anterior.
        assertEquals(1, timeline.indexAtFraction(0.5f))

        // Una apertura redundante devuelve el estado vivo: no reconstruye la timeline.
        harness.viewModel.openInternal(request)
        assertSame(timeline, harness.viewModel.timelineMapper.value)
    }

    @Test
    fun `la timeline respeta el span real con vfr primer pts no cero y huecos`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = VFR_VIDEO_INDEX

        harness.viewModel.openInternal(request)

        val timeline = requireNotNull(harness.viewModel.timelineMapper.value)
        assertEquals(4, timeline.frameCount)
        // PTS reales 5.000 / 25.000 / 45.000 / 145.000 µs: el eje es temporal, no ordinal.
        assertEquals(0f, timeline.fractionAtIndex(0), 0.0001f)
        assertEquals(40_000f / 140_000f, timeline.fractionAtIndex(2), 0.0001f)
        assertEquals(1f, timeline.fractionAtIndex(3), 0.0001f)
        // El hueco de 100 ms ocupa el 71,4 % del eje: no se aplana a índices sintéticos.
        assertEquals(2, timeline.indexAtFraction(0.5f))
        // El extremo izquierdo es el primer frame REAL (PTS 5.000), no un instante cero inventado.
        assertEquals(0, timeline.indexAtFraction(0f))
        assertEquals(0f, timeline.fractionAtPts(VFR_VIDEO_INDEX.timeOf(0)), 0.0001f)
    }

    @Test
    fun `la timeline copia los pts y no cambia si se muta el indice`() = runBlocking {
        val harness = Harness()
        val mutableIndex = VIDEO_INDEX.copy(frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L))
        harness.indexer.indexToReturn = mutableIndex

        harness.viewModel.openInternal(request)
        val timeline = requireNotNull(harness.viewModel.timelineMapper.value)

        // El ViewModel no expone el array: aunque el índice se reutilice y mute fuera, la timeline
        // publicada conserva sus copias.
        mutableIndex.frameTimesUs[3] = 999_000L

        assertEquals(4, timeline.frameCount)
        // Si la mutación se hubiera colado, el span sería 999 ms y este PTS ya no sería el final.
        assertEquals(1f, timeline.fractionAtPts(120_000L), 0.0001f)
        assertEquals(1f, timeline.fractionAtIndex(3), 0.0001f)
    }

    @Test
    fun `un observador de ready nunca ve la timeline publicada ni cuando ready es el primer valor`() =
        runBlocking {
            val harness = Harness()
            val timelineAtReady = mutableListOf<Boolean>()
            // Collector no confinado: se ejecuta de forma síncrona en la MISMA publicación de
            // `videoState`, justo después de Ready y antes de que `openInternal` continúe. Es el
            // único punto donde se puede distinguir "publicada antes de Ready" de "después".
            val collector = CoroutineScope(Dispatchers.Unconfined).launch {
                harness.viewModel.videoState.collect { state ->
                    if (state is AngleVideoState.Ready) {
                        timelineAtReady += harness.viewModel.timelineMapper.value != null
                    }
                }
            }
            try {
                harness.viewModel.openInternal(request)
            } finally {
                collector.cancel()
            }

            assertEquals(
                "Ready debe observarse con la timeline todavía nula",
                listOf(false),
                timelineAtReady,
            )
            assertTrue(
                "tras completar la apertura la timeline real sí queda publicada",
                harness.viewModel.timelineMapper.value != null,
            )
        }

    @Test
    fun `un cierre reentrante desde el observador de ready no publica la timeline`() = runBlocking {
        val harness = Harness()
        // Un consumidor puede cerrar el editor en el mismo instante en que ve Ready. La publicación
        // posterior de la timeline debe comprobar el cierre y la generación, no escribir a ciegas.
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            harness.viewModel.videoState.collect { state ->
                if (state is AngleVideoState.Ready) {
                    harness.viewModel.close()
                }
            }
        }
        try {
            harness.viewModel.openInternal(request)
        } finally {
            collector.cancel()
        }

        assertEquals(AngleVideoState.Closed, harness.viewModel.videoState.value)
        assertNull(
            "una timeline publicada tras el cierre mentiría sobre un vídeo ya liberado",
            harness.viewModel.timelineMapper.value,
        )
    }

    @Test
    fun `la timeline es nula antes de ready y se libera al cerrar`() = runBlocking {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.grants.takeGate = gate
        val opening = async { harness.viewModel.openInternal(request) }
        harness.grants.takeEntered.await()

        // Apertura en vuelo: aún no hay índice real que convertir en timeline.
        assertEquals(AngleVideoState.Opening, harness.viewModel.videoState.value)
        assertNull(harness.viewModel.timelineMapper.value)

        gate.complete(Unit)
        opening.await()
        assertTrue(harness.viewModel.timelineMapper.value != null)

        harness.viewModel.close()

        assertNull("el cierre libera también la timeline", harness.viewModel.timelineMapper.value)
        assertEquals(AngleVideoState.Closed, harness.viewModel.videoState.value)
    }

    @Test
    fun `una apertura fallida no publica timeline`() = runBlocking {
        val harness = Harness()
        harness.indexer.failure = AngleVideoUnavailableException(AngleVideoUnavailableReason.URI_UNAVAILABLE)

        val state = harness.viewModel.openInternal(request)

        assertEquals(AngleVideoState.Unavailable(AngleVideoUnavailableReason.URI_UNAVAILABLE), state)
        assertNull(harness.viewModel.timelineMapper.value)
    }

    @Test
    fun `reabrir un analisis conserva la misma timeline del video`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        // La reapertura exige el frame de apertura ya presentado por el transporte.
        harness.pauseAt(frameIndex = 2)
        val timeline = requireNotNull(harness.viewModel.timelineMapper.value)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, revision = 4, frameIndex = 3, sourcePtsUs = 120_000L),
            marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera")),
        )

        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))

        // La reapertura legacy no reconstruye la fuente ni su timeline: misma instancia.
        assertSame(timeline, harness.viewModel.timelineMapper.value)
        assertEquals(
            AngleVideoState.Ready(frameCount = 4, frameIndex = 3, sourcePtsUs = 120_000L),
            harness.viewModel.videoState.value,
        )
    }

    @Test
    fun `delega pasos reproduccion y scrubbing en el transporte comun`() = runBlocking {
        val harness = Harness()

        // Sin vídeo abierto no se delega nada ni se crea player.
        harness.viewModel.stepFrames(1)
        harness.viewModel.togglePlayback()
        harness.viewModel.pausePlayback()
        harness.viewModel.beginScrubbing()
        harness.viewModel.scrubToFrame(1)
        harness.viewModel.endScrubbing()
        assertTrue(harness.viewer.transportEvents.isEmpty())
        assertEquals(0, harness.viewer.starts)

        harness.viewModel.openInternal(request)
        harness.viewModel.stepFrames(-1)
        harness.viewModel.stepFrames(1)
        harness.viewModel.togglePlayback()
        harness.viewModel.pausePlayback()
        harness.viewModel.beginScrubbing()
        // El índice real acota el rango: 99 no puede convertirse en un seek fuera de índice.
        harness.viewModel.scrubToFrame(99)
        harness.viewModel.endScrubbing()

        assertEquals(
            listOf(
                "step:-1",
                "step:1",
                "toggle-playback",
                "pause-playback",
                "begin-scrub",
                "scrub:3",
                "end-scrub",
            ),
            harness.viewer.transportEvents,
        )
        assertEquals("un solo player abierto por el ciclo de apertura", 1, harness.viewer.starts)

        harness.viewModel.close()
        harness.viewModel.stepFrames(1)
        assertEquals(7, harness.viewer.transportEvents.size)
    }

    // --- confirmación de frame y aborto de gestos -----------------------------------------------

    @Test
    fun `la confirmacion exige canMark y el PTS canonico del indice`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)

        assertNull("sin frame renderizado no hay confirmación", harness.viewModel.currentConfirmation())

        // El player confirma el PTS del frame pero todavía no está en pausa.
        harness.viewer.display.value = FrameDisplayState(
            requestedIndex = 2,
            renderedIndex = 2,
            renderedPtsUs = 80_000L,
            mode = ViewerTransportMode.SEEKING,
        )
        assertNull(harness.viewModel.currentConfirmation())

        // En pausa, pero con un PTS que no es el canónico del índice: no se inventa la marca.
        harness.pauseAt(frameIndex = 2, pts = 81_000L)
        assertNull(harness.viewModel.currentConfirmation())

        harness.pauseAt(frameIndex = 2)
        val confirmation = harness.viewModel.currentConfirmation()
        assertEquals(AngleFrameAnchor(VIDEO_URI, frameIndex = 2, sourcePtsUs = 80_000L), confirmation?.frame)
        assertTrue(confirmation!!.paused)
    }

    @Test
    fun `colocar tres puntos crea un angulo anclado al frame confirmado`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)

        harness.placeAngle()

        val editor = requireNotNull(harness.viewModel.editor.value)
        assertEquals(AngleFrameAnchor(VIDEO_URI, frameIndex = 2, sourcePtsUs = 80_000L), editor.anchor)
        assertEquals(1, editor.angles.size)
        assertEquals("Angle 1", editor.angles.single().name)
        assertEquals(AngleId(1), editor.selectedAngleId)
    }

    @Test
    fun `un gesto de otro frame se aborta sin tocar la geometria`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        val gestureFrame = requireNotNull(harness.viewModel.currentConfirmation())
        harness.placeAngle()
        val before = requireNotNull(harness.viewModel.editor.value)

        harness.pauseAt(frameIndex = 3)
        val result = harness.viewModel.placePoint(AnglePoint(20.0, 20.0), gestureFrame)

        assertEquals(AngleEditorActionResult.FrameNotConfirmed, result)
        assertEquals(before, harness.viewModel.editor.value)

        // Con la confirmación viva el estado sí rechaza el arrastre por ancla de otro frame.
        val moved = harness.viewModel.moveMarker(
            angleId = AngleId(1),
            marker = AngleMarker.A,
            point = AnglePoint(20.0, 20.0),
            confirmation = requireNotNull(harness.viewModel.currentConfirmation()),
        )
        assertTrue(moved is AngleEditorActionResult.Rejected)
        assertTrue(
            (moved as AngleEditorActionResult.Rejected).reason is AngleEditorRejection.OtherFrame,
        )
    }

    // --- guardado --------------------------------------------------------------------------------

    @Test
    fun `guardar un analisis nuevo envia marcas nuevas con frame y pts reales`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )

        val state = harness.viewModel.saveInternal()

        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), state)
        val draft = harness.store.saveNewDrafts.single()
        assertEquals(SESSION_KEY, draft.sessionKey)
        assertEquals(ATHLETE_ID, draft.athleteId)
        assertEquals(VIDEO_URI, draft.videoUri)
        assertEquals(VIDEO_URI, draft.sourceIdentity)
        assertEquals(640, draft.encodedWidth)
        assertEquals(360, draft.encodedHeight)
        assertEquals(640, draft.presentationWidth)
        assertEquals(360, draft.presentationHeight)
        assertEquals(0, draft.rotationDegrees)
        assertEquals(2, draft.frameIndex)
        assertEquals(80_000L, draft.sourcePtsUs)
        assertEquals(ANGLE_GEOMETRY_VERSION, draft.geometryVersion)
        val mark = draft.marks.single()
        assertEquals(NEW_ANGLE_MARK_ID, mark.id)
        assertEquals(0, mark.ordinal)
        assertEquals("Angle 1", mark.name)
        assertTrue(mark.visible)
        // La fila guardada es la referencia durable para reabrir, pero el editor sigue vivo: el lease
        // del vídeo NO se suelta ni se pide reconciliación (ver `adoptPersisted`).
        assertEquals(
            "la fila guardada no libera el vídeo mientras el editor sigue mostrándolo",
            0,
            harness.grants.closedLeases,
        )
        assertEquals("sin liberación de lease no hay reconciliación que pedir", 0, harness.reconciles)
        assertEquals(7L, harness.viewModel.savedAnalysisId)
        assertTrue(harness.viewModel.editor.value!!.externallyPersistedAngleIds.contains(AngleId(1)))
    }

    @Test
    fun `el lease se conserva durante toda la vida del editor y se libera una sola vez al cerrar`() =
        runBlocking {
            val harness = Harness()
            harness.viewModel.openInternal(request)
            harness.pauseAt(frameIndex = 2)
            harness.placeAngle()
            harness.store.snapshotToReturn = snapshot(
                revision = 0,
                marks = listOf(markEntity(id = 11L, ordinal = 0)),
            )
            assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())

            // Guardados posteriores sobre la fila adoptada tampoco sueltan el vídeo.
            harness.store.updateToReturn = AngleUpdateResult.Updated(
                snapshot(revision = 1, marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Rodilla"))),
            )
            assertEquals(AngleEditorActionResult.Applied, harness.viewModel.rename(AngleId(1), "Rodilla"))
            assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 1), harness.viewModel.saveInternal())

            assertEquals(0, harness.grants.closedLeases)
            assertEquals(0, harness.reconciles)

            harness.viewModel.close()
            harness.viewModel.close()

            assertEquals(
                "el cierre libera el lease una sola vez, sin fugas ni dobles liberaciones",
                1,
                harness.grants.closedLeases,
            )
            assertEquals("solo el cierre pide reconciliación", 1, harness.reconciles)
        }

    @Test
    fun `un segundo guardado usa CAS con la revision vigente y conserva los ids de Room`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )
        harness.viewModel.saveInternal()
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 1, marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Rodilla"))),
        )

        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.rename(AngleId(1), "Rodilla"))
        val state = harness.viewModel.saveInternal()

        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 1), state)
        val update = harness.store.updateDrafts.single()
        assertEquals(7L, update.analysisId)
        assertEquals(0, update.expectedRevision)
        assertEquals(11L, update.marks.single().id)
        assertEquals("Rodilla", update.marks.single().name)
        assertTrue(update.removedMarkIds.isEmpty())
        assertEquals(1, harness.store.saveNewDrafts.size)
    }

    @Test
    fun `guardar no pierde ni oculta las marcas ocultas`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.placeAngle()

        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.setVisible(AngleId(1), false))
        assertEquals(
            AngleEditorActionResult.Applied,
            harness.viewModel.setColor(AngleId(2), 0xFF112233.toInt()),
        )
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(
                markEntity(id = 11L, ordinal = 0, visible = false),
                markEntity(id = 12L, ordinal = 1, colorArgb = 0xFF112233.toInt()),
            ),
        )

        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())

        val marks = harness.store.saveNewDrafts.single().marks
        assertEquals(2, marks.size)
        assertFalse(marks[0].visible)
        assertTrue(marks[1].visible)
        assertEquals(0xFF112233.toInt(), marks[1].colorArgb)
    }

    @Test
    fun `borrar un angulo persistido lo envia como baja explicita sin tocar las demas`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(
            revision = 2,
            marks = listOf(markEntity(id = 11L, ordinal = 0), markEntity(id = 12L, ordinal = 1)),
        )
        harness.viewModel.saveInternal()
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 3, marks = listOf(markEntity(id = 12L, ordinal = 0, name = "Angle 2"))),
        )

        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.deleteAngle(AngleId(1)))
        val state = harness.viewModel.saveInternal()

        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 3), state)
        val update = harness.store.updateDrafts.single()
        assertEquals(setOf(11L), update.removedMarkIds)
        assertEquals(12L, update.marks.single().id)
        assertEquals(0, update.marks.single().ordinal)
        assertEquals(2, update.expectedRevision)
    }

    @Test
    fun `un conflicto CAS no sobrescribe y mantiene el lease`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 4, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))
        harness.store.updateToReturn = AngleUpdateResult.Conflict(analysisId = 7L, storedRevision = 6)

        val state = harness.viewModel.saveInternal()

        assertEquals(AngleSaveState.Conflict(analysisId = 7L, storedRevision = 6), state)
        assertEquals(1, harness.store.updateDrafts.size)
        assertEquals(0, harness.store.updateDrafts.single().removedMarkIds.size)
        assertEquals(0, harness.grants.closedLeases)
        assertEquals("Angle 1", harness.viewModel.editor.value!!.angles.single().name)
    }

    @Test
    fun `un analisis vacio no se escribe para no borrar datos`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)

        assertEquals(AngleSaveState.EmptyAnalysis, harness.viewModel.saveInternal())
        assertTrue(harness.store.saveNewDrafts.isEmpty())
        assertTrue(harness.store.updateDrafts.isEmpty())
    }

    // --- guardado: blindaje durante la escritura (B6) -------------------------------------------

    @Test
    fun `durante el guardado se rechazan edicion transporte y reapertura y solo se informa Saved de lo enviado`() =
        runBlocking {
            val harness = Harness()
            harness.viewModel.openInternal(request)
            harness.pauseAt(frameIndex = 2)
            harness.placeAngle()
            val staleConfirmation = requireNotNull(harness.viewModel.currentConfirmation())
            harness.store.snapshotToReturn = snapshot(
                revision = 0,
                marks = listOf(markEntity(id = 11L, ordinal = 0)),
            )
            val gate = CompletableDeferred<Unit>()
            harness.store.saveNewGate = gate

            val saving = async { harness.viewModel.saveInternal() }
            harness.store.saveNewEntered.await()
            assertEquals(AngleSaveState.Saving, harness.viewModel.saveState.value)

            // Mientras la escritura está en vuelo no hay confirmación viva y ninguna mutación se aplica.
            assertNull(harness.viewModel.currentConfirmation())
            assertEquals(
                AngleEditorActionResult.SaveInFlight,
                harness.viewModel.placePoint(AnglePoint(20.0, 20.0), staleConfirmation),
            )
            assertEquals(
                AngleEditorActionResult.SaveInFlight,
                harness.viewModel.moveMarker(
                    angleId = AngleId(1),
                    marker = AngleMarker.A,
                    point = AnglePoint(30.0, 30.0),
                    confirmation = staleConfirmation,
                ),
            )
            assertEquals(
                AngleEditorActionResult.SaveInFlight,
                harness.viewModel.rename(AngleId(1), "Rodilla"),
            )
            assertEquals(AngleEditorActionResult.SaveInFlight, harness.viewModel.setVisible(AngleId(1), false))
            assertEquals(AngleEditorActionResult.SaveInFlight, harness.viewModel.deleteAngle(AngleId(1)))
            assertEquals(AngleEditorActionResult.SaveInFlight, harness.viewModel.cancelDraft())
            assertEquals(AngleEditorActionResult.SaveInFlight, harness.viewModel.select(AngleId(1)))

            // La presentación también se rechaza: mover el frame invalidaría la instantánea en vuelo.
            assertFalse(harness.viewModel.stepFrames(1))
            assertFalse(harness.viewModel.togglePlayback())
            assertFalse(harness.viewModel.pausePlayback())
            assertFalse(harness.viewModel.beginScrubbing())
            assertFalse(harness.viewModel.scrubToFrame(1))
            assertFalse(harness.viewModel.endScrubbing())
            assertTrue(harness.viewer.transportEvents.isEmpty())

            // Reabrir reemplazaría estado y revisión a mitad de escritura: rechazo tipado.
            assertEquals(
                AngleReopenResult.Rejected(AngleReopenRejection.SAVE_IN_FLIGHT),
                harness.viewModel.reopenInternal(7L),
            )
            assertEquals(
                AngleReopenState.Rejected(AngleReopenRejection.SAVE_IN_FLIGHT),
                harness.viewModel.reopenState.value,
            )
            // Un segundo guardado no inicia otra escritura.
            assertEquals(AngleSaveState.Saving, harness.viewModel.saveInternal())
            assertEquals(listOf("Angle 1"), harness.viewModel.editor.value!!.angles.map { it.name })

            gate.complete(Unit)

            assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), saving.await())
            assertEquals(1, harness.store.saveNewDrafts.size)
            assertEquals(
                "la instantánea persistida es exactamente la edición que había al guardar",
                listOf("Angle 1"),
                harness.store.saveNewDrafts.single().marks.map { it.name },
            )
            assertTrue(harness.store.updateDrafts.isEmpty())
            assertTrue(harness.viewModel.editor.value!!.externallyPersistedAngleIds.contains(AngleId(1)))

            // Terminado el guardado, la edición pendiente se puede hacer y persistir con CAS.
            harness.pauseAt(frameIndex = 2)
            harness.placeAngle()
            harness.store.updateToReturn = AngleUpdateResult.Updated(
                snapshot(
                    revision = 1,
                    marks = listOf(
                        markEntity(id = 11L, ordinal = 0),
                        markEntity(id = 12L, ordinal = 1),
                    ),
                ),
            )
            assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 1), harness.viewModel.saveInternal())
            assertEquals(2, harness.store.updateDrafts.single().marks.size)
        }

    // --- guardado: verificación del snapshot y recuperación (B7/B11) -----------------------------

    @Test
    fun `un saveNew idempotente con contenido viejo no se adopta y no pierde la edicion`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        // La clave ya existía en otra pantalla con otra marca: saveNew devuelve esa raíz.
        harness.store.snapshotToReturn = snapshot(
            revision = 3,
            marks = listOf(
                markEntity(
                    id = 41L,
                    ordinal = 0,
                    name = "Ajena",
                    pointA = AnglePoint(1.0, 2.0),
                ),
            ),
        )

        val state = harness.viewModel.saveInternal()

        assertTrue(
            "una clave ocupada con otro contenido no se adopta ni se informa guardada: $state",
            state is AngleSaveState.Failed,
        )
        assertEquals(
            "la recuperación explícita queda disponible para no dejar la edición sin destino",
            AngleSaveRecovery.Unverified(analysisId = 7L, revision = 3),
            harness.viewModel.saveRecovery.value,
        )
        assertNull("no se adopta una raíz con otro contenido", harness.viewModel.savedAnalysisId)
        assertEquals("Angle 1", harness.viewModel.editor.value!!.angles.single().name)
        assertTrue(harness.viewModel.editor.value!!.externallyPersistedAngleIds.isEmpty())
        assertTrue(harness.store.updateDrafts.isEmpty())
        assertEquals(
            "la raíz ajena no pasa a ser referencia durable: el lease sigue vivo",
            0,
            harness.grants.closedLeases,
        )

        // Reintento: el guardado normal queda bloqueado (misma clave, fila que no se puede actualizar)
        // y no se adopta por posición.
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals(1, harness.store.saveNewDrafts.size)
        assertTrue(harness.store.updateDrafts.isEmpty())

        // Recuperación explícita: análisis nuevo con clave derivada; la edición queda intacta.
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 51L, ordinal = 0)),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveAsNewInternal())
        assertEquals(recoverySessionKey(SESSION_KEY, 0), harness.store.saveNewDrafts.last().sessionKey)
        assertNotEquals(SESSION_KEY, harness.store.saveNewDrafts.last().sessionKey)
        assertEquals("Angle 1", harness.store.saveNewDrafts.last().marks.single().name)
    }

    @Test
    fun `un snapshot con las marcas intercambiadas no se empareja por posicion`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.placeAngle()
        // Mismo número de marcas, pero el contenido de cada ordinal no es el de la edición.
        harness.store.snapshotToReturn = snapshot(
            revision = 1,
            marks = listOf(
                markEntity(id = 11L, ordinal = 0, name = "Angle 2"),
                markEntity(id = 12L, ordinal = 1, name = "Angle 1"),
            ),
        )

        assertEquals(
            "un snapshot con las marcas intercambiadas no se adopta",
            AngleSaveState.Failed::class,
            harness.viewModel.saveInternal()::class,
        )
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals(
            "la recuperación explícita sustituye al conflicto sin salida",
            AngleSaveRecovery.Unverified(analysisId = 7L, revision = 1),
            harness.viewModel.saveRecovery.value,
        )
        val editor = requireNotNull(harness.viewModel.editor.value)
        assertEquals(listOf("Angle 1", "Angle 2"), editor.angles.map { it.name })
        assertTrue(editor.externallyPersistedAngleIds.isEmpty())
        assertTrue(harness.store.updateDrafts.isEmpty())
    }

    @Test
    fun `la igualdad del snapshot exige contenido completo pero admite otra uri de video`() {
        val draft = AngleAnalysisDraft(
            sessionKey = SESSION_KEY,
            athleteId = ATHLETE_ID,
            videoUri = VIDEO_URI,
            sourceIdentity = VIDEO_URI,
            encodedWidth = 640,
            encodedHeight = 360,
            presentationWidth = 640,
            presentationHeight = 360,
            rotationDegrees = 0,
            frameIndex = 2,
            sourcePtsUs = 80_000L,
            marks = listOf(
                AngleMarkDraft(
                    ordinal = 0,
                    name = "Cadera",
                    colorArgb = COLOR_ARGB,
                    pointA = AnglePoint(10.0, 10.0),
                    pointB = AnglePoint(10.0, 50.0),
                    pointC = AnglePoint(50.0, 10.0),
                ),
            ),
        )
        val matching = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                revision = 2,
                // La URI puede cambiar mientras la identidad lógica de la fuente se mantiene.
                videoUri = "content://openjump/misma-fuente-otra-uri",
                sourceIdentity = VIDEO_URI,
            ),
            marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Cadera")),
        )

        assertTrue(snapshotMatchesDraft(matching, draft))
        assertFalse(
            "distinto nombre de marca no es el guardado enviado",
            snapshotMatchesDraft(
                matching.copy(marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Otra"))),
                draft,
            ),
        )
        assertFalse(
            "mismo contenido pero otro frame no es el guardado enviado",
            snapshotMatchesDraft(
                matching.copy(analysis = analysisEntity(revision = 2, frameIndex = 3, sourcePtsUs = 120_000L)),
                draft,
            ),
        )
        assertFalse(
            "un hueco en los ordinales no permite emparejar por posición",
            marksMatch(
                listOf(markEntity(id = 11L, ordinal = 1, name = "Cadera")),
                draft.marks,
            ),
        )
    }

    @Test
    fun `un snapshot con otro atleta no se atribuye a Saved`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(athleteId = 999L),
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )

        assertEquals(
            "un snapshot de otro atleta no se atribuye a Saved",
            AngleSaveState.Failed::class,
            harness.viewModel.saveInternal()::class,
        )
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals(
            AngleSaveRecovery.Unverified(analysisId = 7L, revision = 0),
            harness.viewModel.saveRecovery.value,
        )
        assertEquals("Angle 1", harness.viewModel.editor.value!!.angles.single().name)
    }

    @Test
    fun `un update efectuado con foto no verificada publica recuperacion y no vuelve a escribir`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())
        // La actualización sí se efectuó, pero la foto devuelta no es lo enviado (otro escritor ganó).
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 9, marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Otro"))),
        )

        val state = harness.viewModel.saveInternal()

        assertTrue("con escritura efectuada no se afirma conflicto: $state", state is AngleSaveState.Failed)
        assertTrue(
            "la revisión persistida se recuerda para informar",
            (state as AngleSaveState.Failed).message.orEmpty().contains("revisión persistida 9"),
        )
        assertEquals(1, harness.store.updateDrafts.size)
        assertEquals("Angle 1", harness.viewModel.editor.value!!.angles.single().name)
        assertEquals(
            "la raíz no verificada sigue bajo la protección del editor vivo",
            0,
            harness.grants.closedLeases,
        )

        // Bloqueo: no se vuelve a escribir sobre la raíz no verificada.
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertEquals(1, harness.store.updateDrafts.size)
        assertTrue(harness.store.updateDrafts.single().removedMarkIds.isEmpty())

        // Recuperación explícita como análisis nuevo: conserva la edición sin pisar la raíz.
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 61L, ordinal = 0)),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveAsNewInternal())
        assertEquals(recoverySessionKey(SESSION_KEY, 0), harness.store.saveNewDrafts.last().sessionKey)
        assertEquals(
            "la copia inserta marcas nuevas, nunca reutiliza el id de otra raíz",
            NEW_ANGLE_MARK_ID,
            harness.store.saveNewDrafts.last().marks.single().id,
        )

        // Recuperada, la edición vuelve a guardarse de forma normal desde la nueva revisión.
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 1, marks = listOf(markEntity(id = 61L, ordinal = 0))),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 1), harness.viewModel.saveInternal())
        assertEquals(0, harness.store.updateDrafts.last().expectedRevision)
        assertEquals(61L, harness.store.updateDrafts.last().marks.single().id)
    }

    // --- guardado: último ángulo protegido (B8) -------------------------------------------------

    @Test
    fun `no se borra el ultimo angulo de un analisis guardado y la edicion no pierde datos`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        // El transporte ya presentó su frame de apertura: la reapertura puede reposicionar.
        harness.pauseAt(frameIndex = 2)
        harness.store.snapshotToReturn = snapshot(
            revision = 4,
            marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera")),
        )
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))

        assertEquals(
            AngleEditorActionResult.LastAngleProtected,
            harness.viewModel.deleteAngle(AngleId(1)),
        )
        assertEquals(listOf("Cadera"), harness.viewModel.editor.value!!.angles.map { it.name })

        // Guardar conserva la marca: la baja nunca ocurre por accidente.
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 5, marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera"))),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 5), harness.viewModel.saveInternal())
        assertTrue(harness.store.updateDrafts.single().removedMarkIds.isEmpty())
        assertEquals(21L, harness.store.updateDrafts.single().marks.single().id)
    }

    @Test
    fun `borrar el unico angulo de un analisis aun no guardado sigue permitido`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()

        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.deleteAngle(AngleId(1)))
        assertTrue(harness.viewModel.editor.value!!.angles.isEmpty())
        assertEquals(AngleSaveState.EmptyAnalysis, harness.viewModel.saveInternal())
        assertTrue(harness.store.saveNewDrafts.isEmpty())
    }

    // --- guardado: análisis borrado en otra pantalla (B9) ---------------------------------------

    @Test
    fun `un analisis borrado no se recrea y solo se recupera guardando como nuevo`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        // El transporte ya presentó su frame de apertura: la reapertura puede reposicionar.
        harness.pauseAt(frameIndex = 2)
        harness.store.snapshotToReturn = snapshot(
            revision = 4,
            marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera")),
        )
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))
        harness.store.updateToReturn = AngleUpdateResult.NotFound

        assertEquals(AngleSaveState.Deleted, harness.viewModel.saveInternal())
        assertEquals(
            "la raíz borrada sigue siendo la referencia: no se recrea su clave",
            7L,
            harness.viewModel.savedAnalysisId,
        )
        assertEquals(1, harness.store.updateDrafts.size)
        assertEquals(listOf("Cadera"), harness.viewModel.editor.value!!.angles.map { it.name })

        assertEquals(AngleSaveState.Deleted, harness.viewModel.saveInternal())
        assertEquals(2, harness.store.updateDrafts.size)
        assertTrue("nunca se recrea con el sessionKey original", harness.store.saveNewDrafts.isEmpty())

        // Recuperación explícita: análisis nuevo, otra clave, mismas marcas como filas nuevas.
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 71L, ordinal = 0, name = "Cadera")),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveAsNewInternal())
        val copy = harness.store.saveNewDrafts.single()
        assertEquals(recoverySessionKey(SESSION_KEY, 0), copy.sessionKey)
        assertNotEquals(SESSION_KEY, copy.sessionKey)
        assertEquals(ATHLETE_ID, copy.athleteId)
        assertEquals(VIDEO_URI, copy.sourceIdentity)
        assertEquals(listOf("Cadera"), copy.marks.map { it.name })
        assertEquals(listOf(NEW_ANGLE_MARK_ID), copy.marks.map { it.id })
        assertEquals(
            "la copia de recuperación no libera el vídeo del editor que sigue abierto",
            0,
            harness.grants.closedLeases,
        )
    }

    // --- reapertura y rehidratación -------------------------------------------------------------

    @Test
    fun `reabrir un analisis reconstruye ids ancla y mapeo a Room`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, revision = 4, frameIndex = 3, sourcePtsUs = 120_000L),
            marks = listOf(
                markEntity(id = 21L, ordinal = 0, name = "Cadera"),
                markEntity(id = 22L, ordinal = 1, name = "Rodilla"),
            ),
        )

        val result = harness.viewModel.reopenInternal(7L)
        val editor = requireNotNull(harness.viewModel.editor.value)

        assertEquals(AngleReopenResult.Reopened, result)
        assertEquals(listOf(AngleId(1), AngleId(2)), editor.angles.map { it.id })
        assertEquals(listOf("Cadera", "Rodilla"), editor.angles.map { it.name })
        assertEquals(AngleFrameAnchor(VIDEO_URI, frameIndex = 3, sourcePtsUs = 120_000L), editor.anchor)
        assertEquals(setOf(AngleId(1), AngleId(2)), editor.externallyPersistedAngleIds)
        assertEquals(7L, harness.viewModel.savedAnalysisId)
        // El player se reposiciona en el frame del análisis para poder confirmarlo en pausa.
        assertEquals(3, harness.session?.currentFrameIndex)
        assertEquals(
            AngleVideoState.Ready(frameCount = 4, frameIndex = 3, sourcePtsUs = 120_000L),
            harness.viewModel.videoState.value,
        )
    }

    /**
     * Regresión del bug de reapertura medido en el AVD: abrir en el frame 0 y reabrir un análisis
     * guardado en el frame 10 dejaba el editor mostrando el frame correcto pero con
     * "Waiting for the confirmed video frame…" para siempre (añadir y arrastrar deshabilitados;
     * guardar seguía permitido porque solo exige ancla y ángulos).
     *
     * Causa: el transporte fija el desfase PTS→frame con la **primera** salida del player, anclándolo
     * en el frame de apertura. La reapertura movía la sesión al frame del análisis antes de esa
     * salida, así que el ancla quedaba en el frame de apertura y todo el visor se desplazaba el
     * número de frames del salto: la imagen en pantalla era la del análisis pero `renderedIndex`
     * valía otro índice y `canMark` no se cumplía nunca.
     *
     * La prueba simula las dos vidas del editor (guardar en el frame 10, volver y reabrir desde el
     * historial con un ViewModel nuevo) y discrimina el orden: mientras el frame de apertura no está
     * renderizado, la reapertura no adopta el análisis, no mueve la sesión y no delega transporte;
     * después de la primera salida se reposiciona en el frame del análisis y la edición vuelve a
     * estar disponible en ese frame y su PTS exacto, sin romper el guardado por CAS.
     */
    @Test
    fun `reabrir en un frame posterior espera la primera salida del player y deja editar en el frame del analisis`() =
        runBlocking {
            // Vida 1: el usuario marca y guarda el ángulo en el frame 10 (el editor abrió en el 0).
            val firstLife = Harness()
            firstLife.indexer.indexToReturn = LONG_VIDEO_INDEX
            firstLife.viewModel.openInternal(request.copy(initialFrameIndex = 0))
            firstLife.renderFrame(frameIndex = 0)
            firstLife.seekAndRender(frameIndex = 10)
            firstLife.placeAngle()
            firstLife.store.snapshotToReturn = AngleAnalysisSnapshot(
                analysis = analysisEntity(
                    id = 7L,
                    revision = 0,
                    frameIndex = 10,
                    sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
                ),
                marks = listOf(markEntity(id = 21L, ordinal = 0)),
            )
            assertEquals(
                AngleSaveState.Saved(analysisId = 7L, revision = 0),
                firstLife.viewModel.saveInternal(),
            )
            val saved = firstLife.store.saveNewDrafts.single()
            assertEquals("el análisis queda guardado en el frame marcado", 10, saved.frameIndex)
            assertEquals(LONG_VIDEO_INDEX.timeOf(10), saved.sourcePtsUs)
            val persisted = requireNotNull(firstLife.store.snapshotToReturn)
            firstLife.viewModel.close()

            // Vida 2: nuevo ViewModel (historial → Editar) que abre en el frame 0 por la ruta.
            val secondLife = Harness()
            secondLife.indexer.indexToReturn = LONG_VIDEO_INDEX
            secondLife.store.snapshotToReturn = persisted
            secondLife.viewModel.openInternal(request.copy(initialFrameIndex = 0))

            val reopening = async { secondLife.viewModel.reopenInternal(7L) }
            secondLife.store.snapshotEntered.await()
            yield()

            // El frame de apertura aún no se ha presentado: nada adoptado, sesión quieta y
            // transporte cerrado para que el ancla no se mueva a otro frame.
            assertEquals(AngleReopenState.Reopening, secondLife.viewModel.reopenState.value)
            assertNull(secondLife.viewModel.savedAnalysisId)
            assertEquals(0, secondLife.session?.currentFrameIndex)
            assertFalse("el transporte no puede mover el frame de apertura", secondLife.viewModel.stepFrames(1))
            assertTrue(secondLife.viewer.transportEvents.isEmpty())

            // Primera salida del player = frame de apertura: ahora sí se reposiciona y se adopta.
            secondLife.renderFrame(frameIndex = 0)
            assertEquals(AngleReopenResult.Reopened, reopening.await())
            assertEquals(10, secondLife.session?.currentFrameIndex)
            assertEquals(
                AngleVideoState.Ready(
                    frameCount = 24,
                    frameIndex = 10,
                    sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
                ),
                secondLife.viewModel.videoState.value,
            )
            val anchor = AngleFrameAnchor(
                VIDEO_URI,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            )
            assertEquals(anchor, secondLife.viewModel.editor.value?.anchor)

            // El player busca el frame del análisis y lo confirma: la edición vuelve a estar
            // disponible en ESE frame y con su PTS exacto (sin desplazamiento de índice).
            secondLife.renderFrame(frameIndex = 10)
            val confirmation = requireNotNull(secondLife.viewModel.currentConfirmation())
            assertEquals(anchor, confirmation.frame)
            val moved = AnglePoint(12.0, 14.0)
            assertEquals(
                AngleEditorActionResult.Applied,
                secondLife.viewModel.moveMarker(AngleId(1), AngleMarker.A, moved, confirmation),
            )

            // El guardado conserva el frame/PTS del análisis y los ids de Room de la vida anterior.
            secondLife.store.updateToReturn = AngleUpdateResult.Updated(
                AngleAnalysisSnapshot(
                    analysis = analysisEntity(
                        id = 7L,
                        revision = 1,
                        frameIndex = 10,
                        sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
                    ),
                    marks = listOf(markEntity(id = 21L, ordinal = 0, pointA = moved)),
                ),
            )
            assertEquals(
                AngleSaveState.Saved(analysisId = 7L, revision = 1),
                secondLife.viewModel.saveInternal(),
            )
            val update = secondLife.store.updateDrafts.single()
            assertEquals(0, update.expectedRevision)
            assertEquals(21L, update.marks.single().id)
            assertEquals(moved, update.marks.single().pointA)
            assertEquals("el ancla sigue siendo el frame y PTS del análisis", anchor, secondLife.viewModel.editor.value?.anchor)
        }

    @Test
    fun `reabrir pide explicitamente el frame del analisis tras la primera salida`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = LONG_VIDEO_INDEX
        harness.viewModel.openInternal(request.copy(initialFrameIndex = 0))
        harness.renderFrame(frameIndex = 0)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                id = 7L,
                revision = 0,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            ),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))

        // El frame del análisis se pide al transporte de forma explícita: no se depende de que el
        // flujo de la sesión emita un valor distinto ni de que el colector lo vea como no redundante.
        assertEquals(listOf("reopen:10"), harness.viewer.transportEvents)
        assertEquals(10, harness.session?.currentFrameIndex)
        // El frame pedido no está confirmado (la salida fue la del frame de apertura 0): sin salida
        // real del player no hay gestos.
        assertNull(harness.viewModel.currentConfirmation())

        harness.renderFrame(frameIndex = 10)
        val confirmation = requireNotNull(harness.viewModel.currentConfirmation())
        assertEquals(10, confirmation.frame.frameIndex)
        assertEquals(LONG_VIDEO_INDEX.timeOf(10), confirmation.frame.sourcePtsUs)
    }

    @Test
    fun `una salida que no es el frame de apertura no confirma la reapertura`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = LONG_VIDEO_INDEX
        harness.viewModel.openInternal(request.copy(initialFrameIndex = 0))
        // Primera salida desalineada: el visor publica otro frame con el frame 0 pedido (callback
        // atrasado o desfase mal anclado). `renderedIndex != null` no basta: darla por buena dejaría
        // la reapertura en éxito con los gestos deshabilitados para siempre.
        harness.viewer.display.value = FrameDisplayState(
            requestedIndex = 0,
            renderedIndex = 3,
            renderedPtsUs = LONG_VIDEO_INDEX.timeOf(3),
            mode = ViewerTransportMode.PAUSED,
        )
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                id = 7L,
                revision = 0,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            ),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        val reopening = async { harness.viewModel.reopenInternal(7L) }
        harness.store.snapshotEntered.await()
        yield()

        // La salida desviada no confirma el ancla: no se adopta nada, no se mueve la sesión y no se
        // pide ningún frame al transporte.
        assertFalse("la reapertura sigue esperando el frame de apertura", reopening.isCompleted)
        assertEquals(AngleReopenState.Reopening, harness.viewModel.reopenState.value)
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals(0, harness.session?.currentFrameIndex)
        assertTrue(harness.viewer.transportEvents.isEmpty())

        // Llega la salida del frame de apertura: ahora sí se reposiciona y se adopta.
        harness.pauseAt(frameIndex = 0)

        assertEquals(AngleReopenResult.Reopened, reopening.await())
        assertEquals(10, harness.session?.currentFrameIndex)
        // El frame pedido (10) no es el confirmado (0): la petición de reapertura invalida la
        // confirmación y espera la salida real del player.
        assertEquals(listOf("reopen:10"), harness.viewer.transportEvents)
    }

    @Test
    fun `reabrir conserva la confirmacion vigente del mismo frame sin invalidarla`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = LONG_VIDEO_INDEX
        // La ruta abre ya en el frame del análisis: `setFrameIndex` deduplica y no emite nada, y el
        // transporte ya presentó exactamente ese frame en pausa.
        harness.viewModel.openInternal(request.copy(initialFrameIndex = 10))
        harness.renderFrame(frameIndex = 10)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                id = 7L,
                revision = 0,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            ),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))

        // La petición sigue siendo explícita (no depende de la emisión del StateFlow), pero el
        // transporte conserva la confirmación real de ese mismo frame en vez de invalidarla: pedir
        // un seek a la posición actual es un no-op de ExoPlayer que jamás emitiría una salida nueva
        // y dejaría el visor en SEEKING con los gestos deshabilitados para siempre (frame 0 incluido).
        assertEquals(listOf("reopen:10"), harness.viewer.transportEvents)
        assertEquals(10, harness.session?.currentFrameIndex)
        val confirmation = requireNotNull(harness.viewModel.currentConfirmation())
        assertEquals(10, confirmation.frame.frameIndex)
        assertEquals(LONG_VIDEO_INDEX.timeOf(10), confirmation.frame.sourcePtsUs)
        assertEquals(10, harness.viewModel.editor.value?.anchor?.frameIndex)
    }

    @Test
    fun `una reapertura con la instantanea suspendida reabre la puerta sin nueva salida del visor`() =
        runBlocking {
            val harness = Harness()
            harness.indexer.indexToReturn = LONG_VIDEO_INDEX
            // Frame 0 confirmado en pausa antes de reabrir: el caso del informe.
            harness.viewModel.openInternal(request.copy(initialFrameIndex = 0))
            harness.renderFrame(frameIndex = 0)
            val opening = requireNotNull(harness.viewModel.currentConfirmation())
            harness.store.snapshotToReturn = AngleAnalysisSnapshot(
                analysis = analysisEntity(
                    id = 7L,
                    revision = 4,
                    frameIndex = 0,
                    sourcePtsUs = LONG_VIDEO_INDEX.timeOf(0),
                ),
                marks = listOf(markEntity(id = 21L, ordinal = 0)),
            )
            val snapshotGate = CompletableDeferred<Unit>()
            harness.store.snapshotGate = snapshotGate

            val reopening = async { harness.viewModel.reopenInternal(7L) }
            harness.store.snapshotEntered.await()
            yield()

            // La puerta observable refleja el bloqueo real y la confirmación no se puede fabricar
            // mientras la instantánea persistida está en suspenso.
            assertEquals(AngleEditorGestureGate.ReopenInFlight, harness.viewModel.gestureGate.value)
            assertNull(harness.viewModel.currentConfirmation())
            val confirmedBeforeReopen = harness.viewer.display.value
            assertEquals(0, confirmedBeforeReopen.renderedIndex)

            snapshotGate.complete(Unit)
            assertEquals(AngleReopenResult.Reopened, reopening.await())

            // El transporte conserva el frame ya confirmado: NO emite otra salida (misma instancia).
            // Es justo la transición en la que una caché memorizada solo por `displayState` se
            // quedaba con el `null` de la reapertura y la pantalla esperaba un frame que estaba
            // confirmado. La puerta sí cambia y publica la reapertura como terminada.
            assertSame(confirmedBeforeReopen, harness.viewer.display.value)
            assertEquals(listOf("reopen:0"), harness.viewer.transportEvents)
            assertEquals(AngleEditorGestureGate.Open, harness.viewModel.gestureGate.value)
            assertEquals(
                "la confirmación vuelve a derivarse del estado vivo, con el PTS real del índice",
                opening,
                harness.viewModel.currentConfirmation(),
            )
            assertEquals(0L, opening.frame.sourcePtsUs)
            assertEquals(7L, harness.viewModel.savedAnalysisId)
        }

    @Test
    fun `la puerta de gestos se cierra durante el guardado y se reabre al terminar`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.renderFrame(frameIndex = 2)
        harness.placeAngle()
        val saveGate = CompletableDeferred<Unit>()
        harness.store.saveNewGate = saveGate

        val saving = async { harness.viewModel.saveInternal() }
        harness.store.saveNewEntered.await()
        yield()

        assertEquals(AngleEditorGestureGate.SaveInFlight, harness.viewModel.gestureGate.value)
        assertNull("durante el guardado no se inicia geometría", harness.viewModel.currentConfirmation())

        saveGate.complete(Unit)
        saving.await()

        assertEquals(AngleEditorGestureGate.Open, harness.viewModel.gestureGate.value)
        val reopened = requireNotNull(harness.viewModel.currentConfirmation()) {
            "la puerta abierta debe dejar confirmar el frame vivo"
        }
        assertEquals(2, reopened.frame.frameIndex)
    }

    @Test
    fun `reabrir dos veces el mismo analisis no pisa la edicion sin guardar`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = LONG_VIDEO_INDEX
        harness.viewModel.openInternal(request.copy(initialFrameIndex = 10))
        harness.renderFrame(frameIndex = 10)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                id = 7L,
                revision = 4,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            ),
            marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera")),
        )
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))
        assertEquals(1, harness.store.snapshotsRead)

        // Edición viva sin guardar: renombrar no escribe en Room.
        val angleId = harness.viewModel.editor.value!!.angles.single().id
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.rename(angleId, "Cadera editada"))

        // Recreación de la Activity: el `remember` de la composición se reinicia y vuelve a pedir la
        // reapertura del MISMO analysisId, pero este ViewModel sobrevive. Repetirla por Room
        // reemplazaría editor, ancla y bindings y perdería la edición sin guardar.
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))

        assertEquals(
            "la edición sin guardar sigue viva tras la recreación",
            listOf("Cadera editada"),
            harness.viewModel.editor.value!!.angles.map { it.name },
        )
        assertEquals("no se vuelve a leer la raíz", 1, harness.store.snapshotsRead)
        assertEquals(
            "los bindings persistidos no se reasignan",
            setOf(angleId),
            harness.viewModel.editor.value!!.externallyPersistedAngleIds,
        )
        assertEquals(7L, harness.viewModel.savedAnalysisId)
        assertEquals(AngleReopenState.Reopened, harness.viewModel.reopenState.value)
    }

    @Test
    fun `una reapertura pedida antes de abrir el video no bloquea la posterior`() = runBlocking {
        val harness = Harness()

        // La ruta solo pide reabrir con el vídeo listo, pero una petición temprana no puede consumir
        // la identidad del análisis: cuando el vídeo abre, la reapertura legítima sigue ocurriendo.
        assertEquals(AngleReopenResult.NoVideo, harness.viewModel.reopenInternal(7L))

        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.store.snapshotToReturn = snapshot(
            revision = 4,
            marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera")),
        )

        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))
        assertEquals(7L, harness.viewModel.savedAnalysisId)
        assertEquals(listOf("Cadera"), harness.viewModel.editor.value!!.angles.map { it.name })
    }

    @Test
    fun `una reapertura rechazada no pide ningun frame al transporte`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.renderFrame(frameIndex = 2)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, sourceIdentity = "content://openjump/otro-video"),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.SOURCE_MISMATCH),
            harness.viewModel.reopenInternal(7L),
        )
        assertTrue(harness.viewer.transportEvents.isEmpty())
    }

    @Test
    fun `si el player falla antes de la primera salida la reapertura falla sin mover la sesion`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = LONG_VIDEO_INDEX
        harness.viewModel.openInternal(request.copy(initialFrameIndex = 0))
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                id = 7L,
                revision = 0,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            ),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        val reopening = async { harness.viewModel.reopenInternal(7L) }
        harness.store.snapshotEntered.await()
        yield()
        harness.viewer.errorState.value = "no se pudo analizar el vídeo"

        assertEquals(AngleReopenResult.Failed("no se pudo analizar el vídeo"), reopening.await())
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals("el player no se reposiciona al frame del análisis", 0, harness.session?.currentFrameIndex)
        assertNull("la edición viva no se reemplaza", harness.viewModel.editor.value?.anchor)
        assertTrue(harness.store.updateDrafts.isEmpty())
    }

    @Test
    fun `cerrar mientras se espera la primera salida no deja la reapertura suspendida`() = runBlocking {
        val harness = Harness()
        harness.indexer.indexToReturn = LONG_VIDEO_INDEX
        harness.viewModel.openInternal(request.copy(initialFrameIndex = 0))
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(
                id = 7L,
                revision = 0,
                frameIndex = 10,
                sourcePtsUs = LONG_VIDEO_INDEX.timeOf(10),
            ),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        val reopening = async { harness.viewModel.reopenInternal(7L) }
        harness.store.snapshotEntered.await()
        yield()
        harness.viewModel.close()

        assertEquals(AngleReopenResult.NoVideo, reopening.await())
        assertNull(harness.viewModel.savedAnalysisId)
        assertEquals(AngleVideoState.Closed, harness.viewModel.videoState.value)
    }

    @Test
    fun `reabrir con otro frame o pts se rechaza sin escribir`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)

        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, frameIndex = 9),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )
        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.FRAME_NOT_INDEXED),
            harness.viewModel.reopenInternal(7L),
        )

        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, sourcePtsUs = 81_000L),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )
        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.PTS_MISMATCH),
            harness.viewModel.reopenInternal(7L),
        )

        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, sourceIdentity = "content://otro/video"),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )
        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.SOURCE_MISMATCH),
            harness.viewModel.reopenInternal(7L),
        )

        assertNull(harness.viewModel.savedAnalysisId)
        assertTrue(harness.store.saveNewDrafts.isEmpty())
        assertTrue(harness.store.updateDrafts.isEmpty())
        assertTrue(harness.viewModel.editor.value!!.angles.isEmpty())
    }

    @Test
    fun `reabrir rechaza un analisis de otro atleta sin rehidratar ni escribir`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        val anchorBefore = harness.viewModel.editor.value!!.anchor
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, athleteId = 999L),
            marks = listOf(markEntity(id = 21L, ordinal = 0, name = "Cadera")),
        )

        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.ATHLETE_MISMATCH),
            harness.viewModel.reopenInternal(7L),
        )

        assertEquals(
            AngleReopenState.Rejected(AngleReopenRejection.ATHLETE_MISMATCH),
            harness.viewModel.reopenState.value,
        )
        assertNull("un análisis ajeno nunca se adopta", harness.viewModel.savedAnalysisId)
        assertEquals(
            "la edición en curso no se reemplaza",
            listOf("Angle 1"),
            harness.viewModel.editor.value!!.angles.map { it.name },
        )
        assertEquals(anchorBefore, harness.viewModel.editor.value!!.anchor)
        assertTrue(harness.store.saveNewDrafts.isEmpty())
        assertTrue(harness.store.updateDrafts.isEmpty())
        // El rechazo no reposiciona el player ni libera el lease de edición.
        assertEquals(2, harness.session?.currentFrameIndex)
        assertEquals(0, harness.grants.closedLeases)
    }

    @Test
    fun `el athleteId del analisis sin dueno tambien se rechaza`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, athleteId = null),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )

        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.ATHLETE_MISMATCH),
            harness.viewModel.reopenInternal(7L),
        )
        assertNull(harness.viewModel.savedAnalysisId)
    }

    @Test
    fun `marcas persistidas incoherentes no se rehidratan`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L),
            marks = listOf(markEntity(id = 21L, ordinal = 0), markEntity(id = 22L, ordinal = 0)),
        )

        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.CORRUPT_MARKS),
            harness.viewModel.reopenInternal(7L),
        )
    }

    @Test
    fun `reabrir un analisis inexistente no crea estado ni escribe`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.store.snapshotToReturn = null

        assertEquals(AngleReopenResult.NotFound, harness.viewModel.reopenInternal(7L))
        assertNull(harness.viewModel.savedAnalysisId)
        assertTrue(harness.viewModel.editor.value!!.angles.isEmpty())
    }

    @Test
    fun `reabrir publica todas las salidas en el flujo observable`() = runBlocking {
        val harness = Harness()
        assertEquals(AngleReopenState.Idle, harness.viewModel.reopenState.value)

        assertEquals(AngleReopenResult.NoVideo, harness.viewModel.reopenInternal(7L))
        assertEquals(AngleReopenState.NoVideo, harness.viewModel.reopenState.value)

        harness.viewModel.openInternal(request)
        harness.store.snapshotToReturn = null
        assertEquals(AngleReopenResult.NotFound, harness.viewModel.reopenInternal(7L))
        assertEquals(AngleReopenState.NotFound, harness.viewModel.reopenState.value)

        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, frameIndex = 9),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )
        assertEquals(
            AngleReopenResult.Rejected(AngleReopenRejection.FRAME_NOT_INDEXED),
            harness.viewModel.reopenInternal(7L),
        )
        assertEquals(
            AngleReopenState.Rejected(AngleReopenRejection.FRAME_NOT_INDEXED),
            harness.viewModel.reopenState.value,
        )

        harness.store.snapshotFailure = IllegalStateException("boom")
        assertEquals(AngleReopenResult.Failed("boom"), harness.viewModel.reopenInternal(7L))
        assertEquals(AngleReopenState.Failed("boom"), harness.viewModel.reopenState.value)

        // El estado transitorio también se publica mientras la lectura sigue en vuelo.
        harness.store.snapshotFailure = null
        harness.store.snapshotToReturn = snapshot(
            revision = 4,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )
        // El frame de apertura ya está renderizado: la reapertura no tiene que esperarlo.
        harness.pauseAt(frameIndex = 2)
        val gate = CompletableDeferred<Unit>()
        harness.store.snapshotGate = gate
        val reopening = async { harness.viewModel.reopenInternal(7L) }
        yield()
        assertEquals(AngleReopenState.Reopening, harness.viewModel.reopenState.value)
        gate.complete(Unit)

        assertEquals(AngleReopenResult.Reopened, reopening.await())
        assertEquals(AngleReopenState.Reopened, harness.viewModel.reopenState.value)
    }

    // --- lifecycle ------------------------------------------------------------------------------

    @Test
    fun `cerrar libera visor y lease una sola vez`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)

        harness.viewModel.close()
        harness.viewModel.close()

        assertEquals(1, harness.viewer.releases)
        assertEquals(1, harness.viewer.scrubCancels)
        assertEquals(1, harness.grants.closedLeases)
        assertEquals(1, harness.reconciles)
        assertEquals(AngleVideoState.Closed, harness.viewModel.videoState.value)
        assertNull(harness.viewModel.editor.value)
        assertEquals(AngleVideoState.Closed, harness.viewModel.openInternal(request))
    }

    @Test
    fun `cerrar durante la apertura no arranca el player ni resucita estado`() = runBlocking {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.grants.takeGate = gate
        val opening = async { harness.viewModel.openInternal(request) }
        harness.grants.takeEntered.await()

        harness.viewModel.close()
        gate.complete(Unit)

        assertEquals(AngleVideoState.Closed, opening.await())
        assertEquals(AngleVideoState.Closed, harness.viewModel.videoState.value)
        assertNull(harness.viewModel.editor.value)
        assertNull(harness.viewModel.videoGeometry.value)
        assertNull("no se prepara el player después del cierre", harness.session?.frameIndex)
        assertEquals(0, harness.viewer.starts)
        assertEquals(0, harness.viewer.collections)
        assertEquals("el lease registrado antes del cierre se cierra una sola vez", 1, harness.grants.closedLeases)
        assertEquals(1, harness.reconciles)
        assertEquals(1, harness.viewer.releases)
    }

    @Test
    fun `cerrar durante el guardado no publica resultado ni reasigna el analisis`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )
        val gate = CompletableDeferred<Unit>()
        harness.store.saveNewGate = gate

        val saving = async { harness.viewModel.saveInternal() }
        harness.store.saveNewEntered.await()
        assertEquals(AngleSaveState.Saving, harness.viewModel.saveState.value)

        harness.viewModel.close()
        gate.complete(Unit)

        assertEquals(AngleSaveState.NoVideo, saving.await())
        assertEquals(AngleSaveState.Idle, harness.viewModel.saveState.value)
        assertNull(harness.viewModel.savedAnalysisId)
        assertNull(harness.viewModel.editor.value)
        // La escritura ya iniciada termina (no se cancela una transacción en curso), pero el
        // ViewModel no publica su resultado ni reasigna bindings después del cierre.
        assertEquals(1, harness.store.saveNewDrafts.size)
        assertEquals(1, harness.grants.closedLeases)
        assertEquals(1, harness.viewer.releases)
    }

    @Test
    fun `cerrar durante la reapertura no publica estado ni reposiciona el player`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, revision = 4, frameIndex = 3, sourcePtsUs = 120_000L),
            marks = listOf(markEntity(id = 21L, ordinal = 0)),
        )
        val gate = CompletableDeferred<Unit>()
        harness.store.snapshotGate = gate

        val reopening = async { harness.viewModel.reopenInternal(7L) }
        harness.store.snapshotEntered.await()

        harness.viewModel.close()
        gate.complete(Unit)

        assertEquals(AngleReopenResult.NoVideo, reopening.await())
        assertEquals(
            "el cierre no deja el flujo de reapertura en Reopening",
            AngleReopenState.Idle,
            harness.viewModel.reopenState.value,
        )
        assertEquals(AngleVideoState.Closed, harness.viewModel.videoState.value)
        assertNull(harness.viewModel.savedAnalysisId)
        assertNull(harness.viewModel.editor.value)
        assertEquals("el player no se reposiciona tras el cierre", 2, harness.session?.currentFrameIndex)
        assertEquals(1, harness.viewer.releases)
        assertEquals(1, harness.grants.closedLeases)
    }

    // --- política de cierre: línea base verificada ------------------------------------------------

    /**
     * Decisión de cierre de la pantalla, leída del ViewModel vivo como lo hace el composable: línea
     * base publicada + contenido semántico del editor actual + fotogramas congelados.
     */
    private fun Harness.closeDecision(frozenFrameCount: Int = 0) = angleEditorCloseDecision(
        saveInFlight = viewModel.isSaveInFlight(),
        baseline = viewModel.closeBaseline.value,
        current = angleEditorContent(viewModel.editor.value),
        frozenFrameCount = frozenFrameCount,
    )

    @Test
    fun `el cierre de un editor abierto sin contenido no pide confirmacion`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)

        assertEquals(AngleEditorContent(), harness.viewModel.closeBaseline.value)
        assertEquals(AngleEditorCloseDecision.Exit, harness.closeDecision())
    }

    @Test
    fun `un borrador parcial de puntos exige confirmar el cierre`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        val confirmation = requireNotNull(harness.viewModel.currentConfirmation())
        listOf(AnglePoint(10.0, 10.0), AnglePoint(10.0, 50.0)).forEach { point ->
            assertEquals(AngleEditorActionResult.Applied, harness.viewModel.placePoint(point, confirmation))
        }

        val decision = harness.closeDecision()
        assertTrue("un borrador es contenido sin guardar", decision is AngleEditorCloseDecision.Confirm)
        assertEquals(
            AngleEditorDiscardContent(liveUnsavedChanges = true, frozenFrameCount = 0),
            (decision as AngleEditorCloseDecision.Confirm).content,
        )
    }

    @Test
    fun `cancelar el borrador tras colocar A y B deja el editor limpio para cerrar`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        val confirmation = requireNotNull(harness.viewModel.currentConfirmation())
        listOf(AnglePoint(10.0, 10.0), AnglePoint(10.0, 50.0)).forEach { point ->
            assertEquals(AngleEditorActionResult.Applied, harness.viewModel.placePoint(point, confirmation))
        }
        assertTrue(
            "un borrador a medias protege el cierre",
            harness.closeDecision() is AngleEditorCloseDecision.Confirm,
        )

        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.cancelDraft())

        // `cancelDraft` no libera el ancla a propósito: el estado vivo no queda vacío del todo, así
        // que este caso es el que detecta que el ancla sola no puede contar como contenido.
        val editor = requireNotNull(harness.viewModel.editor.value)
        assertTrue("cancelar el borrador conserva el ancla", editor.anchor != null)
        assertTrue(editor.angles.isEmpty())
        assertTrue(editor.draft.points.isEmpty())
        assertEquals(AngleEditorContent(), harness.viewModel.closeBaseline.value)
        assertEquals(AngleEditorCloseDecision.Exit, harness.closeDecision())

        // La protección no se relaja: un borrador nuevo vuelve a exigir confirmación.
        val fresh = requireNotNull(harness.viewModel.currentConfirmation())
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.placePoint(AnglePoint(20.0, 20.0), fresh))
        assertTrue(harness.closeDecision() is AngleEditorCloseDecision.Confirm)
    }

    @Test
    fun `un guardado verificado adopta la instantanea enviada como linea base y seleccionar no la ensucia`() =
        runBlocking {
            val harness = Harness()
            harness.viewModel.openInternal(request)
            harness.pauseAt(frameIndex = 2)
            harness.placeAngle()
            harness.store.snapshotToReturn = snapshot(
                revision = 0,
                marks = listOf(markEntity(id = 11L, ordinal = 0)),
            )

            assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())

            assertEquals(angleEditorContent(harness.viewModel.editor.value), harness.viewModel.closeBaseline.value)
            assertEquals(AngleEditorCloseDecision.Exit, harness.closeDecision())
            // Seleccionar y tener ids persistidos no es editar: sigue limpio.
            harness.viewModel.select(AngleId(1))
            assertTrue(harness.viewModel.editor.value!!.externallyPersistedAngleIds.isNotEmpty())
            assertEquals(AngleEditorCloseDecision.Exit, harness.closeDecision())
            // Un cambio real de contenido sí exige confirmar.
            assertEquals(AngleEditorActionResult.Applied, harness.viewModel.rename(AngleId(1), "Cadera"))
            assertTrue(harness.closeDecision() is AngleEditorCloseDecision.Confirm)
        }

    @Test
    fun `un guardado verificado con borrador pendiente no limpia la proteccion`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        val confirmation = requireNotNull(harness.viewModel.currentConfirmation())
        listOf(AnglePoint(60.0, 60.0), AnglePoint(60.0, 90.0)).forEach { point ->
            assertEquals(AngleEditorActionResult.Applied, harness.viewModel.placePoint(point, confirmation))
        }
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )

        val state = harness.viewModel.saveInternal()

        // La fila se adoptó, pero el borrador no está persistido: la pantalla no puede cerrar sola.
        assertTrue(state is AngleSaveState.Failed)
        assertEquals(angleEditorContent(harness.viewModel.editor.value).copy(draftPoints = 0), harness.viewModel.closeBaseline.value)
        val decision = harness.closeDecision()
        assertTrue("el borrador pendiente sigue exigiendo confirmación", decision is AngleEditorCloseDecision.Confirm)
        assertTrue((decision as AngleEditorCloseDecision.Confirm).content.liveUnsavedChanges)
    }

    @Test
    fun `un conflicto de CAS no actualiza la linea base verificada`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(
            revision = 0,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())
        val baseline = harness.viewModel.closeBaseline.value
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.rename(AngleId(1), "Cadera"))
        harness.store.updateToReturn = AngleUpdateResult.Conflict(analysisId = 7L, storedRevision = 6)

        val state = harness.viewModel.saveInternal()

        assertEquals(AngleSaveState.Conflict(analysisId = 7L, storedRevision = 6), state)
        assertEquals("un conflicto no verifica nada: la línea base no cambia", baseline, harness.viewModel.closeBaseline.value)
        assertTrue(harness.closeDecision() is AngleEditorCloseDecision.Confirm)
    }

    @Test
    fun `una reapertura verificada deja la edicion limpia y borrar despues exige confirmar`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.store.snapshotToReturn = AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, revision = 4, frameIndex = 3, sourcePtsUs = 120_000L),
            marks = listOf(
                markEntity(id = 21L, ordinal = 0, name = "Cadera"),
                markEntity(id = 22L, ordinal = 1, name = "Rodilla"),
            ),
        )

        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(7L))

        assertEquals(angleEditorContent(harness.viewModel.editor.value), harness.viewModel.closeBaseline.value)
        assertEquals(AngleEditorCloseDecision.Exit, harness.closeDecision())
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.deleteAngle(AngleId(2)))
        assertTrue(harness.closeDecision() is AngleEditorCloseDecision.Confirm)
        // El último ángulo de un análisis YA persistido no se puede borrar desde el editor (se
        // gestiona desde el historial): la protección de cierre sigue activa por la baja anterior.
        assertEquals(
            AngleEditorActionResult.LastAngleProtected,
            harness.viewModel.deleteAngle(AngleId(1)),
        )
        assertTrue(harness.closeDecision() is AngleEditorCloseDecision.Confirm)
    }

    @Test
    fun `un guardado en vuelo bloquea el cierre mono y multi`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        val gate = CompletableDeferred<Unit>()
        harness.store.saveNewGate = gate

        val saving = async { harness.viewModel.saveInternal() }
        harness.store.saveNewEntered.await()

        assertTrue(harness.viewModel.isSaveInFlight())
        assertEquals(AngleEditorCloseDecision.BlockedBySave, harness.closeDecision())

        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        gate.complete(Unit)
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), saving.await())
        assertFalse(harness.viewModel.isSaveInFlight())
    }

    // --- utilidades -----------------------------------------------------------------------------

    private fun snapshot(
        revision: Int,
        marks: List<AngleMarkEntity>,
        sessionKey: String = SESSION_KEY,
    ): AngleAnalysisSnapshot =
        AngleAnalysisSnapshot(
            analysis = analysisEntity(id = 7L, revision = revision, sessionKey = sessionKey),
            marks = marks,
        )

    private fun analysisEntity(
        id: Long = 7L,
        revision: Int = 0,
        athleteId: Long? = ATHLETE_ID,
        sessionKey: String = SESSION_KEY,
        frameIndex: Int = 2,
        sourcePtsUs: Long = 80_000L,
        sourceIdentity: String? = VIDEO_URI,
        videoUri: String? = VIDEO_URI,
        presentationWidth: Int = 640,
        presentationHeight: Int = 360,
        rotationDegrees: Int = 0,
        geometryVersion: Int = ANGLE_GEOMETRY_VERSION,
    ) = AngleAnalysisEntity(
        id = id,
        sessionKey = sessionKey,
        athleteId = athleteId,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        revision = revision,
        videoUri = videoUri,
        sourceIdentity = sourceIdentity,
        sourceHash = null,
        encodedWidth = 640,
        encodedHeight = 360,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        frameIndex = frameIndex,
        sourcePtsUs = sourcePtsUs,
        geometryVersion = geometryVersion,
    )

    private fun markEntity(
        id: Long,
        ordinal: Int,
        name: String = "Angle ${ordinal + 1}",
        visible: Boolean = true,
        colorArgb: Int = COLOR_ARGB,
        pointA: AnglePoint = AnglePoint(10.0, 10.0),
        pointB: AnglePoint = AnglePoint(10.0, 50.0),
        pointC: AnglePoint = AnglePoint(50.0, 10.0),
    ) = AngleMarkEntity(
        id = id,
        analysisId = 7L,
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

    private class Harness(val store: FakeStore = FakeStore()) {
        val events = mutableListOf<String>()
        val grants = FakeGrants(events)
        val indexer = FakeIndexer(events)
        val viewer = FakeViewer(events)
        var reconciles = 0
        var session: AngleViewerSession? = null
        val viewModel = AngleEditorViewModel(
            store = store,
            grants = grants,
            requestReconcile = { reconciles += 1 },
            indexer = indexer,
            defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = COLOR_ARGB),
            createViewer = { createdSession, _ ->
                session = createdSession
                viewer
            },
        )

        fun pauseAt(frameIndex: Int, pts: Long = VIDEO_INDEX.timeOf(frameIndex)) {
            viewer.display.value = FrameDisplayState(
                requestedIndex = frameIndex,
                renderedIndex = frameIndex,
                renderedPtsUs = pts,
                mode = ViewerTransportMode.PAUSED,
            )
        }

        /**
         * Publica la salida del player como si el transporte hubiera renderizado [frameIndex] en
         * pausa: frame pedido == renderizado y PTS canónico del índice compartido con la sesión.
         */
        fun renderFrame(frameIndex: Int) {
            val index = requireNotNull(session?.frameIndex) { "el vídeo debe estar abierto" }
            pauseAt(frameIndex = frameIndex, pts = index.timeOf(frameIndex))
        }

        /** Simula una búsqueda del transporte del editor: sesión y render en el mismo frame. */
        fun seekAndRender(frameIndex: Int) {
            requireNotNull(session).setFrameIndex(frameIndex)
            renderFrame(frameIndex)
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
    }

    private class FakeStore : AngleAnalysisStore {

        val saveNewDrafts = mutableListOf<AngleAnalysisDraft>()
        val updateDrafts = mutableListOf<AngleUpdateDraft>()
        var analysisIdToReturn = 7L
        var snapshotToReturn: AngleAnalysisSnapshot? = null
        var updateToReturn: AngleUpdateResult = AngleUpdateResult.NotFound
        var saveNewFailure: Exception? = null
        var snapshotFailure: Exception? = null
        var snapshotsRead = 0

        /** Puertas de suspensión para probar el cierre con la operación en vuelo. */
        var saveNewGate: CompletableDeferred<Unit>? = null
        var snapshotGate: CompletableDeferred<Unit>? = null
        val saveNewEntered = CompletableDeferred<Unit>()
        val snapshotEntered = CompletableDeferred<Unit>()

        override suspend fun saveNew(draft: AngleAnalysisDraft): Long {
            saveNewDrafts += draft
            saveNewEntered.complete(Unit)
            saveNewGate?.await()
            saveNewFailure?.let { throw it }
            return analysisIdToReturn
        }

        override suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult {
            updateDrafts += draft
            return updateToReturn
        }

        override suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? {
            snapshotsRead += 1
            snapshotEntered.complete(Unit)
            snapshotGate?.await()
            snapshotFailure?.let { throw it }
            return snapshotToReturn
        }
    }

    private class FakeGrants(private val events: MutableList<String>) : AngleVideoGrants {

        var closedLeases = 0
        var takeGate: CompletableDeferred<Unit>? = null
        val takeEntered = CompletableDeferred<Unit>()

        override fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease {
            events += "register:${uris.single()}"
            return VideoUriGrantReconciler.Lease {
                closedLeases += 1
                events += "lease-closed"
            }
        }

        override suspend fun takeReadGrant(uri: String) {
            events += "take:$uri"
            takeEntered.complete(Unit)
            takeGate?.await()
        }
    }

    private class FakeIndexer(private val events: MutableList<String>) : AngleVideoIndexer {

        var failure: Exception? = null
        var indexToReturn: VideoFrameIndex = VIDEO_INDEX
        var indexGate: CompletableDeferred<Unit>? = null
        val indexEntered = CompletableDeferred<Unit>()

        override suspend fun index(uri: String): VideoFrameIndex {
            events += "index:$uri"
            indexEntered.complete(Unit)
            indexGate?.await()
            failure?.let { throw it }
            return indexToReturn
        }
    }

    private class FakeViewer(private val events: MutableList<String>) : AngleViewerViewer {

        val display = MutableStateFlow(FrameDisplayState())
        override val player: StateFlow<ExoPlayer?> = MutableStateFlow<ExoPlayer?>(null)
        override val displayState: StateFlow<FrameDisplayState> = display
        override val loading: StateFlow<Boolean> = MutableStateFlow(true)
        val errorState = MutableStateFlow<String?>(null)
        override val error: StateFlow<String?> = errorState
        var starts = 0
        var collections = 0
        var releases = 0
        var scrubCancels = 0

        /** Llamadas de transporte observadas, en orden: prueba la delegación exacta. */
        val transportEvents = mutableListOf<String>()

        override fun start() {
            starts += 1
            events += "viewer-start"
        }

        override fun startSessionCollection() {
            collections += 1
        }

        override fun seekToFrame(frameIndex: Int) {
            transportEvents += "seek:$frameIndex"
            // Misma transición que el transporte real: la petición invalida la confirmación anterior
            // (renderizado != pedido) y queda a la espera de la salida del player.
            display.value = display.value.request(frameIndex, ViewerTransportMode.SEEKING)
        }

        override fun reopenFrame(frameIndex: Int) {
            transportEvents += "reopen:$frameIndex"
            // Contrato del transporte real (`requestFrameForReopen`): conserva la confirmación solo
            // si ya es exactamente ese frame en pausa; en cualquier otro caso la invalida y espera
            // una salida real del player (un seek a la misma posición no emitiría callback).
            val current = display.value
            if (current.canMark && current.requestedIndex == frameIndex) return
            display.value = current.request(frameIndex, ViewerTransportMode.SEEKING)
        }

        override fun refreshCurrentFrame() = Unit

        override fun stepFrames(delta: Int) {
            transportEvents += "step:$delta"
        }

        override fun togglePlayback() {
            transportEvents += "toggle-playback"
        }

        override fun pausePlayback() {
            transportEvents += "pause-playback"
        }

        override fun beginScrubbing() {
            transportEvents += "begin-scrub"
        }

        override fun scrubToFrame(frameIndex: Int) {
            transportEvents += "scrub:$frameIndex"
        }

        override fun endScrubbing() {
            transportEvents += "end-scrub"
        }

        override fun cancelPendingScrubRelease() {
            scrubCancels += 1
        }

        override fun release() {
            releases += 1
            events += "viewer-release"
        }
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

        /** Mismo índice grabado en vertical: encoded 640x360 con rotación de 90°. */
        val ROTATED_VIDEO_INDEX = VIDEO_INDEX.copy(rotationDegrees = 90)

        /**
         * Índice largo: permite abrir en el frame 0 y reabrir un análisis guardado en un frame
         * posterior (> 0), que es el caso que rompía el ancla PTS→frame del visor.
         */
        val LONG_VIDEO_INDEX = VIDEO_INDEX.copy(
            frameTimesUs = LongArray(24) { position -> position * 40_000L },
            durationUs = 960_000L,
        )

        /**
         * VFR con primer PTS distinto de cero y un hueco largo: 5 / 25 / 45 / 145 ms. La timeline
         * temporal debe conservar el span real en vez de repartir los frames por índice.
         */
        val VFR_VIDEO_INDEX = VIDEO_INDEX.copy(
            frameTimesUs = longArrayOf(5_000L, 25_000L, 45_000L, 145_000L),
            durationUs = 150_000L,
        )
    }
}
