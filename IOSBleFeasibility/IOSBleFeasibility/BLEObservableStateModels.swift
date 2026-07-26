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
