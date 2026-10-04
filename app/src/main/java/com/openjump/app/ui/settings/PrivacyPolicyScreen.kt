package com.openjump.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.openjump.app.R
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing

private data class PrivacySection(
    @StringRes val title: Int,
    @StringRes val body: Int,
)

private val privacySections = listOf(
    PrivacySection(R.string.privacy_data_title, R.string.privacy_data_body),
    PrivacySection(R.string.privacy_video_title, R.string.privacy_video_body),
    PrivacySection(R.string.privacy_sharing_title, R.string.privacy_sharing_body),
    PrivacySection(R.string.privacy_control_title, R.string.privacy_control_body),
    PrivacySection(R.string.privacy_permissions_title, R.string.privacy_permissions_body),
    PrivacySection(R.string.privacy_open_source_title, R.string.privacy_open_source_body),
    PrivacySection(R.string.privacy_contact_title, R.string.privacy_contact_body),
)

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.settings_privacy_title),
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
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            item {
                Text(
                    stringResource(R.string.privacy_updated),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    stringResource(R.string.privacy_intro),
                    style = OpenJumpTypes.Body,
                )
            }
            privacySections.forEach { section ->
                item(section.title) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            stringResource(section.title),
                            modifier = Modifier.semantics { heading() },
                            style = OpenJumpTypes.SectionTitle,
                        )
                        Text(
                            stringResource(section.body),
                            style = OpenJumpTypes.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
