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

private struct JumpVideoInspectionDiagnostic: Error, CustomStringConvertible {
    let stage: String
    let errorDomain: String?
    let errorCode: Int?
    let readerStatus: Int?
    let timingOSStatus: OSStatus?
    let sampleCount: Int?
    let sampleValid: Bool?
    let sampleReady: Bool?

    var description: String {
        var fields = ["stage=\(stage)"]
        if let errorDomain, let errorCode { fields.append("error=\(errorDomain):\(errorCode)") }
        if let readerStatus { fields.append("readerStatus=\(readerStatus)") }
        if let timingOSStatus { fields.append("timingOSStatus=\(timingOSStatus)") }
        if let sampleCount { fields.append("sampleCount=\(sampleCount)") }
        if let sampleValid { fields.append("sampleValid=\(sampleValid)") }
        if let sampleReady { fields.append("sampleReady=\(sampleReady)") }
        return "Jump video inspection failed (\(fields.joined(separator: ", ")))"
    }
}

enum JumpVideoSampleDecision: Equatable {
    case media(sampleCount: CMItemCount)
    case skipEmptyNonFrame
    case invalid
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
        } catch let diagnostic as JumpVideoInspectionDiagnostic {
            throw diagnostic
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
        guard times.count <= maximumFrames else { throw JumpVideoServiceError.tooManyFrames }
        let validated: [(time: CMTime, ptsUs: Int64)] = try times.map { time in
            do { return (time, try MediaProbe.microseconds(for: time)) }
            catch { throw JumpVideoServiceError.invalidTimestamp }
        }
        guard times.count >= 2 else { throw JumpVideoServiceError.insufficientFrames }
        let sorted = validated.sorted { CMTimeCompare($0.time, $1.time) < 0 }

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

    // Primary CoreMedia semantics: CMSampleBufferCreate permits sampleCount 0 with a
    // nil format description and nil data buffer (dataReady must then be true), while
    // CMSampleBufferGetNumSamples returns 0 on error. A valid, data-ready zero-sample
    // buffer therefore carries no frame timing and is skipped as a non-frame; an
    // invalid buffer, or a zero-sample buffer that is not data-ready, is
    // indistinguishable from an error and fails inspection. No attachment check.
    static func classifySampleBuffer(_ sample: CMSampleBuffer) -> JumpVideoSampleDecision {
        let sampleCount = CMSampleBufferGetNumSamples(sample)
        guard CMSampleBufferIsValid(sample), sampleCount >= 0 else { return .invalid }
        if sampleCount == 0 {
            guard CMSampleBufferDataIsReady(sample) else { return .invalid }
            return .skipEmptyNonFrame
        }
        return .media(sampleCount: sampleCount)
    }

    private static func buildManifest(video: ImportedJumpVideo, asset: AVURLAsset,
                                      control: JumpVideoOperationControl) async throws -> JumpVideoManifest {
        var stage = "loadTracks"
        var reader: AVAssetReader?
        var timingOSStatus: OSStatus?
        var lastSampleCount: Int?
        var lastSampleValid: Bool?
        var lastSampleReady: Bool?
        do {
            let tracks = try await asset.load(.tracks)
            guard !control.isCancelled else { throw CancellationError() }
            let videoTracks = tracks.filter { $0.mediaType == .video }
            guard videoTracks.count == 1 else { throw JumpVideoServiceError.unsupportedVideo }
            let track = videoTracks[0]
            stage = "loadNaturalSize"
            let naturalSize = try await track.load(.naturalSize)
            stage = "loadPreferredTransform"
            let transform = try await track.load(.preferredTransform)
            stage = "loadFormatDescriptions"
            let descriptions = try await track.load(.formatDescriptions)
            stage = "loadSegments"
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

            stage = "createReader"
            let createdReader = try AVAssetReader(asset: asset)
            reader = createdReader
            control.install(reader: createdReader)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
            guard createdReader.canAdd(output) else { throw JumpVideoServiceError.unsupportedVideo }
            createdReader.add(output)
            stage = "startReading"
            guard createdReader.startReading() else { throw JumpVideoServiceError.inspectionFailed }

            var times: [CMTime] = []
            while true {
                stage = "readSamples"
                guard let sample = output.copyNextSampleBuffer() else { break }
                guard !control.isCancelled else { throw CancellationError() }
                lastSampleCount = CMSampleBufferGetNumSamples(sample)
                lastSampleValid = CMSampleBufferIsValid(sample)
                lastSampleReady = CMSampleBufferDataIsReady(sample)
                switch Self.classifySampleBuffer(sample) {
                case .skipEmptyNonFrame:
                    continue
                case .invalid:
                    throw JumpVideoServiceError.inspectionFailed
                case .media(let sampleCount):
                    guard sampleCount <= maximumFrames - times.count else {
                        createdReader.cancelReading()
                        throw JumpVideoServiceError.tooManyFrames
                    }
                    for sampleIndex in 0..<sampleCount {
                        var timing = CMSampleTimingInfo()
                        stage = "sampleTiming"
                        let status = CMSampleBufferGetSampleTimingInfo(sample, at: sampleIndex, timingInfoOut: &timing)
                        guard status == noErr else {
                            timingOSStatus = status
                            throw JumpVideoServiceError.inspectionFailed
                        }
                        times.append(timing.presentationTimeStamp)
                    }
                }
            }
            guard !control.isCancelled else { throw CancellationError() }
            stage = "readerCompletion"
            guard createdReader.status == .completed else { throw JumpVideoServiceError.inspectionFailed }
            let frames = try indexedFrames(from: times)
            return JumpVideoManifest(video: video, frames: frames,
                                     width: Int(naturalSize.width), height: Int(naturalSize.height))
        } catch {
            if error is CancellationError || control.isCancelled { throw CancellationError() }
            if let diagnostic = error as? JumpVideoInspectionDiagnostic { throw diagnostic }
            if let serviceError = error as? JumpVideoServiceError, serviceError != .inspectionFailed {
                throw serviceError
            }
            let diagnosticError: Error
            if let serviceError = error as? JumpVideoServiceError, serviceError == .inspectionFailed,
               let readerError = reader?.error {
                diagnosticError = readerError
            } else {
                diagnosticError = error
            }
            let platformError = diagnosticError as NSError
            let safeDomains = ["AVFoundationErrorDomain", "NSOSStatusErrorDomain", "NSCocoaErrorDomain", "CoreMediaErrorDomain"]
            let safeDomain = safeDomains.contains(platformError.domain) ? platformError.domain : nil
            let status = reader?.status.rawValue
            reader?.cancelReading()
            throw JumpVideoInspectionDiagnostic(stage: stage, errorDomain: safeDomain,
                                                errorCode: safeDomain == nil ? nil : platformError.code,
                                                readerStatus: status, timingOSStatus: timingOSStatus,
                                                sampleCount: lastSampleCount, sampleValid: lastSampleValid,
                                                sampleReady: lastSampleReady)
        }
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
