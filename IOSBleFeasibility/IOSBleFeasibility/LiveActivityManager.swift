import ActivityKit
import Foundation

@MainActor
final class LiveActivityManager {
    static let shared = LiveActivityManager()

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
    private var startInFlight = false
    private var startCooldownUntil = Date.distantPast

    private init() {}

    func update(
        title: String,
        artist: String,
        lyric: String,
        isPlaying: Bool,
        positionMs: Int64,
        durationMs: Int64,
        trackId: String,
        artworkKey: String?,
        artworkRevision: Int,
        connectionState: String = "connected",
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
            isPlaying: isPlaying,
            positionMs: positionMs,
            durationMs: durationMs,
            trackId: trackId,
            artworkKey: artworkKey,
            artworkRevision: artworkRevision,
            connectionState: connectionState,
            logger: logger
        )
        let merged = mergeLatestState(candidate: state, reason: reason, logger: logger)
        let payloadBytes = payloadSize(merged)
        logger?(
            "[LiveActivity] update request reason=\(reason) " +
                "title=\(merged.title) lyric=\(merged.lyric) trackId=\(merged.trackId)"
        )
        logger?("[LiveActivityPerf] payloadBytes=\(payloadBytes)")

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
    }

    func end(logger: ((String) -> Void)? = nil) {
        debounceTask?.cancel()
        debounceTask = nil
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
            merged.isPlaying = candidate.isPlaying
            merged.positionAtAnchorMs = candidate.positionAtAnchorMs
            merged.anchorDate = candidate.anchorDate
            merged.durationMs = candidate.durationMs
            merged.connectionState = candidate.connectionState
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
        isPlaying: Bool,
        positionMs: Int64,
        durationMs: Int64,
        trackId: String,
        artworkKey: String?,
        artworkRevision: Int,
        connectionState: String,
        logger: ((String) -> Void)?
    ) -> SonyMusicActivityAttributes.ContentState {
        let cleanTitle = trimmed(
            cleaned(title, fallback: "Sony Music"),
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
        let cleanConnectionState = connectionState == "disconnected" ? "disconnected" : "connected"
        let cleanArtworkKey = artworkKey.flatMap {
            let value = trimmed($0, limit: 64, field: "artworkKey", logger: logger)
            return value.isEmpty ? nil : value
        }

        return SonyMusicActivityAttributes.ContentState(
            trackId: cleanTrackId,
            title: cleanTitle,
            artist: cleanArtist,
            lyric: cleanLyric,
            isPlaying: isPlaying,
            positionAtAnchorMs: max(positionMs, 0),
            anchorDate: Date(),
            durationMs: max(durationMs, 0),
            connectionState: cleanConnectionState,
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
            do {
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
                    }
                    logger?(
                        "[LiveActivity] update sent reason=\(reason) " +
                            "title=\(state.title) lyric=\(state.lyric) " +
                            "position=\(state.positionAtAnchorMs)"
                    )
                    logger?("[LiveActivityState] update success version=\(version)")
                }
            } catch {
                logger?("[LiveActivity] error=\(error.localizedDescription)")
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
        if let activity { return activity }

        logger?("[LiveActivity] activity missing, lookup existing")
        if let existing = Activity<SonyMusicActivityAttributes>.activities.first {
            activity = existing
            observeStateUpdates(for: existing, logger: logger)
            logger?("[LiveActivity] existing activity restored id=\(existing.id)")
            return existing
        }

        return await start(state: state, logger: logger)
    }

    private func start(
        state: SonyMusicActivityAttributes.ContentState,
        logger: ((String) -> Void)?
    ) async -> Activity<SonyMusicActivityAttributes>? {
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
            lastCalibrationDate = Date()
            logger?("[LiveActivity] start")
            return requested
        } catch {
            startCooldownUntil = Date().addingTimeInterval(5)
            logger?("[LiveActivity] error=\(error.localizedDescription)")
            return nil
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
                        self?.activity = nil
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
            previous.isPlaying != current.isPlaying ||
            previous.durationMs != current.durationMs ||
            previous.connectionState != current.connectionState ||
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
        if state.connectionState == "disconnected" {
            return Date().addingTimeInterval(60)
        }
        return Date().addingTimeInterval(state.isPlaying ? 45 : 5 * 60)
    }

    private func payloadSize(_ state: SonyMusicActivityAttributes.ContentState) -> Int {
        (try? JSONEncoder().encode(state).count) ?? 0
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

    private func mergedReason(existing: String, incoming: String) -> String {
        let current = normalizedReason(existing)
        let next = normalizedReason(incoming)
        let priority = [
            "unknown": 0,
            "playbackState": 1,
            "seek": 2,
            "playState": 3,
            "artworkUnavailable": 4,
            "artworkReady": 5,
            "lyricChanged": 6,
            "trackInfo": 7,
            "disconnect": 8
        ]
        return (priority[next, default: 1] >= priority[current, default: 1]) ? next : current
    }
}
