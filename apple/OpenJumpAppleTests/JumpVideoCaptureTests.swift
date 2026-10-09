import AVFoundation
import Foundation
import XCTest
@testable import OpenJumpApple

// C1 foundation tests (authored, not executed here): 7 synchronous pure/unit
// checks over the ACTUAL policies/lease used by JumpVideoCaptureEngine.
// Temp-owned filesystem fixtures only. No camera, permissions, simulator,
// hardware, awaits, or device resets.
final class JumpVideoCaptureTests: XCTestCase {
    func testPermissionMappingAndInactiveLateGrantRefusesCapture() {
        XCTAssertEqual(JumpVideoCapturePolicy.permission(for: .authorized), .authorized)
        XCTAssertEqual(JumpVideoCapturePolicy.permission(for: .denied), .denied)
        XCTAssertEqual(JumpVideoCapturePolicy.permission(for: .restricted), .restricted)
        XCTAssertEqual(JumpVideoCapturePolicy.permission(for: .notDetermined), .notDetermined)
        XCTAssertEqual(JumpVideoCapturePolicy.phase(for: .authorized), .preparing)
        XCTAssertEqual(JumpVideoCapturePolicy.phase(for: .denied), .denied)
        XCTAssertEqual(JumpVideoCapturePolicy.phase(for: .restricted), .denied)
        XCTAssertEqual(JumpVideoCapturePolicy.phase(for: .notDetermined), .requestingPermission)
        // Active grant while requesting may proceed to configuration (never
        // records by itself; ready follows session start only).
        XCTAssertTrue(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .requestingPermission))
        // Late grant after cancel/background/invisibility must NOT start.
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: false, isActiveNow: true, phase: .requestingPermission),
            "cancelled request must not start on late grant")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: false, phase: .requestingPermission),
            "backgrounded/invisible grant must not start")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .recording),
            "grant never starts from an unrelated phase")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .ready))
    }

    func testFinalizingGatesRejectDuplicateStopAndEarlyUse() {
        XCTAssertTrue(JumpVideoCapturePolicy.canStartRecording(phase: .ready))
        XCTAssertFalse(JumpVideoCapturePolicy.canStartRecording(phase: .finalizing),
            "no second recording while the writer has not finalized")
        XCTAssertFalse(JumpVideoCapturePolicy.canStartRecording(phase: .recording))
        XCTAssertTrue(JumpVideoCapturePolicy.canStopRecording(phase: .recording))
        XCTAssertFalse(JumpVideoCapturePolicy.canStopRecording(phase: .finalizing),
            "no duplicate stop while finalizing")
        XCTAssertFalse(JumpVideoCapturePolicy.canStopRecording(phase: .ready))
        XCTAssertFalse(JumpVideoCapturePolicy.canStopRecording(phase: .recorded))
        XCTAssertFalse(JumpVideoCapturePolicy.canStopRecording(phase: .idle))
        XCTAssertTrue(JumpVideoCapturePolicy.canUseRecorded(phase: .recorded))
        XCTAssertFalse(JumpVideoCapturePolicy.canUseRecorded(phase: .finalizing),
            "no Use/repeat/new recording until the writer actually finalized")
        XCTAssertFalse(JumpVideoCapturePolicy.canUseRecorded(phase: .recording))
        XCTAssertFalse(JumpVideoCapturePolicy.canBeginNewRecording(phase: .finalizing))
        XCTAssertTrue(JumpVideoCapturePolicy.canBeginNewRecording(phase: .ready))
        XCTAssertTrue(JumpVideoCapturePolicy.canBeginNewRecording(phase: .recorded))
        XCTAssertTrue(JumpVideoCapturePolicy.canBeginNewRecording(phase: .failed))
    }

    func testStaleCallbackUIRejectedWhileOwnCleanupStillFinalizes() throws {
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("pending".utf8).write(to: fileURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        // Superseded epoch with the same URL: UI rejects, but the owner
        // context for THIS url still finalizes (delegate proof), never skipped.
        XCTAssertFalse(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: 5, currentEpoch: 6, capturedURL: fileURL, currentURL: fileURL),
            "superseded epoch is stale for UI")
        XCTAssertFalse(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: 5, currentEpoch: 5,
            capturedURL: fileURL,
            currentURL: fileURL.appendingPathExtension("other")),
            "foreign URL never drives UI")
        XCTAssertFalse(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: 5, currentEpoch: 5, capturedURL: fileURL, currentURL: nil),
            "torn-down session rejects all UI")
        XCTAssertTrue(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: 5, currentEpoch: 5, capturedURL: fileURL, currentURL: fileURL))
        lease.markWriterFinalized()
        XCTAssertTrue(lease.isWriterFinalized,
            "stale UI rejection must not skip cleanup of its finalized context")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))
        lease.dispose()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path),
            "unborrowed finalized lease disposes its own directory")
    }

    func testRecordingSuccessClassifierHonorsNilTrueAndFailsClosed() {
        XCTAssertTrue(JumpVideoCapturePolicy.isRecordingSuccess(error: nil))
        let finishedTrue = NSError(domain: "capture-test", code: 7,
                                    userInfo: [AVErrorRecordingSuccessfullyFinishedKey: true])
        XCTAssertTrue(JumpVideoCapturePolicy.isRecordingSuccess(error: finishedTrue))
        let finishedFalse = NSError(domain: "capture-test", code: 7,
                                     userInfo: [AVErrorRecordingSuccessfullyFinishedKey: false])
        XCTAssertFalse(JumpVideoCapturePolicy.isRecordingSuccess(error: finishedFalse),
            "explicit unsuccessful finish fails closed")
        let plain = NSError(domain: "capture-test", code: 7, userInfo: [:])
        XCTAssertFalse(JumpVideoCapturePolicy.isRecordingSuccess(error: plain),
            "error without the success key fails closed")
    }

    func testOwnedDirectoriesAreUniqueAndSourceIsCamera() throws {
        let first = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        let second = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer {
            try? FileManager.default.removeItem(at: first.directory)
            try? FileManager.default.removeItem(at: second.directory)
        }
        XCTAssertNotEqual(first.directory, second.directory)
        XCTAssertNotEqual(first.fileURL, second.fileURL)
        XCTAssertEqual(first.fileURL.lastPathComponent, "capture.mov")
        XCTAssertTrue(JumpVideoCapturePaths.isOwnedCaptureURL(first.fileURL))
        XCTAssertTrue(JumpVideoCapturePaths.isOwnedCaptureURL(second.fileURL))
        XCTAssertFalse(JumpVideoCapturePaths.isOwnedCaptureURL(URL(fileURLWithPath: "/tmp/foreign.mov")),
            "foreign directories must never qualify for sweeping")
        XCTAssertEqual(JumpVideoCaptureLease.captureSource, .camera)
        XCTAssertEqual(JumpVideoCaptureLease.captureSource.rawValue, "CAMERA")
    }

    func testDiscardWaitsForWriterFinalizedAndAllBorrowers() throws {
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        // Owned/bounded fixture; the lease (not the test) owns removal.
        var sweptByTest = false
        defer {
            if !sweptByTest { try? FileManager.default.removeItem(at: directory) }
        }
        try Data("take".utf8).write(to: fileURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        lease.markWriterFinalized()
        guard let first = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend borrowers")
            return
        }
        guard let second = lease.acquireBorrower() else {
            XCTFail("second concurrent borrower must be admitted")
            first.release()
            return
        }
        lease.markDiscard()
        lease.markDiscard()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path),
            "discard waits: writer finalized but borrowers outstanding keeps the file")
        first.release()
        first.release()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path),
            "one remaining borrower still pins the owned directory")
        second.release()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path),
            "physical removal only after writer-finalized AND all borrowers released")
        sweptByTest = true
    }

    func testDisposeIsIdempotentAndTransferredBorrowKeepsFileUntilRealRelease() throws {
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptByTest = false
        defer {
            if !sweptByTest { try? FileManager.default.removeItem(at: directory) }
        }
        try Data("transfer".utf8).write(to: fileURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        XCTAssertNil(lease.acquireBorrower(), "borrow before finalization fails closed")
        lease.markWriterFinalized()
        lease.markWriterFinalized()
        guard let borrower = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend a transferable borrower")
            return
        }
        // Transfer simulation: the worker holds the borrower strongly while
        // the owner is disposed. Disposal is idempotent and must not delete
        // under a live borrower.
        let transferred = borrower
        lease.dispose()
        lease.dispose()
        XCTAssertNotNil(transferred.fileURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path),
            "transferred borrow keeps the file until real release")
        XCTAssertNil(lease.acquireBorrower(), "disposed lease vends nothing further")
        transferred.release()
        transferred.release()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path),
            "real release after disposal sweeps only the owned directory")
        sweptByTest = true
    }

    // C1 repair regressions (authored, not executed here): 8 synchronous
    // pure/unit checks over the ACTUAL updated policy/event-gate/take/lease
    // used by JumpVideoCaptureEngine (never standalone unused toys).
    // Temp-owned fixtures only; no camera/session instantiation requesting
    // permission/hardware, no awaits, no device resets.

    func testInactiveAuthorizedPrepareAndBackgroundGrantRefusedWhileActiveIdleRetryAccepted() {
        // ACTUAL engine gate (prepareOnQueue/requestPermissionAndPrepare/prepare):
        // inactive authorized never configures; background late grant refused.
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: false, phase: .preparing),
            "inactive authorized preparing must not start (OR bypass removed)")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: false, phase: .requestingPermission),
            "backgrounded/invisible grant must not start")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: false, isActiveNow: true, phase: .requestingPermission),
            "cancelled request must not start on late grant")
        XCTAssertTrue(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .idle),
            "active idle prepare must proceed (never records by itself)")
        XCTAssertTrue(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .requestingPermission))
        XCTAssertTrue(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .preparing))
        XCTAssertTrue(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .failed),
            "active retry from failed must be usable")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .starting))
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .recording))
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .finalizing))
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .recorded))
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .ready))
    }

    func testSynchronousEventGateInvalidatesQueuedOldBeforeNewAndDisposedNeverResurrects() {
        // ACTUAL shared ticket used by engine publishLocked AND facade onEvent.
        let gate = JumpVideoCaptureEventGate()
        let first = gate.currentGeneration()
        XCTAssertTrue(gate.accepts(eventGeneration: first))
        let second = gate.invalidate()
        XCTAssertNotEqual(first, second)
        XCTAssertFalse(gate.accepts(eventGeneration: first),
            "old queued ready/recorded must be rejected after MainActor invalidate")
        XCTAssertTrue(gate.accepts(eventGeneration: second),
            "legitimate new request (new generation) accepted, not suppressed")
        let third = gate.invalidate()
        XCTAssertFalse(gate.accepts(eventGeneration: second))
        XCTAssertTrue(gate.accepts(eventGeneration: third))
        gate.markDisposed()
        XCTAssertTrue(gate.isDisposed())
        XCTAssertFalse(gate.accepts(eventGeneration: third))
        XCTAssertFalse(gate.accepts(eventGeneration: gate.currentGeneration()),
            "disposed gate rejects even current generation")
    }

    func testImmutableTakeEpochStaleUIRejectedWhileOwnFinalizationRunsAndForeignURLIgnored() throws {
        // ACTUAL take stored by engine startRecording, used by didStart/didFinish
        // via the ACTUAL policy (never tautology); OWN cleanup separate from UI.
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("take".utf8).write(to: fileURL)
        let take = JumpVideoCaptureTakeContext(epoch: 11, fileURL: fileURL)
        XCTAssertFalse(take.acceptsUI(currentEpoch: 12, currentURL: fileURL),
            "superseded take epoch stale for UI")
        XCTAssertTrue(take.acceptsUI(currentEpoch: 11, currentURL: fileURL))
        XCTAssertFalse(take.acceptsUI(currentEpoch: 11, currentURL: fileURL.appendingPathExtension("other")))
        XCTAssertFalse(take.acceptsUI(currentEpoch: 11, currentURL: nil))
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        XCTAssertFalse(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: take.epoch, currentEpoch: 12, capturedURL: take.fileURL, currentURL: fileURL))
        lease.markWriterFinalized()
        XCTAssertTrue(lease.isWriterFinalized)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))
        let foreign = URL(fileURLWithPath: "/tmp/foreign.mov")
        XCTAssertFalse(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: 11, currentEpoch: 11, capturedURL: foreign, currentURL: fileURL))
        lease.dispose()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    }

    func testStopParkedDuringStartingExecutesOnceOnNativeStartWithoutReopeningUI() throws {
        // ACTUAL starting policy + take used by engine stop/didStart/finish.
        XCTAssertTrue(JumpVideoCapturePolicy.canStopRecording(phase: .starting),
            "stop during starting parks until real native start")
        XCTAssertTrue(JumpVideoCapturePolicy.shouldParkStopDuringStarting(phase: .starting))
        XCTAssertFalse(JumpVideoCapturePolicy.shouldParkStopDuringStarting(phase: .recording))
        XCTAssertTrue(JumpVideoCapturePolicy.canStopRecording(phase: .recording))
        XCTAssertFalse(JumpVideoCapturePolicy.canStopRecording(phase: .finalizing))
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("starting".utf8).write(to: fileURL)
        let take = JumpVideoCaptureTakeContext(epoch: 21, fileURL: fileURL)
        XCTAssertFalse(take.acceptsUI(currentEpoch: 22, currentURL: fileURL),
            "late start after parked stop must not reopen visible UI")
        XCTAssertTrue(JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: 21, currentEpoch: 21, capturedURL: fileURL, currentURL: fileURL))
        XCTAssertFalse(JumpVideoCapturePolicy.canStartRecording(phase: .starting))
        XCTAssertFalse(JumpVideoCapturePolicy.canStartRecording(phase: .finalizing))
        XCTAssertFalse(JumpVideoCapturePolicy.canUseRecorded(phase: .starting))
    }

    func testBackgroundViewExitDuringFinalizingParksSuspendAndCancelDoesNotClear() {
        // ACTUAL suspend/dispose distinction used by engine background/
        // viewDisappeared/cancel/finish (deferred until real finish).
        XCTAssertFalse(JumpVideoCapturePolicy.cancelClearsSuspendDuringFinalizing(),
            "cancel during finalizing must NOT clear parked suspend/dispose")
        XCTAssertFalse(JumpVideoCapturePolicy.canStopRecording(phase: .finalizing),
            "no duplicate stop while finalizing; suspend parked until delegate")
        XCTAssertTrue(JumpVideoCapturePolicy.canStopRecording(phase: .starting))
        XCTAssertTrue(JumpVideoCapturePolicy.canStopRecording(phase: .recording))
        XCTAssertFalse(JumpVideoCapturePolicy.canBeginNewRecording(phase: .finalizing),
            "no new recording until writer actually finalized")
        XCTAssertFalse(JumpVideoCapturePolicy.canUseRecorded(phase: .finalizing))
        let gate = JumpVideoCaptureEventGate()
        let old = gate.currentGeneration()
        gate.invalidate()
        XCTAssertFalse(gate.accepts(eventGeneration: old))
        XCTAssertTrue(gate.accepts(eventGeneration: gate.currentGeneration()))
    }

    func testDisposeDuringFinalizingDefersFullTeardownAndDisposeBeforeFinalizePinsFile() throws {
        // ACTUAL lease used by engine dispose/finish: disposal pre-delegate
        // keeps OWN file, finalizes, then sweeps only after finish + zero
        // borrowers. No borrower before finalized (fail-closed).
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptByTest = false
        defer { if !sweptByTest { try? FileManager.default.removeItem(at: directory) } }
        try Data("deferred".utf8).write(to: fileURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        XCTAssertNil(lease.acquireBorrower(), "borrow before finalization fails closed")
        lease.dispose()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path),
            "dispose before delegate keeps OWN file (no teardown before finish)")
        XCTAssertNil(lease.acquireBorrower(), "disposed pre-finish vends nothing")
        lease.markWriterFinalized()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path),
            "physical removal only after writer-finalized (deferred teardown)")
        sweptByTest = true
    }

    func testMismatchedDirectoryPairRefusesBorrowAndSweepWhileOwnedFixtureSweeps() throws {
        // ACTUAL exact pair guard used by lease sweep/borrow (temp root +
        // canonical parent, not merely suffix). Owned fixtures only; other
        // owned dir never deleted.
        let (ownedDir, ownedURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        let (otherDir, otherURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var ownedSweptByLease = false
        var otherSweptByTest = false
        defer {
            if !ownedSweptByLease { try? FileManager.default.removeItem(at: ownedDir) }
            if !otherSweptByTest { try? FileManager.default.removeItem(at: otherDir) }
        }
        try Data("owned".utf8).write(to: ownedURL)
        try Data("other".utf8).write(to: otherURL)
        XCTAssertTrue(JumpVideoCapturePaths.isValidOwnedPair(directory: ownedDir, fileURL: ownedURL))
        XCTAssertTrue(JumpVideoCapturePaths.isValidOwnedPair(directory: otherDir, fileURL: otherURL))
        XCTAssertFalse(JumpVideoCapturePaths.isValidOwnedPair(directory: otherDir, fileURL: ownedURL),
            "mismatched file/dir pair must fail closed")
        XCTAssertFalse(JumpVideoCapturePaths.isValidOwnedPair(directory: ownedDir, fileURL: otherURL))
        let mismatched = JumpVideoCaptureLease(ownedDirectory: otherDir, fileURL: ownedURL)
        XCTAssertNil(mismatched.acquireBorrower(), "mismatched pair never vends")
        mismatched.markWriterFinalized()
        mismatched.dispose()
        XCTAssertTrue(FileManager.default.fileExists(atPath: ownedURL.path),
            "mismatched sweep must not delete the owned file")
        XCTAssertTrue(FileManager.default.fileExists(atPath: otherURL.path),
            "other owned dir untouched by mismatched lease")
        let valid = JumpVideoCaptureLease(ownedDirectory: ownedDir, fileURL: ownedURL)
        valid.markWriterFinalized()
        valid.dispose()
        XCTAssertFalse(FileManager.default.fileExists(atPath: ownedURL.path),
            "valid owned pair disposes its own directory")
        ownedSweptByLease = true
        XCTAssertTrue(FileManager.default.fileExists(atPath: otherURL.path),
            "other owned dir still intact after valid sweep")
        try? FileManager.default.removeItem(at: otherDir)
        otherSweptByTest = true
    }

    func testBorrowerIdempotentReleaseAndLockedFileURLNilPinsLiveBorrowUntilRealRelease() throws {
        // ACTUAL borrower barrier used by engine stale probe + future worker
        // (retain INSIDE worker until real exit, locked getter, idempotent).
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptByTest = false
        defer { if !sweptByTest { try? FileManager.default.removeItem(at: directory) } }
        try Data("pin".utf8).write(to: fileURL)
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        lease.markWriterFinalized()
        guard let borrower = lease.acquireBorrower() else {
            XCTFail("finalized lease must vend borrower")
            return
        }
        XCTAssertEqual(borrower.fileURL, fileURL)
        let transferred = borrower
        lease.dispose()
        XCTAssertEqual(transferred.fileURL, fileURL,
            "locked fileURL stays valid under live borrow after dispose")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path),
            "transferred borrow pins discarded OWN file until real release")
        XCTAssertNil(lease.acquireBorrower(), "disposed lease vends nothing further")
        transferred.release()
        XCTAssertNil(transferred.fileURL, "released borrower locked getter goes nil")
        transferred.release()
        XCTAssertNil(transferred.fileURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path),
            "real release after disposal sweeps only the owned directory")
        sweptByTest = true
    }

    // C1 ticket repair regressions (authored, not executed here): 6 synchronous
    // checks over the ACTUAL authoritative types the engine consumes
    // (request ticket + gate foreground, recovery decision, take intent,
    // readiness). Temp-owned fixtures only; no camera/session instantiation,
    // no permission/hardware, no awaits, no device resets.

    func testOriginatingTicketCannotBorrowNewGenerationAfterSyncInvalidate() throws {
        // ACTUAL gate/ticket API consumed by every engine entry
        // (requestPermissionAndPrepare/prepare/startRecording/grant) and by
        // the take context stored at startRecording.
        let gate = JumpVideoCaptureEventGate()
        // T0: originating call freezes its ticket BEFORE the queue hop.
        let oldTicket = gate.issueTicket()
        XCTAssertTrue(gate.isTicketLive(oldTicket))
        // T1: synchronous MainActor view exit/cancel BEFORE queued work runs.
        gate.setForegroundActive(false)
        let bumped = gate.invalidate()
        XCTAssertNotEqual(oldTicket.generation, bumped)
        // Old work executed later must NOT configure/publish: its
        // originating ticket stays stale even though it runs after the bump
        // (never relabelled with the new generation).
        XCTAssertFalse(gate.isTicketLive(oldTicket))
        XCTAssertFalse(gate.isForegroundActive())
        // A take frozen with the old ticket is refused against the live gate.
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("ticket".utf8).write(to: fileURL)
        let staleTake = JumpVideoCaptureTakeContext(epoch: 3, fileURL: fileURL, ticketGeneration: oldTicket.generation)
        XCTAssertFalse(staleTake.acceptsTicket(currentGeneration: gate.currentGeneration()),
            "old take must not validate against the post-invalidate gate")
        // Invalidation racing a blocking configure fails the post-call
        // ready validation the same way (re-read, never cached).
        XCTAssertFalse(gate.isTicketLive(oldTicket),
            "mid-configure invalidate must fail post-call validation")
        // Valid explicit reentry after viewAppeared issues a NEW live ticket.
        gate.setForegroundActive(true)
        let newTicket = gate.issueTicket()
        XCTAssertTrue(gate.isTicketLive(newTicket))
        let freshTake = JumpVideoCaptureTakeContext(epoch: 3, fileURL: fileURL, ticketGeneration: newTicket.generation)
        XCTAssertTrue(freshTake.acceptsTicket(currentGeneration: gate.currentGeneration()))
        XCTAssertTrue(freshTake.acceptsUI(currentEpoch: 3, currentURL: fileURL))
    }

    func testRequestingPreparingViewExitRecoversToEligibleIdle() {
        // ACTUAL recovery decision consumed by the engine
        // viewDisappeared/background blocks (never auto-record).
        let resetRequesting: JumpVideoCapturePhase? =
            JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .requestingPermission)
        XCTAssertEqual(resetRequesting, .idle)
        let resetPreparing: JumpVideoCapturePhase? =
            JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .preparing)
        XCTAssertEqual(resetPreparing, .idle)
        let resetReady: JumpVideoCapturePhase? =
            JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .ready)
        XCTAssertEqual(resetReady, .idle)
        XCTAssertNil(JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .starting),
            "starting takes the parked-intent path, never the idle reset")
        XCTAssertNil(JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .recording))
        XCTAssertNil(JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .finalizing))
        XCTAssertNil(JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: .idle))
        // The old grant after hidden/view-exit cannot start...
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: false, phase: .idle),
            "backgrounded grant must not configure")
        XCTAssertFalse(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: false, isActiveNow: true, phase: .idle),
            "cancelled request must not start on late grant")
        // ...but an explicit active foreground retry from eligible idle is
        // accepted (configuration itself, never automatic recording).
        XCTAssertTrue(JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: true, isActiveNow: true, phase: .idle))
        let retry = JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: true, suspended: false,
            ticketLive: true, activeNow: true)
        XCTAssertTrue(retry.isReady)
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: true, suspended: false,
            ticketLive: true, activeNow: false).isReady,
            "no hidden auto-record: inactive retry is never ready")
    }

    func testTakeIntentStartingStopParksExecutesOnceAndDuplicatesIgnored() {
        // ACTUAL intent consumed by the engine stop/didStart/finish path
        // (replaces the former parked-flag cluster).
        var intent = JumpVideoCaptureTakeIntent()
        intent.request(.stop)
        XCTAssertEqual(intent.termination, .stop)
        // Late matching native start executes the parked stop exactly once...
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .stopOnce)
        // ...duplicate native start has no effect.
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .ignore)
        // Matching finish without a marked recording still consumes the
        // transient intent safely once (plain stop: no session stop)...
        XCTAssertEqual(intent.nativeFinished(), .none)
        // ...a second finish emits nothing.
        XCTAssertEqual(intent.nativeFinished(), .none)
        // Genuine start with no parked intent marks recording.
        var live = JumpVideoCaptureTakeIntent()
        XCTAssertEqual(live.nativeStarted(uiLive: true), .markRecording)
        XCTAssertEqual(live.mode, .recording)
        // Cancel can never downgrade an already-parked suspend.
        var guarded = JumpVideoCaptureTakeIntent()
        guarded.request(.suspend)
        guarded.request(.stop)
        XCTAssertEqual(guarded.termination, .suspend)
        guarded.request(.none)
        XCTAssertEqual(guarded.termination, .suspend)
    }

    func testTakeIntentRuntimeInterruptionParksSuspendThroughFinalizing() throws {
        // ACTUAL intent + take/gate API consumed by the engine runtime/
        // interruption/view-exit/background/finish paths.
        var recording = JumpVideoCaptureTakeIntent()
        XCTAssertEqual(recording.nativeStarted(uiLive: true), .markRecording)
        // Runtime error during recording parks suspend (engine stops once).
        recording.request(.suspend)
        XCTAssertTrue(recording.stopFromRecording())
        XCTAssertEqual(recording.termination, .suspend)
        // Duplicate stop while finalizing is rejected (no second stop).
        XCTAssertFalse(recording.stopFromRecording())
        // Later cancel cannot downgrade suspend.
        recording.request(.stop)
        XCTAssertEqual(recording.termination, .suspend)
        // Matching finish produces stopSession exactly once, never before.
        XCTAssertEqual(recording.nativeFinished(), .stopSession)
        XCTAssertEqual(recording.nativeFinished(), .none)
        // Already-finalizing interruption keeps suspend parked, no second stop.
        var finalizing = JumpVideoCaptureTakeIntent()
        XCTAssertEqual(finalizing.nativeStarted(uiLive: false), .stopOnce)
        XCTAssertEqual(finalizing.termination, .suspend)
        finalizing.request(.suspend)
        XCTAssertFalse(finalizing.stopFromRecording(),
            "no second stop from finalizing")
        XCTAssertEqual(finalizing.nativeFinished(), .stopSession)
        XCTAssertEqual(finalizing.nativeFinished(), .none)
        // UI recorded is refused for an invalidated take ticket (actual take
        // + gate the engine didStart/finish paths consult).
        let gate = JumpVideoCaptureEventGate()
        let ticket = gate.issueTicket()
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let take = JumpVideoCaptureTakeContext(epoch: 9, fileURL: fileURL, ticketGeneration: ticket.generation)
        XCTAssertTrue(take.acceptsTicket(currentGeneration: gate.currentGeneration()))
        gate.setForegroundActive(false)
        gate.invalidate()
        XCTAssertFalse(take.acceptsTicket(currentGeneration: gate.currentGeneration()),
            "interrupted take ticket must not validate for recorded UI")
        XCTAssertFalse(gate.isTicketLive(ticket))
    }

    func testTakeIntentDisposeDefersFullTeardownUntilMatchingFinish() throws {
        // ACTUAL intent consumed by the engine dispose/finish path plus the
        // owned lease the engine finalizes only at the matching delegate.
        var starting = JumpVideoCaptureTakeIntent()
        starting.request(.dispose)
        // Further cancel cannot downgrade dispose.
        starting.request(.stop)
        starting.request(.suspend)
        XCTAssertEqual(starting.termination, .dispose)
        // Matching native finish produces fullTeardown only THEN...
        XCTAssertEqual(starting.nativeFinished(), .fullTeardown)
        // ...duplicate finish emits nothing.
        XCTAssertEqual(starting.nativeFinished(), .none)
        // Disposed-take file: kept before writer finish, pinned while
        // borrowed, removed only after real release (owned fixtures only).
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var sweptByTest = false
        defer { if !sweptByTest { try? FileManager.default.removeItem(at: directory) } }
        try Data("dispose".utf8).write(to: fileURL)
        XCTAssertFalse(JumpVideoCapturePaths.isOwnedCaptureURL(URL(fileURLWithPath: "/tmp/foreign.mov")),
            "foreign URL never qualifies as an owned take context")
        let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
        XCTAssertNil(lease.acquireBorrower(), "borrow before finalization fails closed")
        lease.dispose()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path),
            "dispose before matching finish keeps OWN file (no teardown before delegate)")
        lease.markWriterFinalized()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path),
            "physical removal only after writer-finalized (deferred teardown)")
        sweptByTest = true
        // While-borrowed variant: transfer pins across dispose until release.
        let (secondDir, secondURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        var secondSweptByTest = false
        defer { if !secondSweptByTest { try? FileManager.default.removeItem(at: secondDir) } }
        try Data("pinned".utf8).write(to: secondURL)
        let second = JumpVideoCaptureLease(ownedDirectory: secondDir, fileURL: secondURL)
        second.markWriterFinalized()
        guard let borrower = second.acquireBorrower() else {
            XCTFail("finalized lease must vend borrower")
            return
        }
        second.dispose()
        XCTAssertTrue(FileManager.default.fileExists(atPath: secondURL.path))
        borrower.release()
        XCTAssertFalse(FileManager.default.fileExists(atPath: secondURL.path))
        secondSweptByTest = true
    }

    func testReadinessPredicateGatesRepeatFromFailedAndHiddenReentry() {
        // ACTUAL readiness consumed by the engine startRecording and
        // prepareForNewRecording paths (ready implies live graph + running
        // session + not suspended + live ticket + foreground).
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: false, sessionRunning: false, suspended: false,
            ticketLive: true, activeNow: true).isReady,
            "failed repeat without graph must not advertise ready")
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: false, sessionRunning: true, suspended: false,
            ticketLive: true, activeNow: true).isReady)
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: false, suspended: false,
            ticketLive: true, activeNow: true).isReady,
            "stopped session is never ready")
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: true, suspended: true,
            ticketLive: true, activeNow: true).isReady,
            "suspended session is never ready")
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: true, suspended: false,
            ticketLive: false, activeNow: true).isReady,
            "stale-ticket start is never ready")
        XCTAssertFalse(JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: true, suspended: false,
            ticketLive: true, activeNow: false).isReady,
            "hidden reentry is never ready")
        XCTAssertTrue(JumpVideoCaptureReadiness(
            graphPresent: true, sessionRunning: true, suspended: false,
            ticketLive: true, activeNow: true).isReady,
            "explicit configured running foreground take with live ticket is ready")
        // Readiness never bypasses the phase gate.
        XCTAssertFalse(JumpVideoCapturePolicy.canStartRecording(phase: .failed))
        XCTAssertTrue(JumpVideoCapturePolicy.canStartRecording(phase: .ready))
    }

    // C1 small-closure regressions (authored, not executed here): 4
    // synchronous checks over the ACTUAL engine-consumed helper/callsites
    // repaired in this slice (explicit TakeContext init, per-take intent
    // reset, split app-foreground/view-visible gate, post-configure
    // pre-start guard). Temp-owned fixtures only; no camera/session
    // instantiation, no permission/hardware, no awaits, no device resets.

    func testExplicitTakeContextInitializerPreservesDefaultAndProvidedGeneration() throws {
        // ACTUAL TakeContext init the engine calls with 3 args at
        // startRecording (pendingTake store) while legacy 2-arg
        // construction keeps compiling (Swift SE-0242: a stored `let` with
        // a declaration default is omitted from the synthesized memberwise
        // init, so the explicit init below is required for the 3-arg call).
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("legacy".utf8).write(to: fileURL)
        let legacy = JumpVideoCaptureTakeContext(epoch: 5, fileURL: fileURL)
        XCTAssertEqual(legacy.ticketGeneration, 0,
            "legacy 2-arg construction defaults to generation 0")
        XCTAssertTrue(legacy.acceptsTicket(currentGeneration: 0))
        XCTAssertFalse(legacy.acceptsTicket(currentGeneration: 1))
        let explicit = JumpVideoCaptureTakeContext(epoch: 5, fileURL: fileURL, ticketGeneration: 42)
        XCTAssertEqual(explicit.ticketGeneration, 42)
        XCTAssertEqual(explicit.epoch, 5)
        XCTAssertEqual(explicit.fileURL, fileURL)
        XCTAssertTrue(explicit.acceptsTicket(currentGeneration: 42))
        XCTAssertFalse(explicit.acceptsTicket(currentGeneration: 0))
        // Engine 3-arg path: the stored take accepts the live gate ticket
        // and refuses it after a synchronous invalidate.
        let gate = JumpVideoCaptureEventGate()
        let ticket = gate.issueTicket()
        let engineTake = JumpVideoCaptureTakeContext(
            epoch: 7, fileURL: fileURL, ticketGeneration: ticket.generation)
        XCTAssertTrue(engineTake.acceptsTicket(currentGeneration: gate.currentGeneration()))
        XCTAssertTrue(gate.isTicketLive(ticket))
        gate.invalidate()
        XCTAssertFalse(engineTake.acceptsTicket(currentGeneration: gate.currentGeneration()))
        XCTAssertFalse(gate.isTicketLive(ticket))
    }

    func testTwoConsecutiveTakesResetAuthoritativeIntentAndFinishConsumed() {
        // ACTUAL per-take reset the engine calls in startRecording AFTER
        // readiness/disposed/output guards and owned dir/lease success,
        // BEFORE storing pendingTake/.starting/nativeStart. Drives the SAME
        // intent store through TWO takes (not fresh independent structs).
        var intent = JumpVideoCaptureTakeIntent()
        // Take 1: genuine start -> single stop -> native finish consumed.
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .markRecording)
        XCTAssertTrue(intent.stopFromRecording())
        XCTAssertFalse(intent.stopFromRecording(), "no duplicate stop")
        XCTAssertEqual(intent.nativeFinished(), .none,
            "plain-stop take consumes finish with no deferred effect")
        XCTAssertEqual(intent.nativeFinished(), .none, "second finish emits none")
        // Without the engine reset the second take would stick: a finished
        // intent ignores the next native start (proves the missing-reset shape).
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .ignore)
        // Engine per-take reset for the second owned take: ALL termination
        // and the finish-consumed latch clear.
        intent.resetForNewTake()
        XCTAssertEqual(intent.mode, .starting)
        XCTAssertEqual(intent.termination, .none)
        // Take 2: genuine start, then parked stop + dispose executes once ->
        // fullTeardown exactly once at the matching finish; duplicates ignore.
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .markRecording)
        intent.request(.stop)
        XCTAssertEqual(intent.termination, .stop)
        intent.request(.dispose)
        XCTAssertEqual(intent.termination, .dispose)
        intent.request(.suspend)
        XCTAssertEqual(intent.termination, .dispose, "later request never downgrades dispose")
        XCTAssertEqual(intent.nativeFinished(), .fullTeardown)
        XCTAssertEqual(intent.nativeFinished(), .none, "duplicate finish emits none")
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .ignore, "late start after finish ignored")
        // Third owned take after another reset: parked-stop path still works.
        intent.resetForNewTake()
        XCTAssertEqual(intent.mode, .starting)
        XCTAssertEqual(intent.termination, .none)
        intent.request(.stop)
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .stopOnce,
            "parked stop executes exactly once on native start")
        XCTAssertEqual(intent.nativeStarted(uiLive: true), .ignore)
        XCTAssertEqual(intent.nativeFinished(), .none)
        XCTAssertEqual(intent.nativeFinished(), .none)
    }

    func testViewAppearanceCannotOverrideTrueBackgroundEligibility() {
        // ACTUAL split gate the engine/facade consult (setForegroundActive =
        // app foreground only, setViewVisible = view only, isForegroundActive
        // = combined eligibility, canBeginNativeStart = atomic ticket+both).
        // Simulates the facade init: allocated but hidden.
        let gate = JumpVideoCaptureEventGate()
        gate.setViewVisible(false)
        gate.setForegroundActive(true)
        XCTAssertFalse(gate.isViewVisible())
        XCTAssertTrue(gate.isAppForeground())
        XCTAssertFalse(gate.isForegroundActive(),
            "foreground while view-hidden stays ineligible")
        // True background followed by a spurious appearance: viewVisible
        // true must NOT resurrect the backgrounded app state.
        gate.setForegroundActive(false)
        gate.setViewVisible(true)
        XCTAssertTrue(gate.isViewVisible())
        XCTAssertFalse(gate.isAppForeground())
        XCTAssertFalse(gate.isForegroundActive(),
            "appearance must not override true background")
        let bgTicket = gate.issueTicket()
        XCTAssertFalse(gate.canBeginNativeStart(ticket: bgTicket),
            "no native start while backgrounded even when view-visible")
        // Foreground restored while view hidden: still ineligible.
        gate.setViewVisible(false)
        gate.setForegroundActive(true)
        XCTAssertFalse(gate.isForegroundActive())
        let hiddenTicket = gate.issueTicket()
        XCTAssertFalse(gate.canBeginNativeStart(ticket: hiddenTicket),
            "foreground alone while view-hidden stays ineligible")
        // Both true + current ticket: eligible.
        gate.setViewVisible(true)
        XCTAssertTrue(gate.isForegroundActive())
        let liveTicket = gate.issueTicket()
        XCTAssertTrue(gate.canBeginNativeStart(ticket: liveTicket))
        // Old ticket after a synchronous invalidate: rejected.
        gate.invalidate()
        XCTAssertFalse(gate.isTicketLive(liveTicket))
        XCTAssertFalse(gate.canBeginNativeStart(ticket: liveTicket))
        // Updating foreground ALONE changes no ticket liveness and emits no
        // native command (pure gate: generation untouched by flag writes).
        let genBefore = gate.currentGeneration()
        gate.setForegroundActive(false)
        gate.setForegroundActive(true)
        XCTAssertEqual(gate.currentGeneration(), genBefore)
        let freshTicket = gate.issueTicket()
        XCTAssertTrue(gate.canBeginNativeStart(ticket: freshTicket))
        // Disposed: never eligible even with both flags forced true.
        gate.markDisposed()
        gate.setViewVisible(true)
        gate.setForegroundActive(true)
        XCTAssertFalse(gate.isForegroundActive(), "disposed never eligible")
        XCTAssertFalse(gate.canBeginNativeStart(ticket: freshTicket))
        XCTAssertFalse(gate.isTicketLive(freshTicket))
    }

    func testInvalidatedConfigureTicketRefusesNewStartAndPostStartRequiresRollback() {
        // ACTUAL pre-start predicate the engine invokes in prepareOnQueue
        // AFTER blocking configure BEFORE new startSession (and the KEPT
        // post-start rollback for an invalidate racing startRunning itself).
        let gate = JumpVideoCaptureEventGate()
        gate.setViewVisible(true)
        gate.setForegroundActive(true)
        // Ticket valid before configure: pre-start permits the new start.
        let ticket = gate.issueTicket()
        XCTAssertTrue(gate.canBeginNativeStart(ticket: ticket))
        // Invalidate DURING the blocking configure (synchronous Main
        // view-exit/cancel/background racing configure): pre-start refuses
        // the new native start; the own non-recording graph tears down.
        gate.invalidate()
        XCTAssertFalse(gate.isTicketLive(ticket))
        XCTAssertFalse(gate.canBeginNativeStart(ticket: ticket),
            "stale ticket between configure and start must refuse native start")
        // Background during configure: same refusal via the both-flags gate.
        let gate2 = JumpVideoCaptureEventGate()
        gate2.setViewVisible(true)
        gate2.setForegroundActive(true)
        let bgTicket = gate2.issueTicket()
        XCTAssertTrue(gate2.canBeginNativeStart(ticket: bgTicket))
        gate2.setForegroundActive(false)
        XCTAssertFalse(gate2.canBeginNativeStart(ticket: bgTicket),
            "background during configure must refuse native start")
        // Valid new explicit request after recovery: pre-start permits.
        gate2.setForegroundActive(true)
        let retryTicket = gate2.issueTicket()
        XCTAssertTrue(gate2.isTicketLive(retryTicket))
        XCTAssertTrue(gate2.canBeginNativeStart(ticket: retryTicket))
        // Invalidation DURING the already-entered native start: the same
        // predicate fails post-start, so the engine keeps its post-start
        // rollback (stop + teardown + idle) instead of keeping stale ready.
        gate2.invalidate()
        XCTAssertFalse(gate2.isTicketLive(retryTicket))
        XCTAssertFalse(gate2.canBeginNativeStart(ticket: retryTicket),
            "invalidation during start must fail post-start validation for rollback")
        // Disposed: pre-start never permits.
        gate2.markDisposed()
        XCTAssertFalse(gate2.canBeginNativeStart(ticket: retryTicket))
    }

    func testDelegateProgressKeepsTakeOriginAfterCancelInvalidatesGeneration() throws {
        // Delegate-origin regression (authored, not executed here): the three
        // take-progress publishes in didStartRecordingOnQueue (.markRecording
        // -> .recording; .stopOnce -> .finalizing) and finishRecordingOnQueue
        // (accepted success -> .recorded) stamp take.originatingTicket, so a
        // MAIN-thread synchronous invalidate landing inside the serial finish
        // file-IO window cannot launder the stale take under the NEW
        // generation. Lifecycle/cleanup convergence keeps the NIL/current
        // fallback (publishLocked ticket:nil -> currentGeneration).
        let gate = JumpVideoCaptureEventGate()
        let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("origin".utf8).write(to: fileURL)
        // T0: serial finish snapshots acceptance while g0 is still live.
        let origin = gate.issueTicket()
        let take = JumpVideoCaptureTakeContext(
            epoch: 9, fileURL: fileURL, ticketGeneration: origin.generation)
        XCTAssertTrue(take.acceptsTicket(currentGeneration: gate.currentGeneration()))
        let serialAcceptedSnapshot = take.acceptsTicket(currentGeneration: gate.currentGeneration())
        XCTAssertTrue(serialAcceptedSnapshot)
        // T1: MAIN cancel synchronously invalidates g0 -> g1 behind finish.
        let currentAfterCancel = gate.invalidate()
        XCTAssertNotEqual(origin.generation, currentAfterCancel)
        // Stored take origin never follows the live gate.
        XCTAssertEqual(take.originatingTicket.generation, take.ticketGeneration)
        XCTAssertEqual(take.originatingTicket.generation, origin.generation)
        XCTAssertNotEqual(take.originatingTicket.generation, gate.currentGeneration())
        // Stale origin event rejected; current-gen cleanup accepted.
        XCTAssertFalse(gate.accepts(eventGeneration: take.originatingTicket.generation))
        XCTAssertTrue(gate.accepts(eventGeneration: currentAfterCancel))
        XCTAssertTrue(gate.accepts(eventGeneration: gate.currentGeneration()))
        // Old origin can never begin a new native start.
        XCTAssertFalse(gate.canBeginNativeStart(ticket: take.originatingTicket))
        XCTAssertFalse(gate.isTicketLive(take.originatingTicket))
    }

    // MARK: - iOS16-XR host lifecycle replay (bounded, 2026-10-09)
    //
    // Cross-layer replay of the ACTUAL host decision consumed by
    // JumpWorkflowViews (isCameraLifetimeVisible for viewAppeared/
    // viewDisappeared + isCameraEligible for explicit taps + cameraFirstAction
    // for the open/record split) against the ACTUAL locked event gate. No
    // AVCaptureSession is created. Expectations are hardcoded literals.
    func testHostLifecycleReplayPreservesAlertTicketAndInvalidatesTrueExits() {
        // Facade init: allocated but hidden (viewVisible false, foreground
        // true). Host lifetime with hidden tab fails closed.
        let gate = JumpVideoCaptureEventGate()
        gate.setViewVisible(false)
        gate.setForegroundActive(true)
        XCTAssertFalse(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: false, isForegroundScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(gate.isForegroundActive())
        // Obtain visible foreground active: host appears (viewVisible true).
        // Lifetime + actions both true; the originating ticket is live.
        XCTAssertTrue(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: true, isForegroundScene: true, visibleRoute: .obtainVideo))
        XCTAssertTrue(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: .obtainVideo))
        gate.setViewVisible(true)
        XCTAssertTrue(gate.isForegroundActive())
        let alertTicket = gate.issueTicket()
        XCTAssertTrue(gate.isTicketLive(alertTicket))
        XCTAssertTrue(gate.canBeginNativeStart(ticket: alertTicket))
        // First-tap split at this point: idle opens (prepare only), never
        // records by itself.
        XCTAssertEqual(JumpWorkflowPresentation.cameraFirstAction(for: .idle), .openCamera)
        XCTAssertEqual(JumpWorkflowPresentation.recordButtonKey(for: .idle), "jumps.camera.open")
        XCTAssertFalse(JumpVideoCapturePolicy.canStartRecording(phase: .idle))
        // Transient inactive permission alert (still OS foreground): host
        // lifetime TRUE so it takes NO disappear/invalidate; NEW taps stay
        // active-only false. The originating ticket MUST survive.
        XCTAssertTrue(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: true, isForegroundScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: false, visibleRoute: .obtainVideo))
        // No gate touch here by host contract (permission alerts never call
        // setForegroundActive/setViewVisible/invalidate).
        XCTAssertTrue(gate.isTicketLive(alertTicket), "inactive alert must preserve originating ticket")
        XCTAssertTrue(gate.canBeginNativeStart(ticket: alertTicket), "inactive alert must preserve native-start eligibility")
        XCTAssertTrue(gate.isForegroundActive())
        // Ready explicit record still requires the live ticket + foreground.
        XCTAssertEqual(JumpWorkflowPresentation.cameraFirstAction(for: .ready), .record)
        XCTAssertEqual(JumpWorkflowPresentation.recordButtonKey(for: .ready), "jumps.camera.record")
        XCTAssertTrue(JumpVideoCapturePolicy.canStartRecording(phase: .ready))
        // True background: host lifetime FALSE so it MUST disappear +
        // invalidate (view hidden + generation bump). Old ticket stale.
        XCTAssertFalse(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: true, isForegroundScene: false, visibleRoute: .obtainVideo))
        gate.setViewVisible(false)
        gate.invalidate()
        gate.setForegroundActive(false)
        XCTAssertFalse(gate.isTicketLive(alertTicket), "true background must invalidate originating ticket")
        XCTAssertFalse(gate.canBeginNativeStart(ticket: alertTicket))
        XCTAssertFalse(gate.isForegroundActive())
        // Foreground return + explicit re-appear issues a FRESH live ticket
        // (never resurrects the old one).
        gate.setForegroundActive(true)
        gate.setViewVisible(true)
        XCTAssertTrue(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: true, isForegroundScene: true, visibleRoute: .obtainVideo))
        let routeTicket = gate.issueTicket()
        XCTAssertTrue(gate.isTicketLive(routeTicket))
        XCTAssertTrue(gate.canBeginNativeStart(ticket: routeTicket))
        XCTAssertFalse(gate.isTicketLive(alertTicket))
        // Route exit (Obtain -> Prepare) while foreground active: lifetime
        // FALSE, MUST invalidate (no inert early-return keeps it visible).
        XCTAssertFalse(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: true, isForegroundScene: true, visibleRoute: .prepare))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: true, isActiveScene: true, visibleRoute: .prepare))
        gate.setViewVisible(false)
        gate.invalidate()
        XCTAssertFalse(gate.isTicketLive(routeTicket), "route exit must invalidate")
        XCTAssertFalse(gate.canBeginNativeStart(ticket: routeTicket))
        // Re-appear on Obtain, then hidden tab: MUST invalidate as well.
        gate.setViewVisible(true)
        let tabTicket = gate.issueTicket()
        XCTAssertTrue(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: true, isForegroundScene: true, visibleRoute: .obtainVideo))
        XCTAssertTrue(gate.isTicketLive(tabTicket))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraLifetimeVisible(isVisible: false, isForegroundScene: true, visibleRoute: .obtainVideo))
        XCTAssertFalse(JumpWorkflowPresentation.isCameraEligible(isVisible: false, isActiveScene: true, visibleRoute: .obtainVideo))
        gate.setViewVisible(false)
        gate.invalidate()
        XCTAssertFalse(gate.isTicketLive(tabTicket), "hidden tab must invalidate")
        XCTAssertFalse(gate.canBeginNativeStart(ticket: tabTicket))
    }
}
