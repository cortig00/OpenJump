package com.openjump.app.ui.fvp

import androidx.annotation.StringRes
import com.openjump.app.R

/** Presentation only: never recalculates eligibility, quality, values or their source. */
internal enum class FvpNoticeGroup(@StringRes val titleRes: Int) {
    MODEL(R.string.fvp_notice_group_model),
    MEASUREMENT(R.string.fvp_notice_group_measurement),
    CAPTURE(R.string.fvp_notice_group_capture),
}

internal data class FvpNoticeGroupContent(
    val group: FvpNoticeGroup,
    val warnings: List<FvpUiWarning>,
)

internal data class FvpResultNoticePresentation(
    val critical: List<FvpUiWarning>,
    val brief: List<FvpUiWarning>,
    val groups: List<FvpNoticeGroupContent>,
)

/**
 * All incoming flags remain in the detail, exactly once. A short overview is NOT a new verdict.
 * In particular, snapshot conditionCount may differ from eligibility's count: do not infer a
 * quality cause from a displayed scalar. Only explain warnings the existing presenter supplies.
 */
internal fun fvpResultNotices(warnings: List<FvpUiWarning>): FvpResultNoticePresentation {
    val ordered = warnings.distinct().sortedWith(compareBy<FvpUiWarning> { it.noticePriority() }.thenBy { it.ordinal })
    val critical = ordered.filter { it.isCriticalNotice() }
    val brief = ordered.filterNot { it.isCriticalNotice() }
        .filterNot { it == FvpUiWarning.DIAGNOSTIC_ONLY && FvpUiWarning.FEW_POINTS in ordered }
        .take(2)
    return FvpResultNoticePresentation(
        critical = critical,
        brief = brief,
        groups = FvpNoticeGroup.entries.mapNotNull { group ->
            ordered.filter { it.noticeGroup() == group }.takeIf { it.isNotEmpty() }
                ?.let { FvpNoticeGroupContent(group, it) }
        },
    )
}

internal fun FvpUiWarning.isCriticalNotice(): Boolean = when (this) {
    FvpUiWarning.NON_PHYSICAL,
    FvpUiWarning.FPS_BLOCKED,
    FvpUiWarning.SNAPSHOT_RECALCULATED,
    FvpUiWarning.SNAPSHOT_UNAVAILABLE,
    FvpUiWarning.UNCLASSIFIED_WARNING,
    FvpUiWarning.R2_UNDEFINED -> true
    else -> false
}

internal fun FvpUiWarning.noticeGroup(): FvpNoticeGroup = when (this) {
    FvpUiWarning.FEW_POINTS, FvpUiWarning.DIAGNOSTIC_ONLY,
    FvpUiWarning.NARROW_VELOCITY_RANGE, FvpUiWarning.LOW_R2,
    FvpUiWarning.NON_PHYSICAL, FvpUiWarning.HIGH_EXTRAPOLATION,
    FvpUiWarning.R2_UNDEFINED, FvpUiWarning.SNAPSHOT_RECALCULATED,
    FvpUiWarning.SNAPSHOT_UNAVAILABLE, FvpUiWarning.UNCLASSIFIED_WARNING -> FvpNoticeGroup.MODEL
    FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE, FvpUiWarning.PUSH_OFF_MANUAL_SOURCE,
    FvpUiWarning.BODY_MASS_DECLARED, FvpUiWarning.BODY_MASS_UNKNOWN,
    FvpUiWarning.ADDED_LOAD_NOMINAL, FvpUiWarning.ADDED_LOAD_UNKNOWN -> FvpNoticeGroup.MEASUREMENT
    FvpUiWarning.FPS_BLOCKED, FvpUiWarning.TIMING_CONDITIONAL, FvpUiWarning.TIMING_UNKNOWN,
    FvpUiWarning.TIME_MAPPING_UNVERIFIED, FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN,
    FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE -> FvpNoticeGroup.CAPTURE
}

private fun FvpUiWarning.noticePriority(): Int = when (this) {
    FvpUiWarning.NON_PHYSICAL -> 0
    FvpUiWarning.UNCLASSIFIED_WARNING -> 1
    FvpUiWarning.SNAPSHOT_UNAVAILABLE -> 2
    FvpUiWarning.SNAPSHOT_RECALCULATED -> 3
    FvpUiWarning.FPS_BLOCKED -> 4
    FvpUiWarning.R2_UNDEFINED -> 5
    FvpUiWarning.FEW_POINTS -> 10
    FvpUiWarning.DIAGNOSTIC_ONLY -> 11
    FvpUiWarning.LOW_R2 -> 12
    FvpUiWarning.NARROW_VELOCITY_RANGE -> 13
    FvpUiWarning.TIMING_UNKNOWN -> 14
    FvpUiWarning.TIMING_CONDITIONAL -> 15
    FvpUiWarning.HIGH_EXTRAPOLATION -> 16
    FvpUiWarning.TIME_MAPPING_UNVERIFIED -> 17
    else -> 20 + ordinal
}

@StringRes
internal fun FvpUiWarning.noticeTitleRes(): Int = when (this) {
    FvpUiWarning.FEW_POINTS -> R.string.fvp_notice_few_points
    FvpUiWarning.DIAGNOSTIC_ONLY -> R.string.fvp_notice_diagnostic
    FvpUiWarning.NARROW_VELOCITY_RANGE -> R.string.fvp_notice_narrow_range
    FvpUiWarning.LOW_R2 -> R.string.fvp_notice_low_r2
    FvpUiWarning.NON_PHYSICAL -> R.string.fvp_notice_non_physical
    FvpUiWarning.HIGH_EXTRAPOLATION -> R.string.fvp_notice_extrapolation
    FvpUiWarning.FPS_BLOCKED -> R.string.fvp_notice_fps_blocked
    FvpUiWarning.TIMING_CONDITIONAL -> R.string.fvp_notice_timing_conditional
    FvpUiWarning.TIMING_UNKNOWN -> R.string.fvp_notice_timing_unknown
    FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE -> R.string.fvp_notice_push_off_range
    FvpUiWarning.SNAPSHOT_RECALCULATED -> R.string.fvp_notice_snapshot_changed
    FvpUiWarning.SNAPSHOT_UNAVAILABLE -> R.string.fvp_notice_snapshot_unavailable
    FvpUiWarning.R2_UNDEFINED -> R.string.fvp_notice_r2_undefined
    FvpUiWarning.PUSH_OFF_MANUAL_SOURCE -> R.string.fvp_notice_push_off_manual
    FvpUiWarning.UNCLASSIFIED_WARNING -> R.string.fvp_notice_unclassified
    FvpUiWarning.BODY_MASS_DECLARED -> R.string.fvp_notice_body_mass_declared
    FvpUiWarning.BODY_MASS_UNKNOWN -> R.string.fvp_notice_body_mass_unknown
    FvpUiWarning.ADDED_LOAD_NOMINAL -> R.string.fvp_notice_load_nominal
    FvpUiWarning.ADDED_LOAD_UNKNOWN -> R.string.fvp_notice_load_unknown
    FvpUiWarning.TIME_MAPPING_UNVERIFIED -> R.string.fvp_notice_time_mapping
    FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN -> R.string.fvp_notice_acquisition_unknown
    FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE -> R.string.fvp_notice_acquisition_composite
}
