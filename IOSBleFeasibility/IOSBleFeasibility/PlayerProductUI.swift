import SwiftUI

enum PlayerDesignTokens {
    static let canvas = Color(red: 6 / 255, green: 9 / 255, blue: 14 / 255)
    static let surface1 = Color(red: 15 / 255, green: 21 / 255, blue: 31 / 255)
    static let surface2 = Color(red: 24 / 255, green: 34 / 255, blue: 48 / 255)
    static let primaryText = Color(red: 245 / 255, green: 247 / 255, blue: 251 / 255)
    static let secondaryText = Color(red: 167 / 255, green: 176 / 255, blue: 189 / 255)
    static let quietText = Color(red: 105 / 255, green: 116 / 255, blue: 132 / 255)
    static let healthy = Color(red: 100 / 255, green: 217 / 255, blue: 140 / 255)
    static let warning = Color(red: 242 / 255, green: 181 / 255, blue: 109 / 255)
    static let disconnected = Color(red: 236 / 255, green: 119 / 255, blue: 116 / 255)
    static let stableAccent = Color(red: 120 / 255, green: 219 / 255, blue: 195 / 255)

    static let smallRadius: CGFloat = 12
    static let cardRadius: CGFloat = 18
    static let heroRadius: CGFloat = 28
    static let minimumHitTarget: CGFloat = 44
}

enum PlayerLayoutMode: Equatable {
    case regular
    case compact
    case accessibility

    static func resolve(availableHeight: CGFloat, dynamicTypeSize: DynamicTypeSize) -> Self {
        resolve(
            availableSize: CGSize(width: 393, height: availableHeight),
            safeAreaInsets: EdgeInsets(),
            dynamicTypeSize: dynamicTypeSize
        )
    }

    static func resolve(
        availableSize: CGSize,
        safeAreaInsets: EdgeInsets,
        dynamicTypeSize: DynamicTypeSize
    ) -> Self {
        if dynamicTypeSize.isAccessibilitySize { return .accessibility }
        let usableHeight = availableSize.height - safeAreaInsets.top - safeAreaInsets.bottom
        let isLandscape = availableSize.width > availableSize.height
        if isLandscape || usableHeight < 735 || availableSize.width < 360 {
            return .compact
        }
        return .regular
    }

    var isCompact: Bool { self != .regular }
}

struct PlayerLayoutMetrics: Equatable {
    let mode: PlayerLayoutMode
    let artworkSize: CGFloat
    let horizontalPadding: CGFloat
    let sectionSpacing: CGFloat
    let lyricHeight: CGFloat

    static func resolve(
        availableSize: CGSize,
        safeAreaInsets: EdgeInsets,
        dynamicTypeSize: DynamicTypeSize,
        artworkPreference: ArtworkDisplaySizeOption
    ) -> Self {
        let mode = PlayerLayoutMode.resolve(
            availableSize: availableSize,
            safeAreaInsets: safeAreaInsets,
            dynamicTypeSize: dynamicTypeSize
        )
        let usableHeight = max(
            availableSize.height - safeAreaInsets.top - safeAreaInsets.bottom,
            1
        )
        let horizontalPadding: CGFloat = availableSize.width >= 430 ? 28 : 20
        let artworkSize: CGFloat
        if mode.isCompact {
            let preferred: CGFloat = switch artworkPreference {
            case .small: 126
            case .medium: 136
            case .large: 144
            }
            artworkSize = min(
                preferred,
                max(112, min(availableSize.width * 0.38, usableHeight * 0.23))
            )
        } else {
            let widthLimit = max(184, availableSize.width - horizontalPadding * 2 - 72)
            let heightLimit = max(184, usableHeight * 0.325)
            artworkSize = min(artworkPreference.pointSize, widthLimit, heightLimit)
        }
        return PlayerLayoutMetrics(
            mode: mode,
            artworkSize: artworkSize,
            horizontalPadding: horizontalPadding,
            sectionSpacing: mode.isCompact ? 10 : 14,
            lyricHeight: mode.isCompact ? 112 : 150
        )
    }
}

enum ConnectionStatusKind: Equatable {
    case connected
    case connecting
    case reconnecting
    case disconnected
    case actionRequired
}

struct ConnectionStatusPresentation: Equatable {
    let kind: ConnectionStatusKind
    let title: String
    let detail: String
    let opensDeviceDetail: Bool

    static func resolve(_ state: BLEConnectionPresentationState) -> Self {
        switch state {
        case .connected:
            return ConnectionStatusPresentation(
                kind: .connected,
                title: "已连接",
                detail: "Sony 已可控制",
                opensDeviceDetail: true
            )
        case .scanning, .connecting:
            return ConnectionStatusPresentation(
                kind: .connecting,
                title: "正在连接",
                detail: "正在同步播放状态、歌词和封面",
                opensDeviceDetail: false
            )
        case .reconnecting:
            return ConnectionStatusPresentation(
                kind: .reconnecting,
                title: "正在重连",
                detail: "保留上次播放内容，恢复后自动同步",
                opensDeviceDetail: true
            )
        case .unavailable(.poweredOff), .unavailable(.unauthorized),
                .unavailable(.unsupported), .failed:
            return ConnectionStatusPresentation(
                kind: .actionRequired,
                title: "需要处理",
                detail: "检查蓝牙、权限和 Sony 端服务",
                opensDeviceDetail: false
            )
        case .unavailable, .disconnected:
            return ConnectionStatusPresentation(
                kind: .disconnected,
                title: "已断开",
                detail: "上次播放内容不是实时状态",
                opensDeviceDetail: false
            )
        }
    }

    var color: Color {
        switch kind {
        case .connected: PlayerDesignTokens.healthy
        case .connecting, .reconnecting: PlayerDesignTokens.warning
        case .disconnected, .actionRequired: PlayerDesignTokens.disconnected
        }
    }

    var isBusy: Bool {
        kind == .connecting || kind == .reconnecting
    }
}

struct ReconnectBannerPresentation: Equatable {
    let title: String
    let detail: String
    let showsRetry: Bool

    static func resolve(
        connection: BLEConnectionPresentationState,
        hasSnapshot: Bool
    ) -> Self? {
        guard hasSnapshot, !connection.isConnected else { return nil }
        let status = ConnectionStatusPresentation.resolve(connection)
        return ReconnectBannerPresentation(
            title: status.kind == .reconnecting ? "正在恢复 Sony 连接" : status.title,
            detail: status.detail,
            showsRetry: !status.isBusy
        )
    }
}

enum NowPlayingSnapshotPolicy {
    static func hasDisplayableSnapshot(
        title: String,
        artist: String,
        hasArtwork: Bool,
        isRestoredSnapshot: Bool
    ) -> Bool {
        let normalized = [title, artist].map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return isRestoredSnapshot || hasArtwork || normalized.contains {
            !$0.isEmpty && $0 != "-" && $0 != "等待同步"
        }
    }
}

struct FullLyricsFollowState: Equatable {
    enum Mode: Equatable {
        case following
        case browsing
    }

    private(set) var mode: Mode = .following
    var showsReturnToCurrent: Bool { mode == .browsing }

    mutating func userDidBrowse() {
        mode = .browsing
    }

    mutating func returnToCurrent() {
        mode = .following
    }

    mutating func trackDidChange() {
        mode = .following
    }
}

struct FullLyricsStoreHost: View {
    let manager: BLETestManager
    let onDismiss: () -> Void
    let onShowDiagnostic: () -> Void
    @ObservedObject private var preferences = PreferencesStore.shared

    var body: some View {
        let metadata = manager.playbackStore.metadata
        let timeline = manager.playbackStore.timeline
        let document = manager.lyricsStore.document
        let lines = document.isCurrent ? document.lines : []
        let rawPosition = timeline.isSeeking ? timeline.seekPositionMs : timeline.displayPositionMs
        let position = manager.karaokePositionMs(rawPositionMs: rawPosition)

        FullLyricsView(
            title: display(metadata.title, fallback: "等待同步"),
            artist: display(metadata.artist, fallback: "等待同步"),
            albumArtImage: manager.artworkStore.state.image,
            lyrics: lines,
            lyricsIdentity: "\(document.trackId)|\(lines.count)|\(lines.first?.timeMs ?? -1)|\(lines.last?.timeMs ?? -1)",
            currentIndex: LyricTimelineHelper.currentIndex(lines: lines, positionMs: position) ?? -1,
            positionMs: position,
            translationState: document.translationState,
            romanizationState: document.romanizationState,
            isPlaying: timeline.isPlaying,
            isConnected: manager.connectionStore.presentation.isConnected,
            onDismiss: onDismiss,
            onPrevious: manager.sendPrevious,
            onPlayPause: manager.sendPlayPause,
            onNext: manager.sendNext,
            onSeekToLine: manager.seekToLyricLine,
            showDiagnosticButton: preferences.appExperienceMode == .debug,
            onShowDiagnostic: onShowDiagnostic
        )
    }

    private func display(_ value: String, fallback: String) -> String {
        value == "-" || value.isEmpty ? fallback : value
    }
}

struct ConnectionStatusBanner: View {
    let presentation: ReconnectBannerPresentation
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: presentation.showsRetry ? "antenna.radiowaves.left.and.right.slash" : "arrow.triangle.2.circlepath")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(PlayerDesignTokens.warning)
                    .frame(width: 36, height: 36)
                    .background(PlayerDesignTokens.warning.opacity(0.12), in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(AppLocalization.string(presentation.title))
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(PlayerDesignTokens.primaryText)
                    Text(AppLocalization.string(presentation.detail))
                        .font(.caption)
                        .foregroundStyle(PlayerDesignTokens.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                if !presentation.showsRetry {
                    ProgressView()
                        .tint(PlayerDesignTokens.warning)
                        .accessibilityLabel("正在重连")
                }
            }
            if presentation.showsRetry {
                Button(action: onRetry) {
                    Text(AppLocalization.string("立即重试连接"))
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity, minHeight: PlayerDesignTokens.minimumHitTarget)
                        .foregroundStyle(PlayerDesignTokens.warning)
                        .background(
                            PlayerDesignTokens.warning.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: PlayerDesignTokens.smallRadius)
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius))
        .overlay {
            RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius)
                .strokeBorder(.white.opacity(0.09), lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
    }
}

struct DeviceDetailView: View {
    @ObservedObject var manager: BLETestManager
    let onShowAdvancedDiagnostics: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var confirmsForget = false

    var body: some View {
        NavigationStack {
            ZStack {
                PlayerDesignTokens.canvas.ignoresSafeArea()
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 18) {
                        deviceHeader
                        metrics
                        actionCard
                        Button(role: .destructive) {
                            confirmsForget = true
                        } label: {
                            Label("忘记此设备", systemImage: "trash")
                                .font(.subheadline.weight(.bold))
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .foregroundStyle(PlayerDesignTokens.disconnected)
                                .background(
                                    PlayerDesignTokens.disconnected.opacity(0.10),
                                    in: RoundedRectangle(cornerRadius: PlayerDesignTokens.smallRadius)
                                )
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("设备详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .confirmationDialog(
            "忘记此设备？",
            isPresented: $confirmsForget,
            titleVisibility: .visible
        ) {
            Button("忘记设备", role: .destructive) {
                manager.forgetLastSonyDevice()
                dismiss()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只会移除本机记住的 Sony 设备；不会清理歌词、封面或播放历史。")
        }
    }

    private var deviceHeader: some View {
        HStack(spacing: 14) {
            Image(systemName: "hifispeaker.2.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(status.color)
                .frame(width: 54, height: 54)
                .background(status.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 17))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(deviceName)
                    .font(.title3.bold())
                    .foregroundStyle(PlayerDesignTokens.primaryText)
                    .lineLimit(2)
                Text("\(status.title) · \(status.detail)")
                    .font(.caption)
                    .foregroundStyle(PlayerDesignTokens.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(PlayerDesignTokens.surface1, in: RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius))
    }

    private var metrics: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            if manager.currentMtuBytesForPreferences > 0 {
                metric("MTU", "\(manager.currentMtuBytesForPreferences)")
            }
            if manager.lyricClockBestRoundTripMs > 0 {
                metric("时钟同步 RTT", "\(manager.lyricClockBestRoundTripMs) ms")
            }
            if manager.connectionHealthLastNotifyAgeMs >= 0 {
                metric("最近同步", ageText(manager.connectionHealthLastNotifyAgeMs))
            }
            metric("链路状态", healthText)
            if manager.serverSessionId != "-" {
                metric("协议", "V3")
            }
        }
    }

    private var actionCard: some View {
        VStack(spacing: 0) {
            detailAction("重新同步当前播放", systemImage: "arrow.clockwise") {
                manager.resyncCurrentPlaybackFromDevice()
            }
            Divider().overlay(.white.opacity(0.08))
            detailAction("重新连接", systemImage: "antenna.radiowaves.left.and.right") {
                manager.forceReconnect()
            }
            Divider().overlay(.white.opacity(0.08))
            detailAction("打开高级诊断", systemImage: "waveform.path.ecg.rectangle") {
                dismiss()
                onShowAdvancedDiagnostics()
            }
        }
        .background(PlayerDesignTokens.surface1, in: RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius))
        .clipShape(RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius))
    }

    private func detailAction(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PlayerDesignTokens.primaryText)
                .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
                .padding(.horizontal, 16)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption)
                .foregroundStyle(PlayerDesignTokens.quietText)
            Text(value)
                .font(.subheadline.weight(.bold).monospacedDigit())
                .foregroundStyle(PlayerDesignTokens.primaryText)
                .lineLimit(2)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
        .padding(12)
        .background(PlayerDesignTokens.surface2, in: RoundedRectangle(cornerRadius: PlayerDesignTokens.smallRadius))
    }

    private var status: ConnectionStatusPresentation {
        ConnectionStatusPresentation.resolve(manager.connectionStore.presentation)
    }

    private var deviceName: String {
        let name = manager.connectedDeviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty || name == "-" ? "Sony PlayerAgent" : name
    }

    private var healthText: String {
        switch manager.connectionHealthState.lowercased() {
        case "healthy": "健康"
        case "suspect": "需要观察"
        case "stale": "需要处理"
        default: status.title
        }
    }

    private func ageText(_ milliseconds: Int64) -> String {
        if milliseconds < 1_000 { return "刚刚" }
        return "\(milliseconds / 1_000) 秒前"
    }
}
