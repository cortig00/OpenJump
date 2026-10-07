import CoreGraphics
import Foundation

/// S4 pure frame-layout helper for the jump workflow.
///
/// Only aspect-fit math over already-known sizes: no video decoding, no
/// measurement, no state. The workflow view caps the rendered frame with
/// `maxFrameHeight` through native `scaledToFit`, so the exact rendered size
/// follows this same contract and stays testable without UIKit.
enum JumpWorkflowPresentation {
    /// Upper bound for the rendered exact-frame height. The previous fixed
    /// 460pt view dominated small phones vertically; this cap keeps the
    /// frame, its controls and the marking steps visible together.
    static let maxFrameHeight: CGFloat = 380
    /// Neutral placeholder height shown while no source-bound frame is
    /// displayed. It never claims to be a real frame.
    static let placeholderHeight: CGFloat = 240

    /// Aspect-fit size for a source image inside an available width capped by
    /// `maxHeight`. Never crops: the scale is the minimum of the width and
    /// height ratios. Invalid inputs fall back to a neutral placeholder width
    /// and `placeholderHeight` so callers never divide by zero or propagate
    /// non-finite sizes into layout.
    static func fittedFrameSize(
        imageWidth: CGFloat,
        imageHeight: CGFloat,
        availableWidth: CGFloat,
        maxHeight: CGFloat = maxFrameHeight
    ) -> CGSize {
        guard imageWidth.isFinite, imageHeight.isFinite,
              availableWidth.isFinite, maxHeight.isFinite,
              imageWidth > 0, imageHeight > 0,
              availableWidth > 0, maxHeight > 0 else {
            return CGSize(width: max(availableWidth, 0), height: placeholderHeight)
        }
        let scale = min(availableWidth / imageWidth, maxHeight / imageHeight)
        guard scale.isFinite, scale > 0 else {
            return CGSize(width: availableWidth, height: placeholderHeight)
        }
        return CGSize(width: imageWidth * scale, height: imageHeight * scale)
    }
}
