package com.openjump.app.ui.fvp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.FvpSensitivityAvailability
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Page-independent reading context below the navigation. No action/slot depends on the selected
 * page: changing section must never change either this geometry or the navigation's position.
 * Ordinary causes live in Quality; critical restrictions remain explicit here on every page.
 */
@Composable
internal fun FvpResultInterpretation(
    model: FvpResultUiModel,
    canCompute: Boolean,
    staleQualityVerdicts: Boolean,
    modifier: Modifier = Modifier,
) {
    val notices = fvpResultNotices(model.warnings)
    val scroll = rememberSaveable(model.view, model.warnings, staleQualityVerdicts, saver = ScrollState.Saver) { ScrollState(0) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Multiple critical flags remain scrollable, never collapsed. Leave room for the page.
        val bounded = if (constraints.hasBoundedHeight) {
            Modifier.heightIn(max = maxHeight * 0.45f).verticalScroll(scroll)
        } else Modifier
        Column(
            modifier = Modifier.fillMaxWidth().then(bounded)
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                .testTag("fvp-context-header"),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            val label = stringResource(if (!canCompute) R.string.fvp_cannot_compute else when (model.quality) {
                FvpQualityState.DIAGNOSTIC -> R.string.fvp_notice_diagnostic
                FvpQualityState.INCOMPLETE -> R.string.fvp_quality_incomplete
                FvpQualityState.CONDITIONAL -> R.string.fvp_quality_conditional
                FvpQualityState.PROFILE -> R.string.fvp_quality_profile
            })
            Text(
                if (staleQualityVerdicts) stringResource(R.string.fvp_notice_stored_quality, label) else label,
                style = OpenJumpTypes.Label,
                fontWeight = FontWeight.SemiBold,
                color = if (!canCompute) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("fvp-context-quality"),
            )
            Text(
                stringResource(R.string.fvp_context_experimental),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-context-experimental"),
            )
            // Missing reasons are not evidence that a diagnostic/incomplete series is verified.
            if (notices.brief.isEmpty() && notices.critical.isEmpty() && !staleQualityVerdicts &&
                !(model.quality == FvpQualityState.PROFILE && canCompute)
            ) {
                Text(
                    stringResource(R.string.fvp_notice_reason_unavailable),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-interpretation-causes"),
                )
            }
            notices.critical.filterNot { staleQualityVerdicts && it == FvpUiWarning.SNAPSHOT_RECALCULATED }.forEach { warning ->
                Text(
                    fvpWarningText(warning),
                    style = OpenJumpTypes.Secondary,
                    color = if (!canCompute) MaterialTheme.colorScheme.error else OpenJumpTheme.colors.warning,
                    modifier = Modifier.testTag(if (warning == FvpUiWarning.NON_PHYSICAL) "fvp-context-non-physical" else "fvp-critical-${warning.name}"),
                )
            }
            if (staleQualityVerdicts) {
                Text(
                    stringResource(R.string.fvp_quality_stale_hint),
                    style = OpenJumpTypes.Secondary,
                    color = OpenJumpTheme.colors.warning,
                    modifier = Modifier.testTag("fvp-context-stale"),
                )
            }
            Text(
                stringResource(if (model.sensitivityAvailability == FvpSensitivityAvailability.AVAILABLE_GATED) R.string.fvp_notice_temporal_gated else R.string.fvp_notice_temporal_unavailable),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-sensitivity"),
            )
        }
    }
}

/** Only the presenter-supplied ordinary causes, immediately before their complete Quality detail. */
@Composable
internal fun FvpResultNoticeCauses(model: FvpResultUiModel) {
    val brief = fvpResultNotices(model.warnings).brief
    if (brief.isNotEmpty()) {
        Text(
            brief.map { warning -> stringResource(warning.noticeTitleRes()) }.joinToString(" · "),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("fvp-interpretation-causes"),
        )
    }
}

/** All flags are retained; only the explanation of a noncritical row is optional. */
@Composable
internal fun FvpResultNoticeGroups(model: FvpResultUiModel) {
    val groups = fvpResultNotices(model.warnings).groups
    if (groups.isEmpty()) {
        Text(stringResource(R.string.fvp_notice_no_extra), style = OpenJumpTypes.Secondary)
    }
    groups.forEach { content ->
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("fvp-notice-group-${content.group.name}"),
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(content.group.titleRes), style = OpenJumpTypes.Label, fontWeight = FontWeight.SemiBold)
                content.warnings.forEachIndexed { index, warning ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    key(warning.name, model.view) { FvpResultNoticeRow(warning = warning, view = model.view) }
                }
            }
        }
    }
}

@Composable
private fun FvpResultNoticeRow(warning: FvpUiWarning, view: FvpResultView) {
    var expanded by rememberSaveable(warning.name, view.name) { mutableStateOf(false) }
    val critical = warning.isCriticalNotice()
    val state = stringResource(if (expanded) R.string.result_temporal_sensitivity_expanded else R.string.result_temporal_sensitivity_collapsed)
    Column(Modifier.fillMaxWidth().testTag("fvp-notice-${warning.name}"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (!critical) Modifier.clickable(role = Role.Button) { expanded = !expanded }.semantics { stateDescription = state } else Modifier)
                .testTag("fvp-notice-toggle-${warning.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(warning.noticeTitleRes()), modifier = Modifier.weight(1f), style = OpenJumpTypes.Body)
            if (!critical) {
                Text(
                    stringResource(if (expanded) R.string.result_temporal_sensitivity_hide_details else R.string.result_temporal_sensitivity_show_details),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (critical || expanded) {
            Text(
                fvpWarningText(warning),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm).testTag("fvp-notice-detail-${warning.name}"),
            )
        }
    }
}
