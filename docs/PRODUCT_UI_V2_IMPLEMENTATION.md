# Product UI V2 实现说明

本文记录 V4.0 第一阶段的 iOS 控制端与 Sony PlayerAgent 产品化 UI。UI 只消费现有播放、歌词、封面、历史和 BLE Health 状态；BLE UUID、命令、payload、分片、栅栏、优先级与恢复阈值均未修改。

## 设计 Token

两端统一采用 Canvas `#06090E`、Surface 1 `#0F151F`、Surface 2 `#182230`、主文字 `#F5F7FB`、辅助文字 `#A7B0BD`、弱文字 `#697484`、健康 `#64D98C`、警告 `#F2B56D`、断开 `#EC7774`。音乐高亮使用稳定的 `#78DBC3`，不参与连接健康表达。小组件、普通卡片和主卡片圆角分别为 12、18 和 26–28 pt/dp；交互目标不小于 iOS 44 pt、Android 48 dp。

## iOS 自适应布局

`PlayerLayoutMode` 和 `PlayerLayoutMetrics` 位于 `PlayerProductUI.swift`，共同考虑可用宽高、Safe Area、横竖屏、Dynamic Type 和封面偏好：

- Accessibility Dynamic Type 使用 accessibility 紧凑布局。
- 横屏、有效高度小于 735 pt 或宽度小于 360 pt 使用 compact。
- 其他情况使用 regular。
- 375×667 会进入 compact；393×852 和 430×932 会进入 regular。

regular 使用居中的封面、曲目信息、三行歌词、进度、播放控制和音量。compact 使用 126–144 pt 左侧封面与右侧曲目信息，歌词和控制保持在固定布局中。主播放器不使用整页 `ScrollView`。

封面偏好的语义尺寸为小 184 pt、中 224 pt、大 272 pt。旧值 200/220/260 会在读取时映射到新枚举并沿用原 UserDefaults key；实际渲染尺寸仍受当前屏幕可用空间约束。

## iOS 播放状态矩阵

| 业务状态 | 主界面表达 | 状态胶囊交互 | 控制 |
| --- | --- | --- | --- |
| 已连接 | 实时曲目与歌词 | 打开设备详情 | 启用 |
| 首次扫描/连接 | 连接引导或加载状态 | 不重复发起连接 | 禁用 |
| 自动重连且有快照 | 保留快照并显示重连 Banner | 打开设备详情 | 禁用 |
| 已断开且有快照 | 标注“上次播放内容不是实时状态” | 执行现有扫描/连接 | 禁用 |
| 已断开且无快照 | 首次连接引导 | 执行现有扫描/连接 | 禁用 |
| 蓝牙/权限异常 | “需要处理”及文字说明 | 进入对应现有处理路径 | 禁用 |

设备详情只显示真实可得的设备名、MTU、歌词时钟 RTT、最近 Notify、Health 和协商后的 V3 信息；缺失字段不渲染。“重新同步”调用已有重连同步入口，“重新连接”调用已有强重连入口，“忘记设备”调用已有本地设备记忆清理并要求确认。

完整歌词在用户主动滚动后切换为 browsing，不再使用 4 秒自动回跳；“回到当前歌词”或歌曲身份变化才恢复 following。歌词使用稳定 `LyricLine.id`，不再逐行持续测量 frame，也不比较整个歌词数组。

历史页采用暗色卡片、日期分组、搜索和今日/7 天/30 天/全部筛选；Formatter 为静态缓存，缩略图继续通过现有降采样缓存读取，分页与同步保持单飞。设置页按当前设备、播放器显示、歌词、封面、连接与系统、播放历史、高级与诊断、关于分组，协议与日志只在调试模式显示。

Live Activity 不改变 ActivityKit 模型、控制桥或 App Group 封面读取，只把播放音乐色、重连警告色和断开色与主应用 Token 对齐。所有 Dynamic Island 样式均保留左侧歌曲封面；歌词优先和节奏优先只改变中部信息层级，不再用引号或节奏图标替代封面。

## Sony 三层信息架构

`PlayerAgentNavigationView` 使用原生 Android View 构建稳定底部导航，切页不启动或停止服务，也不重建 GATT：

- 主页：产品化主状态卡、Service/BLE/iPhone 三项摘要、当前歌曲/歌词/封面、设置完成度和“一键检查并修复”。
- 设置：通知读取、QRC 文件访问、歌词辅助功能、按系统版本需要的蓝牙/通知权限，以及后台前台服务说明。不显示无后端的开机启动开关。
- 诊断：链路、歌词、封面、日志四个标签。原扫描、经典蓝牙、QRC 监听/构建/修复/预热、封面发现、历史、日志导出等入口均迁移到相应标签；清理和重建仍保留原确认流程。

`PlayerAgentUiStateMapper` 是纯函数边界。它把 `BleHealthState`、权限是否完成和现有媒体状态映射为展示键、设置步骤和安全恢复动作，不直接访问 GATT 或保存第二套业务状态。“一键修复”动作集合只包括权限引导、启动前台服务和现有 BLE 恢复，不包含清缓存、重建索引、清历史或修改协议。

前台通知使用同一 Health 映射生成“可控制 / 等待 iPhone / 正在恢复连接 / 需要处理”等语义；仅在映射结果变化时调用 `notify`，不会重启服务或 BLE。通知提供“检查连接”和“打开应用”，不包含设备标识和诊断详情。

## 无障碍与性能

- 图标按钮提供可读标签，播放/暂停标签随状态变化。
- 节奏条从无障碍树隐藏；状态同时使用文字与颜色。
- 主播放器与 Sony 导航按钮满足最小触控尺寸，长曲目允许多行且不覆盖控制。
- Reduce Motion 下歌词滚动和高亮使用静态/最小动画。
- UI `body` 不发送 BLE 命令、不增加 Timer、不同步读取磁盘或解码全尺寸图片。

## 与静态设计稿的降级差异

- Sony 广播状态没有可靠的播放/暂停字段，因此主页只展示真实曲目信息，不伪造播放按钮状态。
- Sony 当前没有统一可公开的 MTU/RTT/TrackId/generation 广播给 Activity；这些技术字段仍留在现有真实诊断输出，主页不显示示例值。
- 没有新增封面取色链路，音乐元素使用稳定回退色。
- 没有 `BOOT_COMPLETED` 接收器和持久化策略，因此设置页不显示开机启动。

## 视觉复核

- iOS：启动可用的 iPhone Simulator，执行无签名 `xcodebuild test` 后安装 `Debug-iphonesimulator/sonyMusic.app`。模拟器没有 BLE，因此只能复核真实的“不支持蓝牙”空态；连接、重连、快照、歌词浏览、历史、设置与设备详情由 `testPrimaryIOSSurfacesRenderRepresentativeStates` 的代表状态渲染测试覆盖。375×667、393×852 与 430×932 的布局策略由 `testFocusedStoresAndResponsiveLayoutModes` 覆盖。
- Sony：连接 Android 6 或更高版本的 Sony 设备/模拟器，安装 `PlayerAgentApp/build/outputs/apk/debug/PlayerAgentApp-debug.apk`，依次打开底部“主页 / 设置 / 诊断”，并在诊断页切换“链路 / 歌词 / 封面 / 日志”。当前自动化环境没有 Android 设备，因此不生成虚假的运行时状态截图。

## 下一阶段

- 安全配对和受信任控制端。
- `BOOT_COMPLETED` 自动启动的独立产品与后台策略。
- 端到端延迟 SLO 与 Trace。
- `BLETestManager`、`BleGattServerManager`、`LyricManager` 等核心大类的独立拆分。
