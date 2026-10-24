package com.openjump.app.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-composable coverage for the shared F4 discard dialog used by ResultScreen
 * (non-bilateral Discard).
 *
 * Limits: this verifies the dialog contract (texts, >=48 dp targets, confirm vs
 * dismiss behavior), not the route wiring (BackHandler -> dialog ->
 * discardPending/AppSession.reset). That wiring needs a full READY/result draft
 * and is left to manual smoke (no AVD in this slice).
 */
@RunWith(AndroidJUnit4::class)
class DiscardConfirmDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun showsTitleBodyAndBothActions() {
        compose.setContent {
            OpenJumpTheme {
                DiscardConfirmDialog(
                    title = "Title",
                    body = "Body",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Title").assertIsDisplayed()
        compose.onNodeWithText("Body").assertIsDisplayed()
        compose.onNodeWithText(context().getString(R.string.common_discard)).assertIsDisplayed()
        compose.onNodeWithText(context().getString(R.string.common_cancel)).assertIsDisplayed()
    }

    @Test fun buttonsMeetMinimumTouchTarget() {
        compose.setContent {
            OpenJumpTheme {
                DiscardConfirmDialog(
                    title = "Title",
                    body = "Body",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(context().getString(R.string.common_discard)).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(context().getString(R.string.common_cancel)).assertHeightIsAtLeast(48.dp)
    }

    @Test fun confirmFiresOnceAndDismissNeverConfirms() {
        var confirms = 0
        var dismisses = 0
        compose.setContent {
            OpenJumpTheme {
                DiscardConfirmDialog(
                    title = "Title",
                    body = "Body",
                    onConfirm = { confirms++ },
                    onDismiss = { dismisses++ },
                )
            }
        }
        compose.onNodeWithText(context().getString(R.string.common_cancel)).performClick()
        assertEquals(0, confirms)
        assertEquals(1, dismisses)
        compose.onNodeWithText(context().getString(R.string.common_discard)).performClick()
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
    }

    @Test fun customLabelsAreUsed() {
        compose.setContent {
            OpenJumpTheme {
                DiscardConfirmDialog(
                    title = "Title",
                    body = "Body",
                    onConfirm = {},
                    onDismiss = {},
                    confirmLabel = "Yes, lose it",
                    dismissLabel = "Keep working",
                )
            }
        }
        compose.onNodeWithText("Yes, lose it").assertIsDisplayed()
        compose.onNodeWithText("Keep working").assertIsDisplayed()
        compose.onAllNodesWithText(context().getString(R.string.common_discard)).assertCountEquals(0)
    }
}
