# 专辑封面架构

本文记录 AlbumArt 从 Sony 到 iPhone、缓存、增强和诊断的当前实现。`AlbumArtReceiver` 已从 `BLETestManager` 拆出，后续不要倒退。

## 模块职责

- Sony `AlbumArtTestManager`：从 MediaMetadata 和通知 largeIcon 中探测当前可用封面。当前真实链路常见来源是 QQMusic notification largeIcon。
- Sony `BleGattServerManager`：处理 `ALBUM_ART_REQUEST`，压缩 preview/HQ/fallback，发送 `albumArtOffer` 和 binary chunk。
- Sony `BleNotifyQueue`：发送 `albumArt` 长任务，控制 chunk 进度、超时和短状态让路。
- iOS `AlbumArtReceiver`：接收 offer、请求 preview/HQ、处理 binary start/chunk/end、超时恢复、缓存、displayQuality、enhanced、诊断 snapshot。
- iOS `ArtworkEnhancementManager`：本地离线视觉增强缓存，不覆盖 HQ 原图。
- Live Activity：通过 `LiveActivityArtworkStore` 写 App Group 小缩略图，`ContentState` 只带 key/revision。

## 核心文件

- iOS 接收：[AlbumArtReceiver.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AlbumArtReceiver.swift)
- iOS 增强：[ArtworkEnhancementManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ArtworkEnhancementManager.swift)
- iOS 诊断模型：[NowPlayingDiagnosticSnapshot.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticSnapshot.swift)
- iOS Live Activity 缩略图：[LiveActivityArtworkStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LiveActivityArtworkStore.swift)
- Sony GATT 封面发送：[BleGattServerManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/BleGattServerManager.kt)
- Sony 封面探测：[AlbumArtTestManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/AlbumArtTestManager.kt)
- iOS smoke AlbumArt 验收：[ios_album_art_flow_test.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_album_art_flow_test.sh)

## 数据流

1. Sony 从当前歌曲构造 `albumArtId`，通过 status notify 发 `albumArtOffer`。
2. iOS `BLETestManager.parseStatus` 将 `albumArtOffer` 转交 `albumArtReceiver.handleOffer(id:)`。
3. `AlbumArtReceiver` 优先查 enhanced cache，再查 HQ cache，再查 preview cache。
4. 无可用缓存时请求 `ALBUM_ART_REQUEST quality=preview`。
5. preview 成功后调度 HQ 请求；HQ 成功后可触发本地 enhanced。
6. 二进制传输：
   - JSON：`albumArtBinaryStart` / `albumArtBinaryEnd`
   - 二进制 chunk：6 字节 header + payload
7. iOS 保存 `Documents/AlbumArtCache/`，增强图保存到 Enhanced 子目录。
8. 主 UI 使用最终 `albumArtImage`；Live Activity 使用 App Group 小图。

## 关键状态

- `ArtworkDisplayQuality`：`placeholder`、`preview`、`hqFallback`、`hq`、`enhanced`。
- `AlbumArtTransferSession`：当前 binary transfer 的 id、quality、totalChunks、receivedChunks、bytesReceived。
- 超时：
  - first chunk：3000ms
  - idle chunk：4000ms
  - total：10000ms
- `AlbumArtSnapshot`：诊断页读取的封面状态，包含 cache、transfer、HQ unavailable 和 enhanced 状态。

## 不允许随便修改的点

- 不要改 AlbumArt binary header 和 quality code。
- 不要把 UIImage/Data/Base64 放进 Live Activity `ContentState`。
- 不要让 HQ 请求在 fullLyrics、lyricSecondary、remoteLog、mediaFieldDump 长任务期间抢占。
- 不要覆盖 HQ 原图；enhanced 必须是独立缓存。
- 不要把封面接收逻辑塞回 `BLETestManager`。

## 常见问题排查入口

- iOS：
  - `[AlbumArt] offer/request/unavailable`
  - `[AlbumArtBinary] start/chunk/end/decode success`
  - `[AlbumArt-iOS] transfer start/first chunk timeout/idle chunk timeout/total timeout/transfer cancelled`
  - `[AlbumArtCache] saved/display quality`
  - `[ArtworkDisplay] upgrade ...`
  - `[ArtworkEnhance] ...`
- Sony：
  - `[AlbumArt-Sony]`
  - `[AlbumArt][BLE] failed`
  - `[BleNotifyQueue] job start type=albumArt`
- 自动验收：
  `./tools/ios-smoke-tests/ios_album_art_flow_test.sh <ios_ble.log>`

## 修改后必须跑哪些 smoke test

- 改 iOS 封面接收/缓存/增强/诊断：quick smoke。
- 必须查看 `report.json` 的 `albumArtFlow`，Sony 不在线时允许 Optional WARN/SKIPPED；若真实在线应看到 PASS。
- 改 Xcode target 或新增 Swift 文件：full smoke。
- 改 Sony 发送压缩或 binary 协议：Android build + iPhone 真机封面链路。
