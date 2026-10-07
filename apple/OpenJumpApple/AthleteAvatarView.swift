import SwiftUI
import UIKit

/// Shared athlete avatar thumbnail (lifted from Profiles for later reuse).
///
/// A known key whose image resource is present renders the artwork;
/// a missing resource (`UIImage(named:)` is `nil`) or an unknown key falls
/// back to initials. The raw key itself is never rewritten here: unknown or
/// legacy keys are preserved byte-for-byte by the draft/store, with no
/// automatic frog backfill. Decorative where a contextual name or selection
/// label already exists at the call site.
struct AthleteAvatarView: View {
    let name: String
    let key: String?
    let size: CGFloat

    var body: some View {
        Group {
            if let option = AthleteAvatarCatalog.resolve(key),
               UIImage(named: option.key) != nil {
                Image(option.key)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(Self.initials(for: name))
                    .font(.system(size: size * 0.34, weight: .bold))
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.quaternary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    static func initials(for name: String) -> String {
        String(
            name.split(whereSeparator: \.isWhitespace)
                .prefix(2)
                .compactMap(\.first)
                .map(String.init)
                .joined()
                .uppercased()
                .prefix(2)
        )
    }
}
