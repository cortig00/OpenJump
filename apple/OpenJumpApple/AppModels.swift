import Foundation

public struct Athlete: Codable, Identifiable, Equatable, Sendable {
    public let id: UUID
    public var name: String
    public var weightKg: Double?
    public var heightCm: Double?
    public var notes: String?
    public let createdAt: Date
    public var updatedAt: Date
    public var archivedAt: Date?
    public var avatarKey: String?

    public init(id: UUID = UUID(), name: String, weightKg: Double? = nil, heightCm: Double? = nil,
                notes: String? = nil, createdAt: Date = Date(), updatedAt: Date? = nil, archivedAt: Date? = nil,
                avatarKey: String? = nil) throws {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty, cleanName.count <= 120, !cleanName.contains("\0"),
              createdAt.timeIntervalSince1970.isFinite,
              (updatedAt ?? createdAt).timeIntervalSince1970.isFinite,
              archivedAt.map({ $0.timeIntervalSince1970.isFinite }) ?? true,
              avatarKey.map({ $0.count <= 120 && !$0.contains("\0") }) ?? true else { throw AppModelError.invalidAthlete }
        let cleanNote = Self.normalizedNote(notes)
        guard cleanNote.map({ $0.count <= 500 && !$0.contains("\0") }) ?? true else { throw AppModelError.invalidAthlete }
        guard weightKg.map({ $0.isFinite && $0 > 0 }) ?? true,
              heightCm.map({ $0.isFinite && $0 > 0 }) ?? true else { throw AppModelError.invalidAnthropometrics }
        self.id = id; self.name = cleanName; self.weightKg = weightKg; self.heightCm = heightCm
        self.notes = cleanNote; self.createdAt = createdAt; self.updatedAt = updatedAt ?? createdAt
        self.archivedAt = archivedAt; self.avatarKey = avatarKey
    }

    static func normalizedNote(_ value: String?) -> String? {
        guard let value else { return nil }
        let note = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return note.isEmpty ? nil : note
    }
}

public enum AppModelError: Error, Equatable { case invalidAthlete, invalidAnthropometrics }

public enum SavedProtocol: String, Codable, CaseIterable, Sendable {
    case cmj = "CMJ", sj = "SJ", abalakov = "ABALAKOV", unilateral = "UNILATERAL"
    case dropJump = "DROP_JUMP", horizontal = "HORIZONTAL", asymmetry = "ASYMMETRY"
}

public struct SavedMetric: Codable, Equatable, Sendable {
    public let key: String
    public let unit: String
    public let value: Double
    public let ordinal: Int
    public init(key: String, unit: String, value: Double, ordinal: Int) throws {
        guard !key.isEmpty, key.count <= 120, !key.contains("\0"),
              !unit.isEmpty, unit.count <= 80, !unit.contains("\0"),
              value.isFinite, (0...Int(Int32.max)).contains(ordinal) else { throw StoreError.invalidMeasurement }
        self.key = key; self.unit = unit; self.value = value; self.ordinal = ordinal
    }
}

public struct SavedMeasurement: Codable, Identifiable, Equatable, Sendable {
    public let id: UUID
    public let sessionKey: String
    public let ownerID: UUID?
    public let protocolKey: SavedProtocol
    public let side: String?
    public let dropHeightCm: Double?
    public let recordedAt: Date
    public var notes: String?
    public let metrics: [SavedMetric]

    public init(id: UUID = UUID(), sessionKey: String, ownerID: UUID?, protocolKey: SavedProtocol,
                side: String? = nil, dropHeightCm: Double? = nil, recordedAt: Date = Date(),
                notes: String? = nil, metrics: [SavedMetric]) throws {
        let cleanKey = sessionKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanKey.isEmpty, cleanKey.count <= 240, !cleanKey.contains("\0"),
              !metrics.isEmpty, recordedAt.timeIntervalSince1970.isFinite,
              side.map({ $0 == "LEFT" || $0 == "RIGHT" }) ?? true,
              dropHeightCm.map({ $0.isFinite && $0 > 0 }) ?? true,
              metrics.allSatisfy({ $0.value.isFinite }) else { throw StoreError.invalidMeasurement }
        for metric in metrics {
            _ = try SavedMetric(key: metric.key, unit: metric.unit, value: metric.value, ordinal: metric.ordinal)
        }
        self.id = id; self.sessionKey = cleanKey; self.ownerID = ownerID; self.protocolKey = protocolKey
        self.side = side; self.dropHeightCm = dropHeightCm; self.recordedAt = recordedAt
        let normalized = Athlete.normalizedNote(notes)
        guard normalized.map({ $0.count <= 500 && !$0.contains("\0") }) ?? true else { throw StoreError.invalidMeasurement }
        self.notes = normalized; self.metrics = metrics
    }
}

public enum StoreError: Error, Equatable {
    case sqlite(String), unsupportedSchema(Int), invalidMeasurement, inactiveOwner, ownerConflict, lastActiveAthlete
    case notFound, invalidPageToken
}

public struct MeasurementPage: Sendable {
    public let items: [SavedMeasurement]
    public let nextBefore: (date: Date, id: UUID)?
}
