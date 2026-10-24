package com.openjump.app.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolFeedbackBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun milestoneIsOneAccessibleAnnouncementWithoutDecorativeCheck() {
        compose.setContent {
            OpenJumpTheme {
                ProtocolFeedbackBanner(
                    text = "Left complete · next: right",
                    accessibilityText = "Left leg complete. Now mark the right leg.",
                    level = ProtocolFeedbackLevel.MILESTONE,
                )
            }
        }
        compose.onNodeWithContentDescription("Left leg complete. Now mark the right leg.").assertExists()
        compose.onAllNodesWithContentDescription("Left leg complete. Now mark the right leg.").assertCountEquals(1)
        compose.onAllNodesWithText("✓").assertCountEquals(0)
    }

    @Test fun absentEventHasNoBannerSemantics() {
        compose.setContent {
            OpenJumpTheme {
                ProtocolFeedbackBanner(text = null, accessibilityText = null, level = ProtocolFeedbackLevel.ATTEMPT)
            }
        }
        compose.onAllNodesWithContentDescription("").assertCountEquals(0)
    }
}
