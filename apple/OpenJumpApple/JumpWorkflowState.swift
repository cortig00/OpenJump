import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation
import PhotosUI
import SwiftUI

@MainActor
final class JumpWorkflowState: ObservableObject {
    struct Setup: Equatable {
        var protocolKey: SavedProtocol = .cmj
        var ownerID: UUID?
        var side = "LEFT"
        var dropHeightInput = ""
    }

    @Published private(set) var setup = Setup()
    @Published private(set) var video: ImportedJumpVideo?
    @Published private(set) var manifest: JumpVideoManifest?
    @Published private(set) var presentedFrame: PresentedJumpFrame?
    @Published private(set) var frameIndex = 0
    @Published private(set) var events: [JumpEventMark] = []
    @Published private(set) var selectedEvent: JumpEventKind?
    @Published private(set) var realtimeDeclared = false
    @Published private(set) var notes = ""
    @Published private(set) var metrics: [SavedMetric]?
    @Published private(set) var savedMeasurement: SavedMeasurement?
    @Published private(set) var errorKey: String?
    @Published private(set) var isImporting = false
    @Published private(set) var isIndexing = false
    @Published private(set) var isFrameLoading = false
    @Published private(set) var isCalculating = false
    @Published private(set) var isSaving = false
    @Published var showDiscardConfirmation = false

    // MARK: - P1b native preview transport (NAV only; exact proof stays CG)

    private let playbackController = JumpVideoPlaybackController()
    @Published private(set) var playbackPhase: JumpVideoPlaybackController.Phase = .idle
    @Published private(set) var playbackReadiness: JumpVideoPlaybackController.Readiness = .none
    @Published private(set) var playbackWantsPlayback = false
    @Published private(set) var isPlaybackFailed = false
    @Published private(set) var isScrubbing = false
    @Published private(set) var scrubRequestedIndex = 0
    @Published private(set) var showsNativePreview = false
    @Published private(set) var isViewerActive = true
    @Published private(set) var nativePreviewIndex: Int? = nil

    var previewPlayer: AVPlayer? { playbackController.nativePlayer }
    var isTransportPlaying: Bool { playbackPhase == .playing }
    var isTransportBusy: Bool { playbackPhase == .seeking || isScrubbing }
    /// Toggle gate kept separate from `playbackEnabled` so exact ±1 steps keep
    /// working while readiness is unknown; pausing current playback (or a
    /// pending seek with intent) stays available even before ready.
    var canTogglePlayback: Bool {
        if playbackPhase == .playing || (playbackPhase == .seeking && playbackWantsPlayback) {
            return playbackEnabled
        }
        return playbackEnabled && playbackReadiness == .ready && !isPlaybackFailed && !isScrubbing
    }
    var playbackEnabled: Bool {
        guard savedMeasurement == nil, !isSaving, !isImporting, !isIndexing, !isCalculating,
              isViewerActive, let video, let manifest,
              manifest.sourceID == video.id, !manifest.frames.isEmpty else { return false }
        return true
    }

    init() {
        // Controller already calls back on MainActor; same-actor direct calls
        // avoid a second hop that could retarget stale sources between hops.
        playbackController.onChange = { [weak self] in self?.handlePlaybackChange() }
        playbackController.onEnd = { [weak self] in self?.handlePlaybackEnd() }
        playbackController.onFailure = { [weak self] in self?.handlePlaybackFailure() }
    }

    private func handlePlaybackChange() {
        let priorPhase = playbackPhase
        guard let video, let manifest, manifest.sourceID == video.id,
              playbackController.attachedSourceID == video.id else {
            playbackPhase = playbackController.phase
            playbackReadiness = playbackController.readiness
            playbackWantsPlayback = playbackController.wantsPlayback
            showsNativePreview = false
            nativePreviewIndex = nil
            return
        }
        playbackPhase = playbackController.phase
        playbackReadiness = playbackController.readiness
        playbackWantsPlayback = playbackController.wantsPlayback
        if playbackPhase == .failed { isPlaybackFailed = true }
        // Periodic ticks update labels only; never decode here.
        if let nativeTime = playbackController.currentNativeTime,
              (playbackPhase == .playing || playbackPhase == .seeking) {
            nativePreviewIndex = playbackController.navigationIndex(for: nativeTime, in: manifest)
            showsNativePreview = isViewerActive && nativePreviewIndex != nil
        } else if isScrubbing {
            // Gesture owns the target: never show stale backend pixels as the
            // requested frame and never decode per tick.
            showsNativePreview = false
        } else {
            // Paused exact review hides the native layer so only CG proof shows.
            showsNativePreview = false
            if playbackPhase != .playing && playbackPhase != .seeking { nativePreviewIndex = nil }
        }
        // Failed-seek settle (SEEKING→PAUSED): one exact CG restore at the kept
        // index. Periodic ticks never reach here; scrub/loading/presented/error
        // states suppress the restore so no stale decode is generated.
        if priorPhase == .seeking && (playbackPhase == .paused || playbackPhase == .idle) {
            restoreExactAfterSettle(manifest: manifest, video: video)
        }
    }

    /// One paused exact-CG restore after a transport settle. Requires a bound
    /// current source, the kept index and its PTS; never clears valid errors
    /// and never decodes per periodic tick.
    private func restoreExactAfterSettle(manifest: JumpVideoManifest, video: ImportedJumpVideo) {
        guard !isScrubbing, isViewerActive, savedMeasurement == nil, !isSaving,
              presentedFrame == nil, !isFrameLoading, errorKey == nil,
              manifest.sourceID == video.id,
              manifest.frames.indices.contains(frameIndex) else { return }
        requestFrameInternal(frameIndex)
    }

    private func handlePlaybackEnd() {
        // Genuine end already gated natively; settle to paused logical viewer.
        guard let video, let manifest, manifest.sourceID == video.id,
              playbackController.attachedSourceID == video.id else { return }
        playbackPhase = playbackController.phase
        playbackWantsPlayback = playbackController.wantsPlayback
        showsNativePreview = false
        // Keep the NAV index for the user; one paused exact CG restore brings
        // back proof at the resolved current-source index when the viewer is
        // active with unsaved work and no CG is already shown or loading.
        let kept: Int?
        if let nativeTime = playbackController.currentNativeTime,
           let resolved = playbackController.navigationIndex(for: nativeTime, in: manifest) {
            kept = min(max(resolved, 0), manifest.frames.count - 1)
            nativePreviewIndex = kept
        } else {
            kept = nil
            nativePreviewIndex = nil
        }
        guard let target = kept, isViewerActive, savedMeasurement == nil, !isSaving,
              !isScrubbing, presentedFrame == nil, !isFrameLoading, errorKey == nil,
              manifest.frames.indices.contains(target) else { return }
        requestFrameInternal(target)
    }

    private func handlePlaybackFailure() {
        guard let video, playbackController.attachedSourceID == video.id else { return }
        playbackPhase = playbackController.phase
        playbackReadiness = playbackController.readiness
        playbackWantsPlayback = false
        isPlaybackFailed = true
        showsNativePreview = false
        nativePreviewIndex = nil
    }

    private func ensurePlaybackAttached() {
        guard isViewerActive, savedMeasurement == nil, !isSaving,
              let video, let manifest, manifest.sourceID == video.id,
              !manifest.frames.isEmpty else { return }
        if playbackController.attachedSourceID == video.id { return }
        isPlaybackFailed = false
        playbackController.attach(video: video)
        handlePlaybackChange()
    }

    private func teardownPlaybackForSourceChange() {
        playbackController.teardown()
        playbackPhase = .idle
        playbackReadiness = .none
        playbackWantsPlayback = false
        showsNativePreview = false
        nativePreviewIndex = nil
        isScrubbing = false
    }

    private func pausePlaybackBackend() {
        playbackController.pause()
        playbackPhase = playbackController.phase
        playbackWantsPlayback = false
        showsNativePreview = false
    }

    func viewerAppeared() {
        isViewerActive = true
        ensurePlaybackAttached()
        // Resume shows the kept exact frame; never autoplays.
        if savedMeasurement == nil, video != nil, manifest != nil, presentedFrame == nil, !isFrameLoading {
            requestExactFrameAtKeptIndex()
        } else {
            handlePlaybackChange()
        }
    }

    func viewerDisappeared() {
        // Pause + detach observers; preserve every analysis artifact.
        // Intent first: deactivation before the synchronous pause callback so
        // no exact restore is generated while leaving.
        captureNativeIndexIntoSelection()
        isViewerActive = false
        isScrubbing = false
        playbackController.pause()
        playbackController.teardown()
        playbackPhase = .idle
        playbackReadiness = .none
        playbackWantsPlayback = false
        showsNativePreview = false
        nativePreviewIndex = nil
        frameTask?.cancel(); frameTask = nil
        isFrameLoading = false
        frameGeneration += 1
    }

    func suspendViewerForBackground() {
        guard isViewerActive else { return }
        captureNativeIndexIntoSelection()
        // Suspend intent before the synchronous pause callback: no restore
        // while backgrounded.
        isViewerActive = false
        isScrubbing = false
        playbackController.pause()
        playbackController.teardown()
        playbackPhase = .idle
        playbackReadiness = .none
        playbackWantsPlayback = false
        showsNativePreview = false
        nativePreviewIndex = nil
        frameTask?.cancel(); frameTask = nil
        isFrameLoading = false
        frameGeneration += 1
    }

    func resumeViewerFromBackground() {
        guard !isViewerActive else { return }
        isViewerActive = true
        ensurePlaybackAttached()
        if savedMeasurement == nil, video != nil, manifest != nil, presentedFrame == nil, !isFrameLoading {
            requestExactFrameAtKeptIndex()
        } else {
            handlePlaybackChange()
        }
    }

    private func captureNativeIndexIntoSelection() {
        guard let video, let manifest, manifest.sourceID == video.id,
              playbackController.attachedSourceID == video.id,
              let nativeTime = playbackController.currentNativeTime,
              (playbackPhase == .playing || playbackPhase == .seeking),
              let resolved = playbackController.navigationIndex(for: nativeTime, in: manifest),
              manifest.frames.indices.contains(resolved) else { return }
        frameIndex = resolved
        scrubRequestedIndex = resolved
        nativePreviewIndex = resolved
    }

    private func requestExactFrameAtKeptIndex() {
        guard let video, let manifest, manifest.sourceID == video.id,
              manifest.frames.indices.contains(frameIndex) else { return }
        requestFrameInternal(frameIndex)
    }

    func togglePlayback() {
        guard playbackEnabled, let video, let manifest, manifest.sourceID == video.id,
              manifest.frames.indices.contains(frameIndex) else { return }
        // Pending-seek cancel stays available even before ready.
        if playbackPhase == .playing || (playbackPhase == .seeking && playbackWantsPlayback) {
            pauseTransportAndShowExactAtNativeIndex()
            return
        }
        // Start from the CURRENT selected indexed time, never FPS math.
        // Never clear CG proof until the native backend/source/index is ready.
        ensurePlaybackAttached()
        guard playbackController.attachedSourceID == video.id,
              playbackController.readiness == .ready, playbackReadiness == .ready,
              !isPlaybackFailed, !isScrubbing else { return }
        // Clear stale CG proof + block marking BEFORE the native seek.
        frameGeneration += 1
        frameTask?.cancel(); frameTask = nil
        presentedFrame = nil
        isFrameLoading = false
        isPlaybackFailed = false
        errorKey = nil
        let targetTime = manifest.frames[frameIndex].time
        playbackController.play(from: targetTime)
        playbackPhase = playbackController.phase
        playbackWantsPlayback = playbackController.wantsPlayback
        handlePlaybackChange()
    }

    func retryPlaybackAttachment() {
        guard let video, let manifest, manifest.sourceID == video.id, isViewerActive,
              savedMeasurement == nil else { return }
        isPlaybackFailed = false
        errorKey = nil
        playbackController.teardown()
        playbackController.attach(video: video)
        handlePlaybackChange()
        requestExactFrameAtKeptIndex()
    }

    private func pauseTransportAndShowExactAtNativeIndex() {
        guard let video, let manifest, manifest.sourceID == video.id,
              playbackController.attachedSourceID == video.id else {
            pausePlaybackBackend()
            return
        }
        let resolved: Int?
        if let nativeTime = playbackController.currentNativeTime {
            resolved = playbackController.navigationIndex(for: nativeTime, in: manifest)
        } else { resolved = nil }
        let target = resolved.flatMap { manifest.frames.indices.contains($0) ? $0 : nil } ?? frameIndex
        guard manifest.frames.indices.contains(target) else {
            pausePlaybackBackend()
            return
        }
        // Intent before the synchronous pause callback so any auto-restore
        // targets the same index the explicit request below decodes.
        frameIndex = target
        scrubRequestedIndex = target
        pausePlaybackBackend()
        requestFrameInternal(target)
    }

    func beginScrubbing() {
        guard playbackEnabled else { return }
        // Scrub intent BEFORE the synchronous pause callback so it never
        // generates a stale exact restore mid-gesture.
        if !isScrubbing {
            isScrubbing = true
            scrubRequestedIndex = frameIndex
        }
        playbackController.pause()
        playbackPhase = playbackController.phase
        playbackWantsPlayback = false
        // Gesture start clears CG proof + blocks marking; no decode storm.
        frameGeneration += 1
        frameTask?.cancel(); frameTask = nil
        presentedFrame = nil
        isFrameLoading = false
        ensurePlaybackAttached()
        handlePlaybackChange()
        // Hide stale backend pixels during the drag; labels show the
        // requested target until endScrubbing decodes the exact CG.
        showsNativePreview = false
    }

    func updateScrubTarget(_ index: Int) {
        guard let manifest, manifest.frames.indices.contains(index) else { return }
        guard isScrubbing else { return }
        let clamped = min(max(index, 0), manifest.frames.count - 1)
        scrubRequestedIndex = clamped
        frameIndex = clamped
        // Track the target for labels/slider, but hide the stale native layer
        // so old pixels are never mislabeled as the new exact frame.
        nativePreviewIndex = clamped
        showsNativePreview = false
    }

    func endScrubbing() {
        guard isScrubbing else { return }
        isScrubbing = false
        showsNativePreview = false
        guard let manifest, manifest.frames.indices.contains(scrubRequestedIndex) else { return }
        requestFrameInternal(scrubRequestedIndex)
    }

    func stepFrameForAccessibility(by offset: Int) {
        guard let manifest, !manifest.frames.isEmpty else { return }
        guard savedMeasurement == nil, !isSaving else { return }
        // ±1 always pauses first, even on a clamped boundary.
        playbackController.pause()
        playbackPhase = playbackController.phase
        playbackWantsPlayback = false
        showsNativePreview = false
        let base = nativePreviewIndex ?? frameIndex
        let clampedBase = min(max(base, 0), manifest.frames.count - 1)
        let target = min(max(clampedBase + offset, 0), manifest.frames.count - 1)
        scrubRequestedIndex = target
        requestFrameInternal(target)
    }

    private func requestFrameInternal(_ index: Int) {
        guard !isSaving, isViewerActive, let video, let manifest, manifest.sourceID == video.id,
              manifest.frames.indices.contains(index), savedMeasurement == nil else { return }
        // Exact review hides the native layer while paused.
        showsNativePreview = false
        nativePreviewIndex = nil
        frameGeneration += 1
        let request = frameGeneration
        let sourceID = video.id
        let expectedPTS = manifest.frames[index].ptsUs
        frameTask?.cancel()
        frameIndex = index
        scrubRequestedIndex = index
        presentedFrame = nil
        isFrameLoading = true
        if errorKey == "jumps.error.frame" { errorKey = nil }
        frameTask = Task { [weak self] in
            guard let self else { return }
            do {
                let frame = try await videoService.frame(manifest: manifest, index: index)
                guard !Task.isCancelled, request == frameGeneration, self.video?.id == sourceID,
                      self.manifest?.sourceID == sourceID, frame.sourceID == sourceID,
                      frame.index == index, frame.ptsUs == expectedPTS else { return }
                presentedFrame = frame
                isFrameLoading = false
            } catch {
                guard request == frameGeneration, !Task.isCancelled, self.video?.id == sourceID else { return }
                presentedFrame = nil
                isFrameLoading = false
                errorKey = "jumps.error.frame"
            }
        }
    }

    private enum DeferredAction {
        case setup(Setup, UnitProfile, Locale)
        case file(URL, UnitProfile, Locale)
        case photos(PhotosPickerItem, UnitProfile, Locale)
        case captured(JumpVideoCaptureBorrower, UnitProfile, Locale)
        case discard
    }

    private let videoService = JumpVideoService()
    private var deferredAction: DeferredAction?
    private var importTask: Task<Void, Never>?
    private var frameTask: Task<Void, Never>?
    private var calculationTask: Task<Void, Never>?
    // C2a transactional captured-file Use: own request epoch/task separates old
    // source/frame/calculation epochs DURING staging. Old epochs stay intact
    // until SUCCESS commit; snapshot guards invalidate stale commits.
    private var capturedRequestGeneration = 0
    private var capturedImportTask: Task<Void, Never>?
    private var sourceGeneration = 0
    private var frameGeneration = 0
    private var calculationGeneration = 0
    private var sessionKey = UUID().uuidString
    private var capturedUnits = UnitProfile.metric
    private var capturedLocale = Locale.current
    private var calculatedDraft: TemporalJumpDraft?
    private var hasSynchronized = false

    var activeOwnerID: UUID? { setup.ownerID }
    var isBusy: Bool { isImporting || isIndexing || isSaving }
    var sourceNameKey: String? {
        switch video?.source {
        case .some(.photos): return "jumps.source.PHOTOS"
        case .some(.files): return "jumps.source.FILES"
        case .some(.camera): return "jumps.source.CAMERA"
        case nil: return nil
        }
    }
    var dropHeightUnit: String { capturedUnits.shortLength.rawValue }
    var dropHeightSummary: String {
        guard let centimeters = parsedDropHeight() else { return setup.dropHeightInput }
        return "\(MeasurementPresentation.shortLength(centimeters, as: capturedUnits.shortLength).formatted(.number.precision(.fractionLength(0...3)).locale(capturedLocale))) \(capturedUnits.shortLength.rawValue)"
    }
    var requiredEvents: [JumpEventKind] { TemporalJumpDraft.requiredEvents(for: setup.protocolKey) }
    var nextEventToMark: JumpEventKind? { requiredEvents.first { event(for: $0) == nil } }
    var canMarkDisplayedFrame: Bool {
        guard savedMeasurement == nil, !isSaving, !isFrameLoading, !isScrubbing, isViewerActive,
              let video, let manifest, let presentedFrame,
              manifest.frames.indices.contains(frameIndex) else { return false }
        // Transport fail-closed: never mark while previewing, seeking or failed.
        if playbackPhase == .playing || playbackPhase == .seeking || playbackPhase == .failed { return false }
        if playbackWantsPlayback { return false }
        // No blanket AVPlayer-readiness gate: a valid exact CG frame can mark
        // while the native layer stays hidden, even if readiness is unknown.
        return presentedFrame.sourceID == video.id && presentedFrame.index == frameIndex
            && presentedFrame.ptsUs == manifest.frames[frameIndex].ptsUs && manifest.sourceID == video.id
    }
    var canAttemptCalculation: Bool {
        video != nil && manifest != nil && savedMeasurement == nil && !isCalculating && !isSaving
    }
    var canCalculate: Bool {
        guard canAttemptCalculation, setup.ownerID != nil, realtimeDeclared,
              events.count == requiredEvents.count else { return false }
        do { try makeDraft().validate(); return true } catch { return false }
    }
    func canCalculate(for app: AppState) -> Bool {
        guard canCalculate, let ownerID = setup.ownerID else { return false }
        return app.athletes.contains { $0.id == ownerID && $0.archivedAt == nil }
    }
    /// Read-only Prepare → Obtain gate: active-owner setup parameters are
    /// valid and no import/index/save/calculation is in flight. Reuses the
    /// existing private `parsedSetupIsValid` plus the same active-roster
    /// owner check as `canCalculate(for:)`; never mutates workflow state.
    func canContinuePreparation(for app: AppState) -> Bool {
        guard savedMeasurement == nil, !isBusy, !isCalculating, parsedSetupIsValid,
              let ownerID = setup.ownerID else { return false }
        return app.athletes.contains { $0.id == ownerID && $0.archivedAt == nil }
    }
    var hasUnsavedMarks: Bool {
        savedMeasurement == nil && (!events.isEmpty || realtimeDeclared || metrics != nil || !notes.isEmpty)
    }
    var isDirty: Bool { savedMeasurement == nil && (video != nil || hasUnsavedMarks) }

    func synchronize(with app: AppState) {
        guard !hasSynchronized else { return }
        hasSynchronized = true
        setup.ownerID = app.preferences.resolveActiveAthlete(in: app.athletes)
        capturedUnits = app.preferences.units
        capturedLocale = app.preferences.effectiveLocale
    }

    func requestProtocol(_ value: SavedProtocol, app: AppState) {
        var next = setup; next.protocolKey = value
        if value != .unilateral { next.side = "LEFT" }
        if value != .dropJump { next.dropHeightInput = "" }
        requestSetup(next, app: app)
    }

    func requestOwner(_ value: UUID?, app: AppState) {
        var next = setup; next.ownerID = value
        requestSetup(next, app: app)
    }

    func requestSide(_ value: String, app: AppState) {
        var next = setup; next.side = value
        requestSetup(next, app: app)
    }

    func requestDropHeight(_ value: String, app: AppState) {
        var next = setup; next.dropHeightInput = String(value.prefix(80))
        requestSetup(next, app: app)
    }

    private func requestSetup(_ next: Setup, app: AppState) {
        guard next != setup, !isSaving, savedMeasurement == nil else { return }
        if hasUnsavedMarks {
            deferredAction = .setup(next, app.preferences.units, app.preferences.effectiveLocale)
            showDiscardConfirmation = true
        } else {
            applySetup(next, app: app)
        }
    }

    private func applySetup(_ next: Setup, app: AppState) {
        // C2a: applied setup supersedes any pending camera staging. Cancel the
        // camera worker without explicit borrower release (drains via last-deinit
        // after real exit) and bump its epoch so a stale completion can never
        // commit. Clear camera-owned busy only when no Files/Photos import owns it.
        invalidateCapturedStagingForSupersedingChange(clearCameraOwnedBusy: true)
        pausePlaybackBackend()
        setup = next
        capturedUnits = app.preferences.units
        capturedLocale = app.preferences.effectiveLocale
        restartDraft()
    }

    /// C2a helper: superseding Files/Photos/discard/setup changes invalidate
    /// pending camera staging separately. Cancels the camera worker, bumps its
    /// epoch, drops only an unconsumed deferred captured handle (no worker yet).
    /// Never explicitly releases a worker-owned borrower and never disposes a
    /// staged candidate here; both drain via last-deinit after real worker exit.
    /// Busy flags are cleared only when asked AND no Files/Photos import owns
    /// them, so a stale camera exit can never clobber a newer operation.
    private func invalidateCapturedStagingForSupersedingChange(clearCameraOwnedBusy: Bool) {
        capturedRequestGeneration += 1
        capturedImportTask?.cancel()
        capturedImportTask = nil
        if case .captured = deferredAction {
            deferredAction = nil
            showDiscardConfirmation = false
        }
        if clearCameraOwnedBusy, importTask == nil {
            isImporting = false
            isIndexing = false
        }
    }

    func preferenceContextChanged(app: AppState) {
        guard hasSynchronized, savedMeasurement == nil, !isSaving else { return }
        let units = app.preferences.units, locale = app.preferences.effectiveLocale
        let preferenceChanged = units != capturedUnits || locale.identifier != capturedLocale.identifier
        guard preferenceChanged else { return }
        var next = setup
        // Preserve the canonical drop height when its presentation context changes.
        if let centimeters = parsedDropHeight() {
            let converted = MeasurementPresentation.shortLength(centimeters, as: units.shortLength)
            next.dropHeightInput = converted.formatted(.number.precision(.fractionLength(0...3)).locale(locale))
        }
        if hasUnsavedMarks {
            deferredAction = .setup(next, units, locale)
            showDiscardConfirmation = true
        } else {
            invalidateCapturedStagingForSupersedingChange(clearCameraOwnedBusy: true)
            setup = next
            capturedUnits = units; capturedLocale = locale
            restartDraft()
        }
    }

    func requestPhotos(_ item: PhotosPickerItem, app: AppState) {
        requestImport(.photos(item, app.preferences.units, app.preferences.effectiveLocale))
    }

    func requestFile(_ url: URL, app: AppState) {
        requestImport(.file(url, app.preferences.units, app.preferences.effectiveLocale))
    }

    func reportVideoImportFailure() {
        guard !isSaving else { return }
        errorKey = "jumps.error.video"
    }

    private func requestImport(_ action: DeferredAction) {
        guard !isSaving else { return }
        if hasUnsavedMarks {
            deferredAction = action
            showDiscardConfirmation = true
        } else {
            beginImport(action)
        }
    }

    func cancelAnalysis() {
        guard !isSaving else { return }
        pausePlaybackBackend()
        if savedMeasurement != nil || !isDirty {
            discardAnalysis()
        } else {
            deferredAction = .discard
            showDiscardConfirmation = true
        }
    }

    func confirmDiscard() {
        guard !isSaving else { return }
        pausePlaybackBackend()
        let action = deferredAction
        deferredAction = nil
        showDiscardConfirmation = false
        switch action {
        case .setup(let next, let units, let locale):
            // Setup changes are accepted only after marks from the prior context are discarded.
            // C2a: confirmed setup supersedes any pending camera staging.
            invalidateCapturedStagingForSupersedingChange(clearCameraOwnedBusy: true)
            setup = next
            capturedUnits = units; capturedLocale = locale
            restartDraft()
        case .file(let url, let units, let locale): beginImport(.file(url, units, locale))
        case .photos(let item, let units, let locale): beginImport(.photos(item, units, locale))
        case .captured(let borrower, let units, let locale): beginCapturedImport(borrower, units: units, locale: locale)
        case .discard, nil: discardAnalysis()
        }
    }

    func cancelDiscard() {
        guard !isSaving else { return }
        deferredAction = nil
        showDiscardConfirmation = false
    }

    func requestCapturedFile(borrower: JumpVideoCaptureBorrower, app: AppState) {
        // C2a real entry for transactional captured-video Use (future C2b calls
        // this with a fresh borrower from the recorded lease; preview owns a
        // DIFFERENT borrower). Dirty replacement requires explicit confirmation
        // BEFORE any copy/index starts; confirmation authorizes replacement only
        // on SUCCESS, never early old-analysis erase. Block while saving, saved,
        // or another import/index/calculation is in flight to avoid competing tasks.
        guard !isSaving, savedMeasurement == nil else { return }
        guard !isImporting, !isIndexing, !isCalculating else { return }
        guard capturedImportTask == nil else { return }
        guard borrower.fileURL != nil else {
            errorKey = "jumps.error.video"
            return
        }
        if hasUnsavedMarks {
            deferredAction = .captured(borrower, app.preferences.units, app.preferences.effectiveLocale)
            showDiscardConfirmation = true
            return
        }
        beginCapturedImport(borrower, units: app.preferences.units, locale: app.preferences.effectiveLocale)
    }

    /// C2a explicit camera-staging cancel for future C2b. Preserves old analysis,
    /// never discards unrelated Files/Photos/setup deferred, never touches an
    /// unrelated Files/Photos import task. Drops only an unconsumed deferred
    /// captured handle; never explicitly releases a worker-owned active handle
    /// (it drains via last-deinit after real worker exit). Never disposes a
    /// staged candidate here.
    func cancelCapturedImport() {
        guard !isSaving else { return }
        if case .captured = deferredAction {
            deferredAction = nil
            showDiscardConfirmation = false
        }
        if capturedImportTask != nil {
            capturedRequestGeneration += 1
            capturedImportTask?.cancel()
            capturedImportTask = nil
            if importTask == nil {
                isImporting = false
                isIndexing = false
            }
        }
    }

    private func beginCapturedImport(_ borrower: JumpVideoCaptureBorrower, units: UnitProfile, locale: Locale) {
        guard !isSaving, savedMeasurement == nil, !isImporting, !isIndexing, !isCalculating else { return }
        guard capturedImportTask == nil else { return }
        guard borrower.fileURL != nil else {
            errorKey = "jumps.error.video"
            return
        }
        capturedRequestGeneration += 1
        let request = capturedRequestGeneration
        let snapVideoID = video?.id
        let snapSourceGen = sourceGeneration
        let snapSetup = setup
        let snapSession = sessionKey
        let snapCalcGen = calculationGeneration
        let snapEvents = events
        let snapNotes = notes
        let snapRealtime = realtimeDeclared
        let requestedUnits = units
        let requestedLocale = locale
        // Busy reports copy/index; old source stays attached (no teardown,
        // no dispose, no draft clear) until SUCCESS commit.
        isImporting = true
        isIndexing = false
        capturedImportTask = Task { [weak self] in
            guard let self else { return }
            // Staged candidate owned by this task only. NEVER explicit dispose
            // while the index worker may still read it: dispose deletes the
            // directory despite strong readers. Abandon by dropping refs;
            // last-deinit removes the own copy after worker/manifest drains.
            do {
                let candidate = try await JumpVideoImporter.importCapturedFile(borrower)
                guard !Task.isCancelled, request == self.capturedRequestGeneration else { return }
                guard snapVideoID == self.video?.id,
                      snapSourceGen == self.sourceGeneration,
                      snapSetup == self.setup,
                      snapSession == self.sessionKey,
                      snapCalcGen == self.calculationGeneration,
                      snapEvents == self.events,
                      snapNotes == self.notes,
                      snapRealtime == self.realtimeDeclared,
                      self.savedMeasurement == nil,
                      !self.isSaving else { return }
                self.isImporting = false
                self.isIndexing = true
                let indexed = try await self.videoService.inspect(candidate)
                guard !Task.isCancelled, request == self.capturedRequestGeneration else { return }
                guard snapVideoID == self.video?.id,
                      snapSourceGen == self.sourceGeneration,
                      snapSetup == self.setup,
                      snapSession == self.sessionKey,
                      snapCalcGen == self.calculationGeneration,
                      snapEvents == self.events,
                      snapNotes == self.notes,
                      snapRealtime == self.realtimeDeclared,
                      self.savedMeasurement == nil,
                      !self.isSaving,
                      self.importTask == nil else { return }
                guard candidate.source == .camera,
                      indexed.sourceID == candidate.id,
                      (2...250_000).contains(indexed.frames.count) else {
                    throw TemporalJumpError.invalidSetup
                }
                // SUCCESS-ONLY commit, no await between validation and commit.
                // Detach old player/observers FIRST, then invalidate old epochs,
                // retire old owned copy by dropping refs (no forced dispose under
                // live readers), adopt requested prefs, assign new camera source.
                self.teardownPlaybackForSourceChange()
                self.frameTask?.cancel(); self.frameTask = nil
                self.calculationTask?.cancel(); self.calculationTask = nil
                self.sourceGeneration += 1
                self.frameGeneration += 1
                self.calculationGeneration += 1
                self.adoptPreferences(units: requestedUnits, locale: requestedLocale)
                self.video = candidate
                self.manifest = indexed
                self.events = []
                self.metrics = nil
                self.calculatedDraft = nil
                self.notes = ""
                self.realtimeDeclared = false
                self.presentedFrame = nil
                self.frameIndex = 0
                self.scrubRequestedIndex = 0
                self.selectedEvent = TemporalJumpDraft.requiredEvents(for: self.setup.protocolKey).first
                self.sessionKey = UUID().uuidString
                self.errorKey = nil
                self.isImporting = false
                self.isIndexing = false
                self.isFrameLoading = false
                self.isCalculating = false
                self.capturedImportTask = nil
                if self.isViewerActive {
                    self.ensurePlaybackAttached()
                    self.requestFrameInternal(0)
                }
            } catch is CancellationError {
                guard request == self.capturedRequestGeneration, self.importTask == nil else { return }
                self.isImporting = false
                self.isIndexing = false
                self.capturedImportTask = nil
            } catch {
                guard request == self.capturedRequestGeneration, self.importTask == nil else { return }
                self.isImporting = false
                self.isIndexing = false
                self.capturedImportTask = nil
                self.errorKey = "jumps.error.video"
            }
        }
    }

    private func beginImport(_ action: DeferredAction) {
        guard !isSaving else { return }
        // C2a: superseding Files/Photos import invalidates pending camera staging.
        // Cancel camera worker (drains via last-deinit, no explicit release/dispose)
        // and bump its epoch so stale camera can never commit. Leave busy flags
        // for this new import to own; stale camera exit must not clear them.
        capturedRequestGeneration += 1
        capturedImportTask?.cancel()
        capturedImportTask = nil
        if case .captured = deferredAction {
            deferredAction = nil
            showDiscardConfirmation = false
        }
        teardownPlaybackForSourceChange()
        switch action {
        case .file(_, let units, let locale), .photos(_, let units, let locale):
            adoptPreferences(units: units, locale: locale)
        default: break
        }
        sourceGeneration += 1
        let request = sourceGeneration
        frameGeneration += 1
        frameTask?.cancel(); frameTask = nil
        importTask?.cancel(); importTask = nil
        calculationTask?.cancel(); calculationTask = nil
        calculationGeneration += 1
        video?.dispose()
        video = nil; manifest = nil; presentedFrame = nil; events = []; metrics = nil
        savedMeasurement = nil; calculatedDraft = nil; notes = ""; realtimeDeclared = false
        frameIndex = 0; selectedEvent = nil; errorKey = nil
        isFrameLoading = false; isIndexing = false; isImporting = true; isCalculating = false
        sessionKey = UUID().uuidString
        importTask = Task { [weak self] in
            guard let self else { return }
            var candidate: ImportedJumpVideo?
            do {
                switch action {
                case .file(let url, _, _): candidate = try await JumpVideoImporter.importFile(url)
                case .photos(let item, _, _): candidate = try await JumpVideoImporter.importPhotos(item)
                default:
                    // Unreachable Files/Photos-only entry, but fail closed on the
                    // owned generation so a completed handle never lingers.
                    if request == sourceGeneration {
                        isImporting = false; isIndexing = false; importTask = nil
                    }
                    return
                }
                guard !Task.isCancelled, request == sourceGeneration, let candidate else {
                    candidate?.dispose(); return
                }
                isImporting = false
                isIndexing = true
                let indexed = try await videoService.inspect(candidate)
                guard !Task.isCancelled, request == sourceGeneration else {
                    candidate.dispose(); return
                }
                guard indexed.sourceID == candidate.id, indexed.frames.count >= 2,
                      indexed.frames.count <= 250_000 else {
                    candidate.dispose(); throw TemporalJumpError.invalidSetup
                }
                video = candidate
                manifest = indexed
                frameIndex = 0
                scrubRequestedIndex = 0
                selectedEvent = TemporalJumpDraft.requiredEvents(for: setup.protocolKey).first
                isIndexing = false
                // Completed Files/Photos handle cleanup, gated strictly by
                // sourceGeneration ownership so a stale completion never
                // clobbers a newer import. No live-worker inference: cancelled
                // or superseded generations return above without clearing.
                if request == sourceGeneration { importTask = nil }
                sessionKey = UUID().uuidString
                if isViewerActive { ensurePlaybackAttached() }
                requestFrameInternal(0)
            } catch {
                candidate?.dispose()
                guard request == sourceGeneration, !Task.isCancelled else { return }
                isImporting = false; isIndexing = false; importTask = nil; errorKey = "jumps.error.video"
            }
        }
    }

    private func discardAnalysis() {
        guard !isSaving else { return }
        // C2a: direct discard supersedes pending camera staging (same epoch/cancel
        // rules as beginImport; this discard owns busy clearing below).
        capturedRequestGeneration += 1
        capturedImportTask?.cancel()
        capturedImportTask = nil
        if case .captured = deferredAction {
            deferredAction = nil
            showDiscardConfirmation = false
        }
        teardownPlaybackForSourceChange()
        sourceGeneration += 1; frameGeneration += 1; calculationGeneration += 1
        importTask?.cancel(); frameTask?.cancel(); calculationTask?.cancel()
        importTask = nil; frameTask = nil; calculationTask = nil
        video?.dispose()
        video = nil; manifest = nil; presentedFrame = nil; events = []; metrics = nil
        savedMeasurement = nil; calculatedDraft = nil; notes = ""; realtimeDeclared = false
        frameIndex = 0; selectedEvent = nil; errorKey = nil
        isImporting = false; isIndexing = false; isFrameLoading = false; isCalculating = false
        sessionKey = UUID().uuidString
    }

    /// Starts one new independent trial reusing the confirmed video lease.
    ///
    /// Guarded to confirmed saves only: requires `savedMeasurement != nil` and
    /// no import/index/save in flight, plus a valid source-bound manifest.
    /// Never discards unsaved marks (no-op without a confirmed save), never
    /// disposes or reimports the owned video, never touches stored rows.
    /// Draft state clears through the existing `restartDraft` path with a
    /// fresh session key; the owner resolves explicitly from current
    /// prefs/roster (nil when no single active owner, so calculate stays
    /// blocked until the user creates one). Units/locale follow the existing
    /// conversion semantics only when the context actually changed, so the
    /// drop-height input keeps its verbatim precision otherwise. The frame
    /// position is kept to analyse the next jump in the same video, then the
    /// exact frame at the kept index is re-requested under the normal
    /// source-identity guards; events stay empty until the user marks again.
    func startAnotherTrial(using app: AppState) {
        guard savedMeasurement != nil, !isSaving, !isImporting, !isIndexing,
              let video, let manifest, manifest.sourceID == video.id,
              (2...250_000).contains(manifest.frames.count) else { return }
        pausePlaybackBackend()
        restartDraft()
        let units = app.preferences.units
        let locale = app.preferences.effectiveLocale
        let contextChanged = units != capturedUnits || locale.identifier != capturedLocale.identifier
        var next = setup
        next.ownerID = app.preferences.resolveActiveAthlete(in: app.athletes)
        if contextChanged {
            if let centimeters = parsedDropHeight() {
                let displayed = MeasurementPresentation.shortLength(centimeters, as: units.shortLength)
                next.dropHeightInput = displayed.formatted(.number.precision(.fractionLength(0...3)).locale(locale))
            }
            setup = next
            capturedUnits = units
            capturedLocale = locale
        } else {
            setup = next
        }
        let kept = min(max(frameIndex, 0), manifest.frames.count - 1)
        scrubRequestedIndex = kept
        if isViewerActive { ensurePlaybackAttached() }
        requestFrameInternal(kept)
    }

    func requestFrame(_ index: Int) {
        // Public exact path always pauses transport first; periodic callbacks
        // never re-enter here, so no recursive seek loop is possible.
        pausePlaybackBackend()
        guard !isScrubbing else {
            // Scrub gesture owns decoding; without a gesture this is an
            // accessible step and still needs one exact decode.
            requestFrameInternal(index)
            return
        }
        requestFrameInternal(index)
    }

    func moveFrame(by offset: Int) {
        guard !isSaving, let manifest else { return }
        guard savedMeasurement == nil else { return }
        // ±1 steps pause transport and decode from the current NAV position.
        if isScrubbing { endScrubbing() }
        let base = (playbackPhase == .playing || playbackPhase == .seeking) ? (nativePreviewIndex ?? frameIndex) : frameIndex
        let clampedBase = min(max(base, 0), manifest.frames.count - 1)
        let target = min(max(clampedBase + offset, 0), manifest.frames.count - 1)
        pausePlaybackBackend()
        if target != frameIndex || offset != 0 { requestFrameInternal(target) }
    }

    func selectEvent(_ kind: JumpEventKind) {
        guard !isSaving, savedMeasurement == nil, requiredEvents.contains(kind) else { return }
        selectedEvent = kind
    }

    /// Read-only review helper: selects the kind, then re-requests the
    /// existing mark's exact source index after pausing. Never infers a mark
    /// from player PTS/FPS and never mutates or stores events.
    func reviewEvent(_ kind: JumpEventKind) {
        guard !isSaving, savedMeasurement == nil, isViewerActive, !isScrubbing,
              requiredEvents.contains(kind),
              let video, let manifest, manifest.sourceID == video.id else { return }
        selectedEvent = kind
        guard let mark = event(for: kind),
              manifest.frames.indices.contains(mark.frameIndex),
              manifest.frames[mark.frameIndex].ptsUs == mark.ptsUs else { return }
        pausePlaybackBackend()
        requestFrameInternal(mark.frameIndex)
    }

    func markSelectedEvent() {
        guard !isSaving, canMarkDisplayedFrame, let selectedEvent, let manifest,
              requiredEvents.contains(selectedEvent), manifest.frames.indices.contains(frameIndex) else { return }
        let frame = manifest.frames[frameIndex]
        guard let video, frame.ptsUs >= manifest.originUs else { return }
        let mark = JumpEventMark(kind: selectedEvent, frameIndex: frameIndex, ptsUs: frame.ptsUs,
                                 previousPtsUs: frameIndex > 0 ? manifest.frames[frameIndex - 1].ptsUs : nil,
                                 nextPtsUs: frameIndex + 1 < manifest.frames.count ? manifest.frames[frameIndex + 1].ptsUs : nil)
        guard video.id == manifest.sourceID else { return }
        replaceEvent(mark)
        invalidateCalculation(); sessionKey = UUID().uuidString; errorKey = nil
        self.selectedEvent = nextEventToMark ?? selectedEvent
    }

    func clearEvent(_ kind: JumpEventKind) {
        guard !isSaving, savedMeasurement == nil else { return }
        let before = events.count
        events.removeAll { $0.kind == kind }
        guard events.count != before else { return }
        selectedEvent = kind
        invalidateCalculation(); sessionKey = UUID().uuidString; errorKey = nil
    }

    func clearMarks() {
        guard !isSaving, savedMeasurement == nil, !events.isEmpty else { return }
        events.removeAll(); selectedEvent = requiredEvents.first
        invalidateCalculation(); sessionKey = UUID().uuidString; errorKey = nil
    }

    private func replaceEvent(_ mark: JumpEventMark) {
        events.removeAll { $0.kind == mark.kind }
        let order = Dictionary(uniqueKeysWithValues: requiredEvents.enumerated().map { ($0.element, $0.offset) })
        events.append(mark)
        events.sort { (order[$0.kind] ?? 0) < (order[$1.kind] ?? 0) }
    }

    func event(for kind: JumpEventKind) -> JumpEventMark? { events.first { $0.kind == kind } }

    func setRealtimeDeclared(_ value: Bool) {
        guard !isSaving, savedMeasurement == nil, realtimeDeclared != value else { return }
        realtimeDeclared = value
        if invalidateCalculation() { sessionKey = UUID().uuidString }
        errorKey = nil
    }

    func setNotes(_ value: String) {
        guard !isSaving, savedMeasurement == nil else { return }
        let next = String(value.prefix(500))
        guard next != notes else { return }
        notes = next
        if invalidateCalculation() { sessionKey = UUID().uuidString }
    }

    func calculate(using app: AppState) {
        guard savedMeasurement == nil, !isCalculating, !isSaving else { return }
        guard realtimeDeclared else { errorKey = "jumps.error.timing"; return }
        guard let ownerID = setup.ownerID,
              app.athletes.contains(where: { $0.id == ownerID && $0.archivedAt == nil }),
              parsedSetupIsValid else { errorKey = "jumps.error.setup"; return }
        guard canBuildValidDraft else { errorKey = "jumps.error.events"; return }
        let draft: TemporalJumpDraft
        do { draft = try makeDraft(); try draft.validate() }
        catch { errorKey = "jumps.error.events"; return }
        calculationGeneration += 1
        let request = calculationGeneration
        calculationTask?.cancel()
        isCalculating = true; metrics = nil; calculatedDraft = nil; errorKey = nil
        calculationTask = Task { [weak self] in
            do {
                let values = try await Task.detached(priority: .userInitiated) {
                    try TemporalJumpEngine.calculate(draft: draft)
                }.value
                guard let self, request == calculationGeneration, !Task.isCancelled else { return }
                metrics = values
                calculatedDraft = draft
                isCalculating = false
            } catch {
                guard let self, request == calculationGeneration, !Task.isCancelled else { return }
                metrics = nil; calculatedDraft = nil; isCalculating = false
                errorKey = "jumps.error.events"
            }
        }
    }

    func save(using app: AppState, openHistory: () -> Void) async {
        guard !isSaving, savedMeasurement == nil, let store = app.store,
              let draft = calculatedDraft, metrics != nil else { return }
        // Immediate transport stop before the async commit; never autoplays after.
        pausePlaybackBackend()
        showsNativePreview = false
        nativePreviewIndex = nil
        do { try draft.validate() } catch { errorKey = "jumps.error.events"; return }
        isSaving = true; errorKey = nil
        defer { isSaving = false }
        do {
            let saved = try await store.saveTemporalJump(draft)
            // A committed record is immutable in this workflow, including after ambiguous retries.
            savedMeasurement = saved
            app.historyDidCommit()
            do { try await app.refresh() }
            catch { errorKey = "error.database" }
            openHistory()
        } catch {
            errorKey = "jumps.error.save"
        }
    }

    private var parsedSetupIsValid: Bool {
        guard setup.ownerID != nil else { return false }
        if setup.protocolKey == .unilateral { return setup.side == "LEFT" || setup.side == "RIGHT" }
        if setup.protocolKey == .dropJump { return parsedDropHeight() != nil }
        return true
    }

    private var canBuildValidDraft: Bool {
        guard video != nil, let manifest, manifest.sourceID == video?.id,
              events.map(\.kind) == requiredEvents else { return false }
        do { try makeDraft().validate(); return true } catch { return false }
    }

    private func parsedDropHeight() -> Double? {
        guard setup.protocolKey == .dropJump,
              let displayed = MeasurementPresentation.parsePositive(setup.dropHeightInput, locale: capturedLocale) else { return nil }
        let cm = MeasurementPresentation.centimeters(displayed, from: capturedUnits.shortLength)
        return cm.isFinite && cm > 0 ? cm : nil
    }

    private func adoptPreferences(units: UnitProfile, locale: Locale) {
        if let centimeters = parsedDropHeight() {
            let displayed = MeasurementPresentation.shortLength(centimeters, as: units.shortLength)
            setup.dropHeightInput = displayed.formatted(.number.precision(.fractionLength(0...3)).locale(locale))
        }
        capturedUnits = units; capturedLocale = locale
    }

    private func makeDraft() throws -> TemporalJumpDraft {
        guard let video, let manifest, manifest.sourceID == video.id else { throw TemporalJumpError.invalidSetup }
        let draft = TemporalJumpDraft(
            sessionKey: sessionKey,
            ownerID: try requireOwnerID(),
            protocolKey: setup.protocolKey,
            side: setup.protocolKey == .unilateral ? setup.side : nil,
            dropHeightCm: setup.protocolKey == .dropJump ? parsedDropHeight() : nil,
            recordedAt: Date(), notes: notes.isEmpty ? nil : notes,
            source: video.source, sourceFrameCount: manifest.frames.count,
            sourceOriginUs: manifest.originUs,
            temporalState: realtimeDeclared ? .realtimeDeclared : .unknown,
            events: events
        )
        return draft
    }

    private func requireOwnerID() throws -> UUID {
        guard let ownerID = setup.ownerID else { throw TemporalJumpError.invalidSetup }
        return ownerID
    }

    @discardableResult private func invalidateCalculation() -> Bool {
        let hadCalculation = isCalculating || metrics != nil || calculatedDraft != nil
        calculationGeneration += 1
        calculationTask?.cancel(); calculationTask = nil
        metrics = nil; calculatedDraft = nil; isCalculating = false
        return hadCalculation
    }

    private func restartDraft() {
        invalidateCalculation()
        events = []; metrics = nil; calculatedDraft = nil; savedMeasurement = nil
        realtimeDeclared = false; notes = ""; errorKey = nil
        selectedEvent = requiredEvents.first
        sessionKey = UUID().uuidString
    }
}
