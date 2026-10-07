import SwiftUI

struct HistoryView: View {
    @ObservedObject var state: AppState
    let fixedOwnerID: UUID?
    @State private var rows: [SavedMeasurement] = []
    @State private var cursor: (date: Date, id: UUID)?
    @State private var hasMore = false
    @State private var selectedProtocol: SavedProtocol?
    @State private var selectedOwner: UUID?
    @State private var selectedPeriod: HistoryPeriodPreset = .allTime
    @State private var customFrom = Date()
    @State private var customThrough = Date()
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
    private var protocolPicker: some View {
        Picker(AppText.string("history.protocol", language: language), selection: $selectedProtocol) {
            Text(AppText.string("history.all", language: language)).tag(SavedProtocol?.none)
            ForEach(SavedProtocol.allCases, id: \.self) { Text(AppText.string($0.titleKey, language: language)).tag(Optional($0)) }
        }
        .accessibilityIdentifier("history.protocol")
        .frame(minHeight: 44)
    }
    private var ownerPicker: some View {
        Picker(AppText.string("history.athlete", language: language), selection: $selectedOwner) {
            Text(AppText.string("history.all", language: language)).tag(UUID?.none)
            ForEach(state.athletes) { athlete in
                // The global menu lists archived athletes too, so historical
                // roots stay reachable; the name shown is always the current
                // roster name, never a fabricated historical snapshot.
                if athlete.archivedAt == nil {
                    Text(verbatim: athlete.name).tag(Optional(athlete.id))
                } else {
                    Text(verbatim: "\(athlete.name) · \(AppText.string("profiles.archived", language: language))").tag(Optional(athlete.id))
                }
            }
        }
        .accessibilityIdentifier("history.owner")
        .frame(minHeight: 44)
    }
    private var periodPicker: some View {
        Picker(AppText.string("history.period", language: language), selection: $selectedPeriod) {
            Text(AppText.string("history.allTime", language: language)).tag(HistoryPeriodPreset.allTime)
            Text(AppText.string("history.last7Days", language: language)).tag(HistoryPeriodPreset.last7Days)
            Text(AppText.string("history.last30Days", language: language)).tag(HistoryPeriodPreset.last30Days)
            Text(AppText.string("history.custom", language: language)).tag(HistoryPeriodPreset.custom)
        }
        .accessibilityIdentifier("history.period")
        .frame(minHeight: 44)
    }
    private var fromPicker: some View {
        DatePicker(AppText.string("history.from", language: language), selection: $customFrom, displayedComponents: .date)
            .accessibilityIdentifier("history.from")
            .frame(minHeight: 44)
    }
    private var throughPicker: some View {
        DatePicker(AppText.string("history.through", language: language), selection: $customThrough, displayedComponents: .date)
            .accessibilityIdentifier("history.through")
            .frame(minHeight: 44)
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                // Adaptable native filter layouts: side-by-side when the
                // locale/Dynamic Type fits, stacked otherwise, so long
                // German/Turkish labels never clip (iOS 16 ViewThatFits).
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        protocolPicker
                        if fixedOwnerID == nil { ownerPicker }
                    }
                    VStack(spacing: 0) {
                        protocolPicker
                        if fixedOwnerID == nil { ownerPicker }
                    }
                }
                .pickerStyle(.menu)
                .padding(.horizontal, 16)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        periodPicker
                        Spacer()
                        resetButton
                    }
                    VStack(spacing: 0) {
                        periodPicker
                        resetButton
                    }
                }
                .pickerStyle(.menu)
                .padding(.horizontal, 16)
                if selectedPeriod == .custom {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 16) { fromPicker; throughPicker }
                        VStack(spacing: 0) { fromPicker; throughPicker }
                    }
                    .padding(.horizontal, 16)
                }
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                    TextField(AppText.string("history.search", language: language), text: $query)
                        .accessibilityIdentifier("history.search")
                        .textContentType(.none)
                        .autocorrectionDisabled()
                }
                .padding(8)
                .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 16)
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
                            Button { selected = measurement } label: {
                                HistoryRow(item: measurement,
                                           athlete: measurement.ownerID.flatMap { id in state.athletes.first { $0.id == id } },
                                           language: language,
                                           locale: state.preferences.effectiveLocale,
                                           units: state.preferences.units)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("history.row.\(measurement.id.uuidString)")
                        }
                        if hasMore {
                            Button(AppText.string("history.more", language: language)) { Task { await loadNext(generation) } }
                                .disabled(loadingPage)
                                .frame(minHeight: 48)
                                .accessibilityIdentifier("history.more")
                        }
                        if loadingPage && !rows.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                    }.listStyle(.plain)
                }
            }
            .navigationTitle(AppText.string("tab.history", language: language))
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
            .onChange(of: selectedPeriod) { _ in Task { await reset() } }
            .onChange(of: customFrom) { _ in Task { await reset() } }
            .onChange(of: customThrough) { _ in Task { await reset() } }
            .onChange(of: state.historyRevision) { _ in Task { await reset() } }
            .sheet(item: $selected, onDismiss: { Task { await reset() } }) { item in MeasurementDetailView(state: state, initial: item) }
        }
    }
    private var resetButton: some View {
        Button(AppText.string("history.resetFilters", language: language)) {
            selectedProtocol = nil
            if fixedOwnerID == nil { selectedOwner = nil }
            selectedPeriod = .allTime
            customFrom = Date()
            customThrough = Date()
            query = ""
            Task { await reset() }
        }
        .frame(minHeight: 44)
        .accessibilityIdentifier("history.resetFilters")
    }
    private func reset() async { generation += 1; loadingRequest = nil; loadingPage = false; rows = []; cursor = nil; hasMore = false; error = nil; await loadNext(generation) }
    private func loadNext(_ request: Int) async {
        guard loadingRequest == nil, request == generation, let store = state.store else { return }
        // Capture the full filter snapshot before the actor await so a
        // concurrent preset/owner/protocol/search change starts a newer
        // generation instead of mixing bounds into this page. The fixed
        // profile owner always wins; it is never reinterpreted by the menu.
        let pageCursor = cursor
        let owner = fixedOwnerID ?? selectedOwner
        let protocolFilter = selectedProtocol
        let search = query.isEmpty ? nil : query
        let period = selectedPeriod
        let fromDay = customFrom
        let throughDay = customThrough
        let bounds: HistoryDateBounds
        do {
            // Calendar.current gives current-timezone day semantics; custom
            // dates are day-precision and ignored unless the custom preset is
            // chosen. An inverted custom range surfaces a localized error and
            // issues NO query, never silent all-history.
            bounds = try HistoryFilters.bounds(for: period, customFrom: fromDay, customThrough: throughDay, calendar: .current)
        } catch {
            if request == generation {
                self.error = AppText.string((error as? HistoryFilterError)?.localizationKey ?? "history.invalidRange", language: language)
            }
            return
        }
        loadingRequest = request
        loadingPage = true
        defer { if loadingRequest == request { loadingRequest = nil; loadingPage = false } }
        do {
            let page = try await store.history(ownerID: owner, protocolKey: protocolFilter, search: search, limit: 50, before: pageCursor, recordedFrom: bounds.from, recordedBefore: bounds.before)
            guard request == generation, cursor?.id == pageCursor?.id else { return }
            let known = Set(rows.map(\.id))
            rows += page.items.filter { !known.contains($0.id) }
            cursor = page.nextBefore; hasMore = page.nextBefore != nil
        } catch { if request == generation { self.error = displayError(error, language: language) } }
    }
}

private struct HistoryRow: View {
    let item: SavedMeasurement
    let athlete: Athlete?
    let language: AppLanguage
    let locale: Locale
    let units: UnitProfile
    /// Dominant metric via the frozen protocol contract: RSI for drop jump,
    /// height for CMJ/SJ/Abalakov/unilateral. Never an invented zero: nil when
    /// there is nothing stored.
    private var primary: SavedMetric? { ProtocolPresentation.primaryMetric(in: item.metrics, protocolKey: item.protocolKey) }
    private var secondary: [SavedMetric] {
        guard let primary else { return item.metrics }
        return item.metrics.filter { $0.ordinal != primary.ordinal }
    }
    private var ownerLine: Text {
        if let athlete {
            if athlete.archivedAt == nil { return Text(verbatim: athlete.name) }
            return Text(verbatim: "\(athlete.name) · \(AppText.string("profiles.archived", language: language))")
        }
        return Text(AppText.string("history.unassigned", language: language))
    }
    private var contextLine: String? {
        var parts: [String] = []
        if let side = item.side {
            parts.append(side == "LEFT" ? AppText.string("history.left", language: language)
                         : (side == "RIGHT" ? AppText.string("history.right", language: language) : side))
        }
        if let drop = item.dropHeightCm {
            parts.append("\(MeasurementPresentation.shortLength(drop, as: units.shortLength).formatted(.number.precision(.fractionLength(0...2)).locale(locale))) \(units.shortLength.rawValue)")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AthleteAvatarView(name: athlete?.name ?? AppText.string("history.unassigned", language: language), key: athlete?.avatarKey, size: 40)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(AppText.string(item.protocolKey.titleKey, language: language)).font(.headline)
                    Spacer()
                    Text(item.recordedAt.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(locale))).font(.caption).foregroundStyle(.secondary)
                }
                if let primary {
                    Text(verbatim: "\(metricName(primary.key, language: language)): \(formattedMetric(primary, units: units, locale: locale))")
                        .font(.subheadline.weight(.semibold))
                }
                // Restrained secondary metrics: one Text per metric so long
                // values wrap with Dynamic Type instead of one clipped line.
                ForEach(secondary, id: \.ordinal) { metric in
                    Text(verbatim: "\(metricName(metric.key, language: language)): \(formattedMetric(metric, units: units, locale: locale))")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ownerLine.font(.caption).foregroundStyle(.secondary)
                if let contextLine {
                    Text(verbatim: contextLine).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
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
    @State private var confirmDiscardNotes = false
    @State private var temporalAnalysis: SavedTemporalAnalysis?
    @State private var temporalAnalysisError: String?
    @State private var loadingTemporalAnalysis = true
    init(state: AppState, initial: SavedMeasurement) { self.state = state; self.initial = initial; _notes = State(initialValue: initial.notes ?? "") }
    private var language: AppLanguage { state.preferences.language }
    private var notesDirty: Bool { notes != (initial.notes ?? "") }
    private var primary: SavedMetric? { ProtocolPresentation.primaryMetric(in: initial.metrics, protocolKey: initial.protocolKey) }
    private var secondary: [SavedMetric] {
        guard let primary else { return initial.metrics }
        return initial.metrics.filter { $0.ordinal != primary.ordinal }
    }
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
                if loadingTemporalAnalysis {
                    // The principal result stays hidden until the stored graph
                    // read succeeds, so no unverified hero flashes before .task.
                    Section(AppText.string("jumps.analysis.title", language: language)) {
                        ProgressView().accessibilityIdentifier("measurement.analysis.loading")
                    }
                } else if let temporalAnalysisError {
                    // Fail closed: a present-but-corrupt graph shows the
                    // warning and never the hero as a verified healthy result.
                    Section(AppText.string("jumps.analysis.title", language: language)) {
                        Text(verbatim: temporalAnalysisError)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("measurement.analysis.error")
                    }
                } else {
                    Section(AppText.string("history.metrics", language: language)) {
                        if let primary {
                            OpenJumpMetricHero(metric: primary, language: language, units: state.preferences.units, locale: state.preferences.effectiveLocale)
                                .accessibilityIdentifier("measurement.primaryMetric")
                        }
                        ForEach(secondary, id: \.ordinal) { metric in
                            LabeledContent(metricName(metric.key, language: language), value: formattedMetric(metric, units: state.preferences.units, locale: state.preferences.effectiveLocale))
                        }
                        if temporalAnalysis == nil {
                            // Legacy success: no stored graph, honest absence.
                            Text(AppText.string("history.legacyNotice", language: language))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .accessibilityIdentifier("measurement.legacyNotice")
                        }
                    }
                    if let temporalAnalysis {
                        temporalAnalysisSection(temporalAnalysis)
                    }
                }
                Section(AppText.string("history.notes", language: language)) {
                    TextField(AppText.string("history.notesHint", language: language), text: $notes, axis: .vertical)
                        .lineLimit(3...6)
                        .accessibilityIdentifier("measurement.notes")
                        .onChange(of: notes) { value in if value.count > 500 { notes = String(value.prefix(500)) } }
                }
                if let alert { Text(verbatim: alert).foregroundStyle(.red) }
                Section { Button(AppText.string("history.delete", language: language), role: .destructive) { confirmDelete = true }.disabled(busy).frame(minHeight: 48).accessibilityIdentifier("measurement.delete") }
            }
            .navigationTitle(AppText.string("history.details", language: language))
            .task(id: initial.id) { await loadTemporalAnalysis() }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.close", language: language)) {
                        // Unchanged notes close directly with no UPDATE; dirty
                        // notes confirm so an interactive or explicit close
                        // never loses an edit silently.
                        if notesDirty { confirmDiscardNotes = true } else { dismiss() }
                    }
                    .disabled(busy)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("measurement.close")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppText.string("common.save", language: language)) { Task { await save() } }
                        .disabled(busy)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("measurement.save")
                }
            }
            .interactiveDismissDisabled(busy || notesDirty)
            .confirmationDialog(AppText.string("profiles.discardTitle", language: language), isPresented: $confirmDiscardNotes, titleVisibility: .visible) {
                Button(AppText.string("common.discard", language: language), role: .destructive) { dismiss() }
                Button(AppText.string("common.cancel", language: language), role: .cancel) { }
            } message: {
                Text(AppText.string("profiles.discardBody", language: language))
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
            temporalAnalysisError = AppText.string("history.analysisInvalid", language: language)
            return
        }
        loadingTemporalAnalysis = true
        temporalAnalysisError = nil
        defer { loadingTemporalAnalysis = false }
        // Full-array store recompute stays the validator: any present-but
        // invalid graph throws and surfaces the fail-closed warning, while a
        // legacy row with zero stored events returns nil success.
        do { temporalAnalysis = try await store.temporalAnalysis(measurementID: initial.id) }
        catch { temporalAnalysisError = AppText.string("history.analysisInvalid", language: language) }
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
