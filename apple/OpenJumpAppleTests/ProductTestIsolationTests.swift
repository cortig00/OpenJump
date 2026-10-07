#if DEBUG && targetEnvironment(simulator)
import Foundation
import XCTest
@testable import OpenJumpApple

final class ProductTestIsolationTests: XCTestCase {
    private func arguments(_ id: UUID) -> [String] {
        ["OpenJumpApple", "-openjump-ui-test", "-openjump-ui-test-id", id.uuidString]
    }
    @MainActor private func clean(_ context: ProductUITestEnvironment.Context) {
        // Every caller owns its fresh UUID; never remove other contexts or normal data.
        UserDefaults(suiteName: context.suite)?.removePersistentDomain(forName: context.suite)
        try? FileManager.default.removeItem(at: context.root)
    }

    func testIdentifierAcceptsCanonicalUUIDWithoutFilesystemAccess() throws {
        let id = UUID()
        XCTAssertEqual(try ProductUITestEnvironment.identifier(arguments: arguments(id)), id)
        let lowercase = ["-openjump-ui-test", "-openjump-ui-test-id", id.uuidString.lowercased()]
        XCTAssertEqual(try ProductUITestEnvironment.identifier(arguments: lowercase), id)
    }

    func testIdentifierRejectsMissingDuplicateMalformedAndPathTokens() {
        let id = UUID()
        let invalid: [[String]] = [
            [], ["-openjump-ui-test"], ["-openjump-ui-test-id", id.uuidString],
            ["-openjump-ui-test", "-openjump-ui-test-id", "not-a-uuid"],
            ["-openjump-ui-test", "-openjump-ui-test-id", "../" + id.uuidString],
            ["-openjump-ui-test", "-openjump-ui-test", "-openjump-ui-test-id", id.uuidString],
            ["-openjump-ui-test", "-openjump-ui-test-id", id.uuidString, "-openjump-ui-test-id", id.uuidString]
        ]
        for input in invalid {
            XCTAssertThrowsError(try ProductUITestEnvironment.identifier(arguments: input)) { error in
                guard let setupError = error as? ProductUITestEnvironment.SetupError,
                      case .invalidArguments = setupError else {
                    return XCTFail("wrong rejection class: \(error)")
                }
            }
        }
    }

    @MainActor
    func testSameTokenRelaunchKeepsPreferencesGraphAndEditedNotes() async throws {
        let id = UUID()
        let first = try await ProductUITestEnvironment.prepare(arguments: arguments(id))
        defer { clean(first) }
        let store = try SQLiteStore(databaseURL: first.databaseURL)
        let originalPage = try await store.history()
        let original = try XCTUnwrap(originalPage.items.first)
        let originalRoster = try await store.athletes(includeArchived: true)
        XCTAssertEqual(originalRoster.count, 2)
        XCTAssertEqual(originalPage.items.count, 1)
        let graph = try await store.temporalAnalysis(measurementID: original.id)
        XCTAssertEqual(try XCTUnwrap(graph).events.map(\.ptsUs), [100_000, 400_000, 900_000])
        let height = try XCTUnwrap(original.metrics.first { $0.key == "HEIGHT_CM" })
        XCTAssertEqual(height.value, 30.64578125, accuracy: 1e-10)
        first.preferences.theme = .dark
        first.preferences.selectPreset(.unitedStates)
        try await store.updateNotes(id: original.id, notes: "UI persisted edit")

        let second = try await ProductUITestEnvironment.prepare(arguments: arguments(id))
        XCTAssertEqual(second.root, first.root)
        XCTAssertEqual(second.suite, first.suite)
        XCTAssertEqual(second.preferences.theme, .dark)
        XCTAssertEqual(second.preferences.units, .unitedStates)
        let reopened = try SQLiteStore(databaseURL: second.databaseURL)
        let retainedPage = try await reopened.history()
        let retainedRoster = try await reopened.athletes(includeArchived: true)
        XCTAssertEqual(retainedRoster.map(\.id), originalRoster.map(\.id))
        XCTAssertEqual(retainedPage.items.count, 1)
        let retained = try XCTUnwrap(retainedPage.items.first)
        XCTAssertEqual(retained.id, original.id)
        XCTAssertEqual(retained.sessionKey, original.sessionKey)
        XCTAssertEqual(retained.notes, "UI persisted edit")
        let retainedGraph = try await reopened.temporalAnalysis(measurementID: original.id)
        XCTAssertEqual(retainedGraph, graph)
    }

    @MainActor
    func testDifferentTokensAndInjectedAppStateStayIsolated() async throws {
        let first = try await ProductUITestEnvironment.prepare(arguments: arguments(UUID()))
        defer { clean(first) }
        let second = try await ProductUITestEnvironment.prepare(arguments: arguments(UUID()))
        defer { clean(second) }
        XCTAssertNotEqual(first.databaseURL, second.databaseURL)
        XCTAssertNotEqual(first.suite, second.suite)
        first.preferences.theme = .dark
        XCTAssertEqual(second.preferences.theme, .light)
        let firstStore = try SQLiteStore(databaseURL: first.databaseURL)
        _ = try await firstStore.createAthlete(name: "UI Test first-context only")
        let app = AppState(preferences: second.preferences, databaseURL: second.databaseURL)
        XCTAssertTrue(app.preferences === second.preferences)
        await app.load()
        XCTAssertNil(app.loadError)
        XCTAssertNotNil(app.store)
        XCTAssertEqual(app.athletes.count, 2)
        XCTAssertFalse(app.athletes.contains { $0.name == "UI Test first-context only" })
        XCTAssertTrue(app.athletes.allSatisfy { $0.name.hasPrefix("UI Test") })
    }

    @MainActor
    func testInvalidLaunchIsRejectedBeforeCreatingItsRoot() async throws {
        let id = UUID()
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("OpenJumpUITest-" + id.uuidString)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
        do {
            _ = try await ProductUITestEnvironment.prepare(arguments: ["-openjump-ui-test", "-openjump-ui-test-id", "../" + id.uuidString])
            XCTFail("invalid launch must never fall back to production")
        } catch {
            guard let setupError = error as? ProductUITestEnvironment.SetupError,
                  case .invalidArguments = setupError else {
                return XCTFail("wrong rejection class: \(error)")
            }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    @MainActor
    func testRedirectedRootIsRejectedWithoutChangingItsTarget() async throws {
        let id = UUID()
        let manager = FileManager.default
        let parent = manager.temporaryDirectory.resolvingSymlinksInPath()
        let root = parent.appendingPathComponent("OpenJumpUITest-" + id.uuidString)
        let outside = parent.appendingPathComponent("OpenJumpUITest-owned-target-" + id.uuidString)
        try manager.createDirectory(at: outside, withIntermediateDirectories: false)
        defer { try? manager.removeItem(at: outside); try? manager.removeItem(at: root) }
        let sentinel = outside.appendingPathComponent("sentinel.txt")
        let bytes = Data("test-owned sentinel".utf8)
        try bytes.write(to: sentinel)
        try manager.createSymbolicLink(at: root, withDestinationURL: outside)
        do {
            _ = try await ProductUITestEnvironment.prepare(arguments: arguments(id))
            XCTFail("redirected root must not be opened")
        } catch {
            guard let setupError = error as? ProductUITestEnvironment.SetupError,
                  case .unsafeDirectory = setupError else {
                return XCTFail("wrong rejection class: \(error)")
            }
        }
        XCTAssertEqual(try Data(contentsOf: sentinel), bytes)
        XCTAssertFalse(manager.fileExists(atPath: outside.appendingPathComponent("openjump-ui-test.sqlite").path))
    }
}
#endif
