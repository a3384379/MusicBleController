import CryptoKit
import Foundation

struct FullLyricsCacheEntry: Codable, Equatable {
    static let version = 1
    static let maximumAge: TimeInterval = 30 * 24 * 60 * 60

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
    let lines: [Line]
    let savedAt: Date

    var isValid: Bool {
        version == Self.version &&
            !trackId.isEmpty &&
            !normalized(title).isEmpty &&
            !lines.isEmpty &&
            lines.count <= 2_000
    }

    func matches(title: String, artist: String) -> Bool {
        normalized(self.title) == normalized(title) &&
            normalized(self.artist) == normalized(artist)
    }

    private func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

final class FullLyricsCacheStore {
    static let shared = FullLyricsCacheStore()

    private final class EntryBox {
        let entry: FullLyricsCacheEntry

        init(_ entry: FullLyricsCacheEntry) {
            self.entry = entry
        }
    }

    private let ioQueue = DispatchQueue(
        label: "com.musicblecontroller.full-lyrics-cache",
        qos: .utility
    )
    private let directoryURL: URL
    private let maximumEntryCount: Int
    private let maximumDiskBytes: Int64
    private let memoryCache = NSCache<NSString, EntryBox>()

    init(
        directoryURL: URL? = nil,
        maximumEntryCount: Int = 80,
        maximumDiskBytes: Int64 = 8 * 1024 * 1024
    ) {
        if let directoryURL {
            self.directoryURL = directoryURL
        } else {
            let baseURL = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first ?? FileManager.default.temporaryDirectory
            self.directoryURL = baseURL
                .appendingPathComponent("MusicBleController", isDirectory: true)
                .appendingPathComponent("FullLyricsCache-v1", isDirectory: true)
        }
        self.maximumEntryCount = max(maximumEntryCount, 1)
        self.maximumDiskBytes = max(maximumDiskBytes, 64 * 1024)
        memoryCache.countLimit = 16
        memoryCache.totalCostLimit = 2 * 1024 * 1024
    }

    func load(
        trackId: String,
        title: String,
        artist: String,
        now: Date = Date(),
        completion: @escaping (FullLyricsCacheEntry?) -> Void
    ) {
        guard !trackId.isEmpty else {
            DispatchQueue.main.async { completion(nil) }
            return
        }
        let key = trackId as NSString
        if let cached = memoryCache.object(forKey: key)?.entry,
           isUsable(cached, title: title, artist: artist, now: now) {
            DispatchQueue.main.async { completion(cached) }
            return
        }

        ioQueue.async { [weak self] in
            guard let self else { return }
            let fileURL = self.fileURL(for: trackId)
            let entry = Self.readEntry(
                fileURL: fileURL,
                expectedTrackId: trackId,
                now: now
            )
            let usable = entry.flatMap {
                self.isUsable($0, title: title, artist: artist, now: now) ? $0 : nil
            }
            if let usable {
                self.memoryCache.setObject(
                    EntryBox(usable),
                    forKey: key,
                    cost: Self.estimatedCost(usable)
                )
                try? FileManager.default.setAttributes(
                    [.modificationDate: now],
                    ofItemAtPath: fileURL.path
                )
            } else {
                try? FileManager.default.removeItem(at: fileURL)
            }
            DispatchQueue.main.async {
                completion(usable)
            }
        }
    }

    func save(_ entry: FullLyricsCacheEntry) {
        guard entry.isValid else { return }
        memoryCache.setObject(
            EntryBox(entry),
            forKey: entry.trackId as NSString,
            cost: Self.estimatedCost(entry)
        )
        ioQueue.async { [weak self] in
            guard let self else { return }
            do {
                try FileManager.default.createDirectory(
                    at: self.directoryURL,
                    withIntermediateDirectories: true
                )
                let data = try JSONEncoder().encode(entry)
                try data.write(to: self.fileURL(for: entry.trackId), options: .atomic)
                self.prune(now: Date())
            } catch {
                // A cache failure must never block live lyrics. A later stable
                // full-lyrics result will try again.
            }
        }
    }

    func remove(trackId: String) {
        guard !trackId.isEmpty else { return }
        memoryCache.removeObject(forKey: trackId as NSString)
        ioQueue.async { [weak self] in
            guard let self else { return }
            try? FileManager.default.removeItem(at: self.fileURL(for: trackId))
        }
    }

    static func readEntry(
        fileURL: URL,
        expectedTrackId: String,
        now: Date = Date()
    ) -> FullLyricsCacheEntry? {
        guard let data = try? Data(contentsOf: fileURL),
              let entry = try? JSONDecoder().decode(FullLyricsCacheEntry.self, from: data),
              entry.isValid,
              entry.trackId == expectedTrackId,
              now.timeIntervalSince(entry.savedAt) >= 0,
              now.timeIntervalSince(entry.savedAt) <= FullLyricsCacheEntry.maximumAge else {
            return nil
        }
        return entry
    }

    private func isUsable(
        _ entry: FullLyricsCacheEntry,
        title: String,
        artist: String,
        now: Date
    ) -> Bool {
        entry.isValid &&
            now.timeIntervalSince(entry.savedAt) >= 0 &&
            now.timeIntervalSince(entry.savedAt) <= FullLyricsCacheEntry.maximumAge &&
            entry.matches(title: title, artist: artist)
    }

    private func fileURL(for trackId: String) -> URL {
        let digest = SHA256.hash(data: Data(trackId.utf8))
        let filename = digest.map { String(format: "%02x", $0) }.joined() + ".json"
        return directoryURL.appendingPathComponent(filename)
    }

    private func prune(now: Date) {
        let keys: Set<URLResourceKey> = [
            .isRegularFileKey,
            .contentModificationDateKey,
            .fileSizeKey
        ]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        var records: [(url: URL, date: Date, size: Int64)] = []
        for url in urls where url.pathExtension == "json" {
            guard let values = try? url.resourceValues(forKeys: keys),
                  values.isRegularFile == true else {
                continue
            }
            let date = values.contentModificationDate ?? .distantPast
            if now.timeIntervalSince(date) > FullLyricsCacheEntry.maximumAge {
                try? FileManager.default.removeItem(at: url)
                continue
            }
            records.append((url, date, Int64(values.fileSize ?? 0)))
        }
        records.sort { $0.date > $1.date }
        var retainedBytes: Int64 = 0
        for (index, record) in records.enumerated() {
            let exceedsCount = index >= maximumEntryCount
            let exceedsBytes = retainedBytes + record.size > maximumDiskBytes
            if exceedsCount || exceedsBytes {
                try? FileManager.default.removeItem(at: record.url)
            } else {
                retainedBytes += record.size
            }
        }
    }

    static func estimatedCost(_ entry: FullLyricsCacheEntry) -> Int {
        var totalCost: Int = 0
        for line in entry.lines {
            var lineCost: Int = line.text.utf8.count
            if let translation = line.translation {
                lineCost += translation.utf8.count
            }
            if let romanization = line.romanization {
                lineCost += romanization.utf8.count
            }
            for word in line.words {
                lineCost += word.text.utf8.count
                lineCost += 32
            }
            totalCost += lineCost
        }
        return totalCost
    }
}
