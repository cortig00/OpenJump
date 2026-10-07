import XCTest
@testable import OpenJumpApple

/// S1 product-presentation contracts: protocol illustration mapping and
/// primary-metric selection. Pure mapping over saved values; no formulas.
final class PresentationTests: XCTestCase {
    private func metric(key: String, unit: String = "CENTIMETER", value: Double = 30.0, ordinal: Int = 0) throws -> SavedMetric {
        try SavedMetric(key: key, unit: unit, value: value, ordinal: ordinal)
    }

    func testSingleProtocolIllustrations() {
        XCTAssertEqual(ProtocolPresentation.illustrationAssets(for: .cmj, side: nil), ["jump_cmj"])
        XCTAssertEqual(ProtocolPresentation.illustrationAssets(for: .sj, side: nil), ["jump_sj"])
        XCTAssertEqual(ProtocolPresentation.illustrationAssets(for: .abalakov, side: nil), ["jump_abalakov"])
        XCTAssertEqual(ProtocolPresentation.illustrationAssets(for: .dropJump, side: nil), ["jump_drop_jump"])
    }

    func testUnilateralIllustrationFollowsSide() {
        XCTAssertEqual(ProtocolPresentation.illustrationAssets(for: .unilateral, side: "LEFT"), ["jump_unilateral_left"])
        XCTAssertEqual(ProtocolPresentation.illustrationAssets(for: .unilateral, side: "RIGHT"), ["jump_unilateral_right"])
        // Nil side shows both as a comparison; the setup control stays LEFT/RIGHT.
        XCTAssertEqual(
            ProtocolPresentation.illustrationAssets(for: .unilateral, side: nil),
            ["jump_unilateral_left", "jump_unilateral_right"]
        )
    }

    func testUnsupportedProtocolsHaveNoIllustration() {
        XCTAssertTrue(ProtocolPresentation.illustrationAssets(for: .horizontal, side: nil).isEmpty)
        XCTAssertTrue(ProtocolPresentation.illustrationAssets(for: .asymmetry, side: nil).isEmpty)
    }

    func testDropJumpPrefersRSIEvenWhenHeightIsFirst() throws {
        let height = try metric(key: "HEIGHT_CM", ordinal: 0)
        let rsi = try metric(key: "RSI", unit: "METER_PER_SECOND", value: 1.02, ordinal: 1)
        XCTAssertEqual(ProtocolPresentation.primaryMetric(in: [height, rsi], protocolKey: .dropJump), rsi)
    }

    func testDropJumpFallsBackToRSIMod() throws {
        let height = try metric(key: "JUMP_HEIGHT", ordinal: 0)
        let rsiMod = try metric(key: "RSI_MOD", unit: "METER_PER_SECOND", value: 0.9, ordinal: 2)
        XCTAssertEqual(ProtocolPresentation.primaryMetric(in: [height, rsiMod], protocolKey: .dropJump), rsiMod)
    }

    func testCmjChoosesHeight() throws {
        let flight = try metric(key: "FLIGHT_TIME_MS", unit: "MILLISECOND", value: 500, ordinal: 0)
        let height = try metric(key: "HEIGHT_CM", ordinal: 1)
        XCTAssertEqual(ProtocolPresentation.primaryMetric(in: [flight, height], protocolKey: .cmj), height)
    }

    func testMissingPreferredFallsBackToLowestOrdinal() throws {
        let flight = try metric(key: "FLIGHT_TIME_MS", unit: "MILLISECOND", value: 500, ordinal: 3)
        let contact = try metric(key: "CONTACT_TIME_MS", unit: "MILLISECOND", value: 200, ordinal: 1)
        XCTAssertEqual(ProtocolPresentation.primaryMetric(in: [flight, contact], protocolKey: .sj), contact)
    }

    func testEmptyMetricsHaveNoPrimary() {
        XCTAssertNil(ProtocolPresentation.primaryMetric(in: [], protocolKey: .cmj))
    }

    func testPrimaryMetricDoesNotMutateInput() throws {
        let first = try metric(key: "RSI", unit: "METER_PER_SECOND", value: 1.0, ordinal: 5)
        let second = try metric(key: "HEIGHT_CM", ordinal: 2)
        let input = [first, second]
        _ = ProtocolPresentation.primaryMetric(in: input, protocolKey: .dropJump)
        XCTAssertEqual(input, [first, second])
    }
}
