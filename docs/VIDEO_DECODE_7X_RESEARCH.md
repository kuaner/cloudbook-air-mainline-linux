# RK3568 HEVC 硬解与 7.x 迁移研究

> 结论先行：**HEVC 硬解只存在于 7.x**（6.18 LTS 及以下都没有）；RK3568 的 VDPU346 补丁尚未合入（2026-11-14 的 7 补丁系列，base 7.4-rc2）。但 7.x 自带本机板型 DTS，可以先做**不迁移整机**的最小验证。

## 1. 版本坐标

| 版本 | 状态 | rkvdec HEVC | 备注 |
| :--- | :--- | :--- | :--- |
| 6.12.118 | LTS（至 2028-12） | ❌ | 本机当前基线，rkvdec 仍在 staging，仅 rk3399 |
| 6.18.64 | LTS（至 2028-12） | ❌ | 目录中没有 `rkvdec-hevc.c` |
| **7.3.5** | stable（2026-11-17） | ✅ | 完整现代 rkvdec：HEVC、RCB、VDPU381/383 |
| 7.4-rc3 | mainline（2026-11-15） | ✅ | VDPU346 系列的 base 是 7.4-rc2 |

VDPU346 系列（Daniel Golle，2026-11-14）内容：

1. dt-bindings: RK3568 Video Decoder bindings
2. rkvdec: add VDPU346 variant
3. vdpu346: Limit HEVC EXT SPS RPS dimensions
4. vdpu381: Program the read caches
5. arm64: dts: add vdpu346 decoders on RK356X
6. rk356x: Order vdec regs by address
7. rk356x: Clock the vdec at 400 MHz

作者在 **NanoPi R5C（RK3568B2，与本机同 SoC）** 实测：

- Fluster：HEVC 142/147，H.264 129/135
- HEVC Main 2160p **92 fps**，Main10 2160p 77 fps，H.264 High 1080p 197 fps
- **仅 HEVC + H.264，不含 VP9/AV1**

## 2. 本机移植补丁在 7.3 的存续

| 补丁 | 7.3 状态 | 迁移难度 |
| :--- | :--- | :--- |
| 0001 r8152 SR9900 | 上游仍无 | 低（原样） |
| 0003 clk 659MHz VPLL | 上游仍无 | 低（原样） |
| 0005 MT7663S W103D 隔离驱动 | 上游 mt76 仍无独立 mt7663s 目录 | **高**：要从 7.3 的 mt7615/sdio.c 重新 fork，适配新 mt76 核心 API |
| 0006 BT combo 生命周期 | 7.3 已有 `hdev->reset` | 中：上下文变简单，但 combo 结构仍需移植 |
| 0007 mac80211 速率上报 | 需重打 | 低-中 |
| 0008 SC2021 + BQ25700 | 需重新对账 | 中 |
| 0009 Naneng eDP PHY | 上游仍无（404） | 中：自研驱动需对新 PHY API 复验 |
| 0010 analogix RK3568 eDP | 7.3 有 RK3588 eDP + 新 chip data | 中：按新结构加 RK3568（类似 6.12 已做过的适配） |
| 0011/0012 W103D 加固 | 依附 0005 | 高：跟 0005 一起重做 |

DTS：7.3 使用 **`rk356x-base.dtsi`**，本机 v29 需要迁移；VDPU346 节点也加在这里。

## 3. 关键发现：7.3 自带本机板型 DTS

```
arch/arm64/boot/dts/rockchip/rk3568-evb1-v10.dts
model = "Rockchip RK3568 EVB1 DDR4 V10 Board"
```

这正是 issue 里认定的本机板型（EVB1 V10 衍生机），且已包含 **HDMI、eMMC（sdhci）、UART2、sdmmc0**。

因此可以先用它做**视频硬解最小验证**，完全不需要先迁移那 10 个补丁：

- 显示：HDMI（本机有 HDMI 口）或 UART 串口
- 网络：USB 网卡（RTL8153 免驱）或先不联网
- 存储：U 盘 rootfs（我们已经做好）
- Wi-Fi/BT/eDP 不参与验证，暂不需要 0005/0009/0010

## 4. 分阶段方案

### Phase 0：最小验证（离线可做，风险最低）

1. 构建 **7.3.5 + VDPU346 系列**，使用 `rk3568-evb1-v10.dts`
2. 用我们的 `initramfs` + rootfs 打包 boot.img（resource 模板用 OTA 的 `extracted_resource.img`，脚本会自动选中 w620d 条目）
3. U 盘启动 → UART/HDMI 进入系统
4. 验证：
   - `v4l2-ctl --list-devices` 出现 `rockchip-vdec`
   - `fluster -p hevc` 跑分（对齐作者的 142/147）
   - GStreamer `v4l2slhevcdec` 播放 4K HEVC，`fakesink` 也能测
5. 通过后再谈整机迁移

### Phase 1：决定是否迁移整机

若 Phase 0 通过，再评估：

- 0005 W103D 驱动从 7.3 重 fork（最大工作量）
- 0009/0010 eDP 链路适配
- v29 DTS → rk356x-base.dtsi
- config 重建与全量真机回归

## 5. 风险与取舍

| 项 | 说明 |
| :--- | :--- |
| 7.x 非 LTS | 7.3 是 stable，非 longterm；长期维护要跟 7.y 或等下一个 LTS |
| 6.18 LTS 没有 HEVC | 想兼顾 LTS 与硬解，目前没有交集 |
| 双基线成本 | 6.12（已验证）+ 7.x（实验）需要两套构建与验证 |
| VDPU346 未合入 | 需跟踪 7 补丁系列，合入前每次升级要重打 |

## 6. 建议

1. **先做 Phase 0**：离线构建 7.3.5 + EVB1 + VDPU346，产出可 U 盘启动的镜像；上机只验证 HEVC，不动现有 eMMC 系统。
2. Phase 0 成功后，再决定是否把整机迁到 7.4（等正式版）或维持 6.12 + 视频另案。
3. VP9 暂不投入：当前上游与 VDPU346 系列都不覆盖。