import Foundation

enum AppExperienceMode: String, CaseIterable, Identifiable {
    case daily
    case debug

    var id: String { rawValue }

    var title: String {
        switch self {
        case .daily:
            return "日常模式"
        case .debug:
            return "调试模式"
        }
    }

    var toggleTitle: String {
        switch self {
        case .daily:
            return "进入调试模式"
        case .debug:
            return "退出调试模式"
        }
    }

    var toggled: AppExperienceMode {
        switch self {
        case .daily:
            return .debug
        case .debug:
            return .daily
        }
    }

    static let userDefaultsKey = "appExperienceMode"
    static let defaultMode: AppExperienceMode = .daily
}
