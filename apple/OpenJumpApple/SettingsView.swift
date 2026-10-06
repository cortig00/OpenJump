import SwiftUI

struct SettingsView: View {
    @ObservedObject var state: AppState
    private var language: AppLanguage { state.preferences.language }
    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.string("settings.appearance", language: language)) {
                    Picker(AppText.string("settings.theme", language: language), selection: $state.preferences.theme) {
                        Text(AppText.string("settings.system", language: language)).tag(ThemeMode.system)
                        Text(AppText.string("settings.light", language: language)).tag(ThemeMode.light)
                        Text(AppText.string("settings.dark", language: language)).tag(ThemeMode.dark)
                    }
                }
                Section(AppText.string("settings.units", language: language)) {
                    Picker(AppText.string("settings.preset", language: language), selection: Binding(get: {
                        switch state.preferences.units.preset { case .metric: return "metric"; case .unitedStates: return "us"; case .custom: return "custom" }
                    }, set: { value in if value == "metric" { state.preferences.selectPreset(.metric) } else if value == "us" { state.preferences.selectPreset(.unitedStates) } })) {
                        Text(AppText.string("settings.metric", language: language)).tag("metric")
                        Text(AppText.string("settings.us", language: language)).tag("us")
                        if state.preferences.units.preset == .custom { Text(AppText.string("settings.custom", language: language)).tag("custom") }
                    }
                    Picker(AppText.string("settings.short", language: language), selection: Binding(get: { state.preferences.units.shortLength }, set: { state.preferences.units.shortLength = $0 })) { ForEach(ShortLengthUnit.allCases, id: \.self) { Text($0.rawValue).tag($0) } }
                    Picker(AppText.string("settings.horizontal", language: language), selection: Binding(get: { state.preferences.units.horizontalDistance }, set: { state.preferences.units.horizontalDistance = $0 })) { ForEach(HorizontalDistanceUnit.allCases, id: \.self) { Text($0.rawValue).tag($0) } }
                    Picker(AppText.string("settings.mass", language: language), selection: Binding(get: { state.preferences.units.mass }, set: { state.preferences.units.mass = $0 })) { ForEach(MassUnit.allCases, id: \.self) { Text($0.rawValue).tag($0) } }
                    Picker(AppText.string("settings.speed", language: language), selection: Binding(get: { state.preferences.units.speed }, set: { state.preferences.units.speed = $0 })) { Text("m/s").tag(SpeedUnit.metersPerSecond); Text("ft/s").tag(SpeedUnit.feetPerSecond) }
                    Picker(AppText.string("settings.timing", language: language), selection: Binding(get: { state.preferences.units.timing }, set: { state.preferences.units.timing = $0 })) { ForEach(TimingUnit.allCases, id: \.self) { Text($0.rawValue).tag($0) } }
                }
                Section(AppText.string("settings.language", language: language)) {
                    Picker(AppText.string("settings.language", language: language), selection: $state.preferences.language) {
                        Text(AppText.string("settings.system", language: language)).tag(AppLanguage.system)
                        Text("Español").tag(AppLanguage.es)
                        Text("English").tag(AppLanguage.en)
                        Text("Français").tag(AppLanguage.fr)
                        Text("Deutsch").tag(AppLanguage.de)
                        Text("Português (Brasil)").tag(AppLanguage.ptBR)
                        Text("Português (Portugal)").tag(AppLanguage.ptPT)
                        Text("Italiano").tag(AppLanguage.it)
                        Text("Türkçe").tag(AppLanguage.tr)
                    }
                }
                Section { Text(AppText.string("settings.capturePending", language: language)).font(.footnote).foregroundStyle(.secondary) }
            }.navigationTitle(AppText.string("tab.settings", language: language))
        }
    }
}
