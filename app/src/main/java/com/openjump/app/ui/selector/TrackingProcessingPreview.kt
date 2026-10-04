package com.openjump.app.ui.selector

import android.graphics.Bitmap
import android.graphics.Matrix
import com.openjump.app.tracking.TrackingFrameResult

internal const val MAX_TRACKING_PREVIEW_SIDE = 384

/** A display-only, owned colour copy paired with the exact offline tracking result.
 * Never recycle displayed copies manually: Compose/render-thread references can outlive a state update.
 * Only the latest copy is retained in UI state; neither snapshots nor video are persisted.
 */
data class TrackingProcessingPreview(
    val bitmap: Bitmap,
    val result: TrackingFrameResult,
)

/** The decoder recycles [borrowed] on returning from its callback, so identity transforms must copy too. */
internal fun copyTrackingPreview(borrowed: Bitmap, rotationDegrees: Int): Bitmap {
    val scale = minOf(1f, MAX_TRACKING_PREVIEW_SIDE.toFloat() / maxOf(borrowed.width, borrowed.height))
    val transform = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotationDegrees.toFloat())
    }
    val transformed = Bitmap.createBitmap(borrowed, 0, 0, borrowed.width, borrowed.height, transform, true)
    return if (transformed === borrowed) borrowed.copy(Bitmap.Config.ARGB_8888, false) else transformed
}

/** No future/off-frame point may be drawn over the player's still frame when a preview is unavailable. */
internal fun visibleTrackingResult(
    results: List<TrackingFrameResult>,
    frameIndex: Int?,
    ptsUs: Long?,
): TrackingFrameResult? = if (frameIndex == null || ptsUs == null) null else results.lastOrNull {
    it.frameIndex == frameIndex && it.result.sample.timestampUs == ptsUs
}
