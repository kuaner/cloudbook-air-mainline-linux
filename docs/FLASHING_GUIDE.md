# CloudBook Air 7.3.5 刷机与部署手册

适用于 `CT2000-24RTW`（W620D V12.0、RK3568B2）。A 槽从内置 eMMC 的 `boot_a` 加载 Linux，B 槽保留原厂 Android 11。Debian 13 ARM64 可以先从 U 盘试跑，再部署到内置 p21。U 盘试跑首次仍需写入内置 `boot_a` 并设置启动槽。6.12 的 v28/v29 构建见 [6.12 移植基线](../mainline-6.12/README.md)。

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

用同一次 [7.3.5 桌面构建](../mainline-7.3/README.md#7-debian-桌面与-rootfs-构建) 生成的 `boot_linux_73_v30_auto.img` 和 `rootfs-7.3.5-cloudbook-usb.ext4`。安装已有本地镜像时，可以在输出目录运行 `shasum -a 256 -c SHA256SUMS` 核对完整性。按 [U 盘制作步骤](USB_TEST_KIT_7.3.md#2-制作-u-盘) 将 ext4 根镜像写到专用 U 盘。这一步覆盖 U 盘内容。

已刷入配套的 v30 通用引导镜像且已选择 A 槽时，插着这只 U 盘重启即可。拔掉试跑盘后，通用镜像会改用内置卷标 `EMMC_ROOTFS`。如果内置 Linux 还没部署，保留 U 盘启动。

## 3. 首次写入 A 槽

以下命令从主机执行，设备保持在已验证的 Android B。先完成上面的备份并核对大小。把 `boot_img` 指向同批生成的通用镜像，确认文件大于零且不超过 `boot_a` 大小。写入后只比对镜像覆盖的有效字节。

```bash
boot_img=artifacts/7.3/desktop-kuaner/boot_linux_73_v30_auto.img
adb push "$boot_img" /data/local/tmp/cloudbook-boot-a.img
adb shell su 0 dd if=/data/local/tmp/cloudbook-boot-a.img of=/dev/block/by-name/boot_a bs=4M conv=fsync
adb exec-out su 0 cat /dev/block/by-name/boot_a > "$snapshot/boot_a.readback.img"
head -c "$(wc -c < "$boot_img" | tr -d '[:space:]')" "$snapshot/boot_a.readback.img" | cmp "$boot_img" -
```

读回一致后，把试跑 U 盘插到 CloudBook 的 USB-A 接口，再选择首次启动方式。

- **已有内置 p21，且 Android 已安装切换器**。打开「切换系统」，点击「检查启动状态」，校验通过后重启到 Debian。[图形切换和 ADB 授权](../tools/system-switcher/README.md)
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

初始 `tries_remaining=3` 是 A 槽计数，不能按固定三次整机重启理解。计数由引导器消耗，黑屏停住时它不会自动重启。Debian 挂载本地根盘后由 `cloudbook-mark-boot-successful.service` 标记实际启动槽。无法启动时见 [OTG 恢复手册](OTG_RECOVERY.md)。

## 4. 验收与部署到 eMMC

登录 LightDM 后，用 `uname -r` 确认 `7.3.5-cloudbook`，用 `findmnt -n -o SOURCE /` 和 `lsblk -o NAME,LABEL,MOUNTPOINTS` 确认根盘是试跑 U 盘。账户、快捷键、Wi-Fi 与硬解检查见 [首次启动](USB_TEST_KIT_7.3.md#4-首次启动预期) 和 [验收清单](USB_TEST_KIT_7.3.md#5-验收清单)。

内置部署使用 `rootfs-7.3.5-cloudbook-emmc.tar.zst`。若已有 p21，备份 Debian 数据后直接从 U 盘启动并[部署到现有 p21](P20_P21_DUAL_BOOT_PLAN.md#5-创建-linux-根文件系统)。首次划分 p20/p21 会清除原 `userdata`，先看[实机布局与分区条件](P20_P21_DUAL_BOOT_PLAN.md)。

部署完成后，关机并拔掉 U 盘，再开机确认 `/` 挂载自 `/dev/mmcblk0p21`。Debian 电源菜单选择「安卓」可启动 B 槽。只切换系统保留 Debian 和 Android 各自的数据。[双系统切换](../tools/system-switcher/README.md)

如需恢复 Android 原始 userdata 镜像，先确认目标分区尺寸。旧的 `restore_factory_android.sh` 按约 22 GiB 的原厂 p20 写入，必须先恢复匹配的原厂 GPT 布局才能使用该全量镜像。需要保留 p21 时，使用现有 B 槽启动和适合当前 p20 尺寸的数据恢复方式。[恢复与回滚](P20_P21_DUAL_BOOT_PLAN.md#8-回滚)
