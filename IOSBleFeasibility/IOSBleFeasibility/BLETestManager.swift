import CoreBluetooth
import Foundation
import UIKit

final class BLETestManager: NSObject, ObservableObject {
    @Published private(set) var mode = "BLE Central / GATT Client"
    @Published private(set) var connectionStatus = "未连接"
    @Published private(set) var logs: [String] = []
    @Published private(set) var title = "-"
    @Published private(set) var artist = "-"
    @Published private(set) var album = "-"
    @Published private(set) var lyric = ""
    @Published private(set) var isPlaying = false
    @Published private(set) var positionMs: Int64 = 0
    @Published private(set) var durationMs: Int64 = 0
    @Published private(set) var seekPositionMs: Int64 = 0
    @Published private(set) var isSeeking = false
    @Published private(set) var volumeCurrent = 0
    @Published private(set) var volumeMax = 0
    @Published private(set) var volumeSeekValue = 0
    @Published private(set) var isVolumeSeeking = false
    @Published private(set) var albumArtImage: UIImage?
    @Published private(set) var remoteLogText = ""
    @Published private(set) var remoteLogCopyStatus = ""
    @Published private(set) var isRemoteLogTransferInProgress = false

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private lazy var peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

    private var sonyPeripheral: CBPeripheral?
    private var sonyCommandCharacteristic: CBCharacteristic?
    private var sonyStatusCharacteristic: CBCharacteristic?
    private var pendingWriteCommand = ""
    private var albumArtID = ""
    private var albumArtExpectedSize = 0
    private var albumArtExpectedChunks = 0
    private var albumArtChunks: [Int: Data] = [:]
    private var remoteLogExpectedChunks = 0
    private var remoteLogExpectedLines = 0
    private var remoteLogChunks: [Int: Data] = [:]

    private var commandCharacteristic: CBMutableCharacteristic?
    private var statusCharacteristic: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []
    private var shouldStartAdvertising = false
    private var shouldScanWhenPoweredOn = false
    private var scanTimeoutWorkItem: DispatchWorkItem?

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
        setMode("BLE Central / GATT Client")
        shouldScanWhenPoweredOn = true
        _ = centralManager

        guard centralManager.state == .poweredOn else {
            setStatus("未连接")
            log("[BLE] waiting for Bluetooth")
            return
        }

        beginSonyScan()
    }

    func sendPlayPause() {
        sendCommand(cmd: "PLAY_PAUSE")
        refreshPlaybackState(after: 0.5)
    }

    func sendNext() {
        sendCommand(cmd: "NEXT")
        refreshPlaybackState(after: 0.5)
    }

    func sendPrevious() {
        sendCommand(cmd: "PREVIOUS")
        refreshPlaybackState(after: 0.5)
    }

    func sendVolumeUp() {
        sendCommand(cmd: "VOLUME_UP")
        refreshVolume(after: 0.3)
    }

    func sendVolumeDown() {
        sendCommand(cmd: "VOLUME_DOWN")
        refreshVolume(after: 0.3)
    }

    func sendGetPlaybackState() {
        sendCommand(cmd: "GET_PLAYBACK_STATE")
    }

    func sendGetVolume() {
        sendCommand(cmd: "GET_VOLUME")
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

    func sendCommand(cmd: String, extra: [String: Any] = [:]) {
        var payload = extra
        payload["cmd"] = cmd
        payload["time"] = Int64(Date().timeIntervalSince1970 * 1_000)

        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            log("[Command] encode failed \(cmd)")
            return
        }

        guard let sonyPeripheral,
              sonyPeripheral.state == .connected,
              let sonyCommandCharacteristic else {
            log("[Command] send failed \(cmd): not connected")
            return
        }

        pendingWriteCommand = cmd
        sonyPeripheral.writeValue(data, for: sonyCommandCharacteristic, type: .withResponse)
        if cmd == "SET_VOLUME" {
            log("[iOS][BLE] write SET_VOLUME requested")
        } else {
            log("[Command] send \(cmd)")
        }
        log("[BLE] write requested \(text)")
    }

    func seek(to position: Int64) {
        sendCommand(cmd: "SEEK_TO", extra: ["position": max(position, 0)])
    }

    func beginSeeking() {
        guard durationMs > 0 else { return }
        isSeeking = true
        seekPositionMs = positionMs
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
        isSeeking = false
        log("[iOS][Seek] user set position=\(targetPosition)")
        seek(to: targetPosition)
        refreshPlaybackState(after: 0.5)
    }

    func beginVolumeSeeking() {
        guard volumeMax > 0 else { return }
        if !isVolumeSeeking {
            volumeSeekValue = volumeCurrent
        }
        isVolumeSeeking = true
    }

    func updateVolumeSeekValue(_ value: Double) {
        if !isVolumeSeeking {
            isVolumeSeeking = true
        }
        volumeSeekValue = Int(value.rounded()).clamped(to: 0...max(volumeMax, 0))
    }

    func finishVolumeSeeking() {
        guard volumeMax > 0 else { return }
        let targetVolume = volumeSeekValue.clamped(to: 0...volumeMax)
        volumeCurrent = targetVolume
        isVolumeSeeking = false
        log("[iOS][Volume] user set volume=\(targetVolume)")
        sendCommand(cmd: "SET_VOLUME", extra: ["volume": targetVolume])
    }

    private func beginSonyScan() {
        shouldScanWhenPoweredOn = false
        if let sonyPeripheral, sonyPeripheral.state != .disconnected {
            centralManager.cancelPeripheralConnection(sonyPeripheral)
        }

        sonyPeripheral = nil
        sonyCommandCharacteristic = nil
        sonyStatusCharacteristic = nil
        resetAlbumArtTransfer()
        resetRemoteLogTransfer()
        isRemoteLogTransferInProgress = false
        scanTimeoutWorkItem?.cancel()
        centralManager.stopScan()
        centralManager.scanForPeripherals(
            withServices: [BLEUUIDs.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        setStatus("扫描中")
        log("[BLE] scan started")

        let timeoutWorkItem = DispatchWorkItem { [weak self] in
            guard let self, self.sonyPeripheral == nil else { return }
            self.centralManager.stopScan()
            self.setStatus("未连接")
            self.log("[BLE] scan timeout: SonyPlayerAgent not found")
        }
        scanTimeoutWorkItem = timeoutWorkItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeoutWorkItem)
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
        DispatchQueue.main.async {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss.SSS"
            self.logs.append("[\(formatter.string(from: Date()))] \(message)")
            if self.logs.count > 300 {
                self.logs.removeFirst(self.logs.count - 300)
            }
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
        }
    }
}

extension BLETestManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log("[BLE] central state=\(central.state.rawValue)")
        if central.state == .poweredOn, shouldScanWhenPoweredOn {
            beginSonyScan()
        } else if central.state != .poweredOn {
            setStatus("未连接")
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

        guard sonyPeripheral == nil else { return }

        scanTimeoutWorkItem?.cancel()
        sonyPeripheral = peripheral
        central.stopScan()
        peripheral.delegate = self
        setStatus("连接中")
        log("[BLE] connecting \(name) id=\(peripheral.identifier)")
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        setStatus("连接中")
        log("[BLE] connected")
        peripheral.discoverServices([BLEUUIDs.service])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        setStatus("未连接")
        log("[BLE] connection failed error=\(error?.localizedDescription ?? "unknown")")
        sonyPeripheral = nil
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        setStatus("未连接")
        log("[BLE] disconnected error=\(error?.localizedDescription ?? "none")")
        sonyPeripheral = nil
        sonyCommandCharacteristic = nil
        sonyStatusCharacteristic = nil
        resetAlbumArtTransfer()
        resetRemoteLogTransfer()
        isRemoteLogTransferInProgress = false
    }
}

extension BLETestManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            log("[BLE] service discovery failed error=\(error.localizedDescription)")
            return
        }

        guard let service = peripheral.services?.first(where: { $0.uuid == BLEUUIDs.service }) else {
            log("[BLE] target service not found")
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
        if let error {
            log("[BLE] characteristic discovery failed error=\(error.localizedDescription)")
            return
        }

        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == BLEUUIDs.command {
                sonyCommandCharacteristic = characteristic
                log("[BLE] command characteristic found")
            } else if characteristic.uuid == BLEUUIDs.status {
                sonyStatusCharacteristic = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
                log("[BLE] status characteristic found")
            }
        }

        if sonyCommandCharacteristic != nil {
            setStatus("连接中")
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error {
            if pendingWriteCommand == "SET_VOLUME" {
                log("[iOS][BLE] write SET_VOLUME failed: \(error.localizedDescription)")
            } else {
                log(
                    "[Command] \(pendingWriteCommand) failed " +
                        "error=\(error.localizedDescription)"
                )
            }
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
        guard characteristic.uuid == BLEUUIDs.status else { return }

        if let error {
            setStatus("未连接")
            log("[BLE] status notify subscription failed: \(error.localizedDescription)")
        } else {
            setStatus(characteristic.isNotifying ? "已连接" : "连接中")
            log("[BLE] status notify subscribed")
            guard characteristic.isNotifying else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.sendGetPlaybackState()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                self?.sendGetVolume()
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == BLEUUIDs.status else { return }

        if let error {
            log("[BLE] status notify receive failed: \(error.localizedDescription)")
            return
        }

        guard let data = characteristic.value,
              let text = String(data: data, encoding: .utf8) else {
            log("[Status] invalid UTF-8")
            return
        }

        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let type = object["type"] as? String,
           type == "logChunk" || type == "albumArtChunk" {
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
            switch type {
            case "playbackState":
                self.title = object["title"] as? String ?? "-"
                self.artist = object["artist"] as? String ?? "-"
                self.album = object["album"] as? String ?? "-"
                self.lyric = object["lyric"] as? String ?? ""
                self.isPlaying = object["playing"] as? Bool ?? false
                self.durationMs = Self.int64Value(object["duration"])
                if !self.isSeeking {
                    self.positionMs = Self.int64Value(object["position"])
                    self.seekPositionMs = self.positionMs
                }
                self.log(
                    "[iOS][Status] playbackState title=\(self.title) " +
                        "position=\(self.positionMs) duration=\(self.durationMs)"
                )

            case "volumeState":
                self.volumeMax = Self.intValue(object["max"])
                if !self.isVolumeSeeking {
                    self.volumeCurrent = Self.intValue(object["current"])
                    self.volumeSeekValue = self.volumeCurrent
                }
                self.log(
                    "[Status] volumeState current=\(self.volumeCurrent) " +
                        "max=\(self.volumeMax)"
                )

            case "albumArtStart":
                let id = object["id"] as? String ?? ""
                let size = Self.intValue(object["size"])
                let chunks = Self.intValue(object["chunks"])
                guard !id.isEmpty, size > 0, chunks > 0 else {
                    self.resetAlbumArtTransfer()
                    self.log(
                        "[AlbumArt] invalid start id=\(id) " +
                            "size=\(size) chunks=\(chunks)"
                    )
                    return
                }
                self.resetAlbumArtTransfer()
                self.albumArtID = id
                self.albumArtExpectedSize = size
                self.albumArtExpectedChunks = chunks
                self.log("[AlbumArt] start chunks=\(chunks) size=\(size)")

            case "albumArtChunk":
                let id = object["id"] as? String ?? ""
                let index = Self.intValue(object["index"])
                guard self.albumArtExpectedChunks > 0,
                      id == self.albumArtID,
                      index >= 0,
                      index < self.albumArtExpectedChunks,
                      let base64 = object["data"] as? String,
                      let chunk = Data(base64Encoded: base64) else {
                    self.log("[AlbumArt] invalid chunk id=\(id) index=\(index)")
                    return
                }
                self.albumArtChunks[index] = chunk
                self.log("[AlbumArt] chunk index=\(index) bytes=\(chunk.count)")

            case "albumArtEnd":
                let id = object["id"] as? String ?? ""
                self.log(
                    "[AlbumArt] end received=\(self.albumArtChunks.count) " +
                        "expected=\(self.albumArtExpectedChunks)"
                )
                guard id == self.albumArtID else {
                    self.log(
                        "[AlbumArt] decode failed id mismatch " +
                            "expected=\(self.albumArtID) actual=\(id)"
                    )
                    self.resetAlbumArtTransfer()
                    return
                }
                self.finishAlbumArtTransfer(id: id)

            case "albumArtUnavailable":
                let id = object["id"] as? String ?? ""
                self.resetAlbumArtTransfer()
                self.albumArtImage = nil
                self.log("[AlbumArt] unavailable id=\(id)")

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

            default:
                self.log("[Status] unsupported type=\(type)")
            }
        }
    }

    private func finishAlbumArtTransfer(id: String) {
        let missingIndexes = (0..<albumArtExpectedChunks).filter {
            albumArtChunks[$0] == nil
        }
        log("[AlbumArt] missing indexes=\(missingIndexes)")
        guard albumArtExpectedChunks > 0,
              missingIndexes.isEmpty,
              albumArtChunks.count == albumArtExpectedChunks else {
            log("[AlbumArt] decode failed")
            resetAlbumArtTransfer()
            return
        }

        var jpegData = Data()
        jpegData.reserveCapacity(albumArtExpectedSize)
        for index in 0..<albumArtExpectedChunks {
            guard let chunk = albumArtChunks[index] else {
                log("[AlbumArt] decode failed missing chunk index=\(index)")
                resetAlbumArtTransfer()
                return
            }
            jpegData.append(chunk)
        }

        guard jpegData.count == albumArtExpectedSize else {
            log("[AlbumArt] decode failed")
            resetAlbumArtTransfer()
            return
        }

        guard let image = UIImage(data: jpegData) else {
            log("[AlbumArt] decode failed")
            resetAlbumArtTransfer()
            return
        }

        albumArtImage = image
        let pixelWidth = image.cgImage?.width ?? Int(image.size.width)
        let pixelHeight = image.cgImage?.height ?? Int(image.size.height)
        log(
            "[AlbumArt] decode success " +
                "imageSize=\(pixelWidth)x\(pixelHeight)"
        )
        resetAlbumArtTransfer()
    }

    private func resetAlbumArtTransfer() {
        albumArtID = ""
        albumArtExpectedSize = 0
        albumArtExpectedChunks = 0
        albumArtChunks.removeAll()
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
        log(
            "[RemoteLog] decode success lines=\(decodedLines) " +
                "expected=\(remoteLogExpectedLines)"
        )
        resetRemoteLogTransfer()
    }

    private func resetRemoteLogTransfer() {
        remoteLogExpectedChunks = 0
        remoteLogExpectedLines = 0
        remoteLogChunks.removeAll()
    }

    private static func int64Value(_ value: Any?) -> Int64 {
        if let number = value as? NSNumber {
            return number.int64Value
        }
        return 0
    }

    private static func intValue(_ value: Any?) -> Int {
        if let number = value as? NSNumber {
            return number.intValue
        }
        return 0
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
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
