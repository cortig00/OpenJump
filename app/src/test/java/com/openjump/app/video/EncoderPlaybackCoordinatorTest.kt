package com.openjump.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderPlaybackCoordinatorTest {
    @Test fun `one request is consumed once and metadata acknowledges without reseek`() {
        val coordinator = EncoderPlaybackCoordinator()
        val generation = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE)
        val request = coordinator.requestSeek(12_345L)
        assertEquals(request, coordinator.consumePendingSeek())
        assertNull(coordinator.consumePendingSeek())
        assertTrue(coordinator.onFrameRendered(generation, 12_345L))
        assertEquals(12_345L, coordinator.state.renderedSourcePtsUs)
        assertNull(coordinator.state.pendingSeek)
    }

    @Test fun `source and presentation conversion uses the indexed first PTS`() {
        val coordinator = EncoderPlaybackCoordinator()
        coordinator.bind(EncoderPlaybackAvailability.AVAILABLE, 10_000L)
        assertEquals(0L, coordinator.presentationPtsFromSource(10_000L))
        assertEquals(2_500L, coordinator.presentationPtsFromSource(12_500L))
        assertEquals(12_500L, coordinator.sourcePtsFromPresentation(2_500L))
    }

    @Test fun `unknown player preserves a seek until a compatible bind`() {
        val coordinator = EncoderPlaybackCoordinator()
        val request = coordinator.requestSeek(7_500L)
        assertEquals(request, coordinator.state.pendingSeek)
        val generation = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE, 1_000L)
        assertEquals(request, coordinator.consumePendingSeek())
        coordinator.onFrameRendered(generation, 7_500L)
        assertNull(coordinator.state.pendingSeek)
    }

    @Test fun `unbind keeps in flight seek for the next player generation`() {
        val coordinator = EncoderPlaybackCoordinator()
        val firstGeneration = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE)
        val request = coordinator.requestSeek(8_000L)
        coordinator.consumePendingSeek()
        assertTrue(coordinator.unbind(firstGeneration))
        val secondGeneration = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE)
        assertEquals(request, coordinator.consumePendingSeek())
        assertTrue(coordinator.onFrameRendered(secondGeneration, 8_000L))
    }

    @Test fun `unavailable gestures update selection but never leave a pending request`() {
        val coordinator = EncoderPlaybackCoordinator()
        coordinator.bind(EncoderPlaybackAvailability.UNAVAILABLE)
        coordinator.requestSeek(99L)
        assertEquals(99L, coordinator.state.selectedSourcePtsUs)
        assertNull(coordinator.state.pendingSeek)
        coordinator.bind(EncoderPlaybackAvailability.UNAVAILABLE)
        assertNull(coordinator.state.pendingSeek)
    }

    @Test fun `in flight request survives rebind and is reemitted once`() {
        val coordinator = EncoderPlaybackCoordinator()
        val firstGeneration = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE, 1_000L)
        val request = coordinator.requestSeek(1_250L)
        assertEquals(request, coordinator.consumePendingSeek())
        val secondGeneration = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE, 1_000L)
        assertEquals(request, coordinator.consumePendingSeek())
        assertNull(coordinator.consumePendingSeek())
        assertFalse(coordinator.onFrameRendered(firstGeneration, 1_250L))
        assertTrue(coordinator.onFrameRendered(secondGeneration, 1_250L))
        assertNull(coordinator.state.inFlightSeek)
    }

    @Test fun `sequential gestures remain distinct until each rendered acknowledgement`() {
        val coordinator = EncoderPlaybackCoordinator()
        val generation = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE)
        val first = coordinator.requestSeek(10L)
        val second = coordinator.requestSeek(20L)
        assertEquals(first, coordinator.consumePendingSeek())
        assertNull(coordinator.consumePendingSeek())
        coordinator.onFrameRendered(generation, 10L)
        assertEquals(second, coordinator.consumePendingSeek())
        coordinator.onFrameRendered(generation, 20L)
        assertNull(coordinator.state.pendingSeek)
    }

    @Test fun `stale binding callback cannot move cursor`() {
        val coordinator = EncoderPlaybackCoordinator()
        val old = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE)
        coordinator.onFrameRendered(old, 100L)
        val current = coordinator.bind(EncoderPlaybackAvailability.AVAILABLE)
        assertFalse(coordinator.onFrameRendered(old, 50L))
        assertNull(coordinator.state.renderedSourcePtsUs)
        assertTrue(coordinator.onFrameRendered(current, 200L))
        assertEquals(200L, coordinator.state.renderedSourcePtsUs)
    }
}
