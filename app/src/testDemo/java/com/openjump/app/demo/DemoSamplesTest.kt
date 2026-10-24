package com.openjump.app.demo

import com.openjump.app.fvp.FvpEngine
import com.openjump.app.math.ProtocolCalculator
import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.FvpWarning
import org.junit.Assert.*
import org.junit.Test

class DemoSamplesTest {
    private val now = 1_800_000_000_000L

    @Test fun sixProfilesHaveStableUniqueKeysAndExplicitSyntheticProvenance() {
        val profiles = DemoSamples.profiles(7L, now)
        assertEquals(6, profiles.size)
        assertEquals(6, profiles.map { it.sessionKey }.distinct().size)
        assertEquals(profiles, DemoSamples.profiles(7L, now))
        assertTrue(profiles.all { it.athleteId == 7L && it.dateTime <= now })
        assertTrue(profiles.all { it.notes!!.contains("SYNTHETIC") && it.pushOffSource == "MANUAL" })
    }

    @Test fun examplesNeverInventVideoOrEffectiveFps() {
        DemoSamples.profiles(7L, now).forEach { profile ->
            assertNull(profile.sourceFps)
            profile.conditions.flatMap { it.attempts }.forEach { attempt ->
                assertEquals("MANUAL", attempt.method)
                assertNull(attempt.sourceFps)
                assertNull(attempt.sourceAssessmentId)
            }
        }
        DemoSamples.jumps(7L, now).forEach { (draft, _) -> assertNull(draft.videoUri) }
    }

    @Test fun threeComparableSessionsHaveFiniteProfilesAndFourLoads() {
        DemoSamples.profiles(7L, now).take(3).forEach { draft ->
            assertEquals("SJ", draft.protocol)
            assertEquals(4, draft.conditions.size)
            val profile = FvpEngine.profile(points(draft), draft.bodyMassKg, null)
            assertTrue(profile.f0N.isFinite())
            assertTrue(profile.v0Mps.isFinite() && profile.v0Mps > 0)
            assertTrue(profile.pmaxW.isFinite() && profile.pmaxW > 0)
            assertFalse(profile.warnings.contains(FvpWarning.NON_PHYSICAL))
        }
    }

    @Test fun narrowAndTwoConditionExamplesExerciseQualityWarnings() {
        val samples = DemoSamples.profiles(7L, now)
        val narrow = samples.single { it.sessionKey.endsWith("narrow") }
        assertTrue(FvpEngine.profile(points(narrow), narrow.bodyMassKg, null)
            .warnings.contains(FvpWarning.NARROW_VELOCITY_RANGE))
        val diagnostic = samples.single { it.sessionKey.endsWith("diagnostic") }
        assertTrue(FvpEngine.profile(points(diagnostic), diagnostic.bodyMassKg, null)
            .warnings.contains(FvpWarning.FEW_POINTS))
    }

    @Test fun excludedTallerAttemptIsPreservedButNotSelected() {
        DemoSamples.profiles(7L, now).forEach { draft ->
            draft.conditions.forEach { condition ->
                val selected = condition.attempts.filter { it.valid }.maxOf { it.heightM }
                val excluded = condition.attempts.single { !it.valid }
                assertTrue(excluded.heightM > selected)
                assertFalse(excluded.exclusionReason.isNullOrBlank())
            }
        }
    }

    @Test fun ordinaryHistoryContainsTenValidSjAndCmjJumps() {
        val jumps = DemoSamples.jumps(7L, now)
        assertEquals(10, jumps.size)
        assertEquals(setOf("SJ", "CMJ"), jumps.map { it.first.protocolId.storageKey }.toSet())
        assertEquals(10, jumps.map { it.first.sessionKey }.distinct().size)
        jumps.forEach { (draft, date) ->
            assertNull(draft.validationError())
            assertTrue(draft.notes!!.contains("SYNTHETIC"))
            assertTrue(ProtocolCalculator.compute(draft).primaryMetric.value > 0)
            assertTrue(date < now)
        }
    }

    private fun points(draft: com.openjump.app.data.FvpDraft): List<FvpConditionPoint> =
        draft.conditions.map { condition ->
            val height = condition.attempts.filter { it.valid }.maxOf { it.heightM }
            val mass = draft.bodyMassKg + condition.addedLoadKg
            val point = FvpEngine.point(mass, height, draft.pushOffM)
            FvpConditionPoint(condition.conditionKey, mass, height, draft.pushOffM,
                point.forceN, point.velocityMps, point.powerW)
        }
}
