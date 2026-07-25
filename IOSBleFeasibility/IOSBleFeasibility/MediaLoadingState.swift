import Foundation

enum LyricLoadingStage: Equatable {
    case idle
    case waitingQqQrc
    case windowReady(lineCount: Int)
    case fullLyrics(received: Int, expected: Int)
    case ready(lineCount: Int)
    case failed(reason: String)

    var title: String {
        switch self {
        case .idle:
            return "歌词待同步"
        case .waitingQqQrc:
            return "等待 QQ QRC"
        case let .windowReady(lineCount):
            return "歌词窗口就绪 · \(lineCount)行"
        case let .fullLyrics(received, expected):
            return "完整歌词 · \(received)/\(max(expected, 1))"
        case let .ready(lineCount):
            return "完整歌词就绪 · \(lineCount)行"
        case let .failed(reason):
            return "歌词失败 · \(Self.short(reason))"
        }
    }

    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }

    private static func short(_ value: String) -> String {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? "未找到" : String(text.prefix(24))
    }
}

enum ArtworkLoadingStage: Equatable {
    case idle
    case preview(received: Int, expected: Int)
    case previewReady
    case hq(received: Int, expected: Int)
    case hqReady
    case failed(reason: String)

    var title: String {
        switch self {
        case .idle:
            return "封面待同步"
        case let .preview(received, expected):
            return expected > 0 ? "封面 Preview · \(received)/\(expected)" : "等待封面 Preview"
        case .previewReady:
            return "封面 Preview 就绪"
        case let .hq(received, expected):
            return expected > 0 ? "封面 HQ · \(received)/\(expected)" : "等待封面 HQ"
        case .hqReady:
            return "封面 HQ 就绪"
        case let .failed(reason):
            let text = reason.trimmingCharacters(in: .whitespacesAndNewlines)
            return "封面失败 · \(text.isEmpty ? "未找到" : String(text.prefix(24)))"
        }
    }

    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

struct MediaLoadingState: Equatable {
    var lyric: LyricLoadingStage = .idle
    var artwork: ArtworkLoadingStage = .idle
}
