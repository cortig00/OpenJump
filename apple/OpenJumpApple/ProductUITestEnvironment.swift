#if DEBUG && targetEnvironment(simulator)
import Foundation
import SwiftUI

/// Deliberately absent from every physical-device build and from Release.
/// An explicit test launch never falls back to normal App Support or defaults.
enum ProductUITestEnvironment {
    enum SetupError: Error { case invalidArguments, unsafeDirectory, defaultsUnavailable }
    struct Context {
        let id: UUID
        let root: URL
        let databaseURL: URL
        let suite: String
        let preferences: AppPreferences
    }

    static func identifier(arguments: [String]) throws -> UUID {
        guard arguments.filter({ $0 == "-openjump-ui-test" }).count == 1,
              arguments.filter({ $0 == "-openjump-ui-test-id" }).count == 1,
              let position = arguments.firstIndex(of: "-openjump-ui-test-id"),
              arguments.indices.contains(position + 1) else { throw SetupError.invalidArguments }
        let raw = arguments[position + 1]
        guard raw.count == 36, raw.unicodeScalars.allSatisfy({ $0.isASCII }),
              let id = UUID(uuidString: raw), raw.uppercased() == id.uuidString else {
            throw SetupError.invalidArguments
        }
        return id
    }

    @MainActor static func prepare(arguments: [String]) async throws -> Context {
        // Argument validation occurs before all filesystem, preferences and DB access.
        let id = try identifier(arguments: arguments)
        let manager = FileManager.default
        let parent = manager.temporaryDirectory.resolvingSymlinksInPath()
        let root = parent.appendingPathComponent("OpenJumpUITest-" + id.uuidString, isDirectory: true)
        if manager.fileExists(atPath: root.path) {
            let attributes = try manager.attributesOfItem(atPath: root.path)
            guard attributes[.type] as? FileAttributeType == .typeDirectory,
                  root.resolvingSymlinksInPath().path == root.path else { throw SetupError.unsafeDirectory }
        } else {
            try manager.createDirectory(at: root, withIntermediateDirectories: false)
        }
        let databaseURL = root.appendingPathComponent("openjump-ui-test.sqlite")
        // Reject a pre-existing DB symlink as well as a redirected root.
        if manager.fileExists(atPath: databaseURL.path) {
            let attributes = try manager.attributesOfItem(atPath: databaseURL.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  databaseURL.resolvingSymlinksInPath().path == databaseURL.path else { throw SetupError.unsafeDirectory }
        }
        let suite = "OpenJumpUITest." + id.uuidString
        guard let defaults = UserDefaults(suiteName: suite) else { throw SetupError.defaultsUnavailable }
        let preferences = AppPreferences(defaults: defaults, namespace: "ui-test")
        if defaults.object(forKey: "openjump.apple.ui-test.language") == nil { preferences.language = .en }
        if defaults.object(forKey: "openjump.apple.ui-test.theme") == nil { preferences.theme = .light }
        let store = try SQLiteStore(databaseURL: databaseURL)
        let athletes = try await store.athletes(includeArchived: true)
        let history = try await store.history(limit: 1)
        // Once-only seeding; never delete/reset/reinsert on relaunch. Partial or
        // edited fixtures are preserved too, so a failed setup cannot fake success.
        if athletes.isEmpty && history.items.isEmpty {
            let first = try await store.createAthlete(name: "UI Test Athlete", weightKg: 70, heightCm: 175)
            _ = try await store.createAthlete(name: "UI Test Second Athlete")
            let events = [
                JumpEventMark(kind: .movementStart, frameIndex: 1, ptsUs: 100_000, previousPtsUs: 0, nextPtsUs: 200_000),
                JumpEventMark(kind: .takeoff, frameIndex: 4, ptsUs: 400_000, previousPtsUs: 300_000, nextPtsUs: 500_000),
                JumpEventMark(kind: .landing, frameIndex: 9, ptsUs: 900_000, previousPtsUs: 800_000, nextPtsUs: nil)
            ]
            let draft = TemporalJumpDraft(sessionKey: "ui-test-temporal-" + id.uuidString,
                                          ownerID: first.id, protocolKey: .cmj, side: nil, dropHeightCm: nil,
                                          recordedAt: Date(timeIntervalSince1970: 1_700_000_000),
                                          notes: "UI Test original note", source: .files,
                                          sourceFrameCount: 10, sourceOriginUs: 0,
                                          temporalState: .realtimeDeclared, events: events)
            _ = try await store.saveTemporalJump(draft)
            preferences.selectedAthleteID = first.id
        }
        return Context(id: id, root: root, databaseURL: databaseURL, suite: suite, preferences: preferences)
    }
}

@MainActor struct ProductUITestRoot: View {
    let arguments: [String]
    @State private var context: ProductUITestEnvironment.Context?
    @State private var setupFailed = false
    var body: some View {
        Group {
            if let context {
                ProductUITestShell(context: context)
            } else if setupFailed {
                // Test-only diagnostics: never instantiate production as recovery.
                Text(verbatim: "UI test setup failed; production data was not opened.")
                    .accessibilityIdentifier("testing.setupError")
            } else {
                ProgressView()
            }
        }
        .task {
            guard context == nil, !setupFailed else { return }
            do { context = try await ProductUITestEnvironment.prepare(arguments: arguments) }
            catch { setupFailed = true }
        }
    }
}

@MainActor private struct ProductUITestShell: View {
    let context: ProductUITestEnvironment.Context
    @ObservedObject private var preferences: AppPreferences
    init(context: ProductUITestEnvironment.Context) {
        self.context = context
        _preferences = ObservedObject(wrappedValue: context.preferences)
    }
    var body: some View {
        AppShell(preferences: preferences, databaseURL: context.databaseURL)
            .safeAreaInset(edge: .top) {
                Text(verbatim: "Synthetic UI test data")
                    .font(.caption)
                    .padding(4)
                    .frame(maxWidth: .infinity)
                    .background(Color.openJumpSurfaceElevated)
                    .accessibilityIdentifier("testing.syntheticBanner")
            }
    }
}
#endif
