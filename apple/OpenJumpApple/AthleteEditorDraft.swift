import Foundation

/// S2 pure-Foundation athlete editor draft (no SwiftUI dependency).
///
/// Captures the optional initial `Athlete`, the raw editable text, the raw
/// `avatarKey`, the initial formatted text and the `Locale` plus mass/length
/// units active when editing began. The captured locale/units are immutable:
/// later global preference changes never reinterpret the in-progress text.
///
/// Conversion reuses only `MeasurementPresentation.parsePositive` and the
/// existing canonical converters; no imperial factor is reimplemented here.
/// When the weight/height text is byte-identical to the initial formatted
/// text, the exact original canonical `Double` is returned (no imperial
/// rounding drift). Blank optional input maps to `nil`; non-blank invalid,
/// grouped, junk, non-positive or non-finite input throws
/// `AppModelError.invalidAnthropometrics`. Identity (`UUID`, `createdAt`,
/// `archivedAt`) and the raw avatar key (including unknown or legacy alias
/// keys) are preserved; only an explicit tap on a known avatar choice changes
/// the key, and a new athlete still defaults to the frog.
struct AthleteEditorDraft: Equatable {
    /// New-athlete default avatar; existing athletes keep their raw key.
    static let defaultNewAvatarKey = "avatar_frog_jump"

    // MARK: - Captured context (immutable)

    let initial: Athlete?
    let initialName: String
    let initialWeightText: String
    let initialHeightText: String
    let initialNotes: String
    let initialAvatarKey: String?
    let locale: Locale
    let massUnit: MassUnit
    let lengthUnit: ShortLengthUnit

    // MARK: - Editable state

    var name: String
    var weightText: String
    var heightText: String
    var notes: String
    var avatarKey: String?

    /// Captures a draft from the optional initial athlete, formatting the
    /// canonical values with the units/locale active when editing began.
    init(initial: Athlete?, locale: Locale, massUnit: MassUnit, lengthUnit: ShortLengthUnit) {
        self.initial = initial
        self.locale = locale
        self.massUnit = massUnit
        self.lengthUnit = lengthUnit
        let weightText = initial?.weightKg.map {
            MeasurementPresentation.mass($0, as: massUnit)
                .formatted(.number.precision(.fractionLength(0...2)).locale(locale))
        } ?? ""
        let heightText = initial?.heightCm.map {
            MeasurementPresentation.shortLength($0, as: lengthUnit)
                .formatted(.number.precision(.fractionLength(0...2)).locale(locale))
        } ?? ""
        self.initialName = initial?.name ?? ""
        self.initialWeightText = weightText
        self.initialHeightText = heightText
        self.initialNotes = initial?.notes ?? ""
        self.initialAvatarKey = initial == nil ? Self.defaultNewAvatarKey : initial?.avatarKey
        self.name = self.initialName
        self.weightText = weightText
        self.heightText = heightText
        self.notes = self.initialNotes
        self.avatarKey = self.initialAvatarKey
    }

    /// True when any editable field differs from its captured initial value.
    /// Drives Cancel (direct close vs. discard confirmation) and
    /// `.interactiveDismissDisabled`.
    var isDirty: Bool {
        name != initialName
            || weightText != initialWeightText
            || heightText != initialHeightText
            || notes != initialNotes
            || avatarKey != initialAvatarKey
    }

    /// True when the trimmed name is non-empty. Length/character rules are
    /// enforced by the throwing `Athlete` initializer on save.
    var isNameValid: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Canonical kilograms for the current weight text, or `nil` for blank.
    func canonicalWeightKg() throws -> Double? {
        if weightText.isEmpty { return nil }
        if let original = initial?.weightKg, weightText == initialWeightText { return original }
        guard let parsed = MeasurementPresentation.parsePositive(weightText, locale: locale) else {
            throw AppModelError.invalidAnthropometrics
        }
        return MeasurementPresentation.kilograms(parsed, from: massUnit)
    }

    /// Canonical centimeters for the current height text, or `nil` for blank.
    func canonicalHeightCm() throws -> Double? {
        if heightText.isEmpty { return nil }
        if let original = initial?.heightCm, heightText == initialHeightText { return original }
        guard let parsed = MeasurementPresentation.parsePositive(heightText, locale: locale) else {
            throw AppModelError.invalidAnthropometrics
        }
        return MeasurementPresentation.centimeters(parsed, from: lengthUnit)
    }

    /// Canonical `(weightKg, heightCm)` pair; blank optionals become `nil`.
    func canonicalAnthropometrics() throws -> (weightKg: Double?, heightCm: Double?) {
        (try canonicalWeightKg(), try canonicalHeightCm())
    }

    /// Builds the athlete to persist via the existing throwing domain
    /// initializer (validates name/notes/avatar lengths), preserving the
    /// original `UUID`, `createdAt` and `archivedAt` on edit.
    func validatedAthlete(now: Date = Date()) throws -> Athlete {
        let anthropometrics = try canonicalAnthropometrics()
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let initial {
            return try Athlete(
                id: initial.id,
                name: cleanName,
                weightKg: anthropometrics.weightKg,
                heightCm: anthropometrics.heightCm,
                notes: notes,
                createdAt: initial.createdAt,
                updatedAt: now,
                archivedAt: initial.archivedAt,
                avatarKey: avatarKey
            )
        }
        return try Athlete(
            name: cleanName,
            weightKg: anthropometrics.weightKg,
            heightCm: anthropometrics.heightCm,
            notes: notes,
            createdAt: now,
            avatarKey: avatarKey
        )
    }
}
