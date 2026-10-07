import SwiftUI

struct ProfilesView: View {
    @ObservedObject var state: AppState
    @State private var showArchived = false
    @State private var query = ""
    @State private var editor: Athlete?
    @State private var creating = false
    @State private var selected: Athlete?
    @State private var pendingEditor: Athlete?
    @State private var showPendingEditor = false
    @State private var error: String?
    private var language: AppLanguage { state.preferences.language }
    private var filtered: [Athlete] {
        state.athletes.filter { ($0.archivedAt != nil) == showArchived && matches($0, query: query) }
    }
    private func matches(_ athlete: Athlete, query: String) -> Bool {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return true }
        // Local name search only: case- and diacritic-insensitive, no
        // database migration or ownership rewrite.
        return athlete.name.range(of: trimmed, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }
    private func profileRowAccessibilityLabel(for athlete: Athlete) -> String {
        let status = athlete.archivedAt == nil
            ? AppText.string("profiles.active", language: language)
            : AppText.string("profiles.archived", language: language)
        return athlete.name + ", " + status
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                    TextField(AppText.string("profiles.search", language: language), text: $query)
                        .accessibilityIdentifier("profiles.search")
                        .textContentType(.none)
                        .autocorrectionDisabled()
                }
                .padding(OpenJumpSpacing.sm)
                .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 10))
                .padding([.horizontal, .top], OpenJumpSpacing.md)
                Group {
                    if filtered.isEmpty {
                        OpenJumpEmptyState(
                            title: AppText.string(
                                query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                    ? (showArchived ? "profiles.archivedEmpty" : "profiles.empty")
                                    : "profiles.noResults",
                                language: language
                            ),
                            systemImage: "person.crop.circle",
                            description: query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? Text(AppText.string("profiles.emptyBody", language: language)) : nil
                        ) {
                            if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                Button(AppText.string("profiles.add", language: language)) { creating = true }
                                    .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                                    .frame(minHeight: 44)
                            }
                        }
                    } else {
                        List {
                            ForEach(filtered) { athlete in
                                Button { selected = athlete } label: {
                                    HStack(spacing: OpenJumpSpacing.md) {
                                        AthleteAvatarView(name: athlete.name, key: athlete.avatarKey, size: 44)
                                            .accessibilityHidden(true)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(athlete.name)
                                                .font(.headline)
                                                .lineLimit(2)
                                            Text(athlete.archivedAt == nil
                                                ? AppText.string("profiles.active", language: language)
                                                : AppText.string("profiles.archived", language: language))
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                        if state.preferences.selectedAthleteID == athlete.id {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundStyle(Color.openJumpGreen)
                                                .accessibilityLabel(AppText.string("profiles.selected", language: language))
                                        }
                                    }
                                    .frame(minHeight: 52)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("profile.row.\(athlete.id.uuidString)")
                                .accessibilityLabel(profileRowAccessibilityLabel(for: athlete))
                                .swipeActions {
                                    Button(AppText.string("profiles.edit", language: language)) { editor = athlete }
                                        .tint(.openJumpGreen)
                                }
                            }
                        }
                        .listStyle(.insetGrouped)
                    }
                }
            }
            .navigationTitle(AppText.string("tab.profiles", language: language))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(showArchived
                        ? AppText.string("profiles.active", language: language)
                        : AppText.string("profiles.archived", language: language)) { showArchived.toggle() }
                        .frame(minHeight: 44)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { creating = true } label: {
                        Label(AppText.string("profiles.add", language: language), systemImage: "plus")
                    }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("profiles.add")
                }
            }
            .safeAreaInset(edge: .bottom) {
                if let error {
                    Text(verbatim: error).font(.footnote).foregroundStyle(.red).padding()
                }
            }
            .sheet(isPresented: $creating) {
                AthleteEditor(state: state, athlete: nil) { Task { await reload() } }
            }
            .sheet(item: $editor) { person in
                AthleteEditor(state: state, athlete: person) { Task { await reload() } }
            }
            .sheet(item: $selected, onDismiss: {
                // Present the editor only after detail actually closed, so the
                // two sheet bindings never flip in the same turn.
                if pendingEditor != nil { showPendingEditor = true }
            }) { person in
                AthleteDetail(
                    state: state,
                    athlete: person,
                    edit: { pendingEditor = person; selected = nil },
                    changed: { Task { await reload() } }
                )
            }
            .sheet(isPresented: $showPendingEditor, onDismiss: { pendingEditor = nil }) {
                if let person = pendingEditor {
                    AthleteEditor(state: state, athlete: person) { Task { await reload() } }
                }
            }
        }
    }
    private func reload() async {
        do { try await state.refresh() } catch { self.error = displayError(error, language: language) }
    }
}

private struct AvatarPicker: View {
    @Environment(\.dismiss) private var dismiss
    let language: AppLanguage
    let name: String
    let selectedKey: String?
    let apply: (String?) -> Void
    @State private var pending: String?
    @State private var initialized = false
    /// Stable 1-based ordinals for the localized "Avatar %ld" labels.
    /// Precomputed once (no force-unwrapped `firstIndex`, preventive only).
    private static let ordinals: [String: Int] = Dictionary(
        uniqueKeysWithValues: AthleteAvatarCatalog.options.enumerated().map { ($0.element.key, $0.offset + 1) }
    )
    /// Alias resolution applies to the highlight only; `pending` itself keeps
    /// the raw key so Confirm without an explicit selection preserves
    /// unknown/legacy keys byte-for-byte.
    private var highlightedKey: String? { pending.flatMap { AthleteAvatarCatalog.resolve($0)?.key } }
    private func optionLabel(_ key: String) -> String {
        let ordinal = Self.ordinals[key] ?? 0
        return AppText.string("avatars.option", language: language)
            .replacingOccurrences(of: "%ld", with: "\(ordinal)")
    }
    /// Localized preview label: initials, the selected face ordinal, or the
    /// legacy placeholder for unknown pending keys. The raw pending key is
    /// never shown and never normalized; Confirm applies it byte-for-byte.
    private var previewLabel: String {
        if pending == nil {
            return AppText.string("avatars.initials", language: language)
        }
        if let highlighted = highlightedKey {
            return optionLabel(highlighted)
        }
        return AppText.string("avatars.legacy", language: language)
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                VStack(spacing: 0) {
                    previewHeader
                    categoryChips(proxy: proxy)
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: OpenJumpSpacing.md) {
                            initialsRow
                            ForEach(AthleteAvatarCatalog.groups) { group in
                                groupSection(group)
                                    .id(group.id)
                            }
                        }
                        .padding(OpenJumpSpacing.md)
                    }
                }
            }
            .navigationTitle(AppText.string("avatars.title", language: language))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.cancel", language: language)) { dismiss() }
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("avatar.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    // Confirm only edits the draft; the editor's Save persists.
                    Button(AppText.string("common.confirm", language: language)) { apply(pending); dismiss() }
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("avatar.confirm")
                }
            }
            .onAppear {
                if !initialized {
                    pending = selectedKey
                    initialized = true
                }
            }
        }
    }

    /// Pinned compact preview: stays visible while the grids scroll so the
    /// selected face never scrolls off-screen.
    private var previewHeader: some View {
        HStack(spacing: OpenJumpSpacing.md) {
            AthleteAvatarView(name: name, key: pending, size: 72)
                .accessibilityHidden(true)
            Text(previewLabel)
                .font(.headline)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(OpenJumpSpacing.md)
        .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 16))
        .padding([.horizontal, .top], OpenJumpSpacing.md)
    }

    /// Horizontal category shortcuts; each chip scrolls to its group header.
    /// Labels reuse the existing group keys; no new option or asset mapping.
    private func categoryChips(proxy: ScrollViewProxy) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: OpenJumpSpacing.sm) {
                ForEach(AthleteAvatarCatalog.groups) { group in
                    Button {
                        withAnimation {
                            proxy.scrollTo(group.id, anchor: .top)
                        }
                    } label: {
                        Text(AppText.string("avatars.group.\(group.id)", language: language))
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, OpenJumpSpacing.md)
                            .padding(.vertical, OpenJumpSpacing.sm)
                            .frame(minHeight: 48)
                            .background(Color.openJumpSurface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("avatar.jump.\(group.id)")
                }
            }
            .padding(.horizontal, OpenJumpSpacing.md)
            .padding(.vertical, OpenJumpSpacing.sm)
        }
    }

    /// Full-width initials row: circle plus localized label, 48pt minimum.
    private var initialsRow: some View {
        Button { pending = nil } label: {
            HStack(spacing: OpenJumpSpacing.md) {
                AthleteAvatarView(name: name, key: nil, size: 56)
                    .accessibilityHidden(true)
                Text(AppText.string("avatars.initials", language: language))
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .lineLimit(2)
                if pending == nil {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Color.openJumpGreen)
                        .accessibilityHidden(true)
                }
            }
            .padding(OpenJumpSpacing.sm)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
            .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12)
                .stroke(pending == nil ? Color.openJumpGreen : .clear, lineWidth: 3))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("avatar.initials")
        .accessibilityLabel(AppText.string("avatars.initials", language: language))
        .accessibilityAddTraits(pending == nil ? .isSelected : [])
    }

    /// One full-width header plus its own adaptive grid per group.
    /// Same 84 options, same order, same stable keys as the catalog.
    private func groupSection(_ group: AthleteAvatarGroup) -> some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
            Text(AppText.string("avatars.group.\(group.id)", language: language))
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("avatar.group.\(group.id)")
                .accessibilityAddTraits(.isHeader)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 72))], spacing: OpenJumpSpacing.sm) {
                ForEach(group.options) { option in
                    Button { pending = option.key } label: {
                        AthleteAvatarView(name: name, key: option.key, size: 64)
                            .padding(4)
                            .overlay(Circle().stroke(
                                highlightedKey == option.key ? Color.openJumpGreen : .clear,
                                lineWidth: 3
                            ))
                    }
                    .buttonStyle(.plain)
                    .frame(minWidth: 72, minHeight: 72)
                    .accessibilityIdentifier("avatar.option.\(option.key)")
                    .accessibilityLabel(optionLabel(option.key))
                    .accessibilityAddTraits(highlightedKey == option.key ? .isSelected : [])
                }
            }
        }
    }
}

private struct AthleteEditor: View {
    @ObservedObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focusedField: Field?
    let athlete: Athlete?
    let saved: () -> Void
    @State private var draft: AthleteEditorDraft
    @State private var error: String?
    @State private var showAvatarPicker = false
    @State private var confirmDiscard = false
    @State private var busy = false
    private enum Field: Hashable { case name, weight, height, notes }
    private var language: AppLanguage { state.preferences.language }
    init(state: AppState, athlete: Athlete?, saved: @escaping () -> Void) {
        self.state = state
        self.athlete = athlete
        self.saved = saved
        _draft = State(initialValue: AthleteEditorDraft(
            initial: athlete,
            locale: state.preferences.effectiveLocale,
            massUnit: state.preferences.units.mass,
            lengthUnit: state.preferences.units.shortLength
        ))
    }
    private var weightInvalid: Bool {
        !draft.weightText.isEmpty && (try? draft.canonicalWeightKg()) == nil
    }
    private var heightInvalid: Bool {
        !draft.heightText.isEmpty && (try? draft.canonicalHeightCm()) == nil
    }
    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.string("profiles.identity", language: language)) {
                    HStack(spacing: OpenJumpSpacing.md) {
                        AthleteAvatarView(name: draft.name, key: draft.avatarKey, size: 56)
                            .accessibilityHidden(true)
                        Button(AppText.string("profiles.avatar", language: language)) { showAvatarPicker = true }
                            .frame(minHeight: 48)
                            .accessibilityIdentifier("profile.avatar")
                            .disabled(busy)
                    }
                    TextField(AppText.string("profiles.name", language: language), text: $draft.name)
                        .textContentType(.name)
                        .focused($focusedField, equals: .name)
                        .accessibilityIdentifier("profile.name")
                        .disabled(busy)
                }
                Section {
                    TextField(
                        "\(AppText.string("profiles.weight", language: language)) (\(draft.massUnit.rawValue))",
                        text: $draft.weightText
                    )
                    .keyboardType(.decimalPad)
                    .focused($focusedField, equals: .weight)
                    .accessibilityIdentifier("profile.weight")
                    .disabled(busy)
                    if weightInvalid {
                        Text(AppText.string("error.invalidNumber", language: language))
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    TextField(
                        "\(AppText.string("profiles.height", language: language)) (\(draft.lengthUnit.rawValue))",
                        text: $draft.heightText
                    )
                    .keyboardType(.decimalPad)
                    .focused($focusedField, equals: .height)
                    .accessibilityIdentifier("profile.height")
                    .disabled(busy)
                    if heightInvalid {
                        Text(AppText.string("error.invalidNumber", language: language))
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text(AppText.string("profiles.physical", language: language))
                } footer: {
                    Text(AppText.string("profiles.physicalHint", language: language)).font(.footnote)
                }
                Section(AppText.string("profiles.notes", language: language)) {
                    TextField(
                        AppText.string("profiles.notesHint", language: language),
                        text: $draft.notes,
                        axis: .vertical
                    )
                    .lineLimit(2...5)
                    .focused($focusedField, equals: .notes)
                    .accessibilityIdentifier("profile.notes")
                    .disabled(busy)
                }
                if let error {
                    Text(verbatim: error).foregroundStyle(.red)
                }
            }
            .navigationTitle(AppText.string(athlete == nil ? "profiles.add" : "profiles.edit", language: language))
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(AppText.string("common.done", language: language)) { focusedField = nil }
                        .frame(minHeight: 44)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.cancel", language: language)) {
                        // Cancel with no edits closes directly; a dirty draft
                        // confirms, and confirming discards without any
                        // store or preference mutation.
                        if draft.isDirty { confirmDiscard = true } else { dismiss() }
                    }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("profile.cancel")
                    .disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppText.string("common.save", language: language)) { Task { await save() } }
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("profile.save")
                        .disabled(busy || !draft.isNameValid)
                }
            }
            .interactiveDismissDisabled(busy || draft.isDirty)
            .confirmationDialog(
                AppText.string("profiles.discardTitle", language: language),
                isPresented: $confirmDiscard,
                titleVisibility: .visible
            ) {
                Button(AppText.string("common.discard", language: language), role: .destructive) { dismiss() }
                Button(AppText.string("common.cancel", language: language), role: .cancel) {}
            } message: {
                Text(AppText.string("profiles.discardBody", language: language))
            }
            .sheet(isPresented: $showAvatarPicker) {
                AvatarPicker(language: language, name: draft.name, selectedKey: draft.avatarKey) {
                    draft.avatarKey = $0
                }
            }
        }
    }
    private func save() async {
        guard !busy, let store = state.store else { return }
        let candidate: Athlete
        do {
            // Domain validation before marking busy; no store call yet.
            candidate = try draft.validatedAthlete()
        } catch {
            self.error = displayError(error, language: language)
            return
        }
        busy = true
        defer { busy = false }
        do {
            // One persistence call against the existing store APIs only.
            if draft.initial != nil {
                _ = try await store.updateAthlete(candidate)
            } else {
                _ = try await store.createAthlete(
                    name: candidate.name,
                    weightKg: candidate.weightKg,
                    heightCm: candidate.heightCm,
                    notes: candidate.notes,
                    avatarKey: candidate.avatarKey
                )
            }
            // Commit succeeded; a later refresh failure surfaces through the
            // existing list error pattern and never reinserts the athlete.
            saved()
            dismiss()
        } catch {
            self.error = displayError(error, language: language)
        }
    }
}

private struct AthleteDetail: View {
    @ObservedObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    let athlete: Athlete
    let edit: () -> Void
    let changed: () -> Void
    @State private var count = 0
    @State private var busy = false
    @State private var error: String?
    @State private var confirm = false
    @State private var showHistory = false
    private var language: AppLanguage { state.preferences.language }
    private var isArchived: Bool { athlete.archivedAt != nil }
    private func formattedMassValue(kilograms: Double) -> String {
        let number = MeasurementPresentation.mass(kilograms, as: state.preferences.units.mass)
            .formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale))
        return number + " " + state.preferences.units.mass.rawValue
    }
    private func formattedShortLengthValue(centimeters: Double) -> String {
        let number = MeasurementPresentation.shortLength(centimeters, as: state.preferences.units.shortLength)
            .formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale))
        return number + " " + state.preferences.units.shortLength.rawValue
    }
    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.string("profiles.identity", language: language)) {
                    HStack(spacing: OpenJumpSpacing.md) {
                        AthleteAvatarView(name: athlete.name, key: athlete.avatarKey, size: 72)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(athlete.name).font(.headline).lineLimit(3)
                            Text(isArchived
                                ? AppText.string("profiles.archived", language: language)
                                : AppText.string("profiles.active", language: language))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                if athlete.weightKg != nil || athlete.heightCm != nil {
                    Section(AppText.string("profiles.physical", language: language)) {
                        if let kg = athlete.weightKg {
                            LabeledContent(
                                AppText.string("profiles.weight", language: language),
                                value: formattedMassValue(kilograms: kg)
                            )
                        }
                        if let cm = athlete.heightCm {
                            LabeledContent(
                                AppText.string("profiles.height", language: language),
                                value: formattedShortLengthValue(centimeters: cm)
                            )
                        }
                    }
                }
                if let notes = athlete.notes {
                    Section(AppText.string("profiles.notes", language: language)) {
                        Text(notes)
                    }
                }
                Section(AppText.string("profiles.historyCount", language: language)) {
                    LabeledContent(
                        AppText.string("profiles.historyCount", language: language),
                        value: "\(count)"
                    )
                    Button(AppText.string("profiles.history", language: language)) { showHistory = true }
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("profile.history")
                        .disabled(busy)
                }
                Section {
                    if !isArchived {
                        Button(AppText.string("profiles.select", language: language)) {
                            state.preferences.selectedAthleteID = athlete.id
                            dismiss()
                        }
                        .disabled(busy || state.preferences.selectedAthleteID == athlete.id)
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("profile.select")
                    }
                    Button(AppText.string("profiles.edit", language: language), action: edit)
                        .disabled(busy)
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("profile.edit")
                    Button(AppText.string(
                        isArchived ? "profiles.restore" : "profiles.archive",
                        language: language
                    )) { confirm = true }
                        .disabled(busy)
                        .frame(minHeight: 48)
                        .accessibilityIdentifier("profile.archive")
                }
                if let error {
                    Text(verbatim: error).foregroundStyle(.red)
                }
            }
            .navigationTitle(athlete.name)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.string("common.close", language: language)) { dismiss() }
                        .frame(minHeight: 44)
                        .disabled(busy)
                }
            }
            .confirmationDialog(
                AppText.string(isArchived ? "profiles.restoreTitle" : "profiles.archiveTitle", language: language),
                isPresented: $confirm,
                titleVisibility: .visible
            ) {
                Button(
                    AppText.string(isArchived ? "profiles.restore" : "profiles.archive", language: language),
                    role: isArchived ? nil : .destructive
                ) { Task { await toggleArchive() } }
                Button(AppText.string("common.cancel", language: language), role: .cancel) {}
            }
            .task { await loadCount() }
            .sheet(isPresented: $showHistory) {
                // Fixed history owner: this detail athlete, never follows the
                // globally selected athlete.
                HistoryView(state: state, ownerID: athlete.id)
            }
        }
    }
    private func loadCount() async {
        guard let store = state.store else { return }
        do {
            count = try await store.measurementCount(ownerID: athlete.id)
        } catch {
            self.error = displayError(error, language: language)
        }
    }
    private func toggleArchive() async {
        guard !busy, let store = state.store else { return }
        busy = true
        defer { busy = false }
        do {
            // Last-active guard stays in the SQLite API; no permanent delete.
            try await store.setArchived(athlete.id, archived: !isArchived)
            if !isArchived, state.preferences.selectedAthleteID == athlete.id {
                state.preferences.selectedAthleteID = nil
            }
            changed()
            dismiss()
        } catch {
            self.error = displayError(error, language: language)
        }
    }
}
