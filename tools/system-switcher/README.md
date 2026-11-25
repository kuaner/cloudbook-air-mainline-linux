# CloudBook Air 电源与系统切换

Android 电源菜单适用于运行原厂 Android 11 B 槽的 CT2000-24RTW。双系统切换还需要 Linux A 槽和内置 `rootfs` 分区。切换前保存工作，两端都会在重启前要求确认。若 `boot_a` 使用 USB 优先的引导镜像，切回内置 Ubuntu 前请拔掉带 `ROOTFS` 卷标的试跑 U 盘。

- 在 Ubuntu 点 Noctalia 电源图标，或按 `Win+Shift+E` 打开电源菜单，选择「重启到安卓」。确认后用 Linux 管理员密码授权。
- 在 Android 安装 `cloudbook-system-switcher.apk`，从应用列表打开「电源」。点击「Linux」启动 A 槽。旧版 APK 中该入口显示为「Debian」。「重启」沿用当前启动设置。

Android 电源操作使用启用密钥验证的网络 ADB。应用用独立密钥连接本机 `127.0.0.1:5555`，通过原厂 `su 0` 请求电源操作。首次使用可先点「检查启动状态」，在系统弹窗中勾选「一律允许」并授权。以相同签名覆盖安装新版可保留应用密钥，恢复出厂设置后需重新配置 ADB 和授权。[原厂 Android ADB 配置](../../docs/ANDROID_SIDE_SETUP.md#adb-persistence)

勾选「一律允许」后，Android 会保存应用公钥，正常重启可以继续使用。手动预置 `/data/misc/adb/adb_keys` 时，每条公钥都要独占一行，最后一行也要以换行符结尾。缺少末尾换行时，Android 追加的应用公钥可能和上一条拼在一起，重启后就会重新请求授权。

系统切换只更新 `misc` 中偏移 `0x800` 的 32 字节 A/B 记录，并保留当前系统作为回退。从 Android 启动 Linux 时，工具将 A 槽的 `tries_remaining` 初始化为 `3`。Linux 成功启动后由 `cloudbook-mark-boot-successful.service` 提交成功标志。Linux 使用 logind 请求重启，并遵守关机抑制锁。

2026-11-23 已在真机验证 Debian A → 原厂 Android B → Debian A。切回 Debian 后确认 `/` 挂载自 `/dev/mmcblk0p21`，启动成功标记服务正常退出。再次启动安卓后，连续两次检查启动状态均通过，已保存的 ADB 密钥没有重复弹窗。

APK 1.1 已在同一台真机覆盖安装。关机、重启和 Debian 的确认与取消均已检查。执行「重启」后设备回到原厂 Android B，从应用重新检查 Debian 启动状态时仍使用原有 ADB 授权。

运行 `./scripts/build-system-switcher-apk.sh` 可本地重建 APK。需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。输出为 `artifacts/system-switcher/cloudbook-system-switcher.apk`，使用本机 Android 调试密钥签名。Ubuntu 入口随桌面镜像安装。
