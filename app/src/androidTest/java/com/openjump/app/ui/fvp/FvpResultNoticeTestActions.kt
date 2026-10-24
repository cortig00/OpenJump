package com.openjump.app.ui.fvp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** UI-only disclosure actions; never alter a fixture, warning, quality verdict or saved record. */
internal fun ComposeContentTestRule.openResultQualityForTest() {
    onNodeWithTag(FvpResultSection.QUALITY.tabTag).performClick()
    waitForIdle()
}

internal fun ComposeContentTestRule.showResultNoticeForTest(warning: FvpUiWarning) {
    openResultQualityForTest()
    val detail = "fvp-notice-detail-${warning.name}"
    if (!warning.isCriticalNotice() && onAllNodesWithTag(detail).fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("fvp-notice-toggle-${warning.name}").performScrollTo().performClick()
        waitForIdle()
    }
    onNodeWithTag(detail).performScrollTo().assertIsDisplayed()
}

internal fun ComposeContentTestRule.showResultMethodForTest() {
    openResultQualityForTest()
    if (onAllNodesWithTag("fvp-capture-method-body").fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("fvp-capture-method-toggle").performScrollTo().performClick()
        waitForIdle()
    }
    onNodeWithTag("fvp-capture-method-body").performScrollTo().assertIsDisplayed()
}

internal fun ComposeContentTestRule.returnToResultSummaryForTest() {
    onNodeWithTag(FvpResultSection.SUMMARY.tabTag).performClick()
    waitForIdle()
}
