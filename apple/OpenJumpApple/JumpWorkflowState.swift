import CoreGraphics
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

    private enum DeferredAction {
        case setup(Setup, UnitProfile, Locale)
        case file(URL, UnitProfile, Locale)
        case photos(PhotosPickerItem, UnitProfile, Locale)
        case discard
    }

    private let videoService = JumpVideoService()
    private var deferredAction: DeferredAction?
    private var importTask: Task<Void, Never>?
    private var frameTask: Task<Void, Never>?
    private var calculationTask: Task<Void, Never>?
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
        guard savedMeasurement == nil, !isSaving, !isFrameLoading, let video, let manifest, let presentedFrame,
              manifest.frames.indices.contains(frameIndex) else { return false }
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
        setup = next
        capturedUnits = app.preferences.units
        capturedLocale = app.preferences.effectiveLocale
        restartDraft()
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
        if savedMeasurement != nil || !isDirty {
            discardAnalysis()
        } else {
            deferredAction = .discard
            showDiscardConfirmation = true
        }
    }

    func confirmDiscard() {
        guard !isSaving else { return }
        let action = deferredAction
        deferredAction = nil
        showDiscardConfirmation = false
        switch action {
        case .setup(let next, let units, let locale):
            // Setup changes are accepted only after marks from the prior context are discarded.
            setup = next
            capturedUnits = units; capturedLocale = locale
            restartDraft()
        case .file(let url, let units, let locale): beginImport(.file(url, units, locale))
        case .photos(let item, let units, let locale): beginImport(.photos(item, units, locale))
        case .discard, nil: discardAnalysis()
        }
    }

    func cancelDiscard() {
        guard !isSaving else { return }
        deferredAction = nil
        showDiscardConfirmation = false
    }

    private func beginImport(_ action: DeferredAction) {
        guard !isSaving else { return }
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
                default: return
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
                selectedEvent = TemporalJumpDraft.requiredEvents(for: setup.protocolKey).first
                isIndexing = false
                sessionKey = UUID().uuidString
                requestFrame(0)
            } catch {
                candidate?.dispose()
                guard request == sourceGeneration, !Task.isCancelled else { return }
                isImporting = false; isIndexing = false; errorKey = "jumps.error.video"
            }
        }
    }

    private func discardAnalysis() {
        guard !isSaving else { return }
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

    func requestFrame(_ index: Int) {
        guard !isSaving, let video, let manifest, manifest.sourceID == video.id,
              manifest.frames.indices.contains(index), savedMeasurement == nil else { return }
        frameGeneration += 1
        let request = frameGeneration
        let sourceID = video.id
        let expectedPTS = manifest.frames[index].ptsUs
        frameTask?.cancel()
        frameIndex = index
        presentedFrame = nil
        isFrameLoading = true
        errorKey = nil
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

    func moveFrame(by offset: Int) {
        guard !isSaving, let manifest else { return }
        let target = min(max(frameIndex + offset, 0), manifest.frames.count - 1)
        if target != frameIndex { requestFrame(target) }
    }

    func selectEvent(_ kind: JumpEventKind) {
        guard !isSaving, savedMeasurement == nil, requiredEvents.contains(kind) else { return }
        selectedEvent = kind
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
