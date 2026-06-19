import Foundation

final class PlaybackHistoryStore {
    static let shared = PlaybackHistoryStore()

    private let queue = DispatchQueue(label: "com.sqz.IOSBleFeasibility.PlaybackHistoryStore")
    private let fileManager = FileManager.default
    private let maxSessions = 2_000

    private var directoryURL: URL {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("PlaybackHistory", isDirectory: true)
    }

    private var sessionsURL: URL {
        directoryURL.appendingPathComponent("sessions.json")
    }

    private var syncStateURL: URL {
        directoryURL.appendingPathComponent("sync_state.json")
    }

    private func statsURL(range: String) -> URL {
        directoryURL.appendingPathComponent("stats_\(range.lowercased()).json")
    }

    func loadSessions(completion: @escaping ([PlaybackHistorySession]) -> Void) {
        queue.async {
            completion(self.readSessions())
        }
    }

    func loadSyncState(completion: @escaping (PlaybackHistorySyncState) -> Void) {
        queue.async {
            completion(self.readSyncState())
        }
    }

    func loadStats(completion: @escaping ([String: PlaybackStatsSnapshot]) -> Void) {
        queue.async {
            var result: [String: PlaybackStatsSnapshot] = [:]
            for range in ["TODAY", "WEEK", "MONTH"] {
                if let stats = self.readDecodable(
                    PlaybackStatsSnapshot.self,
                    from: self.statsURL(range: range)
                ) {
                    result[range] = stats
                }
            }
            completion(result)
        }
    }

    func mergeSessions(
        _ sessions: [PlaybackHistorySession],
        completion: @escaping ([PlaybackHistorySession]) -> Void
    ) {
        queue.async {
            var merged = Dictionary(
                uniqueKeysWithValues: self.readSessions().map { ($0.sessionId, $0) }
            )
            sessions.forEach { merged[$0.sessionId] = $0 }
            let sorted = merged.values
                .sorted { $0.sessionId > $1.sessionId }
                .prefix(self.maxSessions)
            let result = Array(sorted)
            self.writeEncodable(result, to: self.sessionsURL)
            completion(result)
        }
    }

    func saveSyncState(_ state: PlaybackHistorySyncState) {
        queue.async {
            self.writeEncodable(state, to: self.syncStateURL)
        }
    }

    func saveStats(_ stats: PlaybackStatsSnapshot) {
        queue.async {
            self.writeEncodable(stats, to: self.statsURL(range: stats.range))
        }
    }

    func clear(completion: @escaping () -> Void) {
        queue.async {
            try? self.fileManager.removeItem(at: self.sessionsURL)
            try? self.fileManager.removeItem(at: self.syncStateURL)
            for range in ["TODAY", "WEEK", "MONTH"] {
                try? self.fileManager.removeItem(at: self.statsURL(range: range))
            }
            completion()
        }
    }

    private func ensureDirectory() {
        try? fileManager.createDirectory(
            at: directoryURL,
            withIntermediateDirectories: true
        )
    }

    private func readSessions() -> [PlaybackHistorySession] {
        readDecodable([PlaybackHistorySession].self, from: sessionsURL) ?? []
    }

    private func readSyncState() -> PlaybackHistorySyncState {
        readDecodable(PlaybackHistorySyncState.self, from: syncStateURL) ??
            PlaybackHistorySyncState()
    }

    private func readDecodable<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func writeEncodable<T: Encodable>(_ value: T, to url: URL) {
        ensureDirectory()
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
