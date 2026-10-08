import AVFoundation
import Combine
import CoreMedia
import Foundation
import UIKit

// C1 camera foundation: video-only capture engine + thin MainActor facade.
// Real implementation (not stubs). No UI preview hosting here (C2); no
// JumpWorkflowState mutation; no import/index; no measurement history writes.
//
// Threading contract:
// - ALL AVCaptureSession verbs (begin/commit/canAdd/add, device lock,
//   startRunning/stopRunning, startRecording/stopRecording) run on ONE private
//   serial queue, never MainActor. MainActor owns observable UI values only.
// - Delegate / notification / permission callbacks enter the serial queue.
//   UI events bridge to MainActor via DispatchQueue.main.async with
//   epoch/active guards. No DispatchQueue.main.sync, no assumeIsolated.
// - Permission uses the closure requestAccess(for:completionHandler:) path.
// - Target floor is iOS 16: no videoRotationAngle, no
//   isVideoFrameDurationLocked, no isInUseByAnotherApplication, no async
//   requestAccess, no 2-arg onChange, no ContentUnavailableView.
// - Video-only: .video authorization, video input + movie file output. No
//   audio input, no microphone key, no Photos/gallery write, no cloud/upload.

// MARK: - Safety bounds (bounds, never measurement inputs)

enum JumpVideoCaptureLimits {
    /// Maximum wall-clock recording length. Safety bound only.
    static let maximumDuration = CMTime(value: 30, timescale: 1)
    /// Maximum file size, below the existing 512 MiB importer limit.
    static let maximumFileSize: Int64 = 256 * 1024 * 1024
    static let stagingRootName = "OpenJumpCapture"
    static let captureFileName = "capture.mov"
    /// Upper bound accepted from runtime format discovery (matches indexer).
    static let maximumDimension: CGFloat = 4096
}

// MARK: - Phases and static UI keys

/// Engine/facade phase. `starting` is the native start intent after
/// startRecording(to:) until didStartRecordingTo proves the writer actually
/// began; `recording` is only after real native start; `finalizing` persists
/// from stopRecording() until didFinishRecordingTo proves all pending data
/// was written. stopRecording() returning is NOT finalization.
enum JumpVideoCapturePhase: Equatable {
    case idle
    case requestingPermission
    case preparing
    case ready
    case starting
    case recording
    case finalizing
    case recorded
    case denied
    case unavailable
    case failed
}

/// Static Localizable.strings keys used by the facade. Raw NSError text and
/// file paths are never exposed to UI.
enum JumpVideoCaptureStrings {
    static let permissionDenied = "jumps.camera.permissionDenied"
    static let unavailable = "jumps.camera.unavailable"
    static let configurationFailed = "jumps.camera.configurationFailed"
    static let recordingFailed = "jumps.camera.recordingFailed"
    static let interrupted = "jumps.camera.interrupted"
    static let backgroundStopped = "jumps.camera.backgroundStopped"
    static let finalizing = "jumps.camera.finalizing"
    static let preparing = "jumps.camera.preparing"
    static let ready = "jumps.camera.ready"
    static let recording = "jumps.camera.recording"
    static let review = "jumps.camera.review"
    static let limitReached = "jumps.camera.limitReached"
}

// MARK: - Pure permission + gate policy (used by engine AND unit tests)

/// Internal permission snapshot. Maps 1:1 from AVAuthorizationStatus for
/// .video; no microphone/gallery status is consulted anywhere.
enum JumpVideoCapturePermission: Equatable {
    case authorized
    case denied
    case restricted
    case notDetermined
}

enum JumpVideoCapturePolicy {
    static func permission(for status: AVAuthorizationStatus) -> JumpVideoCapturePermission {
        switch status {
        case .authorized: return .authorized
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }

    /// Fail-closed phase for a resolved permission. Late grants are handled
    /// separately by shouldStartCaptureAfterGrant (never auto-start).
    static func phase(for permission: JumpVideoCapturePermission) -> JumpVideoCapturePhase {
        switch permission {
        case .authorized: return .preparing
        case .denied, .restricted: return .denied
        case .notDetermined: return .requestingPermission
        }
    }

    /// Late-grant guard: a permission grant received while the request is no
    /// longer active (cancelled, backgrounded, view invisible, disposed) must
    /// NOT start capture. Used by the engine prepare path (gate BEFORE any
    /// .preparing publish/config/start). Actual recording start follows
    /// session readiness, never the grant itself. No automatic recording ever.
    /// Active idle/retry (idle/denied/failed when active) may proceed;
    /// starting/recording/finalizing/recorded/ready/unavailable never start
    /// from a grant until explicitly discarded/re-prepared.
    static func shouldStartCaptureAfterGrant(
        requestWasActive: Bool,
        isActiveNow: Bool,
        phase: JumpVideoCapturePhase
    ) -> Bool {
        guard requestWasActive && isActiveNow else { return false }
        switch phase {
        case .idle, .requestingPermission, .preparing, .denied, .failed:
            return true
        case .ready, .starting, .recording, .finalizing, .recorded, .unavailable:
            return false
        }
    }

    static func canStartRecording(phase: JumpVideoCapturePhase) -> Bool {
        phase == .ready
    }

    /// Idempotent stop: exactly one stop is honoured from a live take.
    /// `.starting` parks the stop intent until real native start (never lost
    /// as a no-op); `.recording` stops immediately. Second stops while
    /// finalizing, and stops from idle/denied/unavailable/failed/recorded/
    /// preparing/requesting/ready, are rejected (no duplicate stop/finalize).
    /// Used by the engine stop entry (starting parks, recording stops).
    static func canStopRecording(phase: JumpVideoCapturePhase) -> Bool {
        phase == .starting || phase == .recording
    }

    /// Use / repeat / new recording are only valid from a finalized recorded
    /// candidate. Never from finalizing (writer not finalized yet).
    static func canUseRecorded(phase: JumpVideoCapturePhase) -> Bool {
        phase == .recorded
    }

    static func canBeginNewRecording(phase: JumpVideoCapturePhase) -> Bool {
        phase == .ready || phase == .recorded || phase == .failed
    }

    /// Whether a stop arriving during `.starting` must be parked (not lost).
    /// Used by the engine stop/didStart path (park until real native start,
    /// execute exactly once); pure so unit tests and engine agree.
    static func shouldParkStopDuringStarting(phase: JumpVideoCapturePhase) -> Bool {
        phase == .starting
    }

    /// Suspend intent survives cancel during finalizing: a cancel must never
    /// clear an already-parked suspend/dispose. Always false (never clear).
    /// Used by the engine cancel path; pure so tests pin the invariant.
    static func cancelClearsSuspendDuringFinalizing() -> Bool {
        false
    }

    /// View-exit/background recovery decision consumed by the engine
    /// viewDisappeared/background blocks: requesting/preparing/ready/failed/
    /// recorded converge to an eligible idle WITHOUT auto-recording, so an
    /// explicit visible retry is accepted afterwards. Starting/recording/
    /// finalizing take the parked take-intent path instead (never here).
    /// Pure so unit tests and the engine agree.
    static func idleResetPhaseForViewExit(phase: JumpVideoCapturePhase) -> JumpVideoCapturePhase? {
        switch phase {
        case .requestingPermission, .preparing, .ready, .failed, .recorded:
            return .idle
        case .idle, .denied, .unavailable, .starting, .recording, .finalizing:
            return nil
        }
    }

    /// Epoch/UI acceptance for delegate + notification callbacks. Stale
    /// callbacks (superseded epoch, torn-down session, foreign URL) are
    /// rejected for UI purposes. Cleanup of their OWN finalized raw-file
    /// context still runs separately (see engine); foreign directories are
    /// never touched.
    static func acceptsUICallback(
        capturedEpoch: UInt64,
        currentEpoch: UInt64,
        capturedURL: URL,
        currentURL: URL?
    ) -> Bool {
        guard capturedEpoch == currentEpoch else { return false }
        guard let currentURL else { return false }
        return capturedURL == currentURL
    }

    /// Success classifier used by the real engine delegate:
    /// error == nil OR NSError.userInfo[AVErrorRecordingSuccessfullyFinishedKey]
    /// as? Bool == true. Every other error fails closed.
    static func isRecordingSuccess(error: Error?) -> Bool {
        guard let error else { return true }
        let nsError = error as NSError
        return nsError.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true
    }

    static func statusKey(for phase: JumpVideoCapturePhase) -> String {
        switch phase {
        case .idle: return JumpVideoCaptureStrings.ready
        case .requestingPermission, .preparing: return JumpVideoCaptureStrings.preparing
        case .ready: return JumpVideoCaptureStrings.ready
        case .starting, .recording: return JumpVideoCaptureStrings.recording
        case .finalizing: return JumpVideoCaptureStrings.finalizing
        case .recorded: return JumpVideoCaptureStrings.review
        case .denied: return JumpVideoCaptureStrings.permissionDenied
        case .unavailable: return JumpVideoCaptureStrings.unavailable
        case .failed: return JumpVideoCaptureStrings.recordingFailed
        }
    }
}

/// Authoritative readiness consumed by the engine start/reprepare path:
/// `.ready` must imply a live graph + running session + not suspended + a
/// live originating ticket + foreground visibility. Built by the engine from
/// live session state on its serial queue; pure so unit tests pin the exact
/// predicate the engine enforces (never a bare phase check).
struct JumpVideoCaptureReadiness: Equatable, Sendable {
    var graphPresent: Bool
    var sessionRunning: Bool
    var suspended: Bool
    var ticketLive: Bool
    var activeNow: Bool
    var isReady: Bool {
        graphPresent && sessionRunning && !suspended && ticketLive && activeNow
    }
}

// MARK: - Owned paths (unique per recording, backup-excluded)

enum JumpVideoCapturePaths {
    /// Creates unique temporaryDirectory/OpenJumpCapture/<UUID>/capture.mov,
    /// excluded from backup where applicable. Caller owns the directory.
    static func makeOwnedCaptureDirectory(
        fileManager: FileManager = .default
    ) throws -> (directory: URL, fileURL: URL) {
        let temporary = fileManager.temporaryDirectory
        let root = temporary.appendingPathComponent(JumpVideoCaptureLimits.stagingRootName, isDirectory: true)
        let owned = root.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
        try fileManager.createDirectory(at: owned, withIntermediateDirectories: true)
        try setExcludedFromBackup(at: root)
        try setExcludedFromBackup(at: owned)
        let fileURL = owned.appendingPathComponent(JumpVideoCaptureLimits.captureFileName)
        return (owned, fileURL)
    }

    static func setExcludedFromBackup(at url: URL) throws {
        var mutable = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try mutable.setResourceValues(values)
    }

    /// True only for our own generated layout (…/OpenJumpCapture/<uuid>/capture.mov).
    static func isOwnedCaptureURL(_ url: URL) -> Bool {
        guard url.isFileURL else { return false }
        guard url.lastPathComponent == JumpVideoCaptureLimits.captureFileName else { return false }
        let parent = url.deletingLastPathComponent()
        guard UUID(uuidString: parent.lastPathComponent) != nil else { return false }
        return parent.deletingLastPathComponent().lastPathComponent == JumpVideoCaptureLimits.stagingRootName
    }

    /// Exact pair guard used by the lease sweep/borrow barrier: the directory
    /// must be the canonical parent of the file AND the file must live under
    /// our own temporaryDirectory/OpenJumpCapture/<UUID>/capture.mov. Rejects
    /// mismatched pairs and foreign roots without crashing or deleting
    /// anything foreign (lexical canonicalization via standardizedFileURL
    /// plus temporary-root prefix plus exact depth plus owned-factory
    /// provenance; symlinks are not resolved). Used by the engine-owned lease.
    static func isValidOwnedPair(directory: URL, fileURL: URL, fileManager: FileManager = .default) -> Bool {
        guard isOwnedCaptureURL(fileURL) else { return false }
        let canonicalDir = directory.standardizedFileURL
        let canonicalParent = fileURL.deletingLastPathComponent().standardizedFileURL
        guard canonicalDir == canonicalParent else { return false }
        let expectedRoot = fileManager.temporaryDirectory
            .appendingPathComponent(JumpVideoCaptureLimits.stagingRootName, isDirectory: true)
            .standardizedFileURL
        guard canonicalDir.path.hasPrefix(expectedRoot.path + "/") else { return false }
        return canonicalDir.deletingLastPathComponent().standardizedFileURL == expectedRoot
    }
}

// MARK: - Owned recording lease + borrower barrier

// Non-negotiable handoff obligations (enforced by this lease, rechecked in C2):
// - The raw capture file is an explicit OWNER lease, finalized separately
//   from any UI result. source is always .camera, never .files.
// - markDiscard/dispose are idempotent. Physical removal deletes ONLY this
//   lease's own generated UUID directory, and ONLY after the writer finalized
//   (delegate fired) AND every acquired borrower released. Unique directory
//   per recording; never sweep old roots.
// - Borrower holds its owner strongly. The file URL is borrowed only after
//   finalization/success; never hand out a raw unowned URL as the sole
//   public handoff.
// - A preview consumer MUST tear down its player/item/observers BEFORE
//   releasing its borrower. A future importer MUST retain the borrower INSIDE
//   the worker until real worker-exit/ownership-transfer, NOT merely until a
//   cancellation continuation resumes or a deadline fires. No importer
//   changes exist in C1.

final class JumpVideoCaptureLease: @unchecked Sendable {
    /// Provenance of every captured file. Never .files/.photos for capture.
    static let captureSource: JumpVideoSource = .camera

    let fileURL: URL
    let source: JumpVideoSource = .camera

    private let ownedDirectory: URL
    private let lock = NSLock()
    private var writerFinalized = false
    private var borrowerCount = 0
    private var disposed = false

    init(ownedDirectory: URL, fileURL: URL) {
        self.ownedDirectory = ownedDirectory
        self.fileURL = fileURL
    }

    var isWriterFinalized: Bool {
        lock.lock()
        defer { lock.unlock() }
        return writerFinalized
    }

    /// Called exactly once by the engine when didFinishRecordingTo fires for
    /// this lease's own URL. Idempotent.
    func markWriterFinalized() {
        lock.lock()
        writerFinalized = true
        let shouldSweep = disposed && borrowerCount == 0
        lock.unlock()
        if shouldSweep { sweepOwnedDirectory() }
    }

    /// User discard intent (Cancel / Re-record). Idempotent; physical removal
    /// still waits for writer-finalized + all borrowers released.
    func markDiscard() {
        lock.lock()
        disposed = true
        let shouldSweep = writerFinalized && borrowerCount == 0
        lock.unlock()
        if shouldSweep { sweepOwnedDirectory() }
    }

    /// Explicit teardown. Idempotent; same gated removal as markDiscard.
    func dispose() {
        lock.lock()
        disposed = true
        let shouldSweep = writerFinalized && borrowerCount == 0
        lock.unlock()
        if shouldSweep { sweepOwnedDirectory() }
    }

    /// Borrow the finalized file. Returns nil until the writer finalized or
    /// after discard/dispose (fail-closed), and nil for any mismatched
    /// directory pair (never vends foreign layouts). The borrower retains this
    /// owner, so the file cannot vanish while borrowed. Used by the engine
    /// discard probe and the future import worker (retain INSIDE worker until
    /// real exit/transfer).
    func acquireBorrower() -> JumpVideoCaptureBorrower? {
        // Mismatched pair fails closed (never vends, never crashes).
        guard JumpVideoCapturePaths.isValidOwnedPair(directory: ownedDirectory, fileURL: fileURL) else { return nil }
        lock.lock()
        guard writerFinalized, !disposed else {
            lock.unlock()
            return nil
        }
        borrowerCount += 1
        lock.unlock()
        return JumpVideoCaptureBorrower(owner: self)
    }

    fileprivate func releaseBorrower() {
        lock.lock()
        if borrowerCount > 0 { borrowerCount -= 1 }
        let shouldSweep = disposed && writerFinalized && borrowerCount == 0
        lock.unlock()
        if shouldSweep { sweepOwnedDirectory() }
    }

    private func sweepOwnedDirectory() {
        // ONLY our own generated UUID directory under our own temp root with
        // exact directory==fileURL-parent. Never a foreign path, never a
        // mismatched pair. Fail-closed without crashing; the engine only ever
        // passes factory pairs.
        guard JumpVideoCapturePaths.isValidOwnedPair(directory: ownedDirectory, fileURL: fileURL) else { return }
        try? FileManager.default.removeItem(at: ownedDirectory)
    }

    deinit { dispose() }
}

/// Scoped read of a finalized lease. Holds the owner strongly; release (or
/// deinit) is idempotent. Preview/import workers must keep this object alive
/// until they fully exited and tore down their player/item/observers.
final class JumpVideoCaptureBorrower: @unchecked Sendable {
    private let lock = NSLock()
    private var owner: JumpVideoCaptureLease?
    /// Valid only while borrowed (owner retained). Never use after release.
    /// Takes the lock before reading the mutable owner (the fileURL itself is
    /// immutable, but owner is mutated under lock on release). Used by the
    /// engine stale probe and future workers.
    var fileURL: URL? {
        lock.lock()
        defer { lock.unlock() }
        return owner?.fileURL
    }

    init(owner: JumpVideoCaptureLease) {
        self.owner = owner
    }

    func release() {
        lock.lock()
        let owner = self.owner
        self.owner = nil
        lock.unlock()
        owner?.releaseBorrower()
    }

    deinit { release() }
}

// MARK: - Synchronous UI event gate + immutable take (used by engine AND tests)

/// Originating request ticket captured BEFORE the session-queue hop on the
/// actual facade/engine public entry and propagated unchanged through
/// config/start/phase/event publication. Old queued work keeps its OLD
/// generation even after a synchronous MainActor invalidate, so it can never
/// borrow the NEW generation at publish time. Snapshots only; never held
/// across blocking AVFoundation calls. Sendable value type.
struct JumpVideoCaptureRequestTicket: Equatable, Sendable {
    /// Generation frozen at call time. Compared against the live gate under lock.
    let generation: UInt64
}

/// Bounded shared ticket that lets MainActor synchronously invalidate queued
/// engine events even before the serial queue processes cancel/dispose/
/// viewDisappeared/background. The engine stamps every Event with the current
/// generation under lock; the facade accepts only events whose generation
/// still matches at delivery. Merely comparing epoch>=lastApplied is
/// insufficient because cancel is async and a stale event may arrive first.
/// Uses a locked generation (not unrestricted main-reading of serial-queue
/// properties); no weak-self check substitutes for generation. Used by the
/// engine publish path AND the facade onEvent gate.
final class JumpVideoCaptureEventGate: @unchecked Sendable {
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var disposed = false
    /// Live foreground/visible authority owned by this locked gate (never a
    /// stale captured isActive bool). Two independent flags: appForeground
    /// (true OS foreground; a permission-alert temporary inactive never
    /// touches this) and viewVisible (explicit view appeared/disappeared).
    /// Combined eligibility is BOTH && not disposed. Set synchronously from
    /// MainActor lifecycle entries and synchronously at true-background
    /// delivery BEFORE the queue hop.
    private var appForeground = true
    /// View-visible flag. Defaults true ONLY so directly-instantiated unit
    /// gates keep legacy isForegroundActive semantics; the facade init sets
    /// the ACTUAL gate hidden (allocated != visible) before any capture event.
    private var viewVisible = true
    func currentGeneration() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return generation
    }
    func isDisposed() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return disposed
    }
    @discardableResult
    func invalidate() -> UInt64 {
        lock.lock()
        generation &+= 1
        let next = generation
        lock.unlock()
        return next
    }
    func markDisposed() {
        lock.lock()
        disposed = true
        generation &+= 1
        lock.unlock()
    }
    /// Freezes the originating ticket for THIS operation. Call synchronously
    /// at public-entry time, BEFORE sessionQueue.async. Never inside the
    /// queued block (that would read the post-invalidate generation).
    func issueTicket() -> JumpVideoCaptureRequestTicket {
        lock.lock()
        defer { lock.unlock() }
        return JumpVideoCaptureRequestTicket(generation: generation)
    }
    /// True only while this EXACT ticket is still current and not disposed.
    /// Old tickets stay stale after any invalidate; explicit new requests
    /// issue fresh live tickets. Snapshot only; never held across blocking calls.
    func isTicketLive(_ ticket: JumpVideoCaptureRequestTicket) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !disposed && ticket.generation == generation
    }
    /// Sets the APP-foreground flag ONLY (never view visibility). True
    /// background delivery sets false + invalidate synchronously; the
    /// explicit OS foreground notification restores true (never starts
    /// anything by itself). A permission-alert temporary inactive never
    /// calls this.
    func setForegroundActive(_ active: Bool) {
        lock.lock()
        appForeground = active
        lock.unlock()
    }
    /// Sets the VIEW-visible flag ONLY (never app foreground). Facade
    /// viewAppeared sets true; viewDisappeared/dispose set false. Appearance
    /// can never resurrect a true-background appForeground.
    func setViewVisible(_ visible: Bool) {
        lock.lock()
        viewVisible = visible
        lock.unlock()
    }
    /// Queue-safe locked snapshot of app foreground alone.
    func isAppForeground() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return appForeground
    }
    /// Queue-safe locked snapshot of view visibility alone.
    func isViewVisible() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return viewVisible
    }
    /// Combined eligibility as existing callers expect: true app foreground
    /// AND view visible AND not disposed. Every prepare/start/didStart/
    /// finish/UI-delivery aggregate gate consults this, so appearance while
    /// backgrounded (or foreground while view-hidden) stays ineligible.
    func isForegroundActive() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !disposed && appForeground && viewVisible
    }
    /// Atomic pre-start decision for ONE originating ticket: ticket still
    /// live AND both app-foreground and view-visible AND not disposed, under
    /// ONE lock (never held across blocking AVFoundation calls). Consumed by
    /// the prepareOnQueue post-configure pre-start guard and by the
    /// viewAppeared restart pre/post guards.
    func canBeginNativeStart(ticket: JumpVideoCaptureRequestTicket) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !disposed && ticket.generation == generation && appForeground && viewVisible
    }
    func accepts(eventGeneration: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !disposed && eventGeneration == generation
    }
}

/// Immutable snapshot taken at startRecording: the generation and owned URL
/// for THIS take. The delegate uses the ACTUAL policy (never a tautological
/// current==current) to decide UI acceptance; OWN lease finalization still
/// runs even when UI is rejected, and foreign URLs never finalize/publish/
/// delete the current context. Stored by the engine as pendingTake; tested
/// directly by unit tests.
struct JumpVideoCaptureTakeContext {
    let epoch: UInt64
    let fileURL: URL
    /// Originating request-ticket generation for THIS take, frozen at the
    /// startRecording entry before the queue hop (never relabelled with the
    /// current generation at publish). Declared WITHOUT a default initializer
    /// because a stored `let` with a declaration default is OMITTED from the
    /// synthesized memberwise initializer (Swift SE-0242: only variables get
    /// synthesized defaults, not constants); the explicit init below keeps
    /// BOTH legacy 2-arg construction (defaults to 0) and the engine 3-arg
    /// call compiling with identical semantics. Old acceptsUI semantics are
    /// unchanged.
    let ticketGeneration: UInt64
    /// Explicit initializer preserving legacy 2-arg construction (default 0)
    /// and the actual engine 3-arg call. Assigns ALL stored lets.
    init(epoch: UInt64, fileURL: URL, ticketGeneration: UInt64 = 0) {
        self.epoch = epoch
        self.fileURL = fileURL
        self.ticketGeneration = ticketGeneration
    }
    func acceptsUI(currentEpoch: UInt64, currentURL: URL?) -> Bool {
        JumpVideoCapturePolicy.acceptsUICallback(
            capturedEpoch: epoch,
            currentEpoch: currentEpoch,
            capturedURL: fileURL,
            currentURL: currentURL)
    }
    /// Ticket acceptance for delegate/UI purposes: the take's originating
    /// generation must still equal the live gate. Consumed by the engine
    /// didStart/finish paths alongside acceptsUI + suspension + disposal.
    func acceptsTicket(currentGeneration: UInt64) -> Bool {
        ticketGeneration == currentGeneration
    }
    /// Reconstructs the originating request ticket for THIS take from the
    /// stored generation (never the live gate's current generation), so the
    /// didStart/finish take-progress publishes cannot borrow a post-invalidate
    /// generation at publish time. No new stored state; pure value mapping.
    var originatingTicket: JumpVideoCaptureRequestTicket {
        JumpVideoCaptureRequestTicket(generation: ticketGeneration)
    }
}

// MARK: - Runtime capabilities (reported, never asserted as clocks)

// MARK: - Authoritative take intent (consumed by the engine, tested directly)

/// Small pure take-intent/transition context. This is the SINGLE authority
/// for starting/recording/finalizing/finished take transitions and for
/// stop/suspend/dispose termination intents; the engine executes the returned
/// effects on its serial queue (retaining URL + lease independently) and
/// issues AVCapture verbs exactly as directed. No output.stop is issued from
/// observers bypassing this context.
///
/// Merge is monotonic (dispose > suspend > plain stop): a later cancel can
/// never downgrade an already-parked suspend/dispose. Transient termination
/// is consumed ONLY by the matching native finish (exactly once), never by
/// cancel. Duplicate native starts have no effect; finish without start
/// still emits the deferred termination effect exactly once; a second finish
/// emits none. Wrong-URL callbacks are rejected by the engine BEFORE
/// touching this context, and clean up their OWN context only. A stalled
/// delegate retains ownership; finalization is never manufactured.
struct JumpVideoCaptureTakeIntent: Equatable, Sendable {
    /// Live take mode. `finished` is terminal for this take.
    enum Mode: Equatable, Sendable {
        case starting
        case recording
        case finalizing
        case finished
    }
    /// Termination intent, monotonically merged. None < stop < suspend < dispose.
    enum Termination: Equatable, Sendable {
        case none
        case stop
        case suspend
        case dispose
    }
    /// Effect for the engine to execute when native start fires.
    enum StartedEffect: Equatable, Sendable {
        /// No parked intent and UI live: mark actual recording.
        case markRecording
        /// Intent parked or UI not live: finalizing + stop exactly once.
        case stopOnce
        /// Duplicate/late start (not starting): no effect.
        case ignore
    }
    /// Deferred effect for the engine to execute AFTER the matching native
    /// finish (never before). Emitted exactly once per take.
    enum FinishedEffect: Equatable, Sendable {
        case none
        case stopSession
        case fullTeardown
    }

    private(set) var mode: Mode
    private(set) var termination: Termination
    private var finishConsumed: Bool

    init(mode: Mode = .starting) {
        self.mode = mode
        self.termination = .none
        self.finishConsumed = false
    }

    /// Resets the authoritative intent for a NEW owned take. Called by the
    /// engine in startRecording AFTER readiness/disposed/output guards and
    /// owned dir/lease success, BEFORE storing pendingTake/.starting/
    /// nativeStart. Clears mode to starting, termination to none, and the
    /// finish-consumed latch (a prior take left finished/consumed, which
    /// would ignore the next native start/finish and drop deferred
    /// suspend/dispose). Never called in didStart/finish/background/stop/
    /// cancel (that would lose parked intents).
    mutating func resetForNewTake() {
        mode = .starting
        termination = .none
        finishConsumed = false
    }

    private static func rank(_ intent: Termination) -> Int {
        switch intent {
        case .none: return 0
        case .stop: return 1
        case .suspend: return 2
        case .dispose: return 3
        }
    }

    /// Merges a termination request monotonically. Dispose is terminal;
    /// suspend beats plain stop; nothing ever downgrades.
    mutating func request(_ intent: Termination) {
        guard Self.rank(intent) > Self.rank(termination) else { return }
        termination = intent
    }

    /// User stop arriving while recording: moves to finalizing now (the
    /// engine then issues the single native stop). Returns false unless the
    /// take is currently recording (no duplicate stop from finalizing).
    mutating func stopFromRecording() -> Bool {
        guard mode == .recording else { return false }
        mode = .finalizing
        request(.stop)
        return true
    }

    /// Native start for the matching take. Produces `.stopOnce` when any
    /// termination was already parked (or the take cannot show UI), else
    /// marks recording. Duplicate starts (mode != starting) are ignored.
    /// `uiLive` folds hidden/suspended/disposed/superseded-take: a start
    /// that cannot show UI must still stop exactly once (never reopen UI).
    mutating func nativeStarted(uiLive: Bool) -> StartedEffect {
        guard mode == .starting else { return .ignore }
        if termination != .none || !uiLive {
            if !uiLive && termination == .none {
                // Hidden start still parks a suspend so the session stops
                // after finish (fail closed while hidden).
                termination = .suspend
            }
            mode = .finalizing
            return .stopOnce
        }
        mode = .recording
        return .markRecording
    }

    /// Matching native finish (right URL already verified by the engine;
    /// also covers finish-without-start since start failure surfaces as a
    /// finish error). Consumes transient termination exactly once and reports
    /// the deferred post-finish effect. Never tears down before finish.
    mutating func nativeFinished() -> FinishedEffect {
        guard !finishConsumed else { return .none }
        finishConsumed = true
        mode = .finished
        switch termination {
        case .none, .stop:
            return .none
        case .suspend:
            return .stopSession
        case .dispose:
            return .fullTeardown
        }
    }
}

/// Actual discovered capture facts. Dimensions and finite FPS ranges are
/// capabilities only; frame durations are left at the runtime safe default
/// in this slice. No nominal-FPS clock, no 120/240 promise, no model caps.
struct JumpVideoCaptureCapabilities: Equatable {
    struct FrameRateRange: Equatable {
        let minFPS: Double
        let maxFPS: Double
    }
    let width: Int
    let height: Int
    let frameRateRanges: [FrameRateRange]
    let codec: String
    let isFrontCamera: Bool
}

// MARK: - Serial capture engine (NSObject + file-output delegate)

/// Owns AVCaptureSession / video input / movie file output on ONE private
/// serial queue. Never MainActor. The engine retains itself while a writer is
/// active so facade teardown/cancel cannot deallocate the recording pipeline
/// before didFinishRecordingTo proves all pending data was written.
final class JumpVideoCaptureEngine: NSObject {
    struct Event {
        let phase: JumpVideoCapturePhase
        let errorKey: String?
        let lease: JumpVideoCaptureLease?
        let capabilities: JumpVideoCaptureCapabilities?
        let epoch: UInt64
        let generation: UInt64
    }

    private let sessionQueue = DispatchQueue(label: "org.openjump.apple.capture.session")
    private let session = AVCaptureSession()
    private var videoInput: AVCaptureDeviceInput?
    private var movieOutput: AVCaptureMovieFileOutput?
    private var activeCameraIsFront = false

    // All mutable state below is confined to sessionQueue except eventGate,
    // which is a locked shared ticket also touched synchronously from MainActor.
    // The facade synchronously invalidates it on cancel/dispose/viewDisappeared
    // before old queued callbacks deliver; background invalidates on the queue.
    let eventGate = JumpVideoCaptureEventGate()
    private var epoch: UInt64 = 0
    private var phase: JumpVideoCapturePhase = .idle
    private var errorKey: String?
    private var pendingURL: URL?
    private var pendingLease: JumpVideoCaptureLease?
    private var pendingTake: JumpVideoCaptureTakeContext?
    private var recordedLease: JumpVideoCaptureLease?
    /// Single authoritative take-intent context (replaces the former
    /// stop/suspend/dispose parked + requested flag cluster). Reset at every
    /// startRecording; merged by ALL lifecycle/interruption paths; consumed
    /// exactly once by didStart/finish. sessionSuspended below is live
    /// session state (not intent duplication) and stays separate.
    private var takeIntent = JumpVideoCaptureTakeIntent()
    private var sessionSuspended = false
    private var permissionRequestActive = false
    private var notificationTokens: [NSObjectProtocol] = []
    private var retainedSelfDuringRecording: JumpVideoCaptureEngine?
    private var capabilities: JumpVideoCaptureCapabilities?

    /// UI bridge. Invoked on MainActor via async hop with epoch guards.
    /// Never retains the facade: the facade owns the engine, not vice versa.
    var onEvent: ((Event) -> Void)?

    /// Readonly session seam for a FUTURE main-thread preview host (C2).
    /// The host may only pass this to AVCaptureVideoPreviewLayer on the main
    /// thread. It must NOT begin/commit configuration, add/remove
    /// inputs/outputs, or lock devices through this handle. No preview UI is
    /// created or mutated on the serial queue in this slice.
    var previewSessionForFutureHost: AVCaptureSession { session }

    override init() {
        super.init()
        installObservers()
    }

    // MARK: Entry points (all hop to the serial queue)

    /// Requests .video authorization with the closure API, then prepares only
    /// if still active. `isActive` distinguishes a permission alert (still
    /// active, may proceed) from true background/invisibility (must not).
    func requestPermissionAndPrepare(isActive: Bool) {
        // Originating ticket frozen BEFORE the queue hop: old queued work
        // keeps its OLD generation even if Main invalidates synchronously
        // before this block runs (never borrows the new generation).
        let ticket = eventGate.issueTicket()
        sessionQueue.async { [weak self, ticket] in
            guard let self else { return }
            // Validate the ORIGINATING ticket + LIVE foreground/visibility
            // before touching permissions/config (a stale isActive bool
            // alone is not authoritative).
            guard self.eventGate.isTicketLive(ticket) else { return }
            let liveActive = self.eventGate.isForegroundActive()
            guard self.phase == .idle || self.phase == .denied || self.phase == .failed else { return }
            // Inactive (invisible/backgrounded/cancelled/disposed) must never
            // configure or request permission. Stay out entirely (never enter
            // .preparing/config/start). No permission request when inactive.
            guard isActive && liveActive else { return }
            guard !self.eventGate.isDisposed() else { return }
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            let permission = JumpVideoCapturePolicy.permission(for: status)
            switch permission {
            case .authorized:
                // Gate BEFORE publishing .preparing/config: inactive or wrong
                // phase never enters preparing.
                guard JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
                    requestWasActive: true, isActiveNow: liveActive, phase: self.phase) else { return }
                self.permissionRequestActive = liveActive
                self.setPhase(.preparing, errorKey: nil, ticket: ticket)
                self.prepareOnQueue(wasActiveAtRequest: true, isActiveNow: liveActive, ticket: ticket)
            case .denied, .restricted:
                self.permissionRequestActive = false
                self.setPhase(.denied, errorKey: JumpVideoCaptureStrings.permissionDenied)
            case .notDetermined:
                // Gate before publishing .requestingPermission and before any
                // requestAccess call (no request when inactive).
                guard JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
                    requestWasActive: true, isActiveNow: liveActive, phase: self.phase) else { return }
                self.permissionRequestActive = liveActive
                self.setPhase(.requestingPermission, errorKey: nil, ticket: ticket)
                let requestWasActive = liveActive
                let requestEpoch = self.epoch
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    guard let self else { return }
                    self.sessionQueue.async { [ticket] in
                        // Enqueue-time ticket + native permission-request epoch
                        // must BOTH still hold: a grant after hidden/cancel/
                        // background can neither start nor leave a provisional
                        // state forever (recovery is explicit + eligible idle).
                        guard self.eventGate.isTicketLive(ticket) else { return }
                        guard requestEpoch == self.epoch else { return }
                        guard !self.eventGate.isDisposed() else { return }
                        guard granted else {
                            self.permissionRequestActive = false
                            self.setPhase(.denied, errorKey: JumpVideoCaptureStrings.permissionDenied)
                            return
                        }
                        // Grant uses the LIVE visibility flag (background
                        // cleared it); it cannot clear suspend implicitly.
                        let isActiveNow = self.permissionRequestActive && self.eventGate.isForegroundActive()
                        guard JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
                            requestWasActive: requestWasActive,
                            isActiveNow: isActiveNow,
                            phase: self.phase) else {
                            // Stale grant while still requesting: fall back to
                            // eligible idle (never stuck requesting).
                            if self.phase == .requestingPermission {
                                self.setPhase(.idle, errorKey: nil)
                            }
                            return
                        }
                        self.setPhase(.preparing, errorKey: nil, ticket: ticket)
                        self.prepareOnQueue(wasActiveAtRequest: requestWasActive,
                                            isActiveNow: isActiveNow,
                                            ticket: ticket)
                    }
                }
            }
        }
    }

    func prepare(isActive: Bool = true) {
        let ticket = eventGate.issueTicket()
        sessionQueue.async { [weak self, ticket] in
            guard let self else { return }
            guard self.eventGate.isTicketLive(ticket) else { return }
            let liveActive = self.eventGate.isForegroundActive()
            guard !self.eventGate.isDisposed() else { return }
            // Inactive prepare never configures (stays out entirely).
            guard isActive && liveActive else { return }
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
                self.setPhase(.denied, errorKey: JumpVideoCaptureStrings.permissionDenied)
                return
            }
            // Usable prep: idle/failed/retry when active; never during
            // starting/recording/finalizing/recorded/ready. Gate before publish.
            guard JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
                requestWasActive: true, isActiveNow: liveActive, phase: self.phase) else { return }
            self.permissionRequestActive = liveActive
            self.prepareOnQueue(wasActiveAtRequest: true, isActiveNow: liveActive, ticket: ticket)
        }
    }

    /// Explicit visibility entry: the view appeared. Never auto-records; if a
    /// suspended session was left by backgrounding, re-prepare the preview
    /// pipeline only (configuration + startRunning), never start recording.
    /// The originating ticket is frozen BEFORE the queue hop; the restart is
    /// validated against the LIVE ticket + both flags before AND after the
    /// blocking start (hidden/backgrounded/disposed appearance never restarts).
    func viewAppeared() {
        let ticket = eventGate.issueTicket()
        sessionQueue.async { [weak self, ticket] in
            guard let self else { return }
            // Pre-restart gate: appearance while backgrounded, view-hidden,
            // stale-ticket, or disposed never restarts the preview.
            guard self.eventGate.canBeginNativeStart(ticket: ticket) else { return }
            self.permissionRequestActive = true
            // Explicit re-prepare of a READY preview only. A failed pipeline
            // never bare-starts an empty graph here (explicit prepare owns
            // reconfigure); requesting/preparing are never promoted.
            if self.sessionSuspended, self.phase == .ready {
                self.sessionSuspended = false
                self.startSessionOnQueue(ticket: ticket)
                // Post-start validation: an invalidate raced the blocking
                // start (never atomically interrupted, only rolled back).
                guard self.eventGate.canBeginNativeStart(ticket: ticket) else {
                    if self.session.isRunning { self.session.stopRunning() }
                    self.tearDownSessionOnQueue()
                    self.sessionSuspended = true
                    if self.phase == .ready {
                        self.setPhase(.idle, errorKey: nil)
                    }
                    return
                }
            }
        }
    }

    /// Explicit visibility entry: the view disappeared (not background).
    /// Stops the session preview; a recording in flight auto-stops first and
    /// still finalizes through the delegate. A starting take parks stop/
    /// suspend intent until real native start (never lost). The facade already
    /// invalidated the shared gate synchronously, so old queued events are
    /// rejected even before this async block runs.
    func viewDisappeared() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.permissionRequestActive = false
            self.epoch &+= 1
            if self.phase == .starting {
                // Park through the authoritative intent (never lost, never
                // cleared by a later cancel); session marked inactive now.
                self.takeIntent.request(.suspend)
                self.sessionSuspended = true
            } else if self.phase == .recording {
                self.takeIntent.request(.suspend)
                self.sessionSuspended = true
                self.stopRecordingOnQueue(reason: .interrupted)
            } else if self.phase == .finalizing {
                // Park suspend for finalizing; executes after delegate.
                // Never cleared by a later cancel (see cancel path).
                self.takeIntent.request(.suspend)
                self.sessionSuspended = true
            }
            // Requesting/preparing/ready/failed/recorded converge to eligible
            // idle via the ACTUAL recovery decision (explicit retry accepted
            // afterwards, never auto-record). Starting/recording/finalizing
            // stay on the parked-intent path above.
            if let reset = JumpVideoCapturePolicy.idleResetPhaseForViewExit(phase: self.phase) {
                self.tearDownSessionOnQueue()
                self.setPhase(reset, errorKey: nil)
            }
        }
    }

    func startRecording() {
        let ticket = eventGate.issueTicket()
        sessionQueue.async { [weak self, ticket] in
            guard let self else { return }
            guard JumpVideoCapturePolicy.canStartRecording(phase: self.phase) else { return }
            // Authoritative readiness: READY must mean a live graph + running
            // session + not suspended + live originating ticket + foreground.
            let readiness = JumpVideoCaptureReadiness(
                graphPresent: self.movieOutput != nil && self.videoInput != nil && self.capabilities != nil,
                sessionRunning: self.session.isRunning,
                suspended: self.sessionSuspended,
                ticketLive: self.eventGate.isTicketLive(ticket),
                activeNow: self.eventGate.isForegroundActive())
            guard readiness.isReady else { return }
            guard !self.eventGate.isDisposed() else { return }
            guard let output = self.movieOutput else {
                self.setPhase(.failed, errorKey: JumpVideoCaptureStrings.configurationFailed)
                return
            }
            do {
                let (directory, fileURL) = try JumpVideoCapturePaths.makeOwnedCaptureDirectory()
                let lease = JumpVideoCaptureLease(ownedDirectory: directory, fileURL: fileURL)
                // Fresh authoritative intent for THIS owned take (actual reset,
                // never a comment-only no-op): the prior take left
                // finished/finishConsumed, which would ignore the next native
                // start (.ignore, stuck .starting) and drop deferred
                // suspend/dispose (.none). Reset AFTER readiness/disposed/
                // output guards and owned dir/lease success, BEFORE storing
                // pendingTake/.starting/nativeStart. Never in didStart/
                // finish/background/stop/cancel (would lose parked intents).
                self.takeIntent.resetForNewTake()
                self.pendingURL = fileURL
                self.pendingLease = lease
                // Immutable take epoch + URL stored now; delegate acceptance
                // later uses the ACTUAL policy (never current==current).
                self.pendingTake = JumpVideoCaptureTakeContext(epoch: self.epoch, fileURL: fileURL, ticketGeneration: ticket.generation)
                self.recordedLease = nil
                // Strong retention: the pipeline (and this engine) survives
                // facade teardown/cancel until the delegate finalizes.
                self.retainedSelfDuringRecording = self
                output.maxRecordedDuration = JumpVideoCaptureLimits.maximumDuration
                output.maxRecordedFileSize = JumpVideoCaptureLimits.maximumFileSize
                // Mark starting (NOT recording) until real native didStart.
                self.setPhase(.starting, errorKey: nil, ticket: ticket)
                output.startRecording(to: fileURL, recordingDelegate: self)
            } catch {
                self.pendingURL = nil
                self.pendingLease = nil
                self.pendingTake = nil
                self.setPhase(.failed, errorKey: JumpVideoCaptureStrings.recordingFailed)
            }
        }
    }

    func stopRecording() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            // Idempotent: live take honours stop; starting parks until real
            // native start (never lost as a no-op); finalizing/others ignored.
            // stopRecording() returning is NOT finalization; finalizing
            // persists until the delegate fires (or never, retaining ownership).
            guard JumpVideoCapturePolicy.canStopRecording(phase: self.phase) else { return }
            if self.phase == .starting {
                // Park stop intent in the authoritative context; didStart
                // executes exactly once. Uses the pure helper so tests and
                // engine agree (never lost no-op).
                if JumpVideoCapturePolicy.shouldParkStopDuringStarting(phase: self.phase) {
                    self.takeIntent.request(.stop)
                }
                return
            }
            self.stopRecordingOnQueue(reason: .userStop)
        }
    }

    /// User cancel: no second stop, no teardown before finalization. Suspend
    /// intent executes safely after the delegate finalizes. The raw temp dir
    /// is removed only after writer-finalized + all borrowers released.
    /// Starting takes park discard/stop/suspend until real native start.
    func cancel() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.epoch &+= 1
            self.permissionRequestActive = false
            // Facade already invalidated the gate synchronously; old queued
            // events are rejected even before this block runs.
            switch self.phase {
            case .starting:
                // Park discard/stop/suspend for the starting take through the
                // authoritative intent; survives late native start (never dropped).
                self.pendingLease?.markDiscard()
                self.takeIntent.request(.suspend)
            case .recording:
                self.pendingLease?.markDiscard()
                self.stopRecordingOnQueue(reason: .cancelled)
            case .finalizing:
                self.pendingLease?.markDiscard()
                // Parked suspend/dispose must NOT be cleared by another
                // cancel (pure helper pins the invariant for tests/engine).
                if JumpVideoCapturePolicy.cancelClearsSuspendDuringFinalizing() {
                    self.takeIntent.request(.none)
                }
            case .recorded:
                self.recordedLease?.markDiscard()
                self.recordedLease = nil
                self.setPhase(.ready, errorKey: nil)
            case .requestingPermission, .preparing:
                self.setPhase(.idle, errorKey: nil)
            case .idle, .denied, .unavailable, .failed, .ready:
                break
            }
        }
    }

    /// Discard a finalized recorded candidate. Leaves any previous video /
    /// manifest / events / notes / metrics untouched (this engine owns no
    /// measurement state at all).
    func discardRecorded() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard self.phase == .recorded else { return }
            self.epoch &+= 1
            self.recordedLease?.markDiscard()
            self.recordedLease = nil
            self.pendingURL = nil
            self.pendingLease = nil
            self.pendingTake = nil
            self.setPhase(.ready, errorKey: nil)
        }
    }

    /// Repeat / new recording after a finalized candidate: allowed only from
    /// ready/recorded/failed, never from finalizing (writer not finalized).
    /// From failed, readiness (live graph + running + not suspended + live
    /// ticket + foreground) is required; otherwise the phase remains failed
    /// for an explicit prepare (never a false ready on an empty graph).
    func prepareForNewRecording() {
        let ticket = eventGate.issueTicket()
        sessionQueue.async { [weak self, ticket] in
            guard let self else { return }
            guard JumpVideoCapturePolicy.canBeginNewRecording(phase: self.phase) else { return }
            guard self.eventGate.isTicketLive(ticket) else { return }
            self.epoch &+= 1
            if self.phase == .recorded {
                self.recordedLease?.markDiscard()
                self.recordedLease = nil
            }
            self.pendingURL = nil
            self.pendingLease = nil
            self.pendingTake = nil
            if self.phase == .failed {
                let readiness = JumpVideoCaptureReadiness(
                    graphPresent: self.movieOutput != nil && self.videoInput != nil && self.capabilities != nil,
                    sessionRunning: self.session.isRunning,
                    suspended: self.sessionSuspended,
                    ticketLive: self.eventGate.isTicketLive(ticket),
                    activeNow: self.eventGate.isForegroundActive())
                guard readiness.isReady else { return }
                self.setPhase(.ready, errorKey: nil)
                return
            }
            if self.phase != .ready {
                self.setPhase(.ready, errorKey: nil)
            }
        }
    }

    /// Orientation/update seam for the future main-thread preview host.
    /// Applies only when NOT recording; mid-recording transform changes are
    /// rejected to prevent incompatible output.
    func updateVideoOrientation(_ orientation: AVCaptureVideoOrientation) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard self.phase != .starting && self.phase != .recording && self.phase != .finalizing else { return }
            guard let output = self.movieOutput,
                  let connection = output.connection(with: .video),
                  connection.isVideoOrientationSupported else { return }
            connection.videoOrientation = orientation
        }
    }

    func dispose() {
        // Strong retention for the queued cleanup: the facade release cannot
        // drop it. The engine stays alive until this block runs even if the
        // facade released its reference immediately after calling dispose().
        // Recording/dispose intents are distinct: suspend stops running after
        // finish, dispose does FULL teardown (graph+observers+idle) after finish.
        let retainedEngine = self
        sessionQueue.async { [retainedEngine] in
            let strongSelf = retainedEngine
            strongSelf.epoch &+= 1
            strongSelf.permissionRequestActive = false
            // Facade already marked the shared gate disposed synchronously;
            // all old queued events are rejected at delivery.
            if strongSelf.phase == .starting {
                // Defer FULL teardown until real finish; retain ownership.
                // Never manufacture finalization/delete on timeout.
                strongSelf.pendingLease?.markDiscard()
                strongSelf.takeIntent.request(.dispose)
                return
            }
            if strongSelf.phase == .recording {
                // Never tear down the pipeline before the file delegate
                // finishes. Mark discard; suspend + FULL teardown run after
                // finalization. If the delegate stalls, ownership is retained;
                // finalization is never manufactured and nothing is deleted
                // on timeout.
                strongSelf.pendingLease?.markDiscard()
                strongSelf.takeIntent.request(.dispose)
                strongSelf.stopRecordingOnQueue(reason: .disposed)
                return
            }
            if strongSelf.phase == .finalizing {
                strongSelf.pendingLease?.markDiscard()
                strongSelf.takeIntent.request(.dispose)
                return
            }
            strongSelf.recordedLease?.dispose()
            strongSelf.recordedLease = nil
            strongSelf.pendingLease?.dispose()
            strongSelf.pendingLease = nil
            strongSelf.pendingURL = nil
            strongSelf.pendingTake = nil
            strongSelf.tearDownSessionOnQueue()
            strongSelf.setPhase(.idle, errorKey: nil)
            strongSelf.removeObservers()
        }
    }

    // MARK: - Serial-queue internals

    private enum StopReason {
        case userStop
        case interrupted
        case background
        case cancelled
        case disposed
        case runtimeError
    }

    private func prepareOnQueue(wasActiveAtRequest: Bool, isActiveNow: Bool, ticket: JumpVideoCaptureRequestTicket) {
        // No unconditional || phase==.preparing bypass: inactive late grants
        // never configure/start. Gate BEFORE any .preparing publish/config.
        guard JumpVideoCapturePolicy.shouldStartCaptureAfterGrant(
            requestWasActive: wasActiveAtRequest,
            isActiveNow: isActiveNow,
            phase: phase
        ) else {
            // Inactive late grant: stay out of capture entirely.
            return
        }
        guard !eventGate.isDisposed() else { return }
        // Re-validate the ORIGINATING ticket + live visibility on the queue
        // immediately before configuring (never trust the stale bool alone).
        guard eventGate.isTicketLive(ticket), eventGate.isForegroundActive() else { return }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            setPhase(.denied, errorKey: JumpVideoCaptureStrings.permissionDenied)
            return
        }
        // Publish .preparing only AFTER the gate passed (never before).
        // Usable prep from idle/requesting/denied/failed when active; never
        // during starting/recording/finalizing/recorded/ready/unavailable.
        if phase == .idle || phase == .requestingPermission || phase == .denied || phase == .failed {
            setPhase(.preparing, errorKey: nil, ticket: ticket)
        } else if phase != .preparing {
            return
        }
        guard configureSessionOnQueue() else {
            setPhase(.unavailable, errorKey: JumpVideoCaptureStrings.unavailable, ticket: ticket)
            return
        }
        // Pre-start gate: the blocking configure above may have spanned a
        // synchronous invalidate (view exit/cancel/background). A stale
        // ticket, lost foreground/visibility, or disposal must prevent the
        // NEW native startRunning (never start-while-stale and rely on
        // rollback alone). The own non-recording graph is torn down; the
        // original operation ticket is preserved (no nil/current-stamp
        // preparing/ready). Snapshots only; no lock held across the
        // blocking calls above. The post-start rollback below is KEPT for an
        // invalidate racing startRunning itself.
        guard eventGate.canBeginNativeStart(ticket: ticket) else {
            tearDownSessionOnQueue()
            if phase == .preparing || phase == .requestingPermission || phase == .ready {
                setPhase(.idle, errorKey: nil)
            }
            return
        }
        startSessionOnQueue(ticket: ticket)
        // The blocking configure/start above may have spanned a synchronous
        // invalidate (view exit/cancel/background): re-validate the ticket +
        // live visibility BEFORE the ready publication stands. Stale work
        // rolls back (stop + teardown + idle) instead of laundering ready
        // with the new generation. No lock is held across the blocking calls
        // above (snapshots only); a native blocking call is never
        // atomically interrupted, only rolled back after it returns.
        guard eventGate.isTicketLive(ticket), eventGate.isForegroundActive(), !eventGate.isDisposed() else {
            if session.isRunning { session.stopRunning() }
            tearDownSessionOnQueue()
            if phase == .preparing || phase == .requestingPermission || phase == .ready {
                setPhase(.idle, errorKey: nil)
            }
            return
        }
    }

    /// Real configuration: video-only session + video input + movie output.
    /// Discovers real wide-angle back/front cameras (prefer back; fall back to
    /// front), fails gracefully when none. No device-name checks, no model
    /// caps. Safe preset BEFORE format/HDR checks (checked profile validated
    /// below), SDR preserved (automatic HDR off under device lock with support
    /// guard), guarded H264 preference if advertised else compatible default.
    /// Capabilities report the post-preset active format as facts only.
    private func configureSessionOnQueue() -> Bool {
        // Never reconfigure while a writer is active (starting/recording/
        // finalizing): reprepare cannot tear down under a take.
        if phase == .starting || phase == .recording || phase == .finalizing {
            return false
        }
        session.beginConfiguration()
        // Discover real cameras. Back preferred; front fallback.
        guard let camera = JumpVideoCaptureEngine.discoverVideoCamera(isFront: nil) else {
            session.commitConfiguration()
            return false
        }
        activeCameraIsFront = camera.position == .front
        // Remove old graph BEFORE canAdd checks: evaluation must see the
        // post-removal capacity, not the occupied graph. Failure below leaves
        // a clean (empty) graph fail-closed, never the old graph running as
        // unavailable. Fields are cleared so they never point at removed
        // resources; every path below balances begin/commit.
        for oldInput in session.inputs {
            session.removeInput(oldInput)
        }
        for oldOutput in session.outputs {
            session.removeOutput(oldOutput)
        }
        videoInput = nil
        movieOutput = nil
        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            session.commitConfiguration()
            return false
        }
        guard session.canAddInput(input) else {
            session.commitConfiguration()
            return false
        }
        session.addInput(input)
        videoInput = input

        // Safe preset BEFORE format/HDR/dimension checks so the committed
        // profile is the one validated below (never invalidated later).
        // Runtime-checked via supportsSessionPreset (pre-16); never forced.
        if camera.supportsSessionPreset(.high) {
            session.sessionPreset = .high
        }
        // SDR preservation + runtime format sanity under device lock.
        do {
            try camera.lockForConfiguration()
            if camera.activeFormat.isVideoHDRSupported {
                camera.automaticallyAdjustsVideoHDREnabled = false
                camera.isVideoHDREnabled = false
            }
            camera.unlockForConfiguration()
        } catch {
            session.removeInput(input)
            videoInput = nil
            session.commitConfiguration()
            return false
        }
        // Runtime-checked dimensions (format description extents are the
        // source of truth; leave active frame durations at device default).
        let dimensions = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
        guard dimensions.width > 0, dimensions.height > 0,
              CGFloat(dimensions.width) <= JumpVideoCaptureLimits.maximumDimension,
              CGFloat(dimensions.height) <= JumpVideoCaptureLimits.maximumDimension else {
            session.removeInput(input)
            videoInput = nil
            session.commitConfiguration()
            return false
        }
        // Active frame durations intentionally left at the runtime safe
        // default in this slice; no fabricated FPS clock.
        let output = AVCaptureMovieFileOutput()
        output.maxRecordedDuration = JumpVideoCaptureLimits.maximumDuration
        output.maxRecordedFileSize = JumpVideoCaptureLimits.maximumFileSize
        guard session.canAddOutput(output) else {
            session.removeInput(input)
            videoInput = nil
            session.commitConfiguration()
            return false
        }
        session.addOutput(output)
        movieOutput = output
        // Guarded codec preference: H264 only if advertised, else default.
        if output.availableVideoCodecTypes.contains(.h264),
           let connection = output.connection(with: .video) {
            output.setOutputSettings([AVVideoCodecKey: AVVideoCodecType.h264], for: connection)
        }
        // Orientation/mirroring guards (pre-17 videoOrientation path).
        if let connection = output.connection(with: .video) {
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
            if connection.isVideoMirroringSupported {
                // Front cameras mirror for preview consistency; rear never.
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = activeCameraIsFront
            }
            _ = output.recordsVideoOrientationAndMirroringChangesAsMetadataTrack(for: connection)
        }
        // Report actual capabilities (dimensions + finite FPS ranges only).
        var ranges: [JumpVideoCaptureCapabilities.FrameRateRange] = []
        for range in camera.activeFormat.videoSupportedFrameRateRanges {
            let minSeconds = CMTimeGetSeconds(range.minFrameDuration)
            let maxSeconds = CMTimeGetSeconds(range.maxFrameDuration)
            guard minSeconds.isFinite, maxSeconds.isFinite, minSeconds > 0, maxSeconds > 0 else { continue }
            ranges.append(JumpVideoCaptureCapabilities.FrameRateRange(minFPS: 1.0 / maxSeconds,
                                                                      maxFPS: 1.0 / minSeconds))
        }
        capabilities = JumpVideoCaptureCapabilities(
            width: Int(dimensions.width),
            height: Int(dimensions.height),
            frameRateRanges: ranges,
            codec: output.availableVideoCodecTypes.contains(.h264) ? AVVideoCodecType.h264.rawValue : "default",
            isFrontCamera: activeCameraIsFront
        )
        // Active frame durations intentionally left at the runtime safe
        // default in this slice; no fabricated FPS clock.
        session.commitConfiguration()
        return true
    }

    /// Real device discovery: wide-angle back preferred, front fallback.
    /// Returns nil when no video camera exists (fail gracefully upstream).
    static func discoverVideoCamera(isFront: Bool?) -> AVCaptureDevice? {
        if let isFront {
            let position: AVCaptureDevice.Position = isFront ? .front : .back
            if let direct = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) {
                return direct
            }
            let discovery = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInWideAngleCamera, .builtInDualCamera, .builtInDualWideCamera,
                              .builtInTripleCamera, .builtInTrueDepthCamera, .builtInUltraWideCamera,
                              .builtInTelephotoCamera],
                mediaType: .video,
                position: position
            )
            return discovery.devices.first
        }
        if let back = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            return back
        }
        let backDiscovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInDualCamera, .builtInDualWideCamera,
                          .builtInTripleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera],
            mediaType: .video,
            position: .back
        )
        if let back = backDiscovery.devices.first {
            return back
        }
        if let front = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) {
            return front
        }
        let frontDiscovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInTrueDepthCamera],
            mediaType: .video,
            position: .front
        )
        return frontDiscovery.devices.first
    }

    private func startSessionOnQueue(ticket: JumpVideoCaptureRequestTicket? = nil) {
        guard !session.isRunning else {
            if phase == .preparing { setPhase(.ready, errorKey: nil, ticket: ticket) }
            return
        }
        session.startRunning()
        if session.isRunning {
            sessionSuspended = false
            if phase == .preparing { setPhase(.ready, errorKey: nil, ticket: ticket) }
        } else {
            setPhase(.unavailable, errorKey: JumpVideoCaptureStrings.unavailable, ticket: ticket)
        }
    }

    private func tearDownSessionOnQueue() {
        if session.isRunning { session.stopRunning() }
        session.beginConfiguration()
        for output in session.outputs { session.removeOutput(output) }
        for input in session.inputs { session.removeInput(input) }
        session.commitConfiguration()
        videoInput = nil
        movieOutput = nil
    }

    private func stopRecordingOnQueue(reason: StopReason) {
        // Single funnel: the authoritative intent allows exactly one
        // recording->finalizing transition per take (no duplicate stops from
        // observers bypassing the context). Plain stop merges monotonically
        // and never downgrades a parked suspend/dispose.
        guard phase == .recording, takeIntent.stopFromRecording() else { return }
        setPhase(.finalizing, errorKey: nil)
        // stopRecording() is async: finalizing persists until the delegate
        // fires. Never stopRunning/tear down the pipeline here; suspend/
        // dispose intents execute only after finalization.
        movieOutput?.stopRecording()
        switch reason {
        case .background:
            errorKey = JumpVideoCaptureStrings.backgroundStopped
            publishLocked()
        case .interrupted, .runtimeError:
            errorKey = JumpVideoCaptureStrings.interrupted
            publishLocked()
        case .userStop, .cancelled, .disposed:
            break
        }
    }

    private func setPhase(_ next: JumpVideoCapturePhase, errorKey: String?, ticket: JumpVideoCaptureRequestTicket? = nil) {
        phase = next
        self.errorKey = errorKey
        publishLocked(ticket: ticket)
    }

    private func publishLocked(ticket: JumpVideoCaptureRequestTicket? = nil) {
        // Called on sessionQueue. Operation progress stamps its ORIGINATING
        // ticket (never the current generation at publish time), so old work
        // running after a synchronous invalidate cannot borrow the new
        // generation. Lifecycle/cleanup convergence publishes the current
        // generation (nil ticket). MainActor invalidates queued events at
        // delivery even before the matching async block runs.
        let capturedPhase = phase
        let capturedError = errorKey
        let capturedLease: JumpVideoCaptureLease? = (capturedPhase == .recorded) ? recordedLease : nil
        let capturedCapabilities = capabilities
        let capturedEpoch = epoch
        let capturedGeneration = ticket?.generation ?? eventGate.currentGeneration()
        let bridge = onEvent
        DispatchQueue.main.async {
            bridge?(Event(phase: capturedPhase,
                          errorKey: capturedError,
                          lease: capturedLease,
                          capabilities: capturedCapabilities,
                          epoch: capturedEpoch,
                          generation: capturedGeneration))
        }
    }

    // MARK: Observers (serial-queue callbacks)

    private func installObservers() {
        // Audited typed notification constants (AVCaptureSession.*Notification,
        // iOS 4.0 floor): never uncertain aliases. Runtime/interruption/
        // background fail closed for preview-ready states as well as recording.
        let center = NotificationCenter.default
        let runtime = center.addObserver(forName: AVCaptureSession.runtimeErrorNotification,
                                         object: session,
                                         queue: nil) { [weak self] _ in
            self?.sessionQueue.async { [weak self] in
                guard let self else { return }
                if self.phase == .starting {
                    // Park suspend/stop for the starting take through the
                    // authoritative intent; the take fails closed via didFinish
                    // error (never manufactured here).
                    self.takeIntent.request(.suspend)
                    self.errorKey = JumpVideoCaptureStrings.interrupted
                    self.publishLocked()
                    return
                }
                if self.phase == .recording {
                    // Park suspend BEFORE stop so the deferred finish stops
                    // the session (stop alone never sets the flag).
                    self.takeIntent.request(.suspend)
                    self.sessionSuspended = true
                    self.eventGate.invalidate()
                    self.stopRecordingOnQueue(reason: .runtimeError)
                    return
                }
                if self.phase == .finalizing {
                    // Already finalizing: keep suspend parked, no second stop.
                    self.takeIntent.request(.suspend)
                    self.sessionSuspended = true
                    return
                }
                // Fail closed for preview states (never stay ready on
                // a broken session): invalidate pending permission/take UI,
                // stop a running session (never rely on OS auto-shutdown),
                // explicit prepare recovers.
                if self.phase == .ready || self.phase == .preparing || self.phase == .requestingPermission {
                    self.permissionRequestActive = false
                    self.epoch &+= 1
                    self.eventGate.invalidate()
                    if self.session.isRunning { self.session.stopRunning() }
                    self.setPhase(.failed, errorKey: JumpVideoCaptureStrings.recordingFailed)
                }
            }
        }
        let interrupted = center.addObserver(forName: AVCaptureSession.wasInterruptedNotification,
                                             object: session,
                                             queue: nil) { [weak self] _ in
            self?.sessionQueue.async { [weak self] in
                guard let self else { return }
                if self.phase == .starting {
                    self.takeIntent.request(.suspend)
                    self.errorKey = JumpVideoCaptureStrings.interrupted
                    self.publishLocked()
                    return
                }
                if self.phase == .recording {
                    // Park suspend BEFORE stop so the deferred finish stops
                    // the session (stop alone never sets the flag).
                    self.takeIntent.request(.suspend)
                    self.sessionSuspended = true
                    self.eventGate.invalidate()
                    self.stopRecordingOnQueue(reason: .interrupted)
                    return
                }
                if self.phase == .finalizing {
                    // Already finalizing: keep suspend parked, no second stop.
                    self.takeIntent.request(.suspend)
                    self.sessionSuspended = true
                    return
                }
                // Fail closed for preview states (never stay ready on a
                // broken session): invalidate pending permission/take UI,
                // stop a running session (never rely on OS auto-shutdown),
                // explicit prepare recovers.
                if self.phase == .ready || self.phase == .preparing || self.phase == .requestingPermission {
                    self.permissionRequestActive = false
                    self.epoch &+= 1
                    self.eventGate.invalidate()
                    if self.session.isRunning { self.session.stopRunning() }
                    self.setPhase(.failed, errorKey: JumpVideoCaptureStrings.recordingFailed)
                }
            }
        }
        let ended = center.addObserver(forName: AVCaptureSession.interruptionEndedNotification,
                                       object: session,
                                       queue: nil) { [weak self] _ in
            self?.sessionQueue.async { [weak self] in
                guard let self else { return }
                // Interruption ended: NEVER auto-record and never silently
                // restart while suspended. The facade may re-prepare the
                // preview via an explicit lifecycle entrypoint.
                _ = self
            }
        }
        let background = center.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                                            object: nil,
                                            queue: nil) { [weak self] _ in
            // True background (never a permission alert): update the shared
            // gate/app-foreground SYNCHRONOUSLY at delivery, BEFORE the queue
            // hop, so old engine work cannot slip through first. View
            // visibility is NOT faked here (stays as the facade left it).
            guard let self else { return }
            self.eventGate.setForegroundActive(false)
            self.eventGate.invalidate()
            self.sessionQueue.async { [weak self] in
                guard let self else { return }
                // A late grant cannot start capture and cannot clear suspend
                // implicitly (gate already bumped synchronously above).
                self.permissionRequestActive = false
                self.sessionSuspended = true
                if self.phase == .requestingPermission || self.phase == .preparing {
                    self.epoch &+= 1
                }
                if self.phase == .starting {
                    self.takeIntent.request(.suspend)
                    self.sessionSuspended = true
                } else if self.phase == .recording {
                    // Park suspend BEFORE stop so finish stops running after
                    // delegate (stop alone does not set the flag).
                    self.takeIntent.request(.suspend)
                    self.stopRecordingOnQueue(reason: .background)
                } else if self.phase == .finalizing {
                    // Park suspend for finalizing; executes after delegate.
                    // A later cancel must NOT clear it.
                    self.takeIntent.request(.suspend)
                    self.sessionSuspended = true
                } else if self.phase == .requestingPermission || self.phase == .preparing {
                    // Eligible idle via the ACTUAL recovery decision: explicit
                    // retry accepted afterwards, never auto-record.
                    self.tearDownSessionOnQueue()
                    self.setPhase(.idle, errorKey: nil)
                } else if self.phase == .ready {
                    if self.session.isRunning { self.session.stopRunning() }
                }
            }
        }
        let foreground = center.addObserver(forName: UIApplication.willEnterForegroundNotification,
                                            object: nil,
                                            queue: nil) { [weak self] _ in
            // Explicit OS foreground: restore the APP-foreground flag ONLY
            // (synchronously at delivery, before the queue hop). Never
            // view visibility, never start/configure/record by itself; if the
            // view is still invisible the combined gate stays ineligible
            // until an explicit viewAppeared. No UIKit read on the engine
            // queue (literal bool, lock-protected gate).
            self?.eventGate.setForegroundActive(true)
            self?.sessionQueue.async { [weak self] in
                guard let self else { return }
                // Foreground alone does not resume capture; the facade's
                // explicit viewAppeared()/prepare() decides. Clear the
                // suspended flag only when an explicit entry re-prepares.
                _ = self
            }
        }
        notificationTokens = [runtime, interrupted, ended, background, foreground]
    }

    private func removeObservers() {
        let center = NotificationCenter.default
        for token in notificationTokens { center.removeObserver(token) }
        notificationTokens = []
    }

    // MARK: AVCaptureFileOutputRecordingDelegate (serial queue)

    private func handleStartedRecording(to fileURL: URL) {
        // didStart may arrive off-main; re-enter the serial queue to
        // serialize with stop/cancel/dispose/parked intents.
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.didStartRecordingOnQueue(fileURL: fileURL)
        }
    }

    private func didStartRecordingOnQueue(fileURL: URL) {
        // Official didStart (iOS/iPadOS 4.0 floor): marks ACTUAL recording only
        // upon real native start, matched to the current owned take URL AND
        // immutable context. Late start after cancel/dispose/background cannot
        // reopen visible UI nor drop parked cancellation. Never manufactures
        // finalization; no polling/sleeps.
        guard let expectedURL = pendingURL, let take = pendingTake,
              fileURL == expectedURL, fileURL == take.fileURL else {
            // Foreign/wrong-URL start: never finalize/publish/delete current,
            // and never touch the authoritative intent.
            return
        }
        guard phase == .starting else { return }
        // Originating-take UI liveness: immutable take vs current epoch/URL,
        // take ticket vs live gate, live foreground, suspension, disposal.
        // A superseded take's OWN cleanup still runs via the matching
        // didFinish (never skipped here).
        let uiLive = take.acceptsUI(currentEpoch: epoch, currentURL: pendingURL)
            && take.acceptsTicket(currentGeneration: eventGate.currentGeneration())
            && eventGate.isForegroundActive()
            && !sessionSuspended && !eventGate.isDisposed()
        // The authoritative intent executes the parked stop exactly once or
        // marks genuine recording; duplicates are ignored.
        switch takeIntent.nativeStarted(uiLive: uiLive) {
        case .markRecording:
            setPhase(.recording, errorKey: nil, ticket: take.originatingTicket)
        case .stopOnce:
            // Native started but intent already parked or UI not live: move
            // to finalizing and stop exactly ONCE now; UI stays hidden and
            // suspend is preserved so finish stops the session afterwards.
            setPhase(.finalizing, errorKey: nil, ticket: take.originatingTicket)
            movieOutput?.stopRecording()
        case .ignore:
            return
        }
    }

    private func handleFinishedRecording(to outputFileURL: URL, error: Error?) {
        // Delegate work stays on the serial queue (AVFoundation may call off
        // main; we re-enter our queue to serialize with stop/cancel/dispose).
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.finishRecordingOnQueue(outputFileURL: outputFileURL, error: error)
        }
    }

    private func finishRecordingOnQueue(outputFileURL: URL, error: Error?) {
        // Foreign/wrong-URL callback must not finalize/publish/delete the
        // current recording. Only OUR OWN pending context is touched. Uses the
        // ACTUAL take policy (immutable epoch+URL), never a tautology.
        guard let expectedURL = pendingURL, let lease = pendingLease,
              let take = pendingTake, outputFileURL == expectedURL,
              outputFileURL == take.fileURL else {
            // Foreign callback: ignore for UI AND filesystem.
            return
        }
        // UI acceptance: immutable take vs current + originating ticket vs
        // live gate + foreground + suspension. Stale/backgrounded/hidden/
        // disposed UI is rejected, but OWN lease finalization below still runs
        // (never skipped); URL cleanup is separate from UI (pending cleared
        // even when UI rejected).
        let acceptedForUI = take.acceptsUI(currentEpoch: epoch, currentURL: pendingURL)
            && take.acceptsTicket(currentGeneration: eventGate.currentGeneration())
            && eventGate.isForegroundActive()
            && !sessionSuspended && !eventGate.isDisposed()
        let success = JumpVideoCapturePolicy.isRecordingSuccess(error: error)
        // This delegate is the matching finish for the take (whether or not
        // didStart fired: start failure surfaces as didFinish error without
        // start). The authoritative intent moves to finished so a late start
        // after this point cannot reopen UI (phase leaves starting/finalizing).
        // Transient termination below is consumed exactly once at the tail.
        if success {
            // Verify OUR OWN output is a regular nonempty file before exposing
            // any recorded candidate. No gallery/cloud/audio side effects.
            if Self.isRegularNonemptyFile(at: outputFileURL) {
                lease.markWriterFinalized()
                // Never expose a newly recorded candidate while
                // backgrounded/hidden/disposed; owned cleanup still occurs.
                if acceptedForUI {
                    recordedLease = lease
                    pendingLease = nil
                    pendingURL = nil
                    pendingTake = nil
                    retainedSelfDuringRecording = nil
                    if leaseAcquiredButDiscarded(lease) {
                        setPhase(.ready, errorKey: nil)
                        recordedLease = nil
                    } else {
                        setPhase(.recorded, errorKey: nil, ticket: take.originatingTicket)
                    }
                } else {
                    // Stale/hidden success: finalize OWN lease, clear
                    // pending/retention, transition OUT of starting/
                    // finalizing/recording to idle (never stuck, never publish
                    // stale UI). Unpublished file is disposed (own dir only).
                    recordedLease = nil
                    pendingLease = nil
                    pendingURL = nil
                    pendingTake = nil
                    retainedSelfDuringRecording = nil
                    if !leaseAcquiredButDiscarded(lease) {
                        lease.dispose()
                    }
                    if phase == .starting || phase == .finalizing || phase == .recording {
                        setPhase(.idle, errorKey: nil)
                    }
                }
            } else {
                lease.markWriterFinalized()
                lease.dispose()
                pendingLease = nil
                pendingURL = nil
                pendingTake = nil
                recordedLease = nil
                retainedSelfDuringRecording = nil
                // Stale empty file still exits starting/finalizing (never
                // stuck); internal fail-closed for explicit retry.
                setPhase(.failed, errorKey: JumpVideoCaptureStrings.recordingFailed)
            }
        } else {
            let limitReached = (error as NSError?)?.code == AVError.diskFull.rawValue
                || outputFileURLExceededLimits()
            lease.markWriterFinalized()
            lease.dispose()
            pendingLease = nil
            pendingURL = nil
            pendingTake = nil
            recordedLease = nil
            retainedSelfDuringRecording = nil
            // Stale failure still exits starting/finalizing (never stuck).
            if limitReached {
                setPhase(.failed, errorKey: JumpVideoCaptureStrings.limitReached)
            } else {
                setPhase(.failed, errorKey: JumpVideoCaptureStrings.recordingFailed)
            }
        }
        // Parked suspend/dispose intents execute ONLY after real finish (never
        // before). Consumed exactly once by the authoritative intent: suspend
        // stops running for hidden/background/interruption; dispose does FULL
        // teardown (graph+observers+idle) after finalization. A duplicate
        // finish emits none. Strong retention already kept the engine alive.
        switch takeIntent.nativeFinished() {
        case .fullTeardown:
            sessionSuspended = true
            if session.isRunning { session.stopRunning() }
            tearDownSessionOnQueue()
            removeObservers()
            if phase != .idle {
                setPhase(.idle, errorKey: nil)
            }
        case .stopSession:
            sessionSuspended = true
            if session.isRunning { session.stopRunning() }
        case .none:
            break
        }
    }

    private func leaseAcquiredButDiscarded(_ lease: JumpVideoCaptureLease) -> Bool {
        // A discard/dispose raced finalization: the lease exposes no borrower
        // once disposed, and our sweep runs when borrowers drain. Detect by
        // attempting a borrow: nil means already discarded/disposed.
        return lease.acquireBorrower() == nil
    }

    private func outputFileURLExceededLimits() -> Bool {
        // Best-effort limit attribution: if the pending file reached either
        // bound, surface the limit key instead of the generic failure key.
        guard let url = pendingURL ?? recordedLease?.fileURL else { return false }
        guard let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber else {
            return false
        }
        return size.int64Value >= JumpVideoCaptureLimits.maximumFileSize
    }

    static func isRegularNonemptyFile(at url: URL) -> Bool {
        guard url.isFileURL else { return false }
        guard JumpVideoCapturePaths.isOwnedCaptureURL(url) else { return false }
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return false }
        guard attributes[.type] as? FileAttributeType == .typeRegular else { return false }
        guard let size = (attributes[.size] as? NSNumber)?.uint64Value, size > 0 else { return false }
        return true
    }

    deinit {
        // Authoritative cleanup is dispose() on the serial queue. deinit only
        // runs when no writer is active (self-retention released in the
        // delegate), so removing observers here cannot strand a recording.
        // Never manufacture finalization or delete on timeout.
        for token in notificationTokens {
            NotificationCenter.default.removeObserver(token)
        }
    }
}

// AVCaptureFileOutputRecordingDelegate conformance is deliberately in an
// extension (not an actor-isolated context) to avoid deinit/actor traps. The
// delegate bodies immediately re-enter the serial queue.
extension JumpVideoCaptureEngine: AVCaptureFileOutputRecordingDelegate {
    // Official exact declaration (iOS/iPadOS 4.0 floor, see
    // apple-flow-camera-foundation-didstart-api.json): informs the delegate
    // when the output has started writing to a file. Handled on the serial
    // queue; matched to the current owned take URL AND context. Marks ACTUAL
    // recording only upon real native start (never permission grant).
    func fileOutput(
        _ output: AVCaptureFileOutput,
        didStartRecordingTo fileURL: URL,
        from connections: [AVCaptureConnection]
    ) {
        handleStartedRecording(to: fileURL)
    }

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        handleFinishedRecording(to: outputFileURL, error: error)
    }
}

// MARK: - Thin MainActor facade (observable UI only, no capture verbs)

/// Explicit-lifecycle facade. Owns the engine strongly; the engine retains
/// itself while a writer is active, so teardown/cancel cannot deallocate the
/// pipeline before finalization. Exposes a recorded-owner lease as a
/// CANDIDATE only: no JumpWorkflowState mutation, no import/index, no old
/// analysis disposal in C1. Cancel/denial/error/failed candidates leave any
/// previous video/manifest/events/notes/metrics/savedMeasurement untouched
/// (this facade owns none of them).
@MainActor
final class JumpVideoCaptureFacade: ObservableObject {
    @Published private(set) var phase: JumpVideoCapturePhase = .idle
    /// Static Localizable key (never raw NSError text or file paths).
    @Published private(set) var errorKey: String?
    /// Candidate owner lease after successful finalization. Borrow (never a
    /// raw URL) for any future preview/import worker.
    @Published private(set) var recordedLease: JumpVideoCaptureLease?
    @Published private(set) var capabilities: JumpVideoCaptureCapabilities?

    /// Readonly session seam for the future main-thread preview host.
    /// Same rule as the engine: host on main only, never configure.
    var previewSessionForFutureHost: AVCaptureSession? {
        engine?.previewSessionForFutureHost
    }

    private var engine: JumpVideoCaptureEngine?

    init(engine: JumpVideoCaptureEngine? = JumpVideoCaptureEngine()) {
        self.engine = engine
        // Allocated != visible: a fresh facade is HIDDEN until the first
        // explicit viewAppeared (never visible-just-allocated). App
        // foreground mirrors the live MainActor app state (a temporary
        // inactive permission alert is NOT background). Both set
        // synchronously before any capture event can run.
        engine?.eventGate.setViewVisible(false)
        engine?.eventGate.setForegroundActive(UIApplication.shared.applicationState != .background)
        // Shared locked gate (not unrestricted main-reading of serial-queue
        // properties, no weak-self substitute, no lastAppliedEpoch-only).
        // The facade synchronously invalidates it on cancel/dispose/
        // viewDisappeared before old queued callbacks deliver; the engine
        // stamps every Event with the generation at publish time.
        let gate = engine?.eventGate
        engine?.onEvent = { [weak self] event in
            // Already on MainActor via the engine's async hop. No
            // assumeIsolated needed. Reject any queued event already
            // invalidated synchronously, even before a newer engine event
            // arrives. Legitimate idle/preparing/ready events after an
            // explicit new request carry the NEW generation and are accepted.
            // Dispose is terminal: never resurrect through late events.
            // Never retains the facade via the bridge (weak self).
            guard let self else { return }
            if let gate {
                guard gate.accepts(eventGeneration: event.generation) else { return }
                // Ticket-generation match alone is not enough: UI delivery
                // additionally requires combined eligibility (true app
                // foreground AND view visible). The flags are owned by the
                // locked gate and flipped synchronously by explicit lifecycle
                // entries only (viewAppeared marks view visible;
                // viewDisappeared hides the view; true background clears app
                // foreground; permission alerts never touch the gate).
                // Inactive always pairs with an invalidate/dispose, so this
                // never rejects a legitimate live event.
                guard gate.isForegroundActive() else { return }
            }
            self.phase = event.phase
            self.errorKey = event.errorKey
            self.recordedLease = event.lease
            self.capabilities = event.capabilities
        }
    }

    var statusKey: String { JumpVideoCapturePolicy.statusKey(for: phase) }

    var canStartRecording: Bool { JumpVideoCapturePolicy.canStartRecording(phase: phase) }
    var canStopRecording: Bool { JumpVideoCapturePolicy.canStopRecording(phase: phase) }
    var canUseRecorded: Bool { JumpVideoCapturePolicy.canUseRecorded(phase: phase) }

    /// Borrow the finalized candidate for a future worker. The borrower must
    /// be retained INSIDE that worker until real exit/ownership-transfer, and
    /// any player/item/observers torn down BEFORE releasing. Nil until a
    /// finalized .recorded candidate exists.
    func borrowRecorded() -> JumpVideoCaptureBorrower? {
        guard phase == .recorded else { return nil }
        return recordedLease?.acquireBorrower()
    }

    // MARK: Explicit lifecycle (no automatic recording anywhere)

    func requestPermissionAndPrepare(isActive: Bool) {
        // No provisional .requestingPermission publish here: the engine drives
        // phase via gated events. An inactive request stays idle (never stuck
        // in a provisional phase the engine rejected).
        engine?.requestPermissionAndPrepare(isActive: isActive)
    }

    func prepare() {
        engine?.prepare()
    }

    func startRecording() {
        guard canStartRecording else { return }
        engine?.startRecording()
    }

    func stopRecording() {
        guard canStopRecording else { return }
        // Pause/stop UI phase is distinct from file finalized: this provisional
        // UI never claims the file. For .starting the provisional stays
        // .starting (parked until real native start); only .recording shows
        // .finalizing provisionally.
        if phase == .recording {
            phase = .finalizing
            errorKey = JumpVideoCaptureStrings.finalizing
        }
        engine?.stopRecording()
    }

    func cancel() {
        // Synchronous MainActor invalidation BEFORE old queued callbacks:
        // clears the UI candidate now (never deletes under writer/readers;
        // the engine-owned lease still finalizes separately) and bumps the
        // shared ticket so stale ready/recorded events are rejected.
        engine?.eventGate.invalidate()
        if recordedLease != nil {
            recordedLease = nil
        }
        engine?.cancel()
    }

    func discardRecorded() {
        engine?.eventGate.invalidate()
        recordedLease = nil
        engine?.discardRecorded()
    }

    func prepareForNewRecording() {
        // Explicit new request: old queued events (prior generation) stay
        // rejected; new idle/ready events with the new generation accepted.
        engine?.eventGate.invalidate()
        recordedLease = nil
        engine?.prepareForNewRecording()
    }

    /// View appeared (visible). Never auto-records. Marks VIEW visible ONLY:
    /// appearance can never overwrite a true-background app state with true.
    /// A stale background is corrected only from the live MainActor app
    /// state (never unconditionally); the engine validates the originating
    /// ticket + both flags before restarting the preview.
    func viewAppeared() {
        // Explicit visible entry marks view visibility synchronously,
        // before the engine async block runs. App foreground is corrected
        // toward false only when the OS reports true background now.
        engine?.eventGate.setViewVisible(true)
        if UIApplication.shared.applicationState == .background {
            engine?.eventGate.setForegroundActive(false)
        }
        engine?.viewAppeared()
    }

    /// View disappeared (not background). Preserves permission-alert context:
    /// a permission alert keeps the request active; true background does not.
    /// Marks VIEW hidden (never app state) and synchronously invalidates
    /// queued UI before the engine async block runs.
    func viewDisappeared() {
        // Synchronous invalidation + view-hidden BEFORE old queued
        // callbacks: stale take/ready/recorded events are rejected even
        // before the engine async block runs.
        engine?.eventGate.setViewVisible(false)
        engine?.eventGate.invalidate()
        if recordedLease != nil {
            recordedLease = nil
        }
        engine?.viewDisappeared()
    }

    func dispose() {
        // Terminal: sever future events synchronously (locked gate, not a
        // racy onEvent nil), clear view eligibility + mark disposed, clear UI
        // candidate now, keep the engine alive
        // until its strongly-retained queued cleanup runs. Cannot resurrect
        // through late events/prepare. Never retains the facade via bridge.
        engine?.eventGate.setViewVisible(false)
        engine?.eventGate.markDisposed()
        recordedLease = nil
        phase = .idle
        errorKey = nil
        capabilities = nil
        let engineToDispose = engine
        // Release the facade reference now; dispose() retains strongly until
        // queued FULL teardown completes (graph+observers+idle after finish).
        engine = nil
        engineToDispose?.dispose()
    }
}
