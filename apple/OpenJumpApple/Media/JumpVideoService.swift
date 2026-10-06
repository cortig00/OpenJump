import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation

struct JumpVideoFrame {
    let index: Int
    let time: CMTime
    let ptsUs: Int64
}

struct JumpVideoManifest {
    let video: ImportedJumpVideo
    let frames: [JumpVideoFrame]
    let width: Int
    let height: Int

    var sourceID: UUID { video.id }
    var originUs: Int64 { frames[0].ptsUs }
}

struct PresentedJumpFrame {
    let sourceID: UUID
    let index: Int
    let ptsUs: Int64
    let image: CGImage
}

enum JumpVideoServiceError: Error, Equatable {
    case unsupportedVideo
    case unsupportedRotation
    case unsupportedHDR
    case unsupportedRetiming
    case invalidTimestamp
    case duplicateTimestamp
    case timestampCollision
    case insufficientFrames
    case tooManyFrames
    case inspectionFailed
    case frameUnavailable
    case frameTimedOut
    case inspectionTimedOut
    case sourceMismatch
    case frameIndexOutOfRange
}

private final class JumpVideoOperationControl {
    private let lock = NSLock()
    private let asset: AVURLAsset
    private var reader: AVAssetReader?
    private var cancelled = false

    init(asset: AVURLAsset) { self.asset = asset }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func install(reader: AVAssetReader) {
        lock.lock()
        self.reader = reader
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { reader.cancelReading() }
    }

    func cancel() {
        lock.lock()
        let shouldCancel = !cancelled
        cancelled = true
        let reader = self.reader
        lock.unlock()
        guard shouldCancel else { return }
        asset.cancelLoading()
        reader?.cancelReading()
    }
}

actor JumpVideoService {
    private static let maximumFrames = 250_000
    private static let maximumDimension = 4_096
    private static let maximumStillDimension: CGFloat = 1_920

    func inspect(_ video: ImportedJumpVideo) async throws -> JumpVideoManifest {
        let asset = AVURLAsset(url: video.url)
        let control = JumpVideoOperationControl(asset: asset)
        do {
            return try await CallbackDeadline.run(timeout: .seconds(60), cancel: { control.cancel() }) { complete in
                Task.detached(priority: .userInitiated) {
                    do {
                        let manifest = try await Self.buildManifest(video: video, asset: asset, control: control)
                        complete(.success(manifest))
                    } catch {
                        complete(.failure(error))
                    }
                }
            }
        } catch let error as JumpVideoServiceError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch CallbackDeadline.DeadlineError.timedOut {
            throw JumpVideoServiceError.inspectionTimedOut
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw JumpVideoServiceError.inspectionFailed
        }
    }

    func frame(manifest: JumpVideoManifest, index: Int) async throws -> PresentedJumpFrame {
        guard manifest.video.id == manifest.sourceID else { throw JumpVideoServiceError.sourceMismatch }
        guard manifest.frames.indices.contains(index) else { throw JumpVideoServiceError.frameIndexOutOfRange }
        let frame = manifest.frames[index]
        guard frame.index == index,
              (try? MediaProbe.microseconds(for: frame.time)) == frame.ptsUs else {
            throw JumpVideoServiceError.sourceMismatch
        }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: manifest.video.url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        generator.maximumSize = CGSize(width: Self.maximumStillDimension, height: Self.maximumStillDimension)

        do {
            let (image, actualTime): (CGImage, CMTime) = try await CallbackDeadline.run(
                timeout: .seconds(10), cancel: { generator.cancelAllCGImageGeneration() }
            ) { complete in
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: frame.time)]) { _, image, actualTime, result, error in
                    switch result {
                    case .succeeded:
                        guard let image else { complete(.failure(error ?? JumpVideoServiceError.frameUnavailable)); return }
                        complete(.success((image, actualTime)))
                    case .failed, .cancelled:
                        complete(.failure(error ?? JumpVideoServiceError.frameUnavailable))
                    @unknown default:
                        complete(.failure(JumpVideoServiceError.frameUnavailable))
                    }
                }
            }
            guard MediaProbe.matchesExactTime(actualTime, requested: frame.time) else {
                throw JumpVideoServiceError.frameUnavailable
            }
            return PresentedJumpFrame(sourceID: manifest.sourceID, index: index, ptsUs: frame.ptsUs, image: image)
        } catch let error as JumpVideoServiceError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch CallbackDeadline.DeadlineError.timedOut {
            throw JumpVideoServiceError.frameTimedOut
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw JumpVideoServiceError.frameUnavailable
        }
    }

    static func indexedFrames(from times: [CMTime]) throws -> [JumpVideoFrame] {
        guard times.count >= 2 else { throw JumpVideoServiceError.insufficientFrames }
        guard times.count <= maximumFrames else { throw JumpVideoServiceError.tooManyFrames }
        let sorted: [(time: CMTime, ptsUs: Int64)] = try times.map { time in
            do { return (time, try MediaProbe.microseconds(for: time)) }
            catch { throw JumpVideoServiceError.invalidTimestamp }
        }.sorted { CMTimeCompare($0.time, $1.time) < 0 }

        var result: [JumpVideoFrame] = []
        result.reserveCapacity(sorted.count)
        for entry in sorted {
            if let previous = result.last {
                guard CMTimeCompare(previous.time, entry.time) != 0 else { throw JumpVideoServiceError.duplicateTimestamp }
                guard previous.ptsUs != entry.ptsUs else { throw JumpVideoServiceError.timestampCollision }
            }
            result.append(JumpVideoFrame(index: result.count, time: entry.time, ptsUs: entry.ptsUs))
        }
        return result
    }

    private static func buildManifest(video: ImportedJumpVideo, asset: AVURLAsset,
                                      control: JumpVideoOperationControl) async throws -> JumpVideoManifest {
        let tracks = try await asset.load(.tracks)
        guard !control.isCancelled else { throw CancellationError() }
        let videoTracks = tracks.filter { $0.mediaType == .video }
        guard videoTracks.count == 1 else { throw JumpVideoServiceError.unsupportedVideo }
        let track = videoTracks[0]
        let naturalSize = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let descriptions = try await track.load(.formatDescriptions)
        let segments = try await track.load(.segments)
        guard !control.isCancelled else { throw CancellationError() }

        guard naturalSize.width.isFinite, naturalSize.height.isFinite,
              abs(naturalSize.width - naturalSize.width.rounded()) < 0.0001,
              abs(naturalSize.height - naturalSize.height.rounded()) < 0.0001,
              naturalSize.width >= 1, naturalSize.height >= 1,
              naturalSize.width <= CGFloat(maximumDimension), naturalSize.height <= CGFloat(maximumDimension) else {
            throw JumpVideoServiceError.unsupportedVideo
        }
        guard supportsRotation(transform) else { throw JumpVideoServiceError.unsupportedRotation }
        guard !track.hasMediaCharacteristic(.containsHDRVideo), !descriptions.contains(where: isHDR) else {
            throw JumpVideoServiceError.unsupportedHDR
        }
        guard !hasRetiming(segments) else { throw JumpVideoServiceError.unsupportedRetiming }

        let reader = try AVAssetReader(asset: asset)
        control.install(reader: reader)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        guard reader.canAdd(output) else { throw JumpVideoServiceError.unsupportedVideo }
        reader.add(output)
        guard reader.startReading() else { throw JumpVideoServiceError.inspectionFailed }

        var times: [CMTime] = []
        while let sample = output.copyNextSampleBuffer() {
            guard !control.isCancelled else { throw CancellationError() }
            let sampleCount = CMSampleBufferGetNumSamples(sample)
            guard sampleCount > 0, sampleCount <= maximumFrames - times.count else {
                reader.cancelReading()
                throw sampleCount > 0 ? JumpVideoServiceError.tooManyFrames : JumpVideoServiceError.inspectionFailed
            }
            for sampleIndex in 0..<sampleCount {
                var timing = CMSampleTimingInfo()
                guard CMSampleBufferGetSampleTimingInfo(sample, at: sampleIndex, timingInfoOut: &timing) == noErr else {
                    reader.cancelReading()
                    throw JumpVideoServiceError.inspectionFailed
                }
                times.append(timing.presentationTimeStamp)
            }
        }
        guard !control.isCancelled else { throw CancellationError() }
        guard reader.status == .completed else { throw JumpVideoServiceError.inspectionFailed }
        let frames = try indexedFrames(from: times)
        return JumpVideoManifest(video: video, frames: frames,
                                 width: Int(naturalSize.width), height: Int(naturalSize.height))
    }

    private static func supportsRotation(_ transform: CGAffineTransform) -> Bool {
        let components = [transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty]
        guard components.allSatisfy(\.isFinite) else { return false }
        let linear = [transform.a, transform.b, transform.c, transform.d]
        guard linear.allSatisfy({ abs($0 - $0.rounded()) < 0.0001 && abs($0.rounded()) <= 1 }) else { return false }
        let determinant = transform.a * transform.d - transform.b * transform.c
        let firstNorm = transform.a * transform.a + transform.b * transform.b
        let secondNorm = transform.c * transform.c + transform.d * transform.d
        let dot = transform.a * transform.c + transform.b * transform.d
        return abs(determinant - 1) < 0.0001 && abs(firstNorm - 1) < 0.0001 &&
            abs(secondNorm - 1) < 0.0001 && abs(dot) < 0.0001
    }

    private static func hasRetiming(_ segments: [AVAssetTrackSegment]) -> Bool {
        for segment in segments where !segment.isEmpty {
            let mapping = segment.timeMapping
            let source = mapping.source.duration
            let target = mapping.target.duration
            guard source.isValid, source.isNumeric, source.timescale > 0,
                  target.isValid, target.isNumeric, target.timescale > 0,
                  source.epoch == 0, target.epoch == 0,
                  CMTimeCompare(source, target) == 0 else { return true }
        }
        return false
    }

    private static func isHDR(_ description: CMFormatDescription) -> Bool {
        guard let extensions = CMFormatDescriptionGetExtensions(description) else { return false }
        let text = (extensions as NSDictionary).map { "\($0.key) \($0.value)" }.joined(separator: " ").lowercased()
        return ["2084", "2100_hlg", "2100-hlg", "dolby vision", "dolby_vision"].contains(where: text.contains)
    }
}
