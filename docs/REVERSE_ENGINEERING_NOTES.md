# 硬件逆向取证与关键引脚笔记 (Reverse Engineering Notes)

本文档汇总了对 ZTE CloudBook Air 14" (`CT2000-24RTW` / `W620D V12.0` / `RK3568B2`) 原厂 Android 11（Linux `4.19.193`）及原厂 `uboot_b.img` 进行设备树提取与二进制逆向后固化的硬件真相，作为后续主线 DTS 与驱动维护的事实依据。

---

## 1. 引导安全边界与硬件刷机口

1. **硬件安全启动边界 (`fuse.programmed=1`, `androidboot.secureboot=1`)**
   * eMMC 扇区 64 处的 `idbloader` 带有 `RKSS` 硬件签名，`uboot_a/b` 与 `trust_a/b` 带有 RSA-2048 FIT 签名。**严禁覆盖前 8 MiB、`uboot_a/b`、`trust_a/b` 或 `security` 分区**。
   * 原厂 U-Boot 加载 `boot_a`/`boot_b` 时不强制拦截未签名内核镜像（`vbmeta_a/b` `flags = 2` 已禁用 AVB，verified boot 状态为 `orange`），因此可以保留原厂签名 U-Boot，将主线 Linux 内核 + initramfs + DTB 打包为 Android Boot Image v2 写入 `boot_a`。
2. **SARADC 硬件版本匹配 (`resource.img`)**
   * 原厂 `resource.img` 含 18 个 DTB，U-Boot 通过 `saradc` 通道 1（`938`）与通道 4（`680`）匹配第 18 项 `rk3568-w620d-25wh_v12#_saradc_ch1=938#_saradc_ch4=680.dtb`。
3. **A/B 双槽位自动回退与每次重启消耗 2 次试启动计数**
   * 实测原厂 SPL 会把 `misc` (`0x800`) 中 `tries_remaining` 减 1（`3 -> 2`），随后 U-Boot `ab_select_slot` 再减 1（`2 -> 1`）。切回/试引导 Slot A 时需保留 `Slot B (priority=14, successful=1)` 作为自动回退保底。
4. **物理 OTG / Rockusb / UMS 接口与串口热键**
   * 本机**无 TF 卡槽**；唯一工作在 `dr_mode = "otg"` 的 `dwc3@fcc00000` 物理连接到 **Type-C 口旁边的 USB-A 母座**（需双公头 USB-A 线）。
   * 主板 UART2 测试焊盘波特率为 `115200 8N1`（内核阶段 `console=ttyS2,115200n8`）。上电瞬间 U-Boot 串口支持 `Ctrl+C`（字符串 `Hit key to stop autoboot('CTRL+C')`，`bootdelay=0`）进入 U-Boot CLI，再执行 `rockusb 0 mmc 0`；另有 ADC `volumeup-key`（`label="recovery"`）可无 UART 进 recovery/download。Linux/Android 支持 `reboot loader` 与 `reboot ums`。

---

## 2. 12 项逆向核实的关键硬件与 GPIO 真相

1. **VOP2 端口路由与 BOE `NV140WUM-N44` eDP**
   * `vp0` 路由给 HDMI (`&pmucru PLL_HPLL`)；`vp1` 路由给 eDP (`&cru PLL_VPLL`, `659 MHz` 频点 -> `164.75~164.81 MHz` 像素时钟，`1920x1200@60Hz`)。
   * `vcc3v3_lcd_edp` 由 `GPIO2_D5` 控制，面板使能脚 `GPIO2_D4`，背光使用 `&pwm8` (`fe6f0000`) + 使能脚 `GPIO2_D3`。
   * Naneng eDP PHY 在 V12/V13 主板搭配 BOE N44 时需要调整链路训练电压摆幅与预加重参数（`0009` 补丁）。
2. **I2C4 触控板 (`touchpad@2c`, `0911:5288`) 双 GPIO 上电时序**
   * 必须先拉高 `GPIO3_B4`（`gpio-power`）延时 `10ms`，再拉高 `GPIO3_A1`（`gpio-reseten`）延时 `100ms`，中断为 `GPIO3_B3` (`IRQ_TYPE_LEVEL_LOW`)。
3. **USB Hub 极性与内置键盘/摄像头供电**
   * `GPIO0_B0` (`usb2hubrst`) 为 **高电平复位、低电平工作**（输出 `0` 才释放 USB 2.0 Hub 复位）。
   * 内置 SINO WEALTH USB 键盘 (`258a:0001`) 挂在 `&usbhost_dwc3` (`fd000000`) 下，供电使能 `GPIO0_C7` (`HIGH`)。
   * USB 3.0 Hub 复位解除 `GPIO3_A5` (`HIGH`)；`vcc5v0_host` 为 `GPIO3_A0`、`vcc5v0_otg` 为 `GPIO0_C4`；UVC 摄像头使能 `GPIO3_A2` (`HIGH`)。
   * 休眠排障发现 `usb_host0/1` `ehci/ohci` 必须将 `&usb2phy1` 声明为第 4 个 `utmi` 时钟，防止挂起顺序关闭 PHY 时钟导致 MMIO 卡死（`v28` 修正）。
4. **音频全链路**
   * `RK809` 扬声器功放 `GPIO3_C3` (`HIGH`)；3.5mm 耳机检测 `GPIO3_C2` (`ACTIVE_LOW`) 与外部耳放使能 `GPIO0_C3` (`HIGH`)；数字硅麦 `&pdm` 使能 `GPIO2_D6` (`HIGH`)；HDMI 音频走 `&i2s0_8ch`。
5. **MT7663S SDIO Wi-Fi / 蓝牙组合芯片 (`037a:7603` / `037a:7663`)**
   * `dwmmc@fe2c0000` (`mmc1`)：Wi-Fi 供电 `GPIO0_A5` (`ACTIVE_HIGH`)、Wi-Fi 复位 `GPIO0_B5`、Wi-Fi 唤醒 `GPIO2_B2`、蓝牙复位 `GPIO2_B7` (`ACTIVE_HIGH`)、蓝牙唤醒 `GPIO2_C1`。
   * **关键纠偏**：RK3568 `mmc1` 需执行标准 `CMD11` 1.8V 切换与 SDR104 调谐（不可套用 Amlogic `zte,sdio-fixed-1-8v`）；为兼容 Linux 桌面 `NetworkManager` / `wpa_supplicant`，驱动侧需屏蔽 W103D N9 固件不支持的 P2P (`P2P_CLIENT`/`P2P_GO`/`P2P_DEVICE`)、ROC 与 `sched_scan` 声明（`0011` 补丁）；此外，对照原厂 `wlan_mt7663_sdio.ko` 的断连状态机，STA 断连时不可误发 `CMD 0x16 (SET_BSS_CONNECTED)`，且必须下发 `CMD 0x12 (UPDATE_BSS_INFO, DISCONNECTED)` + `CMD 0x11 (BSS_ACTIVATE_CTRL, active=0)` 并在下次扫描或切 5 GHz 前重新激活 BSS、正确回收 `CH_PRIVILEGE`（`0012` 补丁），方可彻底消除 2.4 GHz → 5 GHz 认证超时。
6. **Type-C PD (`sc2021@21`)、充电 (`bq25700@6b`) 与电池 (`sbs-battery@0b`)**
   * `GPIO0_A6` (`sc2021_power`) 为南芯 SC2021 芯片供电脚（原厂隐藏在 `gpio-leds` 中，`default-state = "on"`）。
   * `GPIO0_A4` 是 SC2021 中断脚（因此必须禁用无物理卡槽的 `&sdmmc0`，避免与 `cd-gpios` 冲突）。
   * 电池在位检测为 `GPIO3_B2` (`ACTIVE_LOW`)；BQ25700/BQ25703A 中断为 `GPIO3_B7`。
7. **霍尔盒盖开关 (`GPIO0_B7`, `SW_LID`)**
   * 开漏霍尔传感器在 RK3568 PMU_GRF 默认下拉下会误报合盖；必须在父节点 `/gpio-keys` 上配置 `&pcfg_pull_up`（`v27` 修正），才能支持正常的合盖深睡 (`mem`) 与开盖唤醒。
