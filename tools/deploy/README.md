# iOS Debug 自动重签/重新安装

免费 Apple ID 的 iOS Debug provisioning profile 仍然只有 7 天有效期。本工具不会改变这个限制，只是在本机 Mac 上每天检查一次，到快过期时自动重新 build、install、launch 并跑 quick smoke，从而刷新有效期。

## 前提

- iPhone 必须 USB 连接 Mac。
- iPhone 需要解锁，或至少已经允许 `devicectl` 启动调试 App。
- Xcode 必须已经登录 Apple ID，并允许自动签名更新。
- 第一次安装，或证书/账号变化时，仍可能需要手动到 iPhone 设置里信任开发者。
- 脚本不会改 Bundle ID、不会改签名 Team、不会修改业务代码。

## 手动运行

```bash
./tools/deploy/ios_reinstall_if_needed.sh --force
```

常用参数：

```bash
./tools/deploy/ios_reinstall_if_needed.sh --threshold-hours 24
./tools/deploy/ios_reinstall_if_needed.sh --device <IOS_DEVICE_ID>
./tools/deploy/ios_reinstall_if_needed.sh --force --device <IOS_DEVICE_ID>
```

`ios_reinstall_if_needed.sh` 会检查当前 Debug `.app/embedded.mobileprovision` 的过期时间。如果 App 未安装、profile 不可读，或剩余时间低于阈值，就调用 `ios_deploy.sh`。

`ios_deploy.sh` 执行：

```bash
xcodebuild -allowProvisioningUpdates build
xcrun devicectl device install app
xcrun devicectl device process launch
tools/ios-smoke-tests/codex_check.sh
```

默认只做覆盖安装，不会卸载 App，也不会清 App 数据。只有安装明确失败且你显式传入 `--force-reinstall` 时，才会卸载一次后重试安装；这个兜底动作会删除 App 数据，平时不要使用。

每次检查会写出：

```text
tools/deploy/last_deploy.json
```

字段包括 `lastRunTime`、`profileExpireTime`、`daysRemaining`、`deployExecuted` 和 `result`。日志默认在 `/tmp/music_ble_deploy/<timestamp>/`。

## 安装 launchd 定时任务

```bash
./tools/deploy/install_launchd_plist.sh
```

固定设备：

```bash
./tools/deploy/install_launchd_plist.sh --device <IOS_DEVICE_ID>
```

安装后，LaunchAgent 每天晚上 21:00 执行：

```bash
tools/deploy/ios_reinstall_if_needed.sh --threshold-hours 48
```

stdout/stderr 日志写到：

```text
/tmp/music_ble_deploy/ios-reinstall.out.log
/tmp/music_ble_deploy/ios-reinstall.err.log
```

如果当时 iPhone 没有连接或不可用，脚本只会在 `last_deploy.json` 记录 `SKIPPED` 并以成功状态退出，避免 launchd 反复报错刷屏。

卸载定时任务：

```bash
./tools/deploy/install_launchd_plist.sh --uninstall
```

## 验证

```bash
./tools/deploy/ios_reinstall_if_needed.sh --force
cat tools/deploy/last_deploy.json
./tools/deploy/install_launchd_plist.sh
```

确认点：

- `ios_deploy.sh` 安装成功。
- `tools/ios-smoke-tests/codex_check.sh` 输出 `PASS`。
- `tools/deploy/last_deploy.json` 中 `deployExecuted` 为 `true`，`result` 为 `PASS`。
- `launchctl print gui/$(id -u)/com.musicble.ios-reinstall` 能看到任务。
