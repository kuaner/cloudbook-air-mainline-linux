# 6.12 最小候选真机验证计划

这是早期点亮阶段的验证记录。当前部署从 [7.3.5 刷机手册](../docs/FLASHING_GUIDE.md) 开始。

## 本地静态验收

- `build_kernel_612.sh` 无错误完成。
- Image、DTB、模块和配置均有 SHA-256。
- DTB 保留 `__symbols__`，并包含 `chosen`、`reboot_mode`。
- boot.img 的 cmdline 为 8250 UART 和 `LABEL=ROOTFS`，大小不超过 160 MiB。
- 已验证的 4.19 镜像 `build/boot_linux_factory_419_uart.img` 保持不变。

## 刷写前安全状态

当前 A 槽已提交为 successful，不能直接把这个状态用于未验证的 6.12 候选。真正刷写前必须：

1. 单独获得刷写与重启确认。
2. 核对本地候选 SHA-256，并读回确认当前 4.19 boot_a 的备份可用。
3. 只写 `boot_a`，不改 `boot_b`、idbloader、U-Boot、Trust 或 Rootfs。
4. 基于当时的当前 misc 生成 A 槽初始试启动计数为 3 的配置（A successful=0、tries=3），不能直接使用会覆盖其他启动元数据的旧模板。
5. 写入 misc 后完整读回校验，再执行重启。

## 首次真机验收

UART 重点观察：

```text
Starting kernel ...
CLOUDBOOK minimal initramfs entered
CLOUDBOOK found ROOTFS at /dev/sdX1
CLOUDBOOK ROOTFS mounted
CLOUDBOOK switching to /sbin/init
Armbian 26.8.1 Trixie
```

进入系统后检查：

- `/proc/cmdline` 包含 `_a` 和 `console=ttyS2,115200n8`；
- `findmnt /` 为 USB `ROOTFS`；
- `uname -r` 为 `6.12.117-cloudbook`；
- SSH 可用，并单独记录 SR9900 的 probe、DHCP 和 suspend/resume 状态；
- 键盘、触控板只记录为后续验收，不以内屏无显示推断启动失败。

若再次进入 U-Boot charging mode，先短按电源键继续正常启动，不连续改写分区。若候选无法挂载 Rootfs，等试启动计数耗尽后回退 Android B，并保留 UART 证据。

## 通过后才继续

首个最小启动通过后，再按 eDP、输入、音频、电源、摄像头/HDMI 的顺序逐项增加支持。每组修改单独构建、单独记录，不在同一候选中混入多组无证据修改。
