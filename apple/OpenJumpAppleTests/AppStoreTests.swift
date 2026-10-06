import XCTest
import SQLite3
@testable import OpenJumpApple

final class AppStoreTests: XCTestCase {
    private func temporaryDatabase() throws -> (URL, SQLiteStore) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-\(UUID().uuidString).sqlite")
        return (url, try SQLiteStore(databaseURL: url))
    }
    private func metric(_ value: Double = 0.4, ordinal: Int = 0, key: String = "FLIGHT_TIME_MS") throws -> SavedMetric {
        try SavedMetric(key: key, unit: "ms", value: value, ordinal: ordinal)
    }
    private func sample(_ owner: UUID?, key: String = "session-1", date: Date = Date(), value: Double = 400) throws -> SavedMeasurement {
        try SavedMeasurement(sessionKey: key, ownerID: owner, protocolKey: .cmj, recordedAt: date,
                             notes: " confirmed ", metrics: [metric(value)])
    }

    func testDurabilityOwnerAndIdempotentConflict() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let athlete = try await store.createAthlete(name: "A")
        let result = try await store.save(sample(athlete.id))
        XCTAssertEqual(result.ownerID, athlete.id)
        let retry = try await store.save(sample(athlete.id, value: 999))
        XCTAssertEqual(retry.metrics.first?.value, 400, "idempotent retry returns the confirmed stored row")
        do { _ = try await store.save(sample(nil)); XCTFail("expected owner conflict") }
        catch { XCTAssertEqual(error as? StoreError, .ownerConflict) }
        let reopened = try SQLiteStore(databaseURL: url)
        let page = try await reopened.history(ownerID: athlete.id)
        XCTAssertEqual(page.items.count, 1); XCTAssertEqual(page.items[0].metrics[0].value, 400)
    }

    func testArchivePreservesHistoryAndProtectsLastActive() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let first = try await store.createAthlete(name: "First")
        let second = try await store.createAthlete(name: "Second")
        try await store.save(sample(first.id))
        try await store.setArchived(first.id, archived: true)
        let retry = try await store.save(sample(first.id, value: 999))
        XCTAssertEqual(retry.metrics.first?.value, 400)
        let retainedHistory = try await store.history(ownerID: first.id)
        XCTAssertEqual(retainedHistory.items.count, 1)
        do { try await store.setArchived(second.id, archived: true); XCTFail("last active athlete must remain") }
        catch { XCTAssertEqual(error as? StoreError, .lastActiveAthlete) }
    }

    func testNonfiniteArchiveTimestampDoesNotDamageRoster() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let first = try await store.createAthlete(name: "First")
        _ = try await store.createAthlete(name: "Second")
        do {
            try await store.setArchived(first.id, archived: true, now: Date(timeIntervalSince1970: .infinity))
            XCTFail("nonfinite archive date must be rejected before the write")
        } catch { XCTAssertEqual(error as? StoreError, .invalidMeasurement) }
        let roster = try await store.athletes()
        XCTAssertEqual(roster.count, 2)
        XCTAssertTrue(roster.allSatisfy { $0.archivedAt == nil })
    }

    func testMetricPresentationDoesNotReinterpretMismatchedSourceUnits() throws {
        let locale = Locale(identifier: "en_US")
        let mismatch = try SavedMetric(key: "HEIGHT_CM", unit: "m", value: 2.54, ordinal: 0)
        let text = formattedMetric(mismatch, units: .unitedStates, locale: locale)
        XCTAssertEqual(text, "2.54 m")
        let canonical = try SavedMetric(key: "HEIGHT_CM", unit: "CENTIMETER", value: 2.54, ordinal: 0)
        XCTAssertEqual(formattedMetric(canonical, units: .unitedStates, locale: locale), "1 in")
        let legacyDistance = try SavedMetric(key: "DISTANCE_CM", unit: "CENTIMETER", value: 30.48, ordinal: 0)
        XCTAssertEqual(formattedMetric(legacyDistance, units: .unitedStates, locale: locale), "1 ft")
    }

    func testMutableNotesAreRevalidatedBeforeSave() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let athlete = try await store.createAthlete(name: "A")
        var record = try sample(athlete.id)
        record.notes = String(repeating: "x", count: 501)
        do { _ = try await store.save(record); XCTFail("overlong mutable notes must be rejected") }
        catch { XCTAssertEqual(error as? StoreError, .invalidMeasurement) }
        let page = try await store.history()
        XCTAssertTrue(page.items.isEmpty)
        let count = try await store.measurementCount(ownerID: athlete.id)
        XCTAssertEqual(count, 0)
    }

    func testLocaleInputPreservesValidPrecisionAndRejectsGrouping() {
        XCTAssertEqual(MeasurementPresentation.parsePositive("72.0", locale: Locale(identifier: "en")), 72)
        XCTAssertEqual(MeasurementPresentation.parsePositive("72,0", locale: Locale(identifier: "es")), 72)
        XCTAssertEqual(MeasurementPresentation.parsePositive("0.45359237", locale: Locale(identifier: "en")), 0.45359237)
        XCTAssertEqual(MeasurementPresentation.parsePositive("0,45359237", locale: Locale(identifier: "es")), 0.45359237)
        for input in ["1,000", "1.0oops", "1e3", "Infinity", "0", "1.2.3"] {
            XCTAssertNil(MeasurementPresentation.parsePositive(input, locale: Locale(identifier: "en")))
        }
    }

    func testModelsRejectNonfiniteDatesAndEmbeddedNulls() throws {
        XCTAssertThrowsError(try Athlete(name: "bad\0name"))
        XCTAssertThrowsError(try Athlete(name: "A", notes: String(repeating: "x", count: 501)))
        XCTAssertThrowsError(try SavedMeasurement(sessionKey: "bad", ownerID: nil, protocolKey: .cmj,
            recordedAt: Date(timeIntervalSince1970: .infinity), metrics: [metric()]))
        XCTAssertThrowsError(try SavedMetric(key: "BAD\0KEY", unit: "ms", value: 10, ordinal: 0))
    }

    func testInvalidMetricRollsBackWholeSave() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let athlete = try await store.createAthlete(name: "A")
        let bad = try SavedMetric(key: "BAD", unit: "m", value: 1, ordinal: 0)
        let duplicate = try SavedMetric(key: "BAD2", unit: "m", value: 2, ordinal: 0)
        let result = try SavedMeasurement(sessionKey: "bad", ownerID: athlete.id, protocolKey: .sj, metrics: [bad,duplicate])
        do { _ = try await store.save(result); XCTFail("expected uniqueness failure") } catch { }
        let history = try await store.history()
        XCTAssertTrue(history.items.isEmpty)
    }

    func testHistoryFilterTiesAndKeysetPages() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let athlete = try await store.createAthlete(name: "A")
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        for key in ["one", "two", "three"] { try await store.save(sample(athlete.id, key: key, date: date)) }
        let first = try await store.history(ownerID: athlete.id, protocolKey: .cmj, search: "one", limit: 1)
        XCTAssertEqual(first.items.count, 1)
        let page = try await store.history(ownerID: athlete.id, limit: 2)
        XCTAssertEqual(page.items.count, 2); XCTAssertNotNil(page.nextBefore)
        let next = try await store.history(ownerID: athlete.id, limit: 2, before: page.nextBefore)
        XCTAssertEqual(next.items.count, 1)
    }

    @MainActor func testPreferencesUseIsolatedSuiteAndResolveSelectionConservatively() throws {
        let suite = "openjump-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = AppPreferences(defaults: defaults, namespace: suite)
        let one = try Athlete(name: "One")
        XCTAssertEqual(prefs.resolveActiveAthlete(in: [one]), one.id)
        let selected = try Athlete(name: "Selected")
        prefs.selectedAthleteID = selected.id
        XCTAssertNil(prefs.resolveActiveAthlete(in: [one]))
        XCTAssertNil(prefs.selectedAthleteID)
        prefs.units = .unitedStates
        XCTAssertEqual(AppPreferences(defaults: defaults, namespace: suite).units, .unitedStates)
    }

    func testMetricPresentationConvertsCanonicalStoredValues() throws {
        let us = UnitProfile.unitedStates
        let locale = Locale(identifier: "en_US")
        let height = try SavedMetric(key: "HEIGHT_CM", unit: "cm", value: 2.54, ordinal: 0)
        XCTAssertTrue(formattedMetric(height, units: us, locale: locale).hasSuffix("in"))
        let distance = try SavedMetric(key: "DISTANCE_M", unit: "m", value: 0.3048, ordinal: 0)
        XCTAssertTrue(formattedMetric(distance, units: us, locale: locale).hasSuffix("ft"))
        let speed = try SavedMetric(key: "TAKEOFF_VELOCITY_MPS", unit: "m/s", value: 0.3048, ordinal: 0)
        XCTAssertTrue(formattedMetric(speed, units: us, locale: locale).hasSuffix("ft/s"))
        let time = try SavedMetric(key: "FLIGHT_TIME_MS", unit: "ms", value: 1000, ordinal: 0)
        XCTAssertTrue(formattedMetric(time, units: us, locale: locale).hasSuffix("s"))
    }

    func testAvatarDefaultUpdateAndArchivePersistence() async throws {
        let (url, store) = try temporaryDatabase(); defer { try? FileManager.default.removeItem(at: url) }
        let athlete = try await store.createAthlete(name: "Avatar")
        _ = try await store.createAthlete(name: "Other active athlete")
        XCTAssertEqual(athlete.avatarKey, "avatar_frog_jump")
        var edited = athlete; edited.avatarKey = "avatar_jumper"
        _ = try await store.updateAthlete(edited)
        let reopened = try SQLiteStore(databaseURL: url)
        let reopenedRoster = try await reopened.athletes()
        XCTAssertEqual(reopenedRoster.first(where: { $0.id == athlete.id })?.avatarKey, "avatar_jumper")
        try await reopened.setArchived(athlete.id, archived: true)
        let archivedRoster = try await reopened.athletes(includeArchived: true)
        XCTAssertEqual(archivedRoster.first(where: { $0.id == athlete.id })?.avatarKey, "avatar_jumper")
    }

    func testVersionOneMigrationPreservesAthleteData() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-v1-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { if let db { sqlite3_close(db) } }
        let id = UUID().uuidString
        let ownedID = UUID().uuidString
        let unownedID = UUID().uuidString
        let sql = """
            CREATE TABLE athletes(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,weight_kg REAL,height_cm REAL,notes TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL,archived_at REAL);
            INSERT INTO athletes VALUES('\(id)','Legacy',70,180,'note',10,11,NULL);
            CREATE TABLE assessments(id TEXT PRIMARY KEY,session_key TEXT,owner_id TEXT,protocol_key TEXT,side TEXT,drop_height_cm REAL,recorded_at REAL,notes TEXT);
            CREATE TABLE attempt_metrics(assessment_id TEXT,metric_key TEXT,unit TEXT,value REAL,ordinal INTEGER);
            INSERT INTO assessments VALUES('\(ownedID)','owned-session','\(id)','CMJ',NULL,NULL,20,'owned note');
            INSERT INTO assessments VALUES('\(unownedID)','unowned-session',NULL,'SJ',NULL,NULL,21,'unowned note');
            INSERT INTO attempt_metrics VALUES('\(ownedID)','HEIGHT_CM','CENTIMETER',31,0);
            INSERT INTO attempt_metrics VALUES('\(unownedID)','HEIGHT_CM','CENTIMETER',27,0);
            PRAGMA user_version=1;
            """
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
        sqlite3_close(db); db = nil
        let migrated = try SQLiteStore(databaseURL: url)
        let roster = try await migrated.athletes()
        let athlete = try XCTUnwrap(roster.first)
        XCTAssertEqual(athlete.id, UUID(uuidString: id))
        XCTAssertEqual(athlete.weightKg, 70); XCTAssertEqual(athlete.heightCm, 180)
        XCTAssertEqual(athlete.notes, "note"); XCTAssertNil(athlete.avatarKey)
        XCTAssertEqual(athlete.createdAt, Date(timeIntervalSince1970: 10))
        XCTAssertEqual(athlete.updatedAt, Date(timeIntervalSince1970: 11))
        let history = try await migrated.history()
        let owned = try XCTUnwrap(history.items.first { $0.id.uuidString == ownedID })
        let unowned = try XCTUnwrap(history.items.first { $0.id.uuidString == unownedID })
        XCTAssertEqual(owned.sessionKey, "owned-session"); XCTAssertEqual(owned.ownerID, athlete.id)
        XCTAssertEqual(owned.notes, "owned note"); XCTAssertEqual(owned.metrics.first?.value, 31)
        XCTAssertEqual(unowned.sessionKey, "unowned-session"); XCTAssertNil(unowned.ownerID)
        XCTAssertEqual(unowned.notes, "unowned note"); XCTAssertEqual(unowned.metrics.first?.value, 27)
        var edited = athlete; edited.name = "Renamed"; edited.avatarKey = "future_avatar"
        _ = try await migrated.updateAthlete(edited)
        let renamed = try await migrated.athletes()
        XCTAssertEqual(renamed.first?.avatarKey, "future_avatar")
        let reopened = try SQLiteStore(databaseURL: url)
        let reopenedRoster = try await reopened.athletes()
        XCTAssertEqual(reopenedRoster.first?.avatarKey, "future_avatar")
    }

    func testAvatarCatalogLegacyResolutionAndUnknownPreservation() throws {
        XCTAssertEqual(AthleteAvatarCatalog.options.count, 84)
        XCTAssertEqual(Set(AthleteAvatarCatalog.options.map(\.key)).count, 84)
        XCTAssertEqual(AthleteAvatarCatalog.resolve("avatar_img02_r1_c1")?.key, "avatar_junior_athlete")
        XCTAssertEqual(AthleteAvatarCatalog.resolve("avatar_gymnast")?.key, "avatar_mobility")
        let original = "future_avatar"
        let athlete = try Athlete(name: "Legacy", avatarKey: original)
        XCTAssertNil(AthleteAvatarCatalog.resolve(athlete.avatarKey))
        XCTAssertEqual(athlete.avatarKey, original)
    }

    func testCorruptionIsNotResetAndUnitsAndLocaleParsing() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-corrupt-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("not a sqlite database".utf8).write(to: url)
        XCTAssertThrowsError(try SQLiteStore(databaseURL: url))
        XCTAssertEqual(MeasurementPresentation.centimeters(1, from: .inches), 2.54, accuracy: 1e-12)
        XCTAssertEqual(MeasurementPresentation.meters(1, from: .feet), 0.3048, accuracy: 1e-12)
        XCTAssertEqual(MeasurementPresentation.kilograms(1, from: .pounds), 0.45359237, accuracy: 1e-12)
        XCTAssertEqual(MeasurementPresentation.parsePositive("1,5", locale: Locale(identifier: "es")), 1.5)
        XCTAssertEqual(MeasurementPresentation.parsePositive("1.5", locale: Locale(identifier: "en")), 1.5)
        XCTAssertNil(MeasurementPresentation.parsePositive("1,5", locale: Locale(identifier: "en")))
        XCTAssertNil(MeasurementPresentation.parsePositive("-1", locale: Locale(identifier: "en")))
        XCTAssertNil(MeasurementPresentation.parsePositive("NaN", locale: Locale(identifier: "en")))
        XCTAssertNil(MeasurementPresentation.parsePositive("1.2oops", locale: Locale(identifier: "en")))
    }
}
