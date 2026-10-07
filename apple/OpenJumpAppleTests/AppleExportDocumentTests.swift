import XCTest
import UniformTypeIdentifiers
@testable import OpenJumpApple

final class AppleExportDocumentTests: XCTestCase {
    func testFileRepresentationContainsOnlyExactImmutableSnapshotBytes() throws {
        var original = Data("{\"creationOnly\":true}".utf8)
        let document = AppleExportDocument(data: original, format: .jsonBackup)
        original.append(contentsOf: "modified".utf8)
        let wrapper = document.fileRepresentation()
        XCTAssertTrue(wrapper.isRegularFile)
        XCTAssertEqual(wrapper.regularFileContents, Data("{\"creationOnly\":true}".utf8))
        XCTAssertFalse(wrapper.isDirectory)
        XCTAssertFalse(wrapper.isSymbolicLink)
        XCTAssertNil(wrapper.preferredFilename)
    }

    func testRequiredReadingBoundaryRejectsImportInsteadOfDecodingData() {
        // Test the exact rejection function used by FileDocument's required
        // reading initializer; this is not a simulated Files picker or restore.
        XCTAssertThrowsError(try AppleExportDocument.rejectImportedContent()) {
            XCTAssertEqual($0 as? AppleExportDocumentError, .importNotSupported)
        }
    }

    func testDeclaredContentTypesAndFormatsMatchJSONAndCSV() {
        XCTAssertEqual(AppleExportFormat.jsonBackup.contentType, UTType.json)
        XCTAssertEqual(AppleExportFormat.analyticalCSV.contentType, UTType.commaSeparatedText)
        XCTAssertEqual(AppleExportFormat.jsonBackup.fileExtension, "json")
        XCTAssertEqual(AppleExportFormat.analyticalCSV.fileExtension, "csv")
        XCTAssertEqual(AppleExportDocument.writableContentTypes, [UTType.json, UTType.commaSeparatedText])
        let document = AppleExportDocument(data: Data(), format: .analyticalCSV)
        XCTAssertEqual(document.fileRepresentation().regularFileContents, Data())
    }

    func testFilenameUsesUTCTimestampWithoutUserTextOrPaths() {
        let date = Date(timeIntervalSince1970: 1_700_000_000.125)
        XCTAssertEqual(AppleExportDocument.filename(format: .jsonBackup, date: date), "OpenJump-apple-data-20231114T221320")
        XCTAssertEqual(AppleExportDocument.filename(format: .analyticalCSV, date: date), "OpenJump-analysis-20231114T221320")
        XCTAssertFalse(AppleExportDocument.filename(format: .jsonBackup, date: date).contains("/"))
    }
}
