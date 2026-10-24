package com.openjump.fvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM de seleccion de intentos (selectBestPerCondition).
 * Inputs SINTETICOS. Funcion pura: mayor heightM por condicion, determinista.
 */
class FvpSelectionTest {

    private fun attempt(
        cond: String,
        id: String,
        h: Double,
        body: Double = 80.0,
        load: Double = 0.0,
        hpo: Double = 0.40,
    ) = FvpAttempt(
        conditionId = cond,
        attemptId = id,
        bodyMassKg = body,
        addedLoadKg = load,
        jumpHeightM = h,
        pushOffM = hpo,
    )

    @Test
    fun selectsHighestJumpPerCondition() {
        val out = selectBestPerCondition(
            listOf(
                attempt("load-0", "a1", 0.32),
                attempt("load-0", "a2", 0.35),
                attempt("load-0", "a3", 0.30),
                attempt("load-20", "b1", 0.28, load = 20.0),
                attempt("load-20", "b2", 0.31, load = 20.0),
            ),
        )
        assertEquals(2, out.size)
        val c0 = out.first { it.conditionId == "load-0" }
        val c20 = out.first { it.conditionId == "load-20" }
        assertEquals(0.35, c0.heightM, 0.0)
        assertEquals(80.0, c0.totalMassKg, 0.0)
        assertEquals(0.31, c20.heightM, 0.0)
        assertEquals(100.0, c20.totalMassKg, 0.0)
    }

    @Test
    fun tie_keepsFirstInInputOrder() {
        val out = selectBestPerCondition(
            listOf(
                attempt("c", "first", 0.30),
                attempt("c", "second", 0.30),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(0.30, out[0].heightM, 0.0)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(selectBestPerCondition(emptyList()).isEmpty())
    }

    @Test
    fun output_sortedByConditionId_deterministic() {
        val out = selectBestPerCondition(
            listOf(
                attempt("z-load", "a", 0.20, load = 40.0),
                attempt("a-load", "b", 0.35),
            ),
        )
        assertEquals(listOf("a-load", "z-load"), out.map { it.conditionId })
    }

    @Test(expected = FvpInputError::class)
    fun negativeLoad_throws() {
        selectBestPerCondition(listOf(attempt("c", "a", 0.30, load = -5.0)))
    }

    @Test(expected = FvpInputError::class)
    fun zeroBodyMass_throws() {
        selectBestPerCondition(listOf(attempt("c", "a", 0.30, body = 0.0)))
    }

    @Test(expected = FvpInputError::class)
    fun zeroHeight_throws() {
        selectBestPerCondition(listOf(attempt("c", "a", 0.0)))
    }

    @Test(expected = FvpInputError::class)
    fun heightAboveLimit_throws() {
        selectBestPerCondition(listOf(attempt("c", "a", 1.5)))
    }

    @Test(expected = FvpInputError::class)
    fun pushOffAtBound_throws() {
        selectBestPerCondition(listOf(attempt("c", "a", 0.30, hpo = 0.05)))
    }

    @Test(expected = FvpInputError::class)
    fun nanHeight_throws() {
        selectBestPerCondition(listOf(attempt("c", "a", Double.NaN)))
    }

    @Test(expected = FvpInputError::class)
    fun blankConditionId_throws() {
        selectBestPerCondition(listOf(attempt("  ", "a", 0.30)))
    }
}
