import Foundation
import UIKit

/// A finite lease for BLE wake-up work and its asynchronous publication.
final class BoundedBackgroundTask {
    private var identifier: UIBackgroundTaskIdentifier = .invalid
    private var deadline: DispatchWorkItem?
    var isActive: Bool { identifier != .invalid }

    init(name: String) {
        guard UIApplication.shared.applicationState != .active else { return }
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            self?.end()
        }
        guard isActive else { return }
        let item = DispatchWorkItem { [weak self] in self?.end() }
        deadline = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: item)
    }

    func end() {
        deadline?.cancel()
        deadline = nil
        guard isActive else { return }
        let task = identifier
        identifier = .invalid
        UIApplication.shared.endBackgroundTask(task)
    }

    deinit { end() }
}

struct LiveActivityArtworkWriteResult: Sendable {
    let succeeded: Bool
    let messages: [String]
}

actor LiveActivityArtworkFileWriter {
    private let maxCachedFiles = 50
    private let directoryOverride: URL?

    init(directoryOverride: URL? = nil) { self.directoryOverride = directoryOverride }

    private func directoryURL(fileManager: FileManager) -> URL? {
        if let directoryOverride { return directoryOverride }
        return fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: LiveActivitySharedConstants.appGroupIdentifier
        )?.appendingPathComponent(LiveActivitySharedConstants.artworkDirectoryName, isDirectory: true)
    }

    func write(data: Data, key: String, revision: Int) -> LiveActivityArtworkWriteResult {
        var messages = ["[LiveArtwork] write start key=\(key) revision=\(revision)"]
        let fileManager = FileManager.default
        guard let directoryURL = directoryURL(fileManager: fileManager) else {
            messages.append("[LiveArtwork] file validation failed reason=container unavailable")
            return LiveActivityArtworkWriteResult(succeeded: false, messages: messages)
        }

        messages.append("[LiveArtwork] group container=\(directoryURL.deletingLastPathComponent().path)")

        do {
            try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
            guard !data.isEmpty, data.count < 20_000 else {
                messages.append("[LiveArtwork] file validation failed reason=size bytes=\(data.count)")
                return LiveActivityArtworkWriteResult(succeeded: false, messages: messages)
            }

            let fileURL = directoryURL.appendingPathComponent(
                LiveActivitySharedConstants.artworkFileName(key: key, revision: revision)
            )
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            guard fileManager.fileExists(atPath: fileURL.path) else {
                messages.append("[LiveArtwork] file validation failed reason=file missing")
                return LiveActivityArtworkWriteResult(succeeded: false, messages: messages)
            }

            messages.append("[LiveArtwork] write success path=\(fileURL.path) bytes=\(data.count)")
            cleanupOldFiles(in: directoryURL, keeping: fileURL, fileManager: fileManager)
            return LiveActivityArtworkWriteResult(succeeded: true, messages: messages)
        } catch {
            messages.append("[LiveArtwork] file validation failed reason=\(error.localizedDescription)")
            return LiveActivityArtworkWriteResult(succeeded: false, messages: messages)
        }
    }

    func removeAll() {
        let fileManager = FileManager.default
        guard let directoryURL = directoryURL(fileManager: fileManager) else { return }
        guard let urls = try? fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil
        ) else { return }
        urls.forEach { try? fileManager.removeItem(at: $0) }
    }

    private func cleanupOldFiles(
        in directoryURL: URL,
        keeping currentURL: URL,
        fileManager: FileManager
    ) {
        guard let urls = try? fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ), urls.count > maxCachedFiles else { return }

        let sorted = urls.sorted { lhs, rhs in
            let lhsDate = (try? lhs.resourceValues(
                forKeys: [.contentModificationDateKey]
            ).contentModificationDate) ?? .distantPast
            let rhsDate = (try? rhs.resourceValues(
                forKeys: [.contentModificationDateKey]
            ).contentModificationDate) ?? .distantPast
            return lhsDate < rhsDate
        }
        sorted.prefix(max(0, urls.count - maxCachedFiles)).forEach { url in
            guard url != currentURL else { return }
            try? fileManager.removeItem(at: url)
        }
    }
}

final class LiveActivityArtworkStore: @unchecked Sendable {
    static let shared = LiveActivityArtworkStore()

    private final class ImageBox: @unchecked Sendable {
        let image: UIImage
        init(_ image: UIImage) { self.image = image }
    }

    private let encodeQueue = DispatchQueue(
        label: "com.sqz.IOSBleFeasibility.LiveActivityArtworkEncode",
        qos: .utility
    )
    private let writer = LiveActivityArtworkFileWriter()
    private let thumbnailSize = CGSize(width: 80, height: 80)
    private let jpegQuality: CGFloat = 0.78

    private init() {}

    func writeThumbnail(
        image: UIImage,
        key: String,
        revision: Int,
        completion: @escaping @MainActor @Sendable (LiveActivityArtworkWriteResult) -> Void
    ) {
        let imageBox = ImageBox(image)
        let thumbnailSize = thumbnailSize
        let jpegQuality = jpegQuality
        let writer = writer
        encodeQueue.async {
            let data = Self.thumbnailJPEGData(
                from: imageBox.image,
                size: thumbnailSize,
                jpegQuality: jpegQuality
            )
            guard let data else {
                Task { @MainActor in
                    completion(
                        LiveActivityArtworkWriteResult(
                            succeeded: false,
                            messages: ["[LiveArtwork] file validation failed reason=encode failed"]
                        )
                    )
                }
                return
            }
            Task {
                let result = await writer.write(data: data, key: key, revision: revision)
                await completion(result)
            }
        }
    }

    func removeAll() {
        Task { await writer.removeAll() }
    }

    private static func thumbnailJPEGData(
        from image: UIImage,
        size: CGSize,
        jpegQuality: CGFloat
    ) -> Data? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let rendered = renderer.image { _ in
            let sourceSize = image.size
            guard sourceSize.width > 0, sourceSize.height > 0 else { return }
            let scale = max(size.width / sourceSize.width, size.height / sourceSize.height)
            let drawSize = CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)
            let origin = CGPoint(
                x: (size.width - drawSize.width) / 2,
                y: (size.height - drawSize.height) / 2
            )
            image.draw(in: CGRect(origin: origin, size: drawSize))
        }
        return rendered.jpegData(compressionQuality: jpegQuality)
    }
}
