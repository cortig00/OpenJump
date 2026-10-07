import SwiftUI

/// Native guidance for the five implemented, imported-video temporal protocols.
/// No video, measurement, preference or database state is changed here.
struct HelpView: View {
    let language: AppLanguage
    private let protocols: [SavedProtocol] = [.cmj, .sj, .abalakov, .unilateral, .dropJump]

    var body: some View {
        Form {
            Section(AppText.string("help.workflow.title", language: language)) {
                Text(AppText.string("help.workflow.body", language: language))
            }
            Section {
                Text(AppText.string("help.protocols.body", language: language))
                    .foregroundStyle(.secondary)
                ForEach(protocols, id: \.self) { protocolKey in
                    VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
                        Text(AppText.string(protocolKey.titleKey, language: language))
                            .font(.headline)
                        Text(eventSequence(for: protocolKey))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .accessibilityElement(children: .combine)
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

    private func eventSequence(for protocolKey: SavedProtocol) -> String {
        TemporalJumpDraft.requiredEvents(for: protocolKey).map { kind in
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
