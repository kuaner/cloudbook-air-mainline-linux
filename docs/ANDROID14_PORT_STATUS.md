# Android 14 移植状态与刷机 Runbook

> **日期**：2026-11-20  
> **目标机**：ZTE / 中国移动 CloudBook Air 14" `CT2000-24RTW`（主板 `W620D V12.0`，RK3568B2，4 GB / 32 GB）  
> **目标布局**：A 槽 = Android 14，B 槽 = 原厂 Android 11；**B 槽不改**。  
> **实验状态**：内核和 `boot.img` 已完成。`super` 的 A 逻辑分区、vendor overlay、真机验证还未完成。此方案与 Debian 共用 A 槽，日用双系统见 [7.3.5 刷机手册](FLASHING_GUIDE.md)。

---

## 0. 红线（刷机前必读）

1. **绝不写** eMMC 前 8 MiB（`idbloader`）、`uboot_a/b`、`trust_a/b`、`security`。
2. **绝不整包刷** FriendlyElec 的 `android-14-images-20251222.tgz`：它含 `idbloader.img`、`uboot.img`、`parameter.txt`，会覆盖签名 bootloader 和本机 GPT。
3. **绝不用** Friendly 的 `super.img` 整包写 `p19`：它的 B 槽逻辑分区是 0 长度，会清掉工厂 Android 11。
4. **不动 B**：只写 `boot_a`、`misc` 和 `super` 里的 A 逻辑分区；`boot_b`、B 的 `system_b/vendor_b/...` 保持原样。
5. 没有 adb root 时，不要在 Android shell 里 `dd` 块设备；改用 U 盘 Linux（root）或 loader。

---

## 1. 当前状态

| 项 | 状态 | 说明 |
| :--- | :--- | :--- |
| Friendly Android 14 镜像解剖 | ✅ | 整盘 GPT 包；内核 `6.1.141-ab62512222`；`super` 只有 A 槽；RSCE 37 个 nanopi DTB；vendor 有 AIDL Wi-Fi/BT、external camera、health、composer3 |
| 板级 DTS 迁到 Rockchip 6.1 | ✅ | `rk3568-ecloudbook-air-24rtw.dts` 在 6.1.172 上编译通过；DTB **179,478 B** |
| Android 14 内核 | ✅ | Rockchip 6.1.172 BSP + Friendly 6.1.141 配置；`Image` **35,637,256 B** |
| 内置驱动 | ✅ | MT7663S、BT_MTKSDIO、BATTERY_SBS、BQ25700、SC2021、CFG80211/MAC80211、eDP/Naneng、Mali Bifrost、RK817、ext4/f2fs/dm-verity/binderfs |
| `boot.img` | ✅ | **66.65 MiB**，`artifacts/android14/boot_android14_w620d_v1.img`；我们的内核 + Friendly Android 14 ramdisk + 工厂 RSCE（entry 0/25 = 我们的 DTB） |
| `super` A 逻辑分区 | ❌ | 还没写；所以**只刷 `boot_a` 不会得到 Android 14 系统** |
| vendor overlay | ❌ | MT7663 固件/audio/sensor 配置还没打包；当前 vendor 是 Friendly R5S 配置 |
| 真机验证 | ❌ | eDP/触控/音频/Wi-Fi/BT/电池/PD 都未在 Android 14 下验证 |
| W103D 0013 驱动 | ⚠️ | 0013 是 6.12 API；6.1 直接套需要回移 mt76 核心 API，当前用 stock mt7663s |
| adb 权限 | ✅ | B 槽已通过无线 ADB 取得 root；A 槽写入仍未授权 |

---

## 2. 产物

`artifacts/android14/`（`artifacts/` 已被 `.gitignore` 排除，不要提交二进制）：

| 文件 | SHA-256 |
| :--- | :--- |
| `boot_android14_w620d_v1.img` | `2ddb3db7206461cae2ec67dbdb50cf41a924d2ba8bbcf7a79a4bd593cbfcee04` |
| `Image-6.1.172-cloudbook` | `e3183ca51d0303708460bcb94b8376aaf773afbf332d837ccf85553f860d6942` |
| `rk3568-ecloudbook-air-24rtw-6.1.dtb` | `d107f5b11de2b915c7ab16705f39863491185c42782fe2da7aba69aeefc51450` |
| `rk3568-ecloudbook-air-24rtw-6.1.dts` | `78ca3739ce0edb4179105f039d7d866f46775f30c8ce680045c466e2155cd63e` |
| `sc2021_charger-6.1.c` | `16b491ae4117f5e470b68e7f230959f2919a4a9bb1577e71349be94ac850a383` |
| `config-6.1.172-android14` | `d6fd5e08e1490f2fe29ca593e44ce27a63df15f586127ad4bb52cea744dfdc22` |
| `android14-6.1-board-drivers.patch` | `850599ce88edbce6e961768197efd2139ca1bd87c6f7b2c81b0c531b93284009` |

Friendly 原包：`~/Downloads/android-14-images-20251222.tgz`  
A 逻辑分区镜像（解包后）：`/tmp/fee-parts/*.img`（可以由 `super.img` 用 `lpunpack` 重新解出）  
Friendly Android 14 ramdisk：`/tmp/fee-ramdisk.img`（从 Friendly `boot.img` 提取）  
工厂 RSCE 模板：`artifacts/extracted_resource.img`

---

## 3. 内核与驱动

- 基线：Rockchip `develop-6.1`（6.1.172） + 从 Friendly 镜像 `IKCFG` 提取的 6.1.141 完整 `.config`。
- 板级改动：`android14-6.1-board-drivers.patch`（板级 DTS、SC2021 驱动、Kconfig/Makefile）。
- SC2021 已适配 6.1：`.usb_types` 改成数组 + `.num_usb_types`，`i2c_driver.probe` 增加 `id` 参数。
- MT7663S/BT 用 6.1 原生 `mt76/mt7615` 的 mt7663s + `btmtksdio`，**不是** 6.12 的 W103D 隔离驱动。
- 关键 `=y`：`MT7663S`、`BT_MTKSDIO`、`BATTERY_SBS`、`CHARGER_BQ25700`、`CHARGER_SC2021`、`CFG80211`、`MAC80211`、`PHY_ROCKCHIP_NANENG_EDP`、`DRM_ROCKCHIP`、`MALI_BIFROST`。

### 重建内核

```bash
git clone --depth 1 -b develop-6.1 https://github.com/rockchip-linux/kernel.git linux-6.1.172
cd linux-6.1.172
git apply ../android14-6.1-board-drivers.patch
cp ../config-6.1.172-android14 .config
make ARCH=arm64 CROSS_COMPILE= olddefconfig
make ARCH=arm64 CROSS_COMPILE= -j8 Image dtbs
```

`android14-6.1-board-drivers.patch` 是针对当前 6.1.172 HEAD 生成的；上游 HEAD 移动后可能需要 fuzz/手工。

### 重打 boot.img

```bash
python3 scripts/pack_boot_android14.py \
  --kernel Image-6.1.172-cloudbook \
  --ramdisk /tmp/fee-ramdisk.img \
  --dtb rk3568-ecloudbook-air-24rtw-6.1.dtb \
  --resource-template artifacts/extracted_resource.img \
  --output artifacts/android14/boot_android14_w620d_v1.img
```

脚本会把 DTB 追加到工厂 RSCE，并把 entry 0 / w620d entry 指向它，更新每项 SHA-1；boot v2 header 用 Friendly 的 cmdline + `console=ttyS2`。

---

## 4. 分区事实（为什么不能“只刷 boot_a”）

| GPT | 内容 |
| :--- | :--- |
| p11 / p12 | `boot_a` / `boot_b`：只放 kernel + ramdisk + DTB |
| p19 | **一个共享的 `super`（5.0 GiB）**：A/B 的 system/vendor/product/... 都是里面的逻辑分区 |
| p20 | `userdata`（原厂布局约 22 GiB，当前双系统实机为 8 GiB） |

- Linux 只写 `boot_a` 能启动，是因为它的 rootfs 在 U 盘/`userdata`，不依赖 `super`。
- Android 14 的 system/vendor 在 `super` 的 A 逻辑分区里；**只写 `boot_a` 没有 Android 14 系统**。
- 没有 fastboot；U-Boot/Rockusb 只能按 GPT 分区写，不能只写 `super` 里的 A 逻辑分区。
- 因此 A 槽 Android 14 = `boot_a` + `super` 的 A 逻辑分区；B 的数据不动。

---

## 5. 刷机 Runbook

> **串口/热键事实**：本机 U-Boot 的串口中断是 `Ctrl+C`（`uboot.img` 字符串 `Hit key to stop autoboot('CTRL+C')`，`bootdelay=0`）。无 UART 时，按住 **Volume Up**（U-Boot DTB `adc-keys/volumeup-key`，`label="recovery"`）再按电源，是否出现 Loader/Maskrom 需以 `rkdeveloptool ld` 实测为准；这条 ADC 路径尚未在真机确认。


### Phase 0：只读检查 + 备份（不写）

```bash
adb devices
adb shell id                 # 期望 uid=0(root)；如果是 2000(shell) 看 adb root
adb root
adb shell id
adb shell getenforce         # Permissive 最好
adb shell ls -l /dev/block/by-name/boot_a /dev/block/by-name/misc /dev/block/by-name/super
```

备份（root；如果 `adb root` 不可用则用 `su`）：

```bash
adb exec-out su -c 'dd if=/dev/block/by-name/boot_a bs=4M' > factory_boot_a.img
adb exec-out su -c 'dd if=/dev/block/by-name/misc   bs=4M' > factory_misc.img
# super 元数据（前 4 MiB 足够覆盖 4K/8K/12K 的 geometry/metadata 槽）
adb exec-out su -c 'dd if=/dev/block/by-name/super bs=4M count=1' > factory_super_meta.img
# 可选：完整 super（5 GB，确认主机有空间）
adb exec-out su -c 'dd if=/dev/block/by-name/super bs=4M' > factory_super_full.img
```

记录 `lpdump`：

```bash
adb shell su -c 'lpdump /dev/block/by-name/super' | tee super_lpdump.txt
```

重点看：

1. A/B 现有分区大小（`system_a`、`vendor_a`、`product_a`、`system_ext_a`）；
2. `free` extent 有多少；
3. A group 的 `max_size`；
4. 有没有 `vendor_dlkm_a` / `system_dlkm_a` / `odm_dlkm_a`。

### Phase 1：写 A 逻辑分区（保留 B）

**推荐在 U 盘 Linux 里做**（`super` 未挂载，最安全），其次才是 adb root。

- U 盘 Linux：启动仓库已有 rootfs，`sudo lpdump /dev/mmcblk0p19`。
- 工具：`lpdump` / `lpadd` / `lpflash`（AOSP `partition_tools` 的 aarch64 静态构建；**目前还没编**，见 §7）。
- 空间够、A group 上限够：
  - 用 `lpadd` 只写 A 分区（`system_a`、`vendor_a`、`product_a`、`system_ext_a`、`vendor_dlkm_a`、`odm_a`、`odm_dlkm_a`、`system_dlkm_a`）。
  - 先看 `lpadd --help`；不要凭猜写 offset，不要 `dd` 到 super 的任意位置。
  - 如果 A 分区已存在且新镜像更大，`lpadd` 可能需要 resize/重建元数据；如果工具拒绝，停止，不要强写。
- 空间不够 / 元数据不能原地 resize：
  - 退到“双槽 super”：从设备现有 `super` 解出 B 逻辑分区，用 `lpmake` 做 A=Android14、B=现有 B，整包写 `p19`；B 内容不变但物理重写，必须确认电源稳定。

**绝对不要**：把 Friendly 的 A-only `super.img` 直接 `dd` 到 `p19`。

### Phase 2：写 `boot_a` + 设 A 槽

```bash
adb push artifacts/android14/boot_android14_w620d_v1.img /data/local/tmp/
adb shell su -c 'dd if=/data/local/tmp/boot_android14_w620d_v1.img of=/dev/block/by-name/boot_a bs=4M conv=fsync'
adb shell su -c 'sync'
```

设 A 槽（Android 11 的 `bootctl`；没有就用 `misc`）：

```bash
adb shell su -c 'bootctl set-active-boot-slot 0'   # A=0, B=1
adb reboot
```

若 `bootctl` 不存在，用仓库 `switch_ab_slot.py` 生成 A 优先、B 保持 successful 的 `misc`，再 `dd` 到 `/dev/block/by-name/misc`；**不要**把 B 的 `successful_boot` 清掉，B 要能回退。

### Phase 3：首次启动验证

- 串口 `115200 8N1`，期望 `Linux version 6.1.172...`。
- 内屏 eDP 亮、USB 键盘/触控板可用。
- `dmesg | grep -iE 'edp|panel|mt7663|btmtk|sc2021|bq25700|sbs'`
- `uname -r`、`wm size`、`ip link`、`bluetoothctl show`、`cat /sys/class/power_supply/*/uevent`。
- 失败：A 槽试启动计数耗尽后应回 B；或 `bootctl set-active-boot-slot 1` 切回 B。
- 还原 `boot_a`/`misc`：把 `factory_boot_a.img`/`factory_misc.img` `dd` 回对应分区。

### Phase 4：vendor overlay（Wi-Fi/BT/音频/电池）

当前 vendor 是 Friendly R5S 配置；如果首启后 Wi-Fi/BT/音频/电池不工作：

- MT7663 固件：把工厂 `vendor-extract/WIFI_RAM_CODE_MT7663.bin` 复制为
  `/vendor/etc/firmware/mediatek/mt7663_n9_v3.bin` 和 `mt7663_n9_rebb.bin`；
  `mt7663_patch_e2_hdr.bin` 复制为 `mt7663pr2h.bin` / `mt7663pr2h_rebb.bin`。
- 音频：Friendly 的 `audio_policy_configuration.xml` 已包含 Speaker/Headphones/Mic/HDMI；
  工厂 4.19 的 `audio_policy_configuration.xml` 可作对照；`mixer_paths.xml` 尚未定位。
- 电池/PD：内核已有 `sbs-battery` + `bq25700` + `sc2021`，`health-service.rockchip` 读 `/sys/class/power_supply`。
- 传感器 mxc6655 未启用；先不做。

---

## 6. 已知风险

1. **6.1.172 内核 vs 6.1.141 Friendly 用户态**：Mali/HAL 可能对 kbase 版本敏感；首启先验证 eDP/GPU/USB。
2. **vendor 配置是 R5S 路由器**：Wi-Fi/Audio/Battery 需要 overlay，不能指望开箱即用。
3. **W103D 0013 未回移**：Android 14 的 Wi-Fi 是 stock mt7663s，吞吐/切频行为未验证。
4. **`super` A 空间未知**：必须先 `lpdump`；不够就双槽 super，不要硬写。
5. **Android 14 未上机**：所有“能启动”的判断目前只是静态/打包验证。

---

## 7. 还没做的工具/脚本

- `lpdump/lpadd/lpflash` 的 aarch64 静态二进制：还没编译；明天先编或先用设备/ U 盘 Linux 自带 `lpdump`。
- Android 6.1 构建目前是在 `rkbuild` 容器里手工做的；源码改动已导出为
  `android14-6.1-board-drivers.patch`，但还没有 `build_kernel_android14.sh`。
- vendor overlay（固件/audio/传感器）还没打包成可刷镜像；先 adb push 验证，再决定是否重打 vendor。

---

## 8. Linux 侧

Android 14 实验使用独立的 Rockchip 6.1 BSP。当前 Debian 内核、桌面和配套 v30 通用镜像的构建见 [7.3.5 构建说明](../mainline-7.3/README.md)，6.12 构建见 [LTS 移植基线](../mainline-6.12/README.md)。
