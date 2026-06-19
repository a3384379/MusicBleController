import AppIntents
import Foundation

struct PreviousLiveActivityIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "上一首"
    static var description = IntentDescription("控制 Sony 播放上一首")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        LiveActivityCommandBridge.shared.performIntent(command: .previous)
        return .result()
    }
}
