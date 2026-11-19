package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la política y las transiciones extraídas del transporte del visor.
 *
 * Fijan el contrato determinista que [ViewerTransport] aplica sobre [FrameDisplayState];
 * no instancian ExoPlayer ni demuestran presentación física en la superficie.
 */
class ViewerTransportStateTest {

    private fun confirmed(index: Int, ptsUs: Long): FrameDisplayState =
        FrameDisplayState().request(index).rendered(index = index, ptsUs = ptsUs)

    @Test
    fun `solo un frame confirmado en pausa expone indice marcable`() {
        assertEquals(3, ViewerTransportState(display = confirmed(3, 30_000L)).confirmedFrameIndex)

        // Petición nueva: el render antiguo sigue visible pero ya no es marcable.
        assertNull(ViewerTransportState(display = confirmed(3, 30_000L).request(9)).confirmedFrameIndex)

        // Reproduciendo nunca hay frame confirmado, aunque haya render.
        assertNull(ViewerTransportState(display = confirmed(3, 30_000L).playing()).confirmedFrameIndex)

        // Sin ningún frame renderizado.
        assertNull(ViewerTransportState().confirmedFrameIndex)
    }

    @Test
    fun `la lectura agregada refleja display carga y error del transporte`() {
        val display = confirmed(1, 1_000L)
        val state = ViewerTransportState(display = display, loading = false, error = "error de vídeo")

        assertEquals(display, state.display)
        assertFalse(state.loading)
        assertEquals("error de vídeo", state.error)
    }

    @Test
    fun `el modo de una peticion de sesion sigue al scrub activo`() {
        assertEquals(
            ViewerTransportMode.SCRUBBING,
            viewerRequestMode(scrubbingActive = true, scrubEndRequested = false),
        )
        assertEquals(
            ViewerTransportMode.SEEKING,
            viewerRequestMode(scrubbingActive = true, scrubEndRequested = true),
        )
        assertEquals(
            ViewerTransportMode.SEEKING,
            viewerRequestMode(scrubbingActive = false, scrubEndRequested = false),
        )
    }

    @Test
    fun `la sesion no repite el frame que ya es el objetivo pendiente`() {
        val current = FrameDisplayState().request(7)

        assertTrue(viewerSessionRequestIsRedundant(7, current))
        assertFalse(viewerSessionRequestIsRedundant(8, current))
        assertTrue(viewerSessionRequestIsRedundant(0, FrameDisplayState()))
    }

    @Test
    fun `el salto relativo parte del frame visible al reproducir y del de sesion en pausa`() {
        val playing = FrameDisplayState(requestedIndex = 4)
            .rendered(index = 4, ptsUs = 40_000L)
            .playing()
            .rendered(index = 6, ptsUs = 60_000L)
        assertEquals(6, viewerStepBaseFrame(playing, sessionFrameIndex = 2))

        // Reproduciendo sin render todavía: manda la sesión.
        assertEquals(2, viewerStepBaseFrame(FrameDisplayState().request(2).playing(), sessionFrameIndex = 2))

        // En pausa manda la sesión aunque el render visible difiera.
        val paused = FrameDisplayState(requestedIndex = 9).rendered(index = 3, ptsUs = 30_000L)
        assertEquals(9, viewerStepBaseFrame(paused, sessionFrameIndex = 9))
    }

    @Test
    fun `la carga solo depende de que exista un frame renderizado`() {
        assertTrue(viewerLoadingAfterRequest(FrameDisplayState()))
        assertTrue(viewerLoadingAfterRequest(FrameDisplayState().request(5)))

        // Un frame anterior todavía visible mantiene la UI sin bloqueo de carga, igual que antes.
        assertFalse(viewerLoadingAfterRequest(confirmed(2, 20_000L).request(5)))
        assertFalse(viewerLoadingAfterRequest(confirmed(5, 50_000L)))
    }

    @Test
    fun `el cierre de scrub espera al target y luego pausa o sigue buscando`() {
        assertFalse(viewerScrubEndSettled(renderedIndex = null, targetIndex = 3))
        assertFalse(viewerScrubEndSettled(renderedIndex = 2, targetIndex = 3))
        assertTrue(viewerScrubEndSettled(renderedIndex = 3, targetIndex = 3))

        assertEquals(ViewerTransportMode.PAUSED, viewerScrubSettledMode(renderedIndex = 3, requestedIndex = 3))
        assertEquals(ViewerTransportMode.SEEKING, viewerScrubSettledMode(renderedIndex = 2, requestedIndex = 3))
        assertEquals(ViewerTransportMode.SEEKING, viewerScrubSettledMode(renderedIndex = null, requestedIndex = 3))
    }

    @Test
    fun `solo el frame pedido confirma el frame de apertura`() {
        // Salida alineada con lo pedido: es el frame que fija el desfase PTS→frame.
        assertTrue(viewerOpeningFrameConfirmed(confirmed(9, 90_000L)))

        // Una salida cualquiera NO confirma la apertura: si el desfase se fijó con otro frame, todos
        // los PTS quedan corridos y `canMark` no se cumpliría nunca. Es el falso éxito que evita la
        // reapertura del editor.
        val stale = confirmed(9, 90_000L).rendered(index = 3, ptsUs = 30_000L)
        assertEquals(ViewerTransportMode.PAUSED, stale.mode)
        assertFalse(stale.canMark)
        assertFalse(viewerOpeningFrameConfirmed(stale))

        // Sin ninguna salida todavía no hay confirmación.
        assertFalse(viewerOpeningFrameConfirmed(FrameDisplayState().request(9)))

        // En búsqueda o scrub la salida que coincide sí fija el desfase (rendered asienta en pausa
        // cuando el índice pedido y el renderizado coinciden).
        assertTrue(
            viewerOpeningFrameConfirmed(
                FrameDisplayState(requestedIndex = 9).rendered(index = 9, ptsUs = 90_000L),
            ),
        )
        assertTrue(
            viewerOpeningFrameConfirmed(
                confirmed(9, 90_000L).request(9, ViewerTransportMode.SCRUBBING)
                    .rendered(index = 9, ptsUs = 90_000L),
            ),
        )
    }

    @Test
    fun `la reapertura solo conserva una confirmacion del mismo frame con player vivo`() {
        // Frame pedido ya confirmado en pausa y player preparado: no hay nada que volver a presentar,
        // así que la confirmación real se conserva. Pedir un seek a esa misma posición sería un no-op
        // de ExoPlayer sin callback y dejaría el visor en SEEKING con los gestos deshabilitados.
        assertTrue(viewerReopenKeepsConfirmation(confirmed(0, 0L), requestedIndex = 0, playerUsable = true))
        assertTrue(viewerReopenKeepsConfirmation(confirmed(7, 70_000L), requestedIndex = 7, playerUsable = true))

        // Otro frame: hay que buscar y esperar la salida real del player.
        assertFalse(viewerReopenKeepsConfirmation(confirmed(0, 0L), requestedIndex = 7, playerUsable = true))

        // Sin confirmación (petición pendiente, reproducción o scrub) no se conserva nada.
        assertFalse(
            viewerReopenKeepsConfirmation(
                FrameDisplayState().request(7),
                requestedIndex = 7,
                playerUsable = true,
            ),
        )
        assertFalse(
            viewerReopenKeepsConfirmation(
                confirmed(7, 70_000L).playing(),
                requestedIndex = 7,
                playerUsable = true,
            ),
        )
        assertFalse(
            viewerReopenKeepsConfirmation(
                confirmed(7, 70_000L).request(7, ViewerTransportMode.SCRUBBING),
                requestedIndex = 7,
                playerUsable = true,
            ),
        )

        // Player detenido, suspendido o inexistente: la confirmación no demuestra nada presentado.
        assertFalse(viewerReopenKeepsConfirmation(confirmed(7, 70_000L), requestedIndex = 7, playerUsable = false))
    }

    @Test
    fun `una sesion de scrub no habilita marcadores hasta asentar el target`() {
        // Secuencia del transporte: beginScrubbing -> scrubToFrame -> render -> endScrubbing -> render.
        var state = confirmed(5, 50_000L)
        assertTrue(state.canMark)

        state = state.request(state.requestedIndex, ViewerTransportMode.SCRUBBING)
        assertFalse(state.canMark)

        state = state.request(6, ViewerTransportMode.SCRUBBING)
        state = state.rendered(index = 6, ptsUs = 60_000L)
        assertEquals(ViewerTransportMode.SCRUBBING, state.mode)
        assertFalse(state.canMark)

        state = state.request(6, ViewerTransportMode.SEEKING)
        assertFalse(state.canMark)

        state = state.rendered(index = 6, ptsUs = 60_000L)
        state = state.copy(mode = viewerScrubSettledMode(state.renderedIndex, state.requestedIndex))
        assertTrue(state.canMark)
        assertEquals(6, ViewerTransportState(display = state).confirmedFrameIndex)
    }
}
