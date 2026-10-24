package com.openjump.app.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.SideEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.OpenJumpNavHost
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual AppNav/settings route + native keyboard. Edit is cancelled, no settings/Room writes. */
@RunWith(AndroidJUnit4::class)
class EncoderDefaultLoadsImeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun focusedEditorBounds(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return Rect().also(node::getBoundsInScreen)
        for (index in 0 until node.childCount) {
            focusedEditorBounds(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("encoderDefaultsEvidence") != "true") return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val bitmap = automation.takeScreenshot()
        val folder = File(compose.activity.getExternalFilesDir(null), "encoder-default-loads-visual").apply { mkdirs() }
        try { File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun settingsEditorKeepsFocusAndActionsAboveKeyboardWithoutPanningTheSettingsHeader() {
        lateinit var nav: NavHostController
        compose.setContent {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            OpenJumpTheme { OpenJumpNavHost(controller) }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate("settings") }
        compose.onNodeWithTag("settings-screen-list").performScrollToNode(hasTestTag("settings-default-loads"))
        val title = compose.activity.getString(R.string.settings_title)
        fun headerTop(): Float = compose.onAllNodesWithText(title).fetchSemanticsNodes().minOf { it.boundsInWindow.top }
        val before = headerTop()
        compose.onNodeWithTag("settings-default-loads").performClick()
        compose.onNodeWithTag("default-loads-sheet").assertIsDisplayed()
        capture("01-list.png")
        compose.onNodeWithTag("default-load-row-BENCH_PRESS").performClick()
        val editor = compose.onNodeWithTag("default-load-input")
        editor.assertIsDisplayed().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            compose.waitUntil(5_000) { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            editor.assertIsDisplayed().assertIsFocused().performTextReplacement("61.25")
            compose.waitForIdle()
            automation.waitForIdle(300, 5_000)
            val ime = automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val keyboard = Rect().also(ime::getBoundsInScreen)
            val field = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { focusedEditorBounds(it.root) }
            assertNotNull("Native accessibility must expose the focused editor bounds", field)
            assertTrue("Focused field stays above the native IME", field!!.bottom <= keyboard.top)
            compose.onNodeWithTag("default-load-save").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("default-load-cancel").assertIsDisplayed()
            assertEquals("Keyboard must not pan the background settings header", before, headerTop(), 1f)
            editor.assertTextContains("61.25")
            capture("02-editor-ime.png")
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            compose.onNodeWithTag("default-load-cancel").performClick()
            compose.waitForIdle()
        }
    }
}
