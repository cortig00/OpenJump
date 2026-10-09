import AVFoundation
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Inline native preview layer (P1b). The view never owns transport:
/// no observers, seeks, cancels or disposal here. The state controller owns
/// the single AVPlayer; this wrapper only renders it aspect-fit.
struct NativePlayerLayer: UIViewRepresentable {
    let player: AVPlayer?

    func makeUIView(context: Context) -> PlayerBackedView {
        let view = PlayerBackedView()
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ uiView: PlayerBackedView, context: Context) {
        uiView.playerLayer.videoGravity = .resizeAspect
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
    }

    static func dismantleUIView(_ uiView: PlayerBackedView, coordinator: ()) {
        uiView.playerLayer.player = nil
    }

    final class PlayerBackedView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// Live capture preview host (C2b). Mirrors `NativePlayerLayer` teardown:
/// the view never owns capture — it only renders the readonly session handle
/// from `previewSessionForFutureHost`, and only on the main thread, via
/// `AVCaptureVideoPreviewLayer` (iOS 4.0 floor) with `.resizeAspectFill`.
/// The handle is never configured through this view: no begin/commit, no
/// add/remove, no device locks. Orientation stays on the existing guarded
/// engine seam (`updateVideoOrientation`, rejected while starting/recording/
/// finalizing); this host never touches `videoRotationAngle` (iOS 17).
/// Dismantle removes the layer and nils it BEFORE any borrower release or
/// facade dispose runs.
struct CameraPreviewHost: UIViewRepresentable {
    /// Readonly session handle from the facade. Nil renders black until the
    /// engine publishes a session.
    let session: AVCaptureSession?

    func makeUIView(context: Context) -> CameraPreviewContainerView {
        let view = CameraPreviewContainerView()
        view.backgroundColor = .black
        attach(session: session, to: view)
        return view
    }

    func updateUIView(_ uiView: CameraPreviewContainerView, context: Context) {
        // SwiftUI invokes representable methods on the main thread. Reattach
        // only when the session identity actually changed; never reconfigure.
        if uiView.previewLayer?.session !== session {
            attach(session: session, to: uiView)
        }
    }

    static func dismantleUIView(_ uiView: CameraPreviewContainerView, coordinator: ()) {
        uiView.previewLayer?.removeFromSuperlayer()
        uiView.previewLayer = nil
    }

    private func attach(session: AVCaptureSession?, to view: CameraPreviewContainerView) {
        view.previewLayer?.removeFromSuperlayer()
        view.previewLayer = nil
        guard let session else { return }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        view.previewLayer = layer
    }

    final class CameraPreviewContainerView: UIView {
        var previewLayer: AVCaptureVideoPreviewLayer?
        override func layoutSubviews() {
            super.layoutSubviews()
            previewLayer?.frame = bounds
        }
    }
}

/// Available content width for the analysis section (P2c adaptive layout).
/// Passive read-only geometry: defaults to 0 (single column until known).
/// Never persisted, never drives measurement or workflow state.
private struct AnalysisWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

@MainActor
struct JumpHomeView: View {
    @ObservedObject var state: AppState
    let openProfiles: () -> Void
    let openHistory: () -> Void
    @StateObject private var workflow = JumpWorkflowState()
    /// ONE persistent capture facade for the whole Home lifetime. It survives
    /// typed-route changes and is never re-created per appearance or per
    /// route; the camera section drives it with viewAppeared/viewDisappeared
    /// only on real visibility. `dispose()` is intentionally NOT called on
    /// transient disappear (it is terminal: engine released, never
    /// resurrected); parking via viewDisappeared preserves the draft, and the
    /// engine deinit removes its observers when Home truly deallocates.
    @StateObject private var camera = JumpVideoCaptureFacade()
    /// Review-playback borrower + player for the finalized candidate. The
    /// borrower is FRESH per preview attach and is released only AFTER the
    /// player/item is torn down; Use mints its own separate fresh borrower.
    @State private var reviewBorrower: JumpVideoCaptureBorrower?
    @State private var reviewPlayer: AVPlayer?
    @State private var isReviewPlaying = false
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showFileImporter = false
    /// Value-only staged route path. It owns no state, player, or leases:
    /// the single `workflow` StateObject above stays persistent while routes
    /// push and pop natively. Popping never discards media, marks, or notes.
    @State private var flowPath: [JumpFlowRoute] = []
    /// Catalog protocol tapped while a dirty draft holds the confirmation
    /// dialog open. Prepare is pushed only after `confirmDiscard` applies it.
    @State private var pendingCatalogProtocol: SavedProtocol?
    /// New-video request waiting on the same confirmation dialog. After the
    /// confirmed clip disposal it routes to Obtain video instead of catalog.
    @State private var pendingPostDiscardObtain = false
    /// Actual Jumps-tab visibility. Path-last alone cannot prove on-screen
    /// when tabs exist, so async auto-advance and viewer activation gate on
    /// this flag plus scene activity. Set true on root appear, false before
    /// pausing on root disappear. Never drives measurement or persistence.
    @State private var isJumpsVisible = false
    /// Local-only UI width for adaptive analysis layout. Never persisted,
    /// never touches workflow state, marks, or analysis math.
    @State private var analysisContentWidth: CGFloat = 0
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var language: AppLanguage { state.preferences.language }
    private var locale: Locale { state.preferences.effectiveLocale }
    private var activeAthletes: [Athlete] { state.athletes.filter { $0.archivedAt == nil } }
    private var requiredProtocolOptions: [SavedProtocol] { TemporalJumpDraft.supportedProtocols }

    var body: some View {
        NavigationStack(path: $flowPath) {
            catalogRoot
                .navigationTitle(AppText.string("tab.jumps", language: language))
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        flowCancelButton
                    }
                }
                .navigationDestination(for: JumpFlowRoute.self) { route in
                    stageDestination(for: route)
                }
        }
        .appLocale(state.preferences.language)
        .task { workflow.synchronize(with: state) }
        .onAppear {
            isJumpsVisible = true
            synchronizeViewerForVisibleRoute()
        }
        .onDisappear {
            isJumpsVisible = false
            workflow.viewerDisappeared()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                if JumpWorkflowPresentation.shouldActivateViewer(isVisible: isJumpsVisible, isActiveScene: true, visibleRoute: visibleRoute) { workflow.resumeViewerFromBackground() }
            }
            else if phase == .inactive || phase == .background { workflow.suspendViewerForBackground() }
        }
        .onChange(of: selectedPhoto) { item in
            if let item {
                workflow.requestPhotos(item, app: state)
                selectedPhoto = nil
            }
        }
        .onChange(of: state.preferences.units) { _ in workflow.preferenceContextChanged(app: state) }
        .onChange(of: state.preferences.language) { _ in workflow.preferenceContextChanged(app: state) }
        .onChange(of: state.preferences.selectedAthleteID) { id in
            if id != workflow.activeOwnerID { workflow.requestOwner(id, app: state) }
        }
        .onChange(of: flowPath) { _ in synchronizeViewerForVisibleRoute() }
        .onChange(of: workflow.video == nil) { _ in reconcilePathAfterStateChange() }
        .onChange(of: workflow.manifest == nil) { _ in
            // Successful indexing auto-advances ONLY while the Jumps root is
            // actually appeared, the scene is active, and Obtain video is the
            // visible stage; a user who went Back, to the catalog, or to
            // another tab mid-import is never yanked forward. Hidden
            // completions surface via explicit Continue/Resume instead.
            if workflow.manifest != nil {
                if JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: isJumpsVisible, isActiveScene: scenePhase == .active, visibleRoute: visibleRoute, manifestAvailable: workflow.manifest != nil) { pushRoute(.analyse) }
            } else {
                reconcilePathAfterStateChange()
            }
        }
        .onChange(of: workflow.metrics == nil) { _ in
            // Successful calculation auto-advances ONLY while the Jumps root
            // is actually appeared, the scene is active, and Analyse is
            // visible. Failed calculations keep metrics nil and stay put;
            // invalidated results pop back to a valid earlier stage. Hidden
            // completions surface via explicit Review result instead.
            if workflow.metrics != nil {
                if JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: isJumpsVisible, isActiveScene: scenePhase == .active, visibleRoute: visibleRoute, resultAvailable: workflow.metrics != nil) { pushRoute(.result) }
            } else if visibleRoute == .result {
                reconcilePathAfterStateChange()
            }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.movie]) { result in
            switch result {
            case .success(let url): workflow.requestFile(url, app: state)
            case .failure(let error):
                if (error as? CocoaError)?.code != .userCancelled {
                    workflow.reportVideoImportFailure()
                }
            }
        }
        .confirmationDialog(
            AppText.string("jumps.discard.title", language: language),
            isPresented: $workflow.showDiscardConfirmation,
            titleVisibility: .visible
        ) {
            Button(AppText.string("jumps.discard.confirm", language: language), role: .destructive) {
                confirmDiscardAndRoute()
            }.frame(minHeight: 48)
            Button(AppText.string("common.cancel", language: language), role: .cancel) {
                cancelDiscardAndStay()
            }.frame(minHeight: 48)
        } message: {
            Text(AppText.string("jumps.discard.body", language: language))
        }
    }

    /// Illustrated catalog root. The only screen without a route indicator;
    /// a single 48pt Resume CTA appears whenever retained work exists.
    /// Native Back from any stage returns here without discarding.
    private var catalogRoot: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                headerBlock
                catalogSection
                if hasResumeDraft {
                    Button {
                        resumeToBestRoute()
                    } label: {
                        Label(AppText.string("jumps.flow.resume", language: language), systemImage: "arrow.clockwise")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                    .disabled(workflow.isSaving)
                    .accessibilityIdentifier("jumps.flow.resume")
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 20)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
        }
    }

    /// Explicit Cancel keeps its existing identifier and stays in the
    /// trailing position on every stage, so the native Back button remains
    /// visually and semantically distinct.
    private var flowCancelButton: some View {
        Button(AppText.string("common.cancel", language: language)) { cancelFlow() }
            .disabled(workflow.isSaving).frame(minHeight: 48)
            .accessibilityIdentifier("jumps.cancel")
    }

    /// Compact header: the full title/subtitle stays pre-video, while the
    /// indexed analysis keeps a small title so the viewport never duplicates
    /// a huge header above the video.
    @ViewBuilder
    private var headerBlock: some View {
        if workflow.manifest == nil {
            VStack(alignment: .leading, spacing: 6) {
                Text(AppText.string("jumps.title", language: language)).font(.largeTitle.bold())
                Text(AppText.string("jumps.subtitle", language: language)).font(.body).foregroundStyle(.secondary)
            }
        } else {
            Text(AppText.string("jumps.title", language: language)).font(.headline)
        }
    }

    /// View-only staged indicator (Android JumpFlowProgress concept).
    /// Reflects the VISIBLE route, never the highest derived state, so Back
    /// never mislabels an earlier stage. The root catalog omits it.
    /// Never persists, never completes.
    private func routeStageKey(_ route: JumpFlowRoute) -> String {
        switch route {
        case .prepare: "jumps.flow.prepare"
        case .obtainVideo: "jumps.flow.import"
        case .analyse: "jumps.flow.mark"
        case .result: "jumps.flow.results"
        }
    }

    private func routeOrder(_ route: JumpFlowRoute) -> Int {
        switch route {
        case .prepare: 1
        case .obtainVideo: 2
        case .analyse: 3
        case .result: 4
        }
    }

    private func routeIndicator(for visible: JumpFlowRoute) -> some View {
        HStack(spacing: OpenJumpSpacing.sm) {
            ForEach(JumpFlowRoute.allCases, id: \.self) { route in
                let isCurrent = route == visible
                VStack(spacing: 2) {
                    Text(verbatim: String(routeOrder(route)))
                        .font(.caption.bold())
                        .foregroundStyle(isCurrent ? Color.white : Color.secondary)
                        .frame(width: 24, height: 24)
                        .background(isCurrent ? Color.openJumpGreen : Color.openJumpSurface, in: Circle())
                    Text(AppText.string(routeStageKey(route), language: language))
                        .font(.caption)
                        .fontWeight(isCurrent ? .semibold : .regular)
                        .foregroundStyle(isCurrent ? Color.primary : Color.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(isCurrent ? [.isSelected] : [])
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(AppText.string(routeStageKey(visible), language: language))
        .accessibilityIdentifier("jumps.flow.indicator")
    }

    /// Illustrated protocol catalog shown before any video exists (Android
    /// power/reactivity grouping). Tapping a card only calls the existing
    /// `requestProtocol`, then reveals the preparation/import sections.
    private var catalogSection: some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.lg) {
            VStack(alignment: .leading, spacing: 6) {
                Text(AppText.string("jumps.catalog.title", language: language)).font(.title2.bold())
                Text(AppText.string("jumps.catalog.subtitle", language: language)).font(.body).foregroundStyle(.secondary)
            }
            catalogGroup(titleKey: "jumps.catalog.power", protocols: [.cmj, .sj, .abalakov, .unilateral])
            catalogGroup(titleKey: "jumps.catalog.reactivity", protocols: [.dropJump])
        }
    }

    private func catalogGroup(titleKey: String, protocols: [SavedProtocol]) -> some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
            Text(AppText.string(titleKey, language: language))
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 280))], spacing: OpenJumpSpacing.sm) {
                ForEach(protocols, id: \.self) { protocolKey in
                    catalogCard(for: protocolKey)
                }
            }
        }
    }

    private func catalogCard(for protocolKey: SavedProtocol) -> some View {
        let title = AppText.string(protocolKey.titleKey, language: language)
        let detail = AppText.string(catalogDescriptionKey(for: protocolKey), language: language)
        return Button {
            selectCatalogProtocol(protocolKey)
        } label: {
            VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
                HStack(spacing: OpenJumpSpacing.sm) {
                    Text(title)
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                Text(detail)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                catalogThumb(for: protocolKey)
            }
            .padding(OpenJumpSpacing.md)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .contentShape(RoundedRectangle(cornerRadius: 16))
            .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .disabled(workflow.isImporting || workflow.isIndexing || workflow.isSaving)
        .accessibilityLabel(title + ". " + detail)
        .accessibilityIdentifier("jumps.catalog." + protocolKey.rawValue.lowercased())
    }

    @ViewBuilder
    private func catalogThumb(for protocolKey: SavedProtocol) -> some View {
        let assets = ProtocolPresentation.illustrationAssets(for: protocolKey, side: nil)
        if !assets.isEmpty {
            HStack(spacing: OpenJumpSpacing.sm) {
                ForEach(assets, id: \.self) { name in
                    Image(name)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity)
                        .frame(height: assets.count > 1 ? 96 : 136)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityHidden(true)
        }
    }

    private func catalogDescriptionKey(for protocolKey: SavedProtocol) -> String {
        switch protocolKey {
        case .cmj: "jumps.catalog.cmj"
        case .sj: "jumps.catalog.sj"
        case .abalakov: "jumps.catalog.abalakov"
        case .unilateral: "jumps.catalog.unilateral"
        case .dropJump: "jumps.catalog.dropJump"
        case .horizontal, .asymmetry: "jumps.catalog.cmj"
        }
    }

    // MARK: - Staged value-route navigation (single StateObject above path)

    /// Visible staged route, if any. Nil at the illustrated catalog root.
    private var visibleRoute: JumpFlowRoute? { flowPath.last }

    /// Pushes a stage only when it is not already visible. Never duplicates
    /// entries from repeated appearance callbacks.
    private func pushRoute(_ route: JumpFlowRoute) {
        if flowPath.last != route { flowPath.append(route) }
    }

    /// Catalog selection: requests the existing protocol setup, then pushes
    /// Prepare ONLY when the request actually took effect. A dirty draft
    /// keeps the confirmation dialog open and the push waits for confirm;
    /// a saved-locked setup never silently switches and instead resumes the
    /// existing saved context.
    private func selectCatalogProtocol(_ key: SavedProtocol) {
        if workflow.savedMeasurement != nil {
            resumeToBestRoute()
            return
        }
        workflow.requestProtocol(key, app: state)
        if workflow.showDiscardConfirmation {
            pendingCatalogProtocol = key
        } else {
            pendingCatalogProtocol = nil
            if workflow.setup.protocolKey == key {
                pushRoute(.prepare)
            }
        }
    }

    /// Draft exists when any clip, activity, mark, declaration, result, save,
    /// or note is retained. The catalog offers a single Resume CTA then.
    private var hasResumeDraft: Bool {
        workflow.video != nil || workflow.manifest != nil
            || workflow.isImporting || workflow.isIndexing
            || !workflow.events.isEmpty || workflow.realtimeDeclared
            || workflow.metrics != nil || workflow.savedMeasurement != nil
            || !workflow.notes.isEmpty
    }

    /// Resume picks the most useful valid route via existing state, without
    /// clearing or inventing data: result, analyse, obtain, then prepare.
    private func resumeToBestRoute() {
        let target = JumpWorkflowPresentation.resumeRoute(
            hasResult: workflow.savedMeasurement != nil || workflow.metrics != nil,
            hasManifest: workflow.manifest != nil && workflow.video != nil,
            hasVideoOrImportActivity: workflow.video != nil || workflow.isImporting || workflow.isIndexing
        )
        flowPath = JumpWorkflowPresentation.path(to: target)
    }

    /// Trims stages whose content no longer exists (discard, invalidation),
    /// preserving order. Never pushes and never touches workflow data.
    private func reconcilePathAfterStateChange() {
        var valid: [JumpFlowRoute] = []
        for route in flowPath {
            switch route {
            case .prepare, .obtainVideo:
                valid.append(route)
            case .analyse:
                if workflow.manifest != nil, workflow.video != nil { valid.append(route) }
            case .result:
                if workflow.metrics != nil || workflow.savedMeasurement != nil { valid.append(route) }
            }
        }
        if valid != flowPath { flowPath = valid }
    }

    /// Explicit Cancel keeps existing transport/confirmation semantics and
    /// stays distinct from native Back. Immediate discards return to the
    /// catalog; dialog confirmations route in `confirmDiscardAndRoute`.
    private func cancelFlow() {
        workflow.cancelAnalysis()
        if !workflow.showDiscardConfirmation {
            if workflow.video == nil, workflow.manifest == nil {
                flowPath = []
            } else {
                reconcilePathAfterStateChange()
            }
        }
    }

    /// Confirmation applies through the existing workflow, then routes:
    /// pending catalog protocol goes Prepare; pending new-video goes Obtain;
    /// an actual source clear returns to catalog; otherwise invalid stages
    /// are trimmed. Cancelling the dialog keeps the route and all data.
    private func confirmDiscardAndRoute() {
        let pending = pendingCatalogProtocol
        let wantsObtain = pendingPostDiscardObtain
        workflow.confirmDiscard()
        if let pending {
            pendingCatalogProtocol = nil
            pendingPostDiscardObtain = false
            if JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: pending, confirmed: workflow.setup.protocolKey) == .prepare {
                pushRoute(.prepare)
            } else {
                reconcilePathAfterStateChange()
            }
        } else if wantsObtain {
            pendingPostDiscardObtain = false
            if workflow.video == nil, workflow.manifest == nil {
                flowPath = JumpWorkflowPresentation.path(to: .obtainVideo)
            } else {
                reconcilePathAfterStateChange()
            }
        } else if workflow.video == nil, workflow.manifest == nil {
            flowPath = []
        } else {
            reconcilePathAfterStateChange()
        }
    }

    private func cancelDiscardAndStay() {
        workflow.cancelDiscard()
        pendingCatalogProtocol = nil
        pendingPostDiscardObtain = false
    }

    /// The viewer is active ONLY when the Jumps root is actually appeared,
    /// the scene is active, and Analyse is the visible route. Entering
    /// Analyse resumes the kept exact frame without autoplay; every other
    /// visible route, the catalog, a hidden tab, or an inactive scene pauses
    /// without discarding artifacts. The StateObject, player, and leases are
    /// never reallocated here.
    private func synchronizeViewerForVisibleRoute() {
        if JumpWorkflowPresentation.shouldActivateViewer(isVisible: isJumpsVisible, isActiveScene: scenePhase == .active, visibleRoute: visibleRoute) {
            workflow.viewerAppeared()
        } else {
            workflow.viewerDisappeared()
        }
    }

    /// Prepare Continue: advances only through the read-only preparation
    /// gate (active owner, valid parameters, nothing busy).
    private func continueFromPrepare() {
        if workflow.canContinuePreparation(for: state) {
            pushRoute(.obtainVideo)
        }
    }

    /// Obtain Continue: the retained clip manifest is already valid, so an
    /// explicit tap resumes analysis. Background work never auto-pushes.
    private func continueToAnalyse() {
        if workflow.manifest != nil, workflow.video != nil {
            pushRoute(.analyse)
        }
    }

    /// Result Review events: returns to Analyse for precise frame
    /// inspection with no reset, no recalculation, and no data change.
    private func reviewEventsFromResult() {
        if let index = flowPath.lastIndex(of: .result) {
            flowPath.remove(at: index)
        }
        if workflow.manifest != nil, workflow.video != nil {
            pushRoute(.analyse)
        } else {
            reconcilePathAfterStateChange()
        }
    }

    /// Saved Another trial reuses the confirmed video lease with a fresh
    /// session, then routes to Analyse. Guard no-ops stay on Result.
    private func anotherTrialAndRoute() {
        let before = workflow.savedMeasurement?.id
        workflow.startAnotherTrial(using: state)
        if before != nil, workflow.savedMeasurement == nil {
            flowPath = JumpWorkflowPresentation.path(to: .analyse)
        }
    }

    /// Saved New video disposes the confirmed clip through the existing
    /// guarded path (protocol and owner stay in setup), then hosts the next
    /// import. Dialog confirmations route in `confirmDiscardAndRoute`.
    private func newVideoAndRoute() {
        workflow.cancelAnalysis()
        if !workflow.showDiscardConfirmation {
            if workflow.video == nil, workflow.manifest == nil {
                flowPath = JumpWorkflowPresentation.path(to: .obtainVideo)
            } else {
                reconcilePathAfterStateChange()
            }
        } else {
            pendingPostDiscardObtain = true
        }
    }

    private var setupSection: some View {
        OpenJumpSection(title: AppText.string("jumps.setup.protocol", language: language)) {
            Picker(AppText.string("jumps.setup.protocol", language: language), selection: Binding(
                get: { workflow.setup.protocolKey },
                set: { workflow.requestProtocol($0, app: state) }
            )) {
                ForEach(requiredProtocolOptions, id: \.self) { protocolKey in
                    Text(AppText.string(protocolKey.titleKey, language: language)).tag(protocolKey)
                }
            }
            .pickerStyle(.menu).frame(minHeight: 48)
            .accessibilityIdentifier("jumps.protocol")

            if workflow.manifest == nil {
                protocolArt
            }

            if activeAthletes.isEmpty {
                Text(AppText.string("jumps.setup.noProfile", language: language)).font(.callout)
                Button(AppText.string("jumps.setup.createProfile", language: language), action: openProfiles)
                    .buttonStyle(.borderedProminent).tint(.openJumpGreen).frame(minHeight: 48)
            } else {
                Picker(AppText.string("jumps.setup.owner", language: language), selection: Binding(
                    get: { workflow.setup.ownerID },
                    set: { workflow.requestOwner($0, app: state) }
                )) {
                    Text(AppText.string("jumps.setup.chooseProfile", language: language)).tag(UUID?.none)
                    ForEach(activeAthletes) { athlete in Text(athlete.name).tag(Optional(athlete.id)) }
                }
                .pickerStyle(.menu).frame(minHeight: 48)
                .accessibilityIdentifier("jumps.owner")
            }

            if workflow.setup.protocolKey == .unilateral {
                Picker(AppText.string("jumps.setup.side", language: language), selection: Binding(
                    get: { workflow.setup.side },
                    set: { workflow.requestSide($0, app: state) }
                )) {
                    Text(AppText.string("jumps.setup.left", language: language)).tag("LEFT")
                    Text(AppText.string("jumps.setup.right", language: language)).tag("RIGHT")
                }
                .pickerStyle(.segmented).frame(minHeight: 48)
                .accessibilityIdentifier("jumps.side")
            }

            if workflow.setup.protocolKey == .dropJump {
                TextField(
                    dropHeightPlaceholder,
                    text: Binding(
                        get: { workflow.setup.dropHeightInput },
                        set: { workflow.requestDropHeight($0, app: state) }
                    )
                )
                .keyboardType(.decimalPad).textFieldStyle(.roundedBorder).frame(minHeight: 48)
                .accessibilityLabel(AppText.string("jumps.setup.dropHeight", language: language))
                .accessibilityIdentifier("jumps.dropHeight")
            }
        }
        .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
    }

    private var dropHeightPlaceholder: String {
        AppText.string("jumps.setup.dropHeight", language: language) + " (" + workflow.dropHeightUnit + ")"
    }

    /// Selected-protocol illustration. Shown only before a video is indexed so
    /// imagery never clutters the time-critical frame flow. Informative: the
    /// panel carries the protocol/side caption as its VoiceOver label while the
    /// bitmaps themselves stay hidden from accessibility.
    @ViewBuilder
    private var protocolArt: some View {
        let assets = ProtocolPresentation.illustrationAssets(for: workflow.setup.protocolKey, side: workflow.setup.side)
        if !assets.isEmpty {
            VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
                HStack(spacing: OpenJumpSpacing.sm) {
                    ForEach(assets, id: \.self) { name in
                        Image(name)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: 200)
                            .accessibilityHidden(true)
                    }
                }
                Text(protocolArtCaption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(protocolArtCaption)
            .accessibilityAddTraits(.isImage)
        }
    }

    private var protocolArtCaption: String {
        var caption = AppText.string(workflow.setup.protocolKey.titleKey, language: language)
        if workflow.setup.protocolKey == .unilateral {
            if workflow.setup.side == "LEFT" {
                caption += " · " + AppText.string("jumps.setup.left", language: language)
            } else if workflow.setup.side == "RIGHT" {
                caption += " · " + AppText.string("jumps.setup.right", language: language)
            }
        } else if workflow.setup.protocolKey == .dropJump {
            caption += " · " + dropHeightSummary
        }
        return caption
    }

    private var importSection: some View {
        OpenJumpSection(title: AppText.string("jumps.import.title", language: language)) {
            VStack(alignment: .leading, spacing: 10) {
                PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) {
                    Label(AppText.string("jumps.import.photos", language: language), systemImage: "photo.on.rectangle")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered).disabled(workflow.isBusy || workflow.isSaving)
                .accessibilityIdentifier("jumps.import.photos")

                Button {
                    showFileImporter = true
                } label: {
                    Label(AppText.string("jumps.import.files", language: language), systemImage: "folder")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered).disabled(workflow.isBusy || workflow.isSaving)
                .accessibilityIdentifier("jumps.import.files")

                if workflow.video != nil {
                    Button {
                        showFileImporter = true
                    } label: {
                        Label(AppText.string("jumps.video.change", language: language), systemImage: "arrow.triangle.2.circlepath.video")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered).disabled(workflow.isBusy || workflow.isSaving)
                }
            }
        }
    }

    /// Direct-record camera entry (C2b). Lives ONLY inside the Obtain-video
    /// stage. Lifetime (preview + facade viewAppeared/viewDisappeared) runs
    /// while the Jumps root is appeared, the scene is foreground
    /// (active OR inactive), and Obtain video is the visible route, so a
    /// transient inactive permission alert preserves the originating ticket.
    /// Explicit user actions (open/record/retry) stay active-only via
    /// `cameraEligible`. True background, hidden tabs, and wrong routes
    /// fail closed and invalidate. Recording always starts from an explicit
    /// tap — background, tab switches, and hidden reappears never record.
    /// Back/tab/background preserve the workflow draft; finalizing persists
    /// until the real engine delegate lands.
    private var cameraSection: some View {
        let controls = JumpWorkflowPresentation.cameraControls(for: camera.phase)
        return OpenJumpSection(title: AppText.string("jumps.source.CAMERA", language: language)) {
            VStack(alignment: .leading, spacing: 10) {
                if cameraLifetimeVisible && controls.showsLivePreview {
                    CameraPreviewHost(session: camera.previewSessionForFutureHost)
                        .frame(maxWidth: .infinity)
                        .frame(height: JumpWorkflowPresentation.placeholderHeight)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel(AppText.string(camera.statusKey, language: language))
                        .accessibilityIdentifier("jumps.camera.preview")
                }
                Text(AppText.string(camera.errorKey ?? camera.statusKey, language: language))
                    .font(.footnote).foregroundStyle(.secondary)
                    .accessibilityAddTraits(.updatesFrequently)
                    .accessibilityIdentifier("jumps.camera.status")
                if controls.showsRecord {
                    Button {
                        recordTapped()
                    } label: {
                        Label(AppText.string(JumpWorkflowPresentation.recordButtonKey(for: camera.phase) ?? JumpCameraControls.recordKey, language: language), systemImage: "video.badge.plus")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                    .disabled(stagingActive || workflow.isSaving)
                    .accessibilityIdentifier("jumps.camera.record")
                }
                if controls.showsStop {
                    Button {
                        camera.stopRecording()
                    } label: {
                        Label(AppText.string(JumpCameraControls.stopKey, language: language), systemImage: "stop.fill")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!controls.stopEnabled || stagingActive || workflow.isSaving)
                    .accessibilityIdentifier("jumps.camera.stop")
                }
                if controls.showsReview {
                    reviewSection
                }
                if controls.showsRetry {
                    Button {
                        camera.requestPermissionAndPrepare(isActive: cameraEligible)
                    } label: {
                        Label(AppText.string("common.retry", language: language), systemImage: "arrow.clockwise")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(stagingActive || workflow.isSaving)
                    .accessibilityIdentifier("jumps.camera.retry")
                }
                if controls.showsCancel {
                    Button(role: .cancel) {
                        cancelCameraAttempt()
                    } label: {
                        Text(AppText.string("common.cancel", language: language))
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(stagingActive || workflow.isSaving)
                    .accessibilityIdentifier("jumps.camera.cancel")
                }
                if controls.canRepeat {
                    Button {
                        repeatRecording()
                    } label: {
                        Label(AppText.string(JumpCameraControls.repeatKey, language: language), systemImage: "arrow.triangle.2.circlepath")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(stagingActive || workflow.isSaving)
                    .accessibilityIdentifier("jumps.camera.repeat")
                }
                if stagingActive && camera.phase == .recorded {
                    Button(role: .cancel) {
                        workflow.cancelCapturedImport()
                    } label: {
                        Text(AppText.string("common.cancel", language: language))
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("jumps.camera.stagingCancel")
                }
            }
        }
        .onAppear { cameraSectionAppeared() }
        .onDisappear { cameraSectionDisappeared() }
        .onChange(of: cameraLifetimeVisible) { visible in
            if visible { camera.viewAppeared() }
            else { cameraSectionDisappeared() }
        }
        .onChange(of: camera.phase) { phase in
            if phase == .recorded { attachReviewPlayback() }
            else { teardownReviewPlayback() }
        }
    }

    /// Finalized-candidate review (C2b). Renders the candidate through a
    /// FRESH preview borrower (never the borrower later handed to Use) with
    /// the existing `NativePlayerLayer` pattern. The player/item is always
    /// torn down BEFORE the borrower is released; Use mints its own fresh
    /// `borrowRecorded()` handle per tap and transfers it to the C2a
    /// `requestCapturedFile` worker (never released here, never disposed).
    private var reviewSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let player = reviewPlayer {
                NativePlayerLayer(player: player)
                    .frame(maxWidth: .infinity)
                    .frame(height: JumpWorkflowPresentation.placeholderHeight)
                    .background(.black)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(AppText.string("jumps.camera.review", language: language))
                    .accessibilityIdentifier("jumps.camera.reviewPlayer")
                Button {
                    toggleReviewPlayback()
                } label: {
                    Label(
                        isReviewPlaying
                            ? AppText.string("jumps.playback.pause", language: language)
                            : AppText.string("jumps.playback.play", language: language),
                        systemImage: isReviewPlaying ? "pause.fill" : "play.fill"
                    )
                    .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(stagingActive || workflow.isSaving)
                .accessibilityIdentifier("jumps.camera.reviewToggle")
            }
            Button {
                useRecording()
            } label: {
                Label(AppText.string(JumpCameraControls.useRecordingKey, language: language), systemImage: "checkmark.circle")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
            .disabled(!camera.canUseRecorded || stagingActive || workflow.isSaving || workflow.showDiscardConfirmation)
            .accessibilityIdentifier("jumps.camera.use")
        }
    }

    /// Staging activity that may hold a camera Use (copy + SAME-analyzer
    /// inspect). Files/Photos imports also set these flags; the
    /// staging-cancel control only calls the camera-scoped
    /// `cancelCapturedImport`, which no-ops when no captured staging exists,
    /// so it can never disturb another import.
    private var stagingActive: Bool {
        workflow.isImporting || workflow.isIndexing
    }

    /// Explicit user-action gate (active-only): Record/Retry taps run only
    /// while the Jumps root is appeared, the scene is ACTIVE, and Obtain
    /// video is the visible route. A transient inactive permission alert is
    /// not eligible for a NEW tap; the in-flight request keeps its lifetime.
    /// Viewer gate unchanged.
    private var cameraEligible: Bool {
        JumpWorkflowPresentation.isCameraEligible(isVisible: isJumpsVisible, isActiveScene: scenePhase == .active, visibleRoute: visibleRoute)
    }

    /// Foreground lifetime gate (preview + facade lifecycle): appeared root
    /// + scene foreground (active OR inactive, i.e. `scenePhase !=
    /// .background`) + Obtain video route. Survives the transient inactive
    /// permission alert; true background, hidden tabs, and wrong routes fail
    /// closed and invalidate via `cameraSectionDisappeared`.
    private var cameraLifetimeVisible: Bool {
        JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: isJumpsVisible, isForegroundScene: scenePhase != .background, visibleRoute: visibleRoute)
    }

    /// Explicit camera button: idle opens the camera (prepare ONLY), ready
    /// records (start ONLY). Active-only and phase-gated; nothing here runs
    /// without the tap — never auto-record on grant/ready/resume.
    private func recordTapped() {
        guard cameraEligible else { return }
        switch JumpWorkflowPresentation.cameraFirstAction(for: camera.phase) {
        case .openCamera:
            camera.requestPermissionAndPrepare(isActive: cameraEligible)
        case .record:
            camera.startRecording()
        case .none:
            break
        }
    }

    /// Explicit Use tap: mints a FRESH borrower per action (never the preview
    /// borrower) and hands it to the SAME C2a analyzer entry. Ownership moves
    /// to the C2a staging task, which retains it INSIDE the copy worker until
    /// real worker exit: never `release()` the handed handle here, never
    /// dispose the candidate or the old video. Honors busy + dialog gates.
    private func useRecording() {
        guard camera.phase == .recorded, camera.canUseRecorded else { return }
        guard !workflow.isBusy, !workflow.showDiscardConfirmation else { return }
        guard let fresh = camera.borrowRecorded() else { return }
        workflow.requestCapturedFile(borrower: fresh, app: state)
    }

    /// Repeat: tear the review player down BEFORE releasing the preview
    /// borrower, then ask for a new recording (ready/recorded/failed only,
    /// never finalizing — the engine rejects the rest).
    private func repeatRecording() {
        teardownReviewPlayback()
        camera.prepareForNewRecording()
    }

    /// Cancel: tear the review player down BEFORE releasing the preview
    /// borrower, then cancel the camera attempt. The old analysis is never
    /// cleared by any camera path (C2a guarantee).
    private func cancelCameraAttempt() {
        teardownReviewPlayback()
        camera.cancel()
    }

    /// Explicit review play/pause for the finalized candidate. Render-only:
    /// never touches capture, staging, or analysis.
    private func toggleReviewPlayback() {
        guard let player = reviewPlayer else { return }
        if player.rate == 0 {
            player.play()
            isReviewPlaying = true
        } else {
            player.pause()
            isReviewPlaying = false
        }
    }

    /// Route became visible while lifetime holds: mark view visible so the
    /// engine may restart a suspended READY preview. Never starts recording.
    private func cameraSectionAppeared() {
        if camera.phase == .recorded { attachReviewPlayback() }
        guard cameraLifetimeVisible else { return }
        camera.viewAppeared()
    }

    /// True background, route exit, tab hide, or structural disappear:
    /// detach the review player BEFORE releasing its borrower, then park the
    /// session. A transient inactive permission alert never reaches here
    /// (lifetime stays foreground). A recording in flight auto-stops and
    /// still finalizes through the delegate; the workflow draft is preserved
    /// and nothing yanks the route.
    private func cameraSectionDisappeared() {
        teardownReviewPlayback()
        camera.viewDisappeared()
    }

    /// Attach review playback from a FRESH preview borrower. Always tears
    /// down any previous player first so the old borrower is released only
    /// after its player/item detached.
    private func attachReviewPlayback() {
        teardownReviewPlayback()
        guard camera.phase == .recorded else { return }
        guard let preview = camera.borrowRecorded() else { return }
        guard let url = preview.fileURL else { return }
        reviewBorrower = preview
        reviewPlayer = AVPlayer(url: url)
    }

    /// Detach-then-release ordering: pause, drop the item, drop the player,
    /// and only then release the borrower. Call before every borrower
    /// release, every Repeat/Cancel, and every disappear.
    private func teardownReviewPlayback() {
        reviewPlayer?.pause()
        reviewPlayer?.replaceCurrentItem(with: nil)
        reviewPlayer = nil
        isReviewPlaying = false
        reviewBorrower = nil
    }

    /// Compact immutable context once a video is indexed. The setup pickers
    /// stay available inside expandable sections; this card never duplicates
    /// the full header and never edits the confirmed root after saving.
    private var indexedContextCard: some View {
        let ownerName = activeAthletes.first(where: { $0.id == workflow.activeOwnerID })?.name
        return OpenJumpSection(title: AppText.string("jumps.analysis.title", language: language)) {
            VStack(alignment: .leading, spacing: 6) {
                if let sourceKey = workflow.sourceNameKey {
                    Label(AppText.string(sourceKey, language: language), systemImage: "video")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                if let ownerName {
                    LabeledContent(AppText.string("jumps.setup.owner", language: language), value: ownerName)
                }
                LabeledContent(AppText.string("jumps.setup.protocol", language: language), value: AppText.string(workflow.setup.protocolKey.titleKey, language: language))
                if workflow.setup.protocolKey == .unilateral {
                    LabeledContent(AppText.string("jumps.setup.side", language: language), value: unilateralSideName)
                }
                if workflow.setup.protocolKey == .dropJump {
                    LabeledContent(AppText.string("jumps.setup.dropHeight", language: language), value: dropHeightSummary)
                }
            }
        }
    }

    private var unilateralSideName: String {
        if workflow.setup.side == "LEFT" {
            return AppText.string("jumps.setup.left", language: language)
        } else if workflow.setup.side == "RIGHT" {
            return AppText.string("jumps.setup.right", language: language)
        } else {
            return workflow.setup.side
        }
    }

    /// Adaptive analysis layout (P2c, pure SwiftUI layout only).
    /// Wide (available width >= 800, non-accessibility text): video +
    /// transport/event rail left, marks/timing/notes sidebar right.
    /// Narrow or accessibility text: single column in logical order
    /// video → events → timing → notes. Results stay full width below the
    /// pair, before the anchored action. No state, marks, or player changes.
    private enum AnalysisLayout {
        static let wideThreshold: CGFloat = 800
    }

    private var isWideAnalysisLayout: Bool {
        !dynamicTypeSize.isAccessibilitySize && analysisContentWidth >= AnalysisLayout.wideThreshold
    }

    /// Value-route dispatcher: each stage renders only its own content in
    /// a local scroll view with a pinned stage action. The single workflow
    /// StateObject is captured from the root, never reinstantiated per route.
    @ViewBuilder
    private func stageDestination(for route: JumpFlowRoute) -> some View {
        switch route {
        case .prepare: prepareScreen
        case .obtainVideo: obtainVideoScreen
        case .analyse: analyseScreen
        case .result: resultScreen
        }
    }

    /// Prepare hosts the existing setup section and protocol art. Continue
    /// advances only through the read-only preparation gate; native Back
    /// returns to the catalog with no discard.
    private var prepareScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                routeIndicator(for: .prepare)
                setupSection
            }
            .padding(.horizontal, 16).padding(.vertical, 20)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
        }
        .navigationTitle(AppText.string("jumps.flow.prepare", language: language))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                flowCancelButton
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
                if !workflow.canContinuePreparation(for: state) {
                    Text(AppText.string("jumps.flow.preparationRequired", language: language))
                        .font(.footnote).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    continueFromPrepare()
                } label: {
                    Label(AppText.string("jumps.flow.continue", language: language), systemImage: "arrow.right")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(!workflow.canContinuePreparation(for: state))
                .accessibilityIdentifier("jumps.flow.continue")
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
            .background(Color.openJumpSurface)
        }
    }

    /// Obtain video hosts the Photos/Files importers plus the direct-record
    /// camera entry below (never a fake button), plus busy/index status, the
    /// pre-index error, and the retained-clip context once indexed.
    /// pre-index error, and the retained-clip context once indexed.
    /// Cancelling a provider leaves the old source and marks untouched.
    private var obtainVideoScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                routeIndicator(for: .obtainVideo)
                importSection
                cameraSection
                if workflow.isImporting {
                    ProgressView(AppText.string("jumps.import.busy", language: language))
                }
                if workflow.isIndexing {
                    ProgressView(AppText.string("jumps.video.loading", language: language))
                }
                if let errorKey = workflow.errorKey, workflow.manifest == nil {
                    Text(AppText.string(errorKey, language: language))
                        .font(.footnote).foregroundStyle(.red)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                if workflow.manifest != nil, workflow.video != nil {
                    indexedContextCard
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 20)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
        }
        .navigationTitle(AppText.string("jumps.flow.obtainVideo", language: language))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                flowCancelButton
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
                if workflow.isBusy {
                    Text(AppText.string("jumps.action.busy", language: language))
                        .font(.footnote).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    continueToAnalyse()
                } label: {
                    Label(AppText.string("jumps.flow.continue", language: language), systemImage: "arrow.right")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(workflow.manifest == nil || workflow.video == nil || workflow.isSaving)
                .accessibilityIdentifier("jumps.flow.continue")
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
            .background(Color.openJumpSurface)
        }
    }

    /// Analyse hosts the indexed context plus the unchanged frame review,
    /// event controls, timing preview, realtime guard, and editable notes.
    /// No embedded result preview and no saved routes here; the bottom
    /// primary only marks, calculates, or reviews an existing result.
    private var analyseScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                routeIndicator(for: .analyse)
                if let manifest = workflow.manifest, let video = workflow.video {
                    indexedContextCard
                    if isWideAnalysisLayout {
                        HStack(alignment: .top, spacing: 16) {
                            frameReview(manifest: manifest, video: video)
                                .frame(minWidth: 420).frame(maxWidth: .infinity, alignment: .leading)
                            analysisToolsPanel()
                                .frame(minWidth: 280, maxWidth: 340, alignment: .leading)
                        }
                    } else {
                        frameReview(manifest: manifest, video: video)
                        analysisToolsPanel()
                    }
                    // Only the busy indicator stays inline; the single primary
                    // mark/calculate action lives in the pinned bottom bar.
                    if workflow.isCalculating { ProgressView().accessibilityIdentifier("jumps.calculate.loading") }
                    if workflow.savedMeasurement == nil, let errorKey = workflow.errorKey {
                        Text(AppText.string(errorKey, language: language))
                            .font(.footnote).foregroundStyle(.red)
                            .accessibilityAddTraits(.updatesFrequently)
                    }
                } else {
                    Text(AppText.string("jumps.video.waiting", language: language))
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 20)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
            .background(GeometryReader { proxy in
                Color.clear.preference(key: AnalysisWidthKey.self, value: proxy.size.width)
            })
            .onPreferenceChange(AnalysisWidthKey.self) { width in
                if width != analysisContentWidth { analysisContentWidth = width }
            }
        }
        .navigationTitle(AppText.string("jumps.flow.mark", language: language))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                flowCancelButton
            }
        }
        .safeAreaInset(edge: .bottom) {
            analysePrimaryBar
        }
    }

    /// Result hosts the metric summary, the compact timing warning (kept
    /// visible because the analysis warning now lives on another screen),
    /// the read-only review context, and the saved routes. Save and History
    /// are primary only here; notes stay read-only with Review events
    /// returning to Analyse for any edit (which then requires recalculation
    /// through the unchanged invalidation semantics).
    private var resultScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                routeIndicator(for: .result)
                Text(AppText.string("jumps.timing.warning", language: language))
                    .font(.footnote).foregroundStyle(.secondary)
                if let metrics = workflow.metrics {
                    resultPreview(metrics)
                }
                if workflow.savedMeasurement != nil {
                    savedRoutes
                }
                Button {
                    reviewEventsFromResult()
                } label: {
                    Label(AppText.string("jumps.flow.reviewEvents", language: language), systemImage: "film")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(workflow.isSaving)
                .accessibilityIdentifier("jumps.flow.reviewEvents")
            }
            .padding(.horizontal, 16).padding(.vertical, 20)
            .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
        }
        .navigationTitle(AppText.string("jumps.flow.results", language: language))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                flowCancelButton
            }
        }
        .safeAreaInset(edge: .bottom) {
            resultPrimaryBar
        }
    }

    /// Marks/timing/notes sidebar (P2c). Single definition rendered once in
    /// either layout branch; results stay outside and full width below.
    private func analysisToolsPanel() -> some View {
        VStack(alignment: .leading, spacing: 18) {
            eventControls()
            timingPreviewPanel()
            VStack(alignment: .leading, spacing: 8) {
                Text(AppText.string("jumps.timing.warning", language: language)).font(.footnote).foregroundStyle(.secondary)
                Button {
                    workflow.setRealtimeDeclared(!workflow.realtimeDeclared)
                } label: {
                    Label(
                        AppText.string("jumps.timing.confirm", language: language),
                        systemImage: workflow.realtimeDeclared ? "checkmark.square.fill" : "square"
                    )
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(workflow.realtimeDeclared ? .isSelected : [])
                .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
                .accessibilityIdentifier("jumps.realtime")
                if workflow.realtimeDeclared {
                    Text(AppText.string("jumps.timing.declared", language: language))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Text(AppText.string("jumps.timing.slowMotion", language: language)).font(.footnote).foregroundStyle(.secondary)
            }

            TextField(AppText.string("jumps.results.notes", language: language), text: Binding(
                get: { workflow.notes },
                set: { workflow.setNotes($0) }
            ), axis: .vertical)
            .lineLimit(3...5).textFieldStyle(.roundedBorder).frame(minHeight: 48)
            .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
            .accessibilityIdentifier("jumps.notes")
        }
    }

    private func frameReview(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> some View {
        let isDisplayed = isFrameDisplayed(manifest: manifest, video: video)
        return VStack(alignment: .leading, spacing: 12) {
            // Video-dominant viewport: native NAV preview OR exact CG proof,
            // same bounded FIT black surface, max 380 stable height.
            Group {
                if workflow.showsNativePreview, let preview = workflow.previewPlayer {
                    NativePlayerLayer(player: preview)
                        .frame(maxWidth: .infinity)
                        .frame(height: JumpWorkflowPresentation.maxFrameHeight)
                        .background(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel(AppText.string("jumps.playback.preview", language: language))
                        .accessibilityIdentifier("jumps.preview.native")
                } else if let frame = workflow.presentedFrame, isDisplayed {
                    Image(uiImage: UIImage(cgImage: frame.image))
                        .resizable().scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: JumpWorkflowPresentation.maxFrameHeight)
                        .accessibilityLabel(frameAccessibilityLabel(manifest: manifest, index: frame.index))
                        .accessibilityIdentifier("jumps.frame.image")
                } else {
                    ZStack {
                        Rectangle().fill(.black.opacity(0.85))
                        VStack(spacing: 8) {
                            if workflow.isFrameLoading {
                                ProgressView(AppText.string("jumps.video.frameLoading", language: language))
                                    .tint(.white)
                                    .accessibilityIdentifier("jumps.frame.loading")
                            } else if workflow.errorKey == "jumps.error.frame" {
                                Text(AppText.string("jumps.error.frame", language: language))
                                    .font(.callout).foregroundStyle(.white).multilineTextAlignment(.center).padding()
                                Button(AppText.string("jumps.video.retry", language: language)) {
                                    workflow.requestFrame(workflow.frameIndex)
                                }
                                .buttonStyle(.bordered).tint(.white).frame(minHeight: 44)
                                .accessibilityIdentifier("jumps.frame.retry")
                            } else {
                                Text(AppText.string("jumps.video.waiting", language: language))
                                    .font(.callout).foregroundStyle(.white).multilineTextAlignment(.center).padding()
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: JumpWorkflowPresentation.placeholderHeight)
                    .background(.black, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(framePlaceholderLabel)
                }
            }
            .background(.black, in: RoundedRectangle(cornerRadius: 12))

            if manifest.frames.indices.contains(workflow.frameIndex) {
                let pts = manifest.frames[workflow.frameIndex].ptsUs
                Text(verbatim: frameIndexLine(isDisplayed: isDisplayed, manifest: manifest))
                    .font(.subheadline.monospacedDigit()).accessibilityAddTraits(.updatesFrequently)
                    .accessibilityIdentifier("jumps.frame.index")
                Text(verbatim: framePTSLine(ptsUs: pts))
                    .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                    .accessibilityIdentifier("jumps.frame.pts")
                Text(verbatim: exactUsLine(ptsUs: pts))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            TransportStatusLine(manifest: manifest, isDisplayed: isDisplayed)
            Slider(value: Binding(
                get: { Double(workflow.nativePreviewIndex ?? workflow.scrubRequestedIndex) },
                // Discrete/accessibility changes outside the drag gesture take
                // the public pause/exact path; the gesture coalesces to one
                // exact decode on end.
                set: {
                    let target = Int($0.rounded())
                    if workflow.isScrubbing { workflow.updateScrubTarget(target) }
                    else { workflow.requestFrame(target) }
                }
            ), in: 0...Double(max(0, manifest.frames.count - 1)), step: 1,
            onEditingChanged: { editing in
                if editing { workflow.beginScrubbing() }
                else { workflow.endScrubbing() }
            })
            .accessibilityLabel(AppText.string("jumps.playback.scrub", language: language))
            .accessibilityValue(sliderValue(manifest: manifest))
            .accessibilityIdentifier("jumps.frame.slider")
            .disabled(workflow.savedMeasurement != nil || workflow.isSaving || !transportSliderEnabled)
            // Transport group mirrors Android VideoTransportControls:
            // ±1 steps flank one primary play/pause, all 48pt, localized.
            // ViewThatFits keeps the compact row at normal text sizes and
            // stacks to full-width rows when accessibility text needs room.
            // One button definition, one rendered copy, same IDs/actions.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: OpenJumpSpacing.sm) {
                    transportButtons(manifest: manifest)
                }
                VStack(spacing: OpenJumpSpacing.sm) {
                    transportButtons(manifest: manifest)
                }
            }
            // Review events after the transport group: navigation together,
            // then the read-only review rail. Single definition, same
            // callbacks/guards/actions/IDs.
            eventReviewRail(manifest: manifest)
            if workflow.isPlaybackFailed {
                VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
                    Text(AppText.string("jumps.playback.unavailable", language: language))
                        .font(.footnote).foregroundStyle(.red)
                    Button {
                        workflow.retryPlaybackAttachment()
                    } label: {
                        Label(AppText.string("jumps.playback.retry", language: language), systemImage: "arrow.clockwise")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("jumps.playback.retry")
                }
            }
            if !workflow.isViewerActive {
                Text(AppText.string("jumps.playback.suspended", language: language))
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("jumps.playback.suspended")
            }
            Text(AppText.string("jumps.playback.sourceClock", language: language))
                .font(.caption).foregroundStyle(.secondary)
                .accessibilityIdentifier("jumps.playback.sourceClock")
        }
    }

    private var transportSliderEnabled: Bool {
        workflow.playbackEnabled
    }

    /// Single definition of the ±1/play transport buttons shared by both
    /// ViewThatFits branches. Text labels are always present (never
    /// icon-only); every action keeps 48pt and its existing identifier.
    @ViewBuilder
    private func transportButtons(manifest: JumpVideoManifest) -> some View {
        Button { workflow.stepFrameForAccessibility(by: -1) } label: {
            Label(AppText.string("jumps.video.previous", language: language), systemImage: "chevron.left")
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.bordered).disabled(!canStepBackward(manifest: manifest))
        .accessibilityIdentifier("jumps.frame.previous")
        Button { workflow.togglePlayback() } label: {
            Label(
                showsPauseIntent
                    ? AppText.string("jumps.playback.pause", language: language)
                    : AppText.string("jumps.playback.play", language: language),
                systemImage: showsPauseIntent ? "pause.fill" : "play.fill"
            )
            .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.borderedProminent).tint(.openJumpGreen)
        .disabled(!workflow.canTogglePlayback)
        .accessibilityIdentifier("jumps.playback.toggle")
        Button { workflow.stepFrameForAccessibility(by: 1) } label: {
            Label(AppText.string("jumps.video.next", language: language), systemImage: "chevron.right")
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.bordered).disabled(!canStepForward(manifest: manifest))
        .accessibilityIdentifier("jumps.frame.next")
    }

    private func canStepBackward(manifest: JumpVideoManifest) -> Bool {
        guard workflow.savedMeasurement == nil, !workflow.isSaving else { return false }
        return workflow.frameIndex > 0 || workflow.isTransportPlaying
    }

    private func canStepForward(manifest: JumpVideoManifest) -> Bool {
        guard workflow.savedMeasurement == nil, !workflow.isSaving, !manifest.frames.isEmpty else { return false }
        return workflow.frameIndex < manifest.frames.count - 1 || workflow.isTransportPlaying
    }

    private func TransportStatusLine(manifest: JumpVideoManifest, isDisplayed: Bool) -> some View {
        let key: String
        if !workflow.isViewerActive { key = "jumps.playback.suspended" }
        else if workflow.isPlaybackFailed { key = "jumps.playback.unavailable" }
        else if workflow.playbackPhase == .seeking || workflow.isScrubbing { key = "jumps.playback.seeking" }
        else if workflow.isTransportPlaying { key = "jumps.playback.preview" }
        else if workflow.isFrameLoading { key = "jumps.playback.preparing" }
        else if isDisplayed { key = "jumps.playback.exact" }
        else if workflow.playbackReadiness == .unknown { key = "jumps.playback.preparing" }
        else { key = "jumps.playback.settling" }
        return Text(AppText.string(key, language: language))
            .font(.caption).foregroundStyle(.secondary)
            .accessibilityAddTraits(.updatesFrequently)
            .accessibilityIdentifier("jumps.playback.status")
    }

    /// Pause affordance stays visible while playing or while a pending seek
    /// still holds playback intent (cancel available).
    private var showsPauseIntent: Bool {
        workflow.isTransportPlaying || (workflow.playbackPhase == .seeking && workflow.playbackWantsPlayback)
    }

    /// Compact read-only review rail for the required kinds in protocol order.
    /// Chips show the selected state plus the marked 1-based frame/raw PTS or
    /// the localized unmarked text. Taps route through the guarded
    /// `reviewEvent` helper; no player-PTS/FPS inference, no event mutation.
    @ViewBuilder
    private func eventReviewRail(manifest: JumpVideoManifest) -> some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
            Text(AppText.string("jumps.events.timeline", language: language))
                .font(.caption).foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: OpenJumpSpacing.sm) {
                    ForEach(workflow.requiredEvents, id: \.self) { kind in
                        let mark = workflow.event(for: kind)
                        let isSelected = workflow.selectedEvent == kind
                        Button { workflow.reviewEvent(kind) } label: {
                            VStack(spacing: 2) {
                                HStack(spacing: 4) {
                                    Image(systemName: mark == nil ? "circle" : "checkmark.circle.fill")
                                        .foregroundStyle(mark == nil ? Color.secondary : Color.openJumpGreen)
                                        .accessibilityHidden(true)
                                    Text(AppText.string(eventTitleKey(kind), language: language))
                                        .font(.caption.weight(.medium))
                                        .lineLimit(1)
                                }
                                if let mark {
                                    Text(verbatim: eventMarkLine(mark: mark))
                                        .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                                        .lineLimit(1)
                                } else {
                                    Text(AppText.string("jumps.events.unmarked", language: language))
                                        .font(.caption2).foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            .padding(.horizontal, OpenJumpSpacing.md)
                            .padding(.vertical, OpenJumpSpacing.xs)
                            .frame(minHeight: 48)
                            .background(isSelected ? Color.openJumpGreen.opacity(0.16) : Color.openJumpSurface, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
                        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                        .accessibilityLabel(reviewChipLabel(kind: kind, mark: mark))
                        .accessibilityIdentifier("jumps.review." + kind.rawValue)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private func reviewChipLabel(kind: JumpEventKind, mark: JumpEventMark?) -> String {
        let title = AppText.string(eventTitleKey(kind), language: language)
        if let mark {
            return title + ", " + eventMarkLine(mark: mark)
        }
        return title + ", " + AppText.string("jumps.events.unmarked", language: language)
    }

    private func isFrameDisplayed(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> Bool {
        guard let frame = workflow.presentedFrame,
              manifest.frames.indices.contains(workflow.frameIndex),
              manifest.sourceID == video.id else { return false }
        return frame.sourceID == video.id && frame.index == workflow.frameIndex && frame.ptsUs == manifest.frames[workflow.frameIndex].ptsUs
    }

    private func frameIndexLine(isDisplayed: Bool, manifest: JumpVideoManifest) -> String {
        // During NAV preview the label follows the live preview index;
        // exact proof still requires the CG gate in canMarkDisplayedFrame.
        let liveIndex = workflow.nativePreviewIndex ?? workflow.frameIndex
        let status: String
        if workflow.showsNativePreview {
            status = AppText.string("jumps.playback.preview", language: language)
        } else {
            status = isDisplayed ? AppText.string("jumps.video.displayed", language: language) : AppText.string("jumps.video.requested", language: language)
        }
        let frameWord = AppText.string("jumps.video.frame", language: language)
        return status + ": " + frameWord + " " + String(liveIndex + 1) + " / " + String(manifest.frames.count)
    }

    private func framePTSLine(ptsUs: Int64) -> String {
        let timeWord = AppText.string("jumps.video.time", language: language)
        return timeWord + ": " + formatPTS(ptsUs) + " s"
    }

    private func exactUsLine(ptsUs: Int64) -> String {
        let template = AppText.string("jumps.video.exactUs", language: language)
        let number = ptsUs.formatted(.number.locale(locale))
        return template.replacingOccurrences(of: "%@", with: number)
    }

    private func sliderValue(manifest: JumpVideoManifest) -> String {
        String((workflow.nativePreviewIndex ?? workflow.scrubRequestedIndex) + 1) + " / " + String(manifest.frames.count)
    }

    private var framePlaceholderLabel: String {
        if workflow.isFrameLoading {
            return AppText.string("jumps.video.frameLoading", language: language)
        } else if workflow.errorKey == "jumps.error.frame" {
            return AppText.string("jumps.error.frame", language: language)
        } else {
            return AppText.string("jumps.video.waiting", language: language)
        }
    }

    private func eventControls() -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(AppText.string("jumps.help.marking", language: language)).font(.footnote).foregroundStyle(.secondary)
            ForEach(workflow.requiredEvents, id: \.self) { kind in
                let mark = workflow.event(for: kind)
                VStack(alignment: .leading, spacing: 4) {
                    Button { workflow.selectEvent(kind) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: mark == nil ? "circle" : "checkmark.circle.fill")
                                .foregroundStyle(mark == nil ? Color.secondary : Color.openJumpGreen)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(AppText.string(eventTitleKey(kind), language: language)).font(.body.weight(.medium))
                                if let mark {
                                    Text(verbatim: eventMarkLine(mark: mark))
                                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 4)
                            if workflow.selectedEvent == kind {
                                Image(systemName: "arrowtriangle.right.fill").accessibilityHidden(true)
                            }
                        }
                        .contentShape(Rectangle()).frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.plain).disabled(workflow.savedMeasurement != nil || workflow.isSaving)
                    .accessibilityAddTraits(workflow.selectedEvent == kind ? .isSelected : [])
                    .accessibilityIdentifier(eventIdentifier(kind))
                    if mark != nil && workflow.savedMeasurement == nil {
                        Button {
                            workflow.clearEvent(kind)
                        } label: {
                            Text(verbatim: clearEventLine(kind: kind))
                                .frame(minHeight: 44)
                        }.buttonStyle(.borderless).disabled(workflow.isSaving)
                        .accessibilityIdentifier("jumps.event.clear")
                    }
                }
            }
            // Secondary re-mark: only once every required event is marked. The
            // anchored bottom bar owns the single primary Mark action, so this
            // keeps re-marking the selected exact frame without duplicating IDs.
            if allRequiredMarked, workflow.savedMeasurement == nil {
                Button {
                    workflow.markSelectedEvent()
                } label: {
                    Label(markButtonTitle, systemImage: "mappin.and.ellipse")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(!workflow.canMarkDisplayedFrame || workflow.selectedEvent == nil)
                .accessibilityIdentifier("jumps.event.replace")
                .accessibilityLabel(AppText.string("jumps.action.replace", language: language) + ". " + markButtonTitle)
            }
            if !workflow.events.isEmpty && workflow.savedMeasurement == nil {
                Button(AppText.string("jumps.event.clear", language: language), role: .destructive) {
                    workflow.clearMarks()
                }.frame(minHeight: 48).disabled(workflow.isSaving)
                .accessibilityIdentifier("jumps.event.clear")
            }
        }
    }

    private func eventIdentifier(_ kind: JumpEventKind) -> String {
        "jumps.event." + kind.rawValue
    }

    private func eventMarkLine(mark: JumpEventMark) -> String {
        let frameWord = AppText.string("jumps.video.frame", language: language)
        return frameWord + " " + String(mark.frameIndex + 1) + " · " + formatPTS(mark.ptsUs) + " s"
    }

    private func clearEventLine(kind: JumpEventKind) -> String {
        AppText.string("jumps.event.clear", language: language) + " · " + AppText.string(eventTitleKey(kind), language: language)
    }

    private var markButtonTitle: String {
        guard let selected = workflow.selectedEvent else {
            return AppText.string("jumps.event.mark", language: language)
        }
        let template = AppText.string("jumps.event.markSelected", language: language)
        let eventName = AppText.string(eventTitleKey(selected), language: language)
        let expanded = template.replacingOccurrences(of: "%@", with: eventName)
        if expanded == template {
            return AppText.string("jumps.event.mark", language: language) + " · " + eventName
        }
        return expanded
    }

    private func resultPreview(_ metrics: [SavedMetric]) -> some View {
        // Directly on the parent analysis surface: no nested card-within-card.
        // The duplicate timing warning is omitted here because the analysis
        // tools panel already shows it; realtime/slow-motion disclosures stay
        // in that panel and the Calculate gate is untouched.
        return VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
            Text(AppText.string("jumps.results.title", language: language)).font(.title2.bold())
            OpenJumpMetricSummary(
                metrics: metrics,
                protocolKey: workflow.setup.protocolKey,
                language: language,
                units: state.preferences.units,
                locale: locale,
                primaryAccessibilityID: "jumps.result.primary"
            )
            DisclosureGroup {
                reviewContextCard
            } label: {
                Text(AppText.string("jumps.results.review", language: language))
                    .font(.footnote).foregroundStyle(.secondary)
                    .frame(minHeight: 44)
            }
        }
    }

    private var reviewContextCard: some View {
        let ownerName = activeAthletes.first(where: { $0.id == workflow.activeOwnerID })?.name
        let notesText = workflow.notes.isEmpty ? nil : workflow.notes
        return VStack(alignment: .leading, spacing: 6) {
            LabeledContent(AppText.string("jumps.setup.protocol", language: language), value: AppText.string(workflow.setup.protocolKey.titleKey, language: language))
            if let ownerName {
                LabeledContent(AppText.string("jumps.setup.owner", language: language), value: ownerName)
            }
            if workflow.setup.protocolKey == .unilateral {
                LabeledContent(AppText.string("jumps.setup.side", language: language), value: unilateralSideName)
            }
            if workflow.setup.protocolKey == .dropJump {
                LabeledContent(AppText.string("jumps.setup.dropHeight", language: language), value: dropHeightSummary)
            }
            if let sourceKey = workflow.sourceNameKey {
                LabeledContent(AppText.string("jumps.analysis.source", language: language), value: AppText.string(sourceKey, language: language))
            }
            if let notesText {
                LabeledContent(AppText.string("jumps.results.notes", language: language), value: notesText)
            }
        }
        .font(.footnote).foregroundStyle(.secondary)
    }

    private var savedRoutes: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(AppText.string("jumps.results.saved", language: language), systemImage: "checkmark.circle.fill")
                .font(.headline).foregroundStyle(Color.openJumpGreen).accessibilityAddTraits(.updatesFrequently)
                .accessibilityIdentifier("jumps.saved")
            // The single primary history action lives in the result bottom bar;
            // trial/video routes keep their original IDs here.
            Button {
                anotherTrialAndRoute()
            } label: {
                Label(AppText.string("jumps.results.anotherTrial", language: language), systemImage: "repeat")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered).disabled(workflow.isSaving || workflow.isImporting || workflow.isIndexing)
            .accessibilityIdentifier("jumps.anotherTrial")
            Button {
                newVideoAndRoute()
            } label: {
                Label(AppText.string("jumps.results.newVideo", language: language), systemImage: "video.badge.plus")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered).disabled(workflow.isSaving)
            .accessibilityIdentifier("jumps.newVideo")
        }
    }

    /// Every required event has a mark; the anchored bar then offers Calculate.
    private var allRequiredMarked: Bool {
        !workflow.requiredEvents.isEmpty && workflow.nextEventToMark == nil
    }

    /// Single contextual hint for the anchored bar. Text-only (never color
    /// alone), wrapping to any length.
    private var bottomHint: String? {
        if workflow.savedMeasurement != nil { return nil }
        if workflow.isBusy || workflow.isCalculating {
            return AppText.string("jumps.action.busy", language: language)
        }
        if workflow.metrics != nil {
            return AppText.string("jumps.results.review", language: language)
        }
        let ownerOK = workflow.activeOwnerID.flatMap { id in
            activeAthletes.first(where: { $0.id == id })
        } != nil
        if !ownerOK {
            return AppText.string("jumps.action.ownerRequired", language: language)
        }
        if !workflow.realtimeDeclared {
            return AppText.string("jumps.action.timingRequired", language: language)
        }
        if !allRequiredMarked {
            if !workflow.canMarkDisplayedFrame {
                return AppText.string("jumps.action.exactRequired", language: language)
            }
            return AppText.string("jumps.help.marking", language: language)
        }
        return AppText.string("jumps.action.ready", language: language)
    }

    /// Analyse bottom primary (Android JumpMarkingActionBar equivalent).
    /// One 48pt primary only: calculated metrics offer Review result,
    /// all marks offer Calculate, otherwise Mark. Save never appears on
    /// the Analyse route. Existing IDs render exactly once per visible
    /// stage.
    private var analysePrimaryBar: some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
            if let hint = bottomHint {
                Text(hint)
                    .font(.footnote).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("jumps.action.hint")
            }
            if workflow.metrics != nil {
                Button {
                    pushRoute(.result)
                } label: {
                    Label(AppText.string("jumps.flow.reviewResult", language: language), systemImage: "chart.bar")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(workflow.isSaving || workflow.isCalculating)
                .accessibilityIdentifier("jumps.flow.reviewResult")
            } else if allRequiredMarked {
                Button {
                    workflow.calculate(using: state)
                } label: {
                    Label(AppText.string("jumps.results.calculate", language: language), systemImage: "function")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(!workflow.canCalculate(for: state) || workflow.isBusy)
                .accessibilityIdentifier("jumps.calculate")
            } else {
                Button {
                    workflow.markSelectedEvent()
                } label: {
                    Label(markButtonTitle, systemImage: "mappin.and.ellipse")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(!workflow.canMarkDisplayedFrame || workflow.selectedEvent == nil)
                .accessibilityIdentifier("jumps.event.mark")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
        .background(Color.openJumpSurface)
    }

    /// Result bottom primary. One 48pt primary only: saved measurements
    /// open History, calculated metrics offer Save. Existing IDs render
    /// exactly once per visible stage.
    private var resultPrimaryBar: some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
            if let hint = bottomHint {
                Text(hint)
                    .font(.footnote).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("jumps.action.hint")
            }
            if workflow.savedMeasurement != nil {
                Button {
                    openHistory()
                } label: {
                    Label(AppText.string("jumps.results.history", language: language), systemImage: "clock")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .accessibilityIdentifier("jumps.viewHistory")
            } else if workflow.metrics != nil {
                Button {
                    Task { await workflow.save(using: state, openHistory: openHistory) }
                } label: {
                    Label(AppText.string("jumps.results.save", language: language), systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(workflow.isSaving || workflow.isCalculating)
                .accessibilityIdentifier("jumps.save")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
        .background(Color.openJumpSurface)
    }

    private var dropHeightSummary: String {
        workflow.dropHeightSummary
    }

    private func formatPTS(_ ptsUs: Int64) -> String {
        (Double(ptsUs) / 1_000_000).formatted(.number.precision(.fractionLength(0...6)).locale(locale))
    }

    private func frameAccessibilityLabel(manifest: JumpVideoManifest, index: Int) -> String {
        let frameWord = AppText.string("jumps.video.frame", language: language)
        let timeWord = AppText.string("jumps.video.time", language: language)
        return frameWord + " " + String(index + 1) + ", " + timeWord + " " + formatPTS(manifest.frames[index].ptsUs) + " s"
    }

    private func eventTitleKey(_ kind: JumpEventKind) -> String {
        switch kind {
        case .movementStart: "jumps.event.movementStart"
        case .initialContact: "jumps.event.initialContact"
        case .takeoff: "jumps.event.takeoff"
        case .landing: "jumps.event.landing"
        }
    }

    /// Display-only VIDEO interval preview shown before Calculate.
    ///
    /// Uses only `JumpWorkflowPresentation.timingPreview` (raw PTS differences).
    /// Never enables Calculate, never marks/saves, never mutates workflow, and
    /// never claims physical-clock validation. Empty previews render nothing so
    /// no empty result implies success.
    @ViewBuilder
    private func timingPreviewPanel() -> some View {
        let previews = JumpWorkflowPresentation.timingPreview(for: workflow.setup.protocolKey, marks: workflow.events)
        if !previews.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(AppText.string("jumps.timing.preview.title", language: language))
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(AppText.string("jumps.timing.preview.source", language: language))
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(previews, id: \.id) { preview in
                    HStack(alignment: .firstTextBaseline) {
                        Text(metricName(preview.metricKey, language: language)).foregroundStyle(.secondary)
                        Spacer(minLength: 8)
                        Text(verbatim: previewDurationText(durationUs: preview.durationUs))
                            .font(.body.weight(.semibold)).monospacedDigit()
                    }
                    .frame(minHeight: 44)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(metricName(preview.metricKey, language: language) + ", " + previewDurationText(durationUs: preview.durationUs))
                    .accessibilityIdentifier("jumps.preview." + preview.id)
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("jumps.preview.panel")
        }
    }

    /// Raw preview duration in milliseconds for display only (`durationUs / 1_000`).
    /// Fixed "ms" SI unit; number grouping/decimal follows the view locale.
    private func previewDurationText(durationUs: Int64) -> String {
        (Double(durationUs) / 1_000.0).formatted(.number.precision(.fractionLength(0...3)).locale(locale)) + " ms"
    }
}
