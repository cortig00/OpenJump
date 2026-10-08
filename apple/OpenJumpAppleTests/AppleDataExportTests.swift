import XCTest
import SQLite3
@testable import OpenJumpApple

final class AppleDataExportTests: XCTestCase {
    private enum FixtureError: Error { case sqlite, csv }
    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("openjump-export-\(UUID().uuidString).sqlite")
    }
    private func execute(_ url: URL, _ sql: String) throws {
        var handle: OpaquePointer?
        guard sqlite3_open(url.path, &handle) == SQLITE_OK, let db = handle else {
            if let handle { sqlite3_close(handle) }; throw FixtureError.sqlite
        }
        defer { sqlite3_close(db) }
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw FixtureError.sqlite }
    }
    private func scalar(_ url: URL, _ sql: String) throws -> Double {
        var handle: OpaquePointer?
        guard sqlite3_open(url.path, &handle) == SQLITE_OK, let db = handle else {
            if let handle { sqlite3_close(handle) }; throw FixtureError.sqlite
        }
        defer { sqlite3_close(db) }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let stmt = statement else { throw FixtureError.sqlite }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { throw FixtureError.sqlite }
        return sqlite3_column_double(stmt, 0)
    }
    private func object(_ result: AppleExportResult) throws -> [String: Any] {
        let decoded = try JSONSerialization.jsonObject(with: result.data)
        return try XCTUnwrap(decoded as? [String: Any])
    }
    private func legacy(owner: UUID? = nil, session: String = UUID().uuidString, value: Double = 27,
                        date: Date = Date(timeIntervalSince1970: 1_700_000_000.125), ordinal: Int = 0) throws -> SavedMeasurement {
        let metric = try SavedMetric(key: "HEIGHT_CM", unit: "CENTIMETER", value: value, ordinal: ordinal)
        return try SavedMeasurement(sessionKey: session, ownerID: owner, protocolKey: .sj,
                                    recordedAt: date, notes: "legacy", metrics: [metric])
    }
    private func draft(_ owner: UUID, dropJump: Bool = false) -> TemporalJumpDraft {
        let first = JumpEventMark(kind: dropJump ? .initialContact : .movementStart,
            frameIndex: dropJump ? 2 : 1, ptsUs: dropJump ? 200_000 : 100_000,
            previousPtsUs: dropJump ? 100_000 : 0, nextPtsUs: dropJump ? 300_000 : 200_000)
        let events = [first,
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 400_000, previousPtsUs: 300_000, nextPtsUs: 500_000),
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 900_000, previousPtsUs: 800_000, nextPtsUs: nil)]
        return TemporalJumpDraft(sessionKey: UUID().uuidString, ownerID: owner, protocolKey: dropJump ? .dropJump : .cmj,
            side: nil, dropHeightCm: dropJump ? 30 : nil, recordedAt: Date(timeIntervalSince1970: 1_700_000_000.125),
            notes: "marked note", source: .files, sourceFrameCount: 10, sourceOriginUs: 0,
            temporalState: .realtimeDeclared, events: events)
    }
    // Narrow CAMERA-slice helper: same canonical CMJ marks as draft(_:),
    // with an explicit provenance/source. Existing helpers are untouched.
    private func sourcedDraft(_ owner: UUID, source: JumpVideoSource, session: String = UUID().uuidString, notes: String? = "marked note") -> TemporalJumpDraft {
        let events = [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 400_000, previousPtsUs: 300_000, nextPtsUs: 500_000),
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 900_000, previousPtsUs: 800_000, nextPtsUs: nil)]
        return TemporalJumpDraft(sessionKey: session, ownerID: owner, protocolKey: .cmj,
            side: nil, dropHeightCm: nil, recordedAt: Date(timeIntervalSince1970: 1_700_000_000.125),
            notes: notes, source: source, sourceFrameCount: 10, sourceOriginUs: 0,
            temporalState: .realtimeDeclared, events: events)
    }
    private func expectFailure(_ store: SQLiteStore, format: AppleExportFormat = .jsonBackup,
                               limits: AppleExportLimits = .standard, error expected: AppleDataExportError) async {
        do { _ = try await store.exportData(format: format, limits: limits); XCTFail("Invalid snapshot must not return any document") }
        catch { XCTAssertEqual(error as? AppleDataExportError, expected) }
    }
    // Independent RFC4180 reader used only to inspect CSV, never by production.
    private func parseCSV(_ text: String) throws -> [[String]] {
        // CRLF is one Swift Character but two Unicode scalars; parse scalars
        // so the independent RFC4180 oracle recognizes actual row delimiters.
        let chars = Array(text.unicodeScalars)
        var rows: [[String]] = [], row: [String] = [], field = "", quoted = false, index = 0
        while index < chars.count {
            let c = chars[index]
            if quoted {
                if c == "\"" {
                    if index + 1 < chars.count && chars[index + 1] == "\"" { field.append("\""); index += 1 }
                    else { quoted = false }
                } else { field.unicodeScalars.append(c) }
            } else if c == "\"" && field.isEmpty { quoted = true }
            else if c == "," { row.append(field); field = "" }
            else if c == "\r" {
                guard index + 1 < chars.count && chars[index + 1] == "\n" else { throw FixtureError.csv }
                row.append(field); rows.append(row); row = []; field = ""; index += 1
            } else if c == "\n" { throw FixtureError.csv }
            else { field.unicodeScalars.append(c) }
            index += 1
        }
        guard !quoted, field.isEmpty, row.isEmpty else { throw FixtureError.csv }
        return rows
    }

    func testCSVTextHardeningQuotesAndUnicodeScalarPrefix() {
        XCTAssertEqual(AppleDataExport.csvText("plain"), "plain")
        XCTAssertEqual(AppleDataExport.csvText("=SUM(A1)"), "'=SUM(A1)")
        XCTAssertEqual(AppleDataExport.csvText(" \t@cmd"), "' \t@cmd")
        XCTAssertEqual(AppleDataExport.csvText("-formula"), "'-formula")
        XCTAssertEqual(AppleDataExport.csvText("+formula"), "'+formula")
        XCTAssertEqual(AppleDataExport.csvText("=\u{0301}SUM(A1)"), "'=\u{0301}SUM(A1)")
        XCTAssertEqual(AppleDataExport.csvText("a,b\"c"), "\"a,b\"\"c\"")
        XCTAssertEqual(AppleDataExport.csvText("\nhello"), "\"'\nhello\"")
        XCTAssertEqual(AppleDataExport.csvText("plain\r\ntext"), "\"plain\r\ntext\"")
    }

    func testBoundedAppendExactBoundaryAndFailureIsAtomic() throws {
        var writer = AppleBoundedExportBytes(limit: 4)
        try writer.append("abc")
        XCTAssertThrowsError(try writer.append("de")) { XCTAssertEqual($0 as? AppleDataExportError, .tooLarge) }
        XCTAssertEqual(String(data: writer.data, encoding: .utf8), "abc")
        try writer.append("d")
        XCTAssertEqual(writer.data.count, 4)
        XCTAssertThrowsError(try writer.append("e"))
        var invalid = AppleBoundedExportBytes(limit: -1)
        XCTAssertThrowsError(try invalid.append(""))
    }

    func testLimitsCannotGrowAndNumbersCannotBeNonfinite() throws {
        try AppleExportLimits.standard.validate()
        XCTAssertEqual(try AppleDataExport.number(-2.5), "-2.5")
        XCTAssertThrowsError(try AppleDataExport.number(.nan))
        XCTAssertThrowsError(try AppleDataExport.number(.infinity))
        var limits = AppleExportLimits.standard; limits.maxBytes = Int.max
        XCTAssertThrowsError(try limits.validate())
        limits = .standard; limits.measurements = 5_001
        XCTAssertThrowsError(try limits.validate())
    }

    func testEmptySnapshotHasExplicitCreationOnlyHeaderAndNoFakeData() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let snapshot = try await store.exportData(format: .jsonBackup, createdAt: Date(timeIntervalSince1970: 42.25))
        let json = try object(snapshot)
        XCTAssertEqual(json["contract"] as? String, "openjump-apple-backup")
        XCTAssertEqual(json["sourceSchemaVersion"] as? Int, 4)
        XCTAssertEqual(json["dateEncoding"] as? String, "unix-seconds")
        XCTAssertEqual(json["createdAtEpochSeconds"] as? Double, 42.25)
        for key in ["mediaIncluded", "preferencesIncluded", "restorationSupported", "androidCompatible"] {
            XCTAssertEqual(json[key] as? Bool, false)
        }
        XCTAssertEqual((json["athletes"] as? [Any])?.count, 0)
        XCTAssertEqual((json["measurements"] as? [Any])?.count, 0)
        let csv = try await store.exportData(format: .analyticalCSV)
        let rows = try parseCSV(XCTUnwrap(String(data: csv.data, encoding: .utf8)))
        let expected = "schema_version,measurement_id,session_key,recorded_at_epoch_seconds,date_time_utc,athlete_id,athlete_name_current,protocol_key,side,drop_height_cm,metric_key,metric_value,metric_unit,metric_ordinal,analysis_source,analysis_version,source_frame_count,source_origin_us,temporal_state,movement_start_frame,movement_start_pts_us,initial_contact_frame,initial_contact_pts_us,takeoff_frame,takeoff_pts_us,landing_frame,landing_pts_us,notes"
        XCTAssertEqual(rows, [expected.components(separatedBy: ",")])
        XCTAssertEqual(rows[0].count, 28)
    }

    func testJSONPreservesCanonicalCMJDJGraphsArchivedProfileAndUnownedLegacy() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "Archived", weightKg: 70, heightCm: 175,
            notes: "profile note", avatarKey: "future/avatar:raw", now: Date(timeIntervalSince1970: 10))
        _ = try await store.createAthlete(name: "Active")
        let cmj = try await store.saveTemporalJump(draft(owner.id))
        let dj = try await store.saveTemporalJump(draft(owner.id, dropJump: true))
        let unowned = try await store.save(legacy())
        try await store.setArchived(owner.id, archived: true, now: Date(timeIntervalSince1970: 1_700_000_050))
        let snapshot = try await store.exportData(format: .jsonBackup)
        XCTAssertEqual(snapshot.profileCount, 2); XCTAssertEqual(snapshot.measurementCount, 3); XCTAssertEqual(snapshot.metricCount, 10)
        let json = try object(snapshot)
        let athletes = try XCTUnwrap(json["athletes"] as? [[String: Any]])
        let profile = try XCTUnwrap(athletes.first { ($0["id"] as? String) == owner.id.uuidString })
        XCTAssertEqual(profile["weightKg"] as? Double, 70); XCTAssertEqual(profile["heightCm"] as? Double, 175)
        XCTAssertEqual(profile["avatarKey"] as? String, "future/avatar:raw")
        XCTAssertEqual(profile["archivedAt"] as? Double, 1_700_000_050)
        let roots = try XCTUnwrap(json["measurements"] as? [[String: Any]])
        let marked = try XCTUnwrap(roots.first { ($0["id"] as? String) == cmj.id.uuidString })
        XCTAssertEqual(marked["ownerID"] as? String, owner.id.uuidString)
        XCTAssertEqual(marked["sessionKey"] as? String, cmj.sessionKey)
        XCTAssertEqual(marked["recordedAt"] as? Double, 1_700_000_000.125)
        let metrics = try XCTUnwrap(marked["metrics"] as? [[String: Any]])
        let height = try XCTUnwrap(metrics[0]["value"] as? Double)
        XCTAssertEqual(height, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(metrics[1]["value"] as? Double, 500)
        XCTAssertEqual(metrics[3]["value"] as? Double, 300)
        let graph = try XCTUnwrap(marked["analysis"] as? [String: Any])
        XCTAssertEqual(graph["analysisVersion"] as? Int, 1)
        XCTAssertEqual(graph["sourceFrameCount"] as? Int, 10)
        XCTAssertEqual(graph["sourceOriginUs"] as? Int, 0)
        XCTAssertEqual(graph["source"] as? String, "FILES")
        XCTAssertEqual(graph["temporalState"] as? String, "REALTIME_DECLARED")
        let events = try XCTUnwrap(graph["events"] as? [[String: Any]])
        XCTAssertEqual(events[1]["ordinal"] as? Int, 1); XCTAssertEqual(events[1]["frameIndex"] as? Int, 4)
        XCTAssertEqual(events[1]["ptsUs"] as? Int, 400_000)
        XCTAssertEqual(events[1]["previousPtsUs"] as? Int, 300_000)
        XCTAssertEqual(events[1]["nextPtsUs"] as? Int, 500_000)
        let drop = try XCTUnwrap(roots.first { ($0["id"] as? String) == dj.id.uuidString })
        let dropMetrics = try XCTUnwrap(drop["metrics"] as? [[String: Any]])
        let rsi = try XCTUnwrap(dropMetrics[0]["value"] as? Double)
        XCTAssertEqual(rsi, 1.5322890625, accuracy: 1e-12)
        XCTAssertEqual(drop["dropHeightCm"] as? Double, 30)
        let legacyRoot = try XCTUnwrap(roots.first { ($0["id"] as? String) == unowned.id.uuidString })
        XCTAssertTrue(legacyRoot["ownerID"] is NSNull); XCTAssertTrue(legacyRoot["analysis"] is NSNull)
        let text = try XCTUnwrap(String(data: snapshot.data, encoding: .utf8))
        XCTAssertFalse(text.contains(url.path)); XCTAssertFalse(text.contains("videoURL")); XCTAssertFalse(text.contains("openjump.apple."))
    }

    func testRawTextUUIDSpellingEmptyNotesAndEpochAreNotNormalized() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        _ = try await store.createAthlete(name: "Initial")
        let measurement = try await store.save(legacy())
        try execute(url, "UPDATE athletes SET id=lower(id),name='  Raw Name  ',notes='',avatar_key='unknown/path:key',created_at=0.123456789,updated_at=0.987654321; UPDATE assessments SET id=lower(id),session_key='  raw session  ',notes='  raw note  ',recorded_at=0.123456789; UPDATE attempt_metrics SET assessment_id=lower(assessment_id);")
        let snapshot = try await store.exportData(format: .jsonBackup)
        let json = try object(snapshot)
        let profile = try XCTUnwrap((json["athletes"] as? [[String: Any]])?.first)
        XCTAssertEqual(profile["name"] as? String, "  Raw Name  "); XCTAssertEqual(profile["notes"] as? String, "")
        XCTAssertEqual(profile["avatarKey"] as? String, "unknown/path:key")
        XCTAssertEqual(profile["createdAt"] as? Double, 0.123456789)
        let root = try XCTUnwrap((json["measurements"] as? [[String: Any]])?.first)
        XCTAssertEqual(root["id"] as? String, measurement.id.uuidString.lowercased())
        XCTAssertEqual(root["sessionKey"] as? String, "  raw session  "); XCTAssertEqual(root["notes"] as? String, "  raw note  ")
        XCTAssertEqual(root["recordedAt"] as? Double, 0.123456789)
    }

    func testStreamingCursorRetains205TiedDatesAndLegacyOrdinal() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        var expected = Set<String>()
        for _ in 0..<205 {
            let saved = try await store.save(legacy(date: Date(timeIntervalSince1970: 42.25), ordinal: 3))
            expected.insert(saved.id.uuidString)
        }
        let snapshot = try await store.exportData(format: .jsonBackup)
        let json = try object(snapshot)
        let roots = try XCTUnwrap(json["measurements"] as? [[String: Any]])
        let ids = try roots.map { try XCTUnwrap($0["id"] as? String) }
        XCTAssertEqual(ids.count, 205); XCTAssertEqual(Set(ids), expected); XCTAssertEqual(ids, ids.sorted(by: >))
        for root in roots {
            let metric = try XCTUnwrap((root["metrics"] as? [[String: Any]])?.first)
            XCTAssertEqual(metric["ordinal"] as? Int, 3)
        }
    }

    func testRowBudgetFailureRollsBackReadTransactionAndRetainsProfiles() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        _ = try await store.createAthlete(name: "One"); _ = try await store.createAthlete(name: "Two")
        var limits = AppleExportLimits.standard; limits.profiles = 1
        await expectFailure(store, limits: limits, error: .tooLarge)
        let retained = try await store.athletes()
        XCTAssertEqual(retained.count, 2)
        _ = try await store.createAthlete(name: "Three")
        let after = try await store.athletes(); XCTAssertEqual(after.count, 3)
    }

    func testTinyByteBudgetReturnsNoPartialDocumentAndStoreRemainsUsable() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        _ = try await store.createAthlete(name: "Retained")
        var limits = AppleExportLimits.standard; limits.maxBytes = 4
        await expectFailure(store, limits: limits, error: .tooLarge)
        let retained = try await store.athletes(); XCTAssertEqual(retained.count, 1)
        _ = try await store.createAthlete(name: "Still writable")
    }

    func testFiniteTamperedMetricsFailBothFormatsWithoutRepair() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "Owner")
        _ = try await store.saveTemporalJump(draft(owner.id))
        try execute(url, "UPDATE attempt_metrics SET value=value+0.25 WHERE metric_key='HEIGHT_CM'")
        for format in [AppleExportFormat.jsonBackup, .analyticalCSV] { await expectFailure(store, format: format, error: .invalidSnapshot) }
        let retainedTamper = try scalar(url, "SELECT value FROM attempt_metrics WHERE metric_key='HEIGHT_CM'")
        XCTAssertEqual(retainedTamper, 30.89578125, accuracy: 1e-10)
        _ = try await store.createAthlete(name: "After rollback")
    }

    func testMissingAnalysisWithPresentEventsIsNotExportedAsLegacy() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "Owner")
        _ = try await store.saveTemporalJump(draft(owner.id))
        try execute(url, "DELETE FROM assessment_analysis")
        await expectFailure(store, error: .invalidSnapshot)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_events"), 3)
    }

    func testWrongSQLiteTypesAndMalformedUTF8FailClosed() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        _ = try await store.createAthlete(name: "Typed")
        try execute(url, "UPDATE athletes SET created_at='not-a-number'")
        await expectFailure(store, error: .invalidSnapshot)
        try execute(url, "UPDATE athletes SET created_at=10,name=CAST(X'FF' AS TEXT)")
        await expectFailure(store, error: .invalidSnapshot)
    }

    func testOrphanChildFailsWithoutSilentlyDroppingOrDeletingIt() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        try execute(url, "INSERT INTO attempt_metrics VALUES('\(UUID().uuidString)','HEIGHT_CM','CENTIMETER',27,0)")
        await expectFailure(store, error: .invalidSnapshot)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM attempt_metrics"), 1)
    }

    func testCSVHas28CanonicalColumnsQuotedNotesAndNoFakeLegacyEvents() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "=SUM(A1)")
        _ = try await store.save(legacy(owner: owner.id, session: "@session", value: -2.5))
        let note = " \t+danger\r\nquoted,\"x\""
        try execute(url, "UPDATE assessments SET notes='\(note)'")
        let snapshot = try await store.exportData(format: .analyticalCSV)
        let rows = try parseCSV(XCTUnwrap(String(data: snapshot.data, encoding: .utf8)))
        XCTAssertEqual(rows.count, 2); XCTAssertEqual(rows[1].count, 28)
        let row = rows[1]
        XCTAssertEqual(row[2], "'@session"); XCTAssertEqual(row[6], "'=SUM(A1)")
        XCTAssertEqual(Double(row[3]), 1_700_000_000.125)
        XCTAssertEqual(row[4], "2023-11-14T22:13:20.125Z")
        XCTAssertEqual(row[10], "HEIGHT_CM"); XCTAssertEqual(row[11], "-2.5"); XCTAssertEqual(row[12], "CENTIMETER")
        XCTAssertEqual(Array(row[14...26]), Array(repeating: "", count: 13))
        XCTAssertEqual(row[27], "'" + note)
    }

    func testFutureGraphVersionAndNonintegerFrameAreRejected() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "Owner")
        _ = try await store.saveTemporalJump(draft(owner.id))
        try execute(url, "PRAGMA ignore_check_constraints=ON; UPDATE assessment_analysis SET analysis_version=2;")
        await expectFailure(store, error: .invalidSnapshot)
        try execute(url, "UPDATE assessment_analysis SET analysis_version=1; UPDATE assessment_events SET frame_index=1.5 WHERE ordinal=0;")
        await expectFailure(store, error: .invalidSnapshot)
    }

    func testPathologicalGraphemeTextFailsBeforeLargeModelAllocation() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        _ = try await store.createAthlete(name: "Owner")
        let pathological = "A" + String(repeating: "\u{0301}", count: 40_000)
        // Bypass SQLite character-count CHECK only in this disposable fixture
        // to exercise the separate export UTF-8 admission boundary.
        try execute(url, "PRAGMA ignore_check_constraints=ON; UPDATE athletes SET name='\(pathological)'")
        await expectFailure(store, error: .tooLarge)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM athletes"), 1)
    }

    func testCameraGraphExportsJSONAndCSVWithoutMediaLeakage() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "Owner")
        let saved = try await store.saveTemporalJump(sourcedDraft(owner.id, source: .camera, notes: "camera note"))
        let snapshot = try await store.exportData(format: .jsonBackup)
        XCTAssertEqual(snapshot.profileCount, 1)
        XCTAssertEqual(snapshot.measurementCount, 1)
        XCTAssertEqual(snapshot.metricCount, 5)
        let json = try object(snapshot)
        XCTAssertEqual(json["contract"] as? String, "openjump-apple-backup")
        XCTAssertEqual(json["formatVersion"] as? Int, 1)
        XCTAssertEqual(json["sourceSchemaVersion"] as? Int, 4)
        for key in ["mediaIncluded", "preferencesIncluded", "restorationSupported", "androidCompatible"] {
            XCTAssertEqual(json[key] as? Bool, false)
        }
        let root = try XCTUnwrap((json["measurements"] as? [[String: Any]])?.first)
        XCTAssertEqual(root["id"] as? String, saved.id.uuidString)
        XCTAssertEqual(root["sessionKey"] as? String, saved.sessionKey)
        XCTAssertEqual(root["notes"] as? String, "camera note")
        let metrics = try XCTUnwrap(root["metrics"] as? [[String: Any]])
        XCTAssertEqual(metrics.count, 5)
        let keys = try metrics.map { try XCTUnwrap($0["key"] as? String) }
        XCTAssertEqual(keys, ["HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"])
        let height = try XCTUnwrap(metrics[0]["value"] as? Double)
        XCTAssertEqual(height, 30.64578125, accuracy: 1e-10)
        let graph = try XCTUnwrap(root["analysis"] as? [String: Any])
        XCTAssertEqual(graph["source"] as? String, "CAMERA")
        XCTAssertEqual(graph["analysisVersion"] as? Int, 1)
        XCTAssertEqual(graph["sourceFrameCount"] as? Int, 10)
        XCTAssertEqual(graph["sourceOriginUs"] as? Int, 0)
        XCTAssertEqual(graph["temporalState"] as? String, "REALTIME_DECLARED")
        let events = try XCTUnwrap(graph["events"] as? [[String: Any]])
        XCTAssertEqual(events.count, 3)
        XCTAssertEqual(events[0]["frameIndex"] as? Int, 1)
        XCTAssertEqual(events[0]["ptsUs"] as? Int, 100_000)
        XCTAssertEqual(events[1]["frameIndex"] as? Int, 4)
        XCTAssertEqual(events[1]["ptsUs"] as? Int, 400_000)
        XCTAssertEqual(events[2]["frameIndex"] as? Int, 9)
        XCTAssertEqual(events[2]["ptsUs"] as? Int, 900_000)
        XCTAssertNil(events[2]["nextPtsUs"])
        XCTAssertFalse(events[2].keys.contains("nextPtsUs"))
        let csv = try await store.exportData(format: .analyticalCSV)
        let rows = try parseCSV(XCTUnwrap(String(data: csv.data, encoding: .utf8)))
        XCTAssertEqual(rows[0].count, 28)
        XCTAssertEqual(rows.count, 6)
        for row in rows.dropFirst() {
            XCTAssertEqual(row[0], "1")
            XCTAssertEqual(row[1], saved.id.uuidString)
            XCTAssertEqual(row[14], "CAMERA")
            XCTAssertEqual(row[15], "1")
            XCTAssertEqual(row[16], "10")
            XCTAssertEqual(row[17], "0")
            XCTAssertEqual(row[18], "REALTIME_DECLARED")
        }
        XCTAssertEqual(rows[1][19], "1")
        XCTAssertEqual(rows[1][20], "100000")
        XCTAssertEqual(rows[1][21], "")
        XCTAssertEqual(rows[1][22], "")
        XCTAssertEqual(rows[1][23], "4")
        XCTAssertEqual(rows[1][24], "400000")
        XCTAssertEqual(rows[1][25], "9")
        XCTAssertEqual(rows[1][26], "900000")
        let text = try XCTUnwrap(String(data: snapshot.data, encoding: .utf8))
        XCTAssertFalse(text.contains(url.path))
        XCTAssertFalse(text.contains("videoURL"))
        XCTAssertFalse(text.contains("openjump.apple."))
        let csvText = try XCTUnwrap(String(data: csv.data, encoding: .utf8))
        XCTAssertFalse(csvText.contains("videoURL"))
    }

    func testMixedSourcesAndArchivedOwnerPreservedAcrossBothFormats() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let owner = try await store.createAthlete(name: "Keeper")
        _ = try await store.createAthlete(name: "Active")
        let photosSaved = try await store.saveTemporalJump(sourcedDraft(owner.id, source: .photos, session: "mixed-photos", notes: "photo note"))
        let filesSaved = try await store.saveTemporalJump(sourcedDraft(owner.id, source: .files, session: "mixed-files", notes: "file note"))
        let cameraSaved = try await store.saveTemporalJump(sourcedDraft(owner.id, source: .camera, session: "mixed-camera", notes: nil))
        let legacySaved = try await store.save(legacy(owner: owner.id, session: "mixed-legacy"))
        try await store.setArchived(owner.id, archived: true, now: Date(timeIntervalSince1970: 1_700_000_050))
        let snapshot = try await store.exportData(format: .jsonBackup)
        XCTAssertEqual(snapshot.profileCount, 2)
        XCTAssertEqual(snapshot.measurementCount, 4)
        XCTAssertEqual(snapshot.metricCount, 16)
        let json = try object(snapshot)
        XCTAssertEqual(json["sourceSchemaVersion"] as? Int, 4)
        for key in ["mediaIncluded", "preferencesIncluded", "restorationSupported", "androidCompatible"] {
            XCTAssertEqual(json[key] as? Bool, false)
        }
        let roots = try XCTUnwrap(json["measurements"] as? [[String: Any]])
        let byID = Dictionary(uniqueKeysWithValues: try roots.map { (try XCTUnwrap($0["id"] as? String), $0) })
        let photosRoot = try XCTUnwrap(byID[photosSaved.id.uuidString])
        let filesRoot = try XCTUnwrap(byID[filesSaved.id.uuidString])
        let cameraRoot = try XCTUnwrap(byID[cameraSaved.id.uuidString])
        let legacyRoot = try XCTUnwrap(byID[legacySaved.id.uuidString])
        XCTAssertEqual((photosRoot["analysis"] as? [String: Any])?["source"] as? String, "PHOTOS")
        XCTAssertEqual((filesRoot["analysis"] as? [String: Any])?["source"] as? String, "FILES")
        XCTAssertEqual((cameraRoot["analysis"] as? [String: Any])?["source"] as? String, "CAMERA")
        XCTAssertTrue(legacyRoot["analysis"] is NSNull)
        XCTAssertEqual(photosRoot["sessionKey"] as? String, "mixed-photos")
        XCTAssertEqual(photosRoot["notes"] as? String, "photo note")
        XCTAssertEqual(filesRoot["notes"] as? String, "file note")
        XCTAssertTrue(cameraRoot["notes"] is NSNull)
        XCTAssertEqual(legacyRoot["notes"] as? String, "legacy")
        for saved in [photosSaved, filesSaved, cameraSaved] {
            let exported = try XCTUnwrap(byID[saved.id.uuidString])
            let metrics = try XCTUnwrap(exported["metrics"] as? [[String: Any]])
            XCTAssertEqual(metrics.count, 5)
            let value = try XCTUnwrap(metrics[0]["value"] as? Double)
            XCTAssertEqual(value, 30.64578125, accuracy: 1e-10)
        }
        let csv = try await store.exportData(format: .analyticalCSV)
        let rows = try parseCSV(XCTUnwrap(String(data: csv.data, encoding: .utf8)))
        let expected = "schema_version,measurement_id,session_key,recorded_at_epoch_seconds,date_time_utc,athlete_id,athlete_name_current,protocol_key,side,drop_height_cm,metric_key,metric_value,metric_unit,metric_ordinal,analysis_source,analysis_version,source_frame_count,source_origin_us,temporal_state,movement_start_frame,movement_start_pts_us,initial_contact_frame,initial_contact_pts_us,takeoff_frame,takeoff_pts_us,landing_frame,landing_pts_us,notes"
        XCTAssertEqual(rows[0], expected.components(separatedBy: ","))
        XCTAssertEqual(rows[0].count, 28)
        var sourcesByID: [String: String] = [:]
        var countsByID: [String: Int] = [:]
        for row in rows.dropFirst() {
            XCTAssertEqual(row.count, 28)
            sourcesByID[row[1]] = row[14]
            countsByID[row[1], default: 0] += 1
        }
        XCTAssertEqual(sourcesByID[photosSaved.id.uuidString], "PHOTOS")
        XCTAssertEqual(sourcesByID[filesSaved.id.uuidString], "FILES")
        XCTAssertEqual(sourcesByID[cameraSaved.id.uuidString], "CAMERA")
        XCTAssertEqual(sourcesByID[legacySaved.id.uuidString], "")
        XCTAssertEqual(countsByID.values.sorted(), [1, 5, 5, 5])
    }
}
