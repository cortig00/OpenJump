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

// MARK: - Staged flow routes (value-only navigation)

/// Native staged route for Prepare → Obtain video → Analyse → Result.
///
/// Pure value type only: the single `JumpWorkflowState` stays at the
/// `JumpHomeView` root and the `NavigationStack` path owns nothing but
/// these values. No strings, state, physics, or persistence here; stage
/// titles resolve through the existing `jumps.flow.*` locale keys.
enum JumpFlowRoute: Hashable, CaseIterable {
    case prepare
    case obtainVideo
    case analyse
    case result
}

extension JumpWorkflowPresentation {
    /// Most useful valid route for existing workflow content, without
    /// clearing or inventing data. Priority: confirmed/calculated result
    /// first, then indexed analysis, then pending import/video, else
    /// preparation (including notes without a clip). Never returns
    /// `.result` without `hasResult`.
    static func resumeRoute(
        hasResult: Bool,
        hasManifest: Bool,
        hasVideoOrImportActivity: Bool
    ) -> JumpFlowRoute {
        if hasResult { return .result }
        if hasManifest { return .analyse }
        if hasVideoOrImportActivity { return .obtainVideo }
        return .prepare
    }

    /// Full native prefix chain to a target stage, so Back steps through
    /// each earlier stage instead of jumping to the catalog.
    static func path(to route: JumpFlowRoute) -> [JumpFlowRoute] {
        switch route {
        case .prepare: return [.prepare]
        case .obtainVideo: return [.prepare, .obtainVideo]
        case .analyse: return [.prepare, .obtainVideo, .analyse]
        case .result: return [.prepare, .obtainVideo, .analyse, .result]
        }
    }
}

// MARK: - Flow visibility gates (pure decisions for hidden-tab safety)

extension JumpWorkflowPresentation {
    /// Automatic Obtain video → Analyse advance is allowed only when the
    /// Jumps root is actually appeared, the scene is active, Obtain video is
    /// the visible route, and an indexed manifest is available. Hidden or
    /// inactive tabs, wrong stages, and missing content all fail closed, so
    /// hidden completions surface via explicit Continue/Resume instead of an
    /// automatic yank. The caller passes `scenePhase == .active` as
    /// `isActiveScene`; no UIKit/SwiftUI scene types enter pure code.
    static func shouldAutoAdvanceToAnalyse(
        isVisible: Bool,
        isActiveScene: Bool,
        visibleRoute: JumpFlowRoute?,
        manifestAvailable: Bool
    ) -> Bool {
        guard isVisible, isActiveScene, manifestAvailable else { return false }
        return visibleRoute == .obtainVideo
    }

    /// Automatic Analyse → Result advance under the same visibility gates,
    /// with Analyse as the expected visible route and calculated metrics as
    /// the content gate. Hidden/inactive/wrong-stage/missing results fail
    /// closed, leaving explicit Review result instead of an automatic push.
    static func shouldAutoAdvanceToResult(
        isVisible: Bool,
        isActiveScene: Bool,
        visibleRoute: JumpFlowRoute?,
        resultAvailable: Bool
    ) -> Bool {
        guard isVisible, isActiveScene, resultAvailable else { return false }
        return visibleRoute == .analyse
    }

    /// Viewer attaches only when the Jumps root is actually appeared, the
    /// scene is active, and Analyse is the visible route. Every other
    /// combination pauses without discarding artifacts. The caller passes
    /// `scenePhase == .active` as `isActiveScene`.
    static func shouldActivateViewer(
        isVisible: Bool,
        isActiveScene: Bool,
        visibleRoute: JumpFlowRoute?
    ) -> Bool {
        guard isVisible, isActiveScene else { return false }
        return visibleRoute == .analyse
    }

    /// Unit seam for the View confirm decision: an accepted pending catalog
    /// protocol routes to Prepare regardless of retained clip. Rejected
    /// (nil) or mismatched protocols yield nil so the caller trims instead.
    /// Clip emptiness is intentionally not consulted here; State owns
    /// acceptance and this maps acceptance to navigation only.
    static func routeForConfirmedCatalogProtocol(
        pending: SavedProtocol?,
        confirmed: SavedProtocol?
    ) -> JumpFlowRoute? {
        guard let pending, let confirmed, pending == confirmed else { return nil }
        return .prepare
    }
}

// MARK: - Camera entry + controls policy (C2b pure gates for unit tests + view)

/// Value-only camera control matrix for one capture phase. The Obtain-video
/// camera section renders from this; unit tests pin it. No borrowers,
/// players, URLs, or recording side effects live here — the facade/engine
/// own all capture behavior, and the C2a state entries own the Use commit.
struct JumpCameraControls: Equatable, Sendable {
    /// Record affordance (permission prepare on first need, then explicit
    /// start only when the facade reports ready). Never auto-records.
    var showsRecord: Bool
    /// Stop row visibility (live take, including the finalizing wait).
    var showsStop: Bool
    /// Stop enabled only while the take is live (starting/recording).
    /// Finalizing shows a disabled Stop: the delegate owns completion and a
    /// second stop is rejected by policy.
    var stopEnabled: Bool
    /// Use affordance: finalized recorded candidate only, never finalizing.
    var canUse: Bool
    /// Repeat / new-recording affordance: ready, recorded, or failed only.
    var canRepeat: Bool
    /// Retry affordance for denied/unavailable/failed (explicit prepare).
    var showsRetry: Bool
    /// Cancel affordance: clears the camera attempt, never the old analysis.
    var showsCancel: Bool
    /// Live capture preview host visibility (session render, not review).
    var showsLivePreview: Bool
    /// Finalized-candidate review player visibility.
    var showsReview: Bool
}

/// Explicit first-tap intent for the camera record row. `.openCamera`
/// prepares only (idle), `.record` starts only (ready). Pure value so the
/// host split and unit tests agree; never auto-records.
enum JumpCameraFirstAction: Equatable, Sendable {
    case openCamera
    case record
}

extension JumpCameraControls {
    /// Additive C2b control labels (8 locales) plus the bounded iOS16-XR
    /// repair split: idle shows the explicit open label, ready shows the
    /// explicit record label. Status/error text reuses the existing
    /// `jumps.camera.*` keys; these five are the only new keys.
    static let openKey = "jumps.camera.open"
    static let recordKey = "jumps.camera.record"
    static let stopKey = "jumps.camera.stop"
    static let useRecordingKey = "jumps.camera.useRecording"
    static let repeatKey = "jumps.camera.repeat"
    /// Exact additive key surface C2b introduces plus the one bounded
    /// open-camera repair key. Unit tests pin this set so no
    /// mic/gallery/InfoPlist key can slip in through the camera slice.
    static var newControlKeys: [String] {
        [openKey, recordKey, stopKey, useRecordingKey, repeatKey]
    }
}

extension JumpWorkflowPresentation {
    /// Camera-section eligibility for explicit user actions (Record/Retry
    /// taps): the Jumps root must actually be appeared, the scene ACTIVE,
    /// and Obtain video the visible route. A transient inactive permission
    /// alert is NOT eligible for a NEW tap; hidden tabs, wrong routes, and
    /// background all fail closed. The caller passes
    /// `scenePhase == .active` as `isActiveScene`; no UIKit/SwiftUI scene
    /// types enter pure code. Viewer gate unchanged.
    static func isCameraEligible(
        isVisible: Bool,
        isActiveScene: Bool,
        visibleRoute: JumpFlowRoute?
    ) -> Bool {
        guard isVisible, isActiveScene else { return false }
        return visibleRoute == .obtainVideo
    }

    /// Camera lifetime for preview + facade viewAppeared/viewDisappeared:
    /// the Jumps root appeared, true OS foreground (active OR inactive),
    /// and Obtain video the visible route. A transient inactive native
    /// permission alert KEEPS lifetime (still foreground) so the
    /// originating request ticket survives the grant callback; true
    /// background, hidden tabs, and wrong routes fail closed and MUST
    /// invalidate. The caller passes `scenePhase != .background` as
    /// `isForegroundScene`; no UIKit/SwiftUI scene types enter pure code.
    /// Explicit user actions stay active-only via `isCameraEligible`.
    static func isCameraLifetimeVisible(
        isVisible: Bool,
        isForegroundScene: Bool,
        visibleRoute: JumpFlowRoute?
    ) -> Bool {
        guard isVisible, isForegroundScene else { return false }
        return visibleRoute == .obtainVideo
    }

    /// First-tap decision for the explicit camera button: idle opens the
    /// camera (prepare ONLY), ready records (start ONLY). Every other
    /// phase has no first action; nothing here auto-records on grant,
    /// ready, or resume. The host switches on this (never a fused
    /// prepare+start), and both branches remain explicit-tap + policy
    /// gated at the engine.
    static func cameraFirstAction(for phase: JumpVideoCapturePhase) -> JumpCameraFirstAction? {
        switch phase {
        case .idle:
            return .openCamera
        case .ready:
            return .record
        case .requestingPermission, .preparing, .starting, .recording,
             .finalizing, .recorded, .denied, .unavailable, .failed:
            return nil
        }
    }

    /// Explicit button label for the record row: idle shows the new open
    /// key, ready shows the existing record key. Non-record phases have no
    /// label (the row is hidden by the control matrix). The host renders
    /// from this so unit tests pin the exact split.
    static func recordButtonKey(for phase: JumpVideoCapturePhase) -> String? {
        switch phase {
        case .idle:
            return JumpCameraControls.openKey
        case .ready:
            return JumpCameraControls.recordKey
        case .requestingPermission, .preparing, .starting, .recording,
             .finalizing, .recorded, .denied, .unavailable, .failed:
            return nil
        }
    }

    /// Pure phase → control matrix for the camera section. Mirrors the
    /// facade/engine policy exactly:
    /// - idle/ready: Record (prepare on first need, start only when ready).
    /// - starting/recording: Stop enabled, never Use.
    /// - finalizing: disabled Stop + wait text, never Use, never Repeat.
    /// - recorded: review + Use (fresh borrower per tap) + Repeat + Cancel.
    /// - denied/unavailable/failed: Retry + Cancel only (plus Repeat from
    ///   failed, which re-prepares only when the graph is live).
    /// Error phases expose no analysis-destructive action: Cancel preserves
    /// the old analysis (C2a guarantee) and Retry only re-prepares.
    static func cameraControls(for phase: JumpVideoCapturePhase) -> JumpCameraControls {
        switch phase {
        case .idle:
            return JumpCameraControls(showsRecord: true, showsStop: false, stopEnabled: false, canUse: false, canRepeat: false, showsRetry: false, showsCancel: false, showsLivePreview: true, showsReview: false)
        case .requestingPermission, .preparing:
            return JumpCameraControls(showsRecord: false, showsStop: false, stopEnabled: false, canUse: false, canRepeat: false, showsRetry: false, showsCancel: false, showsLivePreview: true, showsReview: false)
        case .ready:
            return JumpCameraControls(showsRecord: true, showsStop: false, stopEnabled: false, canUse: false, canRepeat: true, showsRetry: false, showsCancel: false, showsLivePreview: true, showsReview: false)
        case .starting, .recording:
            return JumpCameraControls(showsRecord: false, showsStop: true, stopEnabled: true, canUse: false, canRepeat: false, showsRetry: false, showsCancel: false, showsLivePreview: true, showsReview: false)
        case .finalizing:
            return JumpCameraControls(showsRecord: false, showsStop: true, stopEnabled: false, canUse: false, canRepeat: false, showsRetry: false, showsCancel: false, showsLivePreview: true, showsReview: false)
        case .recorded:
            return JumpCameraControls(showsRecord: false, showsStop: false, stopEnabled: false, canUse: true, canRepeat: true, showsRetry: false, showsCancel: true, showsLivePreview: false, showsReview: true)
        case .denied, .unavailable:
            return JumpCameraControls(showsRecord: false, showsStop: false, stopEnabled: false, canUse: false, canRepeat: false, showsRetry: true, showsCancel: true, showsLivePreview: false, showsReview: false)
        case .failed:
            return JumpCameraControls(showsRecord: false, showsStop: false, stopEnabled: false, canUse: false, canRepeat: true, showsRetry: true, showsCancel: true, showsLivePreview: false, showsReview: false)
        }
    }
}
