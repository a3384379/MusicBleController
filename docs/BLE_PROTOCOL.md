# BLE 协议架构

本文记录当前 Sony PlayerAgent 与 iPhone 之间的 BLE 协议边界。协议字段没有版本协商，修改必须非常谨慎。

## 模块职责

- Sony `BleGattServerManager`：GATT Server、command 写入解析、status notify、长任务分片。
- Sony `BleNotifyQueue`：短消息和长任务队列，保证控制命令、播放状态、歌词、封面互不长期阻塞。
- iOS `BLETestManager`：GATT Client / Central，写 command，接收 status notify 和封面 binary chunk。
- Live Activity command bridge：iOS AppIntent 通过主 App 已有 BLE 连接发送控制命令。

## 核心文件

- iOS UUID：[BLEUUIDs.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLEUUIDs.swift)
- Sony UUID：[PlayerAgentUuids.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/PlayerAgentUuids.kt)
- Sony GATT：[BleGattServerManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/BleGattServerManager.kt)
- Sony notify 队列：[BleNotifyQueue.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/BleNotifyQueue.kt)
- iOS BLE：[BLETestManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift)
- Live Activity 控制：[LiveActivityControlModels.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LiveActivityControlModels.swift)、[LiveActivityCommandBridge.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LiveActivityCommandBridge.swift)

## 固定 UUID

- Service：`0000A001-0000-1000-8000-00805F9B34FB`
- Command characteristic：`0000A002-0000-1000-8000-00805F9B34FB`
- Status characteristic：`0000A003-0000-1000-8000-00805F9B34FB`
- CCCD：`00002902-0000-1000-8000-00805F9B34FB`
- Advertising name：`SonyPlayerAgent`
- iOS controller name：`MusicControllerIOS`

## 命令数据流

1. iOS 将 JSON 写到 command characteristic，字段通常包含 `cmd`、`time`、`seq`。
2. Sony `onCharacteristicWriteRequest` 解析 JSON。
3. Sony `handleCommand` 执行控制、状态查询或长任务。
4. Sony 通过 status characteristic notify JSON 状态，或用二进制 notify 发送 AlbumArt chunk。

## 当前主要命令

- 控制：`PLAY_PAUSE`、`NEXT`、`PREVIOUS`
- 音量：`VOLUME_UP`、`VOLUME_DOWN`、`SET_VOLUME`
- Seek：`SEEK_TO`
- 状态：`GET_PLAYBACK_STATE`、`GET_VOLUME`
- 封面：`ALBUM_ART_REQUEST`、`ALBUM_ART_SKIP`
- 歌词：`GET_FULL_LYRICS`、`GET_LYRIC_SECONDARY`、`GET_LYRIC_DIAGNOSTIC`
- 日志/诊断：`GET_LOGS`、`DUMP_MEDIA_FIELDS`
- 历史：`GET_PLAY_HISTORY_PAGE`、`GET_PLAY_HISTORY_SINCE`、`GET_PLAY_STATS`

## 主要 notify 类型

- `playbackState`
- `trackInfo` / `trackInfoStart` / `trackInfoChunk` / `trackInfoEnd`
- `volumeState`
- `albumArtOffer`
- `albumArtBinaryStart` / binary chunk / `albumArtBinaryEnd`
- `albumArtUnavailable`
- `fullLyricsStart` / `fullLyricsChunk` / `fullLyricsEnd` / `fullLyricsUnavailable`
- `lyricSecondaryStart` / `lyricSecondaryPart` / `lyricSecondaryEnd`
- `lyricDiagnostic`
- `historyPayloadStart` / `historyPayloadChunk` / `historyPayloadEnd`

## 关键状态

- Sony `BleNotifyQueue` 有 long job 概念，`albumArt`、`fullLyrics`、`lyricSecondary`、`playHistory` 都属于长任务。
- 长任务期间会 interleave 最新短状态，避免控制和播放状态长期饥饿。
- iOS 控制命令在连接不健康时会丢弃，不缓存，不重连后补发。
- AlbumArt binary 使用 Sony 端 `ALBUM_ART_BINARY_MAGIC` 和 6 字节 header；iOS 在 `didUpdateValueFor` 中把非 JSON 二进制 chunk 转交给 `AlbumArtReceiver`。

## 不允许随便修改的点

- 不要改 UUID。
- 不要改 command/status characteristic 用途。
- 不要改现有命令名称。
- 不要破坏 AlbumArt binary header。
- 不要把 FullLyrics 和 LyricSecondary 合并回单包大 JSON。
- 不要让历史/歌词/封面长任务阻塞播放控制。

## 常见问题排查入口

- Sony 收命令：`[CTRL-Sony] command parsed`、`before handle`、`after handle`。
- iOS 发命令：`[CTRL-iOS] send start`、`write skipped`、`send failed`。
- 队列阻塞：`[BleNotifyQueue] job start/end`、`latest ... flushed during long job`。
- AlbumArt 卡死：`[AlbumArt-iOS] transfer start/timeout/cancelled`、`[AlbumArt-Sony] chunk progress`。

## 修改后必须跑哪些 smoke test

- iOS 协议接收/命令分发改动：quick smoke。
- iOS `project.pbxproj` 或启动/日志改动：full smoke。
- Sony 协议改动：Android build `./gradlew :PlayerAgentApp:assembleDebug`，并真机验证 iOS 连接和控制；不要只跑 iOS smoke。
