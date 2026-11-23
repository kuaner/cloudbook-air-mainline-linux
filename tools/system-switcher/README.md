# CloudBook Air 系统切换

适用于 A 槽运行 Debian、B 槽运行原厂 Android 11、内置 `rootfs` 分区存放 Debian 的 CT2000-24RTW。切换前保存工作，两端都会在重启前要求确认。若 `boot_a` 使用 USB 优先的引导镜像，切回内置 Debian 前请拔掉带 `ROOTFS` 卷标的试跑 U 盘。

- **Debian**：点桌面右上角电源图标，或按 `Win+Shift+E` 打开电源菜单，选择带安卓机器人图标的「安卓」。确认后用 Linux 管理员密码授权。
- **Android**：安装 `cloudbook-system-switcher.apk`，打开「切换系统」。首次点击「检查启动状态」时，在系统提示中勾选「一律允许」并授权。检查通过后，点「重启到 Debian」。

Android 端日常切换无需连接电脑，但必须保留开启密钥验证的网络 ADB。应用使用独立密钥连接本机 `127.0.0.1:5555`，通过原厂 `su 0` 取得写入启动记录的权限。恢复出厂设置后需要重新配置 ADB 和授权。[原厂 Android ADB 配置](../../docs/ANDROID_SIDE_SETUP.md#adb-persistence)

勾选「一律允许」后，Android 会保存应用公钥，正常重启可以继续使用。手动预置 `/data/misc/adb/adb_keys` 时，每条公钥都要独占一行，最后一行也要以换行符结尾。缺少末尾换行时，Android 追加的应用公钥可能和上一条拼在一起，重启后就会重新请求授权。

切换只更新 `misc` 中偏移 `0x800` 的 32 字节 A/B 记录，并保留当前系统作为回退。从 Android 启动 Debian 时，工具将 A 槽的 `tries_remaining` 初始化为 `3`，Debian 成功启动后由 `cloudbook-mark-boot-successful.service` 自动提交成功标志。Debian 使用 logind 请求重启，并遵守关机抑制锁。

2026-11-23 已在真机验证 Debian A → 原厂 Android B → Debian A。切回 Debian 后确认 `/` 挂载自 `/dev/mmcblk0p21`，启动成功标记服务正常退出。再次启动安卓后，连续两次检查启动状态均通过，已保存的 ADB 密钥没有重复弹窗。

运行 `./scripts/build-system-switcher-apk.sh` 可本地重建 APK。需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。输出为 `artifacts/system-switcher/cloudbook-system-switcher.apk`，使用本机 Android 调试密钥签名。Debian 入口由 rootfs 后处理脚本安装。
