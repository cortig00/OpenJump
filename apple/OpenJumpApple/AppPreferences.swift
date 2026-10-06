import Foundation
import Combine

public enum ThemeMode: String, CaseIterable, Sendable { case system, light, dark }
public enum AppLanguage: String, CaseIterable, Sendable {
    case system, es, en, fr, de, ptBR = "pt-BR", ptPT = "pt-PT", it, tr
    public var localeIdentifier: String? { self == .system ? nil : rawValue }
}
public enum ShortLengthUnit: String, CaseIterable, Sendable { case cm, inches = "in" }
public enum HorizontalDistanceUnit: String, CaseIterable, Sendable { case meters = "m", feet = "ft" }
public enum MassUnit: String, CaseIterable, Sendable { case kilograms = "kg", pounds = "lb" }
public enum SpeedUnit: String, CaseIterable, Sendable { case metersPerSecond = "mps", feetPerSecond = "ftps" }
public enum TimingUnit: String, CaseIterable, Sendable { case milliseconds = "ms", seconds = "s" }

public struct UnitProfile: Equatable, Sendable {
    public var shortLength: ShortLengthUnit = .cm
    public var horizontalDistance: HorizontalDistanceUnit = .meters
    public var mass: MassUnit = .kilograms
    public var speed: SpeedUnit = .metersPerSecond
    public var timing: TimingUnit = .milliseconds
    public init(shortLength: ShortLengthUnit = .cm, horizontalDistance: HorizontalDistanceUnit = .meters,
                mass: MassUnit = .kilograms, speed: SpeedUnit = .metersPerSecond, timing: TimingUnit = .milliseconds) {
        self.shortLength = shortLength; self.horizontalDistance = horizontalDistance
        self.mass = mass; self.speed = speed; self.timing = timing
    }
    public static let metric = UnitProfile()
    public static let unitedStates = UnitProfile(shortLength: .inches, horizontalDistance: .feet,
                                                  mass: .pounds, speed: .feetPerSecond)
    public var preset: UnitPreset { self == .metric ? .metric : (self == .unitedStates ? .unitedStates : .custom) }
}
public enum UnitPreset: Sendable { case metric, unitedStates, custom }

public enum MeasurementPresentation {
    public static func shortLength(_ centimeters: Double, as unit: ShortLengthUnit) -> Double {
        unit == .cm ? centimeters : centimeters / 2.54
    }
    public static func centimeters(_ value: Double, from unit: ShortLengthUnit) -> Double {
        unit == .cm ? value : value * 2.54
    }
    public static func horizontalDistance(_ meters: Double, as unit: HorizontalDistanceUnit) -> Double {
        unit == .meters ? meters : meters / 0.3048
    }
    public static func meters(_ value: Double, from unit: HorizontalDistanceUnit) -> Double {
        unit == .meters ? value : value * 0.3048
    }
    public static func mass(_ kilograms: Double, as unit: MassUnit) -> Double {
        unit == .kilograms ? kilograms : kilograms / 0.45359237
    }
    public static func kilograms(_ value: Double, from unit: MassUnit) -> Double {
        unit == .kilograms ? value : value * 0.45359237
    }
    public static func speed(_ metersPerSecond: Double, as unit: SpeedUnit) -> Double {
        unit == .metersPerSecond ? metersPerSecond : metersPerSecond / 0.3048
    }
    public static func metersPerSecond(_ value: Double, from unit: SpeedUnit) -> Double {
        unit == .metersPerSecond ? value : value * 0.3048
    }
    public static func timing(_ milliseconds: Double, as unit: TimingUnit) -> Double {
        unit == .milliseconds ? milliseconds : milliseconds / 1000
    }
    public static func milliseconds(_ value: Double, from unit: TimingUnit) -> Double {
        unit == .milliseconds ? value : value * 1000
    }

    public static func parsePositive(_ text: String, locale: Locale) -> Double? {
        let input = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else { return nil }
        let separator = locale.decimalSeparator ?? "."
        var body = input
        if body.first == "+" { body.removeFirst() }
        guard !body.isEmpty else { return nil }
        let pieces = body.components(separatedBy: separator)
        guard pieces.count <= 2,
              pieces.allSatisfy({ $0.allSatisfy { $0 >= "0" && $0 <= "9" } }),
              !pieces[0].isEmpty,
              pieces.count == 1 || !pieces[1].isEmpty else { return nil }
        let normalized = pieces.joined(separator: ".")
        guard let decimal = Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        let decimalValue = NSDecimalNumber(decimal: decimal).doubleValue
        guard decimalValue.isFinite, decimalValue > 0 else { return nil }
        guard let result = Double(normalized), result.isFinite, result > 0 else { return nil }
        return result
    }
}

@MainActor public final class AppPreferences: ObservableObject {
    @Published public var theme: ThemeMode { didSet { defaults.set(theme.rawValue, forKey: key("theme")) } }
    @Published public var language: AppLanguage { didSet { defaults.set(language.rawValue, forKey: key("language")) } }
    @Published public var selectedAthleteID: UUID? { didSet { defaults.set(selectedAthleteID?.uuidString, forKey: key("athlete")) } }
    @Published public var units: UnitProfile { didSet { persistUnits() } }
    private let defaults: UserDefaults
    private let namespace: String
    private func key(_ name: String) -> String { "openjump.apple.\(namespace).\(name)" }

    public init(defaults: UserDefaults = .standard, namespace: String = "preferences") {
        self.defaults = defaults; self.namespace = namespace
        self.theme = ThemeMode(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).theme") ?? "") ?? .system
        self.language = AppLanguage(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).language") ?? "") ?? .system
        self.selectedAthleteID = defaults.string(forKey: "openjump.apple.\(namespace).athlete").flatMap(UUID.init(uuidString:))
        self.units = UnitProfile(
            shortLength: ShortLengthUnit(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).shortLength") ?? "") ?? .cm,
            horizontalDistance: HorizontalDistanceUnit(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).horizontalDistance") ?? "") ?? .meters,
            mass: MassUnit(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).mass") ?? "") ?? .kilograms,
            speed: SpeedUnit(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).speed") ?? "") ?? .metersPerSecond,
            timing: TimingUnit(rawValue: defaults.string(forKey: "openjump.apple.\(namespace).timing") ?? "") ?? .milliseconds)
    }
    public var effectiveLocale: Locale { language.localeIdentifier.map(Locale.init(identifier:)) ?? .current }
    public func resolveActiveAthlete(in roster: [Athlete]) -> UUID? {
        if let selectedAthleteID {
            guard roster.contains(where: { $0.id == selectedAthleteID && $0.archivedAt == nil }) else {
                self.selectedAthleteID = nil
                return nil
            }
            return selectedAthleteID
        }
        let active = roster.filter { $0.archivedAt == nil }
        guard active.count == 1 else { return nil }
        selectedAthleteID = active[0].id
        return active[0].id
    }
    public func selectPreset(_ preset: UnitPreset) {
        if preset == .metric { units = .metric }
        else if preset == .unitedStates { units = .unitedStates }
    }
    private func persistUnits() {
        defaults.set(units.shortLength.rawValue, forKey: key("shortLength"))
        defaults.set(units.horizontalDistance.rawValue, forKey: key("horizontalDistance"))
        defaults.set(units.mass.rawValue, forKey: key("mass")); defaults.set(units.speed.rawValue, forKey: key("speed"))
        defaults.set(units.timing.rawValue, forKey: key("timing"))
    }
}
