import Foundation

/// Shared presentation contracts for saved jump protocols (S1, frozen for
/// later slices). No formulas, no unit conversion, no mutation: pure mapping
/// over already-saved values.
enum ProtocolPresentation {
    /// Asset names in `Assets.xcassets` illustrating `protocolKey`.
    ///
    /// - cmj/sj/abalakov/dropJump: the single matching illustration.
    /// - unilateral: the LEFT/RIGHT image matching `side`; when `side` is nil
    ///   both images are returned as a side-by-side comparison, but the setup
    ///   control itself always stays LEFT/RIGHT.
    /// - horizontal/asymmetry (unsupported): empty, so callers never present
    ///   them as supported features merely because art exists.
    static func illustrationAssets(for protocolKey: SavedProtocol, side: String?) -> [String] {
        switch protocolKey {
        case .cmj:
            return ["jump_cmj"]
        case .sj:
            return ["jump_sj"]
        case .abalakov:
            return ["jump_abalakov"]
        case .dropJump:
            return ["jump_drop_jump"]
        case .unilateral:
            if side == "LEFT" { return ["jump_unilateral_left"] }
            if side == "RIGHT" { return ["jump_unilateral_right"] }
            return ["jump_unilateral_left", "jump_unilateral_right"]
        case .horizontal, .asymmetry:
            return []
        }
    }

    /// The dominant metric for a saved measurement list. Prefers RSI for
    /// drop-jump (`RSI`, then `RSI_MOD`), height (`HEIGHT_CM`, then
    /// `JUMP_HEIGHT`) for the other temporal protocols, with a deterministic
    /// lowest-ordinal fallback. Returns nil for empty lists. Never mutates.
    static func primaryMetric(in metrics: [SavedMetric], protocolKey: SavedProtocol) -> SavedMetric? {
        guard !metrics.isEmpty else { return nil }
        let preferred: [String]
        if protocolKey == .dropJump {
            preferred = ["RSI", "RSI_MOD"]
        } else {
            preferred = ["HEIGHT_CM", "JUMP_HEIGHT"]
        }
        for key in preferred {
            if let match = metrics.filter({ $0.key.uppercased() == key }).min(by: { $0.ordinal < $1.ordinal }) {
                return match
            }
        }
        return metrics.min(by: { $0.ordinal < $1.ordinal })
    }
}
