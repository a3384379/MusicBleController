import Combine
import UIKit

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
