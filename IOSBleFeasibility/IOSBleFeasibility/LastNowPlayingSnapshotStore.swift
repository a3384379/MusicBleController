import Foundation

struct LastNowPlayingSnapshot: Codable, Equatable {
    static let version = 1
    static let maximumAge: TimeInterval = 24 * 60 * 60

    struct Word: Codable, Equatable {
        let id: Int
        let startMs: Int64
        let durationMs: Int64
        let text: String
    }

    struct Line: Codable, Equatable {
        let index: Int
        let timeMs: Int64
        let durationMs: Int64
        let text: String
        let translation: String?
        let romanization: String?
        let words: [Word]
    }

    let version: Int
    let trackId: String
    let title: String
    let artist: String
    let album: String
    let wasPlaying: Bool
    let positionMs: Int64
    let durationMs: Int64
    let lyricLines: [Line]
    let albumArtId: String
    let savedAt: Date

    var isValid: Bool {
        version == Self.version &&
            !trackId.isEmpty &&
            !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

final class LastNowPlayingSnapshotStore {
    static let shared = LastNowPlayingSnapshotStore()

    private let ioQueue = DispatchQueue(
        label: "com.musicblecontroller.last-now-playing",
        qos: .utility
    )
    private let fileURL: URL
    private var pendingSaveWorkItem: DispatchWorkItem?

    init(fileURL: URL? = nil) {
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let baseURL = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first ?? FileManager.default.temporaryDirectory
            self.fileURL = baseURL
                .appendingPathComponent("MusicBleController", isDirectory: true)
                .appendingPathComponent("LastNowPlayingSnapshot-v1.json")
        }
    }

    func load(
        now: Date = Date(),
        completion: @escaping (LastNowPlayingSnapshot?) -> Void
    ) {
        ioQueue.async { [fileURL] in
            let snapshot = Self.readSnapshot(fileURL: fileURL, now: now)
            DispatchQueue.main.async {
                completion(snapshot)
            }
        }
    }

    func save(_ snapshot: LastNowPlayingSnapshot) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            self.pendingSaveWorkItem?.cancel()
            let workItem = DispatchWorkItem { [weak self] in
                self?.write(snapshot)
            }
            self.pendingSaveWorkItem = workItem
            self.ioQueue.asyncAfter(deadline: .now() + 0.35, execute: workItem)
        }
    }

    func clear() {
        ioQueue.async { [weak self] in
            guard let self else { return }
            self.pendingSaveWorkItem?.cancel()
            self.pendingSaveWorkItem = nil
            try? FileManager.default.removeItem(at: self.fileURL)
        }
    }

    static func readSnapshot(
        fileURL: URL,
        now: Date = Date()
    ) -> LastNowPlayingSnapshot? {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(
                LastNowPlayingSnapshot.self,
                from: data
              ),
              snapshot.isValid,
              now.timeIntervalSince(snapshot.savedAt) >= 0,
              now.timeIntervalSince(snapshot.savedAt) <= LastNowPlayingSnapshot.maximumAge else {
            return nil
        }
        return snapshot
    }

    private func write(_ snapshot: LastNowPlayingSnapshot) {
        guard snapshot.isValid,
              let data = try? JSONEncoder().encode(snapshot) else {
            return
        }
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // The next stable media update will retry. Snapshot persistence must
            // never block or destabilize the playback path.
        }
    }
}
