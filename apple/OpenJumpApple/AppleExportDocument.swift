import SwiftUI
import UniformTypeIdentifiers

extension AppleExportFormat {
    var contentType: UTType { self == .jsonBackup ? .json : .commaSeparatedText }
}

enum AppleExportDocumentError: Error, Equatable { case importNotSupported }

/// Immutable bytes for a user-chosen Files destination. This is not an import
/// seam: FileDocument's required reading initializer deliberately rejects reads.
struct AppleExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .commaSeparatedText] }
    static var writableContentTypes: [UTType] { readableContentTypes }
    let data: Data
    let format: AppleExportFormat

    init(data: Data, format: AppleExportFormat) { self.data = data; self.format = format }
    init(configuration: ReadConfiguration) throws {
        self.data = try Self.rejectImportedContent()
        self.format = .jsonBackup
    }
    static func rejectImportedContent() throws -> Data { throw AppleExportDocumentError.importNotSupported }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { fileRepresentation() }
    func fileRepresentation() -> FileWrapper { FileWrapper(regularFileWithContents: data) }

    static func filename(format: AppleExportFormat, date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss"
        let timestamp = formatter.string(from: date)
        let kind = format == .jsonBackup ? "apple-data" : "analysis"
        // FileExporter supplies the selected type's extension; no owner, source
        // video filename, private path or localized user text enters this name.
        return "OpenJump-" + kind + "-" + timestamp
    }
}

@MainActor
struct AppleDataExportView: View {
    @ObservedObject var state: AppState
    @State private var preparing = false
    @State private var snapshot: AppleExportResult?
    @State private var document: AppleExportDocument?
    @State private var showingExporter = false
    @State private var messageKey: String?
    private var language: AppLanguage { state.preferences.language }

    var body: some View {
        Form {
            Section {
                Text(AppText.string("export.scope", language: language))
                Text(AppText.string("export.restoreUnavailable", language: language))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("export.restoreUnavailable")
                Text(AppText.string("export.privacy", language: language))
                    .font(.footnote)
                    .accessibilityIdentifier("export.privacy")
            }
            Section {
                Button(AppText.string("export.csv", language: language)) { prepare(.analyticalCSV) }
                    .frame(minHeight: 48)
                    .accessibilityIdentifier("export.csv")
                Button(AppText.string("export.json", language: language)) { prepare(.jsonBackup) }
                    .frame(minHeight: 48)
                    .accessibilityIdentifier("export.json")
                if preparing {
                    ProgressView(AppText.string("export.preparing", language: language))
                        .accessibilityIdentifier("export.preparing")
                }
            }
            .disabled(preparing || showingExporter || state.store == nil)
            if let snapshot {
                Section(AppText.string("export.preview", language: language)) {
                    Text(AppText.string(snapshot.format == .jsonBackup ? "export.json" : "export.csv", language: language))
                        .font(.headline)
                    if snapshot.format == .jsonBackup {
                        LabeledContent {
                            Text(verbatim: String(snapshot.profileCount))
                        } label: { Text(AppText.string("export.profiles", language: language)) }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(AppText.string("export.profiles", language: language)))
                        .accessibilityValue(Text(verbatim: String(snapshot.profileCount)))
                        .accessibilityIdentifier("export.preview.profiles")
                    }
                    LabeledContent {
                        Text(verbatim: String(snapshot.measurementCount))
                    } label: { Text(AppText.string("export.measurements", language: language)) }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(AppText.string("export.measurements", language: language)))
                    .accessibilityValue(Text(verbatim: String(snapshot.measurementCount)))
                    .accessibilityIdentifier("export.preview.measurements")
                    LabeledContent {
                        Text(verbatim: String(snapshot.metricCount))
                    } label: { Text(AppText.string("export.metrics", language: language)) }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(AppText.string("export.metrics", language: language)))
                    .accessibilityValue(Text(verbatim: String(snapshot.metricCount)))
                    .accessibilityIdentifier("export.preview.metrics")
                    Button(AppText.string("export.save", language: language)) {
                        document = AppleExportDocument(data: snapshot.data, format: snapshot.format)
                        showingExporter = true
                    }
                    .frame(minHeight: 48)
                    .disabled(preparing || showingExporter)
                    .accessibilityIdentifier("export.save")
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("export.preview")
            }
            if let messageKey {
                Section {
                    Text(AppText.string(messageKey, language: language))
                        .accessibilityIdentifier("export.message")
                }
            }
        }
        .navigationTitle(AppText.string("export.title", language: language))
        .accessibilityIdentifier("export.content")
        .fileExporter(isPresented: $showingExporter, document: document,
                      contentType: snapshot?.format.contentType ?? .json,
                      defaultFilename: snapshot.map { AppleExportDocument.filename(format: $0.format, date: $0.createdAt) }) { result in
            document = nil
            switch result {
            case .success:
                // Do not persist/log the provider URL; user choice may sync it.
                messageKey = "export.saved"
            case .failure(let error):
                let cocoa = error as NSError
                if cocoa.domain == NSCocoaErrorDomain && cocoa.code == CocoaError.Code.userCancelled.rawValue {
                    messageKey = nil
                } else {
                    messageKey = "export.writeFailed"
                }
            }
        }
    }

    private func prepare(_ format: AppleExportFormat) {
        guard !preparing, !showingExporter else { return }
        guard let store = state.store else { messageKey = "export.invalidSnapshot"; return }
        preparing = true
        snapshot = nil
        document = nil
        messageKey = nil
        Task {
            defer { preparing = false }
            do {
                // SQLiteStore's synchronous method runs on its actor, not this
                // view's executor, and contains no reentrant awaited page calls.
                snapshot = try await store.exportData(format: format)
            } catch is CancellationError {
                messageKey = nil
            } catch {
                messageKey = (error as? AppleDataExportError) == .tooLarge ? "export.tooLarge" : "export.invalidSnapshot"
            }
        }
    }
}
