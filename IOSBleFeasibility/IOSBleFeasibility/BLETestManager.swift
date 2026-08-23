import CoreBluetooth
import Combine
import Foundation
import UIKit

@_silgen_name("uncompress")
private func zlibUncompress(
    _ destination: UnsafeMutablePointer<UInt8>?,
    _ destinationLength: UnsafeMutablePointer<UInt>,
    _ source: UnsafePointer<UInt8>?,
    _ sourceLength: UInt
) -> Int32

private let LIVE_ACTIVITY_PLAY_PAUSE_DEBOUNCE_MS: Int64 = 600
private let LIVE_ACTIVITY_TRACK_SKIP_DEBOUNCE_MS: Int64 = 800
private let LIVE_ACTIVITY_COMMAND_TTL_MS: Int64 = 1_500
private let LIVE_ACTIVITY_WRITE_STALL_MS: Int64 = 2_000
private let VOLUME_SEND_THROTTLE_MS: Int64 = 120
private let VOLUME_PENDING_TTL_MS: Int64 = 1_000
private let AUTO_RECONNECT_LAST_PERIPHERAL_KEY = "lastSonyPeripheralIdentifier"
private let CENTRAL_RESTORE_IDENTIFIER = "com.musicblecontroller.sony.central.v1"
private let DEBUG_SMOKE_LAUNCH_ARGUMENTS_FILE = "SmokeLaunchArguments.txt"
private let FAST_RETRIEVE_CONNECT_TIMEOUT_MS: Int64 = 1_800
private let DEFAULT_CONNECT_TIMEOUT_MS: Int64 = 8_000
private let CONNECTION_HEALTH_TICK_MS: Int64 = 3_000
private let CONNECTION_HEALTH_PLAYING_SUSPECT_MS: Int64 = 15_000
private let CONNECTION_HEALTH_PAUSED_SUSPECT_MS: Int64 = 30_000
private let CONNECTION_HEALTH_PROBE_TIMEOUT_MS: Int64 = 3_000
private let CONNECTION_HEALTH_HARD_RECONNECT_MIN_INTERVAL_MS: Int64 = 5_000
private let CONNECTION_SUBSCRIBE_NOTIFY_TIMEOUT_MS: Int64 = 5_000
private let CORE_BLUETOOTH_RESTORE_TIMEOUT_MS: Int64 = 5_000
private let CONNECTION_DISPLAY_CONNECTED_MIN_HOLD_MS: Int64 = 5_000
private let CONNECTION_DISPLAY_DISCONNECTED_CONFIRM_MS: Int64 = 1_000
private let COMMAND_WRITE_CALLBACK_TIMEOUT_MS: Int64 = 2_500
private let COMMAND_WRITE_RECENT_NOTIFY_GRACE_MS: Int64 = 3_000
private let COMMAND_WRITE_TIMEOUTS_BEFORE_RECONNECT = 2
private let FOREGROUND_LINK_VALIDATION_TIMEOUT_MS: Int64 = 5_000
private let FOREGROUND_INFLIGHT_SETTLE_TIMEOUT_MS: Int64 = 3_000
private let FULL_LYRICS_REQUEST_DEDUP_WINDOW_MS: Int64 = 1_500
private let FULL_LYRICS_REQUEST_START_TIMEOUT_MS: Int64 = 3_000
private let FULL_LYRICS_REQUEST_START_MAX_RETRIES = 2
private let LYRIC_SECONDARY_START_TIMEOUT_MS: Int64 = 3_000
private let LYRIC_SECONDARY_IDLE_TIMEOUT_MS: Int64 = 3_000
private let LYRIC_SECONDARY_TOTAL_TIMEOUT_MS: Int64 = 10_000
private let LYRIC_SECONDARY_MAX_RETRIES = 1
private let LYRIC_SECONDARY_FAILURE_COOLDOWN_MS: Int64 = 10_000
private let CLOCK_SYNC_BOOTSTRAP_SAMPLE_COUNT = 5
private let CLOCK_SYNC_REFRESH_SAMPLE_COUNT = 3
private let CLOCK_SYNC_SAMPLE_SPACING_MS: Int64 = 150
private let CLOCK_SYNC_REFRESH_INTERVAL_MS: Int64 = 120_000
private let CLOCK_SYNC_PROBE_TTL_MS: Int64 = 5_000

enum CommandWriteTimeoutAction: Equatable {
    case suspendUntilForeground
    case extendWithoutAdvancingQueue
    case reconnect
}

enum CommandWriteTimeoutPolicy {
    static func action(
        appIsActive: Bool,
        transportReady: Bool,
        timeoutCountAfterIncrement: Int,
        reconnectThreshold: Int
    ) -> CommandWriteTimeoutAction {
        guard appIsActive else { return .suspendUntilForeground }
        guard transportReady else { return .reconnect }
        return timeoutCountAfterIncrement < reconnectThreshold
            ? .extendWithoutAdvancingQueue
            : .reconnect
    }
}

enum LyricSecondaryLoadState: Equatable {
    case idle
    case loading
    case ready
    case unavailable
    case failed(reason: String)
}

enum LyricSecondaryRetryAction: Equatable {
    case retry
    case markUnavailable
    case markFailed
}

enum LyricSecondaryRetryPolicy {
    static func action(
        explicitlyUnavailable: Bool,
        retryCount: Int,
        maximumRetries: Int = LYRIC_SECONDARY_MAX_RETRIES
    ) -> LyricSecondaryRetryAction {
        if explicitlyUnavailable {
            return .markUnavailable
        }
        return retryCount < maximumRetries ? .retry : .markFailed
    }
}

enum SystemReconnectPolicy {
    static func shouldScheduleManualReconnect(
        autoReconnectEnabled: Bool,
        systemIsReconnecting: Bool
    ) -> Bool {
        autoReconnectEnabled && !systemIsReconnecting
    }
}

struct LyricWord: Identifiable, Equatable {
    let id: Int
    let startMs: Int64
    let durationMs: Int64
    let text: String
}

struct LyricLine: Identifiable, Equatable {
    let index: Int
    let timeMs: Int64
    let durationMs: Int64
    let text: String
    let translation: String?
    let romanization: String?
    let words: [LyricWord]

    var id: Int { index }
}

struct ResolvedLyric: Equatable {
    let trackId: String
    let lineIndex: Int
    let text: String
    let source: String
}

private enum LyricSecondaryMode: String {
    case translation
    case romanization
}

private enum AutoReconnectState: String {
    case idle
    case connected
    case reconnectScheduled
    case scanning
    case connecting
    case serviceDiscovering
    case subscribing
    case syncing
    case failed
}

private enum ConnectionHealthState: String {
    case healthy
    case suspect
    case stale
    case disconnected
}

private enum ConnectionDisplayState: String {
    case connected
    case reconnecting
    case disconnected
}

private struct LyricSecondaryLineParts {
    let partCount: Int
    var parts: [Int: String]
}

private struct LyricSecondaryTransfer {
    let trackId: String
    let transferId: String
    let mode: LyricSecondaryMode
    let itemCount: Int
    let requestToken: UUID
    let connectionAttemptId: UUID
    var lines: [Int: LyricSecondaryLineParts]
}

private struct LyricSecondaryRequest {
    let trackId: String
    let mode: LyricSecondaryMode
    let token: UUID
    let connectionAttemptId: UUID
    let retryCount: Int
}

private struct ClockSyncProbe {
    let sequence: UInt64
    let clientSendElapsedMs: Int64
    let clientSendDate: Date
}

final class BLETestManager: NSObject, ObservableObject, @unchecked Sendable {
    private static let logTimestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    @Published private(set) var appExperienceMode: AppExperienceMode = PreferencesStore.shared.appExperienceMode
    @Published private(set) var mode = "BLE Central / GATT Client"
    @Published private(set) var connectionStatus = "未连接" {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var logs: [String] = [] {
        didSet { syncDiagnosticsStore() }
    }
    @Published private(set) var title = "-" {
        didSet { syncPlaybackMetadataStore() }
    }
    @Published private(set) var artist = "-" {
        didSet { syncPlaybackMetadataStore() }
    }
    @Published private(set) var album = "-" {
        didSet { syncPlaybackMetadataStore() }
    }
    @Published private(set) var lyric = "" {
        didSet { syncLiveLyricsStore() }
    }
    @Published private(set) var fullLyrics: [LyricLine] = [] {
        didSet { syncFullLyricsStore() }
    }
    @Published private(set) var fullLyricsTrackId = "" {
        didSet { syncFullLyricsStore() }
    }
    @Published private(set) var isFullLyricsCurrent = false {
        didSet { syncFullLyricsStore() }
    }
    @Published private(set) var isFullLyricsReceiving = false {
        didSet { syncFullLyricsStore() }
    }
    @Published private(set) var translationLyricsState: LyricSecondaryLoadState = .idle {
        didSet { syncFullLyricsStore() }
    }
    @Published private(set) var romanizationLyricsState: LyricSecondaryLoadState = .idle {
        didSet { syncFullLyricsStore() }
    }
    @Published private(set) var lyricDiagnostic: LyricDiagnostic?
    @Published private(set) var lyricDiagnosticLoading = false {
        didSet { syncDiagnosticsStore() }
    }
    @Published private(set) var lyricDiagnosticLastUpdatedAt: Date?
    @Published private(set) var isPlaying = false {
        didSet { syncPlaybackTimelineStore() }
    }
    @Published private(set) var positionMs: Int64 = 0 {
        didSet { syncPlaybackTimelineStore() }
    }
    @Published private(set) var displayPositionMs: Int64 = 0 {
        didSet { syncPlaybackTimelineStore() }
    }
    @Published private(set) var durationMs: Int64 = 0 {
        didSet { syncPlaybackTimelineStore() }
    }
    @Published private(set) var seekPositionMs: Int64 = 0 {
        didSet { syncPlaybackTimelineStore() }
    }
    @Published private(set) var isSeeking = false {
        didSet { syncPlaybackTimelineStore() }
    }
    @Published private(set) var volumeCurrent = 0 {
        didSet { syncPlaybackVolumeStore() }
    }
    @Published private(set) var volumeMax = 0 {
        didSet { syncPlaybackVolumeStore() }
    }
    @Published private(set) var volumeSeekValue = 0 {
        didSet { syncPlaybackVolumeStore() }
    }
    @Published private(set) var isVolumeSeeking = false {
        didSet { syncPlaybackVolumeStore() }
    }
    @Published private(set) var albumArtImage: UIImage? {
        didSet { syncArtworkStore() }
    }
    @Published private(set) var connectedDeviceName = "-" {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var artworkDisplayQuality: ArtworkDisplayQuality = .placeholder {
        didSet { syncArtworkStore() }
    }
    @Published private(set) var artworkEnhancementStatus = ArtworkEnhancementDebugStatus() {
        didSet { syncArtworkStore() }
    }
    @Published private(set) var isShowingLastNowPlayingSnapshot = false {
        didSet { syncArtworkStore() }
    }
    @Published private(set) var mediaLoadingState = MediaLoadingState() {
        didSet {
            syncLiveLyricsStore()
            syncArtworkStore()
        }
    }
    @Published private(set) var artworkEnhancementTargetPixelSize = 780
    @Published private(set) var artworkEnhancementSharpness = 0.30
    @Published private(set) var remoteLogText = ""
    @Published private(set) var remoteLogCopyStatus = ""
    @Published private(set) var isRemoteLogTransferInProgress = false {
        didSet { syncDiagnosticsStore() }
    }
    @Published private(set) var mediaFieldDumpText = ""
    @Published private(set) var mediaFieldDumpCopyStatus = ""
    @Published private(set) var isMediaFieldDumpReceiving = false {
        didSet { syncDiagnosticsStore() }
    }
    @Published private(set) var mediaFieldDumpProgressText = ""
    @Published private(set) var automaticLyricSyncEnabled =
        PreferencesStore.shared.automaticLyricSyncEnabled
    @Published private(set) var karaokeOffsetMs: Int64 = Int64(PreferencesStore.shared.lyricOffsetMs)
    @Published private(set) var localLogActionStatus = ""
    @Published private(set) var liveActivityControlStatus = LiveActivityControlStatus()
    @Published private(set) var playbackHistorySessions: [PlaybackHistorySession] = []
    @Published private(set) var playbackStats: [String: PlaybackStatsSnapshot] = [:]
    @Published private(set) var isPlaybackHistorySyncing = false
    @Published private(set) var playbackHistoryStatus = ""
    @Published private(set) var autoReconnectEnabled = PreferencesStore.shared.autoReconnectEnabled
    @Published private(set) var autoReconnectState = AutoReconnectState.idle.rawValue {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var autoReconnectAttempt = 0 {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var autoReconnectNextRetryAt: Date?
    @Published private(set) var autoReconnectWorkItemExists = false
    @Published private(set) var autoReconnectScheduledAgeMs: Int64 = -1
    @Published private(set) var autoReconnectScheduledDelayMs: Int64 = 0
    @Published private(set) var autoReconnectIsConnecting = false
    @Published private(set) var autoReconnectIsScanning = false
    @Published private(set) var autoReconnectLastPeripheralId =
        UserDefaults.standard.string(forKey: AUTO_RECONNECT_LAST_PERIPHERAL_KEY) ?? "-"
    @Published private(set) var autoReconnectLastDisconnectError = "-"
    @Published private(set) var autoReconnectLastCostMs: Int64 = 0
    @Published private(set) var autoReconnectLastRetrieveCostMs: Int64 = 0
    @Published private(set) var autoReconnectLastScanCostMs: Int64 = 0
    @Published private(set) var autoReconnectLastConnectCostMs: Int64 = 0
    @Published private(set) var autoReconnectLastSubscribeCostMs: Int64 = 0
    @Published private(set) var manualReconnectCount = 0
    @Published private(set) var autoReconnectCount = 0
    @Published private(set) var connectionHealthState = ConnectionHealthState.disconnected.rawValue {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var connectionHealthLastNotifyAgeMs: Int64 = -1
    @Published private(set) var connectionHealthLastProbeAtText = "-"
    @Published private(set) var connectionHealthProbeInFlight = false
    @Published private(set) var connectionHealthLastHardReconnectReason = "-" {
        didSet { syncDiagnosticsStore() }
    }
    @Published private(set) var connectionHealthAttemptId = "-" {
        didSet { syncDiagnosticsStore() }
    }
    @Published private(set) var connectionHealthPeripheralState = "-"
    @Published private(set) var connectionHealthCharacteristicReady = false {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var connectionDisplayState = ConnectionDisplayState.disconnected.rawValue {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var connectionHealthSuspectCount = 0
    @Published private(set) var connectionHealthStaleCount = 0
    @Published private(set) var connectionHealthHardReconnectCount = 0
    @Published private(set) var connectionHealthMaxNotifyGapMs: Int64 = 0
    @Published private(set) var currentWordLineIndex = -1 {
        didSet { syncLiveLyricsStore() }
    }
    @Published private(set) var currentWordIndex = -1 {
        didSet { syncLiveLyricsStore() }
    }
    @Published private(set) var currentWordPushCount: Int64 = 0
    @Published private(set) var currentWordDropCount: Int64 = 0
    @Published private(set) var currentWordAverageUpdateIntervalMs: Int64 = 0
    @Published private(set) var currentWordLastLatencyMs: Int64 = 0
    @Published private(set) var lyricAutomaticCompensationMs: Int64 = 0
    @Published private(set) var lyricClockBestRoundTripMs: Int64 = 0
    @Published private(set) var lyricClockOffsetJitterMs: Int64 = 0
    @Published private(set) var lyricClockSampleCount = 0
    @Published private(set) var lyricClockSyncConfident = false

    let connectionStore = ConnectionStore()
    let playbackStore = PlaybackStore()
    let lyricsStore = LyricsStore()
    let artworkStore = ArtworkStore()
    let diagnosticsStore = DiagnosticsStore()

    // Transitional read-only slices keep older diagnostic views and tests
    // source-compatible while production UI observes the focused stores above.
    let connectionStateModel = ObservableStateSlice(BLEConnectionViewState())
    let playbackStateModel = ObservableStateSlice(BLEPlaybackViewState())
    let lyricsStateModel = ObservableStateSlice(BLELyricsViewState())
    let artworkStateModel = ObservableStateSlice(BLEArtworkViewState())

    @Published private(set) var bluetoothAvailability: BLEBluetoothAvailability = .unknown {
        didSet { syncConnectionStore() }
    }
    @Published private(set) var negotiatedV3Features: BLEProtocolV3Features = []
    @Published private(set) var serverSessionId = "-"
    @Published private(set) var lastServerEventSequence: UInt64 = 0
    @Published private(set) var lastCommandErrorSummary = "-"

    private let preferences = PreferencesStore.shared
    private let inboundPipeline = BLEInboundPipeline()
    private let protocolDecodeQueue = DispatchQueue(
        label: "com.musicblecontroller.protocol-decode",
        qos: .userInitiated
    )
    private let uiLogQueue = DispatchQueue(
        label: "com.musicblecontroller.ui-log",
        qos: .utility
    )
    private var pendingUILogLines: [String] = []
    private var isUILogFlushScheduled = false
    private var isUILogStreamingEnabled = false
    private lazy var centralManager = CBCentralManager(
        delegate: self,
        queue: nil,
        options: [
            CBCentralManagerOptionRestoreIdentifierKey: CENTRAL_RESTORE_IDENTIFIER,
            CBCentralManagerOptionShowPowerAlertKey: true
        ]
    )
    private lazy var peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

    private var sonyPeripheral: CBPeripheral?
    private var sonyCommandCharacteristic: CBCharacteristic?
    private var sonyStatusCharacteristic: CBCharacteristic?
    private var commandSeq: UInt64 = 0
    private var commandWriteInflight: [CommandWriteInfo] = []
    private var pendingCommandWrites: [PendingCommandWrite] = []
    private var commandWriteTimeoutWorkItem: DispatchWorkItem?
    private var consecutiveCommandWriteTimeouts = 0
    private var volumeWriteInFlightSeq: UInt64?
    private var lastVolumeSendAtMs: Int64 = 0
    private var lastVolumeRequestedValue: Int?
    private var latestPendingVolumeValue: Int?
    private var latestPendingVolumeReason = ""
    private var latestPendingVolumeIsFinal = false
    private var latestPendingVolumeCreatedAtMs: Int64 = 0
    private var pendingRemoteVolumeValue: Int?
    private var volumeThrottleWorkItem: DispatchWorkItem?
    private var liveActivityControlInFlightSeq: UInt64?
    private var liveActivityControlWriteStartedAtMs: Int64 = 0
    private var lastLiveActivityCommandAcceptedAtMs: [LiveActivityControlCommand: Int64] = [:]
    private var mainHeartbeatWorkItem: DispatchWorkItem?
    private var lastMainHeartbeatAtMs: Int64 = 0
    private var lastMainHeartbeatAppState = "active"
    private var appLifecycleState = "active"
    private var lifecycleGeneration: UInt64 = 0
    private var backgroundEnteredAt: Date?
    private var foregroundValidationPending = false
    private var foregroundValidationWaitingForInflight = false
    private var foregroundValidationCommandSeq: UInt64?
    private var foregroundValidationStartedAt: Date?
    private var foregroundValidationTimeoutWorkItem: DispatchWorkItem?
    private var foregroundInflightSettleWorkItem: DispatchWorkItem?
    private var firstConnectionReadyAtMs: Int64 = 0
    private var lastKaraokeOffsetLogAtMs: Int64 = 0
    private var currentTrackID = ""
    private var currentTrackGeneration: Int64 = 0
    private var currentLiveArtworkKey: String?
    private var currentLiveArtworkRevision = 0
    private var liveArtworkRevisionFence = LiveActivityArtworkRevisionFence()
    private var lastLiveActivityRequestAt = Date.distantPast
    private var pendingLiveActivityUpdateWorkItem: DispatchWorkItem?
    private var lastLiveActivityRequestTrackID = ""
    private var lastLiveActivityLyricTrackID = ""
    private var lastLiveActivityLyricLineIndex = Int.min
    private var lastLiveActivityLyricText = ""
    private var lastLiveActivityCurrentWordSkipLogAtMs: Int64 = 0
    private var requestedFullLyricsTrackIDs: Set<String> = []
    private var lastSnapshotQueuedAtMs: Int64 = 0
    private var completedFullLyricsTrackIDs: Set<String> = []
    private var fullLyricsUnavailableTrackIDs: Set<String> = []
    private var fullLyricsDelayedRetryTrackIDs: Set<String> = []
    private var fullLyricsOptionalRefreshTrackIDs: Set<String> = []
    private var requestedLyricSecondaryKeys: Set<String> = []
    private var completedLyricSecondaryKeys: Set<String> = []
    private var ignoredLyricSecondaryPlaceholderKeys: Set<String> = []
    private var pendingLyricSecondaryModes: [LyricSecondaryMode] = []
    private var lyricSecondaryTransfer: LyricSecondaryTransfer?
    private var activeLyricSecondaryRequest: LyricSecondaryRequest?
    private var lyricSecondaryRetryCounts: [String: Int] = [:]
    private var lyricSecondaryFailureCooldownUntilMs: [String: Int64] = [:]
    private var lyricSecondaryStartTimeoutWorkItem: DispatchWorkItem?
    private var lyricSecondaryIdleTimeoutWorkItem: DispatchWorkItem?
    private var lyricSecondaryTotalTimeoutWorkItem: DispatchWorkItem?
    private var lyricSecondaryDeferredRequestWorkItem: DispatchWorkItem?
    private var lastAutomaticLyricDiagnosticRequestAt: [String: Date] = [:]
    private var fullLyricsReceivingTrackID = ""
    private var fullLyricsExpectedCount = 0
    private var fullLyricsChunks: [Int: LyricLine] = [:]
    private var fullLyricsBinaryTransfer: FullLyricsBinaryTransfer?
    private var fullLyricsBinaryDecodingTransferID: String?
    private var fullLyricsBinaryRetryCounts: [String: Int] = [:]
    private var fullLyricsBinaryFallbackTrackIDs: Set<String> = []
    private var lyricWindowTransfer: LyricWindowTransfer?
    private var requestedLyricWindowTrackIDs: Set<String> = []
    private var fullLyricsTimeoutWorkItem: DispatchWorkItem?
    private var fullLyricsRequestStartTimeouts: [String: DispatchWorkItem] = [:]
    private var fullLyricsRequestStartRetryCounts: [String: Int] = [:]
    private var lastFullLyricsPartialPublishAtMs: Int64 = 0
    private var fullLyricsRequestCreatedAtMs: [String: Int64] = [:]
    private var lyricTraceTrackInfoAtMs: [String: Int64] = [:]
    private var lyricTraceFullLyricsRequestAtMs: [String: Int64] = [:]
    private var lyricTraceFullLyricsStartAtMs: [String: Int64] = [:]
    private var lyricTraceFirstPlaybackLyricAtMs: [String: Int64] = [:]
    #if DEBUG
    private var trackMatrixV31RunID = ""
    private var trackMatrixV31Active = false
    #endif
    private var remoteLogExpectedChunks = 0
    private var remoteLogExpectedLines = 0
    private var remoteLogChunks: [Int: Data] = [:]
    private var mediaFieldDumpExpectedSize = 0
    private var mediaFieldDumpExpectedChunks = 0
    private var mediaFieldDumpChunks: [Int: Data] = [:]
    private var historyPayloads: [String: HistoryPayloadAssembly] = [:]
    private var pendingHistoryRequests: [String: HistoryRequestKind] = [:]
    private var pendingPlaybackStatsRanges: [String] = []
    private var refreshStatsAfterHistorySync = false
    private var lastSyncedHistorySessionId: Int64 = 0
    private var isLoadingMoreHistory = false
    private var trackInfoExpectedSize = 0
    private var trackInfoExpectedChunks = 0
    private var trackInfoChunks: [Int: Data] = [:]
    private var trackInfoTransferToken = UUID()
    private var basePlaybackPositionMs: Int64 = 0
    private var playbackAnchorElapsedMs = Int64(
        (ProcessInfo.processInfo.systemUptime * 1_000).rounded()
    )
    private var progressTimer: Timer?
    private var lastCurrentWordReceivedAtMs: Int64 = 0
    private var currentWordIntervalTotalMs: Int64 = 0
    private var currentWordIntervalCount: Int64 = 0
    private var currentWordFence = CurrentWordOrderingFence()
    private var lastCurrentWordLoggedLineIndex = -1
    private var lastCurrentWordDiagnosticLogAtMs: Int64 = 0
    private let selfHealingEngine = SelfHealingEngine.shared
    private lazy var albumArtReceiver: AlbumArtReceiver = {
        let receiver = AlbumArtReceiver(delegate: self)
        receiver.onStateChanged = { [weak self] receiver in
            self?.syncAlbumArtReceiverState(receiver)
        }
        return receiver
    }()

    private var commandCharacteristic: CBMutableCharacteristic?
    private var statusCharacteristic: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []
    private var shouldStartAdvertising = false
    private var shouldScanWhenPoweredOn = false
    private var scanTimeoutWorkItem: DispatchWorkItem?
    private var reconnectWorkItem: DispatchWorkItem?
    private var reconnectStuckCheckWorkItem: DispatchWorkItem?
    private var coreBluetoothRestoreTimeoutWorkItem: DispatchWorkItem?
    private var reconnectScheduledAt: Date?
    private var reconnectScheduledDelayMs: Int64 = 0
    private var connectTimeoutWorkItem: DispatchWorkItem?
    private var healthCheckWorkItem: DispatchWorkItem?
    private var healthProbeTimeoutWorkItem: DispatchWorkItem?
    private var subscribeNotifyTimeoutWorkItem: DispatchWorkItem?
    private var reconnectStartedAtMs: Int64 = 0
    private var scanStartedAtMs: Int64 = 0
    private var connectStartedAtMs: Int64 = 0
    private var subscribeStartedAtMs: Int64 = 0
    private var isConnectingToSony = false
    private var currentScanIsAutoReconnect = false
    private var currentConnectIsAutoReconnect = false
    private var currentConnectIsRetrievedPeripheral = false
    private var coreBluetoothRestoreInProgress = false
    private var systemAutoReconnectInProgress = false
    private var systemAutoReconnectStartedAt: Date?
    private var connectionAttemptId = UUID()
    private var lastStatusNotifyAt: Date?
    private var lastPlaybackStateAt: Date?
    private var lastSuccessfulWriteAt: Date?
    private var lastNotifySubscribedAt: Date?
    private var reconnectStateSyncWindowUntilMs: Int64 = 0
    private var reconnectStateSyncPlaybackLogged = false
    private var reconnectStateSyncRequestedFullLyricsTrackIDs: Set<String> = []
    private var connectionReadyAt: Date?
    private var lastHealthProbeAt: Date?
    private var healthProbeCommandSeq: UInt64?
    private var healthProbeStartedAt: Date?
    private var healthPingClockProbeStartedAt: Date?
    private var sonyClockOffsetMs: Int64?
    private var serverSupportsClockSyncV1 = false
    private var clockSynchronizer = MonotonicClockSynchronizer()
    private var sonyUnixMinusElapsedMs: Int64?
    private var clockSyncProbes: [String: ClockSyncProbe] = [:]
    private var clockSyncBootstrapWorkItems: [DispatchWorkItem] = []
    private var clockSyncRefreshWorkItem: DispatchWorkItem?
    private var remotePlaybackSpeed = 1.0
    private var lastStaleRemoteAnchorLogAtMs: Int64 = 0
    private var healthProbeFailureCount = 0
    private var serverProtocolVersion = 1
    private var serverSupportsAlbumArtBinary = false
    private var serverSupportsFullLyricsZlib = false
    private var serverSupportsLyricWindow = false
    private var serverSupportsPing = false
    private var serverSupportsTransferRetry = false
    private var lastMediaLoadStateKeyByResource: [BLEMediaResource: String] = [:]
    private var eventSequenceDiagnostics = BLEEventSequenceDiagnostics()
    private var lastHardReconnectAt: Date?
    private var connectionDisplayStateChangedAt = Date()
    private var connectionDisplayWorkItem: DispatchWorkItem?

    private struct CommandWriteInfo {
        let seq: UInt64
        let cmd: String
        let writeCalledAtMs: Int64
    }

    private struct PendingCommandWrite {
        let seq: UInt64
        let cmd: String
        let data: Data
        let payloadText: String
        let enqueuedAtMs: Int64
        let isControl: Bool
        let volumeValue: Int?
        let volumeReason: String?
    }

    private struct TrackInfoTransferPayload: Decodable, Sendable {
        let type: String
        let title: String?
        let artist: String?
        let album: String?
        let trackId: String?
        let generation: Int64?
    }

    private enum HistoryRequestKind {
        case since
        case page
        case stats(String)
    }

    private struct HistoryPayloadAssembly {
        let responseType: String
        let expectedSize: Int
        let expectedChunks: Int
        var chunks: [Int: Data] = [:]
    }

    private struct FullLyricsBinaryTransfer {
        let trackId: String
        let transferId: String
        let generation: Int64
        let expectedSize: Int
        let uncompressedSize: Int
        let expectedChunks: Int
        let expectedLineCount: Int
        let expectedCRC32: UInt32
        var chunks: [Int: Data] = [:]
    }

    private enum FullLyricsBinaryDecodeResult {
        case success(lines: [LyricLine], compressedSize: Int)
        case failure(reason: String)
    }

    private struct LyricWindowTransfer {
        let trackId: String
        let transferId: String
        let generation: Int64
        let expectedCount: Int
        var chunks: [Int: LyricLine] = [:]
    }

    override init() {
        super.init()
        syncAllStores()
        syncPreferencesStateFromStore()
        logAppExperienceModeLoaded()
        log("[BLE-iOS] app log store ready")
        LiveActivityCommandBridge.shared.register(self, logger: { [weak self] message in
            self?.log(message)
        })
        refreshLiveActivityControlStatus()
        updateAppLifecycleState(UIApplication.shared.applicationState, emitLog: false)
        registerAppLifecycleDiagnostics()
        _ = centralManager
        #if DEBUG
        startMainHeartbeatDiagnostics()
        #endif
        loadCachedPlaybackHistory()
        restoreLastNowPlayingSnapshot()
        syncAlbumArtReceiverState(albumArtReceiver)
        runDebugSmokeTestIfNeeded()
    }

    private func syncAllStores() {
        syncConnectionStore()
        syncPlaybackMetadataStore()
        syncPlaybackTimelineStore()
        syncPlaybackVolumeStore()
        syncLiveLyricsStore()
        syncFullLyricsStore()
        syncArtworkStore()
        syncDiagnosticsStore()
    }

    private func syncConnectionStore() {
        let state = BLEConnectionViewState(
            status: connectionStatus,
            displayState: connectionDisplayState,
            healthState: connectionHealthState,
            deviceName: connectedDeviceName,
            characteristicReady: connectionHealthCharacteristicReady,
            autoReconnectState: autoReconnectState
        )
        let deviceName = connectedDeviceName == "-" ? nil : connectedDeviceName
        let presentation: BLEConnectionPresentationState
        if bluetoothAvailability != .available,
           bluetoothAvailability != .unknown {
            presentation = .unavailable(bluetoothAvailability)
        } else {
            switch connectionDisplayState {
            case ConnectionDisplayState.connected.rawValue:
                presentation = .connected(
                    deviceName: deviceName ?? "Sony",
                    health: connectionHealthState
                )
            case ConnectionDisplayState.reconnecting.rawValue:
                if connectionStatus.contains("搜索") || connectionStatus.contains("扫描") {
                    presentation = .scanning
                } else if connectionStatus.contains("连接") && autoReconnectAttempt == 0 {
                    presentation = .connecting(deviceName: deviceName)
                } else {
                    presentation = .reconnecting(
                        deviceName: deviceName,
                        attempt: autoReconnectAttempt
                    )
                }
            default:
                if connectionStatus.contains("未找到") {
                    presentation = .failed(.deviceNotFound)
                } else {
                    presentation = .disconnected(lastDeviceName: deviceName)
                }
            }
        }
        connectionStore.update(state: state, presentation: presentation)
        connectionStateModel.update(state)
    }

    private func syncPlaybackMetadataStore() {
        playbackStore.updateMetadata(
            BLEPlaybackMetadataState(title: title, artist: artist, album: album)
        )
        syncLegacyPlaybackSlice()
    }

    private func syncPlaybackTimelineStore() {
        playbackStore.updateTimeline(
            BLEPlaybackTimelineState(
                isPlaying: isPlaying,
                positionMs: positionMs,
                displayPositionMs: displayPositionMs,
                durationMs: durationMs,
                seekPositionMs: seekPositionMs,
                isSeeking: isSeeking
            )
        )
        syncLegacyPlaybackSlice()
    }

    private func syncPlaybackVolumeStore() {
        playbackStore.updateVolume(
            BLEVolumeViewState(
                current: volumeCurrent,
                maximum: volumeMax,
                seekValue: volumeSeekValue,
                isSeeking: isVolumeSeeking
            )
        )
        syncLegacyPlaybackSlice()
    }

    private func syncLiveLyricsStore() {
        lyricsStore.updateLive(
            BLELiveLyricState(
                text: lyric,
                currentWordLineIndex: currentWordLineIndex,
                currentWordIndex: currentWordIndex,
                loadingStage: mediaLoadingState.lyric
            )
        )
        syncLegacyLyricsSlice()
    }

    private func syncFullLyricsStore() {
        lyricsStore.updateDocument(
            BLEFullLyricsViewState(
                lines: fullLyrics,
                trackId: fullLyricsTrackId,
                isCurrent: isFullLyricsCurrent,
                isReceiving: isFullLyricsReceiving,
                translationState: translationLyricsState,
                romanizationState: romanizationLyricsState
            )
        )
        syncLegacyLyricsSlice()
    }

    private func syncArtworkStore() {
        artworkStore.update(
            BLEArtworkViewState(
                image: albumArtImage,
                displayQuality: artworkDisplayQuality,
                enhancementStatus: artworkEnhancementStatus,
                isRestoredSnapshot: isShowingLastNowPlayingSnapshot,
                loadingStage: mediaLoadingState.artwork
            )
        )
        artworkStateModel.update(artworkStore.state)
    }

    private func syncLegacyPlaybackSlice() {
        playbackStateModel.update(
            BLEPlaybackViewState(
                title: playbackStore.metadata.title,
                artist: playbackStore.metadata.artist,
                album: playbackStore.metadata.album,
                isPlaying: playbackStore.timeline.isPlaying,
                positionMs: playbackStore.timeline.positionMs,
                displayPositionMs: playbackStore.timeline.displayPositionMs,
                durationMs: playbackStore.timeline.durationMs,
                seekPositionMs: playbackStore.timeline.seekPositionMs,
                isSeeking: playbackStore.timeline.isSeeking,
                volumeCurrent: playbackStore.volume.current,
                volumeMax: playbackStore.volume.maximum,
                volumeSeekValue: playbackStore.volume.seekValue,
                isVolumeSeeking: playbackStore.volume.isSeeking
            )
        )
    }

    private func syncLegacyLyricsSlice() {
        lyricsStateModel.update(
            BLELyricsViewState(
                lyric: lyricsStore.live.text,
                fullLyrics: lyricsStore.document.lines,
                fullLyricsTrackId: lyricsStore.document.trackId,
                isCurrent: lyricsStore.document.isCurrent,
                isReceiving: lyricsStore.document.isReceiving,
                currentWordLineIndex: lyricsStore.live.currentWordLineIndex,
                currentWordIndex: lyricsStore.live.currentWordIndex,
                loadingStage: lyricsStore.live.loadingStage,
                translationState: lyricsStore.document.translationState,
                romanizationState: lyricsStore.document.romanizationState
            )
        )
    }

    private func syncDiagnosticsStore() {
        diagnosticsStore.update(
            BLEDiagnosticsViewState(
                logCount: logs.count,
                connectionAttemptId: connectionHealthAttemptId,
                lastHardReconnectReason: connectionHealthLastHardReconnectReason,
                remoteLogInProgress: isRemoteLogTransferInProgress,
                mediaDumpInProgress: isMediaFieldDumpReceiving,
                lyricDiagnosticLoading: lyricDiagnosticLoading
            )
        )
    }

    func toggleAppExperienceMode() {
        setAppExperienceMode(appExperienceMode.toggled)
    }

    func setAppExperienceMode(_ mode: AppExperienceMode) {
        guard preferences.appExperienceMode != mode else { return }
        preferences.appExperienceMode = mode
        appExperienceMode = mode
        log("[AppMode] changed mode=\(mode.rawValue)")
        if mode == .daily {
            log("[AppMode] daily mode skip debug stats")
        } else {
            log("[AppMode] debug mode debug tools available")
        }
    }

    private func syncPreferencesStateFromStore() {
        appExperienceMode = preferences.appExperienceMode
        autoReconnectEnabled = preferences.autoReconnectEnabled
        automaticLyricSyncEnabled = preferences.automaticLyricSyncEnabled
        karaokeOffsetMs = Int64(preferences.lyricOffsetMs)
    }

    private func syncAlbumArtReceiverState(_ receiver: AlbumArtReceiver) {
        if albumArtImage !== receiver.albumArtImage {
            albumArtImage = receiver.albumArtImage
        }
        if artworkDisplayQuality != receiver.artworkDisplayQuality {
            artworkDisplayQuality = receiver.artworkDisplayQuality
        }
        if artworkEnhancementStatus != receiver.artworkEnhancementStatus {
            artworkEnhancementStatus = receiver.artworkEnhancementStatus
        }
        if artworkEnhancementTargetPixelSize != receiver.artworkEnhancementTargetPixelSize {
            artworkEnhancementTargetPixelSize = receiver.artworkEnhancementTargetPixelSize
        }
        if artworkEnhancementSharpness != receiver.artworkEnhancementSharpness {
            artworkEnhancementSharpness = receiver.artworkEnhancementSharpness
        }
        let transfer = receiver.transferSnapshot()
        let artworkStage: ArtworkLoadingStage
        if transfer.state == "failed" || transfer.state == "timeout" {
            artworkStage = .failed(reason: transfer.lastFailureReason)
        } else if transfer.state == "receiving" || transfer.state == "decoding" {
            if transfer.quality.lowercased() == "hq" {
                artworkStage = .hq(
                    received: transfer.receivedChunks,
                    expected: transfer.totalChunks
                )
            } else {
                artworkStage = .preview(
                    received: transfer.receivedChunks,
                    expected: transfer.totalChunks
                )
            }
        } else if receiver.artworkDisplayQuality >= .hq {
            artworkStage = .hqReady
        } else if receiver.albumArtImage != nil {
            artworkStage = .previewReady
        } else if !receiver.currentAlbumArtID.isEmpty {
            artworkStage = .preview(received: 0, expected: 0)
        } else {
            artworkStage = .idle
        }
        if mediaLoadingState.artwork != artworkStage {
            mediaLoadingState.artwork = artworkStage
        }
    }

    private func logAppExperienceModeLoaded() {
        let mode = preferences.appExperienceMode
        log("[AppMode] loaded mode=\(mode.rawValue)")
        if mode == .daily {
            log("[AppMode] daily mode skip debug stats")
        } else {
            log("[AppMode] debug mode debug tools available")
        }
    }

    private func runDebugSmokeTestIfNeeded() {
        #if DEBUG
        runFullLyricsProtocolSelfTest()
        let defaults = UserDefaults.standard
        var arguments = ProcessInfo.processInfo.arguments
        arguments.append(contentsOf: consumeDebugSmokeLaunchArguments())
        let markerKey = "smokeTestPreferencesWritten"
        if arguments.contains("--smoke-force-v2") {
            preferences.forceProtocolV2 = true
            log("[SmokeTest] force protocol V2")
        } else if arguments.contains("--smoke-force-v3") {
            preferences.forceProtocolV2 = false
            log("[SmokeTest] force protocol V3")
        }
        if arguments.contains("--smoke-test-preferences") {
            let expectedArtworkSize = ArtworkDisplaySizeOption.small.rawValue
            setAppExperienceMode(.debug)
            preferences.artworkDisplaySize = .small
            setKaraokeOffsetMs(300)
            setAutoReconnectEnabled(true)
            defaults.set(true, forKey: markerKey)
            AppLogStore.shared.clear { [weak self] in
                guard let self else { return }
                self.log("[SmokeTest] preferences written")
                let verified = self.preferences.appExperienceMode == .debug &&
                    self.preferences.artworkDisplaySize.rawValue == expectedArtworkSize &&
                    self.karaokeOffsetMs == 300 &&
                    self.preferences.autoReconnectEnabled
                if verified {
                    self.log(
                        "[SmokeTest] preferences verified mode=debug " +
                            "artworkDisplaySize=\(expectedArtworkSize) " +
                            "lyricOffsetMs=300 autoReconnect=true"
                    )
                } else {
                    self.log(
                        "[SmokeTest] preferences verification failed " +
                            "mode=\(self.preferences.appExperienceMode.rawValue) " +
                            "artworkDisplaySize=\(self.preferences.artworkDisplaySize.rawValue) " +
                            "lyricOffsetMs=\(self.karaokeOffsetMs) autoReconnect=\(self.preferences.autoReconnectEnabled)"
                    )
                }
            }
        } else if defaults.bool(forKey: markerKey),
                  preferences.appExperienceMode == .debug,
                  preferences.artworkDisplaySize == .small,
                  karaokeOffsetMs == 300,
                  preferences.autoReconnectEnabled {
            log("[SmokeTest] preferences persisted")
        }
        if arguments.contains("--smoke-control-e2e") {
            scheduleControlE2ESmokeTest()
        }
        if arguments.contains("--smoke-source-capability") {
            scheduleSourceCapabilitySmokeTest()
        }
        if arguments.contains("--smoke-track-matrix-v31") {
            scheduleTrackMatrixV31SmokeTest()
        }
        #endif
    }

    #if DEBUG
    private func consumeDebugSmokeLaunchArguments() -> [String] {
        guard let documentsURL = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else {
            return []
        }
        let markerURL = documentsURL.appendingPathComponent(
            DEBUG_SMOKE_LAUNCH_ARGUMENTS_FILE,
            isDirectory: false
        )
        guard let text = try? String(contentsOf: markerURL, encoding: .utf8) else {
            return []
        }
        try? FileManager.default.removeItem(at: markerURL)
        let arguments = text
            .split(whereSeparator: \.isNewline)
            .map(String.init)
            .filter { $0.hasPrefix("--smoke-") }
        if !arguments.isEmpty {
            log("[SmokeTest] restored launch arguments=\(arguments.joined(separator: ","))")
        }
        return arguments
    }

    private func runFullLyricsProtocolSelfTest() {
        let raw = Data(
            "{\"trackId\":\"fixed\",\"generation\":7,\"lines\":[{\"index\":0,\"timeMs\":0,\"durationMs\":1000,\"text\":\"hello\"}]}"
                .utf8
        )
        let compressed = Self.dataFromHex(
            "7801ab562a294a4ccef64c51b2524acbac484d51d2514a4fcd4b2d4a2cc9cccf53b232d751cac9cc4b2d56b28aae56cacc4b49ad50b232d0512ac9cc4df5050a029929a510b520aea181014832b5a204685c466a4e4ebe526d6c2d0075732076"
        )
        let pieces = stride(from: 0, to: compressed.count, by: 17).map { offset in
            compressed.subdata(in: offset..<min(offset + 17, compressed.count))
        }
        var reordered: [Int: Data] = [:]
        Array(pieces.indices.reversed()).forEach { reordered[$0] = pieces[$0] }
        if let first = pieces.indices.first {
            reordered[first] = pieces[first]
        }
        let assembled = pieces.indices.reduce(into: Data()) { value, index in
            value.append(reordered[index] ?? Data())
        }
        let missingDetected = pieces.indices.filter { reordered[$0] == nil }.isEmpty
        let decoded = Self.zlibDecompress(assembled, expectedSize: raw.count)
        let legacyLine = Self.decodeLyricLine([
            "index": 0,
            "timeMs": 0,
            "durationMs": 1_000,
            "text": "hello"
        ])
        let magicDispatchValid = Data([0xA1]).first == 0xA1 && Data([0xA2]).first == 0xA2
        let passed = decoded == raw &&
            Self.crc32(compressed) == 0x74f3_85b3 &&
            assembled == compressed &&
            missingDetected &&
            legacyLine?.text == "hello" &&
            magicDispatchValid
        log("[ProtocolSelfTest] fullLyricsV2 \(passed ? "PASS" : "FAIL")")
    }

    private static func dataFromHex(_ text: String) -> Data {
        var data = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            if let byte = UInt8(text[index..<next], radix: 16) {
                data.append(byte)
            }
            index = next
        }
        return data
    }

    private func scheduleTrackMatrixV31SmokeTest() {
        let runID = "\(currentTimeMs())"
        trackMatrixV31RunID = runID
        trackMatrixV31Active = true
        AppLogStore.shared.clear()
        trackMatrixLog("scheduled")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.runTrackMatrixV31WhenReady(attempt: 0, runID: runID)
        }
    }

    private func trackMatrixLog(_ message: String) {
        // Keep the action immediately after the tag: the shell report parser
        // intentionally keys on stable markers such as `sampleStart`.
        log("[TrackMatrixV31] \(message) runId=\(trackMatrixV31RunID)")
    }

    private func isActiveTrackMatrixRun(_ runID: String) -> Bool {
        trackMatrixV31Active && runID == trackMatrixV31RunID
    }

    private func runTrackMatrixV31WhenReady(attempt: Int, runID: String) {
        guard isActiveTrackMatrixRun(runID) else {
            log("[TrackMatrixV31] runId=\(runID) ignored reason=stale_run activeRunId=\(trackMatrixV31RunID)")
            return
        }
        let ready = sonyPeripheral?.state == .connected &&
            sonyCommandCharacteristic != nil &&
            sonyStatusCharacteristic != nil &&
            isConnectionHealthyOrSuspect &&
            !currentTrackID.isEmpty &&
            title != "-"
        guard ready else {
            if attempt >= 40 {
                trackMatrixLog(
                    "abort reason=not_ready " +
                        "attempt=\(attempt) connected=\(sonyPeripheral?.state == .connected) " +
                        "commandReady=\(sonyCommandCharacteristic != nil) " +
                        "statusReady=\(sonyStatusCharacteristic != nil) " +
                        "health=\(connectionHealthState)"
                )
                trackMatrixV31Active = false
                return
            }
            trackMatrixLog(
                "waiting connection attempt=\(attempt) " +
                    "connected=\(sonyPeripheral?.state == .connected) " +
                    "commandReady=\(sonyCommandCharacteristic != nil) " +
                    "statusReady=\(sonyStatusCharacteristic != nil) " +
                    "health=\(connectionHealthState)"
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.runTrackMatrixV31WhenReady(attempt: attempt + 1, runID: runID)
            }
            return
        }
        trackMatrixLog("start totalTracks=10 dwellMs=10000")
        runTrackMatrixV31Sample(index: 1, previousTrackID: "", runID: runID)
    }

    private func runTrackMatrixV31Sample(index: Int, previousTrackID: String, runID: String) {
        guard isActiveTrackMatrixRun(runID) else {
            log("[TrackMatrixV31] runId=\(runID) ignored reason=stale_sample activeRunId=\(trackMatrixV31RunID)")
            return
        }
        guard index <= 10 else {
            trackMatrixLog("end timeMs=\(currentTimeMs())")
            trackMatrixV31Active = false
            return
        }
        let trackID = currentTrackID
        trackMatrixLog(
            "sampleStart index=\(index) timeMs=\(currentTimeMs()) " +
                "trackId=\(trackID) previousTrackId=\(previousTrackID) " +
                "title=\(title.prefix(60)) artist=\(artist.prefix(60)) " +
                "position=\(displayPositionMs) duration=\(durationMs)"
        )
        sendGetPlaybackState()
        let lyricRequestAt = currentTimeMs()
        sendGetFullLyrics(force: true)
        trackMatrixLog(
            "requestFullLyrics index=\(index) " +
                "trackId=\(trackID) timeMs=\(lyricRequestAt)"
        )
        let artRequestAt = currentTimeMs()
        let artRequested = albumArtReceiver.requestCurrentPreviewAlbumArt()
        trackMatrixLog(
            "requestAlbumArt index=\(index) " +
                "trackId=\(trackID) timeMs=\(artRequestAt) quality=preview result=\(artRequested)"
        )
        guard index < 10 else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 10.0) { [weak self] in
                guard let self, self.isActiveTrackMatrixRun(runID) else { return }
                self.trackMatrixLog("end timeMs=\(self.currentTimeMs())")
                self.trackMatrixV31Active = false
            }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10.0) { [weak self] in
            guard let self else { return }
            guard self.isActiveTrackMatrixRun(runID) else {
                self.log("[TrackMatrixV31] runId=\(runID) ignored reason=stale_next activeRunId=\(self.trackMatrixV31RunID)")
                return
            }
            let beforeNext = self.currentTrackID
            self.trackMatrixLog(
                "next index=\(index) " +
                    "fromTrackId=\(beforeNext) timeMs=\(self.currentTimeMs())"
            )
            self.sendNext()
            self.waitTrackMatrixV31TrackChange(
                nextIndex: index + 1,
                previousTrackID: beforeNext,
                attempt: 0,
                retriedNext: false,
                forceRetryCount: 0,
                runID: runID
            )
        }
    }

    private func waitTrackMatrixV31TrackChange(
        nextIndex: Int,
        previousTrackID: String,
        attempt: Int,
        retriedNext: Bool,
        forceRetryCount: Int,
        runID: String
    ) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self else { return }
            guard self.isActiveTrackMatrixRun(runID) else {
                self.log("[TrackMatrixV31] runId=\(runID) ignored reason=stale_wait activeRunId=\(self.trackMatrixV31RunID)")
                return
            }
            let current = self.currentTrackID
            if !current.isEmpty, !previousTrackID.isEmpty, current != previousTrackID {
                self.trackMatrixLog(
                    "trackChanged nextIndex=\(nextIndex) " +
                        "previousTrackId=\(previousTrackID) currentTrackId=\(current) " +
                        "attempt=\(attempt) timeMs=\(self.currentTimeMs())"
                )
                self.runTrackMatrixV31Sample(index: nextIndex, previousTrackID: previousTrackID, runID: runID)
                return
            }
            if attempt == 4, !retriedNext {
                self.trackMatrixLog(
                    "retryNext nextIndex=\(nextIndex) " +
                        "previousTrackId=\(previousTrackID) currentTrackId=\(current)"
                )
                self.sendNext()
                self.waitTrackMatrixV31TrackChange(
                    nextIndex: nextIndex,
                    previousTrackID: previousTrackID,
                    attempt: attempt + 1,
                    retriedNext: true,
                    forceRetryCount: forceRetryCount,
                    runID: runID
                )
                return
            }
            if attempt >= 14 {
                self.trackMatrixLog(
                    "track_not_changed nextIndex=\(nextIndex) " +
                        "previousTrackId=\(previousTrackID) currentTrackId=\(current) " +
                        "forceRetryCount=\(forceRetryCount)"
                )
                if forceRetryCount >= 3 {
                    self.trackMatrixLog(
                        "abort reason=track_not_changed " +
                            "nextIndex=\(nextIndex) previousTrackId=\(previousTrackID)"
                    )
                    self.trackMatrixV31Active = false
                    return
                }
                self.sendNext()
                self.waitTrackMatrixV31TrackChange(
                    nextIndex: nextIndex,
                    previousTrackID: previousTrackID,
                    attempt: 0,
                    retriedNext: false,
                    forceRetryCount: forceRetryCount + 1,
                    runID: runID
                )
                return
            }
            self.waitTrackMatrixV31TrackChange(
                nextIndex: nextIndex,
                previousTrackID: previousTrackID,
                attempt: attempt + 1,
                retriedNext: retriedNext,
                forceRetryCount: forceRetryCount,
                runID: runID
            )
        }
    }

    private func scheduleSourceCapabilitySmokeTest() {
        log("[SourceCapabilitySmoke] scheduled")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.runSourceCapabilityWhenReady(attempt: 0)
        }
    }

    private func runSourceCapabilityWhenReady(attempt: Int) {
        let ready = sonyPeripheral?.state == .connected &&
            sonyCommandCharacteristic != nil &&
            sonyStatusCharacteristic != nil &&
            isConnectionHealthyOrSuspect
        guard ready else {
            if attempt >= 40 {
                log(
                    "[SourceCapabilitySmoke] abort reason=not_ready " +
                        "attempt=\(attempt) connected=\(sonyPeripheral?.state == .connected) " +
                        "commandReady=\(sonyCommandCharacteristic != nil) " +
                        "statusReady=\(sonyStatusCharacteristic != nil) " +
                        "health=\(connectionHealthState)"
                )
                return
            }
            log(
                "[SourceCapabilitySmoke] waiting connection attempt=\(attempt) " +
                    "connected=\(sonyPeripheral?.state == .connected) " +
                    "commandReady=\(sonyCommandCharacteristic != nil) " +
                    "statusReady=\(sonyStatusCharacteristic != nil) " +
                    "health=\(connectionHealthState)"
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.runSourceCapabilityWhenReady(attempt: attempt + 1)
            }
            return
        }
        runSourceCapabilitySequence()
    }

    private func runSourceCapabilitySequence() {
        log("[SourceCapabilitySmoke] start")
        for index in 0..<5 {
            // The observable display state intentionally settles after the raw
            // CoreBluetooth state. Give it a short grace period so the smoke
            // request exercises the wire instead of being locally rejected.
            let delay = 0.8 + TimeInterval(index * 30)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self else { return }
                self.log(
                    "[SourceCapabilitySmoke] sample=\(index + 1) " +
                        "trackId=\(self.currentTrackID) title=\(self.title.prefix(40))"
                )
                self.sendGetPlaybackState()
                self.sendGetFullLyrics(force: true)
                let requested = self.requestCurrentHqAlbumArt(forceRefresh: true)
                self.log(
                    "[SourceCapabilitySmoke] albumArt force request result=\(requested)"
                )
            }
        }
    }

    private func scheduleControlE2ESmokeTest() {
        log("[ControlE2E] scheduled")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.runControlE2EWhenReady(attempt: 0)
        }
    }

    private func runControlE2EWhenReady(attempt: Int) {
        let ready = sonyPeripheral?.state == .connected &&
            sonyCommandCharacteristic != nil &&
            sonyStatusCharacteristic != nil &&
            isConnectionHealthyOrSuspect
        guard ready else {
            if attempt >= 30 {
                log(
                    "[ControlE2E] abort reason=not_ready " +
                        "attempt=\(attempt) connected=\(sonyPeripheral?.state == .connected) " +
                        "commandReady=\(sonyCommandCharacteristic != nil) " +
                        "statusReady=\(sonyStatusCharacteristic != nil) " +
                        "health=\(connectionHealthState)"
                )
                return
            }
            log(
                "[ControlE2E] waiting connection attempt=\(attempt) " +
                    "connected=\(sonyPeripheral?.state == .connected) " +
                    "commandReady=\(sonyCommandCharacteristic != nil) " +
                    "statusReady=\(sonyStatusCharacteristic != nil) " +
                    "health=\(connectionHealthState)"
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.runControlE2EWhenReady(attempt: attempt + 1)
            }
            return
        }
        runControlE2ESequence()
    }

    private func runControlE2ESequence() {
        let startMs = currentTimeMs()
        log(
            "[ControlE2E] start timeMs=\(startMs) " +
                "trackId=\(currentTrackID) title=\(title.prefix(40)) " +
                "position=\(displayPositionMs) duration=\(durationMs)"
        )
        var delay: TimeInterval = 0
        scheduleControlE2EStep(name: "GET_PLAYBACK_STATE", delay: delay) { [weak self] in
            self?.sendGetPlaybackState()
        }
        delay += 1.0
        scheduleControlE2EStep(name: "GET_VOLUME", delay: delay) { [weak self] in
            self?.sendGetVolume()
        }
        delay += 1.0
        scheduleControlE2EStep(name: "PLAY_PAUSE", delay: delay) { [weak self] in
            self?.sendPlayPause()
        }
        delay += 2.0
        scheduleControlE2EStep(name: "PLAY_PAUSE_RESTORE", delay: delay) { [weak self] in
            self?.sendPlayPause()
        }
        delay += 2.0
        scheduleControlE2EStep(name: "NEXT", delay: delay) { [weak self] in
            self?.sendNext()
        }
        delay += 4.0
        scheduleControlE2EStep(name: "PREVIOUS", delay: delay) { [weak self] in
            self?.sendPrevious()
        }
        delay += 4.0
        scheduleControlE2EStep(name: "VOLUME_UP", delay: delay) { [weak self] in
            self?.sendVolumeUp()
        }
        delay += 1.5
        scheduleControlE2EStep(name: "VOLUME_DOWN", delay: delay) { [weak self] in
            self?.sendVolumeDown()
        }
        delay += 1.5
        scheduleControlE2EStep(name: "SEEK_TO", delay: delay) { [weak self] in
            guard let self else { return }
            let current = self.displayPositionMs
            let duration = self.durationMs
            let target: Int64
            if duration > 30_000 {
                target = min(max(current + 15_000, 0), max(duration - 5_000, 0))
            } else {
                target = max(current + 15_000, 0)
            }
            self.log(
                "[ControlE2E] seek target=\(target) " +
                    "from=\(current) duration=\(duration)"
            )
            self.seek(to: target)
            self.refreshPlaybackState(after: 0.7)
        }
        delay += 2.0
        scheduleControlE2EStep(name: "GET_FULL_LYRICS", delay: delay) { [weak self] in
            self?.sendGetFullLyrics(force: true)
        }
        delay += 2.0
        scheduleControlE2EStep(name: "REQUEST_HQ_ALBUM_ART", delay: delay) { [weak self] in
            guard let self else { return }
            let requested = self.requestCurrentHqAlbumArt()
            self.log("[ControlE2E] albumArt request result=\(requested)")
        }
        delay += 2.0
        scheduleControlE2EStep(name: "FINAL_GET_PLAYBACK_STATE", delay: delay) { [weak self] in
            self?.sendGetPlaybackState()
        }
        delay += 1.0
        scheduleControlE2EStep(name: "END", delay: delay) { [weak self] in
            guard let self else { return }
            self.log(
                "[ControlE2E] end timeMs=\(self.currentTimeMs()) " +
                    "trackId=\(self.currentTrackID) position=\(self.displayPositionMs)"
            )
        }
    }

    private func scheduleControlE2EStep(
        name: String,
        delay: TimeInterval,
        action: @escaping () -> Void
    ) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.log("[ControlE2E] step=\(name) action=start delayMs=\(Int(delay * 1_000))")
            action()
            self?.log("[ControlE2E] step=\(name) action=sent")
        }
    }
    #endif

    deinit {
        LiveActivityCommandBridge.shared.unregister(self)
        progressTimer?.invalidate()
        mainHeartbeatWorkItem?.cancel()
        healthCheckWorkItem?.cancel()
        healthProbeTimeoutWorkItem?.cancel()
        subscribeNotifyTimeoutWorkItem?.cancel()
        foregroundValidationTimeoutWorkItem?.cancel()
        foregroundInflightSettleWorkItem?.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    func startPeripheral() {
        setMode("Peripheral / GATT Server (Scheme B)")
        shouldStartAdvertising = true
        _ = peripheralManager
        log("[BLE-B] Start BLE Peripheral requested")

        if peripheralManager.state == .poweredOn {
            configurePeripheralService()
        } else {
            setStatus("Waiting for Bluetooth")
        }
    }

    func scanSony() {
        log("[BLE-iOS] scanSony called")
        if sonyPeripheral?.state == .connected,
           connectionHealthState == ConnectionHealthState.stale.rawValue ||
            connectionHealthState == ConnectionHealthState.disconnected.rawValue {
            performHardReconnect(reason: "manual scan unhealthy state=\(connectionHealthState)", manual: true)
            return
        }
        cancelPendingReconnect(reason: "manual scan")
        manualReconnectCount += 1
        autoReconnectAttempt = 0
        setMode("BLE Central / GATT Client")
        shouldScanWhenPoweredOn = true
        _ = centralManager
        log("[BLE-iOS] central state=\(centralManager.state.rawValue)")

        guard centralManager.state == .poweredOn else {
            setStatus("未连接")
            log("[BLE] waiting for Bluetooth")
            return
        }

        beginSonyScan(reason: "manual scan", isAutoReconnect: false, force: false)
    }

    func scanSonyFromMenu() {
        log("[UI] menu scan reconnect tapped")
        scanSony()
    }

    func setAutoReconnectEnabled(_ enabled: Bool) {
        preferences.autoReconnectEnabled = enabled
        autoReconnectEnabled = enabled
        log("[BLE-Reconnect] enabled=\(enabled)")
        if enabled {
            scheduleReconnect(reason: "enabled", immediate: true)
        } else {
            cancelPendingReconnect(reason: "disabled")
            if systemAutoReconnectInProgress, let sonyPeripheral {
                systemAutoReconnectInProgress = false
                systemAutoReconnectStartedAt = nil
                isConnectingToSony = false
                centralManager.cancelPeripheralConnection(sonyPeripheral)
                self.sonyPeripheral = nil
            }
            setAutoReconnectState(.idle)
        }
    }

    func forceReconnect() {
        log("[BLE-Reconnect] hard reconnect requested reason=manual")
        performHardReconnect(reason: "manual force reconnect", manual: true)
    }

    func resyncCurrentPlaybackFromDevice() {
        syncAfterReconnect(reason: "device detail manual resync")
    }

    private func performHardReconnect(reason: String, manual: Bool) {
        let now = Date()
        if !manual,
           let lastHardReconnectAt,
           Int64(now.timeIntervalSince(lastHardReconnectAt) * 1_000) < CONNECTION_HEALTH_HARD_RECONNECT_MIN_INTERVAL_MS {
            log("[BLE-Health] hard reconnect skipped reason=rate limited trigger=\(reason)")
            setConnectionHealth(.stale, reason: "hard reconnect rate limited")
            return
        }
        lastHardReconnectAt = now
        systemAutoReconnectInProgress = false
        systemAutoReconnectStartedAt = nil
        connectionHealthHardReconnectCount += 1
        log("[BLE-Health] hard reconnect reason=\(reason)")
        cancelPendingReconnect(reason: manual ? "manual hard reconnect" : "force reconnect")
        if manual {
            manualReconnectCount += 1
            autoReconnectAttempt = 0
        }
        connectionAttemptId = UUID()
        connectionHealthAttemptId = connectionAttemptId.uuidString
        connectionHealthLastHardReconnectReason = reason
        log("[BLE-Reconnect] hard reconnect reason=\(reason) manual=\(manual) attempt=\(connectionAttemptId.uuidString)")
        stopHealthMonitoring(reason: "hard reconnect")
        currentScanIsAutoReconnect = false
        currentConnectIsAutoReconnect = false
        currentConnectIsRetrievedPeripheral = false
        scanTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
        centralManager.stopScan()
        if let sonyPeripheral, sonyPeripheral.state != .disconnected {
            centralManager.cancelPeripheralConnection(sonyPeripheral)
        }
        clearConnectionTransports(reason: "force reconnect")
        sonyPeripheral = nil
        connectedDeviceName = "-"
        setConnectionHealth(.disconnected, reason: reason)
        setStatus("正在重新连接")
        refreshConnectionDisplayState(reason: "hard reconnect \(reason)")
        if manual {
            log("[BLE-Reconnect] start fresh scan")
            beginSonyScan(reason: "force reconnect", isAutoReconnect: false, force: true)
        } else {
            setAutoReconnectState(.failed)
            scheduleReconnect(reason: "hard reconnect: \(reason)", immediate: false)
        }
    }

    func forgetLastSonyDevice() {
        UserDefaults.standard.removeObject(forKey: AUTO_RECONNECT_LAST_PERIPHERAL_KEY)
        autoReconnectLastPeripheralId = "-"
        log("[BLE-Reconnect] forget last Sony device")
    }

    func sendPlayPause() {
        sendUserCommand(cmd: "PLAY_PAUSE")
        refreshPlaybackState(after: 0.5)
    }

    func sendNext() {
        sendUserCommand(cmd: "NEXT")
        refreshPlaybackState(after: 0.5)
    }

    func sendPrevious() {
        sendUserCommand(cmd: "PREVIOUS")
        refreshPlaybackState(after: 0.5)
    }

    func sendVolumeUp() {
        sendUserCommand(cmd: "VOLUME_UP")
        refreshVolume(after: 0.3)
    }

    func sendVolumeDown() {
        sendUserCommand(cmd: "VOLUME_DOWN")
        refreshVolume(after: 0.3)
    }

    func sendGetPlaybackState() {
        sendCommand(cmd: "GET_PLAYBACK_STATE")
    }

    func sendGetVolume() {
        sendCommand(cmd: "GET_VOLUME")
    }

    func sendGetFullLyrics(force: Bool = false) {
        requestFullLyricsIfNeeded(force: force)
    }

    func requestLyricDiagnostic(manual: Bool = false) {
        let trackID = currentTrackID
        guard !trackID.isEmpty else {
            log("[LyricsDiag-iOS] request skipped reason=no track")
            return
        }
        if !manual,
           let last = lastAutomaticLyricDiagnosticRequestAt[trackID],
           Date().timeIntervalSince(last) < 10 {
            log("[LyricsDiag-iOS] request skipped reason=rate limited trackId=\(trackID)")
            return
        }
        if !manual {
            lastAutomaticLyricDiagnosticRequestAt[trackID] = Date()
        }
        lyricDiagnosticLoading = true
        log("[LyricsDiag-iOS] request trackId=\(trackID) manual=\(manual)")
        sendCommand(
            cmd: "GET_LYRIC_DIAGNOSTIC",
            extra: [
                "trackId": trackID,
                "time": Int64(Date().timeIntervalSince1970 * 1_000)
            ]
        )
    }

    func refreshNowPlayingDiagnostics() {
        log("[NowDiag] refresh all start trackId=\(currentTrackID)")
        sendGetPlaybackState()
        sendGetVolume()
        requestLyricDiagnostic(manual: true)
        _ = makeNowPlayingDiagnosticSnapshot()
        log("[NowDiag] refresh all requested trackId=\(currentTrackID)")
    }

    func refreshSystemHealthOverview() {
        log("[SystemHealth] refresh requested trackId=\(currentTrackID)")
        sendGetPlaybackState()
        requestLyricDiagnostic(manual: true)
    }

    func refreshCurrentLyricFromNowPlayingDiagnostics() {
        log("[NowDiag] refresh lyric requested trackId=\(currentTrackID)")
        requestLyricDiagnostic(manual: true)
        sendGetFullLyrics(force: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.log("[NowDiag] lyric refresh completed trackId=\(self?.currentTrackID ?? "-")")
        }
    }

    func retryCurrentLyricsFromMain() {
        let trackID = currentTrackID
        guard !trackID.isEmpty else { return }
        fullLyricsRequestStartTimeouts.removeValue(forKey: trackID)?.cancel()
        fullLyricsRequestStartRetryCounts.removeValue(forKey: trackID)
        requestedFullLyricsTrackIDs.remove(trackID)
        completedFullLyricsTrackIDs.remove(trackID)
        fullLyricsUnavailableTrackIDs.remove(trackID)
        fullLyricsDelayedRetryTrackIDs.remove(trackID)
        requestedLyricWindowTrackIDs.remove(trackID)
        lyricDiagnostic = nil
        lyricDiagnosticLoading = true
        mediaLoadingState.lyric = .waitingQqQrc
        log("[LyricRetry] main UI retry current trackId=\(trackID)")
        sendCommand(
            cmd: "GET_FULL_LYRICS",
            extra: [
                "trackId": trackID,
                "positionMs": displayPositionMs,
                "includeWordsAroundCurrent": true,
                "forceRefresh": true,
                "format": serverSupportsFullLyricsZlib ? "zlib-json-v1" : "legacy"
            ]
        )
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            guard let self, self.currentTrackID == trackID else { return }
            if self.serverSupportsLyricWindow {
                self.requestedLyricWindowTrackIDs.remove(trackID)
                self.requestLyricWindow(trackID: trackID)
            }
            self.requestFullLyricsIfNeeded(force: true)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { [weak self] in
            guard let self, self.currentTrackID == trackID else { return }
            self.requestLyricDiagnostic(manual: true)
        }
    }

    func retryCurrentAlbumArtFromMain() {
        mediaLoadingState.artwork = .preview(received: 0, expected: 0)
        let requested = forceRefreshCurrentAlbumArt()
        log("[AlbumArtRefresh] main UI retry requested=\(requested)")
    }

    @discardableResult
    func requestCurrentHqAlbumArt(forceRefresh: Bool = false) -> Bool {
        albumArtReceiver.requestCurrentHqAlbumArt(forceRefresh: forceRefresh)
    }

    @discardableResult
    func forceRefreshCurrentAlbumArt() -> Bool {
        albumArtReceiver.forceRefreshCurrentAlbumArt()
    }

    func noteNowPlayingDiagnosticsCopied(trackId: String) {
        log("[NowDiag] copied diagnostics trackId=\(trackId)")
    }

    func makeNowPlayingDiagnosticSnapshot() -> NowPlayingDiagnosticSnapshot {
        let hqWriteLength = sonyPeripheral?.maximumWriteValueLength(for: .withResponse) ?? 0
        let connection = ConnectionDiagnosticSnapshot(
            connectionStatus: connectionStatus,
            displayState: connectionDisplayState,
            healthState: connectionHealthState,
            autoReconnectState: autoReconnectState,
            autoReconnectAttempt: autoReconnectAttempt,
            mtuBytes: hqWriteLength > 0 ? hqWriteLength + 3 : 0,
            lastNotifyAgeMs: connectionHealthLastNotifyAgeMs,
            peripheralState: connectionHealthPeripheralState,
            characteristicReady: connectionHealthCharacteristicReady,
            probeInFlight: connectionHealthProbeInFlight,
            lastHardReconnectReason: connectionHealthLastHardReconnectReason,
            reconnectWorkItemExists: autoReconnectWorkItemExists
        )
        let artwork = albumArtReceiver.snapshot()
        let selfHealing = selfHealingEngine.evaluate(
            trackId: currentTrackID,
            title: title,
            connection: connection,
            artwork: artwork,
            lyric: lyricDiagnostic,
            currentLyric: lyric,
            fullLyricsLineCount: fullLyrics.count,
            isFullLyricsCurrent: isFullLyricsCurrent
        )
        return NowPlayingDiagnosticSnapshot(
            generatedAt: Date(),
            title: title,
            artist: artist,
            album: album,
            trackId: currentTrackID,
            albumArtId: artwork.id,
            albumArtDisplayQuality: artwork.displayQuality.label,
            displayArtworkPixelWidth: artwork.displayPixelWidth,
            displayArtworkPixelHeight: artwork.displayPixelHeight,
            artworkEnhancementStatus: artwork.enhancementStatus,
            artworkCaches: artwork.caches,
            hqUnavailableReason: artwork.hqUnavailableReason,
            hqUnavailableBestBytes: artwork.hqUnavailableBestBytes,
            hqUnavailableBestChunks: artwork.hqUnavailableBestChunks,
            hqUnavailableMinCandidateScale: artwork.hqUnavailableMinCandidateScale,
            albumArtTransfer: artwork.transfer,
            predictiveAlbumArt: PredictiveAlbumArtDiagnosticSnapshot(
                lastAlbumArtId: artwork.predictive.lastAlbumArtId,
                pendingHq: artwork.predictive.pendingHq,
                pendingHqId: artwork.predictive.pendingHqId,
                lastSkipReason: artwork.predictive.lastSkipReason,
                offerCount: artwork.predictive.offerCount,
                hqPrefetchScheduled: artwork.predictive.hqPrefetchScheduled,
                hqPrefetchSent: artwork.predictive.hqPrefetchSent,
                hqPrefetchSkippedCacheHit: artwork.predictive.hqPrefetchSkippedCacheHit,
                hqPrefetchSkippedInFlight: artwork.predictive.hqPrefetchSkippedInFlight,
                hqPrefetchSkippedNotConnected: artwork.predictive.hqPrefetchSkippedNotConnected,
                hqPrefetchCancelledTrackChanged: artwork.predictive.hqPrefetchCancelledTrackChanged,
                hqArrivedBeforeDisplayCount: artwork.predictive.hqArrivedBeforeDisplayCount,
                avgOfferToHqRequestMs: artwork.predictive.avgOfferToHqRequestMs,
                avgOfferToHqReadyMs: artwork.predictive.avgOfferToHqReadyMs,
                lastOfferToHqRequestMs: artwork.predictive.lastOfferToHqRequestMs,
                lastOfferToHqReadyMs: artwork.predictive.lastOfferToHqReadyMs
            ),
            isPlaying: isPlaying,
            positionMs: displayPositionMs,
            durationMs: durationMs,
            currentLyric: lyric,
            lyricDiagnostic: lyricDiagnostic,
            fullLyricsLineCount: fullLyrics.count,
            isFullLyricsCurrent: isFullLyricsCurrent,
            isFullLyricsReceiving: isFullLyricsReceiving,
            currentWord: CurrentWordDiagnosticSnapshot(
                lineIndex: currentWordLineIndex,
                wordIndex: currentWordIndex,
                pushCount: currentWordPushCount,
                dropCount: currentWordDropCount,
                averageUpdateIntervalMs: currentWordAverageUpdateIntervalMs,
                lastLatencyMs: currentWordLastLatencyMs,
                automaticSyncEnabled: automaticLyricSyncEnabled,
                automaticCompensationMs: lyricAutomaticCompensationMs,
                manualFineTuneMs: karaokeOffsetMs,
                legacyFallbackMs: legacyLyricFallbackOffsetMs,
                clockBestRoundTripMs: lyricClockBestRoundTripMs,
                clockOffsetJitterMs: lyricClockOffsetJitterMs,
                clockSampleCount: lyricClockSampleCount,
                clockSyncConfident: lyricClockSyncConfident
            ),
            connection: connection,
            selfHealing: selfHealing
        )
    }

    func setKaraokeOffsetMs(_ value: Int64) {
        let normalized = min(max(value, -2_000), 2_000)
        preferences.lyricOffsetMs = Int(normalized)
        karaokeOffsetMs = normalized
        log("[Lyrics-iOS] manual fine tune offsetMs=\(value)")
    }

    func setAutomaticLyricSyncEnabled(_ enabled: Bool) {
        guard automaticLyricSyncEnabled != enabled else { return }
        preferences.automaticLyricSyncEnabled = enabled
        automaticLyricSyncEnabled = enabled
        if !enabled {
            lyricAutomaticCompensationMs = 0
        }
        log("[Lyrics-iOS] automatic sync enabled=\(enabled)")
    }

    func refreshLiveActivityAppearance() {
        log("[LiveActivity] refresh appearance requested")
        updateLiveActivity(force: true, reason: "preferences")
    }

    func copyIOSLogs() {
        AppLogStore.shared.readRecentText { [weak self] text in
            guard let self else { return }
            if text.isEmpty {
                self.localLogActionStatus = "暂无 iOS 日志"
            } else {
                UIPasteboard.general.string = text
                self.localLogActionStatus = "已复制 iOS 日志"
            }
        }
    }

    func setUILogStreamingEnabled(_ enabled: Bool) {
        uiLogQueue.async { [weak self] in
            guard let self else { return }
            self.isUILogStreamingEnabled = enabled
            if !enabled {
                self.pendingUILogLines.removeAll(keepingCapacity: true)
                self.isUILogFlushScheduled = false
                return
            }
            AppLogStore.shared.readRecentText { [weak self] text in
                guard let self else { return }
                self.logs = text
                    .split(separator: "\n", omittingEmptySubsequences: true)
                    .suffix(300)
                    .map(String.init)
            }
        }
    }

    func clearIOSLogs() {
        AppLogStore.shared.clear { [weak self] in
            guard let self else { return }
            self.logs.removeAll()
            self.localLogActionStatus = "已清空 iOS 日志"
        }
    }

    func setArtworkEnhancementEnabled(_ enabled: Bool) {
        albumArtReceiver.setArtworkEnhancementEnabled(enabled)
    }

    func setArtworkEnhancementTargetPixelSize(_ value: Int) {
        albumArtReceiver.setArtworkEnhancementTargetPixelSize(value)
    }

    func setArtworkEnhancementSharpness(_ value: Double) {
        albumArtReceiver.setArtworkEnhancementSharpness(value)
    }

    func clearEnhancedArtworkCache() {
        albumArtReceiver.clearEnhancedArtworkCache()
    }

    var artworkEnhancementEnabledForPreferences: Bool {
        artworkEnhancementStatus.enabled
    }

    var currentMtuBytesForPreferences: Int {
        let writeLength = sonyPeripheral?.maximumWriteValueLength(for: .withResponse) ?? 0
        return writeLength > 0 ? writeLength + 3 : 0
    }

    func rebuildCurrentEnhancedArtwork() {
        albumArtReceiver.rebuildCurrentEnhancedArtwork()
    }

    func toggleArtworkEnhancementABComparison() {
        albumArtReceiver.toggleArtworkEnhancementABComparison()
    }

    func karaokePositionMs(rawPositionMs: Int64) -> Int64 {
        rawPositionMs + karaokeOffsetMs + legacyLyricFallbackOffsetMs
    }

    private var legacyLyricFallbackOffsetMs: Int64 {
        automaticLyricSyncEnabled &&
            !serverSupportsClockSyncV1 &&
            karaokeOffsetMs == 0
            ? 600
            : 0
    }

    private func resolveRemotePlaybackAnchor(
        object: [String: Any],
        remotePositionMs: Int64,
        timestampMs: Int64 = 0,
        isPlaying: Bool
    ) -> RemotePlaybackAnchorResolution {
        guard automaticLyricSyncEnabled else {
            return .unavailable
        }
        let parsedSpeed = Self.doubleValue(object["speed"])
        let speed = parsedSpeed.isFinite && parsedSpeed > 0
            ? parsedSpeed
            : remotePlaybackSpeed
        let sampleElapsedMs = Self.int64Value(object["sampleMono"])
        if sampleElapsedMs > 0 {
            let resolved = RemotePlaybackAnchorPolicy.resolve(
                remotePositionMs: remotePositionMs,
                serverSampleElapsedMs: sampleElapsedMs,
                localReceiveElapsedMs: monotonicTimeMs(),
                playbackSpeed: speed,
                isPlaying: isPlaying,
                durationMs: durationMs,
                synchronizer: clockSynchronizer
            )
            if resolved != .unavailable {
                return resolved
            }
        }
        if timestampMs > 0,
           serverSupportsClockSyncV1,
           lyricClockSyncConfident,
           let sonyUnixMinusElapsedMs {
            let bridgedServerSampleElapsedMs = timestampMs - sonyUnixMinusElapsedMs
            let resolved = RemotePlaybackAnchorPolicy.resolve(
                remotePositionMs: remotePositionMs,
                serverSampleElapsedMs: bridgedServerSampleElapsedMs,
                localReceiveElapsedMs: monotonicTimeMs(),
                playbackSpeed: speed,
                isPlaying: isPlaying,
                durationMs: durationMs,
                synchronizer: clockSynchronizer
            )
            if resolved != .unavailable {
                return resolved
            }
        }
        if timestampMs > 0,
           let sonyClockOffsetMs,
           !serverSupportsClockSyncV1 || lyricClockSyncConfident {
            let mappedSampleTimeMs = timestampMs - sonyClockOffsetMs
            let measuredAgeMs = currentTimeMs() - mappedSampleTimeMs
            return RemotePlaybackAnchorPolicy.resolve(
                remotePositionMs: remotePositionMs,
                measuredTransportAgeMs: measuredAgeMs,
                playbackSpeed: speed,
                isPlaying: isPlaying,
                durationMs: durationMs
            )
        }
        return .unavailable
    }

    private func calibratedRemotePosition(
        from resolution: RemotePlaybackAnchorResolution,
        remotePositionMs: Int64,
        source: String
    ) -> Int64? {
        switch resolution {
        case .unavailable:
            lyricAutomaticCompensationMs = 0
            return remotePositionMs
        case let .stale(transportAgeMs):
            lyricAutomaticCompensationMs = transportAgeMs
            let nowMs = currentTimeMs()
            if nowMs - lastStaleRemoteAnchorLogAtMs >= 1_000 {
                lastStaleRemoteAnchorLogAtMs = nowMs
                log(
                    "[ClockSync] stale anchor discarded source=\(source) " +
                        "ageMs=\(transportAgeMs) positionMs=\(remotePositionMs)"
                )
            }
            return nil
        case let .resolved(targetPositionMs, transportAgeMs):
            lyricAutomaticCompensationMs = automaticLyricSyncEnabled
                ? transportAgeMs
                : 0
            return RemotePlaybackAnchorPolicy.smoothedPosition(
                currentPositionMs: displayPositionMs,
                targetPositionMs: targetPositionMs
            )
        }
    }

    private func locallyResolvedWord(
        positionMs: Int64
    ) -> (lineIndex: Int, wordIndex: Int, text: String)? {
        guard isSameTrackId(incoming: fullLyricsTrackId, current: currentTrackID),
              let arrayIndex = currentLyricIndex(lines: fullLyrics, positionMs: positionMs),
              fullLyrics.indices.contains(arrayIndex) else {
            return nil
        }
        let line = fullLyrics[arrayIndex]
        var wordIndex = -1
        for (index, word) in line.words.enumerated() where word.startMs <= positionMs {
            wordIndex = index
        }
        return (line.index, wordIndex, line.text)
    }

    func resolveCurrentLyric(
        positionMs: Int64,
        fullLyrics: [LyricLine],
        playbackStateLyric: String
    ) -> ResolvedLyric {
        let trackID = currentTrackID
        let effectivePositionMs = karaokePositionMs(rawPositionMs: positionMs)
        if fullLyricsTrackId == trackID,
           !fullLyrics.isEmpty,
           let index = currentLyricIndex(lines: fullLyrics, positionMs: effectivePositionMs),
           fullLyrics.indices.contains(index) {
            let text = fullLyrics[index].text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                return ResolvedLyric(
                    trackId: trackID,
                    lineIndex: fullLyrics[index].index,
                    text: text,
                    source: "fullLyrics"
                )
            }
        }

        let fallbackText = playbackStateLyric.trimmingCharacters(in: .whitespacesAndNewlines)
        if !fallbackText.isEmpty {
            return ResolvedLyric(
                trackId: trackID,
                lineIndex: -1,
                text: fallbackText,
                source: "playbackState"
            )
        }

        if trackID == lastLiveActivityLyricTrackID,
           !lastLiveActivityLyricText.isEmpty,
           lastLiveActivityLyricText != "暂无歌词" {
            return ResolvedLyric(
                trackId: trackID,
                lineIndex: lastLiveActivityLyricLineIndex,
                text: lastLiveActivityLyricText,
                source: "previous"
            )
        }

        return ResolvedLyric(
            trackId: trackID,
            lineIndex: -1,
            text: "暂无歌词",
            source: "none"
        )
    }

    func logKaraokeOffset(rawPositionMs: Int64) {
        let now = currentTimeMs()
        guard now - lastKaraokeOffsetLogAtMs >= 3_000 else { return }
        lastKaraokeOffsetLogAtMs = now
        log(
            "[Lyrics-iOS] automaticSync=\(automaticLyricSyncEnabled) " +
                "autoCompensationMs=\(lyricAutomaticCompensationMs) " +
                "manualOffsetMs=\(karaokeOffsetMs) " +
                "legacyFallbackMs=\(legacyLyricFallbackOffsetMs) " +
                "rawPosition=\(rawPositionMs) " +
                "effectivePosition=\(karaokePositionMs(rawPositionMs: rawPositionMs))"
        )
    }

    func sendGetSonyLogs() {
        remoteLogCopyStatus = ""
        isRemoteLogTransferInProgress = true
        sendCommand(cmd: "GET_LOGS", extra: ["limit": 30])
    }

    func copySonyLogs() {
        guard !remoteLogText.isEmpty else { return }
        UIPasteboard.general.string = remoteLogText
        remoteLogCopyStatus = "已复制 Sony 日志"
    }

    func sendDumpMediaFields() {
        resetMediaFieldDumpTransfer()
        mediaFieldDumpText = ""
        mediaFieldDumpCopyStatus = ""
        isMediaFieldDumpReceiving = true
        mediaFieldDumpProgressText = "Media dump receiving..."
        sendCommand(cmd: "DUMP_MEDIA_FIELDS")
    }

    func copyMediaFieldDump() {
        guard !mediaFieldDumpText.isEmpty else { return }
        UIPasteboard.general.string = mediaFieldDumpText
        mediaFieldDumpCopyStatus = "已复制 Media Field Dump"
    }

    func loadCachedPlaybackHistory() {
        PlaybackHistoryStore.shared.loadSessions { [weak self] sessions in
            PlaybackHistoryStore.shared.loadSyncState { syncState in
                PlaybackHistoryStore.shared.loadStats { stats in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.playbackHistorySessions = sessions
                        self.lastSyncedHistorySessionId = syncState.lastSyncedSessionId
                        self.playbackStats = stats
                        self.playbackHistoryStatus = sessions.isEmpty ? "暂无本地历史" : "已加载本地历史"
                    }
                }
            }
        }
    }

    func syncPlaybackHistory() {
        guard connectionStatus == "已连接" else {
            playbackHistoryStatus = "Sony 未连接"
            log("[History-iOS] sync skipped reason=not connected")
            return
        }
        guard !isPlaybackHistorySyncing else {
            playbackHistoryStatus = "同步中..."
            return
        }
        isPlaybackHistorySyncing = true
        refreshStatsAfterHistorySync = true
        playbackHistoryStatus = "同步播放历史..."
        requestPlaybackHistorySince(afterSessionId: lastSyncedHistorySessionId)
    }

    func loadMorePlaybackHistory() {
        guard connectionStatus == "已连接" else {
            playbackHistoryStatus = "Sony 未连接"
            return
        }
        guard !isLoadingMoreHistory else { return }
        let beforeSessionId = playbackHistorySessions.map(\.sessionId).min()
        guard let beforeSessionId else {
            syncPlaybackHistory()
            return
        }
        isLoadingMoreHistory = true
        let requestId = "history-page-\(currentTimeMs())"
        pendingHistoryRequests[requestId] = .page
        log("[HistorySync] request page requestId=\(requestId) before=\(beforeSessionId)")
        sendCommand(
            cmd: "GET_PLAY_HISTORY_PAGE",
            extra: [
                "requestId": requestId,
                "beforeSessionId": beforeSessionId,
                "limit": 10
            ]
        )
    }

    func refreshPlaybackStats() {
        guard connectionStatus == "已连接" else { return }
        guard pendingPlaybackStatsRanges.isEmpty,
              !pendingHistoryRequests.values.contains(where: {
                  if case .stats = $0 { return true }
                  return false
              }) else { return }
        pendingPlaybackStatsRanges = ["TODAY", "WEEK", "MONTH"]
        requestNextPlaybackStats()
    }

    private func requestNextPlaybackStats() {
        guard connectionStatus == "已连接",
              !pendingPlaybackStatsRanges.isEmpty else { return }
        let range = pendingPlaybackStatsRanges.removeFirst()
        let requestId = "stats-\(range)-\(currentTimeMs())"
        pendingHistoryRequests[requestId] = .stats(range)
        log("[HistorySync] request stats requestId=\(requestId) range=\(range)")
        sendCommand(
            cmd: "GET_PLAY_STATS",
            extra: [
                "requestId": requestId,
                "range": range
            ]
        )
    }

    func clearLocalPlaybackHistory() {
        PlaybackHistoryStore.shared.clear { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.playbackHistorySessions = []
                self.playbackStats = [:]
                self.lastSyncedHistorySessionId = 0
                self.playbackHistoryStatus = "已清空 iPhone 本地缓存"
                self.log("[History-iOS] local cache cleared")
            }
        }
    }

    private func requestPlaybackHistorySince(afterSessionId: Int64) {
        let requestId = "history-since-\(currentTimeMs())"
        pendingHistoryRequests[requestId] = .since
        log("[HistorySync] request since requestId=\(requestId) after=\(afterSessionId)")
        sendCommand(
            cmd: "GET_PLAY_HISTORY_SINCE",
            extra: [
                "requestId": requestId,
                "afterSessionId": afterSessionId,
                "limit": 20
            ]
        )
    }

    private func sendUserCommand(cmd: String, extra: [String: Any] = [:]) {
        let seq = nextCommandSeq()
        ctrlLog("[CTRL-iOS] tap seq=\(seq) cmd=\(cmd) uiTimeMs=\(currentTimeMs())")
        sendCommand(cmd: cmd, extra: extra, seq: seq)
    }

    func sendCommand(cmd: String, extra: [String: Any] = [:]) {
        sendCommand(cmd: cmd, extra: extra, seq: nil)
    }

    private func sendCommand(cmd: String, extra: [String: Any] = [:], seq providedSeq: UInt64?) {
        let seq = providedSeq ?? nextCommandSeq()
        let startMs = currentTimeMs()
        var payload = extra
        payload["cmd"] = cmd
        // Capability payloads sit close to the common 182-byte ATT limit.
        // Their wall-clock field was never consumed by Sony, so omit it to
        // leave room for additive capability flags without fragmenting writes.
        if cmd != "CLIENT_CAPABILITIES" {
            payload["time"] = startMs
        }
        payload["seq"] = seq

        let connected = sonyPeripheral?.state == .connected
        let characteristicReady = sonyCommandCharacteristic != nil
        ctrlLog(
            "[CTRL-iOS] send start seq=\(seq) cmd=\(cmd) timeMs=\(startMs) " +
                "connected=\(connected) characteristicReady=\(characteristicReady) " +
                "connectionStatus=\(connectionStatus) centralState=\(centralManager.state.rawValue) " +
                "peripheralId=\(sonyPeripheral?.identifier.uuidString ?? "nil") " +
                "health=\(connectionHealthState)"
        )

        if isControlCommand(cmd),
           !isConnectionHealthyOrSuspect {
            ctrlLog(
                "[CTRL-iOS] write skipped seq=\(seq) cmd=\(cmd) " +
                    "reason=unhealthy health=\(connectionHealthState)"
            )
            log("[Command] send failed \(cmd): unhealthy \(connectionHealthState)")
            return
        }

        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            ctrlLog("[CTRL-iOS] write skipped seq=\(seq) cmd=\(cmd) reason=encode_failed")
            log("[Command] encode failed \(cmd)")
            return
        }

        guard let sonyPeripheral,
              sonyPeripheral.state == .connected,
              sonyCommandCharacteristic != nil else {
            let reason = isReconnectInProgress ? "reconnecting" : "not_connected"
            ctrlLog("[CTRL-iOS] write skipped seq=\(seq) cmd=\(cmd) reason=\(reason)")
            log("[Command] send failed \(cmd): \(reason)")
            if connected, sonyCommandCharacteristic == nil {
                performHardReconnect(reason: "command characteristic nil while connected", manual: false)
            }
            return
        }

        enqueueCommandWrite(
            PendingCommandWrite(
                seq: seq,
                cmd: cmd,
                data: data,
                payloadText: text,
                enqueuedAtMs: startMs,
                isControl: isControlCommand(cmd),
                volumeValue: nil,
                volumeReason: nil
            )
        )
    }

    private func enqueueCommandWrite(_ request: PendingCommandWrite) {
        if foregroundValidationPending,
           foregroundValidationCommandSeq == request.seq {
            pendingCommandWrites.insert(request, at: 0)
        } else if request.isControl,
           let firstBackgroundIndex = pendingCommandWrites.firstIndex(where: { !$0.isControl }) {
            pendingCommandWrites.insert(request, at: firstBackgroundIndex)
        } else {
            pendingCommandWrites.append(request)
        }
        if !commandWriteInflight.isEmpty {
            ctrlLog(
                "[CTRL-iOS] write queued seq=\(request.seq) cmd=\(request.cmd) " +
                    "pending=\(pendingCommandWrites.count)"
            )
        }
        flushCommandWriteQueue()
    }

    private func flushCommandWriteQueue() {
        // Background execution is opportunistic. Keep queued synchronization
        // work frozen until foreground, while still allowing a user initiated
        // Live Activity control (which is ordered ahead of background work).
        if appLifecycleState != "active",
           pendingCommandWrites.first?.isControl != true {
            return
        }
        guard commandWriteInflight.isEmpty,
              !pendingCommandWrites.isEmpty,
              let sonyPeripheral,
              sonyPeripheral.state == .connected,
              let sonyCommandCharacteristic else {
            return
        }

        let request = pendingCommandWrites.removeFirst()
        let writeBeginMs = currentTimeMs()
        commandWriteInflight.append(
            CommandWriteInfo(
                seq: request.seq,
                cmd: request.cmd,
                writeCalledAtMs: writeBeginMs
            )
        )
        if request.cmd == "SET_VOLUME" {
            volumeWriteInFlightSeq = request.seq
            lastVolumeSendAtMs = writeBeginMs
        }
        ctrlLog(
            "[CTRL-iOS] write begin seq=\(request.seq) cmd=\(request.cmd) " +
                "timeMs=\(writeBeginMs) queuedMs=\(writeBeginMs - request.enqueuedAtMs)"
        )
        sonyPeripheral.writeValue(
            request.data,
            for: sonyCommandCharacteristic,
            type: .withResponse
        )
        ctrlLog(
            "[CTRL-iOS] write called seq=\(request.seq) cmd=\(request.cmd) " +
                "timeMs=\(currentTimeMs())"
        )
        if request.cmd == "SET_VOLUME" {
            log(
                "[VOL-iOS] send SET_VOLUME value=\(request.volumeValue ?? -1) " +
                    "reason=\(request.volumeReason ?? "unknown")"
            )
        } else {
            log("[Command] send \(request.cmd)")
        }
        log("[BLE] write requested \(request.payloadText)")
        scheduleCommandWriteTimeout(seq: request.seq, cmd: request.cmd)
        if healthProbeCommandSeq == request.seq {
            startHealthProbeResponseTimeout(seq: request.seq, command: request.cmd)
        }
        if foregroundValidationPending,
           foregroundValidationCommandSeq == request.seq {
            startForegroundValidationTimeout(seq: request.seq)
        }
    }

    private func scheduleCommandWriteTimeout(seq: UInt64, cmd: String) {
        commandWriteTimeoutWorkItem?.cancel()
        guard appLifecycleState == "active" else {
            commandWriteTimeoutWorkItem = nil
            ctrlLog(
                "[CTRL-iOS] write timeout suspended seq=\(seq) cmd=\(cmd) " +
                    "appState=\(appLifecycleState)"
            )
            return
        }
        let expectedLifecycleGeneration = lifecycleGeneration
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.appLifecycleState == "active",
                  self.lifecycleGeneration == expectedLifecycleGeneration,
                  self.commandWriteInflight.first?.seq == seq else {
                return
            }
            self.commandWriteTimeoutWorkItem = nil
            self.ctrlLog(
                "[CTRL-iOS] write timeout seq=\(seq) cmd=\(cmd) " +
                    "timeoutMs=\(COMMAND_WRITE_CALLBACK_TIMEOUT_MS) " +
                    "pending=\(self.pendingCommandWrites.count)"
            )
            self.handleCommandWriteCallbackTimeout(seq: seq, cmd: cmd)
        }
        commandWriteTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(COMMAND_WRITE_CALLBACK_TIMEOUT_MS) / 1_000.0,
            execute: item
        )
    }

    private func handleCommandWriteCallbackTimeout(seq: UInt64, cmd: String) {
        let now = Date()
        let recentNotifyAgeMs = lastStatusNotifyAt.map {
            Int64(now.timeIntervalSince($0) * 1_000)
        } ?? Int64.max
        let transportReady = sonyPeripheral?.state == .connected &&
            sonyCommandCharacteristic != nil
        let nextTimeoutCount = consecutiveCommandWriteTimeouts + 1
        let action = CommandWriteTimeoutPolicy.action(
            appIsActive: appLifecycleState == "active",
            transportReady: transportReady,
            timeoutCountAfterIncrement: nextTimeoutCount,
            reconnectThreshold: COMMAND_WRITE_TIMEOUTS_BEFORE_RECONNECT
        )
        switch action {
        case .suspendUntilForeground:
            ctrlLog(
                "[CTRL-iOS] write timeout ignored during lifecycle suspension " +
                    "seq=\(seq) cmd=\(cmd) appState=\(appLifecycleState)"
            )
        case .extendWithoutAdvancingQueue:
            consecutiveCommandWriteTimeouts = nextTimeoutCount
            // Never pop the in-flight request here. CoreBluetooth can deliver
            // its callback late; advancing the queue would then attribute that
            // callback to a different command.
            setConnectionHealth(
                .suspect,
                reason: "write callback delayed recentNotifyAgeMs=\(recentNotifyAgeMs)"
            )
            ctrlLog(
                "[CTRL-iOS] write timeout extended seq=\(seq) cmd=\(cmd) " +
                    "count=\(consecutiveCommandWriteTimeouts) " +
                    "recentNotify=\(recentNotifyAgeMs <= COMMAND_WRITE_RECENT_NOTIFY_GRACE_MS)"
            )
            scheduleCommandWriteTimeout(seq: seq, cmd: cmd)
        case .reconnect:
            consecutiveCommandWriteTimeouts = nextTimeoutCount
            performHardReconnect(
                reason: "command write callback timeout x\(nextTimeoutCount) cmd=\(cmd) seq=\(seq)",
                manual: false
            )
        }
    }

    func seek(to position: Int64) {
        sendUserCommand(cmd: "SEEK_TO", extra: ["position": max(position, 0)])
    }

    func seekToLyricLine(_ timeMs: Int64) {
        let targetPosition = timeMs.clamped(to: 0...max(durationMs, 0))
        positionMs = targetPosition
        displayPositionMs = targetPosition
        seekPositionMs = targetPosition
        basePlaybackPositionMs = targetPosition
        playbackAnchorElapsedMs = monotonicTimeMs()
        log("[iOS][Seek] lyric line position=\(targetPosition)")
        updateLiveActivity(force: true, reason: "seek")
        seek(to: targetPosition)
        refreshPlaybackState(after: 0.5)
    }

    func requestFullLyricsOptionalFieldsIfNeeded(displayMode: LyricDisplayMode) {
        let trackID = currentTrackID
        guard !trackID.isEmpty else { return }
        guard fullLyricsTrackId == trackID, !fullLyrics.isEmpty else { return }
        var modes: [LyricSecondaryMode] = []
        if displayMode.showsTranslation {
            modes.append(.translation)
        }
        if displayMode.showsRomanization {
            modes.append(.romanization)
        }
        guard !modes.isEmpty else { return }
        for mode in modes {
            enqueueLyricSecondaryIfNeeded(mode: mode, trackID: trackID)
        }
        requestNextLyricSecondaryIfPossible()
    }

    private func enqueueLyricSecondaryIfNeeded(mode: LyricSecondaryMode, trackID: String) {
        let key = lyricSecondaryKey(trackID: trackID, mode: mode)
        let nowMs = currentTimeMs()
        if let cooldownUntil = lyricSecondaryFailureCooldownUntilMs[key],
           cooldownUntil > nowMs {
            log(
                "[Lyrics-iOS] secondary cooldown mode=\(mode.rawValue) " +
                    "remainingMs=\(cooldownUntil - nowMs)"
            )
            return
        }
        lyricSecondaryFailureCooldownUntilMs.removeValue(forKey: key)
        guard !completedLyricSecondaryKeys.contains(key),
              !requestedLyricSecondaryKeys.contains(key),
              !pendingLyricSecondaryModes.contains(mode),
              lyricSecondaryTransfer?.mode != mode else {
            return
        }
        requestedLyricSecondaryKeys.insert(key)
        pendingLyricSecondaryModes.append(mode)
        setLyricSecondaryState(.loading, mode: mode)
        log("[Lyrics-iOS] secondary queued mode=\(mode.rawValue) trackId=\(trackID)")
    }

    private func requestNextLyricSecondaryIfPossible() {
        guard activeLyricSecondaryRequest == nil else { return }
        guard lyricSecondaryTransfer == nil else { return }
        guard !pendingLyricSecondaryModes.isEmpty else { return }
        let trackID = currentTrackID
        guard !trackID.isEmpty,
              fullLyricsTrackId == trackID,
              !fullLyrics.isEmpty else { return }
        if isInStartupLoadWindow() {
            let delay = startupLoadRemainingDelay()
            guard lyricSecondaryDeferredRequestWorkItem == nil else { return }
            log("[StartupLoad] defer request=GET_LYRIC_SECONDARY reason=first connection warmup delayMs=\(Int(delay * 1_000))")
            let item = DispatchWorkItem { [weak self] in
                self?.lyricSecondaryDeferredRequestWorkItem = nil
                self?.requestNextLyricSecondaryIfPossible()
            }
            lyricSecondaryDeferredRequestWorkItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
            return
        }
        let mode = pendingLyricSecondaryModes.removeFirst()
        let key = lyricSecondaryKey(trackID: trackID, mode: mode)
        let request = LyricSecondaryRequest(
            trackId: trackID,
            mode: mode,
            token: UUID(),
            connectionAttemptId: connectionAttemptId,
            retryCount: lyricSecondaryRetryCounts[key] ?? 0
        )
        activeLyricSecondaryRequest = request
        setLyricSecondaryState(.loading, mode: mode)
        log("[Lyrics-iOS] secondary request mode=\(mode.rawValue) trackId=\(trackID)")
        sendCommand(
            cmd: "GET_LYRIC_SECONDARY",
            extra: [
                "trackId": trackID,
                "mode": mode.rawValue
            ]
        )
        scheduleLyricSecondaryStartTimeout(for: request)
        scheduleLyricSecondaryTotalTimeout(for: request)
    }

    private func lyricSecondaryKey(trackID: String, mode: LyricSecondaryMode) -> String {
        "\(trackID)|\(mode.rawValue)"
    }

    private func setLyricSecondaryState(
        _ state: LyricSecondaryLoadState,
        mode: LyricSecondaryMode
    ) {
        switch mode {
        case .translation:
            guard translationLyricsState != state else { return }
            translationLyricsState = state
        case .romanization:
            guard romanizationLyricsState != state else { return }
            romanizationLyricsState = state
        }
    }

    private func scheduleLyricSecondaryStartTimeout(for request: LyricSecondaryRequest) {
        lyricSecondaryStartTimeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.activeLyricSecondaryRequest?.token == request.token,
                  self.lyricSecondaryTransfer == nil else { return }
            self.handleLyricSecondaryFailure(
                trackID: request.trackId,
                mode: request.mode,
                reason: "start timeout",
                explicitlyUnavailable: false
            )
        }
        lyricSecondaryStartTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(Int(LYRIC_SECONDARY_START_TIMEOUT_MS)),
            execute: item
        )
    }

    private func scheduleLyricSecondaryIdleTimeout(for request: LyricSecondaryRequest) {
        lyricSecondaryIdleTimeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.activeLyricSecondaryRequest?.token == request.token,
                  self.lyricSecondaryTransfer?.requestToken == request.token else { return }
            self.handleLyricSecondaryFailure(
                trackID: request.trackId,
                mode: request.mode,
                reason: "chunk idle timeout",
                explicitlyUnavailable: false
            )
        }
        lyricSecondaryIdleTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(Int(LYRIC_SECONDARY_IDLE_TIMEOUT_MS)),
            execute: item
        )
    }

    private func scheduleLyricSecondaryTotalTimeout(for request: LyricSecondaryRequest) {
        lyricSecondaryTotalTimeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.activeLyricSecondaryRequest?.token == request.token else { return }
            self.handleLyricSecondaryFailure(
                trackID: request.trackId,
                mode: request.mode,
                reason: "total timeout",
                explicitlyUnavailable: false
            )
        }
        lyricSecondaryTotalTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(Int(LYRIC_SECONDARY_TOTAL_TIMEOUT_MS)),
            execute: item
        )
    }

    private func cancelLyricSecondaryTimeouts() {
        lyricSecondaryStartTimeoutWorkItem?.cancel()
        lyricSecondaryStartTimeoutWorkItem = nil
        lyricSecondaryIdleTimeoutWorkItem?.cancel()
        lyricSecondaryIdleTimeoutWorkItem = nil
        lyricSecondaryTotalTimeoutWorkItem?.cancel()
        lyricSecondaryTotalTimeoutWorkItem = nil
    }

    private func handleLyricSecondaryFailure(
        trackID: String,
        mode: LyricSecondaryMode,
        reason: String,
        explicitlyUnavailable: Bool
    ) {
        guard trackID == currentTrackID else { return }
        let key = lyricSecondaryKey(trackID: trackID, mode: mode)
        let retryCount = activeLyricSecondaryRequest?.retryCount ??
            lyricSecondaryRetryCounts[key] ?? 0
        let action = LyricSecondaryRetryPolicy.action(
            explicitlyUnavailable: explicitlyUnavailable,
            retryCount: retryCount
        )
        cancelLyricSecondaryTimeouts()
        activeLyricSecondaryRequest = nil
        lyricSecondaryTransfer = nil

        switch action {
        case .retry:
            let nextRetry = retryCount + 1
            lyricSecondaryRetryCounts[key] = nextRetry
            if !pendingLyricSecondaryModes.contains(mode) {
                pendingLyricSecondaryModes.insert(mode, at: 0)
            }
            setLyricSecondaryState(.loading, mode: mode)
            log(
                "[Lyrics-iOS] secondary retry mode=\(mode.rawValue) " +
                    "attempt=\(nextRetry) reason=\(reason)"
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self, self.currentTrackID == trackID else { return }
                self.requestNextLyricSecondaryIfPossible()
            }
            return
        case .markUnavailable:
            completedLyricSecondaryKeys.insert(key)
            lyricSecondaryRetryCounts.removeValue(forKey: key)
            setLyricSecondaryState(.unavailable, mode: mode)
        case .markFailed:
            requestedLyricSecondaryKeys.remove(key)
            lyricSecondaryRetryCounts.removeValue(forKey: key)
            lyricSecondaryFailureCooldownUntilMs[key] =
                currentTimeMs() + LYRIC_SECONDARY_FAILURE_COOLDOWN_MS
            setLyricSecondaryState(.failed(reason: reason), mode: mode)
        }
        log(
            "[Lyrics-iOS] secondary finished mode=\(mode.rawValue) " +
                "state=\(action) reason=\(reason)"
        )
        requestNextLyricSecondaryIfPossible()
    }

    func beginSeeking() {
        guard durationMs > 0 else { return }
        isSeeking = true
        seekPositionMs = displayPositionMs
    }

    func updateSeekPosition(_ value: Double) {
        let maximum = max(durationMs, 0)
        seekPositionMs = Int64(value.rounded()).clamped(to: 0...maximum)
    }

    func finishSeeking() {
        guard isSeeking else { return }
        let targetPosition = seekPositionMs.clamped(
            to: 0...max(durationMs, 0)
        )
        positionMs = targetPosition
        displayPositionMs = targetPosition
        basePlaybackPositionMs = targetPosition
        playbackAnchorElapsedMs = monotonicTimeMs()
        isSeeking = false
        resetCurrentWordFence()
        log("[iOS][Seek] user set position=\(targetPosition)")
        updateLiveActivity(force: true, reason: "seek")
        seek(to: targetPosition)
        refreshPlaybackState(after: 0.5)
    }

    func beginVolumeSeeking() {
        guard volumeMax > 0 else { return }
        if !isVolumeSeeking {
            volumeSeekValue = volumeCurrent
            pendingRemoteVolumeValue = nil
        }
        isVolumeSeeking = true
        log("[VOL-iOS] drag begin value=\(volumeSeekValue)")
    }

    func updateVolumeSeekValue(_ value: Double) {
        if !isVolumeSeeking {
            beginVolumeSeeking()
        }
        let targetVolume = Int(value.rounded()).clamped(to: 0...max(volumeMax, 0))
        guard targetVolume != volumeSeekValue else { return }
        volumeSeekValue = targetVolume
        log("[VOL-iOS] drag value=\(targetVolume)")
        requestSetVolume(targetVolume, reason: "drag", forceFinal: false)
    }

    func finishVolumeSeeking() {
        guard volumeMax > 0 else { return }
        let targetVolume = volumeSeekValue.clamped(to: 0...volumeMax)
        volumeCurrent = targetVolume
        isVolumeSeeking = false
        log("[VOL-iOS] drag end value=\(targetVolume)")
        requestSetVolume(targetVolume, reason: "final", forceFinal: true)
        refreshVolume(after: 0.3)
    }

    private func requestSetVolume(_ value: Int, reason: String, forceFinal: Bool) {
        guard volumeMax > 0 else {
            log("[VOL-iOS] dropped reason=no volume max value=\(value)")
            return
        }
        let targetVolume = value.clamped(to: 0...volumeMax)
        let nowMs = currentTimeMs()
        if !forceFinal, lastVolumeRequestedValue == targetVolume {
            return
        }
        lastVolumeRequestedValue = targetVolume

        guard sonyPeripheral?.state == .connected else {
            clearPendingVolume()
            log("[VOL-iOS] dropped reason=\(isReconnectInProgress ? "reconnecting" : "not connected") value=\(targetVolume)")
            return
        }
        guard sonyCommandCharacteristic != nil else {
            clearPendingVolume()
            log("[VOL-iOS] dropped reason=\(isReconnectInProgress ? "reconnecting" : "characteristic not ready") value=\(targetVolume)")
            return
        }

        let throttleRemainingMs = VOLUME_SEND_THROTTLE_MS - (nowMs - lastVolumeSendAtMs)
        if !forceFinal, throttleRemainingMs > 0 {
            setPendingVolume(targetVolume, reason: reason, isFinal: false, nowMs: nowMs)
            log("[VOL-iOS] send throttled value=\(targetVolume)")
            schedulePendingVolumeFlush(afterMs: throttleRemainingMs)
            return
        }

        if volumeWriteInFlightSeq != nil ||
            !commandWriteInflight.isEmpty ||
            !pendingCommandWrites.isEmpty {
            setPendingVolume(targetVolume, reason: reason, isFinal: forceFinal, nowMs: nowMs)
            log(
                "[VOL-iOS] send throttled value=\(targetVolume) " +
                    "reason=\(volumeWriteInFlightSeq == nil ? "command in flight" : "volume in flight")"
            )
            return
        }

        sendSetVolumeNow(value: targetVolume, reason: reason, forceFinal: forceFinal)
    }

    private func sendSetVolumeNow(value: Int, reason: String, forceFinal: Bool) {
        let seq = nextCommandSeq()
        let startMs = currentTimeMs()
        let payload: [String: Any] = [
            "cmd": "SET_VOLUME",
            "volume": value,
            "time": startMs,
            "seq": seq
        ]

        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            log("[VOL-iOS] dropped reason=encode failed value=\(value)")
            return
        }
        guard isConnectionHealthyOrSuspect else {
            log("[VOL-iOS] dropped reason=unhealthy health=\(connectionHealthState) value=\(value)")
            return
        }
        guard let sonyPeripheral,
              sonyPeripheral.state == .connected else {
            log("[VOL-iOS] dropped reason=\(isReconnectInProgress ? "reconnecting" : "not connected") value=\(value)")
            return
        }
        guard sonyCommandCharacteristic != nil else {
            log("[VOL-iOS] dropped reason=\(isReconnectInProgress ? "reconnecting" : "characteristic not ready") value=\(value)")
            return
        }

        enqueueCommandWrite(
            PendingCommandWrite(
                seq: seq,
                cmd: "SET_VOLUME",
                data: data,
                payloadText: text,
                enqueuedAtMs: startMs,
                isControl: true,
                volumeValue: value,
                volumeReason: forceFinal ? "final" : reason
            )
        )
    }

    private func setPendingVolume(_ value: Int, reason: String, isFinal: Bool, nowMs: Int64) {
        latestPendingVolumeValue = value
        latestPendingVolumeReason = reason
        latestPendingVolumeIsFinal = isFinal
        latestPendingVolumeCreatedAtMs = nowMs
    }

    private func clearPendingVolume() {
        latestPendingVolumeValue = nil
        latestPendingVolumeReason = ""
        latestPendingVolumeIsFinal = false
        latestPendingVolumeCreatedAtMs = 0
        volumeThrottleWorkItem?.cancel()
        volumeThrottleWorkItem = nil
    }

    private func schedulePendingVolumeFlush(afterMs: Int64) {
        volumeThrottleWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            self?.flushPendingVolumeIfPossible()
        }
        volumeThrottleWorkItem = workItem
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(max(afterMs, 0)) / 1_000.0,
            execute: workItem
        )
    }

    private func flushPendingVolumeIfPossible() {
        guard let value = latestPendingVolumeValue else { return }
        let nowMs = currentTimeMs()
        guard nowMs - latestPendingVolumeCreatedAtMs <= VOLUME_PENDING_TTL_MS else {
            log("[VOL-iOS] dropped reason=pending expired value=\(value)")
            clearPendingVolume()
            return
        }
        guard sonyPeripheral?.state == .connected else {
            log("[VOL-iOS] dropped reason=not connected value=\(value)")
            clearPendingVolume()
            return
        }
        guard isConnectionHealthyOrSuspect else {
            log("[VOL-iOS] dropped reason=unhealthy health=\(connectionHealthState) value=\(value)")
            clearPendingVolume()
            return
        }
        guard sonyCommandCharacteristic != nil else {
            log("[VOL-iOS] dropped reason=characteristic not ready value=\(value)")
            clearPendingVolume()
            return
        }
        guard volumeWriteInFlightSeq == nil,
              commandWriteInflight.isEmpty,
              pendingCommandWrites.isEmpty else {
            return
        }
        if !latestPendingVolumeIsFinal {
            let throttleRemainingMs = VOLUME_SEND_THROTTLE_MS - (nowMs - lastVolumeSendAtMs)
            if throttleRemainingMs > 0 {
                schedulePendingVolumeFlush(afterMs: throttleRemainingMs)
                return
            }
            guard isVolumeSeeking else {
                log("[VOL-iOS] dropped reason=drag ended value=\(value)")
                clearPendingVolume()
                return
            }
        }

        let reason = latestPendingVolumeReason.isEmpty ? "drag" : latestPendingVolumeReason
        let isFinal = latestPendingVolumeIsFinal
        clearPendingVolume()
        sendSetVolumeNow(value: value, reason: reason, forceFinal: isFinal)
    }

    private func handleVolumeWriteCompletion(seq: UInt64, error: Error?, costMs: Int64) {
        if volumeWriteInFlightSeq == seq {
            volumeWriteInFlightSeq = nil
        }
        let errorText = error?.localizedDescription ?? "nil"
        log("[VOL-iOS] didWrite seq=\(seq) costMs=\(costMs) error=\(errorText)")
        flushPendingVolumeIfPossible()
    }

    private func beginSonyScan(
        reason: String,
        isAutoReconnect: Bool,
        force: Bool
    ) {
        shouldScanWhenPoweredOn = false
        log("[BLE-iOS] stop previous scan")
        centralManager.stopScan()
        if force {
            connectTimeoutWorkItem?.cancel()
            connectTimeoutWorkItem = nil
            isConnectingToSony = false
            updateAutoReconnectDebugFields()
        }
        if reason.hasPrefix("foreground") {
            reconnectStartedAtMs = currentTimeMs()
        }
        if sonyPeripheral?.state == .disconnected {
            sonyPeripheral = nil
        }

        if !force,
           let sonyPeripheral,
           sonyPeripheral.state == .connected,
           sonyCommandCharacteristic != nil,
           sonyStatusCharacteristic != nil,
           isConnectionHealthyOrSuspect {
            log("[BLE-Reconnect] scan skipped reason=already usable health=\(connectionHealthState)")
            setAutoReconnectState(.connected)
            syncAfterReconnect(reason: "already connected")
            return
        }
        if !force, isConnectingToSony {
            log("[BLE-Reconnect] scan skipped reason=connect in flight")
            return
        }

        if force,
           let sonyPeripheral,
           sonyPeripheral.state != .disconnected {
            log(
                "[BLE-iOS] cancel previous peripheral " +
                    "id=\(sonyPeripheral.identifier) state=\(sonyPeripheral.state.rawValue)"
            )
            centralManager.cancelPeripheralConnection(sonyPeripheral)
        }

        sonyPeripheral = force ? nil : sonyPeripheral
        sonyCommandCharacteristic = nil
        sonyStatusCharacteristic = nil
        firstConnectionReadyAtMs = 0
        albumArtReceiver.resetForReconnect(reason: force ? "hard reconnect" : "reconnect")
        resetRemoteLogTransfer()
        resetMediaFieldDumpTransfer()
        resetTrackInfoTransfer()
        resetFullLyricsTransfer()
        requestedFullLyricsTrackIDs.removeAll()
        isRemoteLogTransferInProgress = false
        isMediaFieldDumpReceiving = false
        mediaFieldDumpProgressText = ""
        scanTimeoutWorkItem?.cancel()
        currentScanIsAutoReconnect = isAutoReconnect
        scanStartedAtMs = currentTimeMs()
        setAutoReconnectState(.scanning)
        updateAutoReconnectDebugFields()
        centralManager.scanForPeripherals(
            withServices: [BLEUUIDs.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        setStatus("正在搜索 Sony")
        log("[BLE] scan started")
        log(
            "[BLE-Reconnect] scan start services=\(BLEUUIDs.service.uuidString) " +
                "reason=\(reason) attempt=\(connectionAttemptId.uuidString)"
        )
        log("[BLE-iOS] start scan services=\(BLEUUIDs.service.uuidString)")

        let timeoutWorkItem = DispatchWorkItem { [weak self] in
            guard let self,
                  self.sonyPeripheral == nil || self.sonyPeripheral?.state == .disconnected else { return }
            self.centralManager.stopScan()
            self.autoReconnectLastScanCostMs = self.currentTimeMs() - self.scanStartedAtMs
            self.setStatus(self.autoReconnectEnabled ? "未找到，稍后重试" : "未连接")
            self.setAutoReconnectState(.failed)
            self.updateAutoReconnectDebugFields()
            self.log("[BLE] scan timeout: SonyPlayerAgent not found")
            self.log("[BLE-Reconnect] scan timeout attempt=\(self.autoReconnectAttempt)")
            self.log("[BLE-iOS] scan timeout status reset")
            if isAutoReconnect {
                self.scheduleReconnect(reason: "scan timeout", immediate: false)
            }
        }
        scanTimeoutWorkItem = timeoutWorkItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: timeoutWorkItem)
    }

    private func refreshPlaybackState(after delay: TimeInterval) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.sendGetPlaybackState()
        }
    }

    private func refreshVolume(after delay: TimeInterval) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.sendGetVolume()
        }
    }

    private func configurePeripheralService() {
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        subscribedCentrals.removeAll()

        let command = CBMutableCharacteristic(
            type: BLEUUIDs.command,
            properties: [.notify, .read],
            value: nil,
            permissions: [.readable]
        )
        let status = CBMutableCharacteristic(
            type: BLEUUIDs.status,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let service = CBMutableService(type: BLEUUIDs.service, primary: true)
        service.characteristics = [command, status]

        commandCharacteristic = command
        statusCharacteristic = status
        peripheralManager.add(service)
        setStatus("Adding GATT service")
        log("[BLE-B] adding service and characteristics")
    }

    private func startAdvertisingIfReady() {
        guard shouldStartAdvertising,
              peripheralManager.state == .poweredOn,
              commandCharacteristic != nil,
              statusCharacteristic != nil else {
            return
        }

        peripheralManager.startAdvertising([
            CBAdvertisementDataLocalNameKey: BLEUUIDs.iosControllerName,
            CBAdvertisementDataServiceUUIDsKey: [BLEUUIDs.service]
        ])
        setStatus("Starting advertising")
        log("[BLE-B] advertising name=\(BLEUUIDs.iosControllerName)")
    }

    private func log(_ message: String) {
        AppLogStore.shared.append(message)
        #if DEBUG
        if message.hasPrefix("[TrackMatrixV31]") ||
            message.hasPrefix("[LyricTrace-iOS]") ||
            message.hasPrefix("[BLE-Reconnect]") ||
            message.hasPrefix("[BLE-Health]") ||
            message.hasPrefix("[Reconnect]") ||
            message.hasPrefix("[BLE-iOS]") ||
            message.hasPrefix("[SmokeTest]") ||
            message.hasPrefix("[AppMode]") {
            AppLogStore.shared.appendTimeline(message)
        }
        #endif
        let timestamp = Date()
        uiLogQueue.async { [weak self] in
            guard let self, self.isUILogStreamingEnabled else { return }
            self.pendingUILogLines.append(
                "[\(Self.logTimestampFormatter.string(from: timestamp))] \(message)"
            )
            guard !self.isUILogFlushScheduled else { return }
            self.isUILogFlushScheduled = true
            self.uiLogQueue.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                guard let self else { return }
                let batch = self.pendingUILogLines
                self.pendingUILogLines.removeAll(keepingCapacity: true)
                self.isUILogFlushScheduled = false
                guard !batch.isEmpty else { return }
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.logs.append(contentsOf: batch)
                    if self.logs.count > 300 {
                        self.logs.removeFirst(self.logs.count - 300)
                    }
                }
            }
        }
    }

    private func ctrlLog(_ message: String) {
        log(message)
        print(message)
    }

    private func nextCommandSeq() -> UInt64 {
        commandSeq += 1
        return commandSeq
    }

    private func currentTimeMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }

    private func monotonicTimeMs() -> Int64 {
        Int64((ProcessInfo.processInfo.systemUptime * 1_000).rounded())
    }

    private func resetClockSync(reason: String) {
        clockSyncBootstrapWorkItems.forEach { $0.cancel() }
        clockSyncBootstrapWorkItems.removeAll()
        clockSyncRefreshWorkItem?.cancel()
        clockSyncRefreshWorkItem = nil
        clockSyncProbes.removeAll()
        clockSynchronizer.reset()
        sonyUnixMinusElapsedMs = nil
        sonyClockOffsetMs = nil
        lyricAutomaticCompensationMs = 0
        lyricClockBestRoundTripMs = 0
        lyricClockOffsetJitterMs = 0
        lyricClockSampleCount = 0
        lyricClockSyncConfident = false
        lastStaleRemoteAnchorLogAtMs = 0
        log("[ClockSync] reset reason=\(reason)")
    }

    private func pauseClockSyncScheduling(reason: String) {
        clockSyncBootstrapWorkItems.forEach { $0.cancel() }
        clockSyncBootstrapWorkItems.removeAll()
        clockSyncRefreshWorkItem?.cancel()
        clockSyncRefreshWorkItem = nil
        clockSyncProbes.removeAll()
        log("[ClockSync] scheduling paused reason=\(reason)")
    }

    private func scheduleClockSyncBootstrap(reason: String) {
        guard appLifecycleState == "active", serverSupportsClockSyncV1 else { return }
        scheduleClockSyncProbes(
            count: CLOCK_SYNC_BOOTSTRAP_SAMPLE_COUNT,
            reason: reason
        )
        scheduleClockSyncRefresh()
    }

    private func scheduleClockSyncProbes(count: Int, reason: String) {
        guard appLifecycleState == "active", count > 0 else { return }
        clockSyncBootstrapWorkItems.forEach { $0.cancel() }
        clockSyncBootstrapWorkItems.removeAll()
        for index in 0..<count {
            let item = DispatchWorkItem { [weak self] in
                guard let self, self.appLifecycleState == "active" else { return }
                self.sendClockSyncProbe(reason: reason, sample: index + 1, total: count)
            }
            clockSyncBootstrapWorkItems.append(item)
            DispatchQueue.main.asyncAfter(
                deadline: .now() +
                    Double(Int64(index) * CLOCK_SYNC_SAMPLE_SPACING_MS) / 1_000.0,
                execute: item
            )
        }
    }

    private func scheduleClockSyncRefresh() {
        clockSyncRefreshWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.appLifecycleState == "active",
                  self.serverSupportsClockSyncV1 else { return }
            self.scheduleClockSyncProbes(
                count: CLOCK_SYNC_REFRESH_SAMPLE_COUNT,
                reason: "periodic"
            )
            self.scheduleClockSyncRefresh()
        }
        clockSyncRefreshWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(CLOCK_SYNC_REFRESH_INTERVAL_MS) / 1_000.0,
            execute: item
        )
    }

    private func sendClockSyncProbe(reason: String, sample: Int, total: Int) {
        guard appLifecycleState == "active",
              serverSupportsClockSyncV1,
              sonyCharacteristicsReady else { return }
        let sequence = nextCommandSeq()
        let sentAtElapsedMs = monotonicTimeMs()
        let probe = ClockSyncProbe(
            sequence: sequence,
            clientSendElapsedMs: sentAtElapsedMs,
            clientSendDate: Date()
        )
        clockSyncProbes[String(sequence)] = probe
        log(
            "[ClockSync] probe sent seq=\(sequence) reason=\(reason) " +
                "sample=\(sample)/\(total)"
        )
        sendCommand(
            cmd: "PING",
            extra: [
                "clockSyncV1": true,
                "clientSendElapsedMs": sentAtElapsedMs
            ],
            seq: sequence
        )
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(CLOCK_SYNC_PROBE_TTL_MS) / 1_000.0
        ) { [weak self] in
            guard let self,
                  self.clockSyncProbes[String(sequence)]?.sequence == sequence else {
                return
            }
            self.clockSyncProbes.removeValue(forKey: String(sequence))
            self.log("[ClockSync] probe expired seq=\(sequence)")
        }
    }

    @discardableResult
    private func handleClockSyncPong(_ object: [String: Any]) -> Bool {
        let sequence = Self.int64Value(object["seq"])
        guard sequence > 0,
              let probe = clockSyncProbes.removeValue(forKey: String(sequence)) else {
            return false
        }
        let receivedAtElapsedMs = monotonicTimeMs()
        let receivedAt = Date()
        let echoedClientElapsedMs = Self.int64Value(object["clientSendElapsedMs"])
        let serverReceiveElapsedMs = Self.int64Value(object["serverReceiveElapsedMs"])
        let serverSendElapsedMs = Self.int64Value(object["serverSendElapsedMs"])
        let sonyTimeMs = Self.int64Value(object["time"])
        if sonyTimeMs > 0 {
            let midpointMs = Int64(
                (probe.clientSendDate.timeIntervalSince1970 +
                    receivedAt.timeIntervalSince1970) * 500
            )
            sonyClockOffsetMs = sonyTimeMs - midpointMs
        }
        guard echoedClientElapsedMs == probe.clientSendElapsedMs,
              serverReceiveElapsedMs > 0,
              serverSendElapsedMs >= serverReceiveElapsedMs else {
            log("[ClockSync] legacy pong seq=\(sequence) clockOffsetMs=\(sonyClockOffsetMs ?? 0)")
            return true
        }
        if sonyTimeMs > 0 {
            sonyUnixMinusElapsedMs = sonyTimeMs - serverSendElapsedMs
        }
        let snapshot = clockSynchronizer.record(
            clientSendElapsedMs: probe.clientSendElapsedMs,
            serverReceiveElapsedMs: serverReceiveElapsedMs,
            serverSendElapsedMs: serverSendElapsedMs,
            clientReceiveElapsedMs: receivedAtElapsedMs
        )
        if let snapshot {
            lyricClockBestRoundTripMs = snapshot.bestRoundTripMs
            lyricClockOffsetJitterMs = snapshot.offsetJitterMs
            lyricClockSampleCount = snapshot.sampleCount
            lyricClockSyncConfident = snapshot.isConfident
            log(
                "[ClockSync] pong seq=\(sequence) bestRttMs=\(snapshot.bestRoundTripMs) " +
                    "offsetMs=\(Int64(snapshot.localMinusServerMs.rounded())) " +
                    "jitterMs=\(snapshot.offsetJitterMs) samples=\(snapshot.sampleCount) " +
                    "confident=\(snapshot.isConfident)"
            )
        }
        return true
    }

    private func isInStartupLoadWindow() -> Bool {
        guard firstConnectionReadyAtMs > 0 else { return false }
        return currentTimeMs() - firstConnectionReadyAtMs < 3_000
    }

    private var sonyCharacteristicsReady: Bool {
        sonyPeripheral?.state == .connected &&
            sonyCommandCharacteristic != nil &&
            sonyStatusCharacteristic != nil
    }

    private var isSonyHealthyForControls: Bool {
        sonyCharacteristicsReady &&
            connectionStatus == "已连接" &&
            isConnectionHealthyOrSuspect
    }

    private var isConnectionHealthyOrSuspect: Bool {
        connectionHealthState == ConnectionHealthState.healthy.rawValue ||
            connectionHealthState == ConnectionHealthState.suspect.rawValue
    }

    private func isControlCommand(_ cmd: String) -> Bool {
        switch cmd {
        case "PLAY_PAUSE", "NEXT", "PREVIOUS", "VOLUME_UP", "VOLUME_DOWN", "SEEK_TO", "SET_VOLUME":
            return true
        default:
            return false
        }
    }

    private func peripheralStateText(_ peripheral: CBPeripheral?) -> String {
        guard let peripheral else { return "nil" }
        switch peripheral.state {
        case .connected:
            return "connected"
        case .connecting:
            return "connecting"
        case .disconnected:
            return "disconnected"
        case .disconnecting:
            return "disconnecting"
        @unknown default:
            return "unknown"
        }
    }

    private func updateConnectionHealthDebugFields() {
        if let lastStatusNotifyAt {
            connectionHealthLastNotifyAgeMs = Int64(Date().timeIntervalSince(lastStatusNotifyAt) * 1_000)
        } else {
            connectionHealthLastNotifyAgeMs = -1
        }
        if let lastHealthProbeAt {
            connectionHealthLastProbeAtText = "\(Int64(Date().timeIntervalSince(lastHealthProbeAt) * 1_000))ms ago"
        } else {
            connectionHealthLastProbeAtText = "-"
        }
        connectionHealthProbeInFlight = healthProbeStartedAt != nil
        connectionHealthAttemptId = connectionAttemptId.uuidString
        connectionHealthPeripheralState = peripheralStateText(sonyPeripheral)
        connectionHealthCharacteristicReady = sonyCharacteristicsReady
    }

    private func setConnectionHealth(_ state: ConnectionHealthState, reason: String) {
        if connectionHealthState != state.rawValue {
            log("[BLE-Health] state=\(state.rawValue) reason=\(reason)")
            if state == .suspect {
                connectionHealthSuspectCount += 1
            } else if state == .stale {
                connectionHealthStaleCount += 1
            }
        }
        connectionHealthState = state.rawValue
        updateConnectionHealthDebugFields()
        refreshLiveActivityControlStatus()
        refreshConnectionDisplayState(reason: "health \(state.rawValue) \(reason)")
    }

    private func refreshConnectionDisplayState(reason: String, explicitDisconnect: Bool = false) {
        let desired: ConnectionDisplayState
        if connectionHealthState == ConnectionHealthState.stale.rawValue || isReconnectInProgress {
            desired = .reconnecting
        } else if connectionStatus == "已连接",
                  connectionHealthState == ConnectionHealthState.healthy.rawValue ||
                    connectionHealthState == ConnectionHealthState.suspect.rawValue {
            desired = .connected
        } else if connectionStatus == "正在重新连接" ||
                    connectionStatus == "正在搜索 Sony" ||
                    connectionStatus == "正在连接 Sony" ||
                    connectionStatus == "正在恢复服务" ||
                    connectionStatus == "连接中" ||
                    connectionStatus == "扫描中" ||
                    connectionStatus == "未找到，稍后重试" {
            desired = .reconnecting
        } else {
            desired = .disconnected
        }
        setConnectionDisplayState(desired, reason: reason, explicitDisconnect: explicitDisconnect)
    }

    private func setConnectionDisplayState(
        _ state: ConnectionDisplayState,
        reason: String,
        explicitDisconnect: Bool = false
    ) {
        connectionDisplayWorkItem?.cancel()
        connectionDisplayWorkItem = nil
        if connectionDisplayState == state.rawValue { return }

        if state == .disconnected, !explicitDisconnect {
            let delayMs: Int64
            if connectionDisplayState == ConnectionDisplayState.connected.rawValue {
                let heldMs = Int64(Date().timeIntervalSince(connectionDisplayStateChangedAt) * 1_000)
                delayMs = max(
                    CONNECTION_DISPLAY_DISCONNECTED_CONFIRM_MS,
                    CONNECTION_DISPLAY_CONNECTED_MIN_HOLD_MS - heldMs
                )
            } else {
                delayMs = CONNECTION_DISPLAY_DISCONNECTED_CONFIRM_MS
            }
            let item = DispatchWorkItem { [weak self] in
                self?.applyConnectionDisplayState(.disconnected, reason: reason)
            }
            connectionDisplayWorkItem = item
            DispatchQueue.main.asyncAfter(
                deadline: .now() + Double(max(delayMs, 0)) / 1_000.0,
                execute: item
            )
            return
        }

        applyConnectionDisplayState(state, reason: reason)
    }

    private func applyConnectionDisplayState(_ state: ConnectionDisplayState, reason: String) {
        if connectionDisplayState != state.rawValue {
            connectionDisplayState = state.rawValue
            connectionDisplayStateChangedAt = Date()
            if state == .connected, connectionHealthState == ConnectionHealthState.suspect.rawValue {
                log("[BLE-UIState] display connected reason=health suspect hidden")
            } else {
                log("[BLE-UIState] display \(state.rawValue) reason=\(reason)")
            }
            switch state {
            case .connected:
                updateLiveActivity(force: false, reason: "connectionState")
            case .reconnecting:
                updateLiveActivity(force: true, reason: "connectionState")
            case .disconnected:
                updateLiveActivityDisconnected()
            }
        }
    }

    private func stopHealthMonitoring(reason: String) {
        healthCheckWorkItem?.cancel()
        healthCheckWorkItem = nil
        healthProbeTimeoutWorkItem?.cancel()
        healthProbeTimeoutWorkItem = nil
        subscribeNotifyTimeoutWorkItem?.cancel()
        subscribeNotifyTimeoutWorkItem = nil
        healthProbeCommandSeq = nil
        healthProbeStartedAt = nil
        healthProbeFailureCount = 0
        connectionHealthProbeInFlight = false
        lastStatusNotifyAt = nil
        lastPlaybackStateAt = nil
        lastSuccessfulWriteAt = nil
        lastNotifySubscribedAt = nil
        connectionReadyAt = nil
        log("[BLE-Health] stopped reason=\(reason)")
        updateConnectionHealthDebugFields()
    }

    private func startHealthMonitoring(reason: String) {
        healthCheckWorkItem?.cancel()
        guard appLifecycleState == "active" else {
            healthCheckWorkItem = nil
            log("[BLE-Health] start deferred appState=\(appLifecycleState) reason=\(reason)")
            return
        }
        log("[BLE-Health] started reason=\(reason)")
        updateConnectionHealthDebugFields()
        scheduleHealthTick()
    }

    private func scheduleHealthTick() {
        healthCheckWorkItem?.cancel()
        guard appLifecycleState == "active" else {
            healthCheckWorkItem = nil
            return
        }
        let item = DispatchWorkItem { [weak self] in
            self?.runHealthTick()
        }
        healthCheckWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(CONNECTION_HEALTH_TICK_MS) / 1_000.0,
            execute: item
        )
    }

    private func runHealthTick() {
        updateConnectionHealthDebugFields()
        guard appLifecycleState == "active" else {
            healthCheckWorkItem = nil
            log("[BLE-Health] tick suspended appState=\(appLifecycleState)")
            return
        }
        guard centralManager.state == .poweredOn else {
            setConnectionHealth(.disconnected, reason: "bluetooth not powered")
            return
        }
        guard sonyPeripheral?.state == .connected else {
            setConnectionHealth(.disconnected, reason: "peripheral not connected")
            return
        }
        guard sonyCommandCharacteristic != nil else {
            performHardReconnect(reason: "characteristic nil while connected", manual: false)
            return
        }
        guard sonyStatusCharacteristic != nil else {
            performHardReconnect(reason: "status characteristic nil while connected", manual: false)
            return
        }

        let now = Date()
        if let lastStatusNotifyAt {
            let ageMs = Int64(now.timeIntervalSince(lastStatusNotifyAt) * 1_000)
            connectionHealthLastNotifyAgeMs = ageMs
            let suspectThresholdMs = isPlaying
                ? CONNECTION_HEALTH_PLAYING_SUSPECT_MS
                : CONNECTION_HEALTH_PAUSED_SUSPECT_MS
            if ageMs > suspectThresholdMs {
                log("[BLE-Health] suspect no notify ageMs=\(ageMs)")
                setConnectionHealth(.suspect, reason: "notify suspect ageMs=\(ageMs)")
                sendHealthProbeIfNeeded(reason: "no notify ageMs=\(ageMs)")
            } else {
                setConnectionHealth(.healthy, reason: "recent notify ageMs=\(ageMs)")
            }
        } else if let lastNotifySubscribedAt {
            let subscribeAgeMs = Int64(now.timeIntervalSince(lastNotifySubscribedAt) * 1_000)
            if subscribeAgeMs > CONNECTION_SUBSCRIBE_NOTIFY_TIMEOUT_MS {
                performHardReconnect(reason: "notify subscribe no status timeout", manual: false)
                return
            }
            setConnectionHealth(.suspect, reason: "waiting first notify ageMs=\(subscribeAgeMs)")
        }
        scheduleHealthTick()
    }

    private func sendHealthProbeIfNeeded(reason: String) {
        guard appLifecycleState == "active" else {
            log("[BLE-Health] probe deferred appState=\(appLifecycleState) trigger=\(reason)")
            return
        }
        let now = Date()
        if healthProbeCommandSeq != nil {
            log("[BLE-Health] probe skipped reason=in flight trigger=\(reason)")
            return
        }
        if let lastHealthProbeAt,
           Date().timeIntervalSince(lastHealthProbeAt) * 1_000 < Double(CONNECTION_HEALTH_TICK_MS) {
            log("[BLE-Health] probe skipped reason=cooldown trigger=\(reason)")
            return
        }
        guard sonyCharacteristicsReady else {
            performHardReconnect(reason: "probe skipped not ready", manual: false)
            return
        }
        lastHealthProbeAt = now
        let seq = nextCommandSeq()
        healthProbeCommandSeq = seq
        updateConnectionHealthDebugFields()
        log("[BLE-Health] probe queued seq=\(seq) reason=\(reason)")
        sendCommand(cmd: serverSupportsPing ? "PING" : "GET_PLAYBACK_STATE", seq: seq)
    }

    private func startHealthProbeResponseTimeout(seq: UInt64, command: String) {
        guard healthProbeCommandSeq == seq,
              healthProbeStartedAt == nil,
              appLifecycleState == "active" else { return }
        let startedAt = Date()
        healthProbeStartedAt = startedAt
        healthPingClockProbeStartedAt = command == "PING" ? startedAt : nil
        log("[BLE-Health] probe sent seq=\(seq) cmd=\(command)")
        updateConnectionHealthDebugFields()
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.appLifecycleState == "active",
                  self.healthProbeCommandSeq == seq,
                  let healthProbeStartedAt = self.healthProbeStartedAt,
                  abs(healthProbeStartedAt.timeIntervalSince(startedAt)) < 0.001 else {
                return
            }
            let costMs = Int64(Date().timeIntervalSince(startedAt) * 1_000)
            self.log("[BLE-Health] probe timeout costMs=\(costMs)")
            self.healthProbeCommandSeq = nil
            self.healthProbeStartedAt = nil
            self.healthPingClockProbeStartedAt = nil
            self.healthProbeTimeoutWorkItem = nil
            self.healthProbeFailureCount += 1
            if self.healthProbeFailureCount >= 2 {
                self.setConnectionHealth(.stale, reason: "probe timeout x2")
                self.performHardReconnect(reason: "health probe timeout x2", manual: false)
            } else {
                self.setConnectionHealth(.suspect, reason: "probe timeout retry pending")
            }
        }
        healthProbeTimeoutWorkItem?.cancel()
        healthProbeTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(CONNECTION_HEALTH_PROBE_TIMEOUT_MS) / 1_000.0,
            execute: item
        )
    }

    private func markStatusNotifyReceived(type: String) {
        let now = Date()
        let completesForegroundValidation = foregroundValidationPending &&
            appLifecycleState == "active"
        let highVolume = isHighVolumeNotifyType(type)
        if !highVolume {
            log("[BLE-Health] notify received type=\(type)")
        }
        if let lastStatusNotifyAt {
            let gapMs = Int64(now.timeIntervalSince(lastStatusNotifyAt) * 1_000)
            connectionHealthMaxNotifyGapMs = max(connectionHealthMaxNotifyGapMs, gapMs)
        }
        lastStatusNotifyAt = now
        if type == "playbackState" {
            lastPlaybackStateAt = now
        }
        if let probeStartedAt = healthProbeStartedAt {
            let ageMs = Int64(now.timeIntervalSince(probeStartedAt) * 1_000)
            log("[BLE-Health] probe success ageMs=\(ageMs) type=\(type)")
            healthProbeCommandSeq = nil
            healthProbeStartedAt = nil
            healthProbeFailureCount = 0
            healthProbeTimeoutWorkItem?.cancel()
            healthProbeTimeoutWorkItem = nil
        }
        if lastNotifySubscribedAt != nil {
            subscribeNotifyTimeoutWorkItem?.cancel()
            subscribeNotifyTimeoutWorkItem = nil
        }
        if highVolume,
           connectionHealthState == ConnectionHealthState.healthy.rawValue {
            if completesForegroundValidation {
                completeForegroundLinkValidation(type: type)
            }
            return
        }
        setConnectionHealth(.healthy, reason: "notify type=\(type)")
        if completesForegroundValidation {
            completeForegroundLinkValidation(type: type)
        }
    }

    private func isHighVolumeNotifyType(_ type: String) -> Bool {
        type == "albumArtBinaryChunk" ||
            type == "fullLyricsBinaryChunk" ||
            type == "fullLyricsChunk" ||
            type == "historyPayloadChunk" ||
            type == "lyricSecondaryPart" ||
            type == "mediaFieldDumpChunk" ||
            type == "logChunk" ||
            type == "currentWord" ||
            type == "link"
    }

    private func setAutoReconnectState(_ state: AutoReconnectState) {
        autoReconnectState = state.rawValue
        if state == .reconnectScheduled {
            if reconnectScheduledAt == nil {
                reconnectScheduledAt = Date()
            }
            scheduleReconnectStuckCheck()
        } else {
            reconnectStuckCheckWorkItem?.cancel()
            reconnectStuckCheckWorkItem = nil
            if state != .failed {
                reconnectScheduledAt = nil
                reconnectScheduledDelayMs = 0
            }
        }
        updateAutoReconnectDebugFields()
        refreshConnectionDisplayState(reason: "autoReconnectState \(state.rawValue)")
    }

    private func reconnectDelayMs(for attempt: Int) -> Int64 {
        switch attempt {
        case 0...1:
            return 500
        case 2:
            return 1_000
        case 3:
            return 2_000
        case 4:
            return 4_000
        default:
            return 8_000
        }
    }

    private func cancelPendingReconnect(reason: String) {
        if reconnectWorkItem != nil {
            log("[BLE-Reconnect] work item cancelled reason=\(reason)")
        }
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        reconnectStuckCheckWorkItem?.cancel()
        reconnectStuckCheckWorkItem = nil
        autoReconnectNextRetryAt = nil
        reconnectScheduledAt = nil
        reconnectScheduledDelayMs = 0
        updateAutoReconnectDebugFields()
        log("[BLE-Reconnect] pending schedule cleared reason=\(reason)")
    }

    private func scheduleReconnect(reason: String, immediate: Bool) {
        log("[BLE-Reconnect] schedule requested reason=\(reason) immediate=\(immediate)")
        guard autoReconnectEnabled else {
            log("[BLE-Reconnect] schedule skipped reason=disabled trigger=\(reason)")
            return
        }
        guard !systemAutoReconnectInProgress else {
            log("[BLE-Reconnect] schedule skipped reason=system auto reconnecting trigger=\(reason)")
            return
        }
        guard centralManager.state == .poweredOn else {
            setAutoReconnectState(.failed)
            log("[BLE-Reconnect] schedule skipped reason=bluetooth state=\(centralManager.state.rawValue)")
            return
        }
        guard sonyPeripheral?.state != .connected ||
            sonyCommandCharacteristic == nil ||
            !isConnectionHealthyOrSuspect else {
            setAutoReconnectState(.connected)
            log("[BLE-Reconnect] schedule skipped reason=already connected trigger=\(reason)")
            return
        }
        if reconnectWorkItem != nil {
            log("[BLE-Reconnect] schedule skipped reason=existing work item trigger=\(reason)")
            updateAutoReconnectDebugFields()
            return
        }
        if isActiveReconnectState {
            log("[BLE-Reconnect] schedule skipped reason=active reconnect state=\(autoReconnectState) trigger=\(reason)")
            return
        }
        if AutoReconnectState(rawValue: autoReconnectState) == .reconnectScheduled {
            log("[BLE-Reconnect] recover missing work item state=reconnectScheduled")
        }

        reconnectWorkItem?.cancel()
        autoReconnectAttempt += 1
        let delayMs = immediate ? 0 : reconnectDelayMs(for: autoReconnectAttempt)
        autoReconnectNextRetryAt = Date().addingTimeInterval(TimeInterval(delayMs) / 1_000)
        reconnectScheduledAt = Date()
        reconnectScheduledDelayMs = delayMs
        setAutoReconnectState(.reconnectScheduled)
        log("[BLE-Reconnect] scheduled attempt=\(autoReconnectAttempt) delayMs=\(delayMs) reason=\(reason)")

        let scheduledAttempt = autoReconnectAttempt
        var item: DispatchWorkItem!
        item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            guard !item.isCancelled else {
                self.log("[BLE-Reconnect] work item cancelled attempt=\(scheduledAttempt)")
                return
            }
            self.log("[BLE-Reconnect] work item fired attempt=\(scheduledAttempt)")
            self.startAutoReconnect(reason: reason)
        }
        reconnectWorkItem = item
        updateAutoReconnectDebugFields()
        DispatchQueue.main.asyncAfter(deadline: .now() + TimeInterval(delayMs) / 1_000, execute: item)
    }

    private func startAutoReconnect(reason: String) {
        guard autoReconnectEnabled else { return }
        reconnectWorkItem = nil
        autoReconnectNextRetryAt = nil
        reconnectScheduledAt = nil
        reconnectScheduledDelayMs = 0
        updateAutoReconnectDebugFields()
        reconnectStartedAtMs = currentTimeMs()
        autoReconnectCount += 1
        log("[BLE-Reconnect] start attempt=\(autoReconnectAttempt) reason=\(reason)")

        guard centralManager.state == .poweredOn else {
            setAutoReconnectState(.failed)
            log("[BLE-Reconnect] failed reason=bluetooth state=\(centralManager.state.rawValue)")
            return
        }
        guard sonyPeripheral?.state != .connected ||
            sonyCommandCharacteristic == nil ||
            !isConnectionHealthyOrSuspect else {
            setAutoReconnectState(.connected)
            syncAfterReconnect(reason: "auto already connected")
            return
        }
        if shouldUseScanFirstReconnect(reason: reason) {
            log("[BLE-Reconnect] foreground strategy=scanFirst")
            log("[BLE-Reconnect] start scan immediately")
            log("[BLE-Reconnect] retrieve also attempted=false")
            beginSonyScan(reason: "scan first \(reason)", isAutoReconnect: true, force: false)
            return
        }
        if connectLastSonyPeripheralIfAvailable() {
            return
        }
        beginSonyScan(reason: "auto fallback scan", isAutoReconnect: true, force: false)
    }

    private func shouldUseScanFirstReconnect(reason: String) -> Bool {
        reason == "central poweredOn" ||
            reason == "app foreground" ||
            reason.hasPrefix("enabled")
    }

    private func connectLastSonyPeripheralIfAvailable() -> Bool {
        let retrieveStartedAtMs = currentTimeMs()
        guard let identifierText = UserDefaults.standard.string(forKey: AUTO_RECONNECT_LAST_PERIPHERAL_KEY),
              let identifier = UUID(uuidString: identifierText) else {
            autoReconnectLastRetrieveCostMs = currentTimeMs() - retrieveStartedAtMs
            log("[BLE-Reconnect] retrieve last id=nil found=false")
            return false
        }
        let peripherals = centralManager.retrievePeripherals(withIdentifiers: [identifier])
        autoReconnectLastRetrieveCostMs = currentTimeMs() - retrieveStartedAtMs
        log("[BLE-Reconnect] retrieve last id=\(identifierText) found=\(!peripherals.isEmpty)")
        guard let peripheral = peripherals.first else {
            log("[BLE-Reconnect] fallback scan reason=retrieve empty")
            return false
        }
        currentConnectIsAutoReconnect = true
        log("[BLE-Reconnect] retrieve connect start id=\(peripheral.identifier.uuidString)")
        connectSonyPeripheral(peripheral, reason: "retrieved peripheral", isRetrieved: true)
        log("[BLE-Reconnect] connect retrieved peripheral")
        return true
    }

    private func connectSonyPeripheral(
        _ peripheral: CBPeripheral,
        reason: String,
        isRetrieved: Bool = false
    ) {
        guard !isConnectingToSony else {
            log("[BLE-Reconnect] connect skipped reason=connect in flight")
            return
        }
        isConnectingToSony = true
        updateAutoReconnectDebugFields()
        connectTimeoutWorkItem?.cancel()
        connectStartedAtMs = currentTimeMs()
        currentConnectIsRetrievedPeripheral = isRetrieved
        sonyPeripheral = peripheral
        connectedDeviceName = peripheral.name ?? connectedDeviceName
        peripheral.delegate = self
        setAutoReconnectState(.connecting)
        setStatus("正在连接 Sony")
        log(
            "[BLE-Reconnect] connect id=\(peripheral.identifier.uuidString) " +
                "reason=\(reason) attempt=\(connectionAttemptId.uuidString)"
        )
        systemAutoReconnectInProgress = false
        systemAutoReconnectStartedAt = nil
        let options: [String: Any] = autoReconnectEnabled
            ? [CBConnectPeripheralOptionEnableAutoReconnect: true]
            : [:]
        centralManager.connect(peripheral, options: options)
        let timeoutMs = isRetrieved ? FAST_RETRIEVE_CONNECT_TIMEOUT_MS : DEFAULT_CONNECT_TIMEOUT_MS
        let timeout = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self,
                  let peripheral,
                  self.isConnectingToSony,
                  self.sonyPeripheral?.identifier == peripheral.identifier else { return }
            let costMs = self.currentTimeMs() - self.connectStartedAtMs
            self.autoReconnectLastConnectCostMs = costMs
            if isRetrieved {
                self.log("[BLE-Reconnect] retrieve connect fast timeout costMs=\(costMs)")
                self.log("[BLE-Reconnect] fallback scan reason=retrieve fast timeout")
            } else {
                self.log("[BLE-Reconnect] connect timeout id=\(peripheral.identifier.uuidString) costMs=\(costMs)")
            }
            self.isConnectingToSony = false
            self.updateAutoReconnectDebugFields()
            self.centralManager.cancelPeripheralConnection(peripheral)
            self.sonyPeripheral = nil
            self.setAutoReconnectState(.failed)
            self.beginSonyScan(
                reason: isRetrieved ? "retrieve fast timeout fallback" : "connect timeout fallback",
                isAutoReconnect: true,
                force: true
            )
        }
        connectTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + TimeInterval(timeoutMs) / 1_000, execute: timeout)
    }

    private func saveLastSonyPeripheral(_ peripheral: CBPeripheral) {
        let id = peripheral.identifier.uuidString
        UserDefaults.standard.set(id, forKey: AUTO_RECONNECT_LAST_PERIPHERAL_KEY)
        autoReconnectLastPeripheralId = id
        log("[BLE-Reconnect] saved last peripheral id=\(id)")
    }

    private func clearConnectionTransports(reason: String) {
        log("[BLE-Reconnect] clear characteristics reason=\(reason)")
        resetClockSync(reason: reason)
        coreBluetoothRestoreTimeoutWorkItem?.cancel()
        coreBluetoothRestoreTimeoutWorkItem = nil
        sonyCommandCharacteristic = nil
        sonyStatusCharacteristic = nil
        firstConnectionReadyAtMs = 0
        commandWriteTimeoutWorkItem?.cancel()
        commandWriteTimeoutWorkItem = nil
        consecutiveCommandWriteTimeouts = 0
        foregroundValidationTimeoutWorkItem?.cancel()
        foregroundValidationTimeoutWorkItem = nil
        foregroundInflightSettleWorkItem?.cancel()
        foregroundInflightSettleWorkItem = nil
        foregroundValidationPending = false
        foregroundValidationWaitingForInflight = false
        foregroundValidationCommandSeq = nil
        foregroundValidationStartedAt = nil
        coreBluetoothRestoreInProgress = false
        commandWriteInflight.removeAll()
        pendingCommandWrites.removeAll()
        liveActivityControlInFlightSeq = nil
        liveActivityControlWriteStartedAtMs = 0
        clearPendingVolume()
        albumArtReceiver.resetForConnectionLoss(reason: reason)
        resetRemoteLogTransfer()
        resetMediaFieldDumpTransfer()
        resetTrackInfoTransfer()
        resetFullLyricsTransfer()
        cancelLyricSecondaryTimeouts()
        lyricSecondaryDeferredRequestWorkItem?.cancel()
        lyricSecondaryDeferredRequestWorkItem = nil
        activeLyricSecondaryRequest = nil
        lyricSecondaryTransfer = nil
        pendingLyricSecondaryModes.removeAll()
        requestedLyricSecondaryKeys.removeAll()
        completedLyricSecondaryKeys.removeAll()
        lyricSecondaryRetryCounts.removeAll()
        lyricSecondaryFailureCooldownUntilMs.removeAll()
        translationLyricsState = .idle
        romanizationLyricsState = .idle
        isRemoteLogTransferInProgress = false
        isMediaFieldDumpReceiving = false
        mediaFieldDumpProgressText = ""
        log("[BLE-Reconnect] cancel in-flight albumArt/secondary")
    }

    private func scheduleCoreBluetoothRestoreTimeout(for peripheral: CBPeripheral) {
        coreBluetoothRestoreTimeoutWorkItem?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self,
                  let peripheral,
                  self.coreBluetoothRestoreInProgress,
                  self.sonyPeripheral?.identifier == peripheral.identifier,
                  self.sonyCommandCharacteristic == nil ||
                    self.sonyStatusCharacteristic == nil ||
                    self.lastNotifySubscribedAt == nil else {
                return
            }
            self.log("[BLE-Restore] timeout fallback scan")
            self.coreBluetoothRestoreInProgress = false
            self.clearConnectionTransports(reason: "CoreBluetooth restore timeout")
            self.setStatus("正在连接")
            self.setAutoReconnectState(.failed)
            self.beginSonyScan(
                reason: "restore timeout fallback",
                isAutoReconnect: true,
                force: true
            )
        }
        coreBluetoothRestoreTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(CORE_BLUETOOTH_RESTORE_TIMEOUT_MS) / 1_000,
            execute: timeout
        )
    }

    private func syncAfterReconnect(reason: String) {
        guard appLifecycleState == "active",
              sonyCharacteristicsReady,
              !foregroundValidationPending else {
            log(
                "[BLE-Reconnect] sync deferred reason=\(reason) " +
                    "appState=\(appLifecycleState) ready=\(sonyCharacteristicsReady) " +
                    "validating=\(foregroundValidationPending)"
            )
            return
        }
        let expectedAttemptId = connectionAttemptId
        let expectedLifecycleGeneration = lifecycleGeneration
        log("[BLE-Reconnect] sync playback state reason=\(reason)")
        sendGetPlaybackState()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self,
                  self.canRunDeferredConnectionSync(
                    attemptId: expectedAttemptId,
                    lifecycleGeneration: expectedLifecycleGeneration
                  ) else { return }
            self.log("[BLE-Reconnect] sync volume")
            self.sendGetVolume()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            guard let self,
                  self.canRunDeferredConnectionSync(
                    attemptId: expectedAttemptId,
                    lifecycleGeneration: expectedLifecycleGeneration
                  ) else { return }
            self.log("[BLE-Reconnect] defer full lyrics")
            self.requestFullLyricsIfNeeded(after: 0)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            guard let self,
                  self.canRunDeferredConnectionSync(
                    attemptId: expectedAttemptId,
                    lifecycleGeneration: expectedLifecycleGeneration
                  ) else { return }
            self.log("[BLE-Reconnect] defer secondary")
        }
    }

    private func canRunDeferredConnectionSync(
        attemptId: UUID,
        lifecycleGeneration: UInt64
    ) -> Bool {
        appLifecycleState == "active" &&
            connectionAttemptId == attemptId &&
            self.lifecycleGeneration == lifecycleGeneration &&
            sonyCharacteristicsReady &&
            !foregroundValidationPending
    }

    private func startupLoadRemainingDelay() -> TimeInterval {
        guard firstConnectionReadyAtMs > 0 else { return 0 }
        let elapsedMs = currentTimeMs() - firstConnectionReadyAtMs
        return TimeInterval(max(0, 3_000 - elapsedMs)) / 1_000
    }

    private func startMainHeartbeatDiagnostics() {
        lastMainHeartbeatAtMs = currentTimeMs()
        scheduleMainHeartbeatDiagnostics()
    }

    private func scheduleMainHeartbeatDiagnostics() {
        mainHeartbeatWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let now = self.currentTimeMs()
            let gapMs = now - self.lastMainHeartbeatAtMs
            let currentAppState = self.appLifecycleState
            if gapMs > 2_000 {
                if currentAppState == "active", self.lastMainHeartbeatAppState == "active" {
                    self.ctrlLog("[CTRL-iOS] main stall detected gapMs=\(gapMs) appState=\(currentAppState) timeMs=\(now)")
                } else {
                    self.ctrlLog("[APP-LIFECYCLE] execution gap gapMs=\(gapMs) appState=\(currentAppState) previousAppState=\(self.lastMainHeartbeatAppState)")
                }
            }
            self.lastMainHeartbeatAtMs = now
            self.lastMainHeartbeatAppState = currentAppState
            self.scheduleMainHeartbeatDiagnostics()
        }
        mainHeartbeatWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0, execute: item)
    }

    private func registerAppLifecycleDiagnostics() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
    }

    @objc private func appDidBecomeActive() {
        updateAppLifecycleState(.active, emitLog: true)
        handleAppForegroundReconnectCheck()
    }

    @objc private func appWillResignActive() {
        updateAppLifecycleState(.inactive, emitLog: true)
        pauseConnectionWatchdogsForLifecycle(reason: "will resign active")
    }

    @objc private func appDidEnterBackground() {
        updateAppLifecycleState(.background, emitLog: true)
        pauseConnectionWatchdogsForLifecycle(reason: "did enter background")
    }

    private func updateAppLifecycleState(
        _ state: UIApplication.State,
        emitLog: Bool
    ) {
        let value: String
        switch state {
        case .active:
            value = "active"
        case .inactive:
            value = "inactive"
        case .background:
            value = "background"
        @unknown default:
            value = "unknown"
        }
        if value != appLifecycleState {
            lifecycleGeneration &+= 1
        }
        if value == "background", appLifecycleState != "background" {
            backgroundEnteredAt = Date()
        }
        appLifecycleState = value
        updateProgressTimerState()
        if emitLog {
            ctrlLog("[APP-LIFECYCLE] \(value)")
        }
    }

    private func pauseConnectionWatchdogsForLifecycle(reason: String) {
        healthCheckWorkItem?.cancel()
        healthCheckWorkItem = nil
        healthProbeTimeoutWorkItem?.cancel()
        healthProbeTimeoutWorkItem = nil
        subscribeNotifyTimeoutWorkItem?.cancel()
        subscribeNotifyTimeoutWorkItem = nil
        commandWriteTimeoutWorkItem?.cancel()
        commandWriteTimeoutWorkItem = nil
        foregroundValidationTimeoutWorkItem?.cancel()
        foregroundValidationTimeoutWorkItem = nil
        foregroundInflightSettleWorkItem?.cancel()
        foregroundInflightSettleWorkItem = nil
        foregroundValidationPending = false
        foregroundValidationWaitingForInflight = false
        foregroundValidationCommandSeq = nil
        foregroundValidationStartedAt = nil
        healthProbeCommandSeq = nil
        healthProbeStartedAt = nil
        healthPingClockProbeStartedAt = nil
        healthProbeFailureCount = 0
        consecutiveCommandWriteTimeouts = 0
        pauseClockSyncScheduling(reason: reason)
        updateConnectionHealthDebugFields()
        ctrlLog(
            "[APP-LIFECYCLE] BLE watchdogs paused reason=\(reason) " +
                "inflight=\(commandWriteInflight.count) pending=\(pendingCommandWrites.count)"
        )
    }

    private func handleAppForegroundReconnectCheck() {
        let connected = sonyPeripheral?.state == .connected
        let ready = connected && sonyCommandCharacteristic != nil && sonyStatusCharacteristic != nil
        let suspendedMs = backgroundEnteredAt.map {
            max(Int64(Date().timeIntervalSince($0) * 1_000), 0)
        } ?? 0
        backgroundEnteredAt = nil
        log(
            "[BLE-Reconnect] app foreground check connected=\(connected) " +
                "ready=\(ready) health=\(connectionHealthState) suspendedMs=\(suspendedMs)"
        )
        guard centralManager.state == .poweredOn else { return }
        if connected, coreBluetoothRestoreInProgress {
            log("[BLE-Reconnect] foreground restore skipped reason=CoreBluetooth restoration pending")
            return
        }
        if !connected {
            if systemAutoReconnectInProgress {
                let ageMs = systemAutoReconnectStartedAt.map {
                    Int64(Date().timeIntervalSince($0) * 1_000)
                } ?? 0
                if ageMs < DEFAULT_CONNECT_TIMEOUT_MS {
                    log(
                        "[BLE-Reconnect] foreground reconnect skipped " +
                            "reason=system auto reconnecting ageMs=\(ageMs)"
                    )
                    setStatus("正在连接 Sony")
                    setAutoReconnectState(.connecting)
                    return
                }
                log("[BLE-Reconnect] system auto reconnect expired ageMs=\(ageMs)")
                systemAutoReconnectInProgress = false
                systemAutoReconnectStartedAt = nil
                isConnectingToSony = false
                if let sonyPeripheral {
                    centralManager.cancelPeripheralConnection(sonyPeripheral)
                }
                self.sonyPeripheral = nil
            }
            if AutoReconnectState(rawValue: autoReconnectState) == .connecting,
               currentConnectIsRetrievedPeripheral,
               let sonyPeripheral {
                log("[BLE-Reconnect] foreground abandon retrieved connect")
                connectTimeoutWorkItem?.cancel()
                connectTimeoutWorkItem = nil
                isConnectingToSony = false
                centralManager.cancelPeripheralConnection(sonyPeripheral)
                self.sonyPeripheral = nil
                beginSonyScan(reason: "foreground abandon retrieve", isAutoReconnect: true, force: true)
                return
            }
            if AutoReconnectState(rawValue: autoReconnectState) == .scanning {
                log("[BLE-Reconnect] foreground strategy=restartActiveScan")
                beginSonyScan(reason: "foreground active scan", isAutoReconnect: true, force: true)
                return
            }
            log("[BLE-Reconnect] foreground reconnect reason=not connected")
            scheduleReconnect(reason: "app foreground", immediate: true)
        } else if !ready, let sonyPeripheral {
            if isReconnectInProgress {
                log("[BLE-Reconnect] foreground restore skipped reason=in progress state=\(autoReconnectState)")
                return
            }
            log("[BLE-Reconnect] foreground rediscover services reason=characteristic missing")
            setAutoReconnectState(.serviceDiscovering)
            sonyPeripheral.discoverServices([BLEUUIDs.service])
        } else {
            setConnectionHealth(.suspect, reason: "foreground validation pending")
            if commandWriteInflight.isEmpty {
                beginForegroundLinkValidation(reason: "foreground ready")
            } else {
                scheduleForegroundInflightSettle()
            }
        }
    }

    private func scheduleForegroundInflightSettle() {
        foregroundValidationWaitingForInflight = true
        foregroundInflightSettleWorkItem?.cancel()
        let expectedAttemptId = connectionAttemptId
        let expectedLifecycleGeneration = lifecycleGeneration
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.appLifecycleState == "active",
                  self.connectionAttemptId == expectedAttemptId,
                  self.lifecycleGeneration == expectedLifecycleGeneration,
                  self.foregroundValidationWaitingForInflight else { return }
            self.foregroundInflightSettleWorkItem = nil
            if self.commandWriteInflight.isEmpty {
                self.foregroundValidationWaitingForInflight = false
                self.beginForegroundLinkValidation(reason: "foreground inflight settled")
            } else {
                let command = self.commandWriteInflight.first?.cmd ?? "unknown"
                self.performHardReconnect(
                    reason: "foreground suspended write did not settle cmd=\(command)",
                    manual: false
                )
            }
        }
        foregroundInflightSettleWorkItem = item
        ctrlLog(
            "[BLE-Health] foreground waiting for suspended write " +
                "cmd=\(commandWriteInflight.first?.cmd ?? "unknown")"
        )
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(FOREGROUND_INFLIGHT_SETTLE_TIMEOUT_MS) / 1_000.0,
            execute: item
        )
    }

    private func beginForegroundLinkValidation(reason: String) {
        guard appLifecycleState == "active",
              sonyCharacteristicsReady,
              !foregroundValidationPending else { return }
        foregroundInflightSettleWorkItem?.cancel()
        foregroundInflightSettleWorkItem = nil
        foregroundValidationWaitingForInflight = false
        foregroundValidationPending = true
        foregroundValidationStartedAt = nil
        let seq = nextCommandSeq()
        foregroundValidationCommandSeq = seq
        let command = serverSupportsPing ? "PING" : "GET_PLAYBACK_STATE"
        ctrlLog(
            "[BLE-Health] foreground validation queued seq=\(seq) " +
                "cmd=\(command) reason=\(reason)"
        )
        sendCommand(cmd: command, seq: seq)
    }

    private func startForegroundValidationTimeout(seq: UInt64) {
        guard foregroundValidationPending,
              foregroundValidationCommandSeq == seq,
              foregroundValidationStartedAt == nil else { return }
        let startedAt = Date()
        foregroundValidationStartedAt = startedAt
        let expectedAttemptId = connectionAttemptId
        let expectedLifecycleGeneration = lifecycleGeneration
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.appLifecycleState == "active",
                  self.connectionAttemptId == expectedAttemptId,
                  self.lifecycleGeneration == expectedLifecycleGeneration,
                  self.foregroundValidationPending,
                  self.foregroundValidationCommandSeq == seq else { return }
            self.foregroundValidationTimeoutWorkItem = nil
            self.setConnectionHealth(.stale, reason: "foreground validation timeout")
            self.performHardReconnect(
                reason: "foreground link validation timeout seq=\(seq)",
                manual: false
            )
        }
        foregroundValidationTimeoutWorkItem?.cancel()
        foregroundValidationTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(FOREGROUND_LINK_VALIDATION_TIMEOUT_MS) / 1_000.0,
            execute: item
        )
    }

    private func completeForegroundLinkValidation(type: String) {
        guard foregroundValidationPending,
              appLifecycleState == "active" else { return }
        let costMs = foregroundValidationStartedAt.map {
            Int64(Date().timeIntervalSince($0) * 1_000)
        } ?? 0
        foregroundValidationTimeoutWorkItem?.cancel()
        foregroundValidationTimeoutWorkItem = nil
        foregroundValidationPending = false
        foregroundValidationCommandSeq = nil
        foregroundValidationStartedAt = nil
        ctrlLog(
            "[BLE-Health] foreground validation success type=\(type) costMs=\(costMs)"
        )
        startHealthMonitoring(reason: "foreground validated")
        syncAfterReconnect(reason: "foreground validated")
        scheduleClockSyncBootstrap(reason: "foreground validated")
    }

    func albumArtConsoleLog(_ message: String) {
        // AlbumArtReceiver already routes persistent diagnostics through
        // albumArtLog(_:); this hook is console-only to avoid duplicate disk I/O.
        print(message)
    }

    private var liveActivityBleReady: Bool {
        centralManager.state == .poweredOn &&
            sonyPeripheral?.state == .connected &&
            sonyCommandCharacteristic != nil &&
            connectionStatus == "已连接" &&
            isConnectionHealthyOrSuspect
    }

    private var isReconnectInProgress: Bool {
        reconnectWorkItem != nil || isActiveReconnectState
    }

    private var isActiveReconnectState: Bool {
        switch AutoReconnectState(rawValue: autoReconnectState) {
        case .scanning, .connecting, .serviceDiscovering, .subscribing, .syncing:
            return true
        default:
            return false
        }
    }

    private func updateAutoReconnectDebugFields() {
        autoReconnectWorkItemExists = reconnectWorkItem != nil
        if let reconnectScheduledAt {
            autoReconnectScheduledAgeMs = Int64(Date().timeIntervalSince(reconnectScheduledAt) * 1_000)
        } else {
            autoReconnectScheduledAgeMs = -1
        }
        autoReconnectScheduledDelayMs = reconnectScheduledDelayMs
        autoReconnectIsConnecting = isConnectingToSony
        autoReconnectIsScanning = AutoReconnectState(rawValue: autoReconnectState) == .scanning
    }

    private func scheduleReconnectStuckCheck() {
        reconnectStuckCheckWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.updateAutoReconnectDebugFields()
            guard AutoReconnectState(rawValue: self.autoReconnectState) == .reconnectScheduled,
                  self.reconnectWorkItem == nil else {
                return
            }
            let ageMs = self.reconnectScheduledAt.map {
                Int64(Date().timeIntervalSince($0) * 1_000)
            } ?? -1
            guard ageMs < 0 || ageMs > 2_000 else { return }
            self.log("[BLE-Reconnect] stuck scheduled detected no workItem ageMs=\(ageMs)")
            self.log("[BLE-Reconnect] recover stuck scheduled now")
            self.scheduleReconnect(reason: "recover stuck scheduled", immediate: true)
        }
        reconnectStuckCheckWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0, execute: item)
    }

    private func recordLiveActivityControlResult(
        command: LiveActivityControlCommand,
        seq: UInt64,
        result: LiveActivityControlResult,
        startedAtMs: Int64,
        inFlight: Bool? = nil
    ) -> LiveActivityControlResult {
        liveActivityControlStatus.bridgeRegistered = LiveActivityCommandBridge.shared.isRegistered
        liveActivityControlStatus.bleReady = liveActivityBleReady
        liveActivityControlStatus.lastIntentSeq = seq
        liveActivityControlStatus.lastCommand = command
        liveActivityControlStatus.lastResult = result
        liveActivityControlStatus.lastCostMs = currentTimeMs() - startedAtMs
        if let inFlight {
            liveActivityControlStatus.inFlight = inFlight
        }
        if result != .sent {
            liveActivityControlStatus.droppedCount += 1
        }
        if result == .debounced {
            liveActivityControlStatus.debouncedCount += 1
        }
        return result
    }

    private func refreshLiveActivityControlStatus() {
        liveActivityControlStatus.bridgeRegistered = LiveActivityCommandBridge.shared.isRegistered
        liveActivityControlStatus.bleReady = liveActivityBleReady
        liveActivityControlStatus.inFlight = liveActivityControlInFlightSeq != nil
    }

    private func liveActivityDebounceMs(for command: LiveActivityControlCommand) -> Int64 {
        switch command {
        case .playPause:
            return LIVE_ACTIVITY_PLAY_PAUSE_DEBOUNCE_MS
        case .previous, .next:
            return LIVE_ACTIVITY_TRACK_SKIP_DEBOUNCE_MS
        case .reconnect:
            return 2_000
        }
    }

    private func setMode(_ value: String) {
        DispatchQueue.main.async {
            self.mode = value
        }
    }

    private func setStatus(_ value: String) {
        DispatchQueue.main.async {
            self.connectionStatus = value
            self.refreshLiveActivityControlStatus()
            self.refreshConnectionDisplayState(reason: "status \(value)")
        }
    }
}

extension BLETestManager: LiveActivityBLECommandSending {
    func sendLiveActivityCommand(
        _ command: LiveActivityControlCommand,
        seq: UInt64,
        issuedAt: Date
    ) -> LiveActivityControlResult {
        let startedAtMs = currentTimeMs()
        let ageMs = Int64(Date().timeIntervalSince(issuedAt) * 1_000)
        ctrlLog(
            "[LA-CTRL] send check seq=\(seq) cmd=\(command.rawValue) " +
                "ageMs=\(ageMs)"
        )
        ctrlLog(
            "[LA-CTRL] connected=\(connectionStatus == "已连接") " +
                "centralState=\(centralManager.state.rawValue) health=\(connectionHealthState)"
        )
        ctrlLog(
            "[LA-CTRL] peripheralState=\(sonyPeripheral?.state.rawValue ?? -1) " +
                "characteristicReady=\(sonyCommandCharacteristic != nil)"
        )

        if command == .reconnect {
            guard centralManager.state == .poweredOn else {
                ctrlLog("[LA-CTRL] reconnect dropped seq=\(seq) reason=bluetoothUnavailable")
                return recordLiveActivityControlResult(
                    command: command,
                    seq: seq,
                    result: .bluetoothUnavailable,
                    startedAtMs: startedAtMs
                )
            }

            let nowMs = currentTimeMs()
            let debounceMs = liveActivityDebounceMs(for: command)
            let lastAcceptedAtMs = lastLiveActivityCommandAcceptedAtMs[command] ?? 0
            if nowMs - lastAcceptedAtMs < debounceMs {
                ctrlLog(
                    "[LA-CTRL] reconnect dropped seq=\(seq) " +
                        "reason=debounced debounceMs=\(debounceMs)"
                )
                return recordLiveActivityControlResult(
                    command: command,
                    seq: seq,
                    result: .debounced,
                    startedAtMs: startedAtMs
                )
            }

            lastLiveActivityCommandAcceptedAtMs[command] = nowMs
            ctrlLog("[LA-CTRL] reconnect requested seq=\(seq)")
            forceReconnect()
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .sent,
                startedAtMs: startedAtMs,
                inFlight: false
            )
        }

        guard ageMs <= LIVE_ACTIVITY_COMMAND_TTL_MS else {
            ctrlLog("[LA-CTRL] command dropped seq=\(seq) reason=expired")
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .expired,
                startedAtMs: startedAtMs
            )
        }
        guard centralManager.state == .poweredOn else {
            ctrlLog("[LA-CTRL] command dropped seq=\(seq) reason=bluetoothUnavailable")
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .bluetoothUnavailable,
                startedAtMs: startedAtMs
            )
        }
        guard connectionStatus == "已连接" else {
            let reason = isReconnectInProgress ? "reconnecting" : "disconnected"
            ctrlLog("[LA-CTRL] command dropped seq=\(seq) reason=\(reason)")
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .disconnected,
                startedAtMs: startedAtMs
            )
        }
        guard isConnectionHealthyOrSuspect else {
            ctrlLog(
                "[LA-CTRL] command dropped seq=\(seq) " +
                    "reason=unhealthy health=\(connectionHealthState)"
            )
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .disconnected,
                startedAtMs: startedAtMs
            )
        }
        guard sonyPeripheral?.state == .connected else {
            let reason = isReconnectInProgress ? "reconnecting" : "disconnected"
            ctrlLog("[LA-CTRL] command dropped seq=\(seq) reason=\(reason)")
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .disconnected,
                startedAtMs: startedAtMs
            )
        }
        guard sonyCommandCharacteristic != nil else {
            ctrlLog("[LA-CTRL] command dropped seq=\(seq) reason=characteristicNotReady")
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .characteristicNotReady,
                startedAtMs: startedAtMs
            )
        }

        let nowMs = currentTimeMs()
        let debounceMs = liveActivityDebounceMs(for: command)
        let lastAcceptedAtMs = lastLiveActivityCommandAcceptedAtMs[command] ?? 0
        if nowMs - lastAcceptedAtMs < debounceMs {
            ctrlLog(
                "[LA-CTRL] command dropped seq=\(seq) cmd=\(command.rawValue) " +
                    "reason=debounced debounceMs=\(debounceMs)"
            )
            return recordLiveActivityControlResult(
                command: command,
                seq: seq,
                result: .debounced,
                startedAtMs: startedAtMs
            )
        }
        if let inFlightSeq = liveActivityControlInFlightSeq {
            let inFlightAgeMs = nowMs - liveActivityControlWriteStartedAtMs
            if inFlightAgeMs < LIVE_ACTIVITY_WRITE_STALL_MS {
                ctrlLog(
                    "[LA-CTRL] command dropped seq=\(seq) reason=writeInFlight " +
                        "inFlightSeq=\(inFlightSeq)"
                )
                return recordLiveActivityControlResult(
                    command: command,
                    seq: seq,
                    result: .writeInFlight,
                    startedAtMs: startedAtMs,
                    inFlight: true
                )
            }
            ctrlLog(
                "[LA-CTRL] stale in-flight released seq=\(inFlightSeq) " +
                    "ageMs=\(inFlightAgeMs)"
            )
            liveActivityControlInFlightSeq = nil
            liveActivityControlWriteStartedAtMs = 0
        }

        lastLiveActivityCommandAcceptedAtMs[command] = nowMs
        liveActivityControlInFlightSeq = seq
        liveActivityControlWriteStartedAtMs = nowMs
        ctrlLog("[LA-CTRL] write requested seq=\(seq) cmd=\(command.rawValue)")
        sendCommand(
            cmd: command.rawValue,
            extra: ["source": "liveActivity"],
            seq: seq
        )
        return recordLiveActivityControlResult(
            command: command,
            seq: seq,
            result: .sent,
            startedAtMs: startedAtMs,
            inFlight: true
        )
    }
}

extension BLETestManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log("[BLE] central state=\(central.state.rawValue)")
        switch central.state {
        case .poweredOn:
            bluetoothAvailability = .available
        case .poweredOff:
            bluetoothAvailability = .poweredOff
        case .unauthorized:
            bluetoothAvailability = .unauthorized
        case .unsupported:
            bluetoothAvailability = .unsupported
        case .resetting:
            bluetoothAvailability = .resetting
        case .unknown:
            bluetoothAvailability = .unknown
        @unknown default:
            bluetoothAvailability = .unknown
        }
        if central.state == .poweredOn, shouldScanWhenPoweredOn {
            beginSonyScan(reason: "poweredOn pending scan", isAutoReconnect: false, force: false)
        } else if central.state == .poweredOn, autoReconnectEnabled, sonyPeripheral?.state != .connected {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self,
                      self.centralManager.state == .poweredOn,
                      self.sonyPeripheral?.state != .connected else {
                    return
                }
                self.scheduleReconnect(reason: "central poweredOn", immediate: true)
            }
        } else if central.state != .poweredOn {
            stopHealthMonitoring(reason: "central not powered")
            setConnectionHealth(.disconnected, reason: "central state=\(central.state.rawValue)")
            setStatus("未连接")
            setAutoReconnectState(.failed)
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restoredPeripherals =
            dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        let lastIdentifier = UserDefaults.standard.string(
            forKey: AUTO_RECONNECT_LAST_PERIPHERAL_KEY
        )
        let candidate = restoredPeripherals.first {
            $0.identifier.uuidString == lastIdentifier
        } ?? restoredPeripherals.first {
            $0.state == .connected
        }

        guard let peripheral = candidate else {
            log("[BLE-Restore] no restorable Sony peripheral")
            return
        }

        coreBluetoothRestoreInProgress = true
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        scanTimeoutWorkItem?.cancel()
        scanTimeoutWorkItem = nil
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
        central.stopScan()
        isConnectingToSony = false
        connectionAttemptId = UUID()
        connectionHealthAttemptId = connectionAttemptId.uuidString
        sonyPeripheral = peripheral
        peripheral.delegate = self
        connectedDeviceName = peripheral.name ?? "Sony"
        saveLastSonyPeripheral(peripheral)
        log(
            "[BLE-Restore] restored id=\(peripheral.identifier.uuidString) " +
                "state=\(peripheralStateText(peripheral))"
        )

        guard peripheral.state == .connected else {
            coreBluetoothRestoreInProgress = false
            connectSonyPeripheral(
                peripheral,
                reason: "CoreBluetooth restored peripheral",
                isRetrieved: true
            )
            return
        }

        setStatus("正在恢复服务")
        setConnectionHealth(.suspect, reason: "CoreBluetooth restoration pending")
        setAutoReconnectState(.serviceDiscovering)
        scheduleCoreBluetoothRestoreTimeout(for: peripheral)
        if let service = peripheral.services?.first(where: { $0.uuid == BLEUUIDs.service }) {
            let characteristics = service.characteristics ?? []
            sonyCommandCharacteristic = characteristics.first {
                $0.uuid == BLEUUIDs.command
            }
            sonyStatusCharacteristic = characteristics.first {
                $0.uuid == BLEUUIDs.status
            }
            if let status = sonyStatusCharacteristic,
               sonyCommandCharacteristic != nil {
                setAutoReconnectState(.subscribing)
                subscribeStartedAtMs = currentTimeMs()
                peripheral.setNotifyValue(true, for: status)
                if status.isNotifying {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self, weak peripheral] in
                        guard let self,
                              let peripheral,
                              self.lastNotifySubscribedAt == nil,
                              self.sonyPeripheral?.identifier == peripheral.identifier else {
                            return
                        }
                        self.log("[BLE-Restore] reuse restored notifying characteristic")
                        self.peripheral(
                            peripheral,
                            didUpdateNotificationStateFor: status,
                            error: nil
                        )
                    }
                }
                return
            }
            peripheral.discoverCharacteristics(
                [BLEUUIDs.command, BLEUUIDs.status],
                for: service
            )
        } else {
            peripheral.discoverServices([BLEUUIDs.service])
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            ?? peripheral.name
            ?? "Unknown"
        log("[BLE] scan result name=\(name) rssi=\(RSSI)")
        log("[BLE-Reconnect] didDiscover name=\(name) id=\(peripheral.identifier.uuidString)")
        log("[BLE-iOS] didDiscover name=\(name) id=\(peripheral.identifier)")

        guard !isConnectingToSony else { return }
        guard sonyPeripheral == nil || sonyPeripheral?.state == .disconnected else {
            log("[BLE-Reconnect] ignore discover reason=active peripheral state=\(peripheralStateText(sonyPeripheral))")
            return
        }

        scanTimeoutWorkItem?.cancel()
        central.stopScan()
        autoReconnectLastScanCostMs = currentTimeMs() - scanStartedAtMs
        log("[BLE] connecting \(name) id=\(peripheral.identifier)")
        log("[BLE-iOS] connect peripheral=\(peripheral.identifier)")
        connectedDeviceName = name
        currentConnectIsAutoReconnect = currentScanIsAutoReconnect
        connectSonyPeripheral(
            peripheral,
            reason: currentScanIsAutoReconnect ? "auto scan" : "manual scan",
            isRetrieved: false
        )
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale didConnect id=\(peripheral.identifier.uuidString)")
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        systemAutoReconnectInProgress = false
        systemAutoReconnectStartedAt = nil
        isConnectingToSony = false
        updateAutoReconnectDebugFields()
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
        autoReconnectLastConnectCostMs = currentTimeMs() - connectStartedAtMs
        saveLastSonyPeripheral(peripheral)
        setAutoReconnectState(.serviceDiscovering)
        setStatus("正在恢复服务")
        lastNotifySubscribedAt = nil
        lastStatusNotifyAt = nil
        lastPlaybackStateAt = nil
        healthProbeCommandSeq = nil
        healthProbeStartedAt = nil
        healthPingClockProbeStartedAt = nil
        resetClockSync(reason: "didConnect")
        resetCurrentWordFence()
        reconnectStateSyncWindowUntilMs = 0
        reconnectStateSyncPlaybackLogged = false
        log("[BLE] connected")
        log("[BLE-iOS] didConnect")
        log("[Reconnect] connected")
        connectedDeviceName = peripheral.name ?? connectedDeviceName
        log("[BLE-Reconnect] restore services")
        let subscribeTimeout = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self,
                  let peripheral,
                  self.sonyPeripheral?.identifier == peripheral.identifier,
                  self.sonyPeripheral?.state == .connected,
                  self.lastNotifySubscribedAt == nil else { return }
            self.performHardReconnect(reason: "didConnect notify subscribe timeout", manual: false)
        }
        subscribeNotifyTimeoutWorkItem?.cancel()
        subscribeNotifyTimeoutWorkItem = subscribeTimeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(CONNECTION_SUBSCRIBE_NOTIFY_TIMEOUT_MS) / 1_000.0,
            execute: subscribeTimeout
        )
        peripheral.discoverServices([BLEUUIDs.service])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale didFailToConnect id=\(peripheral.identifier.uuidString)")
            return
        }
        systemAutoReconnectInProgress = false
        systemAutoReconnectStartedAt = nil
        isConnectingToSony = false
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
        setStatus("未连接")
        setAutoReconnectState(.failed)
        log("[BLE] connection failed error=\(error?.localizedDescription ?? "unknown")")
        log("[BLE-iOS] didFailToConnect error=\(error?.localizedDescription ?? "unknown")")
        log("[BLE-Reconnect] failed reason=connect error=\(error?.localizedDescription ?? "unknown")")
        sonyPeripheral = nil
        connectedDeviceName = "-"
        if currentConnectIsAutoReconnect || autoReconnectEnabled {
            scheduleReconnect(reason: "connect failed", immediate: false)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        handlePeripheralDisconnect(
            peripheral,
            error: error,
            systemIsReconnecting: false
        )
    }

    @available(iOS 17.0, *)
    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        timestamp: CFAbsoluteTime,
        isReconnecting: Bool,
        error: Error?
    ) {
        handlePeripheralDisconnect(
            peripheral,
            error: error,
            systemIsReconnecting: isReconnecting
        )
    }

    private func handlePeripheralDisconnect(
        _ peripheral: CBPeripheral,
        error: Error?,
        systemIsReconnecting: Bool
    ) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale didDisconnect id=\(peripheral.identifier.uuidString)")
            return
        }
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
        let errorText = error?.localizedDescription ?? "none"
        autoReconnectLastDisconnectError = errorText
        self.systemAutoReconnectInProgress = systemIsReconnecting
        systemAutoReconnectStartedAt = systemIsReconnecting ? Date() : nil
        isConnectingToSony = systemIsReconnecting
        log(
            "[BLE] disconnected error=\(errorText) " +
                "systemReconnecting=\(systemIsReconnecting)"
        )
        log(
            "[BLE-iOS] didDisconnect error=\(errorText) " +
                "systemReconnecting=\(systemIsReconnecting)"
        )
        log(
            "[BLE-Reconnect] disconnected error=\(errorText) " +
                "systemReconnecting=\(systemIsReconnecting)"
        )
        reconnectStateSyncWindowUntilMs = 0
        reconnectStateSyncPlaybackLogged = false
        stopHealthMonitoring(reason: "didDisconnect")
        setConnectionHealth(.disconnected, reason: "didDisconnect")
        clearConnectionTransports(reason: "disconnect")
        clearPendingVolume()

        if systemIsReconnecting {
            setStatus("正在连接 Sony")
            setAutoReconnectState(.connecting)
            connectedDeviceName = peripheral.name ?? connectedDeviceName
            log("[BLE-Reconnect] waiting for CoreBluetooth automatic reconnect")
            return
        }

        sonyPeripheral = nil
        connectedDeviceName = "-"
        setStatus(autoReconnectEnabled ? "正在连接" : "未连接")
        if !autoReconnectEnabled {
            setAutoReconnectState(.idle)
        }
        log("[BLE-Reconnect] update LiveActivity disconnected")
        updateLiveActivityDisconnected()
        if SystemReconnectPolicy.shouldScheduleManualReconnect(
            autoReconnectEnabled: autoReconnectEnabled,
            systemIsReconnecting: systemIsReconnecting
        ) {
            scheduleReconnect(reason: "disconnect", immediate: false)
        }
    }
}

extension BLETestManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale services id=\(peripheral.identifier.uuidString)")
            return
        }
        if let error {
            log("[BLE] service discovery failed error=\(error.localizedDescription)")
            performHardReconnect(reason: "service discovery failed", manual: false)
            return
        }

        guard let service = peripheral.services?.first(where: { $0.uuid == BLEUUIDs.service }) else {
            log("[BLE] target service not found")
            log("[BLE-Reconnect] fallback scan reason=service not found")
            UserDefaults.standard.removeObject(forKey: AUTO_RECONNECT_LAST_PERIPHERAL_KEY)
            autoReconnectLastPeripheralId = "-"
            isConnectingToSony = false
            centralManager.cancelPeripheralConnection(peripheral)
            sonyPeripheral = nil
            beginSonyScan(reason: "service missing fallback", isAutoReconnect: true, force: true)
            return
        }

        log("[BLE] service discovered \(service.uuid.uuidString)")
        peripheral.discoverCharacteristics([BLEUUIDs.command, BLEUUIDs.status], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale characteristics id=\(peripheral.identifier.uuidString)")
            return
        }
        if let error {
            log("[BLE] characteristic discovery failed error=\(error.localizedDescription)")
            performHardReconnect(reason: "characteristic discovery failed", manual: false)
            return
        }

        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == BLEUUIDs.command {
                sonyCommandCharacteristic = characteristic
                log("[BLE] command characteristic found")
            } else if characteristic.uuid == BLEUUIDs.status {
                sonyStatusCharacteristic = characteristic
                setAutoReconnectState(.subscribing)
                subscribeStartedAtMs = currentTimeMs()
                peripheral.setNotifyValue(true, for: characteristic)
                log("[BLE] status characteristic found")
            }
        }

        if sonyCommandCharacteristic != nil {
            setStatus("连接中")
        }
        if sonyCommandCharacteristic == nil || sonyStatusCharacteristic == nil {
            log("[BLE-Reconnect] characteristic missing command=\(sonyCommandCharacteristic != nil) status=\(sonyStatusCharacteristic != nil)")
            performHardReconnect(reason: "characteristic missing", manual: false)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale didWrite id=\(peripheral.identifier.uuidString)")
            return
        }
        commandWriteTimeoutWorkItem?.cancel()
        commandWriteTimeoutWorkItem = nil
        let completed = commandWriteInflight.isEmpty ? nil : commandWriteInflight.removeFirst()
        let didWriteMs = currentTimeMs()
        if let completed {
            let costMs = didWriteMs - completed.writeCalledAtMs
            let errorText = error?.localizedDescription ?? "nil"
            ctrlLog(
                "[CTRL-iOS] didWrite seq=\(completed.seq) cmd=\(completed.cmd) " +
                    "timeMs=\(didWriteMs) costMs=\(costMs) error=\(errorText)"
            )
            if completed.seq == liveActivityControlInFlightSeq {
                liveActivityControlInFlightSeq = nil
                liveActivityControlWriteStartedAtMs = 0
                liveActivityControlStatus.inFlight = false
                ctrlLog(
                    "[LA-CTRL] write callback seq=\(completed.seq) " +
                        "costMs=\(costMs) error=\(errorText)"
                )
                refreshLiveActivityControlStatus()
            }
            if completed.cmd == "SET_VOLUME" {
                handleVolumeWriteCompletion(seq: completed.seq, error: error, costMs: costMs)
            } else {
                flushPendingVolumeIfPossible()
            }
        } else {
            let errorText = error?.localizedDescription ?? "nil"
            ctrlLog(
                "[CTRL-iOS] didWrite seq=unknown cmd=unknown " +
                    "timeMs=\(didWriteMs) costMs=unknown error=\(errorText)"
            )
        }

        if error == nil, completed != nil {
            consecutiveCommandWriteTimeouts = 0
        }
        if let error {
            if completed?.cmd == "SET_VOLUME" {
                log("[iOS][BLE] write SET_VOLUME failed: \(error.localizedDescription)")
            } else {
                log(
                    "[Command] \(completed?.cmd ?? "unknown") failed " +
                        "error=\(error.localizedDescription)"
                )
            }
            performHardReconnect(reason: "didWrite error \(error.localizedDescription)", manual: false)
        } else if completed?.cmd == "SET_VOLUME" {
            lastSuccessfulWriteAt = Date()
            log("[iOS][BLE] write SET_VOLUME success")
        } else {
            lastSuccessfulWriteAt = Date()
            log("[Command] \(completed?.cmd ?? "unknown") success")
        }
        if error == nil {
            if foregroundValidationWaitingForInflight,
               appLifecycleState == "active" {
                foregroundValidationWaitingForInflight = false
                foregroundInflightSettleWorkItem?.cancel()
                foregroundInflightSettleWorkItem = nil
                beginForegroundLinkValidation(reason: "suspended write callback received")
            } else {
                flushCommandWriteQueue()
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale notify state id=\(peripheral.identifier.uuidString)")
            return
        }
        guard characteristic.uuid == BLEUUIDs.status else { return }
        coreBluetoothRestoreInProgress = false
        coreBluetoothRestoreTimeoutWorkItem?.cancel()
        coreBluetoothRestoreTimeoutWorkItem = nil

        if let error {
            setStatus("未连接")
            log("[BLE] status notify subscription failed: \(error.localizedDescription)")
            log("[BLE-Reconnect] failed reason=notify subscribe error=\(error.localizedDescription)")
            performHardReconnect(reason: "notify subscribe error", manual: false)
        } else {
            setStatus(characteristic.isNotifying ? "已连接" : "连接中")
            log("[BLE] status notify subscribed")
            log("[BLE-Reconnect] notify subscribed")
            log("[Reconnect] subscribed")
            guard characteristic.isNotifying else {
                performHardReconnect(reason: "notify disabled", manual: false)
                return
            }
            subscribeNotifyTimeoutWorkItem?.cancel()
            subscribeNotifyTimeoutWorkItem = nil
            lastNotifySubscribedAt = Date()
            reconnectStateSyncWindowUntilMs = currentTimeMs() + 5_000
            reconnectStateSyncPlaybackLogged = false
            connectionReadyAt = Date()
            autoReconnectLastSubscribeCostMs = currentTimeMs() - subscribeStartedAtMs
            setAutoReconnectState(.syncing)
            autoReconnectLastCostMs = reconnectStartedAtMs > 0 ? currentTimeMs() - reconnectStartedAtMs : 0
            log("[BLE-Reconnect] success reset attempts costMs=\(autoReconnectLastCostMs)")
            autoReconnectAttempt = 0
            autoReconnectNextRetryAt = nil
            firstConnectionReadyAtMs = currentTimeMs()
            setConnectionHealth(.suspect, reason: "notify subscribed waiting status")
            startHealthMonitoring(reason: "notify subscribed")
            serverProtocolVersion = 1
            serverSupportsAlbumArtBinary = false
            serverSupportsFullLyricsZlib = false
            serverSupportsLyricWindow = false
            serverSupportsPing = false
            serverSupportsClockSyncV1 = false
            serverSupportsTransferRetry = false
            negotiatedV3Features = []
            serverSessionId = "-"
            lastServerEventSequence = 0
            eventSequenceDiagnostics.reset()
            lastMediaLoadStateKeyByResource.removeAll()
            resetClockSync(reason: "notify subscribed")
            requestedLyricWindowTrackIDs.removeAll()
            let expectedAttemptId = connectionAttemptId
            let expectedLifecycleGeneration = lifecycleGeneration
            let subscribeTimeout = DispatchWorkItem { [weak self] in
                guard let self,
                      self.appLifecycleState == "active",
                      self.connectionAttemptId == expectedAttemptId,
                      self.lifecycleGeneration == expectedLifecycleGeneration,
                      let lastNotifySubscribedAt = self.lastNotifySubscribedAt,
                      self.lastStatusNotifyAt == nil ||
                          self.lastStatusNotifyAt! < lastNotifySubscribedAt else {
                    return
                }
                self.performHardReconnect(reason: "notify subscribed no status", manual: false)
            }
            subscribeNotifyTimeoutWorkItem = subscribeTimeout
            DispatchQueue.main.asyncAfter(
                deadline: .now() + Double(CONNECTION_SUBSCRIBE_NOTIFY_TIMEOUT_MS) / 1_000.0,
                execute: subscribeTimeout
            )
            let requestedProtocolVersion = preferences.forceProtocolV2 ? 2 : 3
            log(
                "[BLE-Reconnect] send CLIENT_CAPABILITIES " +
                    "protocolVersion=\(requestedProtocolVersion) f3=\(requestedProtocolVersion >= 3 ? BLEProtocolV3Features.all.rawValue : 0)"
            )
            var capabilities: [String: Any] = [
                "protocolVersion": requestedProtocolVersion,
                "albumArtBinary": true,
                "fullLyricsZlib": true,
                "lyricWindow": true,
                "ping": true,
                "clockSyncV1": true,
                "transferRetry": true
            ]
            if requestedProtocolVersion >= 3 {
                capabilities["f3"] = BLEProtocolV3Features.all.rawValue
            }
            sendCommand(
                cmd: "CLIENT_CAPABILITIES",
                extra: capabilities
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                guard let self,
                      self.canRunDeferredConnectionSync(
                        attemptId: expectedAttemptId,
                        lifecycleGeneration: expectedLifecycleGeneration
                      ) else { return }
                self.log("[BLE-Reconnect] sync playback state")
                self.sendGetPlaybackState()
            }
            // Let the interactive lyric window finish before the non-critical
            // initial volume snapshot uses the ATT command/notify path.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                guard let self,
                      self.canRunDeferredConnectionSync(
                        attemptId: expectedAttemptId,
                        lifecycleGeneration: expectedLifecycleGeneration
                      ) else { return }
                self.log("[BLE-Reconnect] sync volume")
                self.sendGetVolume()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
                guard let self,
                      self.canRunDeferredConnectionSync(
                        attemptId: expectedAttemptId,
                        lifecycleGeneration: expectedLifecycleGeneration
                      ) else { return }
                self.log("[BLE-Reconnect] defer full lyrics")
                self.requestFullLyricsIfNeeded(after: 0)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                guard let self,
                      self.connectionAttemptId == expectedAttemptId,
                      self.lifecycleGeneration == expectedLifecycleGeneration,
                      self.sonyCharacteristicsReady else { return }
                self.setAutoReconnectState(.connected)
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale notify value id=\(peripheral.identifier.uuidString)")
            return
        }
        guard characteristic.uuid == BLEUUIDs.status else { return }

        if let error {
            log("[BLE] status notify receive failed: \(error.localizedDescription)")
            return
        }

        guard let data = characteristic.value else {
            log("[Status] empty notify")
            return
        }
        let peripheralID = peripheral.identifier
        inboundPipeline.submit { [weak self] in
            guard let self else { return }
            if data.first == 0xA1 {
                DispatchQueue.main.async { [weak self] in
                    guard let self,
                          self.sonyPeripheral?.identifier == peripheralID else { return }
                    self.markStatusNotifyReceived(type: "albumArtBinaryChunk")
                    self.albumArtReceiver.handleBinaryChunk(data)
                }
                return
            }
            if data.first == 0xA2 {
                DispatchQueue.main.async { [weak self] in
                    guard let self,
                          self.sonyPeripheral?.identifier == peripheralID else { return }
                    self.markStatusNotifyReceived(type: "fullLyricsBinaryChunk")
                    self.handleFullLyricsBinaryChunk(data)
                }
                return
            }
            let decodeState = AppPerformanceLog.protocolSignposter.beginInterval("Status JSON Decode")
            let objectBox = BLEProtocolV3Parser.jsonObject(from: data).map(BLEStatusObjectBox.init)
            let type = objectBox?.value["type"] as? String
            let text = String(data: data, encoding: .utf8)
            AppPerformanceLog.protocolSignposter.endInterval("Status JSON Decode", decodeState)
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      self.sonyPeripheral?.identifier == peripheralID else { return }
                guard let object = objectBox?.value, let type else {
                    if let text {
                        self.log("[BLE] status notify received: \(text)")
                    } else {
                        self.log("[Status] invalid UTF-8")
                    }
                    self.log("[Status] JSON parse failed")
                    return
                }
                if type == "albumArtChunk" || type == "trackInfoChunk" {
                    self.log("[BLE] status notify received type=\(type)")
                } else if !self.isHighVolumeNotifyType(type) {
                    self.log("[BLE] status notify received: \(text ?? type)")
                }
                self.parseStatus(object, type: type)
            }
        }
    }

    private func parseStatus(_ object: [String: Any], type: String) {
        let apply: () -> Void = {
            self.markStatusNotifyReceived(type: type)
            self.observeV3StatusMetadata(object, type: type)
            switch type {
            case "link":
                // Sony's per-client keepalive already refreshed connection
                // health above. It intentionally carries no media payload.
                break

            case "clientCapabilitiesAck":
                let ack = BLEProtocolV3Parser.capabilitiesAck(from: object)
                self.serverProtocolVersion = ack.protocolVersion
                self.serverSupportsAlbumArtBinary = ack.v2Features.contains(.albumArtBinary)
                self.serverSupportsFullLyricsZlib = ack.v2Features.contains(.fullLyricsZlib)
                self.serverSupportsLyricWindow = ack.v2Features.contains(.lyricWindow)
                self.serverSupportsPing = ack.v2Features.contains(.ping)
                self.serverSupportsClockSyncV1 = ack.v2Features.contains(.clockSyncV1)
                self.serverSupportsTransferRetry = ack.v2Features.contains(.transferRetry)
                self.negotiatedV3Features = ack.v3Features.intersection(.all)
                if let sessionId = ack.sessionId {
                    self.serverSessionId = sessionId
                    self.lastServerEventSequence = 0
                    self.eventSequenceDiagnostics.reset(sessionId: sessionId)
                }
                self.log(
                    "[BLE-iOS] capabilities ack protocolVersion=\(self.serverProtocolVersion) " +
                        "albumArtBinary=\(self.serverSupportsAlbumArtBinary) " +
                        "fullLyricsZlib=\(self.serverSupportsFullLyricsZlib) " +
                        "lyricWindow=\(self.serverSupportsLyricWindow) " +
                        "ping=\(self.serverSupportsPing) " +
                        "clockSyncV1=\(self.serverSupportsClockSyncV1) " +
                        "transferRetry=\(self.serverSupportsTransferRetry) " +
                        "f3=\(self.negotiatedV3Features.rawValue) sid=\(self.serverSessionId)"
                )
                if self.serverSupportsClockSyncV1 {
                    self.scheduleClockSyncBootstrap(reason: "capability ack")
                }
                if self.serverSupportsLyricWindow, !self.currentTrackID.isEmpty {
                    self.requestLyricWindow(trackID: self.currentTrackID)
                }

            case "commandError":
                self.handleStructuredCommandError(object)

            case "mediaLoadState":
                self.handleNegotiatedMediaLoadState(object)

            case "pong":
                let handledClockSync = self.handleClockSyncPong(object)
                if let startedAt = self.healthPingClockProbeStartedAt {
                    let receivedAt = Date()
                    let rttMs = Int64(receivedAt.timeIntervalSince(startedAt) * 1_000)
                    let midpointMs = Int64(
                        (startedAt.timeIntervalSince1970 +
                            receivedAt.timeIntervalSince1970) * 500
                    )
                    let sonyTimeMs = Self.int64Value(object["time"])
                    if sonyTimeMs > 0 {
                        self.sonyClockOffsetMs = sonyTimeMs - midpointMs
                    }
                    self.healthPingClockProbeStartedAt = nil
                    self.log(
                        "[BLE-Health] pong seq=\(object["seq"] ?? "") " +
                            "rttMs=\(rttMs) " +
                            "clockOffsetMs=\(self.sonyClockOffsetMs ?? 0)"
                    )
                } else if !handledClockSync {
                    self.log("[BLE-Health] pong seq=\(object["seq"] ?? "")")
                }

            case "playbackState":
                let oldLyric = self.lyric
                let oldIsPlaying = self.isPlaying
                let oldPositionMs = self.positionMs
                let reconnectSyncWindow = self.isInReconnectStateSyncWindow()
                self.isPlaying = object["playing"] as? Bool ?? false
                self.durationMs = Self.int64Value(object["duration"])
                let parsedSpeed = Self.doubleValue(object["speed"])
                self.remotePlaybackSpeed = parsedSpeed.isFinite && parsedSpeed > 0
                    ? parsedSpeed
                    : 1.0
                self.updateProgressTimerState()
                self.updateLightweightLyricDiagnostic(from: object)
                if let lyric = object["lyric"] as? String {
                    self.lyric = lyric
                    let lyricEmpty = lyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    if !self.currentTrackID.isEmpty {
                        let nowMs = self.currentTimeMs()
                        var details =
                            "[LyricTrace-iOS] id=\(self.currentTrackID) " +
                            "stage=playbackStateLyric lyricEmpty=\(lyricEmpty)"
                        if !lyricEmpty,
                           self.lyricTraceFirstPlaybackLyricAtMs[self.currentTrackID] == nil {
                            self.lyricTraceFirstPlaybackLyricAtMs[self.currentTrackID] = nowMs
                            if let trackAt = self.lyricTraceTrackInfoAtMs[self.currentTrackID] {
                                details += " sinceTrackInfoMs=\(nowMs - trackAt)"
                            }
                        }
                        self.log(details)
                    }
                    let scheduledDelayedRetry = self.retryFullLyricsIfLyricsBecameAvailable(
                        oldLyric: oldLyric,
                        newLyric: lyric
                    )
                    if !scheduledDelayedRetry,
                       !lyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                       self.fullLyrics.isEmpty {
                        if reconnectSyncWindow,
                           !self.currentTrackID.isEmpty,
                           !self.reconnectStateSyncRequestedFullLyricsTrackIDs.contains(
                            self.currentTrackID
                           ) {
                            self.reconnectStateSyncRequestedFullLyricsTrackIDs.insert(
                                self.currentTrackID
                            )
                            self.log(
                                "[Reconnect] request fullLyrics " +
                                    "reason=lyricsReadyWithoutLocalLyrics " +
                                    "trackId=\(self.currentTrackID)"
                            )
                        }
                        self.requestFullLyricsIfNeeded(after: 0.1)
                    }
                }
                if !self.isSeeking {
                    let remotePositionMs = Self.int64Value(object["position"])
                    let resolution = self.resolveRemotePlaybackAnchor(
                        object: object,
                        remotePositionMs: remotePositionMs,
                        isPlaying: self.isPlaying
                    )
                    if let calibratedPositionMs = self.calibratedRemotePosition(
                        from: resolution,
                        remotePositionMs: remotePositionMs,
                        source: "playbackState"
                    ) {
                        self.positionMs = calibratedPositionMs
                        self.displayPositionMs = calibratedPositionMs
                        self.seekPositionMs = calibratedPositionMs
                        self.basePlaybackPositionMs = calibratedPositionMs
                        self.playbackAnchorElapsedMs = self.monotonicTimeMs()
                    }
                }
                self.log(
                    "[iOS][Status] playbackState " +
                        "position=\(self.positionMs) duration=\(self.durationMs)"
                )
                if reconnectSyncWindow, !self.reconnectStateSyncPlaybackLogged {
                    self.reconnectStateSyncPlaybackLogged = true
                    self.log("[Reconnect] state sync received")
                    self.log(
                        "[Reconnect] playbackState accepted " +
                            "position=\(self.positionMs) duration=\(self.durationMs)"
                    )
                }
                if self.appLifecycleState != "active",
                   oldLyric != self.lyric,
                   !self.lyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    self.log(
                        "[BLE-BG] playbackState received appState=\(self.appLifecycleState) " +
                            "position=\(self.positionMs) lyric=\(self.lyric)"
                    )
                }
                if oldIsPlaying != self.isPlaying {
                    self.updateLiveActivity(force: false, reason: "playState")
                    _ = self.updateLiveActivityForCurrentLyricIfNeeded(reason: "playState")
                } else if self.updateLiveActivityForCurrentLyricIfNeeded(reason: "playbackState") {
                    // Lyric line updates are semantic Live Activity updates and are
                    // intentionally independent of progress calibration.
                } else if self.shouldRequestLiveActivityProgressUpdate(
                    oldPositionMs: oldPositionMs
                ) {
                    self.updateLiveActivity(force: false, reason: "playbackState")
                }
                self.scheduleLastNowPlayingSnapshotSave(reason: "playbackState")

            case "currentWord":
                self.handleCurrentWord(object)

            case "trackInfo":
                self.applyTrackInfo(object)

            case "trackInfoStart":
                let size = Self.intValue(object["size"])
                let chunks = Self.intValue(object["chunks"])
                guard size > 0, chunks > 0 else {
                    self.resetTrackInfoTransfer()
                    self.log("[TrackInfo] invalid start")
                    return
                }
                self.resetTrackInfoTransfer()
                self.trackInfoExpectedSize = size
                self.trackInfoExpectedChunks = chunks
                self.log("[TrackInfo] start chunks=\(chunks) size=\(size)")

            case "trackInfoChunk":
                let index = Self.intValue(object["index"])
                guard self.trackInfoExpectedChunks > 0,
                      index >= 0,
                      index < self.trackInfoExpectedChunks,
                      let base64 = object["data"] as? String,
                      let chunk = Data(base64Encoded: base64) else {
                    self.log("[TrackInfo] invalid chunk index=\(index)")
                    return
                }
                self.trackInfoChunks[index] = chunk

            case "trackInfoEnd":
                self.finishTrackInfoTransfer()

            case "fullLyricsStart":
                self.handleFullLyricsStart(object)

            case "fullLyricsChunk":
                self.handleFullLyricsChunk(object)

            case "fullLyricsEnd":
                self.handleFullLyricsEnd(object)

            case "fullLyricsUnavailable":
                self.handleFullLyricsUnavailable(object)

            case "fullLyricsBinaryStart":
                self.handleFullLyricsBinaryStart(object)

            case "fullLyricsBinaryEnd":
                self.handleFullLyricsBinaryEnd(object)

            case "fullLyricsBinaryError":
                self.handleFullLyricsBinaryError(object)

            case "lyricWindowStart":
                self.handleLyricWindowStart(object)

            case "lyricWindowChunk":
                self.handleLyricWindowChunk(object)

            case "lyricWindowEnd":
                self.handleLyricWindowEnd(object)

            case "lyricWindowUnavailable":
                self.lyricWindowTransfer = nil

            case "lyricDiagnostic":
                self.handleLyricDiagnostic(object)

            case "lyricDiagnosticUnavailable":
                self.handleLyricDiagnosticUnavailable(object)

            case "lyricSecondaryStart":
                self.handleLyricSecondaryStart(object)

            case "lyricSecondaryPart":
                self.handleLyricSecondaryPart(object)

            case "lyricSecondaryEnd":
                self.handleLyricSecondaryEnd(object)

            case "lyricSecondaryUnavailable":
                self.handleLyricSecondaryUnavailable(object, isError: false)

            case "lyricSecondaryError":
                self.handleLyricSecondaryUnavailable(object, isError: true)

            case "volumeState":
                self.volumeMax = Self.intValue(object["max"])
                let remoteVolume = Self.intValue(object["current"])
                if !self.isVolumeSeeking {
                    self.volumeCurrent = remoteVolume
                    self.volumeSeekValue = self.volumeCurrent
                    self.pendingRemoteVolumeValue = nil
                    self.log("[VOL-iOS] remote volume received value=\(remoteVolume)")
                } else {
                    self.pendingRemoteVolumeValue = remoteVolume
                    self.log("[VOL-iOS] remote ignored during drag value=\(remoteVolume)")
                }
                self.log(
                    "[Status] volumeState current=\(self.volumeCurrent) " +
                        "max=\(self.volumeMax)"
                )

            case "albumArtOffer":
                let id = object["id"] as? String ?? ""
                self.albumArtReceiver.handleOffer(id: id)

            case "albumArtStart":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String ?? ""
                let size = Self.intValue(object["size"])
                let chunks = Self.intValue(object["chunks"])
                self.albumArtReceiver.handleLegacyStart(id: id, quality: quality, size: size, chunks: chunks)

            case "albumArtBinaryStart":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String ?? ""
                let size = Self.intValue(object["size"])
                let chunks = Self.intValue(object["chunks"])
                self.albumArtReceiver.handleBinaryStart(
                    id: id,
                    quality: quality,
                    size: size,
                    chunks: chunks,
                    transferId: object["transferId"] as? String,
                    crc32: object["crc32"] as? String,
                    generation: Self.optionalInt64Value(object["generation"])
                )

            case "albumArtChunk":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String
                let index = Self.intValue(object["index"])
                let base64 = object["data"] as? String
                self.albumArtReceiver.handleLegacyChunk(id: id, quality: quality, index: index, base64: base64)

            case "albumArtEnd":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String
                self.albumArtReceiver.handleLegacyEnd(id: id, quality: quality)

            case "albumArtBinaryEnd":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String
                self.albumArtReceiver.handleBinaryEnd(
                    id: id,
                    quality: quality,
                    transferId: object["transferId"] as? String,
                    crc32: object["crc32"] as? String,
                    generation: Self.optionalInt64Value(object["generation"])
                )

            case "albumArtBinaryError":
                let message = object["message"] as? String ?? "unknown"
                self.albumArtReceiver.handleBinaryError(message: message)

            case "albumArtUnavailable":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String ?? "preview"
                let reason = object["reason"] as? String ?? "unknown"
                let bestBytes = Self.intValue(object["bestBytes"])
                let bestChunks = Self.intValue(object["bestChunks"])
                let minCandidateScale = Self.intValue(object["minCandidateScale"])
                self.albumArtReceiver.handleUnavailable(
                    id: id,
                    quality: quality,
                    reason: reason,
                    bestBytes: bestBytes,
                    bestChunks: bestChunks,
                    minCandidateScale: minCandidateScale
                )

            case "logStart":
                let chunks = Self.intValue(object["chunks"])
                let totalLines = Self.intValue(object["totalLines"])
                guard chunks > 0 else {
                    self.resetRemoteLogTransfer()
                    self.isRemoteLogTransferInProgress = false
                    self.log("[RemoteLog] decode failed")
                    return
                }
                self.resetRemoteLogTransfer()
                self.remoteLogExpectedChunks = chunks
                self.remoteLogExpectedLines = totalLines
                self.remoteLogText = ""
                self.remoteLogCopyStatus = ""
                self.isRemoteLogTransferInProgress = true
                self.log("[RemoteLog] start chunks=\(chunks)")

            case "logChunk":
                let index = Self.intValue(object["index"])
                guard self.remoteLogExpectedChunks > 0,
                      index >= 0,
                      index < self.remoteLogExpectedChunks,
                      let base64 = object["data"] as? String,
                      let chunk = Data(base64Encoded: base64) else {
                    self.isRemoteLogTransferInProgress = false
                    self.log("[RemoteLog] decode failed")
                    return
                }
                self.remoteLogChunks[index] = chunk
                self.log("[RemoteLog] chunk index=\(index)")

            case "logEnd":
                if object["empty"] as? Bool == true {
                    self.resetRemoteLogTransfer()
                    self.remoteLogText = "Sony 暂无日志"
                    self.remoteLogCopyStatus = ""
                    self.isRemoteLogTransferInProgress = false
                    self.log("[RemoteLog] decode success lines=0")
                } else {
                    self.finishRemoteLogTransfer()
                }

            case "mediaFieldDumpStart":
                let size = Self.intValue(object["size"])
                let chunks = Self.intValue(object["chunks"])
                guard size > 0, chunks > 0 else {
                    self.failMediaFieldDump("invalid start")
                    return
                }
                self.resetMediaFieldDumpTransfer()
                self.mediaFieldDumpExpectedSize = size
                self.mediaFieldDumpExpectedChunks = chunks
                self.mediaFieldDumpText = ""
                self.mediaFieldDumpCopyStatus = ""
                self.isMediaFieldDumpReceiving = true
                self.mediaFieldDumpProgressText = "Media dump receiving..."
                self.log("[MediaDump] start chunks=\(chunks)")

            case "mediaFieldDumpChunk":
                let index = Self.intValue(object["index"])
                guard self.mediaFieldDumpExpectedChunks > 0,
                      index >= 0,
                      index < self.mediaFieldDumpExpectedChunks,
                      let base64 = object["data"] as? String,
                      let chunk = Data(base64Encoded: base64) else {
                    self.failMediaFieldDump("invalid chunk index=\(index)")
                    return
                }
                self.mediaFieldDumpChunks[index] = chunk
                self.mediaFieldDumpProgressText =
                    "Receiving chunk \(self.mediaFieldDumpChunks.count) / " +
                    "\(self.mediaFieldDumpExpectedChunks)"
                self.log("[MediaDump] chunk index=\(index)")

            case "mediaFieldDumpEnd":
                self.log("[MediaDump] end")
                self.finishMediaFieldDumpTransfer()

            case "mediaFieldDumpError":
                let message = object["message"] as? String ?? "unknown error"
                self.mediaFieldDumpText = "Media field dump failed: \(message)"
                self.failMediaFieldDump(message)

            case "playHistoryPage", "playHistorySince", "playStats":
                self.handleHistoryPayload(object)

            case "historyPayloadStart":
                self.handleHistoryPayloadStart(object)

            case "historyPayloadChunk":
                self.handleHistoryPayloadChunk(object)

            case "historyPayloadEnd":
                self.handleHistoryPayloadEnd(object)

            case "playHistoryError":
                let requestId = object["requestId"] as? String ?? ""
                let message = object["message"] as? String ?? "unknown"
                let failedKind = self.pendingHistoryRequests.removeValue(forKey: requestId)
                if case .some(.stats) = failedKind {
                    self.requestNextPlaybackStats()
                } else {
                    self.isPlaybackHistorySyncing = false
                }
                self.isLoadingMoreHistory = false
                self.playbackHistoryStatus = "同步失败：\(message)"
                self.log("[HistorySync] error requestId=\(requestId) message=\(message)")

            default:
                self.log("[Status] unsupported type=\(type)")
            }
        }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }

    private func observeV3StatusMetadata(_ object: [String: Any], type: String) {
        guard negotiatedV3Features.contains(.statusMetaV1),
              let metadata = BLEProtocolV3Parser.statusMetadata(from: object) else {
            return
        }

        let previousSession = serverSessionId
        let observation = eventSequenceDiagnostics.observe(metadata)
        serverSessionId = metadata.sessionId
        lastServerEventSequence = eventSequenceDiagnostics.highestSequence
        switch observation {
        case .first, .inOrder:
            break
        case .newSession:
            log(
                "[BLE-V3] session changed previous=\(previousSession) " +
                    "current=\(metadata.sessionId) type=\(type)"
            )
        case .duplicate:
            log("[BLE-V3] duplicate event sid=\(metadata.sessionId) es=\(metadata.eventSequence) type=\(type)")
        case let .gap(missing):
            log(
                "[BLE-V3] event gap sid=\(metadata.sessionId) " +
                    "es=\(metadata.eventSequence) missing=\(missing) type=\(type)"
            )
        case .outOfOrder:
            log(
                "[BLE-V3] out-of-order event sid=\(metadata.sessionId) " +
                    "es=\(metadata.eventSequence) last=\(lastServerEventSequence) type=\(type)"
            )
        }
    }

    private func handleStructuredCommandError(_ object: [String: Any]) {
        guard negotiatedV3Features.contains(.structuredErrorV1) else {
            log("[BLE-V3] commandError ignored because capability was not negotiated")
            return
        }
        guard let payload = BLEProtocolV3Parser.commandError(from: object) else {
            log("[BLE-V3] malformed commandError ignored")
            return
        }
        guard isCurrentV3MediaPayload(
            trackId: payload.trackId,
            generation: payload.generation
        ) else {
            log(
                "[BLE-V3] stale commandError ignored cmd=\(payload.command) " +
                    "trackId=\(payload.trackId ?? "-") generation=\(payload.generation ?? -1)"
            )
            return
        }

        let correlatedCommand: String = {
            guard let sequence = payload.sequence else { return payload.command }
            if let command = commandWriteInflight.first(where: { $0.seq == sequence })?.cmd {
                return command
            }
            if let command = pendingCommandWrites.first(where: { $0.seq == sequence })?.cmd {
                return command
            }
            return payload.command
        }()
        let retryText = payload.retryable
            ? "，可重试\(payload.retryAfterMs.map { "（\($0)ms 后）" } ?? "")"
            : ""
        lastCommandErrorSummary = "\(correlatedCommand)：\(payload.code)\(retryText)"
        log(
            "[BLE-V3] commandError seq=\(payload.sequence.map { String($0) } ?? "-") " +
                "cmd=\(correlatedCommand) domain=\(payload.domain.rawValue) " +
                "code=\(payload.code) retryable=\(payload.retryable) " +
                "retryAfterMs=\(payload.retryAfterMs ?? -1)"
        )

        switch payload.domain {
        case .lyrics:
            mediaLoadingState.lyric = .failed(reason: payload.code)
        case .artwork:
            mediaLoadingState.artwork = .failed(reason: payload.code)
        case .protocol, .history, .connection, .unknown:
            break
        }
    }

    private func handleNegotiatedMediaLoadState(_ object: [String: Any]) {
        guard negotiatedV3Features.contains(.mediaLoadStateV1) else {
            log("[BLE-V3] mediaLoadState ignored because capability was not negotiated")
            return
        }
        guard let payload = BLEProtocolV3Parser.mediaLoadState(from: object) else {
            log("[BLE-V3] malformed mediaLoadState ignored")
            return
        }
        guard isCurrentV3MediaPayload(
            trackId: payload.trackId,
            generation: payload.generation
        ) else {
            log(
                "[BLE-V3] stale mediaLoadState ignored resource=\(payload.resource.rawValue) " +
                    "trackId=\(payload.trackId) generation=\(payload.generation ?? -1)"
            )
            return
        }
        guard lastMediaLoadStateKeyByResource[payload.resource] != payload.deduplicationKey else {
            log("[BLE-V3] duplicate mediaLoadState ignored key=\(payload.deduplicationKey)")
            return
        }
        lastMediaLoadStateKeyByResource[payload.resource] = payload.deduplicationKey

        switch payload.resource {
        case .lyrics:
            switch payload.stage {
            case .waiting, .preparing:
                mediaLoadingState.lyric = .waitingQqQrc
            case .transferring:
                if fullLyrics.isEmpty {
                    mediaLoadingState.lyric = .fullLyrics(received: 0, expected: 0)
                }
            case .ready:
                let lineCount = fullLyrics.count
                mediaLoadingState.lyric = lineCount > 0
                    ? .ready(lineCount: lineCount)
                    : .windowReady(lineCount: 0)
            case .unavailable, .failed:
                mediaLoadingState.lyric = .failed(reason: payload.reason)
            }
        case .artwork:
            switch payload.stage {
            case .waiting, .preparing, .transferring:
                if artworkDisplayQuality == .placeholder {
                    mediaLoadingState.artwork = .preview(received: 0, expected: 0)
                }
            case .ready:
                mediaLoadingState.artwork = artworkDisplayQuality == .hq
                    ? .hqReady
                    : .previewReady
            case .unavailable, .failed:
                mediaLoadingState.artwork = .failed(reason: payload.reason)
            }
        }
        log(
            "[BLE-V3] mediaLoadState resource=\(payload.resource.rawValue) " +
                "stage=\(payload.stage.rawValue) reason=\(payload.reason) " +
                "trackId=\(payload.trackId) generation=\(payload.generation ?? -1)"
        )
    }

    private func isCurrentV3MediaPayload(trackId: String?, generation: Int64?) -> Bool {
        if let trackId, !trackId.isEmpty,
           !isSameTrackId(incoming: trackId, current: currentTrackID) {
            return false
        }
        if let generation, generation > 0,
           currentTrackGeneration > 0,
           generation != currentTrackGeneration {
            return false
        }
        return true
    }

    private func handleHistoryPayloadStart(_ object: [String: Any]) {
        let requestId = object["requestId"] as? String ?? ""
        let responseType = object["responseType"] as? String ?? ""
        let size = Self.intValue(object["size"])
        let chunks = Self.intValue(object["chunks"])
        guard !requestId.isEmpty, !responseType.isEmpty, size > 0, chunks > 0 else {
            log("[HistorySync] invalid payload start")
            return
        }
        historyPayloads[requestId] = HistoryPayloadAssembly(
            responseType: responseType,
            expectedSize: size,
            expectedChunks: chunks
        )
        log("[HistorySync] payload start requestId=\(requestId) chunks=\(chunks)")
    }

    private func handleHistoryPayloadChunk(_ object: [String: Any]) {
        let requestId = object["requestId"] as? String ?? ""
        let index = Self.intValue(object["index"])
        guard var assembly = historyPayloads[requestId],
              index >= 0,
              index < assembly.expectedChunks,
              let base64 = object["data"] as? String,
              let chunk = Data(base64Encoded: base64) else {
            log("[HistorySync] invalid payload chunk requestId=\(requestId) index=\(index)")
            return
        }
        assembly.chunks[index] = chunk
        historyPayloads[requestId] = assembly
    }

    private func handleHistoryPayloadEnd(_ object: [String: Any]) {
        let requestId = object["requestId"] as? String ?? ""
        guard let assembly = historyPayloads.removeValue(forKey: requestId),
              assembly.chunks.count == assembly.expectedChunks else {
            log("[HistorySync] payload end missing chunks requestId=\(requestId)")
            return
        }
        protocolDecodeQueue.async { [weak self] in
            guard let self else { return }
            let signpost = AppPerformanceLog.protocolSignposter.beginInterval("History Payload Decode")
            var data = Data()
            data.reserveCapacity(assembly.expectedSize)
            for index in 0..<assembly.expectedChunks {
                guard let chunk = assembly.chunks[index] else {
                    AppPerformanceLog.protocolSignposter.endInterval("History Payload Decode", signpost)
                    DispatchQueue.main.async { [weak self] in
                        self?.log("[HistorySync] payload missing chunk requestId=\(requestId) index=\(index)")
                    }
                    return
                }
                data.append(chunk)
            }
            let decoded = data.count == assembly.expectedSize
                ? (try? JSONSerialization.jsonObject(with: data) as? [String: Any])
                : nil
            AppPerformanceLog.protocolSignposter.endInterval("History Payload Decode", signpost)
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard let decoded else {
                    self.log("[HistorySync] payload decode failed requestId=\(requestId)")
                    return
                }
                self.log("[HistorySync] payload decoded requestId=\(requestId) bytes=\(data.count)")
                self.handleHistoryPayload(decoded)
            }
        }
    }

    private func handleHistoryPayload(_ object: [String: Any]) {
        let type = object["type"] as? String ?? ""
        let requestId = object["requestId"] as? String ?? ""
        switch type {
        case "playHistoryPage", "playHistorySince":
            let sessions = decodeHistorySessions(object["items"] as? [[String: Any]] ?? [])
            PlaybackHistoryStore.shared.mergeSessions(sessions) { [weak self] merged in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.playbackHistorySessions = merged
                    self.handleHistoryRequestCompletion(
                        type: type,
                        requestId: requestId,
                        received: sessions.count,
                        response: object
                    )
                }
            }

        case "playStats":
            guard let stats = decodePlaybackStats(object) else {
                pendingHistoryRequests.removeValue(forKey: requestId)
                playbackHistoryStatus = "统计解析失败"
                log("[HistorySync] stats decode failed requestId=\(requestId)")
                requestNextPlaybackStats()
                return
            }
            playbackStats[stats.range] = stats
            PlaybackHistoryStore.shared.saveStats(stats)
            pendingHistoryRequests.removeValue(forKey: requestId)
            playbackHistoryStatus = "统计已更新"
            log("[HistorySync] stats updated range=\(stats.range)")
            requestNextPlaybackStats()

        default:
            log("[HistorySync] unsupported payload type=\(type)")
        }
    }

    private func handleHistoryRequestCompletion(
        type: String,
        requestId: String,
        received: Int,
        response: [String: Any]
    ) {
        let kind = pendingHistoryRequests.removeValue(forKey: requestId)
        let hasMore = response["hasMore"] as? Bool ?? false
        if type == "playHistorySince" {
            let lastSessionId = Self.int64Value(response["lastSessionId"])
            if lastSessionId > lastSyncedHistorySessionId {
                lastSyncedHistorySessionId = lastSessionId
                PlaybackHistoryStore.shared.saveSyncState(
                    PlaybackHistorySyncState(lastSyncedSessionId: lastSessionId)
                )
            }
            log(
                "[HistorySync] since received=\(received) " +
                    "lastSynced=\(lastSyncedHistorySessionId) hasMore=\(hasMore)"
            )
            if hasMore {
                requestPlaybackHistorySince(afterSessionId: lastSyncedHistorySessionId)
            } else {
                isPlaybackHistorySyncing = false
                playbackHistoryStatus = received == 0 ? "已是最新" : "同步完成"
                if refreshStatsAfterHistorySync {
                    refreshStatsAfterHistorySync = false
                    refreshPlaybackStats()
                }
            }
        } else if type == "playHistoryPage" {
            isLoadingMoreHistory = false
            playbackHistoryStatus = received == 0 ? "没有更多历史" : "已加载更多"
            log("[HistorySync] page received=\(received) hasMore=\(hasMore)")
        } else if kind == nil {
            log("[HistorySync] response without pending requestId=\(requestId)")
        }
    }

    private func decodeHistorySessions(_ items: [[String: Any]]) -> [PlaybackHistorySession] {
        items.compactMap { item in
            let sessionId = Self.int64Value(item["sessionId"])
            guard sessionId > 0 else { return nil }
            return PlaybackHistorySession(
                sessionId: sessionId,
                trackKey: item["trackKey"] as? String ?? "",
                title: item["title"] as? String ?? "",
                artist: item["artist"] as? String ?? "",
                album: item["album"] as? String ?? "",
                artworkId: item["artworkId"] as? String,
                startedAt: Self.int64Value(item["startedAt"]),
                endedAt: Self.optionalInt64Value(item["endedAt"]),
                listenedMs: Self.int64Value(item["listenedMs"]),
                durationMs: Self.int64Value(item["durationMs"]),
                completed: item["completed"] as? Bool ?? false,
                skipped: item["skipped"] as? Bool ?? false,
                countedPlay: item["countedPlay"] as? Bool ?? false
            )
        }
    }

    private func decodePlaybackStats(_ object: [String: Any]) -> PlaybackStatsSnapshot? {
        guard let range = object["range"] as? String else { return nil }
        return PlaybackStatsSnapshot(
            range: range,
            rangeStart: Self.int64Value(object["rangeStart"]),
            rangeEnd: Self.int64Value(object["rangeEnd"]),
            totalListenMs: Self.int64Value(object["totalListenMs"]),
            playCount: Self.intValue(object["playCount"]),
            uniqueTrackCount: Self.intValue(object["uniqueTrackCount"]),
            completedCount: Self.intValue(object["completedCount"]),
            skippedCount: Self.intValue(object["skippedCount"]),
            completionRate: Self.doubleValue(object["completionRate"]),
            skipRate: Self.doubleValue(object["skipRate"]),
            topTracks: decodeTopTracks(object["topTracks"] as? [[String: Any]] ?? []),
            topArtists: decodeTopArtists(object["topArtists"] as? [[String: Any]] ?? []),
            dailyTrend: decodeDailyTrend(object["dailyTrend"] as? [[String: Any]] ?? [])
        )
    }

    private func applyTrackInfo(_ object: [String: Any]) {
        let newTitle = object["title"] as? String ?? "-"
        let newArtist = object["artist"] as? String ?? "-"
        let newAlbum = object["album"] as? String ?? "-"
        let trackID = object["trackId"] as? String ?? ""
        let generation = Self.optionalInt64Value(object["generation"]) ?? 0

        // An empty trackId is Sony's explicit "QQ Music has no active media
        // session" response. Never retain the preceding track here: doing so
        // makes subsequent lyric/art requests target an already-stale ID.
        guard !trackID.isEmpty else {
            clearNowPlayingForNoActiveTrack()
            return
        }

        let trackChanged = newTitle != title ||
            newArtist != artist ||
            newAlbum != album ||
            (!trackID.isEmpty && trackID != currentTrackID) ||
            (generation > 0 && currentTrackGeneration > 0 && generation != currentTrackGeneration)
        if trackChanged {
            resetCurrentWordFence()
            lyric = ""
            fullLyricsTrackId = ""
            isFullLyricsCurrent = false
            requestedFullLyricsTrackIDs.removeAll()
            completedFullLyricsTrackIDs.removeAll()
            fullLyricsBinaryFallbackTrackIDs.removeAll()
            fullLyricsBinaryRetryCounts.removeAll()
            fullLyricsBinaryTransfer = nil
            lyricWindowTransfer = nil
            requestedLyricWindowTrackIDs.removeAll()
            fullLyricsUnavailableTrackIDs.removeAll()
            fullLyricsDelayedRetryTrackIDs.removeAll()
            fullLyricsOptionalRefreshTrackIDs.removeAll()
            requestedLyricSecondaryKeys.removeAll()
            completedLyricSecondaryKeys.removeAll()
            ignoredLyricSecondaryPlaceholderKeys.removeAll()
            pendingLyricSecondaryModes.removeAll()
            cancelLyricSecondaryTimeouts()
            lyricSecondaryDeferredRequestWorkItem?.cancel()
            lyricSecondaryDeferredRequestWorkItem = nil
            activeLyricSecondaryRequest = nil
            lyricSecondaryTransfer = nil
            lyricSecondaryRetryCounts.removeAll()
            lyricSecondaryFailureCooldownUntilMs.removeAll()
            translationLyricsState = .idle
            romanizationLyricsState = .idle
            lyricDiagnostic = nil
            lyricDiagnosticLoading = false
            lyricDiagnosticLastUpdatedAt = nil
            mediaLoadingState.lyric = .waitingQqQrc
            mediaLoadingState.artwork = .preview(received: 0, expected: 0)
            log("[Lyrics-iOS] keep previous lyrics until new chunks")
        }
        title = newTitle
        artist = newArtist
        album = newAlbum
        if !trackID.isEmpty {
            currentTrackID = trackID
            currentTrackGeneration = generation
            isShowingLastNowPlayingSnapshot = false
            if trackChanged {
                restoreFullLyricsCacheIfAvailable(
                    trackID: trackID,
                    title: newTitle,
                    artist: newArtist
                )
            }
            let nowMs = currentTimeMs()
            lyricTraceTrackInfoAtMs[trackID] = nowMs
            log(
                "[LyricTrace-iOS] id=\(trackID) stage=trackInfoReceived " +
                    "title=\(newTitle.prefix(32)) artist=\(newArtist.prefix(32)) t=\(nowMs)"
            )
            albumArtReceiver.handleIdentity(id: trackID)
            if serverSupportsLyricWindow {
                requestLyricWindow(trackID: trackID)
            }
            requestFullLyricsIfNeeded(after: isInStartupLoadWindow() ? 0.9 : 0.25)
        }
        log("[TrackInfo] updated title=\(title) artist=\(artist)")
        updateLiveActivity(
            force: trackChanged,
            reason: trackChanged ? "trackInfo" : "trackInfoRefresh"
        )
        scheduleLastNowPlayingSnapshotSave(reason: "trackInfo", force: true)
    }

    private func clearNowPlayingForNoActiveTrack() {
        let hadTrack = !currentTrackID.isEmpty ||
            title != "-" ||
            artist != "-" ||
            album != "-" ||
            !fullLyrics.isEmpty ||
            albumArtReceiver.currentAlbumArtID.isEmpty == false
        guard hadTrack else { return }

        fullLyricsRequestStartTimeouts.values.forEach { $0.cancel() }
        fullLyricsRequestStartTimeouts.removeAll()
        fullLyricsRequestStartRetryCounts.removeAll()
        resetFullLyricsTransfer()
        requestedFullLyricsTrackIDs.removeAll()
        completedFullLyricsTrackIDs.removeAll()
        fullLyricsBinaryFallbackTrackIDs.removeAll()
        fullLyricsBinaryRetryCounts.removeAll()
        fullLyricsBinaryTransfer = nil
        lyricWindowTransfer = nil
        requestedLyricWindowTrackIDs.removeAll()
        fullLyricsUnavailableTrackIDs.removeAll()
        fullLyricsDelayedRetryTrackIDs.removeAll()
        fullLyricsOptionalRefreshTrackIDs.removeAll()
        requestedLyricSecondaryKeys.removeAll()
        completedLyricSecondaryKeys.removeAll()
        ignoredLyricSecondaryPlaceholderKeys.removeAll()
        pendingLyricSecondaryModes.removeAll()
        cancelLyricSecondaryTimeouts()
        lyricSecondaryDeferredRequestWorkItem?.cancel()
        lyricSecondaryDeferredRequestWorkItem = nil
        activeLyricSecondaryRequest = nil
        lyricSecondaryTransfer = nil
        lyricSecondaryRetryCounts.removeAll()
        lyricSecondaryFailureCooldownUntilMs.removeAll()
        translationLyricsState = .idle
        romanizationLyricsState = .idle
        lyricTraceFullLyricsRequestAtMs.removeAll()
        lyricTraceFullLyricsStartAtMs.removeAll()
        lyricTraceFirstPlaybackLyricAtMs.removeAll()

        currentTrackID = ""
        currentTrackGeneration = 0
        title = "-"
        artist = "-"
        album = "-"
        lyric = ""
        fullLyrics = []
        fullLyricsTrackId = ""
        isFullLyricsCurrent = false
        lyricDiagnostic = nil
        lyricDiagnosticLoading = false
        lyricDiagnosticLastUpdatedAt = nil
        isPlaying = false
        updateProgressTimerState()
        positionMs = 0
        displayPositionMs = 0
        seekPositionMs = 0
        durationMs = 0
        basePlaybackPositionMs = 0
        currentWordLineIndex = -1
        currentWordIndex = -1
        resetCurrentWordFence()
        isShowingLastNowPlayingSnapshot = false
        mediaLoadingState = MediaLoadingState()
        LastNowPlayingSnapshotStore.shared.clear()
        albumArtReceiver.clearCurrentIdentity(reason: "no active QQ track")
        log("[TrackInfo] cleared stale now-playing state reason=no active QQ track")
        updateLiveActivity(force: true, reason: "noActiveTrack")
    }

    private func updateLiveActivity(force: Bool, reason: String) {
        if force {
            pendingLiveActivityUpdateWorkItem?.cancel()
            pendingLiveActivityUpdateWorkItem = nil
        } else {
            let minimumInterval = minimumLiveActivityUpdateInterval
            let elapsed = Date().timeIntervalSince(lastLiveActivityRequestAt)
            if elapsed < minimumInterval {
                pendingLiveActivityUpdateWorkItem?.cancel()
                let workItem = DispatchWorkItem { [weak self] in
                    self?.pendingLiveActivityUpdateWorkItem = nil
                    self?.updateLiveActivity(force: false, reason: "coalesced:\(reason)")
                }
                pendingLiveActivityUpdateWorkItem = workItem
                DispatchQueue.main.asyncAfter(
                    deadline: .now() + max(0.05, minimumInterval - elapsed),
                    execute: workItem
                )
                return
            }
        }
        let snapshotTitle = title
        let snapshotArtist = artist
        let snapshotIsPlaying = isPlaying
        let snapshotPositionMs = displayPositionMs
        let snapshotDurationMs = durationMs
        let snapshotTrackID = currentTrackID
        let snapshotArtworkKey = currentLiveArtworkKey
        let snapshotArtworkRevision = currentLiveArtworkRevision
        let resolvedLyric = resolveCurrentLyric(
            positionMs: snapshotPositionMs,
            fullLyrics: fullLyrics,
            playbackStateLyric: lyric
        )
        lastLiveActivityRequestAt = Date()
        lastLiveActivityRequestTrackID = snapshotTrackID

        Task { @MainActor in
            LiveActivityManager.shared.update(
                title: snapshotTitle,
                artist: snapshotArtist,
                lyric: resolvedLyric.text,
                lyricLineIndex: resolvedLyric.lineIndex,
                isPlaying: snapshotIsPlaying,
                positionMs: snapshotPositionMs,
                durationMs: snapshotDurationMs,
                trackId: snapshotTrackID,
                artworkKey: snapshotArtworkKey,
                artworkRevision: snapshotArtworkRevision,
                connectionState: self.liveActivityConnectionState,
                appState: self.appLifecycleState,
                reason: reason,
                force: force,
                logger: { [weak self] message in
                    self?.log(message)
                }
            )
        }
    }

    private var minimumLiveActivityUpdateInterval: TimeInterval {
        switch preferences.playbackPerformanceMode {
        case .smooth:
            return 0.4
        case .automatic:
            return appLifecycleState == "active" ? 0.8 : 1.8
        case .powerSaving:
            return appLifecycleState == "active" ? 2.0 : 4.0
        }
    }

    private func updateLiveActivityForCurrentLyricIfNeeded(reason: String) -> Bool {
        let resolved = resolveCurrentLyric(
            positionMs: displayPositionMs,
            fullLyrics: fullLyrics,
            playbackStateLyric: lyric
        )
        let lineChanged = resolved.trackId != lastLiveActivityLyricTrackID ||
            resolved.lineIndex != lastLiveActivityLyricLineIndex ||
            resolved.text != lastLiveActivityLyricText

        guard lineChanged else {
            if reason == "currentWord" {
                logLiveActivityCurrentWordSkipped(resolved: resolved)
            }
            return false
        }

        lastLiveActivityLyricTrackID = resolved.trackId
        lastLiveActivityLyricLineIndex = resolved.lineIndex
        lastLiveActivityLyricText = resolved.text
        log(
            "[Lyrics-Live] line changed trackId=\(resolved.trackId) " +
                "appState=\(appLifecycleState) index=\(resolved.lineIndex) text=\(resolved.text)"
        )
        log(
            "[Lyrics-Live] source=\(resolved.source) " +
                "rawPositionMs=\(displayPositionMs) " +
                "effectivePositionMs=\(karaokePositionMs(rawPositionMs: displayPositionMs)) " +
                "reason=\(reason)"
        )
        updateLiveActivity(force: false, reason: "lyricChanged")
        return true
    }

    private func logLiveActivityCurrentWordSkipped(resolved: ResolvedLyric) {
        let nowMs = currentTimeMs()
        guard nowMs - lastLiveActivityCurrentWordSkipLogAtMs >= 5_000 else { return }
        lastLiveActivityCurrentWordSkipLogAtMs = nowMs
        let throttleMs = appLifecycleState == "active" ? 1_000 : 1_800
        log(
            "[LiveActivityPerf] currentWord same-line skipped " +
                "trackId=\(resolved.trackId) lineIndex=\(resolved.lineIndex) " +
                "appState=\(appLifecycleState) throttleMs=\(throttleMs)"
        )
    }

    private func shouldRequestLiveActivityProgressUpdate(oldPositionMs: Int64) -> Bool {
        let now = Date()
        if currentTrackID != lastLiveActivityRequestTrackID {
            return true
        }

        let secondsSinceLastRequest = now.timeIntervalSince(lastLiveActivityRequestAt)
        let sonyJumpMs = abs(positionMs - oldPositionMs)
        if sonyJumpMs > 2_500, secondsSinceLastRequest >= 3 {
            log("[LiveActivityPerf] progress request reason=drift sonyJumpMs=\(sonyJumpMs)")
            return true
        }

        let progressInterval: TimeInterval
        switch preferences.playbackPerformanceMode {
        case .smooth:
            progressInterval = 10
        case .automatic:
            progressInterval = 15
        case .powerSaving:
            progressInterval = 30
        }
        if secondsSinceLastRequest >= progressInterval {
            log("[LiveActivityPerf] progress request reason=interval")
            return true
        }

        return false
    }

    private var liveActivityConnectionState: String {
        switch connectionDisplayState {
        case ConnectionDisplayState.connected.rawValue:
            return "connected"
        case ConnectionDisplayState.reconnecting.rawValue:
            return "reconnecting"
        default:
            return "disconnected"
        }
    }

    private func currentLyricIndex(lines: [LyricLine], positionMs: Int64) -> Int? {
        guard !lines.isEmpty else { return nil }
        if positionMs < lines[0].timeMs {
            return 0
        }

        var low = 0
        var high = lines.count - 1
        var result = 0
        while low <= high {
            let mid = (low + high) / 2
            if lines[mid].timeMs <= positionMs {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private func updateLiveActivityDisconnected() {
        let snapshotTitle = title
        let snapshotArtist = artist
        let snapshotPositionMs = displayPositionMs
        let snapshotDurationMs = durationMs
        let snapshotTrackID = currentTrackID
        let snapshotArtworkKey = currentLiveArtworkKey
        let snapshotArtworkRevision = currentLiveArtworkRevision

        Task { @MainActor in
            LiveActivityManager.shared.update(
                title: snapshotTitle,
                artist: snapshotArtist,
                lyric: "连接已断开",
                lyricLineIndex: -1,
                isPlaying: false,
                positionMs: snapshotPositionMs,
                durationMs: snapshotDurationMs,
                trackId: snapshotTrackID,
                artworkKey: snapshotArtworkKey,
                artworkRevision: snapshotArtworkRevision,
                connectionState: "disconnected",
                appState: self.appLifecycleState,
                reason: "disconnect",
                force: true,
                logger: { [weak self] message in
                    self?.log(message)
                }
            )
        }
    }

    private func requestFullLyricsIfNeeded(
        force: Bool = false,
        after delay: TimeInterval = 0
    ) {
        let trackID = currentTrackID
        guard !trackID.isEmpty else { return }
        let nowMs = currentTimeMs()
        if !force,
           completedFullLyricsTrackIDs.contains(trackID) {
            return
        }
        if isFullLyricsReceiving,
           fullLyricsReceivingTrackID == trackID {
            log(
                "[FullLyrics] request skipped reason=receiving " +
                    "trackId=\(trackID) force=\(force)"
            )
            return
        }
        if requestedFullLyricsTrackIDs.contains(trackID) {
            let previousMs = fullLyricsRequestCreatedAtMs[trackID] ??
                lyricTraceFullLyricsRequestAtMs[trackID] ??
                nowMs
            let ageMs = nowMs - previousMs
            if !force || ageMs < FULL_LYRICS_REQUEST_DEDUP_WINDOW_MS {
                log(
                    "[FullLyrics] request skipped reason=dedup " +
                        "trackId=\(trackID) force=\(force) ageMs=\(ageMs)"
                )
                return
            }
        }
        requestedFullLyricsTrackIDs.insert(trackID)
        fullLyricsRequestCreatedAtMs[trackID] = nowMs
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self else { return }
            guard self.currentTrackID == trackID else { return }
            let nowMs = self.currentTimeMs()
            self.lyricTraceFullLyricsRequestAtMs[trackID] = nowMs
            var detail =
                "[LyricTrace-iOS] id=\(trackID) stage=requestFullLyrics " +
                "positionMs=\(self.displayPositionMs)"
            if let trackAt = self.lyricTraceTrackInfoAtMs[trackID] {
                detail += " sinceTrackInfoMs=\(nowMs - trackAt)"
            }
            self.log(detail)
            self.log("[LyricsPerf] request fullLyrics trackId=\(trackID)")
            var extra: [String: Any] = [
                "trackId": trackID,
                "positionMs": self.displayPositionMs,
                "includeWordsAroundCurrent": true
            ]
            if self.serverSupportsFullLyricsZlib,
               !self.fullLyricsBinaryFallbackTrackIDs.contains(trackID) {
                extra["format"] = "zlib-json-v1"
            }
            self.sendCommand(
                cmd: "GET_FULL_LYRICS",
                extra: extra
            )
            self.scheduleFullLyricsRequestStartTimeout(trackID: trackID)
        }
    }

    private func scheduleFullLyricsRequestStartTimeout(trackID: String) {
        fullLyricsRequestStartTimeouts[trackID]?.cancel()
        let timeout = DispatchWorkItem { [weak self] in
            guard let self,
                  self.currentTrackID == trackID,
                  self.requestedFullLyricsTrackIDs.contains(trackID),
                  !self.isFullLyricsReceiving else {
                return
            }
            self.fullLyricsRequestStartTimeouts.removeValue(forKey: trackID)
            self.requestedFullLyricsTrackIDs.remove(trackID)
            let attempts = (self.fullLyricsRequestStartRetryCounts[trackID] ?? 0) + 1
            self.fullLyricsRequestStartRetryCounts[trackID] = attempts
            guard attempts <= FULL_LYRICS_REQUEST_START_MAX_RETRIES else {
                self.log("[FullLyrics] request start timeout trackId=\(trackID), giving up")
                return
            }
            self.log("[FullLyrics] request start timeout trackId=\(trackID), retry=\(attempts)")
            self.requestFullLyricsIfNeeded(force: true, after: 0.5)
        }
        fullLyricsRequestStartTimeouts[trackID] = timeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(Int(FULL_LYRICS_REQUEST_START_TIMEOUT_MS)),
            execute: timeout
        )
    }

    private func retryFullLyricsIfLyricsBecameAvailable(
        oldLyric: String,
        newLyric: String
    ) -> Bool {
        let trackID = currentTrackID
        guard !trackID.isEmpty else { return false }
        guard fullLyrics.isEmpty else { return false }
        guard fullLyricsUnavailableTrackIDs.contains(trackID) else { return false }
        let oldText = oldLyric.trimmingCharacters(in: .whitespacesAndNewlines)
        let newText = newLyric.trimmingCharacters(in: .whitespacesAndNewlines)
        guard oldText.isEmpty, !newText.isEmpty else { return false }
        guard !fullLyricsDelayedRetryTrackIDs.contains(trackID) else {
            log("[Lyrics-iOS] retry skipped reason=already retried trackId=\(trackID)")
            return false
        }
        fullLyricsDelayedRetryTrackIDs.insert(trackID)
        log("[Lyrics-iOS] delayed lyrics became available trackId=\(trackID)")
        if serverSupportsLyricWindow {
            // The first window request commonly arrives before QQ Music has
            // finished writing its QRC file. Re-issue it as soon as the live
            // lyric proves the runtime index is ready, ahead of the bulk body.
            requestedLyricWindowTrackIDs.remove(trackID)
            requestLyricWindow(trackID: trackID)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self else { return }
            guard self.currentTrackID == trackID,
                  self.fullLyrics.isEmpty else { return }
            self.log("[Lyrics-iOS] retry GET_FULL_LYRICS trackId=\(trackID)")
            self.requestFullLyricsIfNeeded(force: true)
        }
        return true
    }

    private func requestLyricWindow(trackID: String) {
        guard serverProtocolVersion >= 2,
              serverSupportsLyricWindow,
              trackID == currentTrackID,
              !trackID.isEmpty,
              !requestedLyricWindowTrackIDs.contains(trackID) else { return }
        requestedLyricWindowTrackIDs.insert(trackID)
        sendCommand(
            cmd: "GET_LYRIC_WINDOW",
            extra: [
                "trackId": trackID,
                "positionMs": displayPositionMs
            ]
        )
        log("[LyricWindow-iOS] requested trackId=\(trackID)")
    }

    private func handleLyricWindowStart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let transferID = object["transferId"] as? String ?? ""
        let count = Self.intValue(object["count"])
        guard trackID == currentTrackID,
              !transferID.isEmpty,
              count > 0,
              count <= 5 else { return }
        lyricWindowTransfer = LyricWindowTransfer(
            trackId: trackID,
            transferId: transferID,
            generation: Self.int64Value(object["generation"]),
            expectedCount: count
        )
    }

    private func handleLyricWindowChunk(_ object: [String: Any]) {
        guard var transfer = lyricWindowTransfer,
              transfer.trackId == currentTrackID,
              object["trackId"] as? String == transfer.trackId,
              object["transferId"] as? String == transfer.transferId else { return }
        let index = Self.intValue(object["index"])
        guard index >= 0 else { return }
        transfer.chunks[index] = LyricLine(
            index: index,
            timeMs: Self.int64Value(object["timeMs"]),
            durationMs: Self.int64Value(object["durationMs"]),
            text: object["text"] as? String ?? "",
            translation: nil,
            romanization: nil,
            words: []
        )
        lyricWindowTransfer = transfer
    }

    private func handleLyricWindowEnd(_ object: [String: Any]) {
        guard let transfer = lyricWindowTransfer,
              transfer.trackId == currentTrackID,
              object["trackId"] as? String == transfer.trackId,
              object["transferId"] as? String == transfer.transferId,
              Self.int64Value(object["generation"]) == transfer.generation else {
            lyricWindowTransfer = nil
            return
        }
        lyricWindowTransfer = nil
        guard transfer.chunks.count == transfer.expectedCount else {
            log(
                "[LyricWindow-iOS] incomplete received=\(transfer.chunks.count) " +
                    "expected=\(transfer.expectedCount)"
            )
            return
        }
        publishFullLyrics(
            lines: Array(transfer.chunks.values),
            trackID: transfer.trackId,
            isFinal: false
        )
        mediaLoadingState.lyric = .windowReady(lineCount: transfer.chunks.count)
        log("[LyricWindow-iOS] published lines=\(transfer.chunks.count)")
    }

    private func handleFullLyricsBinaryStart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? object["id"] as? String ?? ""
        guard !trackID.isEmpty, trackID == currentTrackID else {
            log(
                "[FullLyricsV2-iOS] ignored stale start trackId=\(trackID) " +
                    "current=\(currentTrackID)"
            )
            return
        }
        let transferID = object["transferId"] as? String ?? object["tid"] as? String ?? ""
        let expectedSize = Self.intValue(object["size"] ?? object["s"])
        let uncompressedSize = Self.intValue(object["uncompressedSize"] ?? object["u"])
        let chunks = Self.intValue(object["chunks"] ?? object["c"])
        let count = Self.intValue(object["count"] ?? object["n"])
        let crcText = object["crc32"] as? String ?? object["crc"] as? String ?? ""
        guard !transferID.isEmpty,
              expectedSize > 0,
              expectedSize <= 24 * 1024,
              uncompressedSize > 0,
              uncompressedSize <= 512 * 1024,
              chunks > 0,
              chunks <= 0xffff,
              count > 0,
              let crc = UInt32(crcText, radix: 16) else {
            fallbackFromFullLyricsBinary(trackID: trackID, reason: "invalid start")
            return
        }
        fullLyricsRequestStartTimeouts.removeValue(forKey: trackID)?.cancel()
        fullLyricsRequestStartRetryCounts.removeValue(forKey: trackID)
        fullLyricsTimeoutWorkItem?.cancel()
        fullLyricsReceivingTrackID = trackID
        fullLyricsExpectedCount = count
        fullLyricsChunks.removeAll()
        fullLyricsBinaryTransfer = FullLyricsBinaryTransfer(
            trackId: trackID,
            transferId: transferID,
            generation: Self.int64Value(object["generation"] ?? object["g"]),
            expectedSize: expectedSize,
            uncompressedSize: uncompressedSize,
            expectedChunks: chunks,
            expectedLineCount: count,
            expectedCRC32: crc
        )
        isFullLyricsReceiving = true
        mediaLoadingState.lyric = .fullLyrics(
            received: 0,
            expected: fullLyricsExpectedCount
        )
        scheduleFullLyricsBinaryTimeout(trackID: trackID, transferID: transferID)
        log(
            "[FullLyricsV2-iOS] start transferId=\(transferID) " +
                "size=\(expectedSize) chunks=\(chunks) lines=\(count)"
        )
    }

    private func handleFullLyricsBinaryChunk(_ data: Data) {
        guard let chunk = BLEBinaryChunkCodec.decode(data, expectedMagic: 0xA2),
              chunk.kindCode == 1,
              var transfer = fullLyricsBinaryTransfer,
              transfer.trackId == currentTrackID else { return }
        let index = chunk.index
        let total = chunk.total
        guard total == transfer.expectedChunks,
              index >= 0,
              index < transfer.expectedChunks else {
            log("[FullLyricsV2-iOS] rejected header index=\(index) total=\(total)")
            return
        }
        transfer.chunks[index] = chunk.payload
        fullLyricsBinaryTransfer = transfer
        if index == 0 || index == total - 1 || index % 10 == 0 {
            log(
                "[FullLyricsV2-iOS] chunk=\(index) " +
                    "received=\(transfer.chunks.count)/\(total)"
            )
            mediaLoadingState.lyric = .fullLyrics(
                received: transfer.chunks.count,
                expected: total
            )
        }
    }

    private func handleFullLyricsBinaryEnd(_ object: [String: Any]) {
        guard let transfer = fullLyricsBinaryTransfer,
              transfer.trackId == currentTrackID,
              (object["trackId"] as? String ?? object["id"] as? String) == transfer.trackId,
              (object["transferId"] as? String ?? object["tid"] as? String) == transfer.transferId,
              Self.int64Value(object["generation"] ?? object["g"]) == transfer.generation,
              fullLyricsBinaryDecodingTransferID != transfer.transferId else {
            return
        }
        fullLyricsTimeoutWorkItem?.cancel()
        let missing = BLEBinaryChunkCodec.missingIndexes(
            chunks: transfer.chunks,
            expectedCount: transfer.expectedChunks
        )
        if !missing.isEmpty {
            requestFullLyricsBinaryRetry(
                transfer: transfer,
                missing: missing,
                retryAll: missing.count > 32,
                reason: "missing chunks"
            )
            return
        }
        fullLyricsBinaryDecodingTransferID = transfer.transferId
        protocolDecodeQueue.async {
            let result = Self.decodeFullLyricsBinaryTransfer(transfer)
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      self.currentTrackID == transfer.trackId,
                      self.fullLyricsBinaryTransfer?.transferId == transfer.transferId,
                      self.fullLyricsBinaryDecodingTransferID == transfer.transferId else {
                    return
                }
                self.fullLyricsBinaryDecodingTransferID = nil
                switch result {
                case let .success(lines, compressedSize):
                    self.fullLyricsBinaryRetryCounts.removeValue(forKey: transfer.transferId)
                    self.publishFullLyrics(
                        lines: lines,
                        trackID: transfer.trackId,
                        isFinal: true
                    )
                    self.fullLyricsUnavailableTrackIDs.remove(transfer.trackId)
                    self.log(
                        "[FullLyricsV2-iOS] complete transferId=\(transfer.transferId) " +
                            "compressed=\(compressedSize) lines=\(lines.count)"
                    )
                    self.resetFullLyricsTransfer()
                case let .failure(reason):
                    self.requestFullLyricsBinaryRetry(
                        transfer: transfer,
                        missing: [],
                        retryAll: true,
                        reason: reason
                    )
                }
            }
        }
    }

    private static func decodeFullLyricsBinaryTransfer(
        _ transfer: FullLyricsBinaryTransfer
    ) -> FullLyricsBinaryDecodeResult {
        guard let compressed = BLEBinaryChunkCodec.reassemble(
            chunks: transfer.chunks,
            expectedCount: transfer.expectedChunks
        ) else {
            return .failure(reason: "reassemble failed")
        }
        guard compressed.count == transfer.expectedSize else {
            return .failure(reason: "size mismatch")
        }
        guard crc32(compressed) == transfer.expectedCRC32 else {
            return .failure(reason: "crc mismatch")
        }
        guard let raw = zlibDecompress(
                compressed,
                expectedSize: transfer.uncompressedSize
              ),
              let payload = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
              payload["trackId"] as? String == transfer.trackId,
              int64Value(payload["generation"]) == transfer.generation,
              let lineObjects = payload["lines"] as? [[String: Any]] else {
            return .failure(reason: "decode failed")
        }
        let lines = lineObjects.compactMap(decodeLyricLine)
        guard lines.count == transfer.expectedLineCount else {
            return .failure(reason: "line count mismatch")
        }
        return .success(lines: lines, compressedSize: compressed.count)
    }

    private func requestFullLyricsBinaryRetry(
        transfer: FullLyricsBinaryTransfer,
        missing: [Int],
        retryAll: Bool,
        reason: String
    ) {
        let retryCount = fullLyricsBinaryRetryCounts[transfer.transferId] ?? 0
        guard serverSupportsTransferRetry, retryCount < 1 else {
            fallbackFromFullLyricsBinary(trackID: transfer.trackId, reason: reason)
            return
        }
        fullLyricsBinaryRetryCounts[transfer.transferId] = retryCount + 1
        sendCommand(
            cmd: "RETRY_TRANSFER",
            extra: [
                "trackId": transfer.trackId,
                "transferId": transfer.transferId,
                "missing": missing,
                "retryAll": retryAll
            ]
        )
        scheduleFullLyricsBinaryTimeout(
            trackID: transfer.trackId,
            transferID: transfer.transferId
        )
        log(
            "[FullLyricsV2-iOS] retry reason=\(reason) " +
                "retryAll=\(retryAll) missing=\(missing.count)"
        )
    }

    private func scheduleFullLyricsBinaryTimeout(trackID: String, transferID: String) {
        fullLyricsTimeoutWorkItem?.cancel()
        let timeout = DispatchWorkItem { [weak self] in
            guard let self,
                  self.currentTrackID == trackID,
                  self.fullLyricsBinaryTransfer?.transferId == transferID else { return }
            self.fallbackFromFullLyricsBinary(trackID: trackID, reason: "timeout")
        }
        fullLyricsTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
    }

    private func handleFullLyricsBinaryError(_ object: [String: Any]) {
        let trackID = fullLyricsBinaryTransfer?.trackId ?? currentTrackID
        fallbackFromFullLyricsBinary(
            trackID: trackID,
            reason: object["reason"] as? String ?? "server error"
        )
    }

    private func fallbackFromFullLyricsBinary(trackID: String, reason: String) {
        guard !trackID.isEmpty, trackID == currentTrackID else {
            resetFullLyricsTransfer()
            return
        }
        fullLyricsBinaryFallbackTrackIDs.insert(trackID)
        requestedFullLyricsTrackIDs.remove(trackID)
        resetFullLyricsTransfer()
        log("[FullLyricsV2-iOS] fallback legacy reason=\(reason) trackId=\(trackID)")
        requestFullLyricsIfNeeded(force: true, after: 0.1)
    }

    private static func decodeLyricLine(_ object: [String: Any]) -> LyricLine? {
        let index = intValue(object["index"])
        guard index >= 0 else { return nil }
        return LyricLine(
            index: index,
            timeMs: int64Value(object["timeMs"]),
            durationMs: int64Value(object["durationMs"]),
            text: object["text"] as? String ?? "",
            translation: object["translation"] as? String,
            romanization: object["romanization"] as? String,
            words: parseLyricWords(object["words"])
        )
    }

    static func zlibDecompress(_ input: Data, expectedSize: Int) -> Data? {
        guard !input.isEmpty, expectedSize > 0 else { return nil }
        var output = Data(count: expectedSize)
        var decodedSize = UInt(expectedSize)
        let status = output.withUnsafeMutableBytes { outputBuffer in
            input.withUnsafeBytes { inputBuffer in
                guard let outputBase = outputBuffer.bindMemory(to: UInt8.self).baseAddress,
                      let inputBase = inputBuffer.bindMemory(to: UInt8.self).baseAddress else {
                    return Int32(-1)
                }
                return zlibUncompress(
                    outputBase,
                    &decodedSize,
                    inputBase,
                    UInt(input.count)
                )
            }
        }
        guard status == 0, decodedSize == UInt(expectedSize) else { return nil }
        return output
    }

    static func crc32(_ data: Data) -> UInt32 {
        BLEBinaryChunkCodec.crc32(data)
    }

    private func handleFullLyricsStart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        guard !trackID.isEmpty, trackID == currentTrackID else {
            log("[FullLyrics] stale start ignored trackId=\(trackID)")
            return
        }
        fullLyricsUnavailableTrackIDs.remove(trackID)
        fullLyricsRequestStartTimeouts.removeValue(forKey: trackID)?.cancel()
        fullLyricsRequestStartRetryCounts.removeValue(forKey: trackID)
        fullLyricsTimeoutWorkItem?.cancel()
        fullLyricsReceivingTrackID = trackID
        fullLyricsExpectedCount = Self.intValue(object["count"])
        fullLyricsChunks.removeAll()
        isFullLyricsReceiving = true
        lastFullLyricsPartialPublishAtMs = 0
        let nowMs = currentTimeMs()
        lyricTraceFullLyricsStartAtMs[trackID] = nowMs
        var traceDetail =
            "[LyricTrace-iOS] id=\(trackID) stage=fullLyricsStart " +
            "count=\(fullLyricsExpectedCount)"
        if let requestAt = lyricTraceFullLyricsRequestAtMs[trackID] {
            traceDetail += " sinceRequestMs=\(nowMs - requestAt)"
        }
        log(traceDetail)
        log("[FullLyrics] start trackId=\(trackID) count=\(fullLyricsExpectedCount)")
        log("[LyricsPerf] receive start count=\(fullLyricsExpectedCount)")

        let timeout = DispatchWorkItem { [weak self] in
            guard let self else { return }
            guard self.isFullLyricsReceiving,
                  self.fullLyricsReceivingTrackID == trackID else { return }
            self.resetFullLyricsTransfer()
            self.requestedFullLyricsTrackIDs.remove(trackID)
            self.log("[FullLyrics] timeout discard trackId=\(trackID)")
        }
        fullLyricsTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
    }

    private func handleFullLyricsChunk(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        guard isFullLyricsReceiving,
              trackID == fullLyricsReceivingTrackID,
              trackID == currentTrackID else {
            return
        }
        let index = Self.intValue(object["index"])
        guard index >= 0, index < fullLyricsExpectedCount else { return }
        let words = Self.parseLyricWords(object["words"])
        fullLyricsChunks[index] = LyricLine(
            index: index,
            timeMs: Self.int64Value(object["timeMs"]),
            durationMs: Self.int64Value(object["durationMs"]),
            text: object["text"] as? String ?? "",
            translation: sanitizedSecondaryText(object["translation"] as? String),
            romanization: sanitizedSecondaryText(object["romanization"] as? String),
            words: words
        )
        if index == 0 || index == fullLyricsExpectedCount - 1 || index % 10 == 0 {
            log(
                "[Lyrics-iOS] chunk index=\(index) " +
                    "received=\(fullLyricsChunks.count)/\(fullLyricsExpectedCount) " +
                    "words=\(words.count)"
            )
            mediaLoadingState.lyric = .fullLyrics(
                received: fullLyricsChunks.count,
                expected: fullLyricsExpectedCount
            )
        }
        publishPartialFullLyricsIfNeeded(trackID: trackID)
    }

    private func handleFullLyricsEnd(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        guard isFullLyricsReceiving,
              trackID == fullLyricsReceivingTrackID,
              trackID == currentTrackID else {
            log("[FullLyrics] stale end ignored trackId=\(trackID)")
            return
        }
        fullLyricsTimeoutWorkItem?.cancel()
        let lines = (0..<fullLyricsExpectedCount).compactMap {
            fullLyricsChunks[$0]
        }
        if lines.count == fullLyricsExpectedCount {
            let nowMs = currentTimeMs()
            var traceDetail =
                "[LyricTrace-iOS] id=\(trackID) stage=fullLyricsFinal " +
                "lines=\(lines.count)"
            if let requestAt = lyricTraceFullLyricsRequestAtMs[trackID] {
                traceDetail += " sinceRequestMs=\(nowMs - requestAt)"
            }
            if let startAt = lyricTraceFullLyricsStartAtMs[trackID] {
                traceDetail += " receiveCostMs=\(nowMs - startAt)"
            }
            log(traceDetail)
            publishFullLyrics(
                lines: lines,
                trackID: trackID,
                isFinal: true
            )
            fullLyricsUnavailableTrackIDs.remove(trackID)
        } else {
            requestedFullLyricsTrackIDs.remove(trackID)
            log(
                "[FullLyrics] incomplete received=\(lines.count) " +
                    "expected=\(fullLyricsExpectedCount)"
            )
        }
        resetFullLyricsTransfer()
    }

    private func updateLightweightLyricDiagnostic(from object: [String: Any]) {
        let status = object["lyricStatus"] as? String ?? ""
        let reason = object["lyricReason"] as? String ?? ""
        let suggestion = object["lyricSuggestion"] as? String ?? ""
        guard !status.isEmpty || !reason.isEmpty else { return }
        let trackID = currentTrackID
        guard !trackID.isEmpty else { return }
        let diagnostic = LyricDiagnostic.lightweight(
            trackId: trackID,
            title: title,
            artist: artist,
            status: normalizedLyricDiagnosticStatus(status),
            reason: reason,
            suggestion: suggestion
        )
        lyricDiagnostic = diagnostic
        lyricDiagnosticLastUpdatedAt = Date()
        if diagnostic.status == "waiting_qqmusic_cache" ||
            diagnostic.status == "loading" ||
            diagnostic.status == "retry_pending" {
            mediaLoadingState.lyric = .waitingQqQrc
        }
        if lyric.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           fullLyrics.isEmpty {
            requestLyricDiagnosticIfNeeded()
        }
    }

    private func requestLyricDiagnosticIfNeeded() {
        requestLyricDiagnostic(manual: false)
    }

    private func handleLyricDiagnostic(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        guard trackID == currentTrackID else {
            log("[LyricsDiag-iOS] discarded stale trackId=\(trackID)")
            return
        }
        lyricDiagnostic = parseLyricDiagnostic(object)
        lyricDiagnosticLoading = false
        lyricDiagnosticLastUpdatedAt = Date()
        if let diagnostic = lyricDiagnostic {
            if diagnostic.status == "waiting_qqmusic_cache" ||
                diagnostic.status == "loading" ||
                diagnostic.status == "retry_pending" {
                mediaLoadingState.lyric = .waitingQqQrc
            } else if diagnostic.status != "loaded",
                      fullLyrics.isEmpty {
                mediaLoadingState.lyric = .failed(reason: diagnostic.reason)
            }
        }
        log(
            "[LyricsDiag-iOS] received status=\(lyricDiagnostic?.status ?? "") " +
                "reason=\(lyricDiagnostic?.reason ?? "")"
        )
    }

    private func handleLyricDiagnosticUnavailable(_ object: [String: Any]) {
        lyricDiagnosticLoading = false
        let reason = object["reason"] as? String ?? "unavailable"
        log("[LyricsDiag-iOS] unavailable reason=\(reason)")
    }

    private func parseLyricDiagnostic(_ object: [String: Any]) -> LyricDiagnostic {
        LyricDiagnostic(
            trackId: object["trackId"] as? String ?? "",
            songKey: object["songKey"] as? String ?? "",
            title: object["title"] as? String ?? title,
            artist: object["artist"] as? String ?? artist,
            status: normalizedLyricDiagnosticStatus(object["status"] as? String ?? ""),
            source: object["source"] as? String ?? "",
            reason: object["reason"] as? String ?? "",
            lines: Self.intValue(object["lines"]),
            lastAttemptAt: Self.int64Value(object["lastAttemptAt"]),
            nextRetryAt: Self.int64Value(object["nextRetryAt"]),
            retryCount: Self.intValue(object["retryCount"]),
            cooldownUntil: Self.int64Value(object["cooldownUntil"]),
            fuzzyIndexReady: object["fuzzyIndexReady"] as? Bool ?? false,
            qrcIndexLoaded: object["qrcIndexLoaded"] as? Bool ?? false,
            maintenanceBusy: object["maintenanceBusy"] as? Bool ?? false,
            waitingQqMusicCache: object["waitingQqMusicCache"] as? Bool ?? false,
            suggestion: object["suggestion"] as? String ?? "",
            recoveryState: object["recoveryState"] as? String ?? "unknown",
            recoveryRetryCount: Self.intValue(object["recoveryRetryCount"]),
            recoveryExpiresAt: Self.int64Value(object["recoveryExpiresAt"]),
            lastRecoveryReason: object["lastRecoveryReason"] as? String ?? "",
            recentQrcCandidateCount: Self.intValue(object["recentQrcCandidateCount"])
        )
    }

    private func normalizedLyricDiagnosticStatus(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch trimmed {
        case "waiting qqmusic lyric cache":
            return "waiting_qqmusic_cache"
        case "maintenance busy":
            return "maintenance_busy"
        case "cooldown retry pending", "lyrics retry pending":
            return "retry_pending"
        case "no safe qrc candidate":
            return "no_safe_candidate"
        case "no parsed lyrics", "no lyrics final":
            return "no_lyrics_final"
        default:
            return trimmed.replacingOccurrences(of: " ", with: "_")
        }
    }

    private func handleFullLyricsUnavailable(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        guard trackID == currentTrackID else { return }
        requestedFullLyricsTrackIDs.remove(trackID)
        fullLyricsUnavailableTrackIDs.insert(trackID)
        resetFullLyricsTransfer()
        if fullLyricsTrackId != trackID {
            fullLyrics = []
            fullLyricsTrackId = ""
            isFullLyricsCurrent = false
        }
        let reason = object["reason"] as? String ?? ""
        let status = normalizedLyricDiagnosticStatus(object["lyricStatus"] as? String ?? "")
        let suggestion = object["lyricSuggestion"] as? String ?? ""
        lyricDiagnostic = LyricDiagnostic.lightweight(
            trackId: trackID,
            title: title,
            artist: artist,
            status: status.isEmpty ? "no_lyrics_final" : status,
            reason: reason,
            suggestion: suggestion
        )
        lyricDiagnosticLastUpdatedAt = Date()
        requestLyricDiagnosticIfNeeded()
        mediaLoadingState.lyric = .failed(
            reason: reason.isEmpty ? status : reason
        )
        log("[FullLyrics] unavailable reason=\(reason)")
    }

    private func handleLyricSecondaryStart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let transferID = object["transferId"] as? String ?? ""
        let modeRaw = object["mode"] as? String ?? ""
        guard trackID == currentTrackID,
              fullLyricsTrackId == trackID,
              let mode = LyricSecondaryMode(rawValue: modeRaw),
              let request = activeLyricSecondaryRequest,
              request.trackId == trackID,
              request.mode == mode,
              request.connectionAttemptId == connectionAttemptId,
              !transferID.isEmpty else {
            log("[Lyrics-iOS] secondary discarded stale trackId=\(trackID)")
            return
        }
        let itemCount = Self.intValue(object["itemCount"])
        guard itemCount > 0 else {
            handleLyricSecondaryFailure(
                trackID: trackID,
                mode: mode,
                reason: "empty transfer",
                explicitlyUnavailable: true
            )
            return
        }
        lyricSecondaryStartTimeoutWorkItem?.cancel()
        lyricSecondaryStartTimeoutWorkItem = nil
        lyricSecondaryTransfer = LyricSecondaryTransfer(
            trackId: trackID,
            transferId: transferID,
            mode: mode,
            itemCount: itemCount,
            requestToken: request.token,
            connectionAttemptId: request.connectionAttemptId,
            lines: [:]
        )
        scheduleLyricSecondaryIdleTimeout(for: request)
        log(
            "[Lyrics-iOS] secondary start mode=\(mode.rawValue) " +
                "items=\(itemCount)"
        )
    }

    private func handleLyricSecondaryPart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let transferID = object["transferId"] as? String ?? ""
        let modeRaw = object["mode"] as? String ?? ""
        guard trackID == currentTrackID,
              var transfer = lyricSecondaryTransfer,
              let request = activeLyricSecondaryRequest,
              transfer.trackId == trackID,
              transfer.transferId == transferID,
              transfer.mode.rawValue == modeRaw,
              transfer.requestToken == request.token,
              transfer.connectionAttemptId == connectionAttemptId else {
            return
        }
        let lineIndex = Self.intValue(object["lineIndex"])
        let partIndex = Self.intValue(object["partIndex"])
        let partCount = Self.intValue(object["partCount"])
        guard lineIndex >= 0,
              partIndex >= 0,
              partIndex < partCount,
              partCount > 0,
              let text = object["text"] as? String else {
            return
        }
        var lineParts = transfer.lines[lineIndex] ?? LyricSecondaryLineParts(
            partCount: partCount,
            parts: [:]
        )
        guard lineParts.partCount == partCount else { return }
        lineParts.parts[partIndex] = text
        transfer.lines[lineIndex] = lineParts
        lyricSecondaryTransfer = transfer
        scheduleLyricSecondaryIdleTimeout(for: request)
        if lineParts.parts.count == partCount {
            let assembled = (0..<partCount).compactMap { lineParts.parts[$0] }.joined()
            log(
                "[Lyrics-iOS] secondary line complete line=\(lineIndex) " +
                    "chars=\(assembled.count) " +
                    "bytes=\(assembled.data(using: .utf8)?.count ?? 0)"
            )
        }
    }

    private func handleLyricSecondaryEnd(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let transferID = object["transferId"] as? String ?? ""
        let modeRaw = object["mode"] as? String ?? ""
        guard trackID == currentTrackID,
              let transfer = lyricSecondaryTransfer,
              let request = activeLyricSecondaryRequest,
              transfer.trackId == trackID,
              transfer.transferId == transferID,
              transfer.mode.rawValue == modeRaw,
              transfer.requestToken == request.token,
              transfer.connectionAttemptId == connectionAttemptId else {
            log("[Lyrics-iOS] secondary end discarded stale trackId=\(trackID)")
            return
        }

        var assembled: [Int: String] = [:]
        var missing = 0
        for (lineIndex, lineParts) in transfer.lines {
            let hasAllParts = lineParts.parts.count == lineParts.partCount &&
                (0..<lineParts.partCount).allSatisfy { lineParts.parts[$0] != nil }
            if hasAllParts {
                assembled[lineIndex] = (0..<lineParts.partCount)
                    .compactMap { lineParts.parts[$0] }
                    .joined()
            } else {
                missing += 1
            }
        }
        let itemMissing = max(max(transfer.itemCount - assembled.count, 0), missing)
        log(
            "[Lyrics-iOS] secondary end mode=\(transfer.mode.rawValue) " +
                "completed=\(assembled.count) missing=\(itemMissing)"
        )
        guard itemMissing == 0 else {
            log(
                "[Lyrics-iOS] secondary incomplete mode=\(transfer.mode.rawValue) " +
                    "missingLines=\(itemMissing)"
            )
            handleLyricSecondaryFailure(
                trackID: transfer.trackId,
                mode: transfer.mode,
                reason: "missing \(itemMissing) lines",
                explicitlyUnavailable: false
            )
            return
        }
        mergeLyricSecondary(
            assembled,
            mode: transfer.mode,
            trackID: transfer.trackId
        )
        completedLyricSecondaryKeys.insert(
            lyricSecondaryKey(trackID: transfer.trackId, mode: transfer.mode)
        )
        lyricSecondaryRetryCounts.removeValue(
            forKey: lyricSecondaryKey(trackID: transfer.trackId, mode: transfer.mode)
        )
        cancelLyricSecondaryTimeouts()
        activeLyricSecondaryRequest = nil
        lyricSecondaryTransfer = nil
        setLyricSecondaryState(.ready, mode: transfer.mode)
        saveFullLyricsCacheIfCurrent(reason: "secondary \(transfer.mode.rawValue)")
        log(
            "[Lyrics-iOS] secondary publish mode=\(transfer.mode.rawValue) " +
                "lines=\(assembled.count)"
        )
        requestNextLyricSecondaryIfPossible()
    }

    private func handleLyricSecondaryUnavailable(
        _ object: [String: Any],
        isError: Bool
    ) {
        let trackID = object["trackId"] as? String ?? currentTrackID
        let modeRaw = object["mode"] as? String ?? ""
        guard trackID == currentTrackID,
              let mode = LyricSecondaryMode(rawValue: modeRaw),
              let request = activeLyricSecondaryRequest,
              request.trackId == trackID,
              request.mode == mode,
              request.connectionAttemptId == connectionAttemptId else {
            log("[Lyrics-iOS] secondary unavailable discarded stale trackId=\(trackID)")
            return
        }
        let reason = object["reason"] as? String ?? object["message"] as? String ??
            (isError ? "remote error" : "not available")
        log(
            "[Lyrics-iOS] secondary \(isError ? "error" : "unavailable") " +
                "mode=\(modeRaw) reason=\(reason)"
        )
        handleLyricSecondaryFailure(
            trackID: trackID,
            mode: mode,
            reason: reason,
            explicitlyUnavailable: !isError
        )
    }

    private func mergeLyricSecondary(
        _ values: [Int: String],
        mode: LyricSecondaryMode,
        trackID: String
    ) {
        guard trackID == currentTrackID,
              fullLyricsTrackId == trackID else { return }
        fullLyrics = fullLyrics.map { line in
            let rawValue = values[line.index]
            let value = sanitizedSecondaryText(rawValue)
            if rawValue != nil, value == nil {
                let key = "\(trackID)|\(mode.rawValue)|\(line.index)"
                if !ignoredLyricSecondaryPlaceholderKeys.contains(key) {
                    ignoredLyricSecondaryPlaceholderKeys.insert(key)
                    log(
                        "[Lyrics-iOS] secondary ignored placeholder " +
                            "mode=\(mode.rawValue) line=\(line.index)"
                    )
                }
            }
            switch mode {
            case .translation:
                return LyricLine(
                    index: line.index,
                    timeMs: line.timeMs,
                    durationMs: line.durationMs,
                    text: line.text,
                    translation: value ?? sanitizedSecondaryText(line.translation),
                    romanization: sanitizedSecondaryText(line.romanization),
                    words: line.words
                )
            case .romanization:
                return LyricLine(
                    index: line.index,
                    timeMs: line.timeMs,
                    durationMs: line.durationMs,
                    text: line.text,
                    translation: sanitizedSecondaryText(line.translation),
                    romanization: value ?? sanitizedSecondaryText(line.romanization),
                    words: line.words
                )
            }
        }
    }

    private func resetFullLyricsTransfer() {
        fullLyricsTimeoutWorkItem?.cancel()
        fullLyricsTimeoutWorkItem = nil
        fullLyricsReceivingTrackID = ""
        fullLyricsExpectedCount = 0
        fullLyricsChunks.removeAll()
        fullLyricsBinaryTransfer = nil
        fullLyricsBinaryDecodingTransferID = nil
        isFullLyricsReceiving = false
        lastFullLyricsPartialPublishAtMs = 0
    }

    private func publishPartialFullLyricsIfNeeded(trackID: String) {
        guard trackID == currentTrackID,
              fullLyricsChunks.count >= 3 else {
            return
        }
        let nowMs = currentTimeMs()
        guard nowMs - lastFullLyricsPartialPublishAtMs >= 250 else {
            return
        }
        lastFullLyricsPartialPublishAtMs = nowMs
        publishFullLyrics(
            lines: Array(fullLyricsChunks.values),
            trackID: trackID,
            isFinal: false
        )
    }

    private func publishFullLyrics(
        lines: [LyricLine],
        trackID: String,
        isFinal: Bool
    ) {
        let existingByIndex: [Int: LyricLine]
        if fullLyricsTrackId == trackID {
            existingByIndex = Dictionary(uniqueKeysWithValues: fullLyrics.map { ($0.index, $0) })
        } else {
            existingByIndex = [:]
        }
        let incomingLines = lines
            .map { line in
                guard let existing = existingByIndex[line.index] else { return line }
                return LyricLine(
                    index: line.index,
                    timeMs: line.timeMs,
                    durationMs: line.durationMs,
                    text: line.text,
                    translation: sanitizedSecondaryText(line.translation) ??
                        sanitizedSecondaryText(existing.translation),
                    romanization: sanitizedSecondaryText(line.romanization) ??
                        sanitizedSecondaryText(existing.romanization),
                    words: line.words
                )
            }
            .sorted { $0.index < $1.index }
        let sortedLines: [LyricLine]
        if !isFinal,
           !existingByIndex.isEmpty,
           existingByIndex.count > incomingLines.count {
            var merged = existingByIndex
            for line in incomingLines {
                merged[line.index] = line
            }
            sortedLines = merged.values.sorted { $0.index < $1.index }
        } else {
            sortedLines = incomingLines
        }
        fullLyrics = sortedLines
        fullLyricsTrackId = trackID
        isFullLyricsCurrent = true
        let nowMs = currentTimeMs()
        var traceDetail =
            "[LyricTrace-iOS] id=\(trackID) stage=uiPublished " +
            "lines=\(sortedLines.count) final=\(isFinal)"
        if let trackAt = lyricTraceTrackInfoAtMs[trackID] {
            traceDetail += " sinceTrackInfoMs=\(nowMs - trackAt)"
        }
        if let requestAt = lyricTraceFullLyricsRequestAtMs[trackID] {
            traceDetail += " sinceRequestMs=\(nowMs - requestAt)"
        }
        log(traceDetail)
        let wordsCount = sortedLines.reduce(0) { $0 + $1.words.count }
        let transCount = sortedLines.filter { sanitizedSecondaryText($0.translation) != nil }.count
        let romaCount = sortedLines.filter { sanitizedSecondaryText($0.romanization) != nil }.count
        if isFinal {
            completedFullLyricsTrackIDs.insert(trackID)
            mediaLoadingState.lyric = .ready(lineCount: sortedLines.count)
            if transCount > 0 {
                translationLyricsState = .ready
            }
            if romaCount > 0 {
                romanizationLyricsState = .ready
            }
            saveFullLyricsCacheIfCurrent(reason: "fullLyrics")
            log(
                "[FullLyrics] end count=\(fullLyrics.count) " +
                    "transCount=\(transCount) romaCount=\(romaCount)"
            )
            log(
                "[LyricsPerf] final publish lines=\(sortedLines.count) " +
                    "words=\(wordsCount) transCount=\(transCount) romaCount=\(romaCount)"
            )
        } else {
            if case .fullLyrics = mediaLoadingState.lyric {
                mediaLoadingState.lyric = .fullLyrics(
                    received: sortedLines.count,
                    expected: max(fullLyricsExpectedCount, sortedLines.count)
                )
            } else {
                mediaLoadingState.lyric = .windowReady(lineCount: sortedLines.count)
            }
            log(
                "[LyricsPerf] partial publish lines=\(sortedLines.count) " +
                    "receiving=\(isFullLyricsReceiving) " +
                    "transCount=\(transCount) romaCount=\(romaCount)"
            )
        }
        scheduleLastNowPlayingSnapshotSave(
            reason: isFinal ? "fullLyrics" : "lyricWindow",
            force: isFinal
        )
    }

    private func restoreFullLyricsCacheIfAvailable(
        trackID: String,
        title: String,
        artist: String
    ) {
        FullLyricsCacheStore.shared.load(
            trackId: trackID,
            title: title,
            artist: artist
        ) { [weak self] entry in
            guard let self,
                  let entry,
                  self.currentTrackID == trackID,
                  !self.completedFullLyricsTrackIDs.contains(trackID) else {
                return
            }
            let lines = entry.lines.map(Self.lyricLine(from:))
            guard !lines.isEmpty else { return }
            self.fullLyrics = lines.sorted { $0.index < $1.index }
            self.fullLyricsTrackId = trackID
            self.isFullLyricsCurrent = true
            self.mediaLoadingState.lyric = .windowReady(lineCount: lines.count)
            if lines.contains(where: { sanitizedSecondaryText($0.translation) != nil }) {
                self.translationLyricsState = .ready
            }
            if lines.contains(where: { sanitizedSecondaryText($0.romanization) != nil }) {
                self.romanizationLyricsState = .ready
            }
            self.log(
                "[FullLyricsCache] hit trackId=\(trackID) lines=\(lines.count) " +
                    "ageMs=\(Int64(Date().timeIntervalSince(entry.savedAt) * 1_000))"
            )
        }
    }

    private func saveFullLyricsCacheIfCurrent(reason: String) {
        guard !currentTrackID.isEmpty,
              fullLyricsTrackId == currentTrackID,
              !fullLyrics.isEmpty,
              !isShowingLastNowPlayingSnapshot else {
            return
        }
        let entry = FullLyricsCacheEntry(
            version: FullLyricsCacheEntry.version,
            trackId: currentTrackID,
            title: title,
            artist: artist,
            album: album,
            lines: fullLyrics.map(Self.cacheLine(from:)),
            savedAt: Date()
        )
        FullLyricsCacheStore.shared.save(entry)
        log(
            "[FullLyricsCache] save queued reason=\(reason) " +
                "trackId=\(currentTrackID) lines=\(fullLyrics.count)"
        )
    }

    private func restoreLastNowPlayingSnapshot() {
        LastNowPlayingSnapshotStore.shared.load { [weak self] snapshot in
            guard let self,
                  let snapshot,
                  self.currentTrackID.isEmpty,
                  self.title == "-" else {
                return
            }
            self.currentTrackID = snapshot.trackId
            self.title = snapshot.title
            self.artist = snapshot.artist
            self.album = snapshot.album
            self.isPlaying = false
            self.positionMs = snapshot.positionMs
            self.displayPositionMs = snapshot.positionMs
            self.seekPositionMs = snapshot.positionMs
            self.durationMs = snapshot.durationMs
            self.basePlaybackPositionMs = snapshot.positionMs
            self.fullLyrics = snapshot.lyricLines.map(Self.lyricLine(from:))
            self.fullLyricsTrackId = snapshot.trackId
            self.isFullLyricsCurrent = !self.fullLyrics.isEmpty
            self.lyric = self.fullLyrics.first(where: {
                $0.timeMs <= snapshot.positionMs &&
                    snapshot.positionMs < $0.timeMs + max($0.durationMs, 1)
            })?.text ?? self.fullLyrics.first?.text ?? ""
            self.isShowingLastNowPlayingSnapshot = true
            self.albumArtReceiver.handleIdentity(
                id: snapshot.albumArtId.isEmpty ? snapshot.trackId : snapshot.albumArtId
            )
            self.log(
                "[NowPlayingSnapshot] restored trackId=\(snapshot.trackId) " +
                    "ageMs=\(Int64(Date().timeIntervalSince(snapshot.savedAt) * 1_000))"
            )
        }
    }

    private func scheduleLastNowPlayingSnapshotSave(
        reason: String,
        force: Bool = false
    ) {
        guard !isShowingLastNowPlayingSnapshot,
              !currentTrackID.isEmpty,
              title != "-" else {
            return
        }
        let nowMs = currentTimeMs()
        if !force, nowMs - lastSnapshotQueuedAtMs < 2_000 {
            return
        }
        lastSnapshotQueuedAtMs = nowMs
        let lyricWindow = lastSnapshotLyricWindow()
        let snapshot = LastNowPlayingSnapshot(
            version: LastNowPlayingSnapshot.version,
            trackId: currentTrackID,
            title: title,
            artist: artist,
            album: album,
            wasPlaying: isPlaying,
            positionMs: displayPositionMs,
            durationMs: durationMs,
            lyricLines: lyricWindow.map(Self.snapshotLine(from:)),
            albumArtId: albumArtReceiver.currentAlbumArtID,
            savedAt: Date()
        )
        LastNowPlayingSnapshotStore.shared.save(snapshot)
        if force {
            log(
                "[NowPlayingSnapshot] queued reason=\(reason) " +
                    "trackId=\(currentTrackID) lines=\(lyricWindow.count)"
            )
        }
    }

    private func lastSnapshotLyricWindow() -> [LyricLine] {
        guard !fullLyrics.isEmpty else { return [] }
        let current = currentLyricIndex(
            lines: fullLyrics,
            positionMs: displayPositionMs
        ) ?? 0
        let lower = max(0, current - 2)
        let upper = min(fullLyrics.count, current + 3)
        return Array(fullLyrics[lower..<upper])
    }

    private static func snapshotLine(from line: LyricLine) -> LastNowPlayingSnapshot.Line {
        LastNowPlayingSnapshot.Line(
            index: line.index,
            timeMs: line.timeMs,
            durationMs: line.durationMs,
            text: line.text,
            translation: line.translation,
            romanization: line.romanization,
            words: line.words.map {
                LastNowPlayingSnapshot.Word(
                    id: $0.id,
                    startMs: $0.startMs,
                    durationMs: $0.durationMs,
                    text: $0.text
                )
            }
        )
    }

    private static func lyricLine(from line: LastNowPlayingSnapshot.Line) -> LyricLine {
        LyricLine(
            index: line.index,
            timeMs: line.timeMs,
            durationMs: line.durationMs,
            text: line.text,
            translation: line.translation,
            romanization: line.romanization,
            words: line.words.map {
                LyricWord(
                    id: $0.id,
                    startMs: $0.startMs,
                    durationMs: $0.durationMs,
                    text: $0.text
                )
            }
        )
    }

    private static func cacheLine(from line: LyricLine) -> FullLyricsCacheEntry.Line {
        FullLyricsCacheEntry.Line(
            index: line.index,
            timeMs: line.timeMs,
            durationMs: line.durationMs,
            text: line.text,
            translation: line.translation,
            romanization: line.romanization,
            words: line.words.map {
                FullLyricsCacheEntry.Word(
                    id: $0.id,
                    startMs: $0.startMs,
                    durationMs: $0.durationMs,
                    text: $0.text
                )
            }
        )
    }

    private static func lyricLine(from line: FullLyricsCacheEntry.Line) -> LyricLine {
        LyricLine(
            index: line.index,
            timeMs: line.timeMs,
            durationMs: line.durationMs,
            text: line.text,
            translation: line.translation,
            romanization: line.romanization,
            words: line.words.map {
                LyricWord(
                    id: $0.id,
                    startMs: $0.startMs,
                    durationMs: $0.durationMs,
                    text: $0.text
                )
            }
        )
    }

    private func handleCurrentWord(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let sameTrack = isSameTrackId(incoming: trackID, current: currentTrackID)
        guard !trackID.isEmpty, sameTrack else {
            currentWordDropCount += 1
            log(
                "[Lyrics-iOS] currentWord discarded stale trackId=\(trackID) " +
                    "current=\(currentTrackID)"
            )
            log(
                "[CurrentWordFence] stale discard trackId=\(trackID) " +
                    "currentTrackId=\(currentTrackID)"
            )
            if isInReconnectStateSyncWindow() {
                log(
                    "[Reconnect] stale discard after reconnect " +
                        "trackId=\(trackID) current=\(currentTrackID)"
                )
            }
            return
        }
        if trackID != currentTrackID {
            log(
                "[Lyrics-iOS] currentWord accepted by normalized trackId " +
                    "incoming=\(trackID) current=\(currentTrackID)"
            )
        }

        let lineIndex = Self.intValue(object["line"])
        let wordIndex = Self.intValue(object["word"])
        let remotePositionMs = Self.int64Value(object["position"])
        let timestampMs = Self.int64Value(object["timestamp"])
        let generation = Self.int64Value(object["generation"])
        let sequence = Self.int64Value(object["seq"])
        let nowMs = currentTimeMs()

        let previousGeneration = currentWordFence.generation
        let previousSequence = currentWordFence.sequence
        let previousPositionMs = currentWordFence.positionMs
        guard currentWordFence.shouldAccept(
            generation: generation,
            sequence: sequence,
            positionMs: remotePositionMs
        ) else {
            currentWordDropCount += 1
            log(
                "[CurrentWordFence] ordered discard trackId=\(trackID) " +
                    "generation=\(generation) seq=\(sequence) " +
                    "position=\(remotePositionMs) " +
                    "lastGeneration=\(previousGeneration) " +
                    "lastSeq=\(previousSequence) lastPosition=\(previousPositionMs)"
            )
            return
        }

        let anchorResolution = resolveRemotePlaybackAnchor(
            object: object,
            remotePositionMs: remotePositionMs,
            timestampMs: timestampMs,
            isPlaying: isPlaying
        )
        guard let calibratedPositionMs = calibratedRemotePosition(
            from: anchorResolution,
            remotePositionMs: remotePositionMs,
            source: "currentWord"
        ) else {
            currentWordDropCount += 1
            return
        }
        let transportAgeMs: Int64
        if case let .resolved(_, ageMs) = anchorResolution {
            transportAgeMs = ageMs
        } else {
            transportAgeMs = 0
        }
        var effectiveLineIndex = lineIndex
        var effectiveWordIndex = wordIndex
        var locallyResolvedLyric: String?
        if transportAgeMs > 300,
           let local = locallyResolvedWord(
               positionMs: karaokePositionMs(rawPositionMs: calibratedPositionMs)
           ) {
            effectiveLineIndex = local.lineIndex
            effectiveWordIndex = local.wordIndex
            locallyResolvedLyric = local.text
        }

        currentWordLineIndex = effectiveLineIndex
        currentWordIndex = effectiveWordIndex
        currentWordPushCount += 1
        if lastCurrentWordReceivedAtMs > 0 {
            currentWordIntervalTotalMs += max(nowMs - lastCurrentWordReceivedAtMs, 0)
            currentWordIntervalCount += 1
            if currentWordIntervalCount > 0 {
                currentWordAverageUpdateIntervalMs =
                    currentWordIntervalTotalMs / currentWordIntervalCount
            } else {
                currentWordAverageUpdateIntervalMs = 0
            }
        }
        lastCurrentWordReceivedAtMs = nowMs
        currentWordLastLatencyMs = transportAgeMs
        let shouldLogDiagnostic = effectiveLineIndex != lastCurrentWordLoggedLineIndex ||
            nowMs - lastCurrentWordDiagnosticLogAtMs >= 5_000
        if shouldLogDiagnostic {
            lastCurrentWordLoggedLineIndex = effectiveLineIndex
            lastCurrentWordDiagnosticLogAtMs = nowMs
            log(
                "[LyricTrace-iOS] id=\(trackID) stage=currentWordAccepted " +
                    "generation=\(generation) seq=\(sequence) " +
                    "line=\(effectiveLineIndex) word=\(effectiveWordIndex) " +
                    "remotePosition=\(remotePositionMs) " +
                    "calibratedPosition=\(calibratedPositionMs) " +
                    "latencyMs=\(currentWordLastLatencyMs)"
            )
        }

        let lineByOffset = fullLyrics.indices.contains(effectiveLineIndex)
            ? fullLyrics[effectiveLineIndex]
            : nil
        if isSameTrackId(incoming: trackID, current: fullLyricsTrackId),
           let line = fullLyricLine(withIndex: effectiveLineIndex) ?? lineByOffset {
            lyric = line.text
        } else if let locallyResolvedLyric {
            lyric = locallyResolvedLyric
        }

        if !isSeeking {
            positionMs = calibratedPositionMs
            displayPositionMs = calibratedPositionMs
            seekPositionMs = calibratedPositionMs
            basePlaybackPositionMs = calibratedPositionMs
            playbackAnchorElapsedMs = monotonicTimeMs()
        }

        if shouldLogDiagnostic {
            log(
                "[Lyrics-iOS] currentWord line=\(effectiveLineIndex) " +
                    "word=\(effectiveWordIndex) position=\(calibratedPositionMs) " +
                    "latencyMs=\(currentWordLastLatencyMs) " +
                    "count=\(currentWordPushCount) " +
                    "avgIntervalMs=\(currentWordAverageUpdateIntervalMs)"
            )
        }
        if isInReconnectStateSyncWindow() {
            log(
                "[Reconnect] currentWord accepted after reconnect " +
                    "line=\(effectiveLineIndex) word=\(effectiveWordIndex)"
            )
        }

        _ = updateLiveActivityForCurrentLyricIfNeeded(reason: "currentWord")
    }

    private func resetCurrentWordFence() {
        currentWordFence.reset()
        lastCurrentWordLoggedLineIndex = -1
        lastCurrentWordDiagnosticLogAtMs = 0
    }

    private func isInReconnectStateSyncWindow() -> Bool {
        reconnectStateSyncWindowUntilMs > 0 && currentTimeMs() <= reconnectStateSyncWindowUntilMs
    }

    private func isSameTrackId(incoming: String, current: String) -> Bool {
        let incoming = incoming.trimmingCharacters(in: .whitespacesAndNewlines)
        let current = current.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !incoming.isEmpty, !current.isEmpty else { return false }
        if incoming == current { return true }
        if current.count >= 10, incoming.hasPrefix(current) { return true }
        if incoming.count >= 10, current.hasPrefix(incoming) { return true }
        return false
    }

    private func updateProgressTimerState() {
        let shouldRun = PlaybackClockPolicy.shouldRun(
            isPlaying: isPlaying,
            durationMs: durationMs,
            appLifecycleState: appLifecycleState
        )
        guard shouldRun else {
            progressTimer?.invalidate()
            progressTimer = nil
            let stablePosition = durationMs > 0
                ? positionMs.clamped(to: 0...durationMs)
                : 0
            if displayPositionMs != stablePosition {
                displayPositionMs = stablePosition
            }
            return
        }
        guard progressTimer == nil else { return }
        let timer = Timer(timeInterval: progressRefreshInterval, repeats: true) { [weak self] _ in
            self?.updateInterpolatedProgress()
        }
        progressTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private var progressRefreshInterval: TimeInterval {
        switch preferences.playbackPerformanceMode {
        case .smooth:
            return 0.1
        case .automatic:
            return ProcessInfo.processInfo.isLowPowerModeEnabled ? 0.5 : 0.25
        case .powerSaving:
            return 0.5
        }
    }

    private func fullLyricLine(withIndex target: Int) -> LyricLine? {
        var lower = 0
        var upper = fullLyrics.count - 1
        while lower <= upper {
            let middle = lower + (upper - lower) / 2
            let line = fullLyrics[middle]
            if line.index == target {
                return line
            }
            if line.index < target {
                lower = middle + 1
            } else {
                upper = middle - 1
            }
        }
        return nil
    }

    private func updateInterpolatedProgress() {
        guard !isSeeking else { return }
        guard durationMs > 0 else {
            if displayPositionMs != 0 {
                displayPositionMs = 0
            }
            return
        }
        guard isPlaying else {
            let stablePosition = positionMs.clamped(to: 0...durationMs)
            if displayPositionMs != stablePosition {
                displayPositionMs = stablePosition
            }
            return
        }

        let elapsedMs = max(monotonicTimeMs() - playbackAnchorElapsedMs, 0)
        let scaledElapsedMs = Int64(
            (Double(max(elapsedMs, 0)) * max(remotePlaybackSpeed, 0)).rounded()
        )
        let interpolated = (basePlaybackPositionMs + scaledElapsedMs)
            .clamped(to: 0...durationMs)
        if interpolated != displayPositionMs {
            displayPositionMs = interpolated
        }
    }

    private func finishTrackInfoTransfer() {
        guard trackInfoExpectedChunks > 0,
              trackInfoChunks.count == trackInfoExpectedChunks else {
            log(
                "[TrackInfo] decode failed received=\(trackInfoChunks.count) " +
                    "expected=\(trackInfoExpectedChunks)"
            )
            resetTrackInfoTransfer()
            return
        }

        let expectedSize = trackInfoExpectedSize
        let expectedChunks = trackInfoExpectedChunks
        let chunks = trackInfoChunks
        let token = trackInfoTransferToken
        trackInfoExpectedSize = 0
        trackInfoExpectedChunks = 0
        trackInfoChunks.removeAll(keepingCapacity: true)

        protocolDecodeQueue.async { [weak self] in
            let signpost = AppPerformanceLog.protocolSignposter.beginInterval("TrackInfo Decode")
            var data = Data()
            data.reserveCapacity(expectedSize)
            for index in 0..<expectedChunks {
                guard let chunk = chunks[index] else {
                    AppPerformanceLog.protocolSignposter.endInterval("TrackInfo Decode", signpost)
                    DispatchQueue.main.async { [weak self] in
                        self?.log("[TrackInfo] decode failed missing index=\(index)")
                    }
                    return
                }
                data.append(chunk)
            }
            let payload = data.count == expectedSize
                ? try? JSONDecoder().decode(TrackInfoTransferPayload.self, from: data)
                : nil
            AppPerformanceLog.protocolSignposter.endInterval("TrackInfo Decode", signpost)
            DispatchQueue.main.async { [weak self] in
                guard let self, self.trackInfoTransferToken == token else {
                    self?.log("[TrackInfo] stale decode ignored")
                    return
                }
                guard let payload, payload.type == "trackInfo" else {
                    self.log("[TrackInfo] decode failed")
                    return
                }
                self.applyTrackInfo([
                    "type": payload.type,
                    "title": payload.title ?? "-",
                    "artist": payload.artist ?? "-",
                    "album": payload.album ?? "-",
                    "trackId": payload.trackId ?? "",
                    "generation": payload.generation ?? 0
                ])
                self.log("[TrackInfo] decode success bytes=\(data.count)")
            }
        }
    }

    private func resetTrackInfoTransfer() {
        trackInfoTransferToken = UUID()
        trackInfoExpectedSize = 0
        trackInfoExpectedChunks = 0
        trackInfoChunks.removeAll()
    }

    private func publishLiveArtworkIfCurrent(
        image: UIImage,
        key: String,
        reason: String
    ) {
        let trackAtStart = currentTrackID
        let revision = currentLiveArtworkRevision + 1
        let writeRequest = liveArtworkRevisionFence.begin()
        log("[LiveArtwork] current main album key=\(key)")
        LiveActivityArtworkStore.shared.writeThumbnail(
            image: image,
            key: key,
            revision: revision,
            completion: { [weak self] result in
                guard let self else { return }
                result.messages.forEach(self.log)
                guard self.liveArtworkRevisionFence.accepts(writeRequest),
                      self.currentTrackID == trackAtStart,
                      self.albumArtReceiver.currentAlbumArtID == key else {
                    self.log(
                        "[LiveArtwork] stale result ignored oldTrackId=\(trackAtStart) " +
                            "currentTrackId=\(self.currentTrackID)"
                    )
                    return
                }
                guard result.succeeded else {
                    self.log("[LiveArtwork] update skipped reason=write failed key=\(key)")
                    return
                }

                self.currentLiveArtworkKey = key
                self.currentLiveArtworkRevision = revision
                self.log(
                    "[LiveArtwork] update requested key=\(key) " +
                        "revision=\(revision) source=\(reason)"
                )
                self.updateLiveActivity(force: true, reason: "artworkReady")
            }
        )
    }

    private func clearLiveArtwork(reason: String, shouldUpdate: Bool) {
        guard currentLiveArtworkKey != nil else { return }
        liveArtworkRevisionFence.invalidate()
        currentLiveArtworkKey = nil
        currentLiveArtworkRevision += 1
        log("[LiveArtwork] update requested key=nil revision=\(currentLiveArtworkRevision) reason=\(reason)")
        if shouldUpdate {
            updateLiveActivity(force: true, reason: "artworkUnavailable")
        }
    }

    private func finishRemoteLogTransfer() {
        guard remoteLogExpectedChunks > 0,
              remoteLogChunks.count == remoteLogExpectedChunks else {
            log("[RemoteLog] decode failed")
            isRemoteLogTransferInProgress = false
            resetRemoteLogTransfer()
            return
        }

        var textData = Data()
        for index in 0..<remoteLogExpectedChunks {
            guard let chunk = remoteLogChunks[index] else {
                log("[RemoteLog] decode failed")
                isRemoteLogTransferInProgress = false
                resetRemoteLogTransfer()
                return
            }
            textData.append(chunk)
        }

        guard let text = String(data: textData, encoding: .utf8) else {
            log("[RemoteLog] decode failed")
            isRemoteLogTransferInProgress = false
            resetRemoteLogTransfer()
            return
        }

        remoteLogText = text
        remoteLogCopyStatus = ""
        isRemoteLogTransferInProgress = false
        let decodedLines = text.isEmpty ? 0 : text.components(separatedBy: "\n").count
        log("[RemoteLog] decode success lines=\(decodedLines) expected=\(remoteLogExpectedLines)")
        resetRemoteLogTransfer()
    }

    private func resetRemoteLogTransfer() {
        remoteLogExpectedChunks = 0
        remoteLogExpectedLines = 0
        remoteLogChunks.removeAll()
    }

    private func finishMediaFieldDumpTransfer() {
        guard mediaFieldDumpExpectedChunks > 0,
              mediaFieldDumpChunks.count == mediaFieldDumpExpectedChunks else {
            failMediaFieldDump("missing chunks")
            return
        }

        var textData = Data()
        textData.reserveCapacity(mediaFieldDumpExpectedSize)
        for index in 0..<mediaFieldDumpExpectedChunks {
            guard let chunk = mediaFieldDumpChunks[index] else {
                failMediaFieldDump("missing chunk index=\(index)")
                return
            }
            textData.append(chunk)
        }

        guard textData.count == mediaFieldDumpExpectedSize,
              let text = String(data: textData, encoding: .utf8) else {
            failMediaFieldDump("invalid size or UTF-8")
            return
        }

        mediaFieldDumpText = text
        mediaFieldDumpCopyStatus = ""
        isMediaFieldDumpReceiving = false
        mediaFieldDumpProgressText = ""
        log("[MediaDump] decode success bytes=\(textData.count)")
        resetMediaFieldDumpTransfer()
    }

    private func failMediaFieldDump(_ reason: String) {
        isMediaFieldDumpReceiving = false
        mediaFieldDumpProgressText = "Media dump failed"
        log("[MediaDump] decode failed \(reason)")
        resetMediaFieldDumpTransfer()
    }

    private func resetMediaFieldDumpTransfer() {
        mediaFieldDumpExpectedSize = 0
        mediaFieldDumpExpectedChunks = 0
        mediaFieldDumpChunks.removeAll()
    }

    private func decodeTopTracks(_ items: [[String: Any]]) -> [PlaybackTopTrack] {
        items.map { item in
            PlaybackTopTrack(
                trackKey: item["trackKey"] as? String ?? "",
                title: item["title"] as? String ?? "",
                artist: item["artist"] as? String ?? "",
                album: item["album"] as? String ?? "",
                artworkId: item["artworkId"] as? String,
                listenedMs: Self.int64Value(item["listenedMs"]),
                playCount: Self.intValue(item["playCount"]),
                completedCount: Self.intValue(item["completedCount"]),
                skippedCount: Self.intValue(item["skippedCount"])
            )
        }
    }

    private func decodeTopArtists(_ items: [[String: Any]]) -> [PlaybackTopArtist] {
        items.map { item in
            PlaybackTopArtist(
                artist: item["artist"] as? String ?? "未知歌手",
                listenedMs: Self.int64Value(item["listenedMs"]),
                playCount: Self.intValue(item["playCount"]),
                trackCount: Self.intValue(item["trackCount"])
            )
        }
    }

    private func decodeDailyTrend(_ items: [[String: Any]]) -> [DailyListenStat] {
        items.map { item in
            DailyListenStat(
                dateKey: item["dateKey"] as? String ?? "",
                listenedMs: Self.int64Value(item["listenedMs"]),
                playCount: Self.intValue(item["playCount"])
            )
        }
    }

    private static func int64Value(_ value: Any?) -> Int64 {
        if let number = value as? NSNumber {
            return number.int64Value
        }
        if let string = value as? String {
            return Int64(string) ?? 0
        }
        return 0
    }

    private static func optionalInt64Value(_ value: Any?) -> Int64? {
        if value is NSNull {
            return nil
        }
        if let number = value as? NSNumber {
            return number.int64Value
        }
        return nil
    }

    private static func intValue(_ value: Any?) -> Int {
        if let number = value as? NSNumber {
            return number.intValue
        }
        if let string = value as? String {
            return Int(string) ?? 0
        }
        return 0
    }

    private static func doubleValue(_ value: Any?) -> Double {
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let string = value as? String {
            return Double(string) ?? 0
        }
        return 0
    }

    private static func parseLyricWords(_ value: Any?) -> [LyricWord] {
        guard let array = value as? [[String: Any]] else {
            return []
        }
        return array.enumerated().compactMap { offset, object in
            let text = object["text"] as? String ?? ""
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return nil
            }
            return LyricWord(
                id: offset,
                startMs: int64Value(object["startMs"]),
                durationMs: int64Value(object["durationMs"]),
                text: text
            )
        }
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}

extension BLETestManager: AlbumArtReceiverDelegate {
    var albumArtCurrentTrackID: String { currentTrackID }
    var albumArtCurrentTitle: String { title }
    var albumArtConnectionStatus: String { connectionStatus }
    var albumArtConnectionDisplayState: String { connectionDisplayState }
    var albumArtConnectionHealthState: String { connectionHealthState }
    var albumArtCharacteristicReady: Bool { connectionHealthCharacteristicReady }
    var albumArtIsBusyForHqRequest: Bool {
        isFullLyricsReceiving ||
            lyricSecondaryTransfer != nil ||
            isRemoteLogTransferInProgress ||
            isMediaFieldDumpReceiving
    }

    func albumArtLog(_ message: String) {
        log(message)
    }

    func albumArtSendCommand(cmd: String, extra: [String: Any]) {
        sendCommand(cmd: cmd, extra: extra)
    }

    func albumArtEffectiveHqDelay(_ delay: TimeInterval) -> (delay: TimeInterval, deferred: Bool) {
        guard isInStartupLoadWindow() else {
            return (delay, false)
        }
        return (max(delay, startupLoadRemainingDelay() + 1.0), true)
    }

    func albumArtPublishLiveArtwork(image: UIImage, key: String, reason: String) {
        publishLiveArtworkIfCurrent(image: image, key: key, reason: reason)
        scheduleLastNowPlayingSnapshotSave(reason: "artworkReady", force: true)
    }

    func albumArtClearLiveArtwork(reason: String, shouldUpdate: Bool) {
        clearLiveArtwork(reason: reason, shouldUpdate: shouldUpdate)
    }

    func albumArtUpdateLiveActivity(force: Bool, reason: String) {
        updateLiveActivity(force: force, reason: reason)
    }
}

extension BLETestManager: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        log("[BLE-B] peripheral state=\(peripheral.state.rawValue)")
        if peripheral.state == .poweredOn, shouldStartAdvertising {
            configurePeripheralService()
        } else if peripheral.state != .poweredOn {
            setStatus("Peripheral Bluetooth unavailable")
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didAdd service: CBService,
        error: Error?
    ) {
        if let error {
            setStatus("GATT service add failed")
            log("[BLE-B] service add failed error=\(error.localizedDescription)")
            return
        }

        log("[BLE-B] service added \(service.uuid.uuidString)")
        startAdvertisingIfReady()
    }

    func peripheralManagerDidStartAdvertising(
        _ peripheral: CBPeripheralManager,
        error: Error?
    ) {
        if let error {
            setStatus("Advertising failed")
            log("[BLE-B] advertising failed error=\(error.localizedDescription)")
        } else {
            setStatus("Advertising MusicControllerIOS")
            log("[BLE-B] advertising started")
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        setStatus("Sony subscribed command")
        log(
            "[BLE-B] Sony subscribed command central=\(central.identifier) " +
            "maximumUpdateValueLength=\(central.maximumUpdateValueLength)"
        )
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        setStatus("Sony unsubscribed")
        log("[BLE-B] Sony unsubscribed command central=\(central.identifier)")
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveRead request: CBATTRequest
    ) {
        guard request.characteristic.uuid == BLEUUIDs.command else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }

        request.value = Data()
        peripheral.respond(to: request, withResult: .success)
        log("[BLE-B] command characteristic read")
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests {
            guard request.characteristic.uuid == BLEUUIDs.status else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
                continue
            }

            let text = request.value.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            log("[BLE-B] status write received: \(text)")
            peripheral.respond(to: request, withResult: .success)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        log("[BLE-B] notify transmit queue ready")
    }
}
