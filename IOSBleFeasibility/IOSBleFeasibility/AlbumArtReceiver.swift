import CryptoKit
import Foundation
import UIKit

private let DEBUG_ART_DIAGNOSTICS = true
private let ALBUM_ART_FIRST_CHUNK_TIMEOUT_MS: Int64 = 3_000
private let ALBUM_ART_IDLE_CHUNK_TIMEOUT_MS: Int64 = 4_000
private let ALBUM_ART_TOTAL_TIMEOUT_MS: Int64 = 10_000
private let ALBUM_ART_TRANSIENT_SOURCE_TTL_MS: Int64 = 5 * 60 * 1_000
private let ALBUM_ART_STABLE_SOURCE_TTL_MS: Int64 = 30 * 60 * 1_000
private let ALBUM_ART_SMALL_CACHE_PIXEL_LIMIT = 300

struct AlbumArtSnapshot {
    let id: String
    let image: UIImage?
    let displayQuality: ArtworkDisplayQuality
    let displayPixelWidth: Int
    let displayPixelHeight: Int
    let enhancementStatus: ArtworkEnhancementDebugStatus
    let caches: [ArtworkCacheDiagnostic]
    let hqUnavailableReason: String
    let hqUnavailableBestBytes: Int
    let hqUnavailableBestChunks: Int
    let hqUnavailableMinCandidateScale: Int
    let transfer: AlbumArtTransferDiagnosticSnapshot
    let predictive: PredictiveAlbumArtSnapshot
}

struct PredictiveAlbumArtSnapshot: Equatable {
    let lastAlbumArtId: String
    let pendingHq: Bool
    let pendingHqId: String
    let lastSkipReason: String
    let offerCount: Int
    let hqPrefetchScheduled: Int
    let hqPrefetchSent: Int
    let hqPrefetchSkippedCacheHit: Int
    let hqPrefetchSkippedInFlight: Int
    let hqPrefetchSkippedNotConnected: Int
    let hqPrefetchCancelledTrackChanged: Int
    let hqArrivedBeforeDisplayCount: Int
    let avgOfferToHqRequestMs: Int
    let avgOfferToHqReadyMs: Int
    let lastOfferToHqRequestMs: Int
    let lastOfferToHqReadyMs: Int
}

private struct AlbumArtCacheMetadata: Codable {
    let id: String
    let quality: String
    let source: String?
    let savedAt: TimeInterval?
    let pixelWidth: Int
    let pixelHeight: Int
    let bytes: Int
    let createdAt: TimeInterval

    var effectiveSavedAt: TimeInterval {
        if let savedAt, savedAt > 0 {
            return savedAt
        }
        return createdAt
    }
}

private struct AlbumArtCacheValidation {
    let image: UIImage?
    let quality: String
    let source: String
    let ageMs: Int64
    let ttlMs: Int64
    let pixelWidth: Int
    let pixelHeight: Int
    let bytes: Int
    let valid: Bool
    let expired: Bool
    let reason: String

    var shouldRefreshOnOffer: Bool {
        guard valid else { return true }
        return expired ||
            source == "notificationLargeIcon" ||
            source == "unknown" ||
            pixelWidth <= ALBUM_ART_SMALL_CACHE_PIXEL_LIMIT ||
            pixelHeight <= ALBUM_ART_SMALL_CACHE_PIXEL_LIMIT
    }

    var shouldDisplayWhileRefreshing: Bool {
        valid && !expired
    }
}

private struct AlbumArtTransferSession {
    let id: String
    let quality: String
    let totalChunks: Int
    let startedAt: Date
    var lastChunkAt: Date?
    var receivedChunks: Set<Int>
    var bytesReceived: Int

    var receivedCount: Int { receivedChunks.count }
}

extension UIImage {
    var pixelWidth: Int {
        cgImage?.width ?? Int(size.width * scale)
    }

    var pixelHeight: Int {
        cgImage?.height ?? Int(size.height * scale)
    }
}

protocol AlbumArtReceiverDelegate: AnyObject {
    var albumArtCurrentTrackID: String { get }
    var albumArtCurrentTitle: String { get }
    var albumArtConnectionStatus: String { get }
    var albumArtConnectionDisplayState: String { get }
    var albumArtConnectionHealthState: String { get }
    var albumArtCharacteristicReady: Bool { get }
    var albumArtIsBusyForHqRequest: Bool { get }

    func albumArtLog(_ message: String)
    func albumArtConsoleLog(_ message: String)
    func albumArtSendCommand(cmd: String, extra: [String: Any])
    func albumArtEffectiveHqDelay(_ delay: TimeInterval) -> (delay: TimeInterval, deferred: Bool)
    func albumArtPublishLiveArtwork(image: UIImage, key: String, reason: String)
    func albumArtClearLiveArtwork(reason: String, shouldUpdate: Bool)
    func albumArtUpdateLiveActivity(force: Bool, reason: String)
}

final class AlbumArtReceiver {
    weak var delegate: AlbumArtReceiverDelegate?
    var onStateChanged: ((AlbumArtReceiver) -> Void)?

    private(set) var albumArtImage: UIImage?
    private(set) var artworkDisplayQuality: ArtworkDisplayQuality = .placeholder
    private(set) var artworkEnhancementStatus = ArtworkEnhancementDebugStatus()
    private(set) var artworkEnhancementTargetPixelSize = 780
    private(set) var artworkEnhancementSharpness = 0.30
    private(set) var currentAlbumArtID = ""

    private var albumArtID = ""
    private var albumArtQuality = ""
    private var albumArtExpectedSize = 0
    private var albumArtExpectedChunks = 0
    private var albumArtChunks: [Int: Data] = [:]

    private var binaryAlbumArtID = ""
    private var binaryAlbumArtQuality = ""
    private var binaryAlbumArtExpectedSize = 0
    private var binaryAlbumArtExpectedChunks = 0
    private var binaryAlbumArtChunks: [Int: Data] = [:]
    private var binaryAlbumArtSession: AlbumArtTransferSession?
    private var albumArtFirstChunkTimeoutWorkItem: DispatchWorkItem?
    private var albumArtIdleChunkTimeoutWorkItem: DispatchWorkItem?
    private var albumArtTotalTimeoutWorkItem: DispatchWorkItem?

    private var albumArtTransferState = "idle"
    private var albumArtCurrentTransferQuality = "-"
    private var albumArtLastFailureReason = "-"
    private var albumArtPreviewRetryCounts: [String: Int] = [:]
    private var albumArtHqRetryCounts: [String: Int] = [:]
    private var currentCachedAlbumArtQuality = ""
    private var requestedAlbumArtKeys: Set<String> = []
    private var requestedHqAlbumArtIDs: Set<String> = []
    private var hqAlbumArtWorkItem: DispatchWorkItem?
    private var hqAlbumArtUnavailableReason = "-"
    private var hqAlbumArtUnavailableBestBytes = 0
    private var hqAlbumArtUnavailableBestChunks = 0
    private var hqAlbumArtUnavailableMinCandidateScale = 0
    private var albumArtPreviewRetryCount = 0
    private var albumArtFallbackWorkItem: DispatchWorkItem?
    private var predictiveLastAlbumArtId = ""
    private var predictivePendingHqId = ""
    private var predictiveLastSkipReason = "-"
    private var predictiveOfferCount = 0
    private var predictiveHqPrefetchScheduled = 0
    private var predictiveHqPrefetchSent = 0
    private var predictiveHqPrefetchSkippedCacheHit = 0
    private var predictiveHqPrefetchSkippedInFlight = 0
    private var predictiveHqPrefetchSkippedNotConnected = 0
    private var predictiveHqPrefetchCancelledTrackChanged = 0
    private var predictiveHqArrivedBeforeDisplayCount = 0
    private var predictiveOfferToHqRequestTotalMs = 0
    private var predictiveOfferToHqRequestCount = 0
    private var predictiveOfferToHqReadyTotalMs = 0
    private var predictiveOfferToHqReadyCount = 0
    private var predictiveLastOfferToHqRequestMs = 0
    private var predictiveLastOfferToHqReadyMs = 0
    private var predictiveOfferTimes: [String: Date] = [:]
    private var predictiveHqRequestTimes: [String: Date] = [:]
    private var sourceRefreshAttemptedAlbumArtIDs: Set<String> = []
    private var artworkEnhancementEnabled = PreferencesStore.shared.artworkEnhancementEnabled
    private var artworkEnhancementABOriginalMode = false

    init(delegate: AlbumArtReceiverDelegate?) {
        self.delegate = delegate
        loadArtworkEnhancementSettings()
        updateArtworkEnhancementStatus(message: "ready")
    }

    func handleOffer(id: String) {
        guard !id.isEmpty else {
            log("[AlbumArt] invalid offer")
            return
        }
        log("[AlbumArt] offer id=\(id)")
        log("[AlbumArtOffer] id=\(id) currentTitle=\(delegate?.albumArtCurrentTitle ?? "-")")
        recordPredictiveOffer(id: id)
        handleAlbumArtIdentity(id)
        let hqValidation = validateAlbumArtCache(id: id, preferredQuality: "hq")
        let previewValidation = validateAlbumArtCache(id: id, preferredQuality: "preview")
        let sourceRefreshAlreadyAttempted = sourceRefreshAttemptedAlbumArtIDs.contains(id)
        let hqNeedsRefresh = hqValidation.shouldRefreshOnOffer && !sourceRefreshAlreadyAttempted
        let previewNeedsRefresh = previewValidation.shouldRefreshOnOffer && !sourceRefreshAlreadyAttempted

        if let cached = cachedEnhancedAlbumArt(id: id),
           hqValidation.valid,
           !hqNeedsRefresh {
            cancelAlbumArtFallback()
            cancelHqAlbumArtRequest()
            recordPredictiveHqSkip(id: id, reason: "cache hit", category: .cacheHit)
            setAlbumArtDisplay(image: cached.image, id: id, quality: .enhanced, reason: "offer cacheHit")
            delegate?.albumArtPublishLiveArtwork(image: cached.image, key: id, reason: "enhancedCacheHit")
            let message = "[AlbumArtCache] display quality=enhanced id=\(id), skip hq request"
            log(message)
            consoleLog(message)
            log("[AlbumArtRefresh] skip reason=fresh_cache id=\(id) source=\(hqValidation.source)")
            delegate?.albumArtSendCommand(cmd: "ALBUM_ART_SKIP", extra: ["id": id, "quality": "hq"])
        } else if let image = hqValidation.image,
                  hqValidation.valid,
                  !hqNeedsRefresh {
            cancelAlbumArtFallback()
            cancelHqAlbumArtRequest()
            recordPredictiveHqSkip(id: id, reason: "cache hit", category: .cacheHit)
            setAlbumArtDisplay(image: image, id: id, quality: .hq, reason: "offer cacheHit")
            delegate?.albumArtPublishLiveArtwork(image: image, key: id, reason: "cacheHit")
            startArtworkEnhancementFromCachedHq(id: id, reason: "hq cacheHit")
            let message = "[AlbumArtCache] display quality=hq id=\(id), enhance from cache"
            log(message)
            consoleLog(message)
            log("[AlbumArtRefresh] skip reason=fresh_cache id=\(id) source=\(hqValidation.source)")
            delegate?.albumArtSendCommand(cmd: "ALBUM_ART_SKIP", extra: ["id": id, "quality": "hq"])
        } else if let image = previewValidation.image,
                  previewValidation.valid,
                  !previewNeedsRefresh {
            cancelAlbumArtFallback()
            setAlbumArtDisplay(image: image, id: id, quality: .preview, reason: "offer cacheHit")
            delegate?.albumArtPublishLiveArtwork(image: image, key: id, reason: "cacheHit")
            let message = "[AlbumArtCache] display quality=preview id=\(id), schedule hq"
            log(message)
            consoleLog(message)
            schedulePredictiveHqPrefetch(id: id, delay: 0.35, reason: "preview cache")
        } else if hqValidation.valid || previewValidation.valid {
            handleStaleAlbumArtCacheOffer(
                id: id,
                hqValidation: hqValidation,
                previewValidation: previewValidation
            )
        } else {
            let message = "[AlbumArtCache] invalid id=\(id), request preview"
            log(message)
            consoleLog(message)
            currentCachedAlbumArtQuality = ""
            artworkDisplayQuality = .placeholder
            updateArtworkEnhancementStatus(message: "cache miss")
            delegate?.albumArtClearLiveArtwork(reason: "cacheMiss", shouldUpdate: false)
            requestAlbumArt(id: id, quality: "preview")
            schedulePredictiveHqPrefetch(id: id, delay: 2.2, reason: "offer cache miss")
            notifyStateChanged()
        }
    }

    func handleIdentity(id: String) {
        handleAlbumArtIdentity(id)
    }

    func handleLegacyStart(id: String, quality: String, size: Int, chunks: Int) {
        guard !id.isEmpty,
              quality == "preview" || quality == "hq" || quality == "full",
              size > 0,
              chunks > 0 else {
            resetAlbumArtTransfer()
            log("[AlbumArt] invalid start id=\(id) quality=\(quality) size=\(size) chunks=\(chunks)")
            return
        }
        handleAlbumArtIdentity(id)
        resetAlbumArtTransfer()
        albumArtID = id
        albumArtQuality = quality
        albumArtExpectedSize = size
        albumArtExpectedChunks = chunks
        log("[AlbumArt] start id=\(id) quality=\(quality) chunks=\(chunks) size=\(size)")
    }

    func handleLegacyChunk(id: String, quality: String?, index: Int, base64: String?) {
        let incomingQuality = quality ?? albumArtQuality
        guard albumArtExpectedChunks > 0,
              id == albumArtID,
              incomingQuality == albumArtQuality,
              index >= 0,
              index < albumArtExpectedChunks,
              let base64,
              let chunk = Data(base64Encoded: base64) else {
            log("[AlbumArt] invalid chunk id=\(id) index=\(index)")
            return
        }
        albumArtChunks[index] = chunk
        log("[AlbumArt] chunk index=\(index) bytes=\(chunk.count)")
    }

    func handleLegacyEnd(id: String, quality: String?) {
        let incomingQuality = quality ?? albumArtQuality
        log("[AlbumArt] end received=\(albumArtChunks.count) expected=\(albumArtExpectedChunks)")
        guard id == albumArtID,
              incomingQuality == albumArtQuality else {
            log(
                "[AlbumArt] decode failed transfer mismatch " +
                    "expected=\(albumArtID)/\(albumArtQuality) actual=\(id)/\(incomingQuality)"
            )
            resetAlbumArtTransfer()
            return
        }
        finishAlbumArtTransfer(id: id, quality: incomingQuality)
    }

    func handleBinaryStart(id: String, quality: String, size: Int, chunks: Int) {
        guard !id.isEmpty,
              quality == "preview" || quality == "hq" || quality == "full",
              size > 0,
              chunks > 0 else {
            resetBinaryAlbumArtTransfer(reason: "invalid binary start")
            log("[AlbumArtBinary] invalid start")
            return
        }
        handleAlbumArtIdentity(id)
        resetBinaryAlbumArtTransfer()
        binaryAlbumArtID = id
        binaryAlbumArtQuality = quality
        binaryAlbumArtExpectedSize = size
        binaryAlbumArtExpectedChunks = chunks
        startBinaryAlbumArtTransferSession(id: id, quality: quality, size: size, chunks: chunks)
        let message = "[AlbumArtBinary] start chunks=\(chunks)"
        log(message)
        consoleLog(message)
    }

    func handleBinaryChunk(_ data: Data) {
        guard data.count > 6 else {
            let message = "[AlbumArtBinary] invalid chunk"
            log(message)
            consoleLog(message)
            return
        }
        let bytes = [UInt8](data.prefix(6))
        let qualityCode = bytes[1]
        let index = (Int(bytes[2]) << 8) | Int(bytes[3])
        let totalChunks = (Int(bytes[4]) << 8) | Int(bytes[5])
        let quality: String
        switch qualityCode {
        case 2:
            quality = "full"
        case 3:
            quality = "hq"
        default:
            quality = "preview"
        }
        guard binaryAlbumArtExpectedChunks > 0,
              totalChunks == binaryAlbumArtExpectedChunks,
              quality == binaryAlbumArtQuality,
              index >= 0,
              index < binaryAlbumArtExpectedChunks else {
            let message = "[AlbumArtBinary] invalid chunk index=\(index)"
            log(message)
            consoleLog(message)
            return
        }
        let payload = data.subdata(in: 6..<data.count)
        binaryAlbumArtChunks[index] = payload
        if var session = binaryAlbumArtSession,
           session.quality == quality,
           session.totalChunks == totalChunks {
            session.lastChunkAt = Date()
            session.receivedChunks.insert(index)
            session.bytesReceived += payload.count
            binaryAlbumArtSession = session
            albumArtFirstChunkTimeoutWorkItem?.cancel()
            albumArtFirstChunkTimeoutWorkItem = nil
            scheduleAlbumArtIdleChunkTimeout(id: session.id, quality: quality)
        }
        let message =
            "[AlbumArtBinary] chunk index=\(index) " +
            "received=\(binaryAlbumArtChunks.count)/\(binaryAlbumArtExpectedChunks)"
        log(message)
        if index == 0 ||
            index == binaryAlbumArtExpectedChunks - 1 ||
            index % 10 == 0 {
            consoleLog(message)
        }
        notifyStateChanged()
    }

    func handleBinaryEnd(id: String, quality: String?) {
        let incomingQuality = quality ?? binaryAlbumArtQuality
        let message = "[AlbumArtBinary] end received=\(binaryAlbumArtChunks.count)"
        log(message)
        consoleLog(message)
        guard id == binaryAlbumArtID,
              incomingQuality == binaryAlbumArtQuality else {
            let failMessage = "[AlbumArtBinary] decode failed transfer mismatch"
            log(failMessage)
            consoleLog(failMessage)
            cancelBinaryAlbumArtTransfer(reason: "transfer mismatch", shouldRetryPreview: incomingQuality == "preview")
            return
        }
        finishBinaryAlbumArtTransfer(id: id, quality: incomingQuality)
    }

    func handleBinaryError(message: String) {
        let quality = binaryAlbumArtSession?.quality ?? binaryAlbumArtQuality
        cancelBinaryAlbumArtTransfer(reason: "binary error \(message)", shouldRetryPreview: quality == "preview")
        log("[AlbumArtBinary] error \(message)")
    }

    func handleUnavailable(
        id: String,
        quality: String,
        reason: String,
        bestBytes: Int,
        bestChunks: Int,
        minCandidateScale: Int
    ) {
        resetAlbumArtTransfer()
        resetBinaryAlbumArtTransfer(reason: "albumArtUnavailable")
        requestedAlbumArtKeys.remove("\(id)|\(quality)")
        if id == currentAlbumArtID,
           quality == "hq" {
            hqAlbumArtUnavailableReason = reason
            hqAlbumArtUnavailableBestBytes = bestBytes
            hqAlbumArtUnavailableBestChunks = bestChunks
            hqAlbumArtUnavailableMinCandidateScale = minCandidateScale
            log(
                "[AlbumArt-iOS] unavailable id=\(id) quality=hq " +
                    "reason=\(reason) bestBytes=\(bestBytes) " +
                    "bestChunks=\(bestChunks) minCandidateScale=\(minCandidateScale)"
            )
            log("[NowDiag] artwork hq unavailable reason=\(reason)")
        }
        if id == currentAlbumArtID,
           quality == "preview" {
            cancelAlbumArtFallback()
            albumArtImage = nil
            currentCachedAlbumArtQuality = ""
            artworkDisplayQuality = .placeholder
            updateArtworkEnhancementStatus(message: "album art unavailable")
            delegate?.albumArtClearLiveArtwork(reason: "unavailable", shouldUpdate: false)
            delegate?.albumArtUpdateLiveActivity(force: true, reason: "albumArt")
        }
        log("[AlbumArt] unavailable id=\(id) quality=\(quality) reason=\(reason)")
        notifyStateChanged()
    }

    @discardableResult
    func requestCurrentPreviewAlbumArt() -> Bool {
        let id = currentAlbumArtID
        guard !id.isEmpty else {
            log("[AlbumArt] preview request skipped reason=no artwork id")
            return false
        }
        guard delegate?.albumArtConnectionDisplayState == "connected",
              delegate?.albumArtCharacteristicReady == true else {
            log(
                "[AlbumArt] preview request skipped reason=not ready " +
                    "display=\(delegate?.albumArtConnectionDisplayState ?? "-") ready=\(delegate?.albumArtCharacteristicReady == true)"
            )
            return false
        }
        requestAlbumArt(id: id, quality: "preview")
        return true
    }

    @discardableResult
    func requestCurrentHqAlbumArt() -> Bool {
        let id = currentAlbumArtID
        guard !id.isEmpty else {
            log("[NowDiag] hq artwork request skipped reason=no artwork id")
            return false
        }
        guard delegate?.albumArtConnectionDisplayState == "connected",
              delegate?.albumArtCharacteristicReady == true else {
            log(
                "[NowDiag] request HQ skipped reason=not ready " +
                    "display=\(delegate?.albumArtConnectionDisplayState ?? "-") ready=\(delegate?.albumArtCharacteristicReady == true)"
            )
            return false
        }
        let health = delegate?.albumArtConnectionHealthState ?? "disconnected"
        guard health != "stale",
              health != "disconnected" else {
            log("[NowDiag] request HQ skipped reason=unhealthy health=\(health)")
            return false
        }
        cancelStaleBinaryAlbumArtTransferIfNeeded(reason: "request found stale transfer")
        if let session = binaryAlbumArtSession,
           session.id != id {
            cancelBinaryAlbumArtTransfer(
                reason: "transfer belongs to old id old=\(session.id) new=\(id)",
                shouldRetryPreview: false
            )
        }
        guard albumArtExpectedChunks == 0,
              binaryAlbumArtExpectedChunks == 0 else {
            log("[NowDiag] request HQ skipped reason=album art transfer in progress")
            return false
        }
        requestedHqAlbumArtIDs.remove(id)
        requestedAlbumArtKeys.remove("\(id)|hq")
        log("[NowDiag] request HQ artwork id=\(id)")
        requestHqAlbumArt(id: id)
        return true
    }

    @discardableResult
    func forceRefreshCurrentAlbumArt() -> Bool {
        let id = currentAlbumArtID
        guard !id.isEmpty else {
            log("[AlbumArtRefresh] force refresh skipped reason=no artwork id")
            return false
        }
        log("[AlbumArtRefresh] force refresh id=\(id)")
        removeCachedAlbumArt(id: id, quality: "preview", reason: "force refresh")
        removeCachedAlbumArt(id: id, quality: "hq", reason: "force refresh")
        ArtworkEnhancementManager.shared.cancel(artworkId: id)
        ArtworkEnhancementManager.shared.clearCachedArtwork(artworkId: id) { [weak self] in
            self?.log("[ArtworkEnhance] cache removed id=\(id) reason=force refresh")
        }
        requestedAlbumArtKeys.remove("\(id)|preview")
        requestedAlbumArtKeys.remove("\(id)|hq")
        requestedHqAlbumArtIDs.remove(id)
        cancelHqAlbumArtRequest()
        cancelAlbumArtFallback()
        if artworkDisplayQuality != .placeholder {
            artworkDisplayQuality = .placeholder
            albumArtImage = nil
            currentCachedAlbumArtQuality = ""
            updateArtworkEnhancementStatus(message: "force refresh")
            delegate?.albumArtClearLiveArtwork(reason: "forceRefresh", shouldUpdate: false)
            notifyStateChanged()
        }
        let requested = requestCurrentPreviewAlbumArt()
        if requested {
            schedulePredictiveHqPrefetch(id: id, delay: 2.2, reason: "force refresh")
        }
        return requested
    }

    func setArtworkEnhancementEnabled(_ enabled: Bool) {
        PreferencesStore.shared.artworkEnhancementEnabled = enabled
        artworkEnhancementEnabled = enabled
        artworkEnhancementABOriginalMode = false
        updateArtworkEnhancementStatus(message: enabled ? "enabled" : "disabled")
        log("[ArtworkEnhance] enabled=\(enabled)")
        guard enabled, !currentAlbumArtID.isEmpty else {
            notifyStateChanged()
            return
        }
        if let cached = cachedEnhancedAlbumArt(id: currentAlbumArtID) {
            setAlbumArtDisplay(image: cached.image, id: currentAlbumArtID, quality: .enhanced, reason: "enable cacheHit")
        } else {
            startArtworkEnhancementFromCachedHq(id: currentAlbumArtID, reason: "enable")
        }
    }

    func setArtworkEnhancementTargetPixelSize(_ value: Int) {
        let normalized = ArtworkEnhancementManager.shared.normalizedTargetPixelSize(value)
        artworkEnhancementTargetPixelSize = normalized
        UserDefaults.standard.set(normalized, forKey: "artworkEnhancementTargetPixelSize")
        artworkEnhancementABOriginalMode = false
        updateArtworkEnhancementStatus(message: "target=\(normalized)")
        log("[ArtworkEnhance] targetPixelSize=\(normalized)")
        if artworkEnhancementEnabled, !currentAlbumArtID.isEmpty {
            startArtworkEnhancementFromCachedHq(id: currentAlbumArtID, reason: "target changed")
        }
        notifyStateChanged()
    }

    func setArtworkEnhancementSharpness(_ value: Double) {
        let normalized = ArtworkEnhancementManager.shared.normalizedSharpness(value)
        artworkEnhancementSharpness = normalized
        UserDefaults.standard.set(normalized, forKey: "artworkEnhancementSharpness")
        artworkEnhancementABOriginalMode = false
        updateArtworkEnhancementStatus(message: String(format: "sharpness=%.2f", normalized))
        log("[ArtworkEnhance] sharpness=\(String(format: "%.2f", normalized))")
        if artworkEnhancementEnabled, !currentAlbumArtID.isEmpty {
            startArtworkEnhancementFromCachedHq(id: currentAlbumArtID, reason: "sharpness changed")
        }
        notifyStateChanged()
    }

    func clearEnhancedArtworkCache() {
        ArtworkEnhancementManager.shared.clearCache { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.updateArtworkEnhancementStatus(message: "cache cleared")
                self.log("[ArtworkEnhance] cache cleared")
                self.notifyStateChanged()
            }
        }
    }

    func rebuildCurrentEnhancedArtwork() {
        guard !currentAlbumArtID.isEmpty else {
            updateArtworkEnhancementStatus(message: "no current artwork")
            notifyStateChanged()
            return
        }
        artworkEnhancementABOriginalMode = false
        startArtworkEnhancementFromCachedHq(id: currentAlbumArtID, reason: "debug rebuild")
    }

    func toggleArtworkEnhancementABComparison() {
        guard !currentAlbumArtID.isEmpty else { return }
        artworkEnhancementABOriginalMode.toggle()
        if artworkEnhancementABOriginalMode,
           let hq = validateCachedAlbumArt(id: currentAlbumArtID, preferredQuality: "hq") {
            setAlbumArtDisplay(image: hq.image, id: currentAlbumArtID, quality: .hq, reason: "A/B original", force: true)
            updateArtworkEnhancementStatus(message: "A/B original HQ")
        } else if let cached = cachedEnhancedAlbumArt(id: currentAlbumArtID, allowLowGain: true) {
            setAlbumArtDisplay(image: cached.image, id: currentAlbumArtID, quality: .enhanced, reason: "A/B enhanced", force: true)
            updateArtworkEnhancementStatus(message: "A/B enhanced")
        } else {
            artworkEnhancementABOriginalMode = false
            updateArtworkEnhancementStatus(message: "no enhanced cache")
        }
        notifyStateChanged()
    }

    func resetForReconnect(reason: String) {
        resetAlbumArtTransfer()
        resetBinaryAlbumArtTransfer(reason: reason)
        requestedAlbumArtKeys.removeAll()
        requestedHqAlbumArtIDs.removeAll()
        cancelHqAlbumArtRequest()
        predictivePendingHqId = ""
        notifyStateChanged()
    }

    func resetForConnectionLoss(reason: String) {
        resetForReconnect(reason: reason)
    }

    func snapshot() -> AlbumArtSnapshot {
        AlbumArtSnapshot(
            id: currentAlbumArtID,
            image: albumArtImage,
            displayQuality: artworkDisplayQuality,
            displayPixelWidth: albumArtImage?.pixelWidth ?? 0,
            displayPixelHeight: albumArtImage?.pixelHeight ?? 0,
            enhancementStatus: artworkEnhancementStatus,
            caches: makeArtworkCacheDiagnostics(id: currentAlbumArtID),
            hqUnavailableReason: hqAlbumArtUnavailableReason,
            hqUnavailableBestBytes: hqAlbumArtUnavailableBestBytes,
            hqUnavailableBestChunks: hqAlbumArtUnavailableBestChunks,
            hqUnavailableMinCandidateScale: hqAlbumArtUnavailableMinCandidateScale,
            transfer: makeAlbumArtTransferDiagnosticSnapshot(),
            predictive: makePredictiveAlbumArtSnapshot()
        )
    }

    private func finishAlbumArtTransfer(id: String, quality: String) {
        let missingIndexes = (0..<albumArtExpectedChunks).filter { albumArtChunks[$0] == nil }
        log("[AlbumArt] missing indexes=\(missingIndexes)")
        guard albumArtExpectedChunks > 0,
              missingIndexes.isEmpty,
              albumArtChunks.count == albumArtExpectedChunks else {
            log("[AlbumArt] decode failed")
            resetAlbumArtTransfer()
            return
        }

        var jpegData = Data()
        jpegData.reserveCapacity(albumArtExpectedSize)
        for index in 0..<albumArtExpectedChunks {
            guard let chunk = albumArtChunks[index] else {
                log("[AlbumArt] decode failed missing chunk index=\(index)")
                resetAlbumArtTransfer()
                return
            }
            jpegData.append(chunk)
        }

        guard jpegData.count == albumArtExpectedSize else {
            log("[AlbumArt] decode failed")
            resetAlbumArtTransfer()
            return
        }

        guard let image = UIImage(data: jpegData) else {
            log("[AlbumArt] decode failed")
            resetAlbumArtTransfer()
            return
        }
        finishDecodedAlbumArt(id: id, quality: quality, data: jpegData, image: image, source: "transfer")
        resetAlbumArtTransfer()
    }

    private func finishBinaryAlbumArtTransfer(id: String, quality: String) {
        guard binaryAlbumArtExpectedChunks > 0,
              binaryAlbumArtChunks.count == binaryAlbumArtExpectedChunks else {
            let message =
                "[AlbumArtBinary] decode failed received=\(binaryAlbumArtChunks.count) " +
                "expected=\(binaryAlbumArtExpectedChunks)"
            log(message)
            consoleLog(message)
            cancelBinaryAlbumArtTransfer(reason: "end before all chunks", shouldRetryPreview: quality == "preview")
            return
        }

        var jpegData = Data()
        jpegData.reserveCapacity(binaryAlbumArtExpectedSize)
        for index in 0..<binaryAlbumArtExpectedChunks {
            guard let chunk = binaryAlbumArtChunks[index] else {
                let message = "[AlbumArtBinary] decode failed missing index=\(index)"
                log(message)
                consoleLog(message)
                cancelBinaryAlbumArtTransfer(reason: "missing chunk \(index)", shouldRetryPreview: quality == "preview")
                return
            }
            jpegData.append(chunk)
        }
        guard jpegData.count == binaryAlbumArtExpectedSize,
              let image = UIImage(data: jpegData) else {
            let message = "[AlbumArtBinary] decode failed"
            log(message)
            consoleLog(message)
            cancelBinaryAlbumArtTransfer(reason: "decode failed", shouldRetryPreview: quality == "preview")
            return
        }
        finishDecodedAlbumArt(id: id, quality: quality, data: jpegData, image: image, source: "binary")
        let message = "[AlbumArtBinary] decode success bytes=\(jpegData.count)"
        log(message)
        consoleLog(message)
        let completeMessage =
            "[AlbumArt-iOS] transfer complete id=\(id) quality=\(quality) " +
            "bytes=\(jpegData.count)"
        log(completeMessage)
        consoleLog(completeMessage)
        albumArtLastFailureReason = "-"
        resetBinaryAlbumArtTransfer(reason: "complete")
    }

    private func finishDecodedAlbumArt(
        id: String,
        quality: String,
        data: Data,
        image: UIImage,
        source: String
    ) {
        if isLikelyPlaceholderAlbumArt(image, dataSize: data.count) {
            let prefix = source == "binary" ? "[AlbumArtBinary]" : "[AlbumArt]"
            let message = "\(prefix) placeholder ignored id=\(id)"
            log(message)
            if source == "binary" {
                consoleLog(message)
            }
            if id == currentAlbumArtID, quality == "preview" {
                cancelAlbumArtFallback()
                albumArtImage = nil
                currentCachedAlbumArtQuality = ""
                artworkDisplayQuality = .placeholder
                updateArtworkEnhancementStatus(message: "placeholder")
                delegate?.albumArtClearLiveArtwork(reason: "placeholder", shouldUpdate: false)
                delegate?.albumArtUpdateLiveActivity(force: true, reason: "albumArt")
                notifyStateChanged()
            }
            requestedAlbumArtKeys.remove("\(id)|\(quality)")
            if source == "binary" {
                resetBinaryAlbumArtTransfer(reason: "complete placeholder")
            }
            return
        }

        if quality == "hq", !shouldAcceptHqAlbumArt(image: image, data: data, id: id) {
            requestedAlbumArtKeys.remove("\(id)|\(quality)")
            if source == "binary" {
                resetBinaryAlbumArtTransfer(reason: "complete hq no visual upgrade")
            }
            return
        }

        saveAlbumArt(data, id: id, quality: quality, image: image)
        if id == currentAlbumArtID {
            if quality == "hq" || artworkDisplayQuality < .hq {
                cancelAlbumArtFallback()
                setAlbumArtDisplay(image: image, id: id, quality: artworkQuality(from: quality), reason: "\(source) \(quality)")
                delegate?.albumArtPublishLiveArtwork(image: image, key: id, reason: quality)
            }
            if quality == "hq" {
                startArtworkEnhancementIfNeeded(id: id, sourceData: data, sourceImage: image, reason: source == "binary" ? "binary hq received" : "hq received")
            }
        }
        writeAlbumArtDiagnostics(id: id, quality: quality, data: data, image: image)
        requestedAlbumArtKeys.remove("\(id)|\(quality)")
        log("[AlbumArt] \(quality) decode success")
        log("[AlbumArt] decode success imageSize=\(image.pixelWidth)x\(image.pixelHeight)")
        albumArtPreviewRetryCount = 0
        if quality == "preview" {
            schedulePredictiveHqPrefetch(id: id, delay: 0.05, reason: "preview complete")
        } else if quality == "hq" || quality == "full" {
            recordPredictiveHqReady(id: id)
        }
        notifyStateChanged()
    }

    private func handleAlbumArtIdentity(_ id: String) {
        guard !id.isEmpty, id != currentAlbumArtID else { return }

        if !currentAlbumArtID.isEmpty {
            ArtworkEnhancementManager.shared.cancel(artworkId: currentAlbumArtID)
            cancelPredictiveHqPrefetch(reason: "track changed", oldId: currentAlbumArtID)
        }
        currentAlbumArtID = id
        currentCachedAlbumArtQuality = ""
        artworkDisplayQuality = .placeholder
        hqAlbumArtUnavailableReason = "-"
        hqAlbumArtUnavailableBestBytes = 0
        hqAlbumArtUnavailableBestChunks = 0
        hqAlbumArtUnavailableMinCandidateScale = 0
        artworkEnhancementABOriginalMode = false
        updateArtworkEnhancementStatus(message: "track changed")
        delegate?.albumArtClearLiveArtwork(reason: "track changed", shouldUpdate: false)
        requestedAlbumArtKeys.removeAll()
        requestedHqAlbumArtIDs.removeAll()
        albumArtPreviewRetryCount = 0
        albumArtPreviewRetryCounts.removeAll()
        albumArtHqRetryCounts.removeAll()
        sourceRefreshAttemptedAlbumArtIDs.removeAll()
        resetAlbumArtTransfer()
        resetBinaryAlbumArtTransfer(reason: "track changed")
        cancelHqAlbumArtRequest()

        if let cached = loadCachedAlbumArt(id: id) {
            cancelAlbumArtFallback()
            setAlbumArtDisplay(image: cached.image, id: id, quality: artworkQuality(from: cached.quality), reason: "identity cacheHit")
            delegate?.albumArtPublishLiveArtwork(image: cached.image, key: id, reason: "cacheHit")
            if cached.quality == "hq" {
                startArtworkEnhancementFromCachedHq(id: id, reason: "identity hq cacheHit")
            }
            let message = "[AlbumArtCache] hit id=\(id)"
            log(message)
            consoleLog(message)
        } else {
            let hqValidation = validateAlbumArtCache(id: id, preferredQuality: "hq")
            let previewValidation = validateAlbumArtCache(id: id, preferredQuality: "preview")
            if (hqValidation.valid || previewValidation.valid),
               (hqValidation.shouldRefreshOnOffer || previewValidation.shouldRefreshOnOffer),
               !sourceRefreshAttemptedAlbumArtIDs.contains(id),
               isPredictiveHqConnectionReady() {
                handleStaleAlbumArtCacheOffer(
                    id: id,
                    hqValidation: hqValidation,
                    previewValidation: previewValidation
                )
                return
            }
            let message = "[AlbumArtCache] miss id=\(id)"
            log(message)
            consoleLog(message)
            scheduleAlbumArtFallback(id: id)
        }
        notifyStateChanged()
    }

    private func resetAlbumArtTransfer() {
        albumArtID = ""
        albumArtQuality = ""
        albumArtExpectedSize = 0
        albumArtExpectedChunks = 0
        albumArtChunks.removeAll()
    }

    private func resetBinaryAlbumArtTransfer(reason: String = "reset") {
        cancelAlbumArtTransferTimeouts()
        if binaryAlbumArtExpectedChunks > 0, reason != "reset" {
            let message = "[AlbumArt-iOS] cancel in-flight reason=\(reason)"
            log(message)
            consoleLog(message)
        }
        binaryAlbumArtID = ""
        binaryAlbumArtQuality = ""
        binaryAlbumArtExpectedSize = 0
        binaryAlbumArtExpectedChunks = 0
        binaryAlbumArtChunks.removeAll()
        binaryAlbumArtSession = nil
        albumArtTransferState = "idle"
        albumArtCurrentTransferQuality = "-"
        notifyStateChanged()
    }

    private func startBinaryAlbumArtTransferSession(id: String, quality: String, size: Int, chunks: Int) {
        binaryAlbumArtSession = AlbumArtTransferSession(
            id: id,
            quality: quality,
            totalChunks: chunks,
            startedAt: Date(),
            lastChunkAt: nil,
            receivedChunks: [],
            bytesReceived: 0
        )
        albumArtTransferState = "receiving"
        albumArtCurrentTransferQuality = quality
        albumArtLastFailureReason = "-"
        scheduleAlbumArtFirstChunkTimeout(id: id, quality: quality)
        scheduleAlbumArtTotalTimeout(id: id, quality: quality)
        let message =
            "[AlbumArt-iOS] transfer start id=\(id) quality=\(quality) " +
            "chunks=\(chunks) bytes=\(size)"
        log(message)
        consoleLog(message)
        notifyStateChanged()
    }

    private func cancelAlbumArtTransferTimeouts() {
        albumArtFirstChunkTimeoutWorkItem?.cancel()
        albumArtFirstChunkTimeoutWorkItem = nil
        albumArtIdleChunkTimeoutWorkItem?.cancel()
        albumArtIdleChunkTimeoutWorkItem = nil
        albumArtTotalTimeoutWorkItem?.cancel()
        albumArtTotalTimeoutWorkItem = nil
    }

    private func scheduleAlbumArtFirstChunkTimeout(id: String, quality: String) {
        albumArtFirstChunkTimeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self,
                  let session = self.binaryAlbumArtSession,
                  session.id == id,
                  session.quality == quality,
                  session.receivedCount == 0 else {
                return
            }
            self.log("[AlbumArt-iOS] first chunk timeout id=\(id) quality=\(quality)")
            self.cancelBinaryAlbumArtTransfer(reason: "first chunk timeout", shouldRetryPreview: quality == "preview")
        }
        albumArtFirstChunkTimeoutWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(ALBUM_ART_FIRST_CHUNK_TIMEOUT_MS)), execute: workItem)
    }

    private func scheduleAlbumArtIdleChunkTimeout(id: String, quality: String) {
        albumArtIdleChunkTimeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self,
                  let session = self.binaryAlbumArtSession,
                  session.id == id,
                  session.quality == quality,
                  session.receivedCount > 0 else {
                return
            }
            self.log(
                "[AlbumArt-iOS] idle chunk timeout id=\(id) " +
                    "received=\(session.receivedCount)/\(session.totalChunks)"
            )
            self.cancelBinaryAlbumArtTransfer(reason: "idle chunk timeout", shouldRetryPreview: quality == "preview")
        }
        albumArtIdleChunkTimeoutWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(ALBUM_ART_IDLE_CHUNK_TIMEOUT_MS)), execute: workItem)
    }

    private func scheduleAlbumArtTotalTimeout(id: String, quality: String) {
        albumArtTotalTimeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self,
                  let session = self.binaryAlbumArtSession,
                  session.id == id,
                  session.quality == quality else {
                return
            }
            self.log(
                "[AlbumArt-iOS] total timeout id=\(id) " +
                    "received=\(session.receivedCount)/\(session.totalChunks)"
            )
            self.cancelBinaryAlbumArtTransfer(reason: "total timeout", shouldRetryPreview: quality == "preview")
        }
        albumArtTotalTimeoutWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(ALBUM_ART_TOTAL_TIMEOUT_MS)), execute: workItem)
    }

    @discardableResult
    private func cancelStaleBinaryAlbumArtTransferIfNeeded(reason: String) -> Bool {
        guard let session = binaryAlbumArtSession else { return false }
        let now = Date()
        let elapsedMs = Int64(now.timeIntervalSince(session.startedAt) * 1_000)
        if session.receivedCount == 0, elapsedMs >= ALBUM_ART_FIRST_CHUNK_TIMEOUT_MS {
            cancelBinaryAlbumArtTransfer(reason: "\(reason): first chunk timeout", shouldRetryPreview: false)
            return true
        }
        if let lastChunkAt = session.lastChunkAt {
            let idleMs = Int64(now.timeIntervalSince(lastChunkAt) * 1_000)
            if idleMs >= ALBUM_ART_IDLE_CHUNK_TIMEOUT_MS {
                cancelBinaryAlbumArtTransfer(reason: "\(reason): idle chunk timeout", shouldRetryPreview: false)
                return true
            }
        }
        if elapsedMs >= ALBUM_ART_TOTAL_TIMEOUT_MS {
            cancelBinaryAlbumArtTransfer(reason: "\(reason): total timeout", shouldRetryPreview: false)
            return true
        }
        return false
    }

    private func cancelBinaryAlbumArtTransfer(reason: String, shouldRetryPreview: Bool) {
        guard let session = binaryAlbumArtSession else {
            resetBinaryAlbumArtTransfer(reason: reason)
            return
        }
        let received = session.receivedCount
        let total = session.totalChunks
        let id = session.id
        let quality = session.quality
        requestedAlbumArtKeys.remove("\(id)|\(quality)")
        cancelAlbumArtTransferTimeouts()
        binaryAlbumArtID = ""
        binaryAlbumArtQuality = ""
        binaryAlbumArtExpectedSize = 0
        binaryAlbumArtExpectedChunks = 0
        binaryAlbumArtChunks.removeAll()
        binaryAlbumArtSession = nil
        albumArtCurrentTransferQuality = quality
        albumArtLastFailureReason = reason
        albumArtTransferState = reason.contains("timeout") ? "timeout" : "failed"
        if quality == "hq" {
            albumArtHqRetryCounts[id] = (albumArtHqRetryCounts[id] ?? 0) + 1
        }
        let message =
            "[AlbumArt-iOS] transfer cancelled reason=\(reason) id=\(id) " +
            "quality=\(quality) received=\(received)/\(total)"
        log(message)
        consoleLog(message)
        if shouldRetryPreview, quality == "preview" {
            retryAlbumArtPreviewAfterTimeout(id: id, reason: reason)
        }
        notifyStateChanged()
    }

    private func retryAlbumArtPreviewAfterTimeout(id: String, reason: String) {
        guard id == currentAlbumArtID else { return }
        let count = albumArtPreviewRetryCounts[id] ?? 0
        guard count < 1 else {
            log("[AlbumArt-iOS] retry skipped reason=max retry reached id=\(id)")
            return
        }
        albumArtPreviewRetryCounts[id] = count + 1
        log("[AlbumArt-iOS] retry preview id=\(id) attempt=\(count + 1) reason=\(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let self,
                  self.currentAlbumArtID == id,
                  self.albumArtImage == nil else {
                return
            }
            self.requestedAlbumArtKeys.remove("\(id)|preview")
            self.requestAlbumArt(id: id, quality: "preview")
        }
    }

    private func makeAlbumArtTransferDiagnosticSnapshot() -> AlbumArtTransferDiagnosticSnapshot {
        AlbumArtTransferDiagnosticSnapshot(
            state: albumArtTransferState,
            quality: albumArtCurrentTransferQuality,
            receivedChunks: binaryAlbumArtSession?.receivedCount ?? binaryAlbumArtChunks.count,
            totalChunks: binaryAlbumArtSession?.totalChunks ?? binaryAlbumArtExpectedChunks,
            lastFailureReason: albumArtLastFailureReason,
            previewRetryCount: albumArtPreviewRetryCounts[currentAlbumArtID] ?? 0,
            hqRetryCount: albumArtHqRetryCounts[currentAlbumArtID] ?? 0
        )
    }

    private func makePredictiveAlbumArtSnapshot() -> PredictiveAlbumArtSnapshot {
        PredictiveAlbumArtSnapshot(
            lastAlbumArtId: predictiveLastAlbumArtId,
            pendingHq: !predictivePendingHqId.isEmpty,
            pendingHqId: predictivePendingHqId,
            lastSkipReason: predictiveLastSkipReason,
            offerCount: predictiveOfferCount,
            hqPrefetchScheduled: predictiveHqPrefetchScheduled,
            hqPrefetchSent: predictiveHqPrefetchSent,
            hqPrefetchSkippedCacheHit: predictiveHqPrefetchSkippedCacheHit,
            hqPrefetchSkippedInFlight: predictiveHqPrefetchSkippedInFlight,
            hqPrefetchSkippedNotConnected: predictiveHqPrefetchSkippedNotConnected,
            hqPrefetchCancelledTrackChanged: predictiveHqPrefetchCancelledTrackChanged,
            hqArrivedBeforeDisplayCount: predictiveHqArrivedBeforeDisplayCount,
            avgOfferToHqRequestMs: averageMs(
                total: predictiveOfferToHqRequestTotalMs,
                count: predictiveOfferToHqRequestCount
            ),
            avgOfferToHqReadyMs: averageMs(
                total: predictiveOfferToHqReadyTotalMs,
                count: predictiveOfferToHqReadyCount
            ),
            lastOfferToHqRequestMs: predictiveLastOfferToHqRequestMs,
            lastOfferToHqReadyMs: predictiveLastOfferToHqReadyMs
        )
    }

    private func averageMs(total: Int, count: Int) -> Int {
        count > 0 ? total / count : 0
    }

    private func schedulePredictiveHqPrefetch(id: String, delay: TimeInterval, reason: String) {
        scheduleHqAlbumArtRequest(id: id, delay: delay, reason: reason, predictive: true)
    }

    private enum PredictiveHqSkipCategory {
        case cacheHit
        case inFlight
        case notConnected
    }

    private func recordPredictiveOffer(id: String) {
        predictiveLastAlbumArtId = id
        predictiveOfferCount += 1
        predictiveOfferTimes[id] = Date()
        log("[PredictiveAlbumArt] offer id=\(id)")
    }

    private func recordPredictiveHqRequest(id: String) {
        let now = Date()
        predictiveHqRequestTimes[id] = now
        if let offeredAt = predictiveOfferTimes[id] {
            let elapsedMs = max(0, Int(now.timeIntervalSince(offeredAt) * 1_000))
            predictiveLastOfferToHqRequestMs = elapsedMs
            predictiveOfferToHqRequestTotalMs += elapsedMs
            predictiveOfferToHqRequestCount += 1
            log("[PredictiveAlbumArt] offerToHqRequestMs=\(elapsedMs) id=\(id)")
        }
    }

    private func recordPredictiveHqReady(id: String) {
        let now = Date()
        if let offeredAt = predictiveOfferTimes[id] {
            let elapsedMs = max(0, Int(now.timeIntervalSince(offeredAt) * 1_000))
            predictiveLastOfferToHqReadyMs = elapsedMs
            predictiveOfferToHqReadyTotalMs += elapsedMs
            predictiveOfferToHqReadyCount += 1
            log("[PredictiveAlbumArt] hq ready id=\(id) offerToHqReadyMs=\(elapsedMs)")
        }
        if let requestedAt = predictiveHqRequestTimes[id] {
            let elapsedMs = max(0, Int(now.timeIntervalSince(requestedAt) * 1_000))
            log("[PredictiveAlbumArt] hq ready id=\(id) requestToHqReadyMs=\(elapsedMs)")
        }
        if artworkDisplayQuality == .placeholder {
            predictiveHqArrivedBeforeDisplayCount += 1
        }
        predictiveHqRequestTimes[id] = nil
    }

    private func recordPredictiveHqSkip(id: String, reason: String, category: PredictiveHqSkipCategory) {
        predictiveLastSkipReason = reason
        switch category {
        case .cacheHit:
            predictiveHqPrefetchSkippedCacheHit += 1
        case .inFlight:
            predictiveHqPrefetchSkippedInFlight += 1
        case .notConnected:
            predictiveHqPrefetchSkippedNotConnected += 1
        }
        log("[PredictiveAlbumArt] skip hq id=\(id) reason=\(reason)")
    }

    private func cancelPredictiveHqPrefetch(reason: String, oldId: String) {
        guard !oldId.isEmpty else { return }
        if hqAlbumArtWorkItem != nil || predictivePendingHqId == oldId {
            predictiveHqPrefetchCancelledTrackChanged += 1
            log("[PredictiveAlbumArt] cancel pending reason=\(reason) id=\(oldId)")
        }
        predictivePendingHqId = ""
        predictiveOfferTimes[oldId] = nil
        predictiveHqRequestTimes[oldId] = nil
    }

    private func isPredictiveHqConnectionReady() -> Bool {
        let statusReady = delegate?.albumArtConnectionStatus == "已连接" ||
            delegate?.albumArtConnectionDisplayState == "connected"
        guard statusReady,
              delegate?.albumArtCharacteristicReady == true else {
            return false
        }
        let health = delegate?.albumArtConnectionHealthState ?? "disconnected"
        return health != "stale" && health != "disconnected" && health != "reconnecting"
    }

    private func scheduleAlbumArtFallback(id: String) {
        cancelAlbumArtFallback()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self,
                  self.currentAlbumArtID == id,
                  self.artworkDisplayQuality == .placeholder else {
                return
            }
            self.albumArtImage = nil
            self.currentCachedAlbumArtQuality = ""
            self.updateArtworkEnhancementStatus(message: "fallback default")
            self.delegate?.albumArtClearLiveArtwork(reason: "fallback default", shouldUpdate: false)
            self.delegate?.albumArtUpdateLiveActivity(force: true, reason: "albumArt")
            self.log("[AlbumArt] fallback default id=\(id)")
            self.notifyStateChanged()
        }
        albumArtFallbackWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: workItem)
    }

    private func cancelAlbumArtFallback() {
        albumArtFallbackWorkItem?.cancel()
        albumArtFallbackWorkItem = nil
    }

    private func scheduleHqAlbumArtRequest(
        id: String,
        delay: TimeInterval = 1.8,
        reason: String = "standard",
        predictive: Bool = false
    ) {
        guard id == currentAlbumArtID else { return }
        guard artworkDisplayQuality < .hq else {
            if predictive {
                recordPredictiveHqSkip(id: id, reason: "cache hit", category: .cacheHit)
            }
            return
        }
        guard !requestedHqAlbumArtIDs.contains(id),
              !requestedAlbumArtKeys.contains("\(id)|hq") else {
            if predictive {
                recordPredictiveHqSkip(id: id, reason: "in flight", category: .inFlight)
            }
            return
        }
        if predictive, !isPredictiveHqConnectionReady() {
            predictivePendingHqId = id
            recordPredictiveHqSkip(id: id, reason: "not connected", category: .notConnected)
            return
        }
        cancelHqAlbumArtRequest()
        let timing = predictive ? nil : delegate?.albumArtEffectiveHqDelay(delay)
        let effectiveDelay = timing?.delay ?? delay
        let deferred = timing?.deferred ?? false
        let workItem = DispatchWorkItem { [weak self] in
            self?.requestHqAlbumArt(id: id, reason: reason, predictive: predictive)
        }
        hqAlbumArtWorkItem = workItem
        log("[AlbumArtHQ] scheduled id=\(id) delayMs=\(Int(effectiveDelay * 1_000))")
        if predictive {
            predictivePendingHqId = id
            predictiveHqPrefetchScheduled += 1
            predictiveLastSkipReason = "-"
            log(
                "[PredictiveAlbumArt] schedule hq id=\(id) " +
                    "delayMs=\(Int(effectiveDelay * 1_000)) reason=\(reason)"
            )
        }
        if deferred {
            log("[StartupLoad] defer request=AlbumArtHQ delayMs=\(Int(effectiveDelay * 1_000))")
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + effectiveDelay, execute: workItem)
    }

    private func cancelHqAlbumArtRequest() {
        hqAlbumArtWorkItem?.cancel()
        hqAlbumArtWorkItem = nil
    }

    private func requestHqAlbumArt(id: String, reason: String = "standard", predictive: Bool = false) {
        guard id == currentAlbumArtID else { return }
        guard artworkDisplayQuality < .hq else {
            if predictive {
                recordPredictiveHqSkip(id: id, reason: "cache hit", category: .cacheHit)
            }
            return
        }
        guard delegate?.albumArtConnectionStatus == "已连接" else {
            log("[AlbumArtHQ] request skipped not connected")
            if predictive {
                predictivePendingHqId = id
                recordPredictiveHqSkip(id: id, reason: "not connected", category: .notConnected)
            }
            return
        }
        cancelStaleBinaryAlbumArtTransferIfNeeded(reason: "request found stale transfer")
        if let session = binaryAlbumArtSession, session.id != id {
            cancelBinaryAlbumArtTransfer(
                reason: "transfer belongs to old id old=\(session.id) new=\(id)",
                shouldRetryPreview: false
            )
        }
        guard delegate?.albumArtIsBusyForHqRequest == false,
              albumArtExpectedChunks == 0,
              binaryAlbumArtExpectedChunks == 0 else {
            log("[AlbumArtHQ] request skipped busy")
            if predictive {
                recordPredictiveHqSkip(id: id, reason: "in flight", category: .inFlight)
            }
            scheduleHqAlbumArtRequest(id: id, delay: 1.0, reason: "busy retry", predictive: predictive)
            return
        }
        guard requestedHqAlbumArtIDs.insert(id).inserted else {
            if predictive {
                recordPredictiveHqSkip(id: id, reason: "in flight", category: .inFlight)
            }
            return
        }
        if predictive {
            predictivePendingHqId = ""
            predictiveHqPrefetchSent += 1
            recordPredictiveHqRequest(id: id)
            log("[PredictiveAlbumArt] request hq id=\(id) reason=\(reason)")
        }
        requestAlbumArt(id: id, quality: "hq")
    }

    private func requestAlbumArt(id: String, quality: String) {
        guard id == currentAlbumArtID else { return }
        cancelStaleBinaryAlbumArtTransferIfNeeded(reason: "request found stale transfer")
        if let session = binaryAlbumArtSession {
            if session.id != id {
                cancelBinaryAlbumArtTransfer(
                    reason: "transfer belongs to old id old=\(session.id) new=\(id)",
                    shouldRetryPreview: false
                )
            } else {
                log("[AlbumArt] request \(quality) skipped reason=transfer in progress")
                return
            }
        }
        let key = "\(id)|\(quality)"
        guard requestedAlbumArtKeys.insert(key).inserted else { return }
        log("[AlbumArt] request \(quality)")
        delegate?.albumArtSendCommand(cmd: "ALBUM_ART_REQUEST", extra: ["id": id, "quality": quality])
    }

    private func loadCachedAlbumArt(id: String) -> (image: UIImage, quality: String)? {
        let hqValidation = validateAlbumArtCache(id: id, preferredQuality: "hq")
        if let cached = cachedEnhancedAlbumArt(id: id),
           hqValidation.valid,
           !hqValidation.shouldRefreshOnOffer {
            return (cached.image, "enhanced")
        }
        if let image = hqValidation.image,
           hqValidation.valid,
           !hqValidation.shouldRefreshOnOffer {
            return (image, "hq")
        }
        let previewValidation = validateAlbumArtCache(id: id, preferredQuality: "preview")
        if let image = previewValidation.image,
           previewValidation.valid,
           !previewValidation.shouldRefreshOnOffer {
            return (image, "preview")
        }
        let legacyValidation = validateAlbumArtCache(id: id, preferredQuality: nil)
        if let image = legacyValidation.image,
           legacyValidation.valid,
           !legacyValidation.shouldRefreshOnOffer {
            return (image, legacyValidation.quality)
        }
        return nil
    }

    private func cachedEnhancedAlbumArt(id: String, allowLowGain: Bool = false) -> ArtworkEnhancementResult? {
        guard artworkEnhancementEnabled else { return nil }
        guard let result = ArtworkEnhancementManager.shared.cachedEnhancedArtwork(
            artworkId: id,
            targetPixelSize: artworkEnhancementTargetPixelSize,
            sharpness: artworkEnhancementSharpness,
            logger: { [weak self] message in
                self?.log(message)
                self?.consoleLog(message)
            }
        ) else {
            return nil
        }
        if !allowLowGain, !result.shouldAutoDisplay {
            log(
                "[ArtworkEnhance] cache ignored reason=edge gain < 5% " +
                    "id=\(id) gain=\(String(format: "%.2f", result.edgeGainPercent))"
            )
            return nil
        }
        return result
    }

    private func setAlbumArtDisplay(
        image: UIImage,
        id: String,
        quality: ArtworkDisplayQuality,
        reason: String,
        force: Bool = false
    ) {
        guard id == currentAlbumArtID else {
            log("[ArtworkDisplay] stale image ignored id=\(id) current=\(currentAlbumArtID)")
            return
        }
        if !force, quality < artworkDisplayQuality {
            log(
                "[ArtworkDisplay] downgrade ignored current=\(artworkDisplayQuality.label) " +
                    "incoming=\(quality.label) reason=\(reason)"
            )
            return
        }

        let previous = artworkDisplayQuality
        albumArtImage = image
        artworkDisplayQuality = quality
        currentCachedAlbumArtQuality = quality.label
        updateArtworkEnhancementStatus(message: reason)
        if previous != quality {
            log(
                "[ArtworkDisplay] id=\(id) upgrade \(previous.label) -> \(quality.label) " +
                    "reason=\(reason)"
            )
        }
        notifyStateChanged()
    }

    private func artworkQuality(from quality: String) -> ArtworkDisplayQuality {
        switch quality {
        case "enhanced":
            return .enhanced
        case "hq", "full":
            return .hq
        case "preview":
            return .preview
        default:
            return .hq
        }
    }

    private func loadArtworkEnhancementSettings() {
        let savedTarget = UserDefaults.standard.integer(forKey: "artworkEnhancementTargetPixelSize")
        artworkEnhancementTargetPixelSize = ArtworkEnhancementManager.shared.normalizedTargetPixelSize(
            savedTarget == 0 ? ArtworkEnhancementManager.defaultTargetPixelSize : savedTarget
        )
        let savedSharpness = UserDefaults.standard.object(forKey: "artworkEnhancementSharpness") as? Double
        artworkEnhancementSharpness = ArtworkEnhancementManager.shared.normalizedSharpness(
            savedSharpness ?? ArtworkEnhancementManager.defaultSharpness
        )
    }

    private func startArtworkEnhancementFromCachedHq(id: String, reason: String) {
        guard let data = try? Data(contentsOf: albumArtCacheURL(id: id, quality: "hq")),
              let image = UIImage(data: data) else {
            updateArtworkEnhancementStatus(message: "HQ cache missing")
            notifyStateChanged()
            return
        }
        startArtworkEnhancementIfNeeded(id: id, sourceData: data, sourceImage: image, reason: reason)
    }

    private func startArtworkEnhancementIfNeeded(
        id: String,
        sourceData: Data,
        sourceImage: UIImage,
        reason: String
    ) {
        guard artworkEnhancementEnabled else {
            updateArtworkEnhancementStatus(message: "disabled")
            notifyStateChanged()
            return
        }
        guard id == currentAlbumArtID else {
            log("[ArtworkEnhance] stale request ignored id=\(id)")
            return
        }
        guard !isLikelyPlaceholderAlbumArt(sourceImage, dataSize: sourceData.count) else {
            updateArtworkEnhancementStatus(message: "placeholder skipped")
            log("[ArtworkEnhance] skipped reason=placeholder")
            notifyStateChanged()
            return
        }

        let target = artworkEnhancementTargetPixelSize
        let sourceWidth = sourceImage.pixelWidth
        let sourceHeight = sourceImage.pixelHeight
        artworkEnhancementStatus.currentSource = "\(sourceWidth)x\(sourceHeight)"
        artworkEnhancementStatus.target = "\(target)x\(target)"
        updateArtworkEnhancementStatus(message: reason)

        guard sourceWidth >= 220, sourceHeight >= 220 else {
            log("[ArtworkEnhance] skipped reason=source too small")
            updateArtworkEnhancementStatus(message: "source too small")
            notifyStateChanged()
            return
        }
        guard sourceWidth < target || sourceHeight < target else {
            log("[ArtworkEnhance] skipped reason=source already large enough")
            notifyStateChanged()
            return
        }
        if cachedEnhancedAlbumArt(id: id) != nil {
            notifyStateChanged()
            return
        }

        let request = ArtworkEnhancementRequest(
            artworkId: id,
            sourceData: sourceData,
            sourcePixelWidth: sourceWidth,
            sourcePixelHeight: sourceHeight,
            targetPixelSize: target,
            sharpness: artworkEnhancementSharpness,
            screenScale: UIScreen.main.scale
        )
        ArtworkEnhancementManager.shared.enhance(
            request: request,
            logger: { [weak self] message in
                self?.log(message)
                self?.consoleLog(message)
            },
            completion: { [weak self] result in
                guard let self else { return }
                switch result {
                case .success(let enhanced):
                    guard self.currentAlbumArtID == id else {
                        self.log("[ArtworkEnhance] stale result ignored id=\(id)")
                        return
                    }
                    self.artworkEnhancementStatus.lastProcessingCostMs = enhanced.processingTimeMs
                    self.artworkEnhancementStatus.lastEdgeGainPercent = enhanced.edgeGainPercent
                    self.updateArtworkEnhancementStatus(message: "success")
                    self.adaptArtworkEnhancementTargetIfNeeded(costMs: enhanced.processingTimeMs)
                    guard enhanced.shouldAutoDisplay else {
                        self.log(
                            "[ArtworkEnhance] auto display skipped reason=edge gain < 5% " +
                                "gain=\(String(format: "%.2f", enhanced.edgeGainPercent))"
                        )
                        self.updateArtworkEnhancementStatus(message: "edge gain < 5%")
                        self.notifyStateChanged()
                        return
                    }
                    guard !self.artworkEnhancementABOriginalMode else {
                        self.log("[ArtworkEnhance] A/B original active, enhanced display held")
                        self.notifyStateChanged()
                        return
                    }
                    self.setAlbumArtDisplay(image: enhanced.image, id: id, quality: .enhanced, reason: "enhancement complete")
                case .failure(let error):
                    self.updateArtworkEnhancementStatus(message: error.localizedDescription)
                    self.notifyStateChanged()
                }
            }
        )
    }

    private func updateArtworkEnhancementStatus(message: String? = nil) {
        let stats = ArtworkEnhancementManager.shared.cacheStats()
        artworkEnhancementStatus.enabled = artworkEnhancementEnabled
        artworkEnhancementStatus.targetPixelSize = artworkEnhancementTargetPixelSize
        artworkEnhancementStatus.sharpness = artworkEnhancementSharpness
        artworkEnhancementStatus.displayQuality = artworkDisplayQuality
        artworkEnhancementStatus.cacheHit = artworkDisplayQuality == .enhanced
        artworkEnhancementStatus.enhancedCacheFiles = stats.files
        artworkEnhancementStatus.enhancedCacheBytes = stats.bytes
        artworkEnhancementStatus.target = "\(artworkEnhancementTargetPixelSize)x\(artworkEnhancementTargetPixelSize)"
        if let message {
            artworkEnhancementStatus.lastMessage = message
        }
    }

    private func adaptArtworkEnhancementTargetIfNeeded(costMs: Int) {
        guard costMs > 1_200 else { return }
        let options = ArtworkEnhancementManager.targetPixelSizeOptions.sorted()
        guard let index = options.firstIndex(of: artworkEnhancementTargetPixelSize), index > 0 else {
            return
        }
        let nextTarget = options[index - 1]
        artworkEnhancementTargetPixelSize = nextTarget
        UserDefaults.standard.set(nextTarget, forKey: "artworkEnhancementTargetPixelSize")
        updateArtworkEnhancementStatus(message: "auto target down to \(nextTarget)")
        log(
            "[ArtworkEnhance] target auto downgraded reason=cost " +
                "costMs=\(costMs) target=\(nextTarget)"
        )
    }

    private func validateCachedAlbumArt(id: String, preferredQuality: String? = nil) -> (image: UIImage, quality: String)? {
        let validation = validateAlbumArtCache(id: id, preferredQuality: preferredQuality)
        guard validation.valid, !validation.expired, let image = validation.image else { return nil }
        return (image, validation.quality)
    }

    private func validateAlbumArtCache(id: String, preferredQuality: String? = nil) -> AlbumArtCacheValidation {
        let imageURL = albumArtCacheURL(id: id, quality: preferredQuality)
        let metadataURL = albumArtMetadataURL(id: id, quality: preferredQuality)
        let exists = FileManager.default.fileExists(atPath: imageURL.path)
        let data = try? Data(contentsOf: imageURL)
        let size = data?.count ?? 0
        let image = data.flatMap(UIImage.init(data:))
        let decoded = image != nil
        let metadata = readAlbumArtMetadata(from: metadataURL)
        let pixelWidth = metadata?.pixelWidth ?? image?.pixelWidth ?? 0
        let pixelHeight = metadata?.pixelHeight ?? image?.pixelHeight ?? 0
        let source = normalizedAlbumArtSource(metadata?.source)
        let ttlMs = albumArtCacheTtlMs(source: source)
        let savedAt = metadata?.effectiveSavedAt ?? fileModifiedAt(imageURL) ?? 0
        let ageMs: Int64 = savedAt > 0
            ? max(0, Int64((Date().timeIntervalSince1970 - savedAt) * 1_000))
            : Int64.max
        let expired = ageMs > ttlMs
        let qualityURL = albumArtQualityURL(id: id, quality: preferredQuality)
        let storedQuality = (try? String(contentsOf: qualityURL, encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let quality = preferredQuality ?? (storedQuality == "preview" ? "preview" : "hq")

        var reason = "ok"
        var valid = true
        if !exists {
            reason = "missing"
            valid = false
        } else if !decoded || size <= 0 {
            reason = "decode_failed"
            valid = false
        } else if let image, isLikelyPlaceholderAlbumArt(image, dataSize: size) {
            reason = "placeholder"
            valid = false
        } else if expired {
            reason = "source_ttl_expired"
        } else if source == "notificationLargeIcon" || source == "unknown" {
            reason = "source_requires_refresh"
        } else if pixelWidth <= ALBUM_ART_SMALL_CACHE_PIXEL_LIMIT ||
                    pixelHeight <= ALBUM_ART_SMALL_CACHE_PIXEL_LIMIT {
            reason = "small_cache_requires_refresh"
        }

        let validateMessage =
            "[AlbumArtCache] validate id=\(id) valid=\(valid) exists=\(exists) " +
            "quality=\(preferredQuality ?? "legacy") source=\(source) " +
            "ageMs=\(ageMs == Int64.max ? -1 : ageMs) ttlMs=\(ttlMs) " +
            "reason=\(reason) size=\(size) " +
            "pixelSize=\(pixelWidth)x\(pixelHeight) decode=\(decoded)"
        log(validateMessage)
        consoleLog(validateMessage)

        guard let image, size > 0 else {
            if exists {
                removeCorruptedAlbumArt(id: id, quality: preferredQuality)
            }
            return AlbumArtCacheValidation(
                image: nil,
                quality: quality,
                source: source,
                ageMs: ageMs,
                ttlMs: ttlMs,
                pixelWidth: pixelWidth,
                pixelHeight: pixelHeight,
                bytes: size,
                valid: false,
                expired: expired,
                reason: reason
            )
        }
        if isLikelyPlaceholderAlbumArt(image, dataSize: size) {
            removePlaceholderAlbumArt(id: id, quality: preferredQuality)
            return AlbumArtCacheValidation(
                image: nil,
                quality: quality,
                source: source,
                ageMs: ageMs,
                ttlMs: ttlMs,
                pixelWidth: pixelWidth,
                pixelHeight: pixelHeight,
                bytes: size,
                valid: false,
                expired: expired,
                reason: reason
            )
        }

        if expired {
            let message =
                "[AlbumArtCache] expired id=\(id) source=\(source) " +
                "ageMs=\(ageMs) ttlMs=\(ttlMs)"
            log(message)
            consoleLog(message)
        }
        return AlbumArtCacheValidation(
            image: image,
            quality: quality,
            source: source,
            ageMs: ageMs,
            ttlMs: ttlMs,
            pixelWidth: pixelWidth,
            pixelHeight: pixelHeight,
            bytes: size,
            valid: valid,
            expired: expired,
            reason: reason
        )
    }

    private func handleStaleAlbumArtCacheOffer(
        id: String,
        hqValidation: AlbumArtCacheValidation,
        previewValidation: AlbumArtCacheValidation
    ) {
        cancelAlbumArtFallback()
        cancelHqAlbumArtRequest()
        let firstRefreshAttempt = sourceRefreshAttemptedAlbumArtIDs.insert(id).inserted
        let selected = hqValidation.valid ? hqValidation : previewValidation
        let reason = selected.reason
        currentCachedAlbumArtQuality = ""
        artworkDisplayQuality = .placeholder
        albumArtImage = nil
        updateArtworkEnhancementStatus(message: "stale cache")
        delegate?.albumArtClearLiveArtwork(reason: "staleCache", shouldUpdate: false)
        let staleReason = selected.expired ? "source_ttl_expired" : reason
        let staleMessage = "[AlbumArtCache] stale id=\(id) reason=\(staleReason)"
        log(staleMessage)
        consoleLog(staleMessage)
        guard firstRefreshAttempt else {
            let waitingMessage = "[AlbumArtRefresh] skip reason=refresh_already_requested id=\(id)"
            log(waitingMessage)
            consoleLog(waitingMessage)
            notifyStateChanged()
            return
        }
        removeCachedAlbumArt(id: id, quality: "preview", reason: "stale refresh")
        removeCachedAlbumArt(id: id, quality: "hq", reason: "stale refresh")
        ArtworkEnhancementManager.shared.cancel(artworkId: id)
        ArtworkEnhancementManager.shared.clearCachedArtwork(artworkId: id) { [weak self] in
            self?.log("[ArtworkEnhance] cache removed id=\(id) reason=stale refresh")
        }
        requestedAlbumArtKeys.remove("\(id)|preview")
        requestedAlbumArtKeys.remove("\(id)|hq")
        requestedHqAlbumArtIDs.remove(id)
        let message =
            "[AlbumArtRefresh] request preview reason=stale_cache " +
            "id=\(id) source=\(selected.source) cacheReason=\(reason)"
        log(message)
        consoleLog(message)
        requestAlbumArt(id: id, quality: "preview")
        schedulePredictiveHqPrefetch(id: id, delay: 2.2, reason: "stale cache")
        notifyStateChanged()
    }

    private func removeCorruptedAlbumArt(id: String, quality: String? = nil) {
        removeCachedAlbumArt(id: id, quality: quality, reason: "corrupted")
    }

    private func removePlaceholderAlbumArt(id: String, quality: String? = nil) {
        removeCachedAlbumArt(id: id, quality: quality, reason: "placeholder")
    }

    private func removeCachedAlbumArt(id: String, quality: String? = nil, reason: String) {
        let imageURL = albumArtCacheURL(id: id, quality: quality)
        let qualityURL = albumArtQualityURL(id: id, quality: quality)
        let metadataURL = albumArtMetadataURL(id: id, quality: quality)
        try? FileManager.default.removeItem(at: imageURL)
        try? FileManager.default.removeItem(at: qualityURL)
        try? FileManager.default.removeItem(at: metadataURL)
        let message = "[AlbumArtCache] removed id=\(id) quality=\(quality ?? "legacy") reason=\(reason)"
        log(message)
        consoleLog(message)
    }

    private func isLikelyPlaceholderAlbumArt(_ image: UIImage, dataSize: Int) -> Bool {
        guard dataSize < 1_800, let cgImage = image.cgImage else {
            return false
        }
        let width = 24
        let height = 24
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return false
        }
        context.interpolationQuality = .low
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        var buckets = Set<Int>()
        var colorfulPixels = 0
        var visiblePixels = 0
        stride(from: 0, to: pixels.count, by: 4).forEach { index in
            let red = Int(pixels[index])
            let green = Int(pixels[index + 1])
            let blue = Int(pixels[index + 2])
            let alpha = Int(pixels[index + 3])
            guard alpha >= 24 else { return }
            let maximum = max(red, max(green, blue))
            let minimum = min(red, min(green, blue))
            if maximum > 0 && Double(maximum - minimum) / Double(maximum) > 0.12 {
                colorfulPixels += 1
            }
            buckets.insert(((red / 32) << 10) | ((green / 32) << 5) | (blue / 32))
            visiblePixels += 1
        }

        return visiblePixels > 0 && buckets.count <= 10 && colorfulPixels * 20 <= visiblePixels
    }

    private func shouldAcceptHqAlbumArt(image: UIImage, data: Data, id: String) -> Bool {
        guard let preview = cachedAlbumArtMetadata(id: id, quality: "preview") else {
            return true
        }
        let hqWidth = image.pixelWidth
        let hqHeight = image.pixelHeight
        let hqArea = hqWidth * hqHeight
        let previewArea = preview.pixelWidth * preview.pixelHeight
        guard hqArea > previewArea else {
            let message =
                "[AlbumArtHQ] ignored reason=no visual upgrade " +
                "hq=\(hqWidth)x\(hqHeight) preview=\(preview.pixelWidth)x\(preview.pixelHeight) " +
                "bytes=\(data.count)"
            log(message)
            consoleLog(message)
            return false
        }
        return true
    }

    private func saveAlbumArt(_ data: Data, id: String, quality: String, image: UIImage) {
        do {
            let directory = albumArtCacheDirectory()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: albumArtCacheURL(id: id, quality: quality), options: .atomic)
            try quality.write(to: albumArtQualityURL(id: id, quality: quality), atomically: true, encoding: .utf8)
            let metadata = AlbumArtCacheMetadata(
                id: id,
                quality: quality,
                source: inferAlbumArtSource(image: image),
                savedAt: Date().timeIntervalSince1970,
                pixelWidth: image.pixelWidth,
                pixelHeight: image.pixelHeight,
                bytes: data.count,
                createdAt: Date().timeIntervalSince1970
            )
            let metadataData = try JSONEncoder().encode(metadata)
            try metadataData.write(to: albumArtMetadataURL(id: id, quality: quality), options: .atomic)
            let message =
                "[AlbumArtCache] saved id=\(id) quality=\(quality) " +
                "source=\(metadata.source ?? "unknown") " +
                "pixelSize=\(metadata.pixelWidth)x\(metadata.pixelHeight) bytes=\(metadata.bytes)"
            log(message)
            consoleLog(message)
        } catch {
            let message = "[AlbumArtCache] save failed id=\(id) error=\(error.localizedDescription)"
            log(message)
            consoleLog(message)
        }
    }

    private func writeAlbumArtDiagnostics(id: String, quality: String, data: Data, image: UIImage) {
        guard DEBUG_ART_DIAGNOSTICS else { return }
        do {
            let directory = albumArtDiagnosticsDirectory()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let receivedURL = directory
                .appendingPathComponent("\(id)_received_\(quality)")
                .appendingPathExtension("jpg")
            try data.write(to: receivedURL, options: .atomic)

            let metadataURL = directory
                .appendingPathComponent("\(id)_ios_metadata")
                .appendingPathExtension("json")
            var metadata = readAlbumArtDiagnosticsMetadata(from: metadataURL)
            let keyPrefix = quality == "hq" ? "receivedHq" : "receivedPreview"
            let displayWidthPoints = 260.0
            let displayHeightPoints = 260.0
            let screenScale = Double(UIScreen.main.scale)
            let requiredDisplayPixelsWidth = displayWidthPoints * screenScale
            let requiredDisplayPixelsHeight = displayHeightPoints * screenScale
            let upscaleRatio = image.pixelWidth > 0 ? requiredDisplayPixelsWidth / Double(image.pixelWidth) : 0.0

            metadata["id"] = id
            metadata["\(keyPrefix)Sha256"] = Self.sha256Data(data)
            metadata["\(keyPrefix)Width"] = image.pixelWidth
            metadata["\(keyPrefix)Height"] = image.pixelHeight
            metadata["\(keyPrefix)Bytes"] = data.count
            metadata["\(keyPrefix)UIImageScale"] = Double(image.scale)
            metadata["\(keyPrefix)UIImageWidthPoints"] = Double(image.size.width)
            metadata["\(keyPrefix)UIImageHeightPoints"] = Double(image.size.height)
            metadata["displayedQuality"] = currentCachedAlbumArtQuality
            metadata["displayedPixelWidth"] = image.pixelWidth
            metadata["displayedPixelHeight"] = image.pixelHeight
            metadata["displayWidthPoints"] = displayWidthPoints
            metadata["displayHeightPoints"] = displayHeightPoints
            metadata["screenScale"] = screenScale
            metadata["requiredDisplayPixelsWidth"] = requiredDisplayPixelsWidth
            metadata["requiredDisplayPixelsHeight"] = requiredDisplayPixelsHeight
            metadata["upscaleRatio"] = upscaleRatio
            if let cacheData = try? Data(contentsOf: albumArtCacheURL(id: id, quality: quality)) {
                metadata["\(quality)CacheSha256"] = Self.sha256Data(cacheData)
                metadata["\(quality)CacheBytes"] = cacheData.count
                metadata["\(quality)CacheMatchesReceived"] = cacheData == data
            }
            metadata["updatedAt"] = Date().timeIntervalSince1970

            let metadataData = try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys])
            try metadataData.write(to: metadataURL, options: .atomic)
            let message =
                "[ArtDiag-iOS] id=\(id) quality=\(quality) " +
                "received=\(image.pixelWidth)x\(image.pixelHeight) " +
                "bytes=\(data.count) displayedQuality=\(currentCachedAlbumArtQuality) " +
                "upscaleRatio=\(String(format: "%.2f", upscaleRatio))"
            log(message)
            consoleLog(message)
        } catch {
            log("[ArtDiag-iOS] export failed id=\(id) error=\(error.localizedDescription)")
        }
    }

    private func readAlbumArtDiagnosticsMetadata(from url: URL) -> [String: Any] {
        guard let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any] else {
            return [:]
        }
        return dictionary
    }

    private func cachedAlbumArtMetadata(id: String, quality: String) -> AlbumArtCacheMetadata? {
        if let metadata = readAlbumArtMetadata(from: albumArtMetadataURL(id: id, quality: quality)) {
            return metadata
        }
        let imageURL = albumArtCacheURL(id: id, quality: quality)
        guard let data = try? Data(contentsOf: imageURL),
              let image = UIImage(data: data) else {
            return nil
        }
        return AlbumArtCacheMetadata(
            id: id,
            quality: quality,
            source: nil,
            savedAt: nil,
            pixelWidth: image.pixelWidth,
            pixelHeight: image.pixelHeight,
            bytes: data.count,
            createdAt: 0
        )
    }

    private func makeArtworkCacheDiagnostics(id: String) -> [ArtworkCacheDiagnostic] {
        guard !id.isEmpty else {
            return [
                ArtworkCacheDiagnostic.missing(quality: "preview"),
                ArtworkCacheDiagnostic.missing(quality: "hq"),
                ArtworkCacheDiagnostic.missing(quality: "enhanced")
            ]
        }
        return [
            makeAlbumArtCacheDiagnostic(id: id, quality: "preview"),
            makeAlbumArtCacheDiagnostic(id: id, quality: "hq"),
            makeEnhancedArtworkCacheDiagnostic(id: id)
        ]
    }

    private func makeAlbumArtCacheDiagnostic(id: String, quality: String) -> ArtworkCacheDiagnostic {
        let imageURL = albumArtCacheURL(id: id, quality: quality)
        let metadata = cachedAlbumArtMetadata(id: id, quality: quality)
        let attributes = try? FileManager.default.attributesOfItem(atPath: imageURL.path)
        let bytes = attributes?[.size] as? Int ?? metadata?.bytes ?? 0
        let modifiedAt = attributes?[.modificationDate] as? Date
        guard FileManager.default.fileExists(atPath: imageURL.path) else {
            return ArtworkCacheDiagnostic.missing(quality: quality, path: imageURL.path)
        }
        let data = try? Data(contentsOf: imageURL)
        let image = data.flatMap(UIImage.init(data:))
        let placeholder = image.map { isLikelyPlaceholderAlbumArt($0, dataSize: data?.count ?? bytes) } ?? false
        return ArtworkCacheDiagnostic(
            quality: quality,
            exists: true,
            bytes: bytes,
            pixelWidth: metadata?.pixelWidth ?? image?.pixelWidth ?? 0,
            pixelHeight: metadata?.pixelHeight ?? image?.pixelHeight ?? 0,
            isPlaceholder: placeholder,
            modifiedAt: modifiedAt,
            path: imageURL.path
        )
    }

    private func makeEnhancedArtworkCacheDiagnostic(id: String) -> ArtworkCacheDiagnostic {
        guard let enhanced = ArtworkEnhancementManager.shared.cachedEnhancedArtwork(
            artworkId: id,
            targetPixelSize: artworkEnhancementTargetPixelSize,
            sharpness: artworkEnhancementSharpness
        ) else {
            return ArtworkCacheDiagnostic.missing(quality: "enhanced")
        }
        let attributes = try? FileManager.default.attributesOfItem(atPath: enhanced.fileURL.path)
        let bytes = attributes?[.size] as? Int ?? 0
        let modifiedAt = attributes?[.modificationDate] as? Date
        return ArtworkCacheDiagnostic(
            quality: "enhanced",
            exists: true,
            bytes: bytes,
            pixelWidth: Int(enhanced.enhancedPixelSize.width),
            pixelHeight: Int(enhanced.enhancedPixelSize.height),
            isPlaceholder: false,
            modifiedAt: modifiedAt,
            path: enhanced.fileURL.path
        )
    }

    private func inferAlbumArtSource(image: UIImage) -> String {
        if image.pixelWidth <= 320 || image.pixelHeight <= 320 {
            return "notificationLargeIcon"
        }
        return "unknown"
    }

    private func normalizedAlbumArtSource(_ source: String?) -> String {
        guard let source,
              !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return "unknown"
        }
        return source
    }

    private func albumArtCacheTtlMs(source: String) -> Int64 {
        switch source {
        case "mediaMetadata", "artUri", "displayIconUri", "embeddedArtwork":
            return ALBUM_ART_STABLE_SOURCE_TTL_MS
        default:
            return ALBUM_ART_TRANSIENT_SOURCE_TTL_MS
        }
    }

    private func fileModifiedAt(_ url: URL) -> TimeInterval? {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.modificationDate] as? Date)?.timeIntervalSince1970
    }

    private func readAlbumArtMetadata(from url: URL) -> AlbumArtCacheMetadata? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(AlbumArtCacheMetadata.self, from: data)
    }

    private func albumArtCacheDirectory() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("AlbumArtCache", isDirectory: true)
    }

    private func albumArtDiagnosticsDirectory() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("AlbumArtDiagnostics", isDirectory: true)
    }

    private func albumArtCacheURL(id: String, quality: String? = nil) -> URL {
        let baseName = Self.sha256(id)
        let fileName: String
        if let quality, quality == "preview" || quality == "hq" {
            fileName = "\(baseName)_\(quality)"
        } else {
            fileName = baseName
        }
        return albumArtCacheDirectory().appendingPathComponent(fileName).appendingPathExtension("jpg")
    }

    private func albumArtQualityURL(id: String, quality: String? = nil) -> URL {
        let baseName = Self.sha256(id)
        let fileName: String
        if let quality, quality == "preview" || quality == "hq" {
            fileName = "\(baseName)_\(quality)"
        } else {
            fileName = baseName
        }
        return albumArtCacheDirectory().appendingPathComponent(fileName).appendingPathExtension("quality")
    }

    private func albumArtMetadataURL(id: String, quality: String? = nil) -> URL {
        let baseName = Self.sha256(id)
        let fileName: String
        if let quality, quality == "preview" || quality == "hq" {
            fileName = "\(baseName)_\(quality)"
        } else {
            fileName = baseName
        }
        return albumArtCacheDirectory().appendingPathComponent(fileName).appendingPathExtension("json")
    }

    private static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256Data(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func log(_ message: String) {
        delegate?.albumArtLog(message)
    }

    private func consoleLog(_ message: String) {
        delegate?.albumArtConsoleLog(message)
    }

    private func notifyStateChanged() {
        onStateChanged?(self)
    }
}
