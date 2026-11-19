# 厂商管控组件盘点与本地加固方案

> 来源：`W6XX_W52X-RK3568-T-ALLV2.25.20P06` 离线包（system / vendor / product / system_ext 全量镜像）
> 目标：保留 Android 本地可用性，同时了解并可选地关闭厂商远程管理与遥测。

## 1. 组件盘点

| 包名 | 位置 | 特权 | 能力 |
| :--- | :--- | :--- | :--- |
| `com.zte.usmartagent` | `/vendor/app/uSmartAgent_*` | `android.uid.system`、`RECOVERY`、`READ_PRIVILEGED_PHONE_STATE` | **TR-069 客户端**（`libtr069.so`、`libacsdiscover.so`）、**MQTT 远程接入**（`libzlink*`、`libmosquitto`）、自动下载（`libAutoDownload.so`）、USB/HID 控制（`libhidec`、`libspca*`） |
| `com.tyzd.mdm.client` | `/system/app/tyzd_mdm_client` | `QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、`DEVICE_POWER`、`DUMP`、`SYSTEM_ALERT_WINDOW` | MDM 客户端；内嵌 `net.memenet.tyycenter.ControlService`，监听开机/网络变化 |
| `com.zte.installer` | `/vendor/app/usmartinstaller_*` | `android.uid.system`、`REQUEST_INSTALL_PACKAGES` | 静默安装/卸载应用（可被远程下发） |
| `com.zte.xlinkagent` | `/vendor/app/xlinkAgent_*` | `android.uid.system`、`RECOVERY` | 远程协助/控制代理 |
| `com.zte.usmartview` | `/vendor/app/uSmartView_*` | 相机/麦克风/悬浮窗 | 云桌面客户端（把画面与输入送到云端，属产品功能） |
| `ctyundesk` | `/vendor/app/ctyundesk` | — | 天翼云桌面组件 |
| `com.zte.usmartlauncher` | `/vendor/app/uSmartLauncher_*` | `WRITE_SECURE_SETTINGS`、`PACKAGE_USAGE_STATS`、`REQUEST_DELETE_PACKAGES` | 定制桌面/看板，可作为管控入口 |
| `com.zte.usmartsettings` | `/vendor/app/uSmartSettings_*` | `WRITE_SECURE_SETTINGS`、`CHANGE_WIFI_STATE`、`MOUNT_UNMOUNT_FILESYSTEMS` | 定制设置，内含 ADB 授权校验（见 `OTA_ANALYSIS.md` 第 9 节） |
| `usmarttool` | `/vendor/app/usmarttool_*` | — | 诊断工具 |

## 2. 通信面

| 用途 | 地址 / 机制 |
| :--- | :--- |
| TR-069 ACS（远程参数读写、升级、诊断） | `persist.sys.mc.ACSAddress` → `http://218.98.6.253:28002/vops-autoops-protocol/web/tr069` |
| MQTT 远程接入 | `libzlink_access` 先 `GET /device/getMqttServer?deviceId=<SN>`，再连 MQTT broker 并订阅主题 |
| 遥测上报 | `/vops-autoops-http/terminal/cloud/{boot,alarm,event,period}`（由 `uSmartAgent` 发出） |
| 智能升级 | `persist.sys.smart.upgrade.defaulturl` → `.../download/images/<渠道>/update.json`，静默升级开关 `persist.cmss.silentupgrade.support=1` |
| 网络探测 | `http://netcheck.ztems.com/generate_204_cloud` |
| 联通云桌面 | `https://dori.ours-ai.com` |
| 固件 CDN | `http://ctmcdn.zx.zte.com.cn/vops-autoops-cpe/download/images/` |

## 3. 可能采集的数据（按权限与端点推断）

- 设备身份：序列号（`ro.serialno` / `persist.sys.stbid`）、`ro.product.productid`、MAC
- 运行状态：电量、充电、网络、开机/告警事件（遥测端点 `boot/alarm/event/period`）
- 应用清单：`QUERY_ALL_PACKAGES`（MDM）
- 云桌面会话：屏幕、键鼠、摄像头/麦克风（`usmartview`，属产品功能）

## 4. 加固方案（按投入从低到高）

### 4.1 无 root（仅 adb/设置）

```bash
# 尝试冻结（系统应用可能因 uid system 被拒）
adb shell pm disable-user --user 0 com.tyzd.mdm.client
adb shell pm disable-user --user 0 com.zte.installer
adb shell pm disable-user --user 0 com.zte.xlinkagent
adb shell pm disable-user --user 0 com.zte.usmartagent
```

失败时只能依赖路由器侧屏蔽（见 4.3）。

### 4.2 有 root（Magisk / userdebug）

```bash
# 冻结（比卸载可逆）
pm disable-user --user 0 com.tyzd.mdm.client
pm disable-user --user 0 com.zte.usmartagent
pm disable-user --user 0 com.zte.installer
pm disable-user --user 0 com.zte.xlinkagent

# 关闭静默升级与升级清单
setprop persist.cmss.silentupgrade.support 0
setprop persist.sys.smart.upgrade.defaulturl ""

# 关闭 ADB 自动授权开关（避免被远程开启）
setprop persist.sys.autotest.openadb 0
```

```bash
# 屏蔽厂商服务端（IP 层）
iptables -I OUTPUT -d 218.98.6.253 -j REJECT

# 域名层（/system/etc/hosts 或 Magisk 模块）
echo "0.0.0.0 ctmcdn.zx.zte.com.cn" >> /etc/hosts
echo "0.0.0.0 netcheck.ztems.com"    >> /etc/hosts
echo "0.0.0.0 dori.ours-ai.com"      >> /etc/hosts
```

### 4.3 网络侧（不改设备）

在路由器/网关直接封掉 `218.98.6.253`、`ctmcdn.zx.zte.com.cn`、`netcheck.ztems.com`。这样 TR-069、MQTT 引导、遥测与 OTA 全部失效，且不影响本地功能。

### 4.4 彻底移除（风险最高）

`/vendor` 与 `/system` 为只读分区，需 `remount` 后删除 APK 并清理对应 `/data` 目录。会破坏后续 OTA 增量包与厂商签名一致性，不建议在保留 Android 的前提下使用。

## 5. 逐项影响

| 关闭对象 | 失去的功能 | 本地影响 |
| :--- | :--- | :--- |
| `com.zte.usmartagent` | TR-069、MQTT 远程、遥测、自动下载 | 本地 Wi-Fi/显示/输入不受影响 |
| `com.tyzd.mdm.client` | 远程管控、策略下发 | 可能影响激活/云桌面开通流程 |
| `com.zte.installer` | 静默安装 | 手动装包不受影响 |
| `com.zte.xlinkagent` | 远程协助 | 无 |
| `com.zte.usmartview` | 云桌面 | 若只用本地 Android 可停 |
| `zted`（系统服务） | 写入 MAC/序列号、设置输入法、触屏 HID 初始化 | **不要停** |

## 6. 验证

```bash
ps -A | grep -E "usmart|tyzd|xlink|installer"
ss -tupn | grep -E "218\.98|:28002"
cat /data/tombstones/{zte,set_properties,update_ota}.log
```

## 7. 风险与合规提示

- 这台设备是“移动云电脑”形态，账号激活与云桌面服务是运营商产品的一部分；关闭 MDM/激活可能违反服务条款或影响保修。
- 关闭 OTA 后安全补丁不再更新（系统当前本就停在 2021-07-05）。
- `vbmeta` 已禁用 AVB（`flags=2`），修改 vendor/system 不会触发校验失败，但会破坏 A/B OTA 的增量基线。