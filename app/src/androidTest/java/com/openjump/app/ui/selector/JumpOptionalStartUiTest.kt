package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.titleResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Renders the production selector event list and contextual action bar for both marking modes.
 * No video, session or preference file is involved: the draft is the only input.
 */
@RunWith(AndroidJUnit4::class)
class JumpOptionalStartUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private lateinit var strings: Context
    private data class RenderSpec(
        val draft: MeasurementDraft,
        val context: Context,
        val config: Configuration,
        val labels: Map<EventType, String>,
        val dark: Boolean,
        val fontScale: Float,
        val generation: Int,
    )
    private val rendered = mutableStateOf<RenderSpec?>(null)
    private var contentInstalled = false
    private var generation = 0

    private fun render(
        draft: MeasurementDraft,
        language: String = "es",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val config = Configuration(appContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = appContext.createConfigurationContext(config)
        val labels = EventType.entries.associateWith { localized.getString(it.titleResource()) }
        val spec = RenderSpec(draft, localized, config, labels, dark, fontScale, ++generation)
        strings = localized
        compose.runOnIdle { rendered.value = spec }
        if (!contentInstalled) {
            compose.setContent {
                rendered.value?.let { current ->
                    key(current.generation) {
                        CompositionLocalProvider(
                            LocalContext provides current.context,
                            LocalConfiguration provides current.config,
                            LocalDensity provides Density(LocalDensity.current.density, current.fontScale),
                        ) {
                            OpenJumpTheme(darkTheme = current.dark) {
                                Column {
                                    val draft = current.draft
                                    EventSection(
                                        markingEvents = draft.markingEvents,
                                        events = draft.events,
                                        completedEvents = draft.markingEvents.count { EventKey(it) in draft.events },
                                        labels = current.labels,
                                        canJump = true,
                                        canReplace = true,
                                        onJump = {},
                                        onReplace = {},
                                    )
                                    JumpMarkingActionBar(
                                        action = JumpMarkingActionPolicy.resolve(
                                            draft = draft,
                                            markingEnabled = true,
                                            isPlaying = false,
                                            isCropFraming = false,
                                        ),
                                        seriesPresentation = seriesPresentationFor(attempt = 1, targetAttempts = 1),
                                        onMark = {},
                                        onCompute = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
            contentInstalled = true
        }
        compose.waitForIdle()
    }

    private fun labelOf(type: EventType): String = strings.getString(type.titleResource())

    private fun countText(completed: Int, total: Int): String =
        strings.getString(R.string.selector_events_count, completed, total)

    private fun markCta(type: EventType): String =
        strings.getString(R.string.selector_mark_event, labelOf(type).lowercase())

    private fun assertNoNodeWithText(text: String) {
        assertTrue(
            "expected no node with text '$text'",
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun countNodesWithText(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun basicModeShowsTwoRowsWithoutAnyStartTrace() {
        render(draft(markMovementStart = false))

        compose.onNodeWithText(countText(completed = 0, total = 2)).assertIsDisplayed()
        compose.onNodeWithText(labelOf(EventType.TAKEOFF)).assertIsDisplayed()
        compose.onNodeWithText(labelOf(EventType.LANDING)).assertIsDisplayed()
        // No start row, label or padding hole: exactly one next row and one pending row.
        assertNoNodeWithText(labelOf(EventType.MOVEMENT_START))
        assertNoNodeWithText(strings.getString(R.string.selector_hint_movement_start))
        assertEquals(1, countNodesWithText(strings.getString(R.string.selector_event_next)))
        assertEquals(1, countNodesWithText(strings.getString(R.string.selector_event_pending)))

        // The contextual action asks for takeoff first and the CTA is a 48 dp target.
        compose.onNodeWithText(strings.getString(R.string.selector_hint_takeoff)).assertIsDisplayed()
        compose.onNodeWithText(markCta(EventType.TAKEOFF))
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun preferenceOnRestoresThreeRowsAndTheStartCue() {
        render(draft(markMovementStart = true))

        compose.onNodeWithText(countText(completed = 0, total = 3)).assertIsDisplayed()
        compose.onNodeWithText(labelOf(EventType.MOVEMENT_START)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.selector_hint_movement_start)).assertIsDisplayed()
        compose.onNodeWithText(markCta(EventType.MOVEMENT_START)).assertIsDisplayed()
        assertEquals(1, countNodesWithText(strings.getString(R.string.selector_event_next)))
        assertEquals(2, countNodesWithText(strings.getString(R.string.selector_event_pending)))
    }

    @Test
    fun bothModesReachAnEnabledComputeActionWithTheirOwnEvents() {
        render(
            draft(
                EventType.TAKEOFF to 1_000_000L,
                EventType.LANDING to 2_000_000L,
                markMovementStart = false,
            ),
        )
        compose.onNodeWithText(countText(completed = 2, total = 2)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.selector_compute_result))
            .assertIsDisplayed()
            .assertIsEnabled()

        render(
            draft(
                EventType.MOVEMENT_START to 500_000L,
                EventType.TAKEOFF to 1_000_000L,
                EventType.LANDING to 2_000_000L,
                markMovementStart = true,
            ),
        )
        compose.onNodeWithText(countText(completed = 3, total = 3)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.selector_compute_result))
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun darkAndLargeFontKeepBothModesReadableAndTouchable() {
        render(draft(markMovementStart = false), dark = true, fontScale = 1.5f)
        compose.onNodeWithText(countText(completed = 0, total = 2)).assertIsDisplayed()
        compose.onNodeWithText(labelOf(EventType.TAKEOFF)).assertIsDisplayed()
        assertNoNodeWithText(labelOf(EventType.MOVEMENT_START))
        compose.onNodeWithText(markCta(EventType.TAKEOFF)).assertHeightIsAtLeast(48.dp)

        // A marked row keeps its 48 dp jump and edit affordances.
        render(
            draft(EventType.TAKEOFF to 1_000_000L, markMovementStart = false),
            dark = false,
            fontScale = 1.5f,
        )
        compose.onNodeWithText(countText(completed = 1, total = 2)).assertIsDisplayed()
        val timestamp = Formatting.usToClockMs(1_000_000L)
        compose.onNodeWithContentDescription(
            strings.getString(R.string.selector_event_go_description, labelOf(EventType.TAKEOFF), timestamp),
        ).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(
            strings.getString(R.string.selector_event_change_description, labelOf(EventType.TAKEOFF)),
        ).assertHeightIsAtLeast(48.dp)

        render(draft(markMovementStart = true), dark = true, fontScale = 1.5f)
        compose.onNodeWithText(countText(completed = 0, total = 3)).assertIsDisplayed()
        compose.onNodeWithText(labelOf(EventType.MOVEMENT_START)).assertIsDisplayed()
        compose.onNodeWithText(markCta(EventType.MOVEMENT_START)).assertHeightIsAtLeast(48.dp)
    }

    private fun draft(
        vararg marks: Pair<EventType, Long>,
        markMovementStart: Boolean,
    ): MeasurementDraft = MeasurementDraft(
        sessionKey = "selector-ui",
        protocolId = ProtocolId.CMJ,
        setup = ProtocolSetup(),
        markMovementStart = markMovementStart,
        events = marks.associate { (type, ptsUs) ->
            EventKey(type) to EventMark(EventKey(type), ptsUs, frameIndex = (ptsUs / 1_000L).toInt())
        },
    )
}
