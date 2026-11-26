package com.openjump.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.settings.EncoderDisplacementUnit
import com.openjump.app.settings.HorizontalDistanceUnit
import com.openjump.app.settings.JumpTimingUnit
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.SpeedUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.settings.DisplayPreferencesStore
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitPreferencesScreen(
    onBack: () -> Unit,
    preferencesStore: DisplayPreferencesStore? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? OpenJumpApp
    val store = preferencesStore ?: requireNotNull(app).displayPreferencesStore
    val preferences by store.state.collectAsStateWithLifecycle()
    val profile = preferences.unitProfile
    val locale = currentAppLocale()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.settings_units_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("unit-preferences-list"),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item {
                Text(stringResource(R.string.settings_units_description), style = OpenJumpTypes.Body)
            }
            item {
                val activePreset = profile.preset()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.settings_units_profile, stringResource(activePreset.titleResource())), style = OpenJumpTypes.SectionTitle)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        UnitPresetButton(
                            label = stringResource(R.string.settings_units_metric),
                            selected = activePreset == com.openjump.app.settings.UnitPreset.METRIC,
                            onClick = { store.setUnitProfile(UnitProfile.METRIC) },
                            modifier = Modifier.weight(1f),
                        )
                        UnitPresetButton(
                            label = stringResource(R.string.settings_units_us),
                            selected = activePreset == com.openjump.app.settings.UnitPreset.UNITED_STATES,
                            onClick = { store.setUnitProfile(UnitProfile.UNITED_STATES) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                UnitSection(stringResource(R.string.settings_units_short_length), ShortLengthUnit.entries.map { it.id }, profile.shortLength.id) { id ->
                    store.updateUnitProfile { it.copy(shortLength = ShortLengthUnit.decode(id)!!) }
                }
            }
            item {
                UnitSection(stringResource(R.string.settings_units_horizontal_distance), HorizontalDistanceUnit.entries.map { it.id }, profile.horizontalDistance.id) { id ->
                    store.updateUnitProfile { it.copy(horizontalDistance = HorizontalDistanceUnit.decode(id)!!) }
                }
            }
            item {
                UnitSection(stringResource(R.string.settings_units_encoder_displacement), EncoderDisplacementUnit.entries.map { it.id }, profile.encoderDisplacement.id) { id ->
                    store.updateUnitProfile { it.copy(encoderDisplacement = EncoderDisplacementUnit.decode(id)!!) }
                }
            }
            item {
                UnitSection(stringResource(R.string.settings_units_mass), MassUnit.entries.map { it.id }, profile.mass.id) { id ->
                    store.updateUnitProfile { it.copy(mass = MassUnit.decode(id)!!) }
                }
            }
            item {
                UnitSection(stringResource(R.string.settings_units_speed), SpeedUnit.entries.map { it.id }, profile.speed.id) { id ->
                    store.updateUnitProfile { it.copy(speed = SpeedUnit.decode(id)!!) }
                }
            }
            item {
                UnitSection(stringResource(R.string.settings_units_jump_timing), JumpTimingUnit.entries.map { it.id }, profile.jumpTiming.id) { id ->
                    store.updateUnitProfile { it.copy(jumpTiming = JumpTimingUnit.decode(id)!!) }
                }
            }
            item {
                Surface(
                    shape = ShapeTokens.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.settings_units_preview_title), style = OpenJumpTypes.SectionTitle)
                        PreviewRow(stringResource(R.string.settings_units_short_length), MeasurementFormatting.format(45.0, MeasurementQuantity.SHORT_LENGTH_CM, profile, locale))
                        PreviewRow(stringResource(R.string.settings_units_horizontal_distance), MeasurementFormatting.format(1.0, MeasurementQuantity.DISTANCE_M, profile, locale))
                        PreviewRow(stringResource(R.string.settings_units_encoder_displacement), MeasurementFormatting.format(0.25, MeasurementQuantity.DISPLACEMENT_M, profile, locale))
                        PreviewRow(stringResource(R.string.settings_units_mass), MeasurementFormatting.format(80.0, MeasurementQuantity.MASS_KG, profile, locale))
                        PreviewRow(stringResource(R.string.settings_units_speed), MeasurementFormatting.format(2.0, MeasurementQuantity.SPEED_MPS, profile, locale))
                        PreviewRow(stringResource(R.string.settings_units_jump_timing), MeasurementFormatting.format(500.0, MeasurementQuantity.DURATION_MS, profile, locale))
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = OpenJumpTypes.Body)
        Text(label, style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnitSection(title: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = OpenJumpTypes.SectionTitle)
        Surface(
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                options.forEach { option ->
                    val isSelected = option == selected
                    val selectionDescription = stringResource(if (isSelected) R.string.settings_units_selected else R.string.settings_units_not_selected)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelected(option) },
                        ).semantics { stateDescription = selectionDescription }
                            .testTag("unit-option-$option")
                            .padding(horizontal = Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(option, modifier = Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
