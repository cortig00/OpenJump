import XCTest
@testable import OpenJumpApple

final class TemporalJumpEngineTests: XCTestCase {
    func testCMJMetricsUseSharedCalculationAndCanonicalOrder() throws {
        let metrics = try TemporalJumpEngine.calculate(draft: draft(.cmj))

        XCTAssertEqual(metrics.map(\.key), [
            "HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"
        ])
        XCTAssertEqual(metrics.map(\.ordinal), Array(0..<5))
        XCTAssertEqual(metrics.map(\.unit), [
            "CENTIMETER", "MILLISECOND", "METER_PER_SECOND", "MILLISECOND", "METER_PER_SECOND"
        ])
        XCTAssertEqual(metrics[0].value, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(metrics[1].value, 500, accuracy: 1e-10)
        XCTAssertEqual(metrics[2].value, 2.4516625, accuracy: 1e-10)
        XCTAssertEqual(metrics[3].value, 200, accuracy: 1e-10)
        XCTAssertEqual(metrics[4].value, 1.5322890625, accuracy: 1e-10)
    }

    func testCMJMetricsMatchIndependent300MillisecondMovementOracle() throws {
        let times: [Int64] = [100_000, 200_000, 300_000, 400_000, 500_000,
                              600_000, 700_000, 800_000, 900_000]
        let draft = TemporalJumpDraft(
            sessionKey: "cmj-300ms-oracle", ownerID: UUID(), protocolKey: .cmj, side: nil,
            dropHeightCm: nil, recordedAt: Date(timeIntervalSince1970: 1_700_000_000), notes: nil,
            source: .files, sourceFrameCount: times.count, sourceOriginUs: times[0],
            temporalState: .realtimeDeclared, events: [
                JumpEventMark(kind: .movementStart, frameIndex: 0, ptsUs: 100_000,
                              previousPtsUs: nil, nextPtsUs: 200_000),
                JumpEventMark(kind: .takeoff, frameIndex: 3, ptsUs: 400_000,
                              previousPtsUs: 300_000, nextPtsUs: 500_000),
                JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 900_000,
                              previousPtsUs: 800_000, nextPtsUs: nil)
            ])

        let metrics = try TemporalJumpEngine.calculate(draft: draft)
        XCTAssertEqual(metrics[0].value, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(metrics[1].value, 500, accuracy: 1e-10)
        XCTAssertEqual(metrics[2].value, 2.4516625, accuracy: 1e-10)
        XCTAssertEqual(metrics[3].value, 300, accuracy: 1e-10)
        XCTAssertEqual(metrics[4].value, 1.0215260416666667, accuracy: 1e-10)
    }

    func testSquatJumpOmitsMovementOnlyMetrics() throws {
        let metrics = try TemporalJumpEngine.calculate(draft: draft(.sj))

        XCTAssertEqual(metrics.map(\.key), ["HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS"])
        XCTAssertEqual(metrics.map(\.ordinal), Array(0..<3))
    }

    func testDropJumpUsesRSIAndContactTimeInCanonicalOrder() throws {
        let metrics = try TemporalJumpEngine.calculate(draft: draft(.dropJump, dropHeightCm: 30))

        XCTAssertEqual(metrics.map(\.key), ["RSI", "CONTACT_TIME_MS", "HEIGHT_CM", "FLIGHT_TIME_MS"])
        XCTAssertEqual(metrics.map(\.ordinal), Array(0..<4))
        XCTAssertEqual(metrics[0].unit, "METER_PER_SECOND")
        XCTAssertEqual(metrics[0].value, 1.5322890625, accuracy: 1e-10)
        XCTAssertEqual(metrics[1].value, 200, accuracy: 1e-10)
    }

    func testAbalakovAndUnilateralShareCountermovementMetrics() throws {
        for protocolKey in [SavedProtocol.abalakov, .unilateral] {
            let metrics = try TemporalJumpEngine.calculate(
                draft: draft(protocolKey, side: protocolKey == .unilateral ? "LEFT" : nil)
            )
            XCTAssertEqual(metrics.map(\.key), [
                "HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"
            ])
        }
    }

    func testMinimumFlightBoundaryMapsToTypedEngineError() {
        XCTAssertThrowsError(try TemporalJumpEngine.calculate(draft: draft(.sj, shortFlight: true))) { error in
            XCTAssertEqual(error as? TemporalJumpCalculationError, .invalidTiming)
        }
    }

    func testUnsupportedProtocolIsRejectedByTheDraftContract() {
        XCTAssertThrowsError(try TemporalJumpEngine.calculate(draft: draft(.horizontal))) { error in
            XCTAssertEqual(error as? TemporalJumpError, .unsupportedProtocol)
        }
    }

    private func draft(
        _ protocolKey: SavedProtocol,
        side: String? = nil,
        dropHeightCm: Double? = nil,
        shortFlight: Bool = false
    ) -> TemporalJumpDraft {
        let times: [Int64] = shortFlight
            ? [100_000, 200_000, 300_000, 400_000, 425_000, 449_999, 500_000, 550_000]
            : [100_000, 200_000, 300_000, 400_000, 800_000, 900_000, 1_000_000, 1_100_000]
        let frameForKind: [JumpEventKind: Int] = [
            .movementStart: 1, .initialContact: 1, .takeoff: 3, .landing: 5
        ]
        let events = TemporalJumpDraft.requiredEvents(for: protocolKey).map { kind -> JumpEventMark in
            let frame = frameForKind[kind]!
            return JumpEventMark(
                kind: kind,
                frameIndex: frame,
                ptsUs: times[frame],
                previousPtsUs: frame == 0 ? nil : times[frame - 1],
                nextPtsUs: frame == times.count - 1 ? nil : times[frame + 1]
            )
        }
        return TemporalJumpDraft(
            sessionKey: "temporal-engine-test",
            ownerID: UUID(),
            protocolKey: protocolKey,
            side: side,
            dropHeightCm: dropHeightCm,
            recordedAt: Date(timeIntervalSince1970: 1_700_000_000),
            notes: nil,
            source: .files,
            sourceFrameCount: times.count,
            sourceOriginUs: times[0],
            temporalState: .realtimeDeclared,
            events: events
        )
    }
}
