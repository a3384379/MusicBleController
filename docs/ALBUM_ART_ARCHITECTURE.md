# 专辑封面架构

本文记录 AlbumArt 从 Sony 到 iPhone、缓存、增强和诊断的当前实现。`AlbumArtReceiver` 已从 `BLETestManager` 拆出，后续不要倒退。

## 模块职责

- Sony `AlbumArtTestManager`：从 MediaMetadata 和通知 largeIcon 中探测当前可用封面。PlayerAgent UI 请求当前封面时必须用当前 title/artist 验证来源身份；当前真实链路常见来源是 QQMusic notification largeIcon。
- Sony `MainActivity`：监听既有 QQ 音乐通知事件，事件到达后在后台执行精确身份读取；用 generation+songKey 防止迟到结果覆盖当前歌曲。
- Sony `BleGattServerManager`：处理 `ALBUM_ART_REQUEST`，压缩 preview/HQ/fallback，发送 `albumArtOffer` 和 binary chunk；短期重传记录由独立 `AlbumArtTransferCoordinator` 持有。
- Sony `BleNotifyQueue`：发送 `albumArt` 长任务，控制 chunk 进度、超时和短状态让路。
- iOS `AlbumArtReceiver`：接收 offer、请求 preview/HQ、处理 binary start/chunk/end、超时恢复、缓存、displayQuality、enhanced、诊断 snapshot。
- iOS `ArtworkImageCache`：缓存已经解码/降采样的 `UIImage`，40 项、32MB；内存警告只清理解码层，不删除磁盘原图。
- iOS `ArtworkEnhancementManager`：本地离线视觉增强缓存，不覆盖 HQ 原图。
- Live Activity：通过 `LiveActivityArtworkStore` 写 App Group 小缩略图，`ContentState` 只带 key/revision。

## 核心文件

- iOS 接收：[AlbumArtReceiver.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AlbumArtReceiver.swift)
- iOS 解码缓存：[ArtworkImageCache.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ArtworkImageCache.swift)
- iOS 增强：[ArtworkEnhancementManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ArtworkEnhancementManager.swift)
- iOS 诊断模型：[NowPlayingDiagnosticSnapshot.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticSnapshot.swift)
- iOS Live Activity 缩略图：[LiveActivityArtworkStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LiveActivityArtworkStore.swift)
- Sony GATT 封面发送：[BleGattServerManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/BleGattServerManager.kt)
- Sony 封面传输协调器：[MediaTransferCoordinators.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/MediaTransferCoordinators.kt)
- Sony 封面探测：[AlbumArtTestManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/AlbumArtTestManager.kt)
- iOS smoke AlbumArt 验收：[ios_album_art_flow_test.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_album_art_flow_test.sh)

## 数据流

1. Sony 从当前歌曲构造 `albumArtId`，通过 status notify 发 `albumArtOffer`。
2. iOS `BLETestManager.parseStatus` 将 `albumArtOffer` 转交 `albumArtReceiver.handleOffer(id:)`。
3. `AlbumArtReceiver` 优先查 enhanced/HQ/preview cache。匹配当前 trackId 且可解码的旧缓存立即显示，后台 stale-while-revalidate。
4. 无可用缓存时请求 `ALBUM_ART_REQUEST quality=preview`。
5. preview 成功后调度 HQ 请求；HQ 成功后可触发本地 enhanced。
6. 二进制传输：
   - JSON：`albumArtBinaryStart` / `albumArtBinaryEnd`
   - 二进制 chunk：6 字节 header + payload
7. 传输结束先复制不可变 `Data` 快照到 utility 串行队列，再做 JPEG 校验、磁盘读写和 ImageIO 降采样；主图约 780px、历史缩略图约 128px。
8. 解码完成回主线程前再次校验 `transferId + artworkId`；切歌后的旧任务不能覆盖新封面。
9. iOS 在 utility 串行队列原子保存 `Documents/AlbumArtCache/`；增强图保存到 Enhanced 子目录。
10. 主 UI 使用最终 `albumArtImage`；Live Activity 使用 App Group 小图。加载层明确区分 preview、HQ 和失败，可只重试当前歌曲。
11. Sony PlayerAgent 本机 UI 在歌曲变化时先进入 LOADING；MediaMetadata/Notification 的 title/artist 与当前身份不匹配时拒绝。通知更新触发精确重读，不增加轮询；同一当前歌曲的临时精确源缺失不会把已经 READY 的图替换为占位图。

## 关键状态

- `ArtworkDisplayQuality`：`placeholder`、`preview`、`hqFallback`、`hq`、`enhanced`。
- `AlbumArtTransferSession`：当前 binary transfer 的 id、quality、totalChunks、receivedChunks、bytesReceived。
- 超时：
  - first chunk：3000ms
  - idle chunk：4000ms
  - total：10000ms
- `AlbumArtSnapshot`：诊断页读取的封面状态，包含 cache、transfer、HQ unavailable 和 enhanced 状态。
- 缓存最长可展示 30 天；通知来源 30 分钟后复验，稳定 metadata 来源 24 小时后复验。小于 300px 只触发后台刷新，不清空可用图。
- Sony preview 目标约 112px、Q40～50、最多 1.8KB/12 包；HQ Q70～80、最多 8KB且保持最低优先级。
- Sony 已编码 JPEG 使用 40 项、16MB、30 分钟内存 LRU，避免同歌曲重复压缩。
- iOS 解码缓存 key 包含 `artworkId + quality + targetPixelSize`，避免同一 JPEG 在主界面和历史列表重复全尺寸解码。
- A1 header 和 quality code 不变；start/end 可带 `transferId`、`generation`、`crc32`，新端支持局部重传，旧端直接忽略新字段。

## 不允许随便修改的点

- 不要改 AlbumArt binary header 和 quality code。
- 不要把 UIImage/Data/Base64 放进 Live Activity `ContentState`。
- 不要让 HQ 请求在 fullLyrics、lyricSecondary、remoteLog、mediaFieldDump 长任务期间抢占。
- 不要覆盖 HQ 原图；enhanced 必须是独立缓存。
- 不要把封面接收逻辑塞回 `BLETestManager`。
- 不要在 SwiftUI `body` 或主线程同步 `Data(contentsOf:)`、校验 JPEG 或创建全尺寸图片。

## 常见问题排查入口

- iOS：
  - `[AlbumArt] offer/request/unavailable`
  - `[AlbumArtBinary] start/chunk/end/decode success`
  - `[AlbumArt-iOS] transfer start/first chunk timeout/idle chunk timeout/total timeout/transfer cancelled`
  - `[AlbumArtCache] saved/display quality`
  - `[ArtworkDisplay] upgrade ...`
  - `[ArtworkImageCache]` / `[ArtworkDecode]`
  - `[ArtworkEnhance] ...`
- Sony：
  - `[AlbumArt-Sony]`
  - `[AlbumArt][BLE] failed`
  - `[BleNotifyQueue] job start type=albumArt`
  - `[PlayerUI] artwork source event=...`
  - `[AlbumArtSource] metadata rejected reason=identity_mismatch`
  - `[PlayerUI] album art retained reason=transient_exact_source_miss`
- 自动验收：
  `./tools/ios-smoke-tests/ios_album_art_flow_test.sh <ios_ble.log>`

## 修改后必须跑哪些 smoke test

- 改 iOS 封面接收/缓存/增强/诊断：quick smoke。
- 必须查看 `report.json` 的 `albumArtFlow`，Sony 不在线时允许 Optional WARN/SKIPPED；若真实在线应看到 PASS。
- 改 Xcode target 或新增 Swift 文件：full smoke。
- 改 Sony 发送压缩或 binary 协议：Android build + iPhone 真机封面链路。

V4 实时性观测按 `trackId + generation + transferId` 记录 offer、cache hit、Preview/HQ 编码、队列、传输、iOS 解码与 publish。Trace 不包含图片正文，不改变 A1、CRC、质量、超时或调度策略；指标定义见 [REALTIME_SLO_V4.md](/Volumes/雷电/project/MusicBleController/docs/REALTIME_SLO_V4.md)。
