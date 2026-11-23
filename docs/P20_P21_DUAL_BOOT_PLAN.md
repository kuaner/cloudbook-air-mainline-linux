# P20/P21 双系统分区与部署

2026-11-23 已验证 CT2000-24RTW 从 A 槽进入 eMMC p21 上的 Debian，从 B 槽进入原厂 Android 11 并挂载 p20 上的 `/data`。装有这套布局的设备重刷 Debian 时，从[部署到现有 p21](#5-创建-linux-根文件系统)开始即可。首次启动 Linux U 盘见 [7.3.5 刷机与部署](FLASHING_GUIDE.md)。

## 1. 启动槽与数据分区

- `boot_a`（p11）存放 Linux 内核、设备树和 initramfs。通用 v30 镜像优先从卷标为 `ROOTFS` 的 U 盘加载 Debian，随后才使用内置 `EMMC_ROOTFS`。
- `boot_b`（p12）和 `super` 内的 B 槽逻辑分区供原厂 Android 使用。Android 的应用、设置和内部存储位于 p20 `userdata`。
- `misc`（p6）保存启动槽记录。两端日常切换使用 [Debian 电源菜单与 Android 切换器](../tools/system-switcher/README.md)。

## 2. 已验证的 8 GiB 布局

| 分区 | 起始 LBA | 扇区数（每扇区 512 字节） | 结束 LBA | 用途 |
| --- | ---: | ---: | ---: | --- |
| p20 `userdata` | 15,325,184 | 16,777,216（8 GiB） | 32,102,399 | Android `/data` |
| p21 `rootfs` | 32,102,400 | 28,968,927（约 13.8 GiB） | 61,071,326 | Debian ext4，卷标 `EMMC_ROOTFS` |

本机原厂 p20 从相同 LBA 开始，大小约 22 GiB，尾部空间不足以直接追加 p21。旧方案中的 4 GiB p20 是早期规划示例，实际日用设备采用上表的 8 GiB。各机以 `sgdisk -p` 和 `lsblk` 的实盘结果为准。

## 3. 首次分区前的检查与备份

仅在尚无 p21 且需要内置双系统时重建分区。操作会清除 p20 上的内容。从独立的 Linux 试跑 U 盘启动，接好电源，将原厂 GPT 和 Android 数据备份到另一块磁盘。若 p20 曾用作 Linux 根盘，也先备份其中的 Linux 用户数据。

下面的分区命令需要 `gdisk` 和 `parted`。

```bash
findmnt -n -o SOURCE /
lsblk -o NAME,SIZE,TYPE,LABEL,PARTLABEL,MOUNTPOINTS
sudo sgdisk -p /dev/mmcblk0
sudo sgdisk -i 20 /dev/mmcblk0
sudo sgdisk --backup=/path/to/backup/mmcblk0-gpt.bin /dev/mmcblk0
```

确认 `/` 来自试跑 U 盘，p20 没有挂载，GPT 中 p20 是最后一个分区。核对扇区大小、p20 起始位置和磁盘末尾可用空间。重建时保留 p20 的类型 GUID、唯一分区 GUID、名称和起始 LBA。不要在 Android 挂载 `/data` 或从 eMMC 启动时修改分区表。

## 4. 重建分区

以下为上表布局的示例。将两个 GUID 变量设为上一步读出的 p20 原值，检查结束 LBA 在本机可用范围内后再执行。使用一次 `sgdisk` 调用写入更新后的 GPT：

```bash
sudo sgdisk \
    --delete=20 \
    --new=20:15325184:32102399 --typecode="20:$p20_type_guid" \
    --partition-guid="20:$p20_partition_guid" --change-name=20:userdata \
    --new=21:32102400:61071326 --typecode=21:8300 --change-name=21:rootfs \
    /dev/mmcblk0
sudo partprobe /dev/mmcblk0
sudo sgdisk -v /dev/mmcblk0
lsblk -o NAME,SIZE,PARTLABEL,MOUNTPOINTS /dev/mmcblk0
```

如果内核没有重新识别 p20 和 p21，保留试跑 U 盘并重启，再次核对。确认 p20 已是 8 GiB 的 `userdata` 且没有挂载，然后清除它开头的旧文件系统数据，让原厂 Android 首次启动时初始化 `/data`：

```bash
sudo dd if=/dev/zero of=/dev/mmcblk0p20 bs=1M count=64 conv=fsync
```

## 5. 创建 Linux 根文件系统

在试跑 U 盘的 Debian 中执行。确认 `/` 来自 U 盘，p21 在内置 eMMC 上且没有挂载。格式化会清空 p21，重刷前先备份 Debian 的用户文件。准备 `zstd`，将 `rootfs_archive` 指向已有的 eMMC 归档，预先确认可读、校验一致且解压后能装入目标分区。对应的 `boot_linux_73_v30_auto.img` 应已写入 A 槽。[配套镜像与写入方法](FLASHING_GUIDE.md)

```bash
rootfs_archive=/path/to/rootfs-7.3.5-cloudbook-emmc.tar.zst
sudo mkfs.ext4 -L EMMC_ROOTFS /dev/mmcblk0p21
sudo mkdir -p /mnt/emmc
sudo mount /dev/mmcblk0p21 /mnt/emmc
sudo tar --numeric-owner --xattrs --acls -I zstd -xf "$rootfs_archive" -C /mnt/emmc
sudo grep '^LABEL=EMMC_ROOTFS / ' /mnt/emmc/etc/fstab
sync
sudo umount /mnt/emmc
```

归档自带 `LABEL=EMMC_ROOTFS` 的 `fstab`。部署到 p21 后关机，拔掉试跑盘，再启动 A 槽。

## 6. 设置启动槽

从 USB 或 eMMC 启动 Debian A 后，可用 `sudo /usr/local/sbin/cloudbook-mark-boot-successful check-android` 检查 A 槽成功标志和 B 槽切换条件。检查通过后，可以直接重启 A 槽或从电源菜单切到 B 槽。Android 切换器会为 A 槽设置试启动计数。启动失败时先保留 U 盘和原厂 B 槽，按 [OTG 恢复手册](OTG_RECOVERY.md) 检查启动记录。

## 7. 验证清单

- `sudo sgdisk -v /dev/mmcblk0` 通过。`lsblk -o NAME,SIZE,LABEL,PARTLABEL,MOUNTPOINTS` 显示 p20 `userdata`、p21 `rootfs`，p21 的文件系统卷标为 `EMMC_ROOTFS`。
- 拔掉试跑盘，在 Debian 执行 `findmnt -n -o SOURCE /`，显示 `/dev/mmcblk0p21`。
- 切到 Android B，确认 `/data` 已挂载并用 `df -h /data` 查看空间。重建或重置 `userdata` 后按需重新安装切换器和授权 ADB。[Android 重置后的影响](ANDROID_SIDE_SETUP.md#what-a-p20userdata-wipe-destroys)
- 从 Android 切回 Debian，核对 `/` 仍由 p21 提供。

## 8. 回滚

只切换到 Android 可保留现有双系统数据。若需要恢复原厂全盘布局，先从 U 盘启动并备份现有 p20/p21 中需要保留的数据，再使用本机的原厂 GPT 备份。恢复原厂 GPT 会移除 p21，原厂备份中的 `userdata` 镜像只可在确认 p20 起止位置与原厂布局匹配后写回。旧的 `restore_factory_android.sh` 本身不会恢复 GPT，会按约 22 GiB 的原厂 p20 写盘。恢复流程见 [OTG 与冷备份](OTG_RECOVERY.md)。
