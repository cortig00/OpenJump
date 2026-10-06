import XCTest
@testable import OpenJumpApple

final class JumpBridgeTests: XCTestCase {
    func testSharedKotlinBridgeReturnsExpectedMetrics() throws {
        let result = try JumpDemoAdapter().calculate(startUs: 100_000, takeoffUs: 400_000, landingUs: 900_000)
        XCTAssertEqual(result.heightCm, 30.64578125, accuracy: 1e-10)
        XCTAssertEqual(result.takeoffVelocityMs, 2.4516625, accuracy: 1e-10)
        XCTAssertEqual(result.rsiMod, 1.0215260416666667, accuracy: 1e-10)
    }

    func testInvalidMarkersSurfaceAsNSError() {
        XCTAssertThrowsError(try JumpDemoAdapter().calculate(startUs: 100_000, takeoffUs: 400_000, landingUs: 449_999)) { error in
            let nativeError = error as NSError
            XCTAssertEqual(nativeError.domain, "KotlinException")
            XCTAssertTrue(nativeError.localizedDescription.contains("49999"), nativeError.localizedDescription)
        }
    }
}
