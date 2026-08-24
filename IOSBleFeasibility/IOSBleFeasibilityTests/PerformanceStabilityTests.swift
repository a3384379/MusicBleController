import Combine
import SwiftUI
import UIKit
import XCTest
@testable import sonyMusic

final class PerformanceStabilityTests: XCTestCase {
    @MainActor
    func testPrimaryIOSSurfacesRenderRepresentativeStates() {
        let manager = BLETestManager()
        let preferences = PreferencesStore.shared
        let originalExperienceMode = preferences.appExperienceMode
        let originalLyricMode = preferences.lyricDisplayMode
        defer {
            preferences.appExperienceMode = originalExperienceMode
            preferences.lyricDisplayMode = originalLyricMode
        }

        preferences.appExperienceMode = .debug

        assertViewRenders(
            AnyView(ContentView()),
            named: "content-view-regular",
            width: 390,
            height: 844
        )
        assertViewRenders(
            AnyView(ContentView().environment(\.dynamicTypeSize, .accessibility3)),
            named: "content-view-accessibility-compact",
            width: 375,
            height: 667
        )

        let lyrics = [
            LyricLine(
                index: 0,
                timeMs: 0,
                durationMs: 2_000,
                text: "第一句歌词",
                translation: "First lyric line",
                romanization: "di yi ju ge ci",
                words: [
                    LyricWord(id: 0, startMs: 0, durationMs: 1_000, text: "第一句"),
                    LyricWord(id: 1, startMs: 1_000, durationMs: 1_000, text: "歌词")
                ]
            ),
            LyricLine(
                index: 1,
                timeMs: 2_000,
                durationMs: 2_000,
                text: "第二句歌词",
                translation: nil,
                romanization: nil,
                words: []
            )
        ]
        let stats = makePlaybackStatsFixture()

        let surfaces: [(String, AnyView)] = [
            ("preferences", AnyView(PreferencesView(bleManager: manager, onDismiss: {}))),
            ("debug", AnyView(DebugToolsView(bleManager: manager))),
            ("now-playing-diagnostic", AnyView(NowPlayingDiagnosticView(bleManager: manager, onDismiss: {}))),
            ("system-health", AnyView(SystemHealthOverviewView(bleManager: manager, onDismiss: {}))),
            ("lyric-diagnostic", AnyView(LyricDiagnosticView(bleManager: manager, onDismiss: {}))),
            ("history-empty", AnyView(PlaybackHistoryView(bleManager: manager))),
            ("history-stats", AnyView(PlaybackStatsView(stats: stats)))
        ]

        for (name, view) in surfaces {
            assertViewRenders(view, named: name, height: 844)
        }

        for mode in LyricDisplayMode.allCases {
            preferences.lyricDisplayMode = mode
            assertViewRenders(
                AnyView(
                    FullLyricsView(
                        title: "测试歌曲",
                        artist: "测试歌手",
                        albumArtImage: nil,
                        lyrics: lyrics,
                        lyricsIdentity: "test|2|0|2000",
                        currentIndex: 0,
                        positionMs: 750,
                        translationState: .ready,
                        romanizationState: .unavailable,
                        isPlaying: true,
                        isConnected: true,
                        onDismiss: {},
                        onPrevious: {},
                        onPlayPause: {},
                        onNext: {},
                        onSeekToLine: { _ in },
                        onShowDiagnostic: {}
                    )
                ),
                named: "full-lyrics-\(mode.rawValue)",
                height: 844
            )
        }

        assertViewRenders(
            AnyView(
                FullLyricsView(
                    title: "测试歌曲",
                    artist: "测试歌手",
                    albumArtImage: nil,
                    lyrics: [],
                    lyricsIdentity: "empty|0|-1|-1",
                    currentIndex: -1,
                    positionMs: 0,
                    translationState: .failed(reason: "测试失败"),
                    romanizationState: .loading,
                    isPlaying: false,
                    isConnected: false,
                    onDismiss: {},
                    onPrevious: {},
                    onPlayPause: {},
                    onNext: {},
                    onSeekToLine: { _ in },
                    onShowDiagnostic: {}
                )
            ),
            named: "full-lyrics-empty",
            height: 667
        )
    }

    func testLyricDiagnosticAndMediaLoadingPresentationBranches() {
        let statuses = [
            "loaded",
            "loading",
            "waiting qqmusic cache",
            "retry_pending",
            "no_safe_candidate",
            "no_lyrics_final",
            "maintenance_busy",
            "error",
            "custom"
        ]
        let reasons = [
            "waiting qqmusic cache",
            "fuzzy index warming",
            "cooldown retry pending",
            "no safe qrc candidate",
            "metadata 演唱 mismatch",
            "maintenance busy",
            "no parsed lyric",
            "loading",
            ""
        ]
        let suggestions = [
            "open_qqmusic_lyrics",
            "retry_later",
            "refresh_current_lyric",
            "no_safe_candidate",
            "maintenance_busy",
            "loaded",
            "custom"
        ]

        for status in statuses {
            let diagnostic = LyricDiagnostic.lightweight(
                trackId: "track-\(status)",
                title: "Song",
                artist: "Artist",
                status: status,
                reason: "",
                suggestion: "custom"
            )
            XCTAssertFalse(diagnostic.statusTitle.isEmpty)
        }
        for reason in reasons {
            let diagnostic = LyricDiagnostic.lightweight(
                trackId: "track",
                title: "Song",
                artist: "Artist",
                status: "custom",
                reason: reason,
                suggestion: "custom"
            )
            XCTAssertFalse(diagnostic.humanReadableReason.isEmpty)
        }
        for suggestion in suggestions {
            let diagnostic = LyricDiagnostic.lightweight(
                trackId: "track",
                title: "Song",
                artist: "Artist",
                status: "loaded",
                reason: "loaded",
                suggestion: suggestion
            )
            XCTAssertFalse(diagnostic.suggestionText.isEmpty)
        }

        let lyricStages: [LyricLoadingStage] = [
            .idle,
            .waitingQqQrc,
            .windowReady(lineCount: 8),
            .fullLyrics(received: 2, expected: 0),
            .ready(lineCount: 42),
            .failed(reason: ""),
            .failed(reason: String(repeating: "x", count: 40))
        ]
        let artworkStages: [ArtworkLoadingStage] = [
            .idle,
            .preview(received: 0, expected: 0),
            .preview(received: 2, expected: 4),
            .previewReady,
            .hq(received: 0, expected: 0),
            .hq(received: 3, expected: 5),
            .hqReady,
            .failed(reason: ""),
            .failed(reason: String(repeating: "y", count: 40))
        ]
        XCTAssertEqual(lyricStages.filter(\.isFailure).count, 2)
        XCTAssertEqual(artworkStages.filter(\.isFailure).count, 2)
        XCTAssertTrue(lyricStages.allSatisfy { !$0.title.isEmpty })
        XCTAssertTrue(artworkStages.allSatisfy { !$0.title.isEmpty })
        XCTAssertEqual(MediaLoadingState(), MediaLoadingState(lyric: .idle, artwork: .idle))
    }

    func testSelfHealingCoversHealthyRecoveringAndDiagnosticStates() {
        let engine = SelfHealingEngine.shared
        var loaded = LyricDiagnostic.lightweight(
            trackId: "healthy",
            title: "Song",
            artist: "Artist",
            status: "loaded",
            reason: "",
            suggestion: "loaded"
        )
        loaded.lines = 12
        loaded.recoveryState = "idle"

        let healthy = engine.evaluate(
            trackId: "healthy",
            title: "Healthy",
            connection: makeConnectionSnapshot(),
            artwork: makeAlbumArtSnapshot(quality: .hq, transferState: "idle"),
            lyric: loaded,
            currentLyric: "line",
            fullLyricsLineCount: 12,
            isFullLyricsCurrent: true
        )
        XCTAssertTrue(healthy.activeReports.isEmpty)
        XCTAssertEqual(healthy.overallStatus, "状态正常")
        XCTAssertFalse(healthy.summaryText.isEmpty)

        var waiting = LyricDiagnostic.lightweight(
            trackId: "recovering",
            title: "Song",
            artist: "Artist",
            status: "waiting_qqmusic_cache",
            reason: "waiting qqmusic lyric cache",
            suggestion: "open_qqmusic_lyrics"
        )
        waiting.waitingQqMusicCache = true
        let recovering = engine.evaluate(
            trackId: "recovering",
            title: "Recovering",
            connection: makeConnectionSnapshot(
                displayState: "reconnecting",
                healthState: "stale",
                autoReconnectState: "scanning",
                ready: false,
                notifyAgeMs: 9_000
            ),
            artwork: makeAlbumArtSnapshot(
                quality: .placeholder,
                transferState: "timeout",
                failure: "chunk timeout"
            ),
            lyric: waiting,
            currentLyric: "",
            fullLyricsLineCount: 0,
            isFullLyricsCurrent: false
        )
        XCTAssertEqual(recovering.activeReports.count, 4)
        XCTAssertEqual(recovering.overallSeverity, .working)

        var noLyrics = LyricDiagnostic.lightweight(
            trackId: "diagnostic",
            title: "Song",
            artist: "Artist",
            status: "no_safe_candidate",
            reason: "no safe qrc candidate",
            suggestion: "no_safe_candidate"
        )
        noLyrics.recoveryState = "idle"
        let diagnostic = engine.evaluate(
            trackId: "diagnostic",
            title: "Diagnostic",
            connection: makeConnectionSnapshot(
                displayState: "disconnected",
                healthState: "disconnected",
                autoReconnectState: "idle",
                ready: false
            ),
            artwork: makeAlbumArtSnapshot(
                quality: .placeholder,
                transferState: "failed",
                failure: "source unavailable"
            ),
            lyric: noLyrics,
            currentLyric: "",
            fullLyricsLineCount: 0,
            isFullLyricsCurrent: false
        )
        XCTAssertEqual(diagnostic.overallSeverity, .warning)
        XCTAssertTrue(diagnostic.summaryText.contains("歌词"))

        var loading = noLyrics
        loading.status = "loading"
        loading.recoveryState = "retrying"
        let probing = engine.evaluate(
            trackId: "probing",
            title: "Probing",
            connection: makeConnectionSnapshot(
                healthState: "suspect",
                autoReconnectState: "idle",
                ready: true,
                notifyAgeMs: 4_000
            ),
            artwork: makeAlbumArtSnapshot(
                quality: .preview,
                transferState: "receiving"
            ),
            lyric: loading,
            currentLyric: "",
            fullLyricsLineCount: 0,
            isFullLyricsCurrent: false
        )
        XCTAssertTrue(probing.activeReports.count >= 3)

        var metrics = RecoveryMetrics()
        for stage in [RecoveryStage.detect, .recover, .verify, .success, .fail, .diagnostics, .idle] {
            metrics.record(stage: stage)
        }
        XCTAssertEqual(metrics.detectCount, 1)
        XCTAssertEqual(metrics.recoverCount, 1)
        XCTAssertEqual(metrics.verifyCount, 1)
        XCTAssertEqual(metrics.successCount, 1)
        XCTAssertEqual(metrics.failCount, 1)
        XCTAssertEqual(metrics.diagnosticsCount, 1)
        XCTAssertEqual(RecoveryDomain.allCases.map(\.title).count, 4)
    }

    func testNowPlayingAndSystemHealthSnapshotsCoverOperationalBranches() {
        var loaded = LyricDiagnostic.lightweight(
            trackId: "track",
            title: "Song",
            artist: "Artist",
            status: "loaded",
            reason: "",
            suggestion: "loaded"
        )
        loaded.lines = 20
        loaded.recoveryState = "idle"

        let healthyHealing = SelfHealingEngine.shared.evaluate(
            trackId: "snapshot-healthy",
            title: "Healthy",
            connection: makeConnectionSnapshot(),
            artwork: makeAlbumArtSnapshot(quality: .hq, transferState: "idle"),
            lyric: loaded,
            currentLyric: "line",
            fullLyricsLineCount: 20,
            isFullLyricsCurrent: true
        )
        let healthy = makeNowPlayingSnapshot(
            lyric: loaded,
            connection: makeConnectionSnapshot(),
            artworkQuality: "hq",
            transferState: "idle",
            hqUnavailableReason: "-",
            selfHealing: healthyHealing
        )
        XCTAssertEqual(healthy.recentIssues, ["状态正常"])
        XCTAssertTrue(healthy.canRequestHqArtwork)
        XCTAssertTrue(healthy.canForceReconnect)
        XCTAssertTrue(healthy.quickSnapshotText.contains("trackId=track"))
        XCTAssertTrue(healthy.diagnosticText.contains("Now Playing Diagnostics"))
        XCTAssertEqual(NowPlayingDiagnosticSnapshot.formatDuration(0), "00:00")
        XCTAssertEqual(NowPlayingDiagnosticSnapshot.formatDuration(125_000), "02:05")
        XCTAssertEqual(NowPlayingDiagnosticSnapshot.optionalDate(nil), "-")

        let healthySystem = SystemHealthSnapshot(nowPlaying: healthy)
        XCTAssertEqual(healthySystem.overallStatus, "系统正常")
        XCTAssertEqual(healthySystem.recommendation, "当前状态正常。")
        XCTAssertTrue(healthySystem.copyText.contains("系统健康总览"))

        var waiting = loaded
        waiting.status = "waiting_qqmusic_cache"
        waiting.waitingQqMusicCache = true
        waiting.suggestion = "open_qqmusic_lyrics"
        waiting.recoveryState = "recovering"
        let recoveringConnection = makeConnectionSnapshot(
            displayState: "reconnecting",
            healthState: "stale",
            autoReconnectState: "connecting",
            ready: false,
            notifyAgeMs: 12_000
        )
        let recoveringHealing = SelfHealingEngine.shared.evaluate(
            trackId: "snapshot-recovering",
            title: "Recovering",
            connection: recoveringConnection,
            artwork: makeAlbumArtSnapshot(
                quality: .placeholder,
                transferState: "timeout",
                failure: "timeout"
            ),
            lyric: waiting,
            currentLyric: "",
            fullLyricsLineCount: 0,
            isFullLyricsCurrent: false
        )
        let recovering = makeNowPlayingSnapshot(
            lyric: waiting,
            connection: recoveringConnection,
            artworkQuality: "placeholder",
            transferState: "timeout",
            transferFailure: "timeout",
            hqUnavailableReason: "too large",
            selfHealing: recoveringHealing
        )
        XCTAssertFalse(recovering.recentIssues.isEmpty)
        XCTAssertFalse(recovering.canRequestHqArtwork)
        XCTAssertTrue(recovering.canForceReconnect)
        let recoveringSystem = SystemHealthSnapshot(nowPlaying: recovering)
        XCTAssertEqual(recoveringSystem.recommendation, "先恢复 Sony 连接。")
        XCTAssertEqual(recoveringSystem.overallLevel, .warning)

        var maintenance = waiting
        maintenance.status = "maintenance_busy"
        maintenance.maintenanceBusy = true
        maintenance.waitingQqMusicCache = false
        let preview = makeNowPlayingSnapshot(
            lyric: maintenance,
            connection: makeConnectionSnapshot(healthState: "suspect", notifyAgeMs: 6_000),
            artworkQuality: "preview",
            transferState: "idle",
            hqUnavailableReason: "source unavailable",
            selfHealing: recoveringHealing
        )
        XCTAssertTrue(preview.recentIssues.contains { $0.contains("封面") })
        let previewSystem = SystemHealthSnapshot(nowPlaying: preview)
        XCTAssertFalse(previewSystem.recommendation.isEmpty)

        for level in [SystemHealthLevel.ok, .working, .warning, .critical, .unknown] {
            XCTAssertFalse(level.badgeTitle.isEmpty)
        }
    }

    func testFullLyricsCacheStoreSaveLoadRemoveAndPrune() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FullLyricsCacheStore(
            directoryURL: directory,
            maximumEntryCount: 2,
            maximumDiskBytes: 64 * 1024
        )
        let now = Date()
        for index in 1...3 {
            store.save(makeFullLyricsCacheEntry(trackId: "track-\(index)", savedAt: now))
        }
        store.save(makeFullLyricsCacheEntry(trackId: "", savedAt: now))

        _ = await loadLyricsCache(
            store,
            trackId: "drain",
            title: "Song",
            artist: "Artist",
            now: now
        )
        let files = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "json" }
        XCTAssertLessThanOrEqual(files.count, 2)

        let diskStore = FullLyricsCacheStore(directoryURL: directory)
        let loaded = await loadLyricsCache(
            diskStore,
            trackId: "track-3",
            title: " song ",
            artist: "ARTIST",
            now: now
        )
        XCTAssertEqual(loaded?.trackId, "track-3")
        let emptyTrack = await loadLyricsCache(
            diskStore,
            trackId: "",
            title: "Song",
            artist: "Artist",
            now: now
        )
        XCTAssertNil(emptyTrack)
        diskStore.remove(trackId: "")
        diskStore.remove(trackId: "track-3")
        _ = await loadLyricsCache(
            diskStore,
            trackId: "drain-2",
            title: "Song",
            artist: "Artist",
            now: now
        )
        let removed = await loadLyricsCache(
            FullLyricsCacheStore(directoryURL: directory),
            trackId: "track-3",
            title: "Song",
            artist: "Artist",
            now: now
        )
        XCTAssertNil(removed)
    }

    func testFullLyricsCacheEstimatedCostForEmptyAndOriginalOnlyLyrics() {
        let empty = makeFullLyricsCacheEntry(lines: [])
        XCTAssertEqual(FullLyricsCacheStore.estimatedCost(empty), 0)

        let originalOnly = makeFullLyricsCacheEntry(
            lines: [
                .init(
                    index: 0,
                    timeMs: 0,
                    durationMs: 1_000,
                    text: "plain text",
                    translation: nil,
                    romanization: nil,
                    words: []
                )
            ]
        )
        XCTAssertEqual(
            FullLyricsCacheStore.estimatedCost(originalOnly),
            "plain text".utf8.count
        )
    }

    func testFullLyricsCacheEstimatedCostIncludesWordAndSecondaryText() {
        let entry = makeFullLyricsCacheEntry(
            lines: [
                .init(
                    index: 0,
                    timeMs: 120,
                    durationMs: 2_500,
                    text: "原文",
                    translation: "translation",
                    romanization: "yuan wen",
                    words: [
                        .init(id: 0, startMs: 120, durationMs: 800, text: "原"),
                        .init(id: 1, startMs: 920, durationMs: 1_700, text: "文")
                    ]
                )
            ]
        )
        let expected: Int = "原文".utf8.count +
            "translation".utf8.count +
            "yuan wen".utf8.count +
            "原".utf8.count + 32 +
            "文".utf8.count + 32
        XCTAssertEqual(FullLyricsCacheStore.estimatedCost(entry), expected)
    }

    func testFullLyricsCacheEstimatedCostUsesUTF8ForLongUnicodeAndEmoji() {
        let unicode = String(repeating: "音乐🎵é", count: 512)
        let entry = makeFullLyricsCacheEntry(
            lines: [
                .init(
                    index: 0,
                    timeMs: 0,
                    durationMs: 1_000,
                    text: unicode,
                    translation: "🌍",
                    romanization: "🎧",
                    words: [
                        .init(id: 0, startMs: 0, durationMs: 1_000, text: "👩‍🎤")
                    ]
                )
            ]
        )
        let expected: Int = unicode.utf8.count + "🌍".utf8.count +
            "🎧".utf8.count + "👩‍🎤".utf8.count + 32
        XCTAssertEqual(FullLyricsCacheStore.estimatedCost(entry), expected)
    }

    func testFullLyricsCacheEstimatedCostPreservesLegacyRuleForLargeLyrics() {
        let lines: [FullLyricsCacheEntry.Line] = (0..<1_500).map { index in
            .init(
                index: index,
                timeMs: Int64(index * 1_000),
                durationMs: 1_000,
                text: "line-\(index)-歌词",
                translation: index.isMultiple(of: 2) ? "translation-\(index)" : nil,
                romanization: index.isMultiple(of: 3) ? "romanization-\(index)" : nil,
                words: [
                    .init(
                        id: index,
                        startMs: Int64(index * 1_000),
                        durationMs: 1_000,
                        text: "word-\(index)"
                    )
                ]
            )
        }
        var legacyExpected: Int = 0
        for line in lines {
            legacyExpected += line.text.utf8.count
            legacyExpected += line.translation?.utf8.count ?? 0
            legacyExpected += line.romanization?.utf8.count ?? 0
            for word in line.words {
                legacyExpected += word.text.utf8.count + 32
            }
        }
        XCTAssertEqual(
            FullLyricsCacheStore.estimatedCost(makeFullLyricsCacheEntry(lines: lines)),
            legacyExpected
        )
    }

    @MainActor
    func testLiveActivityArtworkThumbnailPipelineCompletes() async {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 160, height: 100)).image { context in
            UIColor.systemPurple.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 160, height: 100))
        }
        let result = await withCheckedContinuation { continuation in
            LiveActivityArtworkStore.shared.writeThumbnail(
                image: image,
                key: "coverage-artwork",
                revision: 1
            ) { result in
                continuation.resume(returning: result)
            }
        }
        XCTAssertFalse(result.messages.isEmpty)
        XCTAssertTrue(result.messages.first?.contains("LiveArtwork") == true)
        LiveActivityArtworkStore.shared.removeAll()
    }

    func testPlaybackHistoryStoreRoundTripAndDeduplication() async {
        let store = PlaybackHistoryStore.shared
        await clearPlaybackHistory(store)
        defer { Task { await self.clearPlaybackHistory(store) } }

        let first = makePlaybackHistorySession(id: 1, title: "First")
        let newer = makePlaybackHistorySession(id: 2, title: "Newer")
        let replacement = makePlaybackHistorySession(id: 1, title: "First Updated")
        let merged = await mergePlaybackHistory(store, sessions: [first, newer, replacement])
        XCTAssertEqual(merged.map(\.sessionId), [2, 1])
        XCTAssertEqual(merged.last?.title, "First Updated")

        let loadedSessions = await loadPlaybackHistorySessions(store)
        XCTAssertEqual(loadedSessions, merged)

        let syncState = PlaybackHistorySyncState(lastSyncedSessionId: 2)
        store.saveSyncState(syncState)
        let stats = makePlaybackStatsFixture()["WEEK"]!
        store.saveStats(stats)
        _ = await mergePlaybackHistory(store, sessions: [])
        let loadedSyncState = await loadPlaybackSyncState(store)
        let loadedStats = await loadPlaybackStats(store)
        XCTAssertEqual(loadedSyncState, syncState)
        XCTAssertEqual(loadedStats["WEEK"], stats)

        await clearPlaybackHistory(store)
        let clearedSessions = await loadPlaybackHistorySessions(store)
        let clearedSyncState = await loadPlaybackSyncState(store)
        XCTAssertTrue(clearedSessions.isEmpty)
        XCTAssertEqual(clearedSyncState.lastSyncedSessionId, 0)
    }

    func testArtworkImageCacheMemoryDiskDecodeAndMemoryWarning() async throws {
        let cache = ArtworkImageCache.shared
        cache.removeAllDecodedImages()
        let source = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 180)).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 320, height: 180))
        }
        let data = try XCTUnwrap(source.jpegData(compressionQuality: 0.85))
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("coverage-artwork-\(UUID().uuidString).jpg")
        try data.write(to: fileURL)
        defer { try? FileManager.default.removeItem(at: fileURL) }

        let diskImage = await cache.load(
            artworkId: "disk",
            quality: "hq",
            fileURL: fileURL,
            maximumPixelSize: 128
        )
        XCTAssertNotNil(diskImage)
        XCTAssertNotNil(
            cache.memoryImage(
                artworkId: "disk",
                quality: "hq",
                maximumPixelSize: 128
            )
        )

        let decoded = await withCheckedContinuation { continuation in
            cache.decode(
                data: data,
                artworkId: "decoded",
                quality: "preview",
                maximumPixelSize: 96
            ) { image in
                continuation.resume(returning: image)
            }
        }
        XCTAssertNotNil(decoded)
        cache.store(
            source,
            artworkId: "manual",
            quality: "preview",
            maximumPixelSize: 64
        )
        let memoryHit = await cache.load(
            artworkId: "manual",
            quality: "preview",
            fileURL: fileURL,
            maximumPixelSize: 64
        )
        XCTAssertNotNil(memoryHit)
        NotificationCenter.default.post(
            name: UIApplication.didReceiveMemoryWarningNotification,
            object: nil
        )
        XCTAssertNil(
            cache.memoryImage(
                artworkId: "manual",
                quality: "preview",
                maximumPixelSize: 64
            )
        )
        XCTAssertNil(ArtworkImageCache.downsampledImage(data: data, maximumPixelSize: 0))

        let ioCompleted = expectation(description: "artwork I/O queue")
        cache.performIO { ioCompleted.fulfill() }
        await fulfillment(of: [ioCompleted], timeout: 1)
    }

    func testLastNowPlayingSnapshotStoreSaveLoadAndClear() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let fileURL = directory.appendingPathComponent("snapshot.json")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LastNowPlayingSnapshotStore(fileURL: fileURL)
        let now = Date()
        let snapshot = LastNowPlayingSnapshot(
            version: LastNowPlayingSnapshot.version,
            trackId: "track-store",
            title: "Stored Song",
            artist: "Artist",
            album: "Album",
            wasPlaying: true,
            positionMs: 12_000,
            durationMs: 180_000,
            lyricLines: [
                .init(
                    index: 0,
                    timeMs: 0,
                    durationMs: 2_000,
                    text: "line",
                    translation: "translation",
                    romanization: "romanization",
                    words: [
                        .init(id: 0, startMs: 0, durationMs: 2_000, text: "line")
                    ]
                )
            ],
            albumArtId: "artwork-store",
            savedAt: now
        )
        store.save(snapshot)
        try await Task.sleep(nanoseconds: 450_000_000)
        let loaded = await loadLastNowPlaying(store, now: now)
        XCTAssertEqual(loaded, snapshot)

        store.save(
            LastNowPlayingSnapshot(
                version: LastNowPlayingSnapshot.version,
                trackId: "",
                title: "",
                artist: "",
                album: "",
                wasPlaying: false,
                positionMs: 0,
                durationMs: 0,
                lyricLines: [],
                albumArtId: "",
                savedAt: now
            )
        )
        store.clear()
        let cleared = await loadLastNowPlaying(store, now: now)
        XCTAssertNil(cleared)
    }

    func testAppLogStoreWritesReadsTimelineAndClears() async {
        let store = AppLogStore.shared
        await clearAppLogs(store)
        store.append("coverage regular message")
        store.append("[Coverage] categorized message")
        store.appendTimeline("timeline coverage message")

        let text = await readRecentAppLogs(store)
        XCTAssertTrue(text.contains("[App] coverage regular message"))
        XCTAssertTrue(text.contains("[Coverage] categorized message"))
        XCTAssertTrue(store.currentLogFileExists())
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.timelineLogURL.path))

        await clearTimelineLogs(store)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.timelineLogURL.path))
        await clearAppLogs(store)
        XCTAssertFalse(store.currentLogFileExists())
        let clearedText = await readRecentAppLogs(store)
        XCTAssertTrue(clearedText.isEmpty)
    }

    func testAppLanguageDefaultsToSimplifiedChinese() {
        XCTAssertEqual(AppLanguage.defaultLanguage, .simplifiedChinese)
        XCTAssertEqual(AppLanguage.simplifiedChinese.locale.identifier, "zh-Hans")
        XCTAssertEqual(AppLanguage.english.locale.identifier, "en")
    }

    func testLyricSecondaryRetryPolicySeparatesUnavailableAndTransientFailures() {
        XCTAssertEqual(
            LyricSecondaryRetryPolicy.action(
                explicitlyUnavailable: true,
                retryCount: 0
            ),
            .markUnavailable
        )
        XCTAssertEqual(
            LyricSecondaryRetryPolicy.action(
                explicitlyUnavailable: false,
                retryCount: 0
            ),
            .retry
        )
        XCTAssertEqual(
            LyricSecondaryRetryPolicy.action(
                explicitlyUnavailable: false,
                retryCount: 1
            ),
            .markFailed
        )
    }

    func testSystemReconnectPolicyDoesNotRaceCoreBluetoothReconnect() {
        XCTAssertFalse(
            SystemReconnectPolicy.shouldScheduleManualReconnect(
                autoReconnectEnabled: true,
                systemIsReconnecting: true
            )
        )
        XCTAssertTrue(
            SystemReconnectPolicy.shouldScheduleManualReconnect(
                autoReconnectEnabled: true,
                systemIsReconnecting: false
            )
        )
        XCTAssertFalse(
            SystemReconnectPolicy.shouldScheduleManualReconnect(
                autoReconnectEnabled: false,
                systemIsReconnecting: false
            )
        )
    }

    func testKaraokeHighlightUsesWordDurationWithinWord() {
        let words = [
            LyricWord(id: 0, startMs: 1_000, durationMs: 500, text: "Hello"),
            LyricWord(id: 1, startMs: 2_000, durationMs: 500, text: "world")
        ]
        let firstHalf = KaraokeHighlightResolver.resolve(
            text: "Hello world",
            words: words,
            positionMs: 1_250,
            fallbackProgress: 0
        )
        XCTAssertEqual(firstHalf.highlightedCharacterCount, 2)
        XCTAssertEqual(firstHalf.highlightedText, "He")
        XCTAssertEqual(firstHalf.highlightedCharacterProgress, 2.5, accuracy: 0.001)
        XCTAssertEqual(firstHalf.normalizedProgress, 2.5 / 11.0, accuracy: 0.001)

        let betweenWords = KaraokeHighlightResolver.resolve(
            text: "Hello world",
            words: words,
            positionMs: 1_750,
            fallbackProgress: 0
        )
        XCTAssertEqual(betweenWords.highlightedText, "Hello")

        let secondHalf = KaraokeHighlightResolver.resolve(
            text: "Hello world",
            words: words,
            positionMs: 2_250,
            fallbackProgress: 0
        )
        XCTAssertEqual(secondHalf.highlightedText, "Hello wo")

        let chineseHalf = KaraokeHighlightResolver.resolve(
            text: "你",
            words: [LyricWord(id: 0, startMs: 1_000, durationMs: 500, text: "你")],
            positionMs: 1_250,
            fallbackProgress: 0
        )
        XCTAssertEqual(chineseHalf.highlightedCharacterCount, 0)
        XCTAssertEqual(chineseHalf.highlightedCharacterProgress, 0.5, accuracy: 0.001)
        XCTAssertEqual(chineseHalf.normalizedProgress, 0.5, accuracy: 0.001)

        let projected = KaraokeHighlightResolver.resolve(
            text: "你好",
            words: [
                LyricWord(id: 0, startMs: 1_000, durationMs: 500, text: "你"),
                LyricWord(id: 1, startMs: 1_500, durationMs: 500, text: "好")
            ],
            positionMs: 1_250,
            fallbackProgress: 0,
            lookAheadMs: 250
        )
        XCTAssertEqual(projected.highlightedCharacterProgress, 1, accuracy: 0.001)
    }

    func testKaraokeProgressAnimationPolicySnapsCorrectionsAndReduceMotion() {
        XCTAssertEqual(
            KaraokeProgressAnimationPolicy.duration(
                for: .automatic,
                isLowPowerModeEnabled: false
            ),
            0.25,
            accuracy: 0.001
        )
        XCTAssertTrue(
            KaraokeProgressAnimationPolicy.shouldAnimate(
                from: 0.2,
                to: 0.3,
                isPlaying: true,
                reduceMotion: false
            )
        )
        XCTAssertFalse(
            KaraokeProgressAnimationPolicy.shouldAnimate(
                from: 0.6,
                to: 0.2,
                isPlaying: true,
                reduceMotion: false
            )
        )
        XCTAssertFalse(
            KaraokeProgressAnimationPolicy.shouldAnimate(
                from: 0.2,
                to: 0.3,
                isPlaying: true,
                reduceMotion: true
            )
        )
        XCTAssertFalse(
            KaraokeProgressAnimationPolicy.shouldAnimate(
                from: 0.2,
                to: 0.7,
                isPlaying: true,
                reduceMotion: false
            )
        )
    }

    func testFullLyricsCacheRejectsExpiredMismatchedAndCorruptEntries() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("entry.json")
        let now = Date()
        let entry = FullLyricsCacheEntry(
            version: FullLyricsCacheEntry.version,
            trackId: "track-1",
            title: "Song",
            artist: "Artist",
            album: "Album",
            lines: [
                FullLyricsCacheEntry.Line(
                    index: 0,
                    timeMs: 0,
                    durationMs: 1_000,
                    text: "line",
                    translation: "翻译",
                    romanization: nil,
                    words: [
                        FullLyricsCacheEntry.Word(
                            id: 0,
                            startMs: 0,
                            durationMs: 1_000,
                            text: "line"
                        )
                    ]
                )
            ],
            savedAt: now
        )
        try JSONEncoder().encode(entry).write(to: fileURL, options: .atomic)
        XCTAssertTrue(entry.matches(title: " song ", artist: "ARTIST"))
        XCTAssertFalse(entry.matches(title: "Other Song", artist: "Artist"))
        XCTAssertEqual(
            FullLyricsCacheStore.readEntry(
                fileURL: fileURL,
                expectedTrackId: "track-1",
                now: now
            )?.lines.first?.translation,
            "翻译"
        )
        XCTAssertNil(
            FullLyricsCacheStore.readEntry(
                fileURL: fileURL,
                expectedTrackId: "track-2",
                now: now
            )
        )
        XCTAssertNil(
            FullLyricsCacheStore.readEntry(
                fileURL: fileURL,
                expectedTrackId: "track-1",
                now: now.addingTimeInterval(FullLyricsCacheEntry.maximumAge + 1)
            )
        )

        try Data([0x00, 0x01, 0x02]).write(to: fileURL, options: .atomic)
        XCTAssertNil(
            FullLyricsCacheStore.readEntry(
                fileURL: fileURL,
                expectedTrackId: "track-1",
                now: now
            )
        )
    }

    func testWriteTimeoutDoesNotAdvanceQueueAndSuspendsInBackground() {
        XCTAssertEqual(
            CommandWriteTimeoutPolicy.action(
                appIsActive: false,
                transportReady: true,
                timeoutCountAfterIncrement: 1,
                reconnectThreshold: 2
            ),
            .suspendUntilForeground
        )
        XCTAssertEqual(
            CommandWriteTimeoutPolicy.action(
                appIsActive: true,
                transportReady: true,
                timeoutCountAfterIncrement: 1,
                reconnectThreshold: 2
            ),
            .extendWithoutAdvancingQueue
        )
        XCTAssertEqual(
            CommandWriteTimeoutPolicy.action(
                appIsActive: true,
                transportReady: true,
                timeoutCountAfterIncrement: 2,
                reconnectThreshold: 2
            ),
            .reconnect
        )
        XCTAssertEqual(
            CommandWriteTimeoutPolicy.action(
                appIsActive: true,
                transportReady: false,
                timeoutCountAfterIncrement: 1,
                reconnectThreshold: 2
            ),
            .reconnect
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

    func testMonotonicClockSyncAndAutomaticPlaybackCompensation() {
        var synchronizer = MonotonicClockSynchronizer()
        XCTAssertEqual(
            synchronizer.record(
                clientSendElapsedMs: 1_000,
                serverReceiveElapsedMs: 920,
                serverSendElapsedMs: 922,
                clientReceiveElapsedMs: 1_042
            )?.isConfident,
            false
        )
        _ = synchronizer.record(
            clientSendElapsedMs: 2_000,
            serverReceiveElapsedMs: 1_922,
            serverSendElapsedMs: 1_924,
            clientReceiveElapsedMs: 2_046
        )
        let snapshot = synchronizer.record(
            clientSendElapsedMs: 3_000,
            serverReceiveElapsedMs: 2_919,
            serverSendElapsedMs: 2_921,
            clientReceiveElapsedMs: 3_041
        )
        XCTAssertEqual(snapshot?.isConfident, true)
        XCTAssertEqual(snapshot?.bestRoundTripMs, 39)
        XCTAssertEqual(Int64(snapshot?.localMinusServerMs.rounded() ?? 0), 100)

        XCTAssertEqual(
            RemotePlaybackAnchorPolicy.resolve(
                remotePositionMs: 10_000,
                serverSampleElapsedMs: 4_000,
                localReceiveElapsedMs: 4_220,
                playbackSpeed: 1.0,
                isPlaying: true,
                durationMs: 60_000,
                synchronizer: synchronizer
            ),
            .resolved(positionMs: 10_120, transportAgeMs: 120)
        )
        XCTAssertEqual(
            RemotePlaybackAnchorPolicy.resolve(
                remotePositionMs: 10_000,
                measuredTransportAgeMs: 2_000,
                playbackSpeed: 1.0,
                isPlaying: true,
                durationMs: 60_000
            ),
            .stale(transportAgeMs: 2_000)
        )
        XCTAssertEqual(
            RemotePlaybackAnchorPolicy.resolve(
                remotePositionMs: 10_000,
                measuredTransportAgeMs: 200,
                playbackSpeed: 1.0,
                isPlaying: false,
                durationMs: 60_000
            ),
            .resolved(positionMs: 10_000, transportAgeMs: 200)
        )
        XCTAssertEqual(
            RemotePlaybackAnchorPolicy.smoothedPosition(
                currentPositionMs: 10_000,
                targetPositionMs: 10_200
            ),
            10_100
        )
    }

    func testAutomaticLyricSyncMigratesOnlyLegacyDefaultOffset() {
        XCTAssertEqual(
            PreferencesStore.migratedLegacyLyricOffset(
                storedOffset: 600,
                migrationCompleted: false
            ),
            0
        )
        XCTAssertEqual(
            PreferencesStore.migratedLegacyLyricOffset(
                storedOffset: 300,
                migrationCompleted: false
            ),
            300
        )
        XCTAssertEqual(
            PreferencesStore.migratedLegacyLyricOffset(
                storedOffset: 600,
                migrationCompleted: true
            ),
            600
        )
    }

    func testCompactConnectionAndVolumePresentation() {
        XCTAssertEqual(
            DarkControlConnectionState.connected.compactTitle,
            "Sony"
        )
        XCTAssertEqual(
            DarkControlConnectionState.disconnected.compactTitle,
            "连接"
        )
        XCTAssertTrue(DarkControlConnectionState.connecting.showsProgressIndicator)
        XCTAssertTrue(DarkControlConnectionState.reconnecting.showsProgressIndicator)
        XCTAssertFalse(DarkControlConnectionState.connected.showsProgressIndicator)

        XCTAssertEqual(
            CompactVolumePresentation.normalizedProgress(current: 8, maximum: 16),
            0.5,
            accuracy: 0.001
        )
        XCTAssertEqual(
            CompactVolumePresentation.normalizedProgress(current: 20, maximum: 15),
            1,
            accuracy: 0.001
        )
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

    func testProtocolCapabilitiesV1V2AndV3Negotiation() {
        let v1 = BLEProtocolV3Parser.capabilitiesAck(from: [:])
        XCTAssertEqual(v1.protocolVersion, 1)
        XCTAssertTrue(v1.v2Features.isEmpty)
        XCTAssertTrue(v1.v3Features.isEmpty)

        let v2 = BLEProtocolV3Parser.capabilitiesAck(from: [
            "protocolVersion": 2,
            "albumArtBinary": true,
            "fullLyricsZlib": true,
            "lyricWindow": true,
            "ping": true,
            "clockSyncV1": true,
            "transferRetry": true,
            "unknownCapability": true
        ])
        XCTAssertEqual(v2.protocolVersion, 2)
        XCTAssertEqual(v2.v2Features.rawValue, 63)
        XCTAssertTrue(v2.v3Features.isEmpty)

        let v3 = BLEProtocolV3Parser.capabilitiesAck(from: [
            "protocolVersion": 3,
            "f2": 63 | (1 << 12),
            "f3": 7 | (1 << 10),
            "sid": "sony-session-1"
        ])
        XCTAssertEqual(v3.protocolVersion, 3)
        XCTAssertTrue(v3.v2Features.contains(.albumArtBinary))
        XCTAssertTrue(v3.v2Features.contains(.transferRetry))
        XCTAssertTrue(v3.v3Features.contains(.statusMetaV1))
        XCTAssertTrue(v3.v3Features.contains(.structuredErrorV1))
        XCTAssertTrue(v3.v3Features.contains(.mediaLoadStateV1))
        XCTAssertEqual(v3.sessionId, "sony-session-1")
        XCTAssertEqual(v3.v3Features.intersection(.all).rawValue, 7)
    }

    func testProtocolRejectsMalformedAndOversizedJSON() {
        XCTAssertNil(BLEProtocolV3Parser.jsonObject(from: Data("{".utf8)))
        XCTAssertNil(BLEProtocolV3Parser.jsonObject(from: Data()))
        XCTAssertNil(
            BLEProtocolV3Parser.jsonObject(
                from: Data(repeating: 0x61, count: BLEProtocolV3Parser.maximumJSONNotifyBytes + 1)
            )
        )
        let unknown = BLEProtocolV3Parser.jsonObject(
            from: Data("{\"type\":\"futureNotify\",\"future\":true}".utf8)
        )
        XCTAssertEqual(unknown?["type"] as? String, "futureNotify")
    }

    func testStructuredCommandErrorAndMediaLoadStateParsing() throws {
        let commandError = try XCTUnwrap(BLEProtocolV3Parser.commandError(from: [
            "type": "commandError",
            "seq": 42,
            "cmd": "GET_FULL_LYRICS",
            "domain": "lyrics",
            "code": "qrc_not_ready",
            "retryable": true,
            "retryAfterMs": 800,
            "trackId": "track-1",
            "generation": 7,
            "sid": "session-1",
            "es": 10
        ]))
        XCTAssertEqual(commandError.sequence, 42)
        XCTAssertEqual(commandError.domain, .lyrics)
        XCTAssertTrue(commandError.retryable)
        XCTAssertEqual(commandError.retryAfterMs, 800)
        XCTAssertEqual(commandError.metadata?.eventSequence, 10)
        XCTAssertNil(BLEProtocolV3Parser.commandError(from: ["cmd": "GET_FULL_LYRICS"]))

        let media = try XCTUnwrap(BLEProtocolV3Parser.mediaLoadState(from: [
            "resource": "artwork",
            "stage": "transferring",
            "reason": "hq",
            "retryable": false,
            "trackId": "track-1",
            "generation": 7
        ]))
        XCTAssertEqual(media.resource, .artwork)
        XCTAssertEqual(media.stage, .transferring)
        XCTAssertEqual(media.deduplicationKey, "track-1|7|artwork|transferring|hq")
        XCTAssertEqual(media.deduplicationKey, media.deduplicationKey)
        XCTAssertNil(BLEProtocolV3Parser.mediaLoadState(from: [
            "resource": "future-resource",
            "stage": "ready",
            "trackId": "track-1"
        ]))
    }

    func testEventSequenceDiagnosticsNeverActsAsGlobalDropFence() {
        var diagnostics = BLEEventSequenceDiagnostics()
        XCTAssertEqual(
            diagnostics.observe(BLEStatusMetadata(sessionId: "s1", eventSequence: 1)),
            .first
        )
        XCTAssertEqual(
            diagnostics.observe(BLEStatusMetadata(sessionId: "s1", eventSequence: 1)),
            .duplicate
        )
        XCTAssertEqual(
            diagnostics.observe(BLEStatusMetadata(sessionId: "s1", eventSequence: 4)),
            .gap(missing: 2)
        )
        XCTAssertEqual(
            diagnostics.observe(BLEStatusMetadata(sessionId: "s1", eventSequence: 2)),
            .outOfOrder
        )
        XCTAssertEqual(diagnostics.highestSequence, 4)
        XCTAssertEqual(
            diagnostics.observe(BLEStatusMetadata(sessionId: "s2", eventSequence: 1)),
            .newSession
        )
    }

    func testFocusedStoresAndResponsiveLayoutModes() {
        let connection = ConnectionStore()
        connection.update(
            state: BLEConnectionViewState(status: "已连接"),
            presentation: .connected(deviceName: "Sony", health: "healthy")
        )
        XCTAssertTrue(connection.presentation.isConnected)

        let playback = PlaybackStore()
        playback.updateMetadata(BLEPlaybackMetadataState(title: "Song", artist: "Artist", album: "Album"))
        playback.updateTimeline(BLEPlaybackTimelineState(isPlaying: true, durationMs: 60_000))
        playback.updateVolume(BLEVolumeViewState(current: 8, maximum: 15, seekValue: 8))
        XCTAssertEqual(playback.metadata.title, "Song")
        XCTAssertTrue(playback.timeline.isPlaying)
        XCTAssertEqual(playback.volume.current, 8)

        XCTAssertEqual(
            PlayerLayoutMode.resolve(availableHeight: 667, dynamicTypeSize: .large),
            .compact
        )
        XCTAssertEqual(
            PlayerLayoutMode.resolve(availableHeight: 852, dynamicTypeSize: .large),
            .regular
        )
        XCTAssertEqual(
            PlayerLayoutMode.resolve(
                availableHeight: 852,
                dynamicTypeSize: .accessibility3
            ),
            .accessibility
        )

        let seMetrics = PlayerLayoutMetrics.resolve(
            availableSize: CGSize(width: 375, height: 667),
            safeAreaInsets: EdgeInsets(top: 20, leading: 0, bottom: 0, trailing: 0),
            dynamicTypeSize: .large,
            artworkPreference: .large
        )
        XCTAssertEqual(seMetrics.mode, .compact)
        XCTAssertTrue((112...144).contains(seMetrics.artworkSize))

        let standardMetrics = PlayerLayoutMetrics.resolve(
            availableSize: CGSize(width: 393, height: 852),
            safeAreaInsets: EdgeInsets(top: 59, leading: 0, bottom: 34, trailing: 0),
            dynamicTypeSize: .large,
            artworkPreference: .medium
        )
        XCTAssertEqual(standardMetrics.mode, .regular)
        XCTAssertEqual(standardMetrics.artworkSize, 224)

        let largeMetrics = PlayerLayoutMetrics.resolve(
            availableSize: CGSize(width: 430, height: 932),
            safeAreaInsets: EdgeInsets(top: 59, leading: 0, bottom: 34, trailing: 0),
            dynamicTypeSize: .large,
            artworkPreference: .large
        )
        XCTAssertEqual(largeMetrics.mode, .regular)
        XCTAssertEqual(largeMetrics.artworkSize, 272)
    }

    func testProductUiStateMappingsAndLegacyArtworkMigration() {
        XCTAssertEqual(ArtworkDisplaySizeOption.small.pointSize, 184)
        XCTAssertEqual(ArtworkDisplaySizeOption.medium.pointSize, 224)
        XCTAssertEqual(ArtworkDisplaySizeOption.large.pointSize, 272)
        XCTAssertEqual(PreferencesStore.migratedArtworkDisplaySize(storedValue: 200), .small)
        XCTAssertEqual(PreferencesStore.migratedArtworkDisplaySize(storedValue: 220), .medium)
        XCTAssertEqual(PreferencesStore.migratedArtworkDisplaySize(storedValue: 260), .large)

        XCTAssertEqual(
            ConnectionStatusPresentation.resolve(.connected(deviceName: "Sony", health: "healthy")).title,
            "已连接"
        )
        XCTAssertEqual(
            ConnectionStatusPresentation.resolve(.reconnecting(deviceName: "Sony", attempt: 2)).title,
            "正在重连"
        )
        let banner = ReconnectBannerPresentation.resolve(
            connection: .disconnected(lastDeviceName: "Sony"),
            hasSnapshot: true
        )
        XCTAssertEqual(banner?.showsRetry, true)
        XCTAssertNil(ReconnectBannerPresentation.resolve(
            connection: .disconnected(lastDeviceName: "Sony"),
            hasSnapshot: false
        ))
        XCTAssertTrue(NowPlayingSnapshotPolicy.hasDisplayableSnapshot(
            title: "Song",
            artist: "Artist",
            hasArtwork: false,
            isRestoredSnapshot: false
        ))
        XCTAssertFalse(NowPlayingSnapshotPolicy.hasDisplayableSnapshot(
            title: "-",
            artist: "等待同步",
            hasArtwork: false,
            isRestoredSnapshot: false
        ))
    }

    func testFullLyricsFollowStateOnlyResumesExplicitlyOrOnTrackChange() {
        var state = FullLyricsFollowState()
        XCTAssertFalse(state.showsReturnToCurrent)
        state.userDidBrowse()
        XCTAssertTrue(state.showsReturnToCurrent)
        state.returnToCurrent()
        XCTAssertFalse(state.showsReturnToCurrent)
        state.userDidBrowse()
        state.trackDidChange()
        XCTAssertFalse(state.showsReturnToCurrent)
    }

    func testLiveArtworkRevisionFenceRejectsLateCompletion() {
        var fence = LiveActivityArtworkRevisionFence()
        let first = fence.begin()
        let second = fence.begin()
        XCTAssertFalse(fence.accepts(first))
        XCTAssertTrue(fence.accepts(second))
        fence.invalidate()
        XCTAssertFalse(fence.accepts(second))
    }

    func testDecodeOneThousandV3StatusMessagesPerformance() {
        let payload = Data(
            "{\"type\":\"mediaLoadState\",\"resource\":\"lyrics\",\"stage\":\"transferring\",\"reason\":\"qrc\",\"trackId\":\"t1\",\"generation\":7,\"sid\":\"s1\",\"es\":99}"
                .utf8
        )
        measure {
            for _ in 0..<1_000 {
                let object = BLEProtocolV3Parser.jsonObject(from: payload)
                XCTAssertNotNil(object.flatMap(BLEProtocolV3Parser.mediaLoadState(from:)))
            }
        }
    }

    func testRealtimeTraceUsesMonotonicClockAndFixedRingCapacity() {
        var samples: [Int64] = [100, 90, 110, 120]
        let buffer = RealtimeTraceBuffer(capacity: 3) {
            samples.removeFirst()
        }

        buffer.append(stage: "one")
        buffer.append(stage: "two")
        buffer.append(stage: "three")
        buffer.append(stage: "four")

        let snapshot = buffer.snapshot()
        XCTAssertEqual(snapshot.map(\.stage), ["two", "three", "four"])
        XCTAssertEqual(snapshot.map(\.monoMs), [100, 110, 120])
        XCTAssertEqual(snapshot.map(\.sequence), [2, 3, 4])
    }

    func testRealtimeTraceStableLineAndMissingStageSummary() {
        var lines: [String] = []
        let store = RealtimeTraceStore(
            capacity: 4,
            enabled: true,
            clock: { 42 },
            logSink: { lines.append($0) }
        )
        store.record(
            stage: "playbackStatePublished",
            trackId: "track 1",
            generation: 7,
            result: "ok"
        )
        store.record(stage: "currentWordPublished", result: nil)

        XCTAssertEqual(store.summary().eventCount, 2)
        XCTAssertEqual(store.summary().missingResultCount, 1)
        XCTAssertEqual(store.summary().stageCounts["currentWordPublished"], 1)
        XCTAssertEqual(lines.count, 2)
        XCTAssertTrue(lines[0].contains("trackId=track_1"))
        XCTAssertTrue(lines[0].contains("payloadType=-"))
        XCTAssertFalse(lines[0].contains("lyrics="))
        XCTAssertFalse(lines[0].contains("bytes="))
    }

    func testRealtimePercentilesAndUntrustedClockPolicy() {
        let values: [Int64] = [10, 20, 30, 40, 50]
        let p50 = try? XCTUnwrap(
            RealtimeTraceStatistics.percentile(values, percentile: 50)
        )
        let p95 = try? XCTUnwrap(
            RealtimeTraceStatistics.percentile(values, percentile: 95)
        )
        XCTAssertEqual(p50, 30)
        XCTAssertEqual(p95 ?? 0, 48, accuracy: 0.001)
        XCTAssertNil(RealtimeTraceStatistics.percentile([], percentile: 50))
        XCTAssertNil(
            RealtimeLatencyPolicy.crossDeviceDuration(
                startMonoMs: 10,
                endMonoMs: 30,
                clockTrusted: false
            )
        )
        XCTAssertEqual(
            RealtimeLatencyPolicy.crossDeviceDuration(
                startMonoMs: 10,
                endMonoMs: 30,
                clockTrusted: true
            ),
            20
        )
        XCTAssertNil(
            RealtimeLatencyPolicy.crossDeviceDuration(
                startMonoMs: 30,
                endMonoMs: 10,
                clockTrusted: true
            )
        )
    }

    @MainActor
    private func assertViewRenders(
        _ view: AnyView,
        named name: String,
        width: CGFloat = 390,
        height: CGFloat
    ) {
        let size = CGSize(width: width, height: height)
        let host = UIHostingController(
            rootView: view
                .environment(\.locale, PreferencesStore.shared.appLanguage.locale)
                .frame(width: width, height: height)
        )
        let windowScene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first
        let previousKeyWindow = windowScene?.windows.first(where: \.isKeyWindow)
        let window = windowScene.map(UIWindow.init(windowScene:)) ?? UIWindow(frame: CGRect(origin: .zero, size: size))
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.loadViewIfNeeded()
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.08))
        host.view.layoutIfNeeded()

        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            host.view.layer.render(in: context.cgContext)
        }
        XCTAssertEqual(image.size.width, width, accuracy: 0.1, name)
        XCTAssertEqual(image.size.height, height, accuracy: 0.1, name)

        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKeyAndVisible()
    }

    private func makePlaybackStatsFixture() -> [String: PlaybackStatsSnapshot] {
        let track = PlaybackTopTrack(
            trackKey: "track-1",
            title: "测试歌曲",
            artist: "测试歌手",
            album: "测试专辑",
            artworkId: nil,
            listenedMs: 180_000,
            playCount: 3,
            completedCount: 2,
            skippedCount: 1
        )
        let artist = PlaybackTopArtist(
            artist: "测试歌手",
            listenedMs: 360_000,
            playCount: 6,
            trackCount: 2
        )
        let dailyTrend = [
            DailyListenStat(dateKey: "2026-08-12", listenedMs: 120_000, playCount: 2),
            DailyListenStat(dateKey: "2026-08-13", listenedMs: 240_000, playCount: 4)
        ]
        func snapshot(_ range: String, multiplier: Int64) -> PlaybackStatsSnapshot {
            PlaybackStatsSnapshot(
                range: range,
                rangeStart: 1_723_430_400_000,
                rangeEnd: 1_723_516_800_000,
                totalListenMs: 360_000 * multiplier,
                playCount: Int(6 * multiplier),
                uniqueTrackCount: 2,
                completedCount: Int(4 * multiplier),
                skippedCount: Int(multiplier),
                completionRate: 0.67,
                skipRate: 0.17,
                topTracks: [track],
                topArtists: [artist],
                dailyTrend: dailyTrend
            )
        }
        return [
            "TODAY": snapshot("TODAY", multiplier: 1),
            "WEEK": snapshot("WEEK", multiplier: 3),
            "MONTH": snapshot("MONTH", multiplier: 8)
        ]
    }

    private func makeConnectionSnapshot(
        displayState: String = "connected",
        healthState: String = "healthy",
        autoReconnectState: String = "idle",
        ready: Bool = true,
        notifyAgeMs: Int64 = 100
    ) -> ConnectionDiagnosticSnapshot {
        ConnectionDiagnosticSnapshot(
            connectionStatus: displayState == "connected" ? "已连接" : "未连接",
            displayState: displayState,
            healthState: healthState,
            autoReconnectState: autoReconnectState,
            autoReconnectAttempt: 1,
            mtuBytes: ready ? 185 : 0,
            lastNotifyAgeMs: notifyAgeMs,
            peripheralState: displayState,
            characteristicReady: ready,
            probeInFlight: healthState == "suspect",
            lastHardReconnectReason: "coverage",
            reconnectWorkItemExists: displayState == "reconnecting"
        )
    }

    private func makeAlbumArtSnapshot(
        quality: ArtworkDisplayQuality,
        transferState: String,
        failure: String = "-"
    ) -> AlbumArtSnapshot {
        AlbumArtSnapshot(
            id: "artwork-id",
            image: nil,
            displayQuality: quality,
            displayPixelWidth: quality == .placeholder ? 0 : 600,
            displayPixelHeight: quality == .placeholder ? 0 : 600,
            enhancementStatus: ArtworkEnhancementDebugStatus(
                displayQuality: quality,
                lastMessage: "coverage"
            ),
            caches: [
                .missing(quality: "preview"),
                .missing(quality: "hq"),
                .missing(quality: "enhanced")
            ],
            hqUnavailableReason: "-",
            hqUnavailableBestBytes: 0,
            hqUnavailableBestChunks: 0,
            hqUnavailableMinCandidateScale: 0,
            transfer: AlbumArtTransferDiagnosticSnapshot(
                state: transferState,
                quality: quality.label,
                receivedChunks: transferState == "receiving" ? 2 : 0,
                totalChunks: transferState == "receiving" ? 4 : 0,
                lastFailureReason: failure,
                previewRetryCount: 0,
                hqRetryCount: 0
            ),
            predictive: PredictiveAlbumArtSnapshot(
                lastAlbumArtId: "artwork-id",
                pendingHq: false,
                pendingHqId: "",
                lastSkipReason: "-",
                offerCount: 1,
                hqPrefetchScheduled: 0,
                hqPrefetchSent: 0,
                hqPrefetchSkippedCacheHit: 0,
                hqPrefetchSkippedInFlight: 0,
                hqPrefetchSkippedNotConnected: 0,
                hqPrefetchCancelledTrackChanged: 0,
                hqArrivedBeforeDisplayCount: 0,
                avgOfferToHqRequestMs: 0,
                avgOfferToHqReadyMs: 0,
                lastOfferToHqRequestMs: 0,
                lastOfferToHqReadyMs: 0
            )
        )
    }

    private func makeNowPlayingSnapshot(
        lyric: LyricDiagnostic?,
        connection: ConnectionDiagnosticSnapshot,
        artworkQuality: String,
        transferState: String,
        transferFailure: String = "-",
        hqUnavailableReason: String,
        selfHealing: SelfHealingSnapshot
    ) -> NowPlayingDiagnosticSnapshot {
        NowPlayingDiagnosticSnapshot(
            generatedAt: Date(timeIntervalSince1970: 1_723_500_000),
            title: "Song",
            artist: "Artist",
            album: "Album",
            trackId: "track",
            albumArtId: "artwork-id",
            albumArtDisplayQuality: artworkQuality,
            displayArtworkPixelWidth: artworkQuality == "placeholder" ? 0 : 600,
            displayArtworkPixelHeight: artworkQuality == "placeholder" ? 0 : 600,
            artworkEnhancementStatus: ArtworkEnhancementDebugStatus(
                enabled: false,
                displayQuality: artworkQuality == "hq" ? .hq :
                    (artworkQuality == "preview" ? .preview : .placeholder),
                lastMessage: "coverage"
            ),
            artworkCaches: [
                ArtworkCacheDiagnostic(
                    quality: "preview",
                    exists: artworkQuality != "placeholder",
                    bytes: 1_024,
                    pixelWidth: 300,
                    pixelHeight: 300,
                    isPlaceholder: false,
                    modifiedAt: Date(timeIntervalSince1970: 1_723_500_000),
                    path: "/tmp/preview"
                ),
                artworkQuality == "hq"
                    ? ArtworkCacheDiagnostic(
                        quality: "hq",
                        exists: true,
                        bytes: 8_192,
                        pixelWidth: 800,
                        pixelHeight: 800,
                        isPlaceholder: false,
                        modifiedAt: Date(timeIntervalSince1970: 1_723_500_000),
                        path: "/tmp/hq"
                    )
                    : .missing(quality: "hq"),
                .missing(quality: "enhanced")
            ],
            hqUnavailableReason: hqUnavailableReason,
            hqUnavailableBestBytes: 10_000,
            hqUnavailableBestChunks: 4,
            hqUnavailableMinCandidateScale: 2,
            albumArtTransfer: AlbumArtTransferDiagnosticSnapshot(
                state: transferState,
                quality: artworkQuality,
                receivedChunks: 1,
                totalChunks: 3,
                lastFailureReason: transferFailure,
                previewRetryCount: 1,
                hqRetryCount: 1
            ),
            predictiveAlbumArt: PredictiveAlbumArtDiagnosticSnapshot(
                lastAlbumArtId: "artwork-id",
                pendingHq: artworkQuality == "preview",
                pendingHqId: artworkQuality == "preview" ? "artwork-id" : "",
                lastSkipReason: "-",
                offerCount: 2,
                hqPrefetchScheduled: 1,
                hqPrefetchSent: 1,
                hqPrefetchSkippedCacheHit: 0,
                hqPrefetchSkippedInFlight: 0,
                hqPrefetchSkippedNotConnected: 0,
                hqPrefetchCancelledTrackChanged: 0,
                hqArrivedBeforeDisplayCount: 0,
                avgOfferToHqRequestMs: 120,
                avgOfferToHqReadyMs: 700,
                lastOfferToHqRequestMs: 110,
                lastOfferToHqReadyMs: 650
            ),
            isPlaying: true,
            positionMs: 65_000,
            durationMs: 240_000,
            currentLyric: (lyric?.lines ?? 0) > 0 ? "line" : "",
            lyricDiagnostic: lyric,
            fullLyricsLineCount: lyric?.lines ?? 0,
            isFullLyricsCurrent: (lyric?.lines ?? 0) > 0,
            isFullLyricsReceiving: transferState == "receiving",
            currentWord: CurrentWordDiagnosticSnapshot(
                lineIndex: 1,
                wordIndex: 2,
                pushCount: 10,
                dropCount: 1,
                averageUpdateIntervalMs: 120,
                lastLatencyMs: 30,
                automaticSyncEnabled: true,
                automaticCompensationMs: 20,
                manualFineTuneMs: 0,
                legacyFallbackMs: 0,
                clockBestRoundTripMs: 25,
                clockOffsetJitterMs: 3,
                clockSampleCount: 5,
                clockSyncConfident: true
            ),
            connection: connection,
            selfHealing: selfHealing
        )
    }

    private func makeFullLyricsCacheEntry(
        trackId: String,
        savedAt: Date
    ) -> FullLyricsCacheEntry {
        FullLyricsCacheEntry(
            version: FullLyricsCacheEntry.version,
            trackId: trackId,
            title: "Song",
            artist: "Artist",
            album: "Album",
            lines: [
                .init(
                    index: 0,
                    timeMs: 0,
                    durationMs: 1_000,
                    text: "line",
                    translation: "translation",
                    romanization: "romanization",
                    words: [
                        .init(id: 0, startMs: 0, durationMs: 1_000, text: "line")
                    ]
                )
            ],
            savedAt: savedAt
        )
    }

    private func makeFullLyricsCacheEntry(
        lines: [FullLyricsCacheEntry.Line]
    ) -> FullLyricsCacheEntry {
        FullLyricsCacheEntry(
            version: FullLyricsCacheEntry.version,
            trackId: "cost-test",
            title: "Song",
            artist: "Artist",
            album: "Album",
            lines: lines,
            savedAt: Date(timeIntervalSince1970: 1_723_500_000)
        )
    }

    private func loadLyricsCache(
        _ store: FullLyricsCacheStore,
        trackId: String,
        title: String,
        artist: String,
        now: Date
    ) async -> FullLyricsCacheEntry? {
        await withCheckedContinuation { continuation in
            store.load(
                trackId: trackId,
                title: title,
                artist: artist,
                now: now
            ) { entry in
                continuation.resume(returning: entry)
            }
        }
    }

    private func makePlaybackHistorySession(
        id: Int64,
        title: String
    ) -> PlaybackHistorySession {
        PlaybackHistorySession(
            sessionId: id,
            trackKey: "track-\(id)",
            title: title,
            artist: "Artist",
            album: "Album",
            artworkId: nil,
            startedAt: 1_723_500_000_000 + id,
            endedAt: 1_723_500_060_000 + id,
            listenedMs: 60_000,
            durationMs: 180_000,
            completed: false,
            skipped: false,
            countedPlay: true
        )
    }

    private func mergePlaybackHistory(
        _ store: PlaybackHistoryStore,
        sessions: [PlaybackHistorySession]
    ) async -> [PlaybackHistorySession] {
        await withCheckedContinuation { continuation in
            store.mergeSessions(sessions) { result in
                continuation.resume(returning: result)
            }
        }
    }

    private func loadPlaybackHistorySessions(
        _ store: PlaybackHistoryStore
    ) async -> [PlaybackHistorySession] {
        await withCheckedContinuation { continuation in
            store.loadSessions { continuation.resume(returning: $0) }
        }
    }

    private func loadPlaybackSyncState(
        _ store: PlaybackHistoryStore
    ) async -> PlaybackHistorySyncState {
        await withCheckedContinuation { continuation in
            store.loadSyncState { continuation.resume(returning: $0) }
        }
    }

    private func loadPlaybackStats(
        _ store: PlaybackHistoryStore
    ) async -> [String: PlaybackStatsSnapshot] {
        await withCheckedContinuation { continuation in
            store.loadStats { continuation.resume(returning: $0) }
        }
    }

    private func clearPlaybackHistory(_ store: PlaybackHistoryStore) async {
        await withCheckedContinuation { continuation in
            store.clear { continuation.resume() }
        }
    }

    private func loadLastNowPlaying(
        _ store: LastNowPlayingSnapshotStore,
        now: Date
    ) async -> LastNowPlayingSnapshot? {
        await withCheckedContinuation { continuation in
            store.load(now: now) { continuation.resume(returning: $0) }
        }
    }

    private func readRecentAppLogs(_ store: AppLogStore) async -> String {
        await withCheckedContinuation { continuation in
            store.readRecentText { continuation.resume(returning: $0) }
        }
    }

    private func clearAppLogs(_ store: AppLogStore) async {
        await withCheckedContinuation { continuation in
            store.clear { continuation.resume() }
        }
    }

    private func clearTimelineLogs(_ store: AppLogStore) async {
        await withCheckedContinuation { continuation in
            store.clearTimeline { continuation.resume() }
        }
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
