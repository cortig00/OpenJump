import SwiftUI

struct HistoryView: View {
    @ObservedObject var state: AppState
    let fixedOwnerID: UUID?
    @State private var rows: [SavedMeasurement] = []
    @State private var cursor: (date: Date, id: UUID)?
    @State private var hasMore = false
    @State private var selectedProtocol: SavedProtocol?
    @State private var selectedOwner: UUID?
    @State private var query = ""
    @State private var error: String?
    @State private var selected: SavedMeasurement?
    @State private var generation = 0
    @State private var searchGeneration = 0
    @State private var loadingPage = false
    @State private var loadingRequest: Int?
    private var language: AppLanguage { state.preferences.language }
    init(state: AppState, ownerID: UUID? = nil) {
        self.state = state
        self.fixedOwnerID = ownerID
        _selectedOwner = State(initialValue: ownerID)
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                HStack {
                    Picker(AppText.string("history.protocol", language: language), selection: $selectedProtocol) {
                        Text(AppText.string("history.all", language: language)).tag(SavedProtocol?.none)
                        ForEach(SavedProtocol.allCases, id: \.self) { Text(AppText.string($0.titleKey, language: language)).tag(Optional($0)) }
                    }
                    if fixedOwnerID == nil {
                        Picker(AppText.string("history.athlete", language: language), selection: $selectedOwner) {
                            Text(AppText.string("history.all", language: language)).tag(UUID?.none)
                            ForEach(state.athletes) { Text($0.name).tag(Optional($0.id)) }
                        }
                    }
                }.pickerStyle(.menu).padding(.horizontal, 16)
                if let error {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: error).foregroundStyle(.red).font(.footnote)
                        Button(AppText.string("common.retry", language: language)) { Task { await reset() } }.frame(minHeight: 44)
                    }.padding(.horizontal, 16)
                }
                if loadingPage && rows.isEmpty && error == nil {
                    ProgressView()
                } else if rows.isEmpty && error == nil {
                    OpenJumpEmptyState(title: AppText.string("history.emptyTitle", language: language), systemImage: "clock", description: Text(AppText.string("history.emptyBody", language: language)))
                } else {
                    List {
                        ForEach(rows) { measurement in
                            Button { selected = measurement } label: { HistoryRow(item: measurement, athletes: state.athletes, language: language, locale: state.preferences.effectiveLocale, units: state.preferences.units) }
                                .buttonStyle(.plain)
                        }
                        if hasMore { Button(AppText.string("history.more", language: language)) { Task { await loadNext(generation) } }.disabled(loadingPage).frame(minHeight: 48) }
                        if loadingPage && !rows.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                    }.listStyle(.plain)
                }
            }
            .navigationTitle(AppText.string("tab.history", language: language))
            .searchable(text: $query, prompt: AppText.string("history.search", language: language))
            .task { await reset() }
            .onChange(of: query) { _ in
                searchGeneration += 1
                let request = searchGeneration
                Task {
                    try? await Task.sleep(for: .milliseconds(250))
                    guard request == searchGeneration else { return }
                    await reset()
                }
            }
            .onChange(of: selectedProtocol) { _ in Task { await reset() } }
            .onChange(of: selectedOwner) { _ in Task { await reset() } }
            .onChange(of: state.historyRevision) { _ in Task { await reset() } }
            .sheet(item: $selected, onDismiss: { Task { await reset() } }) { item in MeasurementDetailView(state: state, initial: item) }
        }
    }
    private func reset() async { generation += 1; loadingRequest = nil; loadingPage = false; rows = []; cursor = nil; hasMore = false; error = nil; await loadNext(generation) }
    private func loadNext(_ request: Int) async {
        guard loadingRequest == nil, request == generation, let store = state.store else { return }
        let pageCursor = cursor
        let owner = selectedOwner, protocolFilter = selectedProtocol, search = query.isEmpty ? nil : query
        loadingRequest = request
        loadingPage = true
        defer { if loadingRequest == request { loadingRequest = nil; loadingPage = false } }
        do {
            let page = try await store.history(ownerID: owner, protocolKey: protocolFilter, search: search, limit: 50, before: pageCursor)
            guard request == generation, cursor?.id == pageCursor?.id else { return }
            let known = Set(rows.map(\.id))
            rows += page.items.filter { !known.contains($0.id) }
            cursor = page.nextBefore; hasMore = page.nextBefore != nil
        } catch { if request == generation { self.error = displayError(error, language: language) } }
    }
}

private struct HistoryRow: View {
    let item: SavedMeasurement
    let athletes: [Athlete]
    let language: AppLanguage
    let locale: Locale
    let units: UnitProfile
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack { Text(AppText.string(item.protocolKey.titleKey, language: language)).font(.headline); Spacer(); Text(item.recordedAt.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(locale))).font(.caption).foregroundStyle(.secondary) }
            Text(item.metrics.map { "\(metricName($0.key, language: language)): \(formattedMetric($0, units: units, locale: locale))" }.joined(separator: " · ")).font(.subheadline)
            Text(item.ownerID.flatMap { id in athletes.first { $0.id == id }?.name } ?? AppText.string("history.unassigned", language: language)).font(.caption).foregroundStyle(.secondary)
        }.padding(.vertical, 8).contentShape(Rectangle())
    }
}

private struct MeasurementDetailView: View {
    @ObservedObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    let initial: SavedMeasurement
    @State private var notes: String
    @State private var busy = false
    @State private var alert: String?
    @State private var confirmDelete = false
    @State private var temporalAnalysis: SavedTemporalAnalysis?
    @State private var temporalAnalysisError: String?
    @State private var loadingTemporalAnalysis = false
    init(state: AppState, initial: SavedMeasurement) { self.state = state; self.initial = initial; _notes = State(initialValue: initial.notes ?? "") }
    private var language: AppLanguage { state.preferences.language }
    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.string("history.details", language: language)) {
                    LabeledContent(AppText.string("history.protocol", language: language), value: AppText.string(initial.protocolKey.titleKey, language: language))
                    LabeledContent(AppText.string("history.session", language: language), value: initial.sessionKey)
                    LabeledContent(AppText.string("history.date", language: language), value: initial.recordedAt.formatted(Date.FormatStyle(date: .long, time: .shortened).locale(state.preferences.effectiveLocale)))
                    LabeledContent(AppText.string("history.athlete", language: language), value: initial.ownerID.flatMap { id in state.athletes.first { $0.id == id }?.name } ?? AppText.string("history.unassigned", language: language))
                    if let side = initial.side { LabeledContent(AppText.string("history.side", language: language), value: side == "LEFT" ? AppText.string("history.left", language: language) : (side == "RIGHT" ? AppText.string("history.right", language: language) : side)) }
                    if let drop = initial.dropHeightCm { LabeledContent(AppText.string("history.drop", language: language), value: "\(MeasurementPresentation.shortLength(drop, as: state.preferences.units.shortLength).formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale))) \(state.preferences.units.shortLength.rawValue)") }
                }
                Section(AppText.string("history.metrics", language: language)) {
                    ForEach(Array(initial.metrics.enumerated()), id: \.offset) { element in
                        let metric = element.element
                        LabeledContent(metricName(metric.key, language: language), value: formattedMetric(metric, units: state.preferences.units, locale: state.preferences.effectiveLocale))
                    }
                }
                if loadingTemporalAnalysis {
                    Section(AppText.string("jumps.analysis.title", language: language)) { ProgressView() }
                } else if let temporalAnalysisError {
                    Section(AppText.string("jumps.analysis.title", language: language)) {
                        Text(verbatim: temporalAnalysisError).foregroundStyle(.red)
                    }
                } else if let temporalAnalysis {
                    temporalAnalysisSection(temporalAnalysis)
                }
                Section(AppText.string("history.notes", language: language)) { TextField(AppText.string("history.notesHint", language: language), text: $notes, axis: .vertical).lineLimit(3...6).onChange(of: notes) { value in if value.count > 500 { notes = String(value.prefix(500)) } } }
                if let alert { Text(verbatim: alert).foregroundStyle(.red) }
                Section { Button(AppText.string("history.delete", language: language), role: .destructive) { confirmDelete = true }.disabled(busy).frame(minHeight: 48) }
            }
            .navigationTitle(AppText.string("history.details", language: language))
            .task(id: initial.id) { await loadTemporalAnalysis() }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(AppText.string("common.close", language: language)) { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) { Button(AppText.string("common.save", language: language)) { Task { await save() } }.disabled(busy).frame(minHeight: 44) }
            }
            .confirmationDialog(AppText.string("history.deleteConfirm", language: language), isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(AppText.string("history.delete", language: language), role: .destructive) { Task { await delete() } }.disabled(busy)
                Button(AppText.string("common.cancel", language: language), role: .cancel) { }.disabled(busy)
            }
        }
    }
    @ViewBuilder private func temporalAnalysisSection(_ analysis: SavedTemporalAnalysis) -> some View {
        Section(AppText.string("jumps.analysis.title", language: language)) {
            LabeledContent(AppText.string("jumps.analysis.source", language: language),
                           value: AppText.string("jumps.source.\(analysis.source.rawValue)", language: language))
            LabeledContent(AppText.string("jumps.video.frame", language: language),
                           value: analysis.sourceFrameCount.formatted(.number.locale(state.preferences.effectiveLocale)))
            LabeledContent(AppText.string("jumps.video.time", language: language),
                           value: "\(localizedPTS(analysis.sourceOriginUs)) µs")
            LabeledContent(AppText.string("jumps.analysis.events", language: language),
                           value: analysis.events.count.formatted(.number.locale(state.preferences.effectiveLocale)))
            ForEach(Array(analysis.events.enumerated()), id: \.offset) { element in
                let mark = element.element
                VStack(alignment: .leading, spacing: 4) {
                    Text(AppText.string(eventTitleKey(mark.kind), language: language)).font(.subheadline.weight(.medium))
                    Text(verbatim: "\(AppText.string("jumps.video.frame", language: language)) \(mark.frameIndex + 1) · \(AppText.string("jumps.video.time", language: language)): \(localizedPTS(mark.ptsUs)) µs")
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
            if analysis.temporalState == .realtimeDeclared {
                Text(AppText.string("jumps.timing.declared", language: language)).font(.footnote)
                Text(AppText.string("jumps.timing.warning", language: language)).font(.footnote).foregroundStyle(.secondary)
            } else {
                Text(AppText.string("jumps.error.timing", language: language)).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func eventTitleKey(_ kind: JumpEventKind) -> String {
        switch kind {
        case .movementStart: "jumps.event.movementStart"
        case .initialContact: "jumps.event.initialContact"
        case .takeoff: "jumps.event.takeoff"
        case .landing: "jumps.event.landing"
        }
    }

    private func localizedPTS(_ ptsUs: Int64) -> String {
        ptsUs.formatted(.number.locale(state.preferences.effectiveLocale))
    }

    private func loadTemporalAnalysis() async {
        guard let store = state.store else {
            temporalAnalysisError = AppText.string("error.database", language: language)
            return
        }
        loadingTemporalAnalysis = true
        temporalAnalysisError = nil
        defer { loadingTemporalAnalysis = false }
        do { temporalAnalysis = try await store.temporalAnalysis(measurementID: initial.id) }
        catch { temporalAnalysisError = AppText.string("error.database", language: language) }
    }

    private func save() async {
        guard !busy, let store = state.store else { return }; busy = true; defer { busy = false }
        do { try await store.updateNotes(id: initial.id, notes: notes); state.historyDidCommit(); dismiss() }
        catch { alert = displayError(error, language: language) }
    }
    private func delete() async {
        guard !busy, let store = state.store else { return }; busy = true; defer { busy = false }
        do { try await store.deleteMeasurement(id: initial.id); state.historyDidCommit(); dismiss() }
        catch { alert = displayError(error, language: language) }
    }
}
