package com.openjump.app.benchmark

import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.ProtocolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class JumpGroundTruthRegressionTest {
    private fun catalog(): JumpGoldenCatalog {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("golden_set.json"))
            .bufferedReader().use { it.readText() }
        return JumpGoldenCatalog.parse(text)
    }

    @Test
    fun catalogRejectsMissingUnilateralSide() {
        val invalid = requireNotNull(javaClass.classLoader?.getResourceAsStream("golden_set.json"))
            .bufferedReader().use { it.readText() }.replaceFirst("\"side\": \"LEFT\"", "\"side\": null")
        assertThrows(IllegalArgumentException::class.java) { JumpGoldenCatalog.parse(invalid) }
    }

    @Test
    fun catalogRejectsDropHeightOnNonDropProtocol() {
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("golden_set.json"))
            .bufferedReader().use { it.readText() }
        val invalid = source.replaceFirst("\"drop_height_cm\": null", "\"drop_height_cm\": 30.0")
        assertThrows(IllegalArgumentException::class.java) { JumpGoldenCatalog.parse(invalid) }
    }

    @Test
    fun catalogRejectsFlightTimeNotMatchingEvents() {
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("golden_set.json"))
            .bufferedReader().use { it.readText() }
        val invalid = source.replaceFirst("\"landing_us\": 2086000", "\"landing_us\": 2087000")
        assertTrue(invalid != source)
        assertThrows(IllegalArgumentException::class.java) { JumpGoldenCatalog.parse(invalid) }
    }

    @Test
    fun catalogRejectsDropContactTimeNotMatchingEvents() {
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("golden_set.json"))
            .bufferedReader().use { it.readText() }
        val invalid = source.replaceFirst("\"initial_contact_us\": 866000", "\"initial_contact_us\": 867000")
        assertTrue(invalid != source)
        assertThrows(IllegalArgumentException::class.java) { JumpGoldenCatalog.parse(invalid) }
    }

    @Test
    fun catalogRejectsRsiModNotMatchingHeightAndTimeToTakeoff() {
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("golden_set.json"))
            .bufferedReader().use { it.readText() }
        val invalid = source.replaceFirst("\"rsi_mod\": 0.8151851105848281", "\"rsi_mod\": 0.9")
        assertTrue(invalid != source)
        assertThrows(IllegalArgumentException::class.java) { JumpGoldenCatalog.parse(invalid) }
    }

    @Test
    fun validateAll22GoldenSetTrialsThroughProtocolCalculator() {
        val catalog = catalog()
        assertEquals(22, catalog.trials.size)
        for (trial in catalog.trials) {
            val draft = catalog.draft(trial)
            assertTrue("${trial.test_id}/${trial.trial_id} incomplete", draft.isComplete())
            assertEquals(null, draft.validationError())
            val result = ProtocolCalculator.compute(draft)
            val metrics = result.allMetrics.associateBy { it.key }
            val expected = trial.ballistic_expected
            fun check(key: MetricKey, value: Double?, tolerance: Double = 0.000001) {
                assertNotNull("${trial.test_id}/${trial.trial_id} missing $key", value)
                assertEquals("${trial.test_id}/${trial.trial_id} $key", value!!, metrics.getValue(key).value, tolerance)
            }
            val protocol = ProtocolId.fromStorageKey(trial.protocol)!!
            assertEquals("${trial.test_id}/${trial.trial_id} canonical metric set", JumpGoldenCatalog.releaseMetricKeys.getValue(protocol), metrics.keys)
            check(MetricKey.FLIGHT_TIME_MS, expected.flight_time_ms)
            check(MetricKey.HEIGHT_CM, expected.height_cm)
            when (trial.protocol) {
                ProtocolId.CMJ.storageKey, ProtocolId.UNILATERAL.storageKey -> {
                    check(MetricKey.TAKEOFF_VELOCITY_MPS, expected.takeoff_velocity_mps)
                    check(MetricKey.TIME_TO_TAKEOFF_MS, expected.time_to_takeoff_ms)
                    check(MetricKey.RSI_MOD, expected.rsi_mod)
                }
                ProtocolId.DROP_JUMP.storageKey -> {
                    check(MetricKey.CONTACT_TIME_MS, expected.contact_time_ms)
                    check(MetricKey.RSI, expected.rsi)
                }
                ProtocolId.SJ.storageKey -> check(MetricKey.TAKEOFF_VELOCITY_MPS, expected.takeoff_velocity_mps)
            }
        }
    }
}
