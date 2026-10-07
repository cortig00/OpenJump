import Foundation

/// Apple-specific, creation-only formats. Neither is an Android backup format;
/// this version deliberately has no decoder, importer, or restore operation.
enum AppleExportFormat: Equatable, Sendable {
    case jsonBackup, analyticalCSV
    var fileExtension: String { self == .jsonBackup ? "json" : "csv" }
}

enum AppleDataExportError: Error, Equatable { case tooLarge, invalidSnapshot }

/// Admission limits bound rows and encoded bytes, NOT process RSS. Tests may
/// lower these limits, but callers cannot enlarge the production budgets.
struct AppleExportLimits: Sendable {
    var maxBytes = 8 * 1024 * 1024
    var profiles = 5_000
    var measurements = 5_000
    var metrics = 25_000
    var events = 20_000
    var analyses = 5_000
    static let standard = AppleExportLimits()
    func validate() throws {
        let actual = [maxBytes, profiles, measurements, metrics, events, analyses]
        let ceilings = [8 * 1024 * 1024, 5_000, 5_000, 25_000, 20_000, 5_000]
        guard zip(actual, ceilings).allSatisfy({ $0.0 >= 0 && $0.0 <= $0.1 }) else {
            throw AppleDataExportError.tooLarge
        }
    }
}

struct AppleExportResult: Sendable {
    let format: AppleExportFormat
    let data: Data
    let profileCount: Int
    let measurementCount: Int
    let metricCount: Int
    let createdAt: Date
}

// Explicit projections keep stored text (including empty versus NULL notes,
// unknown avatar keys and UUID spelling) instead of normalizing read models.
struct AppleExportProfile: Encodable, Sendable {
    let id: String
    let name: String
    let weightKg: Double?
    let heightCm: Double?
    let notes: String?
    let createdAt: Double
    let updatedAt: Double
    let archivedAt: Double?
    let avatarKey: String?
}

struct AppleExportEvent: Encodable, Sendable {
    let kind: JumpEventKind
    let ordinal: Int
    let frameIndex: Int
    let ptsUs: Int64
    let previousPtsUs: Int64?
    let nextPtsUs: Int64?
    var mark: JumpEventMark {
        JumpEventMark(kind: kind, frameIndex: frameIndex, ptsUs: ptsUs,
                      previousPtsUs: previousPtsUs, nextPtsUs: nextPtsUs)
    }
}

struct AppleExportAnalysis: Encodable, Sendable {
    let source: JumpVideoSource
    let sourceFrameCount: Int
    let sourceOriginUs: Int64
    let temporalState: JumpTemporalState
    let analysisVersion: Int
    let events: [AppleExportEvent]
}

struct AppleExportMeasurement: Encodable, Sendable {
    let id: String
    let sessionKey: String
    let ownerID: String?
    let protocolKey: SavedProtocol
    let side: String?
    let dropHeightCm: Double?
    let recordedAt: Double
    let notes: String?
    let metrics: [SavedMetric]
    let analysis: AppleExportAnalysis?

    private enum CodingKeys: String, CodingKey {
        case id, sessionKey, ownerID, protocolKey, side, dropHeightCm, recordedAt, notes, metrics, analysis
    }
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(sessionKey, forKey: .sessionKey)
        try container.encode(ownerID, forKey: .ownerID)
        try container.encode(protocolKey, forKey: .protocolKey)
        try container.encode(side, forKey: .side)
        try container.encode(dropHeightCm, forKey: .dropHeightCm)
        try container.encode(recordedAt, forKey: .recordedAt)
        try container.encode(notes, forKey: .notes)
        try container.encode(metrics, forKey: .metrics)
        // Explicit null means a validated legacy record, never an invented graph.
        try container.encode(analysis, forKey: .analysis)
    }
}

struct AppleBoundedExportBytes {
    private(set) var data = Data()
    let limit: Int
    mutating func append(_ bytes: Data) throws {
        try Task.checkCancellation()
        guard limit >= 0, data.count <= limit, bytes.count <= limit - data.count else {
            throw AppleDataExportError.tooLarge
        }
        data.append(bytes)
    }
    mutating func append(_ text: String) throws { try append(Data(text.utf8)) }
}

enum AppleDataExport {
    static let csvColumns = [
        "schema_version", "measurement_id", "session_key", "recorded_at_epoch_seconds", "date_time_utc",
        "athlete_id", "athlete_name_current", "protocol_key", "side", "drop_height_cm", "metric_key",
        "metric_value", "metric_unit", "metric_ordinal", "analysis_source", "analysis_version",
        "source_frame_count", "source_origin_us", "temporal_state", "movement_start_frame",
        "movement_start_pts_us", "initial_contact_frame", "initial_contact_pts_us", "takeoff_frame",
        "takeoff_pts_us", "landing_frame", "landing_pts_us", "notes"
    ]
    private struct Header: Encodable {
        let contract = "openjump-apple-backup"
        let formatVersion = 1
        let sourceSchemaVersion = 3
        let dateEncoding = "unix-seconds"
        let canonicalUnits = true
        let mediaIncluded = false
        let preferencesIncluded = false
        let restorationSupported = false
        let androidCompatible = false
        let createdAtEpochSeconds: Double
    }
    static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .secondsSince1970
        return encoder
    }
    static func jsonPrefix(createdAt: Date) throws -> Data {
        guard createdAt.timeIntervalSince1970.isFinite else { throw AppleDataExportError.invalidSnapshot }
        var bytes = try encoder().encode(Header(createdAtEpochSeconds: createdAt.timeIntervalSince1970))
        // JSONEncoder emits one object. Append arrays without holding a second
        // whole-database DTO; each profile/record is encoded independently.
        guard bytes.last == 125 else { throw AppleDataExportError.invalidSnapshot }
        bytes.removeLast()
        bytes.append(contentsOf: ",\"athletes\":[".utf8)
        return bytes
    }

    /// Harden only text cells. Canonical numeric negatives remain numbers.
    static func csvText(_ raw: String) -> String {
        let scalars = raw.unicodeScalars
        let trimmed = scalars.drop(while: { [UInt32(32), 9, 13, 10].contains($0.value) })
        let startsControl = [UInt32(9), 13, 10].contains(scalars.first?.value ?? 0)
        let startsFormula = [UInt32(61), 43, 45, 64].contains(trimmed.first?.value ?? 0)
        let value = startsControl || startsFormula ? "'" + raw : raw
        if value.unicodeScalars.contains(where: { [UInt32(44), 34, 13, 10].contains($0.value) }) {
            return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return value
    }
    static func number(_ value: Double) throws -> String {
        guard value.isFinite else { throw AppleDataExportError.invalidSnapshot }
        return String(value)
    }
    static func csvRow(_ item: AppleExportMeasurement, metric: SavedMetric, ownerName: String?) throws -> String {
        // Numeric epoch seconds preserve the exact stored timestamp. The UTC
        // companion is a readable millisecond display, not the fidelity field.
        guard (-62_135_596_800..<253_402_300_800).contains(item.recordedAt) else {
            throw AppleDataExportError.invalidSnapshot
        }
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let utc = formatter.string(from: Date(timeIntervalSince1970: item.recordedAt))
        let analysis = item.analysis
        var eventCells: [String] = []
        for kind in [JumpEventKind.movementStart, .initialContact, .takeoff, .landing] {
            let event = analysis?.events.first(where: { $0.kind == kind })
            eventCells += [event.map { String($0.frameIndex) } ?? "", event.map { String($0.ptsUs) } ?? ""]
        }
        let drop = try item.dropHeightCm.map { try number($0) } ?? ""
        var cells: [String] = ["1", csvText(item.id), csvText(item.sessionKey), try number(item.recordedAt), utc,
                     csvText(item.ownerID ?? ""), csvText(ownerName ?? ""), item.protocolKey.rawValue,
                     csvText(item.side ?? ""), drop, csvText(metric.key), try number(metric.value),
                     csvText(metric.unit), String(metric.ordinal), analysis?.source.rawValue ?? "",
                     analysis.map { String($0.analysisVersion) } ?? "",
                     analysis.map { String($0.sourceFrameCount) } ?? "", analysis.map { String($0.sourceOriginUs) } ?? "",
                     analysis?.temporalState.rawValue ?? ""]
        cells += eventCells
        cells.append(csvText(item.notes ?? ""))
        guard cells.count == csvColumns.count else { throw AppleDataExportError.invalidSnapshot }
        return cells.joined(separator: ",") + "\r\n"
    }
}
