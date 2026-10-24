package com.openjump.app.ui.fvp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import java.io.FileInputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Patrón de Encoder sobre Setup real: elegir/cancelar/restaurar nunca confirma ni normaliza datos. */
@RunWith(AndroidJUnit4::class)
class FvpEncoderStyleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var restoration: StateRestorationTester
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun before() { FvpDraftSession.reset() }
    @After fun after() { FvpDraftSession.reset() }

    @Test
    fun exerciseSheetBackAndCancelPreservePartialMassWithoutCommitting() {
        showSetup()
        compose.onNodeWithText(context.getString(R.string.fvp_body_mass_from_profile)).assertExists()
        typePartialMass()
        compose.onAllNodesWithText(context.getString(R.string.fvp_body_mass_from_profile)).assertCountEquals(0)
        openPicker()
        compose.onNodeWithTag("fvp-protocol-SJ").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("fvp-protocol-CMJ").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("fvp-protocol-picker").fetchSemanticsNodes().isEmpty()
        }
        assertPartialDataUnchanged()
        openPicker()
        compose.onNodeWithTag("fvp-protocol-cancel").performClick()
        assertPartialDataUnchanged()
    }

    @Test
    fun reselectingTheCurrentExerciseDoesNotResetTheTypedText() {
        showSetup()
        typePartialMass()
        openPicker()
        compose.onNodeWithTag("fvp-protocol-SJ").performClick()
        assertPartialDataUnchanged()
        compose.onNodeWithTag("fvp-setup-protocol-value", useUnmergedTree = true).assertTextContains("SJ", substring = true)
    }

    @Test
    fun cmjChoiceStaysExplicitlyConditionalAndOnlyChangesTheFormSelection() {
        showSetup()
        openPicker()
        compose.onNodeWithText(context.getString(R.string.protocol_sj_description)).assertExists()
        compose.onNodeWithText(context.getString(R.string.protocol_cmj_description)).assertExists()
        compose.onNodeWithTag("fvp-protocol-CMJ").performClick()
        compose.onNodeWithTag("fvp-protocol-picker").assertDoesNotExist()
        compose.onNodeWithTag("fvp-setup-protocol-value", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.fvp_protocol_cmj_conditional))
        assertNull(FvpDraftSession.draft.value)
        openPicker()
        compose.onNodeWithTag("fvp-protocol-CMJ").assertIsSelected()
        compose.onNodeWithTag("fvp-protocol-SJ").assertIsNotSelected()
        compose.onNodeWithTag("fvp-protocol-cancel").performClick()
    }

    @Test
    fun restoredOpenSheetKeepsTheSelectionAndTheUnparseableField() {
        showSetup()
        typePartialMass()
        openPicker()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-protocol-picker").assertExists()
        compose.onNodeWithTag("fvp-protocol-SJ").assertIsSelected()
        compose.onNodeWithTag("fvp-protocol-cancel").performClick()
        assertPartialDataUnchanged()
    }

    private fun showSetup() {
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            OpenJumpTheme {
                CompositionLocalProvider(LocalUnitSystem provides guidedEvidenceProfile()) {
                    FvpSetupScreen(
                        activeAthletes = listOf(AthleteEntity(
                            id = 7L, displayName = "Encoder style QA", weightKg = 80.0,
                            createdAt = 1L, updatedAt = 1L,
                        )),
                        athletesLoading = false, selectedAthleteId = 7L,
                        onAthleteSelected = {}, onBack = {}, onContinue = {}, onHistory = {},
                        introductionStore = FvpTestIntroductionStore(),
                    )
                }
            }
        }
    }

    private fun typePartialMass() {
        compose.onNodeWithTag("fvp-setup-mass").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-setup-mass").performTextInput("82.")
        compose.waitForIdle()
    }

    private fun openPicker() {
        compose.onNodeWithTag("fvp-setup-protocol").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-protocol-picker").assertExists()
    }

    private fun assertPartialDataUnchanged() {
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-protocol-picker").assertDoesNotExist()
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-mass").assertTextContains("82.")
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()
        assertNull(FvpDraftSession.draft.value)
    }
}
