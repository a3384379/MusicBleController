import CoreImage
import CryptoKit
import Foundation
import UIKit

enum ArtworkDisplayQuality: Int, Comparable {
    case placeholder = 0
    case preview = 1
    case hq = 2
    case enhanced = 3

    static func < (lhs: ArtworkDisplayQuality, rhs: ArtworkDisplayQuality) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    var label: String {
        switch self {
        case .placeholder: return "placeholder"
        case .preview: return "preview"
        case .hq: return "hq"
        case .enhanced: return "enhanced"
        }
    }
}

struct ArtworkEnhancementRequest {
    let artworkId: String
    let sourceData: Data
    let sourcePixelWidth: Int
    let sourcePixelHeight: Int
    let targetPixelSize: Int
    let sharpness: Double
    let screenScale: CGFloat
}

struct ArtworkEnhancementResult {
    let artworkId: String
    let sourceHash: String
    let image: UIImage
    let fileURL: URL
    let sourcePixelSize: CGSize
    let enhancedPixelSize: CGSize
    let processingTimeMs: Int
    let edgeGainPercent: Double
    let shouldAutoDisplay: Bool
}

struct ArtworkEnhancementDebugStatus {
    var enabled: Bool = true
    var currentSource: String = "-"
    var target: String = "-"
    var targetPixelSize: Int = 780
    var sharpness: Double = 0.30
    var displayQuality: ArtworkDisplayQuality = .placeholder
    var cacheHit: Bool = false
    var lastProcessingCostMs: Int = 0
    var lastEdgeGainPercent: Double = 0
    var enhancedCacheFiles: Int = 0
    var enhancedCacheBytes: Int64 = 0
    var lastMessage: String = "-"
}

private struct ArtworkEnhancedMetadata: Codable {
    let artworkId: String
    let sourceHash: String
    let cacheKey: String
    let algorithmVersion: String
    let targetPixelSize: Int
    let sharpness: Double
    let sourcePixelWidth: Int
    let sourcePixelHeight: Int
    let enhancedPixelWidth: Int
    let enhancedPixelHeight: Int
    let bytes: Int
    let format: String
    let sha256: String
    let processingTimeMs: Int
    let edgeSharpnessOriginal: Double
    let edgeSharpnessEnhanced: Double
    let edgeGainPercent: Double
    let shouldAutoDisplay: Bool
    let createdAt: TimeInterval
}

final class ArtworkEnhancementManager {
    static let shared = ArtworkEnhancementManager()

    static let defaultSharpness: Double = 0.30
    static let defaultTargetPixelSize = 780
    static let targetPixelSizeOptions = [560, 680, 780]
    static let sharpnessOptions = [0.20, 0.30, 0.40]
    static let algorithmVersion = "lanczos-sharpen-v1"

    private let queue = DispatchQueue(label: "com.sqz.IOSBleFeasibility.artworkEnhancement", qos: .utility)
    private let ciContext = CIContext(options: [
        .workingColorSpace: CGColorSpaceCreateDeviceRGB(),
        .outputColorSpace: CGColorSpaceCreateDeviceRGB()
    ])
    private var activeRequestTokens: [String: UUID] = [:]

    private init() {}

    func normalizedTargetPixelSize(_ value: Int) -> Int {
        Self.targetPixelSizeOptions.min { abs($0 - value) < abs($1 - value) } ?? Self.defaultTargetPixelSize
    }

    func normalizedSharpness(_ value: Double) -> Double {
        Self.sharpnessOptions.min { abs($0 - value) < abs($1 - value) } ?? Self.defaultSharpness
    }

    func cachedEnhancedArtwork(
        artworkId: String,
        targetPixelSize: Int,
        sharpness: Double,
        logger: ((String) -> Void)? = nil
    ) -> ArtworkEnhancementResult? {
        let targetSize = normalizedTargetPixelSize(targetPixelSize)
        let sharpness = normalizedSharpness(sharpness)
        let latestURL = latestMetadataURL(artworkId: artworkId)
        guard let metadata = readMetadata(from: latestURL),
              metadata.algorithmVersion == Self.algorithmVersion,
              metadata.targetPixelSize == targetSize,
              abs(metadata.sharpness - sharpness) < 0.001 else {
            logger?("[ArtworkEnhance] cache miss id=\(artworkId) target=\(targetSize) sharpness=\(String(format: "%.2f", sharpness))")
            return nil
        }

        let imageURL = imageURL(cacheKey: metadata.cacheKey)
        guard let data = try? Data(contentsOf: imageURL),
              let image = UIImage(data: data) else {
            logger?("[ArtworkEnhance] cache invalid id=\(artworkId)")
            try? FileManager.default.removeItem(at: imageURL)
            try? FileManager.default.removeItem(at: latestURL)
            return nil
        }

        logger?(
            "[ArtworkEnhance] cache hit id=\(artworkId) " +
                "pixel=\(metadata.enhancedPixelWidth)x\(metadata.enhancedPixelHeight) " +
                "bytes=\(metadata.bytes)"
        )
        return ArtworkEnhancementResult(
            artworkId: artworkId,
            sourceHash: metadata.sourceHash,
            image: image,
            fileURL: imageURL,
            sourcePixelSize: CGSize(width: metadata.sourcePixelWidth, height: metadata.sourcePixelHeight),
            enhancedPixelSize: CGSize(width: metadata.enhancedPixelWidth, height: metadata.enhancedPixelHeight),
            processingTimeMs: metadata.processingTimeMs,
            edgeGainPercent: metadata.edgeGainPercent,
            shouldAutoDisplay: metadata.shouldAutoDisplay
        )
    }

    func enhance(
        request: ArtworkEnhancementRequest,
        logger: @escaping (String) -> Void,
        completion: @escaping (Result<ArtworkEnhancementResult, Error>) -> Void
    ) {
        let token = UUID()
        queue.async { [weak self] in
            guard let self else { return }
            self.activeRequestTokens[request.artworkId] = token
            let start = Date()
            do {
                let result = try self.performEnhancement(
                    request: request,
                    token: token,
                    logger: logger
                )
                DispatchQueue.main.async {
                    completion(.success(result))
                }
            } catch {
                let elapsed = Int(Date().timeIntervalSince(start) * 1_000)
                logger("[ArtworkEnhance] failed reason=\(error.localizedDescription) costMs=\(elapsed)")
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }

    func cancel(artworkId: String) {
        queue.async { [weak self] in
            self?.activeRequestTokens[artworkId] = UUID()
        }
    }

    func clearCache(completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            try? FileManager.default.removeItem(at: self.cacheDirectory())
            completion?()
        }
    }

    func clearCachedArtwork(artworkId: String, completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            let latestURL = self.latestMetadataURL(artworkId: artworkId)
            if let metadata = self.readMetadata(from: latestURL) {
                try? FileManager.default.removeItem(at: self.imageURL(cacheKey: metadata.cacheKey))
                try? FileManager.default.removeItem(at: self.metadataURL(cacheKey: metadata.cacheKey))
            }
            try? FileManager.default.removeItem(at: latestURL)
            completion?()
        }
    }

    func cacheStats() -> (files: Int, bytes: Int64) {
        let directory = cacheDirectory()
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else {
            return (0, 0)
        }
        var bytes: Int64 = 0
        var files = 0
        for url in urls where url.pathExtension.lowercased() == "jpg" {
            files += 1
            let values = try? url.resourceValues(forKeys: [.fileSizeKey])
            bytes += Int64(values?.fileSize ?? 0)
        }
        return (files, bytes)
    }

    private func performEnhancement(
        request: ArtworkEnhancementRequest,
        token: UUID,
        logger: (String) -> Void
    ) throws -> ArtworkEnhancementResult {
        let sourceHash = Self.sha256Data(request.sourceData)
        let targetSize = normalizedTargetPixelSize(request.targetPixelSize)
        let sharpness = normalizedSharpness(request.sharpness)
        let sourceWidth = request.sourcePixelWidth
        let sourceHeight = request.sourcePixelHeight
        logger("[ArtworkEnhance] request id=\(request.artworkId)")

        guard sourceWidth >= 220, sourceHeight >= 220 else {
            throw EnhancementError.skipped("source too small")
        }
        guard sourceWidth < targetSize || sourceHeight < targetSize else {
            throw EnhancementError.skipped("source already large enough")
        }

        let cacheKey = Self.sha256(
            request.artworkId +
                sourceHash +
                Self.algorithmVersion +
                "\(targetSize)" +
                String(format: "%.2f", sharpness)
        )
        if let cached = cachedResult(
            artworkId: request.artworkId,
            sourceHash: sourceHash,
            cacheKey: cacheKey
        ) {
            logger("[ArtworkEnhance] cache hit id=\(request.artworkId) pixel=\(Int(cached.enhancedPixelSize.width))x\(Int(cached.enhancedPixelSize.height))")
            return cached
        }

        try ensureNotCancelled(artworkId: request.artworkId, token: token)
        logger(
            "[ArtworkEnhance] start id=\(request.artworkId) " +
                "source=\(sourceWidth)x\(sourceHeight) target=\(targetSize)x\(targetSize) " +
                "sharpness=\(String(format: "%.2f", sharpness))"
        )

        let decodeStart = Date()
        guard let input = CIImage(
            data: request.sourceData,
            options: [.applyOrientationProperty: true]
        ) else {
            throw EnhancementError.skipped("decode failed")
        }
        logger("[ArtworkEnhance] decode costMs=\(Self.elapsedMs(since: decodeStart))")

        try ensureNotCancelled(artworkId: request.artworkId, token: token)
        let resizeStart = Date()
        let scale = CGFloat(targetSize) / max(input.extent.width, input.extent.height)
        guard let lanczos = CIFilter(name: "CILanczosScaleTransform") else {
            throw EnhancementError.failed("Lanczos filter unavailable")
        }
        lanczos.setValue(input, forKey: kCIInputImageKey)
        lanczos.setValue(scale, forKey: kCIInputScaleKey)
        lanczos.setValue(1.0, forKey: kCIInputAspectRatioKey)
        guard let resized = lanczos.outputImage else {
            throw EnhancementError.failed("resize failed")
        }
        logger("[ArtworkEnhance] resize costMs=\(Self.elapsedMs(since: resizeStart))")

        try ensureNotCancelled(artworkId: request.artworkId, token: token)
        let sharpenStart = Date()
        guard let sharpen = CIFilter(name: "CISharpenLuminance") else {
            throw EnhancementError.failed("sharpen filter unavailable")
        }
        sharpen.setValue(resized, forKey: kCIInputImageKey)
        sharpen.setValue(sharpness, forKey: kCIInputSharpnessKey)
        guard let sharpened = sharpen.outputImage else {
            throw EnhancementError.failed("sharpen failed")
        }
        logger("[ArtworkEnhance] sharpen costMs=\(Self.elapsedMs(since: sharpenStart))")

        try ensureNotCancelled(artworkId: request.artworkId, token: token)
        let renderRect = CGRect(x: 0, y: 0, width: targetSize, height: targetSize)
        guard let cgImage = ciContext.createCGImage(sharpened, from: renderRect) else {
            throw EnhancementError.failed("render failed")
        }
        let baselineStart = Date()
        guard let baselineCGImage = ciContext.createCGImage(resized, from: renderRect) else {
            throw EnhancementError.failed("baseline render failed")
        }
        let originalSharpness = Self.edgeSharpness(cgImage: baselineCGImage)
        let enhancedSharpness = Self.edgeSharpness(cgImage: cgImage)
        let edgeGain = originalSharpness > 0
            ? ((enhancedSharpness / originalSharpness) - 1.0) * 100.0
            : 0.0
        let shouldAutoDisplay = edgeGain >= 5.0
        logger(
            "[ArtworkEnhance] edge original=\(String(format: "%.4f", originalSharpness)) " +
                "enhanced=\(String(format: "%.4f", enhancedSharpness)) " +
                "gain=\(String(format: "%.2f", edgeGain))% " +
                "autoDisplay=\(shouldAutoDisplay) " +
                "costMs=\(Self.elapsedMs(since: baselineStart))"
        )

        let encodeStart = Date()
        let enhancedImage = UIImage(cgImage: cgImage, scale: request.screenScale, orientation: .up)
        guard let jpegData = enhancedImage.jpegData(compressionQuality: 0.95) else {
            throw EnhancementError.failed("encode failed")
        }
        logger("[ArtworkEnhance] encode costMs=\(Self.elapsedMs(since: encodeStart))")

        try ensureNotCancelled(artworkId: request.artworkId, token: token)
        try FileManager.default.createDirectory(
            at: cacheDirectory(),
            withIntermediateDirectories: true
        )
        let imageURL = imageURL(cacheKey: cacheKey)
        try jpegData.write(to: imageURL, options: .atomic)
        let metadata = ArtworkEnhancedMetadata(
            artworkId: request.artworkId,
            sourceHash: sourceHash,
            cacheKey: cacheKey,
            algorithmVersion: Self.algorithmVersion,
            targetPixelSize: targetSize,
            sharpness: sharpness,
            sourcePixelWidth: sourceWidth,
            sourcePixelHeight: sourceHeight,
            enhancedPixelWidth: cgImage.width,
            enhancedPixelHeight: cgImage.height,
            bytes: jpegData.count,
            format: "jpg",
            sha256: Self.sha256Data(jpegData),
            processingTimeMs: Self.elapsedMs(since: decodeStart),
            edgeSharpnessOriginal: originalSharpness,
            edgeSharpnessEnhanced: enhancedSharpness,
            edgeGainPercent: edgeGain,
            shouldAutoDisplay: shouldAutoDisplay,
            createdAt: Date().timeIntervalSince1970
        )
        let metadataData = try JSONEncoder().encode(metadata)
        try metadataData.write(to: metadataURL(cacheKey: cacheKey), options: .atomic)
        try metadataData.write(to: latestMetadataURL(artworkId: request.artworkId), options: .atomic)

        logger(
            "[ArtworkEnhance] saved bytes=\(jpegData.count) format=jpg " +
                "sha256=\(metadata.sha256)"
        )
        logger("[ArtworkEnhance] success totalCostMs=\(metadata.processingTimeMs)")
        cleanupCache(protectedCacheKey: cacheKey)
        return ArtworkEnhancementResult(
            artworkId: request.artworkId,
            sourceHash: sourceHash,
            image: enhancedImage,
            fileURL: imageURL,
            sourcePixelSize: CGSize(width: sourceWidth, height: sourceHeight),
            enhancedPixelSize: CGSize(width: cgImage.width, height: cgImage.height),
            processingTimeMs: metadata.processingTimeMs,
            edgeGainPercent: edgeGain,
            shouldAutoDisplay: shouldAutoDisplay
        )
    }

    private func cachedResult(
        artworkId: String,
        sourceHash: String,
        cacheKey: String
    ) -> ArtworkEnhancementResult? {
        let metadataURL = metadataURL(cacheKey: cacheKey)
        guard let metadata = readMetadata(from: metadataURL),
              metadata.artworkId == artworkId,
              metadata.sourceHash == sourceHash,
              let data = try? Data(contentsOf: imageURL(cacheKey: cacheKey)),
              let image = UIImage(data: data) else {
            return nil
        }
        return ArtworkEnhancementResult(
            artworkId: artworkId,
            sourceHash: metadata.sourceHash,
            image: image,
            fileURL: imageURL(cacheKey: cacheKey),
            sourcePixelSize: CGSize(width: metadata.sourcePixelWidth, height: metadata.sourcePixelHeight),
            enhancedPixelSize: CGSize(width: metadata.enhancedPixelWidth, height: metadata.enhancedPixelHeight),
            processingTimeMs: metadata.processingTimeMs,
            edgeGainPercent: metadata.edgeGainPercent,
            shouldAutoDisplay: metadata.shouldAutoDisplay
        )
    }

    private func ensureNotCancelled(artworkId: String, token: UUID) throws {
        if activeRequestTokens[artworkId] != token {
            throw EnhancementError.cancelled
        }
    }

    private func cleanupCache(protectedCacheKey: String) {
        let directory = cacheDirectory()
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        let imageURLs = urls.filter { $0.pathExtension.lowercased() == "jpg" }
        let totalBytes = imageURLs.reduce(Int64(0)) { partial, url in
            let values = try? url.resourceValues(forKeys: [.fileSizeKey])
            return partial + Int64(values?.fileSize ?? 0)
        }
        guard imageURLs.count > 500 || totalBytes > 300_000_000 else { return }

        let sorted = imageURLs.sorted {
            let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return left < right
        }
        var currentCount = imageURLs.count
        var currentBytes = totalBytes
        for url in sorted {
            let cacheKey = url.deletingPathExtension().lastPathComponent
            guard cacheKey != protectedCacheKey else { continue }
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            try? FileManager.default.removeItem(at: url)
            try? FileManager.default.removeItem(at: metadataURL(cacheKey: cacheKey))
            currentCount -= 1
            currentBytes -= size
            if currentCount <= 500 && currentBytes <= 300_000_000 {
                break
            }
        }
    }

    private func readMetadata(from url: URL) -> ArtworkEnhancedMetadata? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(ArtworkEnhancedMetadata.self, from: data)
    }

    private func cacheDirectory() -> URL {
        let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        )[0]
        return documents
            .appendingPathComponent("AlbumArtCache", isDirectory: true)
            .appendingPathComponent("Enhanced", isDirectory: true)
    }

    private func imageURL(cacheKey: String) -> URL {
        cacheDirectory()
            .appendingPathComponent(cacheKey)
            .appendingPathExtension("jpg")
    }

    private func metadataURL(cacheKey: String) -> URL {
        cacheDirectory()
            .appendingPathComponent(cacheKey)
            .appendingPathExtension("json")
    }

    private func latestMetadataURL(artworkId: String) -> URL {
        let latestKey = Self.sha256(artworkId) + "_latest_v1"
        return cacheDirectory()
            .appendingPathComponent(latestKey)
            .appendingPathExtension("json")
    }

    private static func sha256(_ value: String) -> String {
        sha256Data(Data(value.utf8))
    }

    private static func sha256Data(_ data: Data) -> String {
        SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private static func elapsedMs(since start: Date) -> Int {
        Int(Date().timeIntervalSince(start) * 1_000)
    }

    private static func edgeSharpness(cgImage: CGImage) -> Double {
        let size = 96
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: &pixels,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: size * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return 0
        }
        context.interpolationQuality = .high
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size, height: size))

        var gray = [Double](repeating: 0, count: size * size)
        for y in 0..<size {
            for x in 0..<size {
                let offset = (y * size + x) * 4
                let red = Double(pixels[offset])
                let green = Double(pixels[offset + 1])
                let blue = Double(pixels[offset + 2])
                gray[y * size + x] = 0.299 * red + 0.587 * green + 0.114 * blue
            }
        }

        var sum = 0.0
        var count = 0
        for y in 1..<(size - 1) {
            for x in 1..<(size - 1) {
                let a = gray[(y - 1) * size + (x - 1)]
                let b = gray[(y - 1) * size + x]
                let c = gray[(y - 1) * size + (x + 1)]
                let d = gray[y * size + (x - 1)]
                let f = gray[y * size + (x + 1)]
                let g = gray[(y + 1) * size + (x - 1)]
                let h = gray[(y + 1) * size + x]
                let i = gray[(y + 1) * size + (x + 1)]
                let gx = -a + c - 2 * d + 2 * f - g + i
                let gy = -a - 2 * b - c + g + 2 * h + i
                sum += sqrt(gx * gx + gy * gy)
                count += 1
            }
        }
        return count > 0 ? sum / Double(count) : 0
    }

    enum EnhancementError: LocalizedError {
        case skipped(String)
        case failed(String)
        case cancelled

        var errorDescription: String? {
            switch self {
            case .skipped(let reason):
                return reason
            case .failed(let reason):
                return reason
            case .cancelled:
                return "cancelled"
            }
        }
    }
}
