# 真机第三方 / vendor 包审计（只读）

> **设备**：ZTE `eCloudBook Air 24RTW`（`rk3568_r_w600d2`），Android 11，`slot_b`  
> **访问**：无线 ADB `192.168.123.128:5555`，`adb root` 后只读检查  
> **约束**：没有执行 `pm disable`、`setprop`、`dd`、`am`；所有结论来自 `pm`、`dumpsys`、`apkanalyzer`、APK 字符串和运行日志。  
> **范围**：本机 `pm list packages` 共 143 个；`pm list packages -3` 只有 `com.cmss.cloudcomputer`。下面列出全部非 AOSP 的预装/vendor/第三方包；AOSP 核心（SystemUI、Bluetooth、phone、providers、webview、permissioncontroller、networkstack 等）不在此矩阵中，也不建议禁用。

---

## 0. 结论

1. 非 AOSP 的包分三类：
   - **ZTE vendor 平台应用**：7 个，全部与 `android.uid.system`（uid 1000）共享，平台签名。
   - **中国移动云桌面**：`com.cmss.cloudcomputer`，普通应用 uid 10108，中国移动云能力中心签名。
   - **预装/测试工具**：Google 拼音、Rockchip DeviceTest/StressTest/RkExplorer、WebView 测试壳。
2. **不能把“禁用”当成简单的开关**：
   - `com.zte.usmartsettings` 是**默认 Settings + 激活 + 重启/关机**入口；AOSP Settings 在这台机器上只有 `FallbackHome`，没有可用的 SETTINGS Activity。
   - `com.zte.usmartlauncher` 是 **HOME/LAUNCHER + 激活**入口；没有 AOSP Launcher3。
   - `com.cmss.cloudcomputer` 也是 HOME 候选（`LoginActivity`），是云桌面登录入口。
   - 禁用上述任一，可能直接失去设置/桌面/激活界面。
3. **最该优先处理的是网络与平台权限，而不是先 disable**：
   - 平台 uid 1000 的 vendor 应用拥有系统级权限；disable 一个应用不会削减共享 uid 的权限。
   - TR-069、MQTT、明文遥测是主要外联面；先在网关侧阻断，再决定 disable。
4. **ADB 是 userdebug 标准路径**：`ro.debuggable=1`、`settings adb_enabled=1`、`service.adb.tcp.port=5555`、`service.adb.root=1`、`ro.adb.secure` 为空；`adbDataEnc.zip` 不存在。`persist.vendor.adbmode.enable=0` 没有阻止 adbd，因为 `zted` 只在 boot 时执行一次 `stop adbd`。

---

## 1. 分类与签名

| 类别 | 包 | 路径 | uid / 签名 |
| :--- | :--- | :--- | :--- |
| ZTE 平台 | `com.zte.usmartagent` | `/vendor/app/uSmartAgent_*` | uid 1000 / 平台证书 `2d370c21…` |
| ZTE 平台 | `com.zte.usmartlauncher` | `/vendor/app/uSmartLauncher_*` | uid 1000 / 平台证书 |
| ZTE 平台 | `com.zte.usmartsettings` | `/vendor/app/uSmartSettings_*` | uid 1000 / 平台证书 |
| ZTE 平台 | `com.zte.usmartupdate` | `/vendor/app/uSmartUpdate_*` | uid 1000 / 平台证书 |
| ZTE 平台 | `com.zte.usmartview` | `/vendor/app/uSmartView_*` | uid 1000 / 平台证书 |
| ZTE 平台 | `com.zte.usmarttool` | `/vendor/app/usmarttool_*` | uid 1000 / 平台证书 |
| ZTE 平台 | `com.zte.installer` | `/vendor/app/usmartinstaller_*` | uid 1000 / 平台证书 |
| 中国移动云 | `com.cmss.cloudcomputer` | `/data/app/.../base.apk` | uid 10108 / 中国移动云能力中心证书 `b2f2014e…` |
| Google | `com.google.android.inputmethod.pinyin` | `/system/priv-app/ggsrf` | uid 10070 / 平台证书（预装重签） |
| Rockchip 测试 | `com.DeviceTest` | `/system/priv-app/DeviceTest` | uid 1000 / 平台证书 |
| Rockchip 测试 | `com.cghs.stresstest` | `/system/priv-app/StressTest` | uid 1000 / 平台证书 |
| Rockchip 工具 | `com.android.rk` | `/system/app/RkExplorer` | uid 1000 / 平台证书 |
| AOSP 测试 | `org.chromium.webview_shell` | `/product/app/Browser2` | uid 10093 / AOSP 测试证书 `cf2ab381…` |

> 除 `com.cmss.cloudcomputer`、Google 拼音、WebView 测试壳外，其余 vendor/Rockchip 包都在 `android.uid.system` 共享用户里，运行在 uid 1000。`dumpsys` 里的 `INSTALL_PACKAGES`、`RECOVERY`、`MASTER_CLEAR`、`DEVICE_POWER`、`NETWORK_SETTINGS` 等是共享 uid 的权限集合；这些包都能使用平台能力。

---

## 2. 默认角色（实测）

### 2.1 HOME

`cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME`：

| 组件 | 包 | 说明 |
| :--- | :--- | :--- |
| `com.android.provision.DefaultActivity` | `com.android.provision` | 当前默认 HOME；设备仍在 provisioning/setup |
| `com.zte.usmartlauncher.activity.UsmartMainActivity` | `com.zte.usmartlauncher` | 厂商桌面；setup 完成后的正常 HOME |
| `com.cmss.cloudcomputer.module.login.activity.LoginActivity` | `com.cmss.cloudcomputer` | 云桌面登录页，也声明 HOME |
| `com.android.settings.FallbackHome` | `com.android.settings` | AOSP 兜底 Home，不是完整桌面 |

**没有 AOSP Launcher3。** 如果同时禁用 `usmartlauncher` 和 `CloudComputer`，剩下的只是 `FallbackHome`（设置应用的兜底页），不是可用桌面。

### 2.2 Settings

`cmd package resolve-activity -a android.settings.SETTINGS` 只返回：

```
com.zte.usmartsettings/com.zte.usmartsetting.mvvm.view.SettingRouterActivity
```

AOSP `com.android.settings` 在这台机器上只承担 `FallbackHome`；**没有可用的 SETTINGS Activity**。所以 `com.zte.usmartsettings` 是唯一设置入口，不能先禁用。

### 2.3 输入法

```
default_input_method = com.google.android.inputmethod.pinyin/.PinyinIME
enabled_input_methods = com.android.inputmethod.latin/.LatinIME:
                        com.google.android.inputmethod.pinyin/.PinyinIME:
                        com.sohu.inputmethod.sogou.zte/.SogouIME
```

- 默认是 **Google 拼音**。
- LatinIME 可作为替代。
- `com.sohu.inputmethod.sogou.zte` 出现在 enabled 列表，但 `pm path` 为空、包列表里没有它——**未安装，只是残留设置**。

### 2.4 安装器

- `pm get-install-location = 0[auto]`。
- `com.android.packageinstaller` 已安装（AOSP 安装器）。
- `com.zte.installer` 已安装，提供 `InstallService` 和开机接收器；用于厂商静默安装/更新。
- 没有文件时无法解析“默认 APK 打开器”；两个安装器都在。

---

## 3. 包详情

### 3.1 `com.zte.usmartagent` — TR-069 / MDM / 遥测

- 版本：`1.W622D-MB-SecureV2.24.30P5`，`/vendor/app/uSmartAgent_*`，uid 1000，`PERSISTENT`。
- 原生库：`libtr069.so`、`libacsdiscover.so`、`libmosquitto.so`、`libzlink*.so`、`libztebw.so`、`libcurl.so`、`libcrypto.so`、`libssl.so`、`libcc_msgcomm.so`。
- 组件：
  - `com.zte.ui.tr069Service.StartTr069Service`（action 同名）
  - `com.zte.usmartagent.service.MultiAppService`
  - `com.zte.ui.broadcast.BootBroadcastReceiver`（BOOT_COMPLETED / QUICKBOOT / CONNECTIVITY_CHANGE）
- 监听：`0.0.0.0:8000`（TR-069 ConnectionRequest）。
- 端点：
  - ACS：`https://ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069`
  - 遥测：`http://ctm.zte.com.cn` → `218.98.6.253:28001/vops-autoops-http/terminal/cloud/period`（`evntpost.txt` 实证）
  - MQTT/zlink：由 `libzlink`/`libmosquitto` 接入，broker 由配置/发现决定
- 能力：远程参数读写、升级、诊断、重启/恢复类命令所需的平台权限由共享 uid 提供；8000 端口是 ACS 回连入口。
- **禁用建议**：隐私优先时可在激活后禁用；会失去厂商远程管理/售后诊断。`PERSISTENT` 可能导致 `disable-user` 被系统重新拉起，需实测。

### 3.2 `com.zte.usmartlauncher` — 桌面 + 激活 + 云 MQTT

- 版本：`1.W622D-MB-SecureV2.24.30P5`，`/vendor/app/uSmartLauncher_*`，uid 1000。
- 原生库：`libdm_info.so`、`libdm_shared_library.so`、`libpaho-mqtt3as.so`、`libmbedtls.so`、`libstarryos_device_connect.so`、`libmt-jni.so`、`libiclass_proxy.so`、`libmmkv.so`、`libusmartbsp.so`。
- 组件：
  - `UsmartMainActivity`（MAIN + LAUNCHER + HOME）
  - `ActivationActivity`
  - `GlobalService`、`StartMFService`、`ReportCteiService`、`ReportCueiService`（CTCC/联通上报）
  - `cn.sccl.app.iotsdk.old.SDKService`、`MFSDKService`
  - `com.richinfo.mthttp.service.MTService`、`MTAlarmJobService`、`MTHeartJobService`
- 端点：
  - `ssl://mqtt-tls-none.starrycfn.com:20442`
  - `https://device.starrycfn.com:20442/v1/customer/device/secret/info`
  - MQTT 主题：`/sys/%s/%s/thing/properties/report`、`/client/%s/%s/thing/properties/report/reply`
- 能力：桌面、激活、CMCC/CTCC/联通设备上报、云 MQTT 设备管理。
- **禁用建议**：**不可先禁用**。它是正常 HOME/LAUNCHER，并承载激活；必须先安装第三方 Launcher 并设为默认，且激活流程不再需要它，才能考虑。

### 3.3 `com.zte.usmartsettings` — 默认 Settings + 激活

- 版本：`1.W622D-MB-SecureV2.24.30P5`，`/vendor/app/uSmartSettings_*`，uid 1000。
- 原生库：`libmicled.so`、`libusmartbsp.so`、`libhidec.so`。
- 组件：
  - `SettingMainActivity`（MAIN + LAUNCHER）
  - `SettingRouterActivity`（`android.settings.WIFI_SETTINGS`、`cmss.action.SHUTDOWN`、`cmss.action.REBOOT`、`com.ctg.itrdc.clouddesk.BOOT`）
  - `OnlineActivationActivity`
  - `GlobalSettingService`
  - `BootReceiver`（BOOT_COMPLETED / REBOOT / SHUTDOWN）
- 端点：`https://ctm.zte.com.cn/vops-autoops-model/wxqrcode/getWarranty/?sn=`、`http://www.msftconnecttest.com/connecttest.txt`、`https://www.baidu.com`、`time.zte.com.cn`、`cloud.zte.com.cn`。
- 能力：设置、激活、重启/关机、Wi-Fi 设置、时间同步、保修二维码、AdbManager。
- **禁用建议**：**不可先禁用**。它是唯一的 Settings 入口，也是激活入口；AOSP Settings 只有 FallbackHome。只有安装并验证替代 Settings 后才能考虑。

### 3.4 `com.zte.usmartupdate` — OTA / 激活 / 外设固件

- 版本：`1.W622D-MB-SecureV2.24.30P5`，`/vendor/app/uSmartUpdate_*`，uid 1000，`PERSISTENT`。
- 原生库：`libAutoDownload.so`、`libspca*.so`（USB 摄像头/EC 固件升级相关）、`libusmartbsp.so`。
- 组件：
  - `UpdateService`、`ActivationService`
  - `StCameraUpgradeActivity`、`UdiskUpgradeActivity`
  - `ServiceReciver`（BOOT_COMPLETED / QUICKBOOT / HOME）
- 端点：`cloud.zte.com.cn`、`/data/cache/update.json`；`persist.sys.smart.upgrade.defaulturl` 指向 `http://36.133.134.241:28002/.../update.json`。
- 能力：厂商 OTA、激活服务、USB 摄像头/EC 固件升级、U 盘升级。
- **禁用建议**：完成激活且不再使用厂商 OTA/外设固件升级后，可禁用；否则保留。

### 3.5 `com.zte.usmartview` — SPICE 云桌面

- 版本：`3.24.20`，`/vendor/app/uSmartView_*`，uid 1000。
- 原生库：`libspice.so`、`libusbredirect.so`、`libvideoRedirect.so`、`libvdisk.so`、`libcag.so`、`libQoeInterface.so`、`libuds.so`、`libJNICallDot1x.so`、`libEncryptPwd.so`、ffmpeg 系列。
- 组件：`NewMainActivity`（LAUNCHER）、`RemoteCanvasActivity`（VNC）、`UnicomActivity`、`VersionUpdateActivity`；`IraiService`、`QoeService`、`UsbRedirectService`、`Dot1xService`、`ForegroundService`。
- 端点：`https://desktop.cucloud.cn/`、`https://uac.zte.com.cn`、`cpd.zte.com.cn:8899`、`sslvpnsf.zte.com.cn`、`zxapp.ztems.com/AppUpgradeServer/api/singleUpdate`。
- 能力：SPICE 云桌面、USB/摄像头/麦克风重定向、802.1x、QoE 日志上传。
- **禁用建议**：不用云桌面时可禁用；它不是 HOME，不影响本地桌面。

### 3.6 `com.zte.usmarttool` — 诊断工具

- 版本：`CT_DiagnosTool_V1.24.20`，`/vendor/app/usmarttool_*`，uid 1000。
- 原生库：`libhidec.so`、`libusmartbsp.so`。
- 组件：`MainActivity`、`TerminalInfoActivity`、`ScreenCheckActivity`。
- 无固定公网 URL；有 `upload_success/upload_fail` 日志。
- **禁用建议**：可禁用；不影响默认 UI/激活。

### 3.7 `com.zte.installer` — 厂商静默安装

- 版本：`1.0`，`/vendor/app/usmartinstaller_*`，uid 1000。
- 组件：`MainActivity`、`InstallService`、`BootBroadcastReceiver`。
- 无网络端点；用于厂商静默安装/更新。
- **禁用建议**：条件禁用；AOSP `com.android.packageinstaller` 仍在，但 `uSmartUpdate`/CloudComputer 可能依赖它做静默安装。

### 3.8 `com.cmss.cloudcomputer` — 中国移动云桌面

- 版本：`3.3.1`，`/data/app/...`，uid 10108（普通应用），中国移动云能力中心签名。
- 原生库：`libVdconnSdk.so`、`libVdpServer.so`、`libcag.so`、`libcmccsdk.so`、`libcamrdr.so`、`libdevredir.so`、`libeveusb*.so`、`libspice` 相关、SDL2、ffmpeg、gRPC。
- 组件：`LoginActivity`（MAIN + HOME + LAUNCHER）、`CloudComputerActivity`、`HomeActivity`、`ChooseEnvActivity`、更新/下载服务、WorkBarService、QoeService。
- 权限：相机、麦克风、定位、蓝牙、存储、悬浮窗、通知等；普通 uid，不含平台系统权限。
- 端点：CMCC 云桌面登录/策略；具体域名由配置/登录环境决定，字符串中未固定单一公网域名。
- **禁用建议**：不用中国移动云桌面时可禁用；本地 Android/Linux 不需要它。禁用后 `usmartlauncher` 仍是 HOME。

### 3.9 `com.google.android.inputmethod.pinyin` — 默认输入法

- 版本：`4.5.2.193126728-armeabi-v7a`，`/system/priv-app/ggsrf`，uid 10070。
- 权限：INTERNET、GET_ACCOUNTS、MANAGE_ACCOUNTS、READ_CONTACTS、同步设置、存储。
- 组件：`PinyinIME`、`SyncService`、`FirebaseInstanceIdService/Receiver`、反馈/主题 Activity。
- 能力：默认中文输入法，含 Google 账号/联系人同步、Firebase 组件。
- **禁用建议**：条件禁用；先把 LatinIME 或其它输入法设为默认，再禁用。它不是厂商监控通道，但有 Google 网络/账号权限。

### 3.10 Rockchip / 测试工具

| 包 | 角色 | 端点/组件 | 禁用 |
| :--- | :--- | :--- | :--- |
| `com.DeviceTest` | 工厂硬件测试（屏幕/触摸/相机/传感器/音频/按键/HDMI/USB/PCIe/存储等） | 大量 `rk.intent.action.*` 测试 Activity；`http://172.16.9.1/`、`baidu.com` 网络测试 | 可禁用 |
| `com.cghs.stresstest` | 压力测试（重启/休眠/相机/视频/WiFi/蓝牙/SMS/通话/恢复） | `android.rk.intent.action.startStressTest`；`baidu.com` | 可禁用 |
| `com.android.rk` | RkExplorer 文件管理器 | `RockExplorer`（LAUNCHER）、`CopyService` | 可禁用 |
| `org.chromium.webview_shell` | WebView 测试壳/浏览器 | 测试 Activity、`http://foo.bar` | 可禁用 |

### 3.11 未安装的残留

`com.sohu.inputmethod.sogou.zte` 只在 `enabled_input_methods` 里出现，`pm path` 为空，包列表没有它；不是实际安装的包。需要时可在输入法设置里移除该残留项。

---

## 4. 端点汇总

> 下表的“实证”指运行日志/连接/文件内容直接看到；其余来自 APK 字符串、manifest 或系统属性，属于该包的候选端点，没有逐条抓包确认调用频率。

| 包/组件 | 端点 | 协议 |
| :--- | :--- | :--- |
| `usmartagent` TR-069 | `ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069` | HTTPS |
| `usmartagent` 遥测 | `ctm.zte.com.cn` / `218.98.6.253:28001/vops-autoops-http/terminal/cloud/period` | **HTTP** |
| `usmartagent` | `0.0.0.0:8000` | TR-069 回连 |
| `usmartlauncher` | `mqtt-tls-none.starrycfn.com:20442` | MQTT over TLS |
| `usmartlauncher` | `device.starrycfn.com:20442/v1/customer/device/secret/info` | HTTPS |
| `usmartsettings` | `ctm.zte.com.cn/vops-autoops-model/wxqrcode/getWarranty/?sn=` | HTTPS |
| `usmartsettings` | `www.msftconnecttest.com/connecttest.txt`、`www.baidu.com` | HTTP/HTTPS 连通性检查 |
| `usmartsettings` | `time.zte.com.cn`、Aliyun/NTSC NTP | NTP |
| `usmartupdate` | `cloud.zte.com.cn`、`update.json` | HTTP/HTTPS 混合 |
| `usmartupdate` | `36.133.134.241:28002/.../update.json` | **HTTP** |
| `usmartview` | `desktop.cucloud.cn`、`uac.zte.com.cn`、`cpd.zte.com.cn:8899`、`sslvpnsf.zte.com.cn`、`zxapp.ztems.com` | HTTPS/HTTP |
| CloudComputer | CMCC 云桌面登录/策略端点（配置/登录环境决定） | 私有/HTTPS |
| 系统 | `server_ip=cloud.zte.com.cn`、`runtime.smt.mc.stunaddr=ecloudbook.cmecloud.cn` | — |
| 系统 | `persist.sys.captive4=http://netcheck.ztems.com/generate_204_cloud` | **HTTP** |
| 系统 | `ro.product.ota.host=www.rockchip.com:2300` | — |

---

## 5. 禁用矩阵

### 5.1 可禁用（不影响默认 UI/激活）

| 包 | 原因 |
| :--- | :--- |
| `com.zte.usmarttool` | 诊断工具，无默认角色 |
| `com.zte.usmartview` | SPICE 云桌面；不用云桌面时无影响 |
| `com.DeviceTest` | 工厂硬件测试 |
| `com.cghs.stresstest` | 压力测试 |
| `com.android.rk` | 文件管理器 |
| `org.chromium.webview_shell` | WebView 测试壳 |

### 5.2 条件禁用（先满足前提）

| 包 | 前提 | 后果 |
| :--- | :--- | :--- |
| `com.cmss.cloudcomputer` | 不用中国移动云桌面 | 云桌面登录/桌面不可用 |
| `com.zte.usmartupdate` | 激活完成、不用厂商 OTA/外设固件升级 | 厂商 OTA/激活/摄像头/EC 固件升级不可用 |
| `com.zte.installer` | 不需要厂商静默安装 | 厂商静默安装/更新可能失败；AOSP 安装器仍在 |
| `com.google.android.inputmethod.pinyin` | 已把 LatinIME/其它输入法设为默认 | 默认中文输入法不可用；Google 账号/联系人同步停止 |
| `com.zte.usmartagent` | 激活完成、不需要厂商远程管理/售后诊断 | TR-069/MQTT/遥测/远程支持停止；`PERSISTENT` 可能重新拉起，需实测 |

### 5.3 不可先禁用

| 包 | 原因 |
| :--- | :--- |
| `com.zte.usmartsettings` | 唯一的 Settings + 激活 + 重启/关机入口；AOSP Settings 只有 FallbackHome |
| `com.zte.usmartlauncher` | 正常 HOME/LAUNCHER + 激活入口；没有 AOSP Launcher3；必须先安装替代 Launcher 并设为默认 |
| `com.android.provision` | 当前 setup HOME；setup 完成前禁用会中断初始化 |
| `com.android.settings` | AOSP FallbackHome/系统设置后备；保留 |
| `com.android.packageinstaller` | AOSP 安装器后备；保留 |
| AOSP core（`systemui`、`bluetooth`、`phone`、providers、`webview`、`permissioncontroller`、`networkstack` 等） | 系统运行必需 |

### 5.4 不建议的“全禁”顺序

如果目标是“能本地用 + 少监控”，顺序应是：

1. **先在网络侧阻断端点**（OpenWrt DNS/firewall），不动机器；确认不影响自己要用的功能。
2. 禁用 5.1 的 6 个包。
3. 不用云桌面：禁用 `com.cmss.cloudcomputer`、`com.zte.usmartview`。
4. 不用厂商 OTA/静默安装：禁用 `com.zte.usmartupdate`、`com.zte.installer`。
5. 激活完成、不要远程支持：禁用 `com.zte.usmartagent`；观察是否被重新拉起。
6. 安装替代 Launcher/Settings/输入法后，才考虑 `usmartlauncher`/`usmartsettings`/Google 拼音。
7. **不要先动 `usmartsettings` / `usmartlauncher`**；否则可能直接失去设置/桌面/激活。

---

## 6. 平台/root 通道（不是 APK，但同属厂商控制面）

| 通道 | 证据 | 状态 |
| :--- | :--- | :--- |
| `systemcmd` | `/vendor/bin/systemcmd`：`cmd=$(getprop vendor.sys.run.cmd); $cmd`；init `user root`，`on property:vendor.sys.run.cmd=*` | 属性空，服务 stopped；通道存在 |
| `syszteupdate` | 同上，属性 `vendor.sys.run.syszteupdate` | 属性空，服务 stopped |
| `sshd` | `dropbeard -E -R -F -p 23456`；`persist.zte.sshd.enbale=1` 启动 | 当前 0 / stopped |
| `zted` | boot 时执行一次；`persist.vendor.adbmode.enable !=1` 则 `stop adbd` | `init.svc.zted=stopped`，one-shot |
| `ztelogcat`/`ztelogcat_wpa` | 写 `/data/tombstones/syslog.txt`、`wpa_log.txt` | 运行中 |
| `MemMonitor` | 写 `memlog.txt`（meminfo/procstats/top） | 运行中 |
| `update_engine` | A/B OTA | 运行中 |
| `zted` 自身 | `setprop vendor.sys.run.cmd zte_set_properties.sh` | 通过 root 命令通道执行 |

`ztecmd.log` 实际记录过 `am broadcast`、`zte_set_properties.sh`、`ip -6 route del default` 等 root 命令；这是厂商本地使用，不是外部下发原文。

---

## 7. 只读证据

设备侧：

```
/data/tombstones/evntpost.txt
/data/tombstones/syslog.txt
/data/tombstones/ztecmd.log
/data/tombstones/app_install.txt
/data/tombstones/memlog.txt
/data/tombstones/wpa_log.txt
/vendor/bin/zted
/vendor/bin/systemcmd
/vendor/bin/syszteupdate
/vendor/etc/init/hw/init.zte.rc
/vendor/app/uSmart*/...
```

主机侧 APK 副本：

```
/tmp/device-read/apks/*.apk
/tmp/device-read/apks/*.manifest.xml
/tmp/device-read/pkgs/*.txt
```

本次命令全部只读：`pm list`、`pm path`、`dumpsys package`、`cmd package query-activities`、`settings get`、`getprop`、`ps`、`ss`、`cat`、`apkanalyzer`、`apksigner verify`。没有写设备。