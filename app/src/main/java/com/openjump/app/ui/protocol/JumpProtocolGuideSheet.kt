package com.openjump.app.ui.protocol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpProtocolGuideSheet(
    guidance: ProtocolGuidance,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                stringResource(R.string.protocol_full_guide),
                modifier = Modifier.semantics { heading() },
                style = OpenJumpTypes.ScreenTitle,
            )
            GuideSection(R.string.protocol_guide_execution) {
                guidance.executionCues.forEachIndexed { index, cue ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(stringResource(cue), style = OpenJumpTypes.Body)
                    }
                }
            }
            GuideSection(R.string.protocol_guide_camera) { Text(stringResource(guidance.cameraCue)) }
            GuideSection(R.string.protocol_guide_marking) { Text(stringResource(guidance.markingSummary)) }
            GuideSection(R.string.protocol_guide_limits) {
                Text(stringResource(guidance.limits), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GuideSection(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(title), modifier = Modifier.semantics { heading() }, style = OpenJumpTypes.SectionTitle)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}
