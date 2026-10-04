package com.openjump.app.ui.labs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openjump.app.R
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.StatusPill
import com.openjump.app.ui.theme.OpenJumpTheme

@Composable
fun ExperimentalBadge(modifier: Modifier = Modifier) {
    val colors = OpenJumpTheme.colors
    StatusPill(
        text = stringResource(R.string.labs_experimental),
        modifier = modifier,
        containerColor = colors.warningContainer,
        contentColor = colors.warning,
    )
}

@Composable
fun ExperimentalNotice(modifier: Modifier = Modifier) {
    InfoBanner(
        title = stringResource(R.string.labs_live_warning_title),
        body = stringResource(R.string.labs_live_warning_body),
        modifier = modifier,
        warning = true,
    )
}
