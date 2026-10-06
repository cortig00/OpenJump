import SwiftUI
import JumpCoreSpike

struct JumpDemoMetrics {
    let heightCm: Double
    let takeoffVelocityMs: Double
    let rsiMod: Double
    let flightTimeMs: Double
}

struct JumpDemoAdapter {
    func calculate(startUs: Int64, takeoffUs: Int64, landingUs: Int64) throws -> JumpDemoMetrics {
        let shared = try JumpCore().calculate(startUs: startUs, takeoffUs: takeoffUs, landingUs: landingUs)
        return JumpDemoMetrics(
            heightCm: shared.heightCm,
            takeoffVelocityMs: shared.takeoffVelocityMs,
            rsiMod: shared.rsiMod,
            flightTimeMs: shared.flightTimeMs
        )
    }
}

struct ContentView: View {
    @State private var metrics: JumpDemoMetrics?
    @State private var errorText: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("prototype.title").font(.largeTitle.bold()).accessibilityIdentifier("prototypeTitle")
                    Text("prototype.subtitle").font(.body).foregroundStyle(.secondary)
                }
                HStack(spacing: 8) {
                    Image(systemName: "figure.jumprope")
                    Text("prototype.synthetic").font(.subheadline.weight(.medium))
                }.foregroundStyle(Color.primaryGreen).padding(.vertical, 8)
                if let metrics {
                    VStack(alignment: .leading, spacing: 16) {
                        metric("metric.height", value: metrics.heightCm, unit: "unit.cm", digits: 2, valueIdentifier: "heightMetric")
                        Divider()
                        metric("metric.velocity", value: metrics.takeoffVelocityMs, unit: "unit.mps", digits: 2)
                        Divider()
                        metric("metric.rsi", value: metrics.rsiMod, unit: "unit.rsi", digits: 3)
                        Divider()
                        metric("metric.flight", value: metrics.flightTimeMs, unit: "unit.ms", digits: 0)
                    }
                    .padding(16)
                    .background(Color.metricSurface, in: RoundedRectangle(cornerRadius: 20))
                } else if let errorText {
                    Text(errorText).foregroundStyle(.red).accessibilityIdentifier("calculationError")
                }
                Button(action: calculateDemo) {
                    Label("prototype.recalculate", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity).padding(.vertical, 16)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.primaryGreen)
                .accessibilityIdentifier("recalculateDemo")
                Text("prototype.disclaimer").font(.footnote).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16).padding(.vertical, 24)
            .frame(maxWidth: 600, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Color.appBackground.ignoresSafeArea())
        .task { calculateDemo() }
    }

    private func metric(_ title: LocalizedStringKey, value: Double, unit: LocalizedStringKey, digits: Int, valueIdentifier: String? = nil) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.body).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            if let valueIdentifier {
                Text(value, format: .number.precision(.fractionLength(digits)))
                    .font(.largeTitle.bold())
                    .monospacedDigit()
                    .accessibilityIdentifier(valueIdentifier)
            } else {
                Text(value, format: .number.precision(.fractionLength(digits)))
                    .font(.title2.bold()).monospacedDigit()
            }
            Text(unit).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func calculateDemo() {
        do {
            metrics = try JumpDemoAdapter().calculate(startUs: 100_000, takeoffUs: 400_000, landingUs: 900_000)
            errorText = nil
        } catch {
            metrics = nil
            errorText = String(format: NSLocalizedString("prototype.error", comment: ""), String(describing: error))
        }
    }
}

private extension Color {
    static let primaryGreen = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: CGFloat(103)/255, green: CGFloat(209)/255, blue: CGFloat(184)/255, alpha: 1) : UIColor(red: 0, green: CGFloat(107)/255, blue: CGFloat(90)/255, alpha: 1) })
    static let appBackground = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: CGFloat(13)/255, green: CGFloat(18)/255, blue: CGFloat(16)/255, alpha: 1) : UIColor(red: CGFloat(244)/255, green: CGFloat(248)/255, blue: CGFloat(246)/255, alpha: 1) })
    static let metricSurface = Color(uiColor: UIColor.secondarySystemGroupedBackground)
}
