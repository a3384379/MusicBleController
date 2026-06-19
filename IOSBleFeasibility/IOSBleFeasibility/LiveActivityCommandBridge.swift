import Foundation

final class LiveActivityCommandBridge {
    static let shared = LiveActivityCommandBridge()

    private weak var sender: LiveActivityBLECommandSending?
    private var logger: ((String) -> Void)?

    private init() {}

    var isRegistered: Bool {
        sender != nil
    }

    func register(
        _ sender: LiveActivityBLECommandSending,
        logger: @escaping (String) -> Void
    ) {
        self.sender = sender
        self.logger = logger
        logger("[LA-CTRL] bridge registered")
    }

    func unregister(_ sender: LiveActivityBLECommandSending) {
        guard self.sender === sender else { return }
        self.sender = nil
        logger?("[LA-CTRL] bridge unregistered")
        logger = nil
    }

    @MainActor
    func performIntent(command: LiveActivityControlCommand) -> LiveActivityControlResult {
        let seq = Self.makeSeq()
        let issuedAt = Date()
        let startedAt = Self.currentTimeMs()
        log("[LA-CTRL] intent received seq=\(seq) cmd=\(command.rawValue)")
        let result = execute(
            command: command,
            seq: seq,
            issuedAt: issuedAt
        )
        let costMs = Self.currentTimeMs() - startedAt
        log(
            "[LA-CTRL] intent completed seq=\(seq) cmd=\(command.rawValue) " +
                "costMs=\(costMs) result=\(result.rawValue)"
        )
        return result
    }

    @MainActor
    func execute(
        command: LiveActivityControlCommand,
        seq: UInt64,
        issuedAt: Date
    ) -> LiveActivityControlResult {
        guard let sender else {
            log("[LA-CTRL] bridge result seq=\(seq) result=\(LiveActivityControlResult.bridgeUnavailable.rawValue)")
            return .bridgeUnavailable
        }
        let result = sender.sendLiveActivityCommand(
            command,
            seq: seq,
            issuedAt: issuedAt
        )
        log("[LA-CTRL] bridge result seq=\(seq) result=\(result.rawValue)")
        return result
    }

    func log(_ message: String) {
        if let logger {
            logger(message)
        } else {
            print(message)
        }
    }

    private static func makeSeq() -> UInt64 {
        let timePart = UInt64(Date().timeIntervalSince1970 * 1_000)
        let randomPart = UInt64.random(in: 0...999)
        return timePart * 1_000 + randomPart
    }

    private static func currentTimeMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}
