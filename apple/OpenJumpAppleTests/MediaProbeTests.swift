import AVFoundation
import CoreGraphics
import XCTest
@testable import OpenJumpApple

final class MediaProbeTests: XCTestCase {
    func testCanonicalTimeValidationSortsAndRejectsDuplicatesCollisionsAndInvalidValues() throws {
        let unsorted = [CMTime(value: 2, timescale: 30), CMTime(value: 0, timescale: 1), CMTime(value: 1, timescale: 30)]
        let frames = try MediaProbe.validatedFrames(unsorted)
        XCTAssertTrue(MediaProbe.matchesExactTime(CMTime(value: 2, timescale: 60), requested: CMTime(value: 1, timescale: 30)))
        XCTAssertFalse(MediaProbe.matchesExactTime(CMTime(value: 2, timescale: 30), requested: CMTime(value: 3, timescale: 30)))
        XCTAssertEqual(frames.map(\.presentationTimeUs), [0, 33_333, 66_667])
        XCTAssertEqual(frames.map(\.ordinal), [0, 1, 2])
        XCTAssertThrowsError(try MediaProbe.validatedFrames([.zero, .zero]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([CMTime(value: 1, timescale: 4_000_000), CMTime(value: 1, timescale: 3_000_000)]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([.invalid]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([CMTime(value: 1, timescale: 0)]))
        let nonzeroEpoch = CMTime(value: 1, timescale: 1, flags: .valid, epoch: 1)
        XCTAssertThrowsError(try MediaProbe.validatedFrames([nonzeroEpoch])) { error in
            XCTAssertEqual(error as? MediaProbe.ProbeError, MediaProbe.ProbeError.timeOutOfRange)
        }
        XCTAssertThrowsError(try MediaProbe.validatedFrames([CMTime(value: -1, timescale: 600)]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([.indefinite]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([.positiveInfinity]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([.negativeInfinity]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([CMTime(value: Int64.max, timescale: 1)]))
        XCTAssertThrowsError(try MediaProbe.validatedFrames([]))
        XCTAssertEqual(try MediaProbe.validatedFrames((0..<256).map { CMTime(value: Int64($0 * 20), timescale: 600) }).count, 256)
        XCTAssertThrowsError(try MediaProbe.validatedFrames((0..<257).map { CMTime(value: Int64($0 * 20), timescale: 600) }))
        XCTAssertFalse(MediaProbe.matchesExactTime(.invalid, requested: .zero))
    }

    func testRejectsRequestsOutsideExactIndexedSourceTimes() async throws {
        let (url, directory) = try await makeFixture(.variable)
        defer { try? FileManager.default.removeItem(at: directory) }
        let probe = MediaProbe()
        let manifest = try await probe.inspect(url: url)
        XCTAssertEqual(manifest.frames.count, 6)
        do {
            _ = try await probe.image(url: url, manifest: manifest, ordinal: -1)
            XCTFail("Negative ordinal must be rejected")
        } catch MediaProbe.ProbeError.nonExactRequest { }
        do {
            _ = try await probe.image(url: url, manifest: manifest, ordinal: manifest.frames.count)
            XCTFail("Ordinal after final frame must be rejected")
        } catch MediaProbe.ProbeError.nonExactRequest { }
        do {
            _ = try await probe.image(url: url.appendingPathExtension("different"), manifest: manifest, ordinal: 0)
            XCTFail("Manifest must remain bound to its normalized source URL")
        } catch MediaProbe.ProbeError.sourceMismatch { }
        for invalid in [CMTime.invalid, .indefinite, .positiveInfinity] {
            do {
                _ = try await probe.image(url: url, manifest: manifest, at: invalid)
                XCTFail("Nonnumeric request must be rejected before decoding")
            } catch MediaProbe.ProbeError.nonExactRequest { }
        }
        XCTAssertThrowsError(try MediaProbe.validatedFrames([CMTime(value: -1, timescale: 600)]))
        do {
            _ = try await probe.image(url: url, manifest: manifest, at: CMTime(value: 20, timescale: 600))
            XCTFail("Between-sample time must not be treated as an exact match")
        } catch MediaProbe.ProbeError.nonExactRequest { }
        do {
            _ = try await probe.image(url: url, manifest: manifest, at: CMTime(value: -1, timescale: 600))
            XCTFail("Outside-index time must be rejected")
        } catch MediaProbe.ProbeError.nonExactRequest { }
    }

    func testConstantCadenceWriterReaderAndExactImagesMatchIndependentOracle() async throws {
        try await verify(cadence: .constant, requireVariableGap: false)
    }

    func testVariableCadenceWriterReaderAndExactImagesMatchIndependentOracle() async throws {
        try await verify(cadence: .variable, requireVariableGap: true)
    }

    private func verify(cadence: MediaFixtureFactory.Cadence, requireVariableGap: Bool) async throws {
        let (url, directory) = try await makeFixture(cadence)
        defer { try? FileManager.default.removeItem(at: directory) }
        let probe = MediaProbe()
        let manifest = try await probe.inspect(url: url)
        let observed = manifest.frames.map(\.presentationTimeUs)
        XCTAssertEqual(observed, cadence.expectedMicroseconds)
        XCTAssertEqual(manifest.frames.map(\.ordinal), Array(0..<6))
        if requireVariableGap {
            let intervals = zip(observed, observed.dropFirst()).map { $1 - $0 }
            XCTAssertEqual(intervals, [16_667, 33_333, 33_333, 66_667, 50_000])
            XCTAssertNotEqual(Set(intervals).count, 1, "VFR fixture must not accidentally be CFR")
        }
        guard manifest.frames.count == cadence.ticks.count else {
            XCTFail("Expected \(cadence.ticks.count) indexed frames, got \(manifest.frames.count)")
            return
        }
        for (index, expectedID) in MediaFixtureFactory.expectedIDs.enumerated() {
            let frame = manifest.frames[index]
            let expectedSourceTime = CMTime(value: cadence.ticks[index], timescale: 600)
            XCTAssertEqual(CMTimeCompare(frame.sourceTime, expectedSourceTime), 0, "source PTS at index \(index)")
            let image = try await probe.image(url: url, manifest: manifest, ordinal: index)
            let actualID = try decodedID(image)
            XCTAssertEqual(actualID, expectedID, "decoded pixels for source PTS \(frame.sourceTime)")
            print("MediaProbe \(requireVariableGap ? "VFR" : "CFR") frame=\(index) pts=\(frame.sourceTime.value)/\(frame.sourceTime.timescale) ptsUs=\(observed[index]) decodedID=\(actualID)")
        }
        // The pixel oracle is meaningful: swapping expected identities must be detected.
        let firstImage = try await probe.image(url: url, manifest: manifest, ordinal: 0)
        XCTAssertNotEqual(try decodedID(firstImage), MediaFixtureFactory.expectedIDs[1])
    }

    func testCallbackDeadlineTimesOutAndIgnoresLateCallbackExactlyOnce() async throws {
        let canceled = expectation(description: "timeout cancels underlying operation")
        let lateCallback = expectation(description: "late callback is delivered after timeout")
        let lock = NSLock()
        var cancelCount = 0
        do {
            _ = try await CallbackDeadline.run(timeout: .milliseconds(25), cancel: {
                lock.lock(); cancelCount += 1; lock.unlock()
                canceled.fulfill()
            }) { (complete: @escaping (Result<Int, Error>) -> Void) in
                DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(75)) {
                    complete(.success(1))
                    lateCallback.fulfill()
                }
            }
            XCTFail("Never-on-time callback must time out")
        } catch CallbackDeadline.DeadlineError.timedOut { }
        await fulfillment(of: [canceled], timeout: 1)
        await fulfillment(of: [lateCallback], timeout: 1)
        lock.lock(); let observedCancelCount = cancelCount; lock.unlock()
        XCTAssertEqual(observedCancelCount, 1)
    }

    func testAlreadyCancelledCallbackDoesNotStartUnderlyingOperation() async throws {
        let task = Task<Int, Error> {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await CallbackDeadline.run(timeout: .milliseconds(100)) { complete in
                XCTFail("An already cancelled request must not start the underlying operation")
                complete(.success(1))
            }
        }
        do {
            _ = try await task.value
            XCTFail("Already cancelled callback request must throw")
        } catch is CancellationError { }
    }

    private func makeFixture(_ cadence: MediaFixtureFactory.Cadence) async throws -> (URL, URL) {
        let url = try await MediaFixtureFactory.make(cadence: cadence)
        return (url, url.deletingLastPathComponent())
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
