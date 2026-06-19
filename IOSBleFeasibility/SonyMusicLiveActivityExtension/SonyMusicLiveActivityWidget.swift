import ActivityKit
import SwiftUI
import UIKit
import WidgetKit

struct SonyMusicLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SonyMusicActivityAttributes.self) { context in
            LockScreenLiveActivityView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.78))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    LiveActivityArtworkView(
                        artworkKey: context.state.artworkKey,
                        artworkRevision: context.state.artworkRevision,
                        size: 48
                    )
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ExpandedPlaybackStatus(isPlaying: context.state.isPlaying)
                }
                DynamicIslandExpandedRegion(.center) {
                    TrackSummaryView(state: context.state)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ProgressRow(state: context.state)
                    .padding(.horizontal, 16)
                    .padding(.top, 2)
                }
            } compactLeading: {
                LiveActivityArtworkView(
                    artworkKey: context.state.artworkKey,
                    artworkRevision: context.state.artworkRevision,
                    size: 24
                )
            } compactTrailing: {
                PlaybackIcon(isPlaying: context.state.isPlaying)
            } minimal: {
                Image(systemName: "music.note")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
            }
            .keylineTint(.green)
        }
    }

}

private struct LockScreenLiveActivityView: View {
    let state: SonyMusicActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 12) {
            LiveActivityArtworkView(
                artworkKey: state.artworkKey,
                artworkRevision: state.artworkRevision,
                size: 58
            )

            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(state.title)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 6)
                    PlaybackBadge(isPlaying: state.isPlaying)
                }

                Text(state.artist)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .truncationMode(.tail)

                Text(state.lyric)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white.opacity(0.88))
                    .lineLimit(1)
                    .truncationMode(.tail)

                ProgressRow(state: state)
            }
        }
        .padding(.vertical, 7)
        .padding(.horizontal, 2)
    }
}

private struct TrackSummaryView: View {
    let state: SonyMusicActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(state.title)
                .font(.headline.weight(.bold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .truncationMode(.tail)
            Text(state.artist)
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.68))
                .lineLimit(1)
                .truncationMode(.tail)
            Text(state.lyric)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.white.opacity(0.55))
                .lineLimit(1)
                .truncationMode(.tail)
        }
    }
}

private struct ProgressRow: View {
    let state: SonyMusicActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 8) {
            currentTimeView
                .frame(minWidth: 34, alignment: .leading)
            progressView
            Text(formatTime(state.durationMs))
                .frame(minWidth: 34, alignment: .trailing)
        }
        .font(.caption2.monospacedDigit())
        .foregroundStyle(.white.opacity(0.62))
    }

    @ViewBuilder
    private var progressView: some View {
        if state.isPlaying, let interval = playbackInterval {
            ProgressView(timerInterval: interval, countsDown: false)
                .tint(.green)
        } else {
            ProgressView(value: progressValue)
                .tint(.green)
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

private struct PlaybackBadge: View {
    let isPlaying: Bool

    var body: some View {
        Label(isPlaying ? "播放中" : "已暂停", systemImage: isPlaying ? "music.note" : "pause.fill")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(isPlaying ? .green : .white.opacity(0.72))
            .labelStyle(.titleAndIcon)
    }
}

private struct ExpandedPlaybackStatus: View {
    let isPlaying: Bool

    var body: some View {
        VStack(spacing: 3) {
            PlaybackIcon(isPlaying: isPlaying)
                .font(.headline.weight(.bold))
            Text(isPlaying ? "播放中" : "已暂停")
                .font(.caption2.weight(.medium))
                .foregroundStyle(.white.opacity(0.62))
                .lineLimit(1)
        }
        .frame(minWidth: 42)
    }
}

private struct PlaybackIcon: View {
    let isPlaying: Bool

    var body: some View {
        Image(systemName: isPlaying ? "play.fill" : "pause.fill")
            .font(.caption.weight(.bold))
            .foregroundStyle(.white)
    }
}

private func formatTime(_ milliseconds: Int64) -> String {
    let totalSeconds = max(milliseconds, 0) / 1_000
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    return String(format: "%02lld:%02lld", minutes, seconds)
}
