import XCTest
@testable import OpenJumpApple

/// S3 history date/name-search coverage: pure `HistoryFilters` bounds plus the
/// additive `SQLiteStore.history(recordedFrom:recordedBefore:)` predicates and
/// owner-name search. Every store test uses an isolated disposable SQLite URL;
/// no production fixture or shared database is touched.
final class HistoryFilterTests: XCTestCase {
    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("openjump-historyfilter-\(UUID().uuidString).sqlite")
    }

    private func save(_ store: SQLiteStore, owner: UUID?, session: String, date: Date,
                      protocolKey: SavedProtocol = .cmj, notes: String? = nil) async throws {
        let measurement = try SavedMeasurement(sessionKey: session, ownerID: owner, protocolKey: protocolKey,
            recordedAt: date, notes: notes,
            metrics: [try SavedMetric(key: "HEIGHT_CM", unit: "CENTIMETER", value: 30, ordinal: 0)])
        try await store.save(measurement)
    }

    private func utcCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? calendar.timeZone
        return calendar
    }

    // MARK: - Pure bounds

    func testAllTimeLeavesBothBoundsUnset() throws {
        let bounds = try HistoryFilters.bounds(for: .allTime, customFrom: Date(), customThrough: Date())
        XCTAssertNil(bounds.from)
        XCTAssertNil(bounds.before)
        XCTAssertEqual(bounds, .allTime)
    }

    func testLast7And30DaysIncludeTodayDeterministically() throws {
        let calendar = utcCalendar()
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2025, month: 3, day: 10, hour: 15, minute: 30)))
        let seven = try HistoryFilters.bounds(for: .last7Days, calendar: calendar, now: now)
        XCTAssertEqual(seven.from, calendar.date(from: DateComponents(year: 2025, month: 3, day: 4)))
        XCTAssertEqual(seven.before, calendar.date(from: DateComponents(year: 2025, month: 3, day: 11)))
        let thirty = try HistoryFilters.bounds(for: .last30Days, calendar: calendar, now: now)
        XCTAssertEqual(thirty.from, calendar.date(from: DateComponents(year: 2025, month: 2, day: 9)))
        XCTAssertEqual(thirty.before, calendar.date(from: DateComponents(year: 2025, month: 3, day: 11)))
    }

    func testCustomRangeSpansWholeDaysInclusive() throws {
        let calendar = utcCalendar()
        let fromDay = try XCTUnwrap(calendar.date(from: DateComponents(year: 2025, month: 4, day: 2, hour: 15, minute: 30)))
        let throughDay = try XCTUnwrap(calendar.date(from: DateComponents(year: 2025, month: 4, day: 4, hour: 1)))
        let bounds = try HistoryFilters.bounds(for: .custom, customFrom: fromDay, customThrough: throughDay, calendar: calendar)
        XCTAssertEqual(bounds.from, calendar.date(from: DateComponents(year: 2025, month: 4, day: 2)))
        XCTAssertEqual(bounds.before, calendar.date(from: DateComponents(year: 2025, month: 4, day: 5)))
        // A single-day range is valid: midnight through next midnight.
        let single = try HistoryFilters.bounds(for: .custom, customFrom: fromDay, customThrough: fromDay, calendar: calendar)
        XCTAssertNotNil(single.from)
        XCTAssertNotNil(single.before)
        let singleFrom = try XCTUnwrap(single.from)
        let singleBefore = try XCTUnwrap(single.before)
        XCTAssertTrue(singleFrom < singleBefore)
    }

    func testCustomSingleDayUsesCalendarNot24HourArithmeticAcrossDST() throws {
        var madrid = Calendar(identifier: .gregorian)
        madrid.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Madrid"))
        // 2025-10-26 is the 25-hour DST fallback day in Europe/Madrid.
        let midday = try XCTUnwrap(madrid.date(from: DateComponents(year: 2025, month: 10, day: 26, hour: 12)))
        let bounds = try HistoryFilters.bounds(for: .custom, customFrom: midday, customThrough: midday, calendar: madrid)
        let from = try XCTUnwrap(bounds.from)
        let before = try XCTUnwrap(bounds.before)
        XCTAssertEqual(before, madrid.date(byAdding: .day, value: 1, to: from))
        XCTAssertEqual(before.timeIntervalSince(from), 25 * 3600, accuracy: 0.001)
        XCTAssertNotEqual(before.timeIntervalSince(from), 24 * 3600)
    }

    func testInvertedAndMissingCustomRangesThrowLocalizedErrors() {
        let calendar = utcCalendar()
        let early = calendar.date(from: DateComponents(year: 2025, month: 5, day: 1))!
        let late = calendar.date(from: DateComponents(year: 2025, month: 5, day: 3))!
        do {
            _ = try HistoryFilters.bounds(for: .custom, customFrom: late, customThrough: early, calendar: calendar)
            XCTFail("a from-day after the through-day must throw")
        } catch {
            XCTAssertEqual(error as? HistoryFilterError, .invertedRange)
            XCTAssertEqual((error as? HistoryFilterError)?.localizationKey, "history.invalidRange")
        }
        do {
            _ = try HistoryFilters.bounds(for: .custom, customFrom: nil, customThrough: late, calendar: calendar)
            XCTFail("a missing custom day must throw")
        } catch {
            XCTAssertEqual(error as? HistoryFilterError, .missingCustomDate)
        }
        // Presets ignore untouched custom pickers, even inverted ones.
        XCTAssertNoThrow(try HistoryFilters.bounds(for: .allTime, customFrom: late, customThrough: early, calendar: calendar))
        XCTAssertNoThrow(try HistoryFilters.bounds(for: .last7Days, customFrom: late, customThrough: early, calendar: calendar))
    }

    // MARK: - Store date predicates

    func testDateBoundsAreHalfOpenAtExactBoundaries() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Bounds")
        let base: TimeInterval = 1_700_000_000
        let from = Date(timeIntervalSince1970: base)
        let before = Date(timeIntervalSince1970: base + 100)
        try await save(store, owner: athlete.id, session: "before-window", date: Date(timeIntervalSince1970: base - 1))
        try await save(store, owner: athlete.id, session: "at-from", date: from)
        try await save(store, owner: athlete.id, session: "inside", date: Date(timeIntervalSince1970: base + 50))
        try await save(store, owner: athlete.id, session: "at-before", date: before)
        try await save(store, owner: athlete.id, session: "after-window", date: Date(timeIntervalSince1970: base + 101))
        let page = try await store.history(recordedFrom: from, recordedBefore: before)
        XCTAssertEqual(page.items.map(\.sessionKey), ["inside", "at-from"])
    }

    func testInvalidDateBoundsAndCursorsFailClosed() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let base = Date(timeIntervalSince1970: 1_700_000_000)
        let infinite = Date(timeIntervalSince1970: .infinity)
        for (from, before) in [(infinite, nil as Date?), (nil, infinite), (base, base),
                               (Date(timeIntervalSince1970: 2), Date(timeIntervalSince1970: 1))] {
            do {
                _ = try await store.history(recordedFrom: from, recordedBefore: before)
                XCTFail("invalid date bounds must fail closed, not return all history")
            } catch { XCTAssertEqual(error as? StoreError, .invalidPageToken) }
        }
        // Pre-S3 oracles remain: bad limits and a nonfinite cursor still fail.
        for limit in [0, 201] {
            do {
                _ = try await store.history(limit: limit)
                XCTFail("out-of-range limit must fail")
            } catch { XCTAssertEqual(error as? StoreError, .invalidPageToken) }
        }
        do {
            _ = try await store.history(before: (infinite, UUID()))
            XCTFail("a nonfinite cursor must fail")
        } catch { XCTAssertEqual(error as? StoreError, .invalidPageToken) }
    }

    // MARK: - Name search and ownership

    func testOwnerNameSearchMatchesAlongsideSessionAndNotes() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let runner = try await store.createAthlete(name: "Ada Runner")
        _ = try await store.createAthlete(name: "Other Athlete")
        try await save(store, owner: runner.id, session: "runner-session", date: Date(timeIntervalSince1970: 1_700_000_100))
        try await save(store, owner: nil, session: "plain-session", date: Date(timeIntervalSince1970: 1_700_000_200), notes: "keep this note")
        // Current roster name matches (ASCII case-folding like session/notes).
        let byName = try await store.history(search: "runner")
        XCTAssertEqual(byName.items.count, 1)
        XCTAssertEqual(byName.items.first?.sessionKey, "runner-session")
        // Existing session/notes semantics are unchanged.
        let plainSessionPage = try await store.history(search: "plain-session")
        let matchingNotesPage = try await store.history(search: "keep this")
        XCTAssertEqual(plainSessionPage.items.count, 1)
        XCTAssertEqual(matchingNotesPage.items.count, 1)
    }

    func testNullOwnerSearchPreservedAndArchivedOwnersStillListed() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let first = try await store.createAthlete(name: "First")
        _ = try await store.createAthlete(name: "Second")
        try await save(store, owner: first.id, session: "owned-row", date: Date(timeIntervalSince1970: 1_700_000_100))
        try await save(store, owner: nil, session: "unowned-row", date: Date(timeIntervalSince1970: 1_700_000_200))
        try await store.setArchived(first.id, archived: true)
        // Owner-scoped and unfiltered history keep archived roots.
        let archivedOwnerPage = try await store.history(ownerID: first.id)
        let allOwnersPage = try await store.history()
        XCTAssertEqual(archivedOwnerPage.items.count, 1)
        XCTAssertEqual(allOwnersPage.items.count, 2)
        // NULL-owner rows still match on session text without an owner.
        let unowned = try await store.history(search: "unowned-row")
        XCTAssertEqual(unowned.items.count, 1)
        XCTAssertNil(unowned.items.first?.ownerID)
    }

    // MARK: - Filters compose before the limit and page cleanly

    func testDateAndProtocolFiltersApplyBeforeLimit() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Paged")
        let base: TimeInterval = 1_700_000_000
        // Newer rows that must NOT shadow the older matching page.
        for (index, day) in [300, 200, 100].enumerated() {
            try await save(store, owner: athlete.id, session: "new-\(index)", date: Date(timeIntervalSince1970: base + Double(day)), protocolKey: .cmj)
        }
        try await save(store, owner: athlete.id, session: "match-a", date: Date(timeIntervalSince1970: base), protocolKey: .sj)
        try await save(store, owner: athlete.id, session: "match-b", date: Date(timeIntervalSince1970: base - 10), protocolKey: .sj)
        let page = try await store.history(protocolKey: .sj, limit: 2,
            recordedFrom: Date(timeIntervalSince1970: base - 100),
            recordedBefore: Date(timeIntervalSince1970: base + 50))
        XCTAssertEqual(page.items.map(\.sessionKey), ["match-a", "match-b"])
        XCTAssertNil(page.nextBefore)
    }

    func testCombinedOwnerProtocolDateSearchWithCursorAfterFiltering() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let wanted = try await store.createAthlete(name: "Wanted Athlete")
        let other = try await store.createAthlete(name: "Other Athlete")
        let base: TimeInterval = 1_700_000_000
        try await save(store, owner: wanted.id, session: "w-one", date: Date(timeIntervalSince1970: base + 30), protocolKey: .cmj, notes: "alpha drill")
        try await save(store, owner: wanted.id, session: "w-two", date: Date(timeIntervalSince1970: base + 20), protocolKey: .cmj, notes: "alpha drill")
        try await save(store, owner: wanted.id, session: "w-wrong-protocol", date: Date(timeIntervalSince1970: base + 25), protocolKey: .sj, notes: "alpha drill")
        try await save(store, owner: other.id, session: "o-other-owner", date: Date(timeIntervalSince1970: base + 28), protocolKey: .cmj, notes: "alpha drill")
        try await save(store, owner: wanted.id, session: "w-too-new", date: Date(timeIntervalSince1970: base + 300), protocolKey: .cmj, notes: "alpha drill")
        let from = Date(timeIntervalSince1970: base)
        let before = Date(timeIntervalSince1970: base + 100)
        let first = try await store.history(ownerID: wanted.id, protocolKey: .cmj, search: "alpha",
            limit: 1, recordedFrom: from, recordedBefore: before)
        XCTAssertEqual(first.items.map(\.sessionKey), ["w-one"])
        let cursor = try XCTUnwrap(first.nextBefore)
        let second = try await store.history(ownerID: wanted.id, protocolKey: .cmj, search: "alpha",
            limit: 1, before: cursor, recordedFrom: from, recordedBefore: before)
        XCTAssertEqual(second.items.map(\.sessionKey), ["w-two"])
        XCTAssertNil(second.nextBefore)
    }

    func testEqualTimestampPaginationWithFiltersHasNoGapsOrDuplicates() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Ties")
        let stamp = Date(timeIntervalSince1970: 1_700_000_000)
        for index in 0..<5 {
            try await save(store, owner: athlete.id, session: "tie-\(index)", date: stamp)
        }
        var seen: [UUID] = []
        var cursor: (date: Date, id: UUID)?
        repeat {
            let page = try await store.history(limit: 2, before: cursor,
                recordedFrom: Date(timeIntervalSince1970: 1_699_999_000),
                recordedBefore: Date(timeIntervalSince1970: 1_700_001_000))
            seen += page.items.map(\.id)
            cursor = page.nextBefore
            if page.items.count < 2 { break }
        } while cursor != nil
        XCTAssertEqual(seen.count, 5)
        XCTAssertEqual(Set(seen).count, 5)
        XCTAssertNil(cursor)
    }
}
