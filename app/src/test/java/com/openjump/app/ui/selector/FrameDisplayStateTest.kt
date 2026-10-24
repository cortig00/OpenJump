package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDisplayStateTest {

    @Test
    fun `no permite marcar hasta que el frame solicitado sea visible`() {
        val requested = FrameDisplayState().request(12)
        assertTrue(requested.isSeeking)
        assertFalse(requested.canMark)

        val rendered = requested.rendered(index = 12, ptsUs = 50_000L)
        assertFalse(rendered.isSeeking)
        assertTrue(rendered.canMark)
        assertEquals(50_000L, rendered.renderedPtsUs)
    }

    @Test
    fun `un frame obsoleto no habilita el marcador`() {
        val state = FrameDisplayState(renderedIndex = 10, renderedPtsUs = 41_667L)
            .request(20)
            .rendered(index = 11, ptsUs = 45_833L)

        assertTrue(state.isSeeking)
        assertFalse(state.canMark)
        assertEquals(20, state.requestedIndex)
        assertEquals(11, state.renderedIndex)
    }

    @Test
    fun `solo la ultima peticion puede quedar lista`() {
        val state = FrameDisplayState()
            .request(5)
            .request(25)
            .rendered(index = 5, ptsUs = 20_833L)
            .rendered(index = 25, ptsUs = 104_167L)

        assertEquals(25, state.requestedIndex)
        assertEquals(25, state.renderedIndex)
        assertTrue(state.canMark)
    }

    @Test
    fun `una rafaga conserva como autoridad el ultimo target`() {
        val requested = (1..100).fold(FrameDisplayState()) { state, index -> state.request(index) }
        val stale = requested.rendered(index = 73, ptsUs = 73_000L)
        val settled = stale.rendered(index = 100, ptsUs = 100_000L)

        assertEquals(100, stale.requestedIndex)
        assertFalse(stale.canMark)
        assertEquals(100, settled.renderedIndex)
        assertTrue(settled.canMark)
    }

    @Test
    fun `reproducir sigue el frame renderizado pero nunca habilita marcas`() {
        val state = FrameDisplayState(requestedIndex = 4)
            .rendered(index = 4, ptsUs = 40_000L)
            .playing()
            .rendered(index = 5, ptsUs = 50_000L)

        assertTrue(state.isPlaying)
        assertEquals(5, state.requestedIndex)
        assertEquals(5, state.renderedIndex)
        assertFalse(state.canMark)
    }

    @Test
    fun `pausar adopta el ultimo frame confirmado`() {
        val state = FrameDisplayState(requestedIndex = 4)
            .rendered(index = 4, ptsUs = 40_000L)
            .playing()
            .rendered(index = 8, ptsUs = 80_000L)
            .pauseAtRendered()

        assertEquals(ViewerTransportMode.PAUSED, state.mode)
        assertEquals(8, state.requestedIndex)
        assertTrue(state.canMark)
    }

    @Test
    fun `scrubbing no habilita marcas hasta finalizar`() {
        val scrubbing = FrameDisplayState()
            .request(12, ViewerTransportMode.SCRUBBING)
            .rendered(index = 12, ptsUs = 120_000L)

        assertEquals(ViewerTransportMode.SCRUBBING, scrubbing.mode)
        assertFalse(scrubbing.canMark)

        val settled = scrubbing.request(12).rendered(index = 12, ptsUs = 120_000L)
        assertEquals(ViewerTransportMode.PAUSED, settled.mode)
        assertTrue(settled.canMark)
    }
}
