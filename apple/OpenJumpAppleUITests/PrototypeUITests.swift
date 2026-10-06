import XCTest

final class PrototypeUITests: XCTestCase {
    func testPrototypeRendersAndAttachAppScreenshot() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["prototypeTitle"].waitForExistence(timeout: 20))
        let height = app.staticTexts["heightMetric"]
        XCTAssertTrue(height.waitForExistence(timeout: 10))
        XCTAssertTrue(["30.65", "30,65"].contains(height.label), "Unexpected localized height: \(height.label)")
        XCTAssertFalse(app.staticTexts["calculationError"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "openjump-prototype-iphone"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
