# Linux USB 首启黑屏事件记录（2026-11-21）

> **设备**：ZTE `eCloudBook Air 24RTW`（`rk3568_r_w600d2`），Android 11，`slot_b`
> **目标**：在 `boot_a` 上跑主线 Linux `6.12.117-cloudbook`，rootfs 放 U 盘（`LABEL=ROOTFS`）
> **状态**　原 v28 的真机日志显示内屏驱动停在面板探测。换用 `#5` 内核后，用户在笔记本内屏看到了 Debian 的文字界面。这份 6.12 根文件系统提供控制台登录。
> **边界**：`boot_b` 未写入；`boot_a` 和 `misc` 被写过，均有原厂备份。

---

## 0. 结论先行

1. **A 槽确实被 U-Boot 尝试过。** 第一次黑屏后读 `misc`：
   - A：`priority=0, tries=0, successful=0`
   - B：`priority=14, tries=0, successful=1`
   - 写入的 `misc_try_slot_a` 原本是 A `15/3/0`、B `14/0/1`。引导器已用完 A 的试启动次数。下文的真机日志记录了其中一次进入 Linux 的过程。
2. **确认了一个 initramfs 缺陷**：它只扫描 `/dev/sd*[0-9]`（分区），不扫描整盘 `/dev/sda`；而 U 盘镜像是无分区表的整盘 ext4，只有 `/dev/sda`。因此 init 找不到 `LABEL=ROOTFS`，最终掉进 rescue shell。
3. 修复整盘识别后，Debian 从 `/dev/sda` 启动并运行超过八分钟。内屏驱动仍在等待 AUX 总线上的面板。真机启动证据见第 2.2 节。
4. 回到 Android B 后已读回 `misc`。引导器只会在再次上电或复位时重新选槽。运行中的 Debian 不会因内屏黑屏而重启。

---

## 1. 做了什么

### 1.1 只读备份

原厂关键分区已备份到主机：

```text
factory_backup/
  boot_a.img
  boot_b.img
  misc.img
  uboot_a/b.img
  trust_a/b.img
  vbmeta_a/b.img
  dtbo_a/b.img
  mmcblk2_first8M_idbloader_gpt.img
  LOCAL_BACKUP.sha256
```

`boot_b.img` 的 SHA-256：

```text
deeaf8fd7b99f31ff47ac616e8774ca2d532c5ea451387dd0fa69ebd7c8915eb
```

最后一次校验时，设备上的 `boot_b` 与该备份一致；没有写过 `boot_b`。

### 1.2 U 盘 rootfs

生成并写入 64 GB Ventoy U 盘（已清空 Ventoy）：

```text
artifacts/rootfs-debian13-usb.ext4
size  3,221,225,472 bytes
sha256 67ecd48e544f45a590736efc90da4ac544551f86f8c75ae0fa06d29a89f896ca
label ROOTFS
type  ext4（整盘，无分区表）
```

设备侧 Android 内核能识别：

```text
/dev/block/sda: LABEL="ROOTFS" UUID="ed2c592a-..." TYPE="ext4"
```

### 1.3 第一次 A 槽启动

写入：

```text
boot_a  <- artifacts/boot_linux_612_v28_usb.img
          原 SHA 0f7661c4...
misc    <- artifacts/misc_try_slot_a.img
          SHA d759b915...
```

重启后看到黑屏，网络与 ADB 不可达。之后电源循环回到 Android B，`misc` 记录了 A 槽的试启动次数已耗尽。

### 1.4 修 initramfs

拆开 `boot_linux_612_v28_usb.img`：

```text
kernel   23,360,000 bytes
ramdisk   1,017,136 bytes（gzip cpio）
second   16,672,256 bytes（RSCE resource）
cmdline  root=LABEL=ROOTFS rootfstype=ext4 rootwait ...
```

ramdisk 只有：

```text
/init
/bin/busybox
```

`/init` 原始候选设备：

```sh
for candidate in /dev/mmcblk0p20 /dev/mmcblk0p* /dev/mmcblk1p* \
                 /dev/sd*[0-9] /dev/vd* /dev/nvme*n*p*; do
```

没有 `/dev/sda`。U 盘没有分区表，所以只有 `/dev/sda`，`/dev/sda1` 不存在；init 永远匹配不到，120 秒后执行 `/bin/sh`。

修改 `scripts/initramfs-6.12.117/init`：

```sh
for candidate in /dev/mmcblk0p20 /dev/mmcblk0p* /dev/mmcblk1p* \
                 /dev/sd*[0-9] /dev/sd* /dev/vd*[0-9] /dev/vd* \
                 /dev/nvme*n*p* /dev/nvme*n*; do
```

重打：

```text
artifacts/initramfs-6.12.117-cloudbook-emmc.img
  1e085f98...
artifacts/boot_linux_612_v28_usb.img
  524de4de064a0b4142e611b46a85731357c9b76f604a495b5eeae1cf5f370ba3
artifacts/boot_linux_612_v29_emmc.img
  5b2cce7aaf3ed4abca167479cd12b7017f190e532c2efd7732038324f5ff723b
```

新 v28 已写入 `boot_a` 并回读校验一致。

### 1.5 第二次 A 槽启动

再次写 `misc_try_slot_a` 并重启：

```text
U 盘 /dev/block/sda -> LABEL=ROOTFS ext4
boot_a -> 524de4de...（修好的 v28）
misc   -> d759b915...（A 15/3/0）
```

结果：**仍然黑屏、无输出、无网络、无 ADB**。等待 120 秒无 ping；`22/5555/8000` 全部关闭。

---

## 2. 已排除 / 已确认

### 2.1 已确认不是

- **不是 A 槽没被尝试**：第一次失败后 `misc` 里 A 被置 `0/0/0`。
- **不是内核缺 USB 驱动**：`config-6.12.117-cloudbook` 里：
  ```text
  CONFIG_USB_STORAGE=y
  CONFIG_USB_XHCI_HCD=y
  CONFIG_USB_XHCI_PLATFORM=y
  CONFIG_USB_EHCI_HCD=y
  CONFIG_USB_OHCI_HCD=y
  CONFIG_USB_DWC3=y
  CONFIG_USB_DWC3_DUAL_ROLE=y
  CONFIG_SCSI=y
  CONFIG_BLK_DEV_SD=y
  CONFIG_EXT4_FS=y
  ```
- **不是 U 盘镜像损坏**：主机写入后整盘回读 SHA-256 一致；Android 能读出 `LABEL=ROOTFS`、`TYPE=ext4`。
- **不是 boot_b 被写**：B 回读哈希与备份一致。
- **不是 initramfs 完全不支持整盘**：修复后已包含 `/dev/sd*`。

### 2.2 真机日志与显示驱动

在 Android B 用 `mount -t ext4 -o ro,noload` 读取这只 U 盘的 `/var/log/journal`，再用 `journalctl -D` 检查。日志里同时有本机型号、`androidboot.slot_suffix=_a`、内核 `6.12.117-cloudbook` 和这只 U 盘的 ext4 UUID。以下为距 Linux 启动的时间。

| 时间 | 记录 |
| --- | --- |
| 7 秒 | `usb-storage` 识别 61.9 GB 硬盘，`EXT4-fs (sda): mounted filesystem ... r/w` |
| 11 秒 | `Started getty@tty1.service`，本地文本登录已启动 |
| 21 秒 | `platform fe0c0000.edp: deferred probe pending` |
| 22 秒 | `Reached target multi-user.target`，`wlan0` 处于 `disconnected` |
| 34 秒 | `vcc3v3_lcd_edp: disabling` |
| 508 秒 | `systemd-logind: Power key pressed short` |

原厂 Android 读到的面板型号为 `BOE-NV140WUM-N44`，原生分辨率为 `1920x1200`。v28 设备树将通用 `edp-panel` 放在 `/edp@fe0c0000/aux-bus/panel`。旧 6.12 驱动一开始就查找已注册的面板，却没有创建 AUX 子设备，因此一直延迟探测。补丁 `0014` 提前初始化 AUX 和运行时电源管理，在面板完成探测后绑定 DRM。它沿用上游的探测顺序。[Analogix AUX 初始化](https://github.com/torvalds/linux/commit/e5e9fa9f7aad) · [Rockchip 面板注册](https://github.com/torvalds/linux/commit/d7b4936b2bc0)

Android 用 `uas` 驱动这只 USB 硬盘盒，6.12 已用 `usb-storage` 成功挂载同一块盘。本次保留原来的 USB 配置与 v28 设备树，验收目标为内屏出现 Debian 文本登录提示。

---

## 3. 当前设备状态

内屏修复镜像试启动前，在 Android B 读到的设备状态如下。第 7 节记录随后写入的试启动配置。

```text
boot_a = 修好的 v28（524de4de...）
boot_b = 原厂 Android 11（deeaf8fd...，未写）
misc   = A 0/0/0，B 14/0/1（priority / tries_remaining / successful_boot），CRC 有效
```

Android 的 `ro.boot.slot_suffix` 已读回 `_b`。试启动的 A 槽启动 Linux 后没有向 `misc` 提交成功标志，因此后续上电可以自动回到 B。本次取证时 Android 的 pstore 目录为空。

### 3.1 Android B 重启后的普通网络 ADB

首次核查时，`adbd` 日志在开机第 7 秒记录了无线调试的动态端口，在第 19 秒记录了 `persist.adb.tls_server.enable changed to 0` 和 `adb wifi stopped`。当次启动读回的 `persist.vendor.adbmode.enable` 仍为 `1`。日志只记录了属性变化，未标明发起关闭的进程。

这台设备的固定 TCP 端口由 `/vendor/etc/init/hw/init.rk30board.rc` 控制。现在 `persist.internet_adb_enable=1`，由厂商 init 设置 `service.adb.tcp.port=5555`。`persist.vendor.adbmode.enable=1` 使开机脚本 `/vendor/bin/zted` 跳过 `stop adbd`。这份 userdebug 固件能在启动时从 `/data/local.prop` 读取 `ro.adb.secure=1`，使用已有的 ADB 主机密钥验证连接。

Android B 经两次实际重启均可使用 `adb connect 192.168.123.128:5555`。最后一次已移除临时监听地址，开机完成且 `zted` 退出后，`adb_wifi_enabled=0`、`adbd=running`。普通 TCP 握手收到 RSA 质询，这台 Mac 使用已授权的密钥连入，默认身份为 `shell`（UID 2000）。

---

## 4. 恢复步骤（不在本次执行）

1. 长按电源 15 秒断电；等 3 秒再开机。
2. 若仍黑屏，重复最多 3 次，让 A 槽 tries 耗尽，回 Android B。
3. 回到 Android 后开 ADB，写回原厂 `misc`：
   ```bash
   adb root
   adb push factory_backup/misc.img /data/local/tmp/misc_factory.img
   adb shell 'dd if=/data/local/tmp/misc_factory.img of=/dev/block/by-name/misc bs=4M conv=fsync && sync'
   adb shell 'sha256sum /dev/block/by-name/misc'
   # 期望 dcf6365a61ae7884f08cfc13ce2026005fbed8ae4eb6fc34a394a309ac44eba2
   ```
4. 若要回原厂 boot_a：
   ```bash
   adb push factory_backup/boot_a.img /data/local/tmp/boot_a_factory.img
   adb shell 'dd if=/data/local/tmp/boot_a_factory.img of=/dev/block/by-name/boot_a bs=4M conv=fsync && sync'
   ```
   （只建议在完全放弃 Linux 首测时做；B 不受影响。）

---

## 5. 补充诊断通道

### 5.1 先读 pstore

回到 Android 后，先只读：

```bash
adb shell 'ls -l /sys/fs/pstore/ 2>/dev/null'
adb shell 'cat /sys/fs/pstore/console-ramoops* 2>/dev/null'
adb shell 'cat /proc/last_kmsg 2>/dev/null'
```

如果上一轮 Linux 内核崩溃，pstore 可能保留 dmesg，能直接看到 USB/DRM/root 错误。

### 5.2 诊断 initramfs

已生成但**未写入设备**：

```text
artifacts/boot_linux_612_v28_usb_diag.img
sha256 af78b2acaa0cdc12ddc0bb9a452be4e1d84c2bd79b643bf8d36a584b7e2fea55
```

它在 initramfs 挂到 root 后写：

```text
/root/cloudbook-initramfs.log
  - /proc/cmdline
  - /proc/partitions
  - /dev/sd* /dev/mmcblk*
  - dmesg
```

黑屏后断电，把 U 盘插回主机读该文件：

- 文件存在：root 挂上了，查 dmesg 里的 eDP/DRM/USB。
- 该文件由诊断版 initramfs 生成。使用普通 v28 时，优先检查 U 盘上的 `/var/log/journal`。

### 5.3 其他诊断通道

- 外部 HDMI 显示器：区分 eDP 单独故障还是整机没启动。
- USB 转以太网（SR9900）或有线网络：绕开 Wi-Fi 未配置的问题。
- UART `UART2`（115200 8N1）：最直接，但目前没有。
- USB 2.0 U 盘：排除 USB3 hub 复位/枚举问题。

---

## 6. 关键文件

```text
artifacts/rootfs-debian13-usb.ext4
  67ecd48e...
artifacts/initramfs-6.12.117-cloudbook-emmc.img
  1e085f98...
artifacts/boot_linux_612_v28_usb.img
  524de4de...
artifacts/boot_linux_612_v29_emmc.img
  5b2cce7a...
artifacts/boot_linux_612_v28_usb_diag.img
  af78b2ac...
artifacts/boot_linux_612_v28_usb_edp.img
  01f90491bff9f73d9c711809d818a04a31e6a7419025f083ba9a1b2fc7a86e99
artifacts/misc_try_slot_a.img
  d759b915...
factory_backup/misc.img
  dcf6365a...
factory_backup/boot_b.img
  deeaf8fd...
```

源码修复：

```text
scripts/initramfs-6.12.117/init
```

---

## 7. 试机验收

第 2.2 节来自原 v28 镜像。候选 `boot_linux_612_v28_usb_edp.img` 内核构建号为 `#5`，沿用原 v28 的 ramdisk、引导参数、RSCE 资源和 DTB。原厂 A 槽 DTBO 可以应用到该设备树。QEMU 通过 USB 大容量存储加载同一份整盘根文件系统，已到达 `cloudbook login:`。

真机试启动时核对内屏出现 `cloudbook login:`，再用 `cat /sys/class/drm/card*-eDP-*/status` 和 `cat /sys/class/drm/card*-eDP-*/modes` 检查 `connected` 和 `1920x1200`。这份 6.12 的 Debian 根文件系统提供文本登录。

2026-11-21 18:34（北京时间）已在实机发起 `#5` 的首次试启动。写入前在本机 `artifacts/edp-trial-20261121-183312/` 完整备份并校验了当时的 `boot_a` 和 `misc`，同时核实 `boot_b` 与原厂备份一致。写入后，整个 160 MiB 的 `boot_a` 与预期内容一致。试启动记录基于本次读取的 `misc` 生成，只更新 A 槽字段和 CRC，写回后校验了整个 4 MiB。重启前 A 为 `15/3/0`，B 为 `14/0/1`。18:34:50 发出重启指令后，用户确认内屏能显示文字界面，并要求使用带桌面和视频解码的 7.3.5 版本。实际的 DRM 模式尚未从设备读回。


### #9 正常重启请求后电源灯熄灭

在 U 盘读回的这次 journal 中，启动 ID 为 `248ebf00146f4c83ac237ab2cb24350e`。第 168.97 秒 logind 记录 `The system will reboot now!`，第 171.69 秒到达 `reboot.target`，第 171.87 秒同步磁盘，第 171.98 秒 journald 停止。日志中看门狗的超时为 10 分钟。此后是否触发板级复位、是否进入下一轮 U-Boot，超出了本次 journal 的记录范围。前一次重启前 Linux A 剩 1 次，手动开机后从 Android B 读回 A 的优先级、剩余次数、成功位均为零。核对复位到下一次引导之间发生的事情，需要跨重启串口记录。
