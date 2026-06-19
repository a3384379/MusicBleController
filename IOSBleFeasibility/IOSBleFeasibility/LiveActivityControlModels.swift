import Foundation

enum LiveActivityControlCommand: String, Sendable {
    case previous = "PREVIOUS"
    case playPause = "PLAY_PAUSE"
    case next = "NEXT"
}

enum LiveActivityControlResult: String, Sendable {
    case sent
    case bluetoothUnavailable
    case disconnected
    case characteristicNotReady
    case bridgeUnavailable
    case debounced
    case writeInFlight
    case expired
    case failed
}

struct LiveActivityControlStatus: Equatable {
    var bridgeRegistered: Bool = false
    var bleReady: Bool = false
    var lastIntentSeq: UInt64 = 0
    var lastCommand: LiveActivityControlCommand?
    var lastResult: LiveActivityControlResult = .bridgeUnavailable
    var lastCostMs: Int64 = 0
    var inFlight: Bool = false
    var droppedCount: Int = 0
    var debouncedCount: Int = 0
}

@MainActor
protocol LiveActivityBLECommandSending: AnyObject {
    func sendLiveActivityCommand(
        _ command: LiveActivityControlCommand,
        seq: UInt64,
        issuedAt: Date
    ) -> LiveActivityControlResult
}
