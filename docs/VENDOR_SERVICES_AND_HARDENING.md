# 厂商组件盘点与本地加固

> **对象**：本机 `eCloudBook Air 24RTW`（`rk3568_r_w600d2`），Android 11，`slot_b`
> **依据**：真机 `pm`/`dumpsys`/APK/日志，只读检查  
> **约束**：本文是清单与建议；没有执行任何 `pm disable`、`setprop`、`dd`。

## 1. 组件盘点（本机实际安装）

| 包 | 位置 | uid/签名 | 角色 |
| :--- | :--- | :--- | :--- |
| `com.zte.usmartagent` | `/vendor/app/uSmartAgent_*` | 1000 / 平台 | TR-069/MDM、遥测、远程诊断 |
| `com.zte.usmartlauncher` | `/vendor/app/uSmartLauncher_*` | 1000 / 平台 | HOME/LAUNCHER、激活、MQTT 云管控、运营商上报 |
| `com.zte.usmartsettings` | `/vendor/app/uSmartSettings_*` | 1000 / 平台 | 默认 Settings、激活、重启/关机、Wi-Fi |
| `com.zte.usmartupdate` | `/vendor/app/uSmartUpdate_*` | 1000 / 平台 | 厂商 OTA、激活服务、摄像头/EC 固件升级 |
| `com.zte.usmartview` | `/vendor/app/uSmartView_*` | 1000 / 平台 | SPICE 云桌面、USB/摄像头/麦克风重定向 |
| `com.zte.usmarttool` | `/vendor/app/usmarttool_*` | 1000 / 平台 | 诊断工具 |
| `com.zte.installer` | `/vendor/app/usmartinstaller_*` | 1000 / 平台 | 厂商静默安装 |
| `com.cmss.cloudcomputer` | `/data/app/...` | 10108 / 中国移动云 | 云桌面登录/客户端 |
| `com.google.android.inputmethod.pinyin` | `/system/priv-app/ggsrf` | 10070 / 平台重签 | 默认中文输入法，Google 账号/联系人同步 |
| `com.DeviceTest` | `/system/priv-app/DeviceTest` | 1000 / 平台 | 工厂硬件测试 |
| `com.cghs.stresstest` | `/system/priv-app/StressTest` | 1000 / 平台 | 压力测试 |
| `com.android.rk` | `/system/app/RkExplorer` | 1000 / 平台 | 文件管理器 |
| `org.chromium.webview_shell` | `/product/app/Browser2` | 10093 / AOSP 测试 | WebView 测试壳 |

**本机没有** `com.tyzd.mdm.client`、`com.zte.xlinkagent`、`ctyundesk`；这些名字不出现在 `pm list packages` 中。`com.sohu.inputmethod.sogou.zte` 只在 `enabled_input_methods` 残留，`pm path` 为空，不是已安装包。

## 2. 默认角色

- **HOME**：`com.android.provision`（当前 setup）、`com.zte.usmartlauncher`、`com.cmss.cloudcomputer`（`LoginActivity`）、`com.android.settings.FallbackHome`。
- **SETTINGS**：只有 `com.zte.usmartsettings/SettingRouterActivity`；AOSP Settings 只有 `FallbackHome`，不是完整设置。
- **IME**：默认 Google 拼音；LatinIME 可用；Sogou 残留未安装。
- **安装器**：`pm get-install-location=auto`；AOSP `com.android.packageinstaller` 与 `com.zte.installer` 都在，未解析出唯一默认 APK 打开器。

## 3. 通信面（本机实测）

| 用途 | 地址/机制 |
| :--- | :--- |
| TR-069 ACS | `https://ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069`（HTTPS） |
| TR-069 回连 | `0.0.0.0:8000`，`ConnectionRequestURL=http://192.168.123.128:8000` |
| STUN | `stunclient` 向 ACS 侧发 UDP |
| 明文遥测 | `http://218.98.6.253:28001/vops-autoops-http/terminal/cloud/period`（`evntpost.txt` 实证） |
| 桌面 MQTT | `ssl://mqtt-tls-none.starrycfn.com:20442` |
| 设备密钥 | `https://device.starrycfn.com:20442/v1/customer/device/secret/info` |
| 智能升级 | `persist.sys.smart.upgrade.defaulturl` → `http://36.133.134.241:28002/.../update.json` |
| 云桌面 | `desktop.cucloud.cn`、`uac.zte.com.cn`、`cpd.zte.com.cn:8899`、`sslvpnsf.zte.com.cn`、`zxapp.ztems.com` |
| 网络探测 | `http://netcheck.ztems.com/generate_204_cloud` |
| 时间 | `time.zte.com.cn`、Aliyun/NTSC NTP |
| 系统 | `server_ip=cloud.zte.com.cn`、`runtime.smt.mc.stunaddr=ecloudbook.cmecloud.cn` |

> 这些是本机配置/日志/APK 字符串中的实际值；离线 `W6XX_W52X-RK3568-T-ALLV2.25.20P06` 是另一份镜像，其 `218.98.6.253:28002` ACS 不能套到本机。

## 4. 可能采集的数据

- 设备身份：SN、factorySN、MAC、`ro.product.productid`、机型。
- 运行状态：电量/充电/网络/开机/告警/周期事件。
- 触摸/手势统计：`evntpost` 的 `subEventCode=501`。
- 应用与云桌面：静默安装能力；云桌面可访问屏幕、键鼠、摄像头、麦克风、USB。
- 日志：syslog/WPA/mem/radio 本地采集。

## 5. 禁用矩阵

### 5.1 可禁用（不影响默认 UI/激活）

```text
com.zte.usmarttool
com.zte.usmartview        # 不用云桌面
com.DeviceTest
com.cghs.stresstest
com.android.rk
org.chromium.webview_shell
```

### 5.2 条件禁用

| 包 | 前提 | 失去的功能 |
| :--- | :--- | :--- |
| `com.cmss.cloudcomputer` | 不用中国移动云桌面 | 云桌面登录/客户端 |
| `com.zte.usmartupdate` | 激活完成、不用厂商 OTA/外设固件 | 厂商 OTA、激活服务、摄像头/EC 升级 |
| `com.zte.installer` | 不需要厂商静默安装 | 静默安装/更新；AOSP 安装器仍在 |
| `com.google.android.inputmethod.pinyin` | 已设 LatinIME/其它输入法 | 默认中文输入法、Google 账号/联系人同步 |
| `com.zte.usmartagent` | 激活完成、不要远程管理/售后诊断 | TR-069、遥测、远程支持；`PERSISTENT` 可能被重新拉起 |

### 5.3 不可先禁用

| 包 | 原因 |
| :--- | :--- |
| `com.zte.usmartsettings` | 唯一 Settings + 激活 + 重启/关机入口 |
| `com.zte.usmartlauncher` | HOME/LAUNCHER + 激活；无 AOSP Launcher3；需先装替代 Launcher |
| `com.android.provision` | 当前 setup HOME；setup 完成前禁用会中断初始化 |
| AOSP core（SystemUI/Bluetooth/phone/providers/webview/networkstack 等） | 系统运行必需 |

### 5.4 正确的加固顺序

1. **网络侧先封**（OpenWrt DNS/firewall），不动机器；确认不影响自己要用的功能。
2. 禁用 5.1 的 6 个包。
3. 不用云桌面：`com.cmss.cloudcomputer`、`com.zte.usmartview`。
4. 不用厂商 OTA/静默安装：`com.zte.usmartupdate`、`com.zte.installer`。
5. 激活完成、不要远程支持：`com.zte.usmartagent`；观察是否被重新拉起。
6. 安装替代 Launcher/Settings/输入法后，才考虑 `usmartlauncher`/`usmartsettings`/Google 拼音。
7. **不要先动 `usmartsettings`/`usmartlauncher`**；否则可能失去设置/桌面/激活。

## 6. root/init 通道

| 通道 | 机制 | 当前状态 |
| :--- | :--- | :--- |
| `systemcmd` | `vendor.sys.run.cmd` → root 执行 | 属性空，服务 stopped |
| `syszteupdate` | `vendor.sys.run.syszteupdate` → root 执行 | 属性空，服务 stopped |
| `sshd` | `dropbeard -p 23456`，`persist.zte.sshd.enbale=1` | 0/stopped |
| `zted` | boot 时 one-shot；adbmode 非 1 则 `stop adbd` | stopped |
| `ztelogcat`/`MemMonitor` | 本地日志 | 运行中 |
| `update_engine` | A/B OTA | 运行中 |

不要停 `zted`：它负责 MAC/序列号/输入法/触屏初始化；但它的 adb 拦截只在开机执行一次。

## 7. 验证（只读）

```bash
adb shell pm list packages | grep -E 'com\.zte|cmss|DeviceTest|stresstest|android\.rk|pinyin'
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package resolve-activity -a android.settings.SETTINGS
adb shell dumpsys package com.zte.usmartagent | grep -E 'versionName|userId|sharedUser'
adb shell ss -tupn
adb shell getprop | grep -E 'adbmode|adb|sshd|smart.upgrade|ACS'
```

## 8. 风险与合规

- 设备是“移动云电脑”形态，账号激活/云桌面是运营商产品的一部分；禁用 MDM/激活可能影响服务条款/保修。
- 关闭厂商 OTA 后安全补丁不再更新（本机补丁本就停在 2021-07-05）。
- 保留 Android 本地可用性的最低集是：`usmartsettings`、`usmartlauncher`、AOSP core；`usmartupdate`/`usmartinstaller` 视是否用厂商 OTA 决定；`usmartagent` 是隐私与远程支持之间的取舍。