# CloudBook Air Linux 6.12.117 LTS 移植基线

6.12.117 已完成内屏、基本外设、MT7663S 网络和 Hantro H.264 硬解移植。最小点亮过程和部分验收边界见 [早期实机记录](../docs/progress.md)。日常 Debian 13 i3 镜像见 [7.3.5 构建](../mainline-7.3/README.md)。

## 构建

在 Linux 构建环境安装 LLVM 工具链、`make`、`bc`、`bison`、`flex`、`libssl-dev`、`libelf-dev`、`device-tree-compiler`、`python3`、`kmod`、`u-boot-tools` 和 `zstd`。从仓库根目录执行：

```bash
curl -fLO https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.117.tar.xz
mkdir -p linux-6.12
tar -xf linux-6.12.117.tar.xz -C linux-6.12 --strip-components=1
BUILD_MODULES=1 ./build_kernel_612.sh
./scripts/pack_linux_artifacts.sh 6.12
```

`build_kernel_612.sh` 默认使用 `rk3568-ecloudbook-air-24rtw-v30-charging-led.dts`。省略 `BUILD_MODULES=1` 时只构建内核和 DTB。构建使用 `kernel_build/out-6.12/`，首次从仓库内核配置初始化，后续复用该目录中的 `.config`。

打包需要 `artifacts/extracted_resource.img` 和 `artifacts/initramfs-6.12.117-cloudbook-emmc.img`。前者从本机原厂 `boot_b` 提取，后者使用 6.12 的手工 initramfs。[本地输入和早期产物记录](../docs/LOCAL_ARTIFACTS_AND_NEXT_STEPS.md)

`pack_linux_artifacts.sh 6.12` 固定输出 `boot_linux_612_v28_usb.img`（`ROOTFS`）、`boot_linux_612_v29_emmc.img`（`EMMC_ROOTFS`）和 `modules-6.12.117-cloudbook.tar.gz`。`v29_emmc` 是保留的文件名，eMMC 路径使用本次构建的默认板级 DTB。要单独重打 6.12 eMMC 镜像，可执行 `./build_boot_linux_612.sh`，默认输出 `artifacts/boot_linux_612_v30_emmc_rebuild.img`。

6.12 的 eMMC 根盘按 `EMMC_ROOTFS` 卷标识别。历史安装曾覆盖 p20，当前双系统将 p20 留给 Android，Linux 根盘使用 p21。[双系统布局](../docs/P20_P21_DUAL_BOOT_PLAN.md)

## Hantro 硬解基线

6.12 的真机验证使用 GStreamer `v4l2codecs` 和 `xvimagesink` 播放 H.264。安装 `gstreamer1.0-plugins-bad`、`gstreamer1.0-x` 和 `parole` 后，可在桌面账户下为 Parole 指定 XVideo 输出：

```bash
xfconf-query -c parole -p /video/videosink -n -t string -s xvimagesink
```

早期实测的 1080p H.264 网络流在 `/dev/video1` 解码时，播放器进程占用约 47% CPU，同一样本在 VLC 软件解码时约 249%。7.3.5 的 HEVC 验收使用 VDPU346。[7.3.5 视频验收命令](../docs/USB_TEST_KIT_7.3.md#5-验收清单)

## 补丁基线与 6.12.117 重基

`patches/` 中补丁的原始版本是在 `unifreq/linux-6.18.y` 源码树上生成的（见[方案设计](../docs/cloudbook-air-mainline/02-方案设计.md)），部分上下文引用了 6.14+/6.15+/6.16+ 才出现的代码，直接对纯净 `v6.12.117` 应用会失败。

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
