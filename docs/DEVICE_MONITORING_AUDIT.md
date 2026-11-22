# 真机只读审计：ADB 与厂商监控/远控

> **日期**：2026-11-21  
> **设备**：ZTE `eCloudBook Air 24RTW`（`rk3568_r_w600d2`），Android 11，当前运行 `slot_b`  
> **方式**：无线 ADB（`192.168.123.128:5555`），`adb root` 后 **只读**检查。  
> **约束**：本次没有执行任何写入：没有 `dd`、`setprop`、`pm disable`、`am`、`lpadd`，没有改分区/属性/应用。`adb root` 只是让 adbd 以 root 运行，重启即恢复。

---

## 0. 结论

1. **无线 ADB 能通，是因为这是 `userdebug` 构建，标准 Android 设置就能拉起 adbd；不是厂商授权包。**
2. 设备确实有完整的厂商远控/监控链路：TR-069/MDM agent、明文 HTTP 遥测、root 命令属性通道、root SSH 服务、日志采集。
3. 抓到了直接证据：`/data/tombstones/evntpost.txt` 是一条发往 `218.98.6.253:28001` 的**明文 HTTP POST**，body 含 SN、factorySN、MAC、机型、触摸统计。
4. 本次没有修改设备；以下均为只读观察。

---

## 1. 为什么无线 ADB 能访问

### 实测属性

| 属性 | 值 | 含义 |
| :--- | :--- | :--- |
| `ro.build.type` | `userdebug` | 不是 user 生产构建 |
| `ro.build.tags` | `release-keys` | 签名 release，但构建类型 userdebug |
| `ro.debuggable` | `1` | 允许标准 adb |
| `ro.adb.secure` | 空 | 没有 RSA 授权弹窗 |
| `service.adb.root` | `1` | adbd 以 root 运行 |
| `service.adb.tcp.port` | `5555` | 无线 ADB 端口 |
| `persist.adb.tls_server.enable` | `1` | 另有 TLS 调试端口 `35045` |
| `settings global adb_enabled` | `1` | 标准“USB 调试”开关已开 |
| `settings global development_settings_enabled` | `1` | 开发者选项已开 |
| `persist.vendor.adbmode.enable` | `0` | 厂商门控属性仍是 0 |
| `runtime.zte.adbmode` | 空 | 厂商运行时状态未置 1 |
| `init.svc.zted` | `stopped` | `zted` 是一次性 boot 脚本，已退出 |
| `adbDataEnc.zip` | **不存在** | 厂商 AdbManager 授权包没有用上 |

### 机制

`/vendor/bin/zted` 在 `sys.boot_completed=1` 时启动一次，然后执行：

```sh
adbmode_enable=`getprop persist.vendor.adbmode.enable`
if [ $adbmode_enable != "1" ]; then
    stop adbd
fi
```

它只在**开机那一次**检查；`zted` 是 `oneshot`，执行完就退出。你在开机后通过标准设置把 `adb_enabled` 打开，adbd 被重新拉起；`zted` 不会再次执行，所以 `persist.vendor.adbmode.enable=0` 没有继续拦。

`adbDataEnc.zip` 不存在，说明当前 adbd 不是由 `uSmartSettings` 的授权包拉起的，而是 AOSP 标准 adb 开关。**这是 `userdebug` 构建的标准行为，不是厂商设计的“授权开 ADB”。**

### 安全副作用

`ro.adb.secure` 为空、`service.adb.root=1`、adbd 监听 `0.0.0.0:5555`。同一局域网内能连 5555 的主机可以直接拿到 **root adb**，没有 RSA 授权。当前 ADB 是 root 且对 LAN 开放，这是明显的暴露面。

---

## 2. 监控/远控证据（只读）

| 通道 | 证据 | 状态 |
| :--- | :--- | :--- |
| **TR-069 / MDM** | `com.zte.usmartagent`（system uid 1000）运行；服务 `com.zte.ui.tr069Service.StartTr069Service`；监听 `0.0.0.0:8000`；ACS=`https://ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069` | **确认运行**；周期 Inform |
| **TR-069 设备信息** | Inform 含 `SerialNumber`、`FactorySN`、`MACAddress`、`SoftwareVersion`、`Model`、`UserAccount`、`IPAddress`、`ConnectionRequestURL=http://192.168.123.128:8000` | **确认**（日志） |
| **明文遥测** | `/data/tombstones/evntpost.txt`：POST `http://218.98.6.253:28001/vops-autoops-http/terminal/cloud/period`，含 SN、MAC、机型、触摸统计 | **确认**，841 B，SHA-256 `c87f7399…` |
| **root 命令通道** | `systemcmd` 读取 `vendor.sys.run.cmd` 并执行；init 服务 `user root`，`on property:vendor.sys.run.cmd=*` 触发 | **确认机制**；日志有本地调用 |
| **root 更新通道** | `syszteupdate` 读取 `vendor.sys.run.syszteupdate` 并执行；`user root` | **确认机制** |
| **root SSH** | `sshd` 服务：`dropbeard -E -R -F -p 23456`；`persist.zte.sshd.enbale=1` 触发 | **默认关闭**（当前 0/stopped） |
| **日志采集** | `ztelogcat`、`ztelogcat_wpa`、`MemMonitor` 运行；`syslog.txt` 15 MB、`wpa_log.txt` 854 KB、`memlog.txt` 930 KB、`radio.txt` 97 KB | **确认本地采集**；上传未全部证实 |
| **第三方设备身份** | `uSmartLauncher` 调 `https://device.starrycfn.com:20442/v1/customer/device/secret/info` | **确认请求**（HTTPS） |
| **OTA 元数据** | `persist.sys.smart.upgrade.defaulturl=http://36.133.134.241:28002/.../update.json`；`update_engine` 运行 | **确认配置**；明文 HTTP |
| **云桌面** | `com.cmss.cloudcomputer` 运行；`sys.cmss.cloudcomputer.running=1`；`runtime.smt.mc.stunaddr=ecloudbook.cmecloud.cn` | **确认运行** |
| **uSmartSettings HTTP** | 到 `121.40.22.0`、`223.106.89.226`、`23.11.232.8`、`111.13.213.69` 的 **80 端口**连接 | **确认连接**；用途未完全识别（时间/帮助/广告/服务） |

### 2.1 明文遥测原文（已脱敏）

`/data/tombstones/evntpost.txt`（842 B）内容为：

```http
POST /vops-autoops-http/terminal/cloud/period HTTP/1.1
Host: 218.98.6.253:28001
Content-Type: application/json

{"sn":"<redacted>","zteSn":"<redacted>","factorySn":"<redacted>",
 "mac":"<redacted>","os":"Android","terminalType":21,"vendor":"ZTE",
 "model":"eCloudBook Air 24RTW","terminalName":"localhost",
 "clientTime":"2024-11-12T11:47:36Z","eventCode":2,
 "datas":[{"subEventCode":501,"sampleStartTime":"...","sampleEndTime":"...",
 "subData":[{"move":0,"tap":0,"twoFingerTap":0,"twoFingerMoveRightAndLeft":0,
 "threeFingerMoveRight":0,"rightKey":0,"threeFingerTap":0,
 "twoFingerMoveUpAndDown":0,"leftKey":0,"twoFingerZoom":0,
 "threeFingerMoveDown":0,"threeFingerMoveLeft":0,"tapTwiceAndDrag":0,
 "threeFingerMoveUp":0}]}]}
```

这是 **HTTP 明文**，不是 HTTPS；body 含设备唯一标识和触摸/手势统计。日志来源 `[netmanager][ztebw][ccevent.c]`，由 `com.zte.usmartagent` 发出；域名日志写的是 `ctm.zte.com.cn`，解析到 `218.98.6.253:28001`。

### 2.2 TR-069 Inform 字段（已脱敏）

`syslog.txt` 里 `[tr069][rpc_methods.c]` 显示周期 Inform（EventCode 2 PERIODIC）包含：

```
CloudClient.DeviceInfo.SerialNumber       = <redacted>
CloudClient.DeviceInfo.FactorySN         = <redacted>
CloudClient.DeviceInfo.SoftwareVersion   = V2.24.30P6-20241112
CloudClient.DeviceInfo.Model             = ...
CloudClient.DeviceInfo.MACAddress        = <redacted>
CloudClient.DeviceInfo.UserAccount       = user01
CloudClient.ManagementServer.ConnectionRequestURL = http://192.168.123.128:8000
CloudClient.LAN.IPV4.IPAddress           = 192.168.123.128
```

ACS 使用 **HTTPS**（`ecloudbook.cmecloud.cn:28897`），返回 `InformResponse`；同时 `stunclient` 发 UDP，做 NAT 穿透。`com.zte.usmartagent` 的权限包括 `INSTALL_PACKAGES`、`RECOVERY`、`MASTER_CLEAR`、`DEVICE_POWER`、`NETWORK_SETTINGS`、`WRITE_SETTINGS` 等，说明 ACS 侧具备远程安装、恢复/重置、重启、改网络的能力。

### 2.3 root 命令通道

`/vendor/bin/systemcmd`：

```sh
cmd=`getprop vendor.sys.run.cmd`
$cmd
setprop vendor.sys.run.cmd ""
```

`init.zte.rc`：

```
service systemcmd /vendor/bin/systemcmd
    class main
    user root
    group root graphics system cache
    seclabel u:object_r:init:s0
    oneshot

on property:vendor.sys.run.cmd=*
    start systemcmd
```

`/vendor/bin/syszteupdate` 同理，触发属性 `vendor.sys.run.syszteupdate`。

`/data/tombstones/ztecmd.log` 显示这些 root 命令确实执行过（由 `zted`/`zte_set_properties.sh` 在开机时触发）：

```
am broadcast -a com.zte.usmartsettings --es multidisplay.type "0"
zte_set_properties.sh
ip -6 route del default
```

`zted` 自己第 11 行就是 `setprop vendor.sys.run.cmd zte_set_properties.sh`。也就是说：**这条 root 命令通道是厂商自己日常使用的机制**；任何能写该属性的系统应用/远程管理侧，都能以 root 执行命令。日志里看到的是本地厂商脚本调用，没有看到外部 ACS 下发的命令原文。

### 2.4 日志采集

`init.zte.rc` 启动：

```
ztelogcat      -> /data/tombstones/syslog.txt      (15 MB)
ztelogcat_wpa  -> /data/tombstones/wpa_log.txt     (854 KB)
MemMonitor     -> /data/tombstones/memlog.txt      (930 KB)
radiologcat    -> /data/tombstones/radio.txt       (97 KB)
```

`zted` 还会删除蓝牙日志（`rm -rf /data/misc/bluetooth/logs/*`）、调试 PCM 文件等。这些日志是本地采集；除 `evntpost.txt` 外，本次没有证实它们被上传到哪个服务器。

---

## 3. 未证实 / 不要过度解读

1. **没有看到外部 ACS 下发 root 命令的日志**；`ztecmd.log` 里是本地 `zted`/`zte_set_properties.sh` 触发。通道存在，但没有抓到远程命令原文。
2. **没有证实键盘记录/屏幕采集**。`keyboard_monitor.txt` 只是自检计数；`zte_monitor_keyboard` 服务是 stopped。
3. **TR-069 ACS 是 HTTPS**；没有做证书校验审计、没有 MITM，不能断言“可被中间人篡改”。
4. **OTA update.json 是 HTTP**，但 Android `update_engine` 对 payload 有签名校验；HTTP 只证明元数据/下载可被干扰，不等于可远程执行代码。
5. **uSmartSettings 的 80 端口连接**用途未识别；日志里有 NTP（Aliyun/NTSC/time.zte），那些是 UDP 123；HTTP 连接可能是帮助页/壁纸/广告/服务，不能直接叫“监控”。
6. `ro.config.enable.remotecontrol=false`、`com.ctg.itrdc.clouddesk.use=0`；云桌面/遥控有开关状态，不能只看进程名。

---

## 4. 风险清单

| 风险 | 说明 |
| :--- | :--- |
| **ADB root 对 LAN 开放** | `0.0.0.0:5555`、`ro.adb.secure` 空、`service.adb.root=1`；同网段主机可直接 root adb |
| **TR-069/MDM 远控** | agent 常驻、ACS HTTPS、8000 回连、STUN；权限含安装/重置/重启/改网络 |
| **明文遥测** | `evntpost` 明文 HTTP 发 SN/MAC/触摸统计；可被同链路观察/篡改（未做 MITM） |
| **root 命令属性** | `vendor.sys.run.cmd`/`vendor.sys.run.syszteupdate` 由 init 以 root 执行；任何可写属性的系统组件=root |
| **root SSH** | `dropbeard` 23456 默认关，属性一开就是 root shell |
| **本地日志** | syslog/WPA/mem/radio 采集；保留/上传策略未完全证实 |
| **OTA 明文元数据** | `update.json` HTTP；payload 有签名，但元数据可被干扰 |

---

## 5. 只读证据位置

设备侧：

```
/data/tombstones/evntpost.txt
/data/tombstones/syslog.txt
/data/tombstones/ztecmd.log
/data/tombstones/wpa_log.txt
/data/tombstones/memlog.txt
/data/tombstones/app_install.txt
/data/tombstones/set_properties.log
/data/tombstones/factory_config.log
/vendor/bin/zted
/vendor/bin/systemcmd
/vendor/bin/syszteupdate
/vendor/etc/init/hw/init.zte.rc
```

主机侧只读副本（不含 SN/MAC 的完整值）：

```
/tmp/device-read/scripts/
```

本次使用的命令全部为 `getprop`、`cat`、`ls`、`ps`、`ss`、`dumpsys`、`pm list`、`lpdump`、`settings get`；没有写操作。

---

## 6. 建议（未执行）

1. 当前 ADB root 对 LAN 开放，用完把 5555 关掉或在 OpenWrt 上限制来源；不要长期保留。
2. 如果目标是隐私：在 OpenWrt 上阻断 `ctm.zte.com.cn`、`ecloudbook.cmecloud.cn`、`device.starrycfn.com`、`netcheck.ztems.com`、`36.133.134.241`、`218.98.6.253`、`cloud.zte.com.cn`；注意可能影响激活/OTA。
3. 要彻底脱离，还是走 Linux / A 槽 Android 14；B 槽保留原厂，但不要把它当隐私系统用。
4. 若要禁用 `systemcmd`/`syszteupdate`/`sshd`/vendor app，必须先备份并明确授权；本次没有做。