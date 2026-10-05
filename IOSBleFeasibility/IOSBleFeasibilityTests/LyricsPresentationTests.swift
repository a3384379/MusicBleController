import AVFoundation
import CoreImage
import UIKit
import XCTest
@testable import sonyMusic

final class LyricsPresentationTests: XCTestCase {
    func testNewAndLegacyPreferencesDefaultOffAndPersistAllFourCombinations() {
        let name = "lyrics-display-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("lyricFocused", forKey: DynamicIslandStyle.userDefaultsKey)
        XCTAssertFalse(LyricsDisplayPreferences(defaults: defaults).compactEnabled)
        XCTAssertFalse(LyricsDisplayPreferences(defaults: defaults).floatingEnabled)
        for compact in [false, true] {
            for floating in [false, true] {
                defaults.set(compact, forKey: LyricsDisplayPreferences.compactKey)
                defaults.set(floating, forKey: LyricsDisplayPreferences.floatingKey)
                let reopened = LyricsDisplayPreferences(defaults: UserDefaults(suiteName: name)!)
                XCTAssertEqual(reopened.compactEnabled, compact)
                XCTAssertEqual(reopened.floatingEnabled, floating)
            }
        }
    }

    @MainActor
    func testPreferencesReloadAndResetKeepSwitchesIndependent() {
        let preferences = PreferencesStore.shared
        let original = UserDefaults.standard.dictionaryRepresentation()
        defer {
            for (key, value) in original { UserDefaults.standard.set(value, forKey: key) }
            for key in [LyricsDisplayPreferences.compactKey, LyricsDisplayPreferences.floatingKey]
                where original[key] == nil { UserDefaults.standard.removeObject(forKey: key) }
            preferences.load()
        }
        preferences.compactLyricsEnabled = true
        preferences.floatingLyricsEnabled = false
        preferences.load()
        XCTAssertTrue(preferences.compactLyricsEnabled)
        XCTAssertFalse(preferences.floatingLyricsEnabled)
        preferences.floatingLyricsEnabled = true
        preferences.compactLyricsEnabled = false
        preferences.load()
        XCTAssertFalse(preferences.compactLyricsEnabled)
        XCTAssertTrue(preferences.floatingLyricsEnabled)
        preferences.resetToDefaults()
        XCTAssertFalse(preferences.compactLyricsEnabled)
        XCTAssertFalse(preferences.floatingLyricsEnabled)
    }

    func testOldActivityContentDecodesWithCompactLyricsOff() throws {
        let state = makeContent()
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(state)) as? [String: Any])
        json.removeValue(forKey: "compactLyricsEnabled")
        let old = try JSONDecoder().decode(SonyMusicActivityAttributes.ContentState.self,
                                         from: JSONSerialization.data(withJSONObject: json))
        XCTAssertFalse(old.compactLyricsEnabled)
        XCTAssertEqual(old.lyric, state.lyric)
        XCTAssertEqual(old.dynamicIslandStyle, DynamicIslandStyle.lyricFocused.rawValue)
    }

    func testActivityLayoutSwitchChangesContentWithoutChangingLyricsOrPlayback() throws {
        let old = makeContent()
        var new = old
        new.compactLyricsEnabled = true
        XCTAssertNotEqual(old, new)
        XCTAssertEqual(old.lyric, new.lyric)
        XCTAssertEqual(old.isPlaying, new.isPlaying)
        XCTAssertTrue(try JSONDecoder().decode(SonyMusicActivityAttributes.ContentState.self,
                                             from: JSONEncoder().encode(new)).compactLyricsEnabled)
    }

    func testWorstUnicodeAndEscapedActivityPayloadStaysBelowFourKilobytes() throws {
        let raw = String(repeating: "👨‍👩‍👧‍👦\"\\\u{0}\n", count: 500)
        var state = makeContent()
        state.title = LiveActivityPayloadPolicy.boundedText(raw, characterLimit: 48)
        state.artist = LiveActivityPayloadPolicy.boundedText(raw, characterLimit: 40)
        state.lyric = LiveActivityPayloadPolicy.boundedText(raw, characterLimit: 80)
        state.trackId = LiveActivityPayloadPolicy.boundedText(raw, characterLimit: 64)
        state.artworkKey = LiveActivityPayloadPolicy.boundedText(raw, characterLimit: 64)
        state.compactLyricsEnabled = true
        XCTAssertLessThan(try JSONEncoder().encode(state).count, 4_096)
        XCTAssertLessThanOrEqual(state.lyric.utf8.count, 320)
        XCTAssertFalse(state.lyric.contains("\u{0}"))
    }

    func testPictureInPictureBecomesActiveOnlyAfterSuccessfulCallback() throws {
        var machine = LyricsPiPStateMachine()
        machine.setEnabled(true)
        let generation = try XCTUnwrap(machine.prepare())
        XCTAssertEqual(machine.state, .preparing)
        machine.ready(generation: generation)
        XCTAssertTrue(machine.start(generation: generation))
        XCTAssertEqual(machine.state, .starting)
        XCTAssertTrue(machine.didStart(generation: generation))
        XCTAssertEqual(machine.state, .active)
    }

    func testLateStartCannotReopenAfterDisableEvenIfPreferenceIsEnabledAgain() throws {
        var machine = LyricsPiPStateMachine()
        machine.setEnabled(true)
        let old = try XCTUnwrap(machine.prepare())
        machine.ready(generation: old)
        XCTAssertTrue(machine.start(generation: old))
        machine.setEnabled(false)
        XCTAssertFalse(machine.didStart(generation: old))
        machine.didStop()
        XCTAssertEqual(machine.state, .disabled)
        machine.setEnabled(true)
        let new = try XCTUnwrap(machine.prepare())
        XCTAssertNotEqual(old, new)
        XCTAssertFalse(machine.didStart(generation: old))
        XCTAssertEqual(machine.state, .preparing)
    }

    func testUserOrSystemStopPreservesPreferenceAndRequiresExplicitRestart() throws {
        var machine = LyricsPiPStateMachine()
        machine.setEnabled(true)
        let token = try XCTUnwrap(machine.prepare())
        machine.ready(generation: token)
        XCTAssertTrue(machine.start(generation: token))
        XCTAssertTrue(machine.didStart(generation: token))
        machine.stop()
        machine.didStop()
        XCTAssertTrue(machine.enabled)
        XCTAssertEqual(machine.state, .stopped)
        XCTAssertFalse(machine.didStart(generation: token))
    }

    func testFailuresPreservePreferenceAndAllowNewPreparation() {
        var machine = LyricsPiPStateMachine()
        machine.setEnabled(true)
        machine.fail(unavailable: true)
        XCTAssertTrue(machine.enabled)
        XCTAssertEqual(machine.state, .unavailable)
        XCTAssertNotNil(machine.prepare())
        machine.fail()
        XCTAssertEqual(machine.state, .failed)
        machine.setEnabled(false)
        XCTAssertEqual(machine.state, .disabled)
    }

    func testRepeatedTextStillChangesIdentityForLineGenerationAndSeek() {
        let original = snapshot()
        XCTAssertNotEqual(original.key, snapshot(lineIndex: 2).key)
        XCTAssertNotEqual(original.key, snapshot(generation: 2).key)
        XCTAssertNotEqual(original.key, snapshot(timeline: 2).key)
        XCTAssertEqual(original.key, snapshot().key)
    }

    func testFrameBurstKeepsOnlyLatestAndRejectsSupersededCompletion() throws {
        var queue = LatestLyricsFrameQueue()
        XCTAssertTrue(queue.offer(snapshot(), force: false))
        let first = try XCTUnwrap(queue.begin())
        for index in 2...1_000 { XCTAssertTrue(queue.offer(snapshot(lineIndex: index), force: false)) }
        XCTAssertNil(queue.begin())
        XCTAssertFalse(queue.finish(first.token, currentKey: snapshot(lineIndex: 1_000).key))
        let latest = try XCTUnwrap(queue.begin())
        XCTAssertEqual(latest.snapshot.lineIndex, 1_000)
        XCTAssertTrue(queue.finish(latest.token, currentKey: latest.snapshot.key))
        XCTAssertNil(queue.begin())
    }

    func testSameLineWordEventsDoNotScheduleExtraFrames() throws {
        var queue = LatestLyricsFrameQueue()
        XCTAssertTrue(queue.offer(snapshot(), force: false))
        let request = try XCTUnwrap(queue.begin())
        for _ in 0..<1_000 { XCTAssertFalse(queue.offer(snapshot(), force: false)) }
        XCTAssertTrue(queue.finish(request.token, currentKey: request.snapshot.key))
        XCTAssertNil(queue.begin())
    }

    func testDisabledOrChangedAuthorityRejectsLatePixelFrames() throws {
        var queue = LatestLyricsFrameQueue()
        XCTAssertTrue(queue.offer(snapshot(), force: false))
        let old = try XCTUnwrap(queue.begin())
        queue.invalidate()
        let newSnapshot = snapshot(generation: 2)
        XCTAssertTrue(queue.offer(newSnapshot, force: false))
        let new = try XCTUnwrap(queue.begin())
        XCTAssertFalse(queue.finish(old.token, currentKey: newSnapshot.key))
        XCTAssertFalse(queue.finish(new.token, currentKey: snapshot(generation: 3).key))
        XCTAssertNil(queue.begin())
    }

    func testPlaybackTargetRequestsDoNotToggleTwiceBeforeConfirmation() {
        var policy = LyricsPlaybackTargetPolicy()
        XCTAssertTrue(policy.shouldSend(target: false, snapshot: snapshot(), now: 10))
        XCTAssertFalse(policy.shouldSend(target: false, snapshot: snapshot(), now: 10.1))
        XCTAssertFalse(policy.shouldSend(target: true, snapshot: snapshot(), now: 10.2))
        let paused = snapshot(playing: false)
        XCTAssertFalse(policy.shouldSend(target: false, snapshot: paused, now: 11))
        XCTAssertTrue(policy.shouldSend(target: true, snapshot: paused, now: 11.1))
        XCTAssertFalse(policy.shouldSend(target: true, snapshot: paused, now: 11.2))
    }

    func testStaleDisconnectedAndUnsynchronizedPlaybackRejectCommands() {
        for status in [LyricsPresentationSnapshot.Status.stale, .disconnected, .syncing] {
            var policy = LyricsPlaybackTargetPolicy()
            XCTAssertFalse(policy.shouldSend(target: false, snapshot: snapshot(status: status), now: 10))
        }
        var policy = LyricsPlaybackTargetPolicy()
        XCTAssertFalse(policy.shouldSend(target: false, snapshot: snapshot(), now: 101))
        XCTAssertTrue(policy.shouldSend(target: false, snapshot: snapshot(), now: 10))
        XCTAssertTrue(policy.shouldSend(target: false, snapshot: snapshot(generation: 2), now: 10.1))
    }

    func testSampleBufferPixelsAndTimestampsRemainValidAcrossBackwardSeek() throws {
        let renderer = LyricsSampleBufferRenderer()
        let first = try renderer.render(snapshot())
        let sought = try renderer.render(snapshot(timeline: 2))
        XCTAssertGreaterThan(CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(sought),
                                         CMSampleBufferGetPresentationTimeStamp(first)), 0)
        let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sought))
        XCTAssertEqual(CVPixelBufferGetWidth(buffer), 640)
        XCTAssertEqual(CVPixelBufferGetHeight(buffer), 360)
        let image = CIImage(cvPixelBuffer: buffer)
        let cgImage = try XCTUnwrap(CIContext().createCGImage(image, from: image.extent))
        let attachment = XCTAttachment(image: UIImage(cgImage: cgImage))
        attachment.name = "floating-lyrics-render"
        attachment.lifetime = .keepAlways
        add(attachment)
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let bytes = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
        let length = CVPixelBufferGetBytesPerRow(buffer) * CVPixelBufferGetHeight(buffer)
        XCTAssertTrue(stride(from: 0, to: length, by: 4).contains { bytes[$0] > 180 && bytes[$0 + 1] > 180 })
    }

    @MainActor
    func testDefaultDisabledControllerDoesNotChangeAudioSession() {
        let session = AVAudioSession.sharedInstance()
        let category = session.category
        let mode = session.mode
        let options = session.categoryOptions
        let controller = LyricsPictureInPictureController()
        controller.setEnabled(false)
        controller.requestStart()
        XCTAssertEqual(controller.state, .disabled)
        XCTAssertEqual(session.category, category)
        XCTAssertEqual(session.mode, mode)
        XCTAssertEqual(session.categoryOptions, options)
    }

    @MainActor
    func testAcceptedTrackChangeClearsLyricsIncludingSameTrackWithNewGeneration() {
        let manager = BLETestManager(automaticallyStartBluetooth: false)
        let id = "lyrics-presentation-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.commandSenderForTesting = { _, _ in true }
        manager.receiveStatusForTesting(["type": "trackInfo", "trackId": id, "generation": 1,
                                         "title": "Fixture", "artist": "Artist"])
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "position": 2_000,
                                         "duration": 30_000, "lyric": "旧歌词"])
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().text, "旧歌词")
        manager.receiveStatusForTesting(["type": "trackInfo", "trackId": id, "generation": 2,
                                         "title": "Fixture", "artist": "Artist"])
        let changed = manager.makeLyricsPresentationSnapshot()
        XCTAssertEqual(changed.trackGeneration, 2)
        XCTAssertEqual(changed.status, .syncing)
        XCTAssertEqual(changed.text, "")
    }

    @MainActor
    func testRemoteBackwardSeekChangesTimelineAndDisconnectInvalidatesClock() {
        let manager = BLETestManager(automaticallyStartBluetooth: false)
        let id = "lyrics-seek-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "position": 12_000,
                                         "duration": 30_000, "lyric": "同一行"])
        let original = manager.makeLyricsPresentationSnapshot()
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "position": 11_000,
                                         "duration": 30_000, "lyric": "同一行"])
        XCTAssertGreaterThan(manager.makeLyricsPresentationSnapshot().timelineRevision, original.timelineRevision)
        manager.disconnectResponseTests()
        XCTAssertFalse(manager.makeLyricsPresentationSnapshot().hasAuthoritativePlayback)
        manager.configureResponseTests(trackID: id)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .syncing)
    }

    @MainActor
    func testSharedSnapshotDistinguishesIntroInterludeAndRepeatedLinesAfterSeek() {
        let manager = BLETestManager(automaticallyStartBluetooth: false)
        let id = "lyrics-timeline-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.commandSenderForTesting = { _, _ in true }
        manager.receiveStatusForTesting(["type": "fullLyricsStart", "trackId": id, "count": 2])
        for (index, start) in [(0, 1_000), (1, 5_000)] {
            manager.receiveStatusForTesting(["type": "fullLyricsChunk", "trackId": id, "index": index,
                                             "timeMs": start, "durationMs": 1_000, "text": "相同的歌词"])
        }
        manager.receiveStatusForTesting(["type": "fullLyricsEnd", "trackId": id])
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "position": 0,
                                         "duration": 30_000, "lyric": ""])
        manager.setKaraokeOffsetMs(0)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .intro)
        manager.seekToLyricLine(1_100)
        let first = manager.makeLyricsPresentationSnapshot()
        XCTAssertEqual(first.status, .ready)
        manager.seekToLyricLine(3_000)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .instrumental)
        manager.seekToLyricLine(5_100)
        let second = manager.makeLyricsPresentationSnapshot()
        XCTAssertEqual(first.text, second.text)
        XCTAssertNotEqual(first.lineIndex, second.lineIndex)
        XCTAssertNotEqual(first.timelineRevision, second.timelineRevision)
        manager.seekToLyricLine(1_100)
        XCTAssertGreaterThan(manager.makeLyricsPresentationSnapshot().timelineRevision, second.timelineRevision)
    }

    private func snapshot(lineIndex: Int = 1, generation: Int64 = 1, timeline: UInt64 = 1,
                          playing: Bool = true, status: LyricsPresentationSnapshot.Status = .ready) -> LyricsPresentationSnapshot {
        LyricsPresentationSnapshot(trackID: "fixture", trackGeneration: generation, revision: 1,
                                   timelineRevision: timeline, lineIndex: lineIndex, text: "同一句歌词 · Lyrics 🎵",
                                   title: "测试歌曲", artist: "Artist", isPlaying: playing, status: status,
                                   validUntilUptime: 100)
    }

    private func makeContent() -> SonyMusicActivityAttributes.ContentState {
        SonyMusicActivityAttributes.ContentState(trackId: "fixture", title: "Title", artist: "Artist",
                                                lyric: "Lyric", lyricLineIndex: 1, isPlaying: true,
                                                positionAtAnchorMs: 1_000, anchorDate: Date(), durationMs: 30_000,
                                                connectionState: "connected", artworkKey: nil, artworkRevision: 0)
    }
}
