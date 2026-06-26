import CoreBluetooth
import Foundation
import UIKit

private let LIVE_ACTIVITY_PLAY_PAUSE_DEBOUNCE_MS: Int64 = 600
private let LIVE_ACTIVITY_TRACK_SKIP_DEBOUNCE_MS: Int64 = 800
private let LIVE_ACTIVITY_COMMAND_TTL_MS: Int64 = 1_500
private let LIVE_ACTIVITY_WRITE_STALL_MS: Int64 = 2_000
private let VOLUME_SEND_THROTTLE_MS: Int64 = 120
private let VOLUME_PENDING_TTL_MS: Int64 = 1_000
private let AUTO_RECONNECT_LAST_PERIPHERAL_KEY = "lastSonyPeripheralIdentifier"
private let FAST_RETRIEVE_CONNECT_TIMEOUT_MS: Int64 = 1_800
private let DEFAULT_CONNECT_TIMEOUT_MS: Int64 = 8_000
private let CONNECTION_HEALTH_TICK_MS: Int64 = 3_000
private let CONNECTION_HEALTH_SUSPECT_MS: Int64 = 6_000
private let CONNECTION_HEALTH_STALE_MS: Int64 = 12_000
private let CONNECTION_HEALTH_PROBE_TIMEOUT_MS: Int64 = 3_000
private let CONNECTION_HEALTH_HARD_RECONNECT_MIN_INTERVAL_MS: Int64 = 5_000
private let CONNECTION_SUBSCRIBE_NOTIFY_TIMEOUT_MS: Int64 = 5_000
private let CONNECTION_DISPLAY_CONNECTED_MIN_HOLD_MS: Int64 = 5_000
private let CONNECTION_DISPLAY_DISCONNECTED_CONFIRM_MS: Int64 = 1_000

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
    var lines: [Int: LyricSecondaryLineParts]
}

final class BLETestManager: NSObject, ObservableObject {
    @Published private(set) var appExperienceMode: AppExperienceMode = PreferencesStore.shared.appExperienceMode
    @Published private(set) var mode = "BLE Central / GATT Client"
    @Published private(set) var connectionStatus = "未连接"
    @Published private(set) var logs: [String] = []
    @Published private(set) var title = "-"
    @Published private(set) var artist = "-"
    @Published private(set) var album = "-"
    @Published private(set) var lyric = ""
    @Published private(set) var fullLyrics: [LyricLine] = []
    @Published private(set) var fullLyricsTrackId = ""
    @Published private(set) var isFullLyricsCurrent = false
    @Published private(set) var isFullLyricsReceiving = false
    @Published private(set) var lyricDiagnostic: LyricDiagnostic?
    @Published private(set) var lyricDiagnosticLoading = false
    @Published private(set) var lyricDiagnosticLastUpdatedAt: Date?
    @Published private(set) var isPlaying = false
    @Published private(set) var positionMs: Int64 = 0
    @Published private(set) var displayPositionMs: Int64 = 0
    @Published private(set) var durationMs: Int64 = 0
    @Published private(set) var seekPositionMs: Int64 = 0
    @Published private(set) var isSeeking = false
    @Published private(set) var volumeCurrent = 0
    @Published private(set) var volumeMax = 0
    @Published private(set) var volumeSeekValue = 0
    @Published private(set) var isVolumeSeeking = false
    @Published private(set) var albumArtImage: UIImage?
    @Published private(set) var connectedDeviceName = "-"
    @Published private(set) var artworkDisplayQuality: ArtworkDisplayQuality = .placeholder
    @Published private(set) var artworkEnhancementStatus = ArtworkEnhancementDebugStatus()
    @Published private(set) var artworkEnhancementTargetPixelSize = 780
    @Published private(set) var artworkEnhancementSharpness = 0.30
    @Published private(set) var remoteLogText = ""
    @Published private(set) var remoteLogCopyStatus = ""
    @Published private(set) var isRemoteLogTransferInProgress = false
    @Published private(set) var mediaFieldDumpText = ""
    @Published private(set) var mediaFieldDumpCopyStatus = ""
    @Published private(set) var isMediaFieldDumpReceiving = false
    @Published private(set) var mediaFieldDumpProgressText = ""
    @Published private(set) var karaokeOffsetMs: Int64 = Int64(PreferencesStore.shared.lyricOffsetMs)
    @Published private(set) var localLogActionStatus = ""
    @Published private(set) var liveActivityControlStatus = LiveActivityControlStatus()
    @Published private(set) var playbackHistorySessions: [PlaybackHistorySession] = []
    @Published private(set) var playbackStats: [String: PlaybackStatsSnapshot] = [:]
    @Published private(set) var isPlaybackHistorySyncing = false
    @Published private(set) var playbackHistoryStatus = ""
    @Published private(set) var autoReconnectEnabled = PreferencesStore.shared.autoReconnectEnabled
    @Published private(set) var autoReconnectState = AutoReconnectState.idle.rawValue
    @Published private(set) var autoReconnectAttempt = 0
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
    @Published private(set) var connectionHealthState = ConnectionHealthState.disconnected.rawValue
    @Published private(set) var connectionHealthLastNotifyAgeMs: Int64 = -1
    @Published private(set) var connectionHealthLastProbeAtText = "-"
    @Published private(set) var connectionHealthProbeInFlight = false
    @Published private(set) var connectionHealthLastHardReconnectReason = "-"
    @Published private(set) var connectionHealthAttemptId = "-"
    @Published private(set) var connectionHealthPeripheralState = "-"
    @Published private(set) var connectionHealthCharacteristicReady = false
    @Published private(set) var connectionDisplayState = ConnectionDisplayState.disconnected.rawValue
    @Published private(set) var connectionHealthSuspectCount = 0
    @Published private(set) var connectionHealthStaleCount = 0
    @Published private(set) var connectionHealthHardReconnectCount = 0
    @Published private(set) var connectionHealthMaxNotifyGapMs: Int64 = 0
    @Published private(set) var currentWordLineIndex = -1
    @Published private(set) var currentWordIndex = -1
    @Published private(set) var currentWordPushCount: Int64 = 0
    @Published private(set) var currentWordDropCount: Int64 = 0
    @Published private(set) var currentWordAverageUpdateIntervalMs: Int64 = 0
    @Published private(set) var currentWordLastLatencyMs: Int64 = 0

    private let preferences = PreferencesStore.shared
    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private lazy var peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

    private var sonyPeripheral: CBPeripheral?
    private var sonyCommandCharacteristic: CBCharacteristic?
    private var sonyStatusCharacteristic: CBCharacteristic?
    private var pendingWriteCommand = ""
    private var commandSeq: UInt64 = 0
    private var commandWriteInflight: [CommandWriteInfo] = []
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
    private var firstConnectionReadyAtMs: Int64 = 0
    private var lastKaraokeOffsetLogAtMs: Int64 = 0
    private var currentTrackID = ""
    private var currentLiveArtworkKey: String?
    private var currentLiveArtworkRevision = 0
    private var lastLiveActivityRequestAt = Date.distantPast
    private var lastLiveActivityRequestTrackID = ""
    private var lastLiveActivityLyricTrackID = ""
    private var lastLiveActivityLyricLineIndex = Int.min
    private var lastLiveActivityLyricText = ""
    private var lastLiveActivityCurrentWordSkipLogAtMs: Int64 = 0
    private var requestedFullLyricsTrackIDs: Set<String> = []
    private var fullLyricsUnavailableTrackIDs: Set<String> = []
    private var fullLyricsDelayedRetryTrackIDs: Set<String> = []
    private var fullLyricsOptionalRefreshTrackIDs: Set<String> = []
    private var requestedLyricSecondaryKeys: Set<String> = []
    private var completedLyricSecondaryKeys: Set<String> = []
    private var ignoredLyricSecondaryPlaceholderKeys: Set<String> = []
    private var pendingLyricSecondaryModes: [LyricSecondaryMode] = []
    private var lyricSecondaryTransfer: LyricSecondaryTransfer?
    private var lastAutomaticLyricDiagnosticRequestAt: [String: Date] = [:]
    private var fullLyricsReceivingTrackID = ""
    private var fullLyricsExpectedCount = 0
    private var fullLyricsChunks: [Int: LyricLine] = [:]
    private var fullLyricsTimeoutWorkItem: DispatchWorkItem?
    private var lastFullLyricsPartialPublishAtMs: Int64 = 0
    private var remoteLogExpectedChunks = 0
    private var remoteLogExpectedLines = 0
    private var remoteLogChunks: [Int: Data] = [:]
    private var mediaFieldDumpExpectedSize = 0
    private var mediaFieldDumpExpectedChunks = 0
    private var mediaFieldDumpChunks: [Int: Data] = [:]
    private var historyPayloads: [String: HistoryPayloadAssembly] = [:]
    private var pendingHistoryRequests: [String: HistoryRequestKind] = [:]
    private var lastSyncedHistorySessionId: Int64 = 0
    private var isLoadingMoreHistory = false
    private var trackInfoExpectedSize = 0
    private var trackInfoExpectedChunks = 0
    private var trackInfoChunks: [Int: Data] = [:]
    private var basePlaybackPositionMs: Int64 = 0
    private var playbackStateReceivedAt = Date()
    private var progressTimer: Timer?
    private var lastCurrentWordReceivedAtMs: Int64 = 0
    private var currentWordIntervalTotalMs: Int64 = 0
    private var currentWordIntervalCount: Int64 = 0
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
    private var healthProbeStartedAt: Date?
    private var lastHardReconnectAt: Date?
    private var connectionDisplayStateChangedAt = Date()
    private var connectionDisplayWorkItem: DispatchWorkItem?

    private struct CommandWriteInfo {
        let seq: UInt64
        let cmd: String
        let writeCalledAtMs: Int64
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

    override init() {
        super.init()
        preferences.load()
        syncPreferencesStateFromStore()
        logAppExperienceModeLoaded()
        log("[BLE-iOS] app log store ready")
        LiveActivityCommandBridge.shared.register(self, logger: { [weak self] message in
            self?.log(message)
        })
        refreshLiveActivityControlStatus()
        updateAppLifecycleState(UIApplication.shared.applicationState, emitLog: false)
        registerAppLifecycleDiagnostics()
        startProgressTimer()
        startMainHeartbeatDiagnostics()
        loadCachedPlaybackHistory()
        syncAlbumArtReceiverState(albumArtReceiver)
        runDebugSmokeTestIfNeeded()
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
        karaokeOffsetMs = Int64(preferences.lyricOffsetMs)
    }

    private func syncAlbumArtReceiverState(_ receiver: AlbumArtReceiver) {
        albumArtImage = receiver.albumArtImage
        artworkDisplayQuality = receiver.artworkDisplayQuality
        artworkEnhancementStatus = receiver.artworkEnhancementStatus
        artworkEnhancementTargetPixelSize = receiver.artworkEnhancementTargetPixelSize
        artworkEnhancementSharpness = receiver.artworkEnhancementSharpness
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
        let defaults = UserDefaults.standard
        let arguments = ProcessInfo.processInfo.arguments
        let markerKey = "smokeTestPreferencesWritten"
        if arguments.contains("--smoke-test-preferences") {
            setAppExperienceMode(.debug)
            preferences.artworkDisplaySize = .small
            setKaraokeOffsetMs(300)
            setAutoReconnectEnabled(true)
            defaults.set(true, forKey: markerKey)
            AppLogStore.shared.clear { [weak self] in
                guard let self else { return }
                self.log("[SmokeTest] preferences written")
                let verified = self.preferences.appExperienceMode == .debug &&
                    self.preferences.artworkDisplaySize.rawValue == 200 &&
                    self.karaokeOffsetMs == 300 &&
                    self.preferences.autoReconnectEnabled
                if verified {
                    self.log("[SmokeTest] preferences verified mode=debug artworkDisplaySize=200 lyricOffsetMs=300 autoReconnect=true")
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
                  preferences.artworkDisplaySize.rawValue == 200,
                  karaokeOffsetMs == 300,
                  preferences.autoReconnectEnabled {
            log("[SmokeTest] preferences persisted")
        }
        #endif
    }

    deinit {
        LiveActivityCommandBridge.shared.unregister(self)
        progressTimer?.invalidate()
        mainHeartbeatWorkItem?.cancel()
        healthCheckWorkItem?.cancel()
        healthProbeTimeoutWorkItem?.cancel()
        subscribeNotifyTimeoutWorkItem?.cancel()
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
            setAutoReconnectState(.idle)
        }
    }

    func forceReconnect() {
        log("[BLE-Reconnect] hard reconnect requested reason=manual")
        performHardReconnect(reason: "manual force reconnect", manual: true)
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

    @discardableResult
    func requestCurrentHqAlbumArt() -> Bool {
        albumArtReceiver.requestCurrentHqAlbumArt()
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
                lastLatencyMs: currentWordLastLatencyMs
            ),
            connection: connection,
            selfHealing: selfHealing
        )
    }

    func setKaraokeOffsetMs(_ value: Int64) {
        let normalized = min(max(value, -2_000), 2_000)
        preferences.lyricOffsetMs = Int(normalized)
        karaokeOffsetMs = normalized
        log("[Lyrics-iOS] karaoke offsetMs=\(value)")
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
        rawPositionMs + karaokeOffsetMs
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
            "[Lyrics-iOS] karaoke offsetMs=\(karaokeOffsetMs) " +
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
        playbackHistoryStatus = "同步播放历史..."
        requestPlaybackHistorySince(afterSessionId: lastSyncedHistorySessionId)
        refreshPlaybackStats()
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
        for range in ["TODAY", "WEEK", "MONTH"] {
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
        payload["time"] = startMs
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
              let sonyCommandCharacteristic else {
            let reason = isReconnectInProgress ? "reconnecting" : "not_connected"
            ctrlLog("[CTRL-iOS] write skipped seq=\(seq) cmd=\(cmd) reason=\(reason)")
            log("[Command] send failed \(cmd): \(reason)")
            if connected, sonyCommandCharacteristic == nil {
                performHardReconnect(reason: "command characteristic nil while connected", manual: false)
            }
            return
        }

        pendingWriteCommand = cmd
        let writeBeginMs = currentTimeMs()
        ctrlLog("[CTRL-iOS] write begin seq=\(seq) cmd=\(cmd) timeMs=\(writeBeginMs)")
        commandWriteInflight.append(
            CommandWriteInfo(
                seq: seq,
                cmd: cmd,
                writeCalledAtMs: writeBeginMs
            )
        )
        sonyPeripheral.writeValue(data, for: sonyCommandCharacteristic, type: .withResponse)
        lastSuccessfulWriteAt = Date()
        ctrlLog("[CTRL-iOS] write called seq=\(seq) cmd=\(cmd) timeMs=\(currentTimeMs())")
        if cmd == "SET_VOLUME" {
            log("[iOS][BLE] write SET_VOLUME requested")
        } else {
            log("[Command] send \(cmd)")
        }
        log("[BLE] write requested \(text)")
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
        playbackStateReceivedAt = Date()
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
        guard !completedLyricSecondaryKeys.contains(key),
              !requestedLyricSecondaryKeys.contains(key),
              !pendingLyricSecondaryModes.contains(mode),
              lyricSecondaryTransfer?.mode != mode else {
            return
        }
        requestedLyricSecondaryKeys.insert(key)
        pendingLyricSecondaryModes.append(mode)
        log("[Lyrics-iOS] secondary queued mode=\(mode.rawValue) trackId=\(trackID)")
    }

    private func requestNextLyricSecondaryIfPossible() {
        guard lyricSecondaryTransfer == nil else { return }
        guard !pendingLyricSecondaryModes.isEmpty else { return }
        let trackID = currentTrackID
        guard !trackID.isEmpty,
              fullLyricsTrackId == trackID,
              !fullLyrics.isEmpty else { return }
        if isInStartupLoadWindow() {
            let delay = startupLoadRemainingDelay()
            log("[StartupLoad] defer request=GET_LYRIC_SECONDARY reason=first connection warmup delayMs=\(Int(delay * 1_000))")
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                self?.requestNextLyricSecondaryIfPossible()
            }
            return
        }
        let mode = pendingLyricSecondaryModes.removeFirst()
        log("[Lyrics-iOS] secondary request mode=\(mode.rawValue) trackId=\(trackID)")
        sendCommand(
            cmd: "GET_LYRIC_SECONDARY",
            extra: [
                "trackId": trackID,
                "mode": mode.rawValue
            ]
        )
    }

    private func lyricSecondaryKey(trackID: String, mode: LyricSecondaryMode) -> String {
        "\(trackID)|\(mode.rawValue)"
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
        playbackStateReceivedAt = Date()
        isSeeking = false
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

        if volumeWriteInFlightSeq != nil || !commandWriteInflight.isEmpty {
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
        guard let sonyCommandCharacteristic else {
            log("[VOL-iOS] dropped reason=\(isReconnectInProgress ? "reconnecting" : "characteristic not ready") value=\(value)")
            return
        }

        pendingWriteCommand = "SET_VOLUME"
        volumeWriteInFlightSeq = seq
        lastVolumeSendAtMs = startMs
        commandWriteInflight.append(
            CommandWriteInfo(
                seq: seq,
                cmd: "SET_VOLUME",
                writeCalledAtMs: startMs
            )
        )
        ctrlLog("[CTRL-iOS] write begin seq=\(seq) cmd=SET_VOLUME timeMs=\(startMs)")
        sonyPeripheral.writeValue(data, for: sonyCommandCharacteristic, type: .withResponse)
        lastSuccessfulWriteAt = Date()
        ctrlLog("[CTRL-iOS] write called seq=\(seq) cmd=SET_VOLUME timeMs=\(currentTimeMs())")
        log("[VOL-iOS] send SET_VOLUME value=\(value) reason=\(forceFinal ? "final" : reason)")
        log("[BLE] write requested \(text)")
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
        guard volumeWriteInFlightSeq == nil, commandWriteInflight.isEmpty else {
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
        DispatchQueue.main.async {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss.SSS"
            self.logs.append("[\(formatter.string(from: Date()))] \(message)")
            if self.logs.count > 300 {
                self.logs.removeFirst(self.logs.count - 300)
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
        }
    }

    private func stopHealthMonitoring(reason: String) {
        healthCheckWorkItem?.cancel()
        healthCheckWorkItem = nil
        healthProbeTimeoutWorkItem?.cancel()
        healthProbeTimeoutWorkItem = nil
        subscribeNotifyTimeoutWorkItem?.cancel()
        subscribeNotifyTimeoutWorkItem = nil
        healthProbeStartedAt = nil
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
        log("[BLE-Health] started reason=\(reason)")
        updateConnectionHealthDebugFields()
        scheduleHealthTick()
    }

    private func scheduleHealthTick() {
        healthCheckWorkItem?.cancel()
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
            if ageMs > CONNECTION_HEALTH_STALE_MS {
                setConnectionHealth(.stale, reason: "notify stale ageMs=\(ageMs)")
                performHardReconnect(reason: "health stale no notify ageMs=\(ageMs)", manual: false)
                return
            }
            if ageMs > CONNECTION_HEALTH_SUSPECT_MS {
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
        let now = Date()
        if healthProbeStartedAt != nil {
            log("[BLE-Health] probe skipped reason=in flight trigger=\(reason)")
            return
        }
        if let lastHealthProbeAt,
           let lastStatusNotifyAt,
           lastHealthProbeAt > lastStatusNotifyAt {
            log("[BLE-Health] probe skipped reason=already sent in suspect cycle trigger=\(reason)")
            return
        }
        guard sonyCharacteristicsReady else {
            performHardReconnect(reason: "probe skipped not ready", manual: false)
            return
        }
        lastHealthProbeAt = now
        healthProbeStartedAt = now
        updateConnectionHealthDebugFields()
        let seq = nextCommandSeq()
        log("[BLE-Health] probe sent seq=\(seq) reason=\(reason)")
        sendCommand(cmd: "GET_PLAYBACK_STATE", seq: seq)
        let startedAt = now
        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  let healthProbeStartedAt = self.healthProbeStartedAt,
                  abs(healthProbeStartedAt.timeIntervalSince(startedAt)) < 0.001 else {
                return
            }
            let costMs = Int64(Date().timeIntervalSince(startedAt) * 1_000)
            self.log("[BLE-Health] probe timeout costMs=\(costMs)")
            self.setConnectionHealth(.stale, reason: "probe timeout")
            self.performHardReconnect(reason: "health probe timeout", manual: false)
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
            healthProbeStartedAt = nil
            healthProbeTimeoutWorkItem?.cancel()
            healthProbeTimeoutWorkItem = nil
        }
        if lastNotifySubscribedAt != nil {
            subscribeNotifyTimeoutWorkItem?.cancel()
            subscribeNotifyTimeoutWorkItem = nil
        }
        setConnectionHealth(.healthy, reason: "notify type=\(type)")
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
        centralManager.connect(peripheral)
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
        sonyCommandCharacteristic = nil
        sonyStatusCharacteristic = nil
        firstConnectionReadyAtMs = 0
        commandWriteInflight.removeAll()
        liveActivityControlInFlightSeq = nil
        liveActivityControlWriteStartedAtMs = 0
        clearPendingVolume()
        albumArtReceiver.resetForConnectionLoss(reason: reason)
        resetRemoteLogTransfer()
        resetMediaFieldDumpTransfer()
        resetTrackInfoTransfer()
        resetFullLyricsTransfer()
        lyricSecondaryTransfer = nil
        pendingLyricSecondaryModes.removeAll()
        isRemoteLogTransferInProgress = false
        isMediaFieldDumpReceiving = false
        mediaFieldDumpProgressText = ""
        log("[BLE-Reconnect] cancel in-flight albumArt/secondary")
    }

    private func syncAfterReconnect(reason: String) {
        log("[BLE-Reconnect] sync playback state reason=\(reason)")
        sendGetPlaybackState()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            self?.log("[BLE-Reconnect] sync volume")
            self?.sendGetVolume()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            self?.log("[BLE-Reconnect] defer full lyrics")
            self?.requestFullLyricsIfNeeded(after: 0)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.log("[BLE-Reconnect] defer secondary")
        }
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
    }

    @objc private func appDidEnterBackground() {
        updateAppLifecycleState(.background, emitLog: true)
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
        appLifecycleState = value
        if emitLog {
            ctrlLog("[APP-LIFECYCLE] \(value)")
        }
    }

    private func handleAppForegroundReconnectCheck() {
        let connected = sonyPeripheral?.state == .connected
        let ready = connected && sonyCommandCharacteristic != nil && sonyStatusCharacteristic != nil
        log(
            "[BLE-Reconnect] app foreground check connected=\(connected) " +
                "ready=\(ready) health=\(connectionHealthState)"
        )
        guard centralManager.state == .poweredOn else { return }
        if !connected {
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
        } else if connectionHealthState == ConnectionHealthState.stale.rawValue ||
                    connectionHealthState == ConnectionHealthState.disconnected.rawValue {
            performHardReconnect(
                reason: "foreground unhealthy state=\(connectionHealthState)",
                manual: false
            )
        } else {
            startHealthMonitoring(reason: "foreground ready")
            syncAfterReconnect(reason: "foreground ready")
        }
    }

    func albumArtConsoleLog(_ message: String) {
        AppLogStore.shared.append(message)
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
        if central.state == .poweredOn, shouldScanWhenPoweredOn {
            beginSonyScan(reason: "poweredOn pending scan", isAutoReconnect: false, force: false)
        } else if central.state == .poweredOn, autoReconnectEnabled, sonyPeripheral?.state != .connected {
            scheduleReconnect(reason: "central poweredOn", immediate: true)
        } else if central.state != .poweredOn {
            stopHealthMonitoring(reason: "central not powered")
            setConnectionHealth(.disconnected, reason: "central state=\(central.state.rawValue)")
            setStatus("未连接")
            setAutoReconnectState(.failed)
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
        healthProbeStartedAt = nil
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
        guard sonyPeripheral?.identifier == peripheral.identifier else {
            log("[BLE-Reconnect] ignore stale didDisconnect id=\(peripheral.identifier.uuidString)")
            return
        }
        isConnectingToSony = false
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
        let errorText = error?.localizedDescription ?? "none"
        autoReconnectLastDisconnectError = errorText
        setStatus(autoReconnectEnabled ? "正在连接" : "未连接")
        if !autoReconnectEnabled {
            setAutoReconnectState(.idle)
        }
        log("[BLE] disconnected error=\(errorText)")
        log("[BLE-iOS] didDisconnect error=\(errorText)")
        log("[BLE-Reconnect] disconnected error=\(errorText)")
        reconnectStateSyncWindowUntilMs = 0
        reconnectStateSyncPlaybackLogged = false
        stopHealthMonitoring(reason: "didDisconnect")
        setConnectionHealth(.disconnected, reason: "didDisconnect")
        sonyPeripheral = nil
        connectedDeviceName = "-"
        clearConnectionTransports(reason: "disconnect")
        clearPendingVolume()
        log("[BLE-Reconnect] update LiveActivity disconnected")
        updateLiveActivityDisconnected()
        if autoReconnectEnabled {
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
                "[CTRL-iOS] didWrite seq=unknown cmd=\(pendingWriteCommand) " +
                    "timeMs=\(didWriteMs) costMs=unknown error=\(errorText)"
            )
        }

        if let error {
            if pendingWriteCommand == "SET_VOLUME" {
                log("[iOS][BLE] write SET_VOLUME failed: \(error.localizedDescription)")
            } else {
                log(
                    "[Command] \(pendingWriteCommand) failed " +
                        "error=\(error.localizedDescription)"
                )
            }
            performHardReconnect(reason: "didWrite error \(error.localizedDescription)", manual: false)
        } else if pendingWriteCommand == "SET_VOLUME" {
            log("[iOS][BLE] write SET_VOLUME success")
        } else {
            log("[Command] \(pendingWriteCommand) success")
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
            let subscribeTimeout = DispatchWorkItem { [weak self] in
                guard let self,
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
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                self?.log("[BLE-Reconnect] send CLIENT_CAPABILITIES")
                self?.sendCommand(
                    cmd: "CLIENT_CAPABILITIES",
                    extra: ["albumArtBinary": true]
                )
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.log("[BLE-Reconnect] sync playback state")
                self?.sendGetPlaybackState()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                self?.log("[BLE-Reconnect] sync volume")
                self?.sendGetVolume()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
                self?.log("[BLE-Reconnect] defer full lyrics")
                self?.requestFullLyricsIfNeeded(after: 0)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                self?.setAutoReconnectState(.connected)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 6.0) { [weak self] in
                self?.log("[StartupLoad] deferred request=historyStats delayMs=6000")
                self?.syncPlaybackHistory()
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
        if data.first == 0xA1 {
            markStatusNotifyReceived(type: "albumArtBinaryChunk")
            albumArtReceiver.handleBinaryChunk(data)
            return
        }

        guard let text = String(data: data, encoding: .utf8) else {
            log("[Status] invalid UTF-8")
            return
        }

        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let type = object["type"] as? String,
           type == "logChunk" ||
               type == "albumArtChunk" ||
               type == "trackInfoChunk" ||
               type == "mediaFieldDumpChunk" {
            log("[BLE] status notify received type=\(type)")
        } else {
            log("[BLE] status notify received: \(text)")
        }
        parseStatus(data)
    }

    private func parseStatus(_ data: Data) {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String else {
            log("[Status] JSON parse failed")
            return
        }

        DispatchQueue.main.async {
            self.markStatusNotifyReceived(type: type)
            switch type {
            case "playbackState":
                let oldLyric = self.lyric
                let oldIsPlaying = self.isPlaying
                let oldPositionMs = self.positionMs
                let reconnectSyncWindow = self.isInReconnectStateSyncWindow()
                self.isPlaying = object["playing"] as? Bool ?? false
                self.durationMs = Self.int64Value(object["duration"])
                self.updateLightweightLyricDiagnostic(from: object)
                if let lyric = object["lyric"] as? String {
                    self.lyric = lyric
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
                    self.positionMs = Self.int64Value(object["position"])
                    self.displayPositionMs = self.positionMs
                    self.seekPositionMs = self.positionMs
                    self.basePlaybackPositionMs = self.positionMs
                    self.playbackStateReceivedAt = Date()
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

            case "lyricSecondaryUnavailable", "lyricSecondaryError":
                self.handleLyricSecondaryUnavailable(object)

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
                self.albumArtReceiver.handleBinaryStart(id: id, quality: quality, size: size, chunks: chunks)

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
                self.albumArtReceiver.handleBinaryEnd(id: id, quality: quality)

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
                self.pendingHistoryRequests.removeValue(forKey: requestId)
                self.isPlaybackHistorySyncing = false
                self.isLoadingMoreHistory = false
                self.playbackHistoryStatus = "同步失败：\(message)"
                self.log("[HistorySync] error requestId=\(requestId) message=\(message)")

            default:
                self.log("[Status] unsupported type=\(type)")
            }
        }
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
        var data = Data()
        for index in 0..<assembly.expectedChunks {
            guard let chunk = assembly.chunks[index] else {
                log("[HistorySync] payload missing chunk requestId=\(requestId) index=\(index)")
                return
            }
            data.append(chunk)
        }
        guard data.count == assembly.expectedSize,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            log("[HistorySync] payload decode failed requestId=\(requestId)")
            return
        }
        log("[HistorySync] payload decoded requestId=\(requestId) bytes=\(data.count)")
        handleHistoryPayload(object)
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
                playbackHistoryStatus = "统计解析失败"
                log("[HistorySync] stats decode failed requestId=\(requestId)")
                return
            }
            playbackStats[stats.range] = stats
            PlaybackHistoryStore.shared.saveStats(stats)
            pendingHistoryRequests.removeValue(forKey: requestId)
            playbackHistoryStatus = "统计已更新"
            log("[HistorySync] stats updated range=\(stats.range)")

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
        let trackChanged = newTitle != title ||
            newArtist != artist ||
            newAlbum != album ||
            (!trackID.isEmpty && trackID != currentTrackID)
        if trackChanged {
            lyric = ""
            fullLyricsTrackId = ""
            isFullLyricsCurrent = false
            requestedFullLyricsTrackIDs.removeAll()
            fullLyricsUnavailableTrackIDs.removeAll()
            fullLyricsDelayedRetryTrackIDs.removeAll()
            fullLyricsOptionalRefreshTrackIDs.removeAll()
            requestedLyricSecondaryKeys.removeAll()
            completedLyricSecondaryKeys.removeAll()
            ignoredLyricSecondaryPlaceholderKeys.removeAll()
            pendingLyricSecondaryModes.removeAll()
            lyricSecondaryTransfer = nil
            lyricDiagnostic = nil
            lyricDiagnosticLoading = false
            lyricDiagnosticLastUpdatedAt = nil
            log("[Lyrics-iOS] keep previous lyrics until new chunks")
        }
        title = newTitle
        artist = newArtist
        album = newAlbum
        if !trackID.isEmpty {
            currentTrackID = trackID
            albumArtReceiver.handleIdentity(id: trackID)
            requestFullLyricsIfNeeded(after: isInStartupLoadWindow() ? 0.9 : 0.3)
        }
        log("[TrackInfo] updated title=\(title) artist=\(artist)")
        updateLiveActivity(force: true, reason: "trackInfo")
    }

    private func updateLiveActivity(force: Bool, reason: String) {
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
                connectionState: "connected",
                appState: self.appLifecycleState,
                reason: reason,
                force: force,
                logger: { [weak self] message in
                    self?.log(message)
                }
            )
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

        if secondsSinceLastRequest >= 15 {
            log("[LiveActivityPerf] progress request reason=interval")
            return true
        }

        return false
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
        if !force,
           fullLyricsTrackId == trackID,
           !fullLyrics.isEmpty {
            return
        }
        if !force,
           requestedFullLyricsTrackIDs.contains(trackID) {
            return
        }
        requestedFullLyricsTrackIDs.insert(trackID)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self else { return }
            guard self.currentTrackID == trackID else { return }
            self.log("[LyricsPerf] request fullLyrics trackId=\(trackID)")
            self.sendCommand(
                cmd: "GET_FULL_LYRICS",
                extra: [
                    "trackId": trackID,
                    "positionMs": self.displayPositionMs,
                    "includeWordsAroundCurrent": true
                ]
            )
        }
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
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self else { return }
            guard self.currentTrackID == trackID,
                  self.fullLyrics.isEmpty else { return }
            self.log("[Lyrics-iOS] retry GET_FULL_LYRICS trackId=\(trackID)")
            self.requestFullLyricsIfNeeded(force: true)
        }
        return true
    }

    private func handleFullLyricsStart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        guard !trackID.isEmpty, trackID == currentTrackID else {
            log("[FullLyrics] stale start ignored trackId=\(trackID)")
            return
        }
        fullLyricsUnavailableTrackIDs.remove(trackID)
        fullLyricsTimeoutWorkItem?.cancel()
        fullLyricsReceivingTrackID = trackID
        fullLyricsExpectedCount = Self.intValue(object["count"])
        fullLyricsChunks.removeAll()
        isFullLyricsReceiving = true
        lastFullLyricsPartialPublishAtMs = 0
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
        log(
            "[Lyrics-iOS] chunk index=\(index) " +
                "trans=\((object["translation"] as? String)?.isEmpty == false) " +
                "roma=\((object["romanization"] as? String)?.isEmpty == false) " +
                "words=\(words.count)"
        )
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
        log("[FullLyrics] unavailable reason=\(reason)")
    }

    private func handleLyricSecondaryStart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let transferID = object["transferId"] as? String ?? ""
        let modeRaw = object["mode"] as? String ?? ""
        guard trackID == currentTrackID,
              fullLyricsTrackId == trackID,
              let mode = LyricSecondaryMode(rawValue: modeRaw),
              !transferID.isEmpty else {
            log("[Lyrics-iOS] secondary discarded stale trackId=\(trackID)")
            return
        }
        lyricSecondaryTransfer = LyricSecondaryTransfer(
            trackId: trackID,
            transferId: transferID,
            mode: mode,
            itemCount: Self.intValue(object["itemCount"]),
            lines: [:]
        )
        log(
            "[Lyrics-iOS] secondary start mode=\(mode.rawValue) " +
                "items=\(Self.intValue(object["itemCount"]))"
        )
    }

    private func handleLyricSecondaryPart(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? ""
        let transferID = object["transferId"] as? String ?? ""
        let modeRaw = object["mode"] as? String ?? ""
        guard trackID == currentTrackID,
              var transfer = lyricSecondaryTransfer,
              transfer.trackId == trackID,
              transfer.transferId == transferID,
              transfer.mode.rawValue == modeRaw else {
            return
        }
        let lineIndex = Self.intValue(object["lineIndex"])
        let partIndex = Self.intValue(object["partIndex"])
        let partCount = Self.intValue(object["partCount"])
        guard lineIndex >= 0,
              partIndex >= 0,
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
              transfer.trackId == trackID,
              transfer.transferId == transferID,
              transfer.mode.rawValue == modeRaw else {
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
        let itemMissing = max(transfer.itemCount - assembled.count, 0) + missing
        log(
            "[Lyrics-iOS] secondary end mode=\(transfer.mode.rawValue) " +
                "completed=\(assembled.count) missing=\(itemMissing)"
        )
        guard itemMissing == 0 else {
            log(
                "[Lyrics-iOS] secondary incomplete mode=\(transfer.mode.rawValue) " +
                    "missingLines=\(itemMissing)"
            )
            lyricSecondaryTransfer = nil
            requestNextLyricSecondaryIfPossible()
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
        lyricSecondaryTransfer = nil
        log(
            "[Lyrics-iOS] secondary publish mode=\(transfer.mode.rawValue) " +
                "lines=\(assembled.count)"
        )
        requestNextLyricSecondaryIfPossible()
    }

    private func handleLyricSecondaryUnavailable(_ object: [String: Any]) {
        let trackID = object["trackId"] as? String ?? currentTrackID
        let modeRaw = object["mode"] as? String ?? ""
        if let mode = LyricSecondaryMode(rawValue: modeRaw) {
            completedLyricSecondaryKeys.insert(lyricSecondaryKey(trackID: trackID, mode: mode))
        }
        lyricSecondaryTransfer = nil
        log(
            "[Lyrics-iOS] secondary unavailable mode=\(modeRaw) " +
                "reason=\(object["reason"] as? String ?? object["message"] as? String ?? "")"
        )
        requestNextLyricSecondaryIfPossible()
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
        isFullLyricsReceiving = false
        lastFullLyricsPartialPublishAtMs = 0
    }

    private func publishPartialFullLyricsIfNeeded(trackID: String) {
        guard trackID == currentTrackID,
              fullLyricsChunks.count >= 3 else {
            return
        }
        let nowMs = currentTimeMs()
        guard nowMs - lastFullLyricsPartialPublishAtMs >= 200 else {
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
        let sortedLines = lines.sorted { $0.index < $1.index }
        fullLyrics = sortedLines
        fullLyricsTrackId = trackID
        isFullLyricsCurrent = true
        let wordsCount = sortedLines.reduce(0) { $0 + $1.words.count }
        let transCount = sortedLines.filter { sanitizedSecondaryText($0.translation) != nil }.count
        let romaCount = sortedLines.filter { sanitizedSecondaryText($0.romanization) != nil }.count
        if isFinal {
            log(
                "[FullLyrics] end count=\(fullLyrics.count) " +
                    "transCount=\(transCount) romaCount=\(romaCount)"
            )
            log(
                "[LyricsPerf] final publish lines=\(sortedLines.count) " +
                    "words=\(wordsCount) transCount=\(transCount) romaCount=\(romaCount)"
            )
        } else {
            log(
                "[LyricsPerf] partial publish lines=\(sortedLines.count) " +
                    "receiving=\(isFullLyricsReceiving) " +
                    "transCount=\(transCount) romaCount=\(romaCount)"
            )
        }
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
        let nowMs = currentTimeMs()

        currentWordLineIndex = lineIndex
        currentWordIndex = wordIndex
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
        currentWordLastLatencyMs = timestampMs > 0 ? max(nowMs - timestampMs, 0) : 0

        let lineByOffset = fullLyrics.indices.contains(lineIndex) ? fullLyrics[lineIndex] : nil
        if isSameTrackId(incoming: trackID, current: fullLyricsTrackId),
           let line = fullLyrics.first(where: { $0.index == lineIndex }) ?? lineByOffset {
            lyric = line.text
        }

        if !isSeeking {
            positionMs = remotePositionMs
            displayPositionMs = remotePositionMs
            seekPositionMs = remotePositionMs
            basePlaybackPositionMs = remotePositionMs
            playbackStateReceivedAt = Date()
        }

        log(
            "[Lyrics-iOS] currentWord line=\(lineIndex) word=\(wordIndex) " +
                "position=\(remotePositionMs) latencyMs=\(currentWordLastLatencyMs) " +
                "count=\(currentWordPushCount) avgIntervalMs=\(currentWordAverageUpdateIntervalMs)"
        )
        if isInReconnectStateSyncWindow() {
            log(
                "[Reconnect] currentWord accepted after reconnect " +
                    "line=\(lineIndex) word=\(wordIndex)"
            )
        }

        _ = updateLiveActivityForCurrentLyricIfNeeded(reason: "currentWord")
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

    private func startProgressTimer() {
        progressTimer?.invalidate()
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in
            self?.updateInterpolatedProgress()
        }
        progressTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func updateInterpolatedProgress() {
        guard !isSeeking else { return }
        guard durationMs > 0 else {
            displayPositionMs = 0
            return
        }
        guard isPlaying else {
            displayPositionMs = positionMs.clamped(to: 0...durationMs)
            return
        }

        let elapsedMs = Int64(Date().timeIntervalSince(playbackStateReceivedAt) * 1_000)
        let interpolated = (basePlaybackPositionMs + max(elapsedMs, 0))
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

        var data = Data()
        data.reserveCapacity(trackInfoExpectedSize)
        for index in 0..<trackInfoExpectedChunks {
            guard let chunk = trackInfoChunks[index] else {
                log("[TrackInfo] decode failed missing index=\(index)")
                resetTrackInfoTransfer()
                return
            }
            data.append(chunk)
        }

        guard data.count == trackInfoExpectedSize,
              let object = try? JSONSerialization.jsonObject(
                with: data
              ) as? [String: Any],
              object["type"] as? String == "trackInfo" else {
            log("[TrackInfo] decode failed")
            resetTrackInfoTransfer()
            return
        }
        applyTrackInfo(object)
        log("[TrackInfo] decode success bytes=\(data.count)")
        resetTrackInfoTransfer()
    }

    private func resetTrackInfoTransfer() {
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
        log("[LiveArtwork] current main album key=\(key)")
        let wrote = LiveActivityArtworkStore.shared.writeThumbnail(
            image: image,
            key: key,
            revision: revision,
            logger: { [weak self] message in
                self?.log(message)
            }
        )
        guard currentTrackID == trackAtStart,
              albumArtReceiver.currentAlbumArtID == key else {
            log(
                "[LiveArtwork] stale result ignored oldTrackId=\(trackAtStart) " +
                    "currentTrackId=\(currentTrackID)"
            )
            return
        }
        guard wrote else {
            log("[LiveArtwork] update skipped reason=write failed key=\(key)")
            return
        }

        currentLiveArtworkKey = key
        currentLiveArtworkRevision = revision
        log(
            "[LiveArtwork] update requested key=\(key) " +
                "revision=\(revision) source=\(reason)"
        )
        updateLiveActivity(force: true, reason: "artworkReady")
    }

    private func clearLiveArtwork(reason: String, shouldUpdate: Bool) {
        guard currentLiveArtworkKey != nil else { return }
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
        return 0
    }

    private static func doubleValue(_ value: Any?) -> Double {
        if let number = value as? NSNumber {
            return number.doubleValue
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
