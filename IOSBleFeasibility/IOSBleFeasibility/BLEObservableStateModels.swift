import Combine
import Foundation
import Observation
import UIKit

final class BLEInboundPipeline: @unchecked Sendable {
    private let queue = DispatchQueue(
        label: "com.musicblecontroller.ble-inbound-pipeline",
        qos: .userInitiated
    )

    func submit(_ operation: @escaping @Sendable () -> Void) {
        queue.async(execute: operation)
    }
}

final class BLEStatusObjectBox: @unchecked Sendable {
    let value: [String: Any]
    init(_ value: [String: Any]) { self.value = value }
}

final class ObservableStateSlice<Value: Equatable>: ObservableObject {
    @Published private(set) var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func update(_ newValue: Value) {
        guard value != newValue else { return }
        value = newValue
    }
}

struct BLEConnectionViewState: Equatable {
    var status = "未连接"
    var displayState = "disconnected"
    var healthState = "disconnected"
    var deviceName = "-"
    var characteristicReady = false
    var autoReconnectState = "idle"
}

struct BLEPlaybackViewState: Equatable {
    var title = "-"
    var artist = "-"
    var album = "-"
    var isPlaying = false
    var positionMs: Int64 = 0
    var displayPositionMs: Int64 = 0
    var durationMs: Int64 = 0
    var seekPositionMs: Int64 = 0
    var isSeeking = false
    var volumeCurrent = 0
    var volumeMax = 0
    var volumeSeekValue = 0
    var isVolumeSeeking = false
}

struct BLELyricsViewState: Equatable {
    var lyric = ""
    var fullLyrics: [LyricLine] = []
    var fullLyricsTrackId = ""
    var isCurrent = false
    var isReceiving = false
    var currentWordLineIndex = -1
    var currentWordIndex = -1
    var loadingStage: LyricLoadingStage = .idle
    var translationState: LyricSecondaryLoadState = .idle
    var romanizationState: LyricSecondaryLoadState = .idle
}

struct BLEArtworkViewState: Equatable {
    var image: UIImage?
    var displayQuality: ArtworkDisplayQuality = .placeholder
    var enhancementStatus = ArtworkEnhancementDebugStatus()
    var isRestoredSnapshot = false
    var loadingStage: ArtworkLoadingStage = .idle

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.image === rhs.image &&
            lhs.displayQuality == rhs.displayQuality &&
            lhs.enhancementStatus == rhs.enhancementStatus &&
            lhs.isRestoredSnapshot == rhs.isRestoredSnapshot &&
            lhs.loadingStage == rhs.loadingStage
    }
}

struct BLEDiagnosticsViewState: Equatable {
    var logCount = 0
    var connectionAttemptId = "-"
    var lastHardReconnectReason = "-"
    var remoteLogInProgress = false
    var mediaDumpInProgress = false
    var lyricDiagnosticLoading = false
}

enum BLEBluetoothAvailability: String, Equatable {
    case unknown
    case available
    case poweredOff
    case unauthorized
    case unsupported
    case resetting
}

enum BLEConnectionFailure: String, Equatable {
    case deviceNotFound
    case timeout
    case serviceUnavailable
    case incompatibleProtocol
    case connectionLost
    case unknown
}

enum BLEConnectionPresentationState: Equatable {
    case unavailable(BLEBluetoothAvailability)
    case disconnected(lastDeviceName: String?)
    case scanning
    case connecting(deviceName: String?)
    case reconnecting(deviceName: String?, attempt: Int)
    case connected(deviceName: String, health: String)
    case failed(BLEConnectionFailure)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}

struct BLEPlaybackMetadataState: Equatable {
    var title = "-"
    var artist = "-"
    var album = "-"
}

struct BLEPlaybackTimelineState: Equatable {
    var isPlaying = false
    var positionMs: Int64 = 0
    var displayPositionMs: Int64 = 0
    var durationMs: Int64 = 0
    var seekPositionMs: Int64 = 0
    var isSeeking = false
}

struct BLEVolumeViewState: Equatable {
    var current = 0
    var maximum = 0
    var seekValue = 0
    var isSeeking = false
}

struct BLELiveLyricState: Equatable {
    var text = ""
    var currentWordLineIndex = -1
    var currentWordIndex = -1
    var loadingStage: LyricLoadingStage = .idle
}

struct BLEFullLyricsViewState: Equatable {
    var lines: [LyricLine] = []
    var trackId = ""
    var isCurrent = false
    var isReceiving = false
    var translationState: LyricSecondaryLoadState = .idle
    var romanizationState: LyricSecondaryLoadState = .idle
}

@Observable
final class ConnectionStore {
    private(set) var state = BLEConnectionViewState()
    private(set) var presentation: BLEConnectionPresentationState = .disconnected(lastDeviceName: nil)

    func update(
        state newState: BLEConnectionViewState,
        presentation newPresentation: BLEConnectionPresentationState
    ) {
        if state != newState { state = newState }
        if presentation != newPresentation { presentation = newPresentation }
    }
}

@Observable
final class PlaybackStore {
    private(set) var metadata = BLEPlaybackMetadataState()
    private(set) var timeline = BLEPlaybackTimelineState()
    private(set) var volume = BLEVolumeViewState()

    func updateMetadata(_ value: BLEPlaybackMetadataState) {
        if metadata != value { metadata = value }
    }

    func updateTimeline(_ value: BLEPlaybackTimelineState) {
        if timeline != value { timeline = value }
    }

    func updateVolume(_ value: BLEVolumeViewState) {
        if volume != value { volume = value }
    }
}

@Observable
final class LyricsStore {
    private(set) var live = BLELiveLyricState()
    private(set) var document = BLEFullLyricsViewState()

    func updateLive(_ value: BLELiveLyricState) {
        if live != value { live = value }
    }

    func updateDocument(_ value: BLEFullLyricsViewState) {
        if document != value { document = value }
    }
}

@Observable
final class ArtworkStore {
    private(set) var state = BLEArtworkViewState()

    func update(_ value: BLEArtworkViewState) {
        if state != value { state = value }
    }
}

@Observable
final class DiagnosticsStore {
    private(set) var state = BLEDiagnosticsViewState()

    func update(_ value: BLEDiagnosticsViewState) {
        if state != value { state = value }
    }
}

struct BLEProtocolV3Features: OptionSet, Equatable, Sendable {
    let rawValue: Int

    static let statusMetaV1 = Self(rawValue: 1 << 0)
    static let structuredErrorV1 = Self(rawValue: 1 << 1)
    static let mediaLoadStateV1 = Self(rawValue: 1 << 2)
    static let all: Self = [.statusMetaV1, .structuredErrorV1, .mediaLoadStateV1]
}

struct BLEProtocolV2Features: OptionSet, Equatable, Sendable {
    let rawValue: Int

    static let albumArtBinary = Self(rawValue: 1 << 0)
    static let fullLyricsZlib = Self(rawValue: 1 << 1)
    static let lyricWindow = Self(rawValue: 1 << 2)
    static let ping = Self(rawValue: 1 << 3)
    static let clockSyncV1 = Self(rawValue: 1 << 4)
    static let transferRetry = Self(rawValue: 1 << 5)
}

struct BLECapabilitiesAck: Equatable, Sendable {
    var protocolVersion = 1
    var v2Features: BLEProtocolV2Features = []
    var v3Features: BLEProtocolV3Features = []
    var sessionId: String?
}

struct BLEStatusMetadata: Equatable, Sendable {
    let sessionId: String
    let eventSequence: UInt64
}

enum BLEEventSequenceObservation: Equatable, Sendable {
    case first
    case inOrder
    case duplicate
    case gap(missing: UInt64)
    case outOfOrder
    case newSession
}

struct BLEEventSequenceDiagnostics: Sendable {
    private(set) var sessionId: String?
    private(set) var highestSequence: UInt64 = 0

    mutating func reset(sessionId: String? = nil) {
        self.sessionId = sessionId
        highestSequence = 0
    }

    mutating func observe(_ metadata: BLEStatusMetadata) -> BLEEventSequenceObservation {
        guard sessionId == metadata.sessionId else {
            let hadSession = sessionId != nil
            sessionId = metadata.sessionId
            highestSequence = metadata.eventSequence
            return hadSession ? .newSession : .first
        }
        if metadata.eventSequence == highestSequence { return .duplicate }
        if metadata.eventSequence < highestSequence { return .outOfOrder }
        let previous = highestSequence
        highestSequence = metadata.eventSequence
        if previous > 0, metadata.eventSequence > previous + 1 {
            return .gap(missing: metadata.eventSequence - previous - 1)
        }
        return previous == 0 ? .first : .inOrder
    }
}

struct LiveActivityArtworkRevisionFence: Sendable {
    private(set) var current: UInt64 = 0

    mutating func begin() -> UInt64 {
        current &+= 1
        return current
    }

    mutating func invalidate() {
        current &+= 1
    }

    func accepts(_ revision: UInt64) -> Bool {
        revision == current
    }
}

enum BLECommandErrorDomain: String, Equatable, Sendable {
    case `protocol`
    case lyrics
    case artwork
    case history
    case connection
    case unknown
}

struct BLECommandErrorPayload: Equatable, Sendable {
    let sequence: UInt64?
    let command: String
    let domain: BLECommandErrorDomain
    let code: String
    let retryable: Bool
    let retryAfterMs: Int64?
    let trackId: String?
    let generation: Int64?
    let metadata: BLEStatusMetadata?
}

enum BLEMediaResource: String, Equatable, Sendable {
    case lyrics
    case artwork
}

enum BLEMediaLoadStage: String, Equatable, Sendable {
    case waiting
    case preparing
    case transferring
    case ready
    case unavailable
    case failed
}

struct BLEMediaLoadPayload: Equatable, Sendable {
    let resource: BLEMediaResource
    let stage: BLEMediaLoadStage
    let reason: String
    let retryable: Bool
    let retryAfterMs: Int64?
    let trackId: String
    let generation: Int64?
    let metadata: BLEStatusMetadata?

    var deduplicationKey: String {
        "\(trackId)|\(generation ?? -1)|\(resource.rawValue)|\(stage.rawValue)|\(reason)"
    }
}

enum BLEProtocolV3Parser {
    static let maximumJSONNotifyBytes = 64 * 1_024

    static func jsonObject(from data: Data) -> [String: Any]? {
        guard !data.isEmpty, data.count <= maximumJSONNotifyBytes else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    static func capabilitiesAck(from object: [String: Any]) -> BLECapabilitiesAck {
        let protocolVersion = int(object["protocolVersion"], default: 1)
        if protocolVersion >= 3, object["f2"] != nil {
            return BLECapabilitiesAck(
                protocolVersion: protocolVersion,
                v2Features: BLEProtocolV2Features(rawValue: int(object["f2"])),
                v3Features: BLEProtocolV3Features(rawValue: int(object["f3"])),
                sessionId: nonEmptyString(object["sid"])
            )
        }

        var v2: BLEProtocolV2Features = []
        if bool(object["albumArtBinary"]) { v2.insert(.albumArtBinary) }
        if bool(object["fullLyricsZlib"]) { v2.insert(.fullLyricsZlib) }
        if bool(object["lyricWindow"]) { v2.insert(.lyricWindow) }
        if bool(object["ping"]) { v2.insert(.ping) }
        if bool(object["clockSyncV1"]) { v2.insert(.clockSyncV1) }
        if bool(object["transferRetry"]) { v2.insert(.transferRetry) }
        return BLECapabilitiesAck(protocolVersion: protocolVersion, v2Features: v2)
    }

    static func statusMetadata(from object: [String: Any]) -> BLEStatusMetadata? {
        guard let sessionId = nonEmptyString(object["sid"]),
              let sequence = uint64(object["es"]) else {
            return nil
        }
        return BLEStatusMetadata(sessionId: sessionId, eventSequence: sequence)
    }

    static func commandError(from object: [String: Any]) -> BLECommandErrorPayload? {
        guard let command = nonEmptyString(object["cmd"]),
              let code = nonEmptyString(object["code"]) else {
            return nil
        }
        return BLECommandErrorPayload(
            sequence: uint64(object["seq"]),
            command: command,
            domain: BLECommandErrorDomain(
                rawValue: nonEmptyString(object["domain"]) ?? ""
            ) ?? .unknown,
            code: code,
            retryable: bool(object["retryable"]),
            retryAfterMs: int64(object["retryAfterMs"]),
            trackId: nonEmptyString(object["trackId"]),
            generation: int64(object["generation"]),
            metadata: statusMetadata(from: object)
        )
    }

    static func mediaLoadState(from object: [String: Any]) -> BLEMediaLoadPayload? {
        guard let resourceRaw = nonEmptyString(object["resource"]),
              let resource = BLEMediaResource(rawValue: resourceRaw),
              let stageRaw = nonEmptyString(object["stage"]),
              let stage = BLEMediaLoadStage(rawValue: stageRaw),
              let trackId = nonEmptyString(object["trackId"]) else {
            return nil
        }
        return BLEMediaLoadPayload(
            resource: resource,
            stage: stage,
            reason: nonEmptyString(object["reason"]) ?? "unknown",
            retryable: bool(object["retryable"]),
            retryAfterMs: int64(object["retryAfterMs"]),
            trackId: trackId,
            generation: int64(object["generation"]),
            metadata: statusMetadata(from: object)
        )
    }

    private static func nonEmptyString(_ value: Any?) -> String? {
        let text: String?
        if let value = value as? String {
            text = value
        } else if let value = value as? NSNumber {
            text = value.stringValue
        } else {
            text = nil
        }
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func bool(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String { return value == "true" || value == "1" }
        return false
    }

    private static func int(_ value: Any?, default defaultValue: Int = 0) -> Int {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        if let value = value as? String, let result = Int(value) { return result }
        return defaultValue
    }

    private static func int64(_ value: Any?) -> Int64? {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        if let value = value as? String { return Int64(value) }
        return nil
    }

    private static func uint64(_ value: Any?) -> UInt64? {
        if let value = value as? UInt64 { return value }
        if let value = value as? Int, value >= 0 { return UInt64(value) }
        if let value = value as? NSNumber { return value.uint64Value }
        if let value = value as? String { return UInt64(value) }
        return nil
    }
}

enum PlaybackClockPolicy {
    static func shouldRun(
        isPlaying: Bool,
        durationMs: Int64,
        appLifecycleState: String
    ) -> Bool {
        isPlaying && durationMs > 0 && appLifecycleState == "active"
    }
}

struct MonotonicClockSyncSnapshot: Equatable {
    let localMinusServerMs: Double
    let bestRoundTripMs: Int64
    let offsetJitterMs: Int64
    let sampleCount: Int
    let isConfident: Bool
}

struct MonotonicClockSynchronizer {
    private struct Sample {
        let localMinusServerMs: Double
        let roundTripMs: Int64
        let receivedAtLocalMs: Int64
    }

    private var samples: [Sample] = []
    private(set) var snapshot: MonotonicClockSyncSnapshot?

    mutating func reset() {
        samples.removeAll(keepingCapacity: true)
        snapshot = nil
    }

    @discardableResult
    mutating func record(
        clientSendElapsedMs: Int64,
        serverReceiveElapsedMs: Int64,
        serverSendElapsedMs: Int64,
        clientReceiveElapsedMs: Int64
    ) -> MonotonicClockSyncSnapshot? {
        guard clientSendElapsedMs > 0,
              serverReceiveElapsedMs > 0,
              serverSendElapsedMs >= serverReceiveElapsedMs,
              clientReceiveElapsedMs >= clientSendElapsedMs else {
            return snapshot
        }
        let serverProcessingMs = serverSendElapsedMs - serverReceiveElapsedMs
        let roundTripMs = clientReceiveElapsedMs - clientSendElapsedMs - serverProcessingMs
        guard roundTripMs >= 0, roundTripMs <= Self.maximumAcceptedRoundTripMs else {
            return snapshot
        }
        let localMinusServerMs = (
            Double(clientSendElapsedMs - serverReceiveElapsedMs) +
                Double(clientReceiveElapsedMs - serverSendElapsedMs)
        ) / 2.0
        samples.removeAll {
            clientReceiveElapsedMs - $0.receivedAtLocalMs > Self.sampleLifetimeMs
        }
        samples.append(
            Sample(
                localMinusServerMs: localMinusServerMs,
                roundTripMs: roundTripMs,
                receivedAtLocalMs: clientReceiveElapsedMs
            )
        )
        if samples.count > Self.maximumSamples {
            samples.removeFirst(samples.count - Self.maximumSamples)
        }

        let best = Array(samples.sorted { $0.roundTripMs < $1.roundTripMs }.prefix(3))
        guard let minimumRoundTrip = best.map(\.roundTripMs).min() else {
            snapshot = nil
            return nil
        }
        let sortedOffsets = best.map(\.localMinusServerMs).sorted()
        let offset: Double
        if sortedOffsets.count == 2 {
            offset = (sortedOffsets[0] + sortedOffsets[1]) / 2.0
        } else {
            offset = sortedOffsets[sortedOffsets.count / 2]
        }
        let jitter = Int64(
            ((sortedOffsets.last ?? offset) - (sortedOffsets.first ?? offset)).rounded()
        )
        let value = MonotonicClockSyncSnapshot(
            localMinusServerMs: offset,
            bestRoundTripMs: minimumRoundTrip,
            offsetJitterMs: max(jitter, 0),
            sampleCount: samples.count,
            isConfident: samples.count >= 3 &&
                minimumRoundTrip <= Self.confidentRoundTripMs &&
                jitter <= Self.confidentOffsetJitterMs
        )
        snapshot = value
        return value
    }

    func transportAgeMs(
        serverSampleElapsedMs: Int64,
        localReceiveElapsedMs: Int64
    ) -> Int64? {
        guard serverSampleElapsedMs > 0,
              let snapshot,
              snapshot.isConfident else {
            return nil
        }
        let mappedLocalSample = Double(serverSampleElapsedMs) + snapshot.localMinusServerMs
        return Int64((Double(localReceiveElapsedMs) - mappedLocalSample).rounded())
    }

    private static let maximumAcceptedRoundTripMs: Int64 = 1_500
    private static let confidentRoundTripMs: Int64 = 300
    private static let confidentOffsetJitterMs: Int64 = 100
    private static let sampleLifetimeMs: Int64 = 5 * 60 * 1_000
    private static let maximumSamples = 12
}

enum RemotePlaybackAnchorResolution: Equatable {
    case unavailable
    case stale(transportAgeMs: Int64)
    case resolved(positionMs: Int64, transportAgeMs: Int64)
}

enum RemotePlaybackAnchorPolicy {
    static let staleTransportAgeMs: Int64 = 1_500

    static func resolve(
        remotePositionMs: Int64,
        serverSampleElapsedMs: Int64,
        localReceiveElapsedMs: Int64,
        playbackSpeed: Double,
        isPlaying: Bool,
        durationMs: Int64,
        synchronizer: MonotonicClockSynchronizer
    ) -> RemotePlaybackAnchorResolution {
        guard let measuredAge = synchronizer.transportAgeMs(
            serverSampleElapsedMs: serverSampleElapsedMs,
            localReceiveElapsedMs: localReceiveElapsedMs
        ) else {
            return .unavailable
        }
        return resolve(
            remotePositionMs: remotePositionMs,
            measuredTransportAgeMs: measuredAge,
            playbackSpeed: playbackSpeed,
            isPlaying: isPlaying,
            durationMs: durationMs
        )
    }

    static func resolve(
        remotePositionMs: Int64,
        measuredTransportAgeMs measuredAge: Int64,
        playbackSpeed: Double,
        isPlaying: Bool,
        durationMs: Int64
    ) -> RemotePlaybackAnchorResolution {
        guard measuredAge >= -75 else { return .unavailable }
        let transportAgeMs = max(measuredAge, 0)
        guard transportAgeMs <= staleTransportAgeMs else {
            return .stale(transportAgeMs: transportAgeMs)
        }
        let normalizedSpeed = playbackSpeed.isFinite && playbackSpeed > 0
            ? playbackSpeed
            : 1.0
        let advanceMs = isPlaying
            ? Int64((Double(transportAgeMs) * normalizedSpeed).rounded())
            : 0
        let projected = remotePositionMs + advanceMs
        let position = durationMs > 0
            ? min(max(projected, 0), durationMs)
            : max(projected, 0)
        return .resolved(positionMs: position, transportAgeMs: transportAgeMs)
    }

    static func smoothedPosition(
        currentPositionMs: Int64,
        targetPositionMs: Int64,
        force: Bool = false
    ) -> Int64 {
        guard !force else { return targetPositionMs }
        let delta = targetPositionMs - currentPositionMs
        switch abs(delta) {
        case 0..<80:
            return currentPositionMs
        case 80...400:
            return currentPositionMs + delta / 2
        default:
            return targetPositionMs
        }
    }
}

struct CurrentWordOrderingFence {
    private(set) var generation: Int64 = -1
    private(set) var sequence: Int64 = -1
    private(set) var positionMs: Int64 = -1

    mutating func shouldAccept(
        generation incomingGeneration: Int64,
        sequence incomingSequence: Int64,
        positionMs incomingPositionMs: Int64
    ) -> Bool {
        if incomingGeneration > 0, incomingSequence > 0 {
            if incomingGeneration < generation ||
                (incomingGeneration == generation && incomingSequence <= sequence) {
                return false
            }
        }
        if positionMs >= 0,
           incomingPositionMs < positionMs,
           positionMs - incomingPositionMs <= 1_500 {
            return false
        }
        if incomingGeneration > 0, incomingSequence > 0 {
            generation = incomingGeneration
            sequence = incomingSequence
        }
        positionMs = incomingPositionMs
        return true
    }

    mutating func reset() {
        generation = -1
        sequence = -1
        positionMs = -1
    }
}

// MARK: - V4 realtime latency trace

struct RealtimeTraceEvent: Equatable {
    let sequence: UInt64
    let side: String
    let stage: String
    let monoMs: Int64
    let commandSeq: Int64?
    let commandType: String?
    let trackId: String?
    let generation: Int64?
    let transferId: String?
    let payloadType: String?
    let queueWaitMs: Int64?
    let processingMs: Int64?
    let chunkIndex: Int?
    let chunkCount: Int?
    let result: String?
    let reason: String?

    var logLine: String {
        let fields: [(String, String)] = [
            ("side", side),
            ("stage", stage),
            ("monoMs", String(monoMs)),
            ("commandSeq", commandSeq.map(String.init) ?? "-"),
            ("commandType", commandType ?? "-"),
            ("trackId", trackId ?? "-"),
            ("generation", generation.map(String.init) ?? "-"),
            ("transferId", transferId ?? "-"),
            ("payloadType", payloadType ?? "-"),
            ("queueWaitMs", queueWaitMs.map(String.init) ?? "-"),
            ("processingMs", processingMs.map(String.init) ?? "-"),
            ("chunkIndex", chunkIndex.map(String.init) ?? "-"),
            ("chunkCount", chunkCount.map(String.init) ?? "-"),
            ("result", result ?? "-"),
            ("reason", reason ?? "-")
        ]
        return "[RealtimeTrace] " + fields
            .map { "\($0.0)=\(Self.safe($0.1))" }
            .joined(separator: " ")
    }

    private static func safe(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(
            CharacterSet(charactersIn: "-_.:/")
        )
        return String(
            value.unicodeScalars.prefix(96).map { scalar in
                allowed.contains(scalar) ? Character(String(scalar)) : "_"
            }
        )
    }
}

final class RealtimeTraceBuffer {
    typealias MonotonicClock = () -> Int64

    let capacity: Int
    private let clock: MonotonicClock
    private let lock = NSLock()
    private var storage: [RealtimeTraceEvent?]
    private var nextIndex = 0
    private var storedCount = 0
    private var nextSequence: UInt64 = 0
    private var lastMonoMs: Int64 = 0

    init(
        capacity: Int = 2_048,
        clock: @escaping MonotonicClock = {
            Int64(ProcessInfo.processInfo.systemUptime * 1_000)
        }
    ) {
        self.capacity = max(capacity, 1)
        self.clock = clock
        storage = Array(repeating: nil, count: max(capacity, 1))
    }

    @discardableResult
    func append(
        side: String = "ios",
        stage: String,
        monoMs suppliedMonoMs: Int64? = nil,
        commandSeq: Int64? = nil,
        commandType: String? = nil,
        trackId: String? = nil,
        generation: Int64? = nil,
        transferId: String? = nil,
        payloadType: String? = nil,
        queueWaitMs: Int64? = nil,
        processingMs: Int64? = nil,
        chunkIndex: Int? = nil,
        chunkCount: Int? = nil,
        result: String? = nil,
        reason: String? = nil
    ) -> RealtimeTraceEvent {
        lock.lock()
        defer { lock.unlock() }
        let sampled = suppliedMonoMs ?? clock()
        let monoMs = max(sampled, lastMonoMs)
        lastMonoMs = monoMs
        nextSequence &+= 1
        let event = RealtimeTraceEvent(
            sequence: nextSequence,
            side: side,
            stage: stage,
            monoMs: monoMs,
            commandSeq: commandSeq,
            commandType: commandType,
            trackId: trackId,
            generation: generation,
            transferId: transferId,
            payloadType: payloadType,
            queueWaitMs: queueWaitMs,
            processingMs: processingMs,
            chunkIndex: chunkIndex,
            chunkCount: chunkCount,
            result: result,
            reason: reason
        )
        storage[nextIndex] = event
        nextIndex = (nextIndex + 1) % capacity
        storedCount = min(storedCount + 1, capacity)
        return event
    }

    func snapshot() -> [RealtimeTraceEvent] {
        lock.lock()
        defer { lock.unlock() }
        guard storedCount > 0 else { return [] }
        let start = storedCount == capacity ? nextIndex : 0
        return (0..<storedCount).compactMap { offset in
            storage[(start + offset) % capacity]
        }
    }

    func clear() {
        lock.lock()
        storage = Array(repeating: nil, count: capacity)
        nextIndex = 0
        storedCount = 0
        lastMonoMs = 0
        lock.unlock()
    }
}

final class RealtimeTraceStore {
    static let shared = RealtimeTraceStore()

    let buffer: RealtimeTraceBuffer
    var enabled: Bool
    private let logSink: (String) -> Void

    init(
        capacity: Int = 2_048,
        enabled: Bool = {
#if DEBUG
            true
#else
            false
#endif
        }(),
        clock: @escaping RealtimeTraceBuffer.MonotonicClock = {
            Int64(ProcessInfo.processInfo.systemUptime * 1_000)
        },
        logSink: @escaping (String) -> Void = { AppLogStore.shared.append($0) }
    ) {
        buffer = RealtimeTraceBuffer(capacity: capacity, clock: clock)
        self.enabled = enabled
        self.logSink = logSink
    }

    @discardableResult
    func record(
        stage: String,
        monoMs: Int64? = nil,
        commandSeq: Int64? = nil,
        commandType: String? = nil,
        trackId: String? = nil,
        generation: Int64? = nil,
        transferId: String? = nil,
        payloadType: String? = nil,
        queueWaitMs: Int64? = nil,
        processingMs: Int64? = nil,
        chunkIndex: Int? = nil,
        chunkCount: Int? = nil,
        result: String? = nil,
        reason: String? = nil
    ) -> RealtimeTraceEvent? {
        guard enabled else { return nil }
        let event = buffer.append(
            stage: stage,
            monoMs: monoMs,
            commandSeq: commandSeq,
            commandType: commandType,
            trackId: trackId,
            generation: generation,
            transferId: transferId,
            payloadType: payloadType,
            queueWaitMs: queueWaitMs,
            processingMs: processingMs,
            chunkIndex: chunkIndex,
            chunkCount: chunkCount,
            result: result,
            reason: reason
        )
        logSink(event.logLine)
        return event
    }

    func summary() -> RealtimeTraceSummary {
        RealtimeTraceSummary(events: buffer.snapshot())
    }
}

enum RealtimeTraceStatistics {
    static func percentile(_ values: [Int64], percentile: Double) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let clamped = min(max(percentile, 0), 100)
        let rank = clamped / 100 * Double(sorted.count - 1)
        let lower = Int(rank.rounded(.down))
        let upper = Int(rank.rounded(.up))
        guard lower != upper else { return Double(sorted[lower]) }
        let fraction = rank - Double(lower)
        return Double(sorted[lower]) +
            (Double(sorted[upper] - sorted[lower]) * fraction)
    }
}

enum RealtimeLatencyPolicy {
    static func crossDeviceDuration(
        startMonoMs: Int64,
        endMonoMs: Int64,
        clockTrusted: Bool
    ) -> Int64? {
        guard clockTrusted, endMonoMs >= startMonoMs else { return nil }
        return endMonoMs - startMonoMs
    }
}

struct RealtimeTraceSummary: Equatable {
    let eventCount: Int
    let missingResultCount: Int
    let latestMonoMs: Int64?
    let stageCounts: [String: Int]

    init(events: [RealtimeTraceEvent]) {
        eventCount = events.count
        missingResultCount = events.filter { $0.result == nil }.count
        latestMonoMs = events.last?.monoMs
        stageCounts = Dictionary(grouping: events, by: \.stage)
            .mapValues(\.count)
    }
}
