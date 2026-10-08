import Foundation

// Shared contract for one manually marked, imported temporal jump. No video URL is persisted.
enum JumpEventKind: String, Codable, CaseIterable, Sendable {
    case movementStart = "MOVEMENT_START"
    case initialContact = "INITIAL_CONTACT"
    case takeoff = "TAKEOFF"
    case landing = "LANDING"
}

enum JumpVideoSource: String, Codable, Sendable { case photos = "PHOTOS", files = "FILES", camera = "CAMERA" }
enum JumpTemporalState: String, Codable, Sendable {
    case unknown = "UNKNOWN"
    case realtimeDeclared = "REALTIME_DECLARED"
}

enum TemporalJumpError: Error, Equatable {
    case unsupportedProtocol, invalidSetup, incompleteEvents, invalidEvents, realtimeNotDeclared
}

struct JumpEventMark: Codable, Equatable, Sendable {
    let kind: JumpEventKind
    let frameIndex: Int
    let ptsUs: Int64
    let previousPtsUs: Int64?
    let nextPtsUs: Int64?
}

struct TemporalJumpDraft: Codable, Equatable, Sendable {
    let sessionKey: String
    let ownerID: UUID
    let protocolKey: SavedProtocol
    let side: String?
    let dropHeightCm: Double?
    let recordedAt: Date
    var notes: String?
    let source: JumpVideoSource
    let sourceFrameCount: Int
    let sourceOriginUs: Int64
    var temporalState: JumpTemporalState
    var events: [JumpEventMark]

    static let supportedProtocols: [SavedProtocol] = [.cmj, .sj, .abalakov, .unilateral, .dropJump]
    static func requiredEvents(for protocolKey: SavedProtocol) -> [JumpEventKind] {
        switch protocolKey {
        case .cmj, .abalakov, .unilateral: return [.movementStart, .takeoff, .landing]
        case .sj: return [.takeoff, .landing]
        case .dropJump: return [.initialContact, .takeoff, .landing]
        default: return []
        }
    }

    func validate() throws {
        guard Self.supportedProtocols.contains(protocolKey) else { throw TemporalJumpError.unsupportedProtocol }
        guard !sessionKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              sessionKey.count <= 240, !sessionKey.contains("\0"), recordedAt.timeIntervalSince1970.isFinite,
              (2...250_000).contains(sourceFrameCount), sourceOriginUs >= 0,
              notes.map({ $0.count <= 500 && !$0.contains("\0") }) ?? true else {
            throw TemporalJumpError.invalidSetup
        }
        if protocolKey == .unilateral {
            guard side == "LEFT" || side == "RIGHT" else { throw TemporalJumpError.invalidSetup }
        } else if side != nil { throw TemporalJumpError.invalidSetup }
        if protocolKey == .dropJump {
            guard let dropHeightCm, dropHeightCm.isFinite, dropHeightCm > 0 else { throw TemporalJumpError.invalidSetup }
        } else if dropHeightCm != nil { throw TemporalJumpError.invalidSetup }
        guard temporalState == .realtimeDeclared else { throw TemporalJumpError.realtimeNotDeclared }
        let kinds = Self.requiredEvents(for: protocolKey)
        guard events.map(\.kind) == kinds else { throw TemporalJumpError.incompleteEvents }
        for (index, event) in events.enumerated() {
            guard (0..<sourceFrameCount).contains(event.frameIndex), event.ptsUs >= sourceOriginUs,
                  event.previousPtsUs.map({ $0 >= sourceOriginUs && $0 < event.ptsUs }) ?? (event.frameIndex == 0),
                  event.nextPtsUs.map({ $0 > event.ptsUs }) ?? (event.frameIndex == sourceFrameCount - 1),
                  event.frameIndex != 0 || (event.previousPtsUs == nil && event.ptsUs == sourceOriginUs),
                  event.frameIndex != sourceFrameCount - 1 || event.nextPtsUs == nil else {
                throw TemporalJumpError.invalidEvents
            }
            if index > 0 {
                guard events[index - 1].frameIndex < event.frameIndex,
                      events[index - 1].ptsUs < event.ptsUs else { throw TemporalJumpError.invalidEvents }
            }
        }
    }
}

struct SavedTemporalAnalysis: Equatable, Sendable {
    let measurementID: UUID
    let source: JumpVideoSource
    let sourceFrameCount: Int
    let sourceOriginUs: Int64
    let temporalState: JumpTemporalState
    let analysisVersion: Int
    let events: [JumpEventMark]
}
