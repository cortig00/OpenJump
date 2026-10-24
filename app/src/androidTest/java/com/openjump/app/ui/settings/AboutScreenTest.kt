package com.openjump.app.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ClipboardManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.settings.AppLanguage
import com.openjump.app.settings.AppLanguageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun resetLocale() {
        AppLanguageManager.apply(AppLanguage.SYSTEM)
    }

    @Test
    fun installedPackageMetadataIsDisplayedAndAboutHasNoBottomNavigation() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val expectedVersion = "${packageInfo.versionName.orEmpty()} (${PackageInfoCompat.getLongVersionCode(packageInfo)})"
        composeRule.setContent { MaterialTheme { AboutScreen({}, {}, {}) } }

        composeRule.onNodeWithText(context.getString(R.string.about_version, expectedVersion)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.about_open_source)).assertExists()
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
            matcher = hasText(context.getString(R.string.about_diagnostics_title)),
        )
        composeRule.onNodeWithText(context.getString(R.string.about_diagnostics_title)).assertExists()
        composeRule.onAllNodesWithText(context.getString(R.string.nav_settings)).assertCountEquals(0)
    }

    @Test
    fun canonicalLinksRejectUnsafeUrlsAndUnavailableHandlers() {
        val recording = RecordingContext(context)
        assertTrue(launchApprovedUrl(recording, SOURCE_URL))
        assertEquals(Intent.ACTION_VIEW, recording.started.single().action)
        assertEquals(Uri.parse(SOURCE_URL), recording.started.single().data)
        assertTrue(launchApprovedUrl(recording, PUBLIC_PRIVACY_URL))
        assertEquals(Intent.ACTION_VIEW, recording.started[1].action)
        assertEquals(Uri.parse(PUBLIC_PRIVACY_URL), recording.started[1].data)
        assertFalse(launchApprovedUrl(recording, "https://cortig00.github.io/OpenJump/privacy/"))
        assertFalse(launchApprovedUrl(recording, "https://github.com.evil.example/cortig00/OpenJump"))
        assertEquals(2, recording.started.size)

        assertFalse(launchApprovedUrl(RecordingContext(context, failWith = android.content.ActivityNotFoundException()), ISSUES_URL))
        assertFalse(launchApprovedUrl(RecordingContext(context, failWith = SecurityException()), PUBLIC_PRIVACY_URL))
    }

    @Test
    fun coffeeButtonOpensTheExactSupportLink() {
        val recording = RecordingContext(context)
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides recording) {
                MaterialTheme { AboutScreen({}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
            matcher = hasText(context.getString(R.string.about_buy_me_a_coffee)),
        )
        composeRule.onAllNodesWithText(context.getString(R.string.about_support_heading)).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.about_support_body))
            .assert(hasAnyAncestor(hasTestTag("about-product-section")))
        composeRule.onNodeWithText(context.getString(R.string.about_buy_me_a_coffee))
            .assert(hasAnyAncestor(hasTestTag("about-product-section")))
        composeRule.onNodeWithText(context.getString(R.string.about_buy_me_a_coffee)).performClick()

        val intent = recording.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://buymeacoffee.com/cortig00"), intent.data)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun emailRowCreatesEditableLocalizedDraftWithOnlyMinimalDiagnostics() {
        val recording = RecordingContext(context)
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides recording) {
                MaterialTheme { AboutScreen({}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
            matcher = hasTestTag("about-row-email"),
        )
        val emailRow = composeRule.onNodeWithTag("about-row-email")
        // Merged visible text is the accessibility label; do not repeat it as
        // a separate contentDescription on the same tappable row.
        assertFalse(emailRow.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        emailRow.performClick()

        val intent = recording.started.single()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:$PROBLEM_REPORT_EMAIL", intent.data?.toString()?.substringBefore('?'))
        val encoded = intent.data.toString().substringAfter('?').split('&').associate { part ->
            val (key, value) = part.split('=', limit = 2)
            key to java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }
        assertEquals(context.getString(R.string.about_email_subject), encoded["subject"])
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val diagnostic = formatDiagnostics(
            DiagnosticMetadata(
                appVersionName = packageInfo.versionName.orEmpty(),
                appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                androidApi = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
            ),
        )
        val expectedBody = formatProblemReportBody(
            problemLabel = context.getString(R.string.about_email_problem),
            stepsLabel = context.getString(R.string.about_email_steps),
            expectedLabel = context.getString(R.string.about_email_expected),
            actualLabel = context.getString(R.string.about_email_actual),
            diagnosticsLabel = context.getString(R.string.about_email_diagnostics),
            diagnostics = diagnostic,
        )
        assertEquals(expectedBody, encoded["body"])
        assertEquals(context.getString(R.string.about_email_subject), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(expectedBody, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(expectedBody.endsWith(diagnostic))
        listOf("athlete", "session", "video", "uri", "path", "database", "settings").forEach {
            assertFalse(expectedBody.lowercase().contains(it))
        }
    }

    @Test
    fun emailLauncherHandlesMissingAndBlockedMailAppsAndShowsEmailSpecificError() {
        assertFalse(launchProblemReport(
            RecordingContext(context, failWith = android.content.ActivityNotFoundException()), "subject", "body",
        ))
        assertFalse(launchProblemReport(RecordingContext(context, failWith = SecurityException()), "subject", "body"))

        val recording = RecordingContext(context, failWith = android.content.ActivityNotFoundException())
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides recording) {
                MaterialTheme { AboutScreen({}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
            matcher = hasTestTag("about-row-email"),
        )
        composeRule.onNodeWithTag("about-row-email").performClick()
        composeRule.onNodeWithText(context.getString(R.string.about_email_unavailable)).assertExists()
    }

    @Test
    fun projectRowsLaunchOnlyExactHttpsUrlsAndLegalRowsKeepCallbacks() {
        val recording = RecordingContext(context)
        var privacyClicks = 0
        var licenseClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides recording) {
                MaterialTheme { AboutScreen({}, { privacyClicks++ }, { licenseClicks++ }) }
            }
        }
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(hasTestTag("about-row-source"))
        composeRule.onNodeWithTag("about-row-source").performClick()
        assertEquals(Uri.parse(SOURCE_URL), recording.started.last().data)
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(hasTestTag("about-row-github-issues"))
        composeRule.onNodeWithTag("about-row-github-issues").performClick()
        assertEquals(Uri.parse(ISSUES_URL), recording.started.last().data)
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(hasTestTag("about-row-public-privacy"))
        composeRule.onNodeWithTag("about-row-public-privacy").performClick()
        assertEquals(Uri.parse(PUBLIC_PRIVACY_URL), recording.started.last().data)
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(hasTestTag("about-row-privacy"))
        composeRule.onNodeWithTag("about-row-privacy").performClick()
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(hasTestTag("about-row-license"))
        composeRule.onNodeWithTag("about-row-license").performClick()
        assertEquals(1, privacyClicks)
        assertEquals(1, licenseClicks)
    }

    @Test
    fun coffeeButtonReportsUnavailableBrowser() {
        val recording = RecordingContext(context, failWith = android.content.ActivityNotFoundException())
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides recording) {
                MaterialTheme { AboutScreen({}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
            matcher = hasText(context.getString(R.string.about_buy_me_a_coffee)),
        )
        composeRule.onNodeWithText(context.getString(R.string.about_buy_me_a_coffee)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.about_external_unavailable)).assertExists()
    }

    @Test
    fun copyAndShareUseTheSameExactDiagnosticPayload() {
        val payload = formatDiagnostics(
            DiagnosticMetadata("1.1.0-beta.1", 2, "15", 35, "Google", "Pixel 6"),
        )
        assertTrue(copyText(context, payload))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(payload, clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())

        val recording = RecordingContext(context)
        assertTrue(shareText(recording, payload))
        val chooser = recording.started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val shared = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(shared)
        assertEquals(Intent.ACTION_SEND, shared?.action)
        assertEquals("text/plain", shared?.type)
        assertEquals(payload, shared?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun licenseResourceContainsCompleteOfflineGplText() {
        val text = context.resources.openRawResource(R.raw.gpl_3_0).bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(text.startsWith("                    GNU GENERAL PUBLIC LICENSE"))
        assertTrue(text.contains("Version 3, 29 June 2007"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun sectionHeadingsExposeHeadingSemantics() {
        composeRule.setContent { MaterialTheme { AboutScreen({}, {}, {}) } }
        assertTrue(
            composeRule.onNodeWithText(context.getString(R.string.app_name))
                .fetchSemanticsNode().config.contains(SemanticsProperties.Heading),
        )
        composeRule.onAllNodesWithText(context.getString(R.string.about_identity_heading)).assertCountEquals(0)
        composeRule.onNodeWithTag("about-logo")
            .assertWidthIsEqualTo(80.dp)
            .assertHeightIsEqualTo(80.dp)
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).assertExists()
    }

    @Test
    fun licenseScreenExposesVisibleHeadingSemantics() {
        composeRule.setContent { MaterialTheme { LicenseScreen {} } }
        composeRule.onNodeWithTag("license-document-heading")
            .assertTextEquals(context.getString(R.string.about_license_title))
        assertTrue(
            composeRule.onNodeWithTag("license-document-heading")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Heading),
        )
    }

    @Test
    fun supportedLocalesRenderAboutContentAndDiagnosticLabels() {
        val configurationState = mutableStateOf(Configuration(context.resources.configuration))
        composeRule.setContent {
            val configuration = configurationState.value
            val localizedContext = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                MaterialTheme { AboutScreen({}, {}, {}) }
            }
        }
        listOf("es", "en", "fr", "de", "it", "pt-BR", "pt-PT", "tr").forEach { languageTag ->
            configurationState.value = Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(languageTag))
            }
            composeRule.waitForIdle()
            val localizedContext = context.createConfigurationContext(configurationState.value)
            composeRule.onNodeWithText(localizedContext.getString(R.string.about_title)).assertExists()
            composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
                matcher = hasText(localizedContext.getString(R.string.about_product_heading)),
            )
            composeRule.onNodeWithText(localizedContext.getString(R.string.about_product_heading)).assertExists()
            composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
                matcher = hasText(localizedContext.getString(R.string.about_email_title)),
            )
            composeRule.onNodeWithText(localizedContext.getString(R.string.about_email_title)).assertExists()
            composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
                matcher = hasText(localizedContext.getString(R.string.about_buy_me_a_coffee)),
            )
            composeRule.onAllNodesWithText(localizedContext.getString(R.string.about_support_heading)).assertCountEquals(0)
            composeRule.onAllNodesWithText(localizedContext.getString(R.string.about_identity_heading)).assertCountEquals(0)
            composeRule.onNodeWithText(localizedContext.getString(R.string.about_buy_me_a_coffee)).assertExists()
            composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
                matcher = hasText(localizedContext.getString(R.string.about_diagnostics_title)),
            )
            composeRule.onNodeWithText(localizedContext.getString(R.string.about_diagnostics_title)).assertExists()
        }
    }

    @Test
    fun smallViewportAndLargeFontCanScrollToActionsWithoutHorizontalReflow() {
        val configurationState = mutableStateOf(Configuration(context.resources.configuration))
        composeRule.setContent {
            val configuration = configurationState.value
            val localizedContext = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, 2f),
            ) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    MaterialTheme { AboutScreen({}, {}, {}) }
                }
            }
        }
        listOf("fr", "de").forEach { languageTag ->
            configurationState.value = Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(languageTag))
            }
            composeRule.waitForIdle()
            val localizedContext = context.createConfigurationContext(configurationState.value)
            val list = composeRule.onNodeWithTag("about-screen-list")
            list.performScrollToNode(
                matcher = hasText(localizedContext.getString(R.string.about_share_diagnostics)),
            )
            val localizedLabels = listOf(
                R.string.about_open_source,
                R.string.about_email_title,
                R.string.about_open_issues,
                R.string.about_buy_me_a_coffee,
                R.string.about_open_privacy,
                R.string.about_open_public_privacy,
                R.string.about_open_license,
                R.string.about_copy_diagnostics,
                R.string.about_share_diagnostics,
            ).map(localizedContext::getString)
            localizedLabels.forEach { label ->
                list.performScrollToNode(matcher = hasText(label))
                // Product now spans multiple paragraphs. Align the actual action within
                // its lazy item as well, rather than only bringing the item into view.
                val action = composeRule.onNodeWithText(label).performScrollTo()
                assertWithinViewport(action)
            }
            listOf(SOURCE_URL, ISSUES_URL, PUBLIC_PRIVACY_URL, COFFEE_URL).forEach { url ->
                composeRule.onAllNodesWithText(url).assertCountEquals(0)
            }
        }
    }

    private fun assertWithinViewport(node: SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("node is clipped horizontally: $bounds", bounds.left >= 0f)
        assertTrue("node is clipped horizontally: $bounds", bounds.right <= 320f)
        assertTrue("node has no visible bounds: $bounds", bounds.width > 0f)
        assertTrue("node is clipped vertically: $bounds", bounds.top >= 0f)
        assertTrue("node is clipped vertically: $bounds", bounds.bottom <= 480f)
    }

    private class RecordingContext(
        base: Context,
        private val failWith: RuntimeException? = null,
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            failWith?.let { throw it }
            started += intent
        }
    }
}
