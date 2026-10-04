package com.openjump.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.openjump.app.R
import com.openjump.app.ui.components.OpenJumpBrandBadge
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private fun installedMetadata(context: Context): InstalledAppMetadata {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return InstalledAppMetadata(
        versionName = packageInfo.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
    )
}

private fun diagnostics(context: Context, metadata: InstalledAppMetadata): DiagnosticMetadata =
    DiagnosticMetadata(
        appVersionName = metadata.versionName,
        appVersionCode = metadata.versionCode,
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        androidApi = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
    )

/** Starts only a canonical HTTPS destination and reports all launcher failures to the caller. */
internal fun launchApprovedUrl(context: Context, url: String): Boolean {
    if (!isAllowedExternalUrl(url)) return false
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun createProblemReportIntent(subject: String, body: String): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse(encodeProblemReportUri(subject, body))).apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

internal fun launchProblemReport(context: Context, subject: String, body: String): Boolean = try {
    context.startActivity(createProblemReportIntent(subject, body))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

internal fun copyText(context: Context, text: String): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    return runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.about_diagnostics_title), text))
    }.isSuccess
}

internal fun shareText(context: Context, text: String): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.about_share_chooser)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPrivacySelected: () -> Unit,
    onLicenseSelected: () -> Unit,
) {
    val context = LocalContext.current
    val metadata = remember(context) { installedMetadata(context) }
    val diagnosticMetadata = remember(context, metadata) { diagnostics(context, metadata) }
    val diagnosticLabels = DiagnosticLabels(
        app = stringResource(R.string.about_diagnostics_app),
        android = stringResource(R.string.about_diagnostics_android),
        device = stringResource(R.string.about_diagnostics_device),
        api = stringResource(R.string.about_diagnostics_api),
    )
    val diagnosticText = remember(diagnosticMetadata, diagnosticLabels) {
        formatDiagnostics(diagnosticMetadata, diagnosticLabels)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val externalError = stringResource(R.string.about_external_unavailable)
    val emailError = stringResource(R.string.about_email_unavailable)
    val reportSubject = stringResource(R.string.about_email_subject)
    val reportBody = formatProblemReportBody(
        problemLabel = stringResource(R.string.about_email_problem),
        stepsLabel = stringResource(R.string.about_email_steps),
        expectedLabel = stringResource(R.string.about_email_expected),
        actualLabel = stringResource(R.string.about_email_actual),
        diagnosticsLabel = stringResource(R.string.about_email_diagnostics),
        diagnostics = diagnosticText,
    )

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.about_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("about-screen-list"),
            contentPadding = PaddingValues(
                horizontal = Spacing.screenHorizontal,
                vertical = Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OpenJumpBrandBadge(
                        modifier = Modifier.testTag("about-logo"),
                        size = 80.dp,
                        iconSize = 56.dp,
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.semantics { heading() },
                        style = OpenJumpTypes.ScreenTitle,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.about_version, formatVersion(metadata)),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                AboutSection(
                    title = stringResource(R.string.about_product_heading),
                    body = stringResource(R.string.about_product_body),
                    modifier = Modifier.testTag("about-product-section"),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeTokens.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        AboutRow(
                            title = stringResource(R.string.about_buy_me_a_coffee),
                            summary = stringResource(R.string.about_support_body),
                            iconRes = R.drawable.ic_buy_me_a_coffee,
                            external = true,
                            testTag = "about-row-coffee",
                            onClick = {
                                if (!launchApprovedUrl(context, COFFEE_URL)) {
                                    scope.launch { snackbarHostState.showSnackbar(externalError) }
                                }
                            },
                        )
                    }
                }
            }
            item {
                AboutGroup(title = stringResource(R.string.about_project_heading)) {
                    AboutRow(
                        title = stringResource(R.string.about_open_source),
                        summary = stringResource(R.string.about_source_summary),
                        iconRes = R.drawable.ic_code,
                        external = true,
                        testTag = "about-row-source",
                    ) {
                        if (!launchApprovedUrl(context, SOURCE_URL)) {
                            scope.launch { snackbarHostState.showSnackbar(externalError) }
                        }
                    }
                    AboutDivider()
                    AboutRow(
                        title = stringResource(R.string.about_email_title),
                        summary = stringResource(R.string.about_email_summary),
                        iconRes = R.drawable.ic_email,
                        external = true,
                        testTag = "about-row-email",
                    ) {
                        if (!launchProblemReport(context, reportSubject, reportBody)) {
                            scope.launch { snackbarHostState.showSnackbar(emailError) }
                        }
                    }
                    AboutDivider()
                    AboutRow(
                        title = stringResource(R.string.about_open_issues),
                        summary = stringResource(R.string.about_github_summary),
                        iconRes = R.drawable.ic_bug_report,
                        external = true,
                        testTag = "about-row-github-issues",
                    ) {
                        if (!launchApprovedUrl(context, ISSUES_URL)) {
                            scope.launch { snackbarHostState.showSnackbar(externalError) }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = stringResource(R.string.about_privacy_legal_heading),
                        modifier = Modifier.semantics { heading() },
                        style = OpenJumpTypes.SectionTitle,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeTokens.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column {
                            AboutRow(
                                title = stringResource(R.string.about_open_privacy),
                                summary = stringResource(R.string.about_privacy_body),
                                iconRes = R.drawable.ic_privacy,
                                external = false,
                                testTag = "about-row-privacy",
                                onClick = onPrivacySelected,
                            )
                            AboutDivider()
                            AboutRow(
                                title = stringResource(R.string.about_open_public_privacy),
                                summary = stringResource(R.string.about_public_privacy_summary),
                                iconRes = R.drawable.ic_privacy,
                                external = true,
                                testTag = "about-row-public-privacy",
                            ) {
                                if (!launchApprovedUrl(context, PUBLIC_PRIVACY_URL)) {
                                    scope.launch { snackbarHostState.showSnackbar(externalError) }
                                }
                            }
                            AboutDivider()
                            AboutRow(
                                title = stringResource(R.string.about_open_license),
                                summary = stringResource(R.string.about_license_row_summary),
                                iconRes = R.drawable.ic_export,
                                external = false,
                                testTag = "about-row-license",
                                onClick = onLicenseSelected,
                            )
                        }
                    }
                    Text(text = stringResource(R.string.about_license_summary), style = OpenJumpTypes.Body)
                    Text(
                        text = stringResource(R.string.about_license_notice),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                AboutSection(
                    title = stringResource(R.string.about_diagnostics_title),
                    body = stringResource(R.string.about_diagnostics_body),
                ) {
                    SelectableText(diagnosticText, technical = true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        AboutActionButton(
                            text = stringResource(R.string.about_copy_diagnostics),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val success = copyText(context, diagnosticText)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(
                                            if (success) R.string.about_diagnostics_copied
                                            else R.string.about_clipboard_unavailable,
                                        ),
                                    )
                                }
                            },
                        )
                        AboutActionButton(
                            text = stringResource(R.string.about_share_diagnostics),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val success = shareText(context, diagnosticText)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(
                                            if (success) R.string.about_diagnostics_shared
                                            else R.string.about_external_unavailable,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = OpenJumpTypes.SectionTitle,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AboutRow(
    title: String,
    summary: String,
    @DrawableRes iconRes: Int,
    external: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(testTag)
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
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.padding(Spacing.sm),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(if (external) R.drawable.ic_open_in_new else R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSection(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = OpenJumpTypes.SectionTitle,
        )
        body?.let { Text(text = it, style = OpenJumpTypes.Body) }
        content()
    }
}

@Composable
private fun SelectableText(text: String, technical: Boolean = false) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = if (technical) OpenJumpTypes.Timestamp else OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AboutActionButton(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        iconRes?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text = text)
    }
}
