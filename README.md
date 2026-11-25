# ZTE CloudBook Air 14" (CT2000-24RTW) 主线 Linux 移植项目

> **目标机型**：中国移动 / 中兴云电脑笔记本 `CT2000-24RTW`（主板丝印 `W620D V12.0`）  
> **硬件平台**：Rockchip `RK3568B2` · 4GB LPDDR4 · 32GB eMMC（无 TF 卡槽）· BOE `NV140WUM-N44` 1920×1200@60Hz eDP · MT7663S SDIO Wi-Fi/BT  
> 日用桌面使用 Ubuntu 26.04 LTS ARM64、Niri 和 Noctalia，通过 greetd 与 Noctalia Greeter 提供 Wayland 图形登录，搭配 `7.3.5-cloudbook` 板级内核。Debian 构建停止维护，原有部署和测试记录供回查。主线 Linux `6.12.117 LTS` 保留为移植基线。

---

- 制作试跑 U 盘、写入 A 槽和部署到 p21。[7.3.5 刷机与部署](docs/FLASHING_GUIDE.md)
- 构建板级内核和 Ubuntu Niri / Noctalia 镜像。[7.3.5 内核与 Ubuntu 桌面构建](mainline-7.3/README.md)
- 在电源菜单切换系统、构建 APK 和配置 ADB 授权。[Linux 与 Android 图形切换](tools/system-switcher/README.md)
- 维护与排查。[6.12 LTS 移植基线](mainline-6.12/README.md) · [OTG 恢复](docs/OTG_RECOVERY.md) · [移植进度](docs/progress.md)

## 一、 启动与分区

本机保留原厂签名的 `idbloader`、U-Boot 和 Trust，Linux 内核通过原厂 U-Boot 加载。

- **A 槽** `boot_a`（`/dev/mmcblk0p11`）存放 Linux 内核、initramfs 和设备树。
- **B 槽** `boot_b`（`/dev/mmcblk0p12`）与 `super` 中的 B 槽逻辑分区提供原厂 Android 11。
- **双系统数据**。p20 `userdata` 供 Android 使用，p21 `rootfs`（`LABEL=EMMC_ROOTFS`）存放内置 Linux。v30 通用引导镜像优先使用卷标为 `ROOTFS` 的试跑 U 盘。[实机布局](docs/P20_P21_DUAL_BOOT_PLAN.md)
- **刷机边界**。保留原厂签名链的 eMMC 前 8 MiB、`security`、`uboot_a/b`、`trust_a/b`。Type-C 旁的 USB-A 是 Rockusb/UMS 刷机口。写入 `boot_a` 或 `misc` 前先备份并验证 B 槽能启动。[刷机手册](docs/FLASHING_GUIDE.md)

---

## 二、 硬件支持矩阵（Linux `6.12.117-cloudbook`）

此表记录 6.12 的板级基线。7.3.5 的内屏、网络、视频解码和电源验收见 [7.3.5 真机验证范围](mainline-7.3/README.md#5-真机验证范围)。

| 子系统 | 硬件芯片 / 规格 | 当前状态 | 关键技术方案 |
| :--- | :--- | :--- | :--- |
| **内屏显示** | 京东方 `NV140WUM-N44` (`1920x1200@60Hz`) | ✅ 正常 | `0003` 添加 `659 MHz` VPLL 频点 + `0009` Naneng eDP PHY + `0010` Analogix DP/panel-edp；PWM8 背光 (`GPIO2_D3`) |
| **GPU 加速** | ARM Mali-G52 r1 (`fde60000`) | ✅ 正常 | 主线 `panfrost` + Mesa `25.0.7` (OpenGL 3.1 / ES 3.1) |
| **HDMI 输出** | DW-HDMI (`fe0a0000`) | ✅ 就绪 | VOP2 `vp0 -> HDMI` / `vp1 -> eDP` + `i2s0_8ch` HDMI 音频 |
| **Wi-Fi** | MediaTek MT7663S SDIO (`037a:7603`) | ✅ 正常 | `0005` 隔离驱动 + `0007` 速率上报 + `0011` 屏蔽 P2P/ROC/sched_scan + `0012` 修复 2.4G→5G 切频认证超时 + `0013` 启用 2.4G/5G 固件 RA 与 TX A-MPDU 并优化 SDIO 吞吐；标准 `CMD11` SDR104 |
| **蓝牙** | MediaTek MT7663S SDIO (`037a:7663`) | ✅ 正常 | `btmtksdio` + `0006` 共享 eFuse 生命周期协调 + BlueZ |
| **键盘 / 触控板** | SINO WEALTH (`258a:0001`) / I2C-HID (`0911:5288`) | ✅ 正常 | USB3 Host + `GPIO0_C7` 供电；触控板双 GPIO 上电时序 (`GPIO3_B4` → `10ms` → `GPIO3_A1`) + libinput 轻触点击 |
| **音频** | RK809 Codec + 3.5mm 耳机 + PDM 数字麦 | ✅ 正常 | 扬声器 (`GPIO3_C3`)、耳机检测 (`GPIO3_C2`) 与耳放 (`GPIO0_C3`)、PDM 麦 (`GPIO2_D6`)；配合 `alsa-ucm-conf` + `rootfs_overlay/usr/share/alsa/ucm2/` 实现 3.5mm 耳机插拔自动静音喇叭并切换音频路由 |
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
├── mainline-7.3/                          # 7.3.5 适配补丁、配置与构建说明
├── mainline-6.12/
│   ├── config-6.12.117-cloudbook          # 已验证的 Linux 6.12.117 内核配置
│   ├── rk3568-ecloudbook-air-24rtw*.dts   # 分层演进 DTS 源码（最终入口为 v30-charging-led.dts）
│   ├── patches/                           # 内核补丁序列 (0001 ~ 0015)
│   ├── README.md & TESTPLAN.md            # 6.12 构建与验收边界说明
├── docs/
│   ├── board-profile.md                   # 硬件事实源与外设状态矩阵
│   ├── ANDROID14_PORT_STATUS.md           # Android 14 A 槽状态、产物与刷机 Runbook
│   ├── LINUX_USB_BOOT_FAILURE_2026-11-21.md # Linux USB 首启黑屏事件：已确认缺陷与未解决问题
│   ├── SYSTEM_MAP.md                      # 启动链、分区、init/root 通道、应用与通信面
│   ├── DEVICE_MONITORING_AUDIT.md         # 真机只读审计：ADB、TR-069、遥测与 root 通道
│   ├── THIRD_PARTY_PACKAGE_AUDIT.md       # 真机第三方/vendor 包矩阵、默认角色与禁用后果
│   ├── SECURITY_ASSESSMENT.md             # 真机控制面与安全边界（以本机实测为准）
│   ├── VENDOR_SERVICES_AND_HARDENING.md   # 厂商组件、通信面与加固顺序
│   ├── OTA_ANALYSIS.md                    # W6XX P06 离线 OTA 包解剖（非本机结论）
│   ├── progress.md                        # 移植阶段进度记录
│   ├── REVERSE_ENGINEERING_NOTES.md       # Android 4.19 & U-Boot 逆向核心结论汇总
│   ├── OTG_RECOVERY.md                    # OTG Rockusb / UMS / UART 热键恢复手册
│   └── cloudbook-air-mainline/            # P0~P4 阶段详细排障与工程记录
├── rootfs_overlay/                        # 板级配置、开机动画、登录主题和 RK809 音频路由
├── scripts/build-ubuntu-2604-735.sh        # 生成 Ubuntu 26.04 USB/eMMC 根镜像及配套引导
├── scripts/build-system-switcher-apk.sh   # 本地构建 Android 切换工具 APK
├── tools/system-switcher/                 # 双系统切换说明及 Android 源码
├── build_kernel_612.sh                    # 一键应用补丁并编译 Image / DTB（LLVM/Clang）
├── build_boot_linux_612.sh                # 一键打包 Android Boot Image v2 (boot_a)
├── pack_boot_linux.py                     # Rockchip RSCE + Android boot v2 打包脚本
├── switch_ab_slot.py                      # 离线 A/B 槽启动记录与 misc 镜像生成器
├── emmc_backup_and_migrate.sh             # 早期 p20 布局的备份与迁移脚本
├── scripts/restore_factory_android.sh     # 原厂 GPT 布局下的 Android 11 恢复脚本
└── tools/serial_bridge.py                 # 串口调试桥接工具
```

---

## 四、 本地构建

日用桌面按 7.3.5 内核与 Ubuntu 桌面构建说明操作。构建生成 USB 和 eMMC 两套 ext4 根镜像及配套 A 槽引导。Ubuntu 基础系统从 APT 仓库构建，板级内核、从自有设备导出的原厂 RSCE 资源模板及无线固件为本地输入。[内核构建](mainline-7.3/README.md#2-复现) · [Ubuntu 桌面构建](mainline-7.3/README.md#7-ubuntu-桌面与-rootfs-构建) · [版权与固件来源](LICENSES_AND_COPYRIGHT.md)

维护 6.12 时使用 [6.12 构建入口](mainline-6.12/README.md#构建)。`./rebuild_linux.sh` 可一次编译两代内核、导出 7.3 内核文件并打包 6.12 引导镜像。[7.3.5 产物](mainline-7.3/README.md#3-产物) · [6.12 产物](mainline-6.12/README.md#构建)

## 五、 恢复 Android

双系统可正常启动时，从 Noctalia 电源菜单选「重启到安卓」即可切回 B 槽。无法进入桌面时，可使用已验证的 Android ADB、U-Boot Rockusb 或 UMS。整盘恢复前先核对当前 GPT 与备份布局，特别是 p20 的起止位置和大小。[OTG 恢复手册](docs/OTG_RECOVERY.md) · [双系统分区回滚](docs/P20_P21_DUAL_BOOT_PLAN.md#8-回滚)
