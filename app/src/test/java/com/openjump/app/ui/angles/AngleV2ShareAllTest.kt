package com.openjump.app.ui.angles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Núcleo puro de «compartir todas las imágenes»: orden, todo-o-nada, snapshot, presupuesto de
 * transporte y single-flight.
 *
 * El lote se ejercita con un URI de mentira (`String`) porque en la JVM no se puede construir un
 * `android.net.Uri`; la secuencia probada es **la misma función de producción** que usa el
 * ViewModel del detalle, y el único punto que cambia es el tipo del identificador que entrega el
 * publicador. No hay dobles de UI ni comprobaciones por inspección de texto.
 */
class AngleV2ShareAllTest {

    @Test
    fun `todos listos devuelve una entrada por fotograma en orden sin deduplicar`() {
        val frames = listOf(frame(0), frame(1), frame(2))
        // El tercer fotograma publica el MISMO URI que el primero: son dos entradas, no una.
        val published = listOf("u-0", "u-1", "u-0")
        val calls = mutableListOf<String>()
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { true },
            prepareFrame = { frame ->
                calls += frame.imageKey
                AngleV2ShareAllFrame.Ready(published[calls.lastIndex])
            },
            uriLength = String::length,
        )
        val ready = result as AngleV2ShareAllResult.Ready
        assertEquals("El orden debe ser el del contenido cargado.", published, ready.items)
        assertEquals("Una entrada por fotograma, aunque el URI se repita.", 3, ready.items.size)
        assertEquals(
            "Se prepara cada fotograma exactamente una vez y en orden.",
            frames.map { it.imageKey },
            calls,
        )
    }

    @Test
    fun `un fallo en medio no entrega ningun URI y no prepara el resto`() {
        val frames = listOf(frame(0), frame(1), frame(2), frame(3))
        val prepared = mutableListOf<Int>()
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { true },
            prepareFrame = { frame ->
                prepared += frame.ordinal
                if (frame.ordinal == 1) {
                    AngleV2ShareAllFrame.Missing
                } else {
                    AngleV2ShareAllFrame.Ready("u-${frame.ordinal}")
                }
            },
            uriLength = String::length,
        )
        assertEquals(
            "Una imagen ausente invalida el lote completo.",
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.MISSING),
            result,
        )
        assertEquals("No se sigue preparando después del fallo.", listOf(0, 1), prepared)
    }

    @Test
    fun `un fallo en el ultimo tampoco entrega un conjunto incompleto`() {
        val frames = listOf(frame(0), frame(1), frame(2))
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { true },
            prepareFrame = { frame ->
                if (frame.ordinal == 2) {
                    AngleV2ShareAllFrame.Corrupted
                } else {
                    AngleV2ShareAllFrame.Ready("u-${frame.ordinal}")
                }
            },
            uriLength = String::length,
        )
        assertEquals(
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.CORRUPTED),
            result,
        )
    }

    @Test
    fun `un fotograma que no declara png rechaza el lote antes de publicar nada`() {
        val frames = listOf(frame(0), frame(1, mime = "video/mp4"), frame(2))
        var prepared = 0
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { true },
            prepareFrame = {
                prepared++
                AngleV2ShareAllFrame.Ready("u")
            },
            uriLength = String::length,
        )
        assertEquals(
            "Sólo se comparte image/png: un tipo declarado distinto no puede colarse en el lote.",
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.CORRUPTED),
            result,
        )
        assertEquals("El tipo se valida antes de publicar ninguna copia.", 0, prepared)
    }

    @Test
    fun `una raiz sin fotogramas no es un envio valido`() {
        val result = prepareAngleV2ShareAll<String>(
            frames = emptyList(),
            isStillCurrent = { true },
            prepareFrame = { throw AssertionError("No debe prepararse nada en una raíz vacía.") },
            uriLength = String::length,
        )
        assertEquals(AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.EMPTY), result)
    }

    @Test
    fun `un cambio de snapshot durante la preparacion no entrega ningun URI`() {
        var current = true
        val frames = listOf(frame(0), frame(1), frame(2))
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { current },
            prepareFrame = { frame ->
                // Tras preparar el primero, la raíz/snapshot deja de ser el mismo.
                if (frame.ordinal == 0) current = false
                AngleV2ShareAllFrame.Ready("u-${frame.ordinal}")
            },
            uriLength = String::length,
        )
        assertEquals(
            "Un snapshot que cambia a mitad se informa como obsoleto, nunca como éxito.",
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.STALE),
            result,
        )
    }

    @Test
    fun `un snapshot ya cambiado antes de empezar no prepara nada`() {
        var prepared = 0
        val result = prepareAngleV2ShareAll(
            frames = listOf(frame(0)),
            isStillCurrent = { false },
            prepareFrame = {
                prepared++
                AngleV2ShareAllFrame.Ready("u")
            },
            uriLength = String::length,
        )
        assertEquals(AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.STALE), result)
        assertEquals(0, prepared)
    }

    @Test
    fun `una cancelacion no se convierte en fallo ni entrega URI`() {
        val result = runCatching {
            prepareAngleV2ShareAll<String>(
                frames = listOf(frame(0), frame(1)),
                isStillCurrent = { true },
                prepareFrame = { throw CancellationException("cancelado") },
                uriLength = String::length,
            )
        }
        val failure = result.exceptionOrNull()
        assertTrue(
            "La cancelación debe propagarse para que el llamante no lance ningún selector: $failure",
            failure is CancellationException,
        )
    }

    @Test
    fun `el presupuesto de transporte rechaza el lote completo sin truncarlo`() {
        // 4096 fotogramas importados con URI de ~100 caracteres: no cabe en un envío seguro.
        val frames = (0 until 4096).map { frame(it, sha = shaFor(it)) }
        var prepared = 0
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { true },
            prepareFrame = {
                prepared++
                AngleV2ShareAllFrame.Ready(TOO_LONG_URI)
            },
            uriLength = String::length,
        )
        assertEquals(
            "Un conjunto que no cabe se informa; no se recorta la lista.",
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.TOO_LARGE),
            result,
        )
        assertTrue("El presupuesto corta en cuanto no cabe.", prepared in 1..4096)
    }

    @Test
    fun `el presupuesto no impone un tope arbitrario de veinticuatro fotogramas`() {
        // 1500 fotogramas con URI cortos caben de sobra: nada los rechaza por número.
        val frames = (0 until 1500).map { frame(it, sha = shaFor(it)) }
        val result = prepareAngleV2ShareAll(
            frames = frames,
            isStillCurrent = { true },
            prepareFrame = { frame -> AngleV2ShareAllFrame.Ready(uriFor(frame.ordinal)) },
            uriLength = String::length,
        )
        val ready = result as AngleV2ShareAllResult.Ready
        assertEquals("Deben entregarse todos los fotogramas, no una muestra.", 1500, ready.items.size)
        assertEquals(
            "El orden se conserva en un lote grande.",
            (0 until 1500).map(::uriFor),
            ready.items,
        )
    }

    @Test
    fun `el presupuesto de transporte es conservador y documentado`() {
        assertTrue(
            "Un envío de un solo fotograma cabe de sobra.",
            AngleV2ShareAllTransport.fits(1, ANGLE_FRAME_URI_CHARS),
        )
        assertTrue(
            "El presupuesto deja margen bajo el límite Binder de 1 MiB.",
            AngleV2ShareAllTransport.MAX_INTENT_BYTES < AngleV2ShareAllTransport.BINDER_LIMIT_BYTES,
        )
        assertEquals(
            "Cada entrada añade su sobre y los caracteres de su URI.",
            AngleV2ShareAllTransport.estimatedBytes(2, 100),
            AngleV2ShareAllTransport.estimatedBytes(0, 0) +
                2L * AngleV2ShareAllTransport.PER_ITEM_OVERHEAD_BYTES +
                100L * AngleV2ShareAllTransport.BYTES_PER_URI_CHAR,
        )
        assertFalse(
            "4096 URI de 100 caracteres no caben en un único envío seguro.",
            AngleV2ShareAllTransport.fits(4096, 4096 * 100),
        )
    }

    @Test
    fun `un uri vacio entregado por el publicador invalida el lote`() {
        val result = prepareAngleV2ShareAll(
            frames = listOf(frame(0)),
            isStillCurrent = { true },
            prepareFrame = { AngleV2ShareAllFrame.Ready("") },
            uriLength = String::length,
        )
        assertEquals(
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `un fallo inesperado del publicador no se convierte en imagen valida`() {
        val result = prepareAngleV2ShareAll<String>(
            frames = listOf(frame(0), frame(1)),
            isStillCurrent = { true },
            prepareFrame = { throw IllegalStateException("stager roto") },
            uriLength = String::length,
        )
        assertEquals(
            AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `el doble toque no inicia dos preparaciones y la puerta se libera siempre`() {
        val gate = AngleV2ShareSingleFlight()
        assertFalse("La puerta empieza abierta.", gate.isBusy)
        assertTrue("El primer toque entra.", gate.tryBegin())
        assertTrue("Mientras prepara, está ocupada.", gate.isBusy)
        assertFalse("El segundo toque no inicia otra preparación.", gate.tryBegin())
        gate.end()
        assertFalse("El `finally` libera la puerta.", gate.isBusy)
        assertTrue("Tras un fallo/cancelación se puede volver a intentar.", gate.tryBegin())
        gate.end()
    }

    @Test
    fun `cancelar el job durante una copia no prepara el resto ni declara el lote listo`() {
        // El publicador bloqueante NO lanza al cancelar el `Job`: sólo la comprobación inyectada
        // puede abortar el lote. Aquí se cancela un `Job` real desde dentro del publicador falso.
        val job = Job()
        val frames = listOf(frame(0), frame(1), frame(2))
        val prepared = mutableListOf<Int>()
        val result = runCatching {
            prepareAngleV2ShareAll(
                frames = frames,
                isStillCurrent = { true },
                prepareFrame = { frame ->
                    prepared += frame.ordinal
                    job.cancel()
                    AngleV2ShareAllFrame.Ready("u-${frame.ordinal}")
                },
                uriLength = String::length,
                checkCancellation = { job.ensureActive() },
            )
        }
        assertTrue(
            "La cancelación debe propagarse como `CancellationException`: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is CancellationException,
        )
        assertEquals(
            "Tras cancelar no se prepara ni se publica ninguna copia más.",
            listOf(0),
            prepared,
        )
    }

    @Test
    fun `cancelar en el ultimo fotograma tampoco produce un lote listo`() {
        val job = Job()
        val frames = listOf(frame(0), frame(1), frame(2))
        var prepared = 0
        val result = runCatching {
            prepareAngleV2ShareAll(
                frames = frames,
                isStillCurrent = { true },
                prepareFrame = {
                    prepared++
                    if (prepared == frames.size) job.cancel()
                    AngleV2ShareAllFrame.Ready("u-$prepared")
                },
                uriLength = String::length,
                checkCancellation = { job.ensureActive() },
            )
        }
        assertEquals("Las tres copias ya se habían preparado.", 3, prepared)
        assertTrue(
            "Un lote cancelado tras la última copia no puede ser `Ready`: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun `sin comprobacion inyectada el lote mantiene su comportamiento`() {
        // Contrato por defecto: el parámetro es un no-op y no cambia el resultado del lote.
        val result = prepareAngleV2ShareAll(
            frames = listOf(frame(0), frame(1)),
            isStillCurrent = { true },
            prepareFrame = { frame -> AngleV2ShareAllFrame.Ready("u-${frame.ordinal}") },
            uriLength = String::length,
        )
        assertEquals(
            listOf("u-0", "u-1"),
            (result as AngleV2ShareAllResult.Ready).items,
        )
    }

    @Test
    fun `la puerta de la UI se reclama antes de encolar el lote y bloquea el segundo toque`() {
        var frameShareBusy = false
        var shareAllBusy = false
        val order = mutableListOf<String>()
        val started = beginAngleV2ShareAllBatch(
            isFrameShareBusy = { frameShareBusy },
            isShareAllBusy = { shareAllBusy },
            claim = {
                frameShareBusy = true
                order += "claim"
            },
            startBatch = { order += if (frameShareBusy) "start-con-puerta-cerrada" else "start-sin-puerta" },
        )
        assertTrue("El primer toque arranca el lote.", started)
        assertEquals(
            "La puerta debe estar reclamada antes de que se encole el trabajo.",
            listOf("claim", "start-con-puerta-cerrada"),
            order,
        )

        // Segundo toque antes de la recomposición: la puerta ya está cerrada y no se encola nada.
        var secondStarted = false
        val second = beginAngleV2ShareAllBatch(
            isFrameShareBusy = { frameShareBusy },
            isShareAllBusy = { shareAllBusy },
            claim = { throw AssertionError("No debe reclamarse una puerta ya cerrada.") },
            startBatch = { secondStarted = true },
        )
        assertFalse("Un segundo toque no arranca otro lote.", second)
        assertFalse("Ni siquiera se encola el trabajo del segundo toque.", secondStarted)

        // El ViewModel también puede estar ocupado por otra vía: la puerta de UI no arranca nada.
        shareAllBusy = true
        var startedWhileViewModelBusy = false
        assertFalse(
            beginAngleV2ShareAllBatch(
                isFrameShareBusy = { frameShareBusy },
                isShareAllBusy = { shareAllBusy },
                claim = { frameShareBusy = true },
                startBatch = { startedWhileViewModelBusy = true },
            ),
        )
        assertFalse(startedWhileViewModelBusy)

        // Liberada en un `finally`: se puede volver a intentar.
        frameShareBusy = false
        shareAllBusy = false
        assertTrue(
            beginAngleV2ShareAllBatch(
                isFrameShareBusy = { frameShareBusy },
                isShareAllBusy = { shareAllBusy },
                claim = { frameShareBusy = true },
                startBatch = { order += "segundo-lote" },
            ),
        )
        assertEquals("segundo-lote", order.last())
    }

    private fun frame(
        ordinal: Int,
        mime: String = "image/png",
        sha: String = shaFor(ordinal),
    ): AngleV2SavedDetailFrame = AngleV2SavedDetailFrame(
        ordinal = ordinal,
        frameId = ordinal.toLong() + 1L,
        assignedFrameIndex = ordinal,
        reportedFrameIndex = null,
        presentationWidth = 64,
        presentationHeight = 48,
        rotationDegrees = 0,
        imageKey = "img-$ordinal",
        imageWidth = 64,
        imageHeight = 48,
        imageByteCount = 128L,
        imageSha256 = sha,
        imageMimeType = mime,
        marks = emptyList(),
    )

    private fun shaFor(ordinal: Int): String = ordinal.toString().padStart(64, '0')

    private fun uriFor(ordinal: Int): String =
        "content://com.openjump.app.fileprovider/angle_frame_shares/$ordinal.png"

    private companion object {
        /** Longitud de una URI real de copia compartida del `FileProvider`. */
        const val ANGLE_FRAME_URI_CHARS = 90

        val TOO_LONG_URI: String = "content://com.openjump.app.fileprovider/angle_frame_shares/" +
            "0".repeat(120) + ".png"
    }
}
