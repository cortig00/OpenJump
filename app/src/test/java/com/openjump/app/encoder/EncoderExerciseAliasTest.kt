package com.openjump.app.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderExerciseAliasTest {
    @Test
    fun `localized exercise names resolve to canonical values`() {
        val aliases = mapOf(
            "Press banca" to "BENCH_PRESS",
            "Bench press" to "BENCH_PRESS",
            "Développé couché" to "BENCH_PRESS",
            "Bankdrücken" to "BENCH_PRESS",
            "Sentadilla" to "SQUAT",
            "Kniebeugen" to "SQUAT",
            "Peso muerto" to "DEADLIFT",
            "Soulevé de terre" to "DEADLIFT",
            "Kreuzheben" to "DEADLIFT",
            "Dominada" to "PULL_UP",
            "Pull-up" to "PULL_UP",
            "Traction" to "PULL_UP",
            "Klimmzug" to "PULL_UP",
        )

        aliases.forEach { (visibleName, canonical) ->
            assertEquals(canonical, EncoderExercise.canonicalSearchValue(visibleName))
        }
    }

    @Test
    fun `alias resolution is case and accent insensitive but does not rewrite free text`() {
        assertEquals("BENCH_PRESS", EncoderExercise.canonicalSearchValue("  développé COUCHÉ "))
        assertEquals("PULL_UP", EncoderExercise.canonicalSearchValue("pull up"))
        assertEquals("DEADLIFT", EncoderExercise.canonicalSearchValue("soulevé"))
        assertNull(EncoderExercise.canonicalSearchValue("ana"))
    }
}
