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
                    autoReconnectSection
                    artworkEnhancementSection
                    liveActivityControlSection
                    karaokeOffsetSection
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

    private var autoReconnectSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("Auto Reconnect", systemImage: "arrow.triangle.2.circlepath")
                    .font(.headline)

                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                    debugRow("Enabled", bleManager.autoReconnectEnabled ? "true" : "false")
                    debugRow("State", bleManager.autoReconnectState)
                    debugRow("Attempt", "\(bleManager.autoReconnectAttempt)")
                    debugRow("Next retry", reconnectRetryText)
                    debugRow("Last peripheral", bleManager.autoReconnectLastPeripheralId)
                    debugRow("Last error", bleManager.autoReconnectLastDisconnectError)
                    debugRow("Last cost", "\(bleManager.autoReconnectLastCostMs)ms")
                    debugRow("Retrieve cost", "\(bleManager.autoReconnectLastRetrieveCostMs)ms")
                    debugRow("Scan cost", "\(bleManager.autoReconnectLastScanCostMs)ms")
                    debugRow("Connect cost", "\(bleManager.autoReconnectLastConnectCostMs)ms")
                    debugRow("Subscribe cost", "\(bleManager.autoReconnectLastSubscribeCostMs)ms")
                    debugRow("Manual count", "\(bleManager.manualReconnectCount)")
                    debugRow("Auto count", "\(bleManager.autoReconnectCount)")
                }

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 10),
                        GridItem(.flexible(), spacing: 10)
                    ],
                    spacing: 10
                ) {
                    debugActionButton(
                        title: bleManager.autoReconnectEnabled ? "Disable Auto" : "Enable Auto",
                        systemImage: bleManager.autoReconnectEnabled ? "pause.circle" : "play.circle",
                        disabled: false,
                        action: {
                            bleManager.setAutoReconnectEnabled(!bleManager.autoReconnectEnabled)
                        }
                    )
                    debugActionButton(
                        title: "Force Reconnect",
                        systemImage: "bolt.horizontal.circle",
                        disabled: false,
                        action: bleManager.forceReconnect
                    )
                    debugActionButton(
                        title: "Forget Last Sony",
                        systemImage: "trash.circle",
                        disabled: false,
                        action: bleManager.forgetLastSonyDevice
                    )
                }
            }
        }
    }

    private var artworkEnhancementSection: some View {
        let status = bleManager.artworkEnhancementStatus
        return DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("Artwork Enhancement", systemImage: "photo.on.rectangle.angled")
                    .font(.headline)

                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                    debugRow("Enabled", status.enabled ? "true" : "false")
                    debugRow("Current source", status.currentSource)
                    debugRow("Target", status.target)
                    debugRow("Display quality", status.displayQuality.label)
                    debugRow("Cache hit", status.cacheHit ? "true" : "false")
                    debugRow("Last cost", "\(status.lastProcessingCostMs)ms")
                    debugRow("Edge gain", String(format: "%.1f%%", status.lastEdgeGainPercent))
                    debugRow("Enhanced files", "\(status.enhancedCacheFiles)")
                    debugRow("Enhanced size", formatBytes(status.enhancedCacheBytes))
                    debugRow("Status", status.lastMessage)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Target Size")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), spacing: 8),
                            GridItem(.flexible(), spacing: 8),
                            GridItem(.flexible(), spacing: 8)
                        ],
                        spacing: 8
                    ) {
                        ForEach([560, 680, 780], id: \.self) { value in
                            Button {
                                bleManager.setArtworkEnhancementTargetPixelSize(value)
                            } label: {
                                Text("\(value)")
                                    .font(.caption.monospacedDigit().weight(.semibold))
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(status.targetPixelSize == value ? .green : .gray.opacity(0.35))
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Sharpness")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), spacing: 8),
                            GridItem(.flexible(), spacing: 8),
                            GridItem(.flexible(), spacing: 8)
                        ],
                        spacing: 8
                    ) {
                        ForEach([0.20, 0.30, 0.40], id: \.self) { value in
                            Button {
                                bleManager.setArtworkEnhancementSharpness(value)
                            } label: {
                                Text(String(format: "%.2f", value))
                                    .font(.caption.monospacedDigit().weight(.semibold))
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(abs(status.sharpness - value) < 0.001 ? .green : .gray.opacity(0.35))
                        }
                    }
                }

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 10),
                        GridItem(.flexible(), spacing: 10)
                    ],
                    spacing: 10
                ) {
                    debugActionButton(
                        title: status.enabled ? "关闭增强" : "开启增强",
                        systemImage: status.enabled ? "eye.slash" : "eye",
                        disabled: false,
                        action: {
                            bleManager.setArtworkEnhancementEnabled(!status.enabled)
                        }
                    )
                    debugActionButton(
                        title: "清除增强缓存",
                        systemImage: "trash",
                        disabled: false,
                        action: bleManager.clearEnhancedArtworkCache
                    )
                    debugActionButton(
                        title: "重建当前封面",
                        systemImage: "wand.and.stars",
                        disabled: false,
                        action: bleManager.rebuildCurrentEnhancedArtwork
                    )
                    debugActionButton(
                        title: "A/B 原图/增强",
                        systemImage: "rectangle.split.2x1",
                        disabled: false,
                        action: bleManager.toggleArtworkEnhancementABComparison
                    )
                }
            }
        }
    }

    private var liveActivityControlSection: some View {
        let status = bleManager.liveActivityControlStatus
        return DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("Live Activity Control", systemImage: "playpause.circle")
                    .font(.headline)

                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                    debugRow("Bridge registered", status.bridgeRegistered ? "true" : "false")
                    debugRow("BLE ready", status.bleReady ? "true" : "false")
                    debugRow("Last intent seq", status.lastIntentSeq == 0 ? "-" : "\(status.lastIntentSeq)")
                    debugRow("Last command", status.lastCommand?.rawValue ?? "-")
                    debugRow("Last result", status.lastResult.rawValue)
                    debugRow("Last cost", "\(status.lastCostMs)ms")
                    debugRow("In flight", status.inFlight ? "true" : "false")
                    debugRow("Dropped count", "\(status.droppedCount)")
                    debugRow("Debounced count", "\(status.debouncedCount)")
                }
            }
        }
    }

    private var karaokeOffsetSection: some View {
        DebugCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label("Karaoke Offset", systemImage: "textformat")
                        .font(.headline)
                    Spacer()
                    Text(offsetLabel(bleManager.karaokeOffsetMs))
                        .font(.subheadline.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.green)
                }

                Text("只影响逐字高亮提前/延后，不影响进度条和歌词点击跳转。")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 8),
                        GridItem(.flexible(), spacing: 8),
                        GridItem(.flexible(), spacing: 8)
                    ],
                    spacing: 8
                ) {
                    ForEach(karaokeOffsetOptions, id: \.self) { value in
                        Button {
                            bleManager.setKaraokeOffsetMs(value)
                        } label: {
                            Text(offsetLabel(value))
                                .font(.caption.monospacedDigit().weight(.semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(value == bleManager.karaokeOffsetMs ? .green : .gray.opacity(0.35))
                    }
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
                    HStack(spacing: 10) {
                        Button(action: bleManager.copyIOSLogs) {
                            Label("Copy iOS Logs", systemImage: "doc.on.doc")
                        }
                        .buttonStyle(.bordered)

                        Button(role: .destructive, action: bleManager.clearIOSLogs) {
                            Label("Clear", systemImage: "trash")
                        }
                        .buttonStyle(.bordered)

                        if AppLogStore.shared.currentLogFileExists() {
                            ShareLink(item: AppLogStore.shared.currentLogURL) {
                                Label("Share", systemImage: "square.and.arrow.up")
                            }
                            .buttonStyle(.bordered)
                        }
                    }

                    Text(AppLogStore.shared.currentLogURL.path)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .textSelection(.enabled)

                    if !bleManager.localLogActionStatus.isEmpty {
                        Text(bleManager.localLogActionStatus)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }

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

    private var karaokeOffsetOptions: [Int64] {
        [-300, -100, 0, 300, 600, 900]
    }

    private var reconnectRetryText: String {
        guard let retryAt = bleManager.autoReconnectNextRetryAt else { return "-" }
        let remaining = max(0, retryAt.timeIntervalSinceNow)
        return String(format: "%.1fs", remaining)
    }

    private func offsetLabel(_ value: Int64) -> String {
        if value > 0 {
            return "+\(value)ms"
        }
        return "\(value)ms"
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

    private func formatBytes(_ bytes: Int64) -> String {
        if bytes < 1_024 {
            return "\(bytes) B"
        }
        if bytes < 1_024 * 1_024 {
            return String(format: "%.1f KB", Double(bytes) / 1_024.0)
        }
        return String(format: "%.1f MB", Double(bytes) / 1_024.0 / 1_024.0)
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
