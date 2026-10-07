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
                    if workflow.manifest == nil {
                        setupSection
                        importSection
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
            }
            .navigationTitle(AppText.string("tab.jumps", language: language))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.cancel", language: language)) { workflow.cancelAnalysis() }
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
        }
        .appLocale(state.preferences.language)
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

    private func analysisSection(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> some View {
        VStack(alignment: .leading, spacing: 18) {
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
                .accessibilityIdentifier("jumps.realtime")
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
            .accessibilityIdentifier("jumps.notes")

            Button {
                workflow.calculate(using: state)
            } label: {
                Label(AppText.string("jumps.results.calculate", language: language), systemImage: "function")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
            .disabled(!workflow.canCalculate(for: state) || workflow.isBusy)
            .accessibilityIdentifier("jumps.calculate")

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
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func frameReview(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> some View {
        let isDisplayed = isFrameDisplayed(manifest: manifest, video: video)
        return VStack(alignment: .leading, spacing: 12) {
            Group {
                if let frame = workflow.presentedFrame, isDisplayed {
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
            Slider(value: Binding(
                get: { Double(workflow.frameIndex) },
                set: { workflow.requestFrame(Int($0.rounded())) }
            ), in: 0...Double(max(0, manifest.frames.count - 1)), step: 1)
            .accessibilityLabel(AppText.string("jumps.video.frame", language: language))
            .accessibilityValue(sliderValue(manifest: manifest))
            .accessibilityIdentifier("jumps.frame.slider")
            .disabled(workflow.savedMeasurement != nil || workflow.isSaving)
            HStack(spacing: 12) {
                Button { workflow.moveFrame(by: -1) } label: {
                    Label(AppText.string("jumps.video.previous", language: language), systemImage: "chevron.left")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered).disabled(workflow.frameIndex <= 0 || workflow.isSaving || workflow.savedMeasurement != nil)
                .accessibilityIdentifier("jumps.frame.previous")
                Button { workflow.moveFrame(by: 1) } label: {
                    Label(AppText.string("jumps.video.next", language: language), systemImage: "chevron.right")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered).disabled(workflow.frameIndex >= manifest.frames.count - 1 || workflow.isSaving || workflow.savedMeasurement != nil)
                .accessibilityIdentifier("jumps.frame.next")
            }
        }
    }

    private func isFrameDisplayed(manifest: JumpVideoManifest, video: ImportedJumpVideo) -> Bool {
        guard let frame = workflow.presentedFrame,
              manifest.frames.indices.contains(workflow.frameIndex),
              manifest.sourceID == video.id else { return false }
        return frame.sourceID == video.id && frame.index == workflow.frameIndex && frame.ptsUs == manifest.frames[workflow.frameIndex].ptsUs
    }

    private func frameIndexLine(isDisplayed: Bool, manifest: JumpVideoManifest) -> String {
        let status = isDisplayed ? AppText.string("jumps.video.displayed", language: language) : AppText.string("jumps.video.requested", language: language)
        let frameWord = AppText.string("jumps.video.frame", language: language)
        return status + ": " + frameWord + " " + String(workflow.frameIndex + 1) + " / " + String(manifest.frames.count)
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
        String(workflow.frameIndex + 1) + " / " + String(manifest.frames.count)
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
            Button {
                workflow.markSelectedEvent()
            } label: {
                Label(markButtonTitle, systemImage: "mappin.and.ellipse")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
            .disabled(!workflow.canMarkDisplayedFrame || workflow.selectedEvent == nil)
            .accessibilityIdentifier("jumps.event.mark")
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
        return VStack(alignment: .leading, spacing: 12) {
            Text(AppText.string("jumps.results.title", language: language)).font(.title2.bold())
            Text(AppText.string("jumps.results.review", language: language)).font(.footnote).foregroundStyle(.secondary)
            reviewContextCard
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
            Text(AppText.string("jumps.timing.warning", language: language)).font(.footnote).foregroundStyle(.secondary)
            if workflow.savedMeasurement == nil {
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
            Button {
                openHistory()
            } label: {
                Label(AppText.string("jumps.results.history", language: language), systemImage: "clock")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
            .accessibilityIdentifier("jumps.viewHistory")
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
}
