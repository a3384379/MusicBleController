# iOS Debug 自动重签/重新安装

免费 Apple ID 的 iOS Debug provisioning profile 仍然只有 7 天有效期。本工具不会改变这个限制，只是在本机 Mac 上提供一个可手动触发的刷新脚本，用来重新生成 profile、build + install，确保设备上装的是新签名的 Debug App。

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

`ios_reinstall_if_needed.sh` 会检查当前 Debug `.app/embedded.mobileprovision` 的过期时间。如果 App 未安装、profile 不可读，或剩余时间低于阈值，就调用 `ios_deploy.sh`。自动部署使用固定 DerivedData 路径，默认是 `~/Library/Developer/Xcode/DerivedData/MusicBleControllerAutoDeploy`，避免后台任务调用 `xcodebuild -showBuildSettings` 卡住。

`ios_deploy.sh` 执行：

```bash
xcodebuild -allowProvisioningUpdates -allowProvisioningDeviceRegistration build
xcrun devicectl device install app
xcrun devicectl device process launch
tools/ios-smoke-tests/codex_check.sh
```

默认只做覆盖安装，不会卸载 App，也不会清 App 数据。只有安装明确失败且你显式传入 `--force-reinstall` 时，才会卸载一次后重试安装；这个兜底动作会删除 App 数据，平时不要使用。

手动运行时，每次检查会写出：

```text
tools/deploy/last_deploy.json
```

桌面脚本或 launchd 自动运行时，状态写到：

```text
/tmp/music_ble_deploy/last_deploy.json
```

字段包括 `lastRunTime`、`profileExpireTime`、`daysRemaining`、`profileUUID`、`deployExecuted` 和 `result`。日志默认在 `/tmp/music_ble_deploy/<timestamp>/`。

刷新成功时还会写入最近一次成功刷新的状态：

```text
/tmp/music_ble_deploy/last_successful_deploy.json
```

## 桌面双击刷新

推荐使用桌面 `.command` 手动触发，避免 Xcode 账号失效时后台定时任务静默失败：

```bash
./tools/deploy/install_desktop_refresh_command.sh --device <IOS_DEVICE_ID>
```

当前设备可以用下面的命令查看：

```bash
xcrun devicectl list devices
```

安装后双击桌面的 `刷新 MusicBle iOS 签名.command`。它会先打开 Xcode 工程，让 Xcode 有机会加载账号状态或生成新 profile，然后执行：

```bash
tools/deploy/ios_reinstall_if_needed.sh --force --refresh-only --renew-profiles --require-renewed-profile --threshold-hours 48
```

如果 Xcode 账号失效，Terminal 会保留失败原因；重新在 Xcode 里登录 Apple ID 后，再双击一次即可。脚本默认 `--refresh-only`，所以只负责刷新 profile、build 和覆盖安装，不会额外 launch/smoke。

注意：App 里显示的签名到期时间来自 `embedded.mobileprovision`。覆盖安装本身不会把这个时间顺延；只有 Xcode/Apple 重新签发了新的 provisioning profile，7 天窗口才会更新。脚本会先备份并删除当前 App 匹配的本地 profile，让 `xcodebuild -allowProvisioningUpdates` 重新生成 profile。如果命令行 Xcode 暂时先报 `No Accounts` 但随后异步写出新的 profile，脚本会等待最多 90 秒，检测到主 App 和扩展的新 profile 后先重试 build，再安装。如果最终 build/install 完成但 profile UUID 和到期时间没有变化，本次会记录为 `FAIL`，不会写入 `last_successful_deploy.json`。

## launchd 定时任务

当前不推荐使用定时任务。已安装的定时任务可以这样卸载：

```bash
./tools/deploy/install_launchd_plist.sh --uninstall
```

如果以后仍想恢复后台定时任务，可以重新安装：

```bash
./tools/deploy/install_launchd_plist.sh
```

固定设备：

```bash
./tools/deploy/install_launchd_plist.sh --device <IOS_DEVICE_ID>
```

安装后，LaunchAgent 会在登录时以及之后每 5 分钟做一次轻量检查：

```bash
tools/deploy/ios_reinstall_if_needed.sh --force --refresh-only --once-per-day --renew-profiles --require-renewed-profile --threshold-hours 48
```

也就是说，自动任务不再只等固定 21:00；只要当天还没有成功刷新签名，且某一轮检查时 iPhone 连接可用，就直接尝试刷新 profile 并重新 build + install。当天已经成功刷新过时，后续检查只记录 `SKIPPED`。自动任务默认 `--refresh-only`，所以不会因为 iPhone 暂未信任开发者导致 launch/smoke 失败而把刷新记成失败。iPhone 不在线时仍然只记录 `SKIPPED`。

安装脚本会在 `~/Library/Application Support/MusicBleController/deploy-runner/` 生成 checker、runner 和 `.command` 入口。LaunchAgent 每 5 分钟只跑轻量 checker；只有 checker 发现“今天还没成功刷新且 iPhone 已连接”时，才用 `/usr/bin/open` 让 Terminal 执行 `.command`。这样避免 macOS 后台任务直接执行外接盘 `/Volumes/...` 下脚本时触发 `Operation not permitted`，也避免 detached `launchd` 里 `xcodebuild` 卡住。runner 仍然会把 `ROOT_DIR` 指向当前仓库，并构建这个仓库里的 Xcode 工程。

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
./tools/deploy/install_desktop_refresh_command.sh --device <IOS_DEVICE_ID>
```

确认点：

- `ios_deploy.sh` 安装成功。
- `tools/deploy/last_deploy.json` 中 `deployExecuted` 为 `true`，`result` 为 `PASS`。
- 桌面出现 `刷新 MusicBle iOS 签名.command`。
