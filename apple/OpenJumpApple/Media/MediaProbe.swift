import AVFoundation
import CoreMedia
import CoreGraphics
import Foundation

struct CallbackDeadline {
    private final class State<Value> {
        // Start registration and cancellation are serialized; synchronous callbacks may re-enter resolve.
        private let lock = NSRecursiveLock()
        private var continuation: CheckedContinuation<Value, Error>?
        private var result: Result<Value, Error>?
        private var timer: DispatchWorkItem?
        // Separate completion flag: the result payload is released promptly
        // after delivery, while this flag preserves idempotence for
        // startIfPending/setTimer/re-entrant resolve. A cancelled timer is
        // still held by the queue until its deadline, so retaining the
        // payload in `result` would retain inspected manifests (and their
        // owned videos) for the full timeout.
        private var resolved = false
        private let cancelOperation: () -> Void

        init(cancelOperation: @escaping () -> Void) {
            self.cancelOperation = cancelOperation
        }

        func install(_ continuation: CheckedContinuation<Value, Error>) {
            lock.lock()
            if resolved {
                let pending = self.result
                lock.unlock()
                guard let pending else { return }
                continuation.resume(with: pending)
                lock.lock()
                self.result = nil
                lock.unlock()
                return
            }
            self.continuation = continuation
            lock.unlock()
        }

        func setTimer(_ timer: DispatchWorkItem) {
            lock.lock()
            if resolved { lock.unlock(); timer.cancel(); return }
            self.timer = timer
            lock.unlock()
        }

        func startIfPending(_ start: () -> Void) {
            lock.lock()
            defer { lock.unlock() }
            guard !resolved else { return }
            start()
        }

        func resolve(_ result: Result<Value, Error>, cancel: Bool) {
            lock.lock()
            guard !resolved else { lock.unlock(); return }
            resolved = true
            self.result = result
            let continuation = self.continuation
            self.continuation = nil
            let timer = self.timer
            self.timer = nil
            lock.unlock()
            timer?.cancel()
            if cancel { cancelOperation() }
            guard let continuation else { return }
            continuation.resume(with: result)
            lock.lock()
            self.result = nil
            lock.unlock()
        }
    }

    static func run<Value>(timeout: DispatchTimeInterval, cancel: @escaping () -> Void = {},
                           start: @escaping (@escaping (Result<Value, Error>) -> Void) -> Void) async throws -> Value {
        let state = State<Value>(cancelOperation: cancel)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                state.install(continuation)
                let timer = DispatchWorkItem { state.resolve(.failure(DeadlineError.timedOut), cancel: true) }
                state.setTimer(timer)
                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + timeout, execute: timer)
                state.startIfPending {
                    start { result in state.resolve(result, cancel: false) }
                }
            }
        } onCancel: {
            state.resolve(.failure(CancellationError()), cancel: true)
        }
    }

    enum DeadlineError: Error { case timedOut }
}

struct MediaProbe {
    static let maximumFrames = 256
    static let microsecondScale: Int32 = 1_000_000
    private static let operationTimeout: DispatchTimeInterval = .seconds(10)

    struct Frame: Equatable {
        let ordinal: Int
        let sourceTime: CMTime
        let presentationTimeUs: Int64
    }

    struct Manifest {
        let frames: [Frame]
        let width: Int
        let height: Int
        fileprivate let sourceURL: URL
    }

    enum ProbeError: Error, Equatable {
        case invalidTime
        case timeOutOfRange
        case duplicateTime
        case roundingCollision
        case noVideoTrack
        case unsupportedTrackLayout
        case unsupportedDimensions
        case unsupportedTransform
        case emptyVideo
        case tooManyFrames
        case readerFailed
        case decodeFailed
        case nonExactRequest
        case sourceMismatch
    }

    static func microseconds(for time: CMTime) throws -> Int64 {
        guard time.isValid, time.isNumeric, time.timescale > 0 else { throw ProbeError.invalidTime }
        guard time.epoch == 0, CMTimeCompare(time, .zero) >= 0 else { throw ProbeError.timeOutOfRange }
        let scaled = CMTimeConvertScale(time, timescale: microsecondScale, method: .roundHalfAwayFromZero)
        guard scaled.isValid, scaled.isNumeric, scaled.epoch == 0, scaled.timescale == microsecondScale,
              scaled.value >= 0 else { throw ProbeError.timeOutOfRange }
        return scaled.value
    }

    static func matchesExactTime(_ actual: CMTime, requested: CMTime) -> Bool {
        actual.isValid && actual.isNumeric && requested.isValid && requested.isNumeric && CMTimeCompare(actual, requested) == 0
    }

    static func validatedFrames(_ times: [CMTime]) throws -> [Frame] {
        guard !times.isEmpty else { throw ProbeError.emptyVideo }
        guard times.count <= maximumFrames else { throw ProbeError.tooManyFrames }
        let sorted = try times.map { (time: $0, us: try microseconds(for: $0)) }.sorted {
            CMTimeCompare($0.time, $1.time) < 0
        }
        var frames: [Frame] = []
        for entry in sorted {
            if let previous = frames.last {
                guard CMTimeCompare(previous.sourceTime, entry.time) != 0 else { throw ProbeError.duplicateTime }
                guard previous.presentationTimeUs != entry.us else { throw ProbeError.roundingCollision }
            }
            frames.append(Frame(ordinal: frames.count, sourceTime: entry.time, presentationTimeUs: entry.us))
        }
        return frames
    }

    static func normalizedSourceURL(_ url: URL) -> URL {
        url.standardizedFileURL.resolvingSymlinksInPath()
    }

    func inspect(url: URL) async throws -> Manifest {
        let sourceURL = Self.normalizedSourceURL(url)
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.load(.tracks)
        let videoTracks = tracks.filter { $0.mediaType == .video }
        guard videoTracks.count == 1, tracks.filter({ $0.mediaType == .audio }).isEmpty else {
            throw ProbeError.unsupportedTrackLayout
        }
        let track = videoTracks[0]
        let naturalSize = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        guard transform.isIdentity else { throw ProbeError.unsupportedTransform }
        guard naturalSize.width == 320, naturalSize.height == 240 else { throw ProbeError.unsupportedDimensions }

        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        guard reader.canAdd(output) else { throw ProbeError.readerFailed }
        reader.add(output)
        guard reader.startReading() else { throw ProbeError.readerFailed }
        var times: [CMTime] = []
        while let sample = output.copyNextSampleBuffer() {
            let sampleCount = CMSampleBufferGetNumSamples(sample)
            for index in 0..<sampleCount {
                var timing = CMSampleTimingInfo()
                guard CMSampleBufferGetSampleTimingInfo(sample, at: index, timingInfoOut: &timing) == noErr else {
                    reader.cancelReading()
                    throw ProbeError.readerFailed
                }
                times.append(timing.presentationTimeStamp)
                guard times.count <= Self.maximumFrames else {
                    reader.cancelReading()
                    throw ProbeError.tooManyFrames
                }
            }
        }
        guard reader.status == .completed else { throw ProbeError.readerFailed }
        let frames = try Self.validatedFrames(times)
        return Manifest(frames: frames, width: Int(naturalSize.width), height: Int(naturalSize.height), sourceURL: sourceURL)
    }

    func image(url: URL, manifest: Manifest, ordinal: Int) async throws -> CGImage {
        guard manifest.frames.indices.contains(ordinal) else { throw ProbeError.nonExactRequest }
        return try await image(url: url, manifest: manifest, at: manifest.frames[ordinal].sourceTime)
    }

    func image(url: URL, manifest: Manifest, at requestedTime: CMTime) async throws -> CGImage {
        guard Self.normalizedSourceURL(url) == manifest.sourceURL else { throw ProbeError.sourceMismatch }
        guard requestedTime.isValid, requestedTime.isNumeric, requestedTime.timescale > 0,
              requestedTime.epoch == 0 else { throw ProbeError.nonExactRequest }
        guard let frame = manifest.frames.first(where: { CMTimeCompare($0.sourceTime, requestedTime) == 0 }) else {
            throw ProbeError.nonExactRequest
        }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = false
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        do {
            let (image, actualTime): (CGImage, CMTime) = try await CallbackDeadline.run(
                timeout: Self.operationTimeout, cancel: { generator.cancelAllCGImageGeneration() }
            ) { (complete: @escaping (Result<(CGImage, CMTime), Error>) -> Void) in
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: frame.sourceTime)]) { _, image, actualTime, result, error in
                    switch result {
                    case .succeeded:
                        guard let image else { complete(.failure(ProbeError.decodeFailed)); return }
                        complete(.success((image, actualTime)))
                    case .failed, .cancelled:
                        complete(.failure(error ?? ProbeError.decodeFailed))
                    @unknown default:
                        complete(.failure(ProbeError.decodeFailed))
                    }
                }
            }
            guard Self.matchesExactTime(actualTime, requested: frame.sourceTime) else { throw ProbeError.decodeFailed }
            return image
        } catch let error as ProbeError {
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw ProbeError.decodeFailed
        }
    }
}
