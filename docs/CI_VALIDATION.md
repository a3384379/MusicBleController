# CI 验证与证据

`.github/workflows/build.yml` 对两个 Android 模块分别执行 `testDebugUnitTest`、`lintDebug` 和 `assembleDebug`。两个模块及下列设备组分别运行，某一组失败不会取消其余组。

Android API 35 模拟器执行 `tools/ci/android_device.py`：

- `controller-history`：历史仓库、协议和日志设备测试。
- `controller-ui`：导航、历史与完整歌词 Compose 交互。
- `sony-room`：真实 Room 统计和事务。
- `sony-denied`：蓝牙权限拒绝的两个前台服务测试。
- `sony-granted`：授予蓝牙权限、拒绝通知权限的恢复测试。

`python3 tools/ci/android_device.py collect` 从两个模块的 instrumentation 源码收集测试，并检查每项恰好属于一个组；未登记的新测试、重复或不存在的选择器会使 CI 失败。每组运行前安装 debug APK，权限组通过 adb 在目标进程外修改权限。所有组都检查 XML，要求每项预期测试确实出现一次，任何 skipped、失败、额外或缺失测试都使组失败。权限回归读取模拟器当前的系统前台提升超时并等待其完整期限，CI 不修改该系统值。设备能力或授权状态不满足测试时，失败应据实处理，不能将 assumption 跳过当成通过。

本地运行 `python3 tools/ci/android_device.py <group> --serial emulator-5580 --artifacts <directory>` 时，`--serial` 显式选择目标，也可用 `ANDROID_SERIAL` 指定；两者同时存在时命令行优先。仅在 adb 设备列表恰好有一台在线设备时可以省略；多台、缺失、离线或未授权均在安装前失败。脚本为所有设备操作指定 adb `-s`，并给每个 Gradle 子进程设置同一 `ANDROID_SERIAL`。项目使用的 AGP 8.5.2 中，安装任务与设备测试任务均读取该变量选择设备。测试会安装 debug 包，权限组只接受隔离模拟器并会修改 PlayerAgent 的运行时权限。`collect` 仅读取源码，不访问设备。各组保留独立命令日志、XML、HTML、logcat、屏幕截图以及 debug 应用 cache 的 tar；cache 中包含测试保存的截图和 Room 查询证据。截图/cache 获取属于尽力收集，空文件并不证明截图成功。

macOS 分别执行 `sonyMusic` XCTest 和 `LyricsReadingUITests` 真实 UI 交互。`tools/ci/ios_simulator.py` 从可用 iOS runtime 的 iPhone 列表选择并打印设备和 runtime；最多重试一次 **测试开始之前** 的模拟器启动，分别保存启动日志。测试失败仅在 xcresult 的 summary、测试树和日志共同证明唯一错误是运行器建立连接前挂起、没有业务用例执行时重启模拟器并重试一次；其他失败、缺少结果或判定不明均直接失败。首轮和重试的日志、xcresult、截图与 `infra-retry.json` 均保留，重试前提取的 summary 和测试树也会保存。每个 scheme 有独立 job 和独立产物。示例：

```sh
python3 tools/ci/ios_simulator.py sonyMusic --artifacts /tmp/music-ci
python3 tools/ci/ios_simulator.py LyricsReadingUITests --artifacts /tmp/music-ci
```

Python CI 还运行 `tools/ci/tests`、`tools/deploy/tests`、`tools/android-smoke-tests/tests`、`tools/smoke/tests` 的 unittest。CI 脚本行为测试用假进程验证启动重试上限、业务失败不重跑和首轮证据保留；它们不代替模拟器测试。所有产物上传使用 `always()`，但若 runner 在产物生成前终止，可能没有可上传的文件。

模拟器 CI 不代替仓库规定的本地 smoke 或 iPhone + Sony 真机业务验收。需要独立记录设备、实际操作、报告和未覆盖场景。
