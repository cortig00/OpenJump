package com.openjump.app.ui.encoder

import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.OpenJumpNavHost
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production root + route, not a copied Scaffold. No clear/uninstall or measurement save. */
@RunWith(AndroidJUnit4::class)
class EncoderSetupImeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadFocusExcludesKeyboardOnceWithoutPanningHeaderOrHidingEditor() {
        lateinit var nav: NavHostController
        var hideKeyboard: () -> Unit = {}
        compose.setContent {
            val controller = rememberNavController()
            val keyboard = LocalSoftwareKeyboardController.current
            SideEffect { nav = controller; hideKeyboard = { keyboard?.hide() } }
            OpenJumpTheme { OpenJumpNavHost(controller) }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate("encoder/setup") }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("encoder-setup-load").fetchSemanticsNodes().isNotEmpty()
        }
        val title = compose.onNodeWithText(compose.activity.getString(R.string.encoder_setup_title))
        val titleTopBefore = title.fetchSemanticsNode().boundsInWindow.top
        val editor = compose.onNodeWithTag("encoder-setup-load")
        editor.performScrollTo().performClick()
        try {
            compose.waitUntil(5_000) {
                compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
            val decor = compose.activity.window.decorView
            val ime = decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
            assertTrue("Native keyboard exclusion must be nonzero", ime > 0)
            val keyboardTop = (decor.height - ime).toFloat()
            val footer = compose.onNodeWithTag("encoder-setup-footer").fetchSemanticsNode().boundsInWindow
            if (InstrumentationRegistry.getArguments().getString("encoderSetupEvidence") == "true") {
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val folder = File(compose.activity.getExternalFilesDir(null), "encoder-load-focus").apply { mkdirs() }
                try {
                    File(folder, "production-root-ime.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
            assertEquals("IME must not be subtracted by both AppNav and Encoder", keyboardTop, footer.bottom, 1f)
            editor.assertIsDisplayed().assertIsFocused()
            val field = editor.fetchSemanticsNode().boundsInWindow
            val viewport = compose.onNodeWithTag("encoder-setup-content").fetchSemanticsNode().boundsInWindow
            assertTrue("Focused load must stay inside the usable form", field.top >= viewport.top && field.bottom <= footer.top + 1f)
            assertEquals("Keyboard must not pan the header", titleTopBefore, title.fetchSemanticsNode().boundsInWindow.top, 1f)
            editor.performTextReplacement("40")
            editor.assertTextContains("40")
        } finally {
            compose.runOnUiThread { hideKeyboard(); nav.popBackStack() }
            compose.waitForIdle()
        }
    }
}
