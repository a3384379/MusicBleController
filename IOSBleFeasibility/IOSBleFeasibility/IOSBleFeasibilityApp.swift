import SwiftUI

@main
struct IOSBleFeasibilityApp: App {
    @ObservedObject private var preferences = PreferencesStore.shared

    init() {
        MetricDiagnosticsSubscriber.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.locale, preferences.appLanguage.locale)
        }
    }
}
