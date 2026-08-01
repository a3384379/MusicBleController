import Combine
import UIKit
import XCTest
@testable import sonyMusic

final class PerformanceStabilityTests: XCTestCase {
    func testSoftWriteRecoveryRetainsQueuedMediaRequests() {
        XCTAssertTrue(
            CommandWriteSoftRecoveryPolicy.shouldRetainPendingCommand(
                "GET_LYRIC_SECONDARY"
            )
        )
        XCTAssertTrue(
            CommandWriteSoftRecoveryPolicy.shouldRetainPendingCommand(
                "ALBUM_ART_REQUEST"
            )
        )
        XCTAssertTrue(
            CommandWriteSoftRecoveryPolicy.shouldRetainPendingCommand("PING")
        )
    }

    func testCurrentWordOrderingFenceRejectsDuplicatesAndSmallRegression() {
        var fence = CurrentWordOrderingFence()
        XCTAssertTrue(fence.shouldAccept(generation: 7, sequence: 1, positionMs: 1_000))
        XCTAssertFalse(fence.shouldAccept(generation: 7, sequence: 1, positionMs: 1_050))
        XCTAssertFalse(fence.shouldAccept(generation: 7, sequence: 2, positionMs: 900))
        XCTAssertTrue(fence.shouldAccept(generation: 7, sequence: 3, positionMs: 1_700))
        XCTAssertTrue(fence.shouldAccept(generation: 7, sequence: 4, positionMs: 100))
        XCTAssertEqual(fence.sequence, 4)
        XCTAssertEqual(fence.positionMs, 100)

        fence.reset()
        XCTAssertTrue(fence.shouldAccept(generation: 1, sequence: 1, positionMs: 20))
    }

    func testA1AndA2DispatchAndOutOfOrderAssembly() {
        let a1 = packet(magic: 0xA1, kind: 3, index: 0, total: 1, payload: Data([9]))
        let decodedA1 = BLEBinaryChunkCodec.decode(a1, expectedMagic: 0xA1)
        XCTAssertEqual(decodedA1?.kindCode, 3)
        XCTAssertEqual(decodedA1?.payload, Data([9]))
        XCTAssertNil(BLEBinaryChunkCodec.decode(a1, expectedMagic: 0xA2))

        let payloads = [Data("one".utf8), Data("two".utf8), Data("three".utf8)]
        let packets = payloads.enumerated().map {
            packet(
                magic: 0xA2,
                kind: 1,
                index: $0.offset,
                total: payloads.count,
                payload: $0.element
            )
        }
        var chunks: [Int: Data] = [:]
        for packetData in packets.reversed() {
            let chunk = try! XCTUnwrap(
                BLEBinaryChunkCodec.decode(packetData, expectedMagic: 0xA2)
            )
            chunks[chunk.index] = chunk.payload
        }
        let duplicate = try! XCTUnwrap(
            BLEBinaryChunkCodec.decode(packets[1], expectedMagic: 0xA2)
        )
        chunks[duplicate.index] = duplicate.payload
        XCTAssertEqual(
            BLEBinaryChunkCodec.reassemble(chunks: chunks, expectedCount: 3),
            Data("onetwothree".utf8)
        )
        chunks.removeValue(forKey: 1)
        XCTAssertEqual(
            BLEBinaryChunkCodec.missingIndexes(chunks: chunks, expectedCount: 3),
            [1]
        )
        XCTAssertNil(BLEBinaryChunkCodec.reassemble(chunks: chunks, expectedCount: 3))
    }

    func testCRCAndZlibFixedVector() {
        XCTAssertEqual(
            BLEBinaryChunkCodec.crc32(Data("123456789".utf8)),
            0xcbf4_3926
        )
        let raw = Data(
            "{\"trackId\":\"fixed\",\"generation\":7,\"lines\":[{\"index\":0,\"timeMs\":0,\"durationMs\":1000,\"text\":\"hello\"}]}"
                .utf8
        )
        let compressed = dataFromHex(
            "7801ab562a294a4ccef64c51b2524acbac484d51d2514a4fcd4b2d4a2cc9cccf53b232d751cac9cc4b2d56b28aae56cacc4b49ad50b232d0512ac9cc4df5050a029929a510b520aea181014832b5a204685c466a4e4ebe526d6c2d0075732076"
        )
        XCTAssertEqual(
            BLETestManager.zlibDecompress(compressed, expectedSize: raw.count),
            raw
        )
        XCTAssertEqual(BLETestManager.crc32(compressed), 0x74f3_85b3)
    }

    func testSnapshotValidExpiredAndCorrupted() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("LastNowPlayingSnapshot-v1.json")
        let now = Date()
        let snapshot = LastNowPlayingSnapshot(
            version: LastNowPlayingSnapshot.version,
            trackId: "track-1",
            title: "Title",
            artist: "Artist",
            album: "Album",
            wasPlaying: true,
            positionMs: 2_000,
            durationMs: 20_000,
            lyricLines: [],
            albumArtId: "art-1",
            savedAt: now
        )
        try JSONEncoder().encode(snapshot).write(to: fileURL, options: .atomic)
        XCTAssertEqual(
            LastNowPlayingSnapshotStore.readSnapshot(fileURL: fileURL, now: now),
            snapshot
        )
        XCTAssertNil(
            LastNowPlayingSnapshotStore.readSnapshot(
                fileURL: fileURL,
                now: now.addingTimeInterval(LastNowPlayingSnapshot.maximumAge + 1)
            )
        )
        try Data("{broken".utf8).write(to: fileURL, options: .atomic)
        XCTAssertNil(LastNowPlayingSnapshotStore.readSnapshot(fileURL: fileURL))
    }

    func testArtworkDownsamplingAndCorruptData() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1_024, height: 768))
        let image = renderer.image { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1_024, height: 768))
        }
        let data = try XCTUnwrap(image.jpegData(compressionQuality: 0.8))
        let downsampled = try XCTUnwrap(
            ArtworkImageCache.downsampledImage(data: data, maximumPixelSize: 128)
        )
        XCTAssertLessThanOrEqual(max(downsampled.pixelWidth, downsampled.pixelHeight), 128)
        XCTAssertNil(
            ArtworkImageCache.downsampledImage(
                data: Data("not-an-image".utf8),
                maximumPixelSize: 128
            )
        )
    }

    func testQQFallbackIsRejectedAndForceRefreshRetainsRealArtwork() async throws {
        let delegate = AlbumArtReceiverTestDelegate()
        let receiver = AlbumArtReceiver(delegate: delegate)
        let artworkID = "low-information-\(UUID().uuidString)"
        delegate.trackID = artworkID
        receiver.handleIdentity(id: artworkID)
        defer { receiver.clearCurrentIdentity(reason: "test cleanup") }

        let rendererFormat = UIGraphicsImageRendererFormat()
        rendererFormat.scale = 1
        let fallbackRenderer = UIGraphicsImageRenderer(
            size: CGSize(width: 228, height: 228),
            format: rendererFormat
        )
        let fallbackImage = fallbackRenderer.image { context in
            UIColor(white: 0.42, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 228, height: 228))
        }
        let fallbackData = try XCTUnwrap(fallbackImage.jpegData(compressionQuality: 0.8))
        XCTAssertEqual(
            AlbumArtPlaceholderPolicy.disposition(
                image: fallbackImage,
                dataSize: fallbackData.count
            ),
            .provisional
        )

        let rejected = expectation(description: "QQ fallback rejected")
        var didFulfillRejection = false
        receiver.onStateChanged = { state in
            guard !didFulfillRejection,
                  state.artworkEnhancementStatus.lastMessage == "waiting for real artwork" else {
                return
            }
            didFulfillRejection = true
            rejected.fulfill()
        }
        receiver.handleLegacyStart(
            id: artworkID,
            quality: "hq",
            size: fallbackData.count,
            chunks: 1
        )
        receiver.handleLegacyChunk(
            id: artworkID,
            quality: "hq",
            index: 0,
            base64: fallbackData.base64EncodedString()
        )
        receiver.handleLegacyEnd(id: artworkID, quality: "hq")
        await fulfillment(of: [rejected], timeout: 5)
        XCTAssertNil(receiver.albumArtImage)
        XCTAssertEqual(receiver.artworkDisplayQuality, .placeholder)

        let realRenderer = UIGraphicsImageRenderer(
            size: CGSize(width: 280, height: 280),
            format: rendererFormat
        )
        let realImage = realRenderer.image { context in
            UIColor.systemRed.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 140, height: 280))
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 140, y: 0, width: 140, height: 280))
            UIColor.systemGreen.setFill()
            context.fill(CGRect(x: 70, y: 70, width: 140, height: 140))
        }
        let realData = try XCTUnwrap(realImage.jpegData(compressionQuality: 0.8))
        XCTAssertEqual(
            AlbumArtPlaceholderPolicy.disposition(
                image: realImage,
                dataSize: realData.count
            ),
            .normal
        )

        let displayed = expectation(description: "real artwork displayed")
        var didFulfillDisplay = false
        receiver.onStateChanged = { state in
            guard !didFulfillDisplay, state.albumArtImage != nil else { return }
            didFulfillDisplay = true
            displayed.fulfill()
        }
        receiver.handleLegacyStart(
            id: artworkID,
            quality: "preview",
            size: realData.count,
            chunks: 1
        )
        receiver.handleLegacyChunk(
            id: artworkID,
            quality: "preview",
            index: 0,
            base64: realData.base64EncodedString()
        )
        receiver.handleLegacyEnd(id: artworkID, quality: "preview")
        await fulfillment(of: [displayed], timeout: 5)

        let displayedImage = try XCTUnwrap(receiver.albumArtImage)
        delegate.commands.removeAll()
        XCTAssertTrue(receiver.forceRefreshCurrentAlbumArt())
        XCTAssertTrue(receiver.albumArtImage === displayedImage)

        let previewRefresh = try XCTUnwrap(
            delegate.commands.first {
                $0.command == "ALBUM_ART_REQUEST" &&
                    ($0.extra["quality"] as? String) == "preview"
            }
        )
        XCTAssertEqual(previewRefresh.extra["forceRefresh"] as? Bool, true)
    }

    func testSourceRefreshSecondOfferDoesNotBecomeFalseCacheHit() {
        XCTAssertTrue(
            AlbumArtSourceRefreshPolicy.needsRefresh(
                cacheRequiresRefresh: true,
                refreshAttempted: false,
                refreshInFlight: false
            )
        )
        XCTAssertTrue(
            AlbumArtSourceRefreshPolicy.needsRefresh(
                cacheRequiresRefresh: true,
                refreshAttempted: true,
                refreshInFlight: true
            )
        )
        XCTAssertFalse(
            AlbumArtSourceRefreshPolicy.needsRefresh(
                cacheRequiresRefresh: true,
                refreshAttempted: true,
                refreshInFlight: false
            )
        )
        XCTAssertFalse(
            AlbumArtSourceRefreshPolicy.needsRefresh(
                cacheRequiresRefresh: false,
                refreshAttempted: false,
                refreshInFlight: false
            )
        )
    }

    func testRepeatedOfferDuringSourceRefreshDoesNotSkipHQ() async throws {
        let delegate = AlbumArtReceiverTestDelegate()
        let receiver = AlbumArtReceiver(delegate: delegate)
        let artworkID = "source-refresh-\(UUID().uuidString)"
        delegate.trackID = artworkID
        receiver.handleIdentity(id: artworkID)
        defer { receiver.clearCurrentIdentity(reason: "test cleanup") }

        let rendererFormat = UIGraphicsImageRendererFormat()
        rendererFormat.scale = 1
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: 228, height: 228),
            format: rendererFormat
        )
        let image = renderer.image { context in
            UIColor.systemPurple.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 114, height: 228))
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 114, y: 0, width: 114, height: 228))
        }
        let data = try XCTUnwrap(image.jpegData(compressionQuality: 0.8))

        let cacheSaved = expectation(description: "transient HQ cache saved")
        delegate.onLog = { message in
            if message.contains("[AlbumArtCache] saved id=\(artworkID) quality=hq") {
                cacheSaved.fulfill()
            }
        }
        receiver.handleLegacyStart(
            id: artworkID,
            quality: "hq",
            size: data.count,
            chunks: 1
        )
        receiver.handleLegacyChunk(
            id: artworkID,
            quality: "hq",
            index: 0,
            base64: data.base64EncodedString()
        )
        receiver.handleLegacyEnd(id: artworkID, quality: "hq")
        await fulfillment(of: [cacheSaved], timeout: 5)

        delegate.commands.removeAll()
        let forcedPreview = expectation(description: "source refresh preview requested")
        delegate.onCommand = { command, extra in
            guard command == "ALBUM_ART_REQUEST",
                  (extra["quality"] as? String) == "preview",
                  (extra["forceRefresh"] as? Bool) == true else {
                return
            }
            forcedPreview.fulfill()
        }
        receiver.handleOffer(id: artworkID)
        await fulfillment(of: [forcedPreview], timeout: 5)

        let secondOfferHeld = expectation(description: "second offer remains in refresh")
        delegate.onLog = { message in
            if message.contains("refresh_already_requested id=\(artworkID)") {
                secondOfferHeld.fulfill()
            }
        }
        receiver.handleOffer(id: artworkID)
        await fulfillment(of: [secondOfferHeld], timeout: 5)
        XCTAssertFalse(
            delegate.commands.contains {
                $0.command == "ALBUM_ART_SKIP" &&
                    ($0.extra["quality"] as? String) == "hq"
            }
        )
    }

    func testObservableSliceDeduplicatesAndPlaybackClockPauses() {
        let slice = ObservableStateSlice(BLEConnectionViewState())
        var emissions = 0
        let cancellable = slice.$value.dropFirst().sink { _ in emissions += 1 }
        slice.update(BLEConnectionViewState())
        XCTAssertEqual(emissions, 0)
        var connected = BLEConnectionViewState()
        connected.status = "已连接"
        slice.update(connected)
        XCTAssertEqual(emissions, 1)
        withExtendedLifetime(cancellable) {}

        XCTAssertTrue(
            PlaybackClockPolicy.shouldRun(
                isPlaying: true,
                durationMs: 10_000,
                appLifecycleState: "active"
            )
        )
        XCTAssertFalse(
            PlaybackClockPolicy.shouldRun(
                isPlaying: false,
                durationMs: 10_000,
                appLifecycleState: "active"
            )
        )
        XCTAssertFalse(
            PlaybackClockPolicy.shouldRun(
                isPlaying: true,
                durationMs: 10_000,
                appLifecycleState: "background"
            )
        )
    }

    private func packet(
        magic: UInt8,
        kind: UInt8,
        index: Int,
        total: Int,
        payload: Data
    ) -> Data {
        var data = Data([
            magic,
            kind,
            UInt8((index >> 8) & 0xff),
            UInt8(index & 0xff),
            UInt8((total >> 8) & 0xff),
            UInt8(total & 0xff)
        ])
        data.append(payload)
        return data
    }

    private func dataFromHex(_ text: String) -> Data {
        var result = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            result.append(UInt8(text[index..<next], radix: 16) ?? 0)
            index = next
        }
        return result
    }
}

private final class AlbumArtReceiverTestDelegate: AlbumArtReceiverDelegate {
    var trackID = ""
    var commands: [(command: String, extra: [String: Any])] = []
    var onCommand: ((String, [String: Any]) -> Void)?
    var onLog: ((String) -> Void)?

    var albumArtCurrentTrackID: String { trackID }
    var albumArtCurrentTitle: String { "Test Track" }
    var albumArtConnectionStatus: String { "已连接" }
    var albumArtConnectionDisplayState: String { "connected" }
    var albumArtConnectionHealthState: String { "healthy" }
    var albumArtCharacteristicReady: Bool { true }
    var albumArtIsBusyForHqRequest: Bool { false }

    func albumArtLog(_ message: String) {
        onLog?(message)
    }

    func albumArtConsoleLog(_ message: String) {}

    func albumArtSendCommand(cmd: String, extra: [String: Any]) {
        commands.append((cmd, extra))
        onCommand?(cmd, extra)
    }

    func albumArtEffectiveHqDelay(
        _ delay: TimeInterval
    ) -> (delay: TimeInterval, deferred: Bool) {
        (delay, false)
    }

    func albumArtPublishLiveArtwork(image: UIImage, key: String, reason: String) {}
    func albumArtClearLiveArtwork(reason: String, shouldUpdate: Bool) {}
    func albumArtUpdateLiveActivity(force: Bool, reason: String) {}
}
