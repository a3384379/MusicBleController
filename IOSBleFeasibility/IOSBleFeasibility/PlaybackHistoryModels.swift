import Foundation

struct PlaybackHistorySession: Identifiable, Codable, Equatable {
    let sessionId: Int64
    let trackKey: String
    let title: String
    let artist: String
    let album: String
    let artworkId: String?
    let startedAt: Int64
    let endedAt: Int64?
    let listenedMs: Int64
    let durationMs: Int64
    let completed: Bool
    let skipped: Bool
    let countedPlay: Bool

    var id: Int64 { sessionId }
}

struct PlaybackTopTrack: Codable, Equatable, Identifiable {
    let trackKey: String
    let title: String
    let artist: String
    let album: String
    let artworkId: String?
    let listenedMs: Int64
    let playCount: Int
    let completedCount: Int
    let skippedCount: Int

    var id: String { trackKey }
}

struct PlaybackTopArtist: Codable, Equatable, Identifiable {
    let artist: String
    let listenedMs: Int64
    let playCount: Int
    let trackCount: Int

    var id: String { artist }
}

struct DailyListenStat: Codable, Equatable, Identifiable {
    let dateKey: String
    let listenedMs: Int64
    let playCount: Int

    var id: String { dateKey }
}

struct PlaybackStatsSnapshot: Codable, Equatable, Identifiable {
    let range: String
    let rangeStart: Int64
    let rangeEnd: Int64
    let totalListenMs: Int64
    let playCount: Int
    let uniqueTrackCount: Int
    let completedCount: Int
    let skippedCount: Int
    let completionRate: Double
    let skipRate: Double
    let topTracks: [PlaybackTopTrack]
    let topArtists: [PlaybackTopArtist]
    let dailyTrend: [DailyListenStat]

    var id: String { range }
}

struct PlaybackHistorySyncState: Codable, Equatable {
    var lastSyncedSessionId: Int64 = 0
}
