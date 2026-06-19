import Foundation
import UIKit

final class LiveActivityArtworkStore {
    static let shared = LiveActivityArtworkStore()

    private let fileManager = FileManager.default
    private let cleanupQueue = DispatchQueue(
        label: "com.sqz.IOSBleFeasibility.LiveActivityArtworkCleanup",
        qos: .utility
    )
    private let maxCachedFiles = 50
    private let thumbnailSize = CGSize(width: 80, height: 80)
    private let jpegQuality: CGFloat = 0.78

    private init() {}

    func writeThumbnail(
        image: UIImage,
        key: String,
        revision: Int,
        logger: ((String) -> Void)? = nil
    ) -> Bool {
        logger?("[LiveArtwork] write start key=\(key) revision=\(revision)")
        guard let directoryURL = artworkDirectoryURL(logger: logger) else {
            logger?("[LiveArtwork] file validation failed reason=container unavailable")
            return false
        }

        do {
            try fileManager.createDirectory(
                at: directoryURL,
                withIntermediateDirectories: true
            )
            guard let data = thumbnailJPEGData(from: image) else {
                logger?("[LiveArtwork] file validation failed reason=encode failed")
                return false
            }
            guard data.count > 0, data.count < 20_000 else {
                logger?(
                    "[LiveArtwork] file validation failed reason=size bytes=\(data.count)"
                )
                return false
            }

            let fileURL = directoryURL.appendingPathComponent(
                LiveActivitySharedConstants.artworkFileName(
                    key: key,
                    revision: revision
                )
            )
            try data.write(to: fileURL, options: .atomic)

            let exists = fileManager.fileExists(atPath: fileURL.path)
            let savedData = try Data(contentsOf: fileURL)
            let decoded = UIImage(data: savedData) != nil
            logger?(
                "[LiveArtwork] write success path=\(fileURL.path) bytes=\(savedData.count)"
            )
            logger?(
                "[LiveArtwork] validate exists=\(exists) decode=\(decoded)"
            )
            guard exists, !savedData.isEmpty, decoded else {
                logger?("[LiveArtwork] file validation failed reason=decode")
                return false
            }

            cleanupOldFiles(keeping: fileURL, logger: logger)
            return true
        } catch {
            logger?("[LiveArtwork] file validation failed reason=\(error.localizedDescription)")
            return false
        }
    }

    func removeAll(logger: ((String) -> Void)? = nil) {
        guard let directoryURL = artworkDirectoryURL(logger: logger) else { return }
        cleanupQueue.async { [fileManager] in
            guard let urls = try? fileManager.contentsOfDirectory(
                at: directoryURL,
                includingPropertiesForKeys: nil
            ) else { return }
            urls.forEach { try? fileManager.removeItem(at: $0) }
        }
    }

    private func artworkDirectoryURL(logger: ((String) -> Void)?) -> URL? {
        let containerURL = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier:
                LiveActivitySharedConstants.appGroupIdentifier
        )
        logger?(
            "[LiveArtwork] group container=\(containerURL?.path ?? "nil")"
        )
        return containerURL?.appendingPathComponent(
            LiveActivitySharedConstants.artworkDirectoryName,
            isDirectory: true
        )
    }

    private func thumbnailJPEGData(from image: UIImage) -> Data? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: thumbnailSize, format: format)
        let rendered = renderer.image { _ in
            let sourceSize = image.size
            guard sourceSize.width > 0, sourceSize.height > 0 else { return }
            let scale = max(
                thumbnailSize.width / sourceSize.width,
                thumbnailSize.height / sourceSize.height
            )
            let drawSize = CGSize(
                width: sourceSize.width * scale,
                height: sourceSize.height * scale
            )
            let origin = CGPoint(
                x: (thumbnailSize.width - drawSize.width) / 2,
                y: (thumbnailSize.height - drawSize.height) / 2
            )
            image.draw(in: CGRect(origin: origin, size: drawSize))
        }
        return rendered.jpegData(compressionQuality: jpegQuality)
    }

    private func cleanupOldFiles(
        keeping currentURL: URL,
        logger: ((String) -> Void)?
    ) {
        cleanupQueue.async { [fileManager, maxCachedFiles] in
            guard let urls = try? fileManager.contentsOfDirectory(
                at: currentURL.deletingLastPathComponent(),
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles]
            ), urls.count > maxCachedFiles else {
                return
            }

            let sorted = urls.sorted { lhs, rhs in
                let lhsDate = (
                    try? lhs.resourceValues(
                        forKeys: [.contentModificationDateKey]
                    ).contentModificationDate
                ) ?? Date.distantPast
                let rhsDate = (
                    try? rhs.resourceValues(
                        forKeys: [.contentModificationDateKey]
                    ).contentModificationDate
                ) ?? Date.distantPast
                return lhsDate < rhsDate
            }

            let removable = sorted.prefix(max(0, urls.count - maxCachedFiles))
            removable.forEach { url in
                guard url != currentURL else { return }
                try? fileManager.removeItem(at: url)
            }
            logger?("[LiveArtwork] cleanup checked files=\(urls.count)")
        }
    }
}
