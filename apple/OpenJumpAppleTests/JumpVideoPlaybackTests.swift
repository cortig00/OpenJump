import AVFoundation
import CoreMedia
import Foundation
import XCTest
@testable import OpenJumpApple

// P1a foundation tests: six deterministic pure navigation/ticket checks plus
// two owned-fixture ownership checks. Native readiness/seek/render is authored,
// not claimed as executed proof. No UI wiring in this slice.
final class JumpVideoPlaybackTests: XCTestCase {
    @MainActor
    func testNavigationIndexConstantCadenceExactBoundariesAndClamping() throws {
        let frames = try XCTUnwrap(makeFrames(ticks: [0, 20, 40, 60, 80, 100], timescale: 600))
        XCTAssertEqual(frames.count, 6)
        for (expected, frame) in frames.enumerated() {
            XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: frame.time, in: frames), expected, "exact boundary must hit index \(expected)")
        }
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 10, timescale: 600), in: frames), 0)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 39, timescale: 600), in: frames), 1)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 120, timescale: 600), in: frames), 5)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 10_000, timescale: 600), in: frames), 5, "valid time after last clamps to final index")
    }

    @MainActor
    func testNavigationIndexVariableCadenceFloorGaps() throws {
        let frames = try XCTUnwrap(makeFrames(ticks: [0, 10, 30, 50, 90, 120], timescale: 600))
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 0, timescale: 600), in: frames), 0)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 9, timescale: 600), in: frames), 0)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 10, timescale: 600), in: frames), 1)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 20, timescale: 600), in: frames), 1, "mid-gap maps to floor index")
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 30, timescale: 600), in: frames), 2)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 49, timescale: 600), in: frames), 2)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 60, timescale: 600), in: frames), 3)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 89, timescale: 600), in: frames), 3)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 90, timescale: 600), in: frames), 4)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 119, timescale: 600), in: frames), 4)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 120, timescale: 600), in: frames), 5)
    }

    @MainActor
    func testNavigationIndexNonzeroOriginBeforeAfterClamping() throws {
        let frames = try XCTUnwrap(makeFrames(ticks: [3000, 3150, 3300], timescale: 600))
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 0, timescale: 600), in: frames), 0, "valid time before nonzero origin clamps to first")
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 2999, timescale: 600), in: frames), 0)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 3000, timescale: 600), in: frames), 0)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 3100, timescale: 600), in: frames), 0)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 3150, timescale: 600), in: frames), 1)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 3299, timescale: 600), in: frames), 1)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 3300, timescale: 600), in: frames), 2)
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 4000, timescale: 600), in: frames), 2, "valid time after last clamps to final index")
    }

    @MainActor
    func testNavigationIndexInvalidInputsFailClosed() throws {
        let frames = try XCTUnwrap(makeFrames(ticks: [0, 20], timescale: 600))
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 10, timescale: 600), in: []), "empty frames fail closed")
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime.invalid, in: frames))
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime.indefinite, in: frames))
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime.positiveInfinity, in: frames))
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: -1, timescale: 600), in: frames), "negative fails closed")
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 1, timescale: 600, flags: .valid, epoch: 1), in: frames), "wrong epoch fails closed")
        XCTAssertNil(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 0, timescale: 0), in: frames), "non-positive timescale fails closed")
    }

    @MainActor
    func testNavigationIndexRationalPrecisionWithoutFloatApproximation() throws {
        let frames = [
            JumpVideoFrame(index: 0, time: CMTime(value: 0, timescale: 3), ptsUs: 0),
            JumpVideoFrame(index: 1, time: CMTime(value: 1, timescale: 3), ptsUs: 333_333),
            JumpVideoFrame(index: 2, time: CMTime(value: 2, timescale: 3), ptsUs: 666_667)
        ]
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 1, timescale: 3), in: frames), 1, "exact rational boundary hits")
        XCTAssertEqual(
            JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 333_333, timescale: 1_000_000), in: frames),
            0,
            "rounded microseconds stay below exact one-third and floor to prior index"
        )
        XCTAssertEqual(CMTimeCompare(CMTime(value: 333_333, timescale: 1_000_000), CMTime(value: 1, timescale: 3)), -1, "independent exact comparison proves rounding gap")
        XCTAssertEqual(JumpVideoPlaybackController.navigationIndex(for: CMTime(value: 2, timescale: 3), in: frames), 2)
    }

    @MainActor
    func testTicketPolicyRejectsStaleSourceAndRequest() throws {
        let currentSource = UUID()
        let otherSource = UUID()
        XCTAssertTrue(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: 7, currentAttachment: 7, capturedSourceID: currentSource, currentSourceID: currentSource))
        XCTAssertFalse(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: 6, currentAttachment: 7, capturedSourceID: currentSource, currentSourceID: currentSource), "old attachment is stale")
        XCTAssertFalse(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: 7, currentAttachment: 7, capturedSourceID: otherSource, currentSourceID: currentSource), "wrong source is stale")
        XCTAssertFalse(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: 7, currentAttachment: 7, capturedSourceID: currentSource, currentSourceID: nil), "torn-down source rejects all")
        XCTAssertTrue(JumpVideoPlaybackController.acceptsRequestCallback(capturedAttachment: 7, currentAttachment: 7, capturedRequest: 3, currentRequest: 3))
        XCTAssertFalse(JumpVideoPlaybackController.acceptsRequestCallback(capturedAttachment: 6, currentAttachment: 7, capturedRequest: 3, currentRequest: 3), "old attachment invalidates request")
        XCTAssertFalse(JumpVideoPlaybackController.acceptsRequestCallback(capturedAttachment: 7, currentAttachment: 7, capturedRequest: 2, currentRequest: 3), "superseded request is stale")
        // Seek-promotion policy (actually wired into the seek completion):
        // only a finished seek on a ready item with live intent still in
        // SEEKING promotes. Every rejection below settles fail-closed.
        XCTAssertTrue(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: true, wantsPlayback: true, phase: .seeking), "current finished seek on a ready item with live SEEKING intent promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: false, readiness: .ready, itemReady: true, wantsPlayback: true, phase: .seeking), "cancelled seek never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .failed, itemReady: true, wantsPlayback: true, phase: .seeking), "failed readiness never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .unknown, itemReady: true, wantsPlayback: true, phase: .seeking), "unknown readiness never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: false, wantsPlayback: true, phase: .seeking), "unready item never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: true, wantsPlayback: false, phase: .seeking), "dropped intent never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: true, wantsPlayback: true, phase: .failed), "failed phase never resurrects")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: true, wantsPlayback: true, phase: .paused), "paused phase never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: true, wantsPlayback: true, phase: .idle), "idle phase never promotes")
        XCTAssertFalse(JumpVideoPlaybackController.shouldPromoteSeekToPlaying(finished: true, readiness: .ready, itemReady: true, wantsPlayback: true, phase: .playing), "already-playing completion never re-promotes")
        // Genuine-end policy (actually wired into didPlayToEnd): native
        // current time at/past a positive duration with live playing intent.
        XCTAssertTrue(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "native time at duration with live playing intent honors end")
        XCTAssertTrue(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1, timescale: 1), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "rational cross-timescale equality honors end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 500_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "restarted mid-timeline ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 0, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "timeline origin ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .seeking, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "seeking ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .paused, wantsPlayback: false, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "paused without intent ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .failed, wantsPlayback: false, readiness: .failed, itemReady: false, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "failed ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: false, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "dropped intent ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .unknown, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "unknown readiness ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: false, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "unready item ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime.invalid, duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "invalid current time ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime.indefinite, duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "indefinite current time ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime.positiveInfinity, duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "infinite current time ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: -1, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "negative current time ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1, timescale: 1, flags: .valid, epoch: 1), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "wrong-epoch current time ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 0, timescale: 1_000_000)), "zero duration ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: -1, timescale: 1_000_000)), "negative duration ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime.indefinite), "indefinite duration ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime.positiveInfinity), "infinite duration ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .playing, wantsPlayback: true, readiness: .ready, itemReady: true, currentTime: CMTime(value: 1_000_000, timescale: 1_000_000), duration: CMTime(value: 1, timescale: 1, flags: .valid, epoch: 2)), "wrong-epoch duration ignores end")
        XCTAssertFalse(JumpVideoPlaybackController.shouldHonorEnd(phase: .idle, wantsPlayback: false, readiness: .none, itemReady: false, currentTime: CMTime(value: 0, timescale: 1_000_000), duration: CMTime(value: 1_000_000, timescale: 1_000_000)), "idle ignores end")
    }

    @MainActor
    func testAttachRegistersObserverAndTeardownReleasesWithoutDeletingSource() async throws {
        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        let video = try await JumpVideoImporter.importFile(sourceURL)
        defer { video.dispose() }
        let controller = JumpVideoPlaybackController()
        defer { controller.teardown() }
        XCTAssertNil(controller.nativePlayer)
        XCTAssertFalse(controller.hasPeriodicObserver)
        controller.attach(video: video)
        guard let player = controller.nativePlayer else {
            XCTFail("attach must install one owned player")
            return
        }
        XCTAssertTrue(controller.hasPeriodicObserver, "attach must register exactly one periodic observer")
        XCTAssertEqual(controller.attachedSourceID, video.id)
        XCTAssertTrue(player.isMuted, "preview stays muted")
        controller.teardown()
        XCTAssertNil(controller.nativePlayer, "teardown must nil the player")
        XCTAssertFalse(controller.hasPeriodicObserver, "teardown must remove the periodic observer")
        XCTAssertNil(controller.attachedSourceID)
        XCTAssertTrue(FileManager.default.fileExists(atPath: video.url.path), "teardown must not delete the owned source while the caller retains it")
        controller.teardown()
        XCTAssertNil(controller.nativePlayer, "repeated teardown stays safe")
        XCTAssertFalse(controller.hasPeriodicObserver)
        // Sole-owner lease proof: a second source owned only by its
        // controller survives the caller dropping its ref; an explicit
        // teardown then drops the lease after the same store-structured
        // backend cleanup. No sleeps; no weak-AVPlayer assertions (queued
        // KVO tasks may briefly retain the controller/player, never the
        // ImportedJumpVideo once the lease is detached).
        weak var weakLeasedVideo: ImportedJumpVideo?
        var leasedURL: URL?
        let leaseController = JumpVideoPlaybackController()
        defer { leaseController.teardown() }
        do {
            let leasedVideo = try await JumpVideoImporter.importFile(sourceURL)
            leasedURL = leasedVideo.url
            leaseController.attach(video: leasedVideo)
            weakLeasedVideo = leasedVideo
        }
        XCTAssertNotNil(weakLeasedVideo, "controller lease must retain the source after the caller drops its ref")
        guard let leasedFileURL = leasedURL else {
            XCTFail("leased source must expose its owned file URL")
            return
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: leasedFileURL.path), "owned file must exist while the controller leases the source")
        leaseController.teardown()
        XCTAssertNil(weakLeasedVideo, "explicit teardown must drop the lease after backend cleanup")
        XCTAssertFalse(FileManager.default.fileExists(atPath: leasedFileURL.path), "explicit teardown must delete the leased owned file")
    }

    @MainActor
    func testSupersessionPauseAndTeardownInvalidateTickets() async throws {
        let sourceURL = try await MediaFixtureFactory.make(cadence: .temporalReference)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        let videoA = try await JumpVideoImporter.importFile(sourceURL)
        defer { videoA.dispose() }
        let videoB = try await JumpVideoImporter.importFile(sourceURL)
        defer { videoB.dispose() }
        guard videoA.id != videoB.id else {
            XCTFail("distinct imports must carry distinct source identities")
            return
        }
        let controller = JumpVideoPlaybackController()
        defer { controller.teardown() }
        controller.attach(video: videoA)
        let firstAttachment = controller.attachmentTicket
        let firstRequest = controller.requestTicket
        guard let firstSource = controller.attachedSourceID else {
            XCTFail("first attach must expose its source")
            return
        }
        controller.attach(video: videoB)
        XCTAssertNotEqual(controller.attachmentTicket, firstAttachment, "supersession must change the attachment epoch")
        XCTAssertEqual(controller.attachedSourceID, videoB.id)
        XCTAssertFalse(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: firstAttachment, currentAttachment: controller.attachmentTicket, capturedSourceID: firstSource, currentSourceID: controller.attachedSourceID), "old source callback is stale after A to B")
        XCTAssertFalse(JumpVideoPlaybackController.acceptsRequestCallback(capturedAttachment: firstAttachment, currentAttachment: controller.attachmentTicket, capturedRequest: firstRequest, currentRequest: controller.requestTicket), "old request is stale after supersession")
        let beforePauseAttachment = controller.attachmentTicket
        let beforePauseRequest = controller.requestTicket
        _ = controller.pause()
        XCTAssertEqual(controller.attachmentTicket, beforePauseAttachment, "pause keeps the attachment epoch")
        XCTAssertNotEqual(controller.requestTicket, beforePauseRequest, "pause invalidates the pending request epoch")
        XCTAssertFalse(JumpVideoPlaybackController.acceptsRequestCallback(capturedAttachment: beforePauseAttachment, currentAttachment: controller.attachmentTicket, capturedRequest: beforePauseRequest, currentRequest: controller.requestTicket))
        XCTAssertTrue(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: beforePauseAttachment, currentAttachment: controller.attachmentTicket, capturedSourceID: videoB.id, currentSourceID: controller.attachedSourceID))
        let tornAttachment = controller.attachmentTicket
        let tornRequest = controller.requestTicket
        guard let tornSource = controller.attachedSourceID else {
            XCTFail("source must be attached before teardown")
            return
        }
        controller.teardown()
        XCTAssertNil(controller.nativePlayer)
        XCTAssertFalse(controller.hasPeriodicObserver)
        XCTAssertFalse(JumpVideoPlaybackController.acceptsAttachmentCallback(capturedAttachment: tornAttachment, currentAttachment: controller.attachmentTicket, capturedSourceID: tornSource, currentSourceID: controller.attachedSourceID), "teardown suppresses all old attachment callbacks")
        XCTAssertFalse(JumpVideoPlaybackController.acceptsRequestCallback(capturedAttachment: tornAttachment, currentAttachment: controller.attachmentTicket, capturedRequest: tornRequest, currentRequest: controller.requestTicket), "teardown suppresses all old request callbacks")
    }

    private func makeFrames(ticks: [Int64], timescale: Int32) -> [JumpVideoFrame]? {
        guard ticks.count >= 2 else { return nil }
        var frames: [JumpVideoFrame] = []
        for (index, tick) in ticks.enumerated() {
            let time = CMTime(value: tick, timescale: timescale)
            guard let pts = try? MediaProbe.microseconds(for: time) else { return nil }
            frames.append(JumpVideoFrame(index: index, time: time, ptsUs: pts))
        }
        return frames
    }
}
