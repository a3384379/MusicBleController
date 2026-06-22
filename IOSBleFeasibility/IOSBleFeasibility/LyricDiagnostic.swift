import Foundation

struct LyricDiagnostic: Equatable {
    var trackId: String
    var songKey: String
    var title: String
    var artist: String
    var status: String
    var source: String
    var reason: String
    var lines: Int
    var lastAttemptAt: Int64
    var nextRetryAt: Int64
    var retryCount: Int
    var cooldownUntil: Int64
    var fuzzyIndexReady: Bool
    var qrcIndexLoaded: Bool
    var maintenanceBusy: Bool
    var waitingQqMusicCache: Bool
    var suggestion: String

    static func lightweight(
        trackId: String,
        title: String,
        artist: String,
        status: String,
        reason: String,
        suggestion: String
    ) -> LyricDiagnostic {
        LyricDiagnostic(
            trackId: trackId,
            songKey: "",
            title: title,
            artist: artist,
            status: status,
            source: "",
            reason: reason,
            lines: 0,
            lastAttemptAt: 0,
            nextRetryAt: 0,
            retryCount: 0,
            cooldownUntil: 0,
            fuzzyIndexReady: false,
            qrcIndexLoaded: false,
            maintenanceBusy: false,
            waitingQqMusicCache: status == "waiting_qqmusic_cache",
            suggestion: suggestion
        )
    }

    var statusTitle: String {
        switch normalizedStatus {
        case "loaded":
            return "歌词已加载"
        case "loading":
            return "歌词正在加载"
        case "waiting_qqmusic_cache":
            return "等待 QQ音乐生成歌词缓存"
        case "retry_pending":
            return "等待自动重试"
        case "no_safe_candidate":
            return "没有安全匹配的歌词"
        case "no_lyrics_final":
            return "当前没有可用歌词"
        case "maintenance_busy":
            return "歌词缓存维护中"
        case "error":
            return "歌词解析异常"
        default:
            return humanReadableReason
        }
    }

    var humanReadableReason: String {
        let raw = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = raw.lowercased()
        if normalized.contains("waiting qqmusic") {
            return "QQ音乐可能还没有生成歌词缓存。"
        }
        if normalized.contains("fuzzy") && normalized.contains("warming") {
            return "歌词索引正在预热，稍后会自动重试。"
        }
        if normalized.contains("cooldown") || normalized.contains("retry pending") {
            return "刚刚没有找到歌词，正在等待下一次重试。"
        }
        if normalized.contains("no safe qrc") || normalized.contains("safe candidate") {
            return "没有找到可安全匹配的歌词。"
        }
        if normalized.contains("metadata") || normalized.contains("演唱") {
            return "QQ音乐歌词元数据异常，系统已避免错配。"
        }
        if normalized.contains("maintenance") {
            return "歌词缓存维护任务正在运行。"
        }
        if normalized.contains("no parsed") {
            return "Sony 端当前还没有解析到歌词。"
        }
        if normalized.contains("loading") {
            return "歌词正在加载。"
        }
        return raw.isEmpty ? statusTitle : raw
    }

    var suggestionText: String {
        switch suggestion {
        case "open_qqmusic_lyrics":
            return "QQ音乐可能尚未生成歌词缓存。请在 Sony 端 QQ音乐打开歌词或桌面歌词后稍等。"
        case "retry_later":
            return "系统会在冷却结束后自动重试。"
        case "refresh_current_lyric":
            return "可以在 Sony 端点击刷新当前歌词。"
        case "no_safe_candidate":
            return "当前没有安全匹配的歌词候选，系统不会使用低置信度歌词以避免错配。"
        case "maintenance_busy":
            return "歌词缓存维护任务正在运行，稍后会自动重试。"
        case "loaded":
            return "歌词已加载。"
        default:
            return humanReadableReason
        }
    }

    private var normalizedStatus: String {
        status
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: " ", with: "_")
    }
}
