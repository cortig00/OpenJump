import Foundation

/// Date presets for the native history list (S3).
///
/// Pure Foundation mapping from a user-visible preset to the half-open
/// `[from, before)` interval consumed by `SQLiteStore.history(recordedFrom:recordedBefore:)`.
/// No queries, no persistence, no UI: the view captures both bounds before
/// awaiting the store actor, so a preset change can never mix bounds across
/// generations. Day arithmetic always goes through the injected `Calendar`
/// (never `±86400`), so 23h/25h DST days resolve to exact local midnights.
enum HistoryPeriodPreset: String, CaseIterable, Sendable, Hashable {
    case allTime
    case last7Days
    case last30Days
    case custom
}

/// Half-open recorded-at interval for one history load.
///
/// `nil` on either side leaves that side unbounded; `.allTime` leaves both
/// unbounded so the default history query is byte-for-byte the pre-S3 query.
/// Intervals are `[from, before)`: a custom from-day THROUGH an inclusive
/// end-day maps to `startOfDay(from)` through `startOfDay(through) + 1 day`
/// exclusive, and presets end at the start of tomorrow so today is included.
struct HistoryDateBounds: Equatable, Sendable {
    let from: Date?
    let before: Date?

    static let allTime = HistoryDateBounds(from: nil, before: nil)
}

/// Typed failure for custom history ranges.
///
/// The view maps these to localized strings and issues NO store query,
/// rather than silently falling back to unfiltered history.
enum HistoryFilterError: Error, Equatable, Sendable {
    case missingCustomDate
    case invertedRange

    /// Stable localization key for the view-level error text.
    var localizationKey: String {
        switch self {
        case .missingCustomDate, .invertedRange:
            return "history.invalidRange"
        }
    }
}

enum HistoryFilters {
    /// Resolves the store bounds for `preset`.
    ///
    /// - `customFrom`/`customThrough` are only read when `preset == .custom`;
    ///   otherwise they are ignored, so untouched custom pickers never filter.
    /// - Throws `missingCustomDate` when a custom day is absent and
    ///   `invertedRange` when the from-day is after the through-day
    ///   (day granularity; a single-day range is valid).
    /// - `calendar` is injected for deterministic DST tests; pass
    ///   `Calendar.current` from the view for current-timezone semantics.
    static func bounds(for preset: HistoryPeriodPreset,
                       customFrom: Date? = nil,
                       customThrough: Date? = nil,
                       calendar: Calendar = .current,
                       now: Date = Date()) throws -> HistoryDateBounds {
        switch preset {
        case .allTime:
            return .allTime
        case .last7Days:
            return try last(days: 7, calendar: calendar, now: now)
        case .last30Days:
            return try last(days: 30, calendar: calendar, now: now)
        case .custom:
            guard let customFrom, let customThrough else { throw HistoryFilterError.missingCustomDate }
            let startOfFrom = calendar.startOfDay(for: customFrom)
            let startOfThrough = calendar.startOfDay(for: customThrough)
            guard startOfFrom <= startOfThrough else { throw HistoryFilterError.invertedRange }
            guard let exclusiveEnd = calendar.date(byAdding: .day, value: 1, to: startOfThrough) else {
                throw HistoryFilterError.invertedRange
            }
            return HistoryDateBounds(from: startOfFrom, before: exclusiveEnd)
        }
    }

    /// Last `days` calendar days INCLUDING today: `startOfToday - (days-1)`
    /// through `startOfTomorrow` exclusive.
    private static func last(days: Int, calendar: Calendar, now: Date) throws -> HistoryDateBounds {
        let startOfToday = calendar.startOfDay(for: now)
        guard let from = calendar.date(byAdding: .day, value: -(days - 1), to: startOfToday),
              let before = calendar.date(byAdding: .day, value: 1, to: startOfToday),
              from < before else {
            throw HistoryFilterError.invertedRange
        }
        return HistoryDateBounds(from: from, before: before)
    }

    /// View-only recoverability predicate: whether the empty history list is
    /// a filtered-zero (with a Clear-filters recovery) rather than no data.
    ///
    /// Pure, no store/query change. `query` is the RAW view query: whitespace
    /// counts as removable because the store maps `query.isEmpty ? nil : query`
    /// byte-for-byte, so `" "` still issues a search. Never trim or normalize
    /// here. A fixed profile owner never counts as removable; any selected
    /// owner is ignored while `fixedOwnerID` is present.
    static func hasRemovableFilters(protocolKey: SavedProtocol?,
                                    selectedOwnerID: UUID?,
                                    fixedOwnerID: UUID?,
                                    period: HistoryPeriodPreset,
                                    query: String) -> Bool {
        if protocolKey != nil { return true }
        if fixedOwnerID == nil && selectedOwnerID != nil { return true }
        if period != .allTime { return true }
        if !query.isEmpty { return true }
        return false
    }
}
