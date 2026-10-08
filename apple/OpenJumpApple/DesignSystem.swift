import SwiftUI

/// Minimal reusable OpenJump visual foundation (S1 identity slice).
///
/// Only patterns already repeated across near-term screens live here:
/// spacing tokens (mirroring public Android `Spacing`), the existing brand
/// green as a dynamic iOS 16 color, semantic surface colors, one section
/// container and one metric hero. No gradient/glass framework.
enum OpenJumpSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}

extension Color {
    /// Existing OpenJump brand green, dynamic for light/dark (moved here from
    /// `AppShell.swift`; the single declaration lives in this file).
    static let openJumpGreen = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.40, green: 0.82, blue: 0.70, alpha: 1) : UIColor(red: 0.02, green: 0.43, blue: 0.35, alpha: 1) })
    static let openJumpBackground = Color(uiColor: .systemGroupedBackground)
    static let openJumpSurface = Color(uiColor: .secondarySystemGroupedBackground)
    static let openJumpSurfaceElevated = Color(uiColor: .tertiarySystemGroupedBackground)
    static let openJumpOutline = Color(uiColor: .separator)
}

/// Reusable section container: optional title plus content on the shared
/// surface, with the standard section spacing. Used by Jumps setup/import
/// now and by Profiles/History/Settings in later slices.
struct OpenJumpSection<Content: View>: View {
    let title: String?
    @ViewBuilder let content: () -> Content
    init(title: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }
    var body: some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.md) {
            if let title {
                Text(title)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
            }
            content()
        }
        .padding(OpenJumpSpacing.lg)
        .background(Color.openJumpSurface, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Hero display for one saved metric. Uses the typed
/// `formattedMetricParts(_:units:locale:)` only; no canonical conversion
/// rewrite. Value is dominant native SF largeTitle, unit is secondary
/// title3, localized name is secondary below (value → unit → label).
/// ViewThatFits keeps value+unit horizontal when it fits and stacks them
/// when narrow or Dynamic Type needs room; no fixed height, truncation or
/// shrink factor. The combined VoiceOver label is name + combined value/unit.
struct OpenJumpMetricHero: View {
    let metric: SavedMetric
    let language: AppLanguage
    let units: UnitProfile
    let locale: Locale
    var body: some View {
        let parts = formattedMetricParts(metric, units: units, locale: locale)
        let name = metricName(metric.key, language: language)
        VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: OpenJumpSpacing.sm) {
                    Text(verbatim: parts.valueString)
                        .font(.largeTitle.bold())
                        .monospacedDigit()
                    Text(verbatim: parts.unitString)
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: parts.valueString)
                        .font(.largeTitle.bold())
                        .monospacedDigit()
                    Text(verbatim: parts.unitString)
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
            Text(name)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name), \(parts.combined)")
    }
}

/// Shared primary + secondary summary used by the Jumps result preview and
/// the History detail. Primary comes only from the frozen
/// `ProtocolPresentation.primaryMetric`; secondaries are the remaining stored
/// metrics in input order (keyed by ordinal), rendered as native
/// LabeledContent rows. No zero fallback, no discarded metric, no duplicated
/// primary, no cards. Accessibility-large text stacks label/value so nothing
/// clips.
struct OpenJumpMetricSummary: View {
    let metrics: [SavedMetric]
    let protocolKey: SavedProtocol
    let language: AppLanguage
    let units: UnitProfile
    let locale: Locale
    let primaryAccessibilityID: String
    var body: some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.sm) {
            if let primary = ProtocolPresentation.primaryMetric(in: metrics, protocolKey: protocolKey) {
                OpenJumpMetricHero(metric: primary, language: language, units: units, locale: locale)
                    .accessibilityIdentifier(primaryAccessibilityID)
                ForEach(metrics.filter { $0.ordinal != primary.ordinal }, id: \.ordinal) { metric in
                    secondaryRow(for: metric)
                }
            }
        }
    }
    @ViewBuilder
    private func secondaryRow(for metric: SavedMetric) -> some View {
        ViewThatFits(in: .horizontal) {
            LabeledContent {
                Text(formattedMetric(metric, units: units, locale: locale))
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
            } label: {
                Text(metricName(metric.key, language: language))
                    .foregroundStyle(.secondary)
            }
            .frame(minHeight: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(metricName(metric.key, language: language))
                    .foregroundStyle(.secondary)
                Text(formattedMetric(metric, units: units, locale: locale))
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(metricName(metric.key, language: language)), \(formattedMetric(metric, units: units, locale: locale))")
        }
    }
}
