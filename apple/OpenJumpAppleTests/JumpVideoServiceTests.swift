import AVFoundation
import CoreGraphics
import XCTest
@testable import OpenJumpApple

final class JumpVideoServiceTests: XCTestCase {
    func testIndexedCompressedPresentationTimesSortKeepNonzeroOriginAndRejectInvalidSequences() throws {
        let frames = try JumpVideoService.indexedFrames(from: [
            CMTime(value: 11, timescale: 2), CMTime(value: 5, timescale: 1), CMTime(value: 21, timescale: 4)
        ])
        XCTAssertEqual(frames.map(\.index), [0, 1, 2])
        XCTAssertEqual(frames.map(\.ptsUs), [5_000_000, 5_250_000, 5_500_000])
        XCTAssertEqual(frames.first?.time.value, 5)
        XCTAssertEqual(frames.first?.time.timescale, 1)

        XCTAssertThrowsError(try JumpVideoService.indexedFrames(from: [CMTime(value: 1, timescale: 2), CMTime(value: 2, timescale: 4)])) { error in
            XCTAssertEqual(error as? JumpVideoServiceError, .duplicateTimestamp)
        }
        XCTAssertThrowsError(try JumpVideoService.indexedFrames(from: [CMTime(value: 1, timescale: 4_000_000), CMTime(value: 1, timescale: 3_000_000)])) { error in
            XCTAssertEqual(error as? JumpVideoServiceError, .timestampCollision)
        }
        XCTAssertThrowsError(try JumpVideoService.indexedFrames(from: [CMTime(value: -1, timescale: 600)])) { error in
            XCTAssertEqual(error as? JumpVideoServiceError, .invalidTimestamp)
        }
        XCTAssertThrowsError(try JumpVideoService.indexedFrames(from: [CMTime(value: 0, timescale: 1)])) { error in
            XCTAssertEqual(error as? JumpVideoServiceError, .insufficientFrames)
        }
        XCTAssertThrowsError(try JumpVideoService.indexedFrames(from: (0...250_000).map { CMTime(value: Int64($0), timescale: 30) })) { error in
            XCTAssertEqual(error as? JumpVideoServiceError, .tooManyFrames)
        }
    }

    func testImportedVariableCadenceStillIsBoundToItsCompressedSourceIndexAndPTS() async throws {
        let sourceURL = try await MediaFixtureFactory.make(cadence: .variable)
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }

        let video = try await JumpVideoImporter.importFile(sourceURL)
        defer { video.dispose() }
        XCTAssertEqual(try Data(contentsOf: sourceURL), try Data(contentsOf: video.url), "Imported fixture must be an exact byte-for-byte copy")
        let service = JumpVideoService()
        let manifest = try await service.inspect(video)
        XCTAssertEqual(manifest.sourceID, video.id)
        XCTAssertEqual(manifest.frames.map(\.ptsUs), [0, 16_667, 50_000, 83_333, 150_000, 200_000])
        do {
            _ = try await service.frame(manifest: manifest, index: -1)
            XCTFail("Negative compressed-frame index must be rejected")
        } catch JumpVideoServiceError.frameIndexOutOfRange { }
        do {
            _ = try await service.frame(manifest: manifest, index: manifest.frames.count)
            XCTFail("Index after the final compressed frame must be rejected")
        } catch JumpVideoServiceError.frameIndexOutOfRange { }

        for (index, expectedID) in MediaFixtureFactory.expectedIDs.enumerated() {
            let presented = try await service.frame(manifest: manifest, index: index)
            XCTAssertEqual(presented.sourceID, video.id)
            XCTAssertEqual(presented.index, index)
            XCTAssertEqual(presented.ptsUs, manifest.frames[index].ptsUs)
            XCTAssertEqual(try decodedID(presented.image), expectedID, "pixels for compressed source PTS at index \(index)")
        }
    }

    private func decodedID(_ image: CGImage) throws -> Int {
        var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
        return try pixels.withUnsafeMutableBytes { storage in
            let context = try XCTUnwrap(CGContext(data: storage.baseAddress, width: image.width, height: image.height,
                                                  bitsPerComponent: 8, bytesPerRow: image.width * 4,
                                                  space: CGColorSpaceCreateDeviceRGB(),
                                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            var result = 0
            for bit in 0..<3 {
                let x = Int(Double(40 + bit * 40 + 20) * Double(image.width) / Double(MediaFixtureFactory.width))
                let y = Int(Double(120) * Double(image.height) / Double(MediaFixtureFactory.height))
                let offset = (y * image.width + x) * 4
                let brightness = (Int(storage[offset]) + Int(storage[offset + 1]) + Int(storage[offset + 2])) / 3
                XCTAssertTrue(brightness < 55 || brightness > 200, "uncertain lossy sample at bit \(bit): \(brightness)")
                if brightness > 200 { result |= (1 << bit) }
            }
            return result
        }
    }
}
