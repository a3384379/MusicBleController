import Foundation

struct ArtworkCacheDiagnostic: Equatable, Identifiable {
    var id: String { quality }
    let quality: String
    let exists: Bool
    let bytes: Int
    let pixelWidth: Int
    let pixelHeight: Int
    let isPlaceholder: Bool
    let modifiedAt: Date?
    let path: String

    static func missing(quality: String, path: String = "-") -> ArtworkCacheDiagnostic {
        ArtworkCacheDiagnostic(
            quality: quality,
            exists: false,
            bytes: 0,
            pixelWidth: 0,
            pixelHeight: 0,
            isPlaceholder: false,
            modifiedAt: nil,
            path: path
        )
    }
}

struct ConnectionDiagnosticSnapshot: Equatable {
    let connectionStatus: String
    let displayState: String
    let healthState: String
    let autoReconnectState: String
    let autoReconnectAttempt: Int
    let mtuBytes: Int
    let lastNotifyAgeMs: Int64
    let peripheralState: String
    let characteristicReady: Bool
    let probeInFlight: Bool
    let lastHardReconnectReason: String
    let reconnectWorkItemExists: Bool
}

struct AlbumArtTransferDiagnosticSnapshot: Equatable {
    let state: String
    let quality: String
    let receivedChunks: Int
    let totalChunks: Int
    let lastFailureReason: String
    let previewRetryCount: Int
    let hqRetryCount: Int
}

struct CurrentWordDiagnosticSnapshot: Equatable {
    let lineIndex: Int
    let wordIndex: Int
    let pushCount: Int64
    let dropCount: Int64
    let averageUpdateIntervalMs: Int64
    let lastLatencyMs: Int64
}

struct NowPlayingDiagnosticSnapshot {
    let generatedAt: Date
    let title: String
    let artist: String
    let album: String
    let trackId: String
    let albumArtId: String
    let albumArtDisplayQuality: String
    let displayArtworkPixelWidth: Int
    let displayArtworkPixelHeight: Int
    let artworkEnhancementStatus: ArtworkEnhancementDebugStatus
    let artworkCaches: [ArtworkCacheDiagnostic]
    let hqUnavailableReason: String
    let hqUnavailableBestBytes: Int
    let hqUnavailableBestChunks: Int
    let hqUnavailableMinCandidateScale: Int
    let albumArtTransfer: AlbumArtTransferDiagnosticSnapshot
    let isPlaying: Bool
    let positionMs: Int64
    let durationMs: Int64
    let currentLyric: String
    let lyricDiagnostic: LyricDiagnostic?
    let fullLyricsLineCount: Int
    let isFullLyricsCurrent: Bool
    let isFullLyricsReceiving: Bool
    let currentWord: CurrentWordDiagnosticSnapshot
    let connection: ConnectionDiagnosticSnapshot
    let selfHealing: SelfHealingSnapshot

    var quickSnapshotText: String {
        """
        [Quick Snapshot]
        time=\(Self.dateTimeFormatter.string(from: generatedAt))
        displayState=\(connection.displayState)
        health=\(connection.healthState)
        lyricStatus=\(lyricDiagnostic?.status ?? "-")
        lyricReason=\(lyricDiagnostic?.reason ?? "-")
        artworkQuality=\(albumArtDisplayQuality)
        artworkTransferState=\(albumArtTransfer.state)
        artworkTransferReason=\(albumArtTransfer.lastFailureReason)
        currentWordLine=\(currentWord.lineIndex)
        currentWordIndex=\(currentWord.wordIndex)
        currentWordPushCount=\(currentWord.pushCount)
        currentWordDropCount=\(currentWord.dropCount)
        selfHealing=\(selfHealing.overallStatus)
        trackId=\(trackId)
        """
    }

    var recentIssues: [String] {
        var issues: [String] = []
        if let lyricDiagnostic {
            let status = lyricDiagnostic.status
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if status != "loaded", status != "loading" {
                issues.append("歌词：\(lyricDiagnostic.statusTitle)")
            } else if status == "loading" {
                issues.append("歌词：正在加载")
            }
        } else if currentLyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append("歌词：暂无诊断数据")
        }

        let hqCache = artworkCaches.first { $0.quality == "hq" }
        if albumArtDisplayQuality == "placeholder" {
            if albumArtTransfer.state == "timeout" || albumArtTransfer.state == "failed" {
                issues.append("封面：传输中断，\(albumArtTransfer.lastFailureReason)")
            } else {
                issues.append("封面：当前没有可显示封面")
            }
        } else if !hqUnavailableReason.isEmpty,
                  hqUnavailableReason != "-" {
            issues.append("封面：HQ 未生成，\(hqUnavailableReason)")
        } else if albumArtDisplayQuality == "preview", hqCache?.exists != true {
            issues.append("封面：仅 Preview，HQ 不存在")
        } else if albumArtDisplayQuality == "hq",
                  artworkEnhancementStatus.enabled,
                  artworkEnhancementStatus.displayQuality != .enhanced {
            issues.append("封面：HQ 可用，增强图未显示")
        }

        if connection.displayState != "connected" {
            issues.append("连接：\(connection.displayState)")
        } else if connection.healthState != "healthy" {
            issues.append("连接：\(connection.healthState)")
        } else if connection.lastNotifyAgeMs > 5_000 {
            issues.append("连接：\(connection.lastNotifyAgeMs)ms 未收到通知")
        }

        return issues.isEmpty ? ["状态正常"] : issues
    }

    var canRequestHqArtwork: Bool {
        !albumArtId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            connection.displayState == "connected" &&
            connection.characteristicReady &&
            connection.healthState != "stale" &&
            connection.healthState != "disconnected"
    }

    var canForceReconnect: Bool {
        connection.displayState == "connected" ||
            connection.displayState == "reconnecting" ||
            connection.displayState == "connecting" ||
            connection.healthState == "suspect" ||
            connection.healthState == "stale"
    }

    var diagnosticText: String {
        var lines: [String] = []
        lines.append(quickSnapshotText)
        lines.append("")
        lines.append("Now Playing Diagnostics")
        lines.append("generatedAt: \(Self.dateTimeFormatter.string(from: generatedAt))")
        lines.append("")
        lines.append("[Track]")
        lines.append("title: \(title)")
        lines.append("artist: \(artist)")
        lines.append("album: \(album)")
        lines.append("trackId: \(trackId)")
        lines.append("albumArtId: \(albumArtId)")
        lines.append("playing: \(isPlaying)")
        lines.append("position: \(Self.formatDuration(positionMs)) / \(Self.formatDuration(durationMs))")
        lines.append("currentLyric: \(currentLyric)")
        lines.append("")
        lines.append("[Lyrics]")
        if let lyricDiagnostic {
            lines.append("status: \(lyricDiagnostic.status)")
            lines.append("statusTitle: \(lyricDiagnostic.statusTitle)")
            lines.append("reason: \(lyricDiagnostic.reason)")
            lines.append("humanReason: \(lyricDiagnostic.humanReadableReason)")
            lines.append("suggestion: \(lyricDiagnostic.suggestion)")
            lines.append("suggestionText: \(lyricDiagnostic.suggestionText)")
            lines.append("source: \(lyricDiagnostic.source)")
            lines.append("lines: \(lyricDiagnostic.lines)")
            lines.append("retryCount: \(lyricDiagnostic.retryCount)")
            lines.append("waitingQqMusicCache: \(lyricDiagnostic.waitingQqMusicCache)")
        } else {
            lines.append("diagnostic: nil")
        }
        lines.append("fullLyricsLineCount: \(fullLyricsLineCount)")
        lines.append("isFullLyricsCurrent: \(isFullLyricsCurrent)")
        lines.append("isFullLyricsReceiving: \(isFullLyricsReceiving)")
        lines.append("currentWordLine: \(currentWord.lineIndex)")
        lines.append("currentWordIndex: \(currentWord.wordIndex)")
        lines.append("currentWordPushCount: \(currentWord.pushCount)")
        lines.append("currentWordDropCount: \(currentWord.dropCount)")
        lines.append("currentWordAverageIntervalMs: \(currentWord.averageUpdateIntervalMs)")
        lines.append("currentWordLastLatencyMs: \(currentWord.lastLatencyMs)")
        lines.append("")
        lines.append("[Artwork]")
        lines.append("displayQuality: \(albumArtDisplayQuality)")
        lines.append("displayPixels: \(displayArtworkPixelWidth)x\(displayArtworkPixelHeight)")
        lines.append("enhancementEnabled: \(artworkEnhancementStatus.enabled)")
        lines.append("enhancementTarget: \(artworkEnhancementStatus.target)")
        lines.append("enhancementSharpness: \(String(format: "%.2f", artworkEnhancementStatus.sharpness))")
        lines.append("enhancementLastCostMs: \(artworkEnhancementStatus.lastProcessingCostMs)")
        lines.append("enhancementEdgeGain: \(String(format: "%.2f", artworkEnhancementStatus.lastEdgeGainPercent))")
        lines.append("enhancementMessage: \(artworkEnhancementStatus.lastMessage)")
        lines.append("hqUnavailableReason: \(hqUnavailableReason)")
        lines.append("hqUnavailableBestBytes: \(hqUnavailableBestBytes)")
        lines.append("hqUnavailableBestChunks: \(hqUnavailableBestChunks)")
        lines.append("hqUnavailableMinCandidateScale: \(hqUnavailableMinCandidateScale)")
        lines.append("transferState: \(albumArtTransfer.state)")
        lines.append("transferQuality: \(albumArtTransfer.quality)")
        lines.append(
            "transferChunks: \(albumArtTransfer.receivedChunks)/\(albumArtTransfer.totalChunks)"
        )
        lines.append("transferLastFailure: \(albumArtTransfer.lastFailureReason)")
        lines.append("previewRetryCount: \(albumArtTransfer.previewRetryCount)")
        lines.append("hqRetryCount: \(albumArtTransfer.hqRetryCount)")
        for cache in artworkCaches {
            lines.append(
                "\(cache.quality): exists=\(cache.exists) bytes=\(cache.bytes) " +
                    "pixels=\(cache.pixelWidth)x\(cache.pixelHeight) " +
                    "placeholder=\(cache.isPlaceholder) modified=\(Self.optionalDate(cache.modifiedAt))"
            )
            lines.append("  path: \(cache.path)")
        }
        lines.append("")
        lines.append("[Connection]")
        lines.append("status: \(connection.connectionStatus)")
        lines.append("displayState: \(connection.displayState)")
        lines.append("healthState: \(connection.healthState)")
        lines.append("autoReconnectState: \(connection.autoReconnectState)")
        lines.append("autoReconnectAttempt: \(connection.autoReconnectAttempt)")
        lines.append("mtuBytes: \(connection.mtuBytes)")
        lines.append("lastNotifyAgeMs: \(connection.lastNotifyAgeMs)")
        lines.append("peripheralState: \(connection.peripheralState)")
        lines.append("characteristicReady: \(connection.characteristicReady)")
        lines.append("probeInFlight: \(connection.probeInFlight)")
        lines.append("lastHardReconnectReason: \(connection.lastHardReconnectReason)")
        lines.append("reconnectWorkItemExists: \(connection.reconnectWorkItemExists)")
        lines.append("")
        lines.append("[SelfHealing]")
        lines.append("overallStatus: \(selfHealing.overallStatus)")
        lines.append("activeCount: \(selfHealing.metrics.activeCount)")
        lines.append("detectCount: \(selfHealing.metrics.detectCount)")
        lines.append("recoverCount: \(selfHealing.metrics.recoverCount)")
        lines.append("verifyCount: \(selfHealing.metrics.verifyCount)")
        lines.append("successCount: \(selfHealing.metrics.successCount)")
        lines.append("failCount: \(selfHealing.metrics.failCount)")
        lines.append("diagnosticsCount: \(selfHealing.metrics.diagnosticsCount)")
        for report in selfHealing.reports {
            lines.append(
                "\(report.domain.title): stage=\(report.state.stage.rawValue) " +
                    "active=\(report.state.isActive) result=\(report.result.status) " +
                    "reason=\(report.state.reason)"
            )
            lines.append("  detect: \(report.detectedIssue)")
            lines.append("  recover: \(report.recoveryAction)")
            lines.append("  verify: \(report.verifySignal)")
        }
        return lines.joined(separator: "\n")
    }

    static func formatDuration(_ value: Int64) -> String {
        guard value > 0 else { return "00:00" }
        let totalSeconds = max(0, value / 1_000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02lld:%02lld", minutes, seconds)
    }

    static func optionalDate(_ date: Date?) -> String {
        guard let date else { return "-" }
        return dateTimeFormatter.string(from: date)
    }

    private static let dateTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
    }()
}

enum SystemHealthLevel: String {
    case ok
    case working
    case warning
    case critical
    case unknown

    var badgeTitle: String {
        switch self {
        case .ok: return "正常"
        case .working: return "处理中"
        case .warning: return "注意"
        case .critical: return "异常"
        case .unknown: return "未知"
        }
    }

    var rank: Int {
        switch self {
        case .critical: return 4
        case .warning: return 3
        case .working: return 2
        case .unknown: return 1
        case .ok: return 0
        }
    }
}

struct SystemHealthField: Identifiable {
    let id = UUID()
    let title: String
    let value: String
}

struct SystemHealthCardSnapshot: Identifiable {
    let id: String
    let title: String
    let systemImage: String
    let status: String
    let level: SystemHealthLevel
    let summary: String
    let fields: [SystemHealthField]
}

struct SystemHealthSnapshot {
    let generatedAt: Date
    let overallStatus: String
    let overallLevel: SystemHealthLevel
    let connection: SystemHealthCardSnapshot
    let lyric: SystemHealthCardSnapshot
    let artwork: SystemHealthCardSnapshot
    let sony: SystemHealthCardSnapshot
    let selfHealing: SystemHealthCardSnapshot
    let recommendation: String
    let source: NowPlayingDiagnosticSnapshot

    init(nowPlaying source: NowPlayingDiagnosticSnapshot) {
        self.generatedAt = Date()
        self.source = source
        self.connection = Self.makeConnectionCard(source)
        self.lyric = Self.makeLyricCard(source)
        self.artwork = Self.makeArtworkCard(source)
        self.sony = Self.makeSonyCard(source)
        self.selfHealing = Self.makeSelfHealingCard(source)
        self.recommendation = Self.makeRecommendation(
            connection: connection,
            lyric: lyric,
            artwork: artwork,
            sony: sony,
            selfHealing: selfHealing,
            source: source
        )
        let worst = [connection.level, lyric.level, artwork.level, sony.level, selfHealing.level].max {
            $0.rank < $1.rank
        } ?? .unknown
        self.overallLevel = worst
        self.overallStatus = worst == .ok ? "系统正常" : worst.badgeTitle
    }

    var copyText: String {
        var lines: [String] = []
        lines.append("系统健康总览")
        lines.append("时间：\(NowPlayingDiagnosticSnapshot.optionalDate(generatedAt))")
        lines.append("")
        appendCard(connection, to: &lines)
        appendCard(lyric, to: &lines)
        appendCard(artwork, to: &lines)
        appendCard(sony, to: &lines)
        appendCard(selfHealing, to: &lines)
        lines.append("")
        lines.append("[建议]")
        lines.append(recommendation)
        return lines.joined(separator: "\n")
    }

    private func appendCard(_ card: SystemHealthCardSnapshot, to lines: inout [String]) {
        lines.append("[\(card.title)]")
        lines.append("状态=\(card.status)")
        lines.append("说明=\(card.summary)")
        for field in card.fields {
            lines.append("\(field.title)=\(field.value)")
        }
        lines.append("")
    }

    private static func makeConnectionCard(_ source: NowPlayingDiagnosticSnapshot) -> SystemHealthCardSnapshot {
        let connection = source.connection
        let display = connection.displayState
        let health = connection.healthState
        let status: String
        let level: SystemHealthLevel
        let summary: String
        if display == "connected", health == "healthy" {
            status = "正常"
            level = .ok
            summary = "连接正常。"
        } else if display == "reconnecting" || connection.autoReconnectState == "scanning" ||
                    connection.autoReconnectState == "connecting" {
            status = "正在重连"
            level = .working
            summary = "正在重新连接 Sony。"
        } else if display == "connected", health == "suspect" {
            status = "连接异常"
            level = .warning
            summary = "长时间未收到 Sony 状态，正在确认连接是否可用。"
        } else if display == "connected", health == "stale" {
            status = "连接异常"
            level = .critical
            summary = "长时间未收到 Sony 状态，正在恢复连接。"
        } else {
            status = display == "disconnected" ? "未连接" : "连接异常"
            level = .critical
            summary = "当前未建立可用的 Sony 连接。"
        }
        return SystemHealthCardSnapshot(
            id: "connection",
            title: "连接",
            systemImage: "antenna.radiowaves.left.and.right",
            status: status,
            level: level,
            summary: summary,
            fields: [
                .init(title: "displayState", value: connection.displayState),
                .init(title: "healthState", value: connection.healthState),
                .init(title: "autoReconnectState", value: connection.autoReconnectState),
                .init(title: "MTU", value: connection.mtuBytes > 0 ? "\(connection.mtuBytes)" : "unknown"),
                .init(
                    title: "lastNotifyAge",
                    value: connection.lastNotifyAgeMs >= 0 ? "\(connection.lastNotifyAgeMs)ms" : "unknown"
                ),
                .init(title: "lastReconnectReason", value: connection.lastHardReconnectReason),
                .init(title: "reconnectWorkItemExists", value: connection.reconnectWorkItemExists ? "true" : "false")
            ]
        )
    }

    private static func makeLyricCard(_ source: NowPlayingDiagnosticSnapshot) -> SystemHealthCardSnapshot {
        guard let diagnostic = source.lyricDiagnostic else {
            return SystemHealthCardSnapshot(
                id: "lyric",
                title: "歌词",
                systemImage: "text.magnifyingglass",
                status: "未知",
                level: .unknown,
                summary: "尚未获取 Sony 歌词诊断。",
                fields: [.init(title: "lyricStatus", value: "unknown")]
            )
        }
        let normalized = diagnostic.status.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let status: String
        let level: SystemHealthLevel
        switch normalized {
        case "loaded":
            status = "正常"
            level = .ok
        case "loading", "retry_pending":
            status = "加载中"
            level = .working
        case "waiting_qqmusic_cache":
            status = "等待 QQ音乐生成歌词缓存"
            level = .working
        case "no_safe_candidate":
            status = "无安全歌词候选"
            level = .warning
        case "maintenance_busy":
            status = "维护任务中"
            level = .working
        default:
            status = diagnostic.statusTitle
            level = diagnostic.lines > 0 ? .ok : .warning
        }
        return SystemHealthCardSnapshot(
            id: "lyric",
            title: "歌词",
            systemImage: "quote.bubble",
            status: status,
            level: level,
            summary: diagnostic.suggestionText,
            fields: [
                .init(title: "lyricStatus", value: diagnostic.status),
                .init(title: "lyricReason", value: diagnostic.reason.isEmpty ? "unknown" : diagnostic.reason),
                .init(title: "lyricSuggestion", value: diagnostic.suggestion.isEmpty ? "unknown" : diagnostic.suggestion),
                .init(title: "recoveryState", value: diagnostic.recoveryState),
                .init(title: "retryCount", value: "\(diagnostic.retryCount)"),
                .init(
                    title: "nextRetryAt",
                    value: diagnostic.nextRetryAt > 0 ? Self.formatTimestamp(diagnostic.nextRetryAt) : "unknown"
                ),
                .init(title: "lines", value: "\(diagnostic.lines)")
            ]
        )
    }

    private static func makeArtworkCard(_ source: NowPlayingDiagnosticSnapshot) -> SystemHealthCardSnapshot {
        let quality = source.albumArtDisplayQuality
        let transfer = source.albumArtTransfer
        let preview = source.artworkCaches.first { $0.quality == "preview" }
        let hq = source.artworkCaches.first { $0.quality == "hq" }
        let enhanced = source.artworkCaches.first { $0.quality == "enhanced" }
        let status: String
        let level: SystemHealthLevel
        let summary: String
        if transfer.state == "timeout" || transfer.state == "failed" {
            status = "传输中断"
            level = .warning
            summary = "封面传输中断，可重新请求。"
        } else if source.hqUnavailableReason.lowercased().contains("too large") ||
                    source.hqUnavailableReason.contains("过大") {
            status = "图片过大"
            level = .warning
            summary = "HQ 图片过大，已使用 fallback 或 preview。"
        } else if quality == "enhanced" || quality == "hq" {
            status = "HQ 正常"
            level = .ok
            summary = "当前显示高清封面。"
        } else if quality == "preview" {
            status = "Preview"
            level = .warning
            summary = "当前只有预览封面。"
        } else {
            status = "默认图"
            level = .warning
            summary = "当前没有可显示封面。"
        }
        return SystemHealthCardSnapshot(
            id: "artwork",
            title: "封面",
            systemImage: "photo",
            status: status,
            level: level,
            summary: summary,
            fields: [
                .init(title: "displayQuality", value: quality),
                .init(title: "preview", value: Self.cacheSummary(preview)),
                .init(title: "hq", value: Self.cacheSummary(hq)),
                .init(title: "enhanced", value: Self.cacheSummary(enhanced)),
                .init(title: "transferState", value: transfer.state),
                .init(title: "lastFailureReason", value: transfer.lastFailureReason),
                .init(title: "hqUnavailableReason", value: source.hqUnavailableReason.isEmpty ? "-" : source.hqUnavailableReason),
                .init(
                    title: "fallbackScale",
                    value: source.hqUnavailableMinCandidateScale > 0 ? "\(source.hqUnavailableMinCandidateScale)" : "unknown"
                )
            ]
        )
    }

    private static func makeSonyCard(_ source: NowPlayingDiagnosticSnapshot) -> SystemHealthCardSnapshot {
        let diagnostic = source.lyricDiagnostic
        let maintenance = diagnostic?.maintenanceBusy == true
        let waiting = diagnostic?.waitingQqMusicCache == true
        let recovery = diagnostic?.recoveryState ?? "unknown"
        let status: String
        let level: SystemHealthLevel
        let summary: String
        if maintenance {
            status = "缓存维护中"
            level = .working
            summary = "Sony 正在处理歌词缓存维护任务。"
        } else if waiting {
            status = "等待 QQ音乐缓存"
            level = .working
            summary = "QQ音乐可能还没生成当前歌曲歌词缓存。"
        } else if recovery != "unknown", recovery.lowercased() != "idle" {
            status = "歌词恢复中"
            level = .working
            summary = "Sony 正在尝试恢复当前歌曲歌词。"
        } else {
            status = "正常"
            level = .ok
            summary = "Sony 服务和缓存状态正常。"
        }
        return SystemHealthCardSnapshot(
            id: "sony",
            title: "Sony / 缓存",
            systemImage: "externaldrive.connected.to.line.below",
            status: status,
            level: level,
            summary: summary,
            fields: [
                .init(title: "maintenanceBusy", value: diagnostic.map { $0.maintenanceBusy ? "true" : "false" } ?? "unknown"),
                .init(title: "recoveryState", value: recovery),
                .init(title: "fuzzyIndexReady", value: diagnostic.map { $0.fuzzyIndexReady ? "true" : "false" } ?? "unknown"),
                .init(title: "qrcIndexLoaded", value: diagnostic.map { $0.qrcIndexLoaded ? "true" : "false" } ?? "unknown"),
                .init(title: "waitingQqMusicCache", value: diagnostic.map { $0.waitingQqMusicCache ? "true" : "false" } ?? "unknown"),
                .init(title: "recentQrcCandidateCount", value: diagnostic.map { "\($0.recentQrcCandidateCount)" } ?? "unknown")
            ]
        )
    }

    private static func makeSelfHealingCard(_ source: NowPlayingDiagnosticSnapshot) -> SystemHealthCardSnapshot {
        let snapshot = source.selfHealing
        let level: SystemHealthLevel
        switch snapshot.overallSeverity {
        case .normal:
            level = .ok
        case .working:
            level = .working
        case .warning:
            level = .warning
        case .critical:
            level = .critical
        }
        var fields: [SystemHealthField] = [
            .init(title: "active", value: "\(snapshot.metrics.activeCount)"),
            .init(title: "detect", value: "\(snapshot.metrics.detectCount)"),
            .init(title: "recover", value: "\(snapshot.metrics.recoverCount)"),
            .init(title: "verify", value: "\(snapshot.metrics.verifyCount)"),
            .init(title: "success", value: "\(snapshot.metrics.successCount)"),
            .init(title: "fail", value: "\(snapshot.metrics.failCount)")
        ]
        fields.append(contentsOf: snapshot.reports.map {
            .init(
                title: $0.domain.title,
                value: "\($0.state.stage.title) / \($0.result.status)"
            )
        })
        return SystemHealthCardSnapshot(
            id: "selfHealing",
            title: "Self-Healing",
            systemImage: "cross.case",
            status: snapshot.overallStatus,
            level: level,
            summary: snapshot.summaryText,
            fields: fields
        )
    }

    private static func makeRecommendation(
        connection: SystemHealthCardSnapshot,
        lyric: SystemHealthCardSnapshot,
        artwork: SystemHealthCardSnapshot,
        sony: SystemHealthCardSnapshot,
        selfHealing: SystemHealthCardSnapshot,
        source: NowPlayingDiagnosticSnapshot
    ) -> String {
        if connection.level == .critical || connection.status == "正在重连" {
            return "先恢复 Sony 连接。"
        }
        if source.lyricDiagnostic?.waitingQqMusicCache == true ||
            lyric.status == "等待 QQ音乐生成歌词缓存" {
            return "在 Sony QQ音乐打开歌词或桌面歌词后稍等。"
        }
        if artwork.status == "传输中断" {
            return "封面传输中断，可点击重试封面。"
        }
        if artwork.status == "图片过大" {
            return "HQ 图片过大，已使用 fallback 或 preview。"
        }
        if selfHealing.level == .warning || selfHealing.level == .critical {
            return "查看 Self-Healing 报告中的失败或诊断项。"
        }
        if [connection, lyric, artwork, sony, selfHealing].allSatisfy({ $0.level == .ok }) {
            return "当前状态正常。"
        }
        return "优先查看标记为注意或异常的项目。"
    }

    private static func cacheSummary(_ cache: ArtworkCacheDiagnostic?) -> String {
        guard let cache else { return "unknown" }
        guard cache.exists else { return "missing" }
        return "\(cache.pixelWidth)x\(cache.pixelHeight), \(cache.bytes) bytes"
    }

    private static func formatTimestamp(_ value: Int64) -> String {
        guard value > 0 else { return "unknown" }
        return NowPlayingDiagnosticSnapshot.optionalDate(
            Date(timeIntervalSince1970: TimeInterval(value) / 1_000)
        )
    }
}
