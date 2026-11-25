# CloudBook Air 7.3.5 刷机与部署手册

适用于 `CT2000-24RTW`（W620D V12.0、RK3568B2）。A 槽从内置 eMMC 的 `boot_a` 加载 Linux，B 槽保留原厂 Android 11。Ubuntu 26.04 LTS ARM64 可先从 U 盘试跑，再部署到内置 p21。A 槽已有识别 `ROOTFS` 的 `7.3.5-cloudbook` 时，从第 2 节开始。原厂 A 槽首次试跑需按第 1–3 节准备。[6.12 移植基线](../mainline-6.12/README.md)

## 1. 核对机型和备份

1. 主机准备 `adb` 和 `python3`。启动原厂 Android B，从主机连接已授权的 ADB，再运行以下只读检查。[Android ADB 配置](ANDROID_SIDE_SETUP.md#adb-persistence)

   ```bash
   adb devices -l
   adb shell getprop ro.product.model
   adb shell getprop ro.boot.slot_suffix     # _b
   adb shell su 0 id -u                      # 0
   ```

   如果有多台 ADB 设备，先设置环境变量 `ANDROID_SERIAL` 选定这台机器。若原厂 Android 当前运行在 A 槽，先独立验证 B 槽能够正常启动，再继续。

2. 保存本机原始分区和 GPT 备份。单独为本次操作建一个快照目录，避免覆盖原厂备份。下面可从 Android 备份本次试跑涉及的两个分区及 B 槽启动镜像。其余备份见 [OTG 恢复手册](OTG_RECOVERY.md#1-冷备份清单与校验)。

   ```bash
   mkdir -p factory_backup
   snapshot=$(mktemp -d ./factory_backup/pre-flash.XXXXXXXX)
   for part in boot_a boot_b misc; do
       adb exec-out su 0 cat "/dev/block/by-name/$part" > "$snapshot/$part.img" || break
   done
   wc -c "$snapshot/boot_a.img" "$snapshot/boot_b.img" "$snapshot/misc.img"
   (cd "$snapshot" && shasum -a 256 ./*.img > SHA256SUMS)
   ```

   本机的 `boot_a`、`boot_b` 分别为 167772160 字节，`misc` 为 4194304 字节。逐个确认文件长度，备份失败时先解决连接问题。首次构建需要从本机原厂 `boot_b` 提取资源模板：

   ```bash
   mkdir -p artifacts
   python3 scripts/extract_resource_from_boot.py "$snapshot/boot_b.img" -o artifacts/extracted_resource.img
   ```

3. Linux 镜像写入 `boot_a`。保留 eMMC 前 8 MiB 中的原厂引导内容及 `security`、`uboot_a/b`、`trust_a/b`、`boot_b` 和 `super`。如需恢复通道，Type-C 旁的 USB-A 是 Rockusb/UMS 刷机口。[OTG 入口与恢复限制](OTG_RECOVERY.md#3-进入-otg-刷机模式与-loader-限制说明)

## 2. 准备配套镜像与试跑 U 盘

使用 `usb/rootfs-ubuntu-26.04-niri-noctalia-usb.ext4` 写入一只容量至少为 8 GiB 的 U 盘，市售 16 GB 盘足够。该操作覆盖 U 盘内容。在 macOS 上，从仓库根目录运行以下命令，将 `diskN` 替换为 `diskutil list external physical` 中确认的 U 盘编号。[Ubuntu 镜像构建](../mainline-7.3/README.md#7-ubuntu-桌面与-rootfs-构建)

```bash
diskutil list external physical
diskutil unmountDisk /dev/diskN
sudo dd if=artifacts/7.3/ubuntu-26.04/usb/rootfs-ubuntu-26.04-niri-noctalia-usb.ext4 of=/dev/rdiskN bs=4m
sync
diskutil eject /dev/diskN
```

A 槽已刷入兼容的 `7.3.5-cloudbook` v30 通用引导且处于激活状态时，插着 U 盘开机即可。拔掉试跑盘后，通用引导会使用内置 `EMMC_ROOTFS`。内置 Linux 尚未部署时，保留 U 盘启动。macOS 如提示无法读取 ext4，选择「忽略」。

## 3. 首次写入 A 槽

以下命令从主机执行，设备保持在已验证的 Android B。使用第 1 节生成的 `snapshot`，将 `boot_img` 指向同批生成的 Ubuntu USB 引导。确认文件大小在 `boot_a` 容量以内。写入后只比对镜像覆盖的有效字节。

```bash
boot_img=artifacts/7.3/ubuntu-26.04/usb/boot_linux_73_v30_ubuntu_2604_usb.img
adb push "$boot_img" /data/local/tmp/cloudbook-boot-a.img
adb shell su 0 dd if=/data/local/tmp/cloudbook-boot-a.img of=/dev/block/by-name/boot_a bs=4M conv=fsync
adb exec-out su 0 cat /dev/block/by-name/boot_a > "$snapshot/boot_a.readback.img"
head -c "$(wc -c < "$boot_img" | tr -d '[:space:]')" "$snapshot/boot_a.readback.img" | cmp "$boot_img" -
```

读回一致后，把试跑 U 盘插到 CloudBook 的 USB-A 接口，再选择首次启动方式。

- **已有内置 p21，且 Android 已安装切换器**。打开「电源」，点击「检查启动状态」，校验通过后选择「Linux」。旧版 APK 中该入口显示为「Debian」。[图形切换和 ADB 授权](../tools/system-switcher/README.md)
- **手动设置 U 盘试启动**。适用于还没有 p21 或未安装 Android 切换器的设备。先检查刚备份的 `misc` 中 B 槽已标记启动成功、A/B 记录 CRC 正确、BCB 区域无待执行的命令。以下只更新 A/B 记录，保留 `misc` 中其他内容。

  ```bash
  python3 - "$snapshot/misc.img" <<'PY'
  from pathlib import Path
  import sys
  import zlib

  misc = Path(sys.argv[1]).read_bytes()
  record = misc[0x800:0x820]
  assert len(misc) == 4 * 1024 * 1024, 'Unexpected misc size'
  assert record[:6] == b'\0AB0\x01\0', 'Unexpected A/B record format'
  assert int.from_bytes(record[28:], 'big') == zlib.crc32(record[:28]), 'Invalid A/B record CRC'
  assert record[12] > 0 and record[14] == 1, 'Android B is not marked successful'
  assert not any(misc[:32] + misc[0x4000:0x4020]), 'A bootloader command is pending'
  PY
  ```

  检查全部通过后，生成试启动记录并仅写入偏移 `0x800` 的 32 字节：

  ```bash
  python3 switch_ab_slot.py test_slot_a "$snapshot/misc-test-a.img"
  dd if="$snapshot/misc-test-a.img" of="$snapshot/ab-test-a.bin" bs=32 skip=64 count=1
  adb push "$snapshot/ab-test-a.bin" /data/local/tmp/cloudbook-ab-test-a.bin
  adb shell su 0 dd if=/data/local/tmp/cloudbook-ab-test-a.bin of=/dev/block/by-name/misc bs=32 seek=64 count=1 conv=notrunc,fsync
  adb exec-out su 0 dd if=/dev/block/by-name/misc bs=32 skip=64 count=1 status=none | cmp "$snapshot/ab-test-a.bin" -
  ```

  手动写入的记录读回一致后，执行 `adb reboot`。

初始 `tries_remaining=3` 是引导器消耗的 A 槽尝试计数。Linux 挂载本地根盘后由 `cloudbook-mark-boot-successful.service` 标记实际启动槽。[OTG 恢复手册](OTG_RECOVERY.md)

## 4. 验收与部署到 eMMC

默认用 `kuaner` 登录，初始密码为 `kuaner`。按 `Win+Enter` 打开 Foot，运行 `cat /etc/os-release` 确认 Ubuntu 26.04，运行 `uname -r` 确认 `7.3.5-cloudbook`，用 `findmnt -n -o SOURCE /` 和 `lsblk -o NAME,LABEL,MOUNTPOINTS` 确认根盘来自标记为 `ROOTFS` 的 U 盘。`Win+D` 打开应用菜单，`Win+S` 打开 Noctalia 控制中心。三指左右滑动浏览横向窗口列。验收时检查 Wi-Fi、视频播放、合盖和重启。

通过 U 盘验收后，从 U 盘启动并将 `emmc/rootfs-ubuntu-26.04-niri-noctalia-emmc.ext4` 写到未挂载的 `/dev/mmcblk0p21`，将 `emmc/boot_linux_73_v30_ubuntu_2604_emmc.img` 写到 `boot_a`。先用 `lsblk` 和 GPT 分区名核对 p21 与 `boot_a`，并确认目标容量。写入后对未挂载的 p21 运行 `e2fsck -f` 和 `resize2fs`，使文件系统使用整个分区。写入 ext4 镜像会覆盖 p21 上的旧系统。首次划分 p20/p21 会清除原 `userdata`。[实机布局与分区条件](P20_P21_DUAL_BOOT_PLAN.md)

部署完成后，关机并拔掉 U 盘，再开机确认 `/` 挂载自 `/dev/mmcblk0p21`。Noctalia 电源菜单中选择「重启到安卓」可启动 B 槽。[双系统切换](../tools/system-switcher/README.md)

eMMC 引导先检查已就绪的 `ROOTFS` U 盘。只有内核已枚举到 USB 存储接口时，才额外等至多四秒扫描盘符。未检测到存储时直接走 eMMC。较慢的 U 盘可尝试另一 USB 口。首次试跑引导始终提供这段扫描窗口。[镜像构建](../mainline-7.3/README.md#7-ubuntu-桌面与-rootfs-构建)

如需恢复 Android 原始 userdata 镜像，先确认目标分区尺寸。旧的 `restore_factory_android.sh` 按约 22 GiB 的原厂 p20 写入，必须先恢复匹配的原厂 GPT 布局才能使用该全量镜像。需要保留 p21 时，使用现有 B 槽启动和适合当前 p20 尺寸的数据恢复方式。[恢复与回滚](P20_P21_DUAL_BOOT_PLAN.md#8-回滚)
