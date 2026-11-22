# CloudBook Air (CT2000-24RTW) 完整刷机与部署手册

> **适用机型**：中国移动 / 中兴云电脑笔记本 `CT2000-24RTW`（主板丝印 `W620D V12.0`，SoC `RK3568B2`，4GB RAM + 32GB eMMC，无 TF 卡槽）  
> **目标系统**：主线 Linux `6.12.117-cloudbook` + Armbian Debian 13 (Trixie) ARM64  
> **安全机制**：保留原厂签名 `idbloader` / `uboot` / `trust` 与 `Slot B` 原厂 Android 11；Linux 仅部署在 `Slot A (boot_a)`。支持 **先 U 盘零损试跑，再可选迁入内置 eMMC**，并保留随时一键还原 Android 的能力。
>
> **本机进展（2026-11-21）**　真机日志确认 A 槽已从 U 盘进入 Debian，黑屏时内屏控制器一直在等待面板。换用补丁 `0014` 的 6.12 镜像后，用户已看到文字登录界面。7.3.5 的 USB 根文件系统提供 LightDM 和 i3 桌面。[故障记录](LINUX_USB_BOOT_FAILURE_2026-11-21.md) · [7.3.5 桌面试跑包](USB_TEST_KIT_7.3.md)

---

## 目录

1. [准备工作与硬件线材](#1-准备工作与硬件线材)
2. [保命前置：确认槽位与冷备份原厂分区](#2-保命前置确认槽位与冷备份原厂分区)
3. [获取或编译内核、补丁、固件与引导镜像](#3-获取或编译内核补丁固件与引导镜像)
4. [阶段一：制作 USB 3.0 启动 U 盘（推荐首测，不改原厂数据）](#4-阶段一制作-usb-30-启动-u-盘推荐首测不改原厂数据)
5. [阶段二：刷写 `boot_a` 与 `misc` 并首次开机验证](#5-阶段二刷写-boot_a-与-misc-并首次开机验证)
6. [阶段三（可选）：迁移 Rootfs 到内置 32GB eMMC（免插 U 盘日常使用）](#6-阶段三可选迁移-rootfs-到内置-32gb-emmc免插-u-盘日常使用)
7. [系统初始化与音视频/桌面调优](#7-系统初始化与音视频桌面调优)
8. [常见问题与还原原厂 Android](#8-常见问题与还原原厂-android)

---

## 1. 准备工作与硬件线材

### 1.1 必备硬件
* 一台同款 **ZTE CloudBook Air 14" (`CT2000-24RTW`)**。
* 一根 **USB 3.0 U 盘**（建议 >= 16GB，用于阶段一免破坏试启动；插 host USB-A 口，不要插 Type-C 旁边的 OTG 口）。
* 一台 **x86_64 Linux 主机**（Ubuntu / Debian 均可，用于制作 U 盘、打包镜像、可选编译内核）。
* 至少以下通信方式之一：
  * **双公头 USB-A 对 USB-A 数据线**（插入笔记本 **Type-C 口旁边的那个 USB-A 母座**，这是本机唯一接通 `dwc3@fcc00000` OTG 控制器的端口，用于 `adb` 或 `rkdeveloptool` 刷机）。
  * 或者 **USB 有线网卡**（连接路由器走网络 ADB / SSH）。
  * （可选但强烈建议准备）TTL 3.3V USB 转串口工具，焊接主板 `UART2` 焊盘（`115200 8N1`）。

### 1.2 安全红线（动手前必读）
1. **绝对禁止刷写以下区域**（芯片已熔断 `fuse.programmed=1`、`RKSS` / `RSA-2048` 签名校验）：
   * eMMC 前 8 MiB（`idbloader` / 主 GPT）
   * `security` (`p1`)、`uboot_a` (`p2`)、`uboot_b` (`p3`)、`trust_a` (`p4`)、`trust_b` (`p5`)
   * 原厂正在工作的 Android 分区 `boot_b` (`p12`) 与 `super` (`p19`)
2. 切勿把其它机型的 `idbloader.img` / Armbian `.img` 直接整盘 `dd` 到内置 eMMC，否则必变砖。

---

## 2. 保命前置：确认槽位与冷备份原厂分区

在主机安装 `adb`（或 `rkdeveloptool`）：
```bash
sudo apt-get update && sudo apt-get install -y android-sdk-platform-tools python3
```

### 2.1 连接原厂 Android 并检查硬件型号与当前槽位
进入原厂 Android（开启开发者选项 USB 调试，或连接网络 ADB），在主机执行：

```bash
adb connect <DEVICE_IP>:5555   # 若使用双公头 USB-A 线直连 OTG 口，可跳过此步直接 adb root
adb root
adb shell id -u                # 必须输出 0

# 核对机型、硬件保护与当前槽位
adb shell getprop ro.product.model
adb shell getprop ro.boot.slot_suffix      # 正常原厂出厂应为 _b
adb shell getprop ro.boot.fuse.programmed  # 应为 1
```

> [!WARNING]
> **如果 `ro.boot.slot_suffix` 返回 `_a` 而不是 `_b`**：说明这台机器之前走过 OTA，当前 Android 在 `_a` 上！
> 请**先不要动 `boot_a`**，先通过 `dd if=/dev/block/by-name/boot_a of=/dev/block/by-name/boot_b` 或切换槽位确认 `_b` 能正常启动 Android 后，再把 Linux 刷入 `boot_a`。

### 2.2 拉取关键原厂分区备份（并提取 `resource.img` 模板）
在主机创建本地备份目录：
```bash
mkdir -p factory_backup artifacts

adb shell "dd if=/dev/block/by-name/boot_b bs=4M status=none" > factory_backup/boot_b.img
adb shell "dd if=/dev/block/by-name/misc bs=4M count=1 status=none" > factory_backup/misc.img
adb shell "dd if=/dev/block/mmcblk2 bs=1M count=8 status=none" > factory_backup/mmcblk2_first8M_idbloader_gpt.img
for part in uboot_a uboot_b trust_a trust_b vbmeta_a vbmeta_b dtbo_a dtbo_b; do
    adb shell "dd if=/dev/block/by-name/${part} status=none" > "factory_backup/${part}.img"
done

# 确认原厂 boot_b 完整性并生成 SHA256 记录
sha256sum factory_backup/*.img > factory_backup/LOCAL_BACKUP.sha256
```

从你刚备份的 `factory_backup/boot_b.img` 中提取 Rockchip `RSCE` 二阶段模板（打包 Linux 引导镜像必需）：
```bash
python3 scripts/extract_resource_from_boot.py factory_backup/boot_b.img -o artifacts/extracted_resource.img
```

---

## 3. 获取或编译内核、补丁、固件与引导镜像

你有两条路线可以二选一：

### 路线 A：直接使用朋友分享的 `artifacts/` 成品包（最快）
如果你朋友把本地 `artifacts/` 文件夹私下发给了你（因版权原因不在 GitHub 公开托管，见 [LICENSES_AND_COPYRIGHT.md](../LICENSES_AND_COPYRIGHT.md)），先校验完整性和哈希：
```bash
cd artifacts && sha256sum -c SHA256SUMS && cd ..
```
其中核心产出物说明：
* `Image-6.12.117-cloudbook`：已合入当前板级/驱动补丁序列的 ARM64 内核
* `rk3568-ecloudbook-air-24rtw-v29.dtb`：eMMC 版设备树；U 盘首测用 `v28-usb2-pm-fix.dtb`
* `modules-6.12.117-cloudbook.tar.gz`：完整模块树（含 0013 的 `mt7663s`）
* `firmware/mt7663_n9_v3.bin` & `firmware/mt7663pr2h.bin`：MT7663S 无线/蓝牙固件
* `initramfs-6.12.117-cloudbook-emmc.img`：Debian/Armbian initramfs（eMMC 与 U 盘共用）
* `boot_linux_612_v28_usb.img` / `boot_linux_612_v29_emmc.img`：已打好的 U 盘/eMMC 引导镜像

---

### 路线 B：从 GitHub 仓库从零完整编译（推荐开发者）

#### 3.1 安装构建依赖
```bash
sudo apt-get update
sudo apt-get install -y \
    clang lld llvm make bc bison flex libssl-dev libelf-dev \
    device-tree-compiler u-boot-tools python3 rsync xz-utils curl
```

#### 3.2 准备 Linux `6.12.117` 主线源码
```bash
curl -fLO https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.117.tar.xz
mkdir -p linux-6.12
tar -xf linux-6.12.117.tar.xz -C linux-6.12 --strip-components=1
```

#### 3.3 一键应用补丁并编译内核、模块与 DTS
仓库内 `mainline-6.12/patches/` 已集成全部必需补丁（`0001` SR9900 USB 网卡、`0003` VPLL `659MHz`、`0005`~`0007`+`0011`+`0012` MT7663S Wi-Fi/蓝牙、NetworkManager 兼容与 2.4G↔5G 切频修复、`0008` 南芯 SC2021 PD + BQ25700 充电、`0009`+`0010` RK3568 Naneng eDP PHY 与 BOE NV140WUM-N44 屏幕）：

* **先编译 U 盘首测版 DTS（`v28`，引导参数 `root=LABEL=ROOTFS`）**：
  ```bash
  BUILD_MODULES=1 \
  DTS_SRC="$PWD/mainline-6.12/rk3568-ecloudbook-air-24rtw-v28-usb2-pm-fix.dts" \
  ./build_kernel_612.sh
  ```
*(若后续要迁移到内置 eMMC，再不带 `DTS_SRC` 变量执行一次 `./build_kernel_612.sh` 即可默认编译 `v29` `root=LABEL=EMMC_ROOTFS` 的 DTB。)*

构建完成后会产出：
* `kernel_build/out-6.12/arch/arm64/boot/Image`
* `kernel_build/out-6.12/arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dtb`

#### 3.4 准备 MT7663S 无线 / 蓝牙固件
Linux `6.12` 主线 `mt7663s` 驱动加载以下两个固件文件：
* `/lib/firmware/mediatek/mt7663_n9_v3.bin`
* `/lib/firmware/mediatek/mt7663pr2h.bin`

请通过以下任一合规渠道获取：
1. 在目标机联网后安装 Debian 非自由固件或从上游 `linux-firmware` 仓库（遵循 `LICENCE.mediatek`）提取上述两个文件；
2. 或向持有同款设备的朋友点对点拷贝。

---

## 4. 阶段一：制作 USB 3.0 启动 U 盘（推荐首测，不改原厂数据）

### 4.0 最快路线：直接写预构建 USB 镜像

`artifacts/rootfs-debian13-usb.ext4` 是整盘 ext4 的 Debian 13 根文件系统，卷标为 `ROOTFS`，包含 6.12.117 模块、MT7663 固件和 `cloudbook` 账户。U-Boot 从 eMMC 的 A 槽加载 Linux，initramfs 再挂载 U 盘上的 `/dev/sda`。这份镜像的本地登录界面是文本控制台。

```bash
# Linux 主机：先核对 U 盘盘符，别写错主机硬盘
lsblk -dpo NAME,SIZE,MODEL
sudo umount /dev/sdX* 2>/dev/null || true
sudo dd if=artifacts/rootfs-debian13-usb.ext4 of=/dev/sdX bs=4M status=progress conv=fsync
sync

# macOS 主机：diskutil list 找到 external physical disk，卸载后写 rdisk
diskutil list
diskutil unmountDisk /dev/diskN
sudo dd if=artifacts/rootfs-debian13-usb.ext4 of=/dev/rdiskN bs=4m
sync
```

写完后 U 盘整盘卷标应为 `ROOTFS`：

```bash
sudo e2label /dev/sdX        # 应输出 ROOTFS
```

> 默认账户：`cloudbook` / `cloudbook`，`root` / `cloudbook`。首启后第一件事就是改密码。
>
> **整盘 ext4 的 initramfs 兼容性**：旧 initramfs 只扫描 `/dev/sd*[0-9]`（分区），而本镜像无分区表、只有 `/dev/sda`，会找不到 root 并掉进 rescue shell。当前 `artifacts/initramfs-6.12.117-cloudbook-emmc.img` 已改为同时扫描 `/dev/sd*`，`boot_linux_612_v28_usb.img` 也已用该 initramfs 重打。旧镜像不要再用于整盘 U 盘。
>
> **插口**：U 盘必须插 host USB-A 口；Type-C 旁边的 USB-A 是 `dwc3@fcc00000` OTG，Android 下是 device 模式（adb/rkdeveloptool），U 盘不会枚举成 `sda`。
>
> 镜像 `/root/` 已放入 `misc_commit_slot_a.img`、`misc_restore_slot_b.img` 和 `boot_linux_612_v29_emmc.img`，首启后可直接切换永久槽或做 eMMC 迁移。
>
> 4.1~4.5 是手工 Armbian 路线；用 4.0 的预构建镜像可以直接跳到第 5 节。

### 4.1 手工路线：下载并写入 Armbian Debian 13 (Trixie) 基础镜像
在主机下载任意 RK3568 / `rock-3a` 的 Armbian Trixie 镜像（例如 `Armbian_26.8.1_Rock-3a_trixie_current_6.18.44_minimal.img.xz`），插上你的 U 盘（假设识别为 `/dev/sdX`，**请务必先用 `lsblk` 核对盘符，千万别写错主机硬盘！**）：

```bash
lsblk -dpo NAME,SIZE,MODEL
xz -dc Armbian_*_Rock-3a_trixie_*.img.xz | sudo dd of=/dev/sdX bs=4M status=progress conv=fsync
sudo partprobe /dev/sdX
```

### 4.2 修改根分区卷标为 `ROOTFS` 并安装驱动/固件/配置
```bash
# 查看 U 盘上的主 ext4 分区（通常为 /dev/sdX1）
lsblk -f /dev/sdX
sudo e2fsck -fy /dev/sdX1
sudo e2label /dev/sdX1 ROOTFS

sudo mkdir -p /mnt/cb_usb
sudo mount /dev/sdX1 /mnt/cb_usb
```

1. **清理镜像自带的旧模块并安装 6.12.117 内核模块**：
   * 若自行编译（路线 B）：
     ```bash
     sudo rm -rf /mnt/cb_usb/lib/modules/*
     sudo make -C linux-6.12 O="$PWD/kernel_build/out-6.12" \
         ARCH=arm64 LLVM=1 INSTALL_MOD_STRIP=1 INSTALL_MOD_PATH=/mnt/cb_usb modules_install
     ```
   * 若使用 `artifacts/` 成品包（路线 A）：
     ```bash
     sudo rm -rf /mnt/cb_usb/lib/modules/*
     sudo tar -xzf artifacts/modules-6.12.117-cloudbook.tar.gz -C /mnt/cb_usb/
     sudo depmod -b /mnt/cb_usb 6.12.117-cloudbook
     ```

2. **放入 MT7663S 固件与板级配置覆盖层**：
   ```bash
   sudo mkdir -p /mnt/cb_usb/lib/firmware/mediatek
   sudo cp /path/to/mt7663_n9_v3.bin /path/to/mt7663pr2h.bin /mnt/cb_usb/lib/firmware/mediatek/

   # 拷贝背光权限规则、触控板点击、LightDM 会话、合盖深睡配置
   sudo cp -a rootfs_overlay/etc/. /mnt/cb_usb/etc/
   ```

3. **修正 `/etc/fstab` 并屏蔽 Armbian 默认禁休眠配置**：
   ```bash
   sudo sed -i -E 's|^UUID=[^ ]+ / |LABEL=ROOTFS / |' /mnt/cb_usb/etc/fstab
   sudo sed -i '\|/boot|s|^|#|' /mnt/cb_usb/etc/fstab
   sudo rm -f /mnt/cb_usb/etc/systemd/sleep.conf.d/00-disable.conf
   ```

4. **提取供内核打包用的 `initramfs`**：
   > **注意**：`pack_boot_linux.py` 需要原始 gzip 压缩的 `initrd.img`（魔数 `1f 8b`），**不能**直接传带有 64 字节 U-Boot 头的 `uInitrd`！
   ```bash
   if ls /mnt/cb_usb/boot/initrd.img-* >/dev/null 2>&1; then
       cp /mnt/cb_usb/boot/initrd.img-* artifacts/initramfs-6.12.117-cloudbook-usb.img
   else
       # 若镜像里只有 uInitrd，去掉前 64 字节 U-Boot 头
       dd if=/mnt/cb_usb/boot/uInitrd* of=artifacts/initramfs-6.12.117-cloudbook-usb.img bs=64 skip=1
   fi
   ```

5. **卸载 U 盘**：
   ```bash
   sync
   sudo umount /mnt/cb_usb
   ```

---

## 5. 阶段二：刷写 `boot_a` 与 `misc` 并首次开机验证

### 5.1 准备 U 盘引导镜像 `boot_linux_612_v28_usb.img`

`artifacts/boot_linux_612_v28_usb.img` 已经打好在案（`root=LABEL=ROOTFS`，v28 DTB）。只有在你改过内核/DTB 后才需要重打：

```bash
RAMDISK="$PWD/artifacts/initramfs-6.12.117-cloudbook-emmc.img" \
CMDLINE='console=tty0 console=ttyS2,115200n8 root=LABEL=ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 systemd.show_status=1 systemd.default_timeout_start_sec=15s loglevel=7 no_console_suspend' \
./build_boot_linux_612.sh "$PWD/artifacts/boot_linux_612_v28_usb.img"
```

### 5.2 生成“3 次试启动自动回退 Android”的 `misc` 镜像
```bash
python3 switch_ab_slot.py test_slot_a /tmp/misc_try_slot_a.img
```
该配置给 Linux A 槽三次试启动机会（`15/3/0`），Android B 槽已标记成功（`14/0/1`）。U-Boot 每次选中 A 才扣一次。如果 Linux 未把 A 标记为成功，次数用尽后的下一次重启或上电就会选择 B。Linux 正在运行时，黑屏本身不会触发重启。能登录 Linux 时，可以正常执行 `reboot`。

### 5.3 写入 `boot_a` 与 `misc`（任选 ADB 或 Rockusb 方式）

#### 方式 5A：在 Android 下通过 `adb root` 刷写
```bash
sha256sum artifacts/boot_linux_612_v28_usb.img /tmp/misc_try_slot_a.img

adb push artifacts/boot_linux_612_v28_usb.img /data/local/tmp/boot_linux.img
adb push /tmp/misc_try_slot_a.img /data/local/tmp/misc_try.img

adb shell "dd if=/data/local/tmp/boot_linux.img of=/dev/block/by-name/boot_a bs=4M conv=fsync && sync"
adb shell "dd if=/data/local/tmp/misc_try.img of=/dev/block/by-name/misc bs=4M conv=fsync && sync"

# 强制回读校验哈希，确认完全一致再重启！
IMG_SIZE=$(stat -c %s artifacts/boot_linux_612_v28_usb.img)
adb shell "head -c $IMG_SIZE /dev/block/by-name/boot_a | sha256sum"
adb shell "sha256sum /dev/block/by-name/misc"
```
核对两端 `sha256sum` 一致后，把制作好的 **USB 启动 U 盘插到笔记本的 host USB-A 口**（不是 Type-C 旁边那个 OTG 刷机口），执行：
```bash
adb reboot
```

#### 方式 5B：用双公头线连 Type-C 旁边的 USB-A 口，通过 `rkdeveloptool` 刷写
若先在原厂 Android 执行 `adb reboot loader`（或接串口上电连按 `Ctrl+C` 再执行 `rockusb 0 mmc 0`）：
```bash
rkdeveloptool ld
rkdeveloptool wlx boot_a artifacts/boot_linux_612_v28_usb.img
rkdeveloptool wlx misc /tmp/misc_try_slot_a.img
rkdeveloptool rd
```

### 5.4 验证首次进入主线 Linux
屏幕亮起并进入 Armbian 控制台/桌面后（首次 Armbian 会提示设置 `root` 密码与普通用户账户）：
```bash
uname -a                  # 应显示 6.12.117-cloudbook
findmnt /                 # 应挂载在 /dev/sda1 (LABEL=ROOTFS)
cat /proc/cmdline         # 应含 androidboot.slot_suffix=_a
```

---

## 6. 阶段三（可选）：迁移 Rootfs 到内置 32GB eMMC（免插 U 盘日常使用）

> [!CAUTION]
> 此步骤会格式化内置 eMMC 的 `userdata` 分区 (`/dev/mmcblk0p20`，约 22 GiB)，原厂 Android 的用户数据会被清空（系统分区 `boot_b`/`super` 保留不变）。**请务必先确认不再需要 Android 内数据或先做好整分区备份！**

### 6.1 准备 `v29` eMMC 引导镜像
* **路线 A**：直接使用 `artifacts/boot_linux_612_v29_emmc.img`；
* **路线 B**：在主机用默认 `v29` 配置编译 DTB 并打包：
  ```bash
  ./build_kernel_612.sh
  ./build_boot_linux_612.sh "$PWD/artifacts/boot_linux_612_v29_emmc.img"
  ```

### 6.2 在 U 盘启动的 Linux 上克隆系统到 `/dev/mmcblk0p20`
将 `boot_linux_612_v29_emmc.img` 与锁定 `slot_a` 用的 `misc` 拷入正在运行的 U 盘 Linux（如 `/tmp/`），然后在笔记本本地终端执行：

```bash
# 1. 确认当前运行在 U 盘 Rootfs 上（不能已经挂载 /dev/mmcblk0p20）
findmnt -n -o SOURCE /
sudo umount /dev/mmcblk0p20 2>/dev/null || true

# 2. （可选备份）把原厂 userdata 备份到另一块移动硬盘或通过网络传回主机
# sudo dd if=/dev/mmcblk0p20 bs=4M status=progress | zstd -T0 -3 -o /path/to/userdata.img.zst

# 3. 格式化 /dev/mmcblk0p20 为 LABEL=EMMC_ROOTFS 并克隆当前 U 盘系统
sudo mkfs.ext4 -F -L EMMC_ROOTFS /dev/mmcblk0p20
sudo mkdir -p /mnt/emmc
sudo mount /dev/mmcblk0p20 /mnt/emmc

sudo rsync -axHAWX --numeric-ids \
    --exclude='/dev/*' --exclude='/proc/*' --exclude='/sys/*' \
    --exclude='/tmp/*' --exclude='/run/*' --exclude='/mnt/*' \
    --exclude='/media/*' --exclude='/lost+found' \
    / /mnt/emmc/

sudo mkdir -p /mnt/emmc/{dev,proc,sys,tmp,run,mnt,media}
sudo chmod 1777 /mnt/emmc/tmp

# 4. 更新 eMMC 内的 /etc/fstab
sudo tee /mnt/emmc/etc/fstab >/dev/null <<'EOF'
LABEL=EMMC_ROOTFS / ext4 defaults,noatime,commit=120,errors=remount-ro 0 1
tmpfs /tmp tmpfs defaults,nosuid 0 0
EOF

sync && sudo umount /mnt/emmc

# 5. 将 v29 引导镜像刷入 boot_a (/dev/mmcblk0p11) 并回读校验
sudo dd if=/tmp/boot_linux_612_v29_emmc.img of=/dev/mmcblk0p11 bs=4M conv=fsync status=progress
sync
sha256sum /tmp/boot_linux_612_v29_emmc.img
head -c "$(stat -c %s /tmp/boot_linux_612_v29_emmc.img)" /dev/mmcblk0p11 | sha256sum
```

### 6.3 试跑验证并锁定 `Slot A`
1. 拔掉 U 盘，执行 `sudo reboot`，确认无 U 盘也能直接进入桌面且 `findmnt /` 显示 `/dev/mmcblk0p20`。
2. **重要（防止重启几次后退回 Android）**：原厂 SPL + U-Boot 每次冷启动会扣减 `2` 次 `tries_remaining`！确认 eMMC 启动稳定后，在主机生成并传到板子上写入永久锁定 `Slot A` 的 `misc`（`priority=15, tries=0, successful=1`）：
   ```bash
   # 在主机生成：
   python3 switch_ab_slot.py commit_slot_a /tmp/misc_commit_a.img
   # 传到笔记本写入 /dev/mmcblk0p6：
   sudo dd if=/tmp/misc_commit_a.img of=/dev/mmcblk0p6 bs=4M conv=fsync && sync
   ```
   写入后，`reboot` 与日常开关机都会稳定停留在 Linux，不再回退 Android。

---

## 7. 系统初始化与音视频/桌面调优

进系统后推荐按以下清单完成桌面环境与硬件解码设置：

1. **避免 `apt upgrade` 安装无用发行版内核包**
   * 本机内核位于未签名校验的 `boot_a`（`Image`），系统更新时若意外装入 `linux-image-current-rockchip64`，不会生效且白占空间：
     ```bash
     sudo apt-mark hold linux-image-current-rockchip64 linux-dtb-current-rockchip64 linux-u-boot-rock-3a-current 2>/dev/null || true
     ```
2. **桌面环境（LightDM + i3wm）**
   * 如使用轻量桌面（如 `i3`, `lightdm`, `lightdm-gtk-greeter`, `picom`, `alacritty`, `rofi`），确认 `rootfs_overlay/etc/lightdm/lightdm.conf.d/50-cloudbook.conf` 与 `90-backlight.rules` 已生效，且常用用户已加入 `video`、`render`、`audio`、`input`、`netdev`、`plugdev` 组：
     ```bash
     sudo usermod -aG video,render,audio,input,netdev,plugdev <你的用户名>
     ```
3. **开启 1080p H.264/VP8/MPEG-2 硬件解码**
   * 本机 `hantro_vpu`（`/dev/video1`）在 Linux `6.12` 下提供 V4L2 Stateless 硬解。`vlc` 与 `mpv`（默认走 FFmpeg）无法调用它；请使用 **GStreamer + Parole**：
     ```bash
     sudo apt-get update
     sudo apt-get install -y \
         gstreamer1.0-plugins-bad gstreamer1.0-plugins-good gstreamer1.0-plugins-base \
         gstreamer1.0-x gstreamer1.0-gl gstreamer1.0-tools gstreamer1.0-plugins-base-apps \
         parole
     ```
   * **必做配置**：`parole` 默认尝试创建不存在的 `autoimagesink` 后会退回 CPU 软渲染 `ximagesink`，务必在桌面用户下把视频输出设为 `xvimagesink`：
     ```bash
     xfconf-query -c parole -p /video/videosink -n -t string -s xvimagesink
     ```
   * 命令行测试硬解可用：`gst-play-1.0 <视频文件或http流>`。
4. **音频默认输出保存**
   * 首次进入系统若内置扬声器无声，运行 `alsamixer` 打开 `Playback Mux` (`SPK`) 与 `Speaker Switch`，再执行 `sudo alsactl store` 固化。

---

## 8. 常见问题与还原原厂 Android

1. **开机忽然回到 Android 了怎么办？**
   * 原因通常是未执行 `commit_slot_a`，或试启动时连续异常断电消耗完了 `tries_remaining`。
   * 无需重刷 `boot_a`，在 Android 下用 `adb root` 把 `misc_locked_slot_a.img`（由 `switch_ab_slot.py commit_slot_a` 生成）写回 `/dev/block/by-name/misc` 并 `adb reboot` 即可秒回 Linux。
2. **如何彻底切回 / 还原原厂 Android 11？**
   * **仅切换启动槽回 Android**（保留 eMMC Linux 数据，随时可再切回）：在 Linux 下把 `switch_ab_slot.py restore_slot_b` 生成的镜像写入 `/dev/mmcblk0p6` 并重启（详见 [OTG_RECOVERY.md](OTG_RECOVERY.md) 第 6 节）。
   * **彻底整机恢复出厂**：用 stage 1 的 U 盘启动进入 Linux，运行仓库内的 [restore_factory_android.sh](../scripts/restore_factory_android.sh) 并传入之前完整冷备份目录，即可恢复 `boot_a`、`metadata`、`cache`、`userdata` 与 `misc`。
3. **黑屏/卡死救砖**：
   * 用双公头 USB-A 线连 **Type-C 旁边的 USB-A 口** 与电脑，接主板 `UART2`（`115200 8N1`）上电连按 `Ctrl+C` 进入 U-Boot Rockusb/BootROM 下载模式，通过 `rkdeveloptool` 写入备份分区（详见 [OTG_RECOVERY.md](OTG_RECOVERY.md)）。
