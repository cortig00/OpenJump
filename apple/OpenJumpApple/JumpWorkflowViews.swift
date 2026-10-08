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
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showFileImporter = false
    /// Local-only catalog visibility. Starts true so the illustrated catalog
    /// is the initial pre-video UI; a late manifest/video always takes
    /// precedence over this flag.
    @State private var choosingProtocol = true
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
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    headerBlock
                    flowIndicator
                    if workflow.manifest == nil {
                        if choosingProtocol && workflow.video == nil {
                            catalogSection
                        } else {
                            if workflow.video == nil {
                                changeProtocolButton
                            }
                            setupSection
                            importSection
                        }
                    } else {
                        indexedContextCard
                        DisclosureGroup {
                            setupSection
                        } label: {
                            Text(AppText.string("jumps.setup.show", language: language)).frame(minHeight: 44)
                        }
                        DisclosureGroup {
                            importSection
                        } label: {
                            Text(AppText.string("jumps.import.show", language: language)).frame(minHeight: 44)
                        }
                    }
                    if workflow.isImporting {
                        ProgressView(AppText.string("jumps.import.busy", language: language))
                    }
                    if workflow.isIndexing {
                        ProgressView(AppText.string("jumps.video.loading", language: language))
                    }
                    if let manifest = workflow.manifest, let video = workflow.video {
                        analysisSection(manifest: manifest, video: video)
                    }
                    if let errorKey = workflow.errorKey, workflow.manifest == nil {
                        Text(AppText.string(errorKey, language: language))
                            .font(.footnote).foregroundStyle(.red)
                            .accessibilityAddTraits(.updatesFrequently)
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
            .navigationTitle(AppText.string("tab.jumps", language: language))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.cancel", language: language)) { cancelToCatalog() }
                        .disabled(workflow.isSaving).frame(minHeight: 48)
                        .accessibilityIdentifier("jumps.cancel")
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
                    workflow.confirmDiscard()
                }.frame(minHeight: 48)
                Button(AppText.string("common.cancel", language: language), role: .cancel) {
                    workflow.cancelDiscard()
                }.frame(minHeight: 48)
            } message: {
                Text(AppText.string("jumps.discard.body", language: language))
            }
            .task { workflow.synchronize(with: state) }
            .onAppear { workflow.viewerAppeared() }
            .onDisappear { workflow.viewerDisappeared() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { workflow.resumeViewerFromBackground() }
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
            .safeAreaInset(edge: .bottom) {
                if workflow.manifest != nil, workflow.video != nil {
                    anchoredPrimaryBar
                }
            }
        }
        .appLocale(state.preferences.language)
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

    /// View-only four-stage indicator (Android JumpFlowProgress concept).
    /// Derived from existing workflow state; never persists, never completes.
    private enum FlowStage: CaseIterable {
        case prepare, importing, mark, results
    }

    private var currentStage: FlowStage {
        if workflow.savedMeasurement != nil || workflow.metrics != nil { return .results }
        if workflow.manifest != nil { return .mark }
        if workflow.video != nil || workflow.isImporting || workflow.isIndexing { return .importing }
        return .prepare
    }

    private func flowStageKey(_ stage: FlowStage) -> String {
        switch stage {
        case .prepare: "jumps.flow.prepare"
        case .importing: "jumps.flow.import"
        case .mark: "jumps.flow.mark"
        case .results: "jumps.flow.results"
        }
    }

    private func flowOrder(_ stage: FlowStage) -> Int {
        switch stage {
        case .prepare: 1
        case .importing: 2
        case .mark: 3
        case .results: 4
        }
    }

    private var flowIndicator: some View {
        HStack(spacing: OpenJumpSpacing.sm) {
            ForEach(FlowStage.allCases, id: \.self) { stage in
                let isCurrent = stage == currentStage
                VStack(spacing: 2) {
                    Text(verbatim: String(flowOrder(stage)))
                        .font(.caption.bold())
                        .foregroundStyle(isCurrent ? Color.white : Color.secondary)
                        .frame(width: 24, height: 24)
                        .background(isCurrent ? Color.openJumpGreen : Color.openJumpSurface, in: Circle())
                    Text(AppText.string(flowStageKey(stage), language: language))
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
        .accessibilityLabel(AppText.string(flowStageKey(currentStage), language: language))
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
            workflow.requestProtocol(protocolKey, app: state)
            choosingProtocol = false
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

    /// Returns to the catalog without touching workflow state: no
    /// cancelAnalysis, so owner/notes are never cleared. Pre-video only.
    private var changeProtocolButton: some View {
        Button {
            choosingProtocol = true
        } label: {
            Label(AppText.string("jumps.catalog.change", language: language), systemImage: "square.grid.2x2")
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.bordered)
        .disabled(workflow.isImporting || workflow.isIndexing || workflow.isSaving)
        .accessibilityIdentifier("jumps.catalog.change")
    }

    /// Top Cancel keeps existing transport semantics; when no clip exists yet
    /// it only flips the local catalog flag back (never discards media).
    private func cancelToCatalog() {
        workflow.cancelAnalysis()
        if workflow.video == nil && workflow.manifest == nil {
            choosingProtocol = true
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

    private func analysisSection(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> some View {
        VStack(alignment: .leading, spacing: 18) {
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
            // Results stay full width below the pair, before the anchored action.
            // The single primary Calculate action lives in the anchored bottom
            // bar; only the busy indicator stays inline.
            if workflow.isCalculating { ProgressView().accessibilityIdentifier("jumps.calculate.loading") }
            if let metrics = workflow.metrics {
                resultPreview(metrics)
            }
            if workflow.savedMeasurement != nil {
                savedRoutes
            } else if let errorKey = workflow.errorKey, workflow.manifest != nil {
                Text(AppText.string(errorKey, language: language))
                    .font(.footnote).foregroundStyle(.red)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .padding(16)
        .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 16))
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
            eventReviewRail(manifest: manifest)
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
        let primary = ProtocolPresentation.primaryMetric(in: metrics, protocolKey: workflow.setup.protocolKey)
        let secondary = primary.map { hero in metrics.filter { $0.ordinal != hero.ordinal } } ?? metrics
        return VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
            Text(AppText.string("jumps.results.title", language: language)).font(.title2.bold())
            // Primary HERO first; supporting metrics stay clearly secondary.
            if let primary {
                OpenJumpMetricHero(metric: primary, language: language, units: state.preferences.units, locale: locale)
                    .accessibilityIdentifier("jumps.result.primary")
            }
            ForEach(secondary, id: \.ordinal) { metric in
                HStack(alignment: .firstTextBaseline) {
                    Text(metricName(metric.key, language: language)).foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(formattedMetric(metric, units: state.preferences.units, locale: locale))
                        .font(.body.weight(.semibold)).monospacedDigit()
                }
                .frame(minHeight: 44)
            }
            DisclosureGroup {
                reviewContextCard
            } label: {
                Text(AppText.string("jumps.results.review", language: language))
                    .font(.footnote).foregroundStyle(.secondary)
                    .frame(minHeight: 44)
            }
            Text(AppText.string("jumps.timing.warning", language: language)).font(.footnote).foregroundStyle(.secondary)
        }
        .padding(14).background(.background, in: RoundedRectangle(cornerRadius: 12))
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
            // The single primary history action lives in the anchored bottom bar;
            // trial/video routes keep their original IDs here.
            Button {
                workflow.startAnotherTrial(using: state)
            } label: {
                Label(AppText.string("jumps.results.anotherTrial", language: language), systemImage: "repeat")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered).disabled(workflow.isSaving || workflow.isImporting || workflow.isIndexing)
            .accessibilityIdentifier("jumps.anotherTrial")
            Button {
                workflow.cancelAnalysis()
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

    /// Anchored single primary action (Android JumpMarkingActionBar equivalent).
    /// One 48pt primary only: saved→history, metrics→save, all marks→calculate,
    /// else mark. Existing IDs render exactly once here.
    private var anchoredPrimaryBar: some View {
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
