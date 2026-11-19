package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Borrado de análisis de ángulos desde el historial EXISTENTE, sin emulador.
 *
 * Fija lo que no necesita Android: qué filas ofrecen borrado ([historyDeleteTarget]), que el objetivo
 * nunca mezcle ids entre familias ([HistoryDeleteTarget]) y que el guard de doble toque sea por
 * (familia, id) ([HistoryDeleteGate]). No ejecuta SQL: el borrado real es `AngleRepository.delete`
 * (raíz + CASCADE de marcas) y se valida por instrumentación, fuera de este slice.
 */
class HistoryDeletionTest {

    // --- qué filas ofrecen borrado ----------------------------------------------------------------

    @Test
    fun `angle row offers deletion for its own family`() {
        val item = MeasurementHistoryItem.Angle(angle(id = 7L, dateTime = 1_690_000_000_000L))

        val target = historyDeleteTarget(item)

        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 7L), target)
        assertEquals("angle-7", target?.key)
        // La fecha de la fila no participa en el borrado ni se reescribe: el objetivo es sólo
        // (familia, id) y la lista conserva la fecha proyectada.
        assertEquals(1_690_000_000_000L, item.dateTime)
    }

    @Test
    fun `angle row without reopenable video is still deletable`() {
        // Sin vídeo o sin atleta válido la fila no navega, pero sigue siendo un análisis guardado:
        // es justo el caso en el que el usuario necesita poder borrarlo.
        assertEquals(
            HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 21L),
            historyDeleteTarget(
                MeasurementHistoryItem.Angle(angle(id = 21L, videoUri = null, athleteId = null)),
            ),
        )
        assertEquals(
            HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 22L),
            historyDeleteTarget(
                MeasurementHistoryItem.Angle(angle(id = 22L, videoUri = "  ", athleteId = 0L)),
            ),
        )
    }

    @Test
    fun `empty encoder row keeps offering deletion through the shared target`() {
        assertEquals(
            HistoryDeleteTarget(HistoryDeleteFamily.ENCODER, 3L),
            historyDeleteTarget(MeasurementHistoryItem.Encoder(emptySession(id = 3L))),
        )
    }

    @Test
    fun `non-empty encoder rows and jump rows offer no deletion`() {
        assertNull(
            historyDeleteTarget(
                MeasurementHistoryItem.Encoder(emptySession(id = 4L).copy(validRepetitions = 3, bestMcv = 0.71)),
            ),
        )
        assertNull(
            historyDeleteTarget(MeasurementHistoryItem.Encoder(emptySession(id = 5L).copy(bestMcv = 0.42))),
        )
        assertNull(
            historyDeleteTarget(MeasurementHistoryItem.Encoder(emptySession(id = 6L).copy(validRepetitions = 2))),
        )
        // Los saltos no se borran desde HISTORY: ni su medición ni su vídeo.
        assertNull(historyDeleteTarget(MeasurementHistoryItem.Jump(jump(id = 9L))))
    }

    // --- familia e id nunca se mezclan ------------------------------------------------------------

    @Test
    fun `same numeric id in both families stays two independent targets`() {
        val encoder = historyDeleteTarget(MeasurementHistoryItem.Encoder(emptySession(id = 12L)))
        val angle = historyDeleteTarget(MeasurementHistoryItem.Angle(angle(id = 12L)))

        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ENCODER, 12L), encoder)
        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 12L), angle)
        assertFalse(encoder == angle)
        assertEquals("encoder-12", encoder?.key)
        assertEquals("angle-12", angle?.key)
        // Ida y vuelta: una confirmación pendiente guardada como texto recupera SU familia.
        assertEquals(encoder, HistoryDeleteTarget.fromKey("encoder-12"))
        assertEquals(angle, HistoryDeleteTarget.fromKey("angle-12"))
    }

    @Test
    fun `fromKey rejects keys that are not a positive family id pair`() {
        listOf("", "angle", "angle-", "-12", "jump-12", "angle-0", "angle--3", "angle-abc", "angle-12x")
            .forEach { key -> assertNull("clave inesperada aceptada: $key", HistoryDeleteTarget.fromKey(key)) }
    }

    // --- confirmar / cancelar / doble toque -------------------------------------------------------

    @Test
    fun `gate rejects a second tap while the same target is in flight`() {
        val gate = HistoryDeleteGate()
        val target = HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 8L)

        assertTrue(gate.begin(target))
        assertFalse("el segundo toque no debe lanzar otro borrado", gate.begin(target))
        assertEquals(setOf(target), gate.snapshot())

        gate.end(target)
        assertTrue("tras terminar el borrado, el reintento sí se acepta", gate.begin(target))
        assertTrue(target in gate.snapshot())
    }

    @Test
    fun `gate keeps families with the same id independent`() {
        val gate = HistoryDeleteGate()
        val encoder = HistoryDeleteTarget(HistoryDeleteFamily.ENCODER, 12L)
        val angle = HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 12L)

        assertTrue(gate.begin(encoder))
        assertTrue("un id de análisis no queda bloqueado por una sesión de encoder", gate.begin(angle))
        assertEquals(setOf(encoder, angle), gate.snapshot())

        gate.end(angle)
        assertEquals(setOf(encoder), gate.snapshot())
        assertFalse("el borrado de encoder sigue en vuelo", gate.begin(encoder))
    }

    // --- fecha y lista ----------------------------------------------------------------------------

    @Test
    fun `computing delete targets does not reorder or rewrite the history list`() {
        val items = mergeMeasurementHistory(
            jumps = listOf(jump(id = 1L, dateTime = 300L)),
            encoderSessions = listOf(emptySession(id = 2L).copy(dateTime = 200L)),
            angleAnalyses = listOf(angle(id = 3L, dateTime = 100L)),
        )
        val before = items.map { it.kindKey() to it.dateTime }

        val targets = items.map(::historyDeleteTarget)

        assertEquals(before, items.map { it.kindKey() to it.dateTime })
        assertEquals("el orden del merge no cambia por calcular objetivos", listOf("JUMP", "ENCODER", "ANGLE"), before.map { it.first })
        assertNull("un salto nunca aporta borrado", targets[0])
        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ENCODER, 2L), targets[1])
        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 3L), targets[2])
    }

    private fun angle(
        id: Long,
        dateTime: Long = 1_000L,
        athleteId: Long? = 1L,
        videoUri: String? = "content://video/$id",
    ) = AngleAnalysisHistoryEntry(
        id = id,
        dateTime = dateTime,
        athleteId = athleteId,
        athleteName = "Ana",
        markCount = 2,
        videoUri = videoUri,
    )

    private fun emptySession(id: Long) = RecentEncoderSession(
        id = id,
        dateTime = 1_000L,
        exercise = "BENCH_PRESS",
        loadKg = 60.0,
        validRepetitions = 0,
        bestMcv = null,
    )

    private fun jump(id: Long, dateTime: Long = 1_000L) = RecentMeasurement(
        id = id,
        dateTime = dateTime,
        protocolId = "CMJ",
        primaryMetricKey = "HEIGHT_CM",
        primaryMetricValue = 42.0,
        primaryMetricUnit = "CENTIMETER",
        side = null,
    )
}
