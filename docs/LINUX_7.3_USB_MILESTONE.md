# CloudBook Air 7.3 USB 桌面阶段总结

截至 2026-11-22，`CT2000-24RTW`（W620D V12.0，RK3568B2）的 Linux USB 桌面阶段可以收尾。`7.3.5-cloudbook #9` 是有真机观测和用户手感反馈的基线。`#10 / v30` 和新版 Debian 13 i3 根镜像是本阶段交付的候选组合，最新一次记录止于写入内核与读回校验。

## 2026-11-23 后续验证

当时的 Debian 构建生成了 `kuaner` 桌面、USB/eMMC 根镜像和通用 `boot_a` 镜像。图形切换已在真机往返验证，Debian 从 eMMC `p21` 挂载根盘。[Debian 桌面历史记录](USB_TEST_KIT_7.3.md) · [现行 Ubuntu 部署](FLASHING_GUIDE.md)

v30 已纳入充电状态及休眠插拔处理。合盖后持续插电直到充满时，指示灯能否在开盖前变色，仍待完整充电实测。

## 2026-11-25 niri 与 Noctalia 真机试跑

A 槽通用引导已从 `/dev/sda` 挂载 USB 根文件系统。niri 使用内屏 `1920×1200@60 Hz` 和 `1.25` 缩放，Wayland EGL 报告 Mali-G52 Panfrost 硬件加速及 OpenGL ES 3.1。使用者确认触控板手势好用。

从 eMMC 桌面发起重启时，再次出现黑屏后要手动按电源键才能启动的现象。eMMC 持久日志显示 `11:00:19` 收到重启请求，`11:00:22` 到达 `reboot.target`。手动按电源键后成功进入 USB 桌面，当次 USB 启动的 pstore 为空。内核进入系统重启后的板级状态仍待定位。

切到 Noctalia 的网络面板时，对真机 niri 进程采样了 9 秒，记录了 25 次 `DRM_IOCTL_MODE_ATOMIC` 和 0 次 `DRM_IOCTL_MODE_DIRTYFB`。niri 26.04 的 Smithay DRM 后端通过 KMS 原子提交更新画面。保留给 i3/Xorg 的 `0002` 补丁可用 `CLOUDBOOK_X11_DIRTYFB_WORKAROUND=0` 从 niri 对照内核中省略。已生成独立引导候选，initramfs、RSCE、DTB 和启动参数与当前 USB 引导镜像逐字节相同。ARM64 QEMU 已从 USB 进入 Debian 登录，该候选的内屏效果待真机对照。

Noctalia 已注册 NetworkManager 密码代理，niri 会话停用 `nm-applet` 后原 Wi-Fi 连接保持在线，托盘保留 Fcitx。Noctalia 5.6.0 在 `/proc/cpuinfo` 缺少 `model name` 时改用 `lscpu`，真机控制中心已显示 `Cortex-A55`。电源菜单已显示「重启到安卓」，真机目视确认了深色对话框与默认取消。运行了 Android B 槽只读检查，并用替代授权命令验证了取消和确认分支。

Ghostty 1.4.0 需要桌面 OpenGL 4.3，真机 Panfrost 报告桌面 OpenGL 3.1。Ghostty 通过 Mesa llvmpipe 软件渲染运行，使用者反馈打字明显慢于 Foot。USB 镜像继续用 Foot 作为默认终端，快捷键为 `Super+Enter`。

eMMC 首次启动日志显示，LightDM 在 Plymouth 所用的 VT1 上先关闭动画，再到 VT7 启动 Xorg。Noctalia 构建和已安装的 eMMC 系统改由 LightDM 在 VT1 接管 Plymouth，并为 Xorg 请求保留上一帧。QEMU 已走通保留动画的交接分支并进入登录界面，真机内屏的过渡效果待下次开机确认。

Noctalia 镜像只提供 Niri 登录会话，清理 i3、Rofi、Polybar、XFCE Terminal 和旧的网络托盘等组件。Niri 使用 Foot、Thunar 与 Noctalia 控制中心。「重启到安卓」仍使用深色 Zenity 确认框。LightDM 的 GTK 登录界面运行在 Xorg 上，登录后的 Niri 会话通过 Xwayland 兼容需要 X11 的应用。

随后手动开机已在真机确认开机动画保持到 LightDM 显示。该次开机的 LightDM greeter 等待 `user@100.service` 就绪约 12 秒。登录后 LightDM 切到 VT2，logind 自动启动了 `getty@tty2`，比 Niri 接管内屏早约 1.2 秒。已屏蔽图形会话使用的 VT1、VT2 上的 getty，VT3 到 VT6 仍可用作文本控制台。QEMU 已验证 Plymouth 保留开机动画，LightDM 登录后 Niri 正常在 VT2 接管，`getty@tty2` 未启动。

同一轮重启日志显示，重启动画期间 Noctalia 的用户 scope 耗尽了默认 90 秒退出超时，随后 systemd 到达 `reboot.target`。已为 Noctalia scope 设置 5 秒退出超时，并让它在 Niri 之前退出。Foot 继续由桌面快捷键直接启动，已停用造成用户关机依赖环的 Foot server。真机验证了新超时会在 5 秒后结束不响应的进程。需要按电源键才能重新开机的现象在 90 秒等待结束之后发生，现有日志停在 systemd 的最后清理阶段，板级热重启仍待定位。

复测中 Noctalia 正常退出，Linux 在发起重启后数秒内到达 `reboot.target`。机器仍需按电源键开机，关机交接时内屏仍闪过文字画面。随后启动的日志确认 `getty@tty2` 已屏蔽。对照内核 `#13` 对本机 RK809 跳过重启时的 SLP 引脚断电配置和 PMIC `DEV_RST`，交由 SoC 重启处理程序执行，保留 RK809 的关机处理程序。`#13` 和原 initramfs 已在 QEMU 进入 Debian，图形测试验证了限制内屏文字控制台到 VT3–VT6 后 Plymouth、LightDM 和 Niri 的交接。测试镜像已写入并读回核对 `boot_a`。[内核重启补丁](../mainline-7.3/0005-mfd-rk8xx-use-soc-restart-on-cloudbook-air.patch)

使用者确认 `#13` 从 eMMC 正常自动重启，关机时内屏也没有再闪出 TTY。上一轮在北京时间 `12:52:32` 到达 `reboot.target`，下一轮在 `12:52:44` 开始启动，根盘仍为 `/dev/mmcblk0p21`。重启补丁已加入 7.3.5 内核构建。Noctalia 的 USB 与 eMMC 构建在 boot header 和 DTB 中都设置 `fbcon=vc:3-6` 与 `systemd.show_status=no`，常规镜像沿用原有的 ramoops 记录范围。[Noctalia 构建说明](FLASHING_GUIDE.md#4-验收与部署到-emmc)

以真机验证过的 `#13` 重新打包内核、模块和 Noctalia 根镜像，交付目录为 `artifacts/7.3/noctalia/usb/` 和 `artifacts/7.3/noctalia/emmc/`。两套新镜像在 ARM64 QEMU 到达 `graphical.target` 和登录提示。同时接入两块盘时，USB 版选中了 `ROOTFS`。无 U 盘时，eMMC 版从 `EMMC_ROOTFS` 启动。下文的 `#9`、`#10` 镜像路径和哈希属于历史验证记录。

## 2026-11-25 Ubuntu 26.04 试跑

Ubuntu 26.04 LTS ARM64 的 Niri 26.04 和 Noctalia 5.6.0 在同一台机器从 USB 启动。Panfrost 的 OpenGL ES 3.1、H.264 和 HEVC 硬解、清华 APT 源和 RK809 默认音频均已检查。Niri 会话的 ScreenCast portal 已能创建会话。HDMI 音频仍待连接显示设备实测。Ubuntu 的两套镜像沿用 `#13` 重启修复，并在 ARM64 QEMU 启动到图形目标。[当前构建](../mainline-7.3/README.md#7-ubuntu-桌面与-rootfs-构建)

## 真机已经验证的能力

| 项目 | 实机结果 |
| --- | --- |
| 启动与内屏 | 原厂 U-Boot 从 eMMC 的 `boot_a` 加载内核、设备树和 initramfs，initramfs 找到卷标为 `ROOTFS` 的 U 盘后进入 Debian。eDP 内屏在 1920×1200、60 Hz 下进入 i3，Xorg 使用 Mali-G52 的 Panfrost/glamor 加速。 |
| 顶栏附近的光标跳动 | `#8` 的对照测试把延迟定位到 Xorg 局部重绘触发的 Rockchip `DIRTYFB` 同步等待。`#9` 针对这台机器调整这条路径后，用户在原先最容易复现的右上角确认光标恢复平滑。 |
| Wi-Fi 与蓝牙 | 从本机 Android B 的 `vendor_b` 提取的 MT7663 配套固件与本地 OTA 中这两个文件一致。修正蓝牙初始化后，`#9` 实机开机可注册 `wlan0`。已验证 5 GHz 自动连接、双向传输以及 2.4 GHz 回退。现场连接优先级和凭据只属于试机配置，新根镜像首次使用时需选择自己的网络。 |
| 电池与视频 | 板载 SBS 电量计能报告电量与充放电状态。7.3 硬件解码路径已用 1080p30 H.264 和 HEVC 样本各 60 帧验证，解码后的整段像素数据与软件解码一致。 |

[USB 真机试跑、故障复现与对照记录](USB_TEST_KIT_7.3.md)

## 阶段交付

| 用途 | 本地产物与验证范围 |
| --- | --- |
| eMMC A 槽内核 | `artifacts/7.3/candidates/build10-v30/boot_linux_73_v30_usb_build10.img`。已写入 `boot_a`，镜像有效字节的读回 SHA-256 为 `45f3f1d163950a763157f81c07eda6b6f29fb8ba8622af2e018c07ceeb6de87f`。同次核验中，`boot_b` 与原厂备份一致。 |
| USB 根盘 | `artifacts/7.3/rootfs-7.3.5-cloudbook.ext4.zst`，解压得到 8 GiB 整盘 ext4 镜像，卷标 `ROOTFS`，已加入 `alsa-ucm-conf` 与 RK809 UCM2 耳机路由配置。2026-11-22 重建后原始 ext4 的 SHA-256 为 `35393fe5d83ce059e04367bcfd2d2b46761dcdd72fcfaf681b84c8a500372582`。 |
| 默认桌面 | LightDM、i3、Catppuccin Mocha、Rofi 和网络托盘。ARM64 QEMU 使用 `#10` 和最终根镜像副本验证了 i3 登录、应用菜单及网络托盘。`Win+D` 打开应用菜单，点顶栏网络图标选择 Wi-Fi。 |
| 软件源与时钟 | 阿里云 Debian 主仓库、常规更新及安全更新的 arm64 索引已在真机和虚拟机验证。时区为 `Asia/Shanghai`，NTP 首选 `ntp.aliyun.com`。现场 Wi-Fi 的 NTP 请求被拒绝，真机系统时钟和 RTC 已手动校准。虚拟机运行中由备用 NTP 源完成同步，独立的双站 HTTPS 校时也修正了人为引入的约 15 分钟误差。 |

## 2026-11-22 构建方式变更

- **initramfs**：7.3 不再使用手工 busybox `/init`，改由 Debian `initramfs-tools` 在 rootfs 后处理阶段生成 `/boot/initrd.img-7.3.5-cloudbook`。根分区仍由 cmdline 的 `root=LABEL=EMMC_ROOTFS`（U 盘为 `ROOTFS`）确定，现在由发行版 initrd 的 local 脚本解析，并自带 e2fsck、udev 等待与救援 shell。上述早期真机结果使用旧 initramfs。11 月 23 日的 p21 双系统验证使用 Debian initrd。
- **时间同步**：移除自制的 `cloudbook-https-time.service/timer` 与 `cloudbook-https-time` 脚本，统一由 `systemd-timesyncd` 使用阿里云、Cloudflare 与 Debian 池的 NTP 源校时；上表提到的 HTTPS 校时属于旧构建。
- **顶栏**：i3 内置 i3bar 换成 polybar（Catppuccin 配色，左侧 i3 工作区、中间日期、右侧 CPU/内存/Wi-Fi/电量与系统托盘）。`~/.local/bin/i3-theme` 切换 Catppuccin 变体时会同时重写 `~/.config/polybar/colors.ini` 并重启顶栏，i3 与 polybar 保持同一配色。
- **A/B 成功上报**：Linux 没有 Android 的 `bootctl`，新增 `cloudbook-mark-boot-successful.service`（`local-fs.target` 之后运行），按 AVB AB 格式把当前槽写成 `tries=0, successful=1`。写入前校验 magic 与 CRC32，原记录备份到 `/var/lib/cloudbook/misc-ab.bak`。没有这一步，Rockchip U-Boot 会在下一次启动把该槽 priority 清 0（实测一次非正常关机即触发）。

### 2026-11-22 重建产物

复用旧的 `debian:trixie` 构建容器，在已有 `/rootfs73` 上重跑后处理（模块改用 build10 包，`apt-get install --fix-policy` 补齐所有已装包的 Recommends，并加入简体中文、fcitx5、polybar 与完整中文字体，共 1058 个包），删除旧的根镜像后重新生成：

| 产物 | 说明 | SHA-256 / 大小 |
| --- | --- | --- |
| `artifacts/7.3/rootfs-7.3.5-cloudbook.tar.zst` | eMMC 用，卷标 `EMMC_ROOTFS`，含 `/boot/initrd.img-7.3.5-cloudbook` | `98b545a6…` / 898 MB |
| `artifacts/7.3/rootfs-7.3.5-cloudbook.ext4` / `.ext4.zst` | USB 用，8 GiB 整盘 ext4，卷标 `ROOTFS` | `35393fe5…` / 8.0 GiB；`5cf3e25f…` / 895 MB |
| `artifacts/7.3/initramfs-7.3.5-cloudbook-emmc.img` | 发行版 initrd（zstd，含 busybox 与 fsck），USB/eMMC 共用 | `1900b32c…` / 18 MB |
| `artifacts/7.3/boot_linux_73_v30_usb_debian-initrd.img` | v30 USB 启动镜像，cmdline `root=LABEL=ROOTFS` | `8ce19323…` / 57 MB |
| `artifacts/7.3/boot_linux_73_v30_emmc_debian-initrd.img` | v30 eMMC 启动镜像，cmdline `root=LABEL=EMMC_ROOTFS` | `f58c931a…` / 57 MB |

简体中文已作为默认：`/etc/default/locale` 为 `LANG=zh_CN.UTF-8`、`LANGUAGE=zh_CN:zh`，postprocess 会先生成 `zh_CN.UTF-8`；中文字体含 `fonts-noto-cjk`、`fonts-noto-cjk-extra`、`fonts-wqy-zenhei`、`fonts-wqy-microhei` 与 `fonts-noto-color-emoji`。桌面附加 `fcitx5` + `fcitx5-chinese-addons`、`im-config` 默认 fcitx5、`~/.xprofile` 注入 IM 环境变量、`~/.config/fcitx5/profile` 以 pinyin 为默认引擎，i3 自启 `fcitx5 -d`。

顶栏使用 polybar（`fonts-font-awesome` 提供图标，`~/.config/polybar/colors.ini` 在构建时按 Mocha 预生成），i3 的 `bar` 块已注释并改由 `exec_always` 拉起 polybar；`~/.local/bin/i3-theme` 切换 Catppuccin 变体时同步重写 polybar 配色。

完整校验值见 `artifacts/7.3/SHA256SUMS`。

ARM64 QEMU（`-M virt` + virtio-blk + 8 GiB USB 镜像）已用该组合启动：initrd 内 `fsck.ext4 -a /dev/vda` 执行、根分区按 `LABEL=ROOTFS` 挂载、`switch_root` 后 systemd 257 到达 `graphical.target`，lightdm 启动并出现登录提示。真机的 eDP 内屏、Wi-Fi 与电源路径仍需实机复验。

## 2026-11-22 留下的验证项

- 后续桌面与 p21 启动结果见本文开头。充电指示灯、合盖唤醒和无线冷启动需按实际镜像分别验收。
- 热重启曾出现电源灯熄灭、需要手动按电源键的现象。该问题已在 11 月 25 日的 `#13` 真机验证中解决。
- USB 根盘实际占用了配置为 host 的 OTG 控制器。当前 Linux 调试使用 SSH；同一接口同时提供有线 ADB 尚待解决。

## Android 14 可以复用的部分

本阶段验证了原厂引导器加载自制 `boot_a`、内核早期识别 U 盘以及内屏等板级接线。后续已用 Rockchip 6.1 BSP 打包 Android 14 USB v4 候选镜像，在 U 盘上提供 Android 系统分区与独立的 `/data`。Android 14 的 USB 启动和桌面均待实机验证。[Android 14 移植现状](ANDROID14_PORT_STATUS.md)
