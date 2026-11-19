package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleAnalysisDraftRejection
import com.openjump.app.angles.AngleDiscardConfirmation
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AnglePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * Regresión JVM determinista (sin bitmaps, sin vídeo, sin ficheros, sin Room, sin emulador) de
 * [AngleDraftSessionCoordinator].
 *
 * Usa puertos falsos de origen e imagen retenida y un ejecutor manual, de modo que cada captura se
 * ejecuta cuando la prueba lo decide. Cubre: dos fotogramas con imágenes independientes, selección
 * que preserva A/B y ángulos, ediciones aisladas, borrado con confirmación, imagen de otra fuente,
 * respuestas tardías/canceladas/posteriores al cierre, límite de memoria recuperable, single-flight,
 * geometría bloqueada sin imagen retenida, propiedad/leases de píxeles y cierre idempotente.
 *
 * No prueba decodificación real, PTS del códec, orientación, UI ni persistencia: eso pertenece a los
 * adaptadores Android y a slices posteriores.
 */
class AngleDraftSessionCoordinatorTest {

    private val generation = 42L
    private val defaults = AngleEditorDefaults(namePrefix = "Ángulo", colorArgb = 0xFF3366CC.toInt())

    // ------------------------------------------------------------------ fakes

    private fun ptsFor(frameIndex: Int): Long = frameIndex.toLong() * 33_333L + 1L

    private inner class FakeSource(
        override val sourceIdentity: String = SOURCE,
        override val frameCount: Int = 30,
    ) : AngleDraftSourcePort {

        var worstCaseBytes: Long? = 200L
        var captureCalls = 0
        var closeCalls = 0
        var lastCancellation: AngleDraftCancelSignal? = null
        var captureResult: (Int) -> AngleDraftCaptureResult = { index ->
            AngleDraftCaptureResult.Captured(imageFor(index))
        }

        override fun frameAnchor(frameIndex: Int): AngleFrameAnchor? =
            if (frameIndex in 0 until frameCount) {
                AngleFrameAnchor(
                    sourceIdentity = sourceIdentity,
                    frameIndex = frameIndex,
                    sourcePtsUs = ptsFor(frameIndex),
                )
            } else {
                null
            }

        override fun captureWorstCaseBytes(frameIndex: Int): Long? =
            if (frameIndex in 0 until frameCount) worstCaseBytes else null

        override fun capture(
            frameIndex: Int,
            cancellation: AngleDraftCancelSignal,
        ): AngleDraftCaptureResult {
            captureCalls += 1
            lastCancellation = cancellation
            return captureResult(frameIndex)
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private inner class FakeImage(
        override val sourceIdentity: String = SOURCE,
        override val frameIndex: Int,
        override val assignedPtsUs: Long = ptsFor(frameIndex),
        override val width: Int = 10,
        override val height: Int = 10,
        override val contentDigest: Long = frameIndex.toLong() * 7L + 1L,
        override val byteCount: Long = 100L,
    ) : AngleDraftRetainedImage {

        var closed = false

        override fun close() {
            closed = true
        }
    }

    private inner class ManualExecutor : AngleDraftExecutor {

        private val tasks = ArrayDeque<Runnable>()
        val pending: Int get() = tasks.size

        override fun execute(task: Runnable) {
            tasks.addLast(task)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class RecordingObserver : AngleDraftSessionObserver {

        var notifications = 0
        var last: AngleDraftSessionSnapshot? = null

        override fun onSessionChanged(snapshot: AngleDraftSessionSnapshot) {
            notifications += 1
            last = snapshot
        }
    }

    private inner class Fixture(
        val source: FakeSource = FakeSource(),
        limit: Long = AngleDraftSessionCoordinator.MAX_RETAINED_PLUS_CAPTURE_BYTES,
        val executor: ManualExecutor = ManualExecutor(),
        val observer: RecordingObserver = RecordingObserver(),
    ) {
        val coordinator = AngleDraftSessionCoordinator(
            source = source,
            generation = generation,
            editorDefaults = defaults,
            executor = executor,
            observer = observer,
            maxRetainedPlusCaptureBytes = limit,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun imageFor(frameIndex: Int): FakeImage = FakeImage(frameIndex = frameIndex)

    private fun applied(outcome: AngleDraftCoordinatorOutcome): AngleDraftSessionSnapshot =
        (outcome as? AngleDraftCoordinatorOutcome.Applied)?.snapshot
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun rejected(outcome: AngleDraftCoordinatorOutcome): AngleDraftCoordinatorRejection =
        (outcome as? AngleDraftCoordinatorOutcome.Rejected)?.rejection
            ?: throw AssertionError("Esperaba Rejected, llegó $outcome")

    private fun granted(outcome: AngleDraftLeaseOutcome): AngleDraftFrameImageLease =
        (outcome as? AngleDraftLeaseOutcome.Granted)?.lease
            ?: throw AssertionError("Esperaba un lease concedido, llegó $outcome")

    private fun leaseRejection(outcome: AngleDraftLeaseOutcome): AngleDraftCoordinatorRejection =
        (outcome as? AngleDraftLeaseOutcome.Rejected)?.rejection
            ?: throw AssertionError("Esperaba un lease rechazado, llegó $outcome")

    private fun addFrame(fixture: Fixture, frameIndex: Int): AngleFrameId =
        applied(fixture.coordinator.addFrame(frameIndex)).frames.last().frameId

    private fun captureAndRun(fixture: Fixture, frameId: AngleFrameId): AngleDraftSessionSnapshot {
        applied(fixture.coordinator.captureFrame(frameId))
        fixture.executor.runAll()
        return fixture.coordinator.snapshot()
    }

    private fun capturedFrame(fixture: Fixture, frameIndex: Int): AngleFrameId {
        val frameId = addFrame(fixture, frameIndex)
        val snapshot = captureAndRun(fixture, frameId)
        assertTrue(
            "La captura debía adoptarse: ${snapshot.frame(frameId)}",
            snapshot.frame(frameId)?.capture is AngleDraftCapturePhase.Ready,
        )
        return frameId
    }

    private fun confirmation(fixture: Fixture, frameId: AngleFrameId): AngleFrameConfirmation {
        val frame = requireNotNull(fixture.coordinator.snapshot().draft.frame(frameId))
        return AngleFrameConfirmation(
            frame = frame.anchor,
            generation = fixture.coordinator.generation,
            paused = true,
        )
    }

    /** Coloca puntos A/B/C aplicando el editor del fotograma a través del coordinador. */
    private fun place(fixture: Fixture, frameId: AngleFrameId, vararg points: AnglePoint) {
        points.forEach { point ->
            val editor = requireNotNull(fixture.coordinator.snapshot().draft.frame(frameId)).editor
            val outcome = editor.placePoint(point, confirmation(fixture, frameId))
            applied(fixture.coordinator.applyEditorOutcome(frameId, outcome))
        }
    }

    private fun editorOf(fixture: Fixture, frameId: AngleFrameId) =
        requireNotNull(fixture.coordinator.snapshot().draft.frame(frameId)).editor

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    // ------------------------------------------------------------------ 1. dos fotogramas

    @Test
    fun `dos fotogramas tienen imagenes retenidas independientes`() {
        val fixture = Fixture()
        val first = capturedFrame(fixture, 3)
        val second = capturedFrame(fixture, 9)

        val snapshot = fixture.coordinator.snapshot()

        assertEquals(2, snapshot.draft.frameCount)
        assertEquals(200L, snapshot.retainedBytes)
        assertEquals(0, snapshot.pendingCaptureCount)
        assertFalse(snapshot.isClosed)
        val firstFrame = requireNotNull(snapshot.frame(first))
        val secondFrame = requireNotNull(snapshot.frame(second))
        assertTrue(firstFrame.capture is AngleDraftCapturePhase.Ready)
        assertTrue(secondFrame.capture is AngleDraftCapturePhase.Ready)
        assertNotEquals((firstFrame.capture as AngleDraftCapturePhase.Ready).token, (secondFrame.capture as AngleDraftCapturePhase.Ready).token)
        // La imagen adoptada solo existe si sus píxeles están retenidos: mismo hecho atómico.
        assertTrue(requireNotNull(snapshot.draft.frame(first)).image != null)
        assertTrue(requireNotNull(snapshot.draft.frame(second)).image != null)
        assertEquals(2, fixture.source.captureCalls)
    }

    @Test
    fun `no se vuelve a capturar un fotograma que ya tiene imagen`() {
        val fixture = Fixture()
        val frameId = capturedFrame(fixture, 4)

        val outcome = fixture.coordinator.captureFrame(frameId)

        assertEquals(AngleDraftCoordinatorRejection.FrameAlreadyCaptured(frameId), rejected(outcome))
        assertEquals(1, fixture.source.captureCalls)
    }

    // ------------------------------------------------------------------ 2. selección y edición

    @Test
    fun `cambiar de seleccion preserva los puntos A y B y los angulos`() {
        val fixture = Fixture()
        val first = capturedFrame(fixture, 2)
        val second = capturedFrame(fixture, 6)
        // A y B en el primero; ángulo completo en el segundo.
        place(fixture, first, p(10.0, 10.0), p(40.0, 10.0))
        place(fixture, second, p(50.0, 50.0), p(80.0, 50.0), p(80.0, 80.0))

        val beforeFirst = requireNotNull(fixture.coordinator.snapshot().draft.frame(first))
        val beforeSecond = requireNotNull(fixture.coordinator.snapshot().draft.frame(second))

        applied(fixture.coordinator.selectFrame(first))
        applied(fixture.coordinator.selectFrame(second))
        applied(fixture.coordinator.selectFrame(first)).let { snapshot ->
            assertEquals(first, snapshot.activeFrameId)
        }

        val afterFirst = requireNotNull(fixture.coordinator.snapshot().draft.frame(first))
        val afterSecond = requireNotNull(fixture.coordinator.snapshot().draft.frame(second))

        assertEquals(first, fixture.coordinator.snapshot().activeFrameId)
        assertEquals(2, afterFirst.editor.draft.points.size)
        assertEquals(beforeFirst.editor.draft, afterFirst.editor.draft)
        assertEquals(beforeFirst.editor.anchor, afterFirst.editor.anchor)
        assertEquals(beforeFirst.revision, afterFirst.revision)
        assertEquals(1, afterSecond.editor.angles.size)
        assertEquals(beforeSecond.editor.angles, afterSecond.editor.angles)
        assertEquals(beforeSecond.revision, afterSecond.revision)
        assertNotEquals(afterFirst.editor.draft, afterSecond.editor.draft)
    }

    @Test
    fun `editar un fotograma no altera el otro`() {
        val fixture = Fixture()
        val first = capturedFrame(fixture, 1)
        val second = capturedFrame(fixture, 5)

        place(fixture, second, p(50.0, 50.0), p(80.0, 50.0), p(80.0, 80.0))

        val firstEditor = editorOf(fixture, first)
        val secondEditor = editorOf(fixture, second)
        assertTrue(firstEditor.draft.points.isEmpty())
        assertTrue(firstEditor.angles.isEmpty())
        assertNull(firstEditor.anchor)
        assertEquals(1, secondEditor.angles.size)
        assertEquals(0, secondEditor.draft.points.size)
    }

    @Test
    fun `no se permite geometria antes de tener imagen retenida`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 3)
        val editor = editorOf(fixture, frameId)
        val outcome = editor.placePoint(p(10.0, 10.0), confirmation(fixture, frameId))

        val rejection = rejected(fixture.coordinator.applyEditorOutcome(frameId, outcome))

        assertEquals(AngleDraftCoordinatorRejection.FrameImageNotReady(frameId), rejection)
        val unchanged = editorOf(fixture, frameId)
        assertTrue(unchanged.draft.points.isEmpty())
        assertNull(unchanged.anchor)
        assertEquals(0, fixture.source.captureCalls)
    }

    // ------------------------------------------------------------------ 3. borrado con confirmación

    @Test
    fun `eliminar un fotograma con datos exige confirmacion`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 7)
        var retained: FakeImage? = null
        fixture.source.captureResult = {
            FakeImage(frameIndex = 7).also { captured -> retained = captured }
                .let { AngleDraftCaptureResult.Captured(it) }
        }
        captureAndRun(fixture, frameId)
        val image = requireNotNull(retained)
        place(fixture, frameId, p(10.0, 10.0), p(40.0, 10.0))

        val withoutConfirmation = fixture.coordinator.removeFrame(
            frameId,
            AngleDiscardConfirmation.NOT_CONFIRMED,
        )
        assertEquals(
            AngleDraftCoordinatorRejection.DraftRejected(
                AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                    com.openjump.app.angles.AngleDiscardReason.FRAME_REMOVAL,
                ),
            ),
            rejected(withoutConfirmation),
        )
        assertEquals(1, fixture.coordinator.snapshot().draft.frameCount)
        assertEquals(100L, fixture.coordinator.snapshot().retainedBytes)
        assertFalse(image.closed)

        val confirmed = fixture.coordinator.removeFrame(
            frameId,
            AngleDiscardConfirmation.CONFIRMED,
        )

        applied(confirmed)
        assertEquals(0, fixture.coordinator.snapshot().draft.frameCount)
        assertEquals(0L, fixture.coordinator.snapshot().retainedBytes)
        assertNull(fixture.coordinator.snapshot().frame(frameId))
    }

    @Test
    fun `la imagen no se libera hasta cerrar el ultimo lease`() {
        val fixture = Fixture()
        val frameId = capturedFrame(fixture, 8)
        val lease = granted(fixture.coordinator.acquireFrameImageLease(frameId))
        val secondLease = granted(fixture.coordinator.acquireFrameImageLease(frameId))
        assertEquals(2, fixture.coordinator.leaseCount(frameId))

        applied(fixture.coordinator.removeFrame(frameId, AngleDiscardConfirmation.CONFIRMED))

        // El fotograma ya no existe, pero los píxeles siguen prestados: no se han reciclado.
        assertEquals(2, fixture.coordinator.leaseCount(frameId))
        assertEquals(100L, fixture.coordinator.snapshot().retainedBytes)
        assertTrue(lease.isOpen)
        lease.close()
        assertEquals(1, fixture.coordinator.leaseCount(frameId))
        secondLease.close()
        assertEquals(0, fixture.coordinator.leaseCount(frameId))
        assertEquals(0L, fixture.coordinator.snapshot().retainedBytes)
        // Cerrar dos veces un lease es idempotente.
        lease.close()
        secondLease.close()
        assertEquals(0, fixture.coordinator.leaseCount(frameId))
        assertTrue(!lease.isOpen)
    }

    @Test
    fun `un lease no se concede sin imagen retenida`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 2)

        val rejection = leaseRejection(fixture.coordinator.acquireFrameImageLease(frameId))

        assertEquals(AngleDraftCoordinatorRejection.ImageNotRetained(frameId), rejection)
    }

    // ------------------------------------------------------------------ 4. procedencia ajena

    @Test
    fun `una imagen de otra fuente se libera y no se adopta`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 11)
        val foreign = FakeImage(sourceIdentity = "video:otra-fuente", frameIndex = 11)
        fixture.source.captureResult = { AngleDraftCaptureResult.Captured(foreign) }

        val snapshot = captureAndRun(fixture, frameId)

        val phase = requireNotNull(snapshot.frame(frameId)).capture
        assertTrue(phase is AngleDraftCapturePhase.Failed)
        assertEquals(
            AngleDraftCaptureFailureReason.CROSS_SOURCE_IMAGE,
            (phase as AngleDraftCapturePhase.Failed).reason,
        )
        assertTrue(foreign.closed)
        assertNull(requireNotNull(snapshot.draft.frame(frameId)).image)
        assertEquals(0L, snapshot.retainedBytes)
        // La respuesta era del intento vigente, así que no cuenta como tardía; se rechazó al adoptar.
        assertEquals(0L, snapshot.droppedLateResults)
    }

    @Test
    fun `una imagen con otro frame o PTS no se adopta`() {
        val fixture = Fixture()
        val wrongFrame = addFrame(fixture, 12)
        fixture.source.captureResult = { AngleDraftCaptureResult.Captured(FakeImage(frameIndex = 13)) }

        var snapshot = captureAndRun(fixture, wrongFrame)

        assertEquals(
            AngleDraftCaptureFailureReason.ANCHOR_MISMATCH,
            (requireNotNull(snapshot.frame(wrongFrame)).capture as AngleDraftCapturePhase.Failed).reason,
        )

        val wrongPts = addFrame(fixture, 14)
        val image = FakeImage(frameIndex = 14, assignedPtsUs = ptsFor(14) + 1L)
        fixture.source.captureResult = { AngleDraftCaptureResult.Captured(image) }

        snapshot = captureAndRun(fixture, wrongPts)

        assertEquals(
            AngleDraftCaptureFailureReason.ANCHOR_MISMATCH,
            (requireNotNull(snapshot.frame(wrongPts)).capture as AngleDraftCapturePhase.Failed).reason,
        )
        assertTrue(image.closed)
    }

    // ------------------------------------------------------------------ 5. respuestas tardías

    @Test
    fun `una captura cancelada no se adopta y libera su imagen`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 5)
        val image = FakeImage(frameIndex = 5)
        // La captura termina pese a la cancelación: el resultado debe liberarse y no adoptarse.
        fixture.source.captureResult = {
            fixture.coordinator.cancelCapture(frameId)
            AngleDraftCaptureResult.Captured(image)
        }

        applied(fixture.coordinator.captureFrame(frameId))
        assertEquals(1, fixture.executor.pending)
        assertEquals(0, fixture.source.captureCalls)
        fixture.executor.runAll()

        val snapshot = fixture.coordinator.snapshot()
        assertTrue(requireNotNull(snapshot.frame(frameId)).capture is AngleDraftCapturePhase.NotCaptured)
        assertNull(requireNotNull(snapshot.draft.frame(frameId)).image)
        assertTrue(image.closed)
        assertEquals(1L, snapshot.droppedLateResults)
        assertEquals(0L, snapshot.retainedBytes)
        // El intento quedó liberado: se puede volver a capturar.
        applied(fixture.coordinator.captureFrame(frameId))
    }

    @Test
    fun `una respuesta posterior al cierre no se adopta y cierra la fuente una vez`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 6)
        val image = FakeImage(frameIndex = 6)
        var midClose: AngleDraftSessionSnapshot? = null
        // La captura entrega píxeles después del cierre: deben liberarse sin adoptarse. El cierre no
        // libera la reserva de la tarea viva: la memoria comprometida sigue siendo real.
        fixture.source.captureResult = {
            fixture.coordinator.close()
            midClose = fixture.coordinator.snapshot()
            AngleDraftCaptureResult.Captured(image)
        }

        applied(fixture.coordinator.captureFrame(frameId))
        // El cierre ocurre dentro de la captura, ya en ejecución: es la carrera real.
        fixture.executor.runAll()
        fixture.coordinator.close()

        val whileRunning = requireNotNull(midClose)
        assertTrue(whileRunning.isClosed)
        assertEquals(1, whileRunning.pendingCaptureCount)
        assertEquals(200L, whileRunning.reservedCaptureBytes)

        val snapshot = fixture.coordinator.snapshot()
        assertTrue(snapshot.isClosed)
        assertEquals(0, snapshot.pendingCaptureCount)
        assertEquals(0L, snapshot.reservedCaptureBytes)
        assertNull(requireNotNull(snapshot.draft.frame(frameId)).image)
        assertTrue(image.closed)
        assertEquals(1L, snapshot.droppedLateResults)
        assertEquals(1, fixture.source.closeCalls)
        assertEquals(0, fixture.executor.pending)
    }

    @Test
    fun `eliminar un fotograma con captura en vuelo descarta la respuesta`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 7)
        val image = FakeImage(frameIndex = 7)
        // La captura entrega píxeles después de retirar el fotograma: se liberan sin adoptarse.
        fixture.source.captureResult = {
            fixture.coordinator.removeFrame(frameId, AngleDiscardConfirmation.NOT_CONFIRMED)
            AngleDraftCaptureResult.Captured(image)
        }

        applied(fixture.coordinator.captureFrame(frameId))
        fixture.executor.runAll()

        val snapshot = fixture.coordinator.snapshot()
        assertEquals(0, snapshot.draft.frameCount)
        assertTrue(image.closed)
        assertEquals(1L, snapshot.droppedLateResults)
        assertEquals(0L, snapshot.retainedBytes)
    }

    // ------------------------------------------------------------------ 6. límite de memoria

    @Test
    fun `el limite de memoria rechaza antes de capturar y es recuperable`() {
        val fixture = Fixture(limit = 150L)
        fixture.source.worstCaseBytes = 100L
        val first = capturedFrame(fixture, 1)
        val second = addFrame(fixture, 2)

        val rejection = rejected(fixture.coordinator.captureFrame(second))

        assertEquals(
            AngleDraftCoordinatorRejection.MemoryBudgetExceeded(
                retainedBytes = 100L,
                reservedBytes = 0L,
                captureWorstCaseBytes = 100L,
                limitBytes = 150L,
            ),
            rejection,
        )
        assertEquals(1, fixture.source.captureCalls)
        assertEquals(0, fixture.executor.pending)
        // Recuperable: liberar el primero permite capturar el segundo.
        applied(fixture.coordinator.removeFrame(first, AngleDiscardConfirmation.CONFIRMED))
        val snapshot = captureAndRun(fixture, second)
        assertTrue(requireNotNull(snapshot.frame(second)).capture is AngleDraftCapturePhase.Ready)
        assertEquals(100L, snapshot.retainedBytes)
    }

    @Test
    fun `si no se puede presupuestar la captura se rechaza sin decodificar`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 3)
        fixture.source.worstCaseBytes = null

        val rejection = rejected(fixture.coordinator.captureFrame(frameId))

        assertEquals(AngleDraftCoordinatorRejection.CaptureBudgetUnavailable(frameId), rejection)
        assertEquals(0, fixture.source.captureCalls)
        assertEquals(0, fixture.executor.pending)
    }

    @Test
    fun `los bytes reales retenidos tambien respetan el limite`() {
        val fixture = Fixture(limit = 500L)
        val frameId = addFrame(fixture, 4)
        fixture.source.worstCaseBytes = 100L
        val oversized = FakeImage(frameIndex = 4, byteCount = 1_000L)
        fixture.source.captureResult = { AngleDraftCaptureResult.Captured(oversized) }

        val snapshot = captureAndRun(fixture, frameId)

        assertEquals(
            AngleDraftCaptureFailureReason.MEMORY_BUDGET_EXCEEDED,
            (requireNotNull(snapshot.frame(frameId)).capture as AngleDraftCapturePhase.Failed).reason,
        )
        assertTrue(oversized.closed)
        assertEquals(0L, snapshot.retainedBytes)
        assertNull(requireNotNull(snapshot.draft.frame(frameId)).image)
    }

    @Test
    fun `un peor caso no positivo falla cerrado antes de decodificar`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 4)

        fixture.source.worstCaseBytes = 0L
        assertEquals(
            AngleDraftCoordinatorRejection.CaptureBudgetUnavailable(frameId),
            rejected(fixture.coordinator.captureFrame(frameId)),
        )

        fixture.source.worstCaseBytes = -1L
        assertEquals(
            AngleDraftCoordinatorRejection.CaptureBudgetUnavailable(frameId),
            rejected(fixture.coordinator.captureFrame(frameId)),
        )

        assertEquals(0, fixture.source.captureCalls)
        assertEquals(0, fixture.executor.pending)
        val snapshot = fixture.coordinator.snapshot()
        assertEquals(0, snapshot.pendingCaptureCount)
        assertEquals(0L, snapshot.reservedCaptureBytes)
        assertTrue(requireNotNull(snapshot.frame(frameId)).capture is AngleDraftCapturePhase.NotCaptured)
    }

    @Test
    fun `los bytes retenidos por un fotograma retirado con lease siguen contando`() {
        val fixture = Fixture(limit = 250L)
        fixture.source.worstCaseBytes = 200L
        val removed = capturedFrame(fixture, 1)
        val lease = granted(fixture.coordinator.acquireFrameImageLease(removed))
        applied(fixture.coordinator.removeFrame(removed, AngleDiscardConfirmation.CONFIRMED))
        val other = addFrame(fixture, 2)

        // El fotograma ya no existe, pero sus píxeles siguen prestados: cuentan como retenidos.
        assertEquals(100L, fixture.coordinator.snapshot().retainedBytes)
        assertEquals(
            AngleDraftCoordinatorRejection.MemoryBudgetExceeded(
                retainedBytes = 100L,
                reservedBytes = 0L,
                captureWorstCaseBytes = 200L,
                limitBytes = 250L,
            ),
            rejected(fixture.coordinator.captureFrame(other)),
        )

        lease.close()

        assertEquals(0L, fixture.coordinator.snapshot().retainedBytes)
        val snapshot = captureAndRun(fixture, other)
        assertTrue(requireNotNull(snapshot.frame(other)).capture is AngleDraftCapturePhase.Ready)
    }

    // ------------------------------------------------------------------ 7. single-flight y ejecución

    @Test
    fun `una segunda captura en vuelo se rechaza y no duplica la decodificacion`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 2)

        applied(fixture.coordinator.captureFrame(frameId))
        val rejection = rejected(fixture.coordinator.captureFrame(frameId))

        assertTrue(rejection is AngleDraftCoordinatorRejection.CaptureAlreadyInFlight)
        assertEquals(1, fixture.executor.pending)
        assertEquals(0, fixture.source.captureCalls)
        assertEquals(1, fixture.coordinator.snapshot().pendingCaptureCount)

        fixture.executor.runAll()

        assertEquals(1, fixture.source.captureCalls)
        assertTrue(requireNotNull(fixture.coordinator.snapshot().frame(frameId)).capture is AngleDraftCapturePhase.Ready)
    }

    @Test
    fun `una captura en vuelo bloquea la sesion hasta que su tarea termina`() {
        // Con el single-flight por fotograma anterior, A y B validaban cada uno 0 + 200 <= 300 por
        // separado y las dos decodificaciones podían sumar 400 B reales sobre un límite de 300 B.
        val fixture = Fixture(limit = 300L)
        fixture.source.worstCaseBytes = 200L
        val first = addFrame(fixture, 1)
        val second = addFrame(fixture, 2)

        applied(fixture.coordinator.captureFrame(first))
        val inFlight = fixture.coordinator.snapshot()
        assertEquals(1, inFlight.pendingCaptureCount)
        assertEquals(200L, inFlight.reservedCaptureBytes)

        assertEquals(
            AngleDraftCoordinatorRejection.CaptureAlreadyInFlight(first, attemptId = 1L),
            rejected(fixture.coordinator.captureFrame(first)),
        )
        assertEquals(
            AngleDraftCoordinatorRejection.SessionCaptureInFlight(
                activeFrameId = first,
                activeAttemptId = 1L,
                reservedBytes = 200L,
                cancelled = false,
            ),
            rejected(fixture.coordinator.captureFrame(second)),
        )
        assertEquals(1, fixture.executor.pending)
        assertEquals(0, fixture.source.captureCalls)

        fixture.executor.runAll()

        val afterFirst = fixture.coordinator.snapshot()
        assertEquals(0, afterFirst.pendingCaptureCount)
        assertEquals(0L, afterFirst.reservedCaptureBytes)
        assertEquals(100L, afterFirst.retainedBytes)
        assertTrue(requireNotNull(afterFirst.frame(first)).capture is AngleDraftCapturePhase.Ready)

        // Recuperable: 100 retenidos + 0 reservados + 200 = 300 <= 300.
        val afterSecond = captureAndRun(fixture, second)
        assertTrue(requireNotNull(afterSecond.frame(second)).capture is AngleDraftCapturePhase.Ready)
        assertEquals(200L, afterSecond.retainedBytes)
        assertEquals(2, fixture.source.captureCalls)
        // La versión de instantánea avanza de forma monótona para que la UI descarte avisos viejos.
        assertTrue(fixture.observer.notifications > 0)
        assertTrue(requireNotNull(fixture.observer.last).version > inFlight.version)
    }

    @Test
    fun `cancelar una captura en ejecucion no libera la reserva hasta que termina`() {
        val fixture = Fixture()
        val first = addFrame(fixture, 1)
        val second = addFrame(fixture, 2)
        val lateImage = FakeImage(frameIndex = 1)
        var midCancellation: AngleDraftSessionSnapshot? = null
        var retryRejection: AngleDraftCoordinatorRejection? = null
        var otherRejection: AngleDraftCoordinatorRejection? = null
        fixture.source.captureResult = {
            // La captura de A está realmente en ejecución: cancelar invalida la adopción, no el trabajo.
            applied(fixture.coordinator.cancelCapture(first))
            midCancellation = fixture.coordinator.snapshot()
            retryRejection = rejected(fixture.coordinator.captureFrame(first))
            otherRejection = rejected(fixture.coordinator.captureFrame(second))
            AngleDraftCaptureResult.Captured(lateImage)
        }

        applied(fixture.coordinator.captureFrame(first))
        fixture.executor.runAll()

        val whileRunning = requireNotNull(midCancellation)
        assertTrue(
            requireNotNull(whileRunning.frame(first)).capture is AngleDraftCapturePhase.NotCaptured,
        )
        assertEquals(1, whileRunning.pendingCaptureCount)
        assertEquals(200L, whileRunning.reservedCaptureBytes)
        assertEquals(
            AngleDraftCoordinatorRejection.CaptureAlreadyInFlight(first, attemptId = 1L),
            retryRejection,
        )
        assertEquals(
            AngleDraftCoordinatorRejection.SessionCaptureInFlight(
                activeFrameId = first,
                activeAttemptId = 1L,
                reservedBytes = 200L,
                cancelled = true,
            ),
            otherRejection,
        )

        val afterExit = fixture.coordinator.snapshot()
        assertTrue(lateImage.closed)
        assertEquals(1L, afterExit.droppedLateResults)
        assertEquals(0, afterExit.pendingCaptureCount)
        assertEquals(0L, afterExit.reservedCaptureBytes)
        assertEquals(0L, afterExit.retainedBytes)

        // La tarea anterior salió: ambos fotogramas son capturables otra vez.
        fixture.source.captureResult = { index -> AngleDraftCaptureResult.Captured(imageFor(index)) }
        assertTrue(
            requireNotNull(captureAndRun(fixture, second).frame(second)).capture is
                AngleDraftCapturePhase.Ready,
        )
        assertTrue(
            requireNotNull(captureAndRun(fixture, first).frame(first)).capture is
                AngleDraftCapturePhase.Ready,
        )
        assertEquals(200L, fixture.coordinator.snapshot().retainedBytes)
    }

    @Test
    fun `un rechazo del ejecutor libera el single-flight de sesion`() {
        val source = FakeSource()
        val tasks = ArrayDeque<Runnable>()
        var failNext = true
        val coordinator = AngleDraftSessionCoordinator(
            source = source,
            generation = generation,
            editorDefaults = defaults,
            executor = AngleDraftExecutor { task ->
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("ejecutor cerrado")
                }
                tasks.addLast(task)
            },
        )
        val first = applied(coordinator.addFrame(1)).frames.last().frameId
        val second = applied(coordinator.addFrame(2)).frames.last().frameId

        val failed = applied(coordinator.captureFrame(first))

        assertEquals(
            AngleDraftCaptureFailureReason.EXECUTOR_REJECTED,
            (requireNotNull(failed.frame(first)).capture as AngleDraftCapturePhase.Failed).reason,
        )
        assertEquals(0, failed.pendingCaptureCount)
        assertEquals(0L, failed.reservedCaptureBytes)
        assertEquals(0, source.captureCalls)

        // Nada se decodificó y la reserva se soltó: otro fotograma puede capturarse de inmediato.
        applied(coordinator.captureFrame(second))
        assertEquals(1, tasks.size)
        while (tasks.isNotEmpty()) tasks.removeFirst().run()

        val snapshot = coordinator.snapshot()
        assertTrue(requireNotNull(snapshot.frame(second)).capture is AngleDraftCapturePhase.Ready)
        assertEquals(0, snapshot.pendingCaptureCount)
        assertEquals(0L, snapshot.reservedCaptureBytes)
        coordinator.close()
    }

    @Test
    fun `la captura nunca se ejecuta en linea al pedirla`() {
        val fixture = Fixture()
        val frameId = addFrame(fixture, 9)

        applied(fixture.coordinator.captureFrame(frameId))

        assertEquals(0, fixture.source.captureCalls)
        assertEquals(1, fixture.executor.pending)
        assertTrue(fixture.coordinator.snapshot().frame(frameId)?.capture is AngleDraftCapturePhase.Capturing)
    }

    @Test
    fun `un ejecutor que rechaza la tarea no deja la captura en vuelo`() {
        val source = FakeSource()
        val coordinator = AngleDraftSessionCoordinator(
            source = source,
            generation = generation,
            editorDefaults = defaults,
            executor = AngleDraftExecutor { throw IllegalStateException("ejecutor cerrado") },
        )
        val frameId = applied(coordinator.addFrame(10)).frames.last().frameId

        val snapshot = applied(coordinator.captureFrame(frameId))

        assertEquals(
            AngleDraftCaptureFailureReason.EXECUTOR_REJECTED,
            (requireNotNull(snapshot.frame(frameId)).capture as AngleDraftCapturePhase.Failed).reason,
        )
        assertEquals(0, snapshot.pendingCaptureCount)
        assertEquals(0, source.captureCalls)
    }

    // ------------------------------------------------------------------ 8. cierre

    @Test
    fun `tras el cierre no se aceptan operaciones nuevas`() {
        val fixture = Fixture()
        val frameId = capturedFrame(fixture, 2)

        fixture.coordinator.close()

        assertEquals(
            AngleDraftCoordinatorRejection.Closed,
            rejected(fixture.coordinator.addFrame(3)),
        )
        assertEquals(
            AngleDraftCoordinatorRejection.Closed,
            rejected(fixture.coordinator.captureFrame(frameId)),
        )
        assertEquals(
            AngleDraftCoordinatorRejection.Closed,
            rejected(fixture.coordinator.selectFrame(frameId)),
        )
        assertEquals(
            AngleDraftCoordinatorRejection.Closed,
            leaseRejection(fixture.coordinator.acquireFrameImageLease(frameId)),
        )
        fixture.executor.runAll()
        assertEquals(1, fixture.source.closeCalls)
    }

    @Test
    fun `el cierre libera las imagenes sin leases`() {
        val fixture = Fixture()
        val frameId = capturedFrame(fixture, 5)
        val lease = granted(fixture.coordinator.acquireFrameImageLease(frameId))
        val other = capturedFrame(fixture, 6)

        fixture.coordinator.close()

        val snapshot = fixture.coordinator.snapshot()
        assertTrue(snapshot.isClosed)
        // La del fotograma sin lease se libera al cerrar; la prestada espera al lease.
        assertEquals(100L, snapshot.retainedBytes)
        assertEquals(1, fixture.coordinator.leaseCount(frameId))
        assertEquals(0, fixture.coordinator.leaseCount(other))
        lease.close()
        assertEquals(0L, fixture.coordinator.snapshot().retainedBytes)
    }

    @Test
    fun `el observador recibe instantaneas coherentes`() {
        val fixture = Fixture()
        val frameId = capturedFrame(fixture, 4)

        assertTrue(fixture.observer.notifications > 0)
        val last = requireNotNull(fixture.observer.last)
        assertEquals(1, last.draft.frameCount)
        assertTrue(last.frame(frameId)?.capture is AngleDraftCapturePhase.Ready)
    }

    private companion object {
        const val SOURCE = "video:content://openjump/test-source"
    }
}
