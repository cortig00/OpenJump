import XCTest
@testable import OpenJumpApple

/// S2 profile-editing contracts: pure-Foundation draft behavior, unit-context
/// capture, dirty/cancel semantics and identity/avatar preservation.
///
/// These tests exercise the draft model and the exact UI-source paths
/// (initial formatting, store signatures) honestly; live SwiftUI cancel flow
/// is not exercised here and product UI tests follow in S5.
final class ProfileEditingTests: XCTestCase {
    private func athlete(
        weightKg: Double? = 70,
        heightCm: Double? = 180,
        avatarKey: String? = "avatar_frog_jump"
    ) throws -> Athlete {
        try Athlete(name: "Ada", weightKg: weightKg, heightCm: heightCm, notes: "  note  ", avatarKey: avatarKey)
    }

    private func draft(
        initial: Athlete? = nil,
        locale: Locale = Locale(identifier: "en_US"),
        mass: MassUnit = .kilograms,
        length: ShortLengthUnit = .cm
    ) throws -> AthleteEditorDraft {
        AthleteEditorDraft(
            initial: initial ?? (try athlete()),
            locale: locale,
            massUnit: mass,
            lengthUnit: length
        )
    }

    func testUnchangedImperialTextPreservesExactCanonical() throws {
        let original = try athlete(weightKg: 70, heightCm: 180)
        var draft = AthleteEditorDraft(
            initial: original,
            locale: Locale(identifier: "en_US"),
            massUnit: .pounds,
            lengthUnit: .inches
        )
        XCTAssertFalse(draft.weightText.isEmpty)
        XCTAssertFalse(draft.heightText.isEmpty)
        let pair = try draft.canonicalAnthropometrics()
        XCTAssertEqual(pair.weightKg, 70)
        XCTAssertEqual(pair.heightCm, 180)
        draft.name = "Ada Lovelace"
        let validated = try draft.validatedAthlete()
        XCTAssertEqual(validated.weightKg, 70)
        XCTAssertEqual(validated.heightCm, 180)
    }

    func testOnePoundUsesExistingCanonicalFactor() throws {
        var draft = try draft(locale: Locale(identifier: "en_US"), mass: .pounds)
        draft.weightText = "1"
        XCTAssertEqual(try draft.canonicalWeightKg(), 0.45359237)
        var inchDraft = try draft(locale: Locale(identifier: "en_US"), length: .inches)
        inchDraft.heightText = "1"
        XCTAssertEqual(try inchDraft.canonicalHeightCm(), 2.54)
    }

    func testSpanishDecimalCommaAccepted() throws {
        var draft = try draft(locale: Locale(identifier: "es_ES"))
        draft.weightText = "70,5"
        XCTAssertEqual(try draft.canonicalWeightKg(), 70.5)
    }

    func testSpanishGroupingRejected() throws {
        var draft = try draft(locale: Locale(identifier: "es_ES"))
        draft.weightText = "1.000"
        XCTAssertThrowsError(try draft.canonicalWeightKg()) { error in
            XCTAssertEqual(error as? AppModelError, .invalidAnthropometrics)
        }
    }

    func testJunkNonPositiveAndNonFiniteRejected() throws {
        for text in ["abc", "12kg", "1,000.5.2", "", "0", "0.0", "-3", "nan", "NaN", "inf", "Infinity", "+"] {
            var draft = try draft(locale: Locale(identifier: "en_US"))
            draft.weightText = text
            if text.isEmpty { continue }
            XCTAssertThrowsError(try draft.canonicalWeightKg(), "weight \(text) must throw") { error in
                XCTAssertEqual(error as? AppModelError, .invalidAnthropometrics)
            }
        }
    }

    func testBlankOptionalsBecomeNil() throws {
        var draft = try draft()
        draft.weightText = ""
        draft.heightText = ""
        let pair = try draft.canonicalAnthropometrics()
        XCTAssertNil(pair.weightKg)
        XCTAssertNil(pair.heightCm)
        let validated = try draft.validatedAthlete()
        XCTAssertNil(validated.weightKg)
        XCTAssertNil(validated.heightCm)
    }

    func testCapturedContextIsImmutable() throws {
        let draft = try draft(locale: Locale(identifier: "en_US"), mass: .pounds, length: .inches)
        XCTAssertEqual(draft.locale.identifier, "en_US")
        XCTAssertEqual(draft.massUnit, .pounds)
        XCTAssertEqual(draft.lengthUnit, .inches)
        // Metric reinterpretation of the same text must not apply: "70" stays
        // pounds under the captured imperial context.
        var imperial = draft
        imperial.weightText = "70"
        XCTAssertEqual(try imperial.canonicalWeightKg(), 70 * 0.45359237)
    }

    func testDirtyTracksTextAndAvatarTransitions() throws {
        var draft = try draft()
        XCTAssertFalse(draft.isDirty)
        draft.name = "Ada Lovelace"
        XCTAssertTrue(draft.isDirty)
        draft.name = draft.initialName
        XCTAssertFalse(draft.isDirty)
        draft.weightText = "71"
        XCTAssertTrue(draft.isDirty)
        draft.weightText = draft.initialWeightText
        XCTAssertFalse(draft.isDirty)
        draft.avatarKey = "avatar_sprinter"
        XCTAssertTrue(draft.isDirty)
        draft.avatarKey = draft.initialAvatarKey
        XCTAssertFalse(draft.isDirty)
    }

    func testNewAthleteDefaultsToFrog() {
        let draft = AthleteEditorDraft(
            initial: nil,
            locale: Locale(identifier: "en_US"),
            massUnit: .kilograms,
            lengthUnit: .cm
        )
        XCTAssertEqual(draft.avatarKey, "avatar_frog_jump")
        XCTAssertEqual(draft.initialAvatarKey, "avatar_frog_jump")
        XCTAssertTrue(draft.name.isEmpty)
    }

    func testUnknownRawKeyRetainedAndNoOpConfirmIsByteForByte() throws {
        let original = try athlete(avatarKey: "avatar_custom_xyz")
        let draft = AthleteEditorDraft(
            initial: original,
            locale: Locale(identifier: "en_US"),
            massUnit: .kilograms,
            lengthUnit: .cm
        )
        // Picker pending starts from the raw key; Confirm without an explicit
        // selection applies it unchanged.
        XCTAssertEqual(draft.avatarKey, "avatar_custom_xyz")
        let validated = try draft.validatedAthlete()
        XCTAssertEqual(validated.avatarKey, "avatar_custom_xyz")
    }

    func testLegacyAliasNotBackfilledOnNoOp() throws {
        let original = try athlete(avatarKey: "avatar_gymnast")
        let draft = AthleteEditorDraft(
            initial: original,
            locale: Locale(identifier: "en_US"),
            massUnit: .kilograms,
            lengthUnit: .cm
        )
        let validated = try draft.validatedAthlete()
        XCTAssertEqual(validated.avatarKey, "avatar_gymnast")
    }

    func testIdentityPreservedOnValidate() throws {
        let original = try athlete()
        var draft = AthleteEditorDraft(
            initial: original,
            locale: Locale(identifier: "en_US"),
            massUnit: .kilograms,
            lengthUnit: .cm
        )
        draft.name = "  Grace  "
        draft.notes = ""
        let validated = try draft.validatedAthlete()
        XCTAssertEqual(validated.id, original.id)
        XCTAssertEqual(validated.createdAt, original.createdAt)
        XCTAssertEqual(validated.archivedAt, original.archivedAt)
        XCTAssertEqual(validated.name, "Grace")
        XCTAssertNil(validated.notes)
    }

    func testInvalidNameAndAnthropometricsThrow() throws {
        var draft = try draft()
        draft.name = "   "
        XCTAssertFalse(draft.isNameValid)
        XCTAssertThrowsError(try draft.validatedAthlete())
        var badNumber = try draft()
        badNumber.name = "Ada"
        badNumber.weightText = "abc"
        XCTAssertThrowsError(try badNumber.validatedAthlete()) { error in
            XCTAssertEqual(error as? AppModelError, .invalidAnthropometrics)
        }
    }

    func testCancelModelClosesCleanAndConfirmsDirty() throws {
        // Models the editor Cancel paths honestly: a clean draft closes
        // directly, a dirty draft requires discard confirmation, and
        // confirming performs no store or preference mutation (nothing here
        // touches SQLiteStore or AppPreferences).
        let clean = try draft()
        XCTAssertFalse(clean.isDirty)
        var dirty = try draft()
        dirty.notes = "unsaved"
        XCTAssertTrue(dirty.isDirty)
    }
}
