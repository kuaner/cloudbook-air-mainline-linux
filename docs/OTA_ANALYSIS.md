# W6XX OTA 包分析报告（V2.25.20P06）

> 分析对象：`W6XX_W52X-RK3568-T-ALLV2.25.20P06-Firmware.zip`（906 MB，2025-10-19 构建）
> 设备代号：`rk3568_r_w600d2`（同族 W6XX/W52X，产品列表含 W621D，不含 W620D）
> 结论仅来自离线解析，未连接任何设备。
>
> **范围**：本机实际运行的是 `V2.24.30P6-20241112`（vendor APK `V2.24.30P5`），不是这份 P06 镜像。§1、§7、§9 是本机实测；其余包级/内核级事实只适用于 P06。本机 ADB、ACS、遥测与包列表以 `DEVICE_MONITORING_AUDIT.md`、`THIRD_PARTY_PACKAGE_AUDIT.md`、`SECURITY_ASSESSMENT.md` 的真机结论为准。

## 1. ADB 的启动条件（本机实测）

本机是 `userdebug`，标准 Android 设置即可拉起 adbd；`persist.vendor.adbmode.enable` 不是持续门控。

| 项 | 本机值/行为 |
| :--- | :--- |
| `ro.build.type` | `userdebug` |
| `ro.debuggable` | `1` |
| `ro.adb.secure` | 空 |
| `service.adb.root` | `1` |
| `service.adb.tcp.port` | `5555` |
| `settings global adb_enabled` | `1` |
| `settings global development_settings_enabled` | `1` |
| `persist.vendor.adbmode.enable` | `0` |
| `runtime.zte.adbmode` | 空 |
| `adbDataEnc.zip` | 不存在 |
| `init.svc.zted` | `stopped`（one-shot） |

`/vendor/bin/zted` 只在 `sys.boot_completed=1` 时执行一次：

```sh
adbmode_enable=`getprop persist.vendor.adbmode.enable`
if [ $adbmode_enable != "1" ]; then
    stop adbd
fi
```

执行完 `zted` 退出（`oneshot`），不会在之后的标准 adb 开关时再次执行。因此开机后通过设置打开 USB 调试/无线调试，adbd 会重新启动，`persist.vendor.adbmode.enable=0` 不会继续拦截。

`adbDataEnc.zip` 不存在，当前 adbd 不是由 `uSmartSettings` 的授权包拉起，而是 AOSP 标准 adb 开关。

## 2. 升级 / 管理接口（P06 离线包，非本机）

| 用途 | 地址 |
| :--- | :--- |
| 设备侧升级清单 | `http://218.98.6.253:28002/vops-autoops-cpe/download/images/W600DSE_CTCC/update.json` |
| TR-069 远程管理 (ACS) | `http://218.98.6.253:28002/vops-autoops-protocol/web/tr069` |
| 遥测上报（vendor） | `/vops-autoops-http/terminal/cloud/{boot,alarm,event,period}` |
| 公共 CDN（平铺） | `http://ctmcdn.zx.zte.com.cn/vops-autoops-cpe/download/images/<文件名>.zip` |

> 本机 ACS 是 `https://ecloudbook.cmecloud.cn:28897/...`，遥测是 `218.98.6.253:28001`；上表只属于 P06 离线镜像。

- 公共 CDN 上本包 `Content-Length = 949,486,641`，与本地文件一致。
- 同前缀 `P05/P07/P08` 均 404，**P06 是该 CDN 上当前最新**。
- CDN 不提供目录枚举、不托管 `update.json`（404 / API 错误 JSON）。
- 内网升级主机 `218.98.6.253:28002` 从外部不可达。
- `ro.build.version.prefix=W6XX_W52X-RK3568-T` 是产品包的公共前缀。

## 3. 对 Linux 移植的直接价值

1. **eDP PHY 节点与仓库 DTS 完全一致**（验证补丁 `0009` 的 DT 定义）：

   ```dts
   edp-phy@fdcb0000 {
       compatible = "rockchip,rk3568-edp-phy";
       reg = <0x00 0xfdcb0000 0x00 0x8000>;
       clock-names = "refclk", "pclk";
       reset-names = "apb";
       #phy-cells = <0x00>;
   };
   ```

2. **eDP 控制器节点**：`edp@fe0c0000`，clocks `dp/pclk/spdif/hclk`，resets `dp/apb`，`phys = <&edp_phy>`，`force-hpd`。
3. 13 个候选 DTB（ADC 选择）均为 `Rockchip RK3568 EVB1 DDR4 V10 Board`；**不含 1920×1200 eDP 时序**，说明面板参数在厂商内核驱动中，与既有结论一致。
4. 外设变体：`southchip,sc2021`（9 个 DTB）、`ti,bq25703`（17 个）、`rockchip,rk809`；触摸分 `hid-over-i2c` 与 `goodix,gt1x` 两类。
5. U-Boot：`2017.09 (2025-10-19)`，二进制内含 `bootcmd_usb0` / `bootcmd_mmc0` / `distro_bootcmd` / `ums mmc 0` / `rockusb`；默认 `bootcmd=boot_android`。改环境变量走 U 盘引导的路线可行。

## 4. Wi-Fi / 蓝牙（对性能问题有参考价值）

- 厂商驱动：`/vendor/lib/modules/wlan_mt7663_sdio.ko`（4.3 MB，MediaTek GEN4m，源码路径 `rockchip_wlan/mt7661/...`）、`btmtksdio.ko`、`btmtk_uart_unify.ko`。
- 加载顺序（`modules.load`）：

  ```
  wlan_mt7663_sdio_prealloc.ko
  wlan_mt7663_sdio.ko
  ...
  btmtksdio.ko
  btmtk_uart_unify.ko
  ```

  **Wi-Fi 先于蓝牙初始化**，印证了仓库补丁 `0006`（eFuse/生命周期顺序协调）的必要性。

- 配对固件（`/vendor/etc/firmware/`）：

  | 文件 | 大小 |
  | :--- | :--- |
  | `WIFI_RAM_CODE_MT7663.bin` | 448,792 B |
  | `mt7663_patch_e2_hdr.bin` | 278,558 B |
  | `EEPROM_MT7663.bin` | 1,536 B |
  | `TxPwrLimit_MT76x3.dat` | 15,936 B |
  | `woble_setting_7663.bin` | 2,503 B |
  | `wifi_mt7661.cfg` | 1,156 B |

- 本机 Android B 的 `vendor_b` 与这份 OTA 中的 N9 和 ROM patch 分别逐字节一致。Linux 7.3.5 的 W103D 驱动可加载这对原厂文件，目标名称依次为 `mt7663_n9_v3.bin`、`mt7663pr2h.bin`。内核报告 N9 构建时间 `20230629193734`，并已完成双频关联和数据传输。[真机无线验证](USB_TEST_KIT_7.3.md#wi-fi-连接验证)

## 5. 系统构建与运行时属性

- 内核 `4.19.193 #155`（2025-10-19），Android 11 `userdebug`，安全补丁停在 `2021-07-05`。
- `ro.build.version.incremental=V2.25.20P06-20251019`，`ro.build.version.QCinfo=...-20251019200514.218`。
- `verifiedbootstate=orange`、`veritymode=enforcing`、`buildvariant=userdebug`。
- `persist.sys.zram_enabled=1`、`sys.rkadb.root=0`、`ro.oem_unlock_supported=1`。
- `persist.sys.enable_rescue=0`、`persist.sys.disable_rescue=1`。
- 恢复模式 rc 中 USB 控制器为 `fcc00000.dwc3`（即 Type-C 旁的 USB-A OTG 口）。

## 6. 深入分析补充

### 6.1 24RTW 归属已确认

`config/W600DSE-T_config.zip` 内 `config.json` 的 `productTypes` **明确包含**：

```
W620D, eCloudBook Air 24RTW, ECLOUDBOOK AIR 24RTW, eCloudBook Air 24RTWA, W621D, ...
```

T / T4 / TAI / TZTE 四个渠道配置包均包含 24RTW。外层 `update.json` 的产品列表只是该构建的渠道子集，不代表不支持 24RTW。

### 6.2 渠道与 OTA 目录对照

| 配置包 | custcode | OTA 清单目录 | 文件前缀 |
| :--- | :--- | :--- | :--- |
| `W600DSE-T_config.zip` | `T` | `W600DSE-T` | `W6XX_W52X-RK3568-T-T` |
| `W600DSE-T4_config.zip` | `T4` | `W600DSE-T` | `W6XX_W52X-RK3568-T-T4` |
| `W600DSE-TZTE_config.zip` | `TZTE` | `W600DSE_THZTE` | `W6XX_W52X-RK3568-T-TZTE` |
| `W600DSE-TAI_config.zip` | `NX-TAI` | `W600DSE_THZTE` | `W6XX_W52X-RK3568-T-NX` |
| `W521D-T_config.zip` | `T` | `W521D-T` | `W6XX_W52X-RK3568-T-T` |

公共 CDN 上以这些前缀探测均返回 404，只存在 `W6XX_W52X-RK3568-T-ALLV2.25.20P06-Firmware.zip`——即本地这份包本身就是覆盖 24RTW 的那一份。

### 6.3 开机定制脚本 `/vendor/bin/zted`

- 读取 `persist.vendor.adbmode.enable`，不等于 `1` 就 `stop adbd`
- 默认输入法设为 **Google 拼音**（`com.google.android.inputmethod.pinyin`）——社区“输入法绕道进设置”的前提
- 写入 Wi-Fi/蓝牙 MAC、`ro.serialno`、`persist.sys.stbid`，并按 `custcode`（MHZJ/MHHUN 等）生成序列号
- 把 `ro.product.make.model` 覆盖到 `ro.product.model`
- 调整 HDMI/eDP 切换节点权限：`/sys/class/hdmi_screen_switch/...`、`/sys/class/edp_screen_switch/...`
- `custconf=MH` 时用 iptables 丢弃 44570 端口

### 6.4 工厂配置注入机制

`/vendor/bin/zte_set_properties.sh`：

- 挂载 `backup` 分区到 `/data/tmp`，读取 `/data/tmp/config/Factory_config`
- **逐行 `setprop`**（`key value` 格式），需存在 `/data/tmp/flag.prop` 触发
- 渠道配置包内 `install.sh` 会从 `/data/tmpconfigACS/config/Factory_config` 读取并应用，还会替换 `com.zte.usmartview` 的 `server-set-list.xml`

这意味着 `persist.vendor.adbmode.enable 1` 可以被写进工厂配置生效，但需要写入 `backup` 分区或触发生产模式。

### 6.5 框架层与特权服务

- `services.jar` 引用了 `persist.vendor.adbmode.enable`、`runtime.zte.adbmode`、`ADB_MODE_ENABLE`，以及日志 `DownloadMode call reboot loader, adbmode:` —— 说明系统服务层持有一个“ADB 模式”状态机
- `/system/bin/zte_run_cmd_service` 是 Binder 服务 `com.zte.IZteRunCmdService`，配 `libzteruncmd_client.so`，可由系统应用以特权执行命令；`zted` 启动时就用它跑 `zte_set_properties.sh`

### 6.6 厂商内核配置（从 `kernel.img` 提取的内嵌 config，6114 项）

| 配置 | 状态 | 含义 |
| :--- | :--- | :--- |
| `CONFIG_MALI_BIFROST=y` | 开 | 闭源 Mali 驱动（非 Panfrost） |
| `CONFIG_ROCKCHIP_MPP_RKVDEC2=y` | 开 | **RK3568 HEVC/VP9 硬解** |
| `CONFIG_ROCKCHIP_MPP_RKVDEC/RKVENC/VDPU2=y` | 开 | MPP 视频编解码栈 |
| `CONFIG_USB_CONFIGFS_F_FS=y` | 开 | adb 所需的 USB function |
| `CONFIG_DRM_ROCKCHIP=y` | 开 | 厂商 DRM |
| `CONFIG_VIDEO_HANTRO` / `CONFIG_DRM_PANFROST` | 未设 | 无主线 Hantro/Panfrost |

这解释了“为什么厂商 BSP 能硬解 HEVC/VP9”：靠 MPP + rkvdec2，主线至今没有。

## 7. ADB 结论（本机）

1. 本机是 `userdebug`，`ro.debuggable=1`、`ro.adb.secure` 为空、`service.adb.tcp.port=5555`。
2. `zted` 只在 boot 时执行一次 `stop adbd`；之后标准设置里的 USB 调试/无线调试可以重新拉起 adbd。
3. `persist.vendor.adbmode.enable=0` 只是开机时的旧状态，不是持续授权门控。
4. 不需要账号、不需要厂商 `adbDataEnc.zip`、不需要改 boot/ramdisk；标准设置路径即可。
5. 激活与 adb 是独立链路；开 adb 不需要先激活。

## 8. 仍未解决

- 激活/登录界面的具体宿主应用不在 OTA 的系统镜像里（很可能在 `/data` 侧由 MDM 或预装应用提供），离线包内无法确认。
- `W620D` 在设备上的实际 `custcode` 决定使用 `W600DSE-T` 还是 `W600DSE_THZTE` 清单，需要真机 `getprop` 确认。
- 公共 CDN 仅有 CTCC 前缀的 P06 包；T 渠道包未在公网镜像中找到。

## 9. AdbManager 代码（离线 P06 包）与本机实际

离线 P06 的 `com.zte.usmartsettings` 里存在 `AdbManager`（`com.zte.usmartsetting.l.b.a`）：

1. 1 秒内连点 5 次触发（`SystemClock.uptimeMillis` 五元组窗口 1000 ms）。
2. 读取 `/data/data/com.zte.usmartsettings/adbDataEnc.zip`（或 `/data/com.zte.usmartsetting/adbDataEnc.zip`），解密为两行：SN 与可用次数。
3. 校验 SN、次数、`persist.sys.adbEnabled.sha256` 防重放、`runtime.zte.adbmode != 1`。
4. 通过后 `setprop ctl.start adbd`、`setprop runtime.zte.adbmode 1`、递减次数。
5. 另有 `persist.sys.autotest.openadb=1` 的开机自动路径。

**本机实际**：`adbDataEnc.zip` 不存在，`runtime.zte.adbmode` 为空；adbd 是标准设置打开的，不经过这条五连点授权路径。因此“没有授权包就打不开 adb”不适用于本机；本机用标准 USB 调试/无线调试即可。该 AdbManager 代码只说明 P06 包里有一条厂商授权实现，不构成本机门控。
