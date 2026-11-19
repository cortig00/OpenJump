package com.openjump.app.ui.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regresión JVM de [multiFrameSessionKey], la identidad estable del guardado multi-fotograma.
 *
 * La propiedad que se prueba es la que sostiene la idempotencia del repositorio: la clave se deriva
 * **solo** de la clave base del análisis (acuñada una vez por la ruta del editor y conservada ante
 * recreación y muerte del proceso) y del intento del borrador. Reintentar el mismo borrador —también
 * en un `ViewModel` nuevo— debe producir exactamente la misma clave; un intento distinto, otra clave;
 * y una clave base distinta no puede confundirse con la de otro análisis aunque el vídeo sea el mismo.
 *
 * También se cubren las dos colisiones que sí importan en producto: la raíz mono-fotograma, que usa
 * la clave base sin sufijo, y la copia de recuperación (`#copy-N`), que deriva de la misma base.
 * Fixtures puramente sintéticos: no hay Room, vídeo, ficheros ni dispositivo.
 */
class MultiFrameSessionKeyTest {

    private val base = "content://openjump/video/angle-session-1"

    @Test
    fun `el intento cero usa la clave multi-frame original y nunca la clave base mono-fotograma`() {
        val key = multiFrameSessionKey(base, 0)

        assertEquals("$base#multi-frame", key)
        assertNotEquals("la raíz mono-fotograma usa la clave base sin sufijo", base, key)
        assertNotEquals(recoverySessionKey(base, 0), key)
    }

    @Test
    fun `cada intento posterior deriva un sufijo nuevo y nunca reutiliza el anterior`() {
        // El primer reintento es `-2`: el sufijo numera el intento, no el número de reintentos.
        assertEquals("$base#multi-frame-2", multiFrameSessionKey(base, 1))
        assertEquals("$base#multi-frame-3", multiFrameSessionKey(base, 2))
        assertEquals("$base#multi-frame-4", multiFrameSessionKey(base, 3))

        val keys = (0..5).map { multiFrameSessionKey(base, it) }
        assertEquals("dos intentos no pueden compartir clave", keys.size, keys.toSet().size)
    }

    @Test
    fun `un reintento del mismo borrador es idempotente tambien tras recrear el editor`() {
        // Mismos argumentos -> misma clave. Es lo que permite que reintentar (o que el intento lo
        // complete otra instancia) actualice la misma raíz en vez de duplicarla.
        val firstAttempt = multiFrameSessionKey(base, 1)
        val retryAfterRecreation = multiFrameSessionKey(String(base.toCharArray()), 1)

        assertEquals(firstAttempt, retryAfterRecreation)
        // Los intentos intermedios del mismo borrador tampoco se reordenan ni colisionan.
        assertNotEquals(multiFrameSessionKey(base, 0), firstAttempt)
        assertNotEquals(multiFrameSessionKey(base, 2), firstAttempt)
    }

    @Test
    fun `una clave base distinta nunca colisiona aunque comparta el prefijo`() {
        val otherAnalysisOfTheSameVideo = "content://openjump/video/angle-session-2"

        assertNotEquals(
            multiFrameSessionKey(base, 0),
            multiFrameSessionKey(otherAnalysisOfTheSameVideo, 0),
        )
        assertNotEquals(
            multiFrameSessionKey(base, 1),
            multiFrameSessionKey(otherAnalysisOfTheSameVideo, 1),
        )
    }

    @Test
    fun `rechaza una clave base vacia o un intento negativo`() {
        assertThrows(IllegalArgumentException::class.java) { multiFrameSessionKey("", 0) }
        assertThrows(IllegalArgumentException::class.java) { multiFrameSessionKey("   ", 0) }
        assertThrows(IllegalArgumentException::class.java) { multiFrameSessionKey(base, -1) }
    }
}
