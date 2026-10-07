import Foundation
import XCTest
@testable import OpenJumpApple

/// S4 workflow contracts: frame/PTS gating, marking invalidation, save
/// idempotency and single-trial repetition. Uses the real media fixture,
/// a disposable SQLite store and an isolated preferences suite; never the
/// production database or shared defaults. Awaits stay outside assertion
/// autoclosures and every wait is bounded.
final class JumpWorkflowStateTests: XCTestCase {
    @MainActor
    private func isolatedApp(store: SQLiteStore, athletes: [Athlete]) throws -> (AppState, UserDefaults, String) {
        let suite = "openjump-s4-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let app = AppState()
        app.preferences = AppPreferences(defaults: defaults, namespace: suite)
        app.store = store
        app.athletes = athletes
        return (app, defaults, suite)
    }

    @MainActor
    private func waitFor(timeoutNanoseconds: UInt64, pollNanoseconds: UInt64 = 50_000_000, condition: () -> Bool) async -> Bool {
        var remaining = timeoutNanoseconds
        while true {
            if condition() { return true }
            guard remaining > pollNanoseconds else { return condition() }
            remaining -= pollNanoseconds
            try? await Task.sleep(nanoseconds: pollNanoseconds)
        }
    }

    @MainActor
    func testInitialGatingTruncatesNotesAndKeepsSelectedNextPolicy() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-s4-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "S4 Athlete")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)

        XCTAssertFalse(workflow.canMarkDisplayedFrame)
        XCTAssertFalse(workflow.canCalculate)
        XCTAssertNil(workflow.savedMeasurement)
        XCTAssertEqual(workflow.requiredEvents, [.movementStart, .takeoff, .landing])
        XCTAssertEqual(TemporalJumpDraft.requiredEvents(for: .sj), [.takeoff, .landing])
        XCTAssertEqual(TemporalJumpDraft.requiredEvents(for: .dropJump), [.initialContact, .takeoff, .landing])
        XCTAssertEqual(workflow.selectedEvent, nil)

        workflow.selectEvent(.takeoff)
        XCTAssertEqual(workflow.selectedEvent, .takeoff)
        workflow.selectEvent(.landing)
        XCTAssertEqual(workflow.selectedEvent, .landing)

        workflow.setNotes(String(repeating: "n", count: 600))
        XCTAssertEqual(workflow.notes.count, 500)
        workflow.setRealtimeDeclared(true)
        XCTAssertTrue(workflow.realtimeDeclared)
        workflow.setRealtimeDeclared(false)
        XCTAssertFalse(workflow.realtimeDeclared)

        let videoBefore = workflow.video
        workflow.startAnotherTrial(using: app)
        XCTAssertNil(workflow.savedMeasurement)
        XCTAssertEqual(workflow.video?.id, videoBefore?.id)
        XCTAssertTrue(workflow.events.isEmpty)
    }

    func testFittedFrameSizeCapsHeightWithoutCropping() {
        let wide = JumpWorkflowPresentation.fittedFrameSize(imageWidth: 640, imageHeight: 480, availableWidth: 390)
        XCTAssertLessThanOrEqual(wide.height, JumpWorkflowPresentation.maxFrameHeight)
        XCTAssertEqual(wide.width, 390, accuracy: 1e-6)
        XCTAssertEqual(wide.height / wide.width, 480 / 640, accuracy: 1e-6)

        let tall = JumpWorkflowPresentation.fittedFrameSize(imageWidth: 480, imageHeight: 960, availableWidth: 390)
        XCTAssertEqual(tall.height, JumpWorkflowPresentation.maxFrameHeight, accuracy: 1e-6)
        XCTAssertEqual(tall.width, 195, accuracy: 1e-6)

        let fallback = JumpWorkflowPresentation.fittedFrameSize(imageWidth: 0, imageHeight: 0, availableWidth: 390)
        XCTAssertEqual(fallback.height, JumpWorkflowPresentation.placeholderHeight)
    }

    func testCMJGoldenDerivesFromSharedEngine() throws {
        let times: [Int64] = [100_000, 200_000, 300_000, 400_000, 500_000, 600_000, 700_000, 800_000, 900_000]
        let frameForKind: [JumpEventKind: Int] = [.movementStart: 0, .takeoff: 3, .landing: 8]
        let events = TemporalJumpDraft.requiredEvents(for: .cmj).map { kind -> JumpEventMark in
            let frame = frameForKind[kind]!
            return JumpEventMark(kind: kind, frameIndex: frame, ptsUs: times[frame], previousPtsUs: frame == 0 ? nil : times[frame - 1], nextPtsUs: frame == times.count - 1 ? nil : times[frame + 1])
        }
        let draft = TemporalJumpDraft(sessionKey: UUID().uuidString, ownerID: UUID(), protocolKey: .cmj, side: nil, dropHeightCm: nil, recordedAt: Date(timeIntervalSince1970: 1_700_000_000), notes: nil, source: .files, sourceFrameCount: times.count, sourceOriginUs: times[0], temporalState: .realtimeDeclared, events: events)
        let metrics = try TemporalJumpEngine.calculate(draft: draft)
        XCTAssertEqual(metrics.map(\.key), ["HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"])
        XCTAssertEqual(metrics[0].value, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(metrics[1].value, 500, accuracy: 1e-10)
        XCTAssertEqual(metrics[4].value, 1.0215260416666667, accuracy: 1e-10)
        let primary = ProtocolPresentation.primaryMetric(in: metrics, protocolKey: .cmj)
        XCTAssertEqual(primary?.key, "HEIGHT_CM")
    }

    func testFixtureSourceBindingRejectsOutOfRangeFrames() async throws {
        let sourceURL = try await MediaFixtureFactory.make(cadence: .constant)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        let video = try await JumpVideoImporter.importFile(sourceURL)
        defer { video.dispose() }
        let service = JumpVideoService()
        let manifest = try await service.inspect(video)
        let pts = manifest.frames.map(\.ptsUs)
        XCTAssertEqual(pts, [0, 33_333, 66_667, 100_000, 133_333, 166_667])
        XCTAssertEqual(manifest.sourceID, video.id)

        do {
            _ = try await service.frame(manifest: manifest, index: -1)
            XCTFail("negative index must be rejected")
        } catch let error as JumpVideoServiceError {
            XCTAssertEqual(error, .frameIndexOutOfRange)
        }
        do {
            _ = try await service.frame(manifest: manifest, index: manifest.frames.count)
            XCTFail("past-the-end index must be rejected")
        } catch let error as JumpVideoServiceError {
            XCTAssertEqual(error, .frameIndexOutOfRange)
        }
        let presented = try await service.frame(manifest: manifest, index: 2)
        XCTAssertEqual(presented.sourceID, video.id)
        XCTAssertEqual(presented.index, 2)
        XCTAssertEqual(presented.ptsUs, manifest.frames[2].ptsUs)
    }

    @MainActor
    func testSaveThenAnotherTrialKeepsSourceWithFreshSession() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-s4-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "S4 Repeat")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)

        let sourceURL = try await MediaFixtureFactory.make(cadence: .constant)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        workflow.requestFile(sourceURL, app: app)

        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(manifestReady, "synthetic fixture must index within the bounded wait")
        let manifest = try XCTUnwrap(workflow.manifest)
        let video = try XCTUnwrap(workflow.video)
        XCTAssertEqual(manifest.sourceID, video.id)
        let videoID = video.id

        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(frameReady, "first exact frame must present within the bounded wait")

        let targets = [1, 3, 5]
        let kinds = workflow.requiredEvents
        XCTAssertEqual(kinds.count, 3)
        for (position, kind) in kinds.enumerated() {
            workflow.selectEvent(kind)
            workflow.requestFrame(targets[position])
            let shown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
                guard let presented = workflow.presentedFrame else { return false }
                return presented.sourceID == videoID && presented.index == targets[position] && presented.ptsUs == manifest.frames[targets[position]].ptsUs
            })
            XCTAssertTrue(shown, "exact frame must match source identity before marking")
            workflow.markSelectedEvent()
            let recorded = workflow.event(for: kind)
            XCTAssertEqual(recorded?.frameIndex, targets[position])
        }
        workflow.setRealtimeDeclared(true)
        workflow.setNotes("first trial")
        XCTAssertTrue(workflow.canCalculate(for: app))
        workflow.calculate(using: app)
        let calculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        XCTAssertTrue(calculated, "shared calculation must finish within the bounded wait")

        var didOpenHistory = false
        await workflow.save(using: app, openHistory: { didOpenHistory = true })
        let first = try XCTUnwrap(workflow.savedMeasurement)
        XCTAssertTrue(didOpenHistory)
        let firstGraph = try await store.temporalAnalysis(measurementID: first.id)
        let firstEvents = try XCTUnwrap(firstGraph).events
        XCTAssertEqual(firstEvents.map(\.frameIndex), targets)

        let sourceBeforeRepeat = workflow.video?.id
        workflow.startAnotherTrial(using: app)
        XCTAssertNil(workflow.savedMeasurement)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.video?.id, sourceBeforeRepeat)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        XCTAssertTrue(workflow.events.isEmpty)
        XCTAssertTrue(workflow.notes.isEmpty)
        XCTAssertFalse(workflow.realtimeDeclared)
        XCTAssertNil(workflow.metrics)
        XCTAssertEqual(workflow.selectedEvent, workflow.requiredEvents.first)

        let secondFrameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(secondFrameReady, "repeated trial must re-request the kept frame")
        for (position, kind) in kinds.enumerated() {
            workflow.selectEvent(kind)
            workflow.requestFrame(targets[position])
            let shown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
                guard let presented = workflow.presentedFrame else { return false }
                return presented.sourceID == videoID && presented.index == targets[position]
            })
            XCTAssertTrue(shown, "second trial frames must stay source-bound")
            workflow.markSelectedEvent()
        }
        workflow.setRealtimeDeclared(true)
        workflow.calculate(using: app)
        let recalculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        XCTAssertTrue(recalculated)
        await workflow.save(using: app, openHistory: {})
        let second = try XCTUnwrap(workflow.savedMeasurement)
        XCTAssertNotEqual(second.id, first.id)
        XCTAssertNotEqual(second.sessionKey, first.sessionKey)

        let retainedFirst = try await store.temporalAnalysis(measurementID: first.id)
        XCTAssertEqual(try XCTUnwrap(retainedFirst).events, firstEvents)
        let history = try await store.history()
        XCTAssertEqual(history.items.count, 2)
    }

    @MainActor
    func testArchivedOwnerBlocksNewCalculation() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-s4-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let first = try await store.createAthlete(name: "First")
        _ = try await store.createAthlete(name: "Second")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [first])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        workflow.requestOwner(first.id, app: app)
        XCTAssertEqual(workflow.activeOwnerID, first.id)
        try await store.setArchived(first.id, archived: true)
        app.athletes = try await store.athletes(includeArchived: true)
        XCTAssertFalse(workflow.canCalculate(for: app))
    }
}
