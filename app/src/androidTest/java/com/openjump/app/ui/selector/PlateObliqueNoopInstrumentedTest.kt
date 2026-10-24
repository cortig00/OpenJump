package com.openjump.app.ui.selector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.encoder.PlateDetection
import com.openjump.app.encoder.PlateDetectionMode
import com.openjump.app.encoder.PlateDetectionSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editor no-op regression for the oblique policy slice.
 *
 * Uses a standalone [PlateReferencePrecisionEditor] with a known non-black fixture bitmap
 * (light gray with a darker disk) so the editor has image content without depending on
 * PixelCopy, the decoder, or any video fixture. The fixture bitmap check below proves only
 * that the bitmap passed into the editor is non-black; it does NOT prove the production
 * PixelCopy snapshot path renders non-black on device.
 *
 * Covers: Return and Cancel (which shares the Dialog onDismissRequest path also used by
 * hardware Back) with genuine-oblique and provisional proposals. Asserts geometry, adjusted
 * flag and provenance are unchanged, onApply is never called (no scale commit from the
 * editor), and canConfirm stays true->true for oblique and false->false for provisional.
 *
 * Does NOT cover the live confirmPlateProposal -> AppSession.encoderDraft write
 * (automaticPlateCalibration=false / plateDetection=null / debug flag): that path needs a
 * real decoded frame, rendered-view guards and detector identity, which has no seam in this
 * slice without reflection, private-state injection, or faked timing. Reported as an
 * untested gap; see the runtime report.
 */
@RunWith(AndroidJUnit4::class)
class PlateObliqueNoopInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun label(id: Int) = context.getString(id)

    private fun nonBlackFixtureBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = Color.DKGRAY
            isAntiAlias = true
        }
        canvas.drawCircle(120f, 120f, 60f, paint)
        return bitmap
    }

    private fun assertFixtureBitmapIsNonBlack(bitmap: Bitmap) {
        var visible = 0
        for (sample in listOf(60 to 60, 120 to 120, 180 to 180, 60 to 180, 180 to 60)) {
            val pixel = bitmap.getPixel(sample.first, sample.second)
            if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 12) visible++
        }
        assertTrue("Fixture bitmap must be non-black before mounting the editor", visible == 5)
    }

    private fun obliqueDetection() = PlateDetection(
        center = ImagePoint(540.0, 960.0),
        diameterPx = 320.0,
        confidence = 0.85,
        perspectiveRatio = 0.65,
        mode = PlateDetectionMode.AUTO,
        ellipseMajorAxisPx = 340.0,
        ellipseMinorAxisPx = 221.0,
        ellipseAngleRadians = 0.0,
        autoCalibrationAccepted = false,
        source = PlateDetectionSource.CONTOUR_ELLIPSE,
    )

    private fun obliqueProposal() = PlateCalibrationProposal(
        sessionKey = "session",
        videoUri = "content://video",
        generation = 4L,
        frameIndex = 2,
        ptsUs = 66_000L,
        presentationWidth = 1080,
        presentationHeight = 1920,
        originalDetection = obliqueDetection(),
        center = ImagePoint(540.0, 960.0),
        diameterPx = 320.0,
        angleRadians = 0.0,
        provenance = PlateDetectionSource.CONTOUR_ELLIPSE.name,
        adjusted = false,
        requiresAdjustment = true,
    )

    private fun provisionalProposal() = PlateCalibrationProposal(
        sessionKey = "session",
        videoUri = "content://video",
        generation = 4L,
        frameIndex = 2,
        ptsUs = 66_000L,
        presentationWidth = 1080,
        presentationHeight = 1920,
        originalDetection = null,
        center = ImagePoint(540.0, 960.0),
        diameterPx = 320.0,
        angleRadians = 0.0,
        provenance = "USER_CIRCLE",
        adjusted = false,
        requiresAdjustment = true,
    )

    private fun canConfirm(proposal: PlateCalibrationProposal): Boolean =
        PlateDetectionUiState(phase = PlateDetectionPhase.REVIEW, proposal = proposal).canConfirm

    private fun mountEditor(
        bitmap: Bitmap,
        proposal: PlateCalibrationProposal,
        onDismiss: () -> Unit,
        onApply: (ImagePoint, Double) -> Unit,
    ) {
        compose.setContent {
            OpenJumpTheme {
                PlateReferencePrecisionEditor(
                    bitmap = bitmap,
                    proposal = proposal,
                    onDismiss = onDismiss,
                    onApply = onApply,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reference-precision-return").assertIsDisplayed()
    }

    private fun assertProposalUnchanged(before: PlateCalibrationProposal, after: PlateCalibrationProposal) {
        assertEquals(before.center, after.center)
        assertEquals(before.diameterPx, after.diameterPx, 0.0)
        assertEquals(before.adjusted, after.adjusted)
        assertEquals(before.provenance, after.provenance)
        assertEquals(before.requiresAdjustment, after.requiresAdjustment)
        assertEquals(before, after)
    }

    @Test fun obliqueReturnWithoutEditsKeepsProposalAndStaysEnabled() {
        val bitmap = nonBlackFixtureBitmap()
        try {
            assertFixtureBitmapIsNonBlack(bitmap)
            val proposal = obliqueProposal()
            assertTrue(canConfirm(proposal))
            var applyCalls = 0
            var dismissed = false
            mountEditor(bitmap, proposal, onDismiss = { dismissed = true }, onApply = { _, _ -> applyCalls++ })
            compose.onNodeWithTag("reference-precision-return").performClick()
            compose.waitForIdle()
            assertTrue(dismissed)
            assertEquals(0, applyCalls)
            assertProposalUnchanged(proposal, proposal.copy())
            assertTrue(canConfirm(proposal))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun obliqueCancelWithoutEditsKeepsProposalAndStaysEnabled() {
        val bitmap = nonBlackFixtureBitmap()
        try {
            assertFixtureBitmapIsNonBlack(bitmap)
            val proposal = obliqueProposal()
            assertTrue(canConfirm(proposal))
            var applyCalls = 0
            var dismissed = false
            mountEditor(bitmap, proposal, onDismiss = { dismissed = true }, onApply = { _, _ -> applyCalls++ })
            compose.onNode(
                hasContentDescription(label(R.string.common_cancel)) and hasAnyAncestor(isDialog()),
            ).assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertTrue(dismissed)
            assertEquals(0, applyCalls)
            assertProposalUnchanged(proposal, proposal.copy())
            assertTrue(canConfirm(proposal))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun provisionalReturnWithoutEditsKeepsProposalAndStaysDisabled() {
        val bitmap = nonBlackFixtureBitmap()
        try {
            assertFixtureBitmapIsNonBlack(bitmap)
            val proposal = provisionalProposal()
            assertFalse(canConfirm(proposal))
            var applyCalls = 0
            var dismissed = false
            mountEditor(bitmap, proposal, onDismiss = { dismissed = true }, onApply = { _, _ -> applyCalls++ })
            compose.onNodeWithTag("reference-precision-return").performClick()
            compose.waitForIdle()
            assertTrue(dismissed)
            assertEquals(0, applyCalls)
            assertProposalUnchanged(proposal, proposal.copy())
            assertFalse(canConfirm(proposal))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun provisionalCancelWithoutEditsKeepsProposalAndStaysDisabled() {
        val bitmap = nonBlackFixtureBitmap()
        try {
            assertFixtureBitmapIsNonBlack(bitmap)
            val proposal = provisionalProposal()
            assertFalse(canConfirm(proposal))
            var applyCalls = 0
            var dismissed = false
            mountEditor(bitmap, proposal, onDismiss = { dismissed = true }, onApply = { _, _ -> applyCalls++ })
            compose.onNode(
                hasContentDescription(label(R.string.common_cancel)) and hasAnyAncestor(isDialog()),
            ).assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertTrue(dismissed)
            assertEquals(0, applyCalls)
            assertProposalUnchanged(proposal, proposal.copy())
            assertFalse(canConfirm(proposal))
        } finally {
            bitmap.recycle()
        }
    }
}
