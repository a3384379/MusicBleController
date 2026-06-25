import Foundation

enum RecoveryDomain: String, CaseIterable {
    case albumArt
    case lyrics
    case ble
    case health

    var title: String {
        switch self {
        case .albumArt: return "AlbumArt"
        case .lyrics: return "Lyrics"
        case .ble: return "BLE"
        case .health: return "Health"
        }
    }
}

enum RecoveryStage: String {
    case idle
    case detect
    case recover
    case verify
    case success
    case fail
    case diagnostics

    var title: String {
        switch self {
        case .idle: return "Idle"
        case .detect: return "Detect"
        case .recover: return "Recover"
        case .verify: return "Verify"
        case .success: return "Success"
        case .fail: return "Fail"
        case .diagnostics: return "Diagnostics"
        }
    }
}

enum RecoverySeverity: String {
    case normal
    case working
    case warning
    case critical

    var rank: Int {
        switch self {
        case .critical: return 4
        case .warning: return 3
        case .working: return 2
        case .normal: return 0
        }
    }
}

struct RecoveryState: Equatable {
    let domain: RecoveryDomain
    let stage: RecoveryStage
    let isActive: Bool
    let reason: String
    let updatedAt: Date
}

struct RecoveryResult: Equatable {
    let status: String
    let succeeded: Bool?
    let message: String
}

struct RecoveryReport: Identifiable, Equatable {
    var id: String { domain.rawValue }
    let domain: RecoveryDomain
    let severity: RecoverySeverity
    let state: RecoveryState
    let result: RecoveryResult
    let detectedIssue: String
    let recoveryAction: String
    let verifySignal: String
    let diagnosticsHint: String
    let generatedAt: Date

    var summary: String {
        if state.stage == .idle {
            return result.message
        }
        return "\(state.stage.title): \(detectedIssue)"
    }
}

struct RecoveryHistoryEntry: Identifiable, Equatable {
    let id = UUID()
    let date: Date
    let domain: RecoveryDomain
    let stage: RecoveryStage
    let reason: String
    let resultStatus: String
}

struct RecoveryHistory: Equatable {
    var entries: [RecoveryHistoryEntry] = []
}

struct RecoveryMetrics: Equatable {
    var detectCount = 0
    var recoverCount = 0
    var verifyCount = 0
    var successCount = 0
    var failCount = 0
    var diagnosticsCount = 0
    var activeCount = 0

    mutating func record(stage: RecoveryStage) {
        switch stage {
        case .detect:
            detectCount += 1
        case .recover:
            recoverCount += 1
        case .verify:
            verifyCount += 1
        case .success:
            successCount += 1
        case .fail:
            failCount += 1
        case .diagnostics:
            diagnosticsCount += 1
        case .idle:
            break
        }
    }
}

struct SelfHealingSnapshot: Equatable {
    let generatedAt: Date
    let reports: [RecoveryReport]
    let history: RecoveryHistory
    let metrics: RecoveryMetrics

    static let empty = SelfHealingSnapshot(
        generatedAt: Date(),
        reports: [],
        history: RecoveryHistory(),
        metrics: RecoveryMetrics()
    )

    var activeReports: [RecoveryReport] {
        reports.filter { $0.state.isActive }
    }

    var overallSeverity: RecoverySeverity {
        reports.map(\.severity).max { $0.rank < $1.rank } ?? .normal
    }

    var overallStatus: String {
        if activeReports.isEmpty {
            return "状态正常"
        }
        switch overallSeverity {
        case .critical: return "恢复失败"
        case .warning: return "需要关注"
        case .working: return "恢复中"
        case .normal: return "状态正常"
        }
    }

    var summaryText: String {
        let active = activeReports
        guard !active.isEmpty else {
            return "Self-Healing Engine 已就绪，当前没有需要恢复的异常。"
        }
        return active.map { "\($0.domain.title): \($0.summary)" }.joined(separator: "\n")
    }
}

final class SelfHealingEngine {
    static let shared = SelfHealingEngine()

    private var lastSignatures: [RecoveryDomain: String] = [:]
    private var history = RecoveryHistory()
    private var metrics = RecoveryMetrics()

    private init() {}

    func evaluate(
        trackId: String,
        title: String,
        connection: ConnectionDiagnosticSnapshot,
        artwork: AlbumArtSnapshot,
        lyric: LyricDiagnostic?,
        currentLyric: String,
        fullLyricsLineCount: Int,
        isFullLyricsCurrent: Bool
    ) -> SelfHealingSnapshot {
        let now = Date()
        let reports = [
            makeAlbumArtReport(artwork: artwork, now: now),
            makeLyricsReport(
                lyric: lyric,
                currentLyric: currentLyric,
                fullLyricsLineCount: fullLyricsLineCount,
                isFullLyricsCurrent: isFullLyricsCurrent,
                now: now
            ),
            makeBLEReport(connection: connection, now: now),
            makeHealthReport(connection: connection, now: now)
        ]

        for report in reports {
            recordIfChanged(report, trackId: trackId, title: title)
        }

        var currentMetrics = metrics
        currentMetrics.activeCount = reports.filter { $0.state.isActive }.count
        return SelfHealingSnapshot(
            generatedAt: now,
            reports: reports,
            history: history,
            metrics: currentMetrics
        )
    }

    private func makeAlbumArtReport(artwork: AlbumArtSnapshot, now: Date) -> RecoveryReport {
        let transfer = artwork.transfer
        let displayQuality = artwork.displayQuality.label
        let failure = transfer.lastFailureReason.trimmingCharacters(in: .whitespacesAndNewlines)
        let timeout = transfer.state == "timeout" || failure.lowercased().contains("timeout")
        let failed = transfer.state == "failed"
        let receiving = transfer.state == "receiving"
        let hasDisplay = artwork.displayQuality != .placeholder

        if timeout || failed {
            return report(
                domain: .albumArt,
                severity: timeout ? .working : .warning,
                stage: .recover,
                active: true,
                reason: failure.isEmpty || failure == "-" ? transfer.state : failure,
                issue: timeout ? "封面传输超时" : "封面传输失败",
                action: "沿用 AlbumArtReceiver 现有 timeout 清理、preview retry 与 HQ request 策略。",
                verify: "等待 displayQuality 变为 preview / hq / enhanced，且 transferState 回到 idle。",
                resultStatus: "pending",
                resultMessage: "现有 AlbumArt 恢复链路正在处理。",
                now: now
            )
        }

        if receiving {
            return report(
                domain: .albumArt,
                severity: .working,
                stage: .verify,
                active: true,
                reason: "transfer receiving \(transfer.receivedChunks)/\(transfer.totalChunks)",
                issue: "封面二进制传输进行中",
                action: "由 AlbumArtReceiver 继续接收 binary chunk，并保持 timeout 保护。",
                verify: "收到 binaryEnd 并写入 cache。",
                resultStatus: "pending",
                resultMessage: "正在验证封面传输。",
                now: now
            )
        }

        return report(
            domain: .albumArt,
            severity: .normal,
            stage: hasDisplay ? .success : .idle,
            active: false,
            reason: "displayQuality=\(displayQuality)",
            issue: hasDisplay ? "封面可显示" : "暂无封面异常恢复任务",
            action: "无额外动作。",
            verify: "displayQuality=\(displayQuality)",
            resultStatus: hasDisplay ? "success" : "idle",
            resultMessage: hasDisplay ? "封面链路正常。" : "封面恢复框架待命。",
            now: now
        )
    }

    private func makeLyricsReport(
        lyric: LyricDiagnostic?,
        currentLyric: String,
        fullLyricsLineCount: Int,
        isFullLyricsCurrent: Bool,
        now: Date
    ) -> RecoveryReport {
        guard let lyric else {
            return report(
                domain: .lyrics,
                severity: .warning,
                stage: .diagnostics,
                active: true,
                reason: "lyricDiagnostic=nil",
                issue: "缺少歌词诊断",
                action: "沿用现有 GET_LYRIC_DIAGNOSTIC 请求入口。",
                verify: "收到 LyricDiagnostic。",
                resultStatus: "diagnostics",
                resultMessage: "需要歌词诊断数据。",
                now: now
            )
        }

        let status = lyric.status.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let hasLyrics = lyric.lines > 0 ||
            !currentLyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            (isFullLyricsCurrent && fullLyricsLineCount > 0)

        if status == "waiting_qqmusic_cache" || lyric.waitingQqMusicCache {
            return report(
                domain: .lyrics,
                severity: .working,
                stage: .recover,
                active: true,
                reason: lyric.reason.isEmpty ? "waiting qqmusic lyric cache" : lyric.reason,
                issue: "等待 QQ音乐生成歌词缓存",
                action: "沿用 Sony Lyric Recovery 的 watcher / retry wait window。",
                verify: "LyricDiagnostic lines > 0 或 current lyric 非空。",
                resultStatus: "pending",
                resultMessage: "Sony 正在等待可访问 QRC 缓存。",
                now: now
            )
        }

        if status == "loading" || status == "retry_pending" || lyric.recoveryState.lowercased() != "idle" &&
            lyric.recoveryState.lowercased() != "unknown" {
            return report(
                domain: .lyrics,
                severity: .working,
                stage: .recover,
                active: true,
                reason: lyric.reason.isEmpty ? lyric.recoveryState : lyric.reason,
                issue: "歌词恢复任务进行中",
                action: "沿用 Sony 当前 retry / recovery 状态机。",
                verify: "歌词行数或 current lyric 更新。",
                resultStatus: "pending",
                resultMessage: "歌词恢复链路正在处理。",
                now: now
            )
        }

        if !hasLyrics, status == "no_safe_candidate" || status == "no_lyrics_final" || status == "error" {
            return report(
                domain: .lyrics,
                severity: .warning,
                stage: .diagnostics,
                active: true,
                reason: lyric.reason.isEmpty ? status : lyric.reason,
                issue: lyric.statusTitle,
                action: "不强绑低置信度歌词，仅暴露诊断建议。",
                verify: "用户刷新歌词诊断或 Sony watcher 发现新 QRC。",
                resultStatus: "diagnostics",
                resultMessage: "需要诊断或等待新的歌词来源。",
                now: now
            )
        }

        return report(
            domain: .lyrics,
            severity: .normal,
            stage: hasLyrics ? .success : .idle,
            active: false,
            reason: "status=\(lyric.status) lines=\(lyric.lines)",
            issue: hasLyrics ? "歌词可用" : "暂无歌词恢复任务",
            action: "无额外动作。",
            verify: "lines=\(lyric.lines), fullLyrics=\(fullLyricsLineCount)",
            resultStatus: hasLyrics ? "success" : "idle",
            resultMessage: hasLyrics ? "歌词链路正常。" : "歌词恢复框架待命。",
            now: now
        )
    }

    private func makeBLEReport(connection: ConnectionDiagnosticSnapshot, now: Date) -> RecoveryReport {
        if connection.healthState == "stale" {
            return report(
                domain: .ble,
                severity: .working,
                stage: .recover,
                active: true,
                reason: "health=stale lastNotifyAgeMs=\(connection.lastNotifyAgeMs)",
                issue: "BLE 状态通知陈旧",
                action: "沿用 Health Check 现有 hard reconnect 触发逻辑。",
                verify: "收到 playbackState / trackInfo / volumeState 并回到 healthy。",
                resultStatus: "pending",
                resultMessage: "BLE stale 正在交给现有重连链路恢复。",
                now: now
            )
        }

        if connection.healthState == "suspect" {
            return report(
                domain: .ble,
                severity: .working,
                stage: .detect,
                active: true,
                reason: "health=suspect probe=\(connection.probeInFlight)",
                issue: "BLE 通知间隔异常",
                action: "沿用 Health Check probe 发送 GET_PLAYBACK_STATE。",
                verify: "probe 收到任意有效 status notify。",
                resultStatus: "pending",
                resultMessage: "正在确认连接是否假连接。",
                now: now
            )
        }

        if connection.displayState == "disconnected" {
            return report(
                domain: .ble,
                severity: .warning,
                stage: .diagnostics,
                active: true,
                reason: "display=disconnected",
                issue: "BLE 未连接",
                action: "等待用户或 AutoReconnect 启动扫描。",
                verify: "displayState=connected。",
                resultStatus: "diagnostics",
                resultMessage: "当前没有可用 BLE 连接。",
                now: now
            )
        }

        return report(
            domain: .ble,
            severity: .normal,
            stage: .success,
            active: false,
            reason: "health=\(connection.healthState)",
            issue: "BLE 连接可用",
            action: "无额外动作。",
            verify: "healthState=\(connection.healthState)",
            resultStatus: "success",
            resultMessage: "BLE 链路正常。",
            now: now
        )
    }

    private func makeHealthReport(connection: ConnectionDiagnosticSnapshot, now: Date) -> RecoveryReport {
        let reconnectingStates: Set<String> = [
            "reconnectScheduled",
            "scanning",
            "connecting",
            "serviceDiscovering",
            "subscribing",
            "syncing"
        ]
        if connection.displayState == "reconnecting" ||
            reconnectingStates.contains(connection.autoReconnectState) {
            return report(
                domain: .health,
                severity: .working,
                stage: .recover,
                active: true,
                reason: "autoReconnectState=\(connection.autoReconnectState)",
                issue: "连接健康恢复中",
                action: "沿用 AutoReconnect scan/connect/discover/subscribe 状态机。",
                verify: "connected + healthy + characteristicReady。",
                resultStatus: "pending",
                resultMessage: "Health Recovery 正在由现有自动重连链路执行。",
                now: now
            )
        }

        if connection.displayState == "connected",
           connection.healthState == "healthy",
           connection.characteristicReady {
            return report(
                domain: .health,
                severity: .normal,
                stage: .success,
                active: false,
                reason: "connected healthy ready",
                issue: "连接健康",
                action: "无额外动作。",
                verify: "characteristicReady=true",
                resultStatus: "success",
                resultMessage: "Health Check 正常。",
                now: now
            )
        }

        return report(
            domain: .health,
            severity: .warning,
            stage: .diagnostics,
            active: true,
            reason: "display=\(connection.displayState) health=\(connection.healthState)",
            issue: "连接健康状态不完整",
            action: "暴露诊断，不额外改变恢复策略。",
            verify: "connected + healthy + characteristicReady。",
            resultStatus: "diagnostics",
            resultMessage: "需要连接健康诊断。",
            now: now
        )
    }

    private func report(
        domain: RecoveryDomain,
        severity: RecoverySeverity,
        stage: RecoveryStage,
        active: Bool,
        reason: String,
        issue: String,
        action: String,
        verify: String,
        resultStatus: String,
        resultMessage: String,
        now: Date
    ) -> RecoveryReport {
        RecoveryReport(
            domain: domain,
            severity: severity,
            state: RecoveryState(
                domain: domain,
                stage: stage,
                isActive: active,
                reason: reason,
                updatedAt: now
            ),
            result: RecoveryResult(
                status: resultStatus,
                succeeded: resultStatus == "success" ? true : (resultStatus == "failed" ? false : nil),
                message: resultMessage
            ),
            detectedIssue: issue,
            recoveryAction: action,
            verifySignal: verify,
            diagnosticsHint: active ? "查看 \(domain.title) 对应诊断卡片。" : "无",
            generatedAt: now
        )
    }

    private func recordIfChanged(_ report: RecoveryReport, trackId: String, title: String) {
        let signature = [
            report.state.stage.rawValue,
            report.state.isActive ? "active" : "idle",
            report.state.reason,
            report.result.status
        ].joined(separator: "|")
        guard lastSignatures[report.domain] != signature else { return }
        lastSignatures[report.domain] = signature
        metrics.record(stage: report.state.stage)
        let entry = RecoveryHistoryEntry(
            date: report.generatedAt,
            domain: report.domain,
            stage: report.state.stage,
            reason: report.state.reason,
            resultStatus: report.result.status
        )
        history.entries.insert(entry, at: 0)
        if history.entries.count > 40 {
            history.entries = Array(history.entries.prefix(40))
        }
        AppLogStore.shared.append(
            "[SelfHealing] domain=\(report.domain.rawValue) " +
                "stage=\(report.state.stage.rawValue) active=\(report.state.isActive) " +
                "result=\(report.result.status) trackId=\(trackId) title=\(title) " +
                "reason=\(report.state.reason)"
        )
    }
}
