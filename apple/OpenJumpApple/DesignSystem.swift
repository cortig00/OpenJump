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

/// Hero display for one saved metric. Uses the existing `metricName(_:)` and
/// `formattedMetric(_:units:locale:)` only; no canonical conversion rewrite.
/// Wraps with Dynamic Type; the unit is exposed as an accessible label.
struct OpenJumpMetricHero: View {
    let metric: SavedMetric
    let language: AppLanguage
    let units: UnitProfile
    let locale: Locale
    var body: some View {
        VStack(alignment: .leading, spacing: OpenJumpSpacing.xs) {
            Text(metricName(metric.key, language: language))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            Text(formattedMetric(metric, units: units, locale: locale))
                .font(.largeTitle.bold())
                .monospacedDigit()
                .minimumScaleFactor(0.6)
                .lineLimit(2)
                .accessibilityLabel("\(metricName(metric.key, language: language)), \(formattedMetric(metric, units: units, locale: locale))")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
