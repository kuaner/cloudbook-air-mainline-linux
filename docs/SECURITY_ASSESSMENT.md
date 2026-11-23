# 安全评估：本机 Android 11 的厂商控制面

> **对象**：ZTE `eCloudBook Air 24RTW`（`rk3568_r_w600d2`），Android 11，`slot_b`，系统 `V2.24.30P6-20241112`（vendor APK 的 `versionName` 标 `V2.24.30P5`）
> **依据**：真机只读检查（`pm`、`dumpsys`、`ss`、`ps`、`getprop`、日志、APK 反编译）
> **边界**：本文不提供利用工具；没有对设备做任何写入。

ADB 状态为最初审计时的记录。11 月 23 日验证过的普通网络 ADB 已使用密钥认证，主机连接默认为 `shell`。[当前 ADB 配置](ANDROID_SIDE_SETUP.md#adb-persistence)

## 1. 审计时的结论

| 项 | 本机事实 |
| :--- | :--- |
| 构建类型 | `userdebug`，`ro.debuggable=1`，`ro.adb.secure` 为空 |
| ADB | 标准设置可开；`service.adb.tcp.port=5555`；`service.adb.root=1`；当前对 LAN 提供 **root adb** |
| 平台权限 | 7 个 ZTE vendor 应用与 `android.uid.system`（uid 1000）共享；平台签名 |
| 远程管理 | `com.zte.usmartagent` 常驻，TR-069 ACS 为 **HTTPS** `ecloudbook.cmecloud.cn:28897`，本机监听 `0.0.0.0:8000`，带 STUN |
| 遥测 | `/data/tombstones/evntpost.txt` 实证 **HTTP 明文** POST 到 `218.98.6.253:28001`，含 SN/MAC/机型/触摸统计 |
| 云管控 | `com.zte.usmartlauncher` 使用 MQTT over TLS（`starrycfn.com:20442`）和设备密钥接口 |
| root 通道 | `vendor.sys.run.cmd` → `systemcmd`（root）；`vendor.sys.run.syszteupdate`；`sshd`（dropbear 23456，默认关） |
| 日志 | `syslog.txt`、`wpa_log.txt`、`memlog.txt`、`radio.txt` 本地采集；上传策略未完全证实 |
| 云桌面 | `com.zte.usmartview`（平台 uid）与 `com.cmss.cloudcomputer`（普通 uid）提供云桌面/远程外设 |

**最重要的两个事实**：

1. **ADB 是 `userdebug` 的标准行为，不是厂商授权包。** `zted` 只在开机时执行一次 `stop adbd`；之后在设置里打开 USB 调试/无线调试，adbd 会重新启动，`persist.vendor.adbmode.enable=0` 不会再次拦截。
2. **TR-069 ACS 走 HTTPS，明文的是遥测/部分 OTA 元数据**；两者不是同一条链路，不能混为“ACS 明文”。

## 2. ADB 与访问面

| 属性/设置 | 值 |
| :--- | :--- |
| `ro.build.type` | `userdebug` |
| `ro.debuggable` | `1` |
| `ro.adb.secure` | 空 |
| `service.adb.root` | `1` |
| `service.adb.tcp.port` | `5555` |
| `persist.adb.tls_server.enable` | `1`（另有 TLS 调试端口 `35045`） |
| `settings global adb_enabled` | `1` |
| `settings global development_settings_enabled` | `1` |
| `persist.vendor.adbmode.enable` | `0` |
| `runtime.zte.adbmode` | 空 |
| `init.svc.zted` | `stopped`（one-shot） |
| `adbDataEnc.zip` | 不存在 |

`/vendor/bin/zted` 在 `sys.boot_completed=1` 时启动一次，读取 `persist.vendor.adbmode.enable`；不等于 `1` 就 `stop adbd`，然后退出。`zted` 是 `oneshot`，不会在之后的标准 adb 开关时再次执行。

**风险**：`ro.adb.secure` 为空 + `service.adb.root=1` + `0.0.0.0:5555`，同一局域网能连 5555 的主机可直接获得 root adb。用完应关闭无线调试或在网关限制来源。

## 3. TR-069 / MDM

`com.zte.usmartagent`：

- `android.uid.system`，`PERSISTENT`。
- 服务：`com.zte.ui.tr069Service.StartTr069Service`、`MultiAppService`、`BootBroadcastReceiver`。
- 监听：`0.0.0.0:8000`（ConnectionRequest）。
- ACS：`https://ecloudbook.cmecloud.cn:28897/vops-autoops-protocol/web/tr069`（HTTPS）。
- Inform 字段：`SerialNumber`、`FactorySN`、`MACAddress`、`SoftwareVersion`、`Model`、`UserAccount`、`IPAddress`、`ConnectionRequestURL=http://192.168.123.128:8000`。
- STUN：`stunclient` 向 ACS 侧发 UDP，做 NAT 穿透。
- 共享 uid 权限包含 `INSTALL_PACKAGES`、`RECOVERY`、`MASTER_CLEAR`、`DEVICE_POWER`、`NETWORK_SETTINGS`、`WRITE_SETTINGS` 等；平台侧具备远程安装/恢复/重启/改网络的能力基础。

**未证实**：本次没有抓到 ACS 下发 root 命令或脚本的日志；`ztecmd.log` 里是本地 `zted`/`zte_set_properties.sh` 触发的命令。能确认的是通道和权限存在，不能把“可能”写成“已发生”。

## 4. 明文遥测

`/data/tombstones/evntpost.txt`（842 B，SHA-256 `c87f7399…`）：

```http
POST /vops-autoops-http/terminal/cloud/period HTTP/1.1
Host: 218.98.6.253:28001
Content-Type: application/json

{"sn":"<redacted>","zteSn":"<redacted>","factorySn":"<redacted>",
 "mac":"<redacted>","os":"Android","terminalType":21,"vendor":"ZTE",
 "model":"eCloudBook Air 24RTW","terminalName":"localhost",
 "clientTime":"2024-11-12T11:47:36Z","eventCode":2,
 "datas":[{"subEventCode":501,"subSampleStartTime":"...",
 "subData":[{"move":0,"tap":0,"twoFingerTap":0,
 "threeFingerMoveRight":0,"rightKey":0,"leftKey":0,
 "twoFingerZoom":0,"tapTwiceAndDrag":0}]}]}
```

- **HTTP 明文**，不是 HTTPS。
- 含设备唯一标识、机型、触摸/手势统计。
- 日志来源 `[netmanager][ztebw][ccevent.c]`，由 `com.zte.usmartagent` 发出；域名日志写 `ctm.zte.com.cn`，解析到 `218.98.6.253:28001`。

## 5. root 命令与 SSH

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

`/data/tombstones/ztecmd.log` 记录过：

```
am broadcast -a com.zte.usmartsettings --es multidisplay.type "0"
zte_set_properties.sh
ip -6 route del default
```

`zted` 自己就执行 `setprop vendor.sys.run.cmd zte_set_properties.sh`。也就是说，这条 root 通道是厂商日常机制；任何能写该属性的系统组件/远程管理侧都能以 root 执行命令。

`sshd`：

```
dropbeard -E -R -F -p 23456
persist.zte.sshd.enbale=1  -> start sshd
```

当前 `persist.zte.sshd.enbale=0`，服务 `stopped`；属性一开就是 root shell。

## 6. 云管控与云桌面

| 包 | 通道 | 性质 |
| :--- | :--- | :--- |
| `com.zte.usmartlauncher` | `ssl://mqtt-tls-none.starrycfn.com:20442`；`https://device.starrycfn.com:20442/v1/customer/device/secret/info` | 桌面/激活/设备上报/MQTT 云管控 |
| `com.zte.usmartagent` | HTTPS ACS + HTTP 遥测 + zlink MQTT | TR-069/MDM/遥测 |
| `com.zte.usmartview` | `desktop.cucloud.cn`、`uac.zte.com.cn`、`cpd.zte.com.cn:8899`、`sslvpnsf.zte.com.cn`、`zxapp.ztems.com` | SPICE 云桌面、USB/摄像头/麦克风重定向、QoE |
| `com.cmss.cloudcomputer` | CMCC 云桌面登录/策略 | 普通 uid 10108，云桌面客户端 |

`com.zte.usmartview` 的权限包括相机、麦克风、`READ_LOGS`、`SYSTEM_ALERT_WINDOW`、`WRITE_SECURE_SETTINGS`、定位、蓝牙；共享 uid 使其具备平台能力。`com.cmss.cloudcomputer` 是普通应用，不含平台系统权限。

## 7. 日志

`init.zte.rc`：

```
ztelogcat      -> /data/tombstones/syslog.txt      (15 MB)
ztelogcat_wpa  -> /data/tombstones/wpa_log.txt     (854 KB)
MemMonitor     -> /data/tombstones/memlog.txt      (930 KB)
radiologcat    -> /data/tombstones/radio.txt       (97 KB)
```

`zted` 还会删除蓝牙日志、调试 PCM 等。除 `evntpost.txt` 外，本次没有证实这些日志被上传到哪个服务器。

## 8. 风险清单

| 风险 | 说明 |
| :--- | :--- |
| **ADB root 对 LAN 开放** | `0.0.0.0:5555`、`ro.adb.secure` 空、`service.adb.root=1`；同网段可直接 root adb |
| **TR-069/MDM 远控** | agent 常驻、ACS HTTPS、8000 回连、STUN；共享 uid 具备安装/恢复/重启能力 |
| **明文遥测** | `evntpost` 明文 HTTP 发 SN/MAC/触摸统计；可被同链路观察/篡改（本次未做 MITM） |
| **root 命令属性** | `vendor.sys.run.cmd`/`vendor.sys.run.syszteupdate` 由 init 以 root 执行 |
| **root SSH** | `dropbeard` 23456 默认关，属性一开就是 root shell |
| **本地日志** | syslog/WPA/mem/radio 采集；保留/上传策略未完全证实 |
| **云桌面外设** | `usmartview`/CloudComputer 可访问相机、麦克风、USB、屏幕 |
| **明文 OTA 元数据** | `update.json` 走 HTTP；payload 有签名校验，元数据可被干扰 |

## 9. 边界

- 本文只做只读评估与防御建议，不提供利用工具。
- 不移除/不禁用任何包；禁用矩阵见 `THIRD_PARTY_PACKAGE_AUDIT.md`。
- 不使用泄露的 CA 私钥伪造证书；本机 ACS 是 HTTPS，证书校验行为未做审计，不能用“HTTP 可中间人”套在本机上。
- 离线 `W6XX_W52X-RK3568-T-ALLV2.25.20P06` 包不是本机镜像；其 URL/包列表不能替本机结论，本机以 `V2.24.30P6`/vendor `P5` 为准。