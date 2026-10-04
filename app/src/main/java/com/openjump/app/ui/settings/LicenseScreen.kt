package com.openjump.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.openjump.app.R
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing

private fun bundledLicenseText(context: android.content.Context): String =
    context.resources.openRawResource(R.raw.gpl_3_0).bufferedReader(Charsets.UTF_8).use { it.readText() }

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember(context) { bundledLicenseText(context) }
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.about_license_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                horizontal = Spacing.screenHorizontal,
                vertical = Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item {
                Text(
                    text = stringResource(R.string.about_license_title),
                    modifier = Modifier
                        .testTag("license-document-heading")
                        .semantics { heading() },
                    style = OpenJumpTypes.SectionTitle,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.about_license_intro),
                    style = OpenJumpTypes.Body,
                )
            }
            item {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = licenseText,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
