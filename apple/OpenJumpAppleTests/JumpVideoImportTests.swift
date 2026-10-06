import Foundation
import XCTest
@testable import OpenJumpApple

final class JumpVideoImportTests: XCTestCase {
    func testImportCopiesVideoIntoUniqueOwnedContainerAndDisposePreservesOriginal() async throws {
        let (original, externalDirectory) = try makeSource(contents: Data("original movie bytes".utf8))
        defer { try? FileManager.default.removeItem(at: externalDirectory) }

        let imported = try await JumpVideoImporter.importFile(original)
        let secondImport = try await JumpVideoImporter.importFile(original)
        defer {
            imported.dispose()
            secondImport.dispose()
        }

        XCTAssertEqual(imported.source, .files)
        XCTAssertNotEqual(imported.id, secondImport.id)
        XCTAssertNotEqual(imported.url, original)
        XCTAssertNotEqual(imported.url.deletingLastPathComponent(), secondImport.url.deletingLastPathComponent())
        XCTAssertEqual(try Data(contentsOf: imported.url), try Data(contentsOf: original))
        XCTAssertEqual(try Data(contentsOf: secondImport.url), try Data(contentsOf: original))
        XCTAssertEqual(try imported.url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        XCTAssertEqual(try imported.url.deletingLastPathComponent()
            .resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)

        imported.dispose()
        imported.dispose()
        XCTAssertFalse(FileManager.default.fileExists(atPath: imported.url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        XCTAssertEqual(try Data(contentsOf: original), Data("original movie bytes".utf8))
    }

    func testDeinitializationReleasesOwnedCopyOnly() async throws {
        let (original, externalDirectory) = try makeSource(contents: Data("lease".utf8))
        defer { try? FileManager.default.removeItem(at: externalDirectory) }
        let copiedURL: URL
        do {
            let imported = try await JumpVideoImporter.importFile(original)
            copiedURL = imported.url
        }

        XCTAssertFalse(FileManager.default.fileExists(atPath: copiedURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        XCTAssertEqual(try Data(contentsOf: original), Data("lease".utf8))
    }

    func testOversizedVideoIsRejectedWithoutChangingOriginal() async throws {
        let (original, externalDirectory) = try makeSource(contents: Data([0]))
        defer { try? FileManager.default.removeItem(at: externalDirectory) }
        let handle = try FileHandle(forWritingTo: original)
        try handle.truncate(atOffset: JumpVideoImporter.maximumFileSize + 1)
        try handle.close()
        let stagingRoot = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true)
            .appendingPathComponent("OpenJumpImportedVideos", isDirectory: true)
        let before = try directoryEntries(at: stagingRoot)

        do {
            _ = try await JumpVideoImporter.importFile(original)
            XCTFail("Files larger than the import limit must be rejected")
        } catch let error as JumpVideoImportError {
            XCTAssertEqual(error, .fileTooLarge)
        } catch {
            XCTFail("Unexpected import error: \(error)")
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: original.path)[.size] as? NSNumber,
                       NSNumber(value: JumpVideoImporter.maximumFileSize + 1))
        XCTAssertEqual(try directoryEntries(at: stagingRoot), before)
    }

    func testPreCancelledImportDoesNotStartNativeCoordination() async throws {
        let (original, externalDirectory) = try makeSource(contents: Data("source".utf8))
        defer { try? FileManager.default.removeItem(at: externalDirectory) }
        JumpVideoImporter.coordinationDriverForTesting = { _, _, _ in
            XCTFail("pre-cancelled import must not start coordination")
        }
        defer { JumpVideoImporter.coordinationDriverForTesting = nil }

        let task = Task<ImportedJumpVideo, Error> {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await JumpVideoImporter.importFile(original)
        }
        do {
            _ = try await task.value
            XCTFail("pre-cancelled import must fail")
        } catch is CancellationError { }
    }

    func testCancelWhileCoordinatorBlocksReturnsAndRetainsResourcesUntilWorkerExit() async throws {
        let (original, externalDirectory) = try makeSource(contents: Data("source".utf8))
        defer { try? FileManager.default.removeItem(at: externalDirectory) }
        let stagingRoot = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true)
            .appendingPathComponent("OpenJumpImportedVideos", isDirectory: true)
        let before = try directoryEntries(at: stagingRoot)
        let enteredCoordinator = expectation(description: "native coordination entered")
        let cancellationRequested = expectation(description: "native cancellation requested")
        let workerExited = expectation(description: "blocked worker actually exited and released admission")
        let releaseCoordinator = DispatchSemaphore(value: 0)
        JumpVideoImporter.workerExitForTesting = { workerExited.fulfill() }
        JumpVideoImporter.coordinationDriverForTesting = { url, accessor, registerCancellation in
            registerCancellation { cancellationRequested.fulfill() }
            enteredCoordinator.fulfill()
            releaseCoordinator.wait()
            accessor(url)
        }
        defer {
            releaseCoordinator.signal()
            JumpVideoImporter.coordinationDriverForTesting = nil
            JumpVideoImporter.workerExitForTesting = nil
        }

        let importTask = Task { try await JumpVideoImporter.importFile(original) }
        await fulfillment(of: [enteredCoordinator], timeout: 2)
        importTask.cancel()
        do {
            _ = try await importTask.value
            XCTFail("caller cancellation must return without waiting for blocked coordination")
        } catch is CancellationError { }
        await fulfillment(of: [cancellationRequested], timeout: 1)

        XCTAssertEqual(try directoryEntries(at: stagingRoot).count, before.count + 1,
                       "the live worker's owned directory must remain until native code exits")
        do {
            _ = try await JumpVideoImporter.importFile(original)
            XCTFail("a second native worker must not be admitted while the first is still blocked")
        } catch let error as JumpVideoImportError {
            XCTAssertEqual(error, .unableToCopy)
        }

        releaseCoordinator.signal()
        await fulfillment(of: [workerExited], timeout: 2)
        XCTAssertEqual(try directoryEntries(at: stagingRoot), before)
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
    }

    private func makeSource(contents: Data) throws -> (URL, URL) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("OpenJumpImportTest-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        let url = directory.appendingPathComponent("source movie.mov")
        try contents.write(to: url)
        return (url, directory)
    }

    private func directoryEntries(at url: URL) throws -> Set<String> {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        return Set(try FileManager.default.contentsOfDirectory(atPath: url.path))
    }
}
