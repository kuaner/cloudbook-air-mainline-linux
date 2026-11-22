# 板卡档案：ZTE CloudBook Air 14" (CT2000-24RTW) / W620D V12.0

> **依据规程**：[`rk-linux-bringup-skill`](.agents/skills/rk-linux-bringup-skill/SKILL.md)  
> **地位**：整机硬件事实源，每项标明证据级别、出处与验证状态。  
> **最后更新**：2026-11-16  

---

## 1 基本身份

| 项 | 值 | 证据级别 | 出处 | 状态 |
|---|---|---|---|---|
| 整机/对外名称 | 中国移动/中兴 云电脑笔记本 CloudBook Air 14" | L4 | 机身外壳丝印 / 包装 | 已验证 |
| 设备型号 | CT2000-24RTW | L2 / L4 | Android `ro.product.model` / 外壳 | 已验证 |
| 主板型号/丝印 | W620D V12.0 | L3 | `resource.img` DTB 名称、主板丝印 | 已验证 |
| SoC 型号 | Rockchip RK3568B2 (4× Cortex-A55 @ 1.99GHz) | L2 / L3 | `/proc/cpuinfo` / `extracted_factory.dts` | 已验证 |
| 内存 | 4GB LPDDR4 (4096MB @ 1560MHz) | L2 | U-Boot DDR 初始化日志 | 已验证 |
| 内部存储 | 32GB eMMC (HS200, 20 个标准 GPT 分区) | L2 | `dmesg` / `lsblk` / `partitions.txt` | 已验证 |
| 外置扩展 | 无 MicroSD/TF 卡槽；通过 USB-A 外接 USB 3.0 U 盘 | L1 / L2 | 实机外观检查 / `lsblk` | 已验证 |
| 原厂系统 | Android 11 (V2.24.30P6-20241112, 运行在 Slot B) | L2 | `getprop.txt` | 已验证 |
| 原厂内核 | Linux 4.19.193 (`#439 SMP PREEMPT`) | L2 | `extracted_kernel.bin` / `dmesg.txt` | 已验证 |

---

## 2 电源与 PMIC

| 项 | 值 | 证据级别 | 出处 | 状态 |
|---|---|---|---|---|
| 供电接口 | 全功能 Type-C PD 接口（支持 12V/15V/20V 诱骗快充） | L1 / L2 | 实测 PD 充电 / `sc2021_driver.c` | 已验证 |
| 主 PMIC 型号 | Rockchip RK809 (`0x20` on I2C0, IRQ `GPIO0_B7`) | L2 / L3 | `factory_dmesg.txt` (`chip id: 0x8090`) | 已验证 |
| CPU Buck 稳压器 | Silergy SYR827 / RK860-0 (`0x40` on I2C0, Option 8 Rev 1) | L2 / L3 | 4.19 / U-Boot 日志 (`FAN53555 Option[8] Rev[1]`) | 已验证 |
| 未贴片 CPU Buck | TCS4525 (`0x1c`) 与 SYR828 (`0x41`) | L2 | `factory_dmesg.txt` (`Failed to get chip ID!`) | 已证实未贴片 |
| Type-C PD 控制器 | 南芯 SC2021 (`0x21` on I2C0, IRQ `GPIO0_A4`, 使能 `GPIO0_A6`) | L2 / L3 | `sc2021_probe.bin.c` / `factory_dmesg.txt` | 已验证 |
| NVDC 充电管理 | TI BQ25700 (`0x6b` on I2C0, IRQ `GPIO3_B7`) | L2 / L3 | `bq25700_pd_connect.bin.c` / `factory_dmesg.txt` | 已验证 |
| 智能电池 | 25Wh SBS 1.1 Smart Battery (`0x0b` on I2C0, BATID `GPIO3_B2`) | L2 / L3 | `smart_battery_work.bin.c` / `factory_dmesg.txt` | 已验证 |
| USB2 Hub 复位脚 | `GPIO0_B0` (`ACTIVE_LOW`，输出 0 释放复位) | L3 | `ztegpioctl_all.bin.c` 逆向 | 已验证 |
| USB3 Hub 复位脚 | `GPIO3_A5` (拉低 20ms 后拉高 1 释放复位) | L3 | `boardctl_all.bin.c` 逆向 | 已验证 |
| 摄像头供电使能 | `GPIO3_A2` (`ACTIVE_HIGH`，输出 1 使能供电) | L2 / L3 | `ztegpioctl_all.bin.c` / 4.19 Linux 摄像头实测 | 已验证 |
| 键盘控制器供电 | `GPIO0_C7` (`ACTIVE_HIGH`，输出 1 使能供电) | L3 | `ztegpioctl_all.bin.c` 逆向 | 已验证 |

---

## 3 存储与总线分配

| 项 | 值 | 证据级别 | 出处 | 状态 |
|---|---|---|---|---|
| eMMC 控制器 | `sdhci@fe310000` (8 位总线，HS200 模式，固定 `vqmmc` 1.8V) | L2 / L3 | `factory_w620d_v12.dts` / `dmesg` | 已验证 |
| SDIO Wi-Fi 总线 | `dwmmc@fe2c0000` (`mmc1`，接 MT7663S，固定 1.8V 供电) | L2 / L3 | `extracted_factory.dts` / `logs/uart-612-v1-mmc1-hang-20261112.txt` | 已验证 |
| I2C0 (`fdd40000`) | 电源总线：RK809 (`0x20`), SYR827 (`0x40`), SC2021 (`0x21`), BQ25700 (`0x6b`), 电池 (`0x0b`) | L2 / L3 | `factory_dmesg.txt` | 已验证 |
| I2C4 (`fe5d0000`) | 触控板总线：`touchpad@2c` | L2 / L3 | `i2c_hid_all.bin.c` / 4.19 实机验证 | 已验证 |
| I2C5 (`fe5e0000`) | 重力/方向传感器：`mxc6655xa@15` | L3 | `factory_w620d_v12.dts` | 未验证 |

---

## 4 调试与引导

| 项 | 值 | 证据级别 | 出处 | 状态 |
|---|---|---|---|---|
| 调试串口位置 | 主板物理测试焊盘（对应 SoC `UART2` @ `0xfe660000`） | L1 / L2 | 串口日志 `/dev/ttyUSB0` 畅通 | 已验证 |
| 串口参数 | `115200 8N1` (TTL 3.3V) | L1 / L2 | 实机通信实测 | 已验证 |
| U-Boot 串口中断 | `Ctrl+C`（U-Boot 字符串 `Hit key to stop autoboot('CTRL+C')`，`bootdelay=0`，需上电瞬间连按） | L3 | `uboot.img` 字符串 | 待真机复验 |
| U-Boot 无 UART 入口 | ADC `volumeup-key`（`label = "recovery"`）按住开机 → recovery/download | L3 | `uboot.img` DTB `adc-keys` | 待真机验证 |
| OTG 刷机接口 | **Type-C 旁边的 USB-A 口**（内部物理连接 `dwc3@fcc00000` OTG 控制器） | L3 | `uboot_embedded.dts` 逆向 | 已验证 |
| A/B 槽引导回滚 | U-Boot 支持标准 Android `tries_remaining`（试引导上限 3 次，耗尽自动回退 Slot B） | L2 / L3 | `switch_ab_slot.py` / 实机回退验证 | 已验证 |

---

## 5 外设状态矩阵

| 外设 | 具体型号 / 规格 | 4.19 Linux 基线状态 | 6.12 主线状态 | 备注与证据 |
|---|---|---|---|---|
| **内屏 (eDP)** | 京东方 `NV140WUM-N44` (1920×1200@60Hz) | **已真机验证** (Xorg modesetting) | **已真机验证 (`v10`~`v29`)** | VPLL `659MHz` / `eDP-1` 1920×1200@60Hz，LightDM + `i3wm` + 背光权限规则 |
| **HDMI 输出** | DW-HDMI (`fe0a0000.hdmi`) | **已真机验证** (1920×1080 镜像输出) | **已就绪 (`v13`/`v18`~`v29`)** | `HDMI-1` 与 `card 0 rockchip,hdmi` 已注册 |
| **键盘** | SINO WEALTH USB HID Keyboard (`258a:0001`) | **已真机验证** (输入流畅) | **已真机验证 (`v8`~`v29`)** | USB 内部走线，供电 `GPIO0_C7` |
| **触控板** | I2C-HID Touchpad (`0911:5288` @ `0x2c`) | **已真机验证** (光标移动与左右键正常) | **已真机验证 (`v11`~`v29`)** | `hid-over-i2c` + `30-touchpad.conf` (`Tapping=on`) |
| **扬声器音频** | RK809 Codec via I2S1 (`fe410000`) | **已真机验证** (内置扬声器正常发声) | **已真机验证 (`v14`~`v29`)** | `card 1 rockchip,rk809-codec`，`v24` 补全 Speaker/Headphones 路由 |
| **耳机与麦克风** | 3.5mm 耳机口 + PDM 数字麦 | **已真机验证** | **已真机验证 (`v14`/`v19`/`v24`~`v29`)** | `gpio-114 Headphone detection` 及 `card 2 rockchip,pdm-mic-array` |
| **USB 摄像头** | USB HD WebCam (`1bcf:2ced` Sunplus) | **已真机验证** (MJPEG 抓图通过) | **已就绪 (`v8`~`v29`)** | UVC 节点 `/dev/video3`、`/dev/video4`，供电 `GPIO3_A2` |
| **有线网络** | 外接 USB 网卡 (CoreChips SR9900 `0fe6:9900`) | **已真机验证** (`<DEVICE_IP>` SSH) | **已真机验证 (`v8`~`v29`)** | `r8152` 补丁已内置 (`0001`)，SSH 稳定 |
| **无线网络** | MT7663S (SDIO Wi-Fi `037a:7603`) | 暂未加载 | **已真机验证 (`v23`~`v29` + `0011` + `0012`)** | 标准 `CMD11` SDR104 + `0011` 屏蔽 P2P/ROC/sched_scan + `0012` 修复 2.4G→5G 切频认证超时与断连 BSS 状态机，双频切换稳定 |
| **蓝牙** | MT7663S (SDIO BT `037a:7663`) | 暂未加载 | **已真机验证 (`v23`~`v29`)** | `btmtksdio` `hci0` + `bluetoothd 5.82` 在线，鼠标/外设可连 |
| **GPU 加速** | Mali-G52 r1 (`fde60000.gpu`) | 闭源驱动 | **已真机验证 (`v12`~`v29`)** | `panfrost` + Mesa `25.0.7` OpenGL `3.1` / ES `3.1` (`glamor` 硬件加速就绪) |
| **电池 / PD / 充电** | SBS 电池 (`0x0b`) + SC2021 (`0x21`) + BQ25700 (`0x6b`) | 已验证 | **已真机验证 (`v25`~`v29`)** | `0008` 驱动支持 PD 快充阶梯与充电管理；`v27`+`v28` 支持霍尔合盖深睡与唤醒 |
| **温控 / SARADC / VPU** | `tsadc` + `saradc` + `hantro`/`rga` | 已验证 | **部分硬解已验证 (`v16`~`v29`)** | Hantro (`/dev/video1`) 支持 V4L2 Stateless **H.264 / VP8 / MPEG-2**（GStreamer `v4l2codecs` + `xvimagesink` / `parole`）；6.12 无 `rkvdec2`，HEVC/VP9 走软解 |

---

## 6 备份与防变砖安全边界

- **不可触碰红线**：
  - 严禁向 `idbloader`（前 8MB）、`uboot_a`、`uboot_b`、`trust_a`、`trust_b` 写入任何数据；
  - 严禁修改当前工作的 `boot_b` 原厂 Android 分区；
- **测试写入区**：
  - 所有测试只在 `boot_a` 分区进行；
  - 写入 `misc` 时必须保留 3 次试启动自动回退机制（`priority = 15, tries_remaining = 3, successful_boot = 0`）；
- **全量备份清单**：保存在 `factory_backup/`（`mmcblk2_first8M_idbloader_gpt.img`, `uboot_a/b.img`, `trust_a/b.img`, `misc_restore_slot_b.img`, `boot_b.img` 160MB）。
