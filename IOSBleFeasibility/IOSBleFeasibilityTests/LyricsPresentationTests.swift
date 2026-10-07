import AVFoundation
import CoreImage
import SwiftUI
import UIKit
import XCTest
@testable import sonyMusic

final class LyricsPresentationTests: XCTestCase {
    func testColdLaunchDefaultsOffEvenWithLegacyEnabledFlags() {
        let name = "lyrics-display-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("lyricFocused", forKey: DynamicIslandStyle.userDefaultsKey)
        defaults.set(FloatingLyricsLineMode.single.rawValue, forKey: FloatingLyricsLineMode.userDefaultsKey)
        defaults.set(true, forKey: FloatingLyricsAppearance.titleKey)
        defaults.set(FloatingLyricsTheme.blue.rawValue, forKey: FloatingLyricsTheme.userDefaultsKey)
        XCTAssertFalse(LyricsDisplayPreferences(defaults: defaults).compactEnabled)
        XCTAssertFalse(LyricsDisplayPreferences(defaults: defaults).floatingEnabled)
        for compact in [false, true] {
            for floating in [false, true] {
                defaults.set(compact, forKey: LyricsDisplayPreferences.compactKey)
                defaults.set(floating, forKey: LyricsDisplayPreferences.floatingKey)
                let reopened = LyricsDisplayPreferences(defaults: UserDefaults(suiteName: name)!)
                XCTAssertFalse(reopened.compactEnabled)
                XCTAssertFalse(reopened.floatingEnabled)
                XCTAssertNil(defaults.object(forKey: LyricsDisplayPreferences.compactKey))
                XCTAssertNil(defaults.object(forKey: LyricsDisplayPreferences.floatingKey))
                XCTAssertEqual(FloatingLyricsAppearance(defaults: defaults),
                               FloatingLyricsAppearance(lineMode: .single, showsTitle: true, theme: .blue))
            }
        }
    }

    @MainActor
    func testPreferencesReloadAndResetKeepSwitchesIndependent() {
        let preferences = PreferencesStore.shared
        let original = UserDefaults.standard.dictionaryRepresentation()
        let originalSwitches = (preferences.compactLyricsEnabled, preferences.floatingLyricsEnabled)
        defer {
            for (key, value) in original { UserDefaults.standard.set(value, forKey: key) }
            for key in [LyricsDisplayPreferences.compactKey, LyricsDisplayPreferences.floatingKey,
                        FloatingLyricsLineMode.userDefaultsKey, FloatingLyricsAppearance.titleKey,
                        FloatingLyricsTheme.userDefaultsKey]
                where original[key] == nil { UserDefaults.standard.removeObject(forKey: key) }
            preferences.load()
            preferences.compactLyricsEnabled = originalSwitches.0
            preferences.floatingLyricsEnabled = originalSwitches.1
        }
        UserDefaults.standard.removeObject(forKey: LyricsDisplayPreferences.compactKey)
        UserDefaults.standard.removeObject(forKey: LyricsDisplayPreferences.floatingKey)
        preferences.compactLyricsEnabled = true
        preferences.floatingLyricsEnabled = false
        preferences.load()
        XCTAssertTrue(preferences.compactLyricsEnabled)
        XCTAssertFalse(preferences.floatingLyricsEnabled)
        preferences.floatingLyricsEnabled = true
        preferences.compactLyricsEnabled = false
        preferences.floatingLyricsLineMode = .single
        preferences.floatingLyricsShowsTitle = true
        preferences.floatingLyricsTheme = .blue
        preferences.load()
        XCTAssertFalse(preferences.compactLyricsEnabled)
        XCTAssertTrue(preferences.floatingLyricsEnabled)
        XCTAssertNil(UserDefaults.standard.object(forKey: LyricsDisplayPreferences.compactKey))
        XCTAssertNil(UserDefaults.standard.object(forKey: LyricsDisplayPreferences.floatingKey))
        XCTAssertEqual(preferences.floatingLyricsLineMode, .single)
        XCTAssertTrue(preferences.floatingLyricsShowsTitle)
        XCTAssertEqual(preferences.floatingLyricsTheme, .blue)
        preferences.resetToDefaults()
        XCTAssertFalse(preferences.compactLyricsEnabled)
        XCTAssertFalse(preferences.floatingLyricsEnabled)
        XCTAssertEqual(preferences.floatingLyricsLineMode, .double)
        XCTAssertFalse(preferences.floatingLyricsShowsTitle)
        XCTAssertEqual(preferences.floatingLyricsTheme, .warm)
    }

    @MainActor
    func testFloatingSwitchFollowsWindowCompletionAndPreservesExplicitRestart() {
        let preferences = PreferencesStore.shared
        let original = preferences.floatingLyricsEnabled
        defer { preferences.floatingLyricsEnabled = original }
        preferences.floatingLyricsEnabled = false
        preferences.updateFloatingLyricsWindowState(.active, startPending: false)
        XCTAssertTrue(preferences.floatingLyricsEnabled)
        preferences.updateFloatingLyricsWindowState(.stopping, startPending: false)
        XCTAssertTrue(preferences.floatingLyricsEnabled, "Keep the switch while the window is still closing")
        preferences.updateFloatingLyricsWindowState(.stopped, startPending: true)
        XCTAssertTrue(preferences.floatingLyricsEnabled, "Cleanup must not cancel an explicit reopen request")
        for state in [LyricsPiPStateMachine.State.stopped, .unavailable, .failed, .disabled] {
            preferences.floatingLyricsEnabled = true
            preferences.updateFloatingLyricsWindowState(state, startPending: false)
            XCTAssertFalse(preferences.floatingLyricsEnabled, "No enabled switch may remain without a window or request")
        }
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
        XCTAssertFalse(machine.takeRestartAfterStop(), "Closing alone must not reopen the window")
        let reopened = try XCTUnwrap(machine.prepare())
        XCTAssertNotEqual(reopened, token)
        machine.ready(generation: reopened)
        XCTAssertTrue(machine.start(generation: reopened))
        XCTAssertTrue(machine.didStart(generation: reopened))
        XCTAssertEqual(machine.state, .active)
    }

    func testExplicitRestartWaitsForStopThenUsesFreshGeneration() throws {
        var machine = LyricsPiPStateMachine()
        machine.setEnabled(true)
        let old = try XCTUnwrap(machine.prepare())
        machine.ready(generation: old)
        XCTAssertTrue(machine.start(generation: old))
        XCTAssertTrue(machine.didStart(generation: old))
        machine.stop()
        XCTAssertTrue(machine.requestRestartAfterStop())
        XCTAssertTrue(machine.requestRestartAfterStop())
        XCTAssertNil(machine.prepare(), "The old controller must finish stopping before a new one starts")
        XCTAssertFalse(machine.didStart(generation: old))
        machine.didStop()
        XCTAssertTrue(machine.takeRestartAfterStop())
        XCTAssertFalse(machine.takeRestartAfterStop(), "One explicit intent starts only once")
        let reopened = try XCTUnwrap(machine.prepare())
        machine.ready(generation: reopened)
        XCTAssertTrue(machine.start(generation: reopened))
        XCTAssertFalse(machine.didStart(generation: old))
        XCTAssertTrue(machine.didStart(generation: reopened))
    }

    func testDisableCancelsQueuedRestartWithoutReplayingOnReenable() throws {
        var machine = LyricsPiPStateMachine()
        machine.setEnabled(true)
        let token = try XCTUnwrap(machine.prepare())
        machine.ready(generation: token)
        XCTAssertTrue(machine.start(generation: token))
        machine.stop()
        XCTAssertTrue(machine.requestRestartAfterStop())
        machine.setEnabled(false)
        machine.setEnabled(true)
        machine.didStop()
        XCTAssertFalse(machine.takeRestartAfterStop())
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

    func testBlockedFrameRetriesWithoutWaitingForAnotherLyricLine() throws {
        var queue = LatestLyricsFrameQueue()
        let current = snapshot()
        XCTAssertTrue(queue.offer(current, force: false))
        let first = try XCTUnwrap(queue.begin())
        XCTAssertTrue(queue.finish(first.token, currentKey: current.key, retryIfCurrent: true))
        XCTAssertFalse(queue.offer(current, force: false))
        XCTAssertTrue(queue.hasPending)
        let retry = try XCTUnwrap(queue.begin())
        XCTAssertEqual(retry.snapshot, current)
        XCTAssertNotEqual(retry.token, first.token)
        XCTAssertTrue(queue.finish(retry.token, currentKey: current.key))
        XCTAssertFalse(queue.hasPending)
        XCTAssertNil(queue.begin())
    }

    func testBlockedRetryPreservesNewerHeartbeatSnapshot() throws {
        var queue = LatestLyricsFrameQueue()
        let first = snapshot(validUntil: 100)
        let refreshed = snapshot(validUntil: 200)
        XCTAssertEqual(first.key, refreshed.key)
        XCTAssertTrue(queue.offer(first, force: false))
        let request = try XCTUnwrap(queue.begin())
        XCTAssertTrue(queue.offer(refreshed, force: true))
        XCTAssertTrue(queue.finish(request.token, currentKey: refreshed.key, retryIfCurrent: true))
        let retry = try XCTUnwrap(queue.begin())
        XCTAssertEqual(retry.snapshot.validUntilUptime, 200)
        XCTAssertTrue(queue.finish(retry.token, currentKey: refreshed.key))
        XCTAssertNil(queue.begin())
    }

    func testBlockedOldFrameCannotReplaceNewLyricsOrSurviveStop() throws {
        var queue = LatestLyricsFrameQueue()
        XCTAssertTrue(queue.offer(snapshot(), force: false))
        let old = try XCTUnwrap(queue.begin())
        let changed = snapshot(lineIndex: 2, generation: 2, timeline: 2)
        XCTAssertTrue(queue.offer(changed, force: false))
        XCTAssertFalse(queue.finish(old.token, currentKey: changed.key, retryIfCurrent: true))
        let latest = try XCTUnwrap(queue.begin())
        XCTAssertEqual(latest.snapshot, changed)
        queue.invalidate()
        XCTAssertFalse(queue.finish(latest.token, currentKey: changed.key, retryIfCurrent: true))
        XCTAssertFalse(queue.hasPending)
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
        XCTAssertTrue(policy.request(target: false, snapshot: snapshot(), confirmationRevision: 1, now: 10))
        XCTAssertFalse(policy.request(target: false, snapshot: snapshot(), confirmationRevision: 1, now: 10.1))
        XCTAssertFalse(policy.request(target: true, snapshot: snapshot(), confirmationRevision: 1, now: 10.2))
        let paused = snapshot(playing: false)
        // No new request: the already recorded final play intent is reconciled.
        XCTAssertTrue(policy.reconcile(snapshot: paused, confirmationRevision: 2, now: 11))
        XCTAssertFalse(policy.reconcile(snapshot: paused, confirmationRevision: 2, now: 11.1))
        XCTAssertFalse(policy.reconcile(snapshot: snapshot(), confirmationRevision: 3, now: 12))
        XCTAssertFalse(policy.reconcile(snapshot: paused, confirmationRevision: 4, now: 13))
    }

    func testStaleDisconnectedAndUnsynchronizedPlaybackRejectCommands() {
        for status in [LyricsPresentationSnapshot.Status.stale, .disconnected, .syncing] {
            var policy = LyricsPlaybackTargetPolicy()
            XCTAssertFalse(policy.request(target: false, snapshot: snapshot(status: status), confirmationRevision: 1, now: 10))
        }
        var policy = LyricsPlaybackTargetPolicy()
        XCTAssertFalse(policy.request(target: false, snapshot: snapshot(), confirmationRevision: 1, now: 101))
        XCTAssertTrue(policy.request(target: false, snapshot: snapshot(), confirmationRevision: 1, now: 10))
        XCTAssertTrue(policy.request(target: false, snapshot: snapshot(generation: 2), confirmationRevision: 2, now: 10.1))
    }

    @MainActor
    func testLiveActivityRapidSwitchReversalPublishesFinalChoiceAfterInFlightCompletion() async throws {
        let preferences = PreferencesStore.shared
        let original = preferences.compactLyricsEnabled
        defer { preferences.compactLyricsEnabled = original }
        let sink = ActivityPublicationRecorder(suspended: true)
        let manager = activityManager(sink)
        preferences.compactLyricsEnabled = false
        publishFixture(manager)
        try await waitUntil { sink.states.count == 1 }
        sink.completeNext()
        try await Task.sleep(nanoseconds: 20_000_000)
        preferences.compactLyricsEnabled = true
        publishFixture(manager)
        try await waitUntil { sink.states.count == 2 }
        preferences.compactLyricsEnabled = false
        publishFixture(manager)
        XCTAssertEqual(sink.states.count, 2)
        sink.completeNext()
        try await waitUntil { sink.states.count == 3 }
        XCTAssertEqual(sink.states.map(\.compactLyricsEnabled), [false, true, false])
        sink.completeNext()
    }

    @MainActor
    func testDebouncedSwitchReversalDoesNotPublishSupersededChoice() async throws {
        let preferences = PreferencesStore.shared
        let original = preferences.compactLyricsEnabled
        defer { preferences.compactLyricsEnabled = original }
        let sink = ActivityPublicationRecorder()
        let manager = activityManager(sink)
        preferences.compactLyricsEnabled = false
        publishFixture(manager)
        try await waitUntil { sink.states.count == 1 }
        preferences.compactLyricsEnabled = true
        publishFixture(manager, force: false)
        preferences.compactLyricsEnabled = false
        publishFixture(manager, force: false)
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(sink.states.map(\.compactLyricsEnabled), [false])
    }

    @MainActor
    func testLiveActivityBurstPublishesOnlyFinalPendingContent() async throws {
        let sink = ActivityPublicationRecorder(suspended: true)
        let manager = activityManager(sink)
        publishFixture(manager)
        try await waitUntil { sink.states.count == 1 }
        for index in 1...100 { publishFixture(manager, lyric: "line \(index)") }
        sink.completeNext()
        try await waitUntil { sink.states.count == 2 }
        XCTAssertEqual(sink.states.last?.lyric, "line 100")
        sink.completeNext()
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(sink.states.count, 2)
    }

    @MainActor
    func testScheduledDuplicateCleanupFinishesAfterCurrentSessionEnds() async throws {
        let cleanup = LiveActivityCleanupQueue()
        var currentID: String? = "current"
        var ended = 0
        cleanup.enqueue(id: "duplicate", canEnd: { currentID != "duplicate" }) { ended += 1 }
        XCTAssertTrue(cleanup.contains("duplicate"))
        // The session ends in the same MainActor slice, before cleanup starts.
        currentID = nil
        try await waitUntil { ended == 1 }
        XCTAssertFalse(cleanup.contains("duplicate"))
    }

    @MainActor
    func testScheduledDuplicateCleanupCannotEndNewlySelectedActivity() async throws {
        let cleanup = LiveActivityCleanupQueue()
        var currentID = "old current"
        var ended = 0
        cleanup.enqueue(id: "duplicate", canEnd: { currentID != "duplicate" }) { ended += 1 }
        currentID = "duplicate"
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(ended, 0)
        XCTAssertFalse(cleanup.contains("duplicate"))
    }

    @MainActor
    func testActivityRemainsExcludedUntilItsSingleCleanupCompletes() async throws {
        let cleanup = LiveActivityCleanupQueue()
        var ends = 0
        var completion: CheckedContinuation<Void, Never>?
        cleanup.enqueue(id: "ending") {
            ends += 1
            await withCheckedContinuation { completion = $0 }
        }
        cleanup.enqueue(id: "ending") { ends += 1 }
        try await waitUntil { completion != nil }
        XCTAssertEqual(ends, 1)
        XCTAssertTrue(cleanup.contains("ending"))
        completion?.resume()
        try await waitUntil { !cleanup.contains("ending") }
        XCTAssertEqual(ends, 1)
    }

    @MainActor
    func testEndBeforePublicationTaskStartsNeverEntersPublisher() async throws {
        let sink = ActivityPublicationRecorder()
        let manager = activityManager(sink)
        publishFixture(manager)
        manager.end()
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(sink.states.count, 0)
    }

    @MainActor
    func testSentPlaybackRejectionRequiresFreshStateAndExplicitRetry() {
        var sequences: [UInt64] = []
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, sequence, _ in
            sequences.append(sequence)
            return .sent
        })
        let id = "lyrics-terminal-error-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.commandSenderForTesting = { _, _ in true }
        manager.receiveStatusForTesting(["type": "clientCapabilitiesAck", "protocolVersion": 3,
                                         "f2": 0, "f3": 2, "sid": "1234abcd"])
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .pending)
        manager.receiveStatusForTesting(["type": "commandError", "cmd": "PLAY_PAUSE",
                                         "seq": String(sequences[0]), "domain": "protocol",
                                         "code": "unknown_command", "retryable": false, "trackId": id])
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .failed)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false), "A report from before the rejection is insufficient")
        receivePlayback(manager, playing: true)
        XCTAssertEqual(sequences.count, 1, "Failure must not automatically repeat the toggle")
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sequences.count, 2, "A proved rejection must allow an explicit retry after fresh state")
        manager.disconnectResponseTests()
    }

    @MainActor
    func testImmediateEndAndRestartOnlyPublishesNewSession() async throws {
        let sink = ActivityPublicationRecorder()
        let manager = activityManager(sink)
        publishFixture(manager, lyric: "obsolete")
        manager.end()
        publishFixture(manager, lyric: "new session")
        try await waitUntil { !sink.states.isEmpty }
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(sink.states.map(\.lyric), ["new session"])
    }

    @MainActor
    func testOldCompletionCannotReleaseNewSessionPublication() async throws {
        let sink = ActivityPublicationRecorder(suspended: true)
        let manager = activityManager(sink)
        publishFixture(manager, lyric: "old")
        try await waitUntil { sink.states.count == 1 }
        manager.end()
        publishFixture(manager, lyric: "new")
        try await waitUntil { sink.states.count == 2 }
        publishFixture(manager, lyric: "new latest")
        sink.completeNext()
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(sink.states.map(\.lyric), ["old", "new"])
        sink.completeNext()
        try await waitUntil { sink.states.count == 3 }
        XCTAssertEqual(sink.states.map(\.lyric), ["old", "new", "new latest"])
        sink.completeNext()
    }

    @MainActor
    func testEndedActivityRejectsLateCompletionAndPendingContent() async throws {
        let sink = ActivityPublicationRecorder(suspended: true)
        let manager = activityManager(sink)
        publishFixture(manager)
        try await waitUntil { sink.states.count == 1 }
        publishFixture(manager, lyric: "cancelled pending line")
        manager.end()
        publishFixture(manager, lyric: "new session")
        try await waitUntil { sink.states.count == 2 }
        sink.completeNext()
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(sink.states.count, 2)
        sink.completeNext()
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(sink.states.map(\.lyric), ["fixture lyric", "new session"])
    }

    @MainActor
    func testIntroFirstLineAndInterludeTransitionsPublishActualActivityContent() async throws {
        let preferences = PreferencesStore.shared
        let originalOffset = preferences.lyricOffsetMs
        let originalSync = preferences.automaticLyricSyncEnabled
        defer {
            preferences.lyricOffsetMs = originalOffset
            preferences.automaticLyricSyncEnabled = originalSync
        }
        preferences.lyricOffsetMs = 0
        preferences.automaticLyricSyncEnabled = true
        let sink = ActivityPublicationRecorder()
        let manager = BLETestManager(automaticallyStartBluetooth: false, liveActivityPublisher: activityManager(sink))
        let id = "lyrics-publication-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.commandSenderForTesting = { _, _ in true }
        manager.receiveStatusForTesting(["type": "fullLyricsStart", "trackId": id, "count": 2])
        for (index, start) in [(0, 1_000), (1, 5_000)] {
            manager.receiveStatusForTesting(["type": "fullLyricsChunk", "trackId": id, "index": index,
                                             "timeMs": start, "durationMs": 1_000, "text": "相同的歌词"])
        }
        manager.receiveStatusForTesting(["type": "fullLyricsEnd", "trackId": id])
        receivePlayback(manager, playing: false, position: 0)
        try await waitUntil { sink.states.last?.lyric == AppLocalization.string("前奏") }
        XCTAssertEqual(sink.states.last?.lyricLineIndex, -1)
        // Both raw resolutions select line 0, but the actual projection changes.
        receivePlayback(manager, playing: false, position: 700)
        try await waitUntil { sink.states.last?.lyric == "相同的歌词" }
        XCTAssertEqual(sink.states.last?.lyricLineIndex, 0)
        receivePlayback(manager, playing: false, position: 1_500)
        try await waitUntil { sink.states.last?.lyric == AppLocalization.string("间奏") }
        XCTAssertEqual(sink.states.last?.lyricLineIndex, 0)
        let firstLinePublicationCount = sink.states.count
        receivePlayback(manager, playing: false, position: 4_700)
        try await waitUntil { sink.states.count > firstLinePublicationCount && sink.states.last?.lyric == "相同的歌词" }
        XCTAssertEqual(sink.states.last?.lyricLineIndex, 1)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testStalePlaybackSamplesCannotRenewAuthorityOrAcknowledgeToggle() {
        let preferences = PreferencesStore.shared
        let originalSync = preferences.automaticLyricSyncEnabled
        defer { preferences.automaticLyricSyncEnabled = originalSync }
        preferences.automaticLyricSyncEnabled = true
        var now: TimeInterval = 10
        var commands: [LiveActivityControlCommand] = []
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    lyricsPresentationUptime: { now }, remoteClock: synchronizedClock(),
                                    floatingPlaybackCommandSender: { command, _, _ in commands.append(command); return .sent })
        manager.configureResponseTests(trackID: "lyrics-stale-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        let original = manager.makeLyricsPresentationSnapshot()
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        now = 40
        let oldSample = Int64((ProcessInfo.processInfo.systemUptime * 1_000).rounded()) - 5_000
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "position": 29_000,
                                         "duration": 30_000, "lyric": "陈旧内容", "sampleMono": oldSample])
        let rejected = manager.makeLyricsPresentationSnapshot()
        XCTAssertEqual(rejected.validUntilUptime, original.validUntilUptime)
        XCTAssertEqual(rejected.text, original.text)
        XCTAssertTrue(rejected.isPlaying)
        XCTAssertEqual(commands, [.playPause])
        now = 56
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .stale)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        // A legitimate legacy packet has a position but no clock fields.
        receivePlayback(manager, playing: false)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().validUntilUptime, 356)
        // The accepted legacy sample releases the first reservation, but a
        // 46-second-old unsent intent must not be executed automatically.
        XCTAssertEqual(commands, [.playPause])
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        XCTAssertEqual(commands, [.playPause, .playPause])
        manager.disconnectResponseTests()
    }

    @MainActor
    func testSkippedSeekAnchorAndMissingPositionCannotExtendAuthority() {
        var now: TimeInterval = 10
        let manager = BLETestManager(automaticallyStartBluetooth: false, lyricsPresentationUptime: { now })
        manager.configureResponseTests(trackID: "lyrics-seeking-clock-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        let deadline = manager.makeLyricsPresentationSnapshot().validUntilUptime
        manager.beginSeeking()
        now = 20
        receivePlayback(manager, playing: false, position: 2_000)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().validUntilUptime, deadline)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(true))
        now = 30
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "duration": 30_000])
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().validUntilUptime, deadline)
        now = 56
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .stale)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testFinalPlaybackIntentAutomaticallySendsAfterAcceptedPauseReport() {
        var commands: [LiveActivityControlCommand] = []
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { command, _, _ in commands.append(command); return .sent })
        manager.configureResponseTests(trackID: "lyrics-final-target-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        for _ in 0..<10 { XCTAssertTrue(manager.requestFloatingLyricsPlayback(true)) }
        XCTAssertEqual(commands, [.playPause])
        receivePlayback(manager, playing: false)
        XCTAssertEqual(commands, [.playPause, .playPause])
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.makeLyricsPresentationSnapshot().isPlaying)
        // Once fulfilled, the intent must not fight later Sony-side controls.
        receivePlayback(manager, playing: false)
        XCTAssertEqual(commands, [.playPause, .playPause])
        manager.disconnectResponseTests()
    }

    @MainActor
    func testElapsedTimeoutAndUnchangedReportsNeverResendAnUnconfirmedToggle() {
        var now: TimeInterval = 10
        var sent = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false, lyricsPresentationUptime: { now },
                                    floatingPlaybackCommandSender: { _, _, _ in sent += 1; return .sent })
        manager.configureResponseTests(trackID: "lyrics-timeout-target-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        now = 14
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sent, 1)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        receivePlayback(manager, playing: false)
        XCTAssertEqual(sent, 2)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testUnexecutedToggleBecomesUnknownWithOneQueryAndNoBlindRetry() {
        var now: TimeInterval = 10
        var sent = 0
        var queries = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false, lyricsPresentationUptime: { now },
                                    floatingPlaybackCommandSender: { _, _, _ in sent += 1; return .sent })
        manager.configureResponseTests(trackID: "lyrics-unknown-\(UUID().uuidString)")
        manager.commandSenderForTesting = { command, _ in
            if command == "GET_PLAYBACK_STATE" { queries += 1 }
            return true
        }
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        now = 19
        for _ in 0..<20 {
            receivePlayback(manager, playing: true)
            XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        }
        XCTAssertEqual(sent, 1)
        XCTAssertEqual(queries, 1)
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .unknown)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().playbackControlState, .unknown)
        XCTAssertFalse(manager.lyricsStore.floatingPlaybackControlState.message.isEmpty)
        // A late confirmation can resolve the sent command, but not revive the
        // expired final play intent. Later Sony-side changes remain authoritative.
        receivePlayback(manager, playing: false)
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .idle)
        receivePlayback(manager, playing: true)
        XCTAssertEqual(sent, 1)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testMissingReportsReachUnknownWithoutAnotherUserRequest() async throws {
        var sent = 0
        var queries = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, _, _ in sent += 1; return .sent },
                                    floatingPlaybackConfirmationTimeout: 0.06)
        manager.configureResponseTests(trackID: "lyrics-missing-report-\(UUID().uuidString)")
        manager.commandSenderForTesting = { command, _ in
            if command == "GET_PLAYBACK_STATE" { queries += 1 }
            return true
        }
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        try await waitUntil { manager.lyricsStore.floatingPlaybackControlState == .unknown }
        XCTAssertEqual(queries, 1)
        XCTAssertEqual(sent, 1)
        receivePlayback(manager, playing: true)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(queries, 1)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testUnclassifiedCommandErrorKeepsToggleReservation() {
        var sequences: [UInt64] = []
        let manager = playbackErrorFixture { sequences.append($0) }
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        receivePlaybackError(manager, sequence: sequences[0], code: "execution_failed", domain: "connection")
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .unknown)
        receivePlayback(manager, playing: true)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sequences.count, 1)
        // Only a correlated error with known pre-execution semantics can release
        // the ambiguous command, and then a fresh report is still required.
        receivePlaybackError(manager, sequence: sequences[0])
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sequences.count, 2)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testOldSequencesGenerationsAndSessionsCannotFailCurrentCommand() {
        var sequences: [UInt64] = []
        let manager = playbackErrorFixture { sequences.append($0) }
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        receivePlaybackError(manager, sequence: sequences[0] + 100)
        receivePlaybackError(manager, sequence: sequences[0], generation: 2)
        receivePlaybackError(manager, sequence: sequences[0], sessionID: "8765dcba")
        XCTAssertEqual(manager.serverSessionId, "1234abcd")
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .pending)
        receivePlayback(manager, playing: false)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        receivePlaybackError(manager, sequence: sequences[0])
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .pending)
        manager.disconnectResponseTests()
        manager.configureResponseTests(trackID: "error-fixture")
        manager.receiveStatusForTesting(["type": "clientCapabilitiesAck", "protocolVersion": 3,
                                         "f2": 0, "f3": 3, "sid": "1234abcd"])
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        receivePlaybackError(manager, sequence: sequences[1])
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .pending)
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sequences.count, 3)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testOldServerSessionReportCannotConfirmCurrentToggle() {
        var sequences: [UInt64] = []
        let manager = playbackErrorFixture { sequences.append($0) }
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        manager.receiveStatusForTesting(["type": "playbackState", "playing": false, "position": 1_000,
                                         "duration": 30_000, "sid": "8765dcba", "es": 1])
        XCTAssertEqual(sequences.count, 1)
        XCTAssertFalse(manager.makeLyricsPresentationSnapshot().hasAuthoritativePlayback)
        manager.receiveStatusForTesting(["type": "playbackState", "playing": true, "position": 1_000,
                                         "duration": 30_000, "sid": "1234abcd", "es": 2])
        XCTAssertEqual(sequences.count, 1)
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .pending)
        receivePlayback(manager, playing: false)
        XCTAssertEqual(sequences.count, 2)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testOldConnectionQueuedReportCannotConfirmNewCommand() async throws {
        var sequences: [UInt64] = []
        let manager = playbackErrorFixture { sequences.append($0) }
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        let queued = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            manager.receiveStatusForTesting(["type": "playbackState", "playing": false,
                                             "position": 1_000, "duration": 30_000])
            queued.signal()
        }
        XCTAssertEqual(queued.wait(timeout: .now() + 2), .success)
        manager.disconnectResponseTests()
        manager.configureResponseTests(trackID: "error-fixture")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertTrue(manager.makeLyricsPresentationSnapshot().isPlaying)
        XCTAssertEqual(sequences.count, 2)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testStopAndReopenPreserveUnknownProtectionAndReconnectAllowsExplicitRecovery() {
        var now: TimeInterval = 10
        var sent = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false, lyricsPresentationUptime: { now },
                                    floatingPlaybackCommandSender: { _, _, _ in sent += 1; return .sent })
        manager.commandSenderForTesting = { _, _ in true }
        manager.configureResponseTests(trackID: "lyrics-reopen-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        manager.cancelFloatingLyricsPlaybackIntent()
        XCTAssertEqual(sent, 1)
        now = 20
        receivePlayback(manager, playing: true)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .unknown)
        manager.disconnectResponseTests()
        manager.configureResponseTests(trackID: "lyrics-reconnected")
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        receivePlayback(manager, playing: true)
        XCTAssertEqual(sent, 1, "Reconnect must not replay an old intent")
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sent, 2)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testDeferredUnsentIntentExpiresInsteadOfExecutingMuchLater() async throws {
        var now: TimeInterval = 10
        var attempts = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false, lyricsPresentationUptime: { now },
                                    floatingPlaybackCommandSender: { _, _, _ in attempts += 1; return .debounced })
        manager.configureResponseTests(trackID: "lyrics-expired-unsent-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        now = 20
        receivePlayback(manager, playing: true)
        try await Task.sleep(nanoseconds: 800_000_000)
        XCTAssertEqual(attempts, 1)
        XCTAssertEqual(manager.lyricsStore.floatingPlaybackControlState, .failed)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testAutomaticFollowUpRespectsDebounceThenReconcilesWithoutAnotherRequest() async throws {
        var attempts = 0
        var sent = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, _, _ in
            attempts += 1
            if attempts == 2 { return .debounced }
            sent += 1
            return .sent
        })
        manager.configureResponseTests(trackID: "lyrics-deferred-target-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        receivePlayback(manager, playing: false)
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(sent, 1)
        try await waitUntil { sent == 2 }
        XCTAssertEqual(attempts, 3)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testTrackGenerationAndDisconnectDiscardUnfulfilledPlaybackIntent() {
        var sent = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, _, _ in sent += 1; return .sent })
        let id = "lyrics-target-identity-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.commandSenderForTesting = { _, _ in true }
        manager.receiveStatusForTesting(["type": "trackInfo", "trackId": id, "generation": 1,
                                         "title": "Fixture", "artist": "Artist"])
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        manager.receiveStatusForTesting(["type": "trackInfo", "trackId": id, "generation": 2,
                                         "title": "Fixture", "artist": "Artist"])
        receivePlayback(manager, playing: false)
        XCTAssertEqual(sent, 1)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        manager.disconnectResponseTests()
        manager.configureResponseTests(trackID: id)
        receivePlayback(manager, playing: true)
        XCTAssertEqual(sent, 2)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testStoppingPiPCancelsUnsentFinalIntentWithoutSendingPause() {
        var sent = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, _, _ in sent += 1; return .sent })
        manager.configureResponseTests(trackID: "lyrics-stop-target-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(true))
        let controller = LyricsPictureInPictureController()
        let view = LyricsPictureInPictureSourceUIView()
        controller.attach(view, snapshot: { manager.makeLyricsPresentationSnapshot() },
                          setPlaying: { manager.requestFloatingLyricsPlayback($0) },
                          controlsStopped: { manager.cancelFloatingLyricsPlaybackIntent() })
        controller.stop()
        XCTAssertEqual(sent, 1)
        // A new window's request still cannot blindly repeat the unresolved toggle.
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(sent, 1)
        manager.cancelFloatingLyricsPlaybackIntent()
        receivePlayback(manager, playing: false)
        XCTAssertEqual(sent, 1)
        manager.disconnectResponseTests()
    }

    @MainActor
    func testRejectedCommandDoesNotBecomeAnUnconfirmedToggle() {
        var attempts = 0
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, _, _ in
            attempts += 1
            return attempts == 1 ? .bluetoothUnavailable : .sent
        })
        manager.configureResponseTests(trackID: "lyrics-command-failure-\(UUID().uuidString)")
        receivePlayback(manager, playing: true)
        XCTAssertFalse(manager.requestFloatingLyricsPlayback(false))
        receivePlayback(manager, playing: true)
        XCTAssertEqual(attempts, 1)
        XCTAssertTrue(manager.requestFloatingLyricsPlayback(false))
        XCTAssertEqual(attempts, 2)
        manager.disconnectResponseTests()
    }

    func testUnknownControlFeedbackIsRenderedWithoutChangingLyricIdentity() throws {
        let renderer = LyricsSampleBufferRenderer()
        let normal = snapshot()
        var unknown = normal
        unknown.playbackControlState = .unknown
        XCTAssertEqual(normal.key, unknown.key, "Control feedback must not increase ActivityKit lyric publication")
        let plain = try renderer.render(normal)
        let feedback = try renderer.render(unknown)
        func brightFooterPixels(_ sample: CMSampleBuffer) throws -> Int {
            let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
            CVPixelBufferLockBaseAddress(buffer, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
            let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
            let stride = CVPixelBufferGetBytesPerRow(buffer)
            let height = CVPixelBufferGetHeight(buffer)
            let width = CVPixelBufferGetWidth(buffer)
            return ((height - 30)..<height).reduce(0) { count, row in
                count + (0..<width).filter { column in
                    let index = row * stride + column * 4
                    return bytes[index] > 100 && bytes[index + 1] > 100 && bytes[index + 2] > 100
                }.count
            }
        }
        XCTAssertEqual(try brightFooterPixels(plain), 0)
        XCTAssertGreaterThan(try brightFooterPixels(feedback), 100)
        let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(feedback))
        let image = CIImage(cvPixelBuffer: buffer)
        let cgImage = try XCTUnwrap(CIContext().createCGImage(image, from: image.extent))
        let attachment = XCTAttachment(image: UIImage(cgImage: cgImage))
        attachment.name = "floating-lyrics-unknown-control-feedback"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testSampleBufferPixelsAndTimestampsRemainValidAcrossBackwardSeek() throws {
        let renderer = LyricsSampleBufferRenderer()
        let first = try renderer.render(snapshot())
        let sought = try renderer.render(snapshot(timeline: 2))
        XCTAssertGreaterThan(CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(sought),
                                         CMSampleBufferGetPresentationTimeStamp(first)), 0)
        let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sought))
        XCTAssertEqual(CVPixelBufferGetWidth(buffer), 640)
        XCTAssertEqual(CVPixelBufferGetHeight(buffer), 104)
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

    func testPixelBufferPressureIsRecoverableAfterHeldFramesAreReleased() throws {
        let renderer = LyricsSampleBufferRenderer()
        var held: [CMSampleBuffer] = []
        try autoreleasepool {
            for index in 0..<3 { held.append(try renderer.render(snapshot(lineIndex: index))) }
            XCTAssertThrowsError(try renderer.render(snapshot(lineIndex: 3))) { error in
                XCTAssertEqual(error as? LyricsSampleBufferRenderer.RenderError, .bufferPoolExhausted)
            }
        }
        let previousTime = CMSampleBufferGetPresentationTimeStamp(held[0])
        autoreleasepool { held.removeSubrange((held.count - 1)..<held.count) }
        let recovered = try renderer.render(snapshot(lineIndex: 4))
        XCTAssertNotNil(CMSampleBufferGetImageBuffer(recovered))
        XCTAssertGreaterThan(CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(recovered), previousTime), 0)
        XCTAssertEqual(held.count, 2)
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

    func testFloatingAppearanceDefaultsAndInvalidStoredMode() {
        let name = "floating-appearance-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        XCTAssertEqual(FloatingLyricsAppearance(defaults: defaults), FloatingLyricsAppearance())
        defaults.set("unsupported-mode", forKey: FloatingLyricsLineMode.userDefaultsKey)
        defaults.set("unsupported-theme", forKey: FloatingLyricsTheme.userDefaultsKey)
        XCTAssertEqual(FloatingLyricsAppearance(defaults: defaults).lineMode, .double)
        XCTAssertEqual(FloatingLyricsAppearance(defaults: defaults).theme, .warm)
        defaults.set("single", forKey: FloatingLyricsLineMode.userDefaultsKey)
        defaults.set(true, forKey: FloatingLyricsAppearance.titleKey)
        defaults.set("rose", forKey: FloatingLyricsTheme.userDefaultsKey)
        let reopened = FloatingLyricsAppearance(defaults: UserDefaults(suiteName: name)!)
        XCTAssertEqual(reopened.lineMode, .single)
        XCTAssertTrue(reopened.showsTitle)
        XCTAssertEqual(reopened.theme, .rose)
    }

    func testThemeSelectionChangesRenderedBackgroundAndKeepsCompactSize() throws {
        let renderer = LyricsSampleBufferRenderer()
        let content = snapshot(text: "气象报告天气很不错", nextText: "太阳晒的我脸颊红红")
        var allPixels = Set<Data>()
        for theme in FloatingLyricsTheme.allCases {
            let appearance = FloatingLyricsAppearance(theme: theme)
            allPixels.insert(try renderedPixels(renderer, content, appearance: appearance))
            try autoreleasepool {
                let sample = try renderer.render(content, appearance: appearance)
                let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
                XCTAssertEqual(CVPixelBufferGetWidth(buffer), 640)
                XCTAssertEqual(CVPixelBufferGetHeight(buffer), 104)
                let image = CIImage(cvPixelBuffer: buffer)
                let cgImage = try XCTUnwrap(CIContext().createCGImage(image, from: image.extent))
                let attachment = XCTAttachment(image: UIImage(cgImage: cgImage))
                attachment.name = "floating-lyrics-theme-\(theme.rawValue)"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        XCTAssertEqual(allPixels.count, FloatingLyricsTheme.allCases.count,
                       "Each selectable color must change the actual lyric image")
    }

    @MainActor
    func testNextLineFollowsAcceptedTimelineAndClearsWhenStaleOrSongChanges() {
        var now: TimeInterval = 10
        let manager = BLETestManager(automaticallyStartBluetooth: false, lyricsPresentationUptime: { now })
        let id = "floating-next-\(UUID().uuidString)"
        manager.configureResponseTests(trackID: id)
        manager.commandSenderForTesting = { _, _ in true }
        defer { manager.disconnectResponseTests() }
        manager.receiveStatusForTesting(["type": "fullLyricsStart", "trackId": id, "count": 3])
        for (index, text) in ["第一句", "第二句", "第三句"].enumerated() {
            manager.receiveStatusForTesting(["type": "fullLyricsChunk", "trackId": id, "index": index,
                                             "timeMs": 1_000 + index * 4_000, "durationMs": 1_000, "text": text])
        }
        manager.receiveStatusForTesting(["type": "fullLyricsEnd", "trackId": id])
        manager.setKaraokeOffsetMs(0)
        receivePlayback(manager, playing: false, position: 1_100)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().text, "第一句")
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().nextText, "第二句")
        manager.seekToLyricLine(0)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .intro)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().nextText, "第一句")
        manager.seekToLyricLine(3_000)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .instrumental)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().nextText, "第二句")
        manager.seekToLyricLine(9_100)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().nextText, "")
        now = 311
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().status, .stale)
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().nextText, "")
        manager.receiveStatusForTesting(["type": "trackInfo", "trackId": id, "generation": 2,
                                         "title": "New song", "artist": "Artist"])
        XCTAssertEqual(manager.makeLyricsPresentationSnapshot().nextText, "")
    }

    func testChangedNextLineRejectsOldFrameEvenWithSameCurrentLine() throws {
        var queue = LatestLyricsFrameQueue()
        let old = snapshot(nextText: "旧的下一句")
        let new = snapshot(nextText: "修正后的下一句")
        XCTAssertTrue(queue.offer(old, force: false))
        let request = try XCTUnwrap(queue.begin())
        XCTAssertTrue(queue.offer(new, force: false))
        XCTAssertFalse(queue.finish(request.token, currentKey: new.key))
        XCTAssertEqual(queue.begin()?.snapshot.nextText, new.nextText)
    }

    func testStripRenderingKeepsRowsStableAndHonoursTitleAndLineMode() throws {
        let renderer = LyricsSampleBufferRenderer()
        let short = snapshot(text: "当前歌词", title: "Hidden A", nextText: "下一句歌词")
        let differentTitle = snapshot(text: "当前歌词", title: "Hidden B", nextText: "下一句歌词")
        XCTAssertEqual(try renderedPixels(renderer, short), try renderedPixels(renderer, differentTitle),
                       "The title is hidden by default")
        let titleOn = FloatingLyricsAppearance(showsTitle: true)
        XCTAssertNotEqual(try renderedPixels(renderer, short, appearance: titleOn),
                          try renderedPixels(renderer, differentTitle, appearance: titleOn))
        let long = snapshot(text: String(repeating: "很长的歌词 🎵 ", count: 100), nextText: "下一句歌词")
        XCTAssertEqual(try renderedPixels(renderer, short, rows: 56..<104),
                       try renderedPixels(renderer, long, rows: 56..<104),
                       "Long text must not wrap into the next lyric row")
        let changedNext = snapshot(text: "当前歌词", title: "Hidden A", nextText: "另一句歌词")
        XCTAssertNotEqual(try renderedPixels(renderer, short, rows: 56..<104),
                          try renderedPixels(renderer, changedNext, rows: 56..<104))
        let single = FloatingLyricsAppearance(lineMode: .single)
        XCTAssertEqual(try renderedPixels(renderer, short, appearance: single),
                       try renderedPixels(renderer, changedNext, appearance: single))
        let sample = try renderer.render(short, appearance: single)
        let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
        XCTAssertEqual(CVPixelBufferGetWidth(buffer), 640)
        XCTAssertEqual(CVPixelBufferGetHeight(buffer), 60)
        let cgImage = try XCTUnwrap(CIContext().createCGImage(CIImage(cvPixelBuffer: buffer),
                                                           from: CGRect(x: 0, y: 0, width: 640, height: 60)))
        let attachment = XCTAttachment(image: UIImage(cgImage: cgImage))
        attachment.name = "floating-lyrics-single-strip"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testPendingStartWaitsForVisibleSourceWithoutAudioAndDisableCancelsIt() async throws {
        let audio = AVAudioSession.sharedInstance()
        let before = (audio.category, audio.mode, audio.categoryOptions)
        let controller = LyricsPictureInPictureController(supportsPictureInPicture: { true }, isApplicationActive: { true })
        controller.setEnabled(true)
        controller.requestStart()
        XCTAssertTrue(controller.startRequested)
        XCTAssertEqual(controller.state, .preparing)
        XCTAssertFalse(controller.canRequestStart)
        let generation = controller.lifecycle.generation
        controller.requestStart()
        XCTAssertEqual(controller.lifecycle.generation, generation, "Repeated taps must not restart preparation")
        XCTAssertEqual(audio.category, before.0)
        XCTAssertEqual(audio.mode, before.1)
        XCTAssertEqual(audio.categoryOptions, before.2)
        controller.setEnabled(false)
        try await Task.sleep(nanoseconds: 250_000_000)
        XCTAssertFalse(controller.startRequested)
        XCTAssertEqual(controller.state, .disabled)
        XCTAssertEqual(audio.categoryOptions, before.2)
    }

    @MainActor
    func testPendingStartStopsWhenAppLeavesForeground() async throws {
        var active = true
        let controller = LyricsPictureInPictureController(supportsPictureInPicture: { true }, isApplicationActive: { active })
        defer { controller.setEnabled(false) }
        controller.setEnabled(true)
        controller.requestStart()
        active = false
        try await waitUntil { controller.state == .unavailable }
        XCTAssertFalse(controller.startRequested)
        XCTAssertFalse(controller.reason.isEmpty)
    }

    @MainActor
    func testSourceVisibilityRejectsHiddenClippedAndModalCoveredPreview() async throws {
        let previousKeyWindow = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first { $0.isKeyWindow }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        defer {
            root.dismiss(animated: false)
            window.isHidden = true
            previousKeyWindow?.makeKeyAndVisible()
        }
        let source = LyricsPictureInPictureSourceUIView(frame: .zero)
        root.view.addSubview(source)
        XCTAssertFalse(source.isVisibleForPictureInPicture)
        source.frame = CGRect(x: 20, y: 200, width: 300, height: 60)
        XCTAssertTrue(source.isVisibleForPictureInPicture)
        source.isHidden = true
        XCTAssertFalse(source.isVisibleForPictureInPicture)
        source.isHidden = false
        source.frame.origin.y = 1_000
        XCTAssertFalse(source.isVisibleForPictureInPicture)
        source.frame.origin.y = 200
        let clip = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 50))
        clip.clipsToBounds = true
        root.view.addSubview(clip)
        clip.addSubview(source)
        XCTAssertFalse(source.isVisibleForPictureInPicture)
        root.view.addSubview(source)
        let settings = UIViewController()
        root.present(settings, animated: false)
        try await waitUntil { root.presentedViewController != nil }
        XCTAssertFalse(source.isVisibleForPictureInPicture)
        settings.view.addSubview(source)
        try await waitUntil { source.window === window }
        XCTAssertTrue(source.isVisibleForPictureInPicture, "A real source inside the frontmost settings sheet is visible")
        root.dismiss(animated: false)
        try await waitUntil { root.presentedViewController == nil }
        XCTAssertFalse(source.isVisibleForPictureInPicture, "The dismissed sheet cannot remain a valid source")
        root.view.addSubview(source)
        XCTAssertTrue(source.isVisibleForPictureInPicture)
    }

    @MainActor
    func testDismissingSettingsCancelsPendingStartWithoutChangingPreference() {
        let controller = LyricsPictureInPictureController(supportsPictureInPicture: { true }, isApplicationActive: { true })
        defer { controller.setEnabled(false) }
        controller.setEnabled(true)
        controller.requestStart()
        XCTAssertTrue(controller.startRequested)
        controller.cancelPendingStart()
        XCTAssertFalse(controller.startRequested)
        XCTAssertEqual(controller.state, .stopped)
        XCTAssertTrue(controller.lifecycle.enabled, "Cancel the window request, not the user's saved preference")
        XCTAssertTrue(controller.canRequestStart)
        let previousGeneration = controller.lifecycle.generation
        controller.requestStart()
        XCTAssertTrue(controller.startRequested)
        XCTAssertEqual(controller.state, .preparing)
        XCTAssertGreaterThan(controller.lifecycle.generation, previousGeneration)
    }

    func testCompactCanvasOnlyAddsHeightWhenTitleOrControlFeedbackIsPresent() throws {
        let renderer = LyricsSampleBufferRenderer()
        let plain = snapshot(text: "气象报告天气很不错", nextText: "太阳晒的我脸颊红红")
        let pixels = try renderedPixels(renderer, plain)
        var withFeedback = plain
        withFeedback.playbackControlState = .unknown
        let feedbackPixels = try renderedPixels(renderer, withFeedback)
        let expectedStride = feedbackPixels.count / 132
        XCTAssertEqual(pixels.count, expectedStride * 104)
        XCTAssertEqual(Data(feedbackPixels.prefix(pixels.count)), pixels,
                       "Control feedback must add a row without covering either lyric")
        let titlePixels = try renderedPixels(renderer, plain, appearance: FloatingLyricsAppearance(showsTitle: true))
        XCTAssertEqual(titlePixels.count, expectedStride * 128)
        let bothPixels = try renderedPixels(renderer, withFeedback, appearance: FloatingLyricsAppearance(showsTitle: true))
        XCTAssertEqual(bothPixels.count, expectedStride * 156)
        let sample = try renderer.render(plain)
        let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
        let image = CIImage(cvPixelBuffer: buffer)
        let cgImage = try XCTUnwrap(CIContext().createCGImage(image, from: image.extent))
        let attachment = XCTAttachment(image: UIImage(cgImage: cgImage))
        attachment.name = "floating-lyrics-compact-double-strip"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testMainPlayerNeverMountsFloatingLyricsPreviewForEitherPreference() async throws {
        let preferences = PreferencesStore.shared
        let original = preferences.floatingLyricsEnabled
        let previousKeyWindow = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first { $0.isKeyWindow }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        defer {
            window.isHidden = true
            preferences.floatingLyricsEnabled = original
            previousKeyWindow?.makeKeyAndVisible()
        }
        func containsSource(_ view: UIView) -> Bool {
            view is LyricsPictureInPictureSourceUIView || view.subviews.contains(where: containsSource)
        }
        for enabled in [false, true] {
            preferences.floatingLyricsEnabled = enabled
            let host = UIHostingController(rootView: ContentView())
            window.rootViewController = host
            window.makeKeyAndVisible()
            host.view.frame = window.bounds
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            await Task.yield()
            XCTAssertFalse(containsSource(host.view), "No floating-lyrics footer may appear, even when enabled")
            let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in
                host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = enabled ? "player-floating-enabled-no-footer" : "player-floating-disabled-no-footer"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    @MainActor
    func testOpeningLyricsSettingsDoesNotStartWindowOrChangeAudioSession() async throws {
        let preferences = PreferencesStore.shared
        let controller = LyricsPictureInPictureController.shared
        let originalSwitches = (preferences.compactLyricsEnabled, preferences.floatingLyricsEnabled)
        let previousKeyWindow = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first { $0.isKeyWindow }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        preferences.compactLyricsEnabled = false
        preferences.floatingLyricsEnabled = false
        controller.setEnabled(false)
        let audio = AVAudioSession.sharedInstance()
        let category = audio.category
        let options = audio.categoryOptions
        defer {
            window.isHidden = true
            window.rootViewController = nil
            preferences.compactLyricsEnabled = originalSwitches.0
            preferences.floatingLyricsEnabled = originalSwitches.1
            controller.setEnabled(originalSwitches.1)
            previousKeyWindow?.makeKeyAndVisible()
        }
        let manager = BLETestManager(automaticallyStartBluetooth: false)
        let host = UIHostingController(rootView: LyricsDisplaySettingsView(manager: manager, onDismiss: {})
            .environment(\.locale, preferences.appLanguage.locale))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.frame = window.bounds
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        await Task.yield()
        func containsSource(_ view: UIView) -> Bool {
            view is LyricsPictureInPictureSourceUIView || view.subviews.contains(where: containsSource)
        }
        XCTAssertFalse(containsSource(host.view))
        XCTAssertFalse(controller.startRequested)
        XCTAssertEqual(controller.state, .disabled)
        XCTAssertFalse(preferences.compactLyricsEnabled)
        XCTAssertFalse(preferences.floatingLyricsEnabled)
        XCTAssertEqual(audio.category, category)
        XCTAssertEqual(audio.categoryOptions, options)
        let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in
            host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "lyrics-settings-default-off"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func renderedPixels(_ renderer: LyricsSampleBufferRenderer, _ snapshot: LyricsPresentationSnapshot,
                                appearance: FloatingLyricsAppearance = FloatingLyricsAppearance(),
                                rows: Range<Int>? = nil) throws -> Data {
        try autoreleasepool {
            let sample = try renderer.render(snapshot, appearance: appearance)
            let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
            CVPixelBufferLockBaseAddress(buffer, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
            let stride = CVPixelBufferGetBytesPerRow(buffer)
            let selection = rows ?? 0..<CVPixelBufferGetHeight(buffer)
            let pointer = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer))
            return Data(bytes: pointer.advanced(by: stride * selection.lowerBound), count: stride * selection.count)
        }
    }

    private func snapshot(lineIndex: Int = 1, generation: Int64 = 1, timeline: UInt64 = 1,
                          playing: Bool = true, status: LyricsPresentationSnapshot.Status = .ready,
                          validUntil: TimeInterval = 100, text: String = "同一句歌词 · Lyrics 🎵",
                          title: String = "测试歌曲", nextText: String = "") -> LyricsPresentationSnapshot {
        LyricsPresentationSnapshot(trackID: "fixture", trackGeneration: generation, revision: 1,
                                   timelineRevision: timeline, lineIndex: lineIndex, text: text,
                                   title: title, artist: "Artist", isPlaying: playing, status: status,
                                   validUntilUptime: validUntil, nextText: nextText)
    }

    private func makeContent() -> SonyMusicActivityAttributes.ContentState {
        SonyMusicActivityAttributes.ContentState(trackId: "fixture", title: "Title", artist: "Artist",
                                                lyric: "Lyric", lyricLineIndex: 1, isPlaying: true,
                                                positionAtAnchorMs: 1_000, anchorDate: Date(), durationMs: 30_000,
                                                connectionState: "connected", artworkKey: nil, artworkRevision: 0)
    }

    @MainActor
    private func activityManager(_ sink: ActivityPublicationRecorder) -> LiveActivityManager {
        LiveActivityManager(activitiesEnabled: { true }, statePublisher: { await sink.publish($0) })
    }

    @MainActor
    private func publishFixture(_ manager: LiveActivityManager, lyric: String = "fixture lyric", force: Bool = true) {
        manager.update(title: "Fixture", artist: "Artist", lyric: lyric, lyricLineIndex: 0,
                       isPlaying: false, positionMs: 1_000, durationMs: 30_000, trackId: "fixture",
                       artworkKey: nil, artworkRevision: 0, reason: "preferences", force: force)
    }

    private func receivePlayback(_ manager: BLETestManager, playing: Bool, position: Int64 = 1_000) {
        manager.receiveStatusForTesting(["type": "playbackState", "playing": playing, "position": position,
                                         "duration": 30_000, "lyric": "fixture lyric"])
    }

    @MainActor
    private func playbackErrorFixture(recordSequence: @escaping (UInt64) -> Void) -> BLETestManager {
        let manager = BLETestManager(automaticallyStartBluetooth: false,
                                    floatingPlaybackCommandSender: { _, sequence, _ in
            recordSequence(sequence)
            return .sent
        })
        manager.configureResponseTests(trackID: "error-fixture")
        manager.commandSenderForTesting = { _, _ in true }
        manager.receiveStatusForTesting(["type": "clientCapabilitiesAck", "protocolVersion": 3,
                                         "f2": 0, "f3": 3, "sid": "1234abcd"])
        manager.receiveStatusForTesting(["type": "trackInfo", "trackId": "error-fixture", "generation": 1,
                                         "title": "Fixture", "artist": "Artist"])
        return manager
    }

    private func receivePlaybackError(_ manager: BLETestManager, sequence: UInt64,
                                      generation: Int = 1, sessionID: String = "1234abcd",
                                      code: String = "unknown_command", domain: String = "protocol") {
        manager.receiveStatusForTesting(["type": "commandError", "cmd": "PLAY_PAUSE", "seq": String(sequence),
                                         "domain": domain, "code": code, "retryable": false,
                                         "trackId": "error-fixture", "generation": generation,
                                         "sid": sessionID, "es": sequence])
    }

    private func synchronizedClock() -> MonotonicClockSynchronizer {
        var clock = MonotonicClockSynchronizer()
        let base = Int64((ProcessInfo.processInfo.systemUptime * 1_000).rounded()) - 100
        for index in 0..<3 {
            let send = base + Int64(index * 10)
            clock.record(clientSendElapsedMs: send, serverReceiveElapsedMs: send + 1,
                         serverSendElapsedMs: send + 1, clientReceiveElapsedMs: send + 2)
        }
        return clock
    }

    @MainActor
    private func waitUntil(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        while !condition(), ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        guard condition() else {
            XCTFail("Expected publication or command did not arrive", file: file, line: line)
            throw NSError(domain: "LyricsPresentationTests", code: 1)
        }
    }
}

@MainActor
private final class ActivityPublicationRecorder {
    private(set) var states: [SonyMusicActivityAttributes.ContentState] = []
    private var completions: [CheckedContinuation<Bool, Never>] = []
    private let suspended: Bool

    init(suspended: Bool = false) { self.suspended = suspended }

    func publish(_ state: SonyMusicActivityAttributes.ContentState) async -> Bool {
        states.append(state)
        guard suspended else { return true }
        return await withCheckedContinuation { completions.append($0) }
    }

    func completeNext() { completions.removeFirst().resume(returning: true) }
}
