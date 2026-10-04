package com.openjump.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.settings.AppLanguage
import com.openjump.app.settings.AppLanguageManager
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.settings.ThemeMode
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private enum class SettingsPicker { LANGUAGE, THEME, FPS }

private val exportFileDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

private fun exportFileTimestamp(): String = exportFileDateFormatter.format(Instant.now())

private data class SettingsOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onPrivacySelected: () -> Unit,
    onAboutSelected: () -> Unit,
    onUnitsSelected: () -> Unit,
    onOnboardingSelected: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenJumpApp
    val displayPreferences by app.displayPreferencesStore.state.collectAsState()
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val cameraSettings = remember(context) { CameraSettings(context) }
    val encoderSettings = remember(context) { EncoderSettings(context) }
    // Settings exposes product profiles only; CameraX validates the selected profile before READY.
    val availableFps = CameraSettings.PRODUCT_FPS
    var preferredFps by remember {
        mutableStateOf(CameraSettings.normalizePreferredFps(cameraSettings.preferredFps, availableFps))
    }
    var language by remember { mutableStateOf(AppLanguageManager.current()) }
    var picker by rememberSaveable { mutableStateOf<SettingsPicker?>(null) }
    var editPlateDiameter by rememberSaveable { mutableStateOf(false) }
    var plateDiameterCm by remember { mutableStateOf(encoderSettings.defaultPlateDiameterCm) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val exportViewModel: DataExportViewModel = viewModel(factory = DataExportViewModel.Factory)
    val exportState by exportViewModel.state.collectAsState()
    val backupViewModel: ManualBackupViewModel = viewModel(factory = ManualBackupViewModel.Factory)
    val backupState by backupViewModel.state.collectAsState()
    var backupPrivacy by rememberSaveable { mutableStateOf(false) }
    val backupBusy = backupState is ManualBackupState.Creating || backupState is ManualBackupState.Reading || backupState is ManualBackupState.Restoring
    BackHandler(enabled = backupState is ManualBackupState.Restoring) { }
    val backupCreateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupViewModel.create(uri)
    }
    val backupOpenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupViewModel.read(uri)
    }
    val exportFormat = (exportState as? DataExportState.Exporting)?.format
    var privacyFormat by rememberSaveable { mutableStateOf<DataExportFormat?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DataExportFormat.JSON.mimeType)) { uri ->
        if (uri != null) exportViewModel.export(DataExportFormat.JSON, uri)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DataExportFormat.CSV.mimeType)) { uri ->
        if (uri != null) exportViewModel.export(DataExportFormat.CSV, uri)
    }

    androidx.compose.runtime.LaunchedEffect(backupState) {
        when (val state = backupState) {
            is ManualBackupState.CreateSuccess -> { snackbarHostState.showSnackbar(context.getString(R.string.settings_backup_created)); backupViewModel.acknowledge() }
            is ManualBackupState.RestoreSuccess -> {
                val message = if (state.housekeepingFailed) {
                    context.getString(R.string.settings_backup_restored_warning)
                } else {
                    context.getString(R.string.settings_backup_restored)
                }
                snackbarHostState.showSnackbar(message)
                backupViewModel.acknowledge()
            }
            is ManualBackupState.Error -> {
                val message = when (state.kind) {
                    ManualBackupError.INVALID_CONTRACT -> R.string.settings_backup_error_invalid_contract
                    ManualBackupError.UNSUPPORTED_VERSION -> R.string.settings_backup_error_unsupported_version
                    ManualBackupError.CORRUPT_OR_INCOHERENT -> R.string.settings_backup_error_corrupt
                    ManualBackupError.TOO_LARGE -> R.string.settings_backup_error_too_large
                    ManualBackupError.READ_FAILED -> R.string.settings_backup_error_read
                    ManualBackupError.WRITE_FAILED -> R.string.settings_backup_error_write
                    ManualBackupError.PARTIAL -> R.string.settings_backup_error_partial
                    ManualBackupError.REVERTED -> R.string.settings_backup_error_reverted
                }
                snackbarHostState.showSnackbar(context.getString(message)); backupViewModel.acknowledge()
            }
            else -> Unit
        }
    }

    androidx.compose.runtime.LaunchedEffect(exportState) {
        when (val state = exportState) {
            is DataExportState.Success -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_export_success))
                exportViewModel.acknowledge()
            }
            is DataExportState.Error -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_export_error))
                exportViewModel.acknowledge()
            }
            else -> Unit
        }
    }

    val cameraSummary = if (preferredFps == CameraSettings.AUTO) {
        stringResource(R.string.settings_fps_auto)
    } else {
        stringResource(R.string.settings_fps_value, preferredFps)
    }
    val plateSummary = MeasurementFormatting.format(
        plateDiameterCm,
        MeasurementQuantity.SHORT_LENGTH_CM,
        unitSystem,
        locale,
    )

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.settings_title),
                showBrandLogo = true,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings-screen-list"),
            contentPadding = PaddingValues(
                horizontal = Spacing.screenHorizontal,
                vertical = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            item {
                SettingsGroup(stringResource(R.string.settings_general_section)) {
                    SettingsRow(
                        icon = R.drawable.ic_language,
                        title = stringResource(R.string.settings_app_language),
                        summary = stringResource(language.titleResource()),
                        onClick = { picker = SettingsPicker.LANGUAGE },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_units,
                        title = stringResource(R.string.settings_units_title),
                        summary = stringResource(displayPreferences.unitProfile.preset().titleResource()),
                        onClick = onUnitsSelected,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_palette,
                        title = stringResource(R.string.settings_theme_title),
                        summary = stringResource(displayPreferences.themeMode.titleResource()),
                        onClick = { picker = SettingsPicker.THEME },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_people,
                        title = stringResource(R.string.onboarding_settings_title),
                        summary = stringResource(R.string.onboarding_settings_summary),
                        onClick = onOnboardingSelected,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_camera_section)) {
                    SettingsRow(
                        icon = R.drawable.ic_videocam,
                        title = stringResource(R.string.settings_fps_title),
                        summary = cameraSummary,
                        supporting = stringResource(R.string.settings_fps_row_description),
                        onClick = { picker = SettingsPicker.FPS },
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_defaults_section)) {
                    SettingsRow(
                        icon = R.drawable.ic_encoder,
                        title = stringResource(R.string.settings_plate_short_title),
                        summary = plateSummary,
                        supporting = stringResource(R.string.settings_plate_row_description),
                        onClick = { editPlateDiameter = true },
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_data_section)) {
                    Text(
                        stringResource(R.string.settings_data_description),
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_export,
                        title = stringResource(R.string.settings_export_json),
                        summary = stringResource(R.string.settings_export_json_description),
                        enabled = exportFormat == null && !backupBusy,
                        onClick = { privacyFormat = DataExportFormat.JSON },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_export,
                        title = stringResource(R.string.settings_export_csv),
                        summary = stringResource(R.string.settings_export_csv_description),
                        enabled = exportFormat == null && !backupBusy,
                        onClick = { privacyFormat = DataExportFormat.CSV },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_export,
                        title = stringResource(R.string.settings_backup_create),
                        summary = stringResource(R.string.settings_backup_create_description),
                        enabled = exportFormat == null && !backupBusy,
                        onClick = { backupPrivacy = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_export,
                        title = stringResource(R.string.settings_backup_restore),
                        summary = stringResource(R.string.settings_backup_restore_description),
                        enabled = exportFormat == null && !backupBusy,
                        onClick = { backupOpenLauncher.launch(arrayOf("application/json", "text/json")) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = R.drawable.ic_privacy,
                        title = stringResource(R.string.settings_privacy_title),
                        summary = stringResource(R.string.settings_privacy_summary),
                        onClick = onPrivacySelected,
                    )
                    exportFormat?.let { format ->
                        val formatTitle = when (format) {
                            DataExportFormat.JSON -> stringResource(R.string.settings_export_json)
                            DataExportFormat.CSV -> stringResource(R.string.settings_export_csv)
                        }
                        Text(
                            text = stringResource(R.string.settings_export_in_progress, formatTitle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                    }
                    if (backupBusy) {
                        Text(
                            text = stringResource(R.string.settings_backup_in_progress),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm))
                    }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_about_section)) {
                    SettingsRow(
                        icon = R.drawable.ic_settings,
                        title = stringResource(R.string.settings_about_title),
                        summary = stringResource(R.string.settings_about_summary),
                        onClick = onAboutSelected,
                    )
                }
            }
        }
    }

    when (picker) {
        SettingsPicker.LANGUAGE -> SettingsSelectionSheet(
            title = stringResource(R.string.settings_app_language),
            description = stringResource(R.string.settings_language_description),
            options = AppLanguage.entries.map { option ->
                SettingsOption(option, stringResource(option.titleResource()))
            },
            selected = language,
            onDismiss = { picker = null },
            onSelected = { selected ->
                picker = null
                language = selected
                AppLanguageManager.apply(selected)
            },
        )
        SettingsPicker.THEME -> SettingsSelectionSheet(
            title = stringResource(R.string.settings_theme_title),
            description = stringResource(R.string.settings_theme_description),
            options = ThemeMode.entries.map { option ->
                SettingsOption(option, stringResource(option.titleResource()))
            },
            selected = displayPreferences.themeMode,
            onDismiss = { picker = null },
            onSelected = { selected ->
                app.displayPreferencesStore.setThemeMode(selected)
                picker = null
            },
        )
        SettingsPicker.FPS -> {
            val options = buildList {
                add(SettingsOption(CameraSettings.AUTO, stringResource(R.string.settings_fps_auto)))
                availableFps.forEach { fps ->
                    add(SettingsOption(fps, stringResource(R.string.settings_fps_value, fps)))
                }
            }
            SettingsSelectionSheet(
                title = stringResource(R.string.settings_fps_title),
                description = stringResource(R.string.settings_fps_description),
                options = options,
                selected = preferredFps,
                onDismiss = { picker = null },
                onSelected = { selected ->
                    preferredFps = selected
                    cameraSettings.preferredFps = selected
                    picker = null
                },
            )
        }
        null -> Unit
    }

    if (backupPrivacy) {
        AlertDialog(
            onDismissRequest = { if (!backupBusy) backupPrivacy = false },
            title = { Text(stringResource(R.string.settings_backup_privacy_title)) },
            text = { Text(stringResource(R.string.settings_backup_privacy_message)) },
            confirmButton = {
                TextButton(onClick = { backupPrivacy = false; backupCreateLauncher.launch("openjump_backup_${exportFileTimestamp()}.ojbackup.json") }) { Text(stringResource(R.string.settings_export_choose_location)) }
            },
            dismissButton = { TextButton(onClick = { backupPrivacy = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    (backupState as? ManualBackupState.ReadyToRestore)?.let { ready ->
        AlertDialog(
            onDismissRequest = { backupViewModel.cancelRestore() },
            title = { Text(stringResource(R.string.settings_backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_restore_confirm_message, ready.summary.athleteCount, ready.summary.assessmentCount, ready.summary.encoderSessionCount)) },
            confirmButton = { TextButton(onClick = { backupViewModel.confirmRestore() }) { Text(stringResource(R.string.settings_backup_restore_confirm)) } },
            dismissButton = { TextButton(onClick = { backupViewModel.cancelRestore() }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (backupState is ManualBackupState.Restoring) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_in_progress)) },
            confirmButton = {},
        )
    }

    if (privacyFormat != null) {
        val format = requireNotNull(privacyFormat)
        AlertDialog(
            onDismissRequest = { privacyFormat = null },
            title = { Text(stringResource(R.string.settings_export_privacy_title)) },
            text = { Text(stringResource(R.string.settings_export_privacy_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        privacyFormat = null
                        val fileName = "openjump_data_${exportFileTimestamp()}.${format.extension}"
                        when (format) {
                            DataExportFormat.JSON -> jsonLauncher.launch(fileName)
                            DataExportFormat.CSV -> csvLauncher.launch(fileName)
                        }
                    },
                ) { Text(stringResource(R.string.settings_export_choose_location)) }
            },
            dismissButton = {
                TextButton(onClick = { privacyFormat = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (editPlateDiameter) {
        PlateDiameterEditor(
            initialCanonicalCm = plateDiameterCm,
            unitSystem = unitSystem,
            onDismiss = { editPlateDiameter = false },
            onSave = { canonicalCm ->
                encoderSettings.defaultPlateDiameterCm = canonicalCm
                plateDiameterCm = canonicalCm
                editPlateDiameter = false
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_default_saved)) }
            },
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = OpenJumpTypes.SectionTitle)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val semanticsLabel = listOfNotNull(title, summary, supporting).joinToString(". ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = semanticsLabel
                stateDescription = summary
            }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = ShapeTokens.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.padding(Spacing.sm),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = OpenJumpTypes.Secondary,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsSelectionSheet(
    title: String,
    description: String,
    options: List<SettingsOption<T>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    description,
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(vertical = Spacing.md),
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            ) {
                items(options) { option ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .selectable(
                                selected = option.value == selected,
                                enabled = option.enabled,
                                role = Role.RadioButton,
                                onClick = { onSelected(option.value) },
                            ),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = {
                            RadioButton(
                                selected = option.value == selected,
                                onClick = null,
                                enabled = option.enabled,
                            )
                        },
                        headlineContent = { Text(option.title) },
                        supportingContent = option.description?.let { descriptionText ->
                            {
                                Text(
                                    descriptionText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

@Composable
private fun PlateDiameterEditor(
    initialCanonicalCm: Double,
    unitSystem: UnitProfile,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    val locale = currentAppLocale()
    val quantity = MeasurementQuantity.SHORT_LENGTH_CM
    val displayUnit = MeasurementFormatting.unit(quantity, unitSystem)
    val initialDisplayText = MeasurementFormatting.formatInputValue(initialCanonicalCm, quantity, unitSystem, locale)
    var input by rememberSaveable(initialCanonicalCm, stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(initialDisplayText, unitSystem, initialCanonicalCm))
    }
    LaunchedEffect(unitSystem) {
        input = UnitAwareNumericInputState.rebase(input, unitSystem, quantity, locale)
    }
    val text = input.text
    val canonicalCm = UnitAwareNumericInputState.canonical(input, quantity, locale, unitSystem)
    val inputInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(input, quantity, locale)
    val valid = !input.requiresReview && !inputInvalid && canonicalCm?.let(EncoderSettings::isValidPlateDiameterCm) == true
    val rangeDecimals = MeasurementFormatting.unit(quantity, unitSystem).decimals
    val minimum = MeasurementFormatting.format(
        EncoderSettings.MINIMUM_PLATE_DIAMETER_CM,
        quantity,
        unitSystem,
        locale,
        decimals = rangeDecimals,
    )
    val maximum = MeasurementFormatting.format(
        EncoderSettings.MAXIMUM_PLATE_DIAMETER_CM,
        quantity,
        unitSystem,
        locale,
        decimals = rangeDecimals,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_plate_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    stringResource(R.string.settings_plate_description),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { input = UnitAwareNumericInputState.edited(it, unitSystem, quantity, locale) },
                    label = { Text(stringResource(R.string.settings_plate_short_title)) },
                    suffix = { Text(displayUnit.symbol) },
                    singleLine = true,
                    isError = input.requiresReview || text.isNotBlank() && !valid,
                    supportingText = when {
                        input.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                        inputInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                        text.isNotBlank() && !valid -> { { Text(stringResource(R.string.settings_plate_range_error_values, minimum, maximum)) } }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(requireNotNull(canonicalCm)) },
                enabled = valid && canonicalCm != initialCanonicalCm,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
