package com.openjump.app.ui.encoder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.StatusPill
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.theme.OpenJumpTypes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncoderMetricsHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.encoder_help_metrics_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.encoder_help_metrics_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            encoderMetricHelp.forEach { help ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(help.titleResource),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(help.shortDescriptionResource),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.encoder_help_quality_title), style = MaterialTheme.typography.titleMedium)
            QualityHelp(
                RepetitionQuality.VALID,
                stringResource(R.string.encoder_help_valid_description),
            )
            QualityHelp(
                RepetitionQuality.UNCERTAIN,
                stringResource(R.string.encoder_help_uncertain_description),
            )
            QualityHelp(
                RepetitionQuality.INVALID,
                stringResource(R.string.encoder_help_invalid_description),
            )
            EncoderInfoNote(
                title = stringResource(R.string.encoder_help_important),
                body = stringResource(R.string.encoder_help_loss_caveat),
            )
        }
    }
}

@Composable
private fun QualityHelp(quality: RepetitionQuality, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        QualityPill(quality)
        Text(description, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncoderMethodologyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.encoder_method_title),
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
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                stringResource(R.string.encoder_method_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EncoderInfoNote(
                title = stringResource(R.string.encoder_method_one_line_title),
                body = stringResource(R.string.encoder_method_one_line_body),
            )

            MethodStep(
                number = "1",
                title = stringResource(R.string.encoder_method_step_scale_title),
                body = stringResource(R.string.encoder_method_step_scale_body),
            )
            MethodStep(
                number = "2",
                title = stringResource(R.string.encoder_method_step_time_title),
                body = stringResource(R.string.encoder_method_step_time_body),
            )
            MethodStep(
                number = "3",
                title = stringResource(R.string.encoder_method_step_track_title),
                body = stringResource(R.string.encoder_method_step_track_body),
            )
            MethodStep(
                number = "4",
                title = stringResource(R.string.encoder_method_step_phases_title),
                body = stringResource(R.string.encoder_method_step_phases_body),
            )
            EncoderInfoNote(
                title = stringResource(R.string.encoder_method_units_note_title),
                body = stringResource(R.string.encoder_method_units_note),
            )

            FormulaSectionHeader(
                title = stringResource(R.string.encoder_method_conversion_title),
                body = stringResource(R.string.encoder_method_conversion_body),
            )
            FormulaReferenceGroup(
                formulas = formulasFor(EncoderFormulaGroup.CONVERSION),
                initiallyExpanded = null,
            )

            FormulaSectionHeader(
                title = stringResource(R.string.encoder_method_metrics_title),
                body = stringResource(R.string.encoder_method_metrics_body),
            )
            FormulaReferenceGroup(
                formulas = formulasFor(EncoderFormulaGroup.METRICS),
                initiallyExpanded = EncoderFormulaId.MCV,
            )

            FormulaSectionHeader(
                title = stringResource(R.string.encoder_method_technical_title),
                body = stringResource(R.string.encoder_method_technical_body),
            )
            FormulaReferenceGroup(
                formulas = formulasFor(EncoderFormulaGroup.TECHNICAL),
                initiallyExpanded = null,
            )

            EncoderInfoNote(
                title = stringResource(R.string.encoder_method_quality_title),
                body = stringResource(R.string.encoder_method_quality_body),
            )
            EncoderInfoNote(
                title = stringResource(R.string.encoder_method_limits_title),
                body = stringResource(R.string.encoder_method_limits_body),
                warning = true,
            )
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun MethodStep(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
        StatusPill(number, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = OpenJumpTypes.SectionTitle)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormulaSectionHeader(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = OpenJumpTypes.SectionTitle)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormulaReferenceGroup(
    formulas: List<EncoderFormulaSpec>,
    initiallyExpanded: EncoderFormulaId?,
) {
    var expandedName by rememberSaveable(formulas) { mutableStateOf(initiallyExpanded?.name) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            formulas.forEachIndexed { index, formula ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
                FormulaReferenceRow(
                    formula = formula,
                    expanded = expandedName == formula.id.name,
                    onToggle = {
                        expandedName = if (expandedName == formula.id.name) null else formula.id.name
                    },
                )
            }
        }
    }
}

@Composable
private fun FormulaReferenceRow(
    formula: EncoderFormulaSpec,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stackedHeader = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f
            if (stackedHeader) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, top = Spacing.md, end = Spacing.sm, bottom = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FormulaHeaderText(formula)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        formula.unit?.let { StatusPill(it) }
                        Spacer(Modifier.width(Spacing.sm))
                        FormulaToggleButton(formula, expanded, onToggle)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, top = Spacing.md, end = Spacing.sm, bottom = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FormulaHeaderText(formula, Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        formula.unit?.let { StatusPill(it) }
                        FormulaToggleButton(formula, expanded, onToggle)
                    }
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                HorizontalDivider()
                LatexFormulaImage(formula)
                Text(
                    stringResource(formula.explanationResource),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(formula.symbolsResource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FormulaHeaderText(formula: EncoderFormulaSpec, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(formula.titleResource), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(formula.summaryResource),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FormulaToggleButton(
    formula: EncoderFormulaSpec,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val formulaTitle = stringResource(formula.titleResource)
    val toggleDescription = stringResource(
        if (expanded) {
            R.string.encoder_formula_hide_description
        } else {
            R.string.encoder_formula_show_description
        },
        formulaTitle,
    )
    TextButton(
        onClick = onToggle,
        modifier = Modifier.semantics { contentDescription = toggleDescription },
    ) {
        Text(
            stringResource(
                if (expanded) R.string.encoder_formula_hide else R.string.encoder_formula_show,
            ),
        )
    }
}

@Composable
private fun LatexFormulaImage(formula: EncoderFormulaSpec) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        val fontScale = LocalDensity.current.fontScale
        val resource = formula.drawableFor(maxWidth.value, fontScale)
        val painter = painterResource(resource)
        val intrinsic = painter.intrinsicSize
        val aspectRatio = (intrinsic.width / intrinsic.height).takeIf { it.isFinite() && it > 0f } ?: 1f
        Image(
            painter = painter,
            contentDescription = stringResource(formula.spokenEquationResource),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .heightIn(min = 54.dp, max = 132.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            alignment = Alignment.Center,
        )
    }
}
