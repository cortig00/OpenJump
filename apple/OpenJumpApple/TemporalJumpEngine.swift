import Foundation
import JumpCoreSpike

enum TemporalJumpCalculationError: Error, Equatable {
    case invalidTiming
    case invalidMetrics
}

enum TemporalJumpEngine {
    static func calculate(draft: TemporalJumpDraft) throws -> [SavedMetric] {
        try draft.validate()

        guard let takeoff = draft.events.first(where: { $0.kind == .takeoff })?.ptsUs,
              let landing = draft.events.first(where: { $0.kind == .landing })?.ptsUs else {
            throw TemporalJumpCalculationError.invalidTiming
        }
        let firstEventKind: JumpEventKind = draft.protocolKey == .dropJump ? .initialContact : .movementStart
        let firstEvent = draft.events.first(where: { $0.kind == firstEventKind })?.ptsUs ?? 0

        let calculated: TemporalJumpMetrics
        do {
            calculated = try JumpCore().calculateTemporal(
                protocolKey: draft.protocolKey.rawValue,
                firstEventUs: firstEvent,
                takeoffUs: takeoff,
                landingUs: landing
            )
        } catch {
            // Keep Kotlin/Objective-C bridge exceptions inside this typed Swift API.
            throw TemporalJumpCalculationError.invalidTiming
        }

        let orderedValues: [(key: String, unit: String, value: Double)]
        switch draft.protocolKey {
        case .cmj, .abalakov, .unilateral:
            guard calculated.hasHeightCm, calculated.hasFlightTimeMs, calculated.hasTakeoffVelocityMps,
                  calculated.hasTimeToTakeoffMs, calculated.hasRsiMod,
                  !calculated.hasContactTimeMs, !calculated.hasRsi else {
                throw TemporalJumpCalculationError.invalidMetrics
            }
            orderedValues = [
                ("HEIGHT_CM", "CENTIMETER", calculated.heightCm),
                ("FLIGHT_TIME_MS", "MILLISECOND", calculated.flightTimeMs),
                ("TAKEOFF_VELOCITY_MPS", "METER_PER_SECOND", calculated.takeoffVelocityMps),
                ("TIME_TO_TAKEOFF_MS", "MILLISECOND", calculated.timeToTakeoffMs),
                ("RSI_MOD", "METER_PER_SECOND", calculated.rsiMod)
            ]
        case .sj:
            guard calculated.hasHeightCm, calculated.hasFlightTimeMs, calculated.hasTakeoffVelocityMps,
                  !calculated.hasTimeToTakeoffMs, !calculated.hasRsiMod,
                  !calculated.hasContactTimeMs, !calculated.hasRsi else {
                throw TemporalJumpCalculationError.invalidMetrics
            }
            orderedValues = [
                ("HEIGHT_CM", "CENTIMETER", calculated.heightCm),
                ("FLIGHT_TIME_MS", "MILLISECOND", calculated.flightTimeMs),
                ("TAKEOFF_VELOCITY_MPS", "METER_PER_SECOND", calculated.takeoffVelocityMps)
            ]
        case .dropJump:
            guard calculated.hasRsi, calculated.hasContactTimeMs, calculated.hasHeightCm,
                  calculated.hasFlightTimeMs, calculated.hasTakeoffVelocityMps,
                  !calculated.hasTimeToTakeoffMs, !calculated.hasRsiMod else {
                throw TemporalJumpCalculationError.invalidMetrics
            }
            orderedValues = [
                ("RSI", "METER_PER_SECOND", calculated.rsi),
                ("CONTACT_TIME_MS", "MILLISECOND", calculated.contactTimeMs),
                ("HEIGHT_CM", "CENTIMETER", calculated.heightCm),
                ("FLIGHT_TIME_MS", "MILLISECOND", calculated.flightTimeMs)
            ]
        default:
            throw TemporalJumpError.unsupportedProtocol
        }

        guard orderedValues.allSatisfy({ $0.value.isFinite }) else {
            throw TemporalJumpCalculationError.invalidMetrics
        }
        do {
            return try orderedValues.enumerated().map { ordinal, metric in
                try SavedMetric(key: metric.key, unit: metric.unit, value: metric.value, ordinal: ordinal)
            }
        } catch {
            throw TemporalJumpCalculationError.invalidMetrics
        }
    }
}
