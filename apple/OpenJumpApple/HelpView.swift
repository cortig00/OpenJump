import SwiftUI

/// Native guidance for the five implemented, imported-video temporal protocols.
/// No video, measurement, preference or database state is changed here.
struct HelpView: View {
    let language: AppLanguage
    private let protocols: [SavedProtocol] = [.cmj, .sj, .abalakov, .unilateral, .dropJump]

    private var buildIdentity: AppBuildIdentity {
        AppBuildIdentity(infoDictionary: Bundle.main.infoDictionary)
    }

    var body: some View {
        Form {
            Section(AppText.string("help.build.title", language: language)) {
                if let version = buildIdentity.version, let build = buildIdentity.build {
                    HStack {
                        Text(AppText.string("help.build.version", language: language))
                        Spacer()
                        Text("\(version) (\(build))")
                            .monospacedDigit()
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("help.build")
                    Text(AppText.string("help.build.experimental", language: language))
                        .foregroundStyle(.secondary)
                } else {
                    Text(AppText.string("help.build.unknown", language: language))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("help.build")
                }
            }
            Section {
                guideRow(titleKey: "jumps.flow.prepare", bodyKey: "help.guide.prepare")
                guideRow(titleKey: "jumps.flow.import", bodyKey: "help.guide.import")
                guideRow(titleKey: "jumps.playback.scrub", bodyKey: "help.guide.preview")
                guideRow(titleKey: "jumps.flow.mark", bodyKey: "help.guide.mark")
                guideRow(titleKey: "jumps.flow.results", bodyKey: "help.guide.save")
                guideRow(titleKey: "jumps.video.retry", bodyKey: "help.guide.recovery")
                guideRow(titleKey: "help.marking.title", bodyKey: "help.guide.timing")
                guideRow(titleKey: "help.storage.title", bodyKey: "help.guide.data")
            } header: {
                Text(AppText.string("help.workflow.title", language: language))
            }
            Section {
                Text(AppText.string("help.protocols.body", language: language))
                    .foregroundStyle(.secondary)
                ForEach(protocols, id: \.self) { protocolKey in
                    VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
                        Text(AppText.string(protocolKey.titleKey, language: language))
                            .font(.headline)
                        protocolHelpArt(for: protocolKey)
                        Text(eventSequence(for: protocolKey))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(protocolHelpLabel(for: protocolKey))
                    .accessibilityIdentifier("help.protocol." + protocolKey.rawValue)
                }
            } header: {
                Text(AppText.string("help.protocols.title", language: language))
            }
            Section(AppText.string("help.marking.title", language: language)) {
                Text(AppText.string("jumps.help.marking", language: language))
                Text(AppText.string("jumps.timing.warning", language: language))
                    .foregroundStyle(.secondary)
            }
            Section(AppText.string("help.accuracy.title", language: language)) {
                Text(AppText.string("help.accuracy.body", language: language))
            }
            Section(AppText.string("help.privacy.title", language: language)) {
                Text(AppText.string("help.privacy.body", language: language))
            }
            Section(AppText.string("help.storage.title", language: language)) {
                Text(AppText.string("help.storage.body", language: language))
            }
            Section(AppText.string("help.scope.title", language: language)) {
                Text(AppText.string("help.scope.body", language: language))
            }
        }
        .navigationTitle(AppText.string("settings.help", language: language))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("help.content")
    }

    /// One guide step: title reuses an existing localized label, body is one
    /// of the eight authored help.guide.* strings. No new title keys.
    private func guideRow(titleKey: String, bodyKey: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(AppText.string(titleKey, language: language))
                .font(.headline)
            Text(AppText.string(bodyKey, language: language))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// Informative protocol thumbnails reusing the existing catalog assets.
    /// Bounded 72–96pt, aspect-fit, hidden from VoiceOver; the row label
    /// combines the protocol name with its existing event sequence.
    @ViewBuilder
    private func protocolHelpArt(for protocolKey: SavedProtocol) -> some View {
        let assets = ProtocolPresentation.illustrationAssets(for: protocolKey, side: nil)
        if !assets.isEmpty {
            HStack(spacing: OpenJumpSpacing.sm) {
                ForEach(assets, id: \.self) { name in
                    Image(name)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity)
                        .frame(height: 80)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityHidden(true)
        }
    }

    private func protocolHelpLabel(for protocolKey: SavedProtocol) -> String {
        AppText.string(protocolKey.titleKey, language: language) + ", " + eventSequence(for: protocolKey)
    }

    private func eventSequence(for protocolKey: SavedProtocol) -> String {        TemporalJumpDraft.requiredEvents(for: protocolKey).map { kind in
            AppText.string(eventKey(kind), language: language)
        }.joined(separator: " → ")
    }

    private func eventKey(_ kind: JumpEventKind) -> String {
        switch kind {
        case .movementStart: return "jumps.event.movementStart"
        case .initialContact: return "jumps.event.initialContact"
        case .takeoff: return "jumps.event.takeoff"
        case .landing: return "jumps.event.landing"
        }
    }
}

/// Pure build identity read from real bundle metadata.
/// No invented fallback version and no hardcoded source hash.
struct AppBuildIdentity {
    let version: String?
    let build: String?
    let sourceRevision: String?

    init(infoDictionary: [String: Any]?) {
        self.version = Self.trimmedMetadata(infoDictionary?["CFBundleShortVersionString"])
        self.build = Self.trimmedMetadata(infoDictionary?["CFBundleVersion"])
        self.sourceRevision = Self.validatedRevision(infoDictionary?["OpenJumpSourceRevision"])
    }

    var shortSourceRevision: String? {
        sourceRevision.map { String($0.prefix(7)) }
    }

    private static func trimmedMetadata(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 32 else { return nil }
        return trimmed
    }

    private static func validatedRevision(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 40 || trimmed.count == 64 else { return nil }
        guard trimmed.allSatisfy({ $0.isHexDigit && $0.isASCII }) else { return nil }
        return trimmed
    }
}
