import SwiftUI

struct ProfilesView: View {
    @ObservedObject var state: AppState
    @State private var showArchived = false
    @State private var editor: Athlete?
    @State private var creating = false
    @State private var selected: Athlete?
    @State private var error: String?
    private var language: AppLanguage { state.preferences.language }
    private var visible: [Athlete] { state.athletes.filter { ($0.archivedAt != nil) == showArchived } }
    var body: some View {
        NavigationStack {
            Group {
                if visible.isEmpty {
                    ContentUnavailableView {
                        Label(AppText.string(showArchived ? "profiles.archivedEmpty" : "profiles.empty", language: language), systemImage: "person.crop.circle")
                    } description: {
                        Text(AppText.string("profiles.emptyBody", language: language))
                    } actions: {
                        Button(AppText.string("profiles.add", language: language)) { creating = true }
                            .buttonStyle(.borderedProminent).tint(.openJumpGreen)
                    }
                } else {
                    List {
                        ForEach(visible) { athlete in
                            Button { selected = athlete } label: {
                                HStack(spacing: 12) {
                                    AthleteAvatarView(name: athlete.name, key: athlete.avatarKey, size: 44).accessibilityHidden(true)
                                    VStack(alignment: .leading) { Text(athlete.name).font(.headline); Text(athlete.archivedAt == nil ? AppText.string("profiles.active", language: language) : AppText.string("profiles.archived", language: language)).font(.caption).foregroundStyle(.secondary) }
                                    Spacer(); if state.preferences.selectedAthleteID == athlete.id { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.openJumpGreen).accessibilityLabel(AppText.string("profiles.selected", language: language)) }
                                }.frame(minHeight: 52).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                                .swipeActions { Button(AppText.string("profiles.edit", language: language)) { editor = athlete }.tint(.openJumpGreen) }
                        }
                    }.listStyle(.insetGrouped)
                }
            }
            .navigationTitle(AppText.string("tab.profiles", language: language))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button(showArchived ? AppText.string("profiles.active", language: language) : AppText.string("profiles.archived", language: language)) { showArchived.toggle() }.frame(minHeight: 44) }
                ToolbarItem(placement: .topBarTrailing) { Button { creating = true } label: { Label(AppText.string("profiles.add", language: language), systemImage: "plus") }.frame(minHeight: 44) }
            }
            .safeAreaInset(edge: .bottom) { if let error { Text(verbatim: error).font(.footnote).foregroundStyle(.red).padding() } }
            .sheet(isPresented: $creating) { AthleteEditor(state: state, athlete: nil) { Task { await reload() } } }
            .sheet(item: $editor) { person in AthleteEditor(state: state, athlete: person) { Task { await reload() } } }
            .sheet(item: $selected) { person in AthleteDetail(state: state, athlete: person, edit: { selected = nil; editor = person }, changed: { Task { await reload() } }) }
        }
    }
    private func initials(_ name: String) -> String { name.split(whereSeparator: \.isWhitespace).prefix(2).compactMap(\.first).map(String.init).joined().uppercased() }
    private func reload() async { do { try await state.refresh() } catch { self.error = displayError(error, language: language) } }
}

private struct AthleteAvatarView: View {
    let name: String
    let key: String?
    let size: CGFloat
    var body: some View {
        Group {
            if let option = AthleteAvatarCatalog.resolve(key) {
                Image(option.key).resizable().scaledToFill()
            } else {
                Text(String(name.split(whereSeparator: \.isWhitespace).prefix(2).compactMap(\.first).map(String.init).joined().uppercased().prefix(2)))
                    .font(.system(size: size * 0.34, weight: .bold)).foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary)
            }
        }.frame(width: size, height: size).clipShape(Circle())
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
    private func label(_ key: String) -> String { AppText.string("avatars.group.\(key)", language: language) }
    var body: some View {
        NavigationStack {
            ScrollView { LazyVGrid(columns: [GridItem(.adaptive(minimum: 72))]) {
                Button { pending = nil } label: { VStack { AthleteAvatarView(name: name, key: nil, size: 56); Text(AppText.string("avatars.initials", language: language)) }.frame(minWidth: 72, minHeight: 72).overlay(RoundedRectangle(cornerRadius: 8).stroke(pending == nil ? Color.accentColor : .clear, lineWidth: 3)) }
                    .accessibilityLabel(AppText.string("avatars.initials", language: language)).accessibilityAddTraits(pending == nil ? .isSelected : [])
                ForEach(AthleteAvatarCatalog.groups) { group in
                    Section { ForEach(group.options) { option in
                        Button { pending = option.key } label: { AthleteAvatarView(name: name, key: option.key, size: 64).padding(4).overlay(Circle().stroke(pending == option.key ? Color.accentColor : .clear, lineWidth: 3)) }
                            .accessibilityLabel(AppText.string("avatars.option", language: language).replacingOccurrences(of: "%ld", with: "\(AthleteAvatarCatalog.options.firstIndex(of: option)! + 1)"))
                            .accessibilityAddTraits(pending == option.key ? .isSelected : [])
                    } } header: { Text(label(group.id)).font(.headline) }
                }
            }.padding() }
                .navigationTitle(AppText.string("avatars.title", language: language))
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(AppText.string("common.cancel", language: language)) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(AppText.string("common.confirm", language: language)) { apply(pending); dismiss() } } }
                .onAppear { if !initialized { pending = AthleteAvatarCatalog.resolve(selectedKey)?.key ?? selectedKey; initialized = true } }
        }
    }
}

private struct AthleteEditor: View {
    @ObservedObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    let athlete: Athlete?
    let saved: () -> Void
    @State private var name: String
    @State private var weight: String
    @State private var height: String
    @State private var notes: String
    @State private var initialWeightText: String
    @State private var initialHeightText: String
    @State private var initialLocale: Locale
    @State private var initialMassUnit: MassUnit
    @State private var initialLengthUnit: ShortLengthUnit
    @State private var error: String?
    @State private var avatarKey: String?
    @State private var showAvatarPicker = false
    @State private var busy = false
    private var language: AppLanguage { state.preferences.language }
    private var locale: Locale { state.preferences.effectiveLocale }
    init(state: AppState, athlete: Athlete?, saved: @escaping () -> Void) {
        self.state = state; self.athlete = athlete; self.saved = saved
        _name = State(initialValue: athlete?.name ?? "")
        _weight = State(initialValue: athlete?.weightKg.map { MeasurementPresentation.mass($0, as: state.preferences.units.mass).formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale)) } ?? "")
        _height = State(initialValue: athlete?.heightCm.map { MeasurementPresentation.shortLength($0, as: state.preferences.units.shortLength).formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale)) } ?? "")
        _notes = State(initialValue: athlete?.notes ?? "")
        _avatarKey = State(initialValue: athlete == nil ? "avatar_frog_jump" : athlete?.avatarKey)
        _initialWeightText = State(initialValue: _weight.wrappedValue)
        _initialHeightText = State(initialValue: _height.wrappedValue)
        _initialLocale = State(initialValue: state.preferences.effectiveLocale)
        _initialMassUnit = State(initialValue: state.preferences.units.mass)
        _initialLengthUnit = State(initialValue: state.preferences.units.shortLength)
    }
    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.string("profiles.details", language: language)) {
                    HStack { AthleteAvatarView(name: name, key: avatarKey, size: 56).accessibilityHidden(true); Button(AppText.string("profiles.avatar", language: language)) { showAvatarPicker = true }.frame(minHeight: 48) }
                    TextField(AppText.string("profiles.name", language: language), text: $name).textContentType(.name)
                    TextField(AppText.string("profiles.weight", language: language) + " (\(initialMassUnit.rawValue))", text: $weight).keyboardType(.decimalPad)
                    TextField(AppText.string("profiles.height", language: language) + " (\(initialLengthUnit.rawValue))", text: $height).keyboardType(.decimalPad)
                    TextField(AppText.string("profiles.notes", language: language), text: $notes, axis: .vertical).lineLimit(2...5)
                }
                if let error { Text(verbatim: error).foregroundStyle(.red) }
            }
            .navigationTitle(AppText.string(athlete == nil ? "profiles.add" : "profiles.edit", language: language))
            .sheet(isPresented: $showAvatarPicker) { AvatarPicker(language: language, name: name, selectedKey: avatarKey) { avatarKey = $0 } }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(AppText.string("common.cancel", language: language)) { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) { Button(AppText.string("common.save", language: language)) { Task { await save() } }.disabled(busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }
        }
    }
    private func parse(_ value: String, original: Double?, initialText: String, unit: String) -> Double? {
        if value.isEmpty { return nil }
        if let original, value == initialText { return original }
        guard let value = MeasurementPresentation.parsePositive(value, locale: initialLocale) else { return nil }
        switch unit {
        case "kg": return value
        case "lb": return MeasurementPresentation.kilograms(value, from: .pounds)
        case "cm": return value
        default: return MeasurementPresentation.centimeters(value, from: .inches)
        }
    }
    private func save() async {
        guard !busy, let store = state.store else { return }
        let w = parse(weight, original: athlete?.weightKg, initialText: initialWeightText, unit: initialMassUnit.rawValue)
        let h = parse(height, original: athlete?.heightCm, initialText: initialHeightText, unit: initialLengthUnit.rawValue)
        if (!weight.isEmpty && w == nil) || (!height.isEmpty && h == nil) { error = AppText.string("error.invalidNumber", language: language); return }
        busy = true; defer { busy = false }
        do {
            if var athlete { athlete.name = name; athlete.weightKg = w; athlete.heightCm = h; athlete.notes = notes; athlete.avatarKey = avatarKey; _ = try await store.updateAthlete(athlete) }
            else { _ = try await store.createAthlete(name: name, weightKg: w, heightCm: h, notes: notes, avatarKey: avatarKey) }
            saved(); dismiss()
        } catch { self.error = displayError(error, language: language) }
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
    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.string("profiles.details", language: language)) {
                    HStack { AthleteAvatarView(name: athlete.name, key: athlete.avatarKey, size: 72).accessibilityHidden(true); LabeledContent(AppText.string("profiles.name", language: language), value: athlete.name) }
                    if let kg = athlete.weightKg { LabeledContent(AppText.string("profiles.weight", language: language), value: "\(MeasurementPresentation.mass(kg, as: state.preferences.units.mass).formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale))) \(state.preferences.units.mass.rawValue)") }
                    if let cm = athlete.heightCm { LabeledContent(AppText.string("profiles.height", language: language), value: "\(MeasurementPresentation.shortLength(cm, as: state.preferences.units.shortLength).formatted(.number.precision(.fractionLength(0...2)).locale(state.preferences.effectiveLocale))) \(state.preferences.units.shortLength.rawValue)") }
                    LabeledContent(AppText.string("profiles.historyCount", language: language), value: "\(count)")
                    Button(AppText.string("profiles.history", language: language)) { showHistory = true }.frame(minHeight: 48)
                    if let notes = athlete.notes { Text(notes) }
                    if athlete.archivedAt == nil {
                        Button(AppText.string("profiles.select", language: language)) { state.preferences.selectedAthleteID = athlete.id; dismiss() }
                            .disabled(state.preferences.selectedAthleteID == athlete.id).frame(minHeight: 48)
                    }
                }
                if let error { Text(verbatim: error).foregroundStyle(.red) }
                Section {
                    Button(AppText.string("profiles.edit", language: language), action: edit).disabled(busy).frame(minHeight: 48)
                    Button(AppText.string(athlete.archivedAt == nil ? "profiles.archive" : "profiles.restore", language: language)) { confirm = true }.disabled(busy).frame(minHeight: 48)
                }
            }.navigationTitle(athlete.name).toolbar { ToolbarItem(placement: .cancellationAction) { Button(AppText.string("common.close", language: language)) { dismiss() } } }
                .confirmationDialog(AppText.string("profiles.archiveConfirm", language: language), isPresented: $confirm, titleVisibility: .visible) {
                    Button(AppText.string(athlete.archivedAt == nil ? "profiles.archive" : "profiles.restore", language: language), role: athlete.archivedAt == nil ? .destructive : nil) { Task { await toggleArchive() } }
                    Button(AppText.string("common.cancel", language: language), role: .cancel) {}
                }
                .task { await loadCount() }
                .sheet(isPresented: $showHistory) { HistoryView(state: state, ownerID: athlete.id) }
        }
    }
    private func loadCount() async {
        guard let store = state.store else { return }
        do {
            count = try await store.measurementCount(ownerID: athlete.id)
        } catch { self.error = displayError(error, language: language) }
    }
    private func toggleArchive() async {
        guard !busy, let store = state.store else { return }
        busy = true; defer { busy = false }
        do { try await store.setArchived(athlete.id, archived: athlete.archivedAt == nil); if athlete.archivedAt == nil && state.preferences.selectedAthleteID == athlete.id { state.preferences.selectedAthleteID = nil }; changed(); dismiss() }
        catch { self.error = displayError(error, language: language) }
    }
}
