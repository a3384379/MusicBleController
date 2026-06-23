import SwiftUI
import UIKit

struct PreferencesView: View {
    @ObservedObject var bleManager: BLETestManager
    @ObservedObject private var preferences = PreferencesStore.shared
    let onDismiss: () -> Void

    @State private var actionStatus = ""

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
                        modeSection
                        connectionSection
                        lyricSection
                        artworkSection
                        cacheAndLogSection
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

    private var modeSection: some View {
        PreferencesCard(title: "使用模式", systemImage: "person.crop.circle") {
            Picker("使用模式", selection: appModeBinding) {
                ForEach(AppExperienceMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            Text("日常模式保留播放器核心功能；调试模式显示诊断、日志和高级入口。")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
        }
    }

    private var connectionSection: some View {
        PreferencesCard(title: "连接", systemImage: "antenna.radiowaves.left.and.right") {
            Toggle("自动重连", isOn: autoReconnectBinding)
                .tint(.green)

            preferencesRow("当前连接状态", displayConnectionState)
            preferencesRow("Health 状态", bleManager.connectionHealthState)
            preferencesRow(
                "MTU",
                bleManager.currentMtuBytesForPreferences > 0
                    ? "\(bleManager.currentMtuBytesForPreferences)"
                    : "-"
            )
            preferencesRow("最近重连原因", bleManager.connectionHealthLastHardReconnectReason)

            Text("关闭自动重连后，手动扫描 / 重连仍然可用。")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
        }
    }

    private var lyricSection: some View {
        PreferencesCard(title: "歌词", systemImage: "text.quote") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("歌词偏移校准")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(offsetLabel(Int64(preferences.lyricOffsetMs)))
                        .font(.caption.monospacedDigit().weight(.bold))
                        .foregroundStyle(.green)
                }

                Slider(
                    value: karaokeOffsetBinding,
                    in: -2_000...2_000,
                    step: 100
                )
                .tint(.green)
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
                .tint(.green)

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

    private var cacheAndLogSection: some View {
        PreferencesCard(title: "缓存与日志", systemImage: "externaldrive") {
            VStack(spacing: 10) {
                actionButton("清理增强封面缓存", "trash") {
                    bleManager.clearEnhancedArtworkCache()
                    actionStatus = "已请求清理增强封面缓存"
                }
                actionButton("复制最近日志路径", "doc.on.clipboard") {
                    UIPasteboard.general.string = AppLogStore.shared.currentLogURL.path
                    actionStatus = "已复制日志路径"
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
                    actionStatus = "已复制诊断摘要"
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
                    .foregroundStyle(.green.opacity(0.9))
            }
        }
    }

    private var aboutSection: some View {
        PreferencesCard(title: "关于", systemImage: "info.circle") {
            preferencesRow("App", "Sony Music BLE Controller")
            preferencesRow("版本", appVersion)
            preferencesRow("Build", buildVersion)
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

    private var autoReconnectBinding: Binding<Bool> {
        Binding(
            get: { preferences.autoReconnectEnabled },
            set: { bleManager.setAutoReconnectEnabled($0) }
        )
    }

    private var karaokeOffsetBinding: Binding<Double> {
        Binding(
            get: { Double(preferences.lyricOffsetMs) },
            set: { bleManager.setKaraokeOffsetMs(Int64($0)) }
        )
    }

    private var lyricDisplayModeBinding: Binding<LyricDisplayMode> {
        Binding(
            get: { preferences.lyricDisplayMode },
            set: { preferences.lyricDisplayMode = $0 }
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
        case "connected": return "已连接"
        case "reconnecting": return "正在重连"
        case "disconnected": return "未连接"
        default: return bleManager.connectionDisplayState
        }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-"
    }

    private var buildVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"
    }

    private func offsetLabel(_ value: Int64) -> String {
        value > 0 ? "+\(value)ms" : "\(value)ms"
    }

    private func preferencesRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.52))
                .frame(width: 96, alignment: .leading)
            Text(value.isEmpty ? "-" : value)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.82))
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
        Label(title, systemImage: systemImage)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

private struct PreferencesCard<Content: View>: View {
    let title: String
    let systemImage: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            Label(title, systemImage: systemImage)
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
