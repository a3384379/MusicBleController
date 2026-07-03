import ActivityKit
import AppIntents
import SwiftUI
import UIKit
import WidgetKit

struct SonyMusicLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SonyMusicActivityAttributes.self) { context in
            LockScreenLiveActivityView(state: context.state)
                .widgetURL(URL(string: "sonymusic://nowplaying"))
                .activityBackgroundTint(Color.black.opacity(0.82))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let visualState = DynamicIslandPlaybackVisualState.resolve(state: context.state)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    LiveActivityArtworkView(
                        artworkKey: context.state.artworkKey,
                        artworkRevision: context.state.artworkRevision,
                        size: 44
                    )
                }
                DynamicIslandExpandedRegion(.trailing) {
                    DynamicIslandPrimaryActionButton(
                        visualState: visualState,
                        size: 38
                    )
                }
                DynamicIslandExpandedRegion(.center) {
                    TrackSummaryView(state: context.state)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ExpandedBottomView(state: context.state)
                }
            } compactLeading: {
                CompactArtworkProgressView(state: context.state, size: 26)
            } compactTrailing: {
                CompactPlaybackGlyphView(
                    visualState: visualState,
                    size: 18
                )
            } minimal: {
                MinimalPlaybackGlyphView(visualState: visualState)
            }
            .keylineTint(visualState.accentColor)
            .widgetURL(URL(string: "sonymusic://nowplaying"))
        }
    }
}

private enum DynamicIslandPlaybackVisualState: String {
    case playing
    case paused
    case loading
    case reconnecting
    case disconnected
    case unavailable
}

private enum DynamicIslandLyricDisplayState {
    case ready(line: String)
    case loading
    case unavailable
    case disconnected
}

private struct StateTransitionEngine {
    static func resolve(state: SonyMusicActivityAttributes.ContentState) -> DynamicIslandPlaybackVisualState {
        let hintedState = IslandState.resolved(from: state.islandState)
        let connection = state.connectionState
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        if connection == "disconnected" {
            return .disconnected
        }
        if hintedState == .disconnected {
            return .disconnected
        }
        if connection == "reconnecting" || connection == "stale" {
            return .reconnecting
        }
        if connection == "connecting" ||
            connection == "syncing" ||
            connection == "loading" ||
            connection == "subscribing" ||
            connection == "searching" {
            return .loading
        }
        if hintedState == .buffering || hintedState == .connecting {
            return .loading
        }

        let title = state.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let noUsableTrack = title.isEmpty ||
            title == "-" ||
            (title == "Sony Music" && state.durationMs <= 0)
        if noUsableTrack {
            return .unavailable
        }

        if hintedState == .playing {
            return .playing
        }
        if hintedState == .paused {
            return .paused
        }
        return state.isPlaying ? .playing : .paused
    }

    static func lyricState(
        for state: SonyMusicActivityAttributes.ContentState,
        visualState: DynamicIslandPlaybackVisualState
    ) -> DynamicIslandLyricDisplayState {
        if visualState == .disconnected {
            return .disconnected
        }

        let line = state.lyric.trimmingCharacters(in: .whitespacesAndNewlines)
        if !line.isEmpty && line != "暂无歌词" && line != "-" {
            return .ready(line: line)
        }

        switch visualState {
        case .loading, .reconnecting:
            return .loading
        case .unavailable:
            return .unavailable
        case .playing, .paused:
            return .loading
        case .disconnected:
            return .disconnected
        }
    }
}

private extension DynamicIslandPlaybackVisualState {
    static func resolve(state: SonyMusicActivityAttributes.ContentState) -> DynamicIslandPlaybackVisualState {
        StateTransitionEngine.resolve(state: state)
    }

    var accentColor: Color {
        switch self {
        case .playing:
            return Color(red: 0.29, green: 0.95, blue: 0.42)
        case .paused:
            return Color(red: 0.25, green: 0.57, blue: 1.0)
        case .loading:
            return Color(red: 0.55, green: 0.60, blue: 0.68)
        case .reconnecting:
            return Color(red: 0.55, green: 0.60, blue: 0.68)
        case .disconnected:
            return Color(red: 0.72, green: 0.36, blue: 0.34)
        case .unavailable:
            return Color(red: 0.70, green: 0.43, blue: 1.0)
        }
    }

    var symbolName: String {
        switch self {
        case .playing:
            return "pause.fill"
        case .paused:
            return "play.fill"
        case .loading:
            return "circle.dotted"
        case .reconnecting:
            return "antenna.radiowaves.left.and.right"
        case .disconnected:
            return "arrow.clockwise"
        case .unavailable:
            return "play.fill"
        }
    }

    var statusText: String {
        switch self {
        case .playing:
            return "播放中"
        case .paused:
            return "已暂停"
        case .loading:
            return "同步中"
        case .reconnecting:
            return "重连中"
        case .disconnected:
            return "已断开"
        case .unavailable:
            return "不可用"
        }
    }

    var buttonAccessibilityLabel: String {
        switch self {
        case .playing:
            return "暂停"
        case .paused:
            return "播放"
        case .loading:
            return "同步中"
        case .reconnecting:
            return "重连中"
        case .disconnected:
            return "重试连接"
        case .unavailable:
            return "播放"
        }
    }

    var controlsEnabled: Bool {
        switch self {
        case .playing, .paused, .disconnected, .unavailable:
            return true
        case .loading, .reconnecting:
            return false
        }
    }

    var isActiveTransition: Bool {
        self == .loading || self == .reconnecting
    }
}

private extension DynamicIslandLyricDisplayState {
    var text: String {
        switch self {
        case let .ready(line):
            return line
        case .loading:
            return "歌词同步中"
        case .unavailable:
            return "暂无歌词"
        case .disconnected:
            return "等待连接"
        }
    }
}

private struct DynamicIslandPrimaryActionButton: View {
    let visualState: DynamicIslandPlaybackVisualState
    let size: CGFloat

    var body: some View {
        Group {
            if visualState == .disconnected {
                Button(intent: ReconnectLiveActivityIntent()) {
                    ExpandedPlaybackButtonView(
                        visualState: visualState,
                        size: size,
                        iconScale: 0.42
                    )
                }
                .buttonStyle(.plain)
            } else if visualState.controlsEnabled {
                Button(intent: PlayPauseLiveActivityIntent()) {
                    ExpandedPlaybackButtonView(
                        visualState: visualState,
                        size: size,
                        iconScale: 0.42
                    )
                }
                .buttonStyle(.plain)
            } else {
                ExpandedPlaybackButtonView(
                    visualState: visualState,
                    size: size,
                    iconScale: 0.42
                )
            }
        }
        .accessibilityLabel(Text(visualState.buttonAccessibilityLabel))
    }
}

private struct ExpandedPlaybackButtonView: View {
    let visualState: DynamicIslandPlaybackVisualState
    let size: CGFloat
    let iconScale: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(visualState.accentColor.opacity(backgroundOpacity))
            Circle()
                .strokeBorder(visualState.accentColor.opacity(strokeOpacity), lineWidth: 1.4)
            icon
        }
        .frame(width: size, height: size)
        .shadow(color: visualState.accentColor.opacity(shadowOpacity), radius: size * 0.16)
        .scaleEffect(visualState.isActiveTransition ? 0.94 : 1)
        .opacity(visualState == .disconnected ? 0.72 : 1)
        .animation(.easeInOut(duration: 0.28), value: visualState.rawValue)
    }

    @ViewBuilder
    private var icon: some View {
        if visualState == .loading || visualState == .reconnecting {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(visualState.accentColor)
                .scaleEffect(max(size / 42, 0.62))
        } else {
            Image(systemName: visualState.symbolName)
                .font(.system(size: size * iconScale, weight: .bold))
                .foregroundStyle(visualState.accentColor)
                .offset(x: visualState == .paused ? 1 : 0)
        }
    }

    private var backgroundOpacity: Double {
        switch visualState {
        case .playing:
            return 0.18
        case .paused:
            return 0.16
        case .loading, .reconnecting:
            return 0.12
        case .unavailable:
            return 0.14
        case .disconnected:
            return 0.10
        }
    }

    private var strokeOpacity: Double {
        visualState == .disconnected ? 0.30 : 0.70
    }

    private var shadowOpacity: Double {
        visualState == .playing || visualState == .paused ? 0.28 : 0.14
    }
}

private struct CompactArtworkProgressView: View {
    let state: SonyMusicActivityAttributes.ContentState
    let size: CGFloat

    private var visualState: DynamicIslandPlaybackVisualState {
        DynamicIslandPlaybackVisualState.resolve(state: state)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            LiveActivityArtworkView(
                artworkKey: state.artworkKey,
                artworkRevision: state.artworkRevision,
                size: size
            )

            CompactMiniProgressBar(
                visualState: visualState,
                progress: progressValue,
                width: max(size - 7, 12),
                height: 2.2
            )
            .padding(.bottom, 2)
        }
        .frame(width: size, height: size)
    }

    private var progressValue: Double {
        guard state.durationMs > 0 else { return 0 }
        return min(
            max(Double(state.positionAtAnchorMs) / Double(state.durationMs), 0),
            1
        )
    }
}

private struct CompactMiniProgressBar: View {
    let visualState: DynamicIslandPlaybackVisualState
    let progress: Double
    let width: CGFloat
    let height: CGFloat

    var body: some View {
        ZStack(alignment: .leading) {
            Capsule()
                .fill(trackColor)
            Capsule()
                .fill(fillColor)
                .frame(width: max(width * progress, height))
        }
        .frame(width: width, height: height)
        .opacity(isVisible ? 1 : 0.45)
    }

    private var isVisible: Bool {
        visualState != .disconnected && visualState != .unavailable
    }

    private var fillColor: Color {
        switch visualState {
        case .playing:
            return DynamicIslandPlaybackVisualState.playing.accentColor
        case .paused:
            return DynamicIslandPlaybackVisualState.paused.accentColor
        case .loading, .reconnecting:
            return Color.white.opacity(0.46)
        case .disconnected:
            return Color.white.opacity(0.22)
        case .unavailable:
            return DynamicIslandPlaybackVisualState.unavailable.accentColor.opacity(0.45)
        }
    }

    private var trackColor: Color {
        switch visualState {
        case .playing, .paused:
            return Color.black.opacity(0.42)
        case .loading, .reconnecting:
            return Color.white.opacity(0.18)
        case .disconnected, .unavailable:
            return Color.white.opacity(0.10)
        }
    }
}

private struct CompactPlaybackGlyphView: View {
    let visualState: DynamicIslandPlaybackVisualState
    let size: CGFloat

    var body: some View {
        Group {
            if visualState == .disconnected {
                Button(intent: ReconnectLiveActivityIntent()) {
                    PlaybackGlyphContent(
                        visualState: visualState,
                        size: size,
                        iconScale: 0.58,
                        xOffset: 1
                    )
                }
                .buttonStyle(.plain)
            } else if visualState.controlsEnabled {
                Button(intent: PlayPauseLiveActivityIntent()) {
                    PlaybackGlyphContent(
                        visualState: visualState,
                        size: size,
                        iconScale: 0.58,
                        xOffset: 1
                    )
                }
                .buttonStyle(.plain)
            } else {
                PlaybackGlyphContent(
                    visualState: visualState,
                    size: size,
                    iconScale: 0.58,
                    xOffset: 1
                )
            }
        }
        .frame(width: size, height: size)
        .contentShape(Rectangle())
        .accessibilityLabel(Text(visualState.buttonAccessibilityLabel))
    }
}

private struct MinimalPlaybackGlyphView: View {
    let visualState: DynamicIslandPlaybackVisualState

    var body: some View {
        PlaybackGlyphContent(
            visualState: visualState,
            size: 13,
            iconScale: 0.62,
            xOffset: 0
        )
    }
}

private struct PlaybackGlyphContent: View {
    let visualState: DynamicIslandPlaybackVisualState
    let size: CGFloat
    let iconScale: CGFloat
    let xOffset: CGFloat

    var body: some View {
        ZStack {
            if visualState == .loading || visualState == .reconnecting {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(glyphColor)
                    .scaleEffect(max(size / 38, 0.46))
            } else {
                Image(systemName: visualState.symbolName)
                    .font(.system(size: size * iconScale, weight: .bold))
                    .symbolRenderingMode(.monochrome)
                    .foregroundStyle(glyphColor)
                    .offset(x: visualState == .paused ? 0.8 : 0)
            }
        }
        .frame(width: size, height: size)
        .offset(x: xOffset)
        .animation(.easeInOut(duration: 0.22), value: visualState.rawValue)
    }

    private var glyphColor: Color {
        switch visualState {
        case .playing:
            return visualState.accentColor
        case .paused:
            return visualState.accentColor
        case .loading, .reconnecting:
            return Color.white.opacity(0.62)
        case .disconnected:
            return Color.white.opacity(0.42)
        case .unavailable:
            return visualState.accentColor.opacity(0.78)
        }
    }
}

private struct DynamicIslandStatusStrip: View {
    let visualState: DynamicIslandPlaybackVisualState
    let width: CGFloat
    let height: CGFloat

    var body: some View {
        HStack(spacing: 3) {
            switch visualState {
            case .playing:
                ForEach(Array(playingBars.enumerated()), id: \.offset) { _, value in
                    Capsule()
                        .fill(visualState.accentColor)
                        .frame(width: 3, height: max(height * value, 3))
                        .opacity(0.95)
                }
            case .paused:
                ForEach(0..<16, id: \.self) { index in
                    Circle()
                        .fill(visualState.accentColor)
                        .frame(width: 3, height: 3)
                        .opacity(index % 3 == 0 ? 0.95 : 0.55)
                }
            case .loading, .reconnecting:
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(visualState.accentColor)
                    .scaleEffect(0.58)
                Capsule()
                    .fill(visualState.accentColor.opacity(0.32))
                    .frame(width: width * 0.58, height: 3)
            case .unavailable:
                ForEach(0..<9, id: \.self) { index in
                    Capsule()
                        .fill(visualState.accentColor)
                        .frame(width: 4, height: index == 4 ? 8 : 3)
                        .opacity(index == 4 ? 0.85 : 0.42)
                }
            case .disconnected:
                Capsule()
                    .fill(visualState.accentColor.opacity(0.38))
                    .frame(width: width * 0.72, height: 3)
            }
        }
        .frame(width: width, height: height, alignment: .leading)
        .animation(.easeInOut(duration: 0.28), value: visualState.rawValue)
    }

    private var playingBars: [CGFloat] {
        [0.24, 0.44, 0.30, 0.62, 0.38, 0.86, 0.52, 1.0, 0.70, 0.42, 0.78, 0.34, 0.56]
    }
}

private struct LockScreenLiveActivityView: View {
    let state: SonyMusicActivityAttributes.ContentState

    private var visualState: DynamicIslandPlaybackVisualState {
        DynamicIslandPlaybackVisualState.resolve(state: state)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                LiveActivityArtworkView(
                    artworkKey: state.artworkKey,
                    artworkRevision: state.artworkRevision,
                    size: 54
                )

                VStack(alignment: .leading, spacing: 3) {
                    Text(state.title)
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                        .truncationMode(.tail)

                    Text(state.artist)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white.opacity(0.70))
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                        .truncationMode(.tail)

                    Text(lyricText)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(visualState.accentColor.opacity(0.88))
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                        .truncationMode(.tail)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                ExpandedPlaybackButtonView(
                    visualState: visualState,
                    size: 42,
                    iconScale: 0.42
                )
            }

            LiveActivityProgressRow(state: state)

            LiveActivityTransportControls(
                state: state,
                style: .lockScreen
            )
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 15)
    }

    private var lyricText: String {
        StateTransitionEngine.lyricState(
            for: state,
            visualState: visualState
        ).text
    }
}

private struct TrackSummaryView: View {
    let state: SonyMusicActivityAttributes.ContentState

    private var visualState: DynamicIslandPlaybackVisualState {
        DynamicIslandPlaybackVisualState.resolve(state: state)
    }

    private var lyricState: DynamicIslandLyricDisplayState {
        StateTransitionEngine.lyricState(for: state, visualState: visualState)
    }

    private var dynamicIslandStyle: DynamicIslandStyle {
        DynamicIslandStyle(rawValue: state.dynamicIslandStyle) ?? .defaultStyle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(state.title)
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .truncationMode(.tail)

            Text(state.artist)
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.68))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .truncationMode(.tail)

            Text(lyricState.text)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(lyricForegroundStyle)
                .lineLimit(1)
                .minimumScaleFactor(0.76)
                .truncationMode(.tail)

            if dynamicIslandStyle == .waveformFocused {
                DynamicIslandStatusStrip(
                    visualState: visualState,
                    width: 96,
                    height: 10
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var lyricForegroundStyle: Color {
        switch lyricState {
        case .ready:
            return .white.opacity(0.86)
        case .loading:
            return visualState.accentColor.opacity(0.78)
        case .unavailable:
            return .white.opacity(0.46)
        case .disconnected:
            return .white.opacity(0.42)
        }
    }
}

private struct ExpandedBottomView: View {
    let state: SonyMusicActivityAttributes.ContentState

    var body: some View {
        VStack(spacing: 6) {
            LiveActivityProgressRow(state: state)
                .padding(.horizontal, 14)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        }
        .padding(.top, 1)
        .clipped()
    }
}

private struct LiveActivityProgressRow: View {
    let state: SonyMusicActivityAttributes.ContentState

    private var visualState: DynamicIslandPlaybackVisualState {
        DynamicIslandPlaybackVisualState.resolve(state: state)
    }

    var body: some View {
        HStack(spacing: 8) {
            currentTimeView
                .frame(width: 38, alignment: .leading)
            progressView
                .frame(maxWidth: .infinity)
            Text(formatTime(state.durationMs))
                .frame(width: 38, alignment: .trailing)
        }
        .font(.caption2.monospacedDigit())
        .foregroundStyle(.white.opacity(0.62))
    }

    @ViewBuilder
    private var progressView: some View {
        if state.isPlaying, let interval = playbackInterval {
            ProgressView(timerInterval: interval, countsDown: false) {
                EmptyView()
            } currentValueLabel: {
                EmptyView()
            }
                .tint(visualState.accentColor)
                .labelsHidden()
        } else {
            ProgressView(value: progressValue)
                .tint(visualState.accentColor)
                .labelsHidden()
        }
    }

    @ViewBuilder
    private var currentTimeView: some View {
        if state.isPlaying, let interval = playbackInterval {
            Text(timerInterval: interval, pauseTime: nil, countsDown: false, showsHours: false)
        } else {
            Text(formatTime(state.positionAtAnchorMs))
        }
    }

    private var playbackInterval: ClosedRange<Date>? {
        guard state.durationMs > 0 else { return nil }
        let start = state.anchorDate.addingTimeInterval(
            -Double(state.positionAtAnchorMs) / 1_000
        )
        let end = start.addingTimeInterval(Double(state.durationMs) / 1_000)
        guard end > start else { return nil }
        return start...end
    }

    private var progressValue: Double {
        guard state.durationMs > 0 else { return 0 }
        return min(
            max(Double(state.positionAtAnchorMs) / Double(state.durationMs), 0),
            1
        )
    }
}

private struct LiveActivityTransportControls: View {
    enum Style {
        case lockScreen
        case dynamicIsland

        var sideSize: CGFloat {
            switch self {
            case .lockScreen:
                return 44
            case .dynamicIsland:
                return 32
            }
        }

        var playSize: CGFloat {
            switch self {
            case .lockScreen:
                return 54
            case .dynamicIsland:
                return 38
            }
        }

        var spacing: CGFloat {
            switch self {
            case .lockScreen:
                return 38
            case .dynamicIsland:
                return 28
            }
        }

        var hitSize: CGFloat {
            switch self {
            case .lockScreen:
                return 44
            case .dynamicIsland:
                return 36
            }
        }
    }

    let state: SonyMusicActivityAttributes.ContentState
    let style: Style

    private var visualState: DynamicIslandPlaybackVisualState {
        DynamicIslandPlaybackVisualState.resolve(state: state)
    }

    private var sideControlsEnabled: Bool {
        visualState == .playing || visualState == .paused
    }

    var body: some View {
        HStack(spacing: style.spacing) {
            LiveActivityControlButton(
                systemImage: "backward.fill",
                accessibilityLabel: "上一首",
                size: style.sideSize,
                iconScale: 0.38,
                accentColor: .white,
                prominent: false,
                enabled: sideControlsEnabled,
                intent: PreviousLiveActivityIntent(),
                hitSize: style.hitSize
            )

            if visualState == .disconnected {
                LiveActivityControlButton(
                    systemImage: visualState.symbolName,
                    accessibilityLabel: visualState.buttonAccessibilityLabel,
                    size: style.playSize,
                    iconScale: 0.40,
                    accentColor: visualState.accentColor,
                    prominent: true,
                    enabled: true,
                    intent: ReconnectLiveActivityIntent(),
                    hitSize: style.hitSize
                )
            } else {
                LiveActivityControlButton(
                    systemImage: visualState.symbolName,
                    accessibilityLabel: visualState.buttonAccessibilityLabel,
                    size: style.playSize,
                    iconScale: 0.40,
                    accentColor: visualState.accentColor,
                    prominent: true,
                    enabled: visualState.controlsEnabled,
                    intent: PlayPauseLiveActivityIntent(),
                    hitSize: style.hitSize
                )
            }

            LiveActivityControlButton(
                systemImage: "forward.fill",
                accessibilityLabel: "下一首",
                size: style.sideSize,
                iconScale: 0.38,
                accentColor: .white,
                prominent: false,
                enabled: sideControlsEnabled,
                intent: NextLiveActivityIntent(),
                hitSize: style.hitSize
            )
        }
    }
}

private struct LiveActivityControlButton<Intent: LiveActivityIntent>: View {
    let systemImage: String
    let accessibilityLabel: String
    let size: CGFloat
    let iconScale: CGFloat
    let accentColor: Color
    let prominent: Bool
    let enabled: Bool
    let intent: Intent
    let hitSize: CGFloat

    var body: some View {
        Group {
            if enabled {
                Button(intent: intent) {
                    buttonFace
                }
                .buttonStyle(.plain)
            } else {
                buttonFace
                    .opacity(0.42)
            }
        }
        .accessibilityLabel(Text(accessibilityLabel))
    }

    private var buttonFace: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * iconScale, weight: .bold))
            .foregroundStyle(prominent ? accentColor : .white)
            .frame(width: max(size, hitSize), height: max(size, hitSize))
            .background(
                (prominent ? accentColor.opacity(0.16) : Color.white.opacity(0.10)),
                in: Circle()
            )
            .overlay(
                Circle()
                    .stroke(
                        prominent ? accentColor.opacity(0.62) : Color.white.opacity(enabled ? 0.10 : 0.05),
                        lineWidth: prominent ? 1.4 : 1
                    )
            )
            .contentShape(Circle())
    }
}

private func formatTime(_ milliseconds: Int64) -> String {
    let totalSeconds = max(milliseconds, 0) / 1_000
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    return String(format: "%02lld:%02lld", minutes, seconds)
}

#if DEBUG
private let previewAttributes = SonyMusicActivityAttributes(name: "Sony Music")

private let previewPlayingState = SonyMusicActivityAttributes.ContentState(
    trackId: "preview-playing",
    title: "星空剪影",
    artist: "蓝心羽",
    lyric: "这是一句非常长的歌词用于测试灵动岛中的截断效果",
    lyricLineIndex: 12,
    isPlaying: true,
    positionAtAnchorMs: 34_000,
    anchorDate: Date(),
    durationMs: 216_000,
    connectionState: "connected",
    islandState: IslandState.playing.rawValue,
    islandStateChangedAt: Date(),
    artworkKey: nil,
    artworkRevision: 0
)

private let previewPausedState = SonyMusicActivityAttributes.ContentState(
    trackId: "preview-paused",
    title: "如果清醒是种罪",
    artist: "陈奕迅",
    lyric: "谁跟谁告别",
    lyricLineIndex: 4,
    isPlaying: false,
    positionAtAnchorMs: 142_000,
    anchorDate: Date(),
    durationMs: 239_000,
    connectionState: "connected",
    islandState: IslandState.paused.rawValue,
    islandStateChangedAt: Date(),
    artworkKey: nil,
    artworkRevision: 0
)

private let previewDisconnectedState = SonyMusicActivityAttributes.ContentState(
    trackId: "preview-disconnected",
    title: "Sony Music",
    artist: "未知歌手",
    lyric: "",
    lyricLineIndex: 0,
    isPlaying: false,
    positionAtAnchorMs: 0,
    anchorDate: Date(),
    durationMs: 0,
    connectionState: "disconnected",
    islandState: IslandState.disconnected.rawValue,
    islandStateChangedAt: Date(),
    artworkKey: nil,
    artworkRevision: 0
)

private let previewLongTitleState = SonyMusicActivityAttributes.ContentState(
    trackId: "preview-long-title",
    title: "僕が死のうと思ったのは (Live Version)",
    artist: "生物股长 (いきものがかり)",
    lyric: "这是一句非常长的歌词用于测试灵动岛中的截断效果",
    lyricLineIndex: 19,
    isPlaying: true,
    positionAtAnchorMs: 88_000,
    anchorDate: Date(),
    durationMs: 301_000,
    connectionState: "connected",
    islandState: IslandState.trackChanged.rawValue,
    islandStateChangedAt: Date(),
    artworkKey: nil,
    artworkRevision: 0
)

private let previewEmptyLyricState = SonyMusicActivityAttributes.ContentState(
    trackId: "preview-empty-lyric",
    title: "纯音乐",
    artist: "Various Artists",
    lyric: "",
    lyricLineIndex: 0,
    isPlaying: true,
    positionAtAnchorMs: 21_000,
    anchorDate: Date(),
    durationMs: 182_000,
    connectionState: "connected",
    islandState: IslandState.playing.rawValue,
    islandStateChangedAt: Date(),
    artworkKey: nil,
    artworkRevision: 0
)

#Preview("Lock Screen - Playing", as: .content, using: previewAttributes) {
    SonyMusicLiveActivityWidget()
} contentStates: {
    previewPlayingState
}

#Preview("Lock Screen - Paused", as: .content, using: previewAttributes) {
    SonyMusicLiveActivityWidget()
} contentStates: {
    previewPausedState
}

#Preview("Lock Screen - Disconnected", as: .content, using: previewAttributes) {
    SonyMusicLiveActivityWidget()
} contentStates: {
    previewDisconnectedState
}

#Preview("Dynamic Island Expanded", as: .dynamicIsland(.expanded), using: previewAttributes) {
    SonyMusicLiveActivityWidget()
} contentStates: {
    previewLongTitleState
}

#Preview("Dynamic Island Compact", as: .dynamicIsland(.compact), using: previewAttributes) {
    SonyMusicLiveActivityWidget()
} contentStates: {
    previewPlayingState
}

#Preview("Dynamic Island Minimal", as: .dynamicIsland(.minimal), using: previewAttributes) {
    SonyMusicLiveActivityWidget()
} contentStates: {
    previewEmptyLyricState
}
#endif
