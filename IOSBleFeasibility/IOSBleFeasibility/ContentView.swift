import SwiftUI

struct ContentView: View {
    @StateObject private var bleManager = BLETestManager()
    @State private var showLogs = false
    @State private var showSonyLogs = false
    @State private var showDebugTools = false
    @State private var showMediaFieldDump = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    connectionSection
                    Divider()
                    trackSection
                    progressSection
                    playbackControls
                    Divider()
                    volumeSection
                    refreshControls
                    debugToolsSection
                    logSection
                }
                .padding()
            }
            .navigationTitle("Sony 播放控制")
            .background(Color(uiColor: .systemBackground))
        }
    }

    private var connectionSection: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 7) {
                    Circle()
                        .fill(connectionColor)
                        .frame(width: 9, height: 9)
                    Text(bleManager.connectionStatus)
                        .font(.headline)
                }
                Text(bleManager.mode)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button {
                bleManager.scanSony()
            } label: {
                Label("扫描 / 重连", systemImage: "antenna.radiowaves.left.and.right")
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var trackSection: some View {
        VStack(spacing: 7) {
            Group {
                if let image = bleManager.albumArtImage {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.high)
                        .antialiased(true)
                        .scaledToFill()
                } else {
                    ZStack {
                        Color(uiColor: .secondarySystemBackground)
                        Image(systemName: "music.note")
                            .font(.system(size: 48))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(width: 180, height: 180)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .accessibilityLabel("当前歌曲封面")
            .padding(.bottom, 8)

            Text(displayText(bleManager.title, fallback: "歌曲名称"))
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .lineLimit(2)

            Text(displayText(bleManager.artist, fallback: "歌手"))
                .font(.body)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text(displayText(bleManager.album, fallback: "专辑"))
                .font(.subheadline)
                .foregroundStyle(.tertiary)
                .lineLimit(1)

            Label(
                bleManager.isPlaying ? "播放中" : "已暂停",
                systemImage: bleManager.isPlaying ? "play.fill" : "pause.fill"
            )
            .font(.subheadline.weight(.medium))
            .foregroundStyle(bleManager.isPlaying ? Color.green : Color.secondary)
            .padding(.top, 3)

            VStack(spacing: 4) {
                Text("当前歌词")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(
                    bleManager.lyric.trimmingCharacters(
                        in: .whitespacesAndNewlines
                    ).isEmpty ? "暂无歌词" : bleManager.lyric
                )
                .font(.body)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity)
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
    }

    private var progressSection: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isSeeking
                                ? bleManager.seekPositionMs
                                : bleManager.positionMs
                        )
                    },
                    set: { value in
                        bleManager.updateSeekPosition(value)
                    }
                ),
                in: 0...Double(max(bleManager.durationMs, 1)),
                onEditingChanged: { editing in
                    if editing {
                        bleManager.beginSeeking()
                    } else {
                        bleManager.finishSeeking()
                    }
                }
            )
            .disabled(
                bleManager.connectionStatus != "已连接" ||
                    bleManager.durationMs <= 0
            )

            Text(
                "\(format(milliseconds: displayedPositionMs)) / " +
                    "\(format(milliseconds: bleManager.durationMs))"
            )
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }

    private var playbackControls: some View {
        HStack(spacing: 28) {
            controlButton(
                title: "上一首",
                systemImage: "backward.fill",
                action: bleManager.sendPrevious
            )

            Button(action: bleManager.sendPlayPause) {
                Image(systemName: bleManager.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .frame(width: 58, height: 44)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityLabel("播放 / 暂停")

            controlButton(
                title: "下一首",
                systemImage: "forward.fill",
                action: bleManager.sendNext
            )
        }
        .frame(maxWidth: .infinity)
        .disabled(bleManager.connectionStatus != "已连接")
    }

    private var volumeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("音量", systemImage: "speaker.wave.2.fill")
                    .font(.headline)
                Spacer()
                Text("\(displayedVolume) / \(bleManager.volumeMax)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isVolumeSeeking
                                ? bleManager.volumeSeekValue
                                : bleManager.volumeCurrent
                        )
                    },
                    set: { value in
                        bleManager.updateVolumeSeekValue(value)
                    }
                ),
                in: 0...Double(max(bleManager.volumeMax, 1)),
                step: 1,
                onEditingChanged: { editing in
                    if editing {
                        bleManager.beginVolumeSeeking()
                    } else {
                        bleManager.finishVolumeSeeking()
                    }
                }
            )
            .disabled(bleManager.volumeMax <= 0)

            HStack(spacing: 12) {
                Button(action: bleManager.sendVolumeDown) {
                    Label("音量减", systemImage: "speaker.minus.fill")
                        .frame(maxWidth: .infinity)
                }
                Button(action: bleManager.sendVolumeUp) {
                    Label("音量加", systemImage: "speaker.plus.fill")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
        }
        .disabled(bleManager.connectionStatus != "已连接")
    }

    private var refreshControls: some View {
        HStack(spacing: 12) {
            Button(action: bleManager.sendGetPlaybackState) {
                Label("刷新播放状态", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
            }
            Button(action: bleManager.sendGetVolume) {
                Label("刷新音量", systemImage: "speaker.wave.2")
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.bordered)
        .disabled(bleManager.connectionStatus != "已连接")
    }

    private var logSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                showLogs.toggle()
            } label: {
                Label(
                    showLogs ? "隐藏日志" : "显示日志",
                    systemImage: showLogs ? "chevron.up" : "chevron.down"
                )
            }
            .buttonStyle(.bordered)

            if showLogs {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            ForEach(Array(bleManager.logs.enumerated()), id: \.offset) { index, line in
                                Text(line)
                                    .font(.system(size: 11, design: .monospaced))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .id(index)
                            }
                        }
                        .padding(10)
                    }
                    .frame(height: 150)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .onChange(of: bleManager.logs.count) { _, count in
                        guard count > 0 else { return }
                        proxy.scrollTo(count - 1, anchor: .bottom)
                    }
                }
            }
        }
    }

    private var debugToolsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                showDebugTools.toggle()
            } label: {
                HStack {
                    Label("Debug Tools", systemImage: "wrench.and.screwdriver")
                    Spacer()
                    Image(systemName: showDebugTools ? "chevron.up" : "chevron.down")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            if showDebugTools {
                remoteLogSection
                Divider()
                mediaFieldDumpSection
            }
        }
    }

    private var mediaFieldDumpSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Media Field Dump")
                    .font(.headline)
                Spacer()
                Button(action: bleManager.sendDumpMediaFields) {
                    Label("Dump Media Fields", systemImage: "list.bullet.clipboard")
                }
                .buttonStyle(.bordered)
                .disabled(
                    bleManager.connectionStatus != "已连接" ||
                        bleManager.isMediaFieldDumpReceiving
                )
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

            Button {
                showMediaFieldDump.toggle()
            } label: {
                Label(
                    showMediaFieldDump
                        ? "Hide Media Field Dump"
                        : "Show Media Field Dump",
                    systemImage: showMediaFieldDump
                        ? "chevron.up"
                        : "chevron.down"
                )
            }
            .buttonStyle(.bordered)

            if showMediaFieldDump {
                if bleManager.mediaFieldDumpText.isEmpty {
                    Text("尚未获取")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ScrollView {
                        Text(bleManager.mediaFieldDumpText)
                            .font(.system(size: 11, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                    }
                    .frame(height: 260)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 8))

                    HStack {
                        Button(action: bleManager.copyMediaFieldDump) {
                            Label("Copy Media Dump", systemImage: "doc.on.doc")
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

    private var remoteLogSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Sony 日志")
                    .font(.headline)
                Spacer()
                Button(action: bleManager.sendGetSonyLogs) {
                    Label("获取 Sony 日志", systemImage: "arrow.down.doc")
                }
                .buttonStyle(.bordered)
                .disabled(bleManager.connectionStatus != "已连接")
            }

            if bleManager.isRemoteLogTransferInProgress {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Sony 日志传输中...")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Button {
                showSonyLogs.toggle()
            } label: {
                Label(
                    showSonyLogs ? "隐藏 Sony 日志" : "显示 Sony 日志",
                    systemImage: showSonyLogs ? "chevron.up" : "chevron.down"
                )
            }
            .buttonStyle(.bordered)

            if showSonyLogs && !bleManager.remoteLogText.isEmpty {
                ScrollView {
                    Text(bleManager.remoteLogText)
                        .font(.system(size: 11, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                }
                .frame(height: 220)
                .background(Color(uiColor: .secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 8))

                HStack {
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
            } else if showSonyLogs {
                Text("尚未获取")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var connectionColor: Color {
        switch bleManager.connectionStatus {
        case "已连接":
            return .green
        case "扫描中", "连接中":
            return .orange
        default:
            return .secondary
        }
    }

    private var displayedPositionMs: Int64 {
        bleManager.isSeeking ? bleManager.seekPositionMs : bleManager.positionMs
    }

    private var displayedVolume: Int {
        bleManager.isVolumeSeeking ? bleManager.volumeSeekValue : bleManager.volumeCurrent
    }

    private func controlButton(
        title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3)
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(title)
    }

    private func displayText(_ value: String, fallback: String) -> String {
        value == "-" || value.isEmpty ? fallback : value
    }

    private func format(milliseconds: Int64) -> String {
        guard bleManager.durationMs > 0 else { return "00:00" }
        let totalSeconds = max(milliseconds, 0) / 1_000
        return String(format: "%02lld:%02lld", totalSeconds / 60, totalSeconds % 60)
    }
}

#Preview {
    ContentView()
}
