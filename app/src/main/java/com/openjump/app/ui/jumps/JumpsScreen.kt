package com.openjump.app.ui.jumps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.protocol.ProtocolAvailability
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.ui.components.JumpFlowProgress
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.descriptionResource
import com.openjump.app.ui.protocol.ProtocolGuidanceCatalog
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpsScreen(
    onBack: () -> Unit,
    onProtocolSelected: (ProtocolId) -> Unit,
) {
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.jumps_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                horizontal = Spacing.screenHorizontal,
                vertical = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    JumpFlowProgress(currentStep = JumpFlowStep.PREPARE)
                    Text(
                        stringResource(R.string.jumps_subtitle),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                TestSection(
                    title = stringResource(R.string.jumps_vertical_power),
                    protocols = listOf(
                        ProtocolId.CMJ,
                        ProtocolId.SJ,
                        ProtocolId.ABALAKOV,
                        ProtocolId.UNILATERAL,
                        ProtocolId.ASYMMETRY,
                    ),
                    onProtocolSelected = onProtocolSelected,
                )
            }
            item {
                TestSection(
                    title = stringResource(R.string.jumps_reactivity),
                    protocols = listOf(ProtocolId.DROP_JUMP),
                    onProtocolSelected = onProtocolSelected,
                )
            }
            item {
                TestSection(
                    title = stringResource(R.string.jumps_distance),
                    protocols = listOf(ProtocolId.HORIZONTAL),
                    onProtocolSelected = onProtocolSelected,
                )
            }
        }
    }
}

@Composable
private fun TestSection(
    title: String,
    protocols: List<ProtocolId>,
    onProtocolSelected: (ProtocolId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = OpenJumpTypes.SectionTitle, fontWeight = FontWeight.SemiBold)
        protocols.forEach { protocolId ->
            TestCard(protocolId, onProtocolSelected)
        }
    }
}

@Composable
private fun TestCard(
    protocolId: ProtocolId,
    onProtocolSelected: (ProtocolId) -> Unit,
) {
    val definition = ProtocolCatalog.find(protocolId)
    val available = definition.availability == ProtocolAvailability.AVAILABLE
    val cardModifier = if (available) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().semantics { disabled() }.alpha(0.62f)
    }
    if (available) {
        ElevatedCard(
            onClick = { onProtocolSelected(protocolId) },
            modifier = cardModifier,
            shape = ShapeTokens.medium,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            TestCardContent(protocolId, available)
        }
    } else {
        ElevatedCard(
            modifier = cardModifier,
            shape = ShapeTokens.medium,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            TestCardContent(protocolId, available)
        }
    }
}

@Composable
private fun TestCardContent(
    protocolId: ProtocolId,
    available: Boolean,
) {
    val illustrationDescription = stringResource(
        ProtocolGuidanceCatalog.forProtocol(protocolId)?.illustrationDescription
            ?: R.string.protocol_unavailable_body,
    )
    Column(
        modifier = Modifier.padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(protocolId.titleResource()),
                modifier = Modifier.weight(1f),
                style = OpenJumpTypes.SectionTitle,
            )
            if (available) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.common_coming_soon),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = OpenJumpTypes.Label,
                )
            }
        }
        Text(
            stringResource(protocolId.descriptionResource()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = OpenJumpTypes.Body,
        )
        JumpIllustration(
            protocolId = protocolId,
            contentDescription = illustrationDescription,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun JumpIllustration(
    protocolId: ProtocolId,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val illustrations = jumpIllustrations[protocolId].orEmpty()
    val darkMode = OpenJumpTheme.isDark
    val mediaHeight = if (illustrations.size > 1) 96.dp else 136.dp
    Row(
        modifier = modifier
            .height(mediaHeight)
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        illustrations.forEach { illustration ->
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = ShapeTokens.small,
                color = if (darkMode) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                tonalElevation = if (darkMode) 1.dp else 0.dp,
            ) {
                Image(
                    painter = painterResource(illustration),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** UI-only asset mapping; domain protocols and calculations remain untouched. */
private val jumpIllustrations: Map<ProtocolId, List<Int>> = ProtocolId.entries.associateWith { id ->
    when (id) {
        ProtocolId.CMJ -> listOf(R.drawable.cmj_male)
        ProtocolId.SJ -> listOf(R.drawable.squat_jump_male)
        ProtocolId.ABALAKOV -> listOf(R.drawable.abakalov_jump_male)
        ProtocolId.UNILATERAL -> listOf(
            R.drawable.unilateral_left_male,
            R.drawable.unilateral_right_male,
        )
        ProtocolId.DROP_JUMP -> listOf(R.drawable.drop_jump_male)
        ProtocolId.HORIZONTAL -> listOf(R.drawable.horizontal_jump_male)
        ProtocolId.ASYMMETRY -> listOf(
            R.drawable.unilateral_left_male,
            R.drawable.unilateral_right_male,
        )
        ProtocolId.REPEATED_10_5 -> emptyList()
    }
}
