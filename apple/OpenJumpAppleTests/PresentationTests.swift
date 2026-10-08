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

    func testTimingPreviewCountermovementReportsMovementAndFlight() {
        let marks = [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000),
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil)
        ]
        for protocolKey in [SavedProtocol.cmj, .abalakov, .unilateral] {
            let previews = JumpWorkflowPresentation.timingPreview(for: protocolKey, marks: marks)
            XCTAssertEqual(previews.map(\.id), ["movement", "flight"])
            XCTAssertEqual(previews.map(\.metricKey), ["TIME_TO_TAKEOFF_MS", "FLIGHT_TIME_MS"])
            XCTAssertEqual(previews.map(\.startPtsUs), [100_000, 600_000])
            XCTAssertEqual(previews.map(\.endPtsUs), [600_000, 1_100_000])
            XCTAssertEqual(previews.map(\.durationUs), [500_000, 500_000])
            XCTAssertEqual(previews.map(\.durationMs), [500.0, 500.0])
        }
    }

    func testTimingPreviewSquatJumpFlightOnlyAndDropJumpContactAndFlight() {
        let sj = [
            JumpEventMark(kind: .takeoff, frameIndex: 3, ptsUs: 300_000, previousPtsUs: 200_000, nextPtsUs: 400_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 800_000, previousPtsUs: 700_000, nextPtsUs: nil)
        ]
        let sjPreviews = JumpWorkflowPresentation.timingPreview(for: .sj, marks: sj)
        XCTAssertEqual(sjPreviews.map(\.id), ["flight"])
        XCTAssertEqual(sjPreviews.map(\.metricKey), ["FLIGHT_TIME_MS"])
        XCTAssertEqual(sjPreviews.map(\.startPtsUs), [300_000])
        XCTAssertEqual(sjPreviews.map(\.endPtsUs), [800_000])
        XCTAssertEqual(sjPreviews.map(\.durationUs), [500_000])
        XCTAssertEqual(sjPreviews.map(\.durationMs), [500.0])
        let dj = [
            JumpEventMark(kind: .initialContact, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000),
            JumpEventMark(kind: .takeoff, frameIndex: 3, ptsUs: 300_000, previousPtsUs: 200_000, nextPtsUs: 400_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 800_000, previousPtsUs: 700_000, nextPtsUs: nil)
        ]
        let djPreviews = JumpWorkflowPresentation.timingPreview(for: .dropJump, marks: dj)
        XCTAssertEqual(djPreviews.map(\.id), ["contact", "flight"])
        XCTAssertEqual(djPreviews.map(\.metricKey), ["CONTACT_TIME_MS", "FLIGHT_TIME_MS"])
        XCTAssertEqual(djPreviews.map(\.startPtsUs), [100_000, 300_000])
        XCTAssertEqual(djPreviews.map(\.endPtsUs), [300_000, 800_000])
        XCTAssertEqual(djPreviews.map(\.durationUs), [200_000, 500_000])
        XCTAssertEqual(djPreviews.map(\.durationMs), [200.0, 500.0])
    }

    func testTimingPreviewPartialMarksYieldOnlyCompletePairs() {
        let movementOnly = [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: nil)
        ]
        let movementPreviews = JumpWorkflowPresentation.timingPreview(for: .cmj, marks: movementOnly)
        XCTAssertEqual(movementPreviews.map(\.id), ["movement"])
        XCTAssertEqual(movementPreviews.map(\.startPtsUs), [100_000])
        XCTAssertEqual(movementPreviews.map(\.endPtsUs), [600_000])
        XCTAssertEqual(movementPreviews.map(\.durationUs), [500_000])
        XCTAssertEqual(movementPreviews.map(\.durationMs), [500.0])
        XCTAssertFalse(movementPreviews.contains(where: { $0.durationUs == 0 }))
        let flightOnly = [
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000),
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil)
        ]
        let flightPreviews = JumpWorkflowPresentation.timingPreview(for: .cmj, marks: flightOnly)
        XCTAssertEqual(flightPreviews.map(\.id), ["flight"])
        XCTAssertEqual(flightPreviews.map(\.startPtsUs), [600_000])
        XCTAssertEqual(flightPreviews.map(\.endPtsUs), [1_100_000])
        XCTAssertEqual(flightPreviews.map(\.durationUs), [500_000])
        XCTAssertEqual(flightPreviews.map(\.durationMs), [500.0])
        let loneTakeoff = [
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000)
        ]
        XCTAssertTrue(JumpWorkflowPresentation.timingPreview(for: .sj, marks: loneTakeoff).isEmpty)
        XCTAssertTrue(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: []).isEmpty)
    }

    func testTimingPreviewInvalidMarksFailClosed() {
        let movement = JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000)
        let takeoff = JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000)
        let landing = JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil)
        XCTAssertTrue(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: [movement, takeoff, landing, takeoff]).isEmpty)
        let negativeIndex = [
            JumpEventMark(kind: .movementStart, frameIndex: -1, ptsUs: 100_000, previousPtsUs: nil, nextPtsUs: 200_000),
            takeoff,
            landing
        ]
        XCTAssertEqual(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: negativeIndex).map(\.id), ["flight"])
        let negativePts = [
            movement,
            takeoff,
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: -10, previousPtsUs: nil, nextPtsUs: nil)
        ]
        XCTAssertEqual(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: negativePts).map(\.id), ["movement"])
        let reversed = [
            movement,
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil),
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: nil)
        ]
        XCTAssertEqual(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: reversed).map(\.id), ["movement"])
        let equalStamps = [
            movement,
            takeoff,
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: nil)
        ]
        XCTAssertEqual(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: equalStamps).map(\.id), ["movement"])
        let reversedFrameIndex = [
            movement,
            JumpEventMark(kind: .takeoff, frameIndex: 9, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000),
            JumpEventMark(kind: .landing, frameIndex: 4, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil)
        ]
        XCTAssertEqual(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: reversedFrameIndex).map(\.id), ["movement"])
        let equalFrameIndex = [
            movement,
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000),
            JumpEventMark(kind: .landing, frameIndex: 4, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil)
        ]
        XCTAssertEqual(JumpWorkflowPresentation.timingPreview(for: .cmj, marks: equalFrameIndex).map(\.id), ["movement"])
    }

    func testTimingPreviewInt64BoundaryOffsetsStaySafe() {
        let low = [
            JumpEventMark(kind: .takeoff, frameIndex: 3, ptsUs: 300_000, previousPtsUs: 200_000, nextPtsUs: 400_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: 800_000, previousPtsUs: 700_000, nextPtsUs: nil)
        ]
        let high = [
            JumpEventMark(kind: .takeoff, frameIndex: 3, ptsUs: Int64.max - 600_000, previousPtsUs: Int64.max - 700_000, nextPtsUs: Int64.max - 500_000),
            JumpEventMark(kind: .landing, frameIndex: 8, ptsUs: Int64.max - 100_000, previousPtsUs: Int64.max - 200_000, nextPtsUs: nil)
        ]
        let lowPreviews = JumpWorkflowPresentation.timingPreview(for: .sj, marks: low)
        let highPreviews = JumpWorkflowPresentation.timingPreview(for: .sj, marks: high)
        XCTAssertEqual(lowPreviews.map(\.id), ["flight"])
        XCTAssertEqual(lowPreviews.map(\.startPtsUs), [300_000])
        XCTAssertEqual(lowPreviews.map(\.endPtsUs), [800_000])
        XCTAssertEqual(lowPreviews.map(\.durationUs), [500_000])
        XCTAssertEqual(lowPreviews.map(\.durationMs), [500.0])
        XCTAssertEqual(highPreviews.map(\.id), ["flight"])
        XCTAssertEqual(highPreviews.map(\.metricKey), ["FLIGHT_TIME_MS"])
        XCTAssertEqual(highPreviews.map(\.startPtsUs), [Int64.max - 600_000])
        XCTAssertEqual(highPreviews.map(\.endPtsUs), [Int64.max - 100_000])
        XCTAssertEqual(highPreviews.map(\.durationUs), [500_000])
        XCTAssertEqual(highPreviews.map(\.durationMs), [500.0])
        XCTAssertEqual(highPreviews.map(\.durationUs), lowPreviews.map(\.durationUs))
    }

    func testTimingPreviewUnsupportedProtocolsEmptyAndInputsUnchanged() {
        let marks = [
            JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000),
            JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 600_000, previousPtsUs: 500_000, nextPtsUs: 700_000),
            JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 1_100_000, previousPtsUs: 1_000_000, nextPtsUs: nil)
        ]
        XCTAssertTrue(JumpWorkflowPresentation.timingPreview(for: .horizontal, marks: marks).isEmpty)
        XCTAssertTrue(JumpWorkflowPresentation.timingPreview(for: .asymmetry, marks: marks).isEmpty)
        let snapshot = marks
        _ = JumpWorkflowPresentation.timingPreview(for: .cmj, marks: marks)
        XCTAssertEqual(marks, snapshot)
    }

    func testBuildIdentityUsesSuppliedMetadataWithoutAssumingSource() {
        let identity = AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "0.0.2", "CFBundleVersion": "2"])
        XCTAssertEqual(identity.version, "0.0.2")
        XCTAssertEqual(identity.build, "2")
        XCTAssertNil(identity.sourceRevision)
        XCTAssertNil(identity.shortSourceRevision)
        let validRevision = "0123456789abcdef0123456789abcdef01234567"
        XCTAssertEqual(validRevision.count, 40)
        let withRevision = AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "0.0.2", "CFBundleVersion": "2", "OpenJumpSourceRevision": validRevision])
        XCTAssertEqual(withRevision.version, "0.0.2")
        XCTAssertEqual(withRevision.build, "2")
        XCTAssertEqual(withRevision.sourceRevision, validRevision)
        XCTAssertEqual(withRevision.shortSourceRevision, String(validRevision.prefix(7)))
    }

    func testBuildIdentityMissingMetadataDoesNotInventVersionOrRevision() {
        XCTAssertNil(AppBuildIdentity(infoDictionary: nil).version)
        XCTAssertNil(AppBuildIdentity(infoDictionary: nil).build)
        XCTAssertNil(AppBuildIdentity(infoDictionary: nil).sourceRevision)
        XCTAssertNil(AppBuildIdentity(infoDictionary: [:]).version)
        XCTAssertNil(AppBuildIdentity(infoDictionary: [:]).build)
        XCTAssertNil(AppBuildIdentity(infoDictionary: [:]).sourceRevision)
        XCTAssertNil(AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "", "CFBundleVersion": "   ", "OpenJumpSourceRevision": "abc123"]).version)
        XCTAssertNil(AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "", "CFBundleVersion": "   ", "OpenJumpSourceRevision": "abc123"]).build)
        XCTAssertNil(AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "", "CFBundleVersion": "   ", "OpenJumpSourceRevision": "abc123"]).sourceRevision)
        let nonString = AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": 123, "CFBundleVersion": 2, "OpenJumpSourceRevision": 12345])
        XCTAssertNil(nonString.version)
        XCTAssertNil(nonString.build)
        XCTAssertNil(nonString.sourceRevision)
        let invalidHex = AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "0.0.2", "CFBundleVersion": "2", "OpenJumpSourceRevision": "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"])
        XCTAssertEqual(invalidHex.version, "0.0.2")
        XCTAssertEqual(invalidHex.build, "2")
        XCTAssertNil(invalidHex.sourceRevision)
        let missingBuild = AppBuildIdentity(infoDictionary: ["CFBundleShortVersionString": "0.0.2"])
        XCTAssertEqual(missingBuild.version, "0.0.2")
        XCTAssertNil(missingBuild.build)
        XCTAssertNil(missingBuild.sourceRevision)
        XCTAssertNotEqual(missingBuild.version, "0.0.1")
    }

    func testFormattedPartsCanonicalHeightMetric() throws {
        let metric = try SavedMetric(key: "HEIGHT_CM", unit: "CENTIMETER", value: 30.64578125, ordinal: 0)
        let snapshot = metric
        let locale = Locale(identifier: "en_US")
        let parts = formattedMetricParts(metric, units: .metric, locale: locale)
        XCTAssertEqual(parts.valueString, "30.646")
        XCTAssertEqual(parts.unitString, "cm")
        XCTAssertEqual(parts.combined, "30.646 cm")
        XCTAssertEqual(formattedMetric(metric, units: .metric, locale: locale), "30.646 cm")
        XCTAssertEqual(metric, snapshot)
    }

    func testFormattedPartsImperialHeightInches() throws {
        let metric = try SavedMetric(key: "HEIGHT_CM", unit: "CENTIMETER", value: 25.4, ordinal: 0)
        let snapshot = metric
        let locale = Locale(identifier: "en_US")
        let parts = formattedMetricParts(metric, units: .unitedStates, locale: locale)
        XCTAssertEqual(parts.valueString, "10")
        XCTAssertEqual(parts.unitString, "in")
        XCTAssertEqual(parts.combined, "10 in")
        XCTAssertEqual(formattedMetric(metric, units: .unitedStates, locale: locale), "10 in")
        XCTAssertEqual(metric, snapshot)
    }

    func testFormattedPartsTimingSecondsAndMillisDefault() throws {
        let metric = try SavedMetric(key: "FLIGHT_TIME_MS", unit: "MILLISECOND", value: 1500, ordinal: 0)
        let snapshot = metric
        let locale = Locale(identifier: "en_US")
        let secondsUnits = UnitProfile(shortLength: .cm, horizontalDistance: .meters, mass: .kilograms, speed: .metersPerSecond, timing: .seconds)
        let seconds = formattedMetricParts(metric, units: secondsUnits, locale: locale)
        XCTAssertEqual(seconds.valueString, "1.5")
        XCTAssertEqual(seconds.unitString, "s")
        XCTAssertEqual(seconds.combined, "1.5 s")
        XCTAssertEqual(formattedMetric(metric, units: secondsUnits, locale: locale), "1.5 s")
        let millis = formattedMetricParts(metric, units: .metric, locale: locale)
        XCTAssertEqual(millis.valueString, "1,500")
        XCTAssertEqual(millis.unitString, "ms")
        XCTAssertEqual(millis.combined, "1,500 ms")
        XCTAssertEqual(formattedMetric(metric, units: .metric, locale: locale), "1,500 ms")
        XCTAssertEqual(metric, snapshot)
    }

    func testFormattedPartsSpeedFeetPerSecond() throws {
        let metric = try SavedMetric(key: "TAKEOFF_VELOCITY_MPS", unit: "METER_PER_SECOND", value: 3.048, ordinal: 0)
        let snapshot = metric
        let locale = Locale(identifier: "en_US")
        let parts = formattedMetricParts(metric, units: .unitedStates, locale: locale)
        XCTAssertEqual(parts.valueString, "10")
        XCTAssertEqual(parts.unitString, "ft/s")
        XCTAssertEqual(parts.combined, "10 ft/s")
        XCTAssertEqual(formattedMetric(metric, units: .unitedStates, locale: locale), "10 ft/s")
        XCTAssertEqual(metric, snapshot)
    }

    func testFormattedPartsMismatchKeepsRawUnit() throws {
        let metric = try SavedMetric(key: "HEIGHT_CM", unit: "METER", value: 12.5, ordinal: 0)
        let snapshot = metric
        let locale = Locale(identifier: "en_US")
        let parts = formattedMetricParts(metric, units: .metric, locale: locale)
        XCTAssertEqual(parts.valueString, "12.5")
        XCTAssertEqual(parts.unitString, "METER")
        XCTAssertEqual(parts.combined, "12.5 METER")
        XCTAssertEqual(formattedMetric(metric, units: .metric, locale: locale), "12.5 METER")
        XCTAssertEqual(metric, snapshot)
    }

    func testFormattedPartsUnknownKeyDecimalCommaPassthrough() throws {
        let metric = try SavedMetric(key: "CUSTOM_X", unit: "CUSTOM_U", value: 30.646, ordinal: 0)
        let snapshot = metric
        let locale = Locale(identifier: "de_DE")
        let parts = formattedMetricParts(metric, units: .metric, locale: locale)
        XCTAssertEqual(parts.valueString, "30,646")
        XCTAssertEqual(parts.unitString, "CUSTOM_U")
        XCTAssertEqual(parts.combined, "30,646 CUSTOM_U")
        XCTAssertEqual(formattedMetric(metric, units: .metric, locale: locale), "30,646 CUSTOM_U")
        XCTAssertEqual(metric, snapshot)
    }

    func testFlowRoutesCoverAllFourStagesWithoutDuplication() {
        XCTAssertEqual(JumpFlowRoute.allCases.count, 4)
        XCTAssertEqual(Set(JumpFlowRoute.allCases).count, 4)
        XCTAssertEqual(JumpFlowRoute.allCases, [.prepare, .obtainVideo, .analyse, .result])
    }

    func testFlowBackPopsValuesInReversePushOrder() {
        var path = JumpWorkflowPresentation.path(to: .result)
        XCTAssertEqual(path, [.prepare, .obtainVideo, .analyse, .result])
        path.removeLast()
        XCTAssertEqual(path.last, .analyse)
        path.removeLast()
        XCTAssertEqual(path.last, .obtainVideo)
        path.removeLast()
        XCTAssertEqual(path.last, .prepare)
        path.removeLast()
        XCTAssertTrue(path.isEmpty)
        XCTAssertEqual(JumpWorkflowPresentation.path(to: .prepare), [.prepare])
        XCTAssertEqual(JumpWorkflowPresentation.path(to: .obtainVideo), [.prepare, .obtainVideo])
        XCTAssertEqual(JumpWorkflowPresentation.path(to: .analyse), [.prepare, .obtainVideo, .analyse])
    }

    func testFlowResumeRoutingRespectsStateWithoutInventingResults() {
        XCTAssertEqual(
            JumpWorkflowPresentation.resumeRoute(hasResult: true, hasManifest: true, hasVideoOrImportActivity: true),
            .result
        )
        XCTAssertEqual(
            JumpWorkflowPresentation.resumeRoute(hasResult: false, hasManifest: true, hasVideoOrImportActivity: true),
            .analyse
        )
        XCTAssertEqual(
            JumpWorkflowPresentation.resumeRoute(hasResult: false, hasManifest: false, hasVideoOrImportActivity: true),
            .obtainVideo
        )
        XCTAssertEqual(
            JumpWorkflowPresentation.resumeRoute(hasResult: false, hasManifest: false, hasVideoOrImportActivity: false),
            .prepare
        )
        // A failed calculation leaves no result behind: indexed work stays
        // on Analyse and notes without a clip stay on Prepare.
        XCTAssertNotEqual(
            JumpWorkflowPresentation.resumeRoute(hasResult: false, hasManifest: true, hasVideoOrImportActivity: false),
            .result
        )
        XCTAssertNotEqual(
            JumpWorkflowPresentation.resumeRoute(hasResult: false, hasManifest: false, hasVideoOrImportActivity: false),
            .result
        )
    }

    func testFlowAutoAdvanceRequiresVisibleActiveExpectedStageAndContent() {
        // Hidden, inactive, wrong-stage, and missing-content all fail closed
        // for both Obtain → Analyse and Analyse → Result. Only the visible,
        // active, expected-stage, content-available combination succeeds, so
        // a hidden tab is never yanked forward and surfaces explicit
        // Continue/Resume/Review instead.
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: false, isActiveScene: true, visibleRoute: .obtainVideo, manifestAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: true, isActiveScene: false, visibleRoute: .obtainVideo, manifestAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: true, isActiveScene: true, visibleRoute: .prepare, manifestAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: true, isActiveScene: true, visibleRoute: .analyse, manifestAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: true, isActiveScene: true, visibleRoute: nil, manifestAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: true, isActiveScene: true, visibleRoute: .obtainVideo, manifestAvailable: false))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: false, isActiveScene: false, visibleRoute: .obtainVideo, manifestAvailable: true))
        XCTAssertTrue(JumpWorkflowPresentation.shouldAutoAdvanceToAnalyse(isVisible: true, isActiveScene: true, visibleRoute: .obtainVideo, manifestAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: false, isActiveScene: true, visibleRoute: .analyse, resultAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: true, isActiveScene: false, visibleRoute: .analyse, resultAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: true, isActiveScene: true, visibleRoute: .obtainVideo, resultAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: true, isActiveScene: true, visibleRoute: .result, resultAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: true, isActiveScene: true, visibleRoute: nil, resultAvailable: true))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: true, isActiveScene: true, visibleRoute: .analyse, resultAvailable: false))
        XCTAssertFalse(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: false, isActiveScene: false, visibleRoute: .analyse, resultAvailable: true))
        XCTAssertTrue(JumpWorkflowPresentation.shouldAutoAdvanceToResult(isVisible: true, isActiveScene: true, visibleRoute: .analyse, resultAvailable: true))
    }

    func testFlowViewerActivatesOnlyWhenVisibleActiveAnalyse() {
        // Truth table: only actually-appeared + scene-active + Analyse
        // attaches playback. Every hidden, inactive, wrong-stage, or root
        // combination pauses without discarding artifacts.
        XCTAssertTrue(JumpWorkflowPresentation.shouldActivateViewer(isVisible: true, isActiveScene: true, visibleRoute: .analyse))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: false, isActiveScene: true, visibleRoute: .analyse))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: true, isActiveScene: false, visibleRoute: .analyse))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: false, isActiveScene: false, visibleRoute: .analyse))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: true, isActiveScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: true, isActiveScene: true, visibleRoute: .prepare))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: true, isActiveScene: true, visibleRoute: .result))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: true, isActiveScene: true, visibleRoute: nil))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: false, isActiveScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.shouldActivateViewer(isVisible: false, isActiveScene: false, visibleRoute: nil))
    }

    func testFlowConfirmedCatalogProtocolRoutesToPrepareRegardlessOfClip() {
        // Unit seam for the View confirm decision: accepted matching routes
        // to Prepare without consulting clip emptiness (retained video stays).
        // Rejected or mismatched protocols yield nil so the caller trims.
        XCTAssertEqual(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: .cmj, confirmed: .cmj), .prepare)
        XCTAssertEqual(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: .sj, confirmed: .sj), .prepare)
        XCTAssertNil(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: .cmj, confirmed: .sj))
        XCTAssertNil(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: .sj, confirmed: .cmj))
        XCTAssertNil(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: nil, confirmed: .cmj))
        XCTAssertNil(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: .cmj, confirmed: nil))
        XCTAssertNil(JumpWorkflowPresentation.routeForConfirmedCatalogProtocol(pending: nil, confirmed: nil))
    }

    // MARK: - C2b camera entry + controls (pure gates, authored-only 0 RUN)
    //
    // Host behavior (AVCaptureVideoPreviewLayer render, facade/State wiring,
    // borrower lifetimes, XCUITest/device) is UNTESTED 0RUN here by contract:
    // these four tests pin only the pure eligibility + control matrix the
    // camera section renders from. Native compile/XCTest/device validation is
    // a future separate permission.

    func testCameraEligibilityRequiresVisibleActiveObtainRouteWithoutAutoStart() {
        // Hidden, inactive, wrong-stage, and backgrounded combinations all
        // fail closed: the preview host and the facade lifecycle never
        // activate implicitly. Only actually-appeared + scene-active +
        // Obtain video is eligible, mirroring the viewer gate shape.
        XCTAssertTrue(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: false, isActiveScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: false, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: false, isActiveScene: false, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: .prepare))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: .analyse))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: .result))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: nil))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: false, isActiveScene: true, visibleRoute: .analyse))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: false, isActiveScene: false, visibleRoute: nil))
        // Eligibility never auto-starts: an eligible-but-idle camera offers
        // only an explicit Record tap (prepare-then-start-if-ready), and a
        // hidden reappear that lands back on idle offers the same manual
        // Record — never a parked stop, never a Use, never recording state.
        let idle = JumpWorkflowPresentation.cameraControls(for: .idle)
        XCTAssertTrue(idle.showsRecord)
        XCTAssertFalse(idle.showsStop)
        XCTAssertFalse(idle.stopEnabled)
        XCTAssertFalse(idle.canUse)
        XCTAssertFalse(idle.canRepeat)
    }

    func testCameraPhaseControlMatrixMirrorsCapturePolicy() {
        // Ready offers Record; starting/recording offer an enabled Stop and
        // never Use; finalizing shows a DISABLED Stop with wait text and
        // never Use/Repeat; recorded offers review + Use + Repeat + Cancel;
        // denied/unavailable offer Retry + Cancel; failed adds Repeat.
        // Every row must agree with the authoritative capture policy.
        let phases: [JumpVideoCapturePhase] = [.idle, .requestingPermission, .preparing, .ready, .starting, .recording, .finalizing, .recorded, .denied, .unavailable, .failed]
        for phase in phases {
            let controls = JumpWorkflowPresentation.cameraControls(for: phase)
            XCTAssertEqual(controls.showsRecord, phase == .idle || phase == .ready, "record row for \(phase)")
            XCTAssertEqual(controls.showsStop, phase == .starting || phase == .recording || phase == .finalizing, "stop row for \(phase)")
            XCTAssertEqual(controls.stopEnabled, JumpVideoCapturePolicy.canStopRecording(phase: phase), "stop gate for \(phase)")
            XCTAssertEqual(controls.canUse, JumpVideoCapturePolicy.canUseRecorded(phase: phase), "use gate for \(phase)")
            XCTAssertEqual(controls.canRepeat, JumpVideoCapturePolicy.canBeginNewRecording(phase: phase), "repeat gate for \(phase)")
            XCTAssertEqual(controls.showsReview, phase == .recorded, "review row for \(phase)")
        }
        let starting = JumpWorkflowPresentation.cameraControls(for: .starting)
        XCTAssertTrue(starting.stopEnabled)
        XCTAssertFalse(starting.canUse)
        let recording = JumpWorkflowPresentation.cameraControls(for: .recording)
        XCTAssertTrue(recording.stopEnabled)
        XCTAssertFalse(recording.canUse)
        let finalizing = JumpWorkflowPresentation.cameraControls(for: .finalizing)
        XCTAssertTrue(finalizing.showsStop)
        XCTAssertFalse(finalizing.stopEnabled)
        XCTAssertFalse(finalizing.canUse)
        XCTAssertFalse(finalizing.canRepeat)
        let recorded = JumpWorkflowPresentation.cameraControls(for: .recorded)
        XCTAssertTrue(recorded.canUse)
        XCTAssertTrue(recorded.canRepeat)
        XCTAssertTrue(recorded.showsCancel)
        XCTAssertTrue(recorded.showsReview)
        XCTAssertFalse(recorded.showsLivePreview)
        for phase in [JumpVideoCapturePhase.denied, .unavailable, .failed] as [JumpVideoCapturePhase] {
            let controls = JumpWorkflowPresentation.cameraControls(for: phase)
            XCTAssertTrue(controls.showsRetry, "retry row for \(phase)")
            XCTAssertTrue(controls.showsCancel, "cancel row for \(phase)")
            XCTAssertFalse(controls.canUse, "never use from \(phase)")
        }
        XCTAssertFalse(JumpWorkflowPresentation.cameraControls(for: .denied).canRepeat)
        XCTAssertFalse(JumpWorkflowPresentation.cameraControls(for: .unavailable).canRepeat)
        XCTAssertTrue(JumpWorkflowPresentation.cameraControls(for: .failed).canRepeat)
    }

    func testCameraUseRequiresFreshRecordedBorrowerAndErrorsNeverDiscardAnalysis() {
        // Use is gated to the finalized recorded phase at BOTH the policy
        // and the matrix level, so a Use tap can never run from finalizing
        // (writer not finalized) even if a stale borrower reference existed.
        // Host borrower behavior (fresh handle per Use, preview borrower
        // torn down before release, no handed-handle release, no dispose) is
        // enforced at the call sites and is UNTESTED 0RUN here by contract.
        for phase in [JumpVideoCapturePhase.idle, .requestingPermission, .preparing, .ready, .starting, .recording, .finalizing, .recorded, .denied, .unavailable, .failed] as [JumpVideoCapturePhase] {
            XCTAssertEqual(JumpVideoCapturePolicy.canUseRecorded(phase: phase), phase == .recorded, "policy use gate for \(phase)")
            XCTAssertEqual(JumpWorkflowPresentation.cameraControls(for: phase).canUse, phase == .recorded, "matrix use gate for \(phase)")
        }
        XCTAssertFalse(JumpWorkflowPresentation.cameraControls(for: .finalizing).canUse)
        XCTAssertFalse(JumpWorkflowPresentation.cameraControls(for: .finalizing).canRepeat)
        // Camera error paths expose no analysis-destructive action: Retry
        // only re-prepares and Cancel only parks the camera attempt (the old
        // analysis survives per the C2a SUCCESS-only commit guarantee).
        // Cancel must never clear an already-parked suspend/dispose intent.
        XCTAssertFalse(JumpVideoCapturePolicy.cancelClearsSuspendDuringFinalizing())
        for phase in [JumpVideoCapturePhase.denied, .unavailable, .failed] as [JumpVideoCapturePhase] {
            let controls = JumpWorkflowPresentation.cameraControls(for: phase)
            XCTAssertTrue(controls.showsRetry)
            XCTAssertTrue(controls.showsCancel)
            XCTAssertFalse(controls.canUse)
            XCTAssertFalse(controls.showsRecord)
            XCTAssertFalse(controls.showsStop)
            XCTAssertFalse(controls.showsReview)
        }
        // The pure layer holds no shared borrower slot: repeated evaluations
        // are stateless and deterministic, so one Use can never implicitly
        // reuse another attempt's handle at this level.
        XCTAssertEqual(JumpWorkflowPresentation.cameraControls(for: .recorded), JumpWorkflowPresentation.cameraControls(for: .recorded))
        XCTAssertEqual(Set(JumpCameraControls.newControlKeys).count, 4)
    }

    func testCameraControlKeysExistInAllLocalesWithoutNewPlistOrGalleryKeys() throws {
        // The camera slice adds EXACTLY these four control labels; status and
        // error text reuse the existing jumps.camera.* keys. Values must be
        // honest per-locale strings (never the raw key, never empty).
        let expectedKeys = ["jumps.camera.record", "jumps.camera.stop", "jumps.camera.useRecording", "jumps.camera.repeat"]
        XCTAssertEqual(JumpCameraControls.newControlKeys, expectedKeys)
        XCTAssertEqual(Set(JumpCameraControls.newControlKeys).count, 4)
        for key in expectedKeys {
            XCTAssertFalse(key.isEmpty)
            XCTAssertTrue(key.hasPrefix("jumps.camera."))
        }
        let locales = ["en", "es", "fr", "de", "it", "pt-BR", "pt-PT", "tr"]
        let oldSentinels = ["jumps.camera.ready", "jumps.camera.recording", "jumps.camera.review", "jumps.flow.continue", "common.cancel"]
        for locale in locales {
            guard let lproj = Bundle.main.path(forResource: locale, ofType: "lproj") else {
                XCTFail("missing \(locale).lproj in host bundle")
                continue
            }
            let stringsURL = URL(fileURLWithPath: lproj).appendingPathComponent("Localizable.strings")
            let text = try String(contentsOf: stringsURL, encoding: .utf8)
            let lines = text.components(separatedBy: .newlines)
            let keyLines = lines.filter { $0.hasPrefix("\"") }
            // Old 324 keys plus exactly the 4 additive camera control keys.
            XCTAssertEqual(keyLines.count, 328, "key count for \(locale)")
            for key in expectedKeys {
                let matches = keyLines.filter { $0.hasPrefix("\"\(key)\" = \"") }
                XCTAssertEqual(matches.count, 1, "single entry for \(key) in \(locale)")
                guard let line = matches.first else { continue }
                let prefix = "\"\(key)\" = \""
                let remainder = String(line.dropFirst(prefix.count))
                XCTAssertTrue(remainder.hasSuffix("\";"), "value terminator for \(key) in \(locale)")
                let value = String(remainder.dropLast(2))
                XCTAssertFalse(value.isEmpty, "nonempty value for \(key) in \(locale)")
                XCTAssertNotEqual(value, key, "localized (not placeholder) value for \(key) in \(locale)")
            }
            for old in oldSentinels {
                XCTAssertEqual(keyLines.filter { $0.hasPrefix("\"\(old)\" = \"") }.count, 1, "preserved old key \(old) in \(locale)")
            }
        }
        // No new InfoPlist/mic/gallery surface through this slice: the camera
        // usage description predates C2b, and mic + photo-write keys stay out.
        XCTAssertNotNil(Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription"))
        XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription"))
        XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: "NSPhotoLibraryAddUsageDescription"))
        XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: "NSPhotoLibraryUsageDescription"))
    }
}
