package com.openjump.app.ui.fvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FvpResultNoticePresentationTest {
    @Test fun allWarningsRemainExactlyOnceInGroupedDetail() {
        val input = FvpUiWarning.entries.toList()
        val shown = fvpResultNotices(input)
        val detail = shown.groups.flatMap { it.warnings }
        assertEquals(input.toSet(), detail.toSet())
        assertEquals(input.size, detail.size)
        assertEquals(FvpNoticeGroup.entries.toList(), shown.groups.map { it.group })
    }

    @Test fun everyWarningHasAnExplicitGroupAndLocalisedShortTitle() {
        FvpUiWarning.entries.forEach { warning ->
            assertTrue(warning.noticeTitleRes() != 0)
            assertTrue(warning.noticeGroup().titleRes != 0)
        }
    }

    @Test fun detailAndPrioritiesAreDeterministicRegardlessOfInputOrder() {
        val input = FvpUiWarning.entries.toList()
        assertEquals(fvpResultNotices(input), fvpResultNotices(input.reversed()))
    }

    @Test fun duplicateInputFlagsDoNotDuplicateRows() {
        val input = FvpUiWarning.entries.toList()
        assertEquals(fvpResultNotices(input), fvpResultNotices(input + input.reversed()))
    }

    @Test fun criticalInterpretationMessagesAreNeverLimitedToTwoOrHiddenInBrief() {
        val result = fvpResultNotices(FvpUiWarning.entries.toList())
        assertEquals(listOf(FvpUiWarning.NON_PHYSICAL, FvpUiWarning.UNCLASSIFIED_WARNING,
            FvpUiWarning.SNAPSHOT_UNAVAILABLE, FvpUiWarning.SNAPSHOT_RECALCULATED,
            FvpUiWarning.FPS_BLOCKED, FvpUiWarning.R2_UNDEFINED), result.critical)
        assertTrue(result.brief.none { it.isCriticalNotice() })
    }

    @Test fun warningHeavyLegacySeriesShowsActualSamplingAndExtrapolationNotHypotheticalCauses() {
        val input = listOf(FvpUiWarning.HIGH_EXTRAPOLATION, FvpUiWarning.TIMING_UNKNOWN,
            FvpUiWarning.PUSH_OFF_MANUAL_SOURCE, FvpUiWarning.BODY_MASS_UNKNOWN,
            FvpUiWarning.ADDED_LOAD_UNKNOWN, FvpUiWarning.TIME_MAPPING_UNVERIFIED,
            FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN)
        val result = fvpResultNotices(input)
        assertEquals(listOf(FvpUiWarning.TIMING_UNKNOWN, FvpUiWarning.HIGH_EXTRAPOLATION), result.brief)
        assertEquals(input.toSet(), result.groups.flatMap { it.warnings }.toSet())
        assertFalse(result.groups.flatMap { it.warnings }.contains(FvpUiWarning.FEW_POINTS))
    }

    @Test fun twoPointSeriesDoesNotRepeatTheSameDiagnosticCauseInItsBrief() {
        val result = fvpResultNotices(listOf(FvpUiWarning.DIAGNOSTIC_ONLY, FvpUiWarning.FEW_POINTS,
            FvpUiWarning.HIGH_EXTRAPOLATION, FvpUiWarning.TIMING_UNKNOWN))
        assertEquals(listOf(FvpUiWarning.FEW_POINTS, FvpUiWarning.TIMING_UNKNOWN), result.brief)
        assertTrue(FvpUiWarning.DIAGNOSTIC_ONLY in result.groups.flatMap { it.warnings })
    }

    @Test fun unknownWarningRemainsCriticalEvenIfOnlyProvenanceMessagesAccompanyIt() {
        val result = fvpResultNotices(listOf(FvpUiWarning.BODY_MASS_UNKNOWN, FvpUiWarning.UNCLASSIFIED_WARNING))
        assertEquals(listOf(FvpUiWarning.UNCLASSIFIED_WARNING), result.critical)
        assertEquals(listOf(FvpUiWarning.BODY_MASS_UNKNOWN), result.brief)
    }

    @Test fun presentationDoesNotResolveOrPromoteContradictoryStoredProvenance() {
        val input = listOf(FvpUiWarning.BODY_MASS_UNKNOWN, FvpUiWarning.BODY_MASS_DECLARED,
            FvpUiWarning.ADDED_LOAD_UNKNOWN, FvpUiWarning.ADDED_LOAD_NOMINAL)
        assertEquals(input.toSet(), fvpResultNotices(input).groups.flatMap { it.warnings }.toSet())
    }

    @Test fun snapshotWarningsAreInterpretationNotMeasurementIssues() {
        listOf(FvpUiWarning.SNAPSHOT_RECALCULATED, FvpUiWarning.SNAPSHOT_UNAVAILABLE).forEach {
            assertTrue(it.isCriticalNotice())
            assertEquals(FvpNoticeGroup.MODEL, it.noticeGroup())
        }
    }

    @Test fun provenanceIsSeparatedFromModelAndCaptureWithoutChangingFlags() {
        assertEquals(FvpNoticeGroup.MEASUREMENT, FvpUiWarning.PUSH_OFF_MANUAL_SOURCE.noticeGroup())
        assertEquals(FvpNoticeGroup.MEASUREMENT, FvpUiWarning.BODY_MASS_UNKNOWN.noticeGroup())
        assertEquals(FvpNoticeGroup.CAPTURE, FvpUiWarning.TIME_MAPPING_UNVERIFIED.noticeGroup())
        assertEquals(FvpNoticeGroup.CAPTURE, FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE.noticeGroup())
    }

    @Test fun emptyWarningsDoNotInventACauseOrAnEmptyGroup() {
        val result = fvpResultNotices(emptyList())
        assertTrue(result.critical.isEmpty())
        assertTrue(result.brief.isEmpty())
        assertTrue(result.groups.isEmpty())
    }

    @Test fun callerListIsNeverModified() {
        val input = mutableListOf(FvpUiWarning.TIMING_UNKNOWN, FvpUiWarning.FEW_POINTS)
        val original = input.toList()
        fvpResultNotices(input)
        assertEquals(original, input)
    }
}
