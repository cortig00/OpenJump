import CoreTransferable
import Foundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

final class ImportedJumpVideo: @unchecked Sendable {
    let id: UUID
    let url: URL
    let source: JumpVideoSource

    private let ownedDirectory: URL
    private let lock = NSLock()
    private var disposed = false

    fileprivate init(id: UUID, url: URL, source: JumpVideoSource, ownedDirectory: URL) {
        self.id = id
        self.url = url
        self.source = source
        self.ownedDirectory = ownedDirectory
    }

    func dispose() {
        lock.lock()
        guard !disposed else {
            lock.unlock()
            return
        }
        disposed = true
        try? FileManager.default.removeItem(at: ownedDirectory)
        lock.unlock()
    }

    deinit { dispose() }
}

enum JumpVideoImportError: Error, Equatable {
    case unsupportedURL
    case invalidFile
    case fileTooLarge
    case unableToStage
    case unableToCopy
    case photosItemUnavailable
}

private final class CoordinatedCopyControl: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var coordinator: NSFileCoordinator?
    private var nativeCancel: (() -> Void)?
    private var pendingResult: Result<ImportedJumpVideo, Error>?
    private var ownsAdmission = false

    func reserveAdmission() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        Self.admissionLock.lock()
        defer { Self.admissionLock.unlock() }
        guard !Self.hasActiveCopy else { return false }
        Self.hasActiveCopy = true
        ownsAdmission = true
        return true
    }

    private static let admissionLock = NSLock()
    private static var hasActiveCopy = false

    func releaseAdmission() {
        lock.lock()
        let release = ownsAdmission
        ownsAdmission = false
        lock.unlock()
        guard release else { return }
        Self.admissionLock.lock()
        Self.hasActiveCopy = false
        Self.admissionLock.unlock()
    }

    func installCoordinator(_ coordinator: NSFileCoordinator) {
        lock.lock()
        self.coordinator = coordinator
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { coordinator.cancel() }
    }

    func installNativeCancel(_ cancel: @escaping () -> Void) {
        lock.lock()
        nativeCancel = cancel
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { cancel() }
    }

    func cancel() {
        lock.lock()
        let shouldCancel = !cancelled
        cancelled = true
        let coordinator = self.coordinator
        let nativeCancel = self.nativeCancel
        let pendingResult = self.pendingResult
        self.pendingResult = nil
        lock.unlock()
        guard shouldCancel else { return }
        coordinator?.cancel()
        nativeCancel?()
        if case .success(let imported) = pendingResult { imported.dispose() }
    }

    func checkCancellation() throws {
        lock.lock()
        let cancelled = self.cancelled
        lock.unlock()
        if cancelled { throw CancellationError() }
    }

    func publish(_ result: Result<ImportedJumpVideo, Error>, completion: () -> Void) {
        lock.lock()
        guard !cancelled else {
            lock.unlock()
            if case .success(let imported) = result { imported.dispose() }
            return
        }
        pendingResult = result
        completion()
        lock.unlock()
    }

    func takePublishedResult() throws -> ImportedJumpVideo {
        lock.lock()
        defer { lock.unlock() }
        guard !cancelled, let result = pendingResult else { throw CancellationError() }
        pendingResult = nil
        return try result.get()
    }
}

enum JumpVideoImporter {
    static let maximumFileSize: UInt64 = 512 * 1024 * 1024
    private static let stagingFolderName = "OpenJumpImportedVideos"
    private static let copyChunkSize = 1024 * 1024
    static var coordinationDriverForTesting: ((URL, @escaping (URL) -> Void,
                                                @escaping (@escaping () -> Void) -> Void) -> Void)?
    static var workerExitForTesting: (() -> Void)?

    static func importFile(_ url: URL) async throws -> ImportedJumpVideo {
        try await importFile(url, source: .files)
    }

    static func importPhotos(_ item: PhotosPickerItem) async throws -> ImportedJumpVideo {
        try Task.checkCancellation()
        guard let transfer = try await item.loadTransferable(type: PhotosMovieTransfer.self) else {
            throw JumpVideoImportError.photosItemUnavailable
        }
        do {
            try Task.checkCancellation()
            return transfer.video
        } catch {
            transfer.video.dispose()
            throw error
        }
    }

    fileprivate static func importTransferredPhotoFile(_ url: URL) async throws -> ImportedJumpVideo {
        try await importFile(url, source: .photos)
    }

    /// Transactional captured-file Use (C2a): exclusive fresh borrower handoff.
    /// Truthful `.camera` source via the SAME coordinated copy machinery
    /// (Caches OpenJumpImportedVideos/<uuid>/video.<safe-ext>, backup-excluded,
    /// 1MiB chunks, 512MiB limit, 60s deadline, single-flight admission, test
    /// hooks). The incoming borrower ownership transfers to the detached copy
    /// worker: the worker retains it strongly until real worker-exit/ownership
    /// transfer. Cancellation/deadline awaiter resume does NOT release the pin
    /// and never deletes the capture raw under a blocked worker. The caller
    /// must NOT explicitly release the same handle after handoff (preview
    /// owns a DIFFERENT fresh borrower). An externally released handle fails
    /// closed without adopting an unowned URL. No second copy pipeline.
    static func importCapturedFile(_ borrower: JumpVideoCaptureBorrower) async throws -> ImportedJumpVideo {
        guard let sourceURL = borrower.fileURL else { throw JumpVideoImportError.invalidFile }
        guard sourceURL.isFileURL else { throw JumpVideoImportError.unsupportedURL }
        try Task.checkCancellation()
        return try await importFile(sourceURL, source: .camera, retainedBorrower: borrower)
    }

    private static func importFile(_ url: URL, source: JumpVideoSource, retainedBorrower: JumpVideoCaptureBorrower? = nil) async throws -> ImportedJumpVideo {
        guard url.isFileURL else { throw JumpVideoImportError.unsupportedURL }
        try Task.checkCancellation()
        // No outer defer releasing retainedBorrower: the SAME instance is
        // shared with the detached worker below. An explicit outer release
        // would clear the pin while the worker still reads. The worker holds
        // it until real exit; no-worker paths (pre-cancel/invalid/admission
        // refused/deadline-before-launch) simply drop this param without leak.
        if let retained = retainedBorrower, retained.fileURL == nil {
            throw JumpVideoImportError.invalidFile
        }

        let control = CoordinatedCopyControl()
        do {
            try await CallbackDeadline.run(timeout: .seconds(60), cancel: { control.cancel() }) { (complete: @escaping (Result<Void, Error>) -> Void) in
                guard control.reserveAdmission() else {
                    complete(.failure(JumpVideoImportError.unableToCopy))
                    return
                }
                Task.detached(priority: .userInitiated) { [retainedBorrower] in
                    defer {
                        control.releaseAdmission()
                        Self.workerExitForTesting?()
                        // retainedBorrower (when non-nil) stays alive until HERE:
                        // real native exit/ownership-transfer, not merely until
                        // cancel-continuation/deadline resume.
                        _ = retainedBorrower
                    }
                    do {
                        // Fail closed if the transferred handle was externally released
                        // before the worker read it; never adopt an unowned URL.
                        if let retained = retainedBorrower {
                            guard let liveURL = retained.fileURL, liveURL == url else {
                                throw JumpVideoImportError.invalidFile
                            }
                        }
                        let imported = try Self.copyToOwnedContainer(from: url, source: source, control: control)
                        try control.checkCancellation()
                        control.publish(.success(imported)) { complete(.success(())) }
                    } catch {
                        control.publish(.failure(error)) { complete(.success(())) }
                    }
                }
            }
            return try control.takePublishedResult()
        } catch CallbackDeadline.DeadlineError.timedOut {
            throw JumpVideoImportError.unableToCopy
        }
    }

    private static func copyToOwnedContainer(from sourceURL: URL, source: JumpVideoSource,
                                             control: CoordinatedCopyControl) throws -> ImportedJumpVideo {
        try control.checkCancellation()
        let id = UUID()
        let cacheURL: URL
        do {
            cacheURL = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                   appropriateFor: nil, create: true)
        } catch {
            throw JumpVideoImportError.unableToStage
        }
        let stagingRoot = cacheURL.appendingPathComponent(stagingFolderName, isDirectory: true)
        let ownedDirectory = stagingRoot.appendingPathComponent(id.uuidString.lowercased(), isDirectory: true)
        var directoryCreated = false
        defer {
            if directoryCreated { try? FileManager.default.removeItem(at: ownedDirectory) }
        }
        do {
            try FileManager.default.createDirectory(at: ownedDirectory, withIntermediateDirectories: true)
            directoryCreated = true
            try setExcludedFromBackup(at: stagingRoot)
            try setExcludedFromBackup(at: ownedDirectory)
        } catch {
            throw JumpVideoImportError.unableToStage
        }

        let destination = ownedDirectory.appendingPathComponent("video.\(safeExtension(for: sourceURL))")
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessing { sourceURL.stopAccessingSecurityScopedResource() } }

        do {
            try coordinateAndCopy(from: sourceURL, to: destination, control: control)
            try setExcludedFromBackup(at: destination)
            try control.checkCancellation()
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as JumpVideoImportError {
            throw error
        } catch {
            throw JumpVideoImportError.unableToCopy
        }

        let imported = ImportedJumpVideo(id: id, url: destination, source: source, ownedDirectory: ownedDirectory)
        directoryCreated = false
        return imported
    }

    private static func coordinateAndCopy(from sourceURL: URL, to destinationURL: URL,
                                          control: CoordinatedCopyControl) throws {
        try control.checkCancellation()
        let coordinator = NSFileCoordinator(filePresenter: nil)
        control.installCoordinator(coordinator)
        let result = CoordinationResult()
        var coordinationError: NSError?
        if let testDriver = coordinationDriverForTesting {
            testDriver(sourceURL, { coordinatedURL in
                do {
                    try control.checkCancellation()
                    try copyContents(from: coordinatedURL, to: destinationURL, control: control)
                    result.store(.success(destinationURL))
                } catch { result.store(.failure(error)) }
            }, { control.installNativeCancel($0) })
        } else {
            coordinator.coordinate(readingItemAt: sourceURL, options: [], error: &coordinationError) { coordinatedURL in
                do {
                    try control.checkCancellation()
                    try copyContents(from: coordinatedURL, to: destinationURL, control: control)
                    result.store(.success(destinationURL))
                } catch { result.store(.failure(error)) }
            }
        }
        try control.checkCancellation()
        if let coordinationError { throw coordinationError }
        guard let coordinatedResult = result.value else { throw JumpVideoImportError.unableToCopy }
        try coordinatedResult.get()
    }

    private static func copyContents(from sourceURL: URL, to destinationURL: URL,
                                     control: CoordinatedCopyControl) throws {
        let attributes: [FileAttributeKey: Any]
        do {
            attributes = try FileManager.default.attributesOfItem(atPath: sourceURL.path)
        } catch {
            throw JumpVideoImportError.invalidFile
        }
        guard attributes[.type] as? FileAttributeType == .typeRegular,
              let size = (attributes[.size] as? NSNumber)?.uint64Value,
              size > 0 else {
            throw JumpVideoImportError.invalidFile
        }
        guard size <= maximumFileSize else { throw JumpVideoImportError.fileTooLarge }
        try control.checkCancellation()
        guard FileManager.default.createFile(atPath: destinationURL.path, contents: nil) else {
            throw JumpVideoImportError.unableToCopy
        }

        let input: FileHandle
        do {
            input = try FileHandle(forReadingFrom: sourceURL)
        } catch {
            throw JumpVideoImportError.unableToCopy
        }
        defer { try? input.close() }

        let output: FileHandle
        do {
            output = try FileHandle(forWritingTo: destinationURL)
        } catch {
            throw JumpVideoImportError.unableToCopy
        }
        defer { try? output.close() }

        var copied: UInt64 = 0
        while true {
            try control.checkCancellation()
            guard let chunk = try input.read(upToCount: copyChunkSize), !chunk.isEmpty else { break }
            guard UInt64(chunk.count) <= maximumFileSize - copied else {
                throw JumpVideoImportError.fileTooLarge
            }
            try output.write(contentsOf: chunk)
            copied += UInt64(chunk.count)
        }
        try control.checkCancellation()
        guard copied == size else { throw JumpVideoImportError.unableToCopy }
    }

    private static func setExcludedFromBackup(at url: URL) throws {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
    }

    private static func safeExtension(for url: URL) -> String {
        let pathExtension = url.pathExtension.lowercased()
        let permitted = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789")
        guard !pathExtension.isEmpty, pathExtension.count <= 12,
              pathExtension.unicodeScalars.allSatisfy({ permitted.contains($0) }) else { return "mov" }
        return pathExtension
    }

    private final class CoordinationResult: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: Result<URL, Error>?

        func store(_ result: Result<URL, Error>) {
            lock.lock()
            stored = result
            lock.unlock()
        }

        var value: Result<URL, Error>? {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
    }
}

private struct PhotosMovieTransfer: Transferable {
    let video: ImportedJumpVideo

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            PhotosMovieTransfer(video: try await JumpVideoImporter.importTransferredPhotoFile(received.file))
        }
    }
}
