import SwiftUI

@main
@MainActor
struct OpenJumpAppleApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG && targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("-openjump-ui-test") {
                ProductUITestRoot(arguments: ProcessInfo.processInfo.arguments)
            } else {
                normalContent
            }
            #else
            normalContent
            #endif
        }
    }

    @ViewBuilder private var normalContent: some View {
        if ProcessInfo.processInfo.arguments.contains("-openjump-demo") { ContentView() }
        else { AppShell() }
    }
}
