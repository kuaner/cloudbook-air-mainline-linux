# 6.12.117 本地构建与早期试跑记录

> 生成日期：2026-11-19  
> 构建环境：arm64 容器（Ubuntu 24.04 / clang 18.1.3 / LLD 18.1.3 / GNU patch 2.7.6 / QEMU 10.0.13）  
> 本文记录当时的本地和 QEMU 验证。当前安装见 [7.3.5 刷机手册](FLASHING_GUIDE.md)，6.12 维护见 [6.12 构建](../mainline-6.12/README.md)。

---

## 1. 产物清单（`artifacts/`，校验值见 `artifacts/SHA256SUMS`）

| 文件 | 用途 |
| :--- | :--- |
| `Image-6.12.117-cloudbook` | ARM64 内核（`uname -r` = `6.12.117-cloudbook`） |
| `rk3568-ecloudbook-air-24rtw-v29.dtb` | eMMC 版设备树（`root=LABEL=EMMC_ROOTFS`） |
| `rk3568-ecloudbook-air-24rtw-v28-usb.dtb` | U 盘首测版设备树（`root=LABEL=ROOTFS`） |
| `modules-6.12.117-cloudbook.tar.gz` | 795 个内核模块，`lib/modules/6.12.117-cloudbook/` |
| `config-6.12.117-cloudbook` | 与真机验证配置一致的 `.config` |
| `System.map-6.12.117-cloudbook` / `vmlinux-6.12.117-cloudbook` | 调试符号（可 addr2line） |
| `initramfs-6.12.117-cloudbook-emmc.img` | 自制最小 initramfs（gzip，994 KB），按卷标自动找根分区 |
| `rootfs-debian13-trixie-arm64-cloudbook.tar.zst` | Debian 13 (trixie) arm64 最小用户态（含模块/固件/板级配置及 RK809 UCM2） |
| `firmware/mt7663_n9_v3.bin`、`mt7663pr2h.bin` | MT7663S Wi-Fi/BT 固件 |
| `boot-test-qemu-6.12.117-cloudbook.log` | QEMU 全链路启动证据 |

## 2. 已完成的验证

1. **补丁重基**：10 个补丁在纯净 `v6.12.117` 上分别用 `git apply` 与 `patch -p1` 应用，均 100% 成功，两条路径产物树逐字节一致。
2. **从零构建**：仓库 `build_kernel_612.sh` 在全新容器、纯净源码上连续两次成功，全量编译零警告；产出的 `.config` 与仓库提交的已验证配置完全相同。
3. **QEMU 全链路启动**：内核 → initramfs → 按卷标找到 `EMMC_ROOTFS` → 挂载 → `switch_root` → systemd 到达 `multi-user.target` 与 `graphical.target`，无失败单元。日志：

   ```text
   CLOUDBOOK minimal initramfs entered
   CLOUDBOOK found EMMC_ROOTFS at /dev/vda
   CLOUDBOOK ROOTFS mounted
   CLOUDBOOK switching to /sbin/init
   ```

## 3. 本次构建留下的真机验证项

- `0010` 补丁里 RK3568 eDP 的结构体适配与 GRF 写入保护（唯一有硬件风险的改动）。
- MT7663S Wi-Fi/蓝牙实际连接与吞吐、PD 充电、盒盖休眠、键盘触控板、Hantro 硬解。
- 实际 SDIO 吞吐（作者反馈 5G 约 300 Mbps，接近 SDIO 链路上限）。

## 4. 从本机 `boot_b` 提取 `resource.img`

当前 `artifacts/extracted_resource.img` 取自 11 月 21 日留存的本机 `factory_backup/boot_b.img`。按 Android boot v2 头提取的 RSCE 长 15,006,720 字节，SHA-256 为 `32d5427363e892e65e7732006afa8b1b8fb3e706cc9a1b21152e171d2d918aa4`。复现命令如下。

```bash
python3 scripts/extract_resource_from_boot.py factory_backup/boot_b.img -o artifacts/extracted_resource.img
```

本机表有 27 个资源条目，从零计数的第 17 项是 `rk3568-w620d-25wh_v12#_saradc_ch1=938#_saradc_ch4=680.dtb`。早期 6.12 试跑镜像用了 P06 OTA 的 16,672,256 字节模板，该表有 36 项，同名板型在第 25 项。打包时第 0 项和按板名找到的条目都会换成同一份主线 DTB。11 月 21 日重打的 7.3.5 镜像使用本机表。

## 5. 引导镜像已生成并校验

| 产物 | 用途 | 大小 |
| :--- | :--- | :--- |
| `artifacts/boot_linux_612_v29_emmc.img` | eMMC 启动（`root=LABEL=EMMC_ROOTFS`） | 39.28 MB |
| `artifacts/boot_linux_612_v28_usb.img` | U 盘首测（`root=LABEL=ROOTFS`） | 39.28 MB |

下列记录来自早期 OTA 模板的 6.12 eMMC 镜像。其两个主线 DTB 条目的大小和 SHA-1 分别为：

```
entry[0]  size=133012 sha1=2a7708f2...
entry[25] size=133012 sha1=2a7708f2...
```

重新生成命令（如需）：

```bash
python3 pack_boot_linux.py \
  --kernel artifacts/Image-6.12.117-cloudbook \
  --ramdisk artifacts/initramfs-6.12.117-cloudbook-emmc.img \
  --dtb artifacts/rk3568-ecloudbook-air-24rtw-v29.dtb \
  --resource-template artifacts/extracted_resource.img \
  --cmdline 'console=tty0 console=ttyS2,115200n8 root=LABEL=EMMC_ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 systemd.show_status=1 systemd.default_timeout_start_sec=15s loglevel=7 no_console_suspend' \
  --output artifacts/boot_linux_612_v29_emmc.img
```

打包结果远低于 160 MiB 的 `boot_a` 分区上限。

## 6. 后续试跑

早期 U 盘根盘使用 `LABEL=ROOTFS`，内置根盘使用 `LABEL=EMMC_ROOTFS`。原版 6.12 U 盘镜像使用 `cloudbook` 账户。整盘 ext4 识别与内屏早期排障见 [11 月 21 日的真机记录](LINUX_USB_BOOT_FAILURE_2026-11-21.md)。

内置双系统使用 p20 `userdata`、p21 `EMMC_ROOTFS`。当前 7.3.5 桌面使用 Ubuntu 自带的 `initramfs-tools` 生成引导所需的 initramfs。[分区布局](P20_P21_DUAL_BOOT_PLAN.md) · [Ubuntu 镜像构建](../mainline-7.3/README.md#7-ubuntu-桌面与-rootfs-构建)
