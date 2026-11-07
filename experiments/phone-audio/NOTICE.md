# 手机声音实验：源码与第三方组件

本目录是独立实验，新增代码以 [GPL-3.0-only](LICENSE) 提供。原有 MusicBleController 模块的许可不因此改变。

原生接收器来自 [jqssun/android-airplay-server v0.0.31](https://github.com/jqssun/android-airplay-server/tree/v0.0.31)，锁定提交 `c8defdd70d7e6a04f4f1b71d353653682d594106`。本实验复制其 JNI Kotlin 声明，并从校验后的上游发布 APK 提取四个 arm64 原生库；未修改这些原生库。上游作者的版权及许可声明保留在源码包中。

| 组件 | 来源与许可文件 |
| --- | --- |
| Android AirPlay Server / JNI / 原生音频引擎 | 源码包 `upstream/LICENSE` 与各源文件 |
| UxPlay / 内嵌 RAOP、PlayFair、llhttp | `upstream/app/src/main/cpp/third_party/UxPlay` 中的许可及版权声明 |
| FFmpeg ALAC 解码器 | `upstream/app/src/main/cpp/third_party/ffmpeg` 中的 `COPYING.*` 与构建配置 |
| libplist | `upstream/app/src/main/cpp/third_party/libplist` 中的许可文件 |
| openssl-cmake / OpenSSL 3.4.4 | 上游子模块许可；`dependency-sources/openssl.zip` 中的 `LICENSE.txt` |
| Oboe 1.9.3 | `dependency-sources/oboe.zip` 中的 `LICENSE` 与版权声明 |
| Android NDK libc++ 运行时 | 上游构建所用 Android NDK `27.0.12077973`；NDK 的许可和第三方声明 |

`upstream.lock.json` 记录 APK、四个原生库、所有递归子模块和额外源码依赖的版本及摘要。`prepare_native.py` 先验证完整 APK，再验证每个库，并检查 ELF 架构，最后写入构建目录。

提供实验 APK 时，应同时提供 `PhoneAudioLab-sources.zip`、本声明及许可文件。源码包包含实验代码、Gradle wrapper、固定版本的上游完整源码及子模块、OpenSSL 与 Oboe 的源码归档；不包含本机签名私钥、接收音频或设备配对密钥。

本轮重新构建的是实验 Android 应用，原生库直接采用上游发布二进制。尚未在本机重新编译原生引擎，也未验证上游二进制能否逐字节复现。要自行构建原生库，请使用源码包 `upstream/` 的 Gradle、CMake、补丁与依赖配置，并安装其要求的 Android SDK、NDK、CMake 和 JDK。OpenSSL/Oboe 的源码及精确版本已包含；上游默认构建仍会通过其既有流程取得依赖。重新编译后必须重新检查 JNI ABI、打包结果及真机行为，不能沿用本轮二进制摘要。
