package com.openjump.app.ui.history

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openjump.app.MainActivity
import com.openjump.app.R
import com.openjump.app.data.RecentMeasurement
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Covers the F3 append-page failure footer: a [HistoryScreenState.error] with retained
 * items must surface the inline explanation plus an explicit retry (not the ambiguous
 * "load more" button), and the retry must reach the load-more path.
 */
@RunWith(AndroidJUnit4::class)
class HistoryAppendErrorInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun fakeRetainedItems() = listOf(
        MeasurementHistoryItem.Jump(
            RecentMeasurement(
                id = 1L,
                dateTime = 1_000L,
                protocolId = "CMJ",
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = 42.0,
                primaryMetricUnit = "CENTIMETER",
                side = null,
            ),
        ),
    )

    private fun setFooterContent(state: HistoryScreenState, onLoadMore: () -> Unit) {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                OpenJumpTheme {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { item ->
                            Text("entry-${item.id}")
                        }
                        historyListFooter(state, onLoadMore = onLoadMore)
                    }
                }
            }
        }
    }

    @Test
    fun appendErrorKeepsItemsAndShowsRetryInsteadOfLoadMore() {
        val context = composeRule.activity
        var retries = 0
        setFooterContent(
            state = HistoryScreenState(
                items = fakeRetainedItems(),
                loading = false,
                error = true,
                canLoadMore = true,
            ),
            onLoadMore = { retries++ },
        )

        // Retained items stay visible; the partial list is not presented as complete.
        composeRule.onNodeWithText("entry-1").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_error_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_error_body)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_load_more)).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.history_retry))
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun retryDisabledWhileReloadInFlight() {
        val context = composeRule.activity
        setFooterContent(
            state = HistoryScreenState(
                items = fakeRetainedItems(),
                loading = true,
                error = true,
                canLoadMore = true,
            ),
            onLoadMore = {},
        )

        composeRule.onNodeWithText("entry-1").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_retry)).assertIsNotEnabled()
    }

    @Test
    fun noErrorKeepsPlainLoadMoreWithoutErrorCopy() {
        val context = composeRule.activity
        var loads = 0
        setFooterContent(
            state = HistoryScreenState(
                items = fakeRetainedItems(),
                loading = false,
                error = false,
                canLoadMore = true,
            ),
            onLoadMore = { loads++ },
        )

        composeRule.onNodeWithText(context.getString(R.string.history_load_more))
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.history_error_title)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, loads) }
    }
}
