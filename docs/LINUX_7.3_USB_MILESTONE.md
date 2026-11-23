# CloudBook Air 7.3 USB 桌面阶段总结

截至 2026-11-22，`CT2000-24RTW`（W620D V12.0，RK3568B2）的 Linux USB 桌面阶段可以收尾。`7.3.5-cloudbook #9` 是有真机观测和用户手感反馈的基线。`#10 / v30` 和新版 Debian 13 i3 根镜像是本阶段交付的候选组合，最新一次记录止于写入内核与读回校验。

## 2026-11-23 后续验证

新版 rootfs 已构建出 `kuaner` 桌面、USB/eMMC 根镜像和通用 `boot_a` 镜像。图形切换已在真机往返验证，Debian 从 eMMC `p21` 挂载根盘。现行构建和安装方式见 [7.3.5 桌面与 rootfs](../mainline-7.3/README.md#7-debian-桌面与-rootfs-构建) 和 [双系统切换](../tools/system-switcher/README.md)。以下 `#9`、`#10` 与 Catppuccin 配置保留为 USB 阶段记录。

v30 已纳入充电状态及休眠插拔处理。合盖后持续插电直到充满时，指示灯能否在开盖前变色，仍待完整充电实测。

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
- 热重启曾出现电源灯熄灭、需要手动按电源键的现象。最近一次写入后，A 槽剩余尝试为 3，B 已标记启动成功。次数由引导器在后续启动时处理；停在黑屏或已断电时没有新的启动尝试。热重启停在哪一步仍需真机启动日志确认。
- USB 根盘实际占用了配置为 host 的 OTG 控制器。当前 Linux 调试使用 SSH；同一接口同时提供有线 ADB 尚待解决。

## Android 14 可以复用的部分

本阶段验证了原厂引导器加载自制 `boot_a`、内核早期识别 U 盘以及内屏等板级接线。Android 14 已另有 Rockchip 6.1 BSP 候选内核和启动镜像。若采用外置系统盘试验，下一阶段需让 Android first-stage init 从 USB 找到和挂载 Android 逻辑分区，为试验提供独立的 `/data`，并核对 AVB、vendor HAL 与内核驱动的匹配关系。Android 14 的 USB 启动和桌面均待实机验证。[Android 14 移植现状](ANDROID14_PORT_STATUS.md)
