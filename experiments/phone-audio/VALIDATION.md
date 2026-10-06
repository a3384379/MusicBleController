# 本轮验证记录

时间：2026-10-06。原型版本：`0.1-experiment`；应用标识：`com.musicblecontroller.phoneaudiolab`。

APK SHA-256：`e946120a7879e515756d66a5c94edb6a40963de3dbcbffc5c3e5344408126b03`。

| 验证 | 结果与范围 |
| --- | --- |
| 独立 Gradle build | `testDebugUnitTest`、`assembleDebug`、`lintDebug` 成功；JDK 17、SDK 35、AGP 8.5.2。 |
| Kotlin 会话测试 | 14/14；默认关闭、重复开始、开始中停止、迟到回调、新旧会话隔离、失败/中断清理与显式重试。 |
| Python 验收判定测试 | 11/11；缺数据不能通过、手机外放/超时/超门槛停止、非法与不足采样拒绝、证据路径限制。测试数据为合成数据。 |
| lint | 0 错误，1 条 ChromeOS 缺少 x86_64 架构提示；此原型只有 arm64。 |
| APK 身份 | 调试签名验证通过；包名、最低 API 30、目标 API 35、权限和 arm64 打包已检查。APK 中四个原生库均与锁定摘要一致。 |
| 模拟器 | 创建并关闭本轮专用的只读 arm64/API 37/16 KiB 页模拟器。8 项检查通过：冷启动关闭、两轮实际 JNI/NSD 启动、两轮手动停止/唤醒锁释放、活动会话进程终止、重新打开关闭、当前进程无 fatal exception。 |
| 源码归档 | 校验上游及递归子模块提交、依赖 ZIP 摘要；完整源码包 CRC 和必要源文件存在检查通过。包内不含运行时配对文件或本机签名私钥。 |
| Sony 预检 | **FAIL：没有连接到可用 Android 设备。** 没有在 Sony 安装或启动。 |
| iPhone | 设备登记为 iPhone 16 Pro Max，状态 unavailable；实际 iOS 版本未确认。 |
| 实际功能验收 | **NOT_RUN。** 未验证 AirPlay/PIN/真实 App/实际声音/手机外放/同步/切换/锁屏/QQ/BLE 并行。空模板正确输出 `NEEDS_DEVICE_EVIDENCE`，所有检查 `NOT_RUN`，集成授权为 false。 |
| iOS quick/full smoke | 无 iOS 文件改动，不需要，未执行。 |
| 原 PlayerAgent Android smoke | 实验是独立包；未执行 PlayerAgent 的安装/运行 smoke，不能用既有主项目 CI 为本原型背书。 |
| 原生重编译 | 未执行；采用校验后的上游发布库。 |

原生库及来源身份以 `upstream.lock.json` 为准。APK、源码 ZIP、JSON 运行记录和界面截图只保存在本轮工作区的 `artifacts/`，不入 Git。源码包将包含本记录；其摘要在生成后的 `PhoneAudioLab-sources.zip.sha256`，避免将摘要嵌入自身归档。

这是构建与模拟器生命周期验证记录，不能关闭方案中的固定设备真实短视频验收。
