import AppIntents
import Foundation

struct PlayPauseLiveActivityIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "播放/暂停"
    static var description = IntentDescription("控制当前 Sony 播放或暂停")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        LiveActivityCommandBridge.shared.performIntent(command: .playPause)
        return .result()
    }
}
