package com.openjump.app.ui.selector

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementValidationIssue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.ui.titleResource

/** Pure presentation contract shared by the selector's three series affordances. */
internal data class SeriesPresentation(
    val showProgress: Boolean,
    @StringRes val progressResource: Int?,
    val progressArgs: List<Int>,
    val progressTextOverride: String? = null,
    @StringRes val computeResource: Int,
    val computeArgs: List<Int>,
)

/** Builds the simple or multi-attempt presentation without exposing invalid ordinals. */
internal fun seriesPresentationFor(
    attempt: Int,
    targetAttempts: Int,
): SeriesPresentation {
    val isValidSeries = targetAttempts in 2..5 && attempt in 1..targetAttempts
    val args = if (isValidSeries) listOf(attempt, targetAttempts) else emptyList()
    return SeriesPresentation(
        showProgress = isValidSeries,
        progressResource = R.string.camera_jump_progress.takeIf { isValidSeries },
        progressArgs = args,
        computeResource = if (isValidSeries) R.string.selector_compute_jump else R.string.selector_compute_result,
        computeArgs = args,
    )
}

sealed interface JumpMarkingAction {
    data object Hidden : JumpMarkingAction

    data class Mark(
        val event: EventType,
        val enabled: Boolean,
    ) : JumpMarkingAction {
        @get:StringRes
        val hintResource: Int get() = event.hintResource()
    }

    data class Compute(
        val enabled: Boolean,
        val validationError: String?,
        val issue: MeasurementValidationIssue? = null,
    ) : JumpMarkingAction
}

private fun EventType.hintResource(): Int = when (this) {
    EventType.MOVEMENT_START -> R.string.selector_hint_movement_start
    EventType.INITIAL_CONTACT -> R.string.selector_hint_initial_contact
    EventType.TAKEOFF -> R.string.selector_hint_takeoff
    EventType.LANDING -> R.string.selector_hint_landing
}

/** Pure policy for the single contextual action shown by the temporal selector. */
object JumpMarkingActionPolicy {
    fun resolve(
        draft: MeasurementDraft?,
        markingEnabled: Boolean,
        isPlaying: Boolean,
        isCropFraming: Boolean,
    ): JumpMarkingAction {
        if (draft == null || draft.protocolId == ProtocolId.HORIZONTAL || draft.definition.requiredEvents.isEmpty()) {
            return JumpMarkingAction.Hidden
        }

        val nextEvent = draft.nextRequiredEvent()
        if (nextEvent != null) {
            return JumpMarkingAction.Mark(event = nextEvent, enabled = markingEnabled)
        }

        val validationError = draft.validationError()
        return JumpMarkingAction.Compute(
            enabled = validationError == null && !isPlaying && !isCropFraming,
            validationError = validationError,
            issue = draft.validationIssue(),
        )
    }
}

@Composable
internal fun JumpMarkingActionBar(
    action: JumpMarkingAction,
    seriesPresentation: SeriesPresentation,
    onMark: (EventType) -> Unit,
    onCompute: () -> Unit,
    contextText: String? = null,
) {
    if (action is JumpMarkingAction.Hidden) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (action) {
                is JumpMarkingAction.Mark -> {
                    val eventHint = stringResource(action.hintResource)
                    val activeState = listOfNotNull(contextText, eventHint).joinToString(". ")
                    contextText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        eventHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .semantics {
                                stateDescription = activeState
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    Button(
                        onClick = { onMark(action.event) },
                        enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.selector_mark_event,
                                stringResource(action.event.titleResource()).lowercase(),
                            ),
                        )
                    }
                }

                is JumpMarkingAction.Compute -> {
                    action.issue?.let { issue ->
                        Text(
                            text = stringResource(
                                when (issue) {
                                    MeasurementValidationIssue.MISSING_EVENTS -> R.string.selector_issue_missing_events
                                    MeasurementValidationIssue.EVENT_ORDER -> R.string.selector_issue_event_order
                                    MeasurementValidationIssue.SETUP -> R.string.selector_issue_invalid_setup
                                    else -> R.string.selector_issue_invalid_setup
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Button(
                        onClick = onCompute,
                        enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text(
                            stringResource(
                                seriesPresentation.computeResource,
                                *seriesPresentation.computeArgs.toTypedArray(),
                            ),
                        )
                    }
                }

                JumpMarkingAction.Hidden -> Unit
            }
        }
    }
}
