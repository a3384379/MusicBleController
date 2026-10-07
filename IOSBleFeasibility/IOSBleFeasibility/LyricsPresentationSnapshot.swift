import Foundation

enum FloatingLyricsLineMode: String, CaseIterable, Identifiable, Sendable {
    case single, double

    var id: String { rawValue }
    var title: String { AppLocalization.string(self == .single ? "单行" : "双行") }
    var pixelHeight: Int { self == .single ? 60 : 104 }
    static let userDefaultsKey = "floatingLyricsLineMode"
}

enum FloatingLyricsTheme: String, CaseIterable, Identifiable, Sendable {
    case warm, mint, blue, lavender, rose, slate

    static let userDefaultsKey = "floatingLyricsTheme"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .warm: return AppLocalization.string("暖棕")
        case .mint: return AppLocalization.string("薄荷")
        case .blue: return AppLocalization.string("雾蓝")
        case .lavender: return AppLocalization.string("淡紫")
        case .rose: return AppLocalization.string("玫瑰")
        case .slate: return AppLocalization.string("深灰")
        }
    }

    struct RGB: Equatable, Sendable {
        let hex: UInt32
        var red: Double { Double((hex >> 16) & 255) / 255 }
        var green: Double { Double((hex >> 8) & 255) / 255 }
        var blue: Double { Double(hex & 255) / 255 }
    }

    struct Palette: Sendable {
        let start: RGB
        let end: RGB
        let current: RGB
        let next: RGB

        init(_ start: UInt32, _ end: UInt32, _ current: UInt32, _ next: UInt32) {
            self.start = RGB(hex: start)
            self.end = RGB(hex: end)
            self.current = RGB(hex: current)
            self.next = RGB(hex: next)
        }
    }

    var palette: Palette {
        switch self {
        case .warm: return Palette(0x6B473B, 0xAD7352, 0xFFF5E3, 0xF5E0C9)
        case .mint: return Palette(0x26574F, 0x457F6B, 0xF5FFF2, 0xCFF5DA)
        case .blue: return Palette(0x365473, 0x5482A3, 0xF7FCFF, 0xDAF0FF)
        case .lavender: return Palette(0x4F456E, 0x7D6E9E, 0xFCF7FF, 0xF0E0FF)
        case .rose: return Palette(0x733D52, 0xA36475, 0xFFF5FA, 0xFFDBE6)
        case .slate: return Palette(0x303845, 0x525C6E, 0xFAFAFF, 0xD9E3F2)
        }
    }
}

struct FloatingLyricsAppearance: Equatable, Sendable {
    var lineMode: FloatingLyricsLineMode = .double
    var showsTitle = false
    var theme: FloatingLyricsTheme = .warm
    static let titleKey = "floatingLyricsShowsTitle"

    init(lineMode: FloatingLyricsLineMode = .double, showsTitle: Bool = false,
         theme: FloatingLyricsTheme = .warm) {
        self.lineMode = lineMode
        self.showsTitle = showsTitle
        self.theme = theme
    }

    init(defaults: UserDefaults) {
        lineMode = defaults.string(forKey: FloatingLyricsLineMode.userDefaultsKey)
            .flatMap(FloatingLyricsLineMode.init(rawValue:)) ?? .double
        showsTitle = defaults.object(forKey: Self.titleKey) as? Bool ?? false
        theme = defaults.string(forKey: FloatingLyricsTheme.userDefaultsKey)
            .flatMap(FloatingLyricsTheme.init(rawValue:)) ?? .warm
    }

    func pixelHeight(hasControlFeedback: Bool) -> Int {
        lineMode.pixelHeight + (showsTitle ? 24 : 0) + (hasControlFeedback ? 28 : 0)
    }
}

struct LyricsDisplayPreferences: Equatable {
    static let compactKey = "compactLyricsEnabled"
    static let floatingKey = "floatingLyricsEnabled"

    var compactEnabled: Bool
    var floatingEnabled: Bool

    init(defaults: UserDefaults) {
        // Display switches describe this app session. Older saved "on" values
        // must not imply that a window exists after a cold launch.
        compactEnabled = false
        floatingEnabled = false
        defaults.removeObject(forKey: Self.compactKey)
        defaults.removeObject(forKey: Self.floatingKey)
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
        var nextText: String = ""
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
    var playbackControlState: LyricsPlaybackControlState = .idle
    var nextText: String = ""

    var key: Key {
        Key(trackID: trackID, trackGeneration: trackGeneration,
            timelineRevision: timelineRevision, lineIndex: lineIndex, text: text,
            title: title, artist: artist, isPlaying: isPlaying, status: status, nextText: nextText)
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
    private(set) var restartRequested = false

    mutating func setEnabled(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value
        if value {
            if state != .stopping { state = .stopped }
        } else {
            restartRequested = false
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
        restartRequested = false
        generation &+= 1
        state = .stopping
    }

    mutating func requestRestartAfterStop() -> Bool {
        guard enabled, state == .stopping else { return false }
        restartRequested = true
        return true
    }

    mutating func cancelRestartAfterStop() { restartRequested = false }

    mutating func takeRestartAfterStop() -> Bool {
        let shouldRestart = enabled && restartRequested
        restartRequested = false
        return shouldRestart
    }

    mutating func didStop() {
        generation &+= 1
        state = enabled ? .stopped : .disabled
    }

    mutating func fail(unavailable: Bool = false) {
        restartRequested = false
        generation &+= 1
        state = enabled ? (unavailable ? .unavailable : .failed) : .disabled
    }
}

/// The wire protocol exposes toggle. Keep desired, confirmed and in-flight
/// states separate: elapsed time alone cannot prove a toggle was not executed.
enum LyricsPlaybackControlState: Equatable, Sendable {
    case idle, pending, unknown, failed, unavailable

    var message: String {
        switch self {
        case .idle: return ""
        case .pending: return AppLocalization.string("播放控制已发送，等待确认")
        case .unknown: return AppLocalization.string("播放控制结果未知，请回到应用重新连接后重试")
        case .failed: return AppLocalization.string("播放控制未执行，状态同步后可重试")
        case .unavailable: return AppLocalization.string("播放状态未同步，无法执行控制")
        }
    }
}

struct LyricsPlaybackTargetPolicy {
    struct CommandReceipt: Equatable {
        let sequence: UInt64
        let trackID: String
        let generation: Int64
        let connectionEpoch: UInt64
        let serverSessionID: String
        let sentAt: TimeInterval
    }

    private struct InFlight {
        let target: Bool
        let confirmationRevision: UInt64
        var receipt: CommandReceipt?
    }

    let confirmationTimeout: TimeInterval
    private var identity: (trackID: String, generation: Int64, connectionEpoch: UInt64, serverSessionID: String)?
    private var desired: Bool?
    private var desiredExpiresAt: TimeInterval?
    private var confirmed: Bool?
    private var confirmedRevision: UInt64 = 0
    private var inFlight: InFlight?
    private var rejectedAtRevision: UInt64?
    private var stateQueryNeeded = false
    private(set) var controlState: LyricsPlaybackControlState = .idle

    var receipt: CommandReceipt? { inFlight?.receipt }

    init(confirmationTimeout: TimeInterval = 8) {
        self.confirmationTimeout = confirmationTimeout
    }

    mutating func request(target: Bool, snapshot: LyricsPresentationSnapshot,
                          confirmationRevision: UInt64, now: TimeInterval,
                          connectionEpoch: UInt64 = 0, serverSessionID: String = "-") -> Bool {
        expire(now: now)
        guard snapshot.hasAuthoritativePlayback, now < snapshot.validUntilUptime else { return false }
        adoptIdentity(snapshot, connectionEpoch: connectionEpoch, serverSessionID: serverSessionID)
        acceptConfirmation(snapshot, revision: confirmationRevision)
        guard controlState != .unknown, rejectedAtRevision == nil else { return false }
        desired = target
        desiredExpiresAt = now + confirmationTimeout
        controlState = .pending
        return reserveCommand()
    }

    mutating func reconcile(snapshot: LyricsPresentationSnapshot,
                            confirmationRevision: UInt64, now: TimeInterval,
                            connectionEpoch: UInt64 = 0, serverSessionID: String = "-") -> Bool {
        expire(now: now)
        guard snapshot.hasAuthoritativePlayback, now < snapshot.validUntilUptime else { return false }
        adoptIdentity(snapshot, connectionEpoch: connectionEpoch, serverSessionID: serverSessionID)
        acceptConfirmation(snapshot, revision: confirmationRevision)
        return reserveCommand()
    }

    mutating func commandSent(sequence: UInt64, now: TimeInterval) {
        guard let identity, inFlight != nil else { return }
        inFlight?.receipt = CommandReceipt(
            sequence: sequence, trackID: identity.trackID, generation: identity.generation,
            connectionEpoch: identity.connectionEpoch, serverSessionID: identity.serverSessionID, sentAt: now
        )
    }

    /// A transport refusal before sending is safe to release. A timeout is not.
    mutating func commandNotSent() { inFlight = nil }

    mutating func commandSendFailed() {
        commandNotSent()
        cancelDesired()
        controlState = .failed
    }

    @discardableResult
    mutating func receiveCommandError(_ payload: BLECommandErrorPayload,
                                     connectionEpoch: UInt64, serverSessionID: String) -> Bool {
        guard let receipt, payload.sequence == receipt.sequence, payload.command == "PLAY_PAUSE",
              connectionEpoch == receipt.connectionEpoch, serverSessionID == receipt.serverSessionID,
              payload.trackId == nil || payload.trackId == receipt.trackID,
              payload.generation == nil || payload.generation == receipt.generation,
              payload.metadata == nil || payload.metadata?.sessionId == receipt.serverSessionID else { return false }
        // Sony's protocol dispatcher returns before executing an unknown command.
        // ATT errors, retryable flags and arbitrary business errors prove nothing
        // about a non-idempotent toggle's execution and must keep the reservation.
        if payload.domain == .protocol, payload.code == "unknown_command" {
            inFlight = nil
            cancelDesired()
            rejectedAtRevision = confirmedRevision
            controlState = .failed
            stateQueryNeeded = true
        } else {
            markUnknown()
        }
        return true
    }

    mutating func expire(now: TimeInterval) {
        if let receipt, now >= receipt.sentAt + confirmationTimeout { markUnknown() }
        if let desiredExpiresAt, now >= desiredExpiresAt {
            cancelDesired()
            if inFlight == nil { controlState = .failed }
        }
    }

    mutating func takeStateQuery() -> Bool {
        let needed = stateQueryNeeded
        stateQueryNeeded = false
        return needed
    }

    mutating func cancelDesired() {
        desired = nil
        desiredExpiresAt = nil
        if inFlight == nil, rejectedAtRevision == nil { controlState = .idle }
    }

    private mutating func markUnknown() {
        guard controlState != .unknown else { return }
        cancelDesired()
        controlState = .unknown
        stateQueryNeeded = true
    }

    mutating func clear() {
        identity = nil
        desired = nil
        desiredExpiresAt = nil
        confirmed = nil
        confirmedRevision = 0
        inFlight = nil
        rejectedAtRevision = nil
        stateQueryNeeded = false
        controlState = .idle
    }

    private mutating func adoptIdentity(_ snapshot: LyricsPresentationSnapshot,
                                       connectionEpoch: UInt64, serverSessionID: String) {
        if identity?.trackID != snapshot.trackID || identity?.generation != snapshot.trackGeneration ||
            identity?.connectionEpoch != connectionEpoch || identity?.serverSessionID != serverSessionID {
            clear()
            identity = (snapshot.trackID, snapshot.trackGeneration, connectionEpoch, serverSessionID)
        }
    }

    private mutating func acceptConfirmation(_ snapshot: LyricsPresentationSnapshot, revision: UInt64) {
        guard confirmed == nil || revision > confirmedRevision else { return }
        confirmed = snapshot.isPlaying
        confirmedRevision = revision
        if let rejectedAtRevision, revision > rejectedAtRevision { self.rejectedAtRevision = nil }
        if let inFlight, revision > inFlight.confirmationRevision, snapshot.isPlaying == inFlight.target {
            self.inFlight = nil
            controlState = .idle
        }
    }

    private mutating func reserveCommand() -> Bool {
        guard let desired, let confirmed, inFlight == nil else { return false }
        guard desired != confirmed else {
            cancelDesired()
            return false
        }
        inFlight = InFlight(target: desired, confirmationRevision: confirmedRevision)
        controlState = .pending
        return true
    }
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
