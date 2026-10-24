package com.openjump.app.ui.fvp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.openjump.app.R
import com.openjump.app.ui.components.OpenJumpFlowProgress

/**
 * FVP owns its steps independently of JumpFlowStep. Names remain unchanged because they
 * travel in the recoverable payload (FvpDraftStateCodec). The visible positions follow the
 * current pages: Data, Measurement, Jumps. Live results retain the final position; saved
 * results do not display a wizard. Sharing the renderer does not change this navigation.
 */
enum class FvpFlowStep(val progressIndex: Int) {
    SETUP(0),
    MEASUREMENT(1),
    CONDITIONS(2),
    RESULT(2),
}

/** The exact Saltos timeline, adapted to FVP's three labels and existing page mapping. */
@Composable
fun FvpFlowProgress(
    currentStep: FvpFlowStep,
    modifier: Modifier = Modifier,
) {
    OpenJumpFlowProgress(
        labels = listOf(
            stringResource(R.string.fvp_step_data),
            stringResource(R.string.fvp_step_measurement),
            stringResource(R.string.fvp_step_saltos),
        ),
        currentStepIndex = currentStep.progressIndex,
        modifier = modifier.testTag("fvp-flow-progress"),
    )
}
