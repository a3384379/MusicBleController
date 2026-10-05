import Foundation

struct LyricsDisplayPreferences: Equatable {
    static let compactKey = "compactLyricsEnabled"
    static let floatingKey = "floatingLyricsEnabled"

    var compactEnabled: Bool
    var floatingEnabled: Bool

    init(defaults: UserDefaults) {
        compactEnabled = defaults.object(forKey: Self.compactKey) as? Bool ?? false
        floatingEnabled = defaults.object(forKey: Self.floatingKey) as? Bool ?? false
    }
}

/// A small projection of the accepted playback state, never a second lyrics cache.
struct LyricsPresentationSnapshot: Equatable, Sendable {
    enum Status: String, Sendable {
        case ready, intro, instrumental, loading, unavailable, disconnected, syncing, stale
    }

    struct Key: Equatable, Sendable {
        let trackID: String
        let trackGeneration: Int64
        let timelineRevision: UInt64
        let lineIndex: Int
        let text: String
        let title: String
        let artist: String
        let isPlaying: Bool
        let status: Status
    }

    let trackID: String
    let trackGeneration: Int64
    let revision: UInt64
    let timelineRevision: UInt64
    let lineIndex: Int
    let text: String
    let title: String
    let artist: String
    let isPlaying: Bool
    let status: Status
    let validUntilUptime: TimeInterval

    var key: Key {
        Key(trackID: trackID, trackGeneration: trackGeneration,
            timelineRevision: timelineRevision, lineIndex: lineIndex, text: text,
            title: title, artist: artist, isPlaying: isPlaying, status: status)
    }

    var hasAuthoritativePlayback: Bool {
        !trackID.isEmpty && status != .disconnected && status != .syncing && status != .stale
    }

    var displayText: String {
        switch status {
        case .ready: return text
        case .intro: return AppLocalization.string("前奏")
        case .instrumental: return AppLocalization.string("间奏")
        case .loading: return AppLocalization.string("歌词加载中")
        case .unavailable: return AppLocalization.string("暂无歌词")
        case .disconnected: return AppLocalization.string("连接已断开")
        case .syncing: return AppLocalization.string("等待同步")
        case .stale: return AppLocalization.string("歌词已过期，等待同步")
        }
    }
}

/// Callback generations prevent a late PiP start from undoing a user's stop.
struct LyricsPiPStateMachine {
    enum State: String {
        case disabled, preparing, ready, starting, active, stopping, stopped, unavailable, failed

        var title: String {
            switch self {
            case .disabled: return AppLocalization.string("未开启")
            case .preparing: return AppLocalization.string("准备中")
            case .ready: return AppLocalization.string("可以显示")
            case .starting: return AppLocalization.string("正在显示")
            case .active: return AppLocalization.string("已显示")
            case .stopping: return AppLocalization.string("正在停止")
            case .stopped: return AppLocalization.string("已停止")
            case .unavailable: return AppLocalization.string("暂不可用")
            case .failed: return AppLocalization.string("显示失败")
            }
        }
    }

    private(set) var enabled = false
    private(set) var state: State = .disabled
    private(set) var generation: UInt64 = 0

    mutating func setEnabled(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value
        if value {
            if state != .stopping { state = .stopped }
        } else {
            generation &+= 1
            state = state == .active || state == .starting ? .stopping : .disabled
        }
    }

    mutating func prepare() -> UInt64? {
        guard enabled, state != .active, state != .starting, state != .stopping else { return nil }
        generation &+= 1
        state = .preparing
        return generation
    }

    mutating func ready(generation token: UInt64) {
        guard enabled, token == generation, state == .preparing else { return }
        state = .ready
    }

    mutating func start(generation token: UInt64) -> Bool {
        guard enabled, token == generation, state == .ready else { return false }
        state = .starting
        return true
    }

    mutating func didStart(generation token: UInt64) -> Bool {
        guard enabled, token == generation, state == .starting else { return false }
        state = .active
        return true
    }

    mutating func stop() {
        generation &+= 1
        state = .stopping
    }

    mutating func didStop() {
        generation &+= 1
        state = enabled ? .stopped : .disabled
    }

    mutating func fail(unavailable: Bool = false) {
        generation &+= 1
        state = enabled ? (unavailable ? .unavailable : .failed) : .disabled
    }
}

/// The wire protocol exposes toggle. Suppress repeated target-state requests
/// until Sony confirms the first request, rather than toggling for each callback.
struct LyricsPlaybackTargetPolicy {
    private var pending: (trackID: String, generation: Int64, target: Bool, deadline: TimeInterval)?

    mutating func shouldSend(target: Bool, snapshot: LyricsPresentationSnapshot, now: TimeInterval) -> Bool {
        if let pending,
           pending.trackID != snapshot.trackID || pending.generation != snapshot.trackGeneration ||
            pending.target == snapshot.isPlaying || now >= pending.deadline {
            self.pending = nil
        }
        guard snapshot.hasAuthoritativePlayback, now < snapshot.validUntilUptime,
              snapshot.isPlaying != target, pending == nil else { return false }
        pending = (snapshot.trackID, snapshot.trackGeneration, target, now + 3)
        return true
    }

    mutating func clear() { pending = nil }
}

/// Bounded render scheduling: one in-flight request and one replaceable latest
/// snapshot. The production controller uses this same gate for completion.
struct LatestLyricsFrameQueue {
    struct Token: Equatable, Sendable {
        let epoch: UInt64
        let sequence: UInt64
        let key: LyricsPresentationSnapshot.Key
    }

    struct Request: Sendable {
        let snapshot: LyricsPresentationSnapshot
        let token: Token
    }

    private var epoch: UInt64 = 0
    private var sequence: UInt64 = 0
    private var latestKey: LyricsPresentationSnapshot.Key?
    private var inFlight: Request?
    private var pending: LyricsPresentationSnapshot?

    var hasPending: Bool { inFlight == nil && pending != nil }

    mutating func offer(_ snapshot: LyricsPresentationSnapshot, force: Bool) -> Bool {
        guard force || snapshot.key != latestKey else { return false }
        latestKey = snapshot.key
        pending = snapshot
        return true
    }

    mutating func begin() -> Request? {
        guard inFlight == nil, let snapshot = pending else { return nil }
        pending = nil
        sequence &+= 1
        let token = Token(epoch: epoch, sequence: sequence, key: snapshot.key)
        let request = Request(snapshot: snapshot, token: token)
        inFlight = request
        return request
    }

    mutating func finish(_ token: Token, currentKey: LyricsPresentationSnapshot.Key?,
                         retryIfCurrent: Bool = false) -> Bool {
        guard let request = inFlight, token == request.token else { return false }
        inFlight = nil
        let current = token.epoch == epoch && token.key == latestKey && token.key == currentKey
        if current && retryIfCurrent && pending == nil { pending = request.snapshot }
        return current
    }

    mutating func invalidate() {
        epoch &+= 1
        inFlight = nil
        pending = nil
        latestKey = nil
    }
}
