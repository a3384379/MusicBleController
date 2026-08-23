import CryptoKit
import SwiftUI

struct PlaybackHistoryView: View {
    @ObservedObject var bleManager: BLETestManager
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0
    @State private var query = ""
    @State private var range: PlaybackHistoryRange = .week

    var body: some View {
        NavigationStack {
            ZStack {
                PlayerDesignTokens.canvas.ignoresSafeArea()
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
            }
            .navigationTitle("播放历史")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("刷新") { syncIfIdle() }
                            .disabled(bleManager.isPlaybackHistorySyncing)
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
                syncIfIdle()
            }
            .searchable(text: $query, prompt: "搜索歌曲、歌手或专辑")
        }
        .preferredColorScheme(.dark)
    }

    private var recentList: some View {
        List {
            Picker("时间范围", selection: $range) {
                ForEach(PlaybackHistoryRange.allCases) { option in
                    Text(option.title).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)

            if !bleManager.playbackHistoryStatus.isEmpty {
                Text(bleManager.playbackHistoryStatus)
                    .font(.footnote)
                    .foregroundStyle(PlayerDesignTokens.secondaryText)
                    .listRowBackground(Color.clear)
            }

            if filteredSessions.isEmpty {
                ContentUnavailableView(
                    query.isEmpty ? "暂无播放历史" : "未找到播放记录",
                    systemImage: "music.note.list",
                    description: Text(
                        query.isEmpty
                            ? "连接 Sony 后会从 PlayerAgent 同步历史。"
                            : "试试其他歌曲、歌手或专辑。"
                    )
                )
                .listRowBackground(Color.clear)
            } else {
                ForEach(groupedSessions, id: \.title) { group in
                    Section(group.title) {
                        ForEach(group.sessions) { session in
                            PlaybackHistoryRow(session: session)
                                .listRowBackground(PlayerDesignTokens.surface1)
                                .onAppear {
                                    if session.sessionId == filteredSessions.last?.sessionId,
                                       !bleManager.isPlaybackHistorySyncing {
                                        bleManager.loadMorePlaybackHistory()
                                    }
                                }
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .listStyle(.insetGrouped)
        .refreshable { syncIfIdle() }
    }

    private var filteredSessions: [PlaybackHistorySession] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
            .localizedLowercase
        return bleManager.playbackHistorySessions.filter { session in
            range.contains(timestampMs: session.startedAt) && (
                normalized.isEmpty || [session.title, session.artist, session.album]
                    .contains { $0.localizedLowercase.contains(normalized) }
            )
        }
    }

    private var groupedSessions: [(title: String, sessions: [PlaybackHistorySession])] {
        var result: [(String, [PlaybackHistorySession])] = []
        for session in filteredSessions {
            let title = PlaybackHistoryFormat.dayTitle(session.startedAt)
            if result.last?.0 == title {
                result[result.count - 1].1.append(session)
            } else {
                result.append((title, [session]))
            }
        }
        return result
    }

    private func syncIfIdle() {
        guard !bleManager.isPlaybackHistorySyncing else { return }
        bleManager.syncPlaybackHistory()
    }
}

private enum PlaybackHistoryRange: String, CaseIterable, Identifiable {
    case today
    case week
    case month
    case all

    var id: String { rawValue }
    var title: String {
        switch self {
        case .today: "今日"
        case .week: "7 天"
        case .month: "30 天"
        case .all: "全部"
        }
    }

    func contains(timestampMs: Int64, now: Date = Date()) -> Bool {
        guard self != .all else { return true }
        let calendar = Calendar.current
        let days: Int
        switch self {
        case .today: days = 0
        case .week: days = 6
        case .month: days = 29
        case .all: return true
        }
        let startOfToday = calendar.startOfDay(for: now)
        let lowerBound = calendar.date(byAdding: .day, value: -days, to: startOfToday) ?? startOfToday
        return Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1_000) >= lowerBound
    }
}

private struct PlaybackHistoryRow: View {
    let session: PlaybackHistorySession

    var body: some View {
        HStack(spacing: 12) {
            HistoryArtworkView(artworkId: session.artworkId)
                .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(session.title.ifBlank("-"))
                    .font(.headline)
                    .lineLimit(1)
                Text("\(session.artist.ifBlank("未知歌手")) · \(session.album.ifBlank("未知专辑"))")
                    .font(.subheadline)
                    .foregroundStyle(PlayerDesignTokens.secondaryText)
                    .lineLimit(1)
                Text("\(PlaybackHistoryFormat.dateTime(session.startedAt)) · 听了 \(PlaybackHistoryFormat.duration(session.listenedMs))")
                    .font(.caption)
                    .foregroundStyle(PlayerDesignTokens.secondaryText)
                    .monospacedDigit()
                Text(stateText)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(stateColor)
            }
        }
        .padding(.vertical, 7)
        .accessibilityElement(children: .combine)
    }

    private var stateText: String {
        if session.completed { return "已完播" }
        if session.skipped { return "已跳过" }
        if session.countedPlay { return "已计播放" }
        return "未计播放"
    }

    private var stateColor: Color {
        if session.completed { return PlayerDesignTokens.stableAccent }
        if session.skipped { return PlayerDesignTokens.warning }
        return PlayerDesignTokens.secondaryText
    }
}

struct PlaybackStatsView: View {
    let stats: [String: PlaybackStatsSnapshot]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                summaryGrid
                if let week = stats["WEEK"] {
                    rankingSection(title: "本周最常听歌曲", tracks: week.topTracks)
                    artistSection(title: "本周最常听歌手", artists: week.topArtists)
                    trendSection(stats: week.dailyTrend)
                }
            }
            .padding(20)
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
            Text(title).font(.caption).foregroundStyle(PlayerDesignTokens.secondaryText)
            Text(value).font(.headline.monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(PlayerDesignTokens.surface1, in: RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius))
    }

    private func rankingSection(title: String, tracks: [PlaybackTopTrack]) -> some View {
        darkCard(title: title) {
            ForEach(tracks.prefix(10)) { track in
                HStack {
                    Text(track.title.ifBlank("-")).lineLimit(1)
                    Spacer()
                    Text(PlaybackHistoryFormat.duration(track.listenedMs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(PlayerDesignTokens.secondaryText)
                }
            }
        }
    }

    private func artistSection(title: String, artists: [PlaybackTopArtist]) -> some View {
        darkCard(title: title) {
            ForEach(artists.prefix(10)) { artist in
                HStack {
                    Text(artist.artist.ifBlank("未知歌手")).lineLimit(1)
                    Spacer()
                    Text(PlaybackHistoryFormat.duration(artist.listenedMs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(PlayerDesignTokens.secondaryText)
                }
            }
        }
    }

    private func trendSection(stats: [DailyListenStat]) -> some View {
        darkCard(title: "最近趋势") {
            let maxValue = max(stats.map(\.listenedMs).max() ?? 1, 1)
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(stats.suffix(7)) { day in
                    VStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(PlayerDesignTokens.stableAccent)
                            .frame(height: max(8, CGFloat(day.listenedMs) / CGFloat(maxValue) * 80))
                        Text(String(day.dateKey.suffix(5)))
                            .font(.caption2)
                            .foregroundStyle(PlayerDesignTokens.secondaryText)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func darkCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            content()
        }
        .padding(16)
        .background(PlayerDesignTokens.surface1, in: RoundedRectangle(cornerRadius: PlayerDesignTokens.cardRadius))
    }
}

private struct HistoryArtworkView: View {
    let artworkId: String?
    @State private var cachedImage: UIImage?

    var body: some View {
        Group {
            if let image = cachedImage {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note")
                    .font(.title3)
                    .foregroundStyle(PlayerDesignTokens.secondaryText)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(PlayerDesignTokens.surface2)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .task(id: artworkId) { cachedImage = await loadCachedImage() }
    }

    private func loadCachedImage() async -> UIImage? {
        guard let artworkId, !artworkId.isEmpty else { return nil }
        let base = sha256(artworkId)
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("AlbumArtCache", isDirectory: true)
        for (suffix, quality) in [("_hq", "hq"), ("_preview", "preview"), ("", "legacy")] {
            let url = directory.appendingPathComponent("\(base)\(suffix)").appendingPathExtension("jpg")
            if let image = await ArtworkImageCache.shared.load(
                artworkId: artworkId,
                quality: "history-\(quality)",
                fileURL: url,
                maximumPixelSize: ArtworkImageCache.historyArtworkMaximumPixelSize
            ) { return image }
        }
        return nil
    }

    private func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

enum PlaybackHistoryFormat {
    private static let dateTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.doesRelativeDateFormatting = true
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter
    }()

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M 月 d 日 EEEE"
        return formatter
    }()

    static func duration(_ ms: Int64) -> String {
        let totalSeconds = max(ms, 0) / 1_000
        let hours = totalSeconds / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 { return "\(hours)小时\(minutes)分钟" }
        return String(format: "%02d:%02d", minutes, seconds)
    }

    static func dateTime(_ ms: Int64) -> String {
        dateTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1_000))
    }

    static func dayTitle(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1_000)
        if Calendar.current.isDateInToday(date) { return "今天" }
        if Calendar.current.isDateInYesterday(date) { return "昨天" }
        return dayFormatter.string(from: date)
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
