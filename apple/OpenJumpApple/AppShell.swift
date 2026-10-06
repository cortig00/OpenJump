import SwiftUI
import Combine

@MainActor final class AppState: ObservableObject {
    @Published var preferences = AppPreferences()
    @Published var store: SQLiteStore?
    @Published var athletes: [Athlete] = []
    @Published private(set) var historyRevision = 0
    @Published var loadError: String?
    @Published var loading = true
    private var preferencesObservation: AnyCancellable?
    init() {
        preferencesObservation = preferences.objectWillChange.sink { [weak self] _ in
            Task { @MainActor in self?.objectWillChange.send() }
        }
    }
    func load() async {
        loading = true; loadError = nil
        do {
            let database = try await Task.detached { try SQLiteStore() }.value
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

struct AppShell: View {
    private enum Tab: Hashable { case jumps, history, profiles, settings }
    @StateObject private var state = AppState()
    @State private var selectedTab: Tab = .jumps
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        Group {
            if state.loading {
                ProgressView(AppText.string("app.loading", language: state.preferences.language))
            } else if let error = state.loadError {
                ContentUnavailableView(AppText.string("app.problem", language: state.preferences.language), systemImage: "externaldrive.badge.exclamationmark", description: Text(verbatim: error))
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

extension Color {
    static let openJumpGreen = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.40, green: 0.82, blue: 0.70, alpha: 1) : UIColor(red: 0.02, green: 0.43, blue: 0.35, alpha: 1) })
}
