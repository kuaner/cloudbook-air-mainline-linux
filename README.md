# ZTE CloudBook Air 14" (CT2000-24RTW) 主线 Linux 6.12 移植项目

> **目标机型**：中国移动 / 中兴云电脑笔记本 `CT2000-24RTW`（主板丝印 `W620D V12.0`）  
> **硬件平台**：Rockchip `RK3568B2` · 4GB LPDDR4 · 32GB eMMC（无 TF 卡槽）· BOE `NV140WUM-N44` 1920×1200@60Hz eDP · MT7663S SDIO Wi-Fi/BT  
> **当前状态**：✅ **主线 Linux `6.12.117 LTS` + Armbian Debian 13 (Trixie) 已固化至内置 eMMC，日常桌面、Wi-Fi/蓝牙、PD 充电、合盖休眠与 H.264 视频硬解均可稳定使用。**

---

> **想直接给同款机器刷机？** 请直接阅读保姆级分步操作手册：**[docs/FLASHING_GUIDE.md](docs/FLASHING_GUIDE.md)**（涵盖：前置校验与冷备份 → 成品包/从零编译路线 → U 盘免损首测 → 内置 eMMC 迁移 → 桌面与硬件解码配置 → 救砖/还原 Android）。

## 一、 架构亮点与安全原则

本机已启用硬件熔断安全启动（`fuse.programmed=1`、`androidboot.secureboot=1`），`idbloader`（`RKSS`）与 `uboot_a/b`、`trust_a/b` 均带有硬件/RSA-2048 签名，但原厂 U-Boot 对 `boot_a`/`boot_b` 不拦截未签名内核。因此本项目采用 **“保留原厂签名 Bootloader，双槽独立共存”** 的零变砖架构：

* **Slot B (`boot_b`, `/dev/mmcblk0p12`)**：完整保留原厂 Android 11（可随时回退或一键还原）。
* **Slot A (`boot_a`, `/dev/mmcblk0p11`)**：运行主线 Linux `6.12.117-cloudbook` Android Boot Image v2。
* **Rootfs (`/dev/mmcblk0p20`, `LABEL=EMMC_ROOTFS`)**：Armbian Debian 13 (Trixie) ARM64 桌面系统（LightDM + i3wm + Panfrost）。
* **绝对红线**：严禁向 eMMC 前 8 MiB (`idbloader`)、`uboot_a/b`、`trust_a/b`、`security` 写入未签名开源固件；Type-C 旁边的 **USB-A 接口** 物理连接 `dwc3@fcc00000` OTG（唯一 `Rockusb`/`UMS` 刷机口）。

---

## 二、 硬件支持矩阵（Linux `6.12.117-cloudbook`）

| 子系统 | 硬件芯片 / 规格 | 当前状态 | 关键技术方案 |
| :--- | :--- | :--- | :--- |
| **内屏显示** | 京东方 `NV140WUM-N44` (`1920x1200@60Hz`) | ✅ 正常 | `0003` 添加 `659 MHz` VPLL 频点 + `0009` Naneng eDP PHY + `0010` Analogix DP/panel-edp；PWM8 背光 (`GPIO2_D3`) |
| **GPU 加速** | ARM Mali-G52 r1 (`fde60000`) | ✅ 正常 | 主线 `panfrost` + Mesa `25.0.7` (OpenGL 3.1 / ES 3.1) |
| **HDMI 输出** | DW-HDMI (`fe0a0000`) | ✅ 就绪 | VOP2 `vp0 -> HDMI` / `vp1 -> eDP` + `i2s0_8ch` HDMI 音频 |
| **Wi-Fi** | MediaTek MT7663S SDIO (`037a:7603`) | ✅ 正常 | `0005` 隔离驱动 + `0007` 速率上报 + `0011` 屏蔽 P2P/ROC/sched_scan + `0012` 修复 2.4G→5G 切频认证超时 + `0013` 启用 2.4G/5G 固件 RA 与 TX A-MPDU 并优化 SDIO 吞吐；标准 `CMD11` SDR104 |
| **蓝牙** | MediaTek MT7663S SDIO (`037a:7663`) | ✅ 正常 | `btmtksdio` + `0006` 共享 eFuse 生命周期协调 + BlueZ |
| **键盘 / 触控板** | SINO WEALTH (`258a:0001`) / I2C-HID (`0911:5288`) | ✅ 正常 | USB3 Host + `GPIO0_C7` 供电；触控板双 GPIO 上电时序 (`GPIO3_B4` → `10ms` → `GPIO3_A1`) + libinput 轻触点击 |
| **音频** | RK809 Codec + 3.5mm 耳机 + PDM 数字麦 | ✅ 正常 | 扬声器 (`GPIO3_C3`)、耳机检测 (`GPIO3_C2`) 与耳放 (`GPIO0_C3`)、PDM 麦 (`GPIO2_D6`) |
| **电源 / PD / 电池** | 南芯 SC2021 + TI BQ25700/25703A + SBS 电池 | ✅ 正常 | `0008` 驱动支持 PD 快充阶梯、充电管理与 `GPIO0_A4` 休眠插拔唤醒；`0014` 对齐原厂充/满电状态与 LED 触发；`v30` 补回 `charge-animation` + `rk817,battery` 恢复原厂 U-Boot 关机充电与开盖开机；禁 `&sdmmc0` 避免 `GPIO0_A4` 中断冲突 |
| **盒盖休眠 / 指示灯** | 霍尔传感器 (`GPIO0_B7`, `SW_LID`) + 电源/充电 LED | ✅ 正常 | `v27` 父节点上拉 + `v28` USB2 PHY `utmi` 休眠时钟修复 + `logind`/`sleep.conf` (`mem`)；`v30` 绑定电源键白灯 (`GPIO0_C2`)、Type-C 橙/绿充电灯 (`GPIO0_C5`/`C6`) 并支持休眠中插拔刷新与关机充电亮灯 |
| **摄像头 / USB** | UVC 摄像头 (`1bcf:2ced`) / USB 3.0 & 2.0 Hub | ✅ 正常 | 摄像头使能 `GPIO3_A2`；USB2 Hub 低电平释放 (`GPIO0_B0=0`)；`0001` 支持 SR9900 USB 网卡 |
| **视频硬解** | Rockchip Hantro VPU (`/dev/video1`) + RGA | 🟡 部分 | 支持 **H.264 / VP8 / MPEG-2** V4L2 Stateless 硬解（GStreamer `v4l2codecs` + `xvimagesink`，推荐 `gst-play-1.0` / `parole`）；Linux 6.12 无 RK3568 `rkvdec2`，HEVC/VP9 走软解 |

---

## 三、 仓库目录结构

```text
.
├── README.md                              # 项目总览与快速复现指南
├── LICENSES_AND_COPYRIGHT.md              # 版权与闭源固件合规甄别清单（必读）
├── .gitignore                             # 严格隔离内核树、备份与闭源二进制
├── mainline-6.12/
│   ├── config-6.12.117-cloudbook          # 已验证的 Linux 6.12.117 内核配置
│   ├── rk3568-ecloudbook-air-24rtw*.dts   # 分层演进 DTS 源码（最终入口为 v30-charging-led.dts）
│   ├── patches/                           # 完整内核补丁序列 (0001 ~ 0014)
│   ├── README.md & TESTPLAN.md            # 6.12 构建与验收边界说明
├── docs/
│   ├── board-profile.md                   # 硬件事实源与外设状态矩阵
│   ├── progress.md                        # 移植阶段进度记录
│   ├── REVERSE_ENGINEERING_NOTES.md       # Android 4.19 & U-Boot 逆向核心结论汇总
│   ├── OTG_RECOVERY.md                    # OTG Rockusb / UMS / UART 热键恢复手册
│   └── cloudbook-air-mainline/            # P0~P4 阶段详细排障与工程记录
├── rootfs_overlay/etc/                    # 板级配置模板（背光 udev、触控板、LightDM、合盖休眠）
├── build_kernel_612.sh                    # 一键应用补丁并编译 Image / DTB（LLVM/Clang）
├── build_boot_linux_612.sh                # 一键打包 Android Boot Image v2 (boot_a)
├── pack_boot_linux.py                     # Rockchip RSCE + Android boot v2 打包脚本
├── switch_ab_slot.py                      # 安全 A/B 槽切换与 3 次试引导回退 misc 生成器
├── emmc_backup_and_migrate.sh             # 内置 eMMC 分区备份与 Rootfs 迁移脚本
├── scripts/restore_factory_android.sh     # 一键还原原厂 Android 11 脚本
└── tools/serial_bridge.py                 # 串口调试桥接工具
```

---

## 四、 从零构建与复现步骤

### 1. 准备源码与闭源前置件（本地，不入库）
由于版权与体积限制，仓库不包含 Linux 源码与厂商二进制文件（详见 [LICENSES_AND_COPYRIGHT.md](LICENSES_AND_COPYRIGHT.md)）：
1. 下载上游 **Linux `6.12.117`** 源码解压至 `./linux-6.12/`。
2. 从你自己的原厂设备只读导出 `boot_b` 中的 `resource.img` 放到 `./artifacts/extracted_resource.img`，并将目标系统的 `initrd.img` 放到 `./artifacts/initramfs-6.12.117-cloudbook-emmc.img`。
3. 如使用 Wi-Fi/蓝牙，请合法获取 MT7663 固件（`mt7663_n9_v3.bin`、`mt7663pr2h.bin`）安装至目标系统的 `/lib/firmware/mediatek/`。

### 2. 编译内核、模块与设备树
```bash
# 安装交叉编译/LLVM 工具链 (clang, lld, llvm, dtc, python3) 后执行：
BUILD_MODULES=1 ./build_kernel_612.sh
```
脚本会自动同步 `mainline-6.12/rk3568-ecloudbook-air-24rtw*.dts`、按需应用 `mainline-6.12/patches/0001`~`0014`（跳开诊断性 `0002` 与已被 `v23` 替代的 `0004`），并输出 `Image` 与 `rk3568-ecloudbook-air-24rtw.dtb`。

### 3. 打包 `boot_a` 镜像
```bash
./build_boot_linux_612.sh
```
生成 `<= 160 MiB` 的 Rockchip Android Boot Image v2 镜像，校验 DTB 内 `__symbols__`、`chosen` 与 `reboot_mode`。

### 4. 部署桌面与多媒体调优要点
* **系统配置模板**：将 `rootfs_overlay/etc/` 下配置拷入目标系统 `/etc/`，并在 `apt upgrade` 后避免让发行版脚本覆写未签名 `boot_a`（本机已验证可清理无关的 `linux-image-current-rockchip64`）。
* **H.264 视频硬解**：`vlc` 与 `mpv`（走 FFmpeg）暂不支持无 `v4l2-request` 补丁的 RK3568 Hantro 硬解。安装 **`gstreamer1.0-plugins-bad`** + `gstreamer1.0-x` + `parole` 后，将 `parole` 输出设为 `xvimagesink`（避开默认 `autoimagesink` 回退 `ximagesink` 或 `glimagesink` 导致掉帧）：
  ```bash
  xfconf-query -c parole -p /video/videosink -n -t string -s xvimagesink
  ```
  实测播放 1080p H.264 网络流时稳定开启 `/dev/video1` 硬解，单进程 CPU 占用由 VLC 软解的 ~249% 降至 ~47%。

---

## 五、 救砖与回退

详见 [docs/OTG_RECOVERY.md](docs/OTG_RECOVERY.md)：
* 试启动 `misc` 配置为 `tries_remaining=3` 时，连续冷重启失败会自动回退 `Slot B` 原厂 Android；
* 随时可通过串口 `Ctrl+B` 或 `reboot loader` / `reboot ums` 经 Type-C 旁的 USB-A 口连接主机 `rkdeveloptool` 恢复。
