# cloudbook-air 主线 7.3.5 移植（含 RK3568 HEVC 硬解 / VDPU346）

> 状态：**全量编译通过、零警告**；真机尚未验证。
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
   - MT7663S W103D 隔离驱动 + combo 生命周期 + 0011/0012 加固
   - mac80211 速率上报
   - SC2021 + BQ25700 电源（适配 7.3 的 `fwnode` / `power_supply_get_by_reference()`）
   - Naneng eDP PHY
   - Analogix RK3568 eDP（7.3 上游已自带 PHY 支持，只补 `RK3568_EDP` 枚举、`is_rockchip()` 分支、chip data 数组与 DT 条目）

## 2. 复现

```bash
curl -fLO https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.3.5.tar.xz
mkdir -p linux-7.3.5 && tar -xf linux-7.3.5.tar.xz -C linux-7.3.5 --strip-components=1
cd linux-7.3.5
git apply path/to/cloudbook-7.3.5-port.patch

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
| HEVC 硬解 | ❌ | ✅ VDPU346（H.264 + HEVC，4K60） |
| VP9 硬解 | ❌（Hantro 无 VP9 on RK3568） | ❌（VDPU346 系列不含 VP9） |
| eDP | 自研 PHY + analogix 补丁 | 上游已有 PHY 框架，仅补 RK3568 分支 |
| 电源 | 自研 API 适配 | `fwnode` / `get_by_reference` 新 API |
| DTS 基文件 | `rk356x.dtsi` | `rk356x-base.dtsi`（编译一次通过） |
| LTS | 至 2028-12 | 非 LTS（7.3 stable） |

## 5. 待验证（真机）

> **Wi-Fi/BT 只使用 W103D 补丁集，不使用主线原生 mt7663s。** 原生驱动在本机同固件上存在已复现的问题（重启后固件挂死、P2P/ROC/sched_scan 触发异常、2.4G↔5G 切频认证超时、Wi-Fi/BT eFuse 初始化竞争），W103D 补丁即为此而存在，不作为可选替代。

1. 启动：UART/HDMI、eMMC、USB
2. Wi-Fi/BT：W103D 驱动在 7.3 mt76 核心上的运行时行为（编译通过不等于稳定）
3. eDP 内屏点亮（`RK3568_EDP` 分支）
4. PD 充电（SC2021/BQ25700）与合盖休眠
5. HEVC 硬解：
   - `v4l2-ctl --list-devices` 应出现 `rockchip-vdec`
   - `fluster -p hevc`（参考：NanoPi R5C 上 142/147）
   - GStreamer `v4l2slhevcdec`（需 1.28+ 的 v4l2codecs）

## 6. DT 绑定校验

`make CHECK_DTBS=y rockchip/rk3568-ecloudbook-air-24rtw.dtb` 的结果：

- 有 schema 的节点（i2c、mmc、gpio、pinctrl、regulator、panel 等）**0 个缺失属性、0 个非法取值**
- 三个自定义节点原先"无 schema"，本补丁已补齐 YAML：
  - `Documentation/devicetree/bindings/display/rockchip/rockchip,analogix-dp.yaml` 增加 `rockchip,rk3568-edp`
  - 新增 `Documentation/devicetree/bindings/phy/rockchip,rk3568-edp-phy.yaml`
  - 新增 `Documentation/devicetree/bindings/power/supply/southchip,sc2021.yaml`
- 仅剩 `/pmic@20/codec`（`rockchip,rk817-codec`）无上游绑定，属于上游缺口，不影响功能

## 7. U 盘试跑包（rootfs）

`artifacts/7.3/` 里还有一套可直接试跑的 rootfs：

| 文件 | 说明 |
| :--- | :--- |
| `rootfs-7.3.5-cloudbook.ext4.zst` | Debian 13 arm64 + `7.3.5-cloudbook` 模块；LightDM + i3wm + Mesa/Panfrost |
| `rootfs-7.3.5-cloudbook.tar.zst` | 同一 rootfs 的 tar 包 |
| `boot_linux_73_v28_usb.img` | 写入 `boot_a`，`root=LABEL=ROOTFS` |

桌面按作者验证过的组合：**LightDM + i3wm + Panfrost**（未采用 Hyprland/Omarchy，Panfrost 只有 GLES 3.1，Hyprland 会回退 GLES 3.0，4 GB 内存也偏紧）。操作步骤见 [`../docs/USB_TEST_KIT_7.3.md`](../docs/USB_TEST_KIT_7.3.md)。

## 8. 上游合并后

VDPU346 系列合入主线后，可直接去掉补丁中 1~7 号提交，仅保留板级驱动补丁；届时把基线与上游同步即可。