# 手机声音 → Sony：独立可行性原型

本实验落实[共享方案最后两条回复](https://chatgpt.com/share/6ac50243-4308-83e8-9147-6be0b1402fa5)中的短期筛选：先验证真实短视频、声音路由、同步和切换，再决定是否开发正式功能。当前交付是可构建的独立 Android 接收器，**真机验收未执行，不能宣称 iPhone → Sony 已可用**。

仅新增本目录；主 App、iOS 设置、BLE、歌词、QQ 控制及根 Gradle 模块列表均未接入。实验包名 `com.musicblecontroller.phoneaudiolab`，可与 PlayerAgent 并存。分支为 `codex/phone-audio-prototype`。不要在真机筛选完成前合并为正式功能。

## 设备范围与路线

| 项目 | 本轮依据与边界 |
| --- | --- |
| Sony | 仓库参考设备 NW-WM1AM2，Android 11；原型仅打包 arm64，最低 Android 11。实际系统版本仍需现场记录。 |
| iPhone | 开发设备登记为 iPhone 16 Pro Max；当前不可连接，已安装 iOS 版本尚未确认。 |
| 声音输出 | 沿用 Sony 当前输出和硬件音量，不增加耳机选择、路由或型号限制。 |
| 网络 | 两台设备处于可互相通信、允许 mDNS 的同一局域网。接收器要求 Sony 的活动网络是 Wi-Fi 或有线网络。 |
| 音源 | 只筛选用户实际使用的 1～2 个视频 App；不承诺所有 App 或 DRM 内容支持。 |

采用 iPhone 系统 AirPlay 音频输出选择 + Sony 独立接收器。音频协议、解码及 PCM 输出实际由固定版本的 [Android AirPlay Server](https://github.com/jqssun/android-airplay-server/tree/v0.0.31) 原生引擎执行；没有用 BLE 承载音频，没有使用手机录音转发，没有实现全局静音。

方案的原生 MediaDevice 路线需要系统框架、扩展、驱动注册和对应权限，参见 [Apple 扩展文档](https://developer.apple.com/documentation/MediaDevice/creating-a-media-device-extension)。本机 Xcode 26.6 / SDK 26.5 尚不能构建该路线，未添加未经验证的 iOS 扩展或占位实现。

## 使用原型

1. 安装构建产物 `artifacts/PhoneAudioLab-debug.apk`，打开“手机声音实验”。初次打开、重启进程和设备重启都保持关闭。
2. 将 Sony 和 iPhone 连到同一局域网，先手动暂停 Sony 音乐，再勾选确认并点“开始接收”。Android 13 及以上会申请前台通知权限。
3. 在 iPhone 控制中心的**音频输出列表**选择 `Sony Phone Audio Lab`，按 Sony 显示的 PIN 配对。不要选择“屏幕镜像”。
4. 打开目标视频 App，观察画面是否留在手机、声音是否只有 Sony 输出，以及实际音画偏差、滑动切换后的旧音残留。调节 Sony 自身音量；本原型忽略手机发来的接收器音量调整。
5. 结束前先暂停手机视频，再点“结束接收”并确认。断网或结束输出时 iOS 可能恢复手机扬声器，必须现场观察，不能依赖本实验自动静音。

一次显式开启最多 15 分钟，超时、失去网络、音频焦点丢失或错误都会结束当前会话；重新开始需要再次点击。音源暂停/断开时保持接收模式或回到等待，不自动恢复 Sony 音乐。没有 QQ 暂停/恢复自动化，没有接收器 MediaSession，没有开机启动或后台自动接管。

界面“正在处理音频”只表示原生解码计时发生了变化；“接收端积压”和“缓冲目标”也都不是端到端音画延迟，不作为通过依据。

## 构建与产物

要求 JDK 17、Android SDK 35、Python 3.9+。在仓库根目录运行：

```bash
export JAVA_HOME="你的 JDK 17 路径"
export ANDROID_HOME="你的 Android SDK 路径"
./experiments/phone-audio/build.sh
python3 -m unittest discover -s experiments/phone-audio/tests -v
```

脚本下载并检查 `upstream.lock.json` 固定的上游 APK，只提取四个已锁定摘要的 arm64 原生库；执行 Kotlin 单元测试、APK 构建和 Android lint，输出 APK 与 SHA-256。也可用 `build.sh --apk /path/to/verified/upstream.apk` 使用本地归档。首次 Gradle 构建需要可访问依赖仓库。

当前 APK 是调试签名的实验包，原生引擎未在本机重新编译。分发时同时提供源码包及 [NOTICE.md](NOTICE.md)，原生依赖不提交进主仓库，许可范围见 [LICENSE](LICENSE)。

源码归档生成步骤：

```bash
git clone https://github.com/jqssun/android-airplay-server /tmp/phone-audio-upstream
git -C /tmp/phone-audio-upstream checkout c8defdd70d7e6a04f4f1b71d353653682d594106
git -C /tmp/phone-audio-upstream submodule update --init --recursive
curl -fL https://codeload.github.com/openssl/openssl/zip/565bdcc41bbf89fcbaf962636469332689f0c9fd -o /tmp/phone-audio-openssl.zip
curl -fL https://codeload.github.com/google/oboe/zip/b15f5e39c01a7ada306d959e5129620b145fb8b4 -o /tmp/phone-audio-oboe.zip
python3 experiments/phone-audio/package_sources.py \
  --upstream /tmp/phone-audio-upstream \
  --openssl-archive /tmp/phone-audio-openssl.zip \
  --oboe-archive /tmp/phone-audio-oboe.zip
```

归档前会验证上游及全部子模块提交、源码未被修改、依赖 ZIP 摘要。源码包含完整上游、依赖、实验代码和应用构建 wrapper，不含 APK、签名私钥或运行时配对文件。

## 真机检查与停止条件

以下预检默认只读取设备；只有显式加参数才会安装/打开独立实验，打开后仍不会自动开始接收。模拟器不能通过 Sony 真机门槛。

```bash
python3 experiments/phone-audio/device_check.py
python3 experiments/phone-audio/device_check.py --serial SONY_SERIAL --install --launch
```

筛选最多投入 1～2 个工作日。固定实际 iPhone/Sony 和 1～2 个 App，记录系统版本、操作者、最终 APK SHA-256、观察文件及测量值。重点检查：

| 检查 | 判定 |
| --- | --- |
| 正常播放 | 手机画面保留，手机扬声器无同步外放，Sony 实际出声。 |
| 音画同步 | 每个 App 至少 30 个实测偏差，绝对值 p95 ≤ 200 ms。使用可辨认的画面/声音事件逐帧对齐，记录测量方式。 |
| 滑动/切换 | 每个 App 至少 10 次，记录上一条声音从画面切换起残留多久；本原型保守筛选门槛为 p95 ≤ 200 ms。 |
| 暂停、恢复、断网、结束、来电/其他音源抢占 | 观察手机最终输出，确认回退可接受；不能把“断开”视为“手机已静音”。 |
| Sony | 手动暂停原音乐、锁屏仍稳定、没有自动恢复原音乐；现有 BLE 控制/歌词仍可正常使用。 |
| 默认关闭/清理 | 冷启动关闭，结束后停止发现、原生线程、唤醒锁和轮询；迟到的配对/发现/音频回调不能重新开启。 |

**音画与操作响应必须分别测量。** 同步偏差来自真实画面/声音观察，不能填入 ping、内部缓冲值或解码时间。200 ms 切换门槛和样本数是本原型的保守筛选规则；若需改变，应修改实验规则并记录理由，不能为已有结果倒改门槛。遇到手机正常外放、明显秒级滞后、目标 App 不支持，或达到两天仍缺关键证据，就停止本路线，不继续扩展正式 UI/BLE。

复制 `evidence.template.json` 到 `artifacts/evidence.json`。`null` 表示未测；每个 `source_apps` 项填写：

```json
{
  "name": "实际 App 名称与版本",
  "av_sync_ms_samples": [],
  "switch_residual_ms_samples": []
}
```

填完后执行：

```bash
python3 experiments/phone-audio/evaluate.py experiments/phone-audio/artifacts/evidence.json \
  --output experiments/phone-audio/artifacts/acceptance.json
```

缺证据输出 `NEEDS_DEVICE_EVIDENCE`，失败或超过投入上限输出 `STOP`；完整实测只能输出 `READY_FOR_HUMAN_REVIEW`，不会授权主干集成。模板与测试中的合成数据均不是设备验收结果。

## 本轮验证状态

- 独立应用：Gradle 单元测试、构建、lint 已通过；14 项 Kotlin 会话测试、11 项 Python 验收判定测试通过。
- lint：0 错误，1 条 ChromeOS/x86_64 缺失提示；本实验明确仅支持参考 Sony 的 arm64。
- 模拟器：已执行 arm64/API 37/16 KiB 页环境的安装、冷启动、通知授权、实际 JNI 初始化/局域网服务注册、手动停止；不等价于 Sony Android 11 或真实手机音源。
- 原生库：完整上游 APK 与四个提取库的摘要已校验，沿用上游发布版本；未重新编译原生引擎。
- 真机：本轮未连接到可用 Sony，登记的 iPhone 当前不可用。真实 AirPlay 选择、PIN、视频 App 音频、手机外放、延迟、切换残音、QQ、锁屏和 BLE 并行均未验收。
- 文件范围：本目录的应用、构建/源码准备、设备预检、证据模板/判定测试及文档。没有修改现有跨端协议；iOS quick/full smoke 不适用且未运行。原有 Android smoke 面向 PlayerAgent，本实验未用其结果替代独立应用验证。
- 图谱：主仓库完整索引及新模块符号/调用链已检查；当前工具集未提供 `list_projects`、`index_status` 和 `detect_changes`，使用完整重建索引、真实源码及 Git 差异补充核查。上游 C/C++ 索引 worker 失败，已直接复核关键 JNI/解码/清理源文件。

本轮 APK 身份及测试记录见 [VALIDATION.md](VALIDATION.md)；详细 JSON 和截图保存在本轮工作区 `artifacts/`，不入 Git。它们只记录构建与模拟器检查，不关闭真机验收。
