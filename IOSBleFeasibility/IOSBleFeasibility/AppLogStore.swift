import Foundation

final class AppLogStore {
    static let shared = AppLogStore()

    private let queue = DispatchQueue(label: "com.sqz.IOSBleFeasibility.AppLogStore")
    private let fileManager = FileManager.default
    private let maxLogBytes: UInt64 = 2 * 1024 * 1024
    private let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    private init() {}

    var logsDirectoryURL: URL {
        documentsDirectoryURL
            .appendingPathComponent("Logs", isDirectory: true)
    }

    var currentLogURL: URL {
        logsDirectoryURL.appendingPathComponent("ios_ble.log")
    }

    var oldLogURL: URL {
        logsDirectoryURL.appendingPathComponent("ios_ble.old.log")
    }

    var timelineLogURL: URL {
        logsDirectoryURL.appendingPathComponent("ios_lyrics_timeline.log")
    }

    func append(_ message: String) {
        queue.async { [weak self] in
            self?.appendOnQueue(message)
        }
    }

    func appendTimeline(_ message: String) {
        queue.async { [weak self] in
            self?.appendTimelineOnQueue(message)
        }
    }

    func readRecentText(completion: @escaping (String) -> Void) {
        queue.async { [weak self] in
            guard let self else {
                DispatchQueue.main.async { completion("") }
                return
            }
            let text = self.readTextOnQueue()
            DispatchQueue.main.async {
                completion(text)
            }
        }
    }

    func clear(completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else {
                DispatchQueue.main.async { completion?() }
                return
            }
            try? self.fileManager.removeItem(at: self.currentLogURL)
            try? self.fileManager.removeItem(at: self.oldLogURL)
            try? self.fileManager.removeItem(at: self.timelineLogURL)
            DispatchQueue.main.async {
                completion?()
            }
        }
    }

    func clearTimeline(completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else {
                DispatchQueue.main.async { completion?() }
                return
            }
            try? self.fileManager.removeItem(at: self.timelineLogURL)
            DispatchQueue.main.async {
                completion?()
            }
        }
    }

    func currentLogFileExists() -> Bool {
        fileManager.fileExists(atPath: currentLogURL.path)
    }

    private var documentsDirectoryURL: URL {
        fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private func appendOnQueue(_ message: String) {
        do {
            try ensureLogsDirectory()
            try rollIfNeeded()
            let line = formattedLine(for: message)
            guard let data = line.data(using: .utf8) else { return }
            if !fileManager.fileExists(atPath: currentLogURL.path) {
                fileManager.createFile(atPath: currentLogURL.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: currentLogURL)
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            try handle.close()
        } catch {
            print("[AppLogStore] append failed error=\(error.localizedDescription)")
        }
    }

    private func appendTimelineOnQueue(_ message: String) {
        do {
            try ensureLogsDirectory()
            let line = formattedLine(for: message)
            guard let data = line.data(using: .utf8) else { return }
            if !fileManager.fileExists(atPath: timelineLogURL.path) {
                fileManager.createFile(atPath: timelineLogURL.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: timelineLogURL)
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            try handle.close()
        } catch {
            print("[AppLogStore] append timeline failed error=\(error.localizedDescription)")
        }
    }

    private func readTextOnQueue() -> String {
        let urls = [oldLogURL, currentLogURL]
        return urls.compactMap { url in
            guard let data = try? Data(contentsOf: url),
                  !data.isEmpty else {
                return nil
            }
            return String(data: data, encoding: .utf8)
        }
        .joined(separator: "\n")
    }

    private func ensureLogsDirectory() throws {
        if !fileManager.fileExists(atPath: logsDirectoryURL.path) {
            try fileManager.createDirectory(
                at: logsDirectoryURL,
                withIntermediateDirectories: true
            )
        }
    }

    private func rollIfNeeded() throws {
        guard let attributes = try? fileManager.attributesOfItem(
            atPath: currentLogURL.path
        ),
            let fileSize = attributes[.size] as? NSNumber,
            fileSize.uint64Value >= maxLogBytes else {
            return
        }

        try? fileManager.removeItem(at: oldLogURL)
        try fileManager.moveItem(at: currentLogURL, to: oldLogURL)
    }

    private func formattedLine(for message: String) -> String {
        let timestamp = timestampFormatter.string(from: Date())
        if message.first == "[",
           message.firstIndex(of: "]") != nil {
            return "\(timestamp) \(message)\n"
        }
        return "\(timestamp) [App] \(message)\n"
    }
}
