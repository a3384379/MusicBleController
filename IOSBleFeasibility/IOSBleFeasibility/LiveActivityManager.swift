@preconcurrency import ActivityKit
import Foundation

@MainActor
final class LiveActivityManager {
    static let shared = LiveActivityManager()

    private enum DynamicIslandVisualStateReducer {
        static let minimumPlaybackStateDuration: TimeInterval = 0.8
        static let loadingConfirmationDuration: TimeInterval = 1.0
        static let disconnectedConfirmationDuration: TimeInterval = 3.0
    }

    private var activity: Activity<SonyMusicActivityAttributes>?
    private var activityStateTask: Task<Void, Never>?
    private var latestState: SonyMusicActivityAttributes.ContentState?
    private var stateVersion: UInt64 = 0
    private var lastSentState: SonyMusicActivityAttributes.ContentState?
    private var lastSentVersion: UInt64 = 0
    private var lastCalibrationDate = Date.distantPast
    private var updateInFlight = false
    private var pendingLatestState: SonyMusicActivityAttributes.ContentState?
    private var pendingLatestVersion: UInt64 = 0
    private var pendingLatestReason = "unknown"
    private var debounceTask: Task<Void, Never>?
    private var transitionResetTask: Task<Void, Never>?
    private var startInFlight = false
    private var startCooldownUntil = Date.distantPast
    private var updateCounterWindowStart = Date()
    private var updateCounterInWindow = 0
    private var stableIslandState: IslandState?
    private var stableIslandStateChangedAt = Date.distantPast
    private var pendingIslandState: IslandState?
    private var pendingIslandStateStartedAt = Date.distantPast

    private init() {}

    func update(
        title: String,
        artist: String,
        lyric: String,
        lyricLineIndex: Int = -1,
        isPlaying: Bool,
        positionMs: Int64,
        durationMs: Int64,
        trackId: String,
        artworkKey: String?,
        artworkRevision: Int,
        connectionState: String = "connected",
        appState: String = "unknown",
        reason: String = "unknown",
        force: Bool = false,
        logger: ((String) -> Void)? = nil
    ) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            logger?("[LiveActivity] unsupported")
            return
        }

        let state = makeState(
            title: title,
            artist: artist,
            lyric: lyric,
            lyricLineIndex: lyricLineIndex,
            isPlaying: isPlaying,
            positionMs: positionMs,
            durationMs: durationMs,
            trackId: trackId,
            artworkKey: artworkKey,
            artworkRevision: artworkRevision,
            connectionState: connectionState,
            reason: reason,
            logger: logger
        )
        let merged = mergeLatestState(candidate: state, reason: reason, logger: logger)
        let payloadBytes = payloadSize(merged)
        logger?(
            "[LiveActivity] update request reason=\(reason) " +
                "appState=\(appState) title=\(merged.title) lyric=\(merged.lyric) " +
                "lineIndex=\(merged.lyricLineIndex) trackId=\(merged.trackId) " +
                "islandState=\(merged.islandState)"
        )
        logger?("[LiveActivityPerf] payloadBytes=\(payloadBytes)")
        if payloadBytes >= 1_024 {
            logger?("[LiveActivityPerf] payload warning bytes=\(payloadBytes)")
        }

        guard shouldUpdate(
            candidate: merged,
            reason: reason,
            force: force,
            logger: logger
        ) else {
            return
        }

        if shouldDebounce(reason: reason, force: force) {
            pendingLatestState = merged
            pendingLatestVersion = stateVersion
            pendingLatestReason = mergedReason(existing: pendingLatestReason, incoming: reason)
            if debounceTask == nil {
                logger?("[LiveActivityPerf] update queued reason=\(reason)")
                debounceTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 200_000_000)
                    await MainActor.run {
                        guard let self else { return }
                        self.debounceTask = nil
                        self.flushPending(logger: logger)
                    }
                }
            } else {
                logger?("[LiveActivityPerf] update coalesced reason=\(reason)")
            }
            return
        }

        enqueue(state: merged, version: stateVersion, reason: reason, logger: logger)
        scheduleTransitionResetIfNeeded(for: merged, logger: logger)
    }

    func end(logger: ((String) -> Void)? = nil) {
        debounceTask?.cancel()
        debounceTask = nil
        transitionResetTask?.cancel()
        transitionResetTask = nil
        pendingLatestState = nil
        pendingLatestVersion = 0
        pendingLatestReason = "unknown"
        guard let activity else { return }
        let state = lastSentState
        self.activity = nil
        activityStateTask?.cancel()
        activityStateTask = nil
        Task {
            if let state {
                await activity.end(
                    ActivityContent(state: state, staleDate: nil),
                    dismissalPolicy: .after(Date().addingTimeInterval(60))
                )
            } else {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            logger?("[LiveActivity] end")
        }
    }

    private func mergeLatestState(
        candidate: SonyMusicActivityAttributes.ContentState,
        reason: String,
        logger: ((String) -> Void)?
    ) -> SonyMusicActivityAttributes.ContentState {
        let previous = latestState
        var merged = previous ?? candidate

        switch reason {
        case "artworkReady", "artworkUnavailable":
            merged.artworkKey = candidate.artworkKey
            merged.artworkRevision = candidate.artworkRevision
            if previous == nil {
                merged = candidate
            }
        case "disconnect":
            merged = candidate
        default:
            merged.trackId = candidate.trackId
            merged.title = candidate.title
            merged.artist = candidate.artist
            merged.lyric = candidate.lyric
            merged.lyricLineIndex = candidate.lyricLineIndex
            merged.isPlaying = candidate.isPlaying
            merged.positionAtAnchorMs = candidate.positionAtAnchorMs
            merged.anchorDate = candidate.anchorDate
            merged.durationMs = candidate.durationMs
            merged.connectionState = candidate.connectionState
            merged.islandState = candidate.islandState
            merged.islandStateChangedAt = candidate.islandStateChangedAt
            merged.dynamicIslandStyle = candidate.dynamicIslandStyle
            merged.artworkKey = candidate.artworkKey
            merged.artworkRevision = candidate.artworkRevision
        }

        latestState = merged
        if previous != merged {
            stateVersion += 1
            logger?(
                "[LiveActivityState] mutate version=\(stateVersion) " +
                    "reason=\(normalizedReason(reason)) lyric=\(merged.lyric) " +
                    "artworkRevision=\(merged.artworkRevision)"
            )
        } else {
            logger?(
                "[LiveActivityState] mutate skipped version=\(stateVersion) " +
                    "reason=\(normalizedReason(reason))"
            )
        }
        return merged
    }

    private func makeState(
        title: String,
        artist: String,
        lyric: String,
        lyricLineIndex: Int,
        isPlaying: Bool,
        positionMs: Int64,
        durationMs: Int64,
        trackId: String,
        artworkKey: String?,
        artworkRevision: Int,
        connectionState: String,
        reason: String,
        logger: ((String) -> Void)?
    ) -> SonyMusicActivityAttributes.ContentState {
        let cleanTitle = trimmed(
            cleaned(title, fallback: "等待同步"),
            limit: 48,
            field: "title",
            logger: logger
        )
        let cleanArtist = trimmed(
            cleaned(artist, fallback: "未知歌手"),
            limit: 40,
            field: "artist",
            logger: logger
        )
        let cleanLyric = trimmed(
            lyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "暂无歌词" : lyric,
            limit: 80,
            field: "lyric",
            logger: logger
        )
        let cleanTrackId = trimmed(
            trackId.isEmpty ? "\(cleanTitle)|\(cleanArtist)" : trackId,
            limit: 64,
            field: "trackId",
            logger: logger
        )
        let cleanConnectionState = normalizedConnectionState(connectionState)
        let cleanArtworkKey = artworkKey.flatMap {
            let value = trimmed($0, limit: 64, field: "artworkKey", logger: logger)
            return value.isEmpty ? nil : value
        }

        let rawIslandState = nextIslandState(
            reason: reason,
            title: cleanTitle,
            isPlaying: isPlaying,
            durationMs: durationMs,
            connectionState: cleanConnectionState,
            trackId: cleanTrackId
        )
        let reducedIslandState = reduceIslandState(
            rawState: rawIslandState,
            reason: reason,
            logger: logger
        )

        return SonyMusicActivityAttributes.ContentState(
            trackId: cleanTrackId,
            title: cleanTitle,
            artist: cleanArtist,
            lyric: cleanLyric,
            lyricLineIndex: lyricLineIndex,
            isPlaying: isPlaying,
            positionAtAnchorMs: max(positionMs, 0),
            anchorDate: Date(),
            durationMs: max(durationMs, 0),
            connectionState: cleanConnectionState,
            islandState: reducedIslandState.rawValue,
            islandStateChangedAt: stableIslandStateChangedAt,
            dynamicIslandStyle: PreferencesStore.shared.dynamicIslandStyle.rawValue,
            artworkKey: cleanArtworkKey,
            artworkRevision: max(artworkRevision, 0)
        )
    }

    private func shouldUpdate(
        candidate: SonyMusicActivityAttributes.ContentState,
        reason: String,
        force: Bool,
        logger: ((String) -> Void)?
    ) -> Bool {
        guard let previous = lastSentState else { return true }
        if duplicateIgnoringAnchor(previous, candidate) {
            logger?("[LiveActivityPerf] duplicate skipped")
            return false
        }

        if force || semanticChanged(previous, candidate) {
            return true
        }

        let driftMs = progressDriftMs(previous: previous, sonyState: candidate)
        if candidate.isPlaying {
            let sinceCalibration = Date().timeIntervalSince(lastCalibrationDate)
            if driftMs > 1_200, sinceCalibration >= 3 {
                logger?("[LiveActivityPerf] progress calibrate driftMs=\(driftMs)")
                return true
            }
            if sinceCalibration >= 15 {
                logger?("[LiveActivityPerf] progress calibrate driftMs=\(driftMs)")
                return true
            }
            logger?("[LiveActivityPerf] progress skip driftMs=\(driftMs)")
            return false
        }

        logger?("[LiveActivityPerf] progress skip driftMs=\(driftMs)")
        return false
    }

    private func shouldDebounce(reason: String, force: Bool) -> Bool {
        if force { return false }
        switch reason {
        case "playState", "seek", "disconnect", "firstStart":
            return false
        default:
            return true
        }
    }

    private func flushPending(logger: ((String) -> Void)?) {
        guard let state = pendingLatestState else { return }
        let reason = pendingLatestReason
        let version = pendingLatestVersion
        pendingLatestState = nil
        pendingLatestVersion = 0
        pendingLatestReason = "unknown"
        logger?("[LiveActivityState] pending flush version=\(version)")
        enqueue(state: state, version: version, reason: reason, logger: logger)
        scheduleTransitionResetIfNeeded(for: state, logger: logger)
    }

    private func enqueue(
        state: SonyMusicActivityAttributes.ContentState,
        version: UInt64,
        reason: String,
        logger: ((String) -> Void)?
    ) {
        pendingLatestState = state
        pendingLatestVersion = version
        pendingLatestReason = mergedReason(existing: pendingLatestReason, incoming: reason)
        if updateInFlight {
            logger?("[LiveActivityPerf] update coalesced reason=\(reason)")
            return
        }
        processNext(logger: logger)
    }

    private func processNext(logger: ((String) -> Void)?) {
        guard !updateInFlight,
              let state = pendingLatestState else {
            return
        }
        let reason = pendingLatestReason
        let version = pendingLatestVersion
        pendingLatestState = nil
        pendingLatestVersion = 0
        pendingLatestReason = "unknown"
        if isObsoletePending(state, logger: logger) {
            processNext(logger: logger)
            return
        }
        updateInFlight = true

        Task { [weak self] in
            guard let self else { return }
            let startedAt = Date()
            logger?("[LiveActivityPerf] update start reason=\(reason)")
            logger?("[LiveActivityState] update start version=\(version)")
            let target = await self.ensureActivity(for: state, logger: logger)
            if let target {
                let content = ActivityContent(
                    state: state,
                    staleDate: self.staleDate(for: state)
                )
                await target.update(content)
                await MainActor.run {
                    self.activity = target
                    self.lastSentState = state
                    self.lastSentVersion = version
                    self.lastCalibrationDate = Date()
                    self.recordUpdateSent(logger: logger)
                }
                logger?(
                    "[LiveActivity] update sent reason=\(reason) " +
                        "title=\(state.title) lyric=\(state.lyric) " +
                        "lineIndex=\(state.lyricLineIndex) position=\(state.positionAtAnchorMs)"
                )
                logger?("[LiveActivityState] update success version=\(version)")
            }
            let costMs = Int(Date().timeIntervalSince(startedAt) * 1_000)
            logger?("[LiveActivityPerf] update end costMs=\(costMs)")
            await MainActor.run {
                self.updateInFlight = false
                self.processNext(logger: logger)
            }
        }
    }

    private func ensureActivity(
        for state: SonyMusicActivityAttributes.ContentState,
        logger: ((String) -> Void)?
    ) async -> Activity<SonyMusicActivityAttributes>? {
        if let activity {
            endDuplicateActivities(keeping: activity.id, logger: logger)
            return activity
        }

        logger?("[LiveActivity] activity missing, lookup existing")
        if let existing = restoreExistingActivity(logger: logger) {
            return existing
        }

        return await start(state: state, logger: logger)
    }

    private func start(
        state: SonyMusicActivityAttributes.ContentState,
        logger: ((String) -> Void)?
    ) async -> Activity<SonyMusicActivityAttributes>? {
        if let existing = restoreExistingActivity(logger: logger) {
            return existing
        }
        if startInFlight {
            logger?("[LiveActivity] start skipped reason=start in flight")
            return nil
        }
        if Date() < startCooldownUntil {
            logger?("[LiveActivity] start skipped reason=cooldown")
            return nil
        }
        startInFlight = true
        defer { startInFlight = false }

        do {
            logger?("[LiveActivity] start requested once")
            let attributes = SonyMusicActivityAttributes(name: "Sony Music")
            let requested = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: staleDate(for: state)),
                pushType: nil
            )
            activity = requested
            observeStateUpdates(for: requested, logger: logger)
            lastSentState = state
            lastSentVersion = stateVersion
            lastCalibrationDate = Date()
            logger?("[LiveActivity] start id=\(requested.id)")
            return requested
        } catch {
            startCooldownUntil = Date().addingTimeInterval(5)
            logger?("[LiveActivity] error=\(error.localizedDescription)")
            return nil
        }
    }

    private func restoreExistingActivity(
        logger: ((String) -> Void)?
    ) -> Activity<SonyMusicActivityAttributes>? {
        let activities = Activity<SonyMusicActivityAttributes>.activities
        guard let existing = activities.first else { return nil }
        activity = existing
        observeStateUpdates(for: existing, logger: logger)
        endDuplicateActivities(keeping: existing.id, logger: logger)
        logger?("[LiveActivity] existing restored id=\(existing.id)")
        return existing
    }

    private func endDuplicateActivities(
        keeping activityID: String?,
        logger: ((String) -> Void)?
    ) {
        for duplicate in Activity<SonyMusicActivityAttributes>.activities
            where duplicate.id != activityID {
            logger?("[LiveActivity] duplicate activity ending id=\(duplicate.id)")
            Task {
                await duplicate.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    private func observeStateUpdates(
        for activity: Activity<SonyMusicActivityAttributes>,
        logger: ((String) -> Void)?
    ) {
        activityStateTask?.cancel()
        activityStateTask = Task { [weak self] in
            for await state in activity.activityStateUpdates {
                await MainActor.run {
                    logger?("[LiveActivity] state changed state=\(state)")
                    switch state {
                    case .active, .stale:
                        self?.activity = activity
                    case .ended, .dismissed:
                        if self?.activity?.id == activity.id {
                            self?.activity = nil
                        }
                        self?.startCooldownUntil = Date().addingTimeInterval(5)
                    @unknown default:
                        break
                    }
                }
            }
        }
    }

    private func semanticChanged(
        _ previous: SonyMusicActivityAttributes.ContentState,
        _ current: SonyMusicActivityAttributes.ContentState
    ) -> Bool {
        previous.trackId != current.trackId ||
            previous.title != current.title ||
            previous.artist != current.artist ||
            previous.lyric != current.lyric ||
            previous.lyricLineIndex != current.lyricLineIndex ||
            previous.isPlaying != current.isPlaying ||
            previous.durationMs != current.durationMs ||
            previous.connectionState != current.connectionState ||
            previous.islandState != current.islandState ||
            previous.dynamicIslandStyle != current.dynamicIslandStyle ||
            previous.artworkKey != current.artworkKey ||
            previous.artworkRevision != current.artworkRevision
    }

    private func duplicateIgnoringAnchor(
        _ previous: SonyMusicActivityAttributes.ContentState,
        _ current: SonyMusicActivityAttributes.ContentState
    ) -> Bool {
        !semanticChanged(previous, current) &&
            previous.positionAtAnchorMs == current.positionAtAnchorMs
    }

    private func progressDriftMs(
        previous: SonyMusicActivityAttributes.ContentState,
        sonyState: SonyMusicActivityAttributes.ContentState
    ) -> Int64 {
        let estimated: Int64
        if previous.isPlaying {
            let elapsedMs = Int64(Date().timeIntervalSince(previous.anchorDate) * 1_000)
            estimated = min(
                max(previous.positionAtAnchorMs + elapsedMs, 0),
                max(previous.durationMs, previous.positionAtAnchorMs)
            )
        } else {
            estimated = previous.positionAtAnchorMs
        }
        return abs(sonyState.positionAtAnchorMs - estimated)
    }

    private func isObsoletePending(
        _ state: SonyMusicActivityAttributes.ContentState,
        logger: ((String) -> Void)?
    ) -> Bool {
        guard let previous = lastSentState else { return false }
        if duplicateIgnoringAnchor(previous, state) {
            logger?("[LiveActivityPerf] duplicate skipped")
            return true
        }
        if semanticChanged(previous, state) {
            return false
        }
        let driftMs = progressDriftMs(previous: previous, sonyState: state)
        if state.isPlaying,
           driftMs <= 1_200,
           Date().timeIntervalSince(lastCalibrationDate) < 15 {
            logger?("[LiveActivityPerf] progress skip driftMs=\(driftMs)")
            return true
        }
        return false
    }

    private func staleDate(for state: SonyMusicActivityAttributes.ContentState) -> Date {
        if state.connectionState == "disconnected" || state.connectionState == "reconnecting" {
            return Date().addingTimeInterval(30)
        }
        return Date().addingTimeInterval(state.isPlaying ? 45 : 5 * 60)
    }

    private func payloadSize(_ state: SonyMusicActivityAttributes.ContentState) -> Int {
        (try? JSONEncoder().encode(state).count) ?? 0
    }

    private func nextIslandState(
        reason: String,
        title: String,
        isPlaying: Bool,
        durationMs: Int64,
        connectionState: String,
        trackId: String
    ) -> IslandState {
        if connectionState == "disconnected" {
            return .disconnected
        }
        if connectionState == "reconnecting" {
            return .buffering
        }
        if reason == "trackInfo",
           latestState?.trackId != trackId {
            return .trackChanged
        }
        if reason == "seek" {
            return .seeking
        }
        let noUsableTrack = title.isEmpty ||
            title == "-" ||
            (title == "Sony Music" && durationMs <= 0)
        if noUsableTrack {
            return .connecting
        }
        return isPlaying ? .playing : .paused
    }

    private func baseIslandState(for state: SonyMusicActivityAttributes.ContentState) -> IslandState {
        if state.connectionState == "disconnected" {
            return .disconnected
        }
        if state.connectionState == "reconnecting" {
            return .buffering
        }
        let noUsableTrack = state.title.isEmpty ||
            state.title == "-" ||
            (state.title == "Sony Music" && state.durationMs <= 0)
        if noUsableTrack {
            return .connecting
        }
        return state.isPlaying ? .playing : .paused
    }

    private func scheduleTransitionResetIfNeeded(
        for state: SonyMusicActivityAttributes.ContentState,
        logger: ((String) -> Void)?
    ) {
        let islandState = IslandState.resolved(from: state.islandState)
        guard islandState.isTransient else { return }

        transitionResetTask?.cancel()
        let trackId = state.trackId
        let transientState = state.islandState
        transitionResetTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            await MainActor.run {
                self?.finishTransientIslandState(
                    trackId: trackId,
                    transientState: transientState,
                    logger: logger
                )
            }
        }
    }

    private func finishTransientIslandState(
        trackId: String,
        transientState: String,
        logger: ((String) -> Void)?
    ) {
        guard var state = latestState,
              state.trackId == trackId,
              state.islandState == transientState else {
            return
        }

        let baseState = baseIslandState(for: state)
        guard baseState.rawValue != state.islandState else { return }
        acceptIslandState(baseState, now: Date())
        state.islandState = baseState.rawValue
        state.islandStateChangedAt = stableIslandStateChangedAt
        latestState = state
        stateVersion += 1
        logger?(
            "[LiveActivityState] transition reset " +
                "trackId=\(trackId) from=\(transientState) to=\(baseState.rawValue)"
        )
        enqueue(
            state: state,
            version: stateVersion,
            reason: "islandTransitionEnd",
            logger: logger
        )
    }

    private func recordUpdateSent(logger: ((String) -> Void)?) {
        let now = Date()
        if now.timeIntervalSince(updateCounterWindowStart) >= 60 {
            logger?(
                "[LiveActivityPerf] update count last60s=\(updateCounterInWindow)"
            )
            updateCounterWindowStart = now
            updateCounterInWindow = 0
        }
        updateCounterInWindow += 1
        logger?("[LiveActivityPerf] update count window=\(updateCounterInWindow)")
    }

    private func cleaned(_ value: String, fallback: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "-" {
            return fallback
        }
        return trimmed
    }

    private func trimmed(
        _ value: String,
        limit: Int,
        field: String,
        logger: ((String) -> Void)?
    ) -> String {
        if value.count <= limit { return value }
        logger?("[LiveActivityPerf] payload trimmed field=\(field)")
        return String(value.prefix(limit))
    }

    private func normalizedReason(_ reason: String) -> String {
        reason == "lyric" ? "lyricChanged" : reason
    }

    private func normalizedConnectionState(_ value: String) -> String {
        switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "disconnected":
            return "disconnected"
        case "reconnecting", "connecting", "syncing", "loading", "subscribing", "searching", "stale":
            return "reconnecting"
        default:
            return "connected"
        }
    }

    private func reduceIslandState(
        rawState: IslandState,
        reason: String,
        logger: ((String) -> Void)?
    ) -> IslandState {
        let now = Date()
        guard let current = stableIslandState else {
            acceptIslandState(rawState, now: now)
            return rawState
        }

        if rawState == current {
            pendingIslandState = nil
            pendingIslandStateStartedAt = Date.distantPast
            return current
        }

        if rawState == .disconnected, reason == "disconnect" {
            acceptIslandState(rawState, now: now)
            return rawState
        }

        if rawState == .disconnected,
           pendingDuration(for: rawState, now: now) <
            DynamicIslandVisualStateReducer.disconnectedConfirmationDuration {
            logSuppressedIslandState(
                old: current,
                new: rawState,
                reason: "disconnected_pending",
                logger: logger
            )
            return current
        }

        if rawState == .buffering || rawState == .connecting {
            let loadingDuration = pendingDuration(for: rawState, now: now)
            if loadingDuration < DynamicIslandVisualStateReducer.loadingConfirmationDuration {
                logSuppressedIslandState(
                    old: current,
                    new: rawState,
                    reason: "loading_pending",
                    logger: logger
                )
                return current
            }
        }

        if isPlaybackState(current),
           isPlaybackState(rawState),
           now.timeIntervalSince(stableIslandStateChangedAt) <
            DynamicIslandVisualStateReducer.minimumPlaybackStateDuration {
            logSuppressedIslandState(
                old: current,
                new: rawState,
                reason: "min_duration",
                logger: logger
            )
            return current
        }

        acceptIslandState(rawState, now: now)
        return rawState
    }

    private func acceptIslandState(_ state: IslandState, now: Date) {
        if stableIslandState != state {
            stableIslandState = state
            stableIslandStateChangedAt = now
        }
        pendingIslandState = nil
        pendingIslandStateStartedAt = Date.distantPast
    }

    private func pendingDuration(for state: IslandState, now: Date) -> TimeInterval {
        if pendingIslandState != state {
            pendingIslandState = state
            pendingIslandStateStartedAt = now
            return 0
        }
        return now.timeIntervalSince(pendingIslandStateStartedAt)
    }

    private func isPlaybackState(_ state: IslandState) -> Bool {
        state == .playing || state == .paused
    }

    private func logSuppressedIslandState(
        old: IslandState,
        new: IslandState,
        reason: String,
        logger: ((String) -> Void)?
    ) {
        let elapsedMs = Int(Date().timeIntervalSince(stableIslandStateChangedAt) * 1_000)
        logger?(
            "[DynamicIslandState] suppress flicker old=\(old.rawValue) " +
                "new=\(new.rawValue) reason=\(reason) elapsedMs=\(elapsedMs)"
        )
    }

    private func mergedReason(existing: String, incoming: String) -> String {
        let current = normalizedReason(existing)
        let next = normalizedReason(incoming)
        let priority = [
            "unknown": 0,
            "playbackState": 1,
            "albumArt": 1,
            "seek": 2,
            "playState": 3,
            "artworkUnavailable": 4,
            "artworkReady": 5,
            "lyricChanged": 6,
            "connectionState": 7,
            "trackInfo": 7,
            "disconnect": 8
        ]
        return (priority[next, default: 1] >= priority[current, default: 1]) ? next : current
    }
}
