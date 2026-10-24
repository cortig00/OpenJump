package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderMetricKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderHelpContentTest {
    @Test
    fun `every encoder metric has one concise explanation`() {
        assertEquals(EncoderMetricKey.entries.toSet(), encoderMetricHelp.map { it.key }.toSet())
        assertEquals(EncoderMetricKey.entries.size, encoderMetricHelp.size)
        encoderMetricHelp.forEach { help ->
            assertTrue(help.titleResource != 0)
            assertTrue(help.shortDescriptionResource != 0)
        }
    }

    @Test
    fun `every metric resolves to its catalog entry`() {
        EncoderMetricKey.entries.forEach { key ->
            assertEquals(key, metricHelpFor(key).key)
        }
    }
}
