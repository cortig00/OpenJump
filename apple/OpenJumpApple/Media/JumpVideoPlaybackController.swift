import AVFoundation
import Combine
import CoreMedia
import Foundation

// P1a playback foundation (not UI connected yet; P1b integration next).
// Native preview only: one AVPlayer with a single periodic observer, readiness
// gating, and fail-closed seek/play. Navigation time is for selecting an
// indexed source frame; marked measurement always remains manifest PTS via the
// existing exact image path. No transport UI in this slice.
@MainActor
final class JumpVideoPlaybackController: ObservableObject {
    enum Phase: Equatable {
        case idle
        case paused
        case playing
        case seeking
        case failed
    }

    enum Readiness: Equatable {
        case none
        case unknown
        case ready
        case failed
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var readiness: Readiness = .none
    @Published private(set) var wantsPlayback = false

    // P1b weak-owner wiring. All callbacks run on MainActor.
    var onChange: (() -> Void)?
    var onEnd: (() -> Void)?
    var onFailure: (() -> Void)?

    // TWO epochs. Attachment is stable across pause/play and changes on
    // attach/teardown (even for the same file re-attached). Request changes on
    // every play/pause/seek. Permanent observers guard attachment; seek
    // completions guard attachment AND request.
    private(set) var attachmentTicket: UInt64 = 0
    private(set) var requestTicket: UInt64 = 0

    var hasPeriodicObserver: Bool { store.hasTimeObserver }
    var nativePlayer: AVPlayer? { store.currentPlayer() }
    var attachedSourceID: UUID? { attachedSource }
    var currentNativeTime: CMTime? { store.currentPlayer()?.currentTime() }

    private static let previewRate: Float = 1.0
    private static let periodicInterval = CMTime(value: 1, timescale: 10)

    private var attachedSource: UUID?
    private let store = PlaybackResourceStore()

    /// Bound source leased by the resource store. The store retains the
    /// ImportedJumpVideo alongside the backend until after detaching, so the
    /// owned file cannot be deleted before pause/cancel/remove/replace-nil.
    var currentVideo: ImportedJumpVideo? { store.currentVideo() }

    deinit {
        // Nonisolated fallback owns cleanup: authoritative path is teardown()
        // on MainActor. The store below removes its player observer, KVO, and
        // notification registrations without touching MainActor state.
    }

    // MARK: - Ticket policy (shared with tests, actually used below)

    static func acceptsAttachmentCallback(
        capturedAttachment: UInt64,
        currentAttachment: UInt64,
        capturedSourceID: UUID,
        currentSourceID: UUID?
    ) -> Bool {
        capturedAttachment == currentAttachment && capturedSourceID == currentSourceID
    }

    static func acceptsRequestCallback(
        capturedAttachment: UInt64,
        currentAttachment: UInt64,
        capturedRequest: UInt64,
        currentRequest: UInt64
    ) -> Bool {
        capturedAttachment == currentAttachment && capturedRequest == currentRequest
    }

    // MARK: - Pure transport policy (actually used by handlers below)

    /// Seek-completion promotion gate. Only a finished seek on a currently
    /// ready item, with live playback intent and phase still SEEKING,
    /// promotes to playing. Every other combination settles fail-closed
    /// (preserving .failed) and never resurrects a failed/invalid intent.
    static func shouldPromoteSeekToPlaying(
        finished: Bool,
        readiness: Readiness,
        itemReady: Bool,
        wantsPlayback: Bool,
        phase: Phase
    ) -> Bool {
        finished && readiness == .ready && itemReady && wantsPlayback && phase == .seeking
    }

    /// Genuine-end gate over native transport state and native clock only.
    /// Requires current playing intent on a ready item with a valid
    /// non-negative epoch-0 current time at or past a positive epoch-0
    /// duration (CMTimeCompare, no tolerance factor, no FPS/seconds math).
    static func shouldHonorEnd(
        phase: Phase,
        wantsPlayback: Bool,
        readiness: Readiness,
        itemReady: Bool,
        currentTime: CMTime,
        duration: CMTime
    ) -> Bool {
        guard phase == .playing, wantsPlayback, readiness == .ready, itemReady else { return false }
        guard currentTime.isValid, currentTime.isNumeric, currentTime.timescale > 0, currentTime.epoch == 0,
              CMTimeCompare(currentTime, .zero) >= 0 else { return false }
        guard duration.isValid, duration.isNumeric, duration.timescale > 0, duration.epoch == 0,
              CMTimeCompare(duration, .zero) > 0 else { return false }
        return CMTimeCompare(currentTime, duration) >= 0
    }

    /// Central pending-request invalidation + backend stop. Epoch FIRST
    /// (stale seek completions arrive with finished=false and must read as
    /// stale), then pause the backend and cancel the owned item's pending
    /// seeks. Callers settle wantsPlayback/phase after invoking.
    private func invalidatePendingRequestAndStopBackend() {
        requestTicket &+= 1
        store.currentPlayer()?.pause()
        store.currentItem()?.cancelPendingSeeks()
    }

    // MARK: - Pure navigation resolver (NAV ONLY)

    // Floor binary lookup over already-validated, strictly ordered source
    // frame times using CMTimeCompare. Clamps a valid time before the first
    // frame to index 0 and after the last frame to the final index. Returns
    // nil for empty, invalid, indefinite, infinite, negative, wrong-epoch, or
    // non-positive-timescale input. The returned array index selects the exact
    // image path; measurement remains manifest PTS, never player-clock math.
    static func navigationIndex(for time: CMTime, in frames: [JumpVideoFrame]) -> Int? {
        guard !frames.isEmpty else { return nil }
        guard time.isValid, time.isNumeric, time.timescale > 0, time.epoch == 0,
              CMTimeCompare(time, .zero) >= 0 else { return nil }
        if CMTimeCompare(time, frames[0].time) < 0 { return 0 }
        let last = frames.count - 1
        if CMTimeCompare(time, frames[last].time) >= 0 { return last }
        var low = 0
        var high = last
        while high - low > 1 {
            let mid = low + (high - low) / 2
            if CMTimeCompare(frames[mid].time, time) <= 0 {
                low = mid
            } else {
                high = mid
            }
        }
        return low
    }

    // Controller-owned entry point for P1b: only resolves when the manifest
    // belongs to the currently attached source. Uses the pure resolver above.
    func navigationIndex(for nativeTime: CMTime, in manifest: JumpVideoManifest) -> Int? {
        guard let current = attachedSource, current == manifest.sourceID else { return nil }
        return Self.navigationIndex(for: nativeTime, in: manifest.frames)
    }

    // MARK: - Attach / transport

    func attach(video: ImportedJumpVideo) {
        store.detachAll()
        attachmentTicket &+= 1
        requestTicket = 0
        attachedSource = video.id
        let attachment = attachmentTicket
        let sourceID = video.id

        let item = AVPlayerItem(url: video.url)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.actionAtItemEnd = .pause
        store.install(player: player, item: item, video: video)

        let statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] observedItem, _ in
            Task { @MainActor in
                self?.handleItemStatus(observedItem, attachment: attachment, sourceID: sourceID)
            }
        }
        store.setStatusObservation(statusObservation)

        let timeControlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] observedPlayer, _ in
            Task { @MainActor in
                self?.handleTimeControl(observedPlayer, attachment: attachment, sourceID: sourceID)
            }
        }
        store.setTimeControlObservation(timeControlObservation)

        let endToken = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { [weak self] note in
            Task { @MainActor in
                self?.handleDidPlayToEnd(note.object as? AVPlayerItem, attachment: attachment, sourceID: sourceID)
            }
        }
        store.setEndToken(endToken)

        let failureToken = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.failedToPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { [weak self] note in
            Task { @MainActor in
                self?.handleFailure(note.object as? AVPlayerItem, attachment: attachment, sourceID: sourceID)
            }
        }
        store.setFailureToken(failureToken)

        let token = player.addPeriodicTimeObserver(
            forInterval: Self.periodicInterval,
            queue: DispatchQueue.main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.handlePeriodic(attachment: attachment, sourceID: sourceID, player: player)
            }
        }
        store.setTimeToken(token, owner: player)

        readiness = .unknown
        phase = .paused
        wantsPlayback = false
        onChange?()
    }

    @discardableResult
    func pause() -> CMTime? {
        invalidatePendingRequestAndStopBackend()
        wantsPlayback = false
        guard let player = store.currentPlayer(), attachedSource != nil, store.currentVideo() != nil else {
            if phase != .failed && phase != .idle { phase = .paused }
            return nil
        }
        if phase != .failed { phase = .paused }
        let now = player.currentTime()
        onChange?()
        return now
    }

    func play(from indexedTime: CMTime) {
        guard let player = store.currentPlayer(),
              store.currentVideo() != nil,
              let sourceID = attachedSource,
              readiness == .ready,
              player.currentItem?.status == .readyToPlay,
              indexedTime.isValid, indexedTime.isNumeric, indexedTime.timescale > 0, indexedTime.epoch == 0,
              CMTimeCompare(indexedTime, .zero) >= 0 else {
            invalidatePendingRequestAndStopBackend()
            wantsPlayback = false
            if phase != .failed { phase = .paused }
            return
        }
        guard let capturedItem = player.currentItem else {
            invalidatePendingRequestAndStopBackend()
            wantsPlayback = false
            if phase != .failed { phase = .paused }
            return
        }
        // Valid intent: invalidate any prior request and stop the backend
        // before issuing the zero-tolerance seek, so an old seek (if any)
        // completes stale and the backend never plays old content.
        invalidatePendingRequestAndStopBackend()
        let attachment = attachmentTicket
        let request = requestTicket
        let capturedPlayer = player
        wantsPlayback = true
        phase = .seeking
        onChange?()
        player.isMuted = true
        player.seek(to: indexedTime, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            Task { @MainActor in
                guard let self else { return }
                guard Self.acceptsRequestCallback(
                    capturedAttachment: attachment,
                    currentAttachment: self.attachmentTicket,
                    capturedRequest: request,
                    currentRequest: self.requestTicket
                ) else { return }
                guard Self.acceptsAttachmentCallback(
                    capturedAttachment: attachment,
                    currentAttachment: self.attachmentTicket,
                    capturedSourceID: sourceID,
                    currentSourceID: self.attachedSource
                ) else { return }
                guard capturedPlayer === self.store.currentPlayer(),
                      capturedItem === self.store.currentItem() else { return }
                guard finished else {
                    self.wantsPlayback = false
                    capturedPlayer.pause()
                    if self.phase != .failed { self.phase = .paused }
                    self.onChange?()
                    return
                }
                guard Self.shouldPromoteSeekToPlaying(
                    finished: finished,
                    readiness: self.readiness,
                    itemReady: capturedItem.status == .readyToPlay,
                    wantsPlayback: self.wantsPlayback,
                    phase: self.phase
                ) else {
                    // Fail-closed: a finished seek that outlived its intent
                    // (failure/invalid/pause/teardown/end since) never
                    // resurrects playback. Preserve .failed, else settle
                    // paused with intent false and the backend stopped.
                    // Identities were checked first, so this never pauses a
                    // newer request.
                    capturedPlayer.pause()
                    self.wantsPlayback = false
                    if self.phase != .failed { self.phase = .paused }
                    self.onChange?()
                    return
                }
                capturedPlayer.isMuted = true
                capturedPlayer.play()
                capturedPlayer.rate = Self.previewRate
                self.wantsPlayback = true
                self.phase = .playing
                self.onChange?()
            }
        }
    }

    func teardown() {
        requestTicket &+= 1
        attachmentTicket &+= 1
        wantsPlayback = false
        store.detachAll()
        attachedSource = nil
        phase = .idle
        readiness = .none
        onChange?()
    }

    // MARK: - Callback handlers (all MainActor)

    private func handleItemStatus(_ observedItem: AVPlayerItem, attachment: UInt64, sourceID: UUID) {
        guard Self.acceptsAttachmentCallback(
            capturedAttachment: attachment,
            currentAttachment: attachmentTicket,
            capturedSourceID: sourceID,
            currentSourceID: attachedSource
        ) else { return }
        guard observedItem === store.currentItem(),
              store.currentPlayer()?.currentItem === observedItem else { return }
        switch observedItem.status {
        case .readyToPlay:
            readiness = .ready
            if phase == .failed || phase == .idle { phase = .paused }
            onChange?()
        case .failed:
            invalidatePendingRequestAndStopBackend()
            readiness = .failed
            wantsPlayback = false
            phase = .failed
            onFailure?()
        case .unknown:
            readiness = .unknown
            onChange?()
        @unknown default:
            readiness = .unknown
            onChange?()
        }
    }

    private func handleTimeControl(_ observedPlayer: AVPlayer, attachment: UInt64, sourceID: UUID) {
        guard Self.acceptsAttachmentCallback(
            capturedAttachment: attachment,
            currentAttachment: attachmentTicket,
            capturedSourceID: sourceID,
            currentSourceID: attachedSource
        ) else { return }
        guard observedPlayer === store.currentPlayer() else { return }
        let current = observedPlayer.timeControlStatus
        switch current {
        case .playing:
            if phase == .playing && wantsPlayback {
                onChange?()
            } else if phase == .seeking && wantsPlayback {
                // Pending seek owns the intent: stop unexpected backend
                // playback without touching the ticket/intent, and never
                // cancel the in-flight seek. Only the seek completion
                // promotes SEEKING to playing. `current` above is a fresh
                // read of the live player, not a stale KVO payload.
                observedPlayer.pause()
                onChange?()
            } else {
                observedPlayer.pause()
                wantsPlayback = false
                if phase != .failed { phase = .paused }
                onChange?()
            }
        case .paused:
            onChange?()
        case .waitingToPlayAtSpecifiedRate:
            onChange?()
        @unknown default:
            onChange?()
        }
    }

    private func handlePeriodic(attachment: UInt64, sourceID: UUID, player: AVPlayer) {
        guard Self.acceptsAttachmentCallback(
            capturedAttachment: attachment,
            currentAttachment: attachmentTicket,
            capturedSourceID: sourceID,
            currentSourceID: attachedSource
        ) else { return }
        guard player === store.currentPlayer() else { return }
        onChange?()
    }

    private func handleDidPlayToEnd(_ observedItem: AVPlayerItem?, attachment: UInt64, sourceID: UUID) {
        guard Self.acceptsAttachmentCallback(
            capturedAttachment: attachment,
            currentAttachment: attachmentTicket,
            capturedSourceID: sourceID,
            currentSourceID: attachedSource
        ) else { return }
        guard let item = observedItem, item === store.currentItem() else { return }
        if phase == .seeking { return }
        let now = store.currentPlayer()?.currentTime() ?? .invalid
        let itemReady = item.status == .readyToPlay
        guard Self.shouldHonorEnd(
            phase: phase,
            wantsPlayback: wantsPlayback,
            readiness: readiness,
            itemReady: itemReady,
            currentTime: now,
            duration: item.duration
        ) else { return }
        invalidatePendingRequestAndStopBackend()
        wantsPlayback = false
        if phase != .failed { phase = .paused }
        onEnd?()
        onChange?()
    }

    private func handleFailure(_ observedItem: AVPlayerItem?, attachment: UInt64, sourceID: UUID) {
        guard Self.acceptsAttachmentCallback(
            capturedAttachment: attachment,
            currentAttachment: attachmentTicket,
            capturedSourceID: sourceID,
            currentSourceID: attachedSource
        ) else { return }
        guard let item = observedItem, item === store.currentItem() else { return }
        invalidatePendingRequestAndStopBackend()
        readiness = .failed
        wantsPlayback = false
        phase = .failed
        onFailure?()
        onChange?()
    }
}

// Non-actor lease so deinit never touches MainActor state. Holds the single
// player, its one periodic token with the registering player, KVO, and
// notification registrations. Every add is paired with removal on the same
// player. Thread-safe via lock; detachAll is idempotent.
private final class PlaybackResourceStore: @unchecked Sendable {
    private let lock = NSLock()
    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var video: ImportedJumpVideo?
    private var timeToken: Any?
    private var timeOwner: AVPlayer?
    private var statusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?

    var hasTimeObserver: Bool {
        lock.lock()
        defer { lock.unlock() }
        return timeToken != nil && timeOwner != nil
    }

    func currentPlayer() -> AVPlayer? {
        lock.lock()
        defer { lock.unlock() }
        return player
    }

    func currentItem() -> AVPlayerItem? {
        lock.lock()
        defer { lock.unlock() }
        return item
    }

    func currentVideo() -> ImportedJumpVideo? {
        lock.lock()
        defer { lock.unlock() }
        return video
    }

    func install(player: AVPlayer, item: AVPlayerItem, video: ImportedJumpVideo) {
        lock.lock()
        self.player = player
        self.item = item
        self.video = video
        lock.unlock()
    }

    func setStatusObservation(_ observation: NSKeyValueObservation) {
        lock.lock()
        statusObservation = observation
        lock.unlock()
    }

    func setTimeControlObservation(_ observation: NSKeyValueObservation) {
        lock.lock()
        timeControlObservation = observation
        lock.unlock()
    }

    func setEndToken(_ token: NSObjectProtocol) {
        lock.lock()
        endToken = token
        lock.unlock()
    }

    func setFailureToken(_ token: NSObjectProtocol) {
        lock.lock()
        failureToken = token
        lock.unlock()
    }

    func setTimeToken(_ token: Any, owner: AVPlayer) {
        lock.lock()
        timeToken = token
        timeOwner = owner
        lock.unlock()
    }

    func detachAll() {
        lock.lock()
        let player = self.player
        let item = self.item
        let videoSnapshot = self.video
        let timeToken = self.timeToken
        let timeOwner = self.timeOwner
        let statusObservation = self.statusObservation
        let timeControlObservation = self.timeControlObservation
        let endToken = self.endToken
        let failureToken = self.failureToken
        self.player = nil
        self.item = nil
        self.video = nil
        self.timeToken = nil
        self.timeOwner = nil
        self.statusObservation = nil
        self.timeControlObservation = nil
        self.endToken = nil
        self.failureToken = nil
        lock.unlock()

        // The store leases the source: withExtendedLifetime keeps the owned
        // file alive until after the backend is fully detached, so ARC can
        // never delete the item's URL first. Never dispose here; explicit
        // disposal belongs to the video owner after teardown.
        withExtendedLifetime(videoSnapshot) {
            player?.pause()
            item?.cancelPendingSeeks()
            if let timeToken, let timeOwner {
                timeOwner.removeTimeObserver(timeToken)
            }
            statusObservation?.invalidate()
            timeControlObservation?.invalidate()
            if let endToken { NotificationCenter.default.removeObserver(endToken) }
            if let failureToken { NotificationCenter.default.removeObserver(failureToken) }
            player?.replaceCurrentItem(with: nil)
        }
    }

    deinit {
        detachAll()
    }
}
