package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regresión JVM de [AngleEditorConfirmationCache], la caché con la que la pantalla decide cuándo
 * recalcular la confirmación del frame.
 *
 * El caso que motivó el cambio: durante una reapertura el ViewModel no confirma nada
 * (`reopenInFlight`), y al terminar puede conservar el frame ya confirmado **sin** emitir otra
 * salida del visor (un seek a la misma posición no emite callback). Una caché memorizada solo por
 * `displayState` guardaba el `null` de la reapertura y no volvía a preguntar nunca: la capa quedaba
 * deshabilitada, el panel accesible vacío y C imposible de colocar aunque el frame estuviera
 * confirmado en pantalla. La caché debe recalcular con la puerta de gestos aunque la salida del
 * visor sea exactamente la misma instancia.
 */
class AngleEditorConfirmationCacheTest {

    private val source = "content://openjump/angle-confirmation-cache"
    private val generation = 4L
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFFE53935.toInt())
    private val editor = AngleEditorState.initial(source, generation, defaults)
    private val pausedAtFrameZero = FrameDisplayState(
        requestedIndex = 0,
        renderedIndex = 0,
        renderedPtsUs = 0L,
        mode = ViewerTransportMode.PAUSED,
    )
    private val confirmation = AngleFrameConfirmation(
        frame = AngleFrameAnchor(sourceIdentity = source, frameIndex = 0, sourcePtsUs = 0L),
        generation = generation,
        paused = true,
    )

    @Test
    fun `no recalcula mientras no cambie ninguna de sus entradas observables`() {
        val cache = AngleEditorConfirmationCache()
        var computed = 0

        repeat(3) {
            assertEquals(
                confirmation,
                cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.Open, editor) {
                    computed += 1
                    confirmation
                },
            )
        }

        assertEquals("la misma clave no debe volver a derivar la confirmación", 1, computed)
    }

    @Test
    fun `recalcula cuando la reapertura termina sin una nueva salida del visor`() {
        val cache = AngleEditorConfirmationCache()
        var computed = 0

        // 1) La reapertura está en vuelo: el ViewModel no confirma nada (misma salida del visor).
        assertNull(
            cache.confirmation(
                pausedAtFrameZero,
                AngleEditorGestureGate.ReopenInFlight,
                editor,
            ) {
                computed += 1
                null
            },
        )

        // 2) La reapertura termina conservando el frame ya confirmado: `displayState` NO emite otra
        //    salida (misma instancia), solo cambia la puerta. La UI debe volver a preguntar.
        assertEquals(
            confirmation,
            cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.Open, editor) {
                computed += 1
                confirmation
            },
        )

        assertEquals(2, computed)
    }

    @Test
    fun `recalcula cuando el visor publica otra salida`() {
        val cache = AngleEditorConfirmationCache()
        var computed = 0

        cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.Open, editor) {
            computed += 1
            confirmation
        }
        val seeking = pausedAtFrameZero.request(1)
        cache.confirmation(seeking, AngleEditorGestureGate.Open, editor) {
            computed += 1
            null
        }

        assertEquals(2, computed)
    }

    @Test
    fun `recalcula cuando cambia el estado del editor`() {
        val cache = AngleEditorConfirmationCache()
        var computed = 0
        val otherEditor = AngleEditorState.initial(source, generation + 1L, defaults)

        cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.Open, editor) {
            computed += 1
            confirmation
        }
        cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.Open, otherEditor) {
            computed += 1
            confirmation
        }

        assertEquals(2, computed)
    }

    @Test
    fun `no fabrica una confirmacion que el viewmodel no da`() {
        val cache = AngleEditorConfirmationCache()

        assertNull(
            "sin confirmación del ViewModel la caché no inventa frame ni PTS",
            cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.Open, editor) { null },
        )
        assertNull(
            "un bloqueo por guardado tampoco se puede convertir en confirmación",
            cache.confirmation(pausedAtFrameZero, AngleEditorGestureGate.SaveInFlight, editor) { null },
        )
    }
}
