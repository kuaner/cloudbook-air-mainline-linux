# 版权、许可证与固件合规声明 (Licenses & Copyright Audit)

本项目在准备开源发布至 GitHub 前，对所有源码、补丁、设备树、脚本与二进制固件进行了逐项版权甄别。**本仓库不包含任何闭源厂商二进制固件、原厂 Android 分区镜像或私有密钥**；相关文件均已通过 `.gitignore` 排除。

---

## 一、 可开源分发组件（本仓库包含）

| 组件 / 路径 | 许可证 | 版权归属 / 来源 | 说明 |
| :--- | :--- | :--- | :--- |
| `mainline-6.12/rk3568-ecloudbook-air-24rtw*.dts` | `GPL-2.0+ OR MIT` | 原创适配（基于 Rockchip RK3568 主线 DTS 规范） | 设备树源码，可自由分发与上游化 |
| `mainline-6.12/patches/0001-r8152-add-sr9900-id.patch` | `GPL-2.0` | `zjzhao-eda` (r8152 ID patch) | CoreChips SR9900 USB 网卡 ID 补丁 |
| `mainline-6.12/patches/0002-dw-wdt-early-diagnostics.patch` | `GPL-2.0` | CloudBook Air Port | 可选早期看门狗诊断补丁 |
| `mainline-6.12/patches/0003-clk-rk3568-add-659mhz-vpll-rate.patch` | `GPL-2.0` | Rockchip / CloudBook Air Port | RK3568 `659 MHz` VPLL 频点（1920x1200@60Hz eDP） |
| `mainline-6.12/patches/0004-mmc-sdio-skip-cmd11-fixed-1v8.patch` | `GPL-2.0` | W103D Project (参考留存) | 历史参考补丁；`v23+` DTS 已改回标准 `CMD11` SDR104 |
| `mainline-6.12/patches/0005-mt76-mt7663s-w103d-isolated.patch` | `GPL-2.0` | MediaTek / `mt76` / W103D Project (`pigeon2049`) | 从主线 `mt76` 隔离出的 W103D MT7663S SDIO 驱动 |
| `mainline-6.12/patches/0006-mt7663-w103d-combo-lifecycle.patch` | `GPL-2.0` | W103D Project (`pigeon2049`) | `btmtksdio` 与 `mt7663s` 共享 eFuse 时序协调 |
| `mainline-6.12/patches/0007-mac80211-data-rate-reporting.patch` | `GPL-2.0` | W103D Project (`pigeon2049`) | 修正 mac80211 Null 帧覆盖速率统计 |
| `mainline-6.12/patches/0008-power-supply-sc2021-bq25703a.patch` | `GPL-2.0-only` | CloudBook Air Linux Port | 南芯 SC2021 PD 与 TI BQ25700/BQ25703A 驱动 |
| `mainline-6.12/patches/0009-phy-rockchip-naneng-edp.patch` | `GPL-2.0` | Copyright (c) 2020 Rockchip Electronics Co. Ltd. (Wyon Bi) | RK3568 Naneng eDP PHY 驱动（含 V12/V13 BOE N44 参数） |
| `mainline-6.12/patches/0010-drm-analogix-dp-rk3568-and-boe-nv140wum-n44.patch` | `GPL-2.0` | Linux DRM / Rockchip / CloudBook Air Port | RK3568 Analogix eDP 绑定与 BOE `0x0a6a` 时序条目 |
| `mainline-6.12/patches/0011-mt76-mt7663s-w103d-sanitize-wiphy.patch` | `GPL-2.0` | CloudBook Air Linux Port | 屏蔽 W103D Wi-Fi P2P/ROC/sched_scan 以兼容 NetworkManager |
| `mainline-6.12/patches/0012-mt76-mt7663s-w103d-fix-band-switch-disconnect.patch` | `GPL-2.0` | CloudBook Air Linux Port | 修复 `mt7615` 断连误发 `CMD 0x16` 与 W103D STA 断连 BSS/CH_PRIVILEGE 生命周期（解决 2.4G→5G 切频认证超时） |
| `build_kernel_612.sh`, `build_boot_linux_612.sh`, `pack_boot_linux.py`, `switch_ab_slot.py`, `emmc_backup_and_migrate.sh`, `scripts/restore_factory_android.sh`, `tools/serial_bridge.py`, `docs/*` | `MIT` / `GPL-2.0` | 本项目脚本与文档 | 构建、打包、槽位切换与移植文档 |
| `rootfs_overlay/home/kuaner/.config/i3/`、`rootfs_overlay/usr/local/share/cloudbook/archcraft-desktop-assets.tar.zst` | 见配置中的 `ARCHCRAFT-LICENSE` 与归档中的许可文件 | Archcraft 项目及各主题、图标、字体作者 | 归档内的 `ARCHCRAFT-ASSETS.md` 记录来源和版本 |
| `rootfs_overlay/home/kuaner/.config/i3/theme/rofi/power-icons/` | `MIT` | Feather Icons，Cole Bemis 和贡献者 | 五个操作图标基于 [Feather 4.29.2](https://github.com/feathericons/feather/tree/v4.29.2)，安卓机器人轮廓为本项目绘制。 |
| `rootfs_overlay/usr/share/themes/CloudBook-Greeter/gtk-3.0/clock.png`、`power.png` | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Font Awesome，Dave Gandy 和贡献者 | 取自 Symbols Nerd Font 中与桌面 Polybar 相同的字形，并按桌面配色渲染为 PNG。[Font Awesome 许可](https://fontawesome.com/license/free) |
| Android 切换器依赖 `adblib:1.3` | BSD 三条款 | Cameron Gutman、Anton Tananaev | 构建 APK 时附带 [依赖许可](tools/system-switcher/ADBLIB-LICENSE.txt) |

---

## 二、 存在版权限制 / 严禁上传至 GitHub 的闭源组件（已排除）

以下文件受原厂商业版权、NDA 固件授权或硬件签名约束，**未提交至 Git 仓库**。如需复现构建，请用户从自己购买设备的合法原厂系统中提取，或通过上游合规发行版包获取：

1. **MediaTek MT7663S 无线 / 蓝牙固件（版权归属：MediaTek Inc.）**
   * 涉及文件：`mt7663_n9_v3.bin`、`mt7663pr2h.bin`（`artifacts/firmware/` 本地留存）。
   * 风险标识：**专有二进制固件**。虽部分 MT7663 固件存在于上游 `linux-firmware`（受 `LICENCE.mediatek` 约束，仅允许按原样随驱动协议再分发），但为确保本仓库版权边界清晰，**不在仓库内直接托管二进制固件**。
   * 获取方式：在设备系统上安装 Debian 非自由固件包，或从上游 `linux-firmware` 仓库（遵循 `LICENCE.mediatek`）获取，部署至目标机 `/lib/firmware/mediatek/`。
   * 注：原厂 Android `/vendor/firmware/` 中的 `WIFI_RAM_CODE_MT7663.bin`、`EEPROM_MT7663.bin`、`mt7663_patch_e2_hdr.bin`、`woble_setting_*.bin` 及无关的 Broadcom/Cypress (`fw_RK903*`、`BCM43*`) 固件均为闭源厂商二进制文件，**严禁再分发**，且 Linux 6.12 主线 `mt7663s` 驱动不使用 Android 命名格式的固件。

2. **Rockchip / ZTE 原厂签名 Bootloader 与 Android 系统分区（版权归属：Rockchip / 中兴通讯 ZTE Corp.）**
   * 涉及文件：`factory_backup/` 下的 `mmcblk2_first8M_idbloader_gpt.img`（`RKSS`）、`uboot_a/b.img`、`trust_a/b.img`、`boot_b.img`、`super.img.zst`、`userdata.img.zst`、`logo.img`、`W6XX_W52X-RK3568-T-ALLV2.25.20P06-Firmware.zip`，以及 `artifacts/extracted_resource.img`。
   * 风险标识：**专有二进制镜像 + ZTE 开机 Logo/18 块原厂 DTB + 个人用户数据**，严禁公开上传。
   * 对 `extracted_resource.img` 的说明：`pack_boot_linux.py` 需要一份 Rockchip `RSCE` 模板用于满足原厂签名 U-Boot 的二阶段资源校验，用户应自行从本机只读导出的原厂 `boot_b` 分区中提取 `resource.img` 作为本地构建模板，切勿将他人的 `resource.img` 上传至公开仓库。

3. **ZTE / ODM 外设闭源固件与 4.19 预编译内核模块**
   * 涉及文件：原厂 OTA 内附的触控板固件（`HTX_620D.dump`、`YML_620D.bin`、`SA_620D.bin`）、中颖键盘 MCU 固件（`KBC_SH68F83Q_*.hex`）、摄像头固件（`camera_*.bin`）及 Android 4.19 专有 `.ko` 模块。
   * 风险标识：**专有芯片固件，不可再分发**。主线 Linux 6.12 直接使用设备既有片上固件与开源内核驱动，无需且不应包含这些文件。

4. **个人隐私与凭据隔离**
   * 本机私有恢复 SSH 密钥（`rootfs_build/private/`）、真实设备序列号与局域网 IP 均已剥离或列入 `.gitignore`。
