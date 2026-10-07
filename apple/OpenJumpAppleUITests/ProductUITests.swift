import XCTest

/// Actual product UI, never the demo or the normal app data store.
/// These tests do not claim Photos/Files provider or video-playback UI coverage.
final class ProductUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launch(id: UUID) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-openjump-ui-test", "-openjump-ui-test-id", id.uuidString,
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.staticTexts["testing.syntheticBanner"].waitForExistence(timeout: 20), "Refuse UI actions without the isolated fixture banner")
        XCTAssertFalse(app.staticTexts["testing.setupError"].exists)
        XCTAssertTrue(app.tabBars.buttons["Profiles"].waitForExistence(timeout: 20))
        return app
    }
    private func element(_ id: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }
    private func tap(_ id: String, in app: XCUIApplication) {
        let target = element(id, in: app)
        XCTAssertTrue(target.waitForExistence(timeout: 10), "Missing action: \(id)")
        target.tap()
    }
    private func reveal(_ target: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<6 {
            if target.exists && target.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(target.exists && target.isHittable, "Target not visible within six scrolls: \(target)")
    }
    /// Replaces the known isolated notes-fixture content. The app always launches
    /// with `-AppleLanguages (en)`, so the edit menu exposes the exact English
    /// "Select All" action; this label is intentionally not generalized to other
    /// locales. Uses only public touch and menu selection, then asserts the exact
    /// replacement before the caller discards or saves.
    private func replace(_ field: XCUIElement, with text: String, in app: XCUIApplication) {
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Missing notes field for replacement")
        field.tap()
        let focusedValue = field.value as? String
        XCTAssertNotNil(focusedValue, "Notes field had no readable value after focus")
        field.press(forDuration: 1.0)
        let selectAll = app.menuItems["Select All"]
        XCTAssertTrue(selectAll.waitForExistence(timeout: 10), "Select All menu action unavailable for the forced-English fixture")
        selectAll.tap()
        field.typeText(text)
        XCTAssertEqual(field.value as? String, text, "Replacement text was not fully applied before discard/save")
    }
    private func waitUntilGone(_ target: XCUIElement) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: target)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed, "Sheet did not dismiss after committing or discarding")
    }
    private func screenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    private func profile(_ name: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "profile.row.", name)).firstMatch
    }
    private func openMeasurement(in app: XCUIApplication) {
        app.tabBars.buttons["History"].tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "history.row.")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        XCTAssertTrue(element("measurement.close", in: app).waitForExistence(timeout: 10))
    }

    func testProfileAvatarConfirmThenCancelAndSaveSurvivesRelaunch() {
        let id = UUID()
        var app = launch(id: id)
        XCTAssertTrue(element("jumps.import.files", in: app).waitForExistence(timeout: 10))
        screenshot(app, named: "openjump-product-jumps")
        app.tabBars.buttons["Profiles"].tap()
        tap("profiles.add", in: app)
        let name = app.textFields["profile.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap(); name.typeText("Cancelled UI Athlete")
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
        tap("profile.avatar", in: app)
        tap("avatar.initials", in: app)
        tap("avatar.confirm", in: app)
        tap("profile.cancel", in: app)
        let discard = app.buttons["Discard changes"]
        XCTAssertTrue(discard.waitForExistence(timeout: 10)); discard.tap()
        XCTAssertTrue(element("profiles.add", in: app).waitForExistence(timeout: 10))
        XCTAssertFalse(profile("Cancelled UI Athlete", in: app).exists)

        tap("profiles.add", in: app)
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap(); name.typeText("Saved UI Athlete")
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
        tap("profile.save", in: app)
        XCTAssertTrue(profile("Saved UI Athlete", in: app).waitForExistence(timeout: 10))
        screenshot(app, named: "openjump-product-profiles")
        app.terminate()
        app = launch(id: id)
        app.tabBars.buttons["Profiles"].tap()
        XCTAssertTrue(profile("Saved UI Athlete", in: app).waitForExistence(timeout: 10))
        XCTAssertFalse(profile("Cancelled UI Athlete", in: app).exists)
    }

    func testHistoryValidatedResultAndNotesDiscardSaveSurviveRelaunch() {
        let id = UUID()
        var app = launch(id: id)
        openMeasurement(in: app)
        // Independent fixture height is 30.64578125 cm. The existing history
        // display contract uses up to three fractional digits: 30.646 cm.
        let height = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "30.646")).firstMatch
        reveal(height, in: app)
        XCTAssertTrue(height.waitForExistence(timeout: 10))
        XCTAssertTrue(height.label.contains("30.646 cm"), "Expected the independently rounded height with its canonical metric unit")
        XCTAssertFalse(element("measurement.analysis.error", in: app).exists)
        XCTAssertFalse(element("measurement.legacyNotice", in: app).exists)
        screenshot(app, named: "openjump-product-history-detail")
        let notes = element("measurement.notes", in: app)
        reveal(notes, in: app)
        XCTAssertEqual(notes.value as? String, "UI Test original note")
        replace(notes, with: "UI discarded note", in: app)
        tap("measurement.close", in: app)
        let discard = app.buttons["Discard changes"]
        XCTAssertTrue(discard.waitForExistence(timeout: 10)); discard.tap()
        waitUntilGone(element("measurement.close", in: app))
        openMeasurement(in: app)
        reveal(notes, in: app)
        XCTAssertEqual(notes.value as? String, "UI Test original note")
        replace(notes, with: "UI saved note", in: app)
        tap("measurement.save", in: app)
        waitUntilGone(element("measurement.close", in: app))
        XCTAssertTrue(app.tabBars.buttons["History"].waitForExistence(timeout: 10))
        app.terminate()
        app = launch(id: id)
        openMeasurement(in: app)
        let retained = element("measurement.notes", in: app)
        reveal(retained, in: app)
        XCTAssertEqual(retained.value as? String, "UI saved note")
    }

    func testJSONDataPreviewUsesIsolatedFixtureCountsBeforeOpeningFiles() {
        let app = launch(id: UUID())
        app.tabBars.buttons["Settings"].tap()
        let navigation = element("settings.dataExport", in: app)
        reveal(navigation, in: app); navigation.tap()
        let restoreWarning = app.staticTexts["export.restoreUnavailable"]
        XCTAssertTrue(restoreWarning.waitForExistence(timeout: 10))
        XCTAssertTrue(restoreWarning.label.contains("Restoration is not available"))
        let privacy = app.staticTexts["export.privacy"]
        XCTAssertTrue(privacy.label.contains("not encrypted"))
        let create = element("export.json", in: app)
        reveal(create, in: app); create.tap()
        // Preparation triage for the synthetic 2/1/5 fixture only, using the exact
        // source identifiers: the async snapshot must finish (busy gone) with no
        // error message before the preview labels are meaningful.
        waitUntilGone(element("export.preparing", in: app))
        XCTAssertFalse(element("export.message", in: app).exists, "Export preparation reported an error for the synthetic fixture")
        for (identifier, expected) in [("export.preview.profiles", "2"), ("export.preview.measurements", "1"), ("export.preview.metrics", "5")] {
            let count = element(identifier, in: app)
            reveal(count, in: app)
            XCTAssertTrue(count.waitForExistence(timeout: 10), "Missing preview count: \(identifier)")
            XCTAssertEqual(count.label, expected, "Preview count label mismatch for \(identifier)")
        }
        let save = element("export.save", in: app)
        reveal(save, in: app)
        XCTAssertTrue(save.isEnabled)
        // Deliberately do not open/save through an external Files provider.
        screenshot(app, named: "openjump-product-json-preview")
    }

    func testSettingsDarkPreferencePersistsAndNativeHelpShowsFiveProtocols() {
        let id = UUID()
        var app = launch(id: id)
        app.tabBars.buttons["Settings"].tap()
        tap("settings.theme", in: app)
        let dark = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Dark")).firstMatch
        XCTAssertTrue(dark.waitForExistence(timeout: 10)); dark.tap()
        let theme = element("settings.theme", in: app)
        XCTAssertTrue(theme.waitForExistence(timeout: 10))
        XCTAssertTrue((theme.label + " " + (theme.value as? String ?? "")).contains("Dark"))
        screenshot(app, named: "openjump-product-settings-dark")
        let help = element("settings.help", in: app)
        reveal(help, in: app); help.tap()
        let cmj = element("help.protocol.CMJ", in: app)
        reveal(cmj, in: app)
        XCTAssertTrue(cmj.exists)
        for protocolKey in ["SJ", "ABALAKOV", "UNILATERAL", "DROP_JUMP"] {
            let row = element("help.protocol." + protocolKey, in: app)
            reveal(row, in: app)
            XCTAssertTrue(row.exists)
        }
        XCTAssertFalse(element("help.protocol.HORIZONTAL", in: app).exists)
        XCTAssertFalse(element("help.protocol.ASYMMETRY", in: app).exists)
        screenshot(app, named: "openjump-product-help")
        app.terminate()
        app = launch(id: id)
        app.tabBars.buttons["Settings"].tap()
        let persistedTheme = element("settings.theme", in: app)
        XCTAssertTrue(persistedTheme.waitForExistence(timeout: 10))
        XCTAssertTrue((persistedTheme.label + " " + (persistedTheme.value as? String ?? "")).contains("Dark"))
    }
}
