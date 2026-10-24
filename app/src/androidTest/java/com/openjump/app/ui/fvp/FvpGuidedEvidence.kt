package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.UnitProfile
import java.io.File

/** Captura opt-in del root Compose real (ultimo root para dialogos), sincronizada con su dibujo. */
internal fun captureGuidedScreen(compose: ComposeTestRule, group: String, name: String, rootTag: String? = null) {
    if (InstrumentationRegistry.getArguments().getString("fvpGuidedEvidence") != "true") return
    compose.waitForIdle()
    val matcher = if (rootTag == null) isRoot() else isRoot() and hasAnyDescendant(hasTestTag(rootTag))
    val roots = compose.onAllNodes(matcher)
    val bitmap = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap()
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, group)
        .apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

internal fun guidedEvidenceProfile(): UnitProfile = when (
    InstrumentationRegistry.getArguments().getString("fvpUnitProfile")
) {
    "us" -> UnitProfile.UNITED_STATES
    "custom" -> UnitProfile.METRIC.copy(shortLength = ShortLengthUnit.INCH)
    else -> UnitProfile.METRIC
}
