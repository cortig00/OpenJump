import SwiftUI

@main
struct OpenJumpAppleApp: App {
    var body: some Scene {
        WindowGroup {
            if ProcessInfo.processInfo.arguments.contains("-openjump-demo") { ContentView() }
            else { AppShell() }
        }
    }
}
