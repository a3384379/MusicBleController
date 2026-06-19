import CryptoKit
import SwiftUI

struct PlaybackHistoryView: View {
    @ObservedObject var bleManager: BLETestManager
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("播放历史", selection: $tab) {
                    Text("最近播放").tag(0)
                    Text("统计").tag(1)
                }
                .pickerStyle(.segmented)
                .padding()

                if tab == 0 {
                    recentList
                } else {
                    PlaybackStatsView(stats: bleManager.playbackStats)
                }
            }
            .navigationTitle("播放历史")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("刷新") {
                            bleManager.syncPlaybackHistory()
                        }
                        Button("清除 iPhone 本地缓存", role: .destructive) {
                            bleManager.clearLocalPlaybackHistory()
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .onAppear {
                bleManager.loadCachedPlaybackHistory()
                bleManager.syncPlaybackHistory()
            }
        }
    }

    private var recentList: some View {
        List {
            if !bleManager.playbackHistoryStatus.isEmpty {
                Text(bleManager.playbackHistoryStatus)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if bleManager.playbackHistorySessions.isEmpty {
                ContentUnavailableView(
                    "暂无播放历史",
                    systemImage: "music.note.list",
                    description: Text("连接 Sony 后会从 PlayerAgent 同步历史。")
                )
            } else {
                ForEach(bleManager.playbackHistorySessions) { session in
                    PlaybackHistoryRow(session: session)
                        .onAppear {
                            if session.sessionId == bleManager.playbackHistorySessions.last?.sessionId {
                                bleManager.loadMorePlaybackHistory()
                            }
                        }
                }
            }
        }
        .refreshable {
            bleManager.syncPlaybackHistory()
        }
    }
}

private struct PlaybackHistoryRow: View {
    let session: PlaybackHistorySession

    var body: some View {
        HStack(spacing: 12) {
            HistoryArtworkView(artworkId: session.artworkId)
                .frame(width: 48, height: 48)

            VStack(alignment: .leading, spacing: 4) {
                Text(session.title.ifBlank("-"))
                    .font(.headline)
                    .lineLimit(1)
                Text("\(session.artist.ifBlank("未知歌手")) · \(session.album.ifBlank("未知专辑"))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text("\(PlaybackHistoryFormat.dateTime(session.startedAt)) · 听了 \(PlaybackHistoryFormat.duration(session.listenedMs))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                Text(stateText)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(stateColor)
            }
        }
        .padding(.vertical, 4)
    }

    private var stateText: String {
        if session.completed { return "已完播" }
        if session.skipped { return "已跳过" }
        if session.countedPlay { return "已计播放" }
        return "未计播放"
    }

    private var stateColor: Color {
        if session.completed { return .green }
        if session.skipped { return .orange }
        return .secondary
    }
}

struct PlaybackStatsView: View {
    let stats: [String: PlaybackStatsSnapshot]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                summaryGrid
                if let week = stats["WEEK"] {
                    rankingSection(title: "本周最常听歌曲", tracks: week.topTracks)
                    artistSection(title: "本周最常听歌手", artists: week.topArtists)
                    trendSection(stats: week.dailyTrend)
                }
            }
            .padding()
        }
    }

    private var summaryGrid: some View {
        let today = stats["TODAY"]
        let week = stats["WEEK"]
        let month = stats["MONTH"]
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            statCard("今日", PlaybackHistoryFormat.duration(today?.totalListenMs ?? 0))
            statCard("本周", PlaybackHistoryFormat.duration(week?.totalListenMs ?? 0))
            statCard("本月", PlaybackHistoryFormat.duration(month?.totalListenMs ?? 0))
            statCard("播放次数", "\(week?.playCount ?? 0)")
            statCard("完播率", PlaybackHistoryFormat.percent(week?.completionRate ?? 0))
            statCard("跳过率", PlaybackHistoryFormat.percent(week?.skipRate ?? 0))
        }
    }

    private func statCard(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.headline.monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private func rankingSection(title: String, tracks: [PlaybackTopTrack]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            ForEach(tracks.prefix(10)) { track in
                HStack {
                    Text(track.title.ifBlank("-"))
                        .lineLimit(1)
                    Spacer()
                    Text(PlaybackHistoryFormat.duration(track.listenedMs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func artistSection(title: String, artists: [PlaybackTopArtist]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            ForEach(artists.prefix(10)) { artist in
                HStack {
                    Text(artist.artist.ifBlank("未知歌手"))
                        .lineLimit(1)
                    Spacer()
                    Text(PlaybackHistoryFormat.duration(artist.listenedMs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func trendSection(stats: [DailyListenStat]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("最近趋势").font(.headline)
            let maxValue = max(stats.map(\.listenedMs).max() ?? 1, 1)
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(stats.suffix(7)) { day in
                    VStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(.green)
                            .frame(height: max(8, CGFloat(day.listenedMs) / CGFloat(maxValue) * 80))
                        Text(String(day.dateKey.suffix(5)))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

private struct HistoryArtworkView: View {
    let artworkId: String?

    var body: some View {
        Group {
            if let image = cachedImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "music.note")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.quaternary)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 9))
    }

    private var cachedImage: UIImage? {
        guard let artworkId, !artworkId.isEmpty else { return nil }
        let base = sha256(artworkId)
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("AlbumArtCache", isDirectory: true)
        for suffix in ["_hq", "_preview", ""] {
            let url = directory.appendingPathComponent("\(base)\(suffix)").appendingPathExtension("jpg")
            if let data = try? Data(contentsOf: url),
               let image = UIImage(data: data) {
                return image
            }
        }
        return nil
    }

    private func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

enum PlaybackHistoryFormat {
    static func duration(_ ms: Int64) -> String {
        let totalSeconds = max(ms, 0) / 1_000
        let hours = totalSeconds / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return "\(hours)小时\(minutes)分钟"
        }
        return String(format: "%02d:%02d", minutes, seconds)
    }

    static func dateTime(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1_000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.doesRelativeDateFormatting = true
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    static func percent(_ value: Double) -> String {
        "\(Int((value * 100).rounded()))%"
    }
}

private extension String {
    func ifBlank(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }
}
