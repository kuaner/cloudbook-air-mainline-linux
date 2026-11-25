# cloudbook-air 主线 7.3.5 移植（含 RK3568 HEVC 硬解 / VDPU346）

> 当前桌面构建使用 Ubuntu 26.04 LTS ARM64、Niri 和 Noctalia。早期 Debian 真机试跑已核对 RK3568 硬件解码器输出的 1080p H.264 和 HEVC 帧，并验证内屏、触控板、Wi-Fi 与电池。[早期 U 盘试跑记录](../docs/USB_TEST_KIT_7.3.md)
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
   - 附加 `0004-power-sc2021-charger-wakeup.patch`，并共用 `0014-power-supply-sbs-align-charging-led-status.patch`，处理充电器唤醒与 SBS 电量状态。
   - 两条主线构建共用 `0015-rtc-rk808-use-gregorian-calendar-on-cloudbook-air.patch`，让本机的 RK809 与原厂 Android 使用相同的 RTC 日期格式。

## 2. 复现

在 Debian 或 Ubuntu 构建环境安装编译和打包依赖。以下命令从仓库根目录运行。

```bash
sudo apt-get install clang lld llvm make bc bison flex libssl-dev libelf-dev \
    device-tree-compiler python3 kmod zstd curl patch
curl -fLO https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.3.5.tar.xz
mkdir -p linux-7.3.5
tar -xf linux-7.3.5.tar.xz -C linux-7.3.5 --strip-components=1
BUILD_MODULES=1 ./build_kernel_735.sh
```

脚本应用移植与后续板级补丁，同步分层 v30 设备树，生成 `Image`、DTB 和模块到 `kernel_build/out-7.3.5/`。首次构建用仓库中的 `config-7.3.5-cloudbook` 初始化，后续复用输出目录里的 `.config`。

Niri 通过 Smithay 提交 KMS 帧。当前内核仍包含早期 Xorg 桌面的 `DIRTYFB` 补丁，Wayland 登录和 Niri 会话均无需 Xorg。用 `CLOUDBOOK_X11_DIRTYFB_WORKAROUND=0 ./build_kernel_735.sh` 可构建使用 Rockchip 原有回调的对照内核。

关键配置（已在 `config-7.3.5-cloudbook` 中）：

- `CONFIG_LOCALVERSION="-cloudbook"`、`CONFIG_LOCALVERSION_AUTO=n`（模块目录固定为 `7.3.5-cloudbook`）
- `CONFIG_VIDEO_ROCKCHIP_VDEC=m`（HEVC/H.264 硬解）
- `CONFIG_MT7663S=m`、`CONFIG_MT7663S_W103D_COMBO=y`
- `CONFIG_CHARGER_SC2021=y`、`CONFIG_CHARGER_BQ25700=y`
- `CONFIG_PHY_ROCKCHIP_NANENG_EDP=y`
- `CONFIG_ZRAM=m`、`CONFIG_ZSMALLOC=m`（4 GB 机器；rootfs 内 `zram-tools` 启用，`PERCENT=50`、`ALGO=lzo-rle`）

## 3. 产物

`./scripts/pack_linux_artifacts.sh 7.3` 将 Ubuntu 构建所需的内核文件放在 `artifacts/7.3/`。

| 文件 | 说明 |
| :--- | :--- |
| `Image-7.3.5-cloudbook`、`config-7.3.5-cloudbook`、`System.map-7.3.5-cloudbook` | 内核、构建配置和符号表 |
| `modules-7.3.5-cloudbook.tar.gz` | 完整模块树，安装目录 `lib/modules/7.3.5-cloudbook` |
| `rk3568-ecloudbook-air-24rtw-v30-emmc.dtb` | 通用桌面引导镜像的板级 DTB 输入 |

Ubuntu 的 initramfs 和 A 槽引导由第 7 节的桌面镜像构建生成。v28/v29 和旧版 v30 引导镜像见早期试跑记录。

## 4. 与 6.12 版本的差异

| 项 | 6.12 主线 | 7.3.5 |
| :--- | :--- | :--- |
| HEVC 硬解 | ❌ | VDPU346，已核对 1080p30 H.264 和 HEVC 样本的解码内容 |
| VP9 硬解 | ❌（Hantro 无 VP9 on RK3568） | ❌（当前未包含，见 [VP9 研究](../docs/VIDEO_DECODE_7X_RESEARCH.md#7-vp9-可行性下游补丁链)） |
| eDP | Naneng PHY + Analogix 与 AUX 面板补丁 | Naneng PHY、RK3568 板级匹配与链路训练通道数修复 |
| 电源 | 自研 API 适配 | `fwnode` / `get_by_reference` 新 API |
| DTS 基文件 | `rk356x.dtsi` | `rk356x-base.dtsi`（编译一次通过） |
| LTS | 至 2028-12 | 非 LTS（7.3 stable） |

## 5. 真机验证范围

> **Wi-Fi/BT 只使用 W103D 补丁集，不使用主线原生 mt7663s。** 原生驱动在本机同固件上存在已复现的问题（重启后固件挂死、P2P/ROC/sched_scan 触发异常、2.4G↔5G 切频认证超时、Wi-Fi/BT eFuse 初始化竞争），W103D 补丁即为此而存在，不作为可选替代。

1. Ubuntu 已从 USB 根盘和 eMMC `p21` 启动。eDP 内屏在 Niri 下使用 Panfrost 和 OpenGL ES 3.1，Noctalia Greeter 也已验证使用 Panfrost 加速，并通过 greetd 登录 Niri。外接 HDMI 音频仍待实测。
2. 从本机 Android B 提取配套 MT7663 固件后，5 GHz 通过 12 轮双向传输校验，2.4 GHz 自动回退也通过了传输测试。此前的 ACK 不足发生在使用旧固件时。蓝牙控制器已上电。
3. 电池已确认充电和放电，状态栏使用板载 SBS 的实时电量与状态。合盖休眠仍需单独测试。
4. GStreamer 指定 `v4l2slh265dec` 已输出 720p HEVC 样本的全部 48 帧。1080p30 的 H.264 和 HEVC 样本各 60 帧，经指定硬解组件解码后，整段 I420 输出均与 FFmpeg 软件解码的 SHA-256 一致。完整的 `fluster -p hevc` 一致性测试仍待运行。
5. Ubuntu 使用内核默认的 CPU 调频下限，真机为 408 MHz，调频策略为 `schedutil`。600 MHz 下限用于早期 i3/Xorg 实验。[鼠标间歇性卡顿记录](../docs/USB_TEST_KIT_7.3.md#鼠标间歇性卡顿)

## 6. DT 绑定校验

`make CHECK_DTBS=y rockchip/rk3568-ecloudbook-air-24rtw.dtb` 的结果：

- 有 schema 的节点（i2c、mmc、gpio、pinctrl、regulator、panel 等）**0 个缺失属性、0 个非法取值**
- 三个自定义节点原先"无 schema"，本补丁已补齐 YAML：
  - `Documentation/devicetree/bindings/display/rockchip/rockchip,analogix-dp.yaml` 增加 `rockchip,rk3568-edp`
  - 新增 `Documentation/devicetree/bindings/phy/rockchip,rk3568-edp-phy.yaml`
  - 新增 `Documentation/devicetree/bindings/power/supply/southchip,sc2021.yaml`
- 仅剩 `/pmic@20/codec`（`rockchip,rk817-codec`）无上游绑定，属于上游缺口，不影响功能

## 7. Ubuntu 桌面与 rootfs 构建

从仓库根目录运行以下命令。桌面构建需要支持 ARM64 Linux 容器的 Docker。可复用同批次的内核、配置、设备树和模块，从最后一行开始构建。

```bash
BUILD_MODULES=1 ./build_kernel_735.sh
./scripts/pack_linux_artifacts.sh 7.3
./scripts/build-ubuntu-2604-735.sh artifacts/7.3/ubuntu-26.04
```

准备从本机导出的 RSCE 资源模板 `artifacts/extracted_resource.img`，以及 Android `vendor_b` 下 `etc/firmware/` 的 `WIFI_RAM_CODE_MT7663.bin` 和 `mt7663_patch_e2_hdr.bin`。将两份固件放在 `artifacts/firmware/7.3.5-cloudbook/`。重复构建时使用新的输出目录，可用 `CLOUDBOOK_KERNEL_DIR` 指定内核文件目录，用 `CLOUDBOOK_LOGIN_PASSWORD` 设置初始密码。[资源模板提取方法](../docs/FLASHING_GUIDE.md#1-核对机型和备份)

| 产物 | 用途 |
| --- | --- |
| `usb/rootfs-ubuntu-26.04-niri-noctalia-usb.ext4` | 整盘写入试跑 U 盘，卷标 `ROOTFS` |
| `usb/boot_linux_73_v30_ubuntu_2604_usb.img` | A 槽试跑引导，扫描 `ROOTFS` 后回退到内置根盘 |
| `emmc/rootfs-ubuntu-26.04-niri-noctalia-emmc.ext4` | 写入现有 eMMC p21，卷标 `EMMC_ROOTFS` |
| `emmc/boot_linux_73_v30_ubuntu_2604_emmc.img` | eMMC 日用 A 槽引导，检测到 USB 存储才额外等待盘符 |

两份根镜像各有 `.ext4.zst` 压缩版，两个目录各有一份 `SHA256SUMS`。greetd 在 VT1 启动 Noctalia Greeter，登录页和 Niri 桌面共用 Catppuccin 深色配色与东京夜景壁纸。系统自带 Foot、Vim、Fcitx5 拼音和 zsh / Oh My Zsh，默认主题为 `ys`。[Noctalia Greeter 文档](https://docs.noctalia.dev/greeter/) · [镜像部署](../docs/FLASHING_GUIDE.md)

根镜像默认不安装 Xorg 或 Xwayland。`xwayland-satellite` 作为 Niri 的包依赖保留，默认禁用。需要运行 X11 应用时，执行 `sudo apt install xwayland`，删除 `/etc/niri/config.kdl` 中的 `xwayland-satellite { off }` 配置块，再注销重登。

Ubuntu ARM64 的基础、更新、安全和 backports 仓默认使用清华 `ubuntu-ports` 镜像。Firefox 使用 Mozilla 的 ARM64 APT 仓库。Noctalia 5.6.0 在构建时应用 ARM CPU 名称回退补丁。Ubuntu 的发行版 GStreamer 1.28 已含 HEVC 可见区域的 `GstVideoMeta` 修复，支持该元数据的下游可直接消费 DMA-BUF。[上游 HEVC 修复](https://github.com/GStreamer/gstreamer/commit/e7be87b3de56e8c9922359c410b5c17864cbbf29)

WirePlumber 优先选择 RK809 的扬声器和耳机输出，HDMI 仍可在 Noctalia 中选用。Wayland 屏幕共享使用 GNOME portal 的 ScreenCast 后端，文件选择继续用 GTK portal，目录默认由 Thunar 打开。

早期 Debian 桌面的试跑、快捷键和视频测试见[历史记录](../docs/USB_TEST_KIT_7.3.md)。

### 双系统 RTC

2026-11-23 真机复现了两个方向的日期偏差。Android 写入 RTC 后，旧 Debian 在启动时读到了 12 月 3 日。旧 Debian 写入后，原厂 Android 在启动时读到了 11 月 13 日。旧 Linux 驱动对 RK809 也应用了 RK808 的 11 月 31 日换算，偏差随年份累积。Rockchip Android 驱动对 RK809 直接使用公历。

补丁使 `zte,ecloudbook-air-24rtw` 上的 RK809 时间和闹钟读写都直接使用公历，RTC 继续保存 UTC。升级时可以先在原厂 Android 中联网并确认时间正确，再启动使用修复内核的 Ubuntu。若首次启动时 RTC 仍是旧格式，等 NTP 同步成功后，以管理员身份运行 `busybox hwclock -w -u -f /dev/rtc0` 将正确的 UTC 写回 RTC。

刷入修复后的 7.3.5 内核后，三次启动的 `dmesg` 均在联网校时前从 RTC 读到了正确日期。

| 真机验证路径 | 内核从 RTC 读到的 UTC |
| --- | --- |
| 原厂 Android → Debian | `2026-11-23 02:21:48` |
| Debian 写入 RTC → Debian | `2026-11-23 02:23:40` |
| Debian → 原厂 Android | `2026-11-23 02:24:53` |

## 8. 上游合并后

VDPU346 系列合入主线后，可直接去掉补丁中 1~7 号提交，仅保留板级驱动补丁；届时把基线与上游同步即可。


## 9. 历史记录：7.3.5 #10 电源候选

`build_kernel_735.sh` 将上游 v30 的 SC2021 充电器中断唤醒、SBS 电量状态与充电指示灯配置带到 7.3。USB 设备树继承 v30，并将根盘卷标设为 `ROOTFS`。eMMC 版本使用 `EMMC_ROOTFS`。

本次候选产物在 `artifacts/7.3/candidates/build10-v30/`。`boot_linux_73_v30_usb_build10.img` 适用于从 U 盘取根文件系统的配置，启动镜像由 eMMC A 槽加载。两套镜像沿用本机原厂资源模板与已验证的 initramfs。已核对 27 个 RSCE 条目。#10 曾在 ARM64 QEMU 中启动 Xfce，随后用同一内核验证了当前 i3 登录、Rofi 应用菜单和联网校时。上机前的实测基线为 #9，内屏 1920×1200@60、Panfrost、5 GHz 和 UPower 电池均已确认。#10 的充电灯、休眠唤醒和热重启表现仍需板上验证。

当时的 build10 镜像使用 `scripts/initramfs-6.12.117/init` 手工 initramfs。当前 Ubuntu 构建使用发行版 `initramfs-tools` 生成 `/boot/initrd.img-7.3.5-cloudbook`，`root=LABEL=` 由发行版 initrd 的 local 脚本解析。
