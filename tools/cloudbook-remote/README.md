# CloudBook 远程 Mac

CloudBook Air 的 Android 客户端，通过 Apple 高性能屏幕共享连接 Mac。视频协商为单路 H.264 4:2:0，在 CloudBook 上优先用 `OMX.rk.video_decoder.avc` 硬解。有限范围视频直接输出到 Android `SurfaceView`。全范围视频默认通过 `SurfaceTexture` 把硬解输出交给 GPU，由 GPU 直接采样 YUV 并转成 RGB，保留暗部和高光细节。GPU 不支持直接采样时，使用纹理上传路径。Rust 负责 Apple SRP 登录、RFB 控制和 SRTP 音视频收发。输入发送和视频接收由独立线程处理。

视频序号出现缺口时，客户端在 25 毫秒的乱序窗口内重试请求重传。MediaCodec 有可用输入缓冲区时，Rust 收到的压缩帧直接复制到该缓冲区，省去 Java 堆上的中转数组。音频通过 MediaCodec 的输入和输出回调送入 AudioTrack，解码器空闲时音频线程休眠。

连续视频按显示节拍送帧。当片源时间戳和解码输出都出现空档时，客户端尽快提交恢复后的首帧，减少打字或点击触发单次画面更新时的等待。

## 连接

1. 在 Mac 的「系统设置 → 通用 → 共享」启用屏幕共享，并允许要登录的 macOS 账户访问。
2. 在 CloudBook 打开「远程 Mac」，选择局域网中发现的 Mac，或输入 Mac 的 IPv4 地址。默认端口为 TCP 5900，音频和视频分别使用 UDP 5900 和 5901。
3. 输入 macOS 账户和密码。默认请求 1920 × 1200。启用「虚拟屏幕」时，Mac 会为远程会话切换到虚拟显示器，本地屏幕可能熄灭。需要在 Mac 本地同时看到操作时，关闭该选项。

「硬解直出」默认关闭。勾选后，全范围视频的解码缓冲区直接交给 Android 合成器，省去应用内的 GPU 转换。CloudBook 原厂合成器可能裁切暗部和高光。连接时应用会保存此设置。有限范围视频始终直出。

在原厂 Android 11 上，`MediaCodec` 和 SurfaceFlinger 都识别了 BT.709 全范围标记，内屏的 NV12 扫描输出平面仍显示为有限范围。显式设置 `MediaFormat.KEY_COLOR_RANGE` 后，测试灰阶 16 和 235 仍分别映射到 0 和 255。GPU 直采路径保留这两个灰阶。

会话统计中的「解码 fps」按 MediaCodec 输出回调计数。实际呈现帧率可通过 SurfaceFlinger 的图层帧时间戳测量。直出使用视频时间戳按屏幕节拍提交。多个缓冲区落入同一次刷新时，系统可能只显示最后一帧，即使解码器已经输出了每一帧。[Android MediaCodec 文档](https://developer.android.com/reference/android/media/MediaCodec#releaseOutputBuffer(int,long))

Android 14 候选镜像的 Rockchip 硬解组件名为 `c2.rk.avc.decoder`，客户端同样为它提供全范围 GPU 路径。该镜像的内屏硬解和色彩仍待实机验证。[Android 14 移植状态](../../docs/ANDROID14_PORT_STATUS.md)

会话菜单提供断开连接、剪贴板、屏幕键盘和统计。内置键盘的 `Win` 对应 Mac 的 `Command`，`Alt` 对应 `Option`。触摸板支持移动、点击、右键、拖拽和双指滚动。会话菜单可以捕获指针，按 `Ctrl+Alt+F12` 释放指针，再按一次打开菜单。Mac 通告精细滚动支持时使用连续滚动，否则使用滚轮事件。客户端会记住地址和账户，密码仅用于本次连接。

## 本地构建

使用 JDK 17、Android SDK 35、NDK `28.2.13676358` 和 Rust 1.100 或更新版本。在仓库根目录运行以下命令。APK 输出到 `tools/cloudbook-remote/app/build/outputs/apk/debug/app-debug.apk`。

```sh
rustup target add aarch64-linux-android
tools/cloudbook-remote/gradlew -p tools/cloudbook-remote :app:assembleDebug
cargo test --locked --manifest-path tools/cloudbook-remote/native/Cargo.toml
```

客户端使用 AGPL-3.0-or-later。Rust 传输参考了 iShareScreen 提交 `9ab40d3a3151524f954cff9d6239d891197c1705` 的协议实现。许可证随 APK 打包。 [iShareScreen](https://github.com/renegadelink/iShareScreen/tree/9ab40d3a3151524f954cff9d6239d891197c1705)
