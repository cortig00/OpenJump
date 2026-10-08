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

    // Owned CAMERA-slice fixture helpers: render production metric/event rows
    // as literal SQL value lists. Each test's table DDL stays a literal
    // historical schema; these helpers only format row values.
    private func metricInserts(_ id: UUID, _ metrics: [SavedMetric]) -> String {
        metrics.map { metric in
            "INSERT INTO attempt_metrics VALUES('\(id.uuidString)','\(metric.key)','\(metric.unit)',\(metric.value),\(metric.ordinal));"
        }.joined(separator: "\n")
    }

    private func eventInserts(_ id: UUID, _ events: [JumpEventMark]) -> String {
        events.enumerated().map { ordinal, event in
            let previous = event.previousPtsUs.map(String.init) ?? "NULL"
            let next = event.nextPtsUs.map(String.init) ?? "NULL"
            return "INSERT INTO assessment_events VALUES('\(id.uuidString)','\(event.kind.rawValue)',\(ordinal),\(event.frameIndex),\(event.ptsUs),\(previous),\(next));"
        }.joined(separator: "\n")
    }

    // Row counter for zero-row-safe PRAGMA reads (foreign_key_check,
    // table_info): aggregates are avoided so the statement form matches
    // production usage exactly. scalar(_:_:) would record a failure on an
    // empty result because it expects exactly one SQLITE_ROW.
    private func rowCount(_ url: URL, _ sql: String) throws -> Int {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { if let db { sqlite3_close(db) } }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(db, sql, -1, &statement, nil), SQLITE_OK)
        defer { if let statement { sqlite3_finalize(statement) } }
        var count = 0
        while true {
            let code = sqlite3_step(statement)
            if code == SQLITE_ROW { count += 1; continue }
            XCTAssertEqual(code, SQLITE_DONE)
            break
        }
        return count
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
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 4)
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

    func testVersionOneAndTwoMigrateAdditivelyToFour() async throws {
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
            XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 4)
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

    func testCameraMigrationPreservesHistoricalV3RowsGraphsAndIndexes() async throws {
        // OWNED historical v3 fixture: literal v3 DDL with the PHOTOS/FILES-only
        // CHECK, never a fresh v4 database restamped to version 3. Covers two
        // owned graphs, owned/unowned legacy rows without graphs, NULL/empty/
        // nonempty notes, raw IDs/session keys, avatar, archive and dates.
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let athleteID = UUID(), archivedID = UUID()
        let photosID = UUID(), filesID = UUID(), ownedLegacyID = UUID(), unownedLegacyID = UUID()
        let photosDraft = draft(ownerID: athleteID, sessionKey: "camera-migration-photos", source: .photos)
        let filesDraft = draft(ownerID: athleteID, sessionKey: "camera-migration-files", source: .files)
        let photosMetrics = try TemporalJumpEngine.calculate(draft: photosDraft)
        let filesMetrics = try TemporalJumpEngine.calculate(draft: filesDraft)
        let sql = """
            CREATE TABLE athletes (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 120),weight_kg REAL CHECK(weight_kg IS NULL OR weight_kg > 0),height_cm REAL CHECK(height_cm IS NULL OR height_cm > 0),notes TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL,archived_at REAL,avatar_key TEXT);
            INSERT INTO athletes VALUES('\(athleteID.uuidString)','Owner',70,180,'owner note',10,11,NULL,'avatar_legacy');
            INSERT INTO athletes VALUES('\(archivedID.uuidString)','Archived',NULL,NULL,NULL,12,13,14,'avatar_legacy');
            CREATE TABLE assessments (id TEXT PRIMARY KEY NOT NULL,session_key TEXT NOT NULL UNIQUE,owner_id TEXT REFERENCES athletes(id) ON DELETE RESTRICT,protocol_key TEXT NOT NULL CHECK(protocol_key IN ('CMJ','SJ','ABALAKOV','UNILATERAL','DROP_JUMP','HORIZONTAL','ASYMMETRY')),side TEXT,drop_height_cm REAL CHECK(drop_height_cm IS NULL OR drop_height_cm > 0),recorded_at REAL NOT NULL,notes TEXT);
            CREATE INDEX athletes_active_name ON athletes(archived_at, name COLLATE NOCASE);
            CREATE INDEX assessments_history ON assessments(recorded_at DESC, id DESC);
            CREATE INDEX assessments_owner_history ON assessments(owner_id, recorded_at DESC, id DESC);
            CREATE TABLE attempt_metrics (assessment_id TEXT NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,metric_key TEXT NOT NULL,unit TEXT NOT NULL,value REAL NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal >= 0),PRIMARY KEY(assessment_id, ordinal),UNIQUE(assessment_id, metric_key));
            INSERT INTO assessments VALUES('\(photosID.uuidString)','camera-migration-photos','\(athleteID.uuidString)','CMJ',NULL,NULL,20,'photo note');
            INSERT INTO assessments VALUES('\(filesID.uuidString)','camera-migration-files','\(athleteID.uuidString)','CMJ',NULL,NULL,21,'');
            INSERT INTO assessments VALUES('\(ownedLegacyID.uuidString)','camera-migration-owned-legacy','\(athleteID.uuidString)','SJ',NULL,NULL,22,NULL);
            INSERT INTO assessments VALUES('\(unownedLegacyID.uuidString)','camera-migration-unowned-legacy',NULL,'SJ',NULL,NULL,23,'unowned note');
            \(metricInserts(photosID, photosMetrics))
            \(metricInserts(filesID, filesMetrics))
            INSERT INTO attempt_metrics VALUES('\(ownedLegacyID.uuidString)','HEIGHT_CM','CENTIMETER',27,0);
            INSERT INTO attempt_metrics VALUES('\(unownedLegacyID.uuidString)','HEIGHT_CM','CENTIMETER',29,0);
            CREATE TABLE assessment_events (assessment_id TEXT NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,event_key TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal >= 0),frame_index INTEGER NOT NULL CHECK(frame_index >= 0),pts_us INTEGER NOT NULL CHECK(pts_us >= 0),previous_pts_us INTEGER CHECK(previous_pts_us IS NULL OR previous_pts_us >= 0),next_pts_us INTEGER CHECK(next_pts_us IS NULL OR next_pts_us >= 0),PRIMARY KEY(assessment_id, ordinal),UNIQUE(assessment_id, event_key));
            CREATE TABLE assessment_analysis (assessment_id TEXT PRIMARY KEY NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,source_kind TEXT NOT NULL CHECK(source_kind IN ('PHOTOS','FILES')),source_frame_count INTEGER NOT NULL CHECK(source_frame_count BETWEEN 2 AND 250000),source_origin_us INTEGER NOT NULL CHECK(source_origin_us >= 0),temporal_state TEXT NOT NULL CHECK(temporal_state IN ('UNKNOWN','REALTIME_DECLARED')),analysis_version INTEGER NOT NULL CHECK(analysis_version = 1));
            INSERT INTO assessment_analysis VALUES('\(photosID.uuidString)','PHOTOS',12,1000000,'REALTIME_DECLARED',1);
            INSERT INTO assessment_analysis VALUES('\(filesID.uuidString)','FILES',12,1000000,'REALTIME_DECLARED',1);
            \(eventInserts(photosID, photosDraft.events))
            \(eventInserts(filesID, filesDraft.events))
            PRAGMA user_version=3;
            """
        try execute(url, sql)

        let store = try SQLiteStore(databaseURL: url)
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 4)
        let roster = try await store.athletes(includeArchived: true)
        XCTAssertEqual(roster.count, 2)
        let owner = try XCTUnwrap(roster.first { $0.id == athleteID })
        XCTAssertEqual(owner.name, "Owner")
        XCTAssertEqual(owner.weightKg, 70)
        XCTAssertEqual(owner.heightCm, 180)
        XCTAssertEqual(owner.notes, "owner note")
        XCTAssertEqual(owner.avatarKey, "avatar_legacy")
        XCTAssertNil(owner.archivedAt)
        let archived = try XCTUnwrap(roster.first { $0.id == archivedID })
        XCTAssertEqual(archived.archivedAt, Date(timeIntervalSince1970: 14))
        XCTAssertEqual(archived.avatarKey, "avatar_legacy")
        let page = try await store.history()
        XCTAssertEqual(page.items.count, 4)
        let items = Dictionary(uniqueKeysWithValues: page.items.map { ($0.id, $0) })
        XCTAssertEqual(items[photosID]?.sessionKey, "camera-migration-photos")
        XCTAssertEqual(items[photosID]?.notes, "photo note")
        XCTAssertEqual(items[photosID]?.metrics, photosMetrics)
        // Raw empty text stays empty in storage; the read model normalizes it.
        XCTAssertNil(items[filesID]?.notes)
        XCTAssertEqual(items[filesID]?.metrics, filesMetrics)
        XCTAssertNil(items[ownedLegacyID]?.notes)
        XCTAssertEqual(items[unownedLegacyID]?.sessionKey, "camera-migration-unowned-legacy")
        XCTAssertEqual(items[unownedLegacyID]?.notes, "unowned note")
        let photosGraph = try await store.temporalAnalysis(measurementID: photosID)
        XCTAssertEqual(photosGraph?.source, .photos)
        XCTAssertEqual(photosGraph?.sourceFrameCount, 12)
        XCTAssertEqual(photosGraph?.sourceOriginUs, 1_000_000)
        XCTAssertEqual(photosGraph?.temporalState, .realtimeDeclared)
        XCTAssertEqual(photosGraph?.analysisVersion, 1)
        XCTAssertEqual(photosGraph?.events, photosDraft.events)
        let filesGraph = try await store.temporalAnalysis(measurementID: filesID)
        XCTAssertEqual(filesGraph?.source, .files)
        XCTAssertEqual(filesGraph?.events, filesDraft.events)
        let ownedLegacyGraph = try await store.temporalAnalysis(measurementID: ownedLegacyID)
        XCTAssertNil(ownedLegacyGraph, "legacy rows have no fabricated graph")
        let unownedLegacyGraph = try await store.temporalAnalysis(measurementID: unownedLegacyID)
        XCTAssertNil(unownedLegacyGraph, "legacy rows have no fabricated graph")
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_analysis"), 2)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_events"), 6)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN ('athletes_active_name','assessments_history','assessments_owner_history')"), 3)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND tbl_name='assessment_analysis'"), 1)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='assessment_analysis_new'"), 0)
        XCTAssertEqual(try rowCount(url, "PRAGMA foreign_key_check"), 0)
        // The migrated database exports through the actual current pipeline.
        let snapshot = try await store.exportData(format: .jsonBackup)
        XCTAssertEqual(snapshot.profileCount, 2)
        XCTAssertEqual(snapshot.measurementCount, 4)
        XCTAssertEqual(snapshot.metricCount, 12)
        let decoded = try JSONSerialization.jsonObject(with: snapshot.data)
        let json = try XCTUnwrap(decoded as? [String: Any])
        XCTAssertEqual(json["sourceSchemaVersion"] as? Int, 4)
        let roots = try XCTUnwrap(json["measurements"] as? [[String: Any]])
        let bySession = Dictionary(uniqueKeysWithValues: try roots.map { (try XCTUnwrap($0["sessionKey"] as? String), $0) })
        XCTAssertEqual(bySession["camera-migration-photos"]?["notes"] as? String, "photo note")
        XCTAssertEqual(bySession["camera-migration-files"]?["notes"] as? String, "")
        XCTAssertTrue(bySession["camera-migration-owned-legacy"]?["notes"] is NSNull)
        XCTAssertEqual((bySession["camera-migration-photos"]?["analysis"] as? [String: Any])?["source"] as? String, "PHOTOS")
        // Repeated reopen at version 4 is a no-op preserving every graph.
        let reopened = try SQLiteStore(databaseURL: url)
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 4)
        let reopenedPhotosGraph = try await reopened.temporalAnalysis(measurementID: photosID)
        XCTAssertEqual(reopenedPhotosGraph?.events, photosDraft.events)
        let reopenedFilesGraph = try await reopened.temporalAnalysis(measurementID: filesID)
        XCTAssertEqual(reopenedFilesGraph?.source, .files)
    }

    func testFreshCameraSourcePersistsGraphAndCannotBeReplaced() async throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 4)
        let athlete = try await store.createAthlete(name: "Athlete")
        let original = draft(ownerID: athlete.id, sessionKey: "camera-session", source: .camera)
        let saved = try await store.saveTemporalJump(original)
        let graph = try await store.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(graph?.source, .camera)
        XCTAssertEqual(graph?.source.rawValue, "CAMERA")
        XCTAssertEqual(graph?.sourceFrameCount, 12)
        XCTAssertEqual(graph?.sourceOriginUs, 1_000_000)
        XCTAssertEqual(graph?.analysisVersion, 1)
        XCTAssertEqual(graph?.events, original.events)
        // Canonical shared metrics are source-independent: the same marks
        // through PHOTOS compute the identical metric array.
        XCTAssertEqual(saved.metrics, try TemporalJumpEngine.calculate(draft: draft(ownerID: athlete.id, sessionKey: "photo-twin", source: .photos)))
        XCTAssertEqual(saved.metrics.map(\.key), ["HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"])
        // A retry on the same session with changed provenance and marks
        // cannot replace the confirmed graph or its identity.
        let changedMarks = [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 1_010_000, previousPtsUs: 1_000_000, nextPtsUs: 1_020_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 1_080_000, previousPtsUs: 1_070_000, nextPtsUs: 1_090_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 1_160_000, previousPtsUs: 1_150_000, nextPtsUs: 1_170_000)
        ]
        let retry = try await store.saveTemporalJump(draft(ownerID: athlete.id, sessionKey: "camera-session", source: .files, events: changedMarks))
        XCTAssertEqual(retry.id, saved.id)
        let persistedGraph = try await store.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(persistedGraph?.source, .camera)
        XCTAssertEqual(persistedGraph?.events, original.events)
        // No extra media/path/URI columns on the canonical analysis table.
        XCTAssertEqual(try rowCount(url, "PRAGMA table_info(assessment_analysis)"), 6)
        // Owner-conflict and inactive-owner policies are unchanged.
        let second = try await store.createAthlete(name: "Second")
        do {
            _ = try await store.saveTemporalJump(draft(ownerID: second.id, sessionKey: "camera-session"))
            XCTFail("a confirmed session cannot change owners")
        } catch { XCTAssertEqual(error as? StoreError, .ownerConflict) }
        try await store.setArchived(athlete.id, archived: true)
        do {
            _ = try await store.saveTemporalJump(draft(ownerID: athlete.id, sessionKey: "camera-after-archive"))
            XCTFail("a new temporal measurement requires an active owner")
        } catch { XCTAssertEqual(error as? StoreError, .inactiveOwner) }
        let reopened = try SQLiteStore(databaseURL: url)
        let reopenedCameraGraph = try await reopened.temporalAnalysis(measurementID: saved.id)
        XCTAssertEqual(reopenedCameraGraph?.source, .camera)
    }

    func testCameraMigrationCollisionFailsClosedWithoutPartialSchema() async throws {
        // OWNED disposable v3 fixture plus an owned probe occupying the
        // production replacement-table name, so the migration must abort.
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let athleteID = UUID(), measurementID = UUID()
        let valid = draft(ownerID: athleteID, sessionKey: "collision-session", source: .photos)
        let metrics = try TemporalJumpEngine.calculate(draft: valid)
        let sql = """
            CREATE TABLE athletes (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,weight_kg REAL,height_cm REAL,notes TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL,archived_at REAL,avatar_key TEXT);
            INSERT INTO athletes VALUES('\(athleteID.uuidString)','Owner',70,180,NULL,10,11,NULL,'avatar_legacy');
            CREATE TABLE assessments (id TEXT PRIMARY KEY NOT NULL,session_key TEXT NOT NULL UNIQUE,owner_id TEXT REFERENCES athletes(id) ON DELETE RESTRICT,protocol_key TEXT NOT NULL,side TEXT,drop_height_cm REAL,recorded_at REAL NOT NULL,notes TEXT);
            CREATE TABLE attempt_metrics (assessment_id TEXT NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,metric_key TEXT NOT NULL,unit TEXT NOT NULL,value REAL NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(assessment_id, ordinal),UNIQUE(assessment_id, metric_key));
            INSERT INTO assessments VALUES('\(measurementID.uuidString)','collision-session','\(athleteID.uuidString)','CMJ',NULL,NULL,20,NULL);
            \(metricInserts(measurementID, metrics))
            CREATE TABLE assessment_events (assessment_id TEXT NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,event_key TEXT NOT NULL,ordinal INTEGER NOT NULL,frame_index INTEGER NOT NULL,pts_us INTEGER NOT NULL,previous_pts_us INTEGER,next_pts_us INTEGER,PRIMARY KEY(assessment_id, ordinal),UNIQUE(assessment_id, event_key));
            CREATE TABLE assessment_analysis (assessment_id TEXT PRIMARY KEY NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,source_kind TEXT NOT NULL CHECK(source_kind IN ('PHOTOS','FILES')),source_frame_count INTEGER NOT NULL CHECK(source_frame_count BETWEEN 2 AND 250000),source_origin_us INTEGER NOT NULL CHECK(source_origin_us >= 0),temporal_state TEXT NOT NULL CHECK(temporal_state IN ('UNKNOWN','REALTIME_DECLARED')),analysis_version INTEGER NOT NULL CHECK(analysis_version = 1));
            INSERT INTO assessment_analysis VALUES('\(measurementID.uuidString)','PHOTOS',12,1000000,'REALTIME_DECLARED',1);
            \(eventInserts(measurementID, valid.events))
            CREATE TABLE assessment_analysis_new(id INTEGER PRIMARY KEY);
            PRAGMA user_version=3;
            """
        try execute(url, sql)
        do {
            _ = try SQLiteStore(databaseURL: url)
            XCTFail("a colliding replacement table must abort the camera migration")
        } catch { XCTAssertTrue(error is StoreError) }
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 3)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessments"), 1)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM attempt_metrics"), metrics.count)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_analysis"), 1)
        XCTAssertEqual(try scalar(url, "SELECT COUNT(*) FROM assessment_events"), 3)
        XCTAssertEqual(try rowCount(url, "PRAGMA foreign_key_check"), 0)
        // Unknown provenance is rejected by the preserved v3 CHECK and the Swift enum.
        XCTAssertNil(JumpVideoSource(rawValue: "UNKNOWN"))
        var raw: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &raw), SQLITE_OK)
        defer { if let raw { sqlite3_close(raw) } }
        guard let raw else { XCTFail("fixture database must open"); return }
        XCTAssertNotEqual(sqlite3_exec(raw, "INSERT INTO assessment_analysis VALUES('\(UUID().uuidString)','CAMERA',12,1000000,'REALTIME_DECLARED',1);", nil, nil, nil), SQLITE_OK)
        // Removing ONLY the owned probe unblocks the migration; preserved rows migrate.
        try execute(url, "DROP TABLE assessment_analysis_new;")
        let recovered = try SQLiteStore(databaseURL: url)
        XCTAssertEqual(try scalar(url, "PRAGMA user_version"), 4)
        let recoveredGraph = try await recovered.temporalAnalysis(measurementID: measurementID)
        XCTAssertEqual(recoveredGraph?.source, .photos)
        XCTAssertEqual(recoveredGraph?.events, valid.events)
    }
}
