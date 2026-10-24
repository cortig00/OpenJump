package com.openjump.app.settings

import org.junit.Assert.*
import org.junit.Test

class EncoderSettingsLoadTest {
    @Test fun `load validation requires finite nonnegative kilograms`() {
        for (value in listOf(0.0, 0.5, 80.25, 131.54)) assertTrue(EncoderSettings.isValidLoadKg(value))
        for (value in listOf(-0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(EncoderSettings.isValidLoadKg(value))
        }
    }

    @Test fun `missing and corrupted defaults fail safely to zero`() {
        for (value in listOf(null, "", "abc", "-1", "NaN", "Infinity", "80,25")) {
            assertEquals(0.0, EncoderSettings.decodeDefaultLoadKg(value), 0.0)
        }
    }

    @Test fun `decimal preference storage round trips all Double bits without float loss`() {
        for (value in listOf(0.0, 0.5, 80.25, 131.54, 45.359237, 0.12345678912345678)) {
            assertEquals(value.toRawBits(), EncoderSettings.decodeDefaultLoadKg(value.toString()).toRawBits())
        }
    }
}
