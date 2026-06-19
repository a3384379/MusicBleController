import AppIntents
import Foundation

struct NextLiveActivityIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "下一首"
    static var description = IntentDescription("控制 Sony 播放下一首")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        LiveActivityCommandBridge.shared.performIntent(command: .next)
        return .result()
    }
}
