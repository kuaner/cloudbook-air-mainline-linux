# 本地已备产物与真机后续步骤（`6.12.117-cloudbook`）

> 生成日期：2026-11-19  
> 构建环境：arm64 容器（Ubuntu 24.04 / clang 18.1.3 / LLD 18.1.3 / GNU patch 2.7.6 / QEMU 10.0.13）  
> 本文只描述**不接触真机**就能完成并已验证的部分，以及拿到机器后的最小操作。

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

## 3. 尚未验证（必须真机）

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

## 6. 真机步骤 3：U 盘首测（不改原厂数据）

```bash
# 1) 分区与格式化（先 lsblk 核对盘符）
sudo parted -s /dev/sdX mklabel gpt
sudo parted -s /dev/sdX mkpart primary ext4 1MiB 100%
sudo mkfs.ext4 -L ROOTFS /dev/sdX1

# 2) 解开用户态
sudo mkdir -p /mnt/cb && sudo mount /dev/sdX1 /mnt/cb
sudo tar -I zstd -xf artifacts/rootfs-debian13-trixie-arm64-cloudbook.tar.zst -C /mnt/cb

# 3) 首测卷标改为 ROOTFS
sudo sed -i 's/^LABEL=EMMC_ROOTFS /LABEL=ROOTFS /' /mnt/cb/etc/fstab
sync && sudo umount /mnt/cb
```

随后按 `docs/FLASHING_GUIDE.md` 第 5 节写入 `boot_a` 与 `misc`（A 槽三次试引导）。

## 7. 真机步骤 4：迁移到内置 eMMC

在 U 盘启动的系统里执行：

```bash
sudo mkfs.ext4 -F -L EMMC_ROOTFS /dev/mmcblk0p20
sudo mount /dev/mmcblk0p20 /mnt
# rootfs 包内的 /etc/fstab 已经是 LABEL=EMMC_ROOTFS
sudo tar -I zstd -xf /path/to/rootfs-debian13-trixie-arm64-cloudbook.tar.zst -C /mnt
sync && sudo umount /mnt
# 写入 eMMC 版 boot.img 并回读校验
sudo dd if=/path/to/boot_linux_612_v29_emmc.img of=/dev/mmcblk0p11 bs=4M conv=fsync
```

确认可独立启动后，用 `switch_ab_slot.py commit_slot_a` 生成 `misc` 并写入 `/dev/mmcblk0p6` 锁定 Slot A。

## 8. 默认账户与安全

| 账户 | 密码 | 说明 |
| :--- | :--- | :--- |
| `cloudbook` | `cloudbook` | 属 `sudo` 组 |
| `root` | `cloudbook` | 串口/本地登录 |

**首次登录后请立即 `passwd` 修改**。rootfs 已启用 `NetworkManager`、`bluetooth`、`ssh`，SSH 默认允许密码登录。

## 9. 复现方法

| 内容 | 位置 |
| :--- | :--- |
| 7.3 initramfs | `scripts/rootfs-postprocess-debian13.sh` 在 rootfs 内安装 Debian `initramfs-tools`，生成 `/boot/initrd.img-7.3.5-cloudbook`；`root=LABEL=` 由它的 local 脚本解析 |
| 6.12 initramfs（遗留） | `scripts/initramfs-6.12.117/init`（手工 busybox，仅 6.12 分支引用） |
| rootfs 后处理脚本 | `scripts/rootfs-postprocess-debian13.sh` |
| 补丁重基说明 | `mainline-6.12/README.md` 的“补丁基线与 6.12.117 重基”一节 |

复现命令（与本次构建一致）：

```bash
# rootfs
debootstrap --arch=arm64 --variant=minbase \
  --include=systemd,systemd-sysv,dbus,sudo,openssh-server,network-manager,\
wpasupplicant,iw,wireless-regdb,polkitd,rfkill,bluez,kmod,e2fsprogs,usbutils,pciutils,\
ca-certificates,curl,less,vim-tiny,htop,locales \
  trixie /rootfs http://deb.debian.org/debian

# 后处理会安装 initramfs-tools 并在 rootfs 内生成 /boot/initrd.img-7.3.5-cloudbook；
# 打包时 scripts/pack_linux_artifacts.sh 会从 rootfs tarball 自动取出它
KVER=7.3.5-cloudbook MODULES_TARBALL=/inputs/modules-7.3.5-cloudbook.tar.gz \
  scripts/rootfs-postprocess-debian13.sh
```
