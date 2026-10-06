import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

@MainActor
struct JumpHomeView: View {
    @ObservedObject var state: AppState
    let openProfiles: () -> Void
    let openHistory: () -> Void
    @StateObject private var workflow = JumpWorkflowState()
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showFileImporter = false

    private var language: AppLanguage { state.preferences.language }
    private var locale: Locale { state.preferences.effectiveLocale }
    private var activeAthletes: [Athlete] { state.athletes.filter { $0.archivedAt == nil } }
    private var requiredProtocolOptions: [SavedProtocol] { TemporalJumpDraft.supportedProtocols }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(AppText.string("jumps.title", language: language)).font(.largeTitle.bold())
                        Text(AppText.string("jumps.subtitle", language: language)).font(.body).foregroundStyle(.secondary)
                    }
                    setupSection
                    importSection
                    if workflow.isImporting {
                        ProgressView(AppText.string("jumps.import.busy", language: language))
                    }
                    if workflow.isIndexing {
                        ProgressView(AppText.string("jumps.video.loading", language: language))
                    }
                    if let manifest = workflow.manifest, let video = workflow.video {
                        analysisSection(manifest: manifest, video: video)
                    }
                    if let errorKey = workflow.errorKey {
                        Text(AppText.string(errorKey, language: language))
                            .font(.footnote).foregroundStyle(.red)
                            .accessibilityAddTraits(.updatesFrequently)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 20)
                .frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity)
            }
            .navigationTitle(AppText.string("tab.jumps", language: language))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.cancel", language: language)) { workflow.cancelAnalysis() }
                        .disabled(workflow.isSaving).frame(minHeight: 48)
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
            .onChange(of: selectedPhoto) { _, item in
                if let item {
                    workflow.requestPhotos(item, app: state)
                    selectedPhoto = nil
                }
            }
            .onChange(of: state.preferences.units) { _, _ in workflow.preferenceContextChanged(app: state) }
            .onChange(of: state.preferences.language) { _, _ in workflow.preferenceContextChanged(app: state) }
            .onChange(of: state.preferences.selectedAthleteID) { _, id in
                if id != workflow.activeOwnerID { workflow.requestOwner(id, app: state) }
            }
        }
        .appLocale(state.preferences.language)
    }

    private var setupSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker(AppText.string("jumps.setup.protocol", language: language), selection: Binding(
                get: { workflow.setup.protocolKey },
                set: { workflow.requestProtocol($0, app: state) }
            )) {
                ForEach(requiredProtocolOptions, id: \.self) { protocolKey in
                    Text(AppText.string(protocolKey.titleKey, language: language)).tag(protocolKey)
                }
            }
            .pickerStyle(.menu).frame(minHeight: 48)

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
            }

            if workflow.setup.protocolKey == .dropJump {
                TextField(
                    "\(AppText.string("jumps.setup.dropHeight", language: language)) (\(workflow.dropHeightUnit))",
                    text: Binding(
                        get: { workflow.setup.dropHeightInput },
                        set: { workflow.requestDropHeight($0, app: state) }
                    )
                )
                .keyboardType(.decimalPad).textFieldStyle(.roundedBorder).frame(minHeight: 48)
                .accessibilityLabel(AppText.string("jumps.setup.dropHeight", language: language))
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
    }

    private var importSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            PhotosPicker(selection: $selectedPhoto, matching: .videos, preferredItemEncoding: .current) {
                Label(AppText.string("jumps.import.photos", language: language), systemImage: "photo.on.rectangle")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered).disabled(workflow.isBusy || workflow.isSaving)

            Button {
                showFileImporter = true
            } label: {
                Label(AppText.string("jumps.import.files", language: language), systemImage: "folder")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered).disabled(workflow.isBusy || workflow.isSaving)

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

    private func analysisSection(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            if let sourceKey = workflow.sourceNameKey {
                Label(AppText.string(sourceKey, language: language), systemImage: "video")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            if let owner = activeAthletes.first(where: { $0.id == workflow.activeOwnerID }) {
                LabeledContent(AppText.string("jumps.setup.owner", language: language), value: owner.name)
            }
            LabeledContent(AppText.string("jumps.setup.protocol", language: language), value: AppText.string(workflow.setup.protocolKey.titleKey, language: language))
            if workflow.setup.protocolKey == .unilateral {
                LabeledContent(AppText.string("jumps.setup.side", language: language), value: AppText.string(workflow.setup.side == "LEFT" ? "jumps.setup.left" : "jumps.setup.right", language: language))
            }
            if workflow.setup.protocolKey == .dropJump {
                LabeledContent(AppText.string("jumps.setup.dropHeight", language: language), value: dropHeightSummary)
            }

            frameReview(manifest: manifest, video: video)
            eventControls()
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
                if workflow.realtimeDeclared {
                    Text(AppText.string("jumps.timing.declared", language: language))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            TextField(AppText.string("jumps.results.notes", language: language), text: Binding(
                get: { workflow.notes },
                set: { workflow.setNotes($0) }
            ), axis: .vertical)
            .lineLimit(3...5).textFieldStyle(.roundedBorder).frame(minHeight: 48)
            .disabled(workflow.savedMeasurement != nil || workflow.isSaving)

            Button {
                workflow.calculate(using: state)
            } label: {
                Label(AppText.string("jumps.results.calculate", language: language), systemImage: "function")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
            .disabled(!workflow.canCalculate(for: state) || workflow.isBusy)

            if workflow.isCalculating { ProgressView() }
            if let metrics = workflow.metrics {
                resultPreview(metrics)
            }
            if workflow.savedMeasurement != nil {
                Label(AppText.string("jumps.results.saved", language: language), systemImage: "checkmark.circle.fill")
                    .font(.headline).foregroundStyle(Color.openJumpGreen).accessibilityAddTraits(.updatesFrequently)
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func frameReview(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Group {
                if let frame = workflow.presentedFrame,
                   frame.sourceID == video.id, frame.index == workflow.frameIndex,
                   manifest.frames.indices.contains(frame.index),
                   frame.ptsUs == manifest.frames[frame.index].ptsUs {
                    Image(uiImage: UIImage(cgImage: frame.image))
                        .resizable().scaledToFit()
                        .accessibilityLabel(frameAccessibilityLabel(manifest: manifest, index: frame.index))
                } else {
                    ZStack {
                        Rectangle().fill(.black.opacity(0.85))
                        if workflow.isFrameLoading {
                            ProgressView(AppText.string("jumps.video.frameLoading", language: language)).tint(.white)
                        } else {
                            Text(AppText.string("jumps.error.frame", language: language))
                                .font(.callout).foregroundStyle(.white).multilineTextAlignment(.center).padding()
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity).frame(height: 460).clipped()
            .background(.black, in: RoundedRectangle(cornerRadius: 12))

            if manifest.frames.indices.contains(workflow.frameIndex) {
                let pts = manifest.frames[workflow.frameIndex].ptsUs
                Text(verbatim: "\(AppText.string("jumps.video.frame", language: language)) \(workflow.frameIndex + 1) / \(manifest.frames.count) · \(AppText.string("jumps.video.time", language: language)): \(formatPTS(pts))")
                    .font(.subheadline.monospacedDigit()).accessibilityAddTraits(.updatesFrequently)
            }
            Slider(value: Binding(
                get: { Double(workflow.frameIndex) },
                set: { workflow.requestFrame(Int($0.rounded())) }
            ), in: 0...Double(max(0, manifest.frames.count - 1)), step: 1)
            .accessibilityLabel(AppText.string("jumps.video.frame", language: language))
            .accessibilityValue("\(workflow.frameIndex + 1) / \(manifest.frames.count)")
            .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
            HStack(spacing: 12) {
                Button { workflow.moveFrame(by: -1) } label: {
                    Label(AppText.string("jumps.video.previous", language: language), systemImage: "chevron.left")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered).disabled(workflow.frameIndex <= 0 || workflow.isSaving || workflow.savedMeasurement != nil)
                Button { workflow.moveFrame(by: 1) } label: {
                    Label(AppText.string("jumps.video.next", language: language), systemImage: "chevron.right")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered).disabled(workflow.frameIndex >= manifest.frames.count - 1 || workflow.isSaving || workflow.savedMeasurement != nil)
            }
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
                                    Text(verbatim: "\(AppText.string("jumps.video.frame", language: language)) \(mark.frameIndex + 1) · \(formatPTS(mark.ptsUs))")
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
                    if mark != nil && workflow.savedMeasurement == nil {
                        Button {
                            workflow.clearEvent(kind)
                        } label: {
                            Text(verbatim: "\(AppText.string("jumps.event.clear", language: language)) · \(AppText.string(eventTitleKey(kind), language: language))")
                                .frame(minHeight: 44)
                        }.buttonStyle(.borderless).disabled(workflow.isSaving)
                    }
                }
            }
            Button {
                workflow.markSelectedEvent()
            } label: {
                Label(AppText.string("jumps.event.mark", language: language), systemImage: "mappin.and.ellipse")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
            .disabled(!workflow.canMarkDisplayedFrame || workflow.selectedEvent == nil)
            if !workflow.events.isEmpty && workflow.savedMeasurement == nil {
                Button(AppText.string("jumps.event.clear", language: language), role: .destructive) {
                    workflow.clearMarks()
                }.frame(minHeight: 48).disabled(workflow.isSaving)
            }
        }
    }

    private func resultPreview(_ metrics: [SavedMetric]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(AppText.string("jumps.results.title", language: language)).font(.title2.bold())
            Text(AppText.string("jumps.results.review", language: language)).font(.footnote).foregroundStyle(.secondary)
            ForEach(Array(metrics.enumerated()), id: \.offset) { element in
                let metric = element.element
                HStack(alignment: .firstTextBaseline) {
                    Text(metricName(metric.key, language: language)).foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(formattedMetric(metric, units: state.preferences.units, locale: locale))
                        .font(.body.weight(.semibold)).monospacedDigit()
                }
                .frame(minHeight: 44)
            }
            if workflow.savedMeasurement == nil {
                Button {
                    Task { await workflow.save(using: state, openHistory: openHistory) }
                } label: {
                    Label(AppText.string("jumps.results.save", language: language), systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                .disabled(workflow.isSaving || workflow.isCalculating)
            }
        }
        .padding(14).background(.background, in: RoundedRectangle(cornerRadius: 12))
    }

    private var dropHeightSummary: String {
        workflow.dropHeightSummary
    }

    private func formatPTS(_ ptsUs: Int64) -> String {
        (Double(ptsUs) / 1_000_000).formatted(.number.precision(.fractionLength(0...6)).locale(locale))
    }

    private func frameAccessibilityLabel(manifest: JumpVideoManifest, index: Int) -> String {
        "\(AppText.string("jumps.video.frame", language: language)) \(index + 1), \(AppText.string("jumps.video.time", language: language)) \(formatPTS(manifest.frames[index].ptsUs))"
    }

    private func eventTitleKey(_ kind: JumpEventKind) -> String {
        switch kind {
        case .movementStart: "jumps.event.movementStart"
        case .initialContact: "jumps.event.initialContact"
        case .takeoff: "jumps.event.takeoff"
        case .landing: "jumps.event.landing"
        }
    }
}
