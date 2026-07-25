import Foundation
import ImageIO
import UIKit

/// Decoded artwork cache plus the utility queue used for artwork disk I/O.
///
/// CoreBluetooth callbacks use the main queue in this app, so neither file
/// reads nor ImageIO decoding should run inline with a notification callback.
final class ArtworkImageCache {
    static let shared = ArtworkImageCache()

    static let mainArtworkMaximumPixelSize = 780
    static let historyArtworkMaximumPixelSize = 128

    private let images = NSCache<NSString, UIImage>()
    private let ioQueue = DispatchQueue(
        label: "com.sqz.IOSBleFeasibility.artworkImageCache",
        qos: .utility
    )
    private var memoryWarningObserver: NSObjectProtocol?

    private init() {
        images.countLimit = 40
        images.totalCostLimit = 32 * 1_024 * 1_024
        memoryWarningObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.images.removeAllObjects()
        }
    }

    deinit {
        if let memoryWarningObserver {
            NotificationCenter.default.removeObserver(memoryWarningObserver)
        }
    }

    func memoryImage(
        artworkId: String,
        quality: String,
        maximumPixelSize: Int
    ) -> UIImage? {
        images.object(
            forKey: cacheKey(
                artworkId: artworkId,
                quality: quality,
                maximumPixelSize: maximumPixelSize
            )
        )
    }

    func store(
        _ image: UIImage,
        artworkId: String,
        quality: String,
        maximumPixelSize: Int
    ) {
        let cost = max(image.pixelWidth, 1) * max(image.pixelHeight, 1) * 4
        images.setObject(
            image,
            forKey: cacheKey(
                artworkId: artworkId,
                quality: quality,
                maximumPixelSize: maximumPixelSize
            ),
            cost: cost
        )
    }

    func load(
        artworkId: String,
        quality: String,
        fileURL: URL,
        maximumPixelSize: Int,
        completion: @escaping (Data?, UIImage?) -> Void
    ) {
        if let image = memoryImage(
            artworkId: artworkId,
            quality: quality,
            maximumPixelSize: maximumPixelSize
        ) {
            DispatchQueue.main.async {
                completion(nil, image)
            }
            return
        }

        ioQueue.async { [weak self] in
            guard let self else { return }
            let data = try? Data(contentsOf: fileURL, options: [.mappedIfSafe])
            let image = data.flatMap {
                Self.downsampledImage(data: $0, maximumPixelSize: maximumPixelSize)
            }
            if let image {
                self.store(
                    image,
                    artworkId: artworkId,
                    quality: quality,
                    maximumPixelSize: maximumPixelSize
                )
            }
            DispatchQueue.main.async {
                completion(data, image)
            }
        }
    }

    func load(
        artworkId: String,
        quality: String,
        fileURL: URL,
        maximumPixelSize: Int
    ) async -> UIImage? {
        await withCheckedContinuation { continuation in
            load(
                artworkId: artworkId,
                quality: quality,
                fileURL: fileURL,
                maximumPixelSize: maximumPixelSize
            ) { _, image in
                continuation.resume(returning: image)
            }
        }
    }

    func decode(
        data: Data,
        artworkId: String,
        quality: String,
        maximumPixelSize: Int,
        completion: @escaping (UIImage?) -> Void
    ) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            let image = Self.downsampledImage(
                data: data,
                maximumPixelSize: maximumPixelSize
            )
            if let image {
                self.store(
                    image,
                    artworkId: artworkId,
                    quality: quality,
                    maximumPixelSize: maximumPixelSize
                )
            }
            DispatchQueue.main.async {
                completion(image)
            }
        }
    }

    func performIO(_ work: @escaping () -> Void) {
        ioQueue.async(execute: work)
    }

    func removeAllDecodedImages() {
        images.removeAllObjects()
    }

    static func downsampledImage(data: Data, maximumPixelSize: Int) -> UIImage? {
        guard maximumPixelSize > 0,
              let source = CGImageSourceCreateWithData(
                data as CFData,
                [kCGImageSourceShouldCache: false] as CFDictionary
              ),
              let cgImage = CGImageSourceCreateThumbnailAtIndex(
                source,
                0,
                [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: maximumPixelSize,
                    kCGImageSourceShouldCacheImmediately: true
                ] as CFDictionary
              ) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }

    private func cacheKey(
        artworkId: String,
        quality: String,
        maximumPixelSize: Int
    ) -> NSString {
        "\(artworkId)|\(quality)|\(maximumPixelSize)" as NSString
    }
}
