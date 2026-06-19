import SwiftUI

struct DebugToolsView: View {
    @ObservedObject var bleManager: BLETestManager
    @Environment(\.dismiss) private var dismiss
    @State private var showIOSLogs = true
    @State private var showSonyLogs = true
    @State private var showMediaFieldDump = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    statusSection
                    actionSection
                    sonyLogSection
                    mediaFieldDumpSection
                    localLogSection
                }
                .padding(18)
            }
            .background(debugBackground)
            .navigationTitle("调试工具")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var statusSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("连接状态", systemImage: "antenna.radiowaves.left.and.right")
                    .font(.headline)

                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                    debugRow("状态", bleManager.connectionStatus)
                    debugRow("模式", bleManager.mode)
                    debugRow("歌曲", bleManager.title)
                    debugRow("歌手", bleManager.artist)
                    debugRow("专辑", bleManager.album)
                    debugRow(
                        "进度",
                        "\(format(milliseconds: bleManager.displayPositionMs)) / \(format(milliseconds: bleManager.durationMs))"
                    )
                    debugRow("音量", "\(bleManager.volumeCurrent) / \(bleManager.volumeMax)")
                }
            }
        }
    }

    private var actionSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("操作")
                    .font(.headline)

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 10),
                        GridItem(.flexible(), spacing: 10)
                    ],
                    spacing: 10
                ) {
                    debugActionButton(
                        title: "刷新播放状态",
                        systemImage: "arrow.clockwise",
                        disabled: !isConnected,
                        action: bleManager.sendGetPlaybackState
                    )
                    debugActionButton(
                        title: "刷新音量",
                        systemImage: "speaker.wave.2",
                        disabled: !isConnected,
                        action: bleManager.sendGetVolume
                    )
                    debugActionButton(
                        title: "获取 Sony 日志",
                        systemImage: "arrow.down.doc",
                        disabled: !isConnected,
                        action: bleManager.sendGetSonyLogs
                    )
                    debugActionButton(
                        title: "Dump Media Fields",
                        systemImage: "list.bullet.clipboard",
                        disabled: !isConnected || bleManager.isMediaFieldDumpReceiving,
                        action: bleManager.sendDumpMediaFields
                    )
                }
            }
        }
    }

    private var sonyLogSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                disclosureHeader(
                    title: "Sony 日志",
                    systemImage: "terminal",
                    isExpanded: showSonyLogs
                ) {
                    showSonyLogs.toggle()
                }

                if bleManager.isRemoteLogTransferInProgress {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Sony 日志传输中...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if showSonyLogs {
                    if bleManager.remoteLogText.isEmpty {
                        Text("尚未获取")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        debugTextBox(bleManager.remoteLogText, height: 240)
                        HStack(spacing: 10) {
                            Button(action: bleManager.copySonyLogs) {
                                Label("复制 Sony 日志", systemImage: "doc.on.doc")
                            }
                            .buttonStyle(.bordered)

                            if !bleManager.remoteLogCopyStatus.isEmpty {
                                Text(bleManager.remoteLogCopyStatus)
                                    .font(.caption)
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }
            }
        }
    }

    private var mediaFieldDumpSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                disclosureHeader(
                    title: "Media Field Dump",
                    systemImage: "doc.text.magnifyingglass",
                    isExpanded: showMediaFieldDump
                ) {
                    showMediaFieldDump.toggle()
                }

                if bleManager.isMediaFieldDumpReceiving ||
                    !bleManager.mediaFieldDumpProgressText.isEmpty {
                    HStack(spacing: 8) {
                        if bleManager.isMediaFieldDumpReceiving {
                            ProgressView()
                        }
                        Text(bleManager.mediaFieldDumpProgressText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if showMediaFieldDump {
                    if bleManager.mediaFieldDumpText.isEmpty {
                        Text("尚未获取")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        debugTextBox(bleManager.mediaFieldDumpText, height: 280)
                        HStack(spacing: 10) {
                            Button(action: bleManager.copyMediaFieldDump) {
                                Label("复制 Media Dump", systemImage: "doc.on.doc")
                            }
                            .buttonStyle(.bordered)

                            if !bleManager.mediaFieldDumpCopyStatus.isEmpty {
                                Text(bleManager.mediaFieldDumpCopyStatus)
                                    .font(.caption)
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }
            }
        }
    }

    private var localLogSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                disclosureHeader(
                    title: "iOS 本地日志",
                    systemImage: "iphone.gen3",
                    isExpanded: showIOSLogs
                ) {
                    showIOSLogs.toggle()
                }

                if showIOSLogs {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 4) {
                                ForEach(Array(bleManager.logs.enumerated()), id: \.offset) { index, line in
                                    Text(line)
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(.primary.opacity(0.82))
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .id(index)
                                }
                            }
                            .padding(10)
                        }
                        .frame(height: 220)
                        .background(.black.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))
                        .onChange(of: bleManager.logs.count) { _, count in
                            guard count > 0 else { return }
                            proxy.scrollTo(count - 1, anchor: .bottom)
                        }
                    }
                }
            }
        }
    }

    private var debugBackground: some View {
        LinearGradient(
            colors: [
                Color(.systemBackground),
                Color(.secondarySystemBackground)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }

    private var isConnected: Bool {
        bleManager.connectionStatus == "已连接"
    }

    private func debugRow(_ title: String, _ value: String) -> some View {
        GridRow {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value.isEmpty ? "-" : value)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.primary)
                .lineLimit(2)
        }
    }

    private func debugActionButton(
        title: String,
        systemImage: String,
        disabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
        }
        .buttonStyle(.bordered)
        .disabled(disabled)
    }

    private func disclosureHeader(
        title: String,
        systemImage: String,
        isExpanded: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Label(title, systemImage: systemImage)
                    .font(.headline)
                Spacer()
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func debugTextBox(_ text: String, height: CGFloat) -> some View {
        ScrollView {
            Text(text)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.primary.opacity(0.86))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
        }
        .frame(height: height)
        .background(.black.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))
    }

    private func format(milliseconds: Int64) -> String {
        guard milliseconds > 0 else { return "00:00" }
        let totalSeconds = max(milliseconds, 0) / 1_000
        return String(format: "%02lld:%02lld", totalSeconds / 60, totalSeconds % 60)
    }
}

private struct DebugCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(.primary.opacity(0.06), lineWidth: 1)
            }
    }
}
