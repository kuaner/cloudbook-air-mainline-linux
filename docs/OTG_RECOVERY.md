# CloudBook Air (W620D V12.0 / CT2000-24RTW) OTG 与双槽位恢复手册

> **适用范围**：ZTE / 中移云电脑笔记本 `CT2000-24RTW` (`W620D V12.0`, `RK3568B2`)  
> **硬件配置**：无外置 TF 卡槽，Type-C 口旁边的 USB-A 口为 OTG 刷机口 (`dwc3@fcc00000`)；双公头 USB-A 线直连主机。  
> **安全边界**：本机已启用 OTP 熔断安全启动（`fuse.programmed=1`、`androidboot.secureboot=1`）。`idbloader`（`RKSS`）与 `uboot_a/b`、`trust_a/b` 带硬件/RSA-2048 签名校验，**严禁使用未签名开源 SPL/U-Boot 覆盖前 8 MiB、`uboot_a/b`、`trust_a/b` 或 `security` 分区**。

---

## 1. 冷备份清单与校验

所有原始与阶段关键分区备份位于 `factory_backup/`，可使用校验清单确认完整性：

```bash
cd .
sha256sum -c factory_backup/MANIFEST.sha256
```

关键备份文件说明（节点编号：`boot_b` / Android 下为 `/dev/block/mmcblk2p*`，Linux 6.12 下为 `/dev/mmcblk0p*`）：

| 备份文件 | 对应内容 | 用途 |
| :--- | :--- | :--- |
| `mmcblk2_first8M_idbloader_gpt.img` | eMMC 前 8 MiB（GPT + `RKSS` `idbloader`） | 极危兜底（仅在 LBA 0~16383 受损时按 `RKSS` 原样恢复） |
| `mmcblk0boot0.img` | eMMC `boot0` 硬件分区（4 MiB） | 原始全 0 / 硬件 Boot 分区快照 |
| `mmcblk0_gpt.sfdisk` / `mmcblk0_last1M_backup_gpt.bin` | 主/备 GPT 分区表 | 修复 GPT 结构 |
| `boot_b.img` | 完整原厂 Android 11 `boot_b`（160 MiB） | 恢复原厂 Android 内核与 `ramdisk`/`resource` |
| `boot_a_4.19_verified_20261112.img` | 原厂 4.19 内核 Linux 引导快照 | 回退对比 |
| `boot_a_linux612_v23.img` | 主线 Linux 6.12 `v23` 引导快照（160 MiB） | 备份参考（当前 `boot_a` 运行 `v24`，见工作区 `boot_linux_612_v24.img`） |
| `uboot_a.img`, `uboot_b.img`, `trust_a.img`, `trust_b.img`, `vbmeta_a.img`, `vbmeta_b.img`, `dtbo_a.img`, `dtbo_b.img`, `security.img`, `logo.img`, `baseparameter.img`, `zteparameter.img`, `metadata.img`, `backup.img.zst`, `cache.img.zst`, `super.img.zst` | 原厂全套系统与配置分区 | 完整恢复原厂 Android 系统 |
| `misc_locked_slot_a.img` | 已锁定 `slot_a` (`boot_a`) 的 4 MiB `misc` 镜像 | 快速切换回 Linux（`0x0` 与 `0x4000` BCB 已清零） |
| `misc.img` | 原厂抓包时 `misc` 快照 | **注意**：该备份 `0x4000` 含 `boot-recovery` 字样，切回 Android **请使用 `switch_ab_slot.py restore_slot_b` 新生成 clean 镜像**，勿直接盲刷原始 `misc.img` |

---

## 2. eMMC 实盘 GPT 扇区表（512 字节/扇区）

> **勘误警告**：早期 `README.md` 曾误记 `misc` 为 `0x18000`；以下严格依据真机导出的 `factory_backup/mmcblk0_gpt.sfdisk`（`last-lba: 61472734`）：

| 分区 | 名称 | 起始 LBA（十进制 / 十六进制） | 扇区数（大小） | Linux 设备 | Android 设备 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `p1` | `security` | `8192` (`0x2000`) | `8192` (4 MiB) | `/dev/mmcblk0p1` | `/dev/block/mmcblk2p1` |
| `p2` | `uboot_a` | `16384` (`0x4000`) | `8192` (4 MiB) | `/dev/mmcblk0p2` | `/dev/block/mmcblk2p2` |
| `p3` | `uboot_b` | `24576` (`0x6000`) | `8192` (4 MiB) | `/dev/mmcblk0p3` | `/dev/block/mmcblk2p3` |
| `p4` | `trust_a` | `32768` (`0x8000`) | `8192` (4 MiB) | `/dev/mmcblk0p4` | `/dev/block/mmcblk2p4` |
| `p5` | `trust_b` | `40960` (`0xa000`) | `8192` (4 MiB) | `/dev/mmcblk0p5` | `/dev/block/mmcblk2p5` |
| `p6` | **`misc`** | **`49152` (`0xc000`)** | `8192` (4 MiB) | `/dev/mmcblk0p6` | `/dev/block/mmcblk2p6` |
| `p7` | `dtbo_a` | `57344` (`0xe000`) | `8192` (4 MiB) | `/dev/mmcblk0p7` | `/dev/block/mmcblk2p7` |
| `p8` | `dtbo_b` | `65536` (`0x10000`) | `8192` (4 MiB) | `/dev/mmcblk0p8` | `/dev/block/mmcblk2p8` |
| `p9` | `vbmeta_a` | `73728` (`0x12000`) | `2048` (1 MiB) | `/dev/mmcblk0p9` | `/dev/block/mmcblk2p9` |
| `p10` | `vbmeta_b` | `75776` (`0x12800`) | `2048` (1 MiB) | `/dev/mmcblk0p10` | `/dev/block/mmcblk2p10` |
| `p11` | **`boot_a`** | **`77824` (`0x13000`)** | `327680` (160 MiB) | `/dev/mmcblk0p11` | `/dev/block/mmcblk2p11` |
| `p12` | **`boot_b`** | **`405504` (`0x63000`)** | `327680` (160 MiB) | `/dev/mmcblk0p12` | `/dev/block/mmcblk2p12` |
| `p13` | `backup` | `733184` | `3145728` (1.5 GiB) | `/dev/mmcblk0p13` | `/dev/block/mmcblk2p13` |
| `p14` | `cache` | `3878912` | `786432` (384 MiB) | `/dev/mmcblk0p14` | `/dev/block/mmcblk2p14` |
| `p15` | `metadata` | `4665344` | `32768` (16 MiB) | `/dev/mmcblk0p15` | `/dev/block/mmcblk2p15` |
| `p16` | `baseparameter` | `4698112` | `2048` (1 MiB) | `/dev/mmcblk0p16` | `/dev/block/mmcblk2p16` |
| `p17` | `logo` | `4700160` | `65536` (32 MiB) | `/dev/mmcblk0p17` | `/dev/block/mmcblk2p17` |
| `p18` | `zteparameter` | `4765696` | `65536` (32 MiB) | `/dev/mmcblk0p18` | `/dev/block/mmcblk2p18` |
| `p19` | `super` | `4831232` | `10493952` (~5.0 GiB) | `/dev/mmcblk0p19` | `/dev/block/mmcblk2p19` |
| `p20` | `userdata` | `15325184` | `46147520` (~22.0 GiB) | `/dev/mmcblk0p20` | `/dev/block/mmcblk2p20` |

---

## 3. 进入 OTG 刷机模式与 Loader 限制说明

### 方式 A：U-Boot Rockusb / Loader 模式（首选，无需外部 MaskRom Loader）

当 `idbloader` 与原厂签名 `uboot_a/b` 完好时，直接由已签名的原厂 U-Boot 提供 USB 协议栈：

1. 用双公头 USB-A 线连接主机与笔记本 **Type-C 旁边的 USB-A 口**。
2. 任选一种方式进入 Loader 模式：
   - **OS 内重启进入**：在 Linux 或 Android root 下执行 `reboot loader`（Android 下 `adb shell reboot loader`）；
   - **串口热键中断**：接通 UART 控制台后上电/重启时连按 `Ctrl+C` 进入 U-Boot CLI，执行：
     ```text
     rockusb 0 mmc 0
     ```
     （也可执行 `ums 0 mmc 0` 将整块 eMMC 映射为主机块设备，效果同 `reboot ums`。）
   - **无 UART 按键入口**：U-Boot DTB 的 `adc-keys/volumeup-key` 标了 `label = "recovery"`。按住机身 **Volume Up**（或 Fn+Volume Up）再按电源，观察主机 `rkdeveloptool ld` 是否出现 `Loader`/`Maskrom`，或 `adb devices` 是否出现 recovery/sideload。
3. 主机确认识别（`rkdeveloptool ver 1.32`）：
   ```bash
   rkdeveloptool ld
   rkdeveloptool ppt
   ```

### 方式 B：MaskRom 模式与 Loader/官方刷机包实测限制

1. **官方刷机包 `W6XX_W52X-RK3568-T-ALLV2.25.20P06-Firmware.zip` 的实际内容**：
   - 经解包核查，该压缩包内含 `W6XX_W52X-RK3568-T-ALLV2.25.20P06-Firmware.tar.gz`，其内容为 Android OTA 升级包 `W6XX_W52X-RK3568-T-ALLV2.25.20P06-ota.zip`（含 `payload.bin`）、`config/install.sh` 及板载外设固件 `config/firmware/*`。
   - **它不是** 可直接喂给 `rkdeveloptool` 的全盘裸镜像或 MaskRom Loader 包，只能用于系统已能进入 Android/Recovery OTA 流程后的增量/全量升级，无法单独拯救 MaskRom 状态。
2. **提取自原厂 `/vendor/firmware/` 的 `firmware/firmware/update_loader.zip` (`RKLoader.bin`)**：
   - `RKLoader.bin`（457,152 字节）经 `rkdeveloptool unpack` 确认包含 `rk3568_ddr_1560MHz_v`（与板上 `idbloader` 内 DDR 初始化段字节一致）、`rk356x_usbplug_v1`、`FlashData`、`FlashBoot`。
   - **关键提示**：`RKLoader.bin` 的 `UsbHead` 与 `FlashHead` 头部魔数为 `RKNS`，而板上实际 `idbloader` 扇区 64 (`0x8000`) 头部为已熔断签名的 `RKSS`。在 `fuse.programmed=1` 的芯片上，MaskRom 执行 `rkdeveloptool db RKLoader.bin` **尚未在真机硬件上验证 BootROM 是否允许下载执行**。
   - 因此，后续一切刷写都**必须继续保留原厂 `idbloader` 与 `uboot_a/b`、`trust_a/b`**，优先通过 **Loader 模式 (`rockusb`) / UMS / Android ADB** 进行恢复。

---

## 4. `misc` 分区 A/B 槽位数据结构与常用切换命令

原厂 U-Boot 读取 `misc` 分区偏移 `0x800`（2048 字节处）的 32 字节 `AvbABData` 结构（末尾 4 字节为大端 CRC-32），并需同时保证 BCB 区域 `0x0000..0x0040` 与 `0x4000..0x4040` 为全 0：

- **锁定 `slot_a`（直接引导 Linux `boot_a`，跳过 3 次计数）**：
  - `0x800:0x820` hex：`00414230010000000f0001000e000100000000000000000000000000da933679`
  - 含义：`slot_a(prio=15, tries=0, successful=1)`，`slot_b(prio=14, tries=0, successful=1)`
  - 对应生成命令：`python3 switch_ab_slot.py commit_slot_a /tmp/misc_commit_a.img`（与 `factory_backup/misc_locked_slot_a.img` 字节完全一致）
- **试引导 `slot_a`（给 `boot_a` 3 次机会，失败自动回退 Android `boot_b`）**：
  - `0x800:0x820` hex：`00414230010000000f0300000e00010000000000000000000000000096970631`
  - 含义：`slot_a(prio=15, tries=3, successful=0)`，`slot_b(prio=14, tries=0, successful=1)`
  - 对应生成命令：`python3 switch_ab_slot.py test_slot_a /tmp/misc_test_a.img`
- **切回 Android `slot_b`（直接引导原厂 `boot_b`）**：
  - `0x800:0x820` hex：`00414230010000000e0001000f000100000000000000000000000000119c9cae`
  - 含义：`slot_a(prio=14, tries=0, successful=1)`，`slot_b(prio=15, tries=0, successful=1)`
  - 对应生成命令：`python3 switch_ab_slot.py restore_slot_b /tmp/misc_restore_b.img`

---

## 5. 标准恢复操作步骤

### 场景 1：进入 Loader 模式后切回 Android (`boot_b`)

```bash
cd .
python3 switch_ab_slot.py restore_slot_b /tmp/misc_restore_b.img

# 先确认设备已连接且在 Loader 模式
rkdeveloptool ld
rkdeveloptool ppt

# 写入 clean 的 restore_slot_b misc 镜像（二选一：按分区名或按 LBA 49152 / 0xc000）
rkdeveloptool wlx misc /tmp/misc_restore_b.img
# 或: rkdeveloptool wl 49152 /tmp/misc_restore_b.img

# 回读前 32 字节（含 0x800 处）核对：读取 misc 分区再重启
rkdeveloptool rl 49152 8192 /tmp/misc_readback.img
cmp /tmp/misc_restore_b.img /tmp/misc_readback.img
rkdeveloptool rd
```

### 场景 2：进入 Loader 模式后恢复 `boot_b`（Android）或 `boot_a`（Linux）

```bash
# 恢复原厂 Android boot_b
rkdeveloptool wlx boot_b factory_backup/boot_b.img
# 或: rkdeveloptool wl 405504 factory_backup/boot_b.img

# 恢复当前验证通过的主线 Linux 6.12 v24 到 boot_a，并锁定 slot_a
rkdeveloptool wlx boot_a boot_linux_612_v24.img
rkdeveloptool wlx misc factory_backup/misc_locked_slot_a.img
rkdeveloptool rd
```

### 场景 3：整系统压缩分区恢复（如 `super` / `backup` / `cache` 损坏）

```bash
# 解压 .zst 原始镜像（确保磁盘有至少 6 GiB 可用空间）
zstd -d factory_backup/super.img.zst -o /tmp/super.img
rkdeveloptool wlx super /tmp/super.img
rkdeveloptool rd
```

### 场景 4：无 Linux 时，在 Android 下通过 ADB 改写 `misc` 切回 Linux (`boot_a`)

> 注：Android 下 `misc` 设备节点为 `/dev/block/by-name/misc`（对应 `/dev/block/mmcblk2p6`）。经 `flash_candidate_boot_a.sh` 实测，本机通过 `adb root` 后即可直接以 `uid=0` 执行 `dd` 与 `sha256sum` 校验（不依赖 `su`/`xxd`）。

#### 方法 4A：直接推送已锁定 `slot_a` 镜像（推荐，同时清零 BCB 并跳过 3 次计数）

```bash
cd .
adb root
adb shell id -u   # 必须输出 0

sha256sum factory_backup/misc_locked_slot_a.img
adb push factory_backup/misc_locked_slot_a.img /data/local/tmp/misc_locked_slot_a.img
adb shell "dd if=/data/local/tmp/misc_locked_slot_a.img of=/dev/block/by-name/misc bs=4M conv=fsync && sync"

# 整分区 SHA-256 回读校验（必须与本地 factory_backup/misc_locked_slot_a.img 完全一致）
adb shell "sha256sum /dev/block/by-name/misc"
adb reboot
```

#### 方法 4B：使用 `switch_ab_slot.py` 给 `slot_a` 开启 3 次容错试引导

```bash
cd .
python3 switch_ab_slot.py test_slot_a /tmp/misc_test_a.img
sha256sum /tmp/misc_test_a.img

adb root
adb push /tmp/misc_test_a.img /data/local/tmp/misc_test_a.img
adb shell "dd if=/data/local/tmp/misc_test_a.img of=/dev/block/by-name/misc bs=4M conv=fsync && sync"
adb shell "sha256sum /dev/block/by-name/misc"
adb reboot
```

*(提示：若只想在 Android 内检查当前生效槽位，可执行 `adb shell getprop ro.boot.slot_suffix` 或 `adb shell bootctl get-current-slot`；在 `slot_b` / Android 时分别返回 `_b` / `1`。)*

---

## 6. 在 Linux 6.12 下手动切回 Android (`boot_b`)

当需要从 Linux 切回原厂 Android 11 时：

```bash
# 方式一（推荐）：在工作区生成干净的 restore_slot_b 镜像并传至板端写入 /dev/mmcblk0p6
# python3 switch_ab_slot.py restore_slot_b /tmp/misc_restore_b.img
# scp /tmp/misc_restore_b.img root@<BOARD_IP>:/tmp/misc_restore_b.img
dd if=/tmp/misc_restore_b.img of=/dev/mmcblk0p6 bs=4096 conv=fsync && sync

# 方式二：直接在板端 Linux 用 python3 写入已校验的 restore_slot_b 32 字节记录并清零 BCB
python3 - <<'EOF'
restore_b = bytes.fromhex("00414230010000000e0001000f000100000000000000000000000000119c9cae")
with open("/dev/mmcblk0p6", "r+b") as f:
    f.seek(0); f.write(b"\x00" * 64)
    f.seek(0x4000); f.write(b"\x00" * 64)
    f.seek(0x800); f.write(restore_b)
    f.flush()
with open("/dev/mmcblk0p6", "rb") as f:
    f.seek(0x800)
    print("READBACK:", f.read(32).hex())
EOF
sync && reboot
```

*(若需在板端 Linux 下直接切回或锁定 `slot_a`，将上面 `restore_b` 替换为 `commit_slot_a` 十六进制 `00414230010000000f0001000e000100000000000000000000000000da933679` 即可。)*

