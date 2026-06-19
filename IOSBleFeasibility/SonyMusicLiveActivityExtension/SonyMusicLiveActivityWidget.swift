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
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    LiveActivityArtworkView(
                        artworkKey: context.state.artworkKey,
                        artworkRevision: context.state.artworkRevision,
                        size: 44
                    )
                }
                DynamicIslandExpandedRegion(.trailing) {
                    EmptyView()
                }
                DynamicIslandExpandedRegion(.center) {
                    TrackSummaryView(state: context.state)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ExpandedBottomView(state: context.state)
                }
            } compactLeading: {
                LiveActivityArtworkView(
                    artworkKey: context.state.artworkKey,
                    artworkRevision: context.state.artworkRevision,
                    size: 22
                )
            } compactTrailing: {
                PlaybackIcon(isPlaying: context.state.isPlaying)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
            } minimal: {
                LiveActivityArtworkView(
                    artworkKey: context.state.artworkKey,
                    artworkRevision: context.state.artworkRevision,
                    size: 16
                )
            }
            .keylineTint(.green)
            .widgetURL(URL(string: "sonymusic://nowplaying"))
        }
    }
}

private struct LockScreenLiveActivityView: View {
    let state: SonyMusicActivityAttributes.ContentState

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
                        .foregroundStyle(.white.opacity(0.82))
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                        .truncationMode(.tail)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
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
        state.connectionState == "disconnected" ? "Sony 已断开" : state.lyric
    }
}

private struct TrackSummaryView: View {
    let state: SonyMusicActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
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

            Text(lyricText)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.white.opacity(0.72))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .truncationMode(.tail)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var lyricText: String {
        state.connectionState == "disconnected" ? "Sony 已断开" : state.lyric
    }
}

private struct ExpandedBottomView: View {
    let state: SonyMusicActivityAttributes.ContentState

    var body: some View {
        VStack(spacing: 8) {
            LiveActivityProgressRow(state: state)
                .padding(.horizontal, 18)

            LiveActivityTransportControls(
                state: state,
                style: .dynamicIsland
            )
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding(.top, 2)
    }
}

private struct LiveActivityProgressRow: View {
    let state: SonyMusicActivityAttributes.ContentState

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
                .tint(.green)
                .labelsHidden()
        } else {
            ProgressView(value: progressValue)
                .tint(.green)
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

    private var isConnected: Bool {
        state.connectionState == "connected"
    }

    var body: some View {
        HStack(spacing: style.spacing) {
            LiveActivityControlButton(
                systemImage: "backward.fill",
                accessibilityLabel: "上一首",
                size: style.sideSize,
                iconScale: 0.38,
                backgroundOpacity: 0.10,
                enabled: isConnected,
                intent: PreviousLiveActivityIntent(),
                hitSize: style.hitSize
            )

            LiveActivityControlButton(
                systemImage: state.isPlaying ? "pause.fill" : "play.fill",
                accessibilityLabel: state.isPlaying ? "暂停" : "播放",
                size: style.playSize,
                iconScale: 0.40,
                backgroundOpacity: 0.16,
                enabled: isConnected,
                intent: PlayPauseLiveActivityIntent(),
                hitSize: style.hitSize
            )

            LiveActivityControlButton(
                systemImage: "forward.fill",
                accessibilityLabel: "下一首",
                size: style.sideSize,
                iconScale: 0.38,
                backgroundOpacity: 0.10,
                enabled: isConnected,
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
    let backgroundOpacity: Double
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
            .foregroundStyle(.white)
            .frame(width: max(size, hitSize), height: max(size, hitSize))
            .background(.white.opacity(backgroundOpacity), in: Circle())
            .overlay(
                Circle()
                    .stroke(.white.opacity(enabled ? 0.10 : 0.05), lineWidth: 1)
            )
            .contentShape(Circle())
    }
}

private struct PlaybackIcon: View {
    let isPlaying: Bool

    var body: some View {
        Image(systemName: isPlaying ? "play.fill" : "pause.fill")
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
