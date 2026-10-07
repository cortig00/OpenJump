import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

public actor SQLiteStore {

    /// A single, synchronous actor read transaction. No awaited page calls,
    /// normalized roster/history models, DB copying, or restore/write seam.
    func exportData(format: AppleExportFormat, createdAt: Date = Date(),
                    limits: AppleExportLimits = .standard) throws -> AppleExportResult {
        try limits.validate()
        guard createdAt.timeIntervalSince1970.isFinite else { throw AppleDataExportError.invalidSnapshot }
        try Task.checkCancellation()
        try Self.execute(connection, "BEGIN DEFERRED")
        do {
            let counts = try exportAdmissionCounts(limits)
            var remainingTextBytes = limits.maxBytes
            var output = AppleBoundedExportBytes(limit: limits.maxBytes)
            let encoder = AppleDataExport.encoder()
            if format == .jsonBackup {
                try output.append(AppleDataExport.jsonPrefix(createdAt: createdAt))
            } else {
                try output.append(AppleDataExport.csvColumns.joined(separator: ",") + "\r\n")
            }
            var names: [String: String] = [:]
            var profileIDs = Set<UUID>()
            let profiles = try prepare("SELECT id,name,weight_kg,height_cm,notes,created_at,updated_at,archived_at,avatar_key FROM athletes ORDER BY id")
            defer { sqlite3_finalize(profiles) }
            while try exportStep(profiles) {
                let id = try exportRequiredText(profiles, 0, maximum: 36, budget: &remainingTextBytes)
                guard let uuid = UUID(uuidString: id), profileIDs.insert(uuid).inserted else { throw AppleDataExportError.invalidSnapshot }
                let profile = AppleExportProfile(id: id,
                    name: try exportRequiredText(profiles, 1, maximum: 120, budget: &remainingTextBytes),
                    weightKg: try exportOptionalNumber(profiles, 2), heightCm: try exportOptionalNumber(profiles, 3),
                    notes: try exportOptionalText(profiles, 4, maximum: 500, budget: &remainingTextBytes),
                    createdAt: try exportNumber(profiles, 5), updatedAt: try exportNumber(profiles, 6),
                    archivedAt: try exportOptionalNumber(profiles, 7),
                    avatarKey: try exportOptionalText(profiles, 8, maximum: 120, budget: &remainingTextBytes))
                do {
                    _ = try Athlete(id: uuid, name: profile.name, weightKg: profile.weightKg, heightCm: profile.heightCm,
                        notes: profile.notes, createdAt: Date(timeIntervalSince1970: profile.createdAt),
                        updatedAt: Date(timeIntervalSince1970: profile.updatedAt),
                        archivedAt: profile.archivedAt.map { Date(timeIntervalSince1970: $0) }, avatarKey: profile.avatarKey)
                } catch { throw AppleDataExportError.invalidSnapshot }
                if format == .jsonBackup {
                    if !names.isEmpty { try output.append(",") }
                    try output.append(encoder.encode(profile))
                }
                names[id] = profile.name
            }
            guard names.count == counts[0] else { throw AppleDataExportError.invalidSnapshot }
            if format == .jsonBackup { try output.append("],\"measurements\":[") }
            // A streaming SQL cursor retains only one raw root and its children.
            // Unlike history(limit:), no model normalization or UUID case change
            // can invalidate a keyset cursor; ties use the stored raw id.
            let roots = try prepare("SELECT id,session_key,owner_id,protocol_key,side,drop_height_cm,recorded_at,notes FROM assessments ORDER BY recorded_at DESC,id DESC")
            defer { sqlite3_finalize(roots) }
            var rootIDs = Set<UUID>()
            var metricCount = 0, eventCount = 0, analysisCount = 0
            while try exportStep(roots) {
                let id = try exportRequiredText(roots, 0, maximum: 36, budget: &remainingTextBytes)
                guard let uuid = UUID(uuidString: id), rootIDs.insert(uuid).inserted else { throw AppleDataExportError.invalidSnapshot }
                let session = try exportRequiredText(roots, 1, maximum: 240, budget: &remainingTextBytes)
                let owner = try exportOptionalText(roots, 2, maximum: 36, budget: &remainingTextBytes)
                if let owner {
                    guard UUID(uuidString: owner) != nil, names[owner] != nil else { throw AppleDataExportError.invalidSnapshot }
                }
                let protocolRaw = try exportRequiredText(roots, 3, maximum: 120, budget: &remainingTextBytes)
                guard let protocolKey = SavedProtocol(rawValue: protocolRaw) else { throw AppleDataExportError.invalidSnapshot }
                let side = try exportOptionalText(roots, 4, maximum: 80, budget: &remainingTextBytes)
                let drop = try exportOptionalNumber(roots, 5)
                let date = try exportNumber(roots, 6)
                let notes = try exportOptionalText(roots, 7, maximum: 500, budget: &remainingTextBytes)
                let metrics = try exportMetrics(id, budget: &remainingTextBytes)
                let graph = try exportAnalysis(id, budget: &remainingTextBytes)
                do {
                    _ = try SavedMeasurement(id: uuid, sessionKey: session, ownerID: owner.flatMap(UUID.init(uuidString:)),
                        protocolKey: protocolKey, side: side, dropHeightCm: drop,
                        recordedAt: Date(timeIntervalSince1970: date), notes: notes, metrics: metrics)
                    if let graph {
                        guard let ownerID = owner.flatMap(UUID.init(uuidString:)) else { throw AppleDataExportError.invalidSnapshot }
                        try Self.validateTemporalMetrics(metrics, protocolKey: protocolKey)
                        let draft = TemporalJumpDraft(sessionKey: session, ownerID: ownerID, protocolKey: protocolKey,
                            side: side, dropHeightCm: drop, recordedAt: Date(timeIntervalSince1970: date), notes: notes,
                            source: graph.source, sourceFrameCount: graph.sourceFrameCount, sourceOriginUs: graph.sourceOriginUs,
                            temporalState: graph.temporalState, events: graph.events.map(\.mark))
                        try draft.validate()
                        guard try TemporalJumpEngine.calculate(draft: draft) == metrics else { throw AppleDataExportError.invalidSnapshot }
                    }
                } catch { throw AppleDataExportError.invalidSnapshot }
                let item = AppleExportMeasurement(id: id, sessionKey: session, ownerID: owner, protocolKey: protocolKey,
                    side: side, dropHeightCm: drop, recordedAt: date, notes: notes, metrics: metrics, analysis: graph)
                metricCount += metrics.count
                eventCount += graph?.events.count ?? 0
                analysisCount += graph == nil ? 0 : 1
                if format == .jsonBackup {
                    if rootIDs.count > 1 { try output.append(",") }
                    try output.append(encoder.encode(item))
                } else {
                    for metric in metrics {
                        try output.append(AppleDataExport.csvRow(item, metric: metric, ownerName: owner.flatMap { names[$0] }))
                    }
                }
            }
            // Also catches orphan children in older schemas without declared FKs.
            guard rootIDs.count == counts[1], metricCount == counts[2],
                  eventCount == counts[3], analysisCount == counts[4] else { throw AppleDataExportError.invalidSnapshot }
            if format == .jsonBackup { try output.append("]}") }
            try Task.checkCancellation()
            try Self.execute(connection, "COMMIT")
            return AppleExportResult(format: format, data: output.data, profileCount: names.count,
                measurementCount: rootIDs.count, metricCount: metricCount, createdAt: createdAt)
        } catch {
            try? Self.execute(connection, "ROLLBACK")
            throw error
        }
    }

    private func exportAdmissionCounts(_ limits: AppleExportLimits) throws -> [Int] {
        guard try Self.scalarInt(connection, "PRAGMA user_version") == 3 else { throw AppleDataExportError.invalidSnapshot }
        let queries = [
            ("SELECT COUNT(*) FROM athletes", limits.profiles),
            ("SELECT COUNT(*) FROM assessments", limits.measurements),
            ("SELECT COUNT(*) FROM attempt_metrics", limits.metrics),
            ("SELECT COUNT(*) FROM assessment_events", limits.events),
            ("SELECT COUNT(*) FROM assessment_analysis", limits.analyses)
        ]
        var counts: [Int] = []
        for (sql, limit) in queries {
            let stmt = try prepare(sql)
            defer { sqlite3_finalize(stmt) }
            guard try exportStep(stmt) else { throw AppleDataExportError.invalidSnapshot }
            let count = try exportInteger(stmt, 0)
            guard count >= 0, count <= Int64(limit), let exact = Int(exactly: count) else { throw AppleDataExportError.tooLarge }
            counts.append(exact)
        }
        let foreignKeys = try prepare("PRAGMA foreign_key_check")
        defer { sqlite3_finalize(foreignKeys) }
        guard try !exportStep(foreignKeys) else { throw AppleDataExportError.invalidSnapshot }
        return counts
    }

    private func exportStep(_ stmt: OpaquePointer) throws -> Bool {
        try Task.checkCancellation()
        let code = sqlite3_step(stmt)
        try check(code)
        return code == SQLITE_ROW
    }

    private func exportOptionalText(_ stmt: OpaquePointer, _ index: Int32, maximum: Int,
                                    budget: inout Int) throws -> String? {
        if sqlite3_column_type(stmt, index) == SQLITE_NULL { return nil }
        guard sqlite3_column_type(stmt, index) == SQLITE_TEXT else { throw AppleDataExportError.invalidSnapshot }
        let count = Int(sqlite3_column_bytes(stmt, index))
        // Admission bounds total raw UTF-8 too, before allocating models. This
        // and a 64KiB per-field safety cap prevent pathological grapheme strings
        // from exhausting memory despite small String.count values.
        guard count >= 0, count <= 65_536, count <= budget else { throw AppleDataExportError.tooLarge }
        guard let bytes = sqlite3_column_text(stmt, index),
              let value = String(data: Data(bytes: bytes, count: count), encoding: .utf8),
              value.count <= maximum, !value.contains("\0") else { throw AppleDataExportError.invalidSnapshot }
        budget -= count
        return value
    }

    private func exportRequiredText(_ stmt: OpaquePointer, _ index: Int32, maximum: Int,
                                    budget: inout Int) throws -> String {
        guard let value = try exportOptionalText(stmt, index, maximum: maximum, budget: &budget) else {
            throw AppleDataExportError.invalidSnapshot
        }
        return value
    }

    private func exportNumber(_ stmt: OpaquePointer, _ index: Int32) throws -> Double {
        guard sqlite3_column_type(stmt, index) == SQLITE_FLOAT || sqlite3_column_type(stmt, index) == SQLITE_INTEGER else {
            throw AppleDataExportError.invalidSnapshot
        }
        let number = sqlite3_column_double(stmt, index)
        guard number.isFinite else { throw AppleDataExportError.invalidSnapshot }
        return number
    }

    private func exportOptionalNumber(_ stmt: OpaquePointer, _ index: Int32) throws -> Double? {
        if sqlite3_column_type(stmt, index) == SQLITE_NULL { return nil }
        return try exportNumber(stmt, index)
    }

    private func exportInteger(_ stmt: OpaquePointer, _ index: Int32) throws -> Int64 {
        guard sqlite3_column_type(stmt, index) == SQLITE_INTEGER else { throw AppleDataExportError.invalidSnapshot }
        return sqlite3_column_int64(stmt, index)
    }

    private func exportOptionalInteger(_ stmt: OpaquePointer, _ index: Int32) throws -> Int64? {
        if sqlite3_column_type(stmt, index) == SQLITE_NULL { return nil }
        return try exportInteger(stmt, index)
    }

    private func exportMetrics(_ id: String, budget: inout Int) throws -> [SavedMetric] {
        let stmt = try prepare("SELECT metric_key,unit,value,ordinal FROM attempt_metrics WHERE assessment_id=? ORDER BY ordinal")
        defer { sqlite3_finalize(stmt) }
        text(stmt, 1, id)
        var metrics: [SavedMetric] = []
        var keys = Set<String>(), ordinals = Set<Int>()
        while try exportStep(stmt) {
            let key = try exportRequiredText(stmt, 0, maximum: 120, budget: &budget)
            let unit = try exportRequiredText(stmt, 1, maximum: 80, budget: &budget)
            let value = try exportNumber(stmt, 2)
            guard let ordinal = Int(exactly: try exportInteger(stmt, 3)),
                  keys.insert(key).inserted, ordinals.insert(ordinal).inserted else { throw AppleDataExportError.invalidSnapshot }
            do { metrics.append(try SavedMetric(key: key, unit: unit, value: value, ordinal: ordinal)) }
            catch { throw AppleDataExportError.invalidSnapshot }
        }
        return metrics
    }

    private func exportAnalysis(_ id: String, budget: inout Int) throws -> AppleExportAnalysis? {
        let stmt = try prepare("SELECT source_kind,source_frame_count,source_origin_us,temporal_state,analysis_version FROM assessment_analysis WHERE assessment_id=?")
        defer { sqlite3_finalize(stmt) }
        text(stmt, 1, id)
        let present = try exportStep(stmt)
        var source: JumpVideoSource?, state: JumpTemporalState?
        var frameCount = 0, origin: Int64 = 0, version: Int64 = 0
        if present {
            let sourceRaw = try exportRequiredText(stmt, 0, maximum: 120, budget: &budget)
            let stateRaw = try exportRequiredText(stmt, 3, maximum: 120, budget: &budget)
            source = JumpVideoSource(rawValue: sourceRaw)
            state = JumpTemporalState(rawValue: stateRaw)
            guard source != nil, state != nil, let exactFrames = Int(exactly: try exportInteger(stmt, 1)) else {
                throw AppleDataExportError.invalidSnapshot
            }
            frameCount = exactFrames
            origin = try exportInteger(stmt, 2)
            version = try exportInteger(stmt, 4)
            guard version == 1, try !exportStep(stmt) else { throw AppleDataExportError.invalidSnapshot }
        }
        let eventStmt = try prepare("SELECT event_key,ordinal,frame_index,pts_us,previous_pts_us,next_pts_us FROM assessment_events WHERE assessment_id=? ORDER BY ordinal")
        defer { sqlite3_finalize(eventStmt) }
        text(eventStmt, 1, id)
        var events: [AppleExportEvent] = []
        while try exportStep(eventStmt) {
            let kindRaw = try exportRequiredText(eventStmt, 0, maximum: 120, budget: &budget)
            guard let kind = JumpEventKind(rawValue: kindRaw),
                  let ordinal = Int(exactly: try exportInteger(eventStmt, 1)), ordinal == events.count,
                  let frame = Int(exactly: try exportInteger(eventStmt, 2)) else { throw AppleDataExportError.invalidSnapshot }
            events.append(AppleExportEvent(kind: kind, ordinal: ordinal, frameIndex: frame,
                ptsUs: try exportInteger(eventStmt, 3), previousPtsUs: try exportOptionalInteger(eventStmt, 4),
                nextPtsUs: try exportOptionalInteger(eventStmt, 5)))
        }
        if !present {
            guard events.isEmpty else { throw AppleDataExportError.invalidSnapshot }
            return nil
        }
        guard let source, let state else { throw AppleDataExportError.invalidSnapshot }
        return AppleExportAnalysis(source: source, sourceFrameCount: frameCount, sourceOriginUs: origin,
            temporalState: state, analysisVersion: Int(version), events: events)
    }

    private var db: OpaquePointer?
    public let databaseURL: URL
    private static let schemaVersion = 3

    public init(databaseURL: URL? = nil) throws {
        let url: URL
        if let databaseURL { url = databaseURL }
        else {
            let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
            let directory = root.appendingPathComponent("OpenJump", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var backupExcludedDirectory = directory
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try backupExcludedDirectory.setResourceValues(resourceValues)
            url = directory.appendingPathComponent("openjump.sqlite")
        }
        self.databaseURL = url
        var handle: OpaquePointer?
        let result = sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard result == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unable to open database"
            if let handle { sqlite3_close(handle) }
            throw StoreError.sqlite(message)
        }
        db = handle
        do {
            try Self.execute(handle, "PRAGMA busy_timeout = 2500")
            try Self.execute(handle, "PRAGMA foreign_keys = ON")
            let version = try Self.scalarInt(handle, "PRAGMA user_version")
            guard (0...Self.schemaVersion).contains(version) else { throw StoreError.unsupportedSchema(version) }
            if version == 0 {
                try Self.migrate(handle)
                try Self.migrateTemporalSchema(handle)
            } else if version == 1 {
                try Self.migrateAvatarKey(handle)
                try Self.migrateTemporalSchema(handle)
            } else if version == 2 {
                try Self.migrateTemporalSchema(handle)
            }
        } catch { sqlite3_close(handle); db = nil; throw error }
    }

    deinit { if let db { sqlite3_close(db) } }
    private var connection: OpaquePointer { guard let db else { fatalError("Closed SQLiteStore") }; return db }

    private static func migrate(_ db: OpaquePointer) throws {
        try execute(db, "BEGIN IMMEDIATE")
        do {
            try execute(db, """
                CREATE TABLE athletes (
                  id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 120),
                  weight_kg REAL CHECK(weight_kg IS NULL OR weight_kg > 0),
                  height_cm REAL CHECK(height_cm IS NULL OR height_cm > 0),
                  notes TEXT, created_at REAL NOT NULL, updated_at REAL NOT NULL, archived_at REAL, avatar_key TEXT
                );
                CREATE INDEX athletes_active_name ON athletes(archived_at, name COLLATE NOCASE);
                CREATE TABLE assessments (
                  id TEXT PRIMARY KEY NOT NULL, session_key TEXT NOT NULL UNIQUE,
                  owner_id TEXT REFERENCES athletes(id) ON DELETE RESTRICT,
                  protocol_key TEXT NOT NULL CHECK(protocol_key IN ('CMJ','SJ','ABALAKOV','UNILATERAL','DROP_JUMP','HORIZONTAL','ASYMMETRY')),
                  side TEXT, drop_height_cm REAL CHECK(drop_height_cm IS NULL OR drop_height_cm > 0),
                  recorded_at REAL NOT NULL, notes TEXT
                );
                CREATE INDEX assessments_history ON assessments(recorded_at DESC, id DESC);
                CREATE INDEX assessments_owner_history ON assessments(owner_id, recorded_at DESC, id DESC);
                CREATE TABLE attempt_metrics (
                  assessment_id TEXT NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,
                  metric_key TEXT NOT NULL, unit TEXT NOT NULL, value REAL NOT NULL,
                  ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                  PRIMARY KEY(assessment_id, ordinal), UNIQUE(assessment_id, metric_key)
                );
                PRAGMA user_version = 2;
                """)
            try execute(db, "COMMIT")
        } catch { try? execute(db, "ROLLBACK"); throw error }
    }

    private static func migrateAvatarKey(_ db: OpaquePointer) throws {
        try execute(db, "BEGIN IMMEDIATE")
        do {
            try execute(db, "ALTER TABLE athletes ADD COLUMN avatar_key TEXT; PRAGMA user_version = 2;")
            try execute(db, "COMMIT")
        } catch { try? execute(db, "ROLLBACK"); throw error }
    }

    private static func migrateTemporalSchema(_ db: OpaquePointer) throws {
        try execute(db, "BEGIN IMMEDIATE")
        do {
            try execute(db, """
                CREATE TABLE assessment_events (
                  assessment_id TEXT NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,
                  event_key TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                  frame_index INTEGER NOT NULL CHECK(frame_index >= 0), pts_us INTEGER NOT NULL CHECK(pts_us >= 0),
                  previous_pts_us INTEGER CHECK(previous_pts_us IS NULL OR previous_pts_us >= 0),
                  next_pts_us INTEGER CHECK(next_pts_us IS NULL OR next_pts_us >= 0),
                  PRIMARY KEY(assessment_id, ordinal), UNIQUE(assessment_id, event_key)
                );
                CREATE TABLE assessment_analysis (
                  assessment_id TEXT PRIMARY KEY NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,
                  source_kind TEXT NOT NULL CHECK(source_kind IN ('PHOTOS','FILES')),
                  source_frame_count INTEGER NOT NULL CHECK(source_frame_count BETWEEN 2 AND 250000),
                  source_origin_us INTEGER NOT NULL CHECK(source_origin_us >= 0),
                  temporal_state TEXT NOT NULL CHECK(temporal_state IN ('UNKNOWN','REALTIME_DECLARED')),
                  analysis_version INTEGER NOT NULL CHECK(analysis_version = 1)
                );
                PRAGMA user_version = 3;
                """)
            try execute(db, "COMMIT")
        } catch { try? execute(db, "ROLLBACK"); throw error }
    }

    private static func execute(_ db: OpaquePointer, _ sql: String) throws {
        var message: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &message) == SQLITE_OK else {
            let text = message.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(db))
            sqlite3_free(message); throw StoreError.sqlite(text)
        }
    }
    private static func scalarInt(_ db: OpaquePointer, _ sql: String) throws -> Int {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(db))) }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(db))) }
        return Int(sqlite3_column_int(stmt, 0))
    }
    private func check(_ code: Int32) throws {
        guard code == SQLITE_OK || code == SQLITE_DONE || code == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
    }
    private func prepare(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(connection, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
        return stmt
    }
    private func text(_ stmt: OpaquePointer, _ index: Int32, _ value: String?) {
        guard let value else { sqlite3_bind_null(stmt, index); return }
        value.withCString { sqlite3_bind_text(stmt, index, $0, -1, sqliteTransient) }
    }
    private func optionalDouble(_ stmt: OpaquePointer, _ index: Int32, _ value: Double?) {
        if let value { sqlite3_bind_double(stmt, index, value) } else { sqlite3_bind_null(stmt, index) }
    }
    private func string(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        guard let c = sqlite3_column_text(stmt, index) else { return nil }; return String(cString: c)
    }

    public func createAthlete(name: String, weightKg: Double? = nil, heightCm: Double? = nil, notes: String? = nil,
                              avatarKey: String? = "avatar_frog_jump", now: Date = Date()) throws -> Athlete {
        let athlete = try Athlete(name: name, weightKg: weightKg, heightCm: heightCm, notes: notes, createdAt: now, avatarKey: avatarKey)
        let stmt = try prepare("INSERT INTO athletes(id,name,weight_kg,height_cm,notes,created_at,updated_at,avatar_key) VALUES(?,?,?,?,?,?,?,?)")
        defer { sqlite3_finalize(stmt) }
        text(stmt, 1, athlete.id.uuidString); text(stmt, 2, athlete.name); optionalDouble(stmt, 3, athlete.weightKg)
        optionalDouble(stmt, 4, athlete.heightCm); text(stmt, 5, athlete.notes)
        sqlite3_bind_double(stmt, 6, now.timeIntervalSince1970); sqlite3_bind_double(stmt, 7, now.timeIntervalSince1970); text(stmt, 8, athlete.avatarKey)
        try check(sqlite3_step(stmt)); return athlete
    }

    public func updateAthlete(_ athlete: Athlete, now: Date = Date()) throws -> Athlete {
        let validated = try Athlete(id: athlete.id, name: athlete.name, weightKg: athlete.weightKg, heightCm: athlete.heightCm,
                                    notes: athlete.notes, createdAt: athlete.createdAt, updatedAt: now, archivedAt: athlete.archivedAt,
                                    avatarKey: athlete.avatarKey)
        let stmt = try prepare("UPDATE athletes SET name=?,weight_kg=?,height_cm=?,notes=?,updated_at=?,avatar_key=? WHERE id=?")
        defer { sqlite3_finalize(stmt) }
        text(stmt, 1, validated.name); optionalDouble(stmt, 2, validated.weightKg); optionalDouble(stmt, 3, validated.heightCm)
        text(stmt, 4, validated.notes); sqlite3_bind_double(stmt, 5, now.timeIntervalSince1970); text(stmt, 6, validated.avatarKey); text(stmt, 7, validated.id.uuidString)
        try check(sqlite3_step(stmt)); guard sqlite3_changes(connection) == 1 else { throw StoreError.notFound }; return validated
    }

    public func athletes(includeArchived: Bool = false) throws -> [Athlete] {
        let stmt = try prepare("SELECT id,name,weight_kg,height_cm,notes,created_at,updated_at,archived_at,avatar_key FROM athletes \(includeArchived ? "" : "WHERE archived_at IS NULL") ORDER BY archived_at IS NOT NULL,name COLLATE NOCASE,id")
        defer { sqlite3_finalize(stmt) }
        var result: [Athlete] = []
        while true {
            let code = sqlite3_step(stmt)
            if code == SQLITE_DONE { break }
            guard code == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            guard let id = string(stmt,0).flatMap(UUID.init(uuidString:)), let name = string(stmt,1) else {
                throw StoreError.sqlite("invalid stored athlete identity")
            }
            result.append(try Athlete(id:id,name:name,weightKg:sqlite3_column_type(stmt,2) == SQLITE_NULL ? nil : sqlite3_column_double(stmt,2),
                heightCm:sqlite3_column_type(stmt,3) == SQLITE_NULL ? nil : sqlite3_column_double(stmt,3),notes:string(stmt,4),
                createdAt:Date(timeIntervalSince1970:sqlite3_column_double(stmt,5)),updatedAt:Date(timeIntervalSince1970:sqlite3_column_double(stmt,6)),
                archivedAt:sqlite3_column_type(stmt,7) == SQLITE_NULL ? nil : Date(timeIntervalSince1970:sqlite3_column_double(stmt,7)), avatarKey:string(stmt,8)))
        }
        return result
    }

    public func setArchived(_ id: UUID, archived: Bool, now: Date = Date()) throws {
        guard now.timeIntervalSince1970.isFinite else { throw StoreError.invalidMeasurement }
        try Self.execute(connection, "BEGIN IMMEDIATE")
        do {
            if archived {
                let exists = try prepare("SELECT 1 FROM athletes WHERE id=? AND archived_at IS NULL")
                text(exists,1,id.uuidString); let existsStep = sqlite3_step(exists); sqlite3_finalize(exists)
                guard existsStep == SQLITE_ROW || existsStep == SQLITE_DONE else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
                guard existsStep == SQLITE_ROW else { throw StoreError.notFound }
                let count = try Self.scalarInt(connection, "SELECT count(*) FROM athletes WHERE archived_at IS NULL")
                guard count > 1 else { throw StoreError.lastActiveAthlete }
                let stmt = try prepare("UPDATE athletes SET archived_at=?,updated_at=? WHERE id=? AND archived_at IS NULL")
                defer { sqlite3_finalize(stmt) }; sqlite3_bind_double(stmt,1,now.timeIntervalSince1970); sqlite3_bind_double(stmt,2,now.timeIntervalSince1970); text(stmt,3,id.uuidString)
                try check(sqlite3_step(stmt)); guard sqlite3_changes(connection) == 1 else { throw StoreError.notFound }
            } else {
                let stmt = try prepare("UPDATE athletes SET archived_at=NULL,updated_at=? WHERE id=?")
                defer { sqlite3_finalize(stmt) }; sqlite3_bind_double(stmt,1,now.timeIntervalSince1970); text(stmt,2,id.uuidString)
                try check(sqlite3_step(stmt)); guard sqlite3_changes(connection) == 1 else { throw StoreError.notFound }
            }
            try Self.execute(connection, "COMMIT")
        } catch { try? Self.execute(connection, "ROLLBACK"); throw error }
    }

    public func save(_ measurement: SavedMeasurement) throws -> SavedMeasurement {
        // Codable and mutable notes can bypass initializer checks; validate at the write boundary.
        let measurement = try SavedMeasurement(id: measurement.id, sessionKey: measurement.sessionKey,
            ownerID: measurement.ownerID, protocolKey: measurement.protocolKey, side: measurement.side,
            dropHeightCm: measurement.dropHeightCm, recordedAt: measurement.recordedAt,
            notes: measurement.notes, metrics: measurement.metrics)
        try Self.execute(connection,"BEGIN IMMEDIATE")
        do {
            let prior = try prepare("SELECT id,owner_id FROM assessments WHERE session_key=?")
            text(prior,1,measurement.sessionKey); let step = sqlite3_step(prior)
            if step == SQLITE_ROW {
                let existingID = string(prior,0).flatMap(UUID.init(uuidString:))
                let existingOwner = string(prior,1); sqlite3_finalize(prior)
                if let existingOwner, UUID(uuidString: existingOwner) == nil {
                    throw StoreError.sqlite("invalid stored owner identity")
                }
                guard existingOwner == measurement.ownerID?.uuidString else { throw StoreError.ownerConflict }
                guard let existingID else { throw StoreError.sqlite("invalid stored measurement identity") }
                let stored = try measurementByID(existingID)
                try Self.execute(connection,"COMMIT")
                return stored
            }
            sqlite3_finalize(prior)
            guard step == SQLITE_DONE else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            if let owner = measurement.ownerID { try requireActiveOwner(owner) }
            try insertMeasurementRows(measurement)
            try Self.execute(connection,"COMMIT"); return measurement
        } catch { try? Self.execute(connection,"ROLLBACK"); throw error }
    }

    func saveTemporalJump(_ draft: TemporalJumpDraft) throws -> SavedMeasurement {
        try draft.validate()
        let metrics = try TemporalJumpEngine.calculate(draft: draft)
        try Self.validateTemporalMetrics(metrics, protocolKey: draft.protocolKey)
        let measurement = try SavedMeasurement(sessionKey: draft.sessionKey, ownerID: draft.ownerID,
            protocolKey: draft.protocolKey, side: draft.side, dropHeightCm: draft.dropHeightCm,
            recordedAt: draft.recordedAt, notes: draft.notes, metrics: metrics)

        try Self.execute(connection, "BEGIN IMMEDIATE")
        do {
            let prior = try prepare("SELECT id,owner_id FROM assessments WHERE session_key=?")
            text(prior, 1, measurement.sessionKey)
            let step = sqlite3_step(prior)
            if step == SQLITE_ROW {
                let existingID = string(prior, 0).flatMap(UUID.init(uuidString:))
                let existingOwner = string(prior, 1)
                sqlite3_finalize(prior)
                if let existingOwner, UUID(uuidString: existingOwner) == nil {
                    throw StoreError.sqlite("invalid stored owner identity")
                }
                guard existingOwner == draft.ownerID.uuidString else { throw StoreError.ownerConflict }
                guard let existingID else { throw StoreError.sqlite("invalid stored measurement identity") }
                let stored = try measurementByID(existingID)
                guard try temporalAnalysis(measurementID: existingID) != nil else {
                    throw StoreError.sqlite("session already exists without a confirmed temporal analysis")
                }
                try Self.execute(connection, "COMMIT")
                return stored
            }
            sqlite3_finalize(prior)
            guard step == SQLITE_DONE else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            try requireActiveOwner(draft.ownerID)
            try insertMeasurementRows(measurement)
            try insertTemporalAnalysisRows(draft, measurementID: measurement.id)
            try Self.execute(connection, "COMMIT")
            return measurement
        } catch { try? Self.execute(connection, "ROLLBACK"); throw error }
    }

    private static func validateTemporalMetrics(_ metrics: [SavedMetric], protocolKey: SavedProtocol) throws {
        let expected: [(String, String)]
        switch protocolKey {
        case .cmj, .abalakov, .unilateral:
            expected = [("HEIGHT_CM", "CENTIMETER"), ("FLIGHT_TIME_MS", "MILLISECOND"),
                        ("TAKEOFF_VELOCITY_MPS", "METER_PER_SECOND"), ("TIME_TO_TAKEOFF_MS", "MILLISECOND"),
                        ("RSI_MOD", "METER_PER_SECOND")]
        case .sj:
            expected = [("HEIGHT_CM", "CENTIMETER"), ("FLIGHT_TIME_MS", "MILLISECOND"),
                        ("TAKEOFF_VELOCITY_MPS", "METER_PER_SECOND")]
        case .dropJump:
            expected = [("RSI", "METER_PER_SECOND"), ("CONTACT_TIME_MS", "MILLISECOND"),
                        ("HEIGHT_CM", "CENTIMETER"), ("FLIGHT_TIME_MS", "MILLISECOND")]
        default: throw StoreError.invalidMeasurement
        }
        guard metrics.count == expected.count else { throw StoreError.invalidMeasurement }
        for (ordinal, signature) in expected.enumerated() {
            let metric = metrics[ordinal]
            guard metric.key == signature.0, metric.unit == signature.1,
                  metric.ordinal == ordinal, metric.value.isFinite else { throw StoreError.invalidMeasurement }
        }
    }

    private func requireActiveOwner(_ ownerID: UUID) throws {
        let ownerStmt = try prepare("SELECT 1 FROM athletes WHERE id=? AND archived_at IS NULL")
        text(ownerStmt, 1, ownerID.uuidString)
        let ownerStep = sqlite3_step(ownerStmt)
        sqlite3_finalize(ownerStmt)
        if ownerStep != SQLITE_ROW && ownerStep != SQLITE_DONE {
            throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection)))
        }
        guard ownerStep == SQLITE_ROW else { throw StoreError.inactiveOwner }
    }

    private func insertMeasurementRows(_ measurement: SavedMeasurement) throws {
        let stmt = try prepare("INSERT INTO assessments(id,session_key,owner_id,protocol_key,side,drop_height_cm,recorded_at,notes) VALUES(?,?,?,?,?,?,?,?)")
        defer { sqlite3_finalize(stmt) }
        text(stmt,1,measurement.id.uuidString); text(stmt,2,measurement.sessionKey); text(stmt,3,measurement.ownerID?.uuidString)
        text(stmt,4,measurement.protocolKey.rawValue); text(stmt,5,measurement.side); optionalDouble(stmt,6,measurement.dropHeightCm)
        sqlite3_bind_double(stmt,7,measurement.recordedAt.timeIntervalSince1970); text(stmt,8,measurement.notes)
        try check(sqlite3_step(stmt))

        let metricStmt = try prepare("INSERT INTO attempt_metrics(assessment_id,metric_key,unit,value,ordinal) VALUES(?,?,?,?,?)")
        defer { sqlite3_finalize(metricStmt) }
        for metric in measurement.metrics {
            sqlite3_reset(metricStmt); sqlite3_clear_bindings(metricStmt); text(metricStmt,1,measurement.id.uuidString)
            text(metricStmt,2,metric.key); text(metricStmt,3,metric.unit); sqlite3_bind_double(metricStmt,4,metric.value)
            sqlite3_bind_int(metricStmt,5,Int32(metric.ordinal)); try check(sqlite3_step(metricStmt))
        }
    }

    private func insertTemporalAnalysisRows(_ draft: TemporalJumpDraft, measurementID: UUID) throws {
        let eventStmt = try prepare("INSERT INTO assessment_events(assessment_id,event_key,ordinal,frame_index,pts_us,previous_pts_us,next_pts_us) VALUES(?,?,?,?,?,?,?)")
        defer { sqlite3_finalize(eventStmt) }
        for (ordinal, event) in draft.events.enumerated() {
            sqlite3_reset(eventStmt); sqlite3_clear_bindings(eventStmt)
            text(eventStmt, 1, measurementID.uuidString); text(eventStmt, 2, event.kind.rawValue)
            sqlite3_bind_int(eventStmt, 3, Int32(ordinal)); sqlite3_bind_int(eventStmt, 4, Int32(event.frameIndex))
            sqlite3_bind_int64(eventStmt, 5, event.ptsUs)
            if let previous = event.previousPtsUs { sqlite3_bind_int64(eventStmt, 6, previous) } else { sqlite3_bind_null(eventStmt, 6) }
            if let next = event.nextPtsUs { sqlite3_bind_int64(eventStmt, 7, next) } else { sqlite3_bind_null(eventStmt, 7) }
            try check(sqlite3_step(eventStmt))
        }
        let analysisStmt = try prepare("INSERT INTO assessment_analysis(assessment_id,source_kind,source_frame_count,source_origin_us,temporal_state,analysis_version) VALUES(?,?,?,?,?,1)")
        defer { sqlite3_finalize(analysisStmt) }
        text(analysisStmt, 1, measurementID.uuidString); text(analysisStmt, 2, draft.source.rawValue)
        sqlite3_bind_int(analysisStmt, 3, Int32(draft.sourceFrameCount)); sqlite3_bind_int64(analysisStmt, 4, draft.sourceOriginUs)
        text(analysisStmt, 5, draft.temporalState.rawValue)
        try check(sqlite3_step(analysisStmt))
    }

    func temporalAnalysis(measurementID: UUID) throws -> SavedTemporalAnalysis? {
        let measurement = try measurementByID(measurementID)
        let invalidGraph = StoreError.sqlite("invalid stored temporal analysis")
        let analysisStmt = try prepare("SELECT source_kind,source_frame_count,source_origin_us,temporal_state,analysis_version FROM assessment_analysis WHERE assessment_id=?")
        text(analysisStmt, 1, measurementID.uuidString)
        let analysisStep = sqlite3_step(analysisStmt)
        guard analysisStep == SQLITE_ROW || analysisStep == SQLITE_DONE else {
            sqlite3_finalize(analysisStmt)
            throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection)))
        }
        guard analysisStep == SQLITE_ROW else {
            sqlite3_finalize(analysisStmt)
            let eventCount = try prepare("SELECT COUNT(*) FROM assessment_events WHERE assessment_id=?")
            defer { sqlite3_finalize(eventCount) }
            text(eventCount, 1, measurementID.uuidString)
            guard sqlite3_step(eventCount) == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            guard sqlite3_column_int64(eventCount, 0) == 0 else { throw invalidGraph }
            return nil
        }

        let sourceRaw = string(analysisStmt, 0)
        let frameCountRaw = sqlite3_column_int64(analysisStmt, 1)
        let originUs = sqlite3_column_int64(analysisStmt, 2)
        let stateRaw = string(analysisStmt, 3)
        let version = sqlite3_column_int64(analysisStmt, 4)
        let metadataTypesValid = sqlite3_column_type(analysisStmt, 0) == SQLITE_TEXT &&
            sqlite3_column_type(analysisStmt, 1) == SQLITE_INTEGER &&
            sqlite3_column_type(analysisStmt, 2) == SQLITE_INTEGER &&
            sqlite3_column_type(analysisStmt, 3) == SQLITE_TEXT &&
            sqlite3_column_type(analysisStmt, 4) == SQLITE_INTEGER
        let trailingStep = sqlite3_step(analysisStmt)
        sqlite3_finalize(analysisStmt)
        guard metadataTypesValid, trailingStep == SQLITE_DONE,
              let sourceRaw, let source = JumpVideoSource(rawValue: sourceRaw),
              let stateRaw, let state = JumpTemporalState(rawValue: stateRaw),
              let frameCount = Int(exactly: frameCountRaw), version == 1 else { throw invalidGraph }

        let eventsStmt = try prepare("SELECT event_key,ordinal,frame_index,pts_us,previous_pts_us,next_pts_us FROM assessment_events WHERE assessment_id=? ORDER BY ordinal")
        defer { sqlite3_finalize(eventsStmt) }
        text(eventsStmt, 1, measurementID.uuidString)
        var events: [JumpEventMark] = []
        while true {
            let step = sqlite3_step(eventsStmt)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW, sqlite3_column_type(eventsStmt, 0) == SQLITE_TEXT,
                  let key = string(eventsStmt, 0), let kind = JumpEventKind(rawValue: key),
                  let ordinal = Int(exactly: try storedInteger(eventsStmt, 1)), ordinal == events.count,
                  let frameIndex = Int(exactly: try storedInteger(eventsStmt, 2)) else { throw invalidGraph }
            let ptsUs = try storedInteger(eventsStmt, 3)
            let previous = try storedOptionalInteger(eventsStmt, 4)
            let next = try storedOptionalInteger(eventsStmt, 5)
            events.append(JumpEventMark(kind: kind, frameIndex: frameIndex, ptsUs: ptsUs,
                                        previousPtsUs: previous, nextPtsUs: next))
        }

        guard let ownerID = measurement.ownerID else { throw invalidGraph }
        do {
            try Self.validateTemporalMetrics(measurement.metrics, protocolKey: measurement.protocolKey)
            let draft = TemporalJumpDraft(sessionKey: measurement.sessionKey, ownerID: ownerID,
                protocolKey: measurement.protocolKey, side: measurement.side, dropHeightCm: measurement.dropHeightCm,
                recordedAt: measurement.recordedAt, notes: measurement.notes, source: source,
                sourceFrameCount: frameCount, sourceOriginUs: originUs, temporalState: state, events: events)
            try draft.validate()
            let recomputedMetrics = try TemporalJumpEngine.calculate(draft: draft)
            guard recomputedMetrics == measurement.metrics else { throw invalidGraph }
        } catch { throw invalidGraph }
        return SavedTemporalAnalysis(measurementID: measurementID, source: source,
            sourceFrameCount: frameCount, sourceOriginUs: originUs, temporalState: state,
            analysisVersion: Int(version), events: events)
    }

    private func storedInteger(_ stmt: OpaquePointer, _ index: Int32) throws -> Int64 {
        guard sqlite3_column_type(stmt, index) == SQLITE_INTEGER else {
            throw StoreError.sqlite("invalid stored temporal analysis")
        }
        return sqlite3_column_int64(stmt, index)
    }

    private func storedOptionalInteger(_ stmt: OpaquePointer, _ index: Int32) throws -> Int64? {
        switch sqlite3_column_type(stmt, index) {
        case SQLITE_NULL: return nil
        case SQLITE_INTEGER: return sqlite3_column_int64(stmt, index)
        default: throw StoreError.sqlite("invalid stored temporal analysis")
        }
    }

    private func storedOwner(_ value: String?) throws -> UUID? {
        guard let value else { return nil }
        guard let id = UUID(uuidString: value) else { throw StoreError.sqlite("invalid stored owner identity") }
        return id
    }

    public func measurementCount(ownerID: UUID) throws -> Int {
        let stmt = try prepare("SELECT COUNT(*) FROM assessments WHERE owner_id=?")
        defer { sqlite3_finalize(stmt) }
        text(stmt, 1, ownerID.uuidString)
        guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    public func history(ownerID: UUID? = nil, protocolKey: SavedProtocol? = nil, search: String? = nil,
                        limit: Int = 50, before: (date: Date, id: UUID)? = nil,
                        recordedFrom: Date? = nil, recordedBefore: Date? = nil) throws -> MeasurementPage {
        guard (1...200).contains(limit), before.map({ $0.date.timeIntervalSince1970.isFinite }) ?? true else { throw StoreError.invalidPageToken }
        // Half-open recorded-at interval [recordedFrom, recordedBefore): each
        // set bound must be finite, and a fully bounded interval must ascend.
        // Invalid intervals fail closed; they never fall back to all history.
        if let recordedFrom { guard recordedFrom.timeIntervalSince1970.isFinite else { throw StoreError.invalidPageToken } }
        if let recordedBefore { guard recordedBefore.timeIntervalSince1970.isFinite else { throw StoreError.invalidPageToken } }
        if let recordedFrom, let recordedBefore { guard recordedFrom < recordedBefore else { throw StoreError.invalidPageToken } }
        var sql = "SELECT id,session_key,owner_id,protocol_key,side,drop_height_cm,recorded_at,notes FROM assessments WHERE 1=1"
        var args: [String?] = []
        if let ownerID { sql += " AND owner_id=?"; args.append(ownerID.uuidString) }
        if let protocolKey { sql += " AND protocol_key=?"; args.append(protocolKey.rawValue) }
        // Name search stays a correlated EXISTS over the current roster (no
        // JOIN, so no ambiguous root id and no duplicate rows). LIKE keeps
        // the existing ASCII case-folding/wildcard semantics; historical-name
        // snapshots are not fabricated, and NULL owners keep matching on
        // session/notes. Archived owners are never excluded here.
        if let search, !search.isEmpty { sql += " AND (session_key LIKE ? OR COALESCE(notes,'') LIKE ? OR EXISTS (SELECT 1 FROM athletes WHERE athletes.id = assessments.owner_id AND athletes.name LIKE ?))"; args += ["%\(search)%","%\(search)%","%\(search)%"] }
        if recordedFrom != nil { sql += " AND recorded_at >= ?" }
        if recordedBefore != nil { sql += " AND recorded_at < ?" }
        if let before { sql += " AND (recorded_at < ? OR (recorded_at = ? AND id < ?))" }
        sql += " ORDER BY recorded_at DESC,id DESC LIMIT ?"
        let stmt = try prepare(sql); defer { sqlite3_finalize(stmt) }
        var ix: Int32 = 1
        for arg in args { text(stmt,ix,arg); ix += 1 }
        if let recordedFrom { sqlite3_bind_double(stmt,ix,recordedFrom.timeIntervalSince1970); ix += 1 }
        if let recordedBefore { sqlite3_bind_double(stmt,ix,recordedBefore.timeIntervalSince1970); ix += 1 }
        if let before { sqlite3_bind_double(stmt,ix,before.date.timeIntervalSince1970); sqlite3_bind_double(stmt,ix+1,before.date.timeIntervalSince1970); text(stmt,ix+2,before.id.uuidString); ix += 3 }
        sqlite3_bind_int(stmt,ix,Int32(limit + 1))
        var rows: [(UUID,String,UUID?,SavedProtocol,String?,Double?,Date,String?)] = []
        while true {
            let code = sqlite3_step(stmt)
            if code == SQLITE_DONE { break }
            guard code == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            guard let id = string(stmt,0).flatMap(UUID.init(uuidString:)), let key = string(stmt,1),
                  let p = string(stmt,3).flatMap(SavedProtocol.init(rawValue:)) else {
                throw StoreError.sqlite("invalid stored measurement identity or protocol")
            }
            rows.append((id,key,try storedOwner(string(stmt,2)),p,string(stmt,4),
                         sqlite3_column_type(stmt,5) == SQLITE_NULL ? nil : sqlite3_column_double(stmt,5),
                         Date(timeIntervalSince1970:sqlite3_column_double(stmt,6)),string(stmt,7)))
        }
        let hasMore = rows.count > limit; if hasMore { rows.removeLast() }
        var items: [SavedMeasurement] = []
        for row in rows { items.append(try fetchMeasurement(row.0, fallback:row)) }
        let last = items.last
        return MeasurementPage(items:items,nextBefore:hasMore ? last.map { ($0.recordedAt,$0.id) } : nil)
    }

    private func measurementByID(_ id: UUID) throws -> SavedMeasurement {
        let stmt = try prepare("SELECT id,session_key,owner_id,protocol_key,side,drop_height_cm,recorded_at,notes FROM assessments WHERE id=?")
        defer { sqlite3_finalize(stmt) }; text(stmt,1,id.uuidString)
        let step = sqlite3_step(stmt)
        guard step == SQLITE_ROW else {
            if step != SQLITE_DONE { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            throw StoreError.notFound
        }
        guard let rowID = string(stmt,0).flatMap(UUID.init(uuidString:)), let key = string(stmt,1),
              let protocolKey = string(stmt,3).flatMap(SavedProtocol.init(rawValue:)) else {
            throw StoreError.sqlite("invalid stored measurement identity or protocol")
        }
        let row = (rowID,key,try storedOwner(string(stmt,2)),protocolKey,string(stmt,4),
                   sqlite3_column_type(stmt,5) == SQLITE_NULL ? nil : sqlite3_column_double(stmt,5),
                   Date(timeIntervalSince1970:sqlite3_column_double(stmt,6)),string(stmt,7))
        return try fetchMeasurement(id, fallback: row)
    }

    private func fetchMeasurement(_ id: UUID, fallback row: (UUID,String,UUID?,SavedProtocol,String?,Double?,Date,String?)) throws -> SavedMeasurement {
        let stmt = try prepare("SELECT metric_key,unit,value,ordinal FROM attempt_metrics WHERE assessment_id=? ORDER BY ordinal")
        defer { sqlite3_finalize(stmt) }; text(stmt,1,id.uuidString)
        var metrics: [SavedMetric] = []
        while true {
            let code = sqlite3_step(stmt)
            if code == SQLITE_DONE { break }
            guard code == SQLITE_ROW else { throw StoreError.sqlite(String(cString: sqlite3_errmsg(connection))) }
            guard let key = string(stmt,0), let unit = string(stmt,1) else { throw StoreError.sqlite("invalid stored metric") }
            metrics.append(try SavedMetric(key:key,unit:unit,value:sqlite3_column_double(stmt,2),ordinal:Int(sqlite3_column_int(stmt,3))))
        }
        return try SavedMeasurement(id:row.0,sessionKey:row.1,ownerID:row.2,protocolKey:row.3,side:row.4,dropHeightCm:row.5,recordedAt:row.6,notes:row.7,metrics:metrics)
    }

    public func updateNotes(id: UUID, notes: String?) throws {
        let clean = Athlete.normalizedNote(notes); guard clean.map({ $0.count <= 500 && !$0.contains("\0") }) ?? true else { throw StoreError.invalidMeasurement }
        let stmt = try prepare("UPDATE assessments SET notes=? WHERE id=?"); defer { sqlite3_finalize(stmt) }
        text(stmt,1,clean); text(stmt,2,id.uuidString); try check(sqlite3_step(stmt)); guard sqlite3_changes(connection) == 1 else { throw StoreError.notFound }
    }
    public func deleteMeasurement(id: UUID) throws {
        let stmt = try prepare("DELETE FROM assessments WHERE id=?"); defer { sqlite3_finalize(stmt) }
        text(stmt,1,id.uuidString); try check(sqlite3_step(stmt)); guard sqlite3_changes(connection) == 1 else { throw StoreError.notFound }
    }
}
