import XCTest
import SQLite3
@testable import OpenJumpApple

final class TemporalJumpStoreTests: XCTestCase {
    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("openjump-temporal-\(UUID().uuidString).sqlite")
    }

    private func draft(ownerID: UUID, sessionKey: String = UUID().uuidString,
                       source: JumpVideoSource = .photos, events: [JumpEventMark]? = nil) -> TemporalJumpDraft {
        let marks = events ?? [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 1_010_000, previousPtsUs: 1_000_000, nextPtsUs: 1_020_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 1_070_000, previousPtsUs: 1_060_000, nextPtsUs: 1_080_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 1_150_000, previousPtsUs: 1_140_000, nextPtsUs: 1_160_000)
        ]
        return TemporalJumpDraft(sessionKey: sessionKey, ownerID: ownerID, protocolKey: .cmj, side: nil,
            dropHeightCm: nil, recordedAt: Date(timeIntervalSince1970: 1_700_000_000), notes: "manual note",
            source: source, sourceFrameCount: 12, sourceOriginUs: 1_000_000,
            temporalState: .realtimeDeclared, events: marks)
    }

    private func execute(_ url: URL, _ sql: String) throws {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { if let db { sqlite3_close(db) } }
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
    }

    private func scalarDouble(_ url: URL, _ sql: String) throws -> Double {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { if let db { sqlite3_close(db) } }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(db, sql, -1, &statement, nil), SQLITE_OK)
        defer { if let statement { sqlite3_finalize(statement) } }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
        return sqlite3_column_double(statement, 0)
    }

    private func scalar(_ url: URL, _ sql: String) throws -> Int {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { if let db { sqlite3_close(db) } }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(db, sql, -1, &statement, nil), SQLITE_OK)
        defer { if let statement { sqlite3_finalize(statement) } }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
        return Int(sqlite3_column_int64(statement, 0))
    }

    private func legacyDatabase(version: Int) throws -> (URL, UUID, UUID, UUID) {
        let url = temporaryURL()
        let athleteID = UUID(), ownedID = UUID(), unownedID = UUID()
        let avatarColumn = version >= 2 ? ", avatar_key TEXT" : ""
        let avatarValue = version >= 2 ? ", 'avatar_legacy'" : ""
        let avatarInsertColumns = version >= 2 ? ", avatar_key" : ""
        let sql = """
            CREATE TABLE athletes (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,weight_kg REAL,height_cm REAL,notes TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL,archived_at REAL\(avatarColumn));
            INSERT INTO athletes(id,name,weight_kg,height_cm,notes,created_at,updated_at,archived_at\(avatarInsertColumns)) VALUES('\(athleteID.uuidString)','Legacy',70,180,'athlete note',10,11,NULL\(avatarValue));
            CREATE TABLE assessments (id TEXT PRIMARY KEY NOT NULL,session_key TEXT NOT NULL UNIQUE,owner_id TEXT,protocol_key TEXT NOT NULL,side TEXT,drop_height_cm REAL,recorded_at REAL NOT NULL,notes TEXT);
            CREATE TABLE attempt_metrics (assessment_id TEXT NOT NULL,metric_key TEXT NOT NULL,unit TEXT NOT NULL,value REAL NOT NULL,ordinal INTEGER NOT NULL);
            INSERT INTO assessments VALUES('\(ownedID.uuidString)','legacy-owned','\(athleteID.uuidString)','CMJ',NULL,NULL,20,'owned note');
            INSERT INTO assessments VALUES('\(unownedID.uuidString)','legacy-unowned',NULL,'SJ',NULL,NULL,21,'unowned note');
            INSERT INTO attempt_metrics VALUES('\(ownedID.uuidString)','HEIGHT_CM','CENTIMETER',31,0);
            INSERT INTO attempt_metrics VALUES('\(unownedID.uuidString)','HEIGHT_CM','CENTIMETER',27,0);
            PRAGMA user_version=\(version);
            """
        try execute(url, sql)
        return (url, athleteID, ownedID, unownedID)
    }

    func testFreshSchemaPersistsTemporalGraphAndRetryDoesNotReplaceIt() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Athlete")
        let original = draft(ownerID: athlete.id, sessionKey: "same-session", source: .photos)
        let saved = try await store.saveTemporalJump(original)
        XCTAssertEqual(saved.ownerID, athlete.id)
        XCTAssertEqual(saved.notes, "manual note")
        XCTAssertEqual(saved.metrics.map(\.key), ["HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"])
        let graph = try await store.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(graph?.source, .photos)
        XCTAssertEqual(graph?.sourceFrameCount, 12)
        XCTAssertEqual(graph?.sourceOriginUs, 1_000_000)
        XCTAssertEqual(graph?.analysisVersion, 1)
        XCTAssertEqual(graph?.events, original.events)

        let changedMarks = [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 1_010_000, previousPtsUs: 1_000_000, nextPtsUs: 1_020_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 1_080_000, previousPtsUs: 1_070_000, nextPtsUs: 1_090_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 1_160_000, previousPtsUs: 1_150_000, nextPtsUs: 1_170_000)
        ]
        let retry = try await store.saveTemporalJump(draft(ownerID: athlete.id, sessionKey: "same-session", source: .files, events: changedMarks))
        XCTAssertEqual(retry.id, saved.id)
        let retriedGraph = try await store.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(retriedGraph?.events, original.events)

        let fileDraft = draft(ownerID: athlete.id, sessionKey: "files-source", source: .files)
        let fileSaved = try await store.saveTemporalJump(fileDraft)
        let fileGraph = try await store.temporalAnalysis(measurementID: fileSaved.id)
        XCTAssertEqual(fileGraph?.source.rawValue, "FILES")
        XCTAssertEqual(fileGraph?.events.map(\.kind.rawValue), ["MOVEMENT_START", "TAKEOFF", "LANDING"])
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 3)
    }

    func testDifferentOwnerConflictsAndNewSaveRequiresActiveOwner() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let first = try await store.createAthlete(name: "First")
        let second = try await store.createAthlete(name: "Second")
        let originalDraft = draft(ownerID: first.id, sessionKey: " owned ")
        let saved = try await store.saveTemporalJump(originalDraft)
        do {
            _ = try await store.saveTemporalJump(draft(ownerID: second.id, sessionKey: "owned"))
            XCTFail("a confirmed session cannot change owners")
        } catch { XCTAssertEqual(error as? StoreError, .ownerConflict) }
        try await store.setArchived(first.id, archived: true)
        let archivedRetry = try await store.saveTemporalJump(originalDraft)
        XCTAssertEqual(archivedRetry.id, saved.id)
        XCTAssertEqual(archivedRetry.metrics, saved.metrics)
        let archivedGraph = try await store.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(archivedGraph?.events, originalDraft.events)
        do {
            _ = try await store.saveTemporalJump(draft(ownerID: first.id, sessionKey: "new-session"))
            XCTFail("a new temporal measurement requires an active owner")
        } catch { XCTAssertEqual(error as? StoreError, .inactiveOwner) }
        let retainedGraph = try await store.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(retainedGraph?.events, draft(ownerID: first.id, sessionKey: "owned").events)
    }

    func testVersionOneAndTwoMigrateAdditivelyToThree() async throws {
        for version in [1, 2] {
            let (url, athleteID, ownedID, unownedID) = try legacyDatabase(version: version)
            defer { try? FileManager.default.removeItem(at: url) }
            let store = try SQLiteStore(databaseURL: url)
            let roster = try await store.athletes()
            let athlete = try XCTUnwrap(roster.first)
            XCTAssertEqual(athlete.id, athleteID)
            XCTAssertEqual(athlete.weightKg, 70)
            XCTAssertEqual(athlete.heightCm, 180)
            XCTAssertEqual(athlete.notes, "athlete note")
            XCTAssertEqual(athlete.avatarKey, version == 1 ? nil : "avatar_legacy")
            let page = try await store.history()
            XCTAssertEqual(page.items.count, 2)
            XCTAssertEqual(page.items.first(where: { $0.id == ownedID })?.notes, "owned note")
            XCTAssertEqual(page.items.first(where: { $0.id == ownedID })?.metrics.first?.value, 31)
            XCTAssertNil(page.items.first(where: { $0.id == unownedID })?.ownerID)
            let absentGraph = try await store.temporalAnalysis(measurementID: ownedID)
            XCTAssertNil(absentGraph, "legacy rows have no fabricated graph")
            XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 3)
        }
    }

    func testTemporalWritesRollbackAsOneUnit() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Athlete")
        try execute(url, "CREATE TRIGGER reject_temporal BEFORE INSERT ON assessment_analysis BEGIN SELECT RAISE(ABORT,'injected failure'); END;")
        do {
            _ = try await store.saveTemporalJump(draft(ownerID: athlete.id, sessionKey: "rollback"))
            XCTFail("injected metadata failure must abort the graph")
        } catch { XCTAssertTrue(error is StoreError) }
        let emptyHistory = try await store.history()
        XCTAssertTrue(emptyHistory.items.isEmpty)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM attempt_metrics"), 0)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_events"), 0)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_analysis"), 0)
    }

    func testFiniteTamperedMetricFailsGraphReadAndIdempotentRetryWithoutRepair() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Athlete")
        let original = draft(ownerID: athlete.id, sessionKey: "tampered-metric")
        let saved = try await store.saveTemporalJump(original)
        try execute(url, "UPDATE attempt_metrics SET value=value+0.25 WHERE assessment_id='\(saved.id.uuidString)' AND metric_key='HEIGHT_CM'")

        do {
            _ = try await store.temporalAnalysis(measurementID: saved.id)
            XCTFail("finite metric tampering must invalidate a present temporal graph")
        } catch { XCTAssertTrue(error is StoreError) }
        do {
            _ = try await store.saveTemporalJump(original)
            XCTFail("an idempotent retry must fail closed rather than accept or repair corrupt metrics")
        } catch { XCTAssertTrue(error is StoreError) }
        XCTAssertEqual(try scalarDouble(url, "SELECT value FROM attempt_metrics WHERE assessment_id='\(saved.id.uuidString)' AND metric_key='HEIGHT_CM'"),
                       saved.metrics[0].value + 0.25, accuracy: 1e-12)
    }

    func testPresentCorruptGraphFailsClosedAndDeleteCascades() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Athlete")
        let saved = try await store.saveTemporalJump(draft(ownerID: athlete.id, sessionKey: "corrupt"))
        try execute(url, "UPDATE assessment_events SET next_pts_us=0 WHERE assessment_id='\(saved.id.uuidString)' AND ordinal=0")
        do {
            _ = try await store.temporalAnalysis(measurementID: saved.id)
            XCTFail("a present but invalid graph must not appear absent")
        } catch { XCTAssertTrue(error is StoreError) }

        // A separate valid graph verifies foreign-key cleanup as well.
        let clean = try await store.saveTemporalJump(draft(ownerID: athlete.id, sessionKey: "cascade"))
        try await store.deleteMeasurement(id: clean.id)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_events WHERE assessment_id='\(clean.id.uuidString)'"), 0)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_analysis WHERE assessment_id='\(clean.id.uuidString)'"), 0)
        do {
            _ = try await store.temporalAnalysis(measurementID: clean.id)
            XCTFail("missing measurement must not be reported as a legacy graph")
        } catch { XCTAssertEqual(error as? StoreError, .notFound) }
    }
}
