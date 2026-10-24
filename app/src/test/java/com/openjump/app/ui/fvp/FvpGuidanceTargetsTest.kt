package com.openjump.app.ui.fvp

import com.openjump.app.fvp.FvpEligibilityProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prueba decisiones de presentacion; no sustituye elegibilidad ni valida biomecanica. */
class FvpGuidanceTargetsTest {
    @Test fun completeSetupHasNoPendingTarget() = assertNull(setupPending())

    @Test fun setupTargetsTheMissingMetadataFieldNotTheAlreadyCompletedOne() {
        assertEquals(FvpSetupPending.PUSH_OFF_METADATA, setupPending(metadata = true, landmark = true))
        assertEquals(FvpSetupPending.PUSH_OFF_PROCEDURE, setupPending(metadata = true, landmark = false))
    }

    @Test fun setupKeepsVisualOrderAndExpandsOptionalFieldsOnlyWhenNeeded() {
        assertEquals(FvpSetupPending.ATHLETE, setupPending(athlete = false, mass = true))
        assertEquals(FvpSetupPending.BODY_MASS, setupPending(mass = true, pushOff = true))
        assertEquals(FvpSetupPending.PUSH_OFF, setupPending(pushOff = true, metadata = true))
        assertEquals(FvpSetupPending.OPTIONAL_CROUCH, setupPending(crouch = true, context = true))
        assertEquals(FvpSetupPending.OPTIONAL_EXTENDED, setupPending(extended = true, context = true))
        assertEquals(FvpSetupPending.ACQUISITION_CONTEXT_KEY, setupPending(context = true))
        assertTrue(FvpSetupPending.OPTIONAL_CROUCH.expandsOptionalComponents)
        assertFalse(FvpSetupPending.PUSH_OFF_PROCEDURE.expandsOptionalComponents)
    }

    @Test fun anyInvalidHeightIncludingExcludedAttemptsHasItsOwnStableTarget() {
        val valid = condition("first", 0.0)
        val excluded = FvpAttemptDraft(draftKey = "excluded", heightM = 1.5, valid = false, exclusionReason = "posture")
        val second = condition("second", 20.0).copy(attempts = listOf(excluded))
        val pending = target(FvpEligibilityProblem.INVALID_HEIGHT, listOf(valid, second))
        assertEquals(FvpConditionFieldTarget.ATTEMPT_HEIGHT, pending.target)
        assertEquals(second.draftKey, pending.conditionDraftKey)
        assertEquals(excluded.draftKey, pending.attemptDraftKey)
        assertEquals(fvpAttemptAnchorKey(second.draftKey, excluded.draftKey, "height"), pending.anchorKey)
    }

    @Test fun exclusionReasonAndNoValidAttemptHaveDifferentDestinations() {
        val excluded = FvpAttemptDraft(heightM = 0.2, valid = false)
        val c = condition("first", 0.0).copy(attempts = listOf(excluded))
        assertEquals(FvpConditionFieldTarget.EXCLUSION_REASON, target(FvpEligibilityProblem.EXCLUSION_REASON_MISSING, listOf(c)).target)
        assertEquals(FvpConditionFieldTarget.ATTEMPT_LIST, target(FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT, listOf(c)).target)
    }

    @Test fun conditionKeysAndLoadsPointToTheActualOffendingCondition() {
        val a = condition("first", 0.0)
        val b = condition("second", 20.0)
        assertEquals(b.draftKey, target(FvpEligibilityProblem.INVALID_CONDITION_KEY, listOf(a, b.copy(conditionKey = ""))).conditionDraftKey)
        assertEquals(b.draftKey, target(FvpEligibilityProblem.INVALID_ADDED_LOAD, listOf(a, b.copy(addedLoadKg = Double.NaN))).conditionDraftKey)
        assertEquals(a.draftKey, target(FvpEligibilityProblem.DUPLICATE_CONDITION_KEY, listOf(a, b.copy(conditionKey = a.conditionKey))).conditionDraftKey)
        assertEquals(a.draftKey, target(FvpEligibilityProblem.DUPLICATE_LOAD, listOf(a, b.copy(addedLoadKg = a.addedLoadKg))).conditionDraftKey)
    }

    @Test fun pushOffTargetsPerConditionOnlyAndDoesNotInventACommonField() {
        val c = condition("first", 0.0)
        assertEquals(FvpConditionFieldTarget.PUSH_OFF, target(FvpEligibilityProblem.MISSING_PUSH_OFF, listOf(c)).target)
        val common = fvpConditionPendingFor(FvpEligibilityProblem.MISSING_PUSH_OFF, listOf(c), FvpPushOffUiMode.COMMON)
        assertEquals(FvpConditionFieldTarget.NONE, common.target)
        assertNull(common.anchorKey)
        assertFalse(common.hasReviewAnchor)
    }

    @Test fun aggregateProblemsNeverAccuseAnArbitraryField() {
        for (problem in listOf(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS, FvpEligibilityProblem.INVALID_BODY_MASS)) {
            val p = target(problem, listOf(condition("first", 0.0)))
            assertEquals(FvpConditionFieldTarget.NONE, p.target)
            assertNull(p.anchorKey)
        }
        assertEquals(FvpConditionFieldTarget.ADD_CONDITION, target(FvpEligibilityProblem.TOO_FEW_CONDITIONS, emptyList()).target)
    }

    @Test fun pendingOrderMatchesTheExistingBannerAndDoesNotAddNewGates() {
        val conditions = listOf(condition("first", 0.0).copy(attempts = listOf(FvpAttemptDraft())))
        val result = fvpConditionPendings(conditions, FvpPushOffUiMode.COMMON,
            listOf(FvpEligibilityProblem.TOO_FEW_CONDITIONS, FvpEligibilityProblem.INVALID_HEIGHT), true, false)
        assertEquals(listOf(FvpEligibilityProblem.INVALID_HEIGHT, FvpEligibilityProblem.TOO_FEW_CONDITIONS), result.map { it.problem })
        assertTrue(fvpConditionPendings(conditions, FvpPushOffUiMode.COMMON, emptyList(), false, false).isEmpty())
    }

    @Test fun targetsAreRecomputedAfterDeletingAnEarlierCondition() {
        val a = condition("first", 0.0)
        val b = condition("second", 20.0).copy(attempts = listOf(FvpAttemptDraft(heightM = null)))
        val before = target(FvpEligibilityProblem.INVALID_HEIGHT, listOf(a, b))
        assertEquals(before, target(FvpEligibilityProblem.INVALID_HEIGHT, listOf(b)))
        val afterDeletingInvalid = target(FvpEligibilityProblem.INVALID_HEIGHT, listOf(a))
        assertEquals(FvpConditionFieldTarget.NONE, afterDeletingInvalid.target)
        assertNull(afterDeletingInvalid.anchorKey)
    }

    @Test fun displayRangeChecksMatchDomainBoundariesWithoutChangingThem() {
        assertFalse(fvpHeightUsable(null))
        assertFalse(fvpHeightUsable(Double.NaN))
        assertFalse(fvpHeightUsable(0.0))
        assertTrue(fvpHeightUsable(1.2))
        assertFalse(fvpHeightUsable(1.2001))
        assertFalse(fvpPushOffUsable(0.05))
        assertTrue(fvpPushOffUsable(0.80))
        assertFalse(fvpPushOffUsable(0.8001))
        assertTrue(fvpAddedLoadUsable(0.0))
        assertFalse(fvpAddedLoadUsable(-0.1))
        assertFalse(fvpAddedLoadUsable(Double.POSITIVE_INFINITY))
    }

    private fun condition(key: String, load: Double) = FvpConditionDraft(
        conditionKey = key, addedLoadKg = load, attempts = listOf(FvpAttemptDraft(heightM = 0.3)),
    )
    private fun target(problem: FvpEligibilityProblem, conditions: List<FvpConditionDraft>) =
        fvpConditionPendingFor(problem, conditions, FvpPushOffUiMode.PER_CONDITION)

    private fun setupPending(
        athlete: Boolean = true, mass: Boolean = false, pushOff: Boolean = false,
        metadata: Boolean = false, landmark: Boolean = false, context: Boolean = false,
        crouch: Boolean = false, extended: Boolean = false,
    ) = fvpFirstSetupPending(
        athleteSelected = athlete, massNeedsReview = mass, bodyMassInvalid = false,
        pushOffNeedsReview = pushOff, pushOffOutOfDomain = false, pushOffMissing = false,
        pushOffMetadataMissing = metadata, landmarkMissing = landmark,
        acquisitionContextKeyMissing = context, crouchNeedsReview = crouch, extendedNeedsReview = extended,
    )
}
