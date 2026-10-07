import AVFoundation
import CoreVideo
import CoreGraphics
import Foundation
@testable import OpenJumpApple

/// Test-only owned H.264 clips; all files live in a unique temporary directory and are removed by the caller.
struct MediaFixtureFactory {
    enum Cadence {
        case constant
        case variable
        case temporalReference

        var ticks: [Int64] {
            switch self {
            case .constant: return [0, 20, 40, 60, 80, 100]
            case .variable: return [0, 10, 30, 50, 90, 120]
            case .temporalReference: return [0, 60, 120, 240, 420, 480]
            }
        }

        var expectedMicroseconds: [Int64] {
            switch self {
            case .constant: return [0, 33_333, 66_667, 100_000, 133_333, 166_667]
            case .variable: return [0, 16_667, 50_000, 83_333, 150_000, 200_000]
            case .temporalReference: return [0, 100_000, 200_000, 400_000, 700_000, 800_000]
            }
        }
    }

    static let width = 320
    static let height = 240
    static let expectedIDs = [1, 2, 3, 4, 5, 6]

    static func make(cadence: Cadence) async throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("OpenJumpMedia-\(UUID().uuidString)", isDirectory: true)
        var completed = false
        defer { if !completed { try? FileManager.default.removeItem(at: directory) } }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("owned-fixture.mov")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoMaxKeyFrameIntervalKey: 1, AVVideoAllowFrameReorderingKey: false]
        ])
        input.expectsMediaDataInRealTime = false
        input.mediaTimeScale = 600
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
        ])
        writer.movieTimeScale = 600
        guard writer.canAdd(input) else { throw FixtureError.writerSetup }
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? FixtureError.writerSetup }
        var writerCompleted = false
        defer { if !writerCompleted { writer.cancelWriting() } }
        writer.startSession(atSourceTime: .zero)
        guard let pool = adaptor.pixelBufferPool else { throw FixtureError.writerSetup }
        for (index, tick) in cadence.ticks.enumerated() {
            let readinessDeadline = DispatchTime.now().uptimeNanoseconds + 10_000_000_000
            while !input.isReadyForMoreMediaData {
                try Task.checkCancellation()
                guard writer.status == .writing, DispatchTime.now().uptimeNanoseconds < readinessDeadline else {
                    throw writer.error ?? FixtureError.writerSetup
                }
                try await Task.sleep(nanoseconds: 2_000_000)
            }
            try Task.checkCancellation()
            var optionalBuffer: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &optionalBuffer) == kCVReturnSuccess,
                  let buffer = optionalBuffer else { throw FixtureError.writerSetup }
            try draw(frameID: expectedIDs[index], into: buffer)
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: tick, timescale: 600)) else {
                throw writer.error ?? FixtureError.writerSetup
            }
        }
        writer.endSession(atSourceTime: CMTime(value: cadence.ticks[cadence.ticks.count - 1] + 20, timescale: 600))
        input.markAsFinished()
        try await CallbackDeadline.run(timeout: .seconds(10), cancel: { writer.cancelWriting() }) { (complete: @escaping (Result<Void, Error>) -> Void) in
            writer.finishWriting {
                if writer.status == .completed { complete(.success(())) }
                else { complete(.failure(writer.error ?? FixtureError.writerSetup)) }
            }
        }
        guard writer.status == .completed else { throw writer.error ?? FixtureError.writerSetup }
        writerCompleted = true
        completed = true
        return url
    }

    private static func draw(frameID: Int, into buffer: CVPixelBuffer) throws {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer),
              let context = CGContext(data: base, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { throw FixtureError.writerSetup }
        context.setFillColor(gray: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        // Three fixed broad cells centered on the middle row encode six one-based frame IDs.
        let cellWidth = 40
        let startX = 40
        for bit in 0..<3 {
            let isWhite = (frameID & (1 << bit)) != 0
            context.setFillColor(gray: isWhite ? 1 : 0, alpha: 1)
            context.fill(CGRect(x: startX + bit * cellWidth, y: 90, width: cellWidth, height: 60))
        }
    }

    enum FixtureError: Error { case writerSetup }
}
