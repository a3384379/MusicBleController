import SwiftUI

private final class BLETestManagerOwner: ObservableObject {
    let manager: BLETestManager

    init(manager: BLETestManager = BLETestManager()) {
        self.manager = manager
    }
}

struct ContentView: View {
    @StateObject private var managerOwner = BLETestManagerOwner()
    @ObservedObject private var preferences = PreferencesStore.shared
    @State private var showFullLyrics = false
    @State private var showDebugPage = false
    @State private var showPlaybackHistory = false
    @State private var showLyricDiagnostic = false
    @State private var showNowPlayingDiagnostic = false
    @State private var showSystemHealthOverview = false
    @State private var showPreferences = false
    @State private var showDeviceDetails = false

    private var manager: BLETestManager { managerOwner.manager }

    var body: some View {
        NavigationStack {
            ZStack {
                PlayerBackgroundHost(store: manager.artworkStore)
                    .ignoresSafeArea()

                GeometryReader { proxy in
                    ResponsivePlayerLayout(
                        manager: manager,
                        availableSize: proxy.size,
                        safeAreaInsets: proxy.safeAreaInsets,
                        showFullLyrics: $showFullLyrics,
                        showDebugPage: $showDebugPage,
                        showPlaybackHistory: $showPlaybackHistory,
                        showLyricDiagnostic: $showLyricDiagnostic,
                        showNowPlayingDiagnostic: $showNowPlayingDiagnostic,
                        showSystemHealthOverview: $showSystemHealthOverview,
                        showPreferences: $showPreferences,
                        showDeviceDetails: $showDeviceDetails
                    )
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showDebugPage) {
                DebugToolsView(bleManager: manager)
            }
            .sheet(isPresented: $showPlaybackHistory) {
                PlaybackHistoryView(bleManager: manager)
            }
            .sheet(isPresented: $showPreferences) {
                PreferencesView(bleManager: manager, onDismiss: { showPreferences = false })
            }
            .sheet(isPresented: $showDeviceDetails) {
                DeviceDetailView(
                    manager: manager,
                    onShowAdvancedDiagnostics: {
                        showNowPlayingDiagnostic = true
                    }
                )
            }
            .sheet(isPresented: $showLyricDiagnostic) {
                LyricDiagnosticView(
                    bleManager: manager,
                    onDismiss: { showLyricDiagnostic = false }
                )
            }
            .sheet(isPresented: $showNowPlayingDiagnostic) {
                NowPlayingDiagnosticView(
                    bleManager: manager,
                    onDismiss: { showNowPlayingDiagnostic = false }
                )
            }
            .sheet(isPresented: $showSystemHealthOverview) {
                SystemHealthOverviewView(
                    bleManager: manager,
                    onDismiss: { showSystemHealthOverview = false }
                )
            }
            .fullScreenCover(isPresented: $showFullLyrics) {
                FullLyricsStoreHost(
                    manager: manager,
                    onDismiss: { showFullLyrics = false },
                    onShowDiagnostic: {
                        showFullLyrics = false
                        showLyricDiagnostic = true
                    }
                )
            }
            .onChange(of: preferences.lyricDisplayMode) { _, mode in
                manager.requestFullLyricsOptionalFieldsIfNeeded(displayMode: mode)
            }
            .onChange(of: showFullLyrics) { _, presented in
                if presented {
                    manager.requestFullLyricsOptionalFieldsIfNeeded(
                        displayMode: preferences.lyricDisplayMode
                    )
                }
            }
            .onChange(of: preferences.appExperienceMode) { _, mode in
                if mode == .daily {
                    showDebugPage = false
                    showLyricDiagnostic = false
                    showNowPlayingDiagnostic = false
                    showSystemHealthOverview = false
                }
            }
        }
    }
}

private struct PlayerBackgroundHost: View {
    let store: ArtworkStore

    var body: some View {
        PlayerBackgroundView(image: store.state.image)
    }
}

private struct ResponsivePlayerLayout: View {
    let manager: BLETestManager
    let availableSize: CGSize
    let safeAreaInsets: EdgeInsets
    @Binding var showFullLyrics: Bool
    @Binding var showDebugPage: Bool
    @Binding var showPlaybackHistory: Bool
    @Binding var showLyricDiagnostic: Bool
    @Binding var showNowPlayingDiagnostic: Bool
    @Binding var showSystemHealthOverview: Bool
    @Binding var showPreferences: Bool
    @Binding var showDeviceDetails: Bool

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ObservedObject private var preferences = PreferencesStore.shared

    var body: some View {
        let metrics = PlayerLayoutMetrics.resolve(
            availableSize: availableSize,
            safeAreaInsets: safeAreaInsets,
            dynamicTypeSize: dynamicTypeSize,
            artworkPreference: preferences.artworkDisplaySize
        )

        Group {
            if metrics.mode == .regular {
                regularContent(metrics: metrics)
            } else {
                compactContent(metrics: metrics)
            }
        }
        .padding(.horizontal, metrics.horizontalPadding)
        .safeAreaPadding(.top, metrics.mode.isCompact ? 4 : 8)
        .safeAreaPadding(.bottom, metrics.mode.isCompact ? 6 : 12)
        .frame(width: availableSize.width, height: availableSize.height, alignment: .top)
    }

    private func regularContent(metrics: PlayerLayoutMetrics) -> some View {
        VStack(spacing: metrics.sectionSpacing) {
            header(mode: .regular)

            if showsConnectionGuide {
                connectionGuide
                    .frame(maxHeight: .infinity)
            } else {
                connectionBanner
                TrackInfoStoreView(manager: manager, metrics: metrics)
                LyricsPreviewStoreView(
                    manager: manager,
                    metrics: metrics,
                    showFullLyrics: $showFullLyrics
                )
                PlaybackProgressStoreView(manager: manager)
                PlaybackControlsStoreView(manager: manager, mode: .regular)
                VolumeControlStoreView(manager: manager)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func compactContent(metrics: PlayerLayoutMetrics) -> some View {
        VStack(spacing: metrics.sectionSpacing) {
            header(mode: metrics.mode)

            connectionBanner
            PlayerContentStoreRouter(
                manager: manager,
                metrics: metrics,
                showFullLyrics: $showFullLyrics
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var showsConnectionGuide: Bool {
        !manager.connectionStore.presentation.isConnected &&
            !hasPlaybackSnapshot
    }

    private var hasPlaybackSnapshot: Bool {
        NowPlayingSnapshotPolicy.hasDisplayableSnapshot(
            title: manager.playbackStore.metadata.title,
            artist: manager.playbackStore.metadata.artist,
            hasArtwork: manager.artworkStore.state.image != nil,
            isRestoredSnapshot: manager.artworkStore.state.isRestoredSnapshot
        )
    }

    @ViewBuilder
    private var connectionBanner: some View {
        if let presentation = ReconnectBannerPresentation.resolve(
            connection: manager.connectionStore.presentation,
            hasSnapshot: hasPlaybackSnapshot
        ) {
            ConnectionStatusBanner(
                presentation: presentation,
                onRetry: manager.scanSonyFromMenu
            )
        }
    }

    private var connectionGuide: some View {
        ConnectionGuideStoreView(
            presentation: manager.connectionStore.presentation,
            onConnect: manager.scanSonyFromMenu
        )
    }

    private func header(mode: PlayerLayoutMode) -> some View {
        PlayerHeaderStoreView(
            manager: manager,
            mode: mode,
            showDebugPage: $showDebugPage,
            showPlaybackHistory: $showPlaybackHistory,
            showLyricDiagnostic: $showLyricDiagnostic,
            showNowPlayingDiagnostic: $showNowPlayingDiagnostic,
            showSystemHealthOverview: $showSystemHealthOverview,
            showPreferences: $showPreferences,
            showDeviceDetails: $showDeviceDetails
        )
    }
}

private struct PlayerHeaderStoreView: View {
    let manager: BLETestManager
    let mode: PlayerLayoutMode
    @Binding var showDebugPage: Bool
    @Binding var showPlaybackHistory: Bool
    @Binding var showLyricDiagnostic: Bool
    @Binding var showNowPlayingDiagnostic: Bool
    @Binding var showSystemHealthOverview: Bool
    @Binding var showPreferences: Bool
    @Binding var showDeviceDetails: Bool

    @ObservedObject private var preferences = PreferencesStore.shared

    var body: some View {
        HStack(spacing: 12) {
            Button(action: handleConnectionTap) {
                HStack(spacing: 7) {
                    statusIndicator
                    if mode != .accessibility {
                        Text(statusTitle)
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundStyle(isConnected ? .white.opacity(0.88) : statusColor)
                            .lineLimit(1)
                            .contentTransition(.opacity)
                    }
                }
                .padding(.horizontal, 11)
                .frame(height: 34)
                .background(statusColor.opacity(isConnected ? 0.06 : 0.13), in: Capsule())
                .overlay { Capsule().strokeBorder(statusColor.opacity(0.20), lineWidth: 1) }
                .contentShape(Rectangle())
                .padding(.vertical, 5)
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.98))
            .accessibilityLabel(
                isConnected ? AppLocalization.string("Sony 已连接") : statusTitle
            )
            .accessibilityHint(
                AppLocalization.string(
                    statusPresentation.opensDeviceDetail
                        ? "点按查看连接详情"
                        : "点按扫描并连接 Sony"
                )
            )

            Spacer()

            Menu {
                Button { manager.scanSonyFromMenu() } label: {
                    Label("扫描 / 重连", systemImage: "antenna.radiowaves.left.and.right")
                }
                Button { showPlaybackHistory = true } label: {
                    Label("播放历史", systemImage: "clock.arrow.circlepath")
                }
                Button { showPreferences = true } label: {
                    Label("更多设置", systemImage: "gearshape")
                }
                Button { manager.toggleAppExperienceMode() } label: {
                    Label(
                        preferences.appExperienceMode.toggleTitle,
                        systemImage: isDebugMode ? "person.fill" : "ladybug.fill"
                    )
                }
                if isDebugMode {
                    Divider()
                    Button { showSystemHealthOverview = true } label: {
                        Label("系统健康总览", systemImage: "heart.text.square")
                    }
                    Button { showNowPlayingDiagnostic = true } label: {
                        Label("当前歌曲诊断", systemImage: "waveform.path.ecg.rectangle")
                    }
                    Button {
                        manager.requestLyricDiagnostic(manual: true)
                        showLyricDiagnostic = true
                    } label: {
                        Label("歌词诊断中心", systemImage: "text.magnifyingglass")
                    }
                    Button { showDebugPage = true } label: {
                        Label("调试工具", systemImage: "slider.horizontal.3")
                    }
                }
                Divider()
                Picker(
                    "歌词显示",
                    selection: Binding(
                        get: { preferences.lyricDisplayMode },
                        set: { preferences.lyricDisplayMode = $0 }
                    )
                ) {
                    ForEach(LyricDisplayMode.allCases) { mode in
                        Text(mode.menuTitle).tag(mode)
                    }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.86))
                    .frame(width: 42, height: 42)
                    .background(.white.opacity(0.05), in: Circle())
                    .overlay { Circle().stroke(.white.opacity(0.08), lineWidth: 1) }
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.96))
            .accessibilityLabel("更多")
        }
    }

    private var presentation: BLEConnectionPresentationState {
        manager.connectionStore.presentation
    }
    private var isConnected: Bool { presentation.isConnected }
    private var isDebugMode: Bool { preferences.appExperienceMode == .debug }
    private var showsProgress: Bool {
        switch presentation {
        case .scanning, .connecting, .reconnecting: return true
        default: return false
        }
    }
    private var statusPresentation: ConnectionStatusPresentation {
        ConnectionStatusPresentation.resolve(presentation)
    }
    private var statusColor: Color { statusPresentation.color }
    private var statusTitle: String {
        AppLocalization.string(statusPresentation.title)
    }
    @ViewBuilder
    private var statusIndicator: some View {
        if showsProgress {
            ProgressView()
                .controlSize(.mini)
                .tint(statusColor)
                .frame(width: 12, height: 12)
        } else if isConnected {
            Circle()
                .fill(statusColor)
                .frame(width: 7, height: 7)
                .shadow(color: statusColor.opacity(0.48), radius: 4)
                .frame(width: 12, height: 12)
        } else {
            Image(systemName: statusIcon)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(statusColor)
                .frame(width: 12, height: 12)
        }
    }
    private var statusIcon: String {
        switch presentation {
        case .connected: return "antenna.radiowaves.left.and.right"
        case .unavailable(.poweredOff): return "power"
        case .unavailable(.unauthorized): return "lock.trianglebadge.exclamationmark"
        case .failed: return "exclamationmark.triangle.fill"
        default: return "antenna.radiowaves.left.and.right.slash"
        }
    }
    private func handleConnectionTap() {
        if statusPresentation.opensDeviceDetail {
            showDeviceDetails = true
        } else if case .unavailable(.unauthorized) = presentation,
                  let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        } else if statusPresentation.isBusy {
            return
        } else {
            manager.scanSonyFromMenu()
        }
    }
}

private struct PlayerContentStoreRouter: View {
    let manager: BLETestManager
    let metrics: PlayerLayoutMetrics
    @Binding var showFullLyrics: Bool

    var body: some View {
        if !manager.connectionStore.presentation.isConnected,
           !NowPlayingSnapshotPolicy.hasDisplayableSnapshot(
                title: manager.playbackStore.metadata.title,
                artist: manager.playbackStore.metadata.artist,
                hasArtwork: manager.artworkStore.state.image != nil,
                isRestoredSnapshot: manager.artworkStore.state.isRestoredSnapshot
           ) {
            ConnectionGuideStoreView(
                presentation: manager.connectionStore.presentation,
                onConnect: manager.scanSonyFromMenu
            )
        } else {
            PlayerStoreSections(
                manager: manager,
                metrics: metrics,
                showFullLyrics: $showFullLyrics
            )
        }
    }
}

private struct ConnectionGuideStoreView: View {
    let presentation: BLEConnectionPresentationState
    let onConnect: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: icon)
                .font(.system(size: 46, weight: .semibold))
                .foregroundStyle(.orange.opacity(0.90))
                .frame(width: 86, height: 86)
                .background(.white.opacity(0.06), in: Circle())

            VStack(spacing: 8) {
                Text(title)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text(detail)
                    .font(.body)
                    .foregroundStyle(.white.opacity(0.64))
                    .multilineTextAlignment(.center)
            }

            Button(action: primaryAction) {
                Label(buttonTitle, systemImage: isBusy ? "hourglass" : "antenna.radiowaves.left.and.right")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.75)
                    .padding(.vertical, 10)
                    .frame(maxWidth: 280, minHeight: 50)
                    .background(.white, in: Capsule())
                    .foregroundStyle(.black)
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.97))
            .disabled(isPrimaryActionDisabled)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, minHeight: 430)
        .padding(.horizontal, 24)
    }

    private var isBusy: Bool {
        switch presentation {
        case .scanning, .connecting, .reconnecting: return true
        default: return false
        }
    }
    private var isPrimaryActionDisabled: Bool {
        if isBusy { return true }
        switch presentation {
        case .unavailable(.poweredOff), .unavailable(.unsupported), .unavailable(.resetting),
                .unavailable(.unknown), .unavailable(.available):
            return true
        default:
            return false
        }
    }
    private var title: String {
        let key: String = switch presentation {
        case .unavailable(.poweredOff): "请打开蓝牙"
        case .unavailable(.unauthorized): "需要蓝牙权限"
        case .unavailable(.unsupported): "此设备不支持蓝牙"
        case .unavailable: "蓝牙暂不可用"
        case .scanning: "正在查找 Sony"
        case .connecting: "正在连接 Sony"
        case .reconnecting: "正在恢复连接"
        case .failed: "连接没有成功"
        case .disconnected: "连接你的 Sony 设备"
        case .connected: "已连接"
        }
        return AppLocalization.string(key)
    }
    private var detail: String {
        let key: String = switch presentation {
        case .unavailable(.poweredOff): "打开系统蓝牙后，应用会自动继续连接。"
        case .unavailable(.unauthorized): "请在系统设置中允许 Sony Music 使用蓝牙。"
        case .unavailable(.unsupported): "需要支持低功耗蓝牙的 iPhone。"
        case .unavailable: "蓝牙正在恢复，请稍后再试。"
        case .scanning: "请确保 Sony 端已启动并处于可连接状态。"
        case .connecting, .reconnecting: "正在同步播放状态、歌词和封面。"
        case .failed: "确认 Sony 端已启动，然后重新扫描。"
        case .disconnected: "连接后可控制播放，并同步歌词、封面与灵动岛。"
        case .connected: ""
        }
        return AppLocalization.string(key)
    }
    private var icon: String {
        switch presentation {
        case .unavailable(.poweredOff): return "power"
        case .unavailable(.unauthorized): return "lock.trianglebadge.exclamationmark"
        case .failed: return "exclamationmark.triangle.fill"
        case .scanning, .connecting, .reconnecting: return "antenna.radiowaves.left.and.right"
        default: return "hifispeaker.2.fill"
        }
    }
    private var buttonTitle: String {
        let key: String = switch presentation {
        case .unavailable(.unauthorized): "打开设置"
        case .unavailable(.poweredOff): "等待蓝牙开启"
        case .unavailable(.unsupported): "当前设备不支持"
        case .unavailable: "等待蓝牙恢复"
        case .scanning: "正在扫描"
        case .connecting: "正在连接"
        case .reconnecting: "正在恢复"
        default: "扫描并连接"
        }
        return AppLocalization.string(key)
    }
    private func primaryAction() {
        if case .unavailable(.unauthorized) = presentation,
           let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        } else {
            onConnect()
        }
    }
}

private struct PlayerStoreSections: View {
    let manager: BLETestManager
    let metrics: PlayerLayoutMetrics
    @Binding var showFullLyrics: Bool

    var body: some View {
        VStack(spacing: metrics.sectionSpacing) {
            TrackInfoStoreView(manager: manager, metrics: metrics)
            LyricsPreviewStoreView(
                manager: manager,
                metrics: metrics,
                showFullLyrics: $showFullLyrics
            )
            PlaybackProgressStoreView(manager: manager)
            PlaybackControlsStoreView(manager: manager, mode: metrics.mode)
            VolumeControlStoreView(manager: manager)
        }
    }
}

private struct TrackInfoStoreView: View {
    let manager: BLETestManager
    let metrics: PlayerLayoutMetrics

    private var mode: PlayerLayoutMode { metrics.mode }

    var body: some View {
        Group {
            if mode == .regular || mode == .accessibility {
                VStack(spacing: mode == .regular ? 12 : 8) {
                    artwork
                    metadata.multilineTextAlignment(.center)
                }
            } else {
                HStack(spacing: mode.isCompact ? 16 : 27) {
                    artwork
                    metadata
                }
            }
        }
        .frame(maxWidth: .infinity)
        .onChange(of: manager.playbackStore.metadata) { oldValue, newValue in
            guard oldValue != newValue else { return }
            manager.recordNowPlayingStateConsumed()
        }
    }

    private var artworkSize: CGFloat { metrics.artworkSize }
    private var artwork: some View {
        Group {
            if let image = manager.artworkStore.state.image {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFill()
            } else {
                DefaultAlbumArtView()
            }
        }
        .frame(width: artworkSize, height: artworkSize)
        .clipShape(RoundedRectangle(cornerRadius: mode.isCompact ? 20 : 25, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: mode.isCompact ? 20 : 25)
                .stroke(.white.opacity(0.12))
        }
        .shadow(color: .black.opacity(0.34), radius: 20, y: 12)
        .accessibilityLabel("当前歌曲封面")
    }
    private var metadata: some View {
        let metadata = manager.playbackStore.metadata
        let centered = mode == .regular || mode == .accessibility
        return VStack(alignment: centered ? .center : .leading, spacing: mode.isCompact ? 5 : 7) {
            Text(display(metadata.title, fallback: AppLocalization.string("等待同步")))
                .font(
                    mode.isCompact
                        ? .system(.title2, design: .rounded, weight: .bold)
                        : .system(size: 31, weight: .bold, design: .rounded)
                )
                .foregroundStyle(.white)
                .lineLimit(mode == .accessibility ? 3 : 2)
                .minimumScaleFactor(0.58)
            Text(display(metadata.artist, fallback: AppLocalization.string("等待同步")))
                .font(mode.isCompact ? .body.weight(.medium) : .body.weight(.semibold))
                .foregroundStyle(.white.opacity(0.74))
                .lineLimit(2)
            Text(
                String(
                    format: AppLocalization.string("专辑 · %@"),
                    display(metadata.album, fallback: AppLocalization.string("等待同步"))
                )
            )
                .font(.system(.caption, design: .rounded, weight: .medium))
                .foregroundStyle(.white.opacity(0.48))
                .lineLimit(2)
            DarkPlaybackStatusBadge(state: playerVisualState(manager: manager))
                .overlay(alignment: .bottomLeading) {
                    if manager.artworkStore.state.isRestoredSnapshot {
                        Text(AppLocalization.string("上次播放 · 等待同步"))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.orange)
                            .fixedSize()
                            .offset(y: 20)
                    }
                }
        }
        .frame(maxWidth: .infinity, alignment: centered ? .center : .leading)
    }
    private func display(_ value: String, fallback: String) -> String {
        value == "-" || value.isEmpty ? fallback : value
    }
}

private struct LyricsPreviewStoreView: View {
    let manager: BLETestManager
    let metrics: PlayerLayoutMetrics
    @Binding var showFullLyrics: Bool
    @ObservedObject private var preferences = PreferencesStore.shared

    private var mode: PlayerLayoutMode { metrics.mode }

    var body: some View {
        Button(action: openFullLyrics) {
            HStack(spacing: mode.isCompact ? 12 : 22) {
                DarkLyricSideDots(color: visualState.accentColor)
                VStack(spacing: mode.isCompact ? 7 : 12) {
                    Text(line(offset: -1))
                        .font(secondaryLineFont)
                        .foregroundStyle(.white.opacity(0.38))
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                    KaraokeLyricText(
                        text: line(offset: 0),
                        progress: lineProgress,
                        words: lineModel(offset: 0)?.words ?? [],
                        positionMs: karaokePosition,
                        isPlaying: timeline.isPlaying && !timeline.isSeeking,
                        highlightColor: visualState.accentColor.opacity(0.92),
                        normalColor: .white.opacity(0.90),
                        font: currentLineFont,
                        lineLimit: mode == .accessibility ? 4 : 2,
                        alignment: .center
                    )
                    .minimumScaleFactor(0.76)
                    .frame(maxWidth: .infinity, minHeight: mode.isCompact ? 38 : 50)
                    .clipped()
                    if let auxiliaryLine {
                        Text(auxiliaryLine)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(PlayerDesignTokens.secondaryText.opacity(0.72))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    Text(line(offset: 1))
                        .font(secondaryLineFont)
                        .foregroundStyle(.white.opacity(0.40))
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                    GeometryReader { proxy in
                        DarkLyricRhythmLine(
                            state: visualState,
                            trackSeed: manager.playbackStore.metadata.title + manager.playbackStore.metadata.artist,
                            lyricProgress: lineProgress,
                            wordSignature: "\(manager.lyricsStore.live.currentWordLineIndex):\(manager.lyricsStore.live.currentWordIndex)",
                            positionMs: karaokePosition
                        )
                        .frame(width: proxy.size.width * 0.5, height: mode.isCompact ? 24 : 36)
                        .frame(maxWidth: .infinity)
                    }
                    .frame(height: mode.isCompact ? 18 : 24)
                }
                .frame(
                    maxWidth: .infinity,
                    minHeight: metrics.lyricHeight,
                    maxHeight: metrics.lyricHeight
                )
                DarkLyricSideDots(color: visualState.accentColor)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("当前歌词，点按打开完整歌词")
        .onChange(of: displayedPosition) { _, value in
            manager.logKaraokeOffset(rawPositionMs: value)
        }
    }

    private var timeline: BLEPlaybackTimelineState { manager.playbackStore.timeline }
    private var document: BLEFullLyricsViewState { manager.lyricsStore.document }
    private var lines: [LyricLine] { document.isCurrent ? document.lines : [] }
    private var displayedPosition: Int64 {
        timeline.isSeeking ? timeline.seekPositionMs : timeline.displayPositionMs
    }
    private var karaokePosition: Int64 {
        manager.karaokePositionMs(rawPositionMs: displayedPosition)
    }
    private var currentIndex: Int {
        LyricTimelineHelper.currentIndex(lines: lines, positionMs: karaokePosition) ?? -1
    }
    private var lineProgress: Double {
        LyricTimelineHelper.lineProgress(
            lines: lines,
            index: currentIndex,
            positionMs: karaokePosition
        )
    }
    private var visualState: DarkPlaybackVisualState { playerVisualState(manager: manager) }
    private var auxiliaryLine: String? {
        guard let line = lineModel(offset: 0) else { return nil }
        let candidate: String?
        switch preferences.lyricDisplayMode {
        case .original:
            candidate = nil
        case .originalRomanization:
            candidate = line.romanization
        case .originalTranslation, .originalTranslationRomanization:
            candidate = line.translation
        }
        let text = candidate?.trimmingCharacters(in: .whitespacesAndNewlines)
        return text?.isEmpty == false ? text : nil
    }
    private var secondaryLineFont: Font {
        if mode == .accessibility {
            return .system(.body, design: .rounded, weight: .medium)
        }
        return .system(
            size: mode.isCompact ? 14 : 16,
            weight: .medium,
            design: .rounded
        )
    }
    private var currentLineFont: Font {
        if mode == .accessibility {
            return .system(.title2, design: .rounded, weight: .semibold)
        }
        return .system(
            size: mode.isCompact ? 21 : 27,
            weight: .semibold,
            design: .rounded
        )
    }
    private func lineModel(offset: Int) -> LyricLine? {
        let index = currentIndex + offset
        return lines.indices.contains(index) ? lines[index] : nil
    }
    private func line(offset: Int) -> String {
        if let model = lineModel(offset: offset) {
            let text = model.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? " " : text
        }
        if offset == 0 {
            let text = manager.lyricsStore.live.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? "暂无歌词" : text
        }
        return " "
    }
    private func openFullLyrics() {
        if document.lines.isEmpty { manager.sendGetFullLyrics(force: true) }
        manager.requestFullLyricsOptionalFieldsIfNeeded(displayMode: preferences.lyricDisplayMode)
        showFullLyrics = true
    }
}

private struct PlaybackProgressStoreView: View {
    let manager: BLETestManager

    var body: some View {
        let timeline = manager.playbackStore.timeline
        HStack(spacing: 10) {
            Text(format(displayedPosition, duration: timeline.durationMs))
                .frame(width: 44, alignment: .leading)
            CompactPlayerSlider(
                value: Binding(
                    get: { Double(displayedPosition) },
                    set: manager.updateSeekPosition
                ),
                range: 0...Double(max(timeline.durationMs, 1)),
                step: nil,
                accentColor: playerVisualState(manager: manager).accentColor,
                isEnabled: manager.connectionStore.presentation.isConnected && timeline.durationMs > 0,
                accessibilityLabel: "播放进度",
                accessibilityValue: "\(format(displayedPosition, duration: timeline.durationMs))，共 \(format(timeline.durationMs, duration: timeline.durationMs))",
                onEditingChanged: { editing in
                    editing ? manager.beginSeeking() : manager.finishSeeking()
                }
            )
            Text(format(timeline.durationMs, duration: timeline.durationMs))
                .frame(width: 44, alignment: .trailing)
        }
        .font(.caption.monospacedDigit().weight(.semibold))
        .foregroundStyle(.white.opacity(0.72))
    }

    private var displayedPosition: Int64 {
        let timeline = manager.playbackStore.timeline
        return timeline.isSeeking ? timeline.seekPositionMs : timeline.displayPositionMs
    }
    private func format(_ milliseconds: Int64, duration: Int64) -> String {
        guard duration > 0 else { return "00:00" }
        let seconds = max(milliseconds, 0) / 1_000
        return String(format: "%02lld:%02lld", seconds / 60, seconds % 60)
    }
}

private struct CompactPlayerSlider: View {
    let value: Binding<Double>
    let range: ClosedRange<Double>
    let step: Double?
    let accentColor: Color
    let isEnabled: Bool
    let accessibilityLabel: String
    let accessibilityValue: String
    let onEditingChanged: (Bool) -> Void

    var body: some View {
        GeometryReader { proxy in
            let thumbDiameter = CompactSliderPresentation.thumbDiameter
            let trackWidth = max(proxy.size.width - thumbDiameter, 0)
            let progress = CGFloat(
                CompactSliderPresentation.normalizedProgress(
                    value: value.wrappedValue,
                    lowerBound: range.lowerBound,
                    upperBound: range.upperBound
                )
            )
            let fillWidth = trackWidth * progress
            let centerY = proxy.size.height / 2
            let thumbX = thumbDiameter / 2 + fillWidth

            ZStack(alignment: .topLeading) {
                Capsule()
                    .fill(.white.opacity(0.18))
                    .frame(width: trackWidth, height: CompactSliderPresentation.trackHeight)
                    .position(x: proxy.size.width / 2, y: centerY)

                Capsule()
                    .fill(accentColor)
                    .frame(width: fillWidth, height: CompactSliderPresentation.trackHeight)
                    .position(x: thumbDiameter / 2 + fillWidth / 2, y: centerY)

                Circle()
                    .fill(.white)
                    .frame(width: thumbDiameter, height: thumbDiameter)
                    .overlay {
                        Circle().strokeBorder(accentColor.opacity(0.72), lineWidth: 1)
                    }
                    .shadow(color: .black.opacity(0.24), radius: 2, y: 1)
                    .position(x: thumbX, y: centerY)
                    .allowsHitTesting(false)

                interactiveSlider
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .opacity(0.001)
            }
        }
        .frame(height: CompactSliderPresentation.interactionHeight)
        .contentShape(Rectangle())
        .opacity(isEnabled ? 1 : 0.45)
    }

    @ViewBuilder
    private var interactiveSlider: some View {
        if let step {
            Slider(
                value: value,
                in: range,
                step: step,
                onEditingChanged: onEditingChanged
            )
            .disabled(!isEnabled)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityValue(accessibilityValue)
        } else {
            Slider(
                value: value,
                in: range,
                onEditingChanged: onEditingChanged
            )
            .disabled(!isEnabled)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityValue(accessibilityValue)
        }
    }
}

private struct PlaybackControlsStoreView: View {
    let manager: BLETestManager
    let mode: PlayerLayoutMode

    var body: some View {
        HStack(spacing: mode.isCompact ? 32 : 58) {
            transport("上一首", image: "backward.end.fill", action: manager.sendPrevious)
            DarkDynamicPlayButton(
                state: playerVisualState(manager: manager),
                isEnabled: isConnected,
                action: manager.sendPlayPause
            )
            .scaleEffect(mode.isCompact ? 0.84 : 1)
            transport("下一首", image: "forward.end.fill", action: manager.sendNext)
        }
        .frame(maxWidth: .infinity, minHeight: mode.isCompact ? 72 : 88)
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.48)
    }

    private var isConnected: Bool { manager.connectionStore.presentation.isConnected }
    private func transport(_ title: String, image: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: image)
                .font(.system(size: mode.isCompact ? 26 : 30, weight: .bold))
                .foregroundStyle(.white)
                .frame(
                    width: mode.isCompact ? 52 : 62,
                    height: mode.isCompact ? 52 : 62
                )
                .contentShape(Circle())
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.90))
        .accessibilityLabel(title)
    }
}

private struct VolumeControlStoreView: View {
    let manager: BLETestManager
    @State private var feedbackVisible = false
    @State private var feedbackGeneration = 0

    var body: some View {
        let volume = manager.playbackStore.volume
        HStack(spacing: 8) {
            Image(systemName: volumeIcon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white.opacity(0.92))
                .frame(width: 18)
                .accessibilityHidden(true)

            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    CompactPlayerSlider(
                        value: Binding(
                            get: { Double(displayedVolume) },
                            set: manager.updateVolumeSeekValue
                        ),
                        range: 0...Double(max(volume.maximum, 1)),
                        step: 1,
                        accentColor: playerVisualState(manager: manager).accentColor,
                        isEnabled: isAvailable,
                        accessibilityLabel: "音量",
                        accessibilityValue: volume.maximum > 0 ? "\(displayedVolume)，最大 \(volume.maximum)" : "尚未同步",
                        onEditingChanged: handleEditing
                    )

                    if feedbackVisible, isAvailable {
                        Text("音量 \(displayedVolume)")
                            .font(.system(size: 11, weight: .semibold, design: .rounded).monospacedDigit())
                            .foregroundStyle(.white.opacity(0.94))
                            .padding(.horizontal, 8)
                            .frame(height: 24)
                            .background(.black.opacity(0.72), in: Capsule())
                            .overlay { Capsule().strokeBorder(.white.opacity(0.10), lineWidth: 1) }
                            .fixedSize()
                            .position(x: feedbackXPosition(in: proxy.size.width), y: -7)
                            .transition(.opacity.combined(with: .scale(scale: 0.94)))
                            .allowsHitTesting(false)
                    }
                }
            }
            .frame(height: CompactSliderPresentation.interactionHeight)
        }
        .padding(.horizontal, 11)
        .frame(height: 36)
        .background(.black.opacity(0.13), in: RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.06)) }
        .opacity(isAvailable ? 1 : 0.48)
    }

    private var displayedVolume: Int {
        let volume = manager.playbackStore.volume
        return volume.isSeeking ? volume.seekValue : volume.current
    }
    private var isAvailable: Bool {
        manager.connectionStore.presentation.isConnected && manager.playbackStore.volume.maximum > 0
    }
    private var volumeIcon: String {
        if displayedVolume <= 0 { return "speaker.slash.fill" }
        return displayedVolume < max(manager.playbackStore.volume.maximum / 2, 1)
            ? "speaker.wave.1.fill"
            : "speaker.wave.2.fill"
    }
    private func handleEditing(_ editing: Bool) {
        feedbackGeneration += 1
        let generation = feedbackGeneration
        if editing {
            withAnimation(.easeOut(duration: 0.12)) { feedbackVisible = true }
            manager.beginVolumeSeeking()
        } else {
            manager.finishVolumeSeeking()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.65) {
                guard generation == feedbackGeneration else { return }
                withAnimation(.easeInOut(duration: 0.16)) { feedbackVisible = false }
            }
        }
    }

    private func feedbackXPosition(in width: CGFloat) -> CGFloat {
        let horizontalInset: CGFloat = 18
        let usableWidth = max(width - horizontalInset * 2, 0)
        let progress = CGFloat(
            CompactVolumePresentation.normalizedProgress(
                current: displayedVolume,
                maximum: manager.playbackStore.volume.maximum
            )
        )
        return min(max(horizontalInset + usableWidth * progress, 30), max(width - 30, 30))
    }
}

private func playerVisualState(manager: BLETestManager) -> DarkPlaybackVisualState {
    let connection = DarkControlConnectionState(
        rawValue: manager.connectionStore.state.displayState
    ) ?? .disconnected
    return DarkControlPlaybackState(
        isPlaying: manager.playbackStore.timeline.isPlaying,
        isLoading: !manager.connectionStore.presentation.isConnected
    ).visualState(connection: connection)
}

private struct PlayerBackgroundView: View {
    let image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .saturation(1.22)
                        .brightness(-0.12)
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                        .blur(radius: 30)
                        .overlay(Color.black.opacity(0.40))
                } else {
                    LinearGradient(
                        colors: [
                            Color(red: 0.02, green: 0.05, blue: 0.09),
                            Color(red: 0.08, green: 0.12, blue: 0.18),
                            Color(red: 0.02, green: 0.03, blue: 0.06)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    .frame(width: proxy.size.width, height: proxy.size.height)
                }

                LinearGradient(
                    colors: [
                        Color.black.opacity(0.42),
                        Color.black.opacity(0.10),
                        Color.black.opacity(0.84)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(width: proxy.size.width, height: proxy.size.height)

                RadialGradient(
                    colors: [
                        .clear,
                        Color.black.opacity(0.28)
                    ],
                    center: .center,
                    startRadius: min(proxy.size.width, proxy.size.height) * 0.24,
                    endRadius: max(proxy.size.width, proxy.size.height) * 0.70
                )
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
    }
}

private struct NowPlayingInfoProvider {
    let title: String
    let artist: String
    let album: String
    let albumArt: UIImage?
    let positionMs: Int64
    let durationMs: Int64
    let isPlaying: Bool
}

private struct DarkControlSystemState {
    let connection: DarkControlConnectionState
}

private struct DarkControlPlaybackState {
    let isPlaying: Bool
    let isLoading: Bool

    func visualState(connection: DarkControlConnectionState) -> DarkPlaybackVisualState {
        switch connection {
        case .disconnected:
            return .stopped
        case .reconnecting:
            return .reconnecting
        case .connecting:
            return .loading
        case .connected:
            if isLoading {
                return .loading
            }
            return isPlaying ? .playing : .paused
        }
    }
}

private struct DarkControlUIState {
    let system: DarkControlSystemState
    let playback: DarkPlaybackVisualState
}

enum DarkControlConnectionState: String {
    case connected
    case reconnecting
    case connecting
    case disconnected

    var compactTitle: String {
        let key: String = switch self {
        case .connected:
            "Sony"
        case .connecting:
            "连接中"
        case .reconnecting:
            "重连中"
        case .disconnected:
            "连接"
        }
        return AppLocalization.string(key)
    }

    var showsProgressIndicator: Bool {
        self == .connecting || self == .reconnecting
    }

    var accessibilityLabel: String {
        let key: String = switch self {
        case .connected:
            "Sony 已连接"
        case .connecting:
            "正在连接 Sony"
        case .reconnecting:
            "正在重新连接 Sony"
        case .disconnected:
            "Sony 未连接"
        }
        return AppLocalization.string(key)
    }

    var color: Color {
        switch self {
        case .connected:
            return .green
        case .connecting:
            return .orange
        case .reconnecting:
            return .orange
        case .disconnected:
            return .orange
        }
    }
}

struct CompactVolumePresentation {
    static func normalizedProgress(current: Int, maximum: Int) -> Double {
        guard maximum > 0 else { return 0 }
        return min(max(Double(current) / Double(maximum), 0), 1)
    }
}

struct CompactSliderPresentation {
    static let trackHeight: CGFloat = 3
    static let thumbDiameter: CGFloat = 11
    static let interactionHeight: CGFloat = 32

    static func normalizedProgress(
        value: Double,
        lowerBound: Double,
        upperBound: Double
    ) -> Double {
        guard upperBound > lowerBound else { return 0 }
        return min(max((value - lowerBound) / (upperBound - lowerBound), 0), 1)
    }
}

private enum DarkPlaybackVisualState: Equatable {
    case playing
    case paused
    case loading
    case reconnecting
    case stopped

    var title: String {
        let key: String = switch self {
        case .playing:
            "正在播放"
        case .paused:
            "已暂停"
        case .loading:
            "加载中"
        case .reconnecting:
            "重连中"
        case .stopped:
            "已停止"
        }
        return AppLocalization.string(key)
    }

    var detail: String {
        let key: String = switch self {
        case .playing:
            "正在播放音乐"
        case .paused:
            "音乐暂停状态"
        case .loading:
            "内容加载中"
        case .reconnecting:
            "设备重连中"
        case .stopped:
            "音乐已停止"
        }
        return AppLocalization.string(key)
    }

    var accentColor: Color {
        switch self {
        case .playing:
            return PlayerDesignTokens.stableAccent
        case .paused:
            return .blue
        case .loading:
            return PlayerDesignTokens.warning
        case .reconnecting:
            return PlayerDesignTokens.warning
        case .stopped:
            return PlayerDesignTokens.disconnected
        }
    }

    var icon: String {
        switch self {
        case .playing:
            return "pause.fill"
        case .paused:
            return "play.fill"
        case .loading:
            return "circle.dotted"
        case .reconnecting:
            return "arrow.clockwise"
        case .stopped:
            return "stop.fill"
        }
    }
}

private struct DarkPlaybackStatusBadge: View {
    let state: DarkPlaybackVisualState

    var body: some View {
        HStack(spacing: 7) {
            if state == .playing {
                MicroWaveformBars(color: state.accentColor.opacity(0.92), height: 15, animated: true)
                    .frame(width: 22, height: 15)
            } else if state == .loading || state == .reconnecting {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(state.accentColor)
                    .scaleEffect(0.62)
                    .frame(width: 20, height: 18)
            } else {
                Image(systemName: state.icon)
                    .font(.system(size: 12, weight: .bold))
            }

            Text(state.title)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
        }
        .foregroundStyle(state.accentColor.opacity(0.92))
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(state.accentColor.opacity(0.10), in: Capsule())
        .overlay {
            Capsule().strokeBorder(state.accentColor.opacity(0.12), lineWidth: 1)
        }
    }
}

private struct DarkDynamicPlayButton: View {
    let state: DarkPlaybackVisualState
    let isEnabled: Bool
    let action: () -> Void

    @State private var isPulsing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(Color.black.opacity(0.30))
                    .background(.ultraThinMaterial, in: Circle())
                    .frame(width: 86, height: 86)
                    .overlay(alignment: .topLeading) {
                        Circle()
                            .fill(state.accentColor.opacity(state == .playing ? 0.18 : 0.10))
                            .blur(radius: 12)
                            .padding(12)
                    }
                    .overlay {
                        Circle()
                            .strokeBorder(state.accentColor.opacity(state == .playing ? 0.72 : 0.52), lineWidth: 1)
                    }
                    .overlay {
                        Circle()
                            .strokeBorder(.white.opacity(0.09), lineWidth: 1)
                            .padding(1)
                    }
                    .shadow(
                        color: state == .playing ? state.accentColor.opacity(isPulsing ? 0.28 : 0.12) : .clear,
                        radius: state == .playing ? (isPulsing ? 18 : 8) : 0
                    )
                    .scaleEffect(state == .playing && isPulsing && !reduceMotion ? 1.018 : 1.0)

                if state == .loading || state == .reconnecting {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .scaleEffect(1.18)
                } else {
                    Image(systemName: state.icon)
                        .font(.system(size: 33, weight: .bold))
                        .foregroundStyle(state == .paused ? .white.opacity(0.94) : .white)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.90))
        .disabled(!isEnabled || state == .loading || state == .reconnecting)
        .opacity(isEnabled ? 1 : 0.55)
        .onAppear {
            isPulsing = !reduceMotion
        }
        .animation(
            state == .playing && !reduceMotion
                ? .easeInOut(duration: 1.15).repeatForever(autoreverses: true)
                : nil,
            value: isPulsing
        )
        .animation(reduceMotion ? nil : .spring(response: 0.26, dampingFraction: 0.72), value: state)
        .accessibilityLabel("播放 / 暂停")
    }
}

private struct MicroWaveformBars: View {
    let color: Color
    let height: CGFloat
    var animated: Bool

    @State private var phase = false

    private let multipliers: [CGFloat] = [0.38, 0.76, 0.52, 0.92, 0.44]

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            ForEach(multipliers.indices, id: \.self) { index in
                Capsule()
                    .fill(color)
                    .frame(
                        width: 3,
                        height: height * (animated && phase ? multipliers[index] : max(0.35, multipliers.reversed()[index]))
                    )
                    .animation(
                        animated
                            ? .easeInOut(duration: 0.55 + Double(index) * 0.10).repeatForever(autoreverses: true)
                            : .default,
                        value: phase
                    )
            }
        }
        .onAppear {
            if animated {
                phase = true
            }
        }
    }
}

private struct DarkLyricRhythmLine: View {
    let state: DarkPlaybackVisualState
    let trackSeed: String
    let lyricProgress: Double
    let wordSignature: String
    let positionMs: Int64

    @StateObject private var spectrumEngine = NaturalPseudoSpectrumEngine()
    @ObservedObject private var preferences = PreferencesStore.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var opacity: Double {
        switch state {
        case .playing:
            return 0.62
        case .paused:
            return 0.27
        case .loading, .reconnecting:
            return 0.22
        case .stopped:
            return 0.14
        }
    }

    var body: some View {
        Group {
            if let frameInterval {
                TimelineView(.animation(minimumInterval: frameInterval)) { timeline in
                    spectrumFrame(
                        time: timeline.date.timeIntervalSinceReferenceDate
                    )
                }
            } else {
                spectrumFrame(time: 0)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }

    private var frameInterval: TimeInterval? {
        guard !reduceMotion else {
            return nil
        }
        let performanceMode = preferences.playbackPerformanceMode
        if performanceMode == .powerSaving {
            return nil
        }
        if performanceMode == .automatic,
           ProcessInfo.processInfo.isLowPowerModeEnabled {
            return nil
        }
        switch state {
        case .playing:
            return performanceMode == .smooth ? 1.0 / 20.0 : 1.0 / 15.0
        case .loading, .reconnecting:
            return performanceMode == .smooth ? 1.0 / 15.0 : 1.0 / 12.0
        case .paused, .stopped:
            return nil
        }
    }

    private func spectrumFrame(time: TimeInterval) -> some View {
        GeometryReader { proxy in
            let levels = spectrumEngine.levels(
                time: time,
                width: proxy.size.width,
                state: state,
                trackSeed: trackSeed,
                lyricProgress: lyricProgress,
                wordSignature: wordSignature,
                positionMs: positionMs
            )
            spectrumCanvas(levels: levels)
        }
    }

    private func spectrumCanvas(levels: [Double]) -> some View {
        Canvas { context, size in
            guard !levels.isEmpty, size.width > 0, size.height > 0 else { return }

            let accent = state.accentColor
            let centerY = size.height / 2
            let usableWidth = size.width
            let count = levels.count
            let step = usableWidth / CGFloat(count)
            let gap = max(2.0, min(4.0, step * 0.28))
            let barWidth = max(2.4, step - gap)
            let minHeight = max(2.4, size.height * 0.12)
            let maxHeight = size.height * 0.92

            let lineRect = CGRect(x: 0, y: centerY - 0.6, width: usableWidth, height: 1.2)
            context.fill(
                Path(roundedRect: lineRect, cornerRadius: 0.6),
                with: .linearGradient(
                    Gradient(colors: [
                        .clear,
                        accent.opacity(opacity * 0.20),
                        accent.opacity(opacity * 0.30),
                        accent.opacity(opacity * 0.20),
                        .clear
                    ]),
                    startPoint: CGPoint(x: 0, y: centerY),
                    endPoint: CGPoint(x: usableWidth, y: centerY)
                )
            )

            for index in levels.indices {
                let x = CGFloat(index) * step + step / 2
                let heightRatio = max(0, min(1, levels[index]))
                let height = minHeight + CGFloat(heightRatio) * (maxHeight - minHeight)
                let bandOpacity = opacity * (0.52 + heightRatio * 0.48)
                let rect = CGRect(
                    x: x - barWidth / 2,
                    y: centerY - height / 2,
                    width: barWidth,
                    height: height
                )
                let path = Path(roundedRect: rect, cornerRadius: barWidth / 2)

                context.fill(
                    path,
                    with: .linearGradient(
                        Gradient(colors: [
                            accent.opacity(bandOpacity * 0.30),
                            accent.opacity(bandOpacity),
                            accent.opacity(bandOpacity * 0.44)
                        ]),
                        startPoint: CGPoint(x: rect.midX, y: rect.maxY),
                        endPoint: CGPoint(x: rect.midX, y: rect.minY)
                    )
                )
            }
        }
    }
}

private final class NaturalPseudoSpectrumEngine: ObservableObject {
    private struct Constants {
        static let minBars = 28
        static let maxBars = 44
        static let attack = 0.38
        static let release = 0.13
        static let pauseRelease = 0.08
        static let stoppedRelease = 0.14
        static let wordPulseDuration = 0.20
        static let wordPulseDebounce = 0.12
        static let entranceDuration = 0.60
        static let idleBaseline = 0.10
    }

    private var levels: [Double] = []
    private var lastTime: TimeInterval?
    private var lastTrackSeed = ""
    private var trackStartedAt: TimeInterval = 0
    private var lastWordSignature = ""
    private var wordPulseStartedAt: TimeInterval?
    private var lastWordPulseAt: TimeInterval = 0
    private var stableSeed: UInt64 = 0

    func levels(
        time: TimeInterval,
        width: CGFloat,
        state: DarkPlaybackVisualState,
        trackSeed: String,
        lyricProgress: Double,
        wordSignature: String,
        positionMs: Int64
    ) -> [Double] {
        let count = barCount(for: width)
        ensureLevelCount(count)
        updateSeedIfNeeded(trackSeed: trackSeed, time: time)
        updateWordPulseIfNeeded(wordSignature: wordSignature, time: time)

        let delta = min(max(time - (lastTime ?? time), 1.0 / 60.0), 0.12)
        lastTime = time

        let rawTargets = rawTargets(
            count: count,
            time: time,
            state: state,
            lyricProgress: lyricProgress,
            positionMs: positionMs
        )
        let targets = smoothTargets(rawTargets)

        for index in levels.indices {
            let target = targets[index]
            let coefficient: Double
            if target > levels[index] {
                coefficient = Constants.attack
            } else if state == .paused {
                coefficient = Constants.pauseRelease
            } else if state == .stopped {
                coefficient = Constants.stoppedRelease
            } else {
                coefficient = Constants.release
            }
            let frameAdjusted = 1.0 - pow(1.0 - coefficient, delta * 60.0)
            levels[index] += (target - levels[index]) * frameAdjusted
        }

        return levels
    }

    private func rawTargets(
        count: Int,
        time: TimeInterval,
        state: DarkPlaybackVisualState,
        lyricProgress: Double,
        positionMs: Int64
    ) -> [Double] {
        let playbackPhase = Double(positionMs % 12_000) / 12_000.0
        let seedPhase = Double(stableSeed % 1_000) / 1_000.0 * Double.pi * 2.0
        let phase = (state == .paused || state == .stopped)
            ? playbackPhase + seedPhase
            : time + playbackPhase
        let global = globalEnergy(state: state, time: time)
        let motion = motionEnergy(state: state, time: time)
        let wordPulse = state == .playing ? currentWordPulse(time: time) : 0
        let entrance = trackEntrancePulse(time: time)

        return (0..<count).map { index in
            let x = Double(index) / Double(max(count - 1, 1))
            let band = bandProfile(x)
            let seedNoise = seededUnit(index: index + Int(stableSeed % 997))
            let localPhase = seedNoise * Double.pi * 2.0
            let group = floor(x * 9.0)
            let low = sin(phase * band.lowRate + localPhase * 0.62 + group * 0.38)
            let mid = sin(phase * band.midRate + localPhase * 1.17 + sin(phase * 0.62 + group) * 0.46)
            let high = sin(phase * band.highRate + localPhase * 2.3 + group * 1.15)
            let beat = pow((sin(phase * band.beatRate + localPhase * 0.7) + 1.0) / 2.0, band.beatShape)
            let lyricAccent = state == .playing
                ? sin((lyricProgress * Double.pi * 2.0) + localPhase * 0.25) * 0.025
                : 0
            let energy =
                Constants.idleBaseline
                + global * band.weight
                + motion * low * band.lowAmount
                + motion * mid * band.midAmount
                + motion * high * band.highAmount
                + motion * beat * band.beatAmount
                + wordPulse * band.wordAmount
                + entrance * band.entranceAmount
                + lyricAccent
            return min(1.0, max(0.04, energy))
        }
    }

    private func globalEnergy(state: DarkPlaybackVisualState, time: TimeInterval) -> Double {
        switch state {
        case .playing:
            return 0.34 + 0.05 * sin(time * 1.2)
        case .paused:
            return 0.055
        case .loading:
            return 0.10 + 0.04 * (sin(time * 2.0) + 1.0) / 2.0
        case .reconnecting:
            return 0.12 + 0.06 * (sin(time * 1.05) + 1.0) / 2.0
        case .stopped:
            return 0.02
        }
    }

    private func motionEnergy(state: DarkPlaybackVisualState, time: TimeInterval) -> Double {
        switch state {
        case .playing:
            return 1.0
        case .paused:
            return 0.08
        case .loading:
            return 0.12 + 0.04 * (sin(time * 1.6) + 1.0) / 2.0
        case .reconnecting:
            return 0.14 + 0.06 * (sin(time * 1.0) + 1.0) / 2.0
        case .stopped:
            return 0.0
        }
    }

    private func smoothTargets(_ raw: [Double]) -> [Double] {
        guard raw.count > 2 else { return raw }
        return raw.indices.map { index in
            let x = Double(index) / Double(max(raw.count - 1, 1))
            let nearBandBoundary = abs(x - 0.28) < 0.035 || abs(x - 0.76) < 0.035
            let neighborWeight = nearBandBoundary ? 0.06 : 0.13
            let selfWeight = 1.0 - neighborWeight * 2.0
            let left = raw[max(0, index - 1)]
            let right = raw[min(raw.count - 1, index + 1)]
            return raw[index] * selfWeight + left * neighborWeight + right * neighborWeight
        }
    }

    private func bandProfile(_ x: Double) -> RhythmBandProfile {
        if x < 0.28 {
            return RhythmBandProfile(
                weight: 0.90,
                lowRate: 1.45,
                lowSpan: 5.0,
                lowAmount: 0.18,
                midRate: 2.25,
                midSpan: 8.5,
                midAmount: 0.12,
                highRate: 4.0,
                highSpan: 16.0,
                highAmount: 0.035,
                beatRate: 1.95,
                beatShape: 2.4,
                beatAmount: 0.16,
                wordAmount: 0.03,
                entranceAmount: 0.08
            )
        } else if x < 0.76 {
            return RhythmBandProfile(
                weight: 0.72,
                lowRate: 1.10,
                lowSpan: 4.2,
                lowAmount: 0.11,
                midRate: 3.25,
                midSpan: 17.0,
                midAmount: 0.18,
                highRate: 7.8,
                highSpan: 30.0,
                highAmount: 0.07,
                beatRate: 2.55,
                beatShape: 2.9,
                beatAmount: 0.12,
                wordAmount: 0.14,
                entranceAmount: 0.06
            )
        } else {
            return RhythmBandProfile(
                weight: 0.46,
                lowRate: 0.85,
                lowSpan: 3.0,
                lowAmount: 0.055,
                midRate: 3.8,
                midSpan: 18.0,
                midAmount: 0.09,
                highRate: 10.8,
                highSpan: 42.0,
                highAmount: 0.13,
                beatRate: 3.4,
                beatShape: 3.2,
                beatAmount: 0.08,
                wordAmount: 0.05,
                entranceAmount: 0.03
            )
        }
    }

    private func currentWordPulse(time: TimeInterval) -> Double {
        guard let startedAt = wordPulseStartedAt else { return 0 }
        let age = time - startedAt
        guard age >= 0, age <= Constants.wordPulseDuration else { return 0 }
        let normalized = age / Constants.wordPulseDuration
        return pow(1.0 - normalized, 2.2)
    }

    private func trackEntrancePulse(time: TimeInterval) -> Double {
        let age = time - trackStartedAt
        guard age >= 0, age <= Constants.entranceDuration else { return 0 }
        return 0.22 * pow(1.0 - age / Constants.entranceDuration, 1.8)
    }

    private func updateSeedIfNeeded(trackSeed: String, time: TimeInterval) {
        guard trackSeed != lastTrackSeed else { return }
        lastTrackSeed = trackSeed
        stableSeed = stableHash(trackSeed)
        trackStartedAt = time
        lastWordSignature = ""
        wordPulseStartedAt = nil
    }

    private func updateWordPulseIfNeeded(wordSignature: String, time: TimeInterval) {
        guard !wordSignature.isEmpty else { return }
        if lastWordSignature.isEmpty {
            lastWordSignature = wordSignature
            return
        }
        guard wordSignature != lastWordSignature else { return }
        lastWordSignature = wordSignature
        guard time - lastWordPulseAt >= Constants.wordPulseDebounce else { return }
        lastWordPulseAt = time
        wordPulseStartedAt = time
    }

    private func ensureLevelCount(_ count: Int) {
        guard count > 0 else {
            levels = []
            return
        }
        guard levels.count != count else { return }
        guard !levels.isEmpty else {
            levels = Array(repeating: Constants.idleBaseline, count: count)
            return
        }
        let oldLevels = levels
        let oldCount = oldLevels.count
        levels = (0..<count).map { index in
            let progress = Double(index) / Double(max(count - 1, 1))
            let oldPosition = progress * Double(max(oldCount - 1, 1))
            let lower = Int(floor(oldPosition))
            let upper = min(oldCount - 1, lower + 1)
            let fraction = oldPosition - Double(lower)
            return oldLevels[lower] * (1.0 - fraction) + oldLevels[upper] * fraction
        }
    }

    private func barCount(for width: CGFloat) -> Int {
        let estimated = Int((width / 8.5).rounded())
        return min(Constants.maxBars, max(Constants.minBars, estimated))
    }

    private func seededUnit(index: Int) -> Double {
        let value = sin(Double(index * 127 + 31) * 12.9898 + Double(stableSeed % 10_000)) * 43758.5453
        return value - floor(value)
    }

    private func stableHash(_ text: String) -> UInt64 {
        var hash: UInt64 = 1469598103934665603
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1099511628211
        }
        return hash
    }
}

private struct RhythmBandProfile {
    let weight: Double
    let lowRate: Double
    let lowSpan: Double
    let lowAmount: Double
    let midRate: Double
    let midSpan: Double
    let midAmount: Double
    let highRate: Double
    let highSpan: Double
    let highAmount: Double
    let beatRate: Double
    let beatShape: Double
    let beatAmount: Double
    let wordAmount: Double
    let entranceAmount: Double
}

private struct DarkLyricSideDots: View {
    let color: Color

    var body: some View {
        VStack(spacing: 12) {
            Circle().fill(.white.opacity(0.14)).frame(width: 6, height: 6)
            Circle().fill(color).frame(width: 7, height: 7)
            Circle().fill(.white.opacity(0.14)).frame(width: 6, height: 6)
        }
    }
}

private struct PressScaleButtonStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1.0)
            .animation(.spring(response: 0.18, dampingFraction: 0.72), value: configuration.isPressed)
    }
}

struct KaraokeLyricText: View {
    let text: String
    let progress: Double
    var words: [LyricWord] = []
    var positionMs: Int64? = nil
    var isPlaying = false
    let highlightColor: Color
    let normalColor: Color
    let font: Font
    var lineLimit: Int? = nil
    var alignment: TextAlignment = .leading

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var preferences = PreferencesStore.shared
    @State private var renderedProgress = 0.0
    @State private var previousTargetProgress: Double?

    private var rawHighlightResult: KaraokeHighlightResult {
        KaraokeHighlightResolver.resolve(
            text: text,
            words: words,
            positionMs: positionMs,
            fallbackProgress: progress
        )
    }

    private var targetHighlightResult: KaraokeHighlightResult {
        KaraokeHighlightResolver.resolve(
            text: text,
            words: words,
            positionMs: positionMs,
            fallbackProgress: progress,
            lookAheadMs: isPlaying && !reduceMotion ? interpolationLookAheadMs : 0
        )
    }

    private var targetProgress: Double {
        targetHighlightResult.normalizedProgress
    }

    private var interpolationDuration: TimeInterval {
        KaraokeProgressAnimationPolicy.duration(
            for: preferences.playbackPerformanceMode,
            isLowPowerModeEnabled: ProcessInfo.processInfo.isLowPowerModeEnabled
        )
    }

    private var interpolationLookAheadMs: Int64 {
        Int64((interpolationDuration * 1_000).rounded())
    }

    var body: some View {
        ZStack {
            lyricText(color: normalColor)
            lyricText(color: highlightColor)
                .textRenderer(KaraokeProgressTextRenderer(progress: renderedProgress))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .onAppear(perform: resetProgress)
        .onChange(of: text) { _, _ in resetProgress() }
        .onChange(of: targetProgress) { oldValue, newValue in
            updateRenderedProgress(from: oldValue, to: newValue)
        }
        .onChange(of: reduceMotion) { _, _ in resetProgress() }
        .onChange(of: isPlaying) { _, playing in
            if !playing { resetProgress() }
        }
    }

    private func lyricText(color: Color) -> some View {
        Text(text)
            .foregroundStyle(color)
            .font(font)
            .multilineTextAlignment(alignment)
            .lineLimit(lineLimit)
    }

    private func resetProgress() {
        let progress = rawHighlightResult.normalizedProgress
        previousTargetProgress = progress
        var transaction = Transaction()
        transaction.animation = nil
        withTransaction(transaction) {
            renderedProgress = progress
        }
    }

    private func updateRenderedProgress(from oldValue: Double, to newValue: Double) {
        let previous = previousTargetProgress ?? oldValue
        previousTargetProgress = newValue
        guard KaraokeProgressAnimationPolicy.shouldAnimate(
            from: previous,
            to: newValue,
            isPlaying: isPlaying,
            reduceMotion: reduceMotion
        ) else {
            resetProgress()
            return
        }
        withAnimation(.linear(duration: interpolationDuration)) {
            renderedProgress = newValue
        }
    }
}

struct KaraokeHighlightResult: Equatable {
    let highlightedText: String
    let remainingText: String
    let highlightedCharacterCount: Int
    let highlightedCharacterProgress: Double
    let totalCharacterCount: Int

    var normalizedProgress: Double {
        guard totalCharacterCount > 0 else { return 0 }
        return min(max(highlightedCharacterProgress / Double(totalCharacterCount), 0), 1)
    }
}

enum KaraokeHighlightResolver {
    static func resolve(
        text: String,
        words: [LyricWord],
        positionMs: Int64?,
        fallbackProgress: Double,
        lookAheadMs: Int64 = 0
    ) -> KaraokeHighlightResult {
        let characters = Array(text)
        let count = characters.count
        guard count > 0 else {
            return KaraokeHighlightResult(
                highlightedText: "",
                remainingText: "",
                highlightedCharacterCount: 0,
                highlightedCharacterProgress: 0,
                totalCharacterCount: 0
            )
        }
        let highlightProgress: Double
        if let positionMs, !words.isEmpty {
            highlightProgress = wordHighlightProgress(
                textCharacters: characters,
                words: words,
                positionMs: positionMs + max(lookAheadMs, 0)
            )
        } else {
            let boundedProgress = min(max(fallbackProgress, 0), 1)
            highlightProgress = Double(count) * boundedProgress
        }
        let boundedHighlightProgress = min(max(highlightProgress, 0), Double(count))
        let highlightCount = Int(boundedHighlightProgress.rounded(.down))
        return KaraokeHighlightResult(
            highlightedText: String(characters.prefix(highlightCount)),
            remainingText: String(characters.dropFirst(highlightCount)),
            highlightedCharacterCount: highlightCount,
            highlightedCharacterProgress: boundedHighlightProgress,
            totalCharacterCount: count
        )
    }

    private static func wordHighlightProgress(
        textCharacters: [Character],
        words: [LyricWord],
        positionMs: Int64
    ) -> Double {
        var searchStart = 0
        var completedEnd = 0
        for word in words {
            let wordCharacters = Array(word.text)
            guard !wordCharacters.isEmpty else { continue }
            let wordStart = find(
                wordCharacters,
                in: textCharacters,
                startingAt: searchStart
            ) ?? min(searchStart, textCharacters.count)
            let wordEnd = min(wordStart + wordCharacters.count, textCharacters.count)
            guard positionMs >= word.startMs else {
                return Double(min(completedEnd, textCharacters.count))
            }
            let durationMs = max(word.durationMs, 1)
            let elapsedMs = positionMs - word.startMs
            if elapsedMs < durationMs {
                let fraction = min(max(Double(elapsedMs) / Double(durationMs), 0), 1)
                let partialProgress = Double(wordStart) +
                    Double(max(wordEnd - wordStart, 0)) * fraction
                return min(
                    max(partialProgress, Double(completedEnd)),
                    Double(textCharacters.count)
                )
            }
            completedEnd = max(completedEnd, wordEnd)
            searchStart = wordEnd
        }
        return Double(textCharacters.count)
    }

    private static func find(
        _ needle: [Character],
        in haystack: [Character],
        startingAt start: Int
    ) -> Int? {
        guard !needle.isEmpty,
              start < haystack.count,
              needle.count <= haystack.count else { return nil }
        let lastStart = haystack.count - needle.count
        guard start <= lastStart else { return nil }
        for index in start...lastStart {
            if haystack[index..<(index + needle.count)].elementsEqual(needle) {
                return index
            }
        }
        return nil
    }
}

struct KaraokeProgressTextRenderer: TextRenderer {
    var progress: Double

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        let boundedProgress = min(max(progress, 0), 1)
        let totalWidth = layout.reduce(CGFloat.zero) { partialResult, line in
            partialResult + max(line.typographicBounds.width, 0)
        }
        guard totalWidth > 0, boundedProgress > 0 else { return }

        var remainingWidth = totalWidth * boundedProgress
        for line in layout {
            let bounds = line.typographicBounds
            let lineWidth = max(bounds.width, 0)
            guard lineWidth > 0, remainingWidth > 0 else { continue }

            if remainingWidth >= lineWidth {
                for run in line { context.draw(run) }
                remainingWidth -= lineWidth
                continue
            }

            var clippedContext = context
            let isRightToLeft = line.first?.layoutDirection == .rightToLeft
            let revealWidth = min(max(remainingWidth, 0), lineWidth)
            let rect = bounds.rect
            let clipRect = CGRect(
                x: isRightToLeft ? rect.maxX - revealWidth : rect.minX,
                y: rect.minY - 1,
                width: revealWidth,
                height: rect.height + 2
            )
            clippedContext.clip(to: Path(clipRect))
            for run in line { clippedContext.draw(run) }
            remainingWidth = 0
        }
    }
}

enum KaraokeProgressAnimationPolicy {
    static func duration(
        for mode: PlaybackPerformanceMode,
        isLowPowerModeEnabled: Bool
    ) -> TimeInterval {
        switch mode {
        case .smooth:
            return 0.10
        case .automatic:
            return isLowPowerModeEnabled ? 0.50 : 0.25
        case .powerSaving:
            return 0.50
        }
    }

    static func shouldAnimate(
        from oldProgress: Double,
        to newProgress: Double,
        isPlaying: Bool,
        reduceMotion: Bool
    ) -> Bool {
        guard isPlaying, !reduceMotion else { return false }
        let delta = newProgress - oldProgress
        return delta >= 0 && delta <= 0.30
    }
}

enum LyricTimelineHelper {
    static func currentIndex(lines: [LyricLine], positionMs: Int64) -> Int? {
        guard !lines.isEmpty else { return nil }
        if positionMs < lines[0].timeMs {
            return 0
        }

        var low = 0
        var high = lines.count - 1
        var result = 0
        while low <= high {
            let mid = (low + high) / 2
            if lines[mid].timeMs <= positionMs {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    static func lineProgress(
        lines: [LyricLine],
        index: Int,
        positionMs: Int64
    ) -> Double {
        guard lines.indices.contains(index) else { return 0 }
        let start = lines[index].timeMs
        let end: Int64
        if lines[index].durationMs > 0 {
            end = start + lines[index].durationMs
        } else if lines.indices.contains(index + 1) {
            end = max(lines[index + 1].timeMs, start + 1_000)
        } else {
            end = start + 4_000
        }
        let duration = max(end - start, 1_000)
        return Double(positionMs - start) / Double(duration)
    }
}

private struct DefaultAlbumArtView: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.08, green: 0.24, blue: 0.44),
                    Color(red: 0.28, green: 0.36, blue: 0.62),
                    Color(red: 0.04, green: 0.05, blue: 0.10)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            Circle()
                .fill(.white.opacity(0.12))
                .frame(width: 150, height: 150)

            Image(systemName: "music.note")
                .font(.system(size: 76, weight: .semibold))
                .foregroundStyle(.white.opacity(0.86))
        }
    }
}

#Preview {
    ContentView()
}
