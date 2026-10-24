package com.openjump.app.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalPolynomialKinematicsTest {
    @Test
    fun `irregular timestamps recover cubic position and analytical derivative`() {
        val times = listOf(-120_000L, -70_000L, -10_000L, 0L, 45_000L, 110_000L, 170_000L)
        val observations = times.map { timeUs ->
            val t = timeUs / 1_000_000.0
            LocalPolynomialKinematics.Observation(
                timeUs = timeUs + 500_000L,
                point = MetricPoint(x = 1.0 + 2.0 * t + 3.0 * t * t + 4.0 * t * t * t, y = -2.0 + 0.5 * t),
                confidence = 1.0,
                block = 0,
            )
        }

        val estimate = LocalPolynomialKinematics.estimateAll(observations, 7)[3]
        assertNotNull(estimate)
        requireNotNull(estimate)

        assertEquals(1.0, estimate.point.x, 1e-8)
        assertEquals(2.0, estimate.velocityX, 1e-7)
        assertEquals(-2.0, estimate.point.y, 1e-8)
        assertEquals(0.5, estimate.velocityY, 1e-7)
    }

    @Test
    fun `low confidence outlier has limited influence`() {
        val observations = (-5..5).map { index ->
            val t = index / 100.0
            LocalPolynomialKinematics.Observation(
                timeUs = 1_000_000L + index * 10_000L,
                point = MetricPoint(0.0, t + if (index == 2) 1.0 else 0.0),
                confidence = if (index == 2) 0.01 else 1.0,
                block = 0,
            )
        }

        val estimate = requireNotNull(LocalPolynomialKinematics.estimateAll(observations, 11)[5])

        assertEquals(1.0, estimate.velocityY, 0.03)
    }

    @Test
    fun `exact windows preserve ties boundaries and noncontiguous block identities`() {
        val blocks = listOf(-7, 2, -7, 2, -7, -7, 2, -7, 2, -7, 2, -7, -7, 2, -7, 2, -7)
        val observations = blocks.mapIndexed { index, block ->
            LocalPolynomialKinematics.Observation(
                timeUs = 4_000_000L + index * 4_167L + index * index * 71L,
                point = MetricPoint(index * index * 0.017, kotlin.math.sin(index.toDouble()) * 0.2),
                confidence = if (index % 3 == 0) 0.03 else 0.83,
                block = block,
            )
        }
        listOf(5, 7, 11, 19, 101, Int.MAX_VALUE).forEach { assertLegacyBits(observations, it) }
    }

    @Test
    fun `exact windows preserve null and degenerate numerical outcomes`() {
        val confidence = listOf(-2.0, -0.0, 0.0, 0.01, 0.7, 1.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY)
        val observations = confidence.mapIndexed { index, value ->
            LocalPolynomialKinematics.Observation(
                timeUs = 1_000_000L + index * 17_071L,
                point = MetricPoint(if (index % 2 == 0) -0.0 else 0.0, index * index * 0.021),
                confidence = value,
                block = -1,
            )
        }
        for (window in listOf(-7, 0, 4, 5, 6, 7, 11, 19, Int.MAX_VALUE)) {
            assertLegacyBits(emptyList(), window)
            assertLegacyBits(observations.filter { !it.confidence.isNaN() }, window)
            assertLegacyBits(observations, window)
            assertLegacyBits(observations.take(3), window)
            assertLegacyBits(observations.map { it.copy(timeUs = 10L) }, window)
            assertLegacyBits(observations.map { it.copy(timeUs = it.timeUs % 2) }, window)
            assertLegacyBits(observations.map { it.copy(block = it.timeUs.toInt()) }, window)
        }
    }

    @Test
    fun `generated irregular windows match frozen implementation bit for bit`() {
        val random = kotlin.random.Random(0x51A)
        repeat(120) { case ->
            var timeUs = 2_000_000L
            val observations = List(random.nextInt(0, 100)) { index ->
                timeUs += random.nextLong(1L, 60_001L)
                LocalPolynomialKinematics.Observation(
                    timeUs = timeUs,
                    point = MetricPoint(random.nextDouble(-2.0, 2.0), random.nextDouble(-1.0, 1.0)),
                    confidence = random.nextDouble(-0.2, 1.3),
                    block = when (case % 4) {
                        0 -> 0
                        1 -> index / 13
                        2 -> index % 3 - 1
                        else -> random.nextInt(-3, 4)
                    },
                )
            }
            listOf(5, 7, 11, 19, 101).forEach { assertLegacyBits(observations, it) }
        }
    }

    @Test
    fun `different calls do not retain block or window state`() {
        val first = List(29) { index ->
            LocalPolynomialKinematics.Observation(
                3_000_000L + index * 4_173L,
                MetricPoint(index * 0.01, kotlin.math.cos(index.toDouble())),
                0.77,
                index % 2,
            )
        }
        val second = first.reversed().mapIndexed { index, item ->
            item.copy(timeUs = index * 23_113L, block = 44, confidence = 0.02)
        }
        assertLegacyBits(first, 19)
        assertLegacyBits(second, 7)
        assertLegacyBits(first, 5)
        assertLegacyBits(first, 19)
    }

    private fun assertLegacyBits(
        observations: List<LocalPolynomialKinematics.Observation>,
        windowSize: Int,
    ) {
        val expectedRun = runCatching { LegacyLocalPolynomialKinematicsReference.estimateAll(observations, windowSize) }
        val actualRun = runCatching { LocalPolynomialKinematics.estimateAll(observations, windowSize) }
        val expectedFailure = expectedRun.exceptionOrNull()
        if (expectedFailure != null) {
            // Invalid numeric inputs historically throw; preserve that rejection, not a new fallback.
            val actualFailure = actualRun.exceptionOrNull()
            assertNotNull("window=$windowSize must reject the same invalid input", actualFailure)
            assertEquals(expectedFailure.javaClass, requireNotNull(actualFailure).javaClass)
            assertEquals(expectedFailure.message, actualFailure.message)
            return
        }
        val expected = expectedRun.getOrThrow()
        val actual = actualRun.getOrThrow()
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            val e = expected[index]
            val a = actual[index]
            val context = "window=$windowSize center=$index block=${observations[index].block}"
            if (e == null) {
                assertEquals(context, null, a)
            } else {
                assertNotNull(context, a)
                requireNotNull(a)
                assertEquals("$context point.x", e.point.x.toRawBits(), a.point.x.toRawBits())
                assertEquals("$context point.y", e.point.y.toRawBits(), a.point.y.toRawBits())
                assertEquals("$context velocity.x", e.velocityX.toRawBits(), a.velocityX.toRawBits())
                assertEquals("$context velocity.y", e.velocityY.toRawBits(), a.velocityY.toRawBits())
                assertEquals("$context residual", e.residualM.toRawBits(), a.residualM.toRawBits())
            }
        }
    }

    @Test
    fun `window policy follows physical sampling rate`() {
        assertEquals(5, LocalPolynomialKinematics.windowForFps(30.0))
        assertEquals(7, LocalPolynomialKinematics.windowForFps(60.0))
        assertEquals(11, LocalPolynomialKinematics.windowForFps(120.0))
        assertEquals(19, LocalPolynomialKinematics.windowForFps(240.0))
    }
}
