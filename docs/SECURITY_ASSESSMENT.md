# 安全评估：厂商远程管理通道（W6XX / Android 11）

> 分析对象：`W6XX_W52X-RK3568-T-ALLV2.25.20P06` 系统镜像与厂商应用（离线静态分析）
> 目的：评估"这台设备能否安心使用"，以及风险来自哪里
> 本文不含可直接复现的攻击构造，只做架构与证据层面的判断

## 1. 结论摘要

| 项 | 判断 |
| :--- | :--- |
| 设计质量 | **不合格**：高权限控制面使用明文 HTTP，下载内容无签名校验 |
| 最严重问题 | ACS 可下发脚本，设备**无签名校验**地解密并 **以 root 执行** |
| 谁能利用 | 任何能控制 ACS 响应的一方：同网段/热点中间人、ISP 链路、被攻陷的厂商服务器、恶意系统应用 |
| 普通互联网攻击者能否直接打 | 不能直接打（需在链路或服务器侧），但设备连公共 Wi-Fi 时风险显著 |
| 厂商是否有意预留 | 功能上属于"远程运维/售后"设计，但实现方式把风险敞口放大了 |
| 能否安心使用 | **可以，但必须做网络侧封堵**；否则等于把 root 交给了链路上的任何人 |

## 2. 证据链：从 ACS 到 root 执行

### 2.1 明文 ACS

```
persist.sys.mc.ACSAddress = http://218.98.6.253:28002/vops-autoops-protocol/web/tr069
```

- 协议：TR-069 / CWMP（SOAP + HTTP）
- **无 TLS**；`libtr069.so` 虽编译了 OpenSSL 3.3.0，但配置的 URL 是 `http://`
- CPE 只向 ACS 认证（`ManagementServer.Username/Password`），**不校验服务端身份**

### 2.2 脚本下发与执行（关键）

调用链（`com.zte.usmartagent`，以 `android.uid.system` 运行）：

```
libtr069.so
  → JNI onCallbackUpdateShellUrl(url, cag, type)
  → GlobalEventManager.post(15, ...)
  → PreAndPostUpgradeManager.handleShellInstallEvent()
  → startDownloadPreAndPostShell(url)
       └─ 下载 <url目录>/preinstall.sh、postinstall.sh
  → decodeShell()
  → AgentUtil.runCmd("/data/cache/shell/postinstall.sh")
```

关键缺陷：

| 环节 | 实现 | 问题 |
| :--- | :--- | :--- |
| 传输 | `HttpURLConnection`，允许 `http://` | 明文，可被中间人替换 |
| 完整性 | `doDownloadFile()` 只检查 HTTP 200 与大小 ≤10 MB | **无哈希、无签名、无可信源校验** |
| "加密" | Base64 + AES/GCM，密钥 = `SHA-256(密文自身)`，IV 固定为 `"0123456789ab"` | 密钥由攻击者可控数据派生，**不提供任何真实性** |
| 内容校验 | `startsWithZte()`：只检查前 3 字节是否 ASCII `ZTE` | 魔法前缀，可任意满足 |
| 执行 | `chmod 775` 后 `runCmd()`，经 `zte_run_cmd_service` 或 `vendor.sys.run.cmd` | **以 root 执行** |

结论：**能控制 ACS 响应的一方，可以让设备以 root 执行任意脚本，且不需要任何密钥或签名。**

### 2.3 其他高权限通道

- `vendor.sys.run.cmd` → init 服务 `systemcmd`（root）
- `vendor.sys.run.syszteupdate` → root 升级器
- Binder `com.zte.IZteRunCmdService`（`zte_run_cmd_service`）
- `persist.zte.sshd.enbale=1` → root dropbear，端口 23456
- `persist.internet_adb_enable=1` / `persist.sys.adb_enable=1` → 直接开 adb（绕过 adbmode）
- zlink MQTT 方法通道：`/sys/method/<方法>/req`，含 reboot/poweroff/时间/网络/分辨率/unbind 等

### 2.4 数据模型里的控制面

`assets/device_cc.xml` 暴露可写参数：远程重启/关机、设备锁定、强制弹窗、登录模式、自动登录凭据、应用静默安装/卸载、日志采集、远程诊断。

## 3. 威胁模型

| 攻击者位置 | 能力 | 现实性 |
| :--- | :--- | :--- |
| 同局域网/恶意热点（ARP/网关劫持） | 改写 ACS 响应 → root 脚本 | **高**，咖啡馆/酒店 Wi-Fi 即成立 |
| ISP / 链路中间人 | 同上 | 中 |
| 厂商服务器被攻陷或内部滥用 | 同上（设计如此） | 中 |
| 恶意系统应用 | 写属性 / 调 Binder 服务 | 需已获得系统权限 |
| 纯互联网随机扫描 | **不成立**（设备是主动外连，未被监听端口） | — |

## 4. 设计层面的问题清单

1. 高权限控制面使用明文 HTTP（最严重）
2. 下发脚本无签名、无哈希，所谓 AES 密钥自派生，形同虚设
3. 远程 shell 执行链以 root 运行，且没有二次确认
4. 应用静默安装、设备锁定、强制弹窗等能力默认开启
5. 安全补丁停留在 2021-07-05，内核 4.19.193
6. root SSH 仅靠一个属性开关（且属性名拼写错误 `enbale`）
7. 遥测（boot/alarm/event/period）默认开启，用户不可见
8. 账号激活与云桌面强绑定，本地设备能力被服务端策略控制

## 5. 缓解措施（按有效性排序）

### 5.1 网络侧封堵（最有效，且不改设备）

在路由器/防火墙阻断：

```
218.98.6.253                 # ACS / 遥测 / OTA 清单
ctmcdn.zx.zte.com.cn         # 固件 CDN
netcheck.ztems.com           # 网络探测
dori.ours-ai.com             # 联通云
```

效果：TR-069、MQTT 引导、遥测、OTA 全部失效；本地功能不受影响。

### 5.2 设备侧（需 root）

```bash
pm disable-user --user 0 com.zte.usmartagent
pm disable-user --user 0 com.tyzd.mdm.client
pm disable-user --user 0 com.zte.installer
pm disable-user --user 0 com.zte.xlinkagent
setprop persist.cmss.silentupgrade.support 0
setprop persist.sys.smart.upgrade.defaulturl ""
setprop persist.zte.sshd.enbale 0
```

（不要停 `zted`，它负责 MAC/序列号/输入法/触屏初始化。）

### 5.3 使用习惯

- 不在不可信 Wi-Fi 下让它连厂商服务端（或先做 5.1）
- 不把设备接入你无法控制网关的网络后长期待机
- 定期检查 `/data/tombstones/` 下的日志与异常应用

## 6. 明确的边界

- 本文只做静态风险评估与防御建议，不提供利用工具。
- **不要使用任何泄露的 CA 私钥伪造证书**：这是滥用全球信任链，且本场景 ACS 是 HTTP，根本用不上。
- 若发现厂商漏洞，建议通过 CNCERT/CNVD 或厂商安全响应渠道报送，而不是自行利用。

## 7. 待补的最后一块

`libtr069.so` 中 `onCallbackRunCmd` 的直接触发 RPC（SOAP 方法名）尚未定位；但"脚本下发→root 执行"这条链已经完整闭合，严重性判断不依赖它。