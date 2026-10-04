package com.openjump.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.PersonalRecord
import com.openjump.app.ui.components.DiscardConfirmDialog
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.JumpFlowProgress
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.titleResource
import com.openjump.app.video.AppSession
import com.openjump.app.video.BilateralFeedbackKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    onHome: () -> Unit,
    onCorrect: () -> Unit,
    onSaved: ((List<PersonalRecord>) -> Unit)? = null,
    viewModel: ResultViewModel = viewModel(factory = ResultViewModel.Factory),
) {
    val draft by AppSession.draft.collectAsState()
    val bilateral by AppSession.bilateral.collectAsState()
    val frameIndex by AppSession.frameIndex.collectAsState()
    val seriesAttempt by AppSession.seriesAttempt.collectAsState()
    val seriesTargetAttempts by AppSession.seriesTargetAttempts.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    // Dismissing only clears this flag; the draft stays intact for Correct/Save.
    // Dialog intent must not reappear for a different draft after process death.
    var confirmDiscard by remember { mutableStateOf(false) }
    // Saving/Saved never confirm: the save-navigation owns the exit, never a duplicate save.
    val discardEnabled = shouldConfirmResultDiscard(saveState)
    // Claim-once guard for the bilateral Accept: the synchronous claim below drops a
    // rapid second tap before recomposition disables the button. Scoped to the active
    // attempt/session so the next attempt recomposes with a fresh guard; the completed
    // bilateral route above is untouched.
    var bilateralAcceptInFlight by remember(draft?.sessionKey, bilateral?.sessionKey) {
        mutableStateOf(false)
    }
    val detectedFps = frameIndex?.detectedFps ?: 0
    val completedBilateral = remember(bilateral) { AppSession.bilateralComparison() }
    if (completedBilateral != null) {
        var showCompletedFeedback by rememberSaveable(completedBilateral.sessionKey) { mutableStateOf(false) }
        LaunchedEffect(completedBilateral.sessionKey) {
            if (AppSession.takeBilateralFeedback(completedBilateral.sessionKey)?.kind == BilateralFeedbackKind.COMPLETE) {
                showCompletedFeedback = true
            }
        }
        BilateralLiveResultScreen(
            comparison = completedBilateral,
            completionFeedback = showCompletedFeedback,
            onSaved = { records ->
                if (onSaved != null) onSaved(records) else { AppSession.reset(); onHome() }
            },
            onDiscard = { AppSession.reset(); onHome() },
        )
        return
    }
    val presentationAttempt = remember(draft, detectedFps) {
        runCatching {
            viewModel.presentLive(requireNotNull(draft) { "No hay una medición completa." }, detectedFps)
        }
    }
    val presentation = presentationAttempt.getOrNull()

    if (draft == null || presentation == null) {
        InvalidResultScreen(
            message = stringResource(R.string.result_invalid_body),
            onCorrect = onCorrect,
            onHome = {
                AppSession.reset()
                onHome()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = if (bilateral != null) stringResource(R.string.protocol_bilateral_title)
                    else stringResource(presentation.uiModel.protocolId.titleResource()),
                subtitle = if (bilateral != null && bilateral!!.currentSide != null) {
                    val side = stringResource(if (bilateral!!.currentSide == com.openjump.app.protocol.MeasurementSide.LEFT)
                        R.string.protocol_side_left else R.string.protocol_side_right)
                    if (bilateral!!.targetAttempts == 1) side else stringResource(
                        R.string.bilateral_progress, side, bilateral!!.currentSideAttempt, bilateral!!.targetAttempts,
                    )
                } else stringResource(
                    R.string.result_subtitle,
                    stringResource(presentation.uiModel.method.titleResource()),
                ),
                onNavigationClick = onCorrect,
                navigationContentDescription = stringResource(R.string.result_correct),
            )
        },
    ) { padding ->
        ResultContent(
            model = presentation.uiModel,
            modifier = Modifier.fillMaxSize().padding(padding),
            flowProgress = {
                JumpFlowProgress(currentStep = JumpFlowStep.RESULT)
            },
            onNoteChange = if (showMeasurementNotesForResult(bilateral != null)) {
                {
                    AppSession.updateMeasurementNote(it)
                    viewModel.clearError()
                }
            } else null,
            noteEnabled = bilateral == null && saveState != SaveState.Saving && saveState != SaveState.Saved,
            showNotes = showMeasurementNotesForResult(bilateral != null),
            actions = {
                when (val state = saveState) {
                    is SaveState.Error -> InfoBanner(
                        title = stringResource(R.string.result_save_error),
                        body = state.message,
                        warning = true,
                    )
                    SaveState.Saved -> InfoBanner(
                        stringResource(R.string.common_saved),
                        stringResource(R.string.result_saved_body),
                    )
                    SaveState.Idle, SaveState.Saving -> Unit
                }
                Button(
                    onClick = {
                        val activeBilateral = AppSession.bilateral.value
                        if (activeBilateral != null) {
                            if (!bilateralAcceptInFlight) {
                                bilateralAcceptInFlight = true
                                try {
                                    val advance = AppSession.acceptBilateralAttempt(draft!!.sessionKey, presentation.result, detectedFps)
                                    if (advance == com.openjump.app.video.BilateralAdvance.CONTINUE) onSaved?.invoke(emptyList())
                                } catch (error: Exception) {
                                    bilateralAcceptInFlight = false
                                    throw error
                                }
                            }
                        } else {
                            if (saveState is SaveState.Error) viewModel.clearError()
                            viewModel.save(draft!!, presentation.result, detectedFps, frameIndex = frameIndex) { records ->
                                if (onSaved != null) onSaved(records) else {
                                    AppSession.reset()
                                    onHome()
                                }
                            }
                        }
                    },
                    enabled = resultAcceptEnabled(
                        bilateralActive = bilateral != null,
                        bilateralAcceptInFlight = bilateralAcceptInFlight,
                        saveState = saveState,
                    ),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        if (saveState == SaveState.Saving) {
                            stringResource(R.string.common_saving)
                        } else if (bilateral != null) {
                            if (bilateral!!.attempts.size + 1 == bilateral!!.targetAttempts * 2)
                                stringResource(R.string.bilateral_view_results)
                            else stringResource(R.string.bilateral_accept_continue)
                        } else if (seriesAttempt < seriesTargetAttempts) {
                            stringResource(R.string.result_save_and_mark_jump, seriesAttempt + 1)
                        } else {
                            stringResource(R.string.result_save_measurement)
                        },
                    )
                }
                OutlinedButton(
                    onClick = onCorrect,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.result_correct_markers))
                }
                TextButton(
                    onClick = { confirmDiscard = true },
                    enabled = discardEnabled,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.common_discard))
                }
            },
        )
    }
    if (confirmDiscard && discardEnabled) {
        DiscardConfirmDialog(
            title = stringResource(R.string.result_discard_title),
            body = stringResource(R.string.result_discard_body),
            onConfirm = {
                confirmDiscard = false
                AppSession.reset()
                onHome()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * The non-bilateral Discard button asks for confirmation before [AppSession.reset].
 * Saving/Saved never confirm (the save-navigation owns the exit). The bilateral live
 * route, [InvalidResultScreen] and onCorrect keep their previous semantics.
 */
internal fun shouldConfirmResultDiscard(saveState: SaveState): Boolean =
    saveState != SaveState.Saving && saveState != SaveState.Saved

/**
 * Accept-button enabled state. The bilateral branch ignores [SaveState] (that flow
 * has no async save; [AppSession.acceptBilateralAttempt] is synchronous and
 * idempotent by sessionKey) and renders disabled once the in-flight claim is held,
 * so a rapid second tap cannot queue a duplicate accept. The unilateral branch
 * keeps its Saving/Saved guard unchanged.
 */
internal fun resultAcceptEnabled(
    bilateralActive: Boolean,
    bilateralAcceptInFlight: Boolean,
    saveState: SaveState,
): Boolean = if (bilateralActive) {
    !bilateralAcceptInFlight
} else {
    saveState != SaveState.Saving && saveState != SaveState.Saved
}

@Composable
private fun InvalidResultScreen(
    message: String,
    onCorrect: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.result_review_markers), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCorrect, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.result_correct))
        }
        TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.result_back_home))
        }
    }
}
