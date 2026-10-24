package com.openjump.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PresentationTimestampNormalizerTest {
    @Test
    fun `out of order presentation timestamps are sorted`() {
        assertArrayEquals(
            longArrayOf(10L, 20L, 30L),
            PresentationTimestampNormalizer.normalize(listOf(30L, 10L, 20L)),
        )
    }

    @Test
    fun `duplicate presentation timestamp is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PresentationTimestampNormalizer.normalize(listOf(10L, 20L, 20L))
        }
    }

    @Test
    fun `monotonic presentation timestamps remain unchanged`() {
        assertArrayEquals(
            longArrayOf(10L, 20L, 30L),
            PresentationTimestampNormalizer.normalize(listOf(10L, 20L, 30L)),
        )
    }
}
