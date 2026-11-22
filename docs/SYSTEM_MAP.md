# 整机系统地图（W620D / RK3568 Android 11）

> **对象**：本机 ZTE `eCloudBook Air 24RTW`（`rk3568_r_w600d2`），Android 11，`slot_b`，系统 `V2.24.30P6-20241112`
> **依据**：真机只读检查 + 本机 APK/日志；启动链/分区/DTB 部分参考同族 P06 离线包与真机 `lpdump`
> **边界**：没有写设备；包/ACS/遥测结论以本机为准。

## 1. 启动链

```
BootROM（熔断校验）
  └─ idbloader（eMMC boot0/boot1，RKSS 签名）
      └─ U-Boot 2017.09（FIT 格式）
          ├─ bootcmd=boot_android ${devtype} ${devnum}
          ├─ 可用命令：bootcmd_usb0 / bootcmd_mmc0 / distro_bootcmd / ums / rockusb
          └─ 读取 misc 的 BCB 与 AvbABData 决定槽位/模式
              └─ boot.img（Android Boot Image v2，page 2048）
                  ├─ kernel 4.19.193 #155
                  ├─ ramdisk 88 MB（first-stage init，system-as-root）
                  └─ second 16.6 MB（RSCE resource，板型 DTB + logo/电池图）
                      └─ /system/bin/init → system-as-root → vendor init → zted
```

关键 cmdline：`console=ttyFIQ0 androidboot.hardware=rk30board androidboot.verifiedbootstate=orange buildvariant=userdebug`。

## 2. 分区（20 个 GPT 分区）

| 分区 | 用途 |
| :--- | :--- |
| p1 `security` / p2-3 `uboot_a/b` / p4-5 `trust_a/b` | 签名链，**禁止写入** |
| p6 `misc` | BCB + AvbABData（槽位与试启动计数） |
| p7-8 `dtbo_a/b` | 仅 bootargs_ext / reboot_mode |
| p9-10 `vbmeta_a/b` | AVB 已禁用（`flags=2`，orange） |
| p11-12 `boot_a/b` | 160 MB，Android boot v2 |
| p13 `backup` | 1.5 GB，含 `config/Factory_config`（工厂属性注入） |
| p14 `cache` / p15 `metadata` / p16 `baseparameter` / p17 `logo` / p18 `zteparameter` | 杂项与中兴定制 |
| p19 `super` | 动态分区（system/vendor/product/system_ext） |
| p20 `userdata` | ~22 GB |

## 3. Init 层（`/vendor/etc/init/hw/init.zte.rc`）

| 服务 | 触发 | 说明 |
| :--- | :--- | :--- |
| `zted` | `sys.boot_completed=1` | root 开机脚本：写 MAC/序列号、设默认输入法、触屏 HID 初始化；`adbmode≠1` 时 `stop adbd`，**只执行一次** |
| `sshd`（`/vendor/bin/dropbeard`） | `persist.zte.sshd.enbale=1` | root SSH，端口 23456；默认 0 |
| `systemcmd` | `vendor.sys.run.cmd=*` | 以 root 执行命令 |
| `syszteupdate` | `vendor.sys.run.syszteupdate=*` | root 升级器 |
| `UDSDaemon` / `udscheck` | `sys.boot_completed=1` | 云桌面统一数据服务 |
| `set_cloud_white` | `sys.boot_completed=1` | 设置云桌面应用白名单 |
| `MemMonitor` / `xiriservice` | `sys.boot_completed=1` | 内存监控 / xiri 服务 |
| `zte_driver_para` | `sys.boot_completed=1` | 写驱动参数 |
| `hdmi_in_hotplug` | `sys.boot_completed=1` | HDMI 输入热插拔 |
| `transportmode` | `sys.zte.transport.mode=1 && sys.zte.appinstall.done=1` | 工厂电池关机脚本 |

本机 `/vendor/etc/init/hw/init.rk30board.rc` 分别处理两个开关。`persist.internet_adb_enable` 为 `1` 时将 `service.adb.tcp.port` 设为 `5555`，为 `0` 时设为 `0`，两种情况都会重启 `adbd`。`persist.sys.adb_enable` 为 `1` 时重启 `adbd`，为 `0` 时停止 `adbd`。Android 开发者选项中的「无线调试」单独控制动态 TLS 端口。

## 4. 特权执行通道

| 通道 | 方式 | 本机状态 |
| :--- | :--- | :--- |
| `systemcmd` | 属性 `vendor.sys.run.cmd` → root | 属性空，服务 stopped |
| `syszteupdate` | 属性 `vendor.sys.run.syszteupdate` → root | 属性空，服务 stopped |
| `zte_run_cmd_service` | Binder `com.zte.IZteRunCmdService` | 系统应用可调用 |
| `sshd` | `persist.zte.sshd.enbale` | 0/stopped |
| adbd | 普通网络 ADB | `5555`，RSA 主机密钥认证，默认 `shell`（2026-11-21 重启实测） |

这些通道默认不对普通应用开放；系统 uid 应用和 TR-069 侧具备使用条件。

## 5. 应用层（本机实际）

### 厂商应用（`/vendor/app`，平台 uid 1000）

| 应用 | 包名 | 角色 |
| :--- | :--- | :--- |
| uSmartAgent | `com.zte.usmartagent` | TR-069/MDM + 遥测 + 远程诊断 |
| uSmartView | `com.zte.usmartview` | SPICE 云桌面（屏幕/键鼠/USB/摄像头/麦克风） |
| uSmartLauncher | `com.zte.usmartlauncher` | HOME/LAUNCHER + 激活 + MQTT 云管控 + 运营商上报 |
| uSmartSettings | `com.zte.usmartsettings` | 默认 Settings + 激活 + 重启/关机 |
| uSmartUpdate | `com.zte.usmartupdate` | 厂商 OTA + 激活服务 + 外设固件 |
| usmarttool | `com.zte.usmarttool` | 诊断工具 |
| usmartinstaller | `com.zte.installer` | 静默安装 |

### 其他预装/第三方

| 应用 | 包名 | 角色 |
| :--- | :--- | :--- |
| 中国移动云桌面 | `com.cmss.cloudcomputer` | 云桌面登录/客户端（普通 uid 10108） |
| Google 拼音 | `com.google.android.inputmethod.pinyin` | 默认中文输入法（uid 10070） |
| DeviceTest | `com.DeviceTest` | 工厂硬件测试 |
| StressTest | `com.cghs.stresstest` | 压力测试 |
| RkExplorer | `com.android.rk` | 文件管理器 |
| WebView Shell | `org.chromium.webview_shell` | WebView 测试壳 |

**本机没有** `com.tyzd.mdm.client`、`com.zte.xlinkagent`、`ctyundesk`、`com.zte.runcmd`；AOSP Settings 只承担 `FallbackHome`，默认 Settings 是 `uSmartSettings`。

## 6. 云桌面协议

- `com.zte.usmartview` 使用 **SPICE**，含通道/剪贴板/端口协商、USB/摄像头/麦克风重定向、QoE 上报。
- `com.cmss.cloudcomputer` 使用中国移动云桌面 SDK（`libVdconnSdk`、`libVdpServer`、`libcag`、gRPC、ffmpeg）。
- 两者是独立客户端；不用云桌面时可分别禁用。

## 7. 硬件接口（来自厂商 DTB 的 `zte-gpio-ctl` 节点）

| 属性 | 引用 | 作用 |
| :--- | :--- | :--- |
| `gpio-cameraen` | phandle 0x3f idx 2 | 摄像头使能 |
| `gpio-usb2hubrst` | 0x36 idx 8 | USB2 Hub 复位 |
| `gpio-masterresten` | 0x36 idx 20 | 主复位 |
| `gpio-ecpoweren` | 0x3f idx 1 | EC 供电 |
| `gpio-smallresten` | 0x3f idx 0 | 小复位 |
| `gpio-pwmenable` | 0x3f idx 9 | PWM 使能 |
| `gpio-btreset` | 0x85 idx 15 | 蓝牙复位 |
| `gpio-wifireset` | 0x36 idx 13 | Wi-Fi 复位 |
| `gpio-powertp` | 0x3f idx 12 | 触控板供电 |
| `gpio-keyboardled` | 0x85 idx 1（低有效） | 键盘灯 |
| `gpio-keyboarpower` | 0x36 idx 23 | 键盘供电 |

其它 DTB 属性：`zte_usbhub_reset_gpio`、`zte_hwversion`、`zte-usblimited`、`zte,smart_battery`、`zte-super-lowpower`，以及电池 SKU：`gf-25wh/gf-28wh/gf-38wh/gf-40wh/gf2-38wh/gf3-38wh/scud-25wh/scud-38wh`。

sysfs：`/sys/class/zte_gpio_ctrl/{zmreset,zmswitch,zmpower}_gpio_status`、`/sys/class/hdmi_screen_switch/...`、`/sys/class/edp_screen_switch/...`、`/sys/class/hdmi_resolution_about/...`。

框架层钩子：`ZteFnKeyManager` / `ZteFnKeyService` / `ZteFnKeyCallback`（Fn 键）、`ZteRunCmdServiceManager`。

## 8. 通信面（本机实测）

| 类型 | 地址 |
| :--- | :--- |
| TR-069 ACS | `https://ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069` |
| TR-069 回连 | `0.0.0.0:8000` |
| 遥测 | `http://218.98.6.253:28001/vops-autoops-http/terminal/cloud/period` |
| 桌面 MQTT | `ssl://mqtt-tls-none.starrycfn.com:20442` |
| 设备密钥 | `https://device.starrycfn.com:20442/v1/customer/device/secret/info` |
| OTA 清单 | `persist.sys.smart.upgrade.defaulturl` → `http://36.133.134.241:28002/.../update.json` |
| 云桌面 | `desktop.cucloud.cn`、`uac.zte.com.cn`、`cpd.zte.com.cn:8899`、`sslvpnsf.zte.com.cn`、`zxapp.ztems.com` |
| 网络探测 | `http://netcheck.ztems.com/generate_204_cloud` |
| 时间 | `time.zte.com.cn`、Aliyun/NTSC NTP |

## 9. 安全状态

- AVB 已禁用（`vbmeta flags=2`，`verifiedbootstate=orange`），boot/vendor/system 可改，不触发校验。
- SELinux 当前 **Permissive**（真机 `getenforce`）。
- 厂商应用以 `android.uid.system`（uid 1000）共享，持有 `INSTALL_PACKAGES`、`RECOVERY`、`MASTER_CLEAR`、`DEVICE_POWER`、`NETWORK_SETTINGS` 等平台权限。
- 最初审计时，ADB 的 `ro.adb.secure` 为空，`service.adb.root=1`。11 月 21 日配置了普通网络 ADB 的开机启动和密钥验证。[重启实测](LINUX_USB_BOOT_FAILURE_2026-11-21.md#31-android-b-重启后的普通网络-adb)
- 默认关闭：`persist.zte.sshd.enbale=0`、`persist.vendor.adbmode.enable=0`、`persist.sys.autotest.openadb=0`。
- 默认开启：`persist.cmss.silentupgrade.support=1`、`persist.sys.smart.upgrade.defaulturl`、遥测、TR-069 ACS。
- root 通道：`systemcmd`/`syszteupdate`/`sshd` 均由属性触发。

## 10. 对本项目的用处

1. **GPIO 表可直接与主线 `v29` DTS 对照**，验证摄像头/USB Hub/触控/复位时序。
2. **电池 SKU 属性**解释不同容量机型差异，便于 DTS 适配。
3. **eDP/HDMI 切换 sysfs** 说明厂商用私有驱动处理双屏，主线需要 VOP2 方案替代。
4. **U-Boot 支持 `bootcmd_usb0`/`ums`/`rockusb`**，改环境变量实现 U 盘引导可行。
5. **厂商 MPP/rkvdec2** 是 HEVC/VP9 硬解唯一路径；主线无替代。

## 11. 待验证

- `persist.internet_adb_enable` / `persist.sys.adb_enable` 是否被云端使用。
- uSmartView 的 SPICE 服务端地址（由 `server-set-list.xml` 下发）。
- `Factory_config` 实际内容（在 backup 分区）。
- TR-069 HTTPS 证书校验策略（未做 MITM/证书审计）。

## 12. 厂商远程控制协议

### 12.1 TR-069 / CWMP（HTTPS）

- ACS：`persist.sys.mc.ACSAddress` = `https://ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069`。
- 数据模型来自 `assets/device_cc.xml`（`CloudClient.*`），可写控制类参数包括：

| 参数 | 作用 |
| :--- | :--- |
| `CTRL.Reboot.*` / `CTRL.PowerOff.*` | 远程重启/关机 |
| `CTRL.LockSwitch.LockSwitchCtrl` | 设备锁定 |
| `CTRL.MsgShow.*` | 强制弹窗 |
| `Config.LoginMode.CurrentLoginMode` | 登录/激活模式 |
| `Config.AutoLoginUsername / AutoLoginPassword` | 自动登录凭据 |
| `X_ZTE_App.InstallTask / UninstallTask` | 应用静默安装/卸载 |
| `LogCtrl.*` | 日志采集 |
| `ManagementServer.*` | ACS 鉴权与回连 |
| `Diagnostics.*` | 远程诊断 |

- 命令执行链：

```
libtr069.so  →  JNI onCallbackRunCmd(byte[])
             →  AgentUtil.runCmd(cmd + " &")
             →  RunCmdUtil.runCmd()
                  ├─ ZteRunCmdServiceManager.zteRunCmd()   (Binder)
                  └─ setprop vendor.sys.run.cmd <cmd>      (init systemcmd)
```

### 12.2 zlink 远程服务（MQTT）

- 引导：`POST /device/auth`、`GET /device/getMqttServer?deviceId=<SN>`。
- 主题：`/sys/method/<method>/req`、`/sys/method/<method>/resp`，JSON。
- 方法：`unbind`、`reboot`、`poweroff`、`setSystemTime`/`getSystemTime`、`setTimeZone`/`getTimeZone`、`ntpServer`、`setWifiEnabled`/`getWifiEnabled`、`setResolution`。
- OTA 回调：`InstallOta`、`UpdateUrl`、`UpdateShellUrl`、`CancelUpdate`、`SilentDownloadStatus`、`RunCmd`。
- 传输：`libmosquitto`/`libzlink` 支持 TLS；broker 由配置/发现决定。

### 12.3 边界

- TR-069 是 HTTPS；证书校验行为未审计，不做 MITM。
- 不在自有设备/自有网络之外测试厂商协议；不提供利用工具。
- 不要使用泄露的 CA 私钥伪造证书。
