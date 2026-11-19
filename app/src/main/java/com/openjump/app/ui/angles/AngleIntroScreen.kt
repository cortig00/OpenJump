package com.openjump.app.ui.angles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.ui.components.AthleteSelector
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Presentational preparation screen for Angles (video-first, image angles only).
 *
 * The screen owns no state, database, media job or permission request: the route supplies the
 * active athletes, the current selection, whether starting is allowed and an optional error copy.
 * Record/import are plain callbacks for the caller's route policy; this file never opens a camera,
 * a picker or an analysis by itself. Navigation, the frozen athlete and the single-flight guard
 * live in the entry owner ([AngleEntryNavigation] + `AngleEntryHandoffViewModel`), which is the
 * authoritative source of truth: the disabled state here is a best-effort UI guard only.
 *
 * The measurement is an angle on the 2D image, so the visible warning states that perspective and
 * camera position affect the result. A valid, non-archived athlete is required: without one the
 * footer stays disabled and explains why instead of inventing a default athlete.
 *
 * [multiFrameEnabled] is the caller's real multi-frame/save gate. When it is OFF the copy must not
 * teach a
 * capture button or several measured frames in one analysis: the generic pause hint and the manual
 * save reminder of the legacy flow are used instead. The screen never flips that gate itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AngleIntroScreen(
    onBack: () -> Unit,
    onHowItWorks: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onAthleteSelected: (Long) -> Unit,
    activeAthletes: List<AthleteEntity> = emptyList(),
    athletesLoading: Boolean = false,
    selectedAthleteId: Long? = null,
    startEnabled: Boolean = false,
    errorMessage: String? = null,
    multiFrameEnabled: Boolean = false,
) {
    val selectedAthlete = activeAthletes.firstOrNull {
        it.id == selectedAthleteId && it.archivedAt == null
    }
    val canStart = startEnabled && !athletesLoading && selectedAthlete != null
    // Legible reason for a disabled footer: external error first, then the local blockers.
    val blockedReason = when {
        errorMessage != null -> errorMessage
        athletesLoading -> stringResource(R.string.athlete_selector_loading)
        selectedAthlete == null -> stringResource(R.string.angle_intro_select_athlete)
        !startEnabled -> stringResource(R.string.angle_intro_start_blocked)
        else -> null
    }

    Scaffold(
        modifier = Modifier.testTag("angle-intro-root"),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.angle_intro_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    TextButton(
                        onClick = onHowItWorks,
                        modifier = Modifier.testTag("angle-intro-help"),
                    ) {
                        Text(stringResource(R.string.encoder_how_it_works))
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                    .testTag("angle-intro-footer"),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (blockedReason != null) {
                        Text(
                            blockedReason,
                            modifier = Modifier.fillMaxWidth().testTag("angle-intro-pending-reason"),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        // Best-effort UI guard only. The authoritative start guard is the caller's
                        // single-flight `begin` in the entry ViewModel: a lambda captured from an
                        // older composition cannot start a second attempt.
                        onClick = { if (canStart) onRecord() },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("angle-intro-record"),
                    ) {
                        Text(stringResource(R.string.angle_intro_record))
                    }
                    OutlinedButton(
                        onClick = { if (canStart) onImport() },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("angle-intro-import"),
                    ) {
                        Text(stringResource(R.string.common_import_video))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag("angle-intro-content")
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(R.string.angle_intro_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    // With the multi-frame gate OFF this analysis is the legacy single-video one,
                    // so the short Home description is the truthful overview; nothing here enables
                    // the gate or hides the introduction.
                    stringResource(
                        if (multiFrameEnabled) R.string.angle_intro_overview
                        else R.string.home_angles_description,
                    ),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AngleIntroNote(
                title = stringResource(R.string.angle_intro_limit_title),
                body = stringResource(R.string.angle_intro_limit_body),
                modifier = Modifier.testTag("angle-intro-limit"),
            )
            AthleteSelector(
                athletes = activeAthletes,
                selectedAthleteId = selectedAthleteId,
                loading = athletesLoading,
                compact = true,
                softSurface = true,
                modifier = Modifier.testTag("angle-intro-athlete"),
                onAthleteSelected = onAthleteSelected,
            )
            if (selectedAthlete != null) {
                Text(
                    stringResource(R.string.angle_intro_history_helper, selectedAthlete.displayName),
                    modifier = Modifier.testTag("angle-intro-history-helper"),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    AngleIntroStep(
                        "1",
                        R.string.angle_intro_step_pick_title,
                        angleIntroPickBody(multiFrameEnabled),
                    )
                    AngleIntroStep(
                        "2",
                        R.string.angle_intro_step_capture_title,
                        stringResource(R.string.angle_intro_step_capture_body),
                    )
                    AngleIntroStep(
                        "3",
                        R.string.angle_intro_step_save_title,
                        angleIntroSaveBody(multiFrameEnabled),
                    )
                }
            }
            Text(
                stringResource(R.string.angle_intro_tips),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Help/methodology screen for Angles. Separate destination with its own back action; it never
 * starts a recording or an import, and it documents the same three steps plus the practical
 * limits (2D image, manual save). No calibration, load, height or formula is introduced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AngleHowItWorksScreen(
    onBack: () -> Unit,
    multiFrameEnabled: Boolean = false,
) {
    Scaffold(
        modifier = Modifier.testTag("angle-how-it-works-root"),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.angle_intro_help_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag("angle-how-it-works-content")
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                stringResource(R.string.angle_intro_help_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    AngleIntroStep(
                        "1",
                        R.string.angle_intro_step_pick_title,
                        angleIntroPickBody(multiFrameEnabled),
                    )
                    AngleIntroStep(
                        "2",
                        R.string.angle_intro_step_capture_title,
                        stringResource(R.string.angle_intro_step_capture_body),
                    )
                    AngleIntroStep(
                        "3",
                        R.string.angle_intro_step_save_title,
                        angleIntroSaveBody(multiFrameEnabled),
                    )
                }
            }
            Text(
                stringResource(R.string.angle_intro_help_vertex),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (multiFrameEnabled) {
                // Freezing/capturing several frames only exists with the multi-frame gate ON.
                Text(
                    stringResource(R.string.angle_intro_help_safe_capture),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.angle_intro_help_precision),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // With the gate OFF step 3 already IS the manual-save reminder, so the same paragraph
            // is not repeated; no capture button or multi-frame analysis is taught in that mode.
            AngleIntroNote(
                title = stringResource(R.string.angle_intro_limit_title),
                body = stringResource(R.string.angle_intro_limit_body),
            )
            Text(
                stringResource(R.string.angle_intro_help_tips),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AngleIntroStep(number: String, titleRes: Int, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = ShapeTokens.full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Step 1 body: with the multi-frame gate ON it teaches the real action (pause, press the localized
 * capture label, measure A-B-C afterwards). With the gate OFF no capture button or several frames
 * are promised: the existing generic pause hint is reused instead.
 */
@Composable
private fun angleIntroPickBody(multiFrameEnabled: Boolean): String = if (multiFrameEnabled) {
    stringResource(
        R.string.angle_intro_step_pick_body,
        stringResource(R.string.angle_workspace_capture),
    )
} else {
    stringResource(R.string.angle_workspace_capture_hint)
}

/**
 * Step 3 body: the multi-frame gate ON saves several measured frames by hand; the gate OFF keeps
 * the legacy single-video copy, whose reminder about the manual save is the precision help.
 */
@Composable
private fun angleIntroSaveBody(multiFrameEnabled: Boolean): String = if (multiFrameEnabled) {
    stringResource(R.string.angle_intro_step_save_body)
} else {
    stringResource(R.string.angle_intro_help_precision)
}

/** Small, visible limitation note; it does not carry the experimental encoder warning. */
@Composable
private fun AngleIntroNote(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = OpenJumpTheme.colors.warning,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenJumpTheme.colors.warning,
                )
            }
            Text(body, style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
