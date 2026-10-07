import SwiftUI
import Combine

@MainActor final class AppState: ObservableObject {
    @Published var preferences: AppPreferences
    @Published var store: SQLiteStore?
    @Published var athletes: [Athlete] = []
    @Published private(set) var historyRevision = 0
    @Published var loadError: String?
    @Published var loading = true
    private var preferencesObservation: AnyCancellable?
    private let databaseURL: URL?
    init(preferences: AppPreferences? = nil, databaseURL: URL? = nil) {
        // Construct production defaults only when no preferences were injected.
        // The observation must subscribe to the injected object, not a temporary
        // object using UserDefaults.standard.
        self.preferences = preferences ?? AppPreferences()
        self.databaseURL = databaseURL
        preferencesObservation = self.preferences.objectWillChange.sink { [weak self] _ in
            Task { @MainActor in self?.objectWillChange.send() }
        }
    }
    func load() async {
        loading = true; loadError = nil
        do {
            let capturedURL = databaseURL
            let database = try await Task.detached { try SQLiteStore(databaseURL: capturedURL) }.value
            let roster = try await database.athletes(includeArchived: true)
            store = database
            athletes = roster
            _ = preferences.resolveActiveAthlete(in: athletes)
            loading = false
        } catch {
            store = nil
            athletes = []
            loadError = AppText.string("error.database", language: preferences.language)
            loading = false
        }
    }
    func refresh() async throws {
        guard let store else { return }
        athletes = try await store.athletes(includeArchived: true)
        _ = preferences.resolveActiveAthlete(in: athletes)
    }
    func historyDidCommit() { historyRevision += 1 }
}

@MainActor struct AppShell: View {
    private enum Tab: Hashable { case jumps, history, profiles, settings }
    @StateObject private var state: AppState
    @State private var selectedTab: Tab = .jumps
    @Environment(\.colorScheme) private var scheme
    init(preferences: AppPreferences? = nil, databaseURL: URL? = nil) {
        _state = StateObject(wrappedValue: AppState(preferences: preferences, databaseURL: databaseURL))
    }
    var body: some View {
        Group {
            if state.loading {
                ProgressView(AppText.string("app.loading", language: state.preferences.language))
            } else if let error = state.loadError {
                OpenJumpEmptyState(title: AppText.string("app.problem", language: state.preferences.language), systemImage: "externaldrive.badge.exclamationmark", description: Text(verbatim: error))
                    .overlay(alignment: .bottom) { Button(AppText.string("common.retry", language: state.preferences.language)) { Task { await state.load() } }.padding(24) }
            } else {
                TabView(selection: $selectedTab) {
                    JumpHomeView(state: state,
                                 openProfiles: { selectedTab = .profiles },
                                 openHistory: { selectedTab = .history })
                        .tabItem { Label(AppText.string("tab.jumps", language: state.preferences.language), systemImage: "figure.run") }
                        .tag(Tab.jumps)
                    HistoryView(state: state)
                        .tabItem { Label(AppText.string("tab.history", language: state.preferences.language), systemImage: "clock") }
                        .tag(Tab.history)
                    ProfilesView(state: state)
                        .tabItem { Label(AppText.string("tab.profiles", language: state.preferences.language), systemImage: "person.2") }
                        .tag(Tab.profiles)
                    SettingsView(state: state)
                        .tabItem { Label(AppText.string("tab.settings", language: state.preferences.language), systemImage: "gearshape") }
                        .tag(Tab.settings)
                }
            }
        }
        .preferredColorScheme(state.preferences.theme == .system ? nil : (state.preferences.theme == .dark ? .dark : .light))
        .appLocale(state.preferences.language)
        .task { await state.load() }
    }
}

/// iOS 16-compatible centered empty/error state replacing the iOS 17-only
/// `ContentUnavailableView`. Title, icon, description and actions mirror the
/// previous call sites; all user strings remain `AppText`-localized at the call
/// site. The stack fills and centers within the available area without
/// imposing an unbounded minimum height, so it stays neutral inside
/// `ScrollView`/`Form`. The icon is decorative (titles already convey meaning)
/// and the title is exposed as a heading; text wraps with Dynamic Type.
struct OpenJumpEmptyState<Actions: View>: View {
    let title: String
    let systemImage: String
    let description: Text?
    let actions: Actions
    init(title: String, systemImage: String, description: Text? = nil, @ViewBuilder actions: () -> Actions) {
        self.title = title
        self.systemImage = systemImage
        self.description = description
        self.actions = actions()
    }
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            if let description {
                description
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            actions
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

extension OpenJumpEmptyState where Actions == EmptyView {
    init(title: String, systemImage: String, description: Text? = nil) {
        self.init(title: title, systemImage: systemImage, description: description) { EmptyView() }
    }
}
