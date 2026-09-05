import Foundation
@preconcurrency import MetricKit
import OSLog

final class AppLogStore {
    static let shared = AppLogStore()

    private let queue = DispatchQueue(label: "com.sqz.IOSBleFeasibility.AppLogStore")
    private let fileManager = FileManager.default
    private let maxLogBytes: UInt64 = 2 * 1024 * 1024
    private let flushInterval: TimeInterval = 0.35
    private let immediateFlushBytes = 64 * 1024
    private var currentBuffer = Data()
    private var timelineBuffer = Data()
    private var currentHandle: FileHandle?
    private var timelineHandle: FileHandle?
    private var flushWorkItem: DispatchWorkItem?
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
            self.flushOnQueue()
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
            self.discardBufferedLogsOnQueue()
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
            self.timelineBuffer.removeAll(keepingCapacity: true)
            try? self.timelineHandle?.close()
            self.timelineHandle = nil
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
        let line = formattedLine(for: message)
        guard let data = line.data(using: .utf8) else { return }
        currentBuffer.append(data)
        scheduleFlushOnQueue()
    }

    private func appendTimelineOnQueue(_ message: String) {
        let line = formattedLine(for: message)
        guard let data = line.data(using: .utf8) else { return }
        timelineBuffer.append(data)
        scheduleFlushOnQueue()
    }

    private func scheduleFlushOnQueue() {
        if currentBuffer.count + timelineBuffer.count >= immediateFlushBytes {
            flushWorkItem?.cancel()
            flushWorkItem = nil
            flushOnQueue()
            return
        }
        guard flushWorkItem == nil else { return }
        let item = DispatchWorkItem { [weak self] in
            self?.flushWorkItem = nil
            self?.flushOnQueue()
        }
        flushWorkItem = item
        queue.asyncAfter(deadline: .now() + flushInterval, execute: item)
    }

    private func flushOnQueue() {
        flushWorkItem?.cancel()
        flushWorkItem = nil
        do {
            try ensureLogsDirectory()
            if !currentBuffer.isEmpty {
                try rollIfNeeded(additionalBytes: currentBuffer.count)
                let handle = try writableHandle(
                    existing: currentHandle,
                    url: currentLogURL
                )
                currentHandle = handle
                try handle.write(contentsOf: currentBuffer)
                currentBuffer.removeAll(keepingCapacity: true)
            }
            if !timelineBuffer.isEmpty {
                let handle = try writableHandle(
                    existing: timelineHandle,
                    url: timelineLogURL
                )
                timelineHandle = handle
                try handle.write(contentsOf: timelineBuffer)
                timelineBuffer.removeAll(keepingCapacity: true)
            }
        } catch {
            print("[AppLogStore] flush failed error=\(error.localizedDescription)")
        }
    }

    private func writableHandle(existing: FileHandle?, url: URL) throws -> FileHandle {
        if let existing {
            return existing
        }
        if !fileManager.fileExists(atPath: url.path) {
            fileManager.createFile(atPath: url.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        return handle
    }

    private func discardBufferedLogsOnQueue() {
        flushWorkItem?.cancel()
        flushWorkItem = nil
        currentBuffer.removeAll(keepingCapacity: true)
        timelineBuffer.removeAll(keepingCapacity: true)
        try? currentHandle?.close()
        try? timelineHandle?.close()
        currentHandle = nil
        timelineHandle = nil
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

    private func rollIfNeeded(additionalBytes: Int) throws {
        guard let attributes = try? fileManager.attributesOfItem(
            atPath: currentLogURL.path
        ),
            let fileSize = attributes[.size] as? NSNumber,
            fileSize.uint64Value + UInt64(additionalBytes) >= maxLogBytes else {
            return
        }

        try? currentHandle?.close()
        currentHandle = nil
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

enum AppPerformanceLog {
    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.sqz.IOSBleFeasibility"

    static let connection = Logger(subsystem: subsystem, category: "BLE.Connection")
    static let protocolLog = Logger(subsystem: subsystem, category: "BLE.Protocol")
    static let lyrics = Logger(subsystem: subsystem, category: "Media.Lyrics")
    static let artwork = Logger(subsystem: subsystem, category: "Media.Artwork")
    static let liveActivity = Logger(subsystem: subsystem, category: "LiveActivity")
    static let ui = Logger(subsystem: subsystem, category: "UI.Responsiveness")

    static let protocolSignposter = OSSignposter(logger: protocolLog)
    static let artworkSignposter = OSSignposter(logger: artwork)
    static let liveActivitySignposter = OSSignposter(logger: liveActivity)
}

final class MetricDiagnosticsSubscriber: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    static let shared = MetricDiagnosticsSubscriber()

    private let lock = NSLock()
    private var started = false

    func start() {
        lock.lock()
        guard !started else {
            lock.unlock()
            return
        }
        started = true
        lock.unlock()
        MXMetricManager.shared.add(self)
        AppPerformanceLog.ui.info("MetricKit subscriber started")
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        AppLogStore.shared.append("[MetricKit] metric payloads=\(payloads.count)")
        payloads.forEach { payload in
            AppPerformanceLog.ui.info(
                "MetricKit metric payload bytes=\(payload.jsonRepresentation().count)"
            )
        }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        AppLogStore.shared.append("[MetricKit] diagnostic payloads=\(payloads.count)")
        payloads.forEach { payload in
            AppPerformanceLog.ui.error(
                "MetricKit diagnostic payload bytes=\(payload.jsonRepresentation().count)"
            )
        }
    }
}
