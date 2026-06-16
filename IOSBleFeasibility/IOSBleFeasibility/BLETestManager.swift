import CoreBluetooth
import CryptoKit
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
    @Published private(set) var mediaFieldDumpText = ""
    @Published private(set) var mediaFieldDumpCopyStatus = ""
    @Published private(set) var isMediaFieldDumpReceiving = false
    @Published private(set) var mediaFieldDumpProgressText = ""

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private lazy var peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

    private var sonyPeripheral: CBPeripheral?
    private var sonyCommandCharacteristic: CBCharacteristic?
    private var sonyStatusCharacteristic: CBCharacteristic?
    private var pendingWriteCommand = ""
    private var albumArtID = ""
    private var albumArtQuality = ""
    private var albumArtExpectedSize = 0
    private var albumArtExpectedChunks = 0
    private var albumArtChunks: [Int: Data] = [:]
    private var currentAlbumArtID = ""
    private var currentCachedAlbumArtQuality = ""
    private var requestedAlbumArtKeys: Set<String> = []
    private var albumArtPreviewRetryCount = 0
    private var remoteLogExpectedChunks = 0
    private var remoteLogExpectedLines = 0
    private var remoteLogChunks: [Int: Data] = [:]
    private var mediaFieldDumpExpectedSize = 0
    private var mediaFieldDumpExpectedChunks = 0
    private var mediaFieldDumpChunks: [Int: Data] = [:]
    private var trackInfoExpectedSize = 0
    private var trackInfoExpectedChunks = 0
    private var trackInfoChunks: [Int: Data] = [:]

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
        requestedAlbumArtKeys.removeAll()
        resetRemoteLogTransfer()
        resetMediaFieldDumpTransfer()
        resetTrackInfoTransfer()
        isRemoteLogTransferInProgress = false
        isMediaFieldDumpReceiving = false
        mediaFieldDumpProgressText = ""
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
        requestedAlbumArtKeys.removeAll()
        resetRemoteLogTransfer()
        resetTrackInfoTransfer()
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
            switch type {
            case "playbackState":
                self.isPlaying = object["playing"] as? Bool ?? false
                self.durationMs = Self.int64Value(object["duration"])
                if let lyric = object["lyric"] as? String {
                    self.lyric = lyric
                }
                if !self.isSeeking {
                    self.positionMs = Self.int64Value(object["position"])
                    self.seekPositionMs = self.positionMs
                }
                self.log(
                    "[iOS][Status] playbackState " +
                        "position=\(self.positionMs) duration=\(self.durationMs)"
                )

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

            case "albumArtOffer":
                let id = object["id"] as? String ?? ""
                guard !id.isEmpty else {
                    self.log("[AlbumArt] invalid offer")
                    return
                }
                self.log("[AlbumArt] offer id=\(id)")
                self.handleAlbumArtIdentity(id)
                if self.currentCachedAlbumArtQuality == "full" {
                    self.sendCommand(
                        cmd: "ALBUM_ART_SKIP",
                        extra: ["id": id]
                    )
                } else if self.currentCachedAlbumArtQuality == "preview" {
                    self.requestAlbumArt(id: id, quality: "full")
                } else {
                    self.requestAlbumArt(id: id, quality: "preview")
                }

            case "albumArtStart":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String ?? ""
                let size = Self.intValue(object["size"])
                let chunks = Self.intValue(object["chunks"])
                guard !id.isEmpty,
                      quality == "preview" || quality == "full",
                      size > 0,
                      chunks > 0 else {
                    self.resetAlbumArtTransfer()
                    self.log(
                        "[AlbumArt] invalid start id=\(id) " +
                            "quality=\(quality) size=\(size) chunks=\(chunks)"
                    )
                    return
                }
                self.handleAlbumArtIdentity(id)
                self.resetAlbumArtTransfer()
                self.albumArtID = id
                self.albumArtQuality = quality
                self.albumArtExpectedSize = size
                self.albumArtExpectedChunks = chunks
                self.log(
                    "[AlbumArt] start id=\(id) quality=\(quality) " +
                        "chunks=\(chunks) size=\(size)"
                )

            case "albumArtChunk":
                let id = object["id"] as? String ?? ""
                let quality =
                    object["quality"] as? String ?? self.albumArtQuality
                let index = Self.intValue(object["index"])
                guard self.albumArtExpectedChunks > 0,
                      id == self.albumArtID,
                      quality == self.albumArtQuality,
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
                let quality =
                    object["quality"] as? String ?? self.albumArtQuality
                self.log(
                    "[AlbumArt] end received=\(self.albumArtChunks.count) " +
                        "expected=\(self.albumArtExpectedChunks)"
                )
                guard id == self.albumArtID,
                      quality == self.albumArtQuality else {
                    self.log(
                        "[AlbumArt] decode failed transfer mismatch " +
                            "expected=\(self.albumArtID)/\(self.albumArtQuality) " +
                            "actual=\(id)/\(quality)"
                    )
                    self.resetAlbumArtTransfer()
                    return
                }
                self.finishAlbumArtTransfer(id: id, quality: quality)

            case "albumArtUnavailable":
                let id = object["id"] as? String ?? ""
                let quality = object["quality"] as? String ?? "preview"
                self.resetAlbumArtTransfer()
                self.requestedAlbumArtKeys.remove("\(id)|\(quality)")
                if id == self.currentAlbumArtID,
                   quality == "preview",
                   self.albumArtImage == nil {
                    self.albumArtImage = nil
                    self.retryAlbumArtPreviewIfNeeded(id: id)
                }
                self.log(
                    "[AlbumArt] unavailable id=\(id) quality=\(quality)"
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

            default:
                self.log("[Status] unsupported type=\(type)")
            }
        }
    }

    private func finishAlbumArtTransfer(id: String, quality: String) {
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

        saveAlbumArt(jpegData, id: id, quality: quality)
        if id == currentAlbumArtID {
            albumArtImage = image
            currentCachedAlbumArtQuality = quality
        }
        requestedAlbumArtKeys.remove("\(id)|\(quality)")
        let pixelWidth = image.cgImage?.width ?? Int(image.size.width)
        let pixelHeight = image.cgImage?.height ?? Int(image.size.height)
        log("[AlbumArt] \(quality) decode success")
        log("[AlbumArt] decode success imageSize=\(pixelWidth)x\(pixelHeight)")
        resetAlbumArtTransfer()

        if id == currentAlbumArtID, quality == "preview" {
            albumArtPreviewRetryCount = 0
            requestAlbumArt(id: id, quality: "full")
        }
    }

    private func resetAlbumArtTransfer() {
        albumArtID = ""
        albumArtQuality = ""
        albumArtExpectedSize = 0
        albumArtExpectedChunks = 0
        albumArtChunks.removeAll()
    }

    private func applyTrackInfo(_ object: [String: Any]) {
        let newTitle = object["title"] as? String ?? "-"
        let newArtist = object["artist"] as? String ?? "-"
        let newAlbum = object["album"] as? String ?? "-"
        if newTitle != title || newArtist != artist || newAlbum != album {
            lyric = ""
        }
        title = newTitle
        artist = newArtist
        album = newAlbum
        log("[TrackInfo] updated title=\(title) artist=\(artist)")
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

    private func handleAlbumArtIdentity(_ id: String) {
        guard !id.isEmpty, id != currentAlbumArtID else { return }

        currentAlbumArtID = id
        currentCachedAlbumArtQuality = ""
        requestedAlbumArtKeys.removeAll()
        albumArtPreviewRetryCount = 0
        resetAlbumArtTransfer()

        if let cached = loadCachedAlbumArt(id: id) {
            albumArtImage = cached.image
            currentCachedAlbumArtQuality = cached.quality
            log("[AlbumArtCache] hit id=\(id)")
        } else {
            albumArtImage = nil
            log("[AlbumArtCache] miss id=\(id)")
        }
    }

    private func requestAlbumArt(id: String, quality: String) {
        guard id == currentAlbumArtID else { return }
        let key = "\(id)|\(quality)"
        guard requestedAlbumArtKeys.insert(key).inserted else { return }
        log("[AlbumArt] request \(quality)")
        sendCommand(
            cmd: "ALBUM_ART_REQUEST",
            extra: ["id": id, "quality": quality]
        )
    }

    private func retryAlbumArtPreviewIfNeeded(id: String) {
        guard id == currentAlbumArtID,
              albumArtPreviewRetryCount < 2 else {
            return
        }
        albumArtPreviewRetryCount += 1
        log("[AlbumArt] preview retry=\(albumArtPreviewRetryCount)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let self,
                  self.currentAlbumArtID == id,
                  self.albumArtImage == nil else {
                return
            }
            self.requestAlbumArt(id: id, quality: "preview")
        }
    }

    private func loadCachedAlbumArt(
        id: String
    ) -> (image: UIImage, quality: String)? {
        let imageURL = albumArtCacheURL(id: id)
        guard let data = try? Data(contentsOf: imageURL),
              let image = UIImage(data: data) else {
            return nil
        }

        let qualityURL = albumArtQualityURL(id: id)
        let quality = (
            try? String(contentsOf: qualityURL, encoding: .utf8)
        )?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (image, quality == "preview" ? "preview" : "full")
    }

    private func saveAlbumArt(_ data: Data, id: String, quality: String) {
        do {
            let directory = albumArtCacheDirectory()
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
            try data.write(to: albumArtCacheURL(id: id), options: .atomic)
            try quality.write(
                to: albumArtQualityURL(id: id),
                atomically: true,
                encoding: .utf8
            )
            log("[AlbumArtCache] saved id=\(id)")
        } catch {
            log(
                "[AlbumArtCache] save failed id=\(id) " +
                    "error=\(error.localizedDescription)"
            )
        }
    }

    private func albumArtCacheDirectory() -> URL {
        let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        )[0]
        return documents.appendingPathComponent(
            "AlbumArtCache",
            isDirectory: true
        )
    }

    private func albumArtCacheURL(id: String) -> URL {
        albumArtCacheDirectory()
            .appendingPathComponent(Self.sha256(id))
            .appendingPathExtension("jpg")
    }

    private func albumArtQualityURL(id: String) -> URL {
        albumArtCacheDirectory()
            .appendingPathComponent(Self.sha256(id))
            .appendingPathExtension("quality")
    }

    private static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
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
