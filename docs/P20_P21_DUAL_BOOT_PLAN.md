# P20/P21 双系统分区方案

> 状态：已在真机部署并验证图形双向切换\
> 最近验证：2026-11-23\
> 目标设备：ZTE CloudBook Air CT2000-24RTW（RK3568B2）  
> 前提：继续保留原厂签名的 idbloader、U-Boot、Trust 和现有 A/B 启动机制。

本机读回的 `p20 userdata` 起始 LBA 为 `15,325,184`，大小为 `16,777,216` 扇区（8 GiB）。`p21 rootfs` 起始 LBA 为 `32,102,400`，大小为 `28,968,927` 扇区（约 13.8 GiB）。已确认原厂 Android B 启动并挂载 `/data`，Debian A 以 `/dev/mmcblk0p21` 为根盘。后续分区表与命令仍保留原始规划示例，重刷已有双系统的 Debian 时可直接使用现有 p21。[图形切换实测记录](../tools/system-switcher/README.md)

## 1. 目标

把原厂 Android 的 `userdata`（`p20`）拆成两个分区：

- `p20 userdata`：约 4 GiB，继续给原厂 Android 使用。
- `p21 EMMC_ROOTFS`：剩余约 18 GiB，作为 Linux 根文件系统。

启动槽位保持：

- A 槽 `boot_a`：Linux 内核、initramfs、DTB。
- B 槽 `boot_b`：原厂 Android 11 内核和 ramdisk。
- `misc`（`p6`）：只负责选择 A/B 槽，不承载系统文件。

本方案不实现 U-Boot 启动菜单；系统切换仍通过改写 `misc` 完成。

## 2. 当前分区事实

`p20` 不是 Android 的系统分区，而是原厂 `userdata`：

| 项目 | 数值 |
| --- | --- |
| GPT 分区名 | `userdata` |
| Linux 设备 | `/dev/mmcblk0p20` |
| Android 设备 | `/dev/block/mmcblk2p20` 或 `/dev/block/by-name/userdata` |
| 起始 LBA | `15,325,184` |
| 当前大小 | `46,147,520` 扇区，约 22 GiB |
| 当前结束 LBA | `61,472,703` |
| GPT 最后可用 LBA | `61,472,734` |
| `p20` 后的空闲空间 | 31 扇区，约 15.5 KiB |

因此不能直接追加 `p21`。必须先缩小或删除重建 `p20`，才能在尾部创建 `p21`。

Android 的系统文件在 `p19 super` 的动态分区中，`boot_b` 只是 B 槽内核；`p20` 只承载 Android 的 `/data`，包括应用数据、设置、缓存和 `/data/media` 内部存储。

## 3. 目标布局

按 Android 4 GiB 计算：

| 分区 | 起始 LBA | 大小 | 结束 LBA | 用途 |
| --- | ---: | ---: | ---: | --- |
| `p20 userdata` | `15,325,184` | `8,388,608` 扇区 / 4 GiB | `23,713,791` | Android `/data` |
| `p21 rootfs` | `23,713,792` | `37,758,943` 扇区 / 约 18 GiB | `61,472,734` | Linux `EMMC_ROOTFS` |

`p21` 的 GPT 分区名可以写成 `rootfs`，文件系统卷标必须是：

```text
EMMC_ROOTFS
```

Linux initramfs 会扫描 `/dev/mmcblk0p*` 并按这个卷标寻找根分区。

### 4 GiB 的实际可用空间

4 GiB 可以启动原厂 Android，但格式化后实际可用约 3.7–3.9 GiB。Android 的 `/data` 同时承载应用、应用数据、缓存、`/data/media` 和 dexopt 临时文件，因此只适合应急启动或少量使用。

如果要日常使用 Android，建议改为 8 GiB：

| 分区 | 起始 LBA | 大小 | 结束 LBA |
| --- | ---: | ---: | ---: |
| `p20 userdata` | `15,325,184` | `16,777,216` 扇区 / 8 GiB | `32,102,399` |
| `p21 rootfs` | `32,102,400` | `29,370,335` 扇区 / 约 14 GiB | `61,472,734` |

## 4. 为什么不能直接在运行中的 Android 下改

不能从正在运行的 Android 直接重建 `p20`：

- Android 正在把 `p20` 挂载为 `/data`，大量系统服务正在使用它。
- 改动 GPT 后，运行中的内核不会安全地重新读取分区表。
- Android 用户态通常没有 `sgdisk`/`parted`，临时推入工具也不能消除在线改分区表的风险。
- 如果此时断电，GPT 主表和备份表可能不一致，设备可能无法正常启动。

必须先启动独立的 USB Linux，或进入 Loader/Rockusb 模式后再处理。

## 5. 执行前检查

从 USB Linux 启动，并确认当前根文件系统不是 `p20`：

```bash
findmnt -n -o SOURCE /
lsblk -o NAME,SIZE,TYPE,FSTYPE,LABEL,PARTTYPENAME
sgdisk -p /dev/mmcblk0
sgdisk -i 20 /dev/mmcblk0
```

记录 `p20` 的原始分区类型 GUID、分区名和当前起始 LBA。重建 `p20` 时应保留原类型和 `userdata` 名称。

即使不做用户数据备份，也建议至少备份 GPT 分区表；它只有约 1 MiB：

```bash
sgdisk --backup=/tmp/mmcblk0-gpt-backup.bin /dev/mmcblk0
```

用户明确表示不需要备份时，重建 `p20` 会造成 Android `/data` 丢失；如果 `p20` 当前是 Linux 根文件系统，则会造成 Linux 数据丢失。若 `p20` 里仍有需要保留的 Linux 数据，必须先复制到 USB 或其它存储。

## 6. 重建分区

以下操作会破坏 `p20` 原数据，只能从 USB Linux 执行。

1. 卸载 `p20`，确认没有进程占用：

```bash
sudo umount /dev/mmcblk0p20 2>/dev/null || true
findmnt /dev/mmcblk0p20 || true
```

2. 删除并重建 4 GiB 的 `p20`，起始 LBA 保持不变：

```bash
sudo sgdisk -d 20 /dev/mmcblk0
sudo sgdisk -n 20:15325184:23713791 \
    -t 20:<p20-原始类型GUID> \
    -c 20:userdata /dev/mmcblk0
```

3. 在剩余空间创建 `p21`：

```bash
sudo sgdisk -n 21:23713792:61472734 \
    -t 21:8300 \
    -c 21:rootfs /dev/mmcblk0
```

4. 让内核重新读取 GPT：

```bash
sudo partprobe /dev/mmcblk0
sudo blockdev --rereadpt /dev/mmcblk0
sudo sgdisk -v /dev/mmcblk0
sudo sgdisk -p /dev/mmcblk0
```

5. 清除 `p20` 开头的旧文件系统签名，避免 Android 首次启动误判：

```bash
sudo dd if=/dev/zero of=/dev/mmcblk0p20 bs=1M count=64 conv=fsync
```

不要手工给 `p20` 建加密 Android 文件系统；让原厂 Android 首次启动时初始化 `/data`。

## 7. 创建 Linux 根文件系统

先确认 `findmnt -n -o SOURCE /` 显示当前从 U 盘运行，再核对 `lsblk -o NAME,SIZE,LABEL,PARTLABEL,MOUNTPOINTS`。首次创建 p21 或确认要清空现有 Linux 根盘时，格式化 p21 并挂载：

```bash
sudo mkfs.ext4 -F -L EMMC_ROOTFS /dev/mmcblk0p21
sudo mkdir -p /mnt/emmc
sudo mount /dev/mmcblk0p21 /mnt/emmc
```

可以从当前 USB Linux 复制：

```bash
sudo rsync -axHAWX --numeric-ids \
    --exclude='/dev/*' \
    --exclude='/proc/*' \
    --exclude='/sys/*' \
    --exclude='/tmp/*' \
    --exclude='/run/*' \
    --exclude='/mnt/*' \
    --exclude='/media/*' \
    --exclude='/lost+found' \
    / /mnt/emmc/
```

也可以解压 [7.3.5 桌面构建](../mainline-7.3/README.md#7-debian-桌面与-rootfs-构建) 生成的 eMMC 根镜像。将下方路径替换为构建的输出目录：

```bash
sudo tar --numeric-owner --xattrs --acls -I zstd \
    -xf artifacts/7.3/desktop-kuaner/rootfs-7.3.5-cloudbook-emmc.tar.zst -C /mnt/emmc
```

确认 `/mnt/emmc/etc/fstab` 使用：

```text
LABEL=EMMC_ROOTFS / ext4 defaults,noatime,commit=120,errors=remount-ro 0 1
tmpfs /tmp tmpfs defaults,nosuid 0 0
```

完成后同步并卸载：

```bash
sync
sudo umount /mnt/emmc
```

## 8. 设置启动槽

双系统能够正常启动后，可以通过 Debian 电源菜单和 Android「切换系统」切换启动槽。初次安装和恢复时按下文手动设置槽位。[切换入口与授权](../tools/system-switcher/README.md)

分区完成后再处理 `misc`：

- 先进入 Android 验证 4 GiB `userdata` 能正常初始化时，使用 B 槽优先。
- Linux 验证通过后，使用 A 槽优先。

在 USB Linux 或主机上生成对应的 `misc` 镜像，再写入 `/dev/mmcblk0p6`。写入后校验 `0x800` 的 32 字节记录和 `0x0`/`0x4000` BCB 区域。

Linux 稳定启动时：

- A：`priority=15, tries=0, successful=1`
- B：`priority=14, tries=0, successful=1`
- 记录：`00414230010000000f0001000e000100000000000000000000000000da933679`

Android 稳定启动时：

- A：`priority=14, tries=0, successful=1`
- B：`priority=15, tries=0, successful=1`
- 记录：`00414230010000000e0001000f000100000000000000000000000000119c9cae`

## 9. 验证清单

分区层：

```bash
sgdisk -p /dev/mmcblk0
blkid /dev/mmcblk0p20 /dev/mmcblk0p21
lsblk -o NAME,SIZE,FSTYPE,LABEL,PARTTYPENAME
```

预期：

- `p20` 约 4 GiB，名称为 `userdata`
- `p21` 约 18 GiB，文件系统卷标为 `EMMC_ROOTFS`
- `p20`、`p21` 的结束 LBA 与分区表一致
- GPT 校验通过，主表和备份表都在

Android 层：

- B 槽能进入原厂 Android。
- `/data` 能初始化并挂载。
- 检查 `df -h /data`，确认剩余空间符合预期。
- 安装应用前先确认低存储阈值和 dexopt 不会立即占满。

Linux 层：

- A 槽能进入 Linux。
- `findmnt /` 显示 `/dev/mmcblk0p21`。
- `/etc/fstab`、initramfs 的 `EMMC_ROOTFS` 卷标一致。
- 重启多次后仍稳定进入 A 槽。

## 10. 风险

- **GPT 损坏**：改分区表时断电或工具中断，可能导致主/备 GPT 不一致。恢复需要 Loader/Rockusb 和 GPT 备份。
- **Android 数据丢失**：重建 `p20` 会清掉原 Android `/data`。没有备份就无法恢复。
- **Android 4 GiB 空间不足**：能开机，但应用更新、缓存、照片和 OTA 很快占满；建议 8 GiB。
- **Android 首次启动可能重新格式化 `p20`**：这是预期行为；不要在其中放置需要保留的 Linux 数据。
- **`p21` 未被识别**：Linux initramfs 依赖 `EMMC_ROOTFS` 卷标；GPT 分区名不是关键，文件系统卷标才是关键。
- **在线修改一定不安全**：不要在 Android 运行中修改 GPT。
- **如果 `p20` 当前是 Linux 根盘且没有备份**：重建 `p20` 会让该 Linux 系统丢失，必须先把 rootfs 复制到 USB 或 `p21`。

## 11. 回滚

如果只做了 GPT 修改但尚未写入系统：

- 用 `sgdisk --backup` 生成的 GPT 备份恢复。
- 或删除 `p21`，把 `p20` 恢复到原始结束 LBA `61,472,703`。

如果 `p20` 已被重建：

- 原始 Android `/data` 没有备份时无法恢复。
- 恢复原厂 Android 需要按 `restore_factory_android.sh` 的流程恢复 `boot_a`、`metadata`、`cache`、`userdata` 和 `misc`。

## 12. 结论

该方案可行，核心限制不是“能不能建 `p21`”，而是：

- `p20` 是最后一个分区，必须先缩小或重建；
- 不能在运行中的 Android 下改；
- 4 GiB 对 Android 只适合应急，不建议作为日常数据分区；
- Linux 侧使用 `p21` + `EMMC_ROOTFS`，A/B 启动仍由 `misc` 控制。

本文件只记录方案，尚未对设备执行任何分区写入。
