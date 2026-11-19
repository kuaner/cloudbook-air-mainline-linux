# CloudBook Air Linux 6.12 最小启动候选

## 目标

第一阶段只要求 UART 可诊断、内核挂载 USB `ROOTFS`、进入 Armbian 控制台，并具备基础输入和 USB 网络条件。eDP、音频、摄像头、PD 和 Wi-Fi 不作为首次启动的验收前提。

## 源码与输出

- 源码：`linux-6.12`，版本 `6.12.117`。
- 板级 DTS：`mainline-6.12/rk3568-ecloudbook-air-24rtw.dts`。
- 独立构建目录：`kernel_build/out-6.12`，不覆盖 6.18 的 `kernel_build/out`。
- 构建入口：工作区根目录 `build_kernel_612.sh`。
- 构建日志：`logs/build-6.12-minimal-20261112.log`。

首版配置把 UART、EXT4、USB 存储、xHCI/EHCI/OHCI、eMMC、I2C、RK8xx PMIC、USB 输入和 RTL8152 网络支持内置到内核，避免挂载 Rootfs 前依赖模块目录。已保存并应用 `patches/0001-r8152-add-sr9900-id.patch`，仅为现有 CoreChips SR9900（0fe6:9900）添加 r8152 设备 ID。该补丁有参考真机测试，但在本机 6.12 候选中仍需重新验证；首测仍建议准备 AX88179 或 RTL8153 网卡作为备选。

## 构建

```bash
./build_kernel_612.sh
```

该命令只读取源码并写本地构建目录，不连接或刷写设备。重新运行会保留并增量使用 `kernel_build/out-6.12/.config`；需要完全重建时，应另行备份后清理该独立输出目录。

## 验收边界

首版只构建 Image 和 DTB，不构建完整模块包；启动所需的存储、串口、USB 和输入支持均已内置。后续外设阶段再按实际驱动选择性构建和安装模块，避免通用 defconfig 产生大量无关模块。

生成 Image、DTB 和 boot.img 不等于真机启动成功。候选镜像必须先检查：

1. DTB 包含 `__symbols__`、`chosen` 和 `reboot_mode`；
2. boot cmdline 使用 `console=ttyS2,115200n8` 和 `root=LABEL=ROOTFS`；
3. 镜像小于 160 MiB 的 `boot_a` 分区；
4. 本地 SHA-256 清单完整；
5. UART 可用、Android B 保留，另行确认后才允许写入 `boot_a` 和重启。

## 补丁基线与 6.12.117 重基

`patches/` 中补丁的原始版本是在 `unifreq/linux-6.18.y` 源码树上生成的（见 `../docs/cloudbook-air-mainline/02-方案设计.md`），部分上下文引用了 6.14+/6.15+/6.16+ 才出现的代码，直接对纯净 `v6.12.117` 应用会失败。

现已按 6.12.117 的真实代码重基，并已用两条独立路径验证（`git apply` 与 `patch -p1` 均 100% 成功，产物树逐字节一致）：

| 补丁 | 调整内容 |
| :--- | :--- |
| `0005` | 去除邮件头；用真实 blob 哈希重新生成（原 `index 000000000..000000000` 会被 GNU patch 误判为删除） |
| `0006` | 去掉 6.14+ 才有的 `hdev->reset = btmtksdio_reset;` 上下文行 |
| `0008` | 修正 `power/supply/Kconfig` 的空行上下文 |
| `0009` | 去掉 6.15+ 才有的 `PHY_ROCKCHIP_SAMSUNG_DCPHY` 上下文行 |
| `0010` | 按 6.12 的扁平 `struct rockchip_dp_chip_data` 重写 RK3568 条目（丢弃 6.18 的 `.reg`/`.edp_mode`），并增加 GRF `lcdsel` 写入保护，避免向 GRF 偏移 0 写入 |
| `0011`/`0012` | 去除邮件头 |

`build_kernel_612.sh` 现优先使用 `git apply`（缺失时回退 GNU `patch`），并在首次构建时以仓库内已验证的 `config-6.12.117-cloudbook` 作为 `.config` 种子，使新克隆可复现真机验证过的配置。

`rk3568-ecloudbook-air-24rtw-v9-419align-edp.dts` 中旧的 `scmi_shmem` 迁移块已移除：6.12 的 `rk356x.dtsi` 已在根节点提供 `sram@10f000/sram@0`（标签 `scmi_shmem`），旧写法会因重复标签导致 DTB 构建失败。
