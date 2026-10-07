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

// MARK: - Video timing preview (display-only, S6 trust slice)

/// One complete VIDEO timeline interval derived from already-marked event PTS.
///
/// Display-only preview of raw video timestamp differences. It is not a
/// canonical measurement, not physical-clock validation, and carries no
/// height, velocity, or physics. Durations are raw PTS differences in
/// microseconds; `durationMs` is a display-only `durationUs / 1_000` value.
struct JumpTimingPreviewInterval: Equatable, Sendable {
    /// Stable identifier for UI and accessibility: "movement", "contact" or "flight".
    let id: String
    /// Existing canonical metric key reused only for its localized label via
    /// `metricName(_:language:)`. No `SavedMetric` is created here.
    let metricKey: String
    /// Raw start/end presentation timestamps in microseconds, exactly as marked.
    let startPtsUs: Int64
    let endPtsUs: Int64
    /// Raw duration in microseconds (`endPtsUs - startPtsUs`), overflow-checked.
    let durationUs: Int64
    /// Display-only milliseconds (`Double(durationUs) / 1_000`). No clock factor.
    var durationMs: Double { Double(durationUs) / 1_000.0 }
}

extension JumpWorkflowPresentation {
    /// Pure VIDEO interval preview for the analysis screen.
    ///
    /// - Parameters:
    ///   - protocolKey: Supported temporal protocol. Unsupported protocols
    ///     (horizontal, asymmetry) return empty.
    ///   - marks: Already-marked events. Inputs are never mutated.
    /// - Returns: Complete valid pairs only, in temporal order
    ///   (movement/contact then flight). Partial marks yield only the complete
    ///   pairs; duplicates, negative indices/PTS, non-increasing index/PTS,
    ///   or overflow-unsafe differences yield no interval for that pair
    ///   (duplicates yield empty overall). No zeros are invented.
    static func timingPreview(
        for protocolKey: SavedProtocol,
        marks: [JumpEventMark]
    ) -> [JumpTimingPreviewInterval] {
        guard TemporalJumpDraft.supportedProtocols.contains(protocolKey) else { return [] }
        // Fail closed on ambiguous duplicate kinds: never guess which mark wins.
        var seen = Set<JumpEventKind>()
        for mark in marks {
            if !seen.insert(mark.kind).inserted {
                return []
            }
        }
        func uniqueMark(_ kind: JumpEventKind) -> JumpEventMark? {
            marks.first { $0.kind == kind }
        }
        func validInterval(
            id: String,
            metricKey: String,
            start: JumpEventMark?,
            end: JumpEventMark?
        ) -> JumpTimingPreviewInterval? {
            guard let start, let end else { return nil }
            guard start.frameIndex >= 0, end.frameIndex >= 0,
                  start.ptsUs >= 0, end.ptsUs >= 0 else { return nil }
            guard start.frameIndex < end.frameIndex, start.ptsUs < end.ptsUs else { return nil }
            let (diff, overflow) = end.ptsUs.subtractingReportingOverflow(start.ptsUs)
            guard !overflow, diff > 0 else { return nil }
            return JumpTimingPreviewInterval(
                id: id,
                metricKey: metricKey,
                startPtsUs: start.ptsUs,
                endPtsUs: end.ptsUs,
                durationUs: diff
            )
        }
        let takeoff = uniqueMark(.takeoff)
        let landing = uniqueMark(.landing)
        let flight = validInterval(
            id: "flight",
            metricKey: "FLIGHT_TIME_MS",
            start: takeoff,
            end: landing
        )
        switch protocolKey {
        case .cmj, .abalakov, .unilateral:
            let movement = validInterval(
                id: "movement",
                metricKey: "TIME_TO_TAKEOFF_MS",
                start: uniqueMark(.movementStart),
                end: takeoff
            )
            return [movement, flight].compactMap { $0 }
        case .dropJump:
            let contact = validInterval(
                id: "contact",
                metricKey: "CONTACT_TIME_MS",
                start: uniqueMark(.initialContact),
                end: takeoff
            )
            return [contact, flight].compactMap { $0 }
        case .sj:
            return [flight].compactMap { $0 }
        case .horizontal, .asymmetry:
            return []
        }
    }
}
