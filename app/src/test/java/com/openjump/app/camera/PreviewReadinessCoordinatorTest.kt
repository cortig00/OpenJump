package com.openjump.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewReadinessCoordinatorTest {
    @Test
    fun `bind success waits for current streaming before ready exactly once`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val generation = coordinator.begin(listOf(240))
        val candidate = coordinator.bindCandidate(generation, 0)

        assertNotNull(candidate)
        assertEquals(PreviewReadinessCoordinator.State.WAITING_FOR_STREAM, coordinator.state)
        assertFalse(coordinator.onStreaming(generation, candidate!! + 1))
        assertEquals(PreviewReadinessCoordinator.State.WAITING_FOR_STREAM, coordinator.state)
        assertTrue(coordinator.onStreaming(generation, candidate))
        assertFalse(coordinator.onStreaming(generation, candidate))
        assertEquals(PreviewReadinessCoordinator.State.READY, coordinator.state)
    }

    @Test
    fun `stale stream and timeout cannot affect a newer generation or candidate`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val oldGeneration = coordinator.begin(listOf(120, 60))
        val oldCandidate = coordinator.bindCandidate(oldGeneration, 0)!!
        coordinator.cancel()

        val generation = coordinator.begin(listOf(240))
        val candidate = coordinator.bindCandidate(generation, 0)!!
        assertFalse(coordinator.onStreaming(oldGeneration, oldCandidate))
        assertEquals(
            PreviewReadinessCoordinator.Decision.Ignored,
            coordinator.onTimeout(oldGeneration, oldCandidate),
        )
        assertEquals(PreviewReadinessCoordinator.State.WAITING_FOR_STREAM, coordinator.state)
        assertTrue(coordinator.onStreaming(generation, candidate))
    }

    @Test
    fun `auto timeout advances through approved order including thirty`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val generation = coordinator.begin(listOf(120, 60, 240, 30))
        var index = 0
        var candidate = coordinator.bindCandidate(generation, index)!!

        listOf(60, 240, 30).forEach { expected ->
            val staleCandidate = candidate
            val decision = coordinator.onTimeout(generation, candidate)
            assertEquals(PreviewReadinessCoordinator.Decision.Retry(expected, index + 1), decision)
            assertEquals(
                PreviewReadinessCoordinator.Decision.Ignored,
                coordinator.onTimeout(generation, staleCandidate),
            )
            index++
            assertTrue(coordinator.onIdle(generation))
            candidate = coordinator.bindCandidate(generation, index)!!
        }
        assertEquals(PreviewReadinessCoordinator.Decision.Error, coordinator.onTimeout(generation, candidate))
        assertEquals(PreviewReadinessCoordinator.State.ERROR, coordinator.state)
    }

    @Test
    fun `unsupported first candidate advances to second without stuck binding`() {
        val coordinator = PreviewReadinessCoordinator<String>()
        val generation = coordinator.begin(listOf("unsupported", "supported"))

        assertTrue(coordinator.skipCandidate(generation, 0))
        val candidate = coordinator.bindCandidate(generation, 1)

        assertNotNull(candidate)
        assertEquals(PreviewReadinessCoordinator.State.WAITING_FOR_STREAM, coordinator.state)
        assertTrue(coordinator.onStreaming(generation, candidate!!))
        assertEquals(PreviewReadinessCoordinator.State.READY, coordinator.state)
    }

    @Test
    fun `stale streaming cannot ready a candidate without a fresh idle barrier`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val generation = coordinator.begin(listOf(1))
        assertTrue(coordinator.requireIdle(generation))
        assertFalse(coordinator.onStreaming(generation, 1L))
        assertEquals(null, coordinator.bindCandidate(generation, 0))
        assertTrue(coordinator.onIdle(generation))
        val candidate = coordinator.bindCandidate(generation, 0)!!
        assertTrue(coordinator.onStreaming(generation, candidate))
    }

    @Test
    fun `idle barrier timeout terminalizes instead of leaving idle binding`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val generation = coordinator.begin(listOf(1))
        assertTrue(coordinator.requireIdle(generation))

        assertEquals(PreviewReadinessCoordinator.Decision.Error, coordinator.onIdleTimeout(generation))
        assertEquals(PreviewReadinessCoordinator.State.ERROR, coordinator.state)
    }

    @Test
    fun `manual timeout retries exact-fps quality then errors without lower-fps fallback`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val generation = coordinator.begin(listOf(240, 240))
        val firstCandidate = coordinator.bindCandidate(generation, 0)!!

        assertEquals(
            PreviewReadinessCoordinator.Decision.Retry(240, 1),
            coordinator.onTimeout(generation, firstCandidate),
        )
        assertTrue(coordinator.onIdle(generation))
        val secondCandidate = coordinator.bindCandidate(generation, 1)!!
        assertEquals(PreviewReadinessCoordinator.Decision.Error, coordinator.onTimeout(generation, secondCandidate))
        assertEquals(PreviewReadinessCoordinator.Decision.Ignored, coordinator.onTimeout(generation, secondCandidate))
        assertEquals(PreviewReadinessCoordinator.State.ERROR, coordinator.state)
        assertEquals(null, coordinator.bindCandidate(generation, 2))
    }

    @Test
    fun `cancel and dispose invalidate callbacks exactly once`() {
        val coordinator = PreviewReadinessCoordinator<Int>()
        val generation = coordinator.begin(listOf(120))
        val candidate = coordinator.bindCandidate(generation, 0)!!
        coordinator.cancel()

        assertEquals(PreviewReadinessCoordinator.Decision.Ignored, coordinator.onTimeout(generation, candidate))
        assertEquals(PreviewReadinessCoordinator.Decision.Ignored, coordinator.onTimeout(generation, candidate))
        coordinator.dispose()
        assertFalse(coordinator.onStreaming(generation, candidate))
        assertEquals(PreviewReadinessCoordinator.State.DISPOSED, coordinator.state)
    }
}
