# 整机系统地图（W6XX / RK3568 Android 11）

> 基于 `W6XX_W52X-RK3568-T-ALLV2.25.20P06` OTA 的离线解析，不依赖真机。
> 目的：把启动链、特权通道、应用层、通信面与硬件接口一次讲清。

## 1. 启动链

```
BootROM（熔断校验）
  └─ idbloader（eMMC boot0/boot1，RKSS 签名）
      └─ U-Boot 2017.09（2025-10-19 构建，FIT 格式）
          ├─ bootcmd=boot_android ${devtype} ${devnum}
          ├─ 可用命令：bootcmd_usb0 / bootcmd_mmc0 / distro_bootcmd / ums / rockusb
          └─ 读取 misc 的 BCB 与 AvbABData 决定槽位/模式
              └─ boot.img（Android Boot Image v2，page 2048）
                  ├─ kernel 4.19.193 #155（2025-10-19）
                  ├─ ramdisk 88 MB（first-stage init，system-as-root）
                  └─ second 16.6 MB（RSCE resource，25 个板型 DTB + 开机 logo/电池图）
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
| `zted` | `sys.boot_completed=1` | root 开机脚本：写 MAC/序列号、设默认输入法、触屏 HID 初始化、`adbmode≠1` 时 `stop adbd` |
| `sshd`（`/vendor/bin/dropbeard`） | `persist.zte.sshd.enbale=1` | **root SSH，端口 23456**；默认 0（关闭），注意属性拼写是 `enbale` |
| `systemcmd` | `vendor.sys.run.cmd=*` | 以 root 执行命令（属性触发） |
| `syszteupdate` | `vendor.sys.run.syszteupdate=*` | root 升级器 |
| `UDSDaemon` / `udscheck` | `sys.boot_completed=1` | 云桌面统一数据服务（zxcloud） |
| `set_cloud_white` | `sys.boot_completed=1` | 设置云桌面应用白名单 |
| `MemMonitor` / `xiriservice` | `sys.boot_completed=1` | 内存监控 / xiri 服务 |
| `zte_driver_para` | `sys.boot_completed=1` | 写驱动参数 |
| `hdmi_in_hotplug` | `sys.boot_completed=1` | HDMI 输入热插拔 |
| `transportmode` | `sys.zte.transport.mode=1 && sys.zte.appinstall.done=1` | 工厂电池关机脚本 |

另有 `persist.internet_adb_enable=1` 与 `persist.sys.adb_enable=1` 两个属性会直接设置 `service.adb.tcp.port=5555` 并 `restart adbd`。

## 4. 特权执行通道（安全面重点）

| 通道 | 方式 | 备注 |
| :--- | :--- | :--- |
| `stop/start adbd` | 属性 `ctl.start` | uSmartSettings 授权后使用 |
| `systemcmd` | 属性 `vendor.sys.run.cmd` | root shell，任何能写属性的系统应用都可触发 |
| `syszteupdate` | 属性 `vendor.sys.run.syszteupdate` | root 升级 |
| `zte_run_cmd_service` | Binder `com.zte.IZteRunCmdService` | 系统应用可调用，`libzteruncmd_client.so` |
| `sshd` | `persist.zte.sshd.enbale` | root dropbear，23456 |

这些通道默认都不对普通应用开放（SELinux 限制），但**系统 uid 应用与 TR-069 下发都能用**。

## 5. 应用层

### 厂商应用（`/vendor/app`）

| 应用 | 包名 | 角色 |
| :--- | :--- | :--- |
| uSmartAgent | `com.zte.usmartagent` | TR-069 + MQTT 远程 + 自动下载 + 遥测 |
| uSmartView | `com.zte.usmartview` | **SPICE 云桌面客户端**（剪贴板、摄像头、麦克风、OAuth 登录端点） |
| uSmartLauncher | `com.zte.usmartlauncher` | 定制桌面/看板 |
| uSmartSettings | `com.zte.usmartsettings` | 定制设置（含 ADB 授权校验、丰富工厂/显示选项） |
| usmartinstaller | `com.zte.installer` | 静默安装；应用清单来自 `/data/launcher_apps.json`、`/data/other_apps.json`、`/data/json/launcher.json` |
| xlinkAgent | `com.zte.xlinkagent` | 远程协助 |
| ctyundesk / usmarttool / uSmartErrorCode | — | 天翼云桌面 / 诊断 / 错误码 |

### 系统应用

- `com.tyzd.mdm.client`（`/system/app`）：MDM 客户端，含 `net.memenet.tyycenter.ControlService`
- `com.zte.runcmd`：`ZteRunCmdService` 客户端封装
- AOSP `Settings`（`system_ext/priv-app`）与厂商 `uSmartSettings` 并存

## 6. 云桌面协议

uSmartView 使用 **SPICE**（1114 处符号），包含通道/剪贴板/端口协商；同时引用 `login.microsoftonline.com/common/oauth2/` 与 Microsoft 链接，说明支持 OAuth 登录与分发。

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

sysfs 节点：`/sys/class/zte_gpio_ctrl/{zmreset,zmswitch,zmpower}_gpio_status`、`/sys/class/hdmi_screen_switch/...`、`/sys/class/edp_screen_switch/...`、`/sys/class/hdmi_resolution_about/...`。

框架层钩子：`ZteFnKeyManager` / `ZteFnKeyService` / `ZteFnKeyCallback`（Fn 键）、`ZteRunCmdServiceManager`。

## 8. 通信面汇总

| 类型 | 地址 |
| :--- | :--- |
| TR-069 ACS | `218.98.6.253:28002/vops-autoops-protocol/web/tr069` |
| MQTT 引导 | `GET /device/getMqttServer?deviceId=<SN>` |
| 遥测 | `/vops-autoops-http/terminal/cloud/{boot,alarm,event,period}` |
| OTA 清单 | `.../download/images/<渠道>/update.json`（渠道见 `OTA_ANALYSIS.md` 6.2） |
| 固件 CDN | `ctmcdn.zx.zte.com.cn/vops-autoops-cpe/download/images/` |
| 网络探测 | `netcheck.ztems.com` |
| 联通云 | `dori.ours-ai.com` |

## 9. 安全状态

- AVB 已禁用（`vbmeta flags=2`，`verifiedbootstate=orange`），boot/vendor/system 可改，不触发校验。
- SELinux 编译启用（`CONFIG_DEFAULT_SECURITY=selinux`）；userdebug 构建，具体 permissive/enforcing 需真机看 `getenforce`。
- 大量厂商进程以 `uid 0 / android.uid.system` 运行，并持有 `RECOVERY`、`SYS_BOOT`、`NET_ADMIN` 等能力。
- 默认关闭项：`persist.zte.sshd.enbale=0`、`persist.vendor.adbmode.enable=0`、`persist.sys.autotest.openadb=0`。
- 默认开启项：`persist.cmss.silentupgrade.support=1`、`persist.sys.smart.upgrade.defaulturl`、`per_vops` 遥测、TR-069 ACS。

## 10. 对本项目的用处

1. **GPIO 表可直接与主线 `v29` DTS 对照**，验证摄像头/USB Hub/触控/复位时序。
2. **电池 SKU 属性**解释了不同容量机型差异，便于 DTS 适配。
3. **eDP/HDMI 切换 sysfs** 说明厂商用私有驱动处理双屏，主线需要 VOP2 方案替代。
4. **U-Boot 支持 `bootcmd_usb0`/`ums`/`rockusb`**，改环境变量实现 U 盘引导可行。
5. **厂商 MPP/rkvdec2** 是 HEVC/VP9 硬解唯一路径；主线无替代。

## 11. 待验证

- SELinux 实际模式（真机 `getenforce`）。
- `persist.internet_adb_enable` / `persist.sys.adb_enable` 是否被云端使用。
- uSmartView 的 SPICE 服务端地址（由 `server-set-list.xml` 下发，系统镜像内只有模板路径）。
- `Factory_config` 实际内容（在 backup 分区，OTA 不含）。

## 12. 厂商远程控制协议（静态分析还原）

### 12.1 TR-069 / CWMP（HTTP，非 TLS）

- ACS 地址：`persist.sys.mc.ACSAddress` = `http://218.98.6.253:28002/vops-autoops-protocol/web/tr069`
- 数据模型来自 `assets/device_cc.xml`（`CloudClient.*`），可写（`wr_attr=3`）的控制类参数包括：

| 参数 | 作用 |
| :--- | :--- |
| `CTRL.Reboot.Reboot` / `RebootCtrl` / `Delay` / `CtrlMode` | 远程重启 |
| `CTRL.PowerOff.*` | 远程关机 |
| `CTRL.LockSwitch.LockSwitchCtrl` | 设备锁定 |
| `CTRL.MsgShow.MsgInfo / MsgIsAuto / MsgTime / MsgOsUser` | 强制弹窗 |
| `Config.LoginMode.CurrentLoginMode` | 登录/激活模式 |
| `Config.AutoLoginUsername / AutoLoginPassword` | 自动登录凭据 |
| `X_ZTE_App.InstallTask / UninstallTask / DownloadResult / InstallResult` | 应用静默安装/卸载 |
| `LogCtrl.SupportedCmd / Cmd / ModelName / Level / TimeInv / QoeReport` | 日志采集（带白名单字段） |
| `ManagementServer.Username / Password / ConnectionRequestURL / IQASURL` | ACS 鉴权与回连 |
| `Diagnostics.*`（IPPing / TraceRoute / DSP / FLASH） | 远程诊断 |

- 命令执行链（native → Java → root）：

```
libtr069.so  →  JNI onCallbackRunCmd(byte[])
             →  AgentUtil.runCmd(cmd + " &")
             →  RunCmdUtil.runCmd()
                  ├─ ZteRunCmdServiceManager.zteRunCmd()   (Binder, /system/bin/zte_run_cmd_service)
                  └─ setprop vendor.sys.run.cmd <cmd>      (init 服务 systemcmd)
```

### 12.2 zlink 远程服务（MQTT）

- 引导（HTTP）：`POST /device/auth`（`deviceId` + `authKey` + `pwdSignatureType`）、`GET /device/getMqttServer?deviceId=<SN>`
- 主题：`/sys/method/<method>/req` 与 `/sys/method/<method>/resp`，JSON（cJSON）
- 方法集（来自 JNI 回调）：`unbind`、`reboot`、`poweroff`、`setSystemTime`/`getSystemTime`、`setTimeZone`/`getTimeZone`、`ntpServer`、`setWifiEnabled`/`getWifiEnabled`、`setResolution`
- OTA 通道回调：`InstallOta`、`UpdateUrl`、`UpdateShellUrl`（下载并执行 shell）、`CancelUpdate`、`SilentDownloadStatus`、`RunCmd`
- 传输：mosquitto 支持 TLS（CA/客户端证书），但引导阶段是明文 HTTP
- 引导主机来自 CAG/CloudServerInfo 相关属性（`persist.sys.login.cag.address/port` 等），需真机 `getprop` 确认

### 12.3 局域网 mock 的可行边界

- ACS 是 **明文 HTTP + IP 直连**（不是域名），DNS 无法重定向；需要网关静态路由/NAT 或透明 TCP 代理，或局域网 ARP 重定向。
- 可用开源 CWMP 服务端（如 GenieACS）或自建 SOAP 桩，匹配 `/vops-autoops-protocol/web/tr069` 路径；CPE 会先发 `Inform`，再接受 `SetParameterValues` / `Download` 等 RPC。
- 只在自有设备、自有网络、且不劫持他人流量时测试；不要暴露到公网。
- **不要使用被攻破的 CA 私钥伪造证书**：这是滥用全球信任链，会波及所有信任该 CA 的系统，也超出设备所有权范围；本场景 ACS 为 HTTP，本来就不需要证书。