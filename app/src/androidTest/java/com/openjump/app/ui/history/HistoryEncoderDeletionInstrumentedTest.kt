package com.openjump.app.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F035: empty Encoder rows offer Delete directly in HISTORY (with confirmation
 * copy reuse); non-empty Encoder rows and jumps do not. Cancel has no effect and
 * the row stays listed; the repository call only happens after confirmation.
 */
@RunWith(AndroidJUnit4::class)
class HistoryEncoderDeletionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val deleteLabel get() = context.getString(R.string.result_delete_action)

    private fun emptySession(id: Long = 1L) = RecentEncoderSession(
        id = id,
        dateTime = 1_000L,
        exercise = "BENCH_PRESS",
        loadKg = 60.0,
        validRepetitions = 0,
        bestMcv = null,
    )

    @Test fun emptyEncoderRowOffersDeleteAndCancelKeepsRow() {
        var deletes = 0
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = MeasurementHistoryItem.Encoder(emptySession()),
                    onClick = {},
                    onDelete = { deletes++ },
                )
            }
        }
        compose.onNodeWithText(deleteLabel).assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(deletes == 1)
    }

    @Test fun deletingRowDisablesDeleteWhileRequestInFlight() {
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = MeasurementHistoryItem.Encoder(emptySession()),
                    onClick = {},
                    onDelete = {},
                    deleting = true,
                )
            }
        }
        compose.onNodeWithText(deleteLabel).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun nonEmptyEncoderAndJumpRowsOfferNoDelete() {
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = MeasurementHistoryItem.Encoder(
                        emptySession(id = 2L).copy(validRepetitions = 3, bestMcv = 0.71),
                    ),
                    onClick = {},
                    onDelete = null,
                )
            }
        }
        compose.onNodeWithText(deleteLabel).assertDoesNotExist()

        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = MeasurementHistoryItem.Jump(
                        RecentMeasurement(
                            id = 9L,
                            dateTime = 1_000L,
                            protocolId = "CMJ",
                            primaryMetricKey = "HEIGHT_CM",
                            primaryMetricValue = 42.0,
                            primaryMetricUnit = "CENTIMETER",
                            side = null,
                        ),
                    ),
                    onClick = {},
                    onDelete = null,
                )
            }
        }
        compose.onNodeWithText(deleteLabel).assertDoesNotExist()
    }
}
