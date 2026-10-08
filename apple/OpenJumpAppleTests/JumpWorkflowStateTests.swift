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
        // Independent geometry: a 480:960 source capped at 380pt fits to
        // 190pt wide, not 195pt (which would imply a 390pt height).
        XCTAssertEqual(tall.width, 190, accuracy: 1e-6)
        XCTAssertEqual(tall.width / tall.height, 0.5, accuracy: 1e-6)

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

    @MainActor
    func testTemporalReferenceImportMarkCalculateSaveReopen() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-p0-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "P0 Temporal")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }
        XCTAssertEqual(workflow.requiredEvents, [.movementStart, .takeoff, .landing])

        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        workflow.requestFile(sourceURL, app: app)

        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        if !manifestReady {
            XCTFail("temporalReference fixture must index within the bounded wait")
            return
        }
        let manifest = try XCTUnwrap(workflow.manifest)
        let video = try XCTUnwrap(workflow.video)
        XCTAssertEqual(manifest.sourceID, video.id)
        let videoID = video.id
        XCTAssertEqual(manifest.frames.count, 6)
        XCTAssertEqual(manifest.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])
        guard manifest.frames.count == 6 else {
            XCTFail("temporalReference manifest must contain exactly 6 frames")
            return
        }

        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        if !frameReady {
            XCTFail("first exact frame must present within the bounded wait")
            return
        }

        let targets = [0, 2, 4]
        let expectedPTS: [Int64] = [0, 200_000, 700_000]
        let kinds = workflow.requiredEvents
        XCTAssertEqual(kinds, [.movementStart, .takeoff, .landing])
        XCTAssertEqual(kinds.count, 3)
        guard kinds == [.movementStart, .takeoff, .landing], kinds.count == 3,
              targets.count == 3, expectedPTS.count == 3 else {
            XCTFail("temporalReference targets must align exactly with required events")
            return
        }
        for (position, kind) in kinds.enumerated() {
            guard targets.indices.contains(position), expectedPTS.indices.contains(position) else {
                XCTFail("target/PTS index out of range")
                return
            }
            let target = targets[position]
            let expected = expectedPTS[position]
            guard manifest.frames.indices.contains(target) else {
                XCTFail("temporalReference frame \(target) out of range")
                return
            }
            let expectedFramePTS = manifest.frames[target].ptsUs
            workflow.selectEvent(kind)
            workflow.requestFrame(target)
            let shown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
                guard let presented = workflow.presentedFrame else { return false }
                return presented.sourceID == videoID && presented.index == target && presented.ptsUs == expectedFramePTS
            })
            if !shown {
                XCTFail("exact frame \(target) must match source identity before marking")
                return
            }
            XCTAssertEqual(workflow.presentedFrame?.ptsUs, expected)
            XCTAssertTrue(workflow.canMarkDisplayedFrame)
            workflow.markSelectedEvent()
            let recorded = workflow.event(for: kind)
            XCTAssertEqual(recorded?.frameIndex, target)
            XCTAssertEqual(recorded?.ptsUs, expected)
            XCTAssertEqual(workflow.video?.id, videoID)
            XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        }

        workflow.setRealtimeDeclared(true)
        XCTAssertTrue(workflow.realtimeDeclared)
        XCTAssertTrue(workflow.canCalculate(for: app))
        workflow.calculate(using: app)
        let calculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        if !calculated {
            XCTFail("shared calculation must finish within the bounded wait")
            return
        }
        let metrics = try XCTUnwrap(workflow.metrics)
        XCTAssertEqual(metrics.count, 5)
        XCTAssertEqual(metrics.map(\.key), ["HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"])
        XCTAssertEqual(metrics.map(\.unit), ["CENTIMETER", "MILLISECOND", "METER_PER_SECOND", "MILLISECOND", "METER_PER_SECOND"])
        guard metrics.count == 5 else {
            XCTFail("temporalReference metrics must contain exactly 5 ordered values")
            return
        }
        XCTAssertEqual(metrics[0].value, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(metrics[1].value, 500, accuracy: 1e-10)
        XCTAssertEqual(metrics[2].value, 2.4516625, accuracy: 1e-10)
        XCTAssertEqual(metrics[3].value, 200, accuracy: 1e-10)
        XCTAssertEqual(metrics[4].value, 1.5322890625, accuracy: 1e-10)

        var didOpenHistory = false
        await workflow.save(using: app, openHistory: { didOpenHistory = true })
        let saved = try XCTUnwrap(workflow.savedMeasurement)
        XCTAssertTrue(didOpenHistory)
        XCTAssertEqual(saved.ownerID, athlete.id)
        XCTAssertEqual(saved.protocolKey, .cmj)

        let reopened = try SQLiteStore(databaseURL: url)
        let graph = try await reopened.temporalAnalysis(measurementID: saved.id)
        let analysis = try XCTUnwrap(graph)
        XCTAssertEqual(analysis.measurementID, saved.id)
        XCTAssertEqual(analysis.source, .files)
        XCTAssertEqual(analysis.sourceFrameCount, 6)
        XCTAssertEqual(analysis.sourceOriginUs, 0)
        XCTAssertEqual(analysis.temporalState, .realtimeDeclared)
        XCTAssertEqual(analysis.events.map(\.kind), kinds)
        XCTAssertEqual(analysis.events.map(\.frameIndex), targets)
        XCTAssertEqual(analysis.events.map(\.ptsUs), expectedPTS)
        XCTAssertEqual(analysis.events.map(\.previousPtsUs), [nil, 100_000, 400_000])
        XCTAssertEqual(analysis.events.map(\.nextPtsUs), [100_000, 400_000, 800_000])
        XCTAssertEqual(saved.metrics, metrics)

        let history = try await reopened.history()
        XCTAssertEqual(history.items.count, 1)
        let item = try XCTUnwrap(history.items.first)
        XCTAssertEqual(item.id, saved.id)
        XCTAssertEqual(item.sessionKey, saved.sessionKey)
        XCTAssertEqual(item.ownerID, athlete.id)
    }

    @MainActor
    func testCanContinuePreparationGateUsesExistingSetupValidation() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-flow-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Flow Prep")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        XCTAssertEqual(workflow.activeOwnerID, athlete.id)
        XCTAssertTrue(workflow.canContinuePreparation(for: app))

        workflow.requestOwner(nil, app: app)
        XCTAssertNil(workflow.activeOwnerID)
        XCTAssertFalse(workflow.canContinuePreparation(for: app))

        workflow.requestOwner(athlete.id, app: app)
        XCTAssertTrue(workflow.canContinuePreparation(for: app))
        try await store.setArchived(athlete.id, archived: true)
        app.athletes = try await store.athletes(includeArchived: true)
        XCTAssertFalse(workflow.canContinuePreparation(for: app))
        try await store.setArchived(athlete.id, archived: false)
        app.athletes = try await store.athletes(includeArchived: true)
        XCTAssertTrue(workflow.canContinuePreparation(for: app))

        workflow.requestProtocol(.dropJump, app: app)
        XCTAssertEqual(workflow.setup.protocolKey, .dropJump)
        XCTAssertFalse(workflow.canContinuePreparation(for: app))
        workflow.requestDropHeight("30", app: app)
        XCTAssertTrue(workflow.canContinuePreparation(for: app))
    }

    @MainActor
    func testAnalyseViewerLifecyclePreservesClipMarksNotesAndRestoresExactFrame() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-flow-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Flow Lifecycle")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }

        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        workflow.requestFile(sourceURL, app: app)

        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(manifestReady, "temporalReference fixture must index within the bounded wait")
        let manifest = try XCTUnwrap(workflow.manifest)
        let video = try XCTUnwrap(workflow.video)
        let videoID = video.id
        XCTAssertEqual(manifest.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])

        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(frameReady, "first exact frame must present within the bounded wait")

        let targets = [0, 2, 4]
        for (position, kind) in workflow.requiredEvents.enumerated() {
            let target = targets[position]
            workflow.selectEvent(kind)
            workflow.requestFrame(target)
            let shown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
                guard let presented = workflow.presentedFrame else { return false }
                return presented.sourceID == videoID && presented.index == target && presented.ptsUs == manifest.frames[target].ptsUs
            })
            XCTAssertTrue(shown, "exact frame must match source identity before marking")
            workflow.markSelectedEvent()
        }
        workflow.setRealtimeDeclared(true)
        workflow.setNotes("lifecycle note")
        XCTAssertTrue(workflow.canCalculate(for: app))
        workflow.calculate(using: app)
        let calculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        XCTAssertTrue(calculated, "shared calculation must finish within the bounded wait")

        workflow.viewerDisappeared()
        XCTAssertFalse(workflow.isViewerActive)
        XCTAssertFalse(workflow.canMarkDisplayedFrame)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        XCTAssertEqual(workflow.events.map(\.frameIndex), targets)
        XCTAssertEqual(workflow.frameIndex, targets.last)
        XCTAssertEqual(workflow.notes, "lifecycle note")
        XCTAssertTrue(workflow.realtimeDeclared)
        XCTAssertNotNil(workflow.metrics)

        workflow.viewerAppeared()
        let restored = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
            guard let presented = workflow.presentedFrame else { return false }
            return presented.sourceID == videoID && presented.index == workflow.frameIndex && presented.ptsUs == manifest.frames[workflow.frameIndex].ptsUs
        })
        XCTAssertTrue(restored, "exact frame must restore at the kept index after reappearing")
        XCTAssertTrue(workflow.isViewerActive)
        XCTAssertTrue(workflow.canMarkDisplayedFrame)

        workflow.suspendViewerForBackground()
        XCTAssertFalse(workflow.isViewerActive)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.notes, "lifecycle note")
        workflow.resumeViewerFromBackground()
        let resumed = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(resumed, "exact frame must restore after foreground resume")

        // Existing notes semantics are unchanged: editing notes invalidates
        // the calculated draft and requires recalculation before saving.
        workflow.setNotes("lifecycle note edited")
        XCTAssertNil(workflow.metrics)
    }

    @MainActor
    func testAcceptedProtocolSwitchPreservesClipWhileClearingDraftMarks() async throws {
        // Documents State acceptance preserving video: a dirty draft with a
        // retained clip defers a different supported protocol, then confirm
        // updates the protocol while keeping video/manifest source/PTS and
        // clearing old events/notes/metrics per existing restartDraft.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-flow-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "Flow Protocol")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        XCTAssertEqual(workflow.activeOwnerID, athlete.id)
        XCTAssertEqual(workflow.setup.protocolKey, .cmj)

        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }
        workflow.requestFile(sourceURL, app: app)

        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(manifestReady, "temporalReference fixture must index within the bounded wait")
        let manifest = try XCTUnwrap(workflow.manifest)
        let video = try XCTUnwrap(workflow.video)
        let videoID = video.id
        XCTAssertEqual(manifest.sourceID, videoID)
        XCTAssertEqual(manifest.frames.count, 6)
        XCTAssertEqual(manifest.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])

        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(frameReady, "first exact frame must present within the bounded wait")

        let targets = [0, 2, 4]
        let expectedPTS: [Int64] = [0, 200_000, 700_000]
        let kinds = workflow.requiredEvents
        XCTAssertEqual(kinds, [.movementStart, .takeoff, .landing])
        for (position, kind) in kinds.enumerated() {
            let target = targets[position]
            let expected = expectedPTS[position]
            let expectedFramePTS = manifest.frames[target].ptsUs
            XCTAssertEqual(expectedFramePTS, expected, "temporalReference PTS must match the isolated oracle")
            workflow.selectEvent(kind)
            workflow.requestFrame(target)
            let shown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
                guard let presented = workflow.presentedFrame else { return false }
                return presented.sourceID == videoID && presented.index == target && presented.ptsUs == expectedFramePTS
            })
            XCTAssertTrue(shown, "exact frame must match source identity before marking")
            XCTAssertEqual(workflow.presentedFrame?.ptsUs, expected)
            workflow.markSelectedEvent()
            let recorded = workflow.event(for: kind)
            XCTAssertEqual(recorded?.frameIndex, target)
            XCTAssertEqual(recorded?.ptsUs, expected)
        }
        workflow.setRealtimeDeclared(true)
        workflow.setNotes("dirty protocol note")
        XCTAssertTrue(workflow.canCalculate(for: app))
        workflow.calculate(using: app)
        let calculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        XCTAssertTrue(calculated, "shared calculation must finish within the bounded wait")
        let metricsBefore = try XCTUnwrap(workflow.metrics)
        XCTAssertEqual(metricsBefore.count, 5)
        XCTAssertFalse(workflow.events.isEmpty)
        XCTAssertTrue(workflow.hasUnsavedMarks)
        XCTAssertNil(workflow.savedMeasurement)

        workflow.requestProtocol(.sj, app: app)
        XCTAssertTrue(workflow.showDiscardConfirmation, "dirty marks must defer the supported protocol switch")
        XCTAssertEqual(workflow.setup.protocolKey, .cmj, "deferred setup must stay unchanged before confirm")
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)

        workflow.confirmDiscard()
        XCTAssertFalse(workflow.showDiscardConfirmation)
        XCTAssertEqual(workflow.setup.protocolKey, .sj, "confirmed protocol must update")
        XCTAssertEqual(workflow.video?.id, videoID, "confirmed setup must retain the clip")
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        XCTAssertEqual(workflow.manifest?.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])
        XCTAssertTrue(workflow.events.isEmpty, "restartDraft must clear old marks")
        XCTAssertEqual(workflow.notes, "")
        XCTAssertFalse(workflow.realtimeDeclared)
        XCTAssertNil(workflow.metrics, "restartDraft must clear old metrics")
        XCTAssertNil(workflow.savedMeasurement)
        XCTAssertEqual(workflow.activeOwnerID, athlete.id, "owner must survive the accepted protocol switch")
    }

    @MainActor
    func testCapturedStagingCancelPreservesOldAnalysis() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-c2a-cancel-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "C2a Cancel")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }
        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        workflow.requestFile(sourceURL, app: app)
        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(manifestReady)
        let manifest = try XCTUnwrap(workflow.manifest)
        let video = try XCTUnwrap(workflow.video)
        let videoID = video.id
        let oldPTS = manifest.frames.map(\.ptsUs)
        XCTAssertEqual(oldPTS, [0, 100_000, 200_000, 400_000, 700_000, 800_000])
        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(frameReady)
        workflow.selectEvent(.movementStart)
        workflow.requestFrame(0)
        let shown0 = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
            guard let presented = workflow.presentedFrame else { return false }
            return presented.sourceID == videoID && presented.index == 0
        })
        XCTAssertTrue(shown0)
        workflow.markSelectedEvent()
        workflow.setRealtimeDeclared(true)
        workflow.setNotes("old note")
        XCTAssertTrue(workflow.hasUnsavedMarks)
        workflow.calculate(using: app)
        let calculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        XCTAssertTrue(calculated)
        let oldMetricsCount = try XCTUnwrap(workflow.metrics).count
        XCTAssertEqual(oldMetricsCount, 5)
        let (captureDirectory, captureURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptByLease = false
        defer {
            if !sweptByLease { try? FileManager.default.removeItem(at: captureDirectory) }
        }
        try Data("pending".utf8).write(to: captureURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: captureDirectory, fileURL: captureURL)
        lease.markWriterFinalized()
        guard let deferredBorrower = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend deferred borrower")
            return
        }
        workflow.requestCapturedFile(borrower: deferredBorrower, app: app)
        XCTAssertTrue(workflow.showDiscardConfirmation)
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        workflow.cancelDiscard()
        XCTAssertFalse(workflow.showDiscardConfirmation)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.frames.map(\.ptsUs), oldPTS)
        XCTAssertEqual(workflow.events.count, 1)
        XCTAssertEqual(workflow.notes, "old note")
        XCTAssertTrue(workflow.realtimeDeclared)
        XCTAssertEqual(workflow.metrics?.count, 5)
        XCTAssertNil(workflow.savedMeasurement)
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        let entered = expectation(description: "captured copy entered")
        let workerExited = expectation(description: "captured worker exited")
        let gate = DispatchSemaphore(value: 0)
        JumpVideoImporter.workerExitForTesting = { workerExited.fulfill() }
        JumpVideoImporter.coordinationDriverForTesting = { url, accessor, _ in
            entered.fulfill()
            gate.wait()
            accessor(url)
        }
        defer {
            gate.signal()
            JumpVideoImporter.coordinationDriverForTesting = nil
            JumpVideoImporter.workerExitForTesting = nil
        }
        let fixtureURL = try await MediaFixtureFactory.make(cadence: .constant)
        let fixtureDirectory = fixtureURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: fixtureDirectory) }
        let fixtureBytes = try Data(contentsOf: fixtureURL)
        try fixtureBytes.write(to: captureURL)
        guard let activeBorrower = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend active borrower")
            return
        }
        workflow.requestCapturedFile(borrower: activeBorrower, app: app)
        XCTAssertTrue(workflow.showDiscardConfirmation)
        workflow.confirmDiscard()
        XCTAssertTrue(workflow.isImporting)
        await fulfillment(of: [entered], timeout: 2)
        workflow.cancelCapturedImport()
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        XCTAssertEqual(workflow.events.count, 1)
        XCTAssertEqual(workflow.notes, "old note")
        gate.signal()
        await fulfillment(of: [workerExited], timeout: 2)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.frames.map(\.ptsUs), oldPTS)
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        XCTAssertNil(workflow.savedMeasurement)
        lease.markDiscard()
        sweptByLease = false
    }

    @MainActor
    func testCapturedCopyAndIndexFailuresPreserveOldAnalysis() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-c2a-fail-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "C2a Fail")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }
        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        workflow.requestFile(sourceURL, app: app)
        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(manifestReady)
        let video = try XCTUnwrap(workflow.video)
        let videoID = video.id
        let oldPath = video.url.path
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPath))
        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(frameReady)
        workflow.selectEvent(.takeoff)
        workflow.requestFrame(2)
        let shown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
            guard let presented = workflow.presentedFrame else { return false }
            return presented.sourceID == videoID && presented.index == 2
        })
        XCTAssertTrue(shown)
        workflow.markSelectedEvent()
        workflow.setNotes("keep me")
        XCTAssertTrue(workflow.hasUnsavedMarks)
        let (badDirectory, badURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptBad = false
        defer {
            if !sweptBad { try? FileManager.default.removeItem(at: badDirectory) }
        }
        try Data("pending".utf8).write(to: badURL)
        let badLease = JumpVideoCaptureLease(ownedDirectory: badDirectory, fileURL: badURL)
        badLease.markWriterFinalized()
        guard let releasedBorrower = badLease.acquireBorrower() else {
            XCTFail("finalized lease must vend borrower")
            return
        }
        releasedBorrower.release()
        XCTAssertNil(releasedBorrower.fileURL)
        workflow.requestCapturedFile(borrower: releasedBorrower, app: app)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        XCTAssertEqual(workflow.events.count, 1)
        XCTAssertEqual(workflow.notes, "keep me")
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPath))
        badLease.markDiscard()
        sweptBad = false
        let (invalidDirectory, invalidURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptInvalid = false
        defer {
            if !sweptInvalid { try? FileManager.default.removeItem(at: invalidDirectory) }
        }
        try Data("regular nonempty but not a video".utf8).write(to: invalidURL)
        let invalidLease = JumpVideoCaptureLease(ownedDirectory: invalidDirectory, fileURL: invalidURL)
        invalidLease.markWriterFinalized()
        guard let invalidBorrower = invalidLease.acquireBorrower() else {
            XCTFail("finalized lease must vend invalid borrower")
            return
        }
        workflow.requestCapturedFile(borrower: invalidBorrower, app: app)
        XCTAssertTrue(workflow.showDiscardConfirmation)
        workflow.confirmDiscard()
        let failed = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.errorKey != nil && !workflow.isBusy })
        XCTAssertTrue(failed)
        XCTAssertEqual(workflow.video?.id, videoID)
        XCTAssertEqual(workflow.manifest?.sourceID, videoID)
        XCTAssertEqual(workflow.events.count, 1)
        XCTAssertEqual(workflow.notes, "keep me")
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPath))
        invalidLease.markDiscard()
        sweptInvalid = false
    }

    @MainActor
    func testCapturedSuccessCommitsCameraSourceWithExactFrameAndSave() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-c2a-success-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "C2a Success")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }
        let oldFixtureURL = try await MediaFixtureFactory.make(cadence: .constant)
        let oldFixtureDirectory = oldFixtureURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: oldFixtureDirectory) }
        workflow.requestFile(oldFixtureURL, app: app)
        let oldReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(oldReady)
        let oldVideo = try XCTUnwrap(workflow.video)
        let oldManifest = try XCTUnwrap(workflow.manifest)
        let oldVideoID = oldVideo.id
        let oldPath = oldVideo.url.path
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPath))
        let oldFrameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(oldFrameReady)
        workflow.selectEvent(.movementStart)
        workflow.requestFrame(1)
        let oldShown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
            guard let presented = workflow.presentedFrame else { return false }
            return presented.sourceID == oldVideoID && presented.index == 1
        })
        XCTAssertTrue(oldShown)
        workflow.markSelectedEvent()
        workflow.setNotes("old dirty")
        workflow.setRealtimeDeclared(true)
        XCTAssertTrue(workflow.hasUnsavedMarks)
        let newFixtureURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let newFixtureDirectory = newFixtureURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: newFixtureDirectory) }
        let newBytes = try Data(contentsOf: newFixtureURL)
        let (captureDirectory, captureURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptCapture = false
        defer {
            if !sweptCapture { try? FileManager.default.removeItem(at: captureDirectory) }
        }
        try newBytes.write(to: captureURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: captureDirectory, fileURL: captureURL)
        lease.markWriterFinalized()
        guard let borrower = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend borrower")
            return
        }
        var retainedOldVideo: ImportedJumpVideo? = oldVideo
        var retainedOldManifest: JumpVideoManifest? = oldManifest
        workflow.requestCapturedFile(borrower: borrower, app: app)
        XCTAssertTrue(workflow.showDiscardConfirmation)
        XCTAssertEqual(workflow.video?.id, oldVideoID)
        workflow.confirmDiscard()
        let committed = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: {
            guard let current = workflow.video else { return false }
            return current.id != oldVideoID && current.source == .camera && workflow.manifest?.sourceID == current.id
        })
        XCTAssertTrue(committed)
        let newVideo = try XCTUnwrap(workflow.video)
        let newManifest = try XCTUnwrap(workflow.manifest)
        XCTAssertEqual(newVideo.source, .camera)
        XCTAssertEqual(workflow.sourceNameKey, "jumps.source.CAMERA")
        XCTAssertNotEqual(newVideo.id, oldVideoID)
        XCTAssertEqual(newManifest.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])
        XCTAssertEqual(newManifest.sourceID, newVideo.id)
        XCTAssertTrue(workflow.events.isEmpty)
        XCTAssertEqual(workflow.notes, "")
        XCTAssertFalse(workflow.realtimeDeclared)
        XCTAssertNil(workflow.metrics)
        XCTAssertNil(workflow.savedMeasurement)
        XCTAssertEqual(workflow.activeOwnerID, athlete.id)
        XCTAssertEqual(workflow.setup.protocolKey, .cmj)
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPath))
        let exactReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
            guard let presented = workflow.presentedFrame else { return false }
            return presented.sourceID == newVideo.id && presented.index == 0 && presented.ptsUs == newManifest.frames[0].ptsUs
        })
        XCTAssertTrue(exactReady)
        XCTAssertTrue(workflow.canMarkDisplayedFrame)
        let targets = [0, 2, 4]
        let expectedPTS: [Int64] = [0, 200_000, 700_000]
        let kinds = workflow.requiredEvents
        XCTAssertEqual(kinds, [.movementStart, .takeoff, .landing])
        for (position, kind) in kinds.enumerated() {
            let target = targets[position]
            let expected = expectedPTS[position]
            workflow.selectEvent(kind)
            workflow.requestFrame(target)
            let targetPTS = newManifest.frames[target].ptsUs
            XCTAssertEqual(targetPTS, expected)
            let targetShown = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: {
                guard let presented = workflow.presentedFrame else { return false }
                return presented.sourceID == newVideo.id && presented.index == target && presented.ptsUs == targetPTS
            })
            XCTAssertTrue(targetShown)
            workflow.markSelectedEvent()
        }
        workflow.setRealtimeDeclared(true)
        XCTAssertTrue(workflow.canCalculate(for: app))
        workflow.calculate(using: app)
        let recalculated = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.metrics != nil })
        XCTAssertTrue(recalculated)
        let metrics = try XCTUnwrap(workflow.metrics)
        XCTAssertEqual(metrics.count, 5)
        XCTAssertEqual(metrics[0].value, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(metrics[1].value, 500, accuracy: 1e-10)
        var didOpenHistory = false
        await workflow.save(using: app, openHistory: { didOpenHistory = true })
        XCTAssertTrue(didOpenHistory)
        let saved = try XCTUnwrap(workflow.savedMeasurement)
        XCTAssertEqual(saved.ownerID, athlete.id)
        let reopened = try SQLiteStore(databaseURL: url)
        let graph = try await reopened.temporalAnalysis(measurementID: saved.id)
        let analysis = try XCTUnwrap(graph)
        XCTAssertEqual(analysis.source, .camera)
        XCTAssertEqual(analysis.sourceFrameCount, 6)
        XCTAssertEqual(analysis.sourceOriginUs, 0)
        XCTAssertEqual(retainedOldVideo?.id, oldVideoID)
        XCTAssertEqual(retainedOldManifest?.sourceID, oldVideoID)
        retainedOldManifest = nil
        retainedOldVideo = nil
        let oldSwept = await waitFor(timeoutNanoseconds: 5_000_000_000, condition: { !FileManager.default.fileExists(atPath: oldPath) })
        XCTAssertTrue(oldSwept)
        lease.markDiscard()
        sweptCapture = false
    }

    @MainActor
    func testCapturedStaleCompletionCannotReplaceOrReactivateHiddenViewer() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("openjump-c2a-stale-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try SQLiteStore(databaseURL: url)
        let athlete = try await store.createAthlete(name: "C2a Stale")
        let (app, defaults, suite) = try isolatedApp(store: store, athletes: [athlete])
        defer { defaults.removePersistentDomain(forName: suite) }
        let workflow = JumpWorkflowState()
        workflow.synchronize(with: app)
        defer { workflow.cancelAnalysis(); workflow.confirmDiscard() }
        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        workflow.requestFile(sourceURL, app: app)
        let manifestReady = await waitFor(timeoutNanoseconds: 60_000_000_000, condition: { workflow.manifest != nil })
        XCTAssertTrue(manifestReady)
        let oldVideo = try XCTUnwrap(workflow.video)
        let oldManifest = try XCTUnwrap(workflow.manifest)
        let oldVideoID = oldVideo.id
        XCTAssertEqual(oldManifest.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])
        let frameReady = await waitFor(timeoutNanoseconds: 30_000_000_000, condition: { workflow.presentedFrame != nil })
        XCTAssertTrue(frameReady)
        workflow.viewerDisappeared()
        XCTAssertFalse(workflow.isViewerActive)
        let entered = expectation(description: "stale captured copy entered")
        let workerExited = expectation(description: "stale captured worker exited")
        let gate = DispatchSemaphore(value: 0)
        JumpVideoImporter.workerExitForTesting = { workerExited.fulfill() }
        JumpVideoImporter.coordinationDriverForTesting = { url, accessor, _ in
            entered.fulfill()
            gate.wait()
            accessor(url)
        }
        defer {
            gate.signal()
            JumpVideoImporter.coordinationDriverForTesting = nil
            JumpVideoImporter.workerExitForTesting = nil
        }
        let newFixtureURL = try await MediaFixtureFactory.make(cadence: .constant)
        let newFixtureDirectory = newFixtureURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: newFixtureDirectory) }
        let newBytes = try Data(contentsOf: newFixtureURL)
        let (captureDirectory, captureURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptCapture = false
        defer {
            if !sweptCapture { try? FileManager.default.removeItem(at: captureDirectory) }
        }
        try newBytes.write(to: captureURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: captureDirectory, fileURL: captureURL)
        lease.markWriterFinalized()
        guard let borrower = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend borrower")
            return
        }
        workflow.requestCapturedFile(borrower: borrower, app: app)
        XCTAssertTrue(workflow.isImporting)
        await fulfillment(of: [entered], timeout: 2)
        workflow.requestProtocol(.sj, app: app)
        XCTAssertEqual(workflow.setup.protocolKey, .sj)
        XCTAssertEqual(workflow.video?.id, oldVideoID)
        gate.signal()
        await fulfillment(of: [workerExited], timeout: 2)
        let settled = await waitFor(timeoutNanoseconds: 5_000_000_000, condition: { !workflow.isBusy })
        XCTAssertTrue(settled)
        XCTAssertEqual(workflow.video?.id, oldVideoID)
        XCTAssertEqual(workflow.manifest?.sourceID, oldVideoID)
        XCTAssertEqual(workflow.manifest?.frames.map(\.ptsUs), [0, 100_000, 200_000, 400_000, 700_000, 800_000])
        XCTAssertFalse(workflow.isViewerActive)
        XCTAssertFalse(workflow.isImporting)
        XCTAssertFalse(workflow.isIndexing)
        let presentedSource = workflow.presentedFrame?.sourceID
        if let presentedSource {
            XCTAssertEqual(presentedSource, oldVideoID)
        }
        lease.markDiscard()
        sweptCapture = false
    }
}
