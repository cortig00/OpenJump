import Foundation
import SwiftUI

struct AppText {
    static let supported = ["es", "en", "fr", "de", "pt-BR", "pt-PT", "it", "tr"]
    static func string(_ key: String, language: AppLanguage) -> String {
        let preferred: String
        if let identifier = language.localeIdentifier { preferred = identifier }
        else {
            preferred = Bundle.preferredLocalizations(from: supported, forPreferences: Locale.preferredLanguages).first
                ?? Locale.current.language.languageCode?.identifier ?? "en"
        }
        let exact = bundle(preferred)
        let base = bundle(String(preferred.split(separator: "-").first ?? "en"))
        let english = bundle("en")
        for candidate in [exact, base, english].compactMap({ $0 }) {
            let value = candidate.localizedString(forKey: key, value: nil, table: nil)
            if value != key { return value }
        }
        return english?.localizedString(forKey: "metric.generic", value: "Recorded metric", table: nil) ?? "Recorded metric"
    }
    private static func bundle(_ identifier: String) -> Bundle? {
        guard let path = Bundle.main.path(forResource: identifier, ofType: "lproj") else { return nil }
        return Bundle(path: path)
    }
}

extension View {
    func appLocale(_ language: AppLanguage) -> some View {
        environment(\.locale, language.localeIdentifier.map(Locale.init(identifier:)) ?? .current)
    }
}

extension SavedProtocol {
    var titleKey: String { "protocol.\(rawValue)" }
}

func displayError(_ error: Error, language: AppLanguage) -> String {
    if let store = error as? StoreError {
        switch store {
        case .lastActiveAthlete: return AppText.string("error.lastAthlete", language: language)
        case .inactiveOwner: return AppText.string("error.archivedAthlete", language: language)
        case .notFound: return AppText.string("error.notFound", language: language)
        default: return AppText.string("error.save", language: language)
        }
    }
    if let model = error as? AppModelError, model == .invalidAnthropometrics {
        return AppText.string("error.invalidNumber", language: language)
    }
    return AppText.string("error.save", language: language)
}

func metricName(_ key: String, language: AppLanguage) -> String {
    let keys = ["HEIGHT_CM", "JUMP_HEIGHT", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "CONTACT_TIME_MS", "RSI_MOD", "RSI", "DISTANCE_M", "DISTANCE_CM", "ASYMMETRY_PERCENT", "BEST_FIVE_RSI_MEAN", "ESTIMATED_PEAK_POWER_SAYERS_W", "ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG", "ESTIMATED_POTENTIAL_ENERGY_J", "ESTIMATED_TAKEOFF_KINETIC_ENERGY_J", "ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS", "RELATIVE_JUMP_HEIGHT_PERCENT", "RELATIVE_HORIZONTAL_DISTANCE_PERCENT", "SAYERS_POWER_W", "POWER"]
    let normalized = key.uppercased()
    return AppText.string(keys.contains(normalized) ? "metric.\(normalized)" : "metric.generic", language: language)
}

/// Typed value/unit parts for one saved metric. Pure extraction of the
/// existing `formattedMetric` pipeline: same key uppercasing, same expected
/// unit set/mismatch rule, same `MeasurementPresentation` conversions and
/// same 0...3 locale precision. `combined` is the exact legacy text.
struct FormattedMetricParts: Equatable, Sendable {
    let valueString: String
    let unitString: String
    var combined: String { "\(valueString) \(unitString)" }
}

func formattedMetricParts(_ metric: SavedMetric, units: UnitProfile, locale: Locale) -> FormattedMetricParts {
    let key = metric.key.uppercased()
    var value = metric.value
    var unit = metric.unit
    let expected: [String: Set<String>] = [
        "HEIGHT_CM": ["CENTIMETER", "cm"], "JUMP_HEIGHT": ["CENTIMETER", "cm"],
        "DISTANCE_CM": ["CENTIMETER", "cm"], "DISTANCE_M": ["METER", "m"],
        "TAKEOFF_VELOCITY_MPS": ["METER_PER_SECOND", "m/s"],
        "RSI_MOD": ["METER_PER_SECOND", "m/s"], "RSI": ["METER_PER_SECOND", "m/s"],
        "BEST_FIVE_RSI_MEAN": ["METER_PER_SECOND", "m/s"],
        "FLIGHT_TIME_MS": ["MILLISECOND", "ms"], "TIME_TO_TAKEOFF_MS": ["MILLISECOND", "ms"],
        "CONTACT_TIME_MS": ["MILLISECOND", "ms"], "ASYMMETRY_PERCENT": ["PERCENT", "%"],
        "RELATIVE_JUMP_HEIGHT_PERCENT": ["PERCENT", "%"], "RELATIVE_HORIZONTAL_DISTANCE_PERCENT": ["PERCENT", "%"]
    ]
    // Unknown or mismatched source units are displayed unchanged, never reinterpreted.
    if let accepted = expected[key], !accepted.contains(metric.unit) {
        return FormattedMetricParts(
            valueString: value.formatted(.number.precision(.fractionLength(0...3)).locale(locale)),
            unitString: unit
        )
    }
    switch key {
    case "HEIGHT_CM", "JUMP_HEIGHT":
        value = MeasurementPresentation.shortLength(value, as: units.shortLength); unit = units.shortLength.rawValue
    case "DISTANCE_CM":
        value = MeasurementPresentation.horizontalDistance(value / 100, as: units.horizontalDistance); unit = units.horizontalDistance.rawValue
    case "DISTANCE_M":
        value = MeasurementPresentation.horizontalDistance(value, as: units.horizontalDistance); unit = units.horizontalDistance.rawValue
    case "TAKEOFF_VELOCITY_MPS", "RSI_MOD", "RSI", "BEST_FIVE_RSI_MEAN":
        value = MeasurementPresentation.speed(value, as: units.speed); unit = units.speed == .metersPerSecond ? "m/s" : "ft/s"
    case "FLIGHT_TIME_MS", "TIME_TO_TAKEOFF_MS", "CONTACT_TIME_MS":
        value = MeasurementPresentation.timing(value, as: units.timing); unit = units.timing.rawValue
    case "ASYMMETRY_PERCENT", "RELATIVE_JUMP_HEIGHT_PERCENT", "RELATIVE_HORIZONTAL_DISTANCE_PERCENT": unit = "%"
    default: break
    }
    return FormattedMetricParts(
        valueString: value.formatted(.number.precision(.fractionLength(0...3)).locale(locale)),
        unitString: unit
    )
}

func formattedMetric(_ metric: SavedMetric, units: UnitProfile, locale: Locale) -> String {
    formattedMetricParts(metric, units: units, locale: locale).combined
}
