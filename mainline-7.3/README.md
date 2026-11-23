# cloudbook-air 主线 7.3.5 移植（含 RK3568 HEVC 硬解 / VDPU346）

> 已在本机从 U 盘进入 Debian i3 桌面，并核对 RK3568 硬件解码器输出的 1080p H.264 和 HEVC 帧。内屏、光标、Wi-Fi 与电池的实测结果见 [U 盘试跑记录](../docs/USB_TEST_KIT_7.3.md)。
> 基线：Linux `7.3.5`（stable，2026-11-17）
> 补丁：`cloudbook-7.3.5-port.patch`，可干净应用于纯净 7.3.5。

## 1. 内容

该补丁一次性包含：

1. **上游社区 VDPU346 系列（7 个补丁）**
   - `media: dt-bindings: rockchip: Add RK3568 Video Decoder bindings`
   - `media: rkvdec: Add support for the VDPU346 variant`
   - `media: rkvdec: vdpu346: Limit HEVC EXT SPS RPS dimensions`
   - `media: rkvdec: vdpu381: Program the read caches`
   - `arm64: dts: rockchip: Add the vdpu346 Video Decoders on RK356X`
   - `arm64: dts: rockchip: rk356x: Order vdec regs by address`
   - `arm64: dts: rockchip: rk356x: Clock the vdec at 400 MHz`
2. **本机板级补丁（从 6.12 适配到 7.3.5）**
   - r8152 SR9900 ID（7.3 上下文变了，手工插入）
   - RK3568 VPLL 659 MHz
   - MT7663S W103D 隔离驱动 + combo 生命周期 + 0011/0012/0013 加固与吞吐
   - mac80211 速率上报
   - SC2021 + BQ25700 电源（适配 7.3 的 `fwnode` / `power_supply_get_by_reference()`）
   - Naneng eDP PHY
   - Analogix RK3568 eDP 板级匹配与 BOE 面板时序。附加补丁 `0001-drm-analogix-dp-pass-lane-count-to-phy.patch` 在设置速率和训练电压时传入实际通道数，满足 Naneng PHY 的参数检查。
   - 附加补丁 `0002-drm-rockchip-skip-dirtyfb-on-cloudbook-air.patch` 让这块板连续扫描的 VOP2 帧缓冲省去局部重绘通知，针对 Xorg 绘制软件光标时反复等待垂直刷新的问题。使用者已确认 `#9` 的内屏光标恢复平滑。
   - 附加补丁 `0003-bluetooth-handle-unsupported-local-pairing-options.patch` 处理 MT7663S 在启动时拒绝可选 HCI 命令 `0x100c` 的情况，使初始化能够继续到共享芯片就绪通知。验证范围见[Wi-Fi 连接验证](../docs/USB_TEST_KIT_7.3.md#wi-fi-连接验证)。
   - 两条主线构建共用 `0015-rtc-rk808-use-gregorian-calendar-on-cloudbook-air.patch`，让本机的 RK809 与原厂 Android 使用相同的 RTC 日期格式。

## 2. 复现

```bash
curl -fLO https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.3.5.tar.xz
mkdir -p linux-7.3.5 && tar -xf linux-7.3.5.tar.xz -C linux-7.3.5 --strip-components=1
cd linux-7.3.5
git apply path/to/cloudbook-7.3.5-port.patch
git apply path/to/0013-mt76-mt7663s-w103d-tx-ampdu-and-sdio-throughput.patch
git apply path/to/0001-drm-analogix-dp-pass-lane-count-to-phy.patch
git apply path/to/0002-drm-rockchip-skip-dirtyfb-on-cloudbook-air.patch
git apply path/to/0003-bluetooth-handle-unsupported-local-pairing-options.patch
git apply path/to/0015-rtc-rk808-use-gregorian-calendar-on-cloudbook-air.patch

cp path/to/config-7.3.5-cloudbook .config
make ARCH=arm64 LLVM=1 olddefconfig
make ARCH=arm64 LLVM=1 -j$(nproc) DTC_FLAGS="-@" \
     Image rockchip/rk3568-ecloudbook-air-24rtw.dtb modules
```

关键配置（已在 `config-7.3.5-cloudbook` 中）：

- `CONFIG_LOCALVERSION="-cloudbook"`、`CONFIG_LOCALVERSION_AUTO=n`（模块目录固定为 `7.3.5-cloudbook`）
- `CONFIG_VIDEO_ROCKCHIP_VDEC=m`（HEVC/H.264 硬解）
- `CONFIG_MT7663S=m`、`CONFIG_MT7663S_W103D_COMBO=y`
- `CONFIG_CHARGER_SC2021=y`、`CONFIG_CHARGER_BQ25700=y`
- `CONFIG_PHY_ROCKCHIP_NANENG_EDP=y`
- `CONFIG_ZRAM=m`、`CONFIG_ZSMALLOC=m`（4 GB 机器；rootfs 内 `zram-tools` 启用，`PERCENT=50`、`ALGO=lz4`，lz4 已内置于内核）

## 3. 产物

| 文件 | 说明 |
| :--- | :--- |
| `Image-7.3.5-cloudbook` | 7.3.5 + 上述补丁 |
| `modules-7.3.5-cloudbook.tar.gz` | 767 个模块，目录 `lib/modules/7.3.5-cloudbook` |
| `rk3568-ecloudbook-air-24rtw-v29.dtb` | eMMC 版（`root=LABEL=EMMC_ROOTFS`） |
| `rk3568-ecloudbook-air-24rtw-v28-usb.dtb` | U 盘版（`root=LABEL=ROOTFS`） |
| `boot_linux_73_v29_emmc.img` / `..._v28_usb.img` | Android Boot Image v2，已按 w620d 板型条目打包 |

## 4. 与 6.12 版本的差异

| 项 | 6.12 主线 | 7.3.5 |
| :--- | :--- | :--- |
| HEVC 硬解 | ❌ | VDPU346，已核对 1080p30 H.264 和 HEVC 样本的解码内容 |
| VP9 硬解 | ❌（Hantro 无 VP9 on RK3568） | ❌（当前未包含；可补，见 `../docs/VIDEO_DECODE_7X_RESEARCH.md` 第 7 节） |
| eDP | Naneng PHY + Analogix 与 AUX 面板补丁 | Naneng PHY、RK3568 板级匹配与链路训练通道数修复 |
| 电源 | 自研 API 适配 | `fwnode` / `get_by_reference` 新 API |
| DTS 基文件 | `rk356x.dtsi` | `rk356x-base.dtsi`（编译一次通过） |
| LTS | 至 2028-12 | 非 LTS（7.3 stable） |

## 5. 真机验证范围

> **Wi-Fi/BT 只使用 W103D 补丁集，不使用主线原生 mt7663s。** 原生驱动在本机同固件上存在已复现的问题（重启后固件挂死、P2P/ROC/sched_scan 触发异常、2.4G↔5G 切频认证超时、Wi-Fi/BT eFuse 初始化竞争），W103D 补丁即为此而存在，不作为可选替代。

1. USB 根盘已启动，eDP 内屏已进入 i3 桌面。后续双系统测试也确认 Debian 能挂载 eMMC `p21`。外接 HDMI 尚待验证。
2. 从本机 Android B 提取配套 MT7663 固件后，5 GHz 通过 12 轮双向传输校验，2.4 GHz 自动回退也通过了传输测试。此前的 ACK 不足发生在使用旧固件时。蓝牙控制器已上电。
3. 电池已确认充电和放电，状态栏使用板载 SBS 的实时电量与状态。合盖休眠仍需单独测试。
4. GStreamer 指定 `v4l2slh265dec` 已输出 720p HEVC 样本的全部 48 帧。1080p30 的 H.264 和 HEVC 样本各 60 帧，经指定硬解组件解码后，整段 I420 输出均与 FFmpeg 软件解码的 SHA-256 一致。完整的 `fluster -p hevc` 一致性测试仍待运行。
5. 桌面镜像将 CPU 最低频率设为 600 MHz，保留 `schedutil`。在 `#8` 真机上，实体触控板通常每 7.27 ms 上报一次，Xorg 在局部刷新时仍会同步等待 VOP2。顶栏开关对照和 `#9` 的验证范围见[鼠标间歇性卡顿](../docs/USB_TEST_KIT_7.3.md#鼠标间歇性卡顿)。

## 6. DT 绑定校验

`make CHECK_DTBS=y rockchip/rk3568-ecloudbook-air-24rtw.dtb` 的结果：

- 有 schema 的节点（i2c、mmc、gpio、pinctrl、regulator、panel 等）**0 个缺失属性、0 个非法取值**
- 三个自定义节点原先"无 schema"，本补丁已补齐 YAML：
  - `Documentation/devicetree/bindings/display/rockchip/rockchip,analogix-dp.yaml` 增加 `rockchip,rk3568-edp`
  - 新增 `Documentation/devicetree/bindings/phy/rockchip,rk3568-edp-phy.yaml`
  - 新增 `Documentation/devicetree/bindings/power/supply/southchip,sc2021.yaml`
- 仅剩 `/pmic@20/codec`（`rockchip,rk817-codec`）无上游绑定，属于上游缺口，不影响功能

## 7. Debian 桌面与 rootfs 构建

`artifacts/7.3/` 下可复用的早期根镜像及配套引导文件如下。

| 文件 | 说明 |
| :--- | :--- |
| `rootfs-7.3.5-cloudbook.ext4.zst` | Debian 13 arm64 + `7.3.5-cloudbook` 模块；LightDM + i3 + Mesa/Panfrost |
| `rootfs-7.3.5-cloudbook.tar.zst` | 同一 rootfs 的 tar 包 |
| `boot_linux_73_v28_usb.img` | 写入 `boot_a`，`root=LABEL=ROOTFS` |

新版桌面在 LightDM 中使用 `kuaner` 登录，初始密码为 `kuaner`。首次进入 i3 会加载 Archcraft 配置、Polybar、中文 Rofi 和深色 GTK 主题。按 `Win+D` 打开应用菜单，按 `Win+Enter` 打开 Xfce Terminal，点顶栏网络图标选择 Wi-Fi。时区为 `Asia/Shanghai`，首选 NTP 服务器为 `ntp.aliyun.com`。[桌面快捷键与 U 盘试跑记录](../docs/USB_TEST_KIT_7.3.md)

用 Docker 从已有的 Debian 13 桌面 tar 包和当前 v30 内核产物生成新镜像。

```bash
BUILD_MODULES=1 ./build_kernel_735.sh
./scripts/pack_linux_artifacts.sh 7.3
./scripts/build-rootfs-735.sh artifacts/7.3/rootfs-7.3.5-cloudbook.tar.zst artifacts/7.3/desktop-kuaner
```

构建需要可运行 ARM64 容器的 Docker、上述 Debian 13 LightDM 基础 tar 包，以及从本机导出的 `artifacts/extracted_resource.img`。桌面构建默认读取 `artifacts/7.3/` 下的内核、模块、配置和 v30 eMMC DTB。可通过 `CLOUDBOOK_LOGIN_PASSWORD` 设置初始密码，通过 `CLOUDBOOK_V30_INPUTS` 指定内核产物目录。重用旧镜像时，脚本会迁移 `cloudbook` 账户并保留其 UID/GID，将默认终端设为 Xfce Terminal，然后移除 `xterm`。

| 产物 | 用途 |
| --- | --- |
| `rootfs-7.3.5-cloudbook-usb.ext4` / `.ext4.zst` | 8 GiB USB 根镜像，卷标 `ROOTFS` |
| `rootfs-7.3.5-cloudbook-emmc.tar.zst` | 部署到卷标为 `EMMC_ROOTFS` 的 Linux 分区，双系统布局使用 p21 |
| `boot_linux_73_v30_auto.img` | A 槽引导镜像，有匹配的 `ROOTFS` U 盘时优先使用它，否则挂载 `EMMC_ROOTFS` |
| `SHA256SUMS` | 四个文件的本地校验清单 |

引导镜像启用 Plymouth CloudBook Air 动画并隐藏内核企鹅。按 `Esc` 可查看启动信息。LightDM 顶部的 `cloudbook` 标识按整条面板居中。桌面镜像也会安装带图标的 Linux 切换工具，安卓版单独构建安装。[双系统切换说明](../tools/system-switcher/README.md)

制作同配置的本地根镜像时，把自己设备 `vendor_b` 的 `/etc/firmware/WIFI_RAM_CODE_MT7663.bin` 和 `mt7663_patch_e2_hdr.bin` 一起放入构建环境的 `/inputs/firmware/7.3.5-cloudbook/`，也可用 `MT7663_VENDOR_FIRMWARE_DIR` 指定目录。`scripts/rootfs-postprocess-debian13.sh` 按驱动所需文件名安装这两个文件。省略版本目录与覆盖变量时，脚本使用 `/inputs/firmware/` 中原有的发行版固件。

### 双系统 RTC

2026-11-23 真机复现了两个方向的日期偏差。Android 写入 RTC 后，旧 Debian 在启动时读到了 12 月 3 日。旧 Debian 写入后，原厂 Android 在启动时读到了 11 月 13 日。旧 Linux 驱动对 RK809 也应用了 RK808 的 11 月 31 日换算，偏差随年份累积。Rockchip Android 驱动对 RK809 直接使用公历。

补丁使 `zte,ecloudbook-air-24rtw` 上的 RK809 时间和闹钟读写都直接使用公历，RTC 继续保存 UTC。升级时可以先在原厂 Android 中联网并确认时间正确，再启动新版 Debian。若新版 Debian 首次启动时 RTC 仍是旧格式，等 NTP 同步成功后，以管理员身份运行 `busybox hwclock -w -u -f /dev/rtc0` 将正确的 UTC 写回 RTC。

刷入修复后的 7.3.5 内核后，三次启动的 `dmesg` 均在联网校时前从 RTC 读到了正确日期。

| 真机验证路径 | 内核从 RTC 读到的 UTC |
| --- | --- |
| 原厂 Android → Debian | `2026-11-23 02:21:48` |
| Debian 写入 RTC → Debian | `2026-11-23 02:23:40` |
| Debian → 原厂 Android | `2026-11-23 02:24:53` |

## 8. 上游合并后

VDPU346 系列合入主线后，可直接去掉补丁中 1~7 号提交，仅保留板级驱动补丁；届时把基线与上游同步即可。


### 7.3.5 #10 的 v30 电源改动

`build_kernel_735.sh` 将上游 v30 的 SC2021 充电器中断唤醒、SBS 电量状态与充电指示灯配置带到 7.3。USB 设备树继承 v30，并将根盘卷标设为 `ROOTFS`。eMMC 版本使用 `EMMC_ROOTFS`。

本次候选产物在 `artifacts/7.3/candidates/build10-v30/`。`boot_linux_73_v30_usb_build10.img` 适用于从 U 盘取根文件系统的配置，启动镜像由 eMMC A 槽加载。两套镜像沿用本机原厂资源模板与已验证的 initramfs。已核对 27 个 RSCE 条目。#10 曾在 ARM64 QEMU 中启动 Xfce，随后用同一内核验证了当前 i3 登录、Rofi 应用菜单和联网校时。上机前的实测基线为 #9，内屏 1920×1200@60、Panfrost、5 GHz 和 UPower 电池均已确认。#10 的充电灯、休眠唤醒和热重启表现仍需板上验证。

这两个 build10 镜像仍使用 `scripts/initramfs-6.12.117/init` 手工 initramfs。重新构建 7.3 根镜像后改由 Debian `initramfs-tools` 生成 `/boot/initrd.img-7.3.5-cloudbook`，`root=LABEL=` 由发行版 initrd 的 local 脚本解析；打包时 `scripts/pack_linux_artifacts.sh` 会从 rootfs tarball 自动取出该文件。
