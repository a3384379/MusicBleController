import SwiftUI
import UIKit

struct PreferencesView: View {
    @ObservedObject var bleManager: BLETestManager
    @ObservedObject private var preferences = PreferencesStore.shared
    let onDismiss: () -> Void

    @State private var actionStatus = ""
    private let signingProfile = ProvisioningProfileInfo.current

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(
                    colors: [
                        Color.black,
                        Color(red: 0.07, green: 0.09, blue: 0.12),
                        Color.black.opacity(0.96)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 14) {
                        currentDeviceSection
                        playerDisplaySection
                        lyricSection
                        artworkSection
                        connectionAndSystemSection
                        playbackHistorySection
                        if preferences.appExperienceMode == .debug {
                            advancedSection
                        }
                        aboutSection
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 18)
                    .padding(.bottom, 34)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("设置")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成", action: onDismiss)
                        .foregroundStyle(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var currentDeviceSection: some View {
        PreferencesCard(title: "当前设备", systemImage: "hifispeaker.2") {
            preferencesRow(
                "设备",
                bleManager.connectedDeviceName == "-" ? "Sony" : bleManager.connectedDeviceName
            )
            preferencesRow("状态", displayConnectionState, valueColor: connectionStatusColor)
            if bleManager.currentMtuBytesForPreferences > 0 {
                preferencesRow("MTU", "\(bleManager.currentMtuBytesForPreferences)")
            }
            actionButton("重新连接", "antenna.radiowaves.left.and.right") {
                bleManager.forceReconnect()
                actionStatus = AppLocalization.string("已请求重新连接")
            }
        }
    }

    private var playerDisplaySection: some View {
        PreferencesCard(title: "播放器显示", systemImage: "rectangle.inset.filled.and.person.filled") {
            VStack(alignment: .leading, spacing: 8) {
                Text("界面语言")
                    .font(.subheadline.weight(.semibold))
                Picker("界面语言", selection: appLanguageBinding) {
                    ForEach(AppLanguage.allCases) { language in
                        Text(language.title).tag(language)
                    }
                }
                .pickerStyle(.segmented)
            }

            Picker("使用模式", selection: appModeBinding) {
                ForEach(AppExperienceMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            Text("日常模式保留核心功能；调试模式显示诊断、日志和协议入口。")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))

            VStack(alignment: .leading, spacing: 8) {
                Text("性能模式")
                    .font(.subheadline.weight(.semibold))
                Picker("性能模式", selection: performanceModeBinding) {
                    ForEach(PlaybackPerformanceMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
            }

            Text(preferences.playbackPerformanceMode.detail)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))

            VStack(alignment: .leading, spacing: 8) {
                Text("灵动岛样式")
                    .font(.subheadline.weight(.semibold))
                Picker("灵动岛样式", selection: dynamicIslandStyleBinding) {
                    ForEach(DynamicIslandStyle.allCases) { style in
                        Text(style.title).tag(style)
                    }
                }
                .pickerStyle(.segmented)
            }

            Text("默认展示封面、标题与歌手；歌词优先突出当前歌词；节奏优先展示播放状态与节奏条。")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
        }
    }

    private var connectionAndSystemSection: some View {
        PreferencesCard(title: "连接与系统", systemImage: "antenna.radiowaves.left.and.right") {
            Toggle("自动重连", isOn: autoReconnectBinding)
                .tint(PlayerDesignTokens.stableAccent)

            preferencesRow("当前连接状态", displayConnectionState)

            Text("关闭自动重连后，手动扫描 / 重连仍然可用。")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
        }
    }

    private var lyricSection: some View {
        PreferencesCard(title: "歌词", systemImage: "text.quote") {
            VStack(alignment: .leading, spacing: 10) {
                Toggle("自动同步歌词时间", isOn: automaticLyricSyncBinding)
                    .tint(PlayerDesignTokens.stableAccent)

                Text("自动补偿 Sony 与 iPhone 时钟差及蓝牙传输延迟")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.58))

                HStack {
                    Text("人工微调")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(offsetLabel(Int64(preferences.lyricOffsetMs)))
                        .font(.caption.monospacedDigit().weight(.bold))
                        .foregroundStyle(PlayerDesignTokens.stableAccent)
                }

                Slider(
                    value: karaokeOffsetBinding,
                    in: -2_000...2_000,
                    step: 100
                )
                .tint(PlayerDesignTokens.stableAccent)
            }

            Picker("歌词显示模式", selection: lyricDisplayModeBinding) {
                ForEach(LyricDisplayMode.allCases) { mode in
                    Text(mode.menuTitle).tag(mode)
                }
            }
            .pickerStyle(.menu)
        }
    }

    private var artworkSection: some View {
        let enhancement = bleManager.artworkEnhancementStatus
        return PreferencesCard(title: "封面", systemImage: "photo.on.rectangle") {
            Toggle("封面增强", isOn: artworkEnhancementBinding)
                .tint(PlayerDesignTokens.stableAccent)

            VStack(alignment: .leading, spacing: 8) {
                Text("封面显示尺寸")
                    .font(.subheadline.weight(.semibold))
                Picker("封面显示尺寸", selection: artworkDisplaySizeBinding) {
                    ForEach(ArtworkDisplaySizeOption.allCases) { option in
                        Text("\(option.title) · \(option.rawValue)pt").tag(option)
                    }
                }
                .pickerStyle(.segmented)
            }

            preferencesRow("当前显示质量", enhancement.displayQuality.label)
            preferencesRow("增强目标", enhancement.target)
            preferencesRow("增强状态", enhancement.lastMessage)
        }
    }

    private var playbackHistorySection: some View {
        PreferencesCard(title: "播放历史", systemImage: "clock.arrow.circlepath") {
            preferencesRow(
                "本地记录",
                "\(bleManager.playbackHistorySessions.count) 条"
            )
            actionButton("同步播放历史", "arrow.clockwise") {
                guard !bleManager.isPlaybackHistorySyncing else { return }
                bleManager.syncPlaybackHistory()
                actionStatus = AppLocalization.string("已请求同步播放历史")
            }
            actionButton("清理增强封面缓存", "photo.badge.minus") {
                bleManager.clearEnhancedArtworkCache()
                actionStatus = AppLocalization.string("已请求清理增强封面缓存")
            }
        }
    }

    private var advancedSection: some View {
        PreferencesCard(title: "高级与诊断", systemImage: "waveform.path.ecg.rectangle") {
            Toggle("强制使用 V2 协议", isOn: forceProtocolV2Binding)
                .tint(PlayerDesignTokens.warning)

            Text("仅用于跨端 A/B 与紧急回退；切换后请重新连接。")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))

            preferencesRow("Health 状态", bleManager.connectionHealthState)
            preferencesRow("最近重连原因", bleManager.connectionHealthLastHardReconnectReason)

            VStack(spacing: 10) {
                actionButton("复制最近日志路径", "doc.on.clipboard") {
                    UIPasteboard.general.string = AppLogStore.shared.currentLogURL.path
                    actionStatus = AppLocalization.string("已复制日志路径")
                }
                if AppLogStore.shared.currentLogFileExists() {
                    ShareLink(item: AppLogStore.shared.currentLogURL) {
                        settingsActionLabel("分享 iOS 日志", "square.and.arrow.up")
                    }
                } else {
                    settingsActionLabel("暂无 iOS 日志可分享", "square.and.arrow.up")
                        .opacity(0.42)
                }
                actionButton("复制当前诊断摘要", "doc.on.doc") {
                    let snapshot = SystemHealthSnapshot(
                        nowPlaying: bleManager.makeNowPlayingDiagnosticSnapshot()
                    )
                    UIPasteboard.general.string = snapshot.copyText
                    actionStatus = AppLocalization.string("已复制诊断摘要")
                }
            }

            Text(AppLogStore.shared.currentLogURL.path)
                .font(.caption2.monospaced())
                .foregroundStyle(.white.opacity(0.46))
                .lineLimit(2)
                .textSelection(.enabled)

            if !actionStatus.isEmpty {
                Text(actionStatus)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(PlayerDesignTokens.stableAccent.opacity(0.9))
            }
        }
    }

    private var aboutSection: some View {
        PreferencesCard(title: "关于", systemImage: "info.circle") {
            preferencesRow("应用", "Sony 音乐控制器")
            preferencesRow("版本", appVersion)
            preferencesRow("构建版本", buildVersion)
            preferencesRow("签名有效期", signingProfileExpireText, valueColor: signingProfileStatusColor)
            preferencesRow("剩余时间", signingProfileRemainingText, valueColor: signingProfileStatusColor)
            preferencesRow("签名团队", signingProfile?.teamIdentifier ?? "-")
            preferencesRow("当前模式", preferences.appExperienceMode.title)
            preferencesRow("连接设备", bleManager.connectedDeviceName == "-" ? "Sony" : bleManager.connectedDeviceName)
        }
    }

    private var appModeBinding: Binding<AppExperienceMode> {
        Binding(
            get: { preferences.appExperienceMode },
            set: { bleManager.setAppExperienceMode($0) }
        )
    }

    private var appLanguageBinding: Binding<AppLanguage> {
        Binding(
            get: { preferences.appLanguage },
            set: { preferences.appLanguage = $0 }
        )
    }

    private var autoReconnectBinding: Binding<Bool> {
        Binding(
            get: { preferences.autoReconnectEnabled },
            set: { bleManager.setAutoReconnectEnabled($0) }
        )
    }

    private var performanceModeBinding: Binding<PlaybackPerformanceMode> {
        Binding(
            get: { preferences.playbackPerformanceMode },
            set: { preferences.playbackPerformanceMode = $0 }
        )
    }

    private var forceProtocolV2Binding: Binding<Bool> {
        Binding(
            get: { preferences.forceProtocolV2 },
            set: { preferences.forceProtocolV2 = $0 }
        )
    }

    private var karaokeOffsetBinding: Binding<Double> {
        Binding(
            get: { Double(preferences.lyricOffsetMs) },
            set: { bleManager.setKaraokeOffsetMs(Int64($0)) }
        )
    }

    private var automaticLyricSyncBinding: Binding<Bool> {
        Binding(
            get: { preferences.automaticLyricSyncEnabled },
            set: { bleManager.setAutomaticLyricSyncEnabled($0) }
        )
    }

    private var lyricDisplayModeBinding: Binding<LyricDisplayMode> {
        Binding(
            get: { preferences.lyricDisplayMode },
            set: { preferences.lyricDisplayMode = $0 }
        )
    }

    private var dynamicIslandStyleBinding: Binding<DynamicIslandStyle> {
        Binding(
            get: { preferences.dynamicIslandStyle },
            set: {
                preferences.dynamicIslandStyle = $0
                bleManager.refreshLiveActivityAppearance()
            }
        )
    }

    private var artworkDisplaySizeBinding: Binding<ArtworkDisplaySizeOption> {
        Binding(
            get: { preferences.artworkDisplaySize },
            set: { preferences.artworkDisplaySize = $0 }
        )
    }

    private var artworkEnhancementBinding: Binding<Bool> {
        Binding(
            get: { bleManager.artworkEnhancementStatus.enabled },
            set: { bleManager.setArtworkEnhancementEnabled($0) }
        )
    }

    private var displayConnectionState: String {
        switch bleManager.connectionDisplayState {
        case "connected": return AppLocalization.string("已连接")
        case "reconnecting": return AppLocalization.string("正在重连")
        case "disconnected": return AppLocalization.string("未连接")
        default: return bleManager.connectionDisplayState
        }
    }

    private var connectionStatusColor: Color {
        ConnectionStatusPresentation.resolve(bleManager.connectionStore.presentation).color
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-"
    }

    private var buildVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"
    }

    private var signingProfileExpireText: String {
        guard let expirationDate = signingProfile?.expirationDate else {
            return "未找到"
        }
        return Self.profileDateFormatter.string(from: expirationDate)
    }

    private var signingProfileRemainingText: String {
        guard let daysRemaining = signingProfile?.daysRemaining else {
            return "未知"
        }
        if daysRemaining < 0 {
            return "已过期"
        }
        if daysRemaining < 1 {
            let hours = max(0, Int((daysRemaining * 24).rounded(.down)))
            return "\(hours) 小时"
        }
        return String(format: "%.1f 天", daysRemaining)
    }

    private var signingProfileStatusColor: Color {
        guard let daysRemaining = signingProfile?.daysRemaining else {
            return .orange
        }
        if daysRemaining < 0 {
            return .red
        }
        if daysRemaining < 2 {
            return .orange
        }
        return .green
    }

    private static let profileDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm zzz"
        return formatter
    }()

    private func offsetLabel(_ value: Int64) -> String {
        value > 0 ? "+\(value)ms" : "\(value)ms"
    }

    private func preferencesRow(
        _ title: String,
        _ value: String,
        valueColor: Color = .white.opacity(0.82)
    ) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(AppLocalization.string(title))
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.52))
                .frame(width: 96, alignment: .leading)
            Text(value.isEmpty ? "-" : value)
                .font(.caption.monospacedDigit())
                .foregroundStyle(valueColor)
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(2)
        }
    }

    private func actionButton(
        _ title: String,
        _ systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            settingsActionLabel(title, systemImage)
        }
        .buttonStyle(.plain)
    }

    private func settingsActionLabel(_ title: String, _ systemImage: String) -> some View {
        Label(AppLocalization.string(title), systemImage: systemImage)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

private struct ProvisioningProfileInfo {
    let expirationDate: Date
    let teamIdentifier: String?

    var daysRemaining: Double {
        expirationDate.timeIntervalSince(Date()) / 86_400
    }

    static let current: ProvisioningProfileInfo? = {
        guard
            let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
            let data = try? Data(contentsOf: url),
            let plistData = extractPlistData(from: data),
            let object = try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil),
            let plist = object as? [String: Any],
            let expirationDate = plist["ExpirationDate"] as? Date
        else {
            return nil
        }

        let teamIdentifier = (plist["TeamIdentifier"] as? [String])?.first
        return ProvisioningProfileInfo(
            expirationDate: expirationDate,
            teamIdentifier: teamIdentifier
        )
    }()

    private static func extractPlistData(from data: Data) -> Data? {
        guard
            let startMarker = "<?xml".data(using: .utf8),
            let endMarker = "</plist>".data(using: .utf8),
            let startRange = data.range(of: startMarker),
            let endRange = data.range(of: endMarker)
        else {
            return nil
        }

        let endIndex = endRange.upperBound
        guard startRange.lowerBound < endIndex else {
            return nil
        }

        return data.subdata(in: startRange.lowerBound..<endIndex)
    }
}

private struct PreferencesCard<Content: View>: View {
    let title: String
    let systemImage: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            Label(AppLocalization.string(title), systemImage: systemImage)
                .font(.headline.weight(.bold))

            content
        }
        .foregroundStyle(.white)
        .padding(16)
        .background(.black.opacity(0.28), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.white.opacity(0.09), lineWidth: 1)
        }
    }
}
