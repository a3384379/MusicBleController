import SwiftUI

struct ContentView: View {
    @StateObject private var bleManager = BLETestManager()
    @State private var showVolumeDetails = false
    @State private var showFullLyrics = false
    @State private var showDebugPage = false

    var body: some View {
        NavigationStack {
            ZStack {
                PlayerBackgroundView(image: bleManager.albumArtImage)
                    .ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(spacing: 13) {
                        connectionSection
                        nowPlayingSection
                        lyricCard
                        progressSection
                        playbackControls
                        volumeSection
                    }
                    .frame(maxWidth: 390)
                    .padding(.horizontal, 24)
                    .padding(.top, 18)
                    .padding(.bottom, 32)
                    .frame(maxWidth: .infinity)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showDebugPage) {
                DebugToolsView(bleManager: bleManager)
            }
            .fullScreenCover(isPresented: $showFullLyrics) {
                FullLyricsView(
                    title: nowPlayingInfo.title,
                    artist: nowPlayingInfo.artist,
                    albumArtImage: bleManager.albumArtImage,
                    lyrics: currentTrackFullLyrics,
                    currentIndex: currentFullLyricIndex,
                    positionMs: karaokePositionMs,
                    isPlaying: bleManager.isPlaying,
                    isConnected: isConnected,
                    onDismiss: { showFullLyrics = false },
                    onPrevious: bleManager.sendPrevious,
                    onPlayPause: bleManager.sendPlayPause,
                    onNext: bleManager.sendNext,
                    onSeekToLine: bleManager.seekToLyricLine
                )
            }
            .onChange(of: displayedPositionMs) { _, newValue in
                bleManager.logKaraokeOffset(rawPositionMs: newValue)
            }
        }
    }

    private var connectionSection: some View {
        HStack(spacing: 12) {
            HStack(spacing: 8) {
                Circle()
                    .fill(connectionColor)
                    .frame(width: 9, height: 9)
                    .shadow(color: connectionColor.opacity(0.78), radius: 8)

                VStack(alignment: .leading, spacing: 2) {
                    Text(bleManager.connectionStatus)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(bleManager.mode)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.66))
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(.white.opacity(0.10), in: Capsule())
            .overlay {
                Capsule().stroke(.white.opacity(0.08), lineWidth: 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer()

            Menu {
                Button {
                    bleManager.scanSonyFromMenu()
                } label: {
                    Label("扫描 / 重连", systemImage: "antenna.radiowaves.left.and.right")
                }

                Button {
                    showDebugPage = true
                } label: {
                    Label("调试工具", systemImage: "slider.horizontal.3")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 19, weight: .bold))
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.96))
            .background(.white.opacity(0.09), in: Circle())
            .overlay {
                Circle().stroke(.white.opacity(0.09), lineWidth: 1)
            }
            .accessibilityLabel("更多操作")
        }
        .foregroundStyle(.white)
        .animation(.easeInOut(duration: 0.2), value: bleManager.connectionStatus)
    }

    private var nowPlayingSection: some View {
        VStack(spacing: 9) {
            albumArtView
                .id(albumArtIdentity)
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
                .animation(.easeInOut(duration: 0.28), value: albumArtIdentity)

            VStack(spacing: 4) {
                Text(nowPlayingInfo.title)
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.58)

                Text(nowPlayingInfo.artist)
                    .font(.system(size: 18, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .padding(.top, 4)

                Text(nowPlayingInfo.album)
                    .font(.system(size: 15, weight: .regular, design: .rounded))
                    .foregroundStyle(.white.opacity(0.46))
                    .lineLimit(1)
            }

            HStack(spacing: 8) {
                Image(systemName: bleManager.isPlaying ? "music.note" : "pause.fill")
                Text(bleManager.isPlaying ? "播放中" : "已暂停")
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(
                bleManager.isPlaying
                    ? Color.green.opacity(0.95)
                    : Color.white.opacity(0.62)
            )
            .padding(.horizontal, 11)
            .padding(.vertical, 6)
            .background(.white.opacity(0.095), in: Capsule())
            .overlay {
                Capsule().stroke(.white.opacity(0.08), lineWidth: 1)
            }
            .animation(.spring(response: 0.28, dampingFraction: 0.78), value: bleManager.isPlaying)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
    }

    private var albumArtView: some View {
        ZStack {
            if let image = bleManager.albumArtImage {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .antialiased(true)
                    .scaledToFill()
            } else {
                DefaultAlbumArtView()
            }
        }
        .frame(width: albumArtSize, height: albumArtSize)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(.white.opacity(0.14), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.28), radius: 22, y: 12)
        .accessibilityLabel("当前歌曲封面")
    }

    private var lyricCard: some View {
        Button {
            if bleManager.fullLyrics.isEmpty {
                bleManager.sendGetFullLyrics(force: true)
            }
            showFullLyrics = true
        } label: {
            VStack(spacing: 8) {
                Text("当前歌词")
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.46))
                    .textCase(.uppercase)
                    .tracking(1.2)

                if currentTrackFullLyrics.isEmpty {
                    Text(currentLyricText)
                        .font(.system(size: 28, weight: .semibold, design: .rounded))
                        .foregroundStyle(
                            currentLyricText == "暂无歌词"
                                ? Color.white.opacity(0.58)
                                : Color.white.opacity(0.93)
                        )
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .minimumScaleFactor(0.68)
                        .frame(maxWidth: .infinity, minHeight: 62)
                } else {
                    VStack(spacing: 5) {
                        Text(lyricPreviewLine(offset: -1))
                            .font(.system(size: 17, weight: .medium, design: .rounded))
                            .foregroundStyle(.white.opacity(0.44))
                            .lineLimit(1)
                        KaraokeLyricText(
                            text: lyricPreviewLine(offset: 0),
                            progress: currentLyricProgress,
                            words: lyricPreviewLineModel(offset: 0)?.words ?? [],
                            positionMs: karaokePositionMs,
                            highlightColor: Color.green.opacity(0.98),
                            normalColor: Color.white.opacity(0.48),
                            font: .system(size: 24, weight: .bold, design: .rounded),
                            lineLimit: 2,
                            alignment: .center
                        )
                        .minimumScaleFactor(0.72)
                        Text(lyricPreviewLine(offset: 1))
                            .font(.system(size: 17, weight: .medium, design: .rounded))
                            .foregroundStyle(.white.opacity(0.44))
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, minHeight: 82)
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 4)
            .id(lyricPreviewIdentity)
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.22), value: lyricPreviewIdentity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("打开完整歌词")
    }

    private var progressSection: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isSeeking
                                ? bleManager.seekPositionMs
                                : bleManager.displayPositionMs
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
            .tint(.white.opacity(0.94))
            .disabled(!isConnected || bleManager.durationMs <= 0)

            HStack {
                Text(format(milliseconds: displayedPositionMs))
                Spacer()
                Text(format(milliseconds: bleManager.durationMs))
            }
            .font(.caption2.monospacedDigit().weight(.medium))
            .foregroundStyle(.white.opacity(0.58))
        }
        .padding(.horizontal, 4)
        .animation(
            bleManager.isSeeking ? nil : .linear(duration: 0.18),
            value: bleManager.positionMs
        )
    }

    private var playbackControls: some View {
        HStack(spacing: 26) {
            playerControlButton(
                title: "上一首",
                systemImage: "backward.fill",
                size: 52,
                fontSize: 22,
                action: bleManager.sendPrevious
            )

            Button(action: bleManager.sendPlayPause) {
                Image(systemName: bleManager.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(width: 70, height: 70)
                    .background(.white, in: Circle())
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 7)
                    .scaleEffect(bleManager.isPlaying ? 1.0 : 0.96)
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.92))
            .accessibilityLabel("播放 / 暂停")

            playerControlButton(
                title: "下一首",
                systemImage: "forward.fill",
                size: 52,
                fontSize: 22,
                action: bleManager.sendNext
            )
        }
        .frame(maxWidth: .infinity)
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.46)
        .animation(.spring(response: 0.28, dampingFraction: 0.72), value: bleManager.isPlaying)
    }

    private var volumeSection: some View {
        VStack(spacing: 12) {
            volumeHeader

            if showVolumeDetails {
                VStack(spacing: 12) {
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
                    .tint(.white)
                    .disabled(bleManager.volumeMax <= 0)

                    HStack(spacing: 12) {
                        compactButton(
                            title: "音量减",
                            systemImage: "speaker.minus.fill",
                            action: bleManager.sendVolumeDown
                        )
                        compactButton(
                            title: "音量加",
                            systemImage: "speaker.plus.fill",
                            action: bleManager.sendVolumeUp
                        )
                    }
                }
                .padding(13)
                .foregroundStyle(.white)
                .background(.white.opacity(0.062), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(.white.opacity(0.08), lineWidth: 1)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.52)
    }

    private var volumeHeader: some View {
        Button {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.82)) {
                showVolumeDetails.toggle()
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: volumeIcon)
                    .font(.headline)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 3) {
                    Text("音量 \(displayedVolume) / \(bleManager.volumeMax)")
                        .font(.subheadline.weight(.semibold))
                    ProgressView(
                        value: Double(displayedVolume),
                        total: Double(max(bleManager.volumeMax, 1))
                    )
                    .tint(.white.opacity(0.86))
                }
                Spacer()
                Image(systemName: showVolumeDetails ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white.opacity(0.56))
            }
            .padding(13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .background(.white.opacity(0.070), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.white.opacity(0.08), lineWidth: 1)
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

    private var isConnected: Bool {
        bleManager.connectionStatus == "已连接"
    }

    private var displayedPositionMs: Int64 {
        bleManager.isSeeking ? bleManager.seekPositionMs : bleManager.displayPositionMs
    }

    private var karaokePositionMs: Int64 {
        bleManager.karaokePositionMs(rawPositionMs: displayedPositionMs)
    }

    private var displayedVolume: Int {
        bleManager.isVolumeSeeking ? bleManager.volumeSeekValue : bleManager.volumeCurrent
    }

    private var nowPlayingInfo: NowPlayingInfoProvider {
        NowPlayingInfoProvider(
            title: displayText(bleManager.title, fallback: "Sony Music"),
            artist: displayText(bleManager.artist, fallback: "未知歌手"),
            album: displayText(bleManager.album, fallback: "未知专辑"),
            albumArt: bleManager.albumArtImage,
            positionMs: displayedPositionMs,
            durationMs: bleManager.durationMs,
            isPlaying: bleManager.isPlaying
        )
    }

    private var currentLyricText: String {
        let trimmed = bleManager.lyric.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "暂无歌词" : trimmed
    }

    private var currentFullLyricIndex: Int {
        LyricTimelineHelper.currentIndex(
            lines: currentTrackFullLyrics,
            positionMs: karaokePositionMs
        ) ?? -1
    }

    private var currentLyricProgress: Double {
        LyricTimelineHelper.lineProgress(
            lines: currentTrackFullLyrics,
            index: currentFullLyricIndex,
            positionMs: karaokePositionMs
        )
    }

    private var lyricPreviewIdentity: String {
        if currentTrackFullLyrics.isEmpty {
            return currentLyricText
        }
        return "\(bleManager.fullLyricsTrackId)-\(currentFullLyricIndex)"
    }

    private func lyricPreviewLine(offset: Int) -> String {
        guard let line = lyricPreviewLineModel(offset: offset) else {
            return offset == 0 ? currentLyricText : " "
        }
        let text = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? " " : text
    }

    private func lyricPreviewLineModel(offset: Int) -> LyricLine? {
        let index = currentFullLyricIndex + offset
        guard currentTrackFullLyrics.indices.contains(index) else {
            return nil
        }
        return currentTrackFullLyrics[index]
    }

    private var currentTrackFullLyrics: [LyricLine] {
        bleManager.isFullLyricsCurrent ? bleManager.fullLyrics : []
    }

    private var albumArtIdentity: String {
        if bleManager.albumArtImage == nil {
            return "default-\(bleManager.title)-\(bleManager.artist)"
        }
        return "art-\(bleManager.title)-\(bleManager.artist)-\(bleManager.album)"
    }

    private var albumArtSize: CGFloat {
        260
    }

    private var volumeIcon: String {
        if displayedVolume <= 0 {
            return "speaker.slash.fill"
        }
        if displayedVolume < max(bleManager.volumeMax / 2, 1) {
            return "speaker.wave.1.fill"
        }
        return "speaker.wave.2.fill"
    }

    private func playerControlButton(
        title: String,
        systemImage: String,
        size: CGFloat,
        fontSize: CGFloat,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(.white.opacity(0.10), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.10), lineWidth: 1)
                }
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.92))
        .accessibilityLabel(title)
    }

    private func compactButton(
        title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.96))
        .background(.white.opacity(0.10), in: Capsule())
        .overlay {
            Capsule().stroke(.white.opacity(0.09), lineWidth: 1)
        }
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

private struct PlayerBackgroundView: View {
    let image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .saturation(1.15)
                        .brightness(-0.08)
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                        .blur(radius: 34)
                        .overlay(Color.black.opacity(0.28))
                } else {
                    LinearGradient(
                        colors: [
                            Color(red: 0.04, green: 0.09, blue: 0.16),
                            Color(red: 0.12, green: 0.18, blue: 0.28),
                            Color(red: 0.02, green: 0.03, blue: 0.06)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    .frame(width: proxy.size.width, height: proxy.size.height)
                }

                LinearGradient(
                    colors: [
                        Color.black.opacity(0.28),
                        Color.black.opacity(0.12),
                        Color.black.opacity(0.80)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
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
    let highlightColor: Color
    let normalColor: Color
    let font: Font
    var lineLimit: Int? = nil
    var alignment: TextAlignment = .leading

    private var highlightCount: Int {
        let characters = Array(text)
        let count = characters.count
        guard count > 0 else { return 0 }
        if let positionMs, !words.isEmpty {
            let wordCharacterCount = words.reduce(0) { partial, word in
                guard positionMs >= word.startMs else { return partial }
                return partial + Array(word.text).count
            }
            return min(max(wordCharacterCount, 0), count)
        }
        let boundedProgress = min(max(progress, 0), 1)
        let rawCount = Int((Double(count) * boundedProgress).rounded(.down))
        return min(max(rawCount, 0), count)
    }

    private var splitText: (String, String) {
        guard highlightCount > 0 else { return ("", text) }
        let characters = Array(text)
        guard highlightCount < characters.count else { return (text, "") }
        return (
            String(characters.prefix(highlightCount)),
            String(characters.dropFirst(highlightCount))
        )
    }

    var body: some View {
        let parts = splitText
        (Text(parts.0).foregroundColor(highlightColor) +
            Text(parts.1).foregroundColor(normalColor))
            .font(font)
            .multilineTextAlignment(alignment)
            .lineLimit(lineLimit)
            .animation(.linear(duration: 0.25), value: highlightCount)
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
