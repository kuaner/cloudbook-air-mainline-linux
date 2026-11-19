# 7.3.5 U 盘试跑包

> 用途：不触碰内置 eMMC，从 USB 启动验证 7.3.5 内核（HEVC 硬解）与 LightDM + i3 桌面。
> 系统：Debian 13 (trixie) arm64 + 内核 `7.3.5-cloudbook`（VDPU346 + 全部板级补丁）

## 1. 文件（`artifacts/7.3/`）

| 文件 | 用途 |
| :--- | :--- |
| `boot_linux_73_v28_usb.img` | 写入 `boot_a`，cmdline `root=LABEL=ROOTFS` |
| `rootfs-7.3.5-cloudbook.ext4.zst` | 8 GB ext4 文件系统镜像（卷标 `ROOTFS`），可直接 dd 到分区 |
| `rootfs-7.3.5-cloudbook.tar.zst` | 同一套 rootfs 的 tar 包（分区大小可自选） |
| `modules-7.3.5-cloudbook.tar.gz` | 内核模块（rootfs 内已安装，备用） |
| `Image-7.3.5-cloudbook`、`*.dtb`、`config-7.3.5-cloudbook` | 内核/设备树/配置 |
| `SHA256SUMS` | 校验和 |

## 2. 制作 U 盘（Linux 主机）

先 `lsblk -dpo NAME,SIZE,MODEL` 核对盘符，**千万别写错主机硬盘**。

### 方式 A：直接写 8 GB 镜像（U 盘 ≥ 16 GB）

```bash
sudo parted -s /dev/sdX mklabel gpt
sudo parted -s /dev/sdX mkpart primary ext4 1MiB 100%
zstd -dc rootfs-7.3.5-cloudbook.ext4.zst | sudo dd of=/dev/sdX1 bs=4M conv=fsync
```

### 方式 B：解 tar（U 盘任意 ≥ 2 GB）

```bash
sudo parted -s /dev/sdX mklabel gpt
sudo parted -s /dev/sdX mkpart primary ext4 1MiB 100%
sudo mkfs.ext4 -L ROOTFS /dev/sdX1
sudo mkdir -p /mnt/cb && sudo mount /dev/sdX1 /mnt/cb
sudo tar -I zstd -xf rootfs-7.3.5-cloudbook.tar.zst -C /mnt/cb
sync && sudo umount /mnt/cb
```

确认卷标（initramfs 靠它找根分区）：

```bash
sudo blkid /dev/sdX1   # 应显示 LABEL="ROOTFS"
```

## 3. 写入 `boot_a` 并首次启动

按 `docs/FLASHING_GUIDE.md` 第 5 节的流程，把 `boot_linux_73_v28_usb.img` 写入 `boot_a`
（`/dev/mmcblk0p11`），并生成 A 槽三次试启动的 `misc`。这一步仍需要 loader/UART/adb 通道。

## 4. 首次启动预期

- UART（`115200 8N1`）：`Linux version 7.3.5-cloudbook`、`CLOUDBOOK found ROOTFS at ...`、systemd 启动
- 屏幕：LightDM 登录界面 → 会话 `i3`
- 账户：

| 账户 | 密码 |
| :--- | :--- |
| `cloudbook`（sudo） | `cloudbook` |
| `root` | `cloudbook` |

**首次登录后请改密码**：`passwd`。

桌面快捷键（已写入 `/home/cloudbook/.config/i3/config`）：

| 键 | 动作 |
| :--- | :--- |
| `Mod4 + Enter` | xterm |
| `Mod4 + d` | dmenu |
| `Mod4 + Shift + d` | rofi |
| `Mod4 + 1..8` | 工作区切换 |
| `Mod4 + Shift + e` | 退出 i3 |
| `Print` | 截图到 `~/Pictures` |

## 5. 验收清单

```bash
uname -r                                  # 7.3.5-cloudbook
lsmod | grep -E "panfrost|mt7663s|btmtksdio|rockchip_vdec"
dmesg | grep -i panfrost                  # Mali-G52
glxinfo -B | grep -E "OpenGL renderer|version"

# Wi-Fi / BT
nmcli device status
iw dev
bluetoothctl show

# HEVC 解码器
v4l2-ctl --list-devices                   # 应出现 rockchip-vdec
v4l2-ctl -d /dev/videoN --list-formats-out  # 应包含 HEVC/H.265
gst-inspect-1.0 v4l2slh265dec             # 有解码设备时才会注册

# 播放示例（把码流换成自己的）
gst-launch-1.0 filesrc location=/path/sample.hevc ! h265parse ! \
  v4l2slh265dec ! videoconvert ! xvimagesink

# 电源
cat /sys/class/power_supply/*/online
systemctl suspend                         # 合盖/唤醒回归

# ZRAM（4 GB 机器，默认取内存 50%）
zramctl
swapon --show                             # /dev/zram0，约 2 GB
systemctl status zramswap
```

## 6. 已知注意事项

1. **模块目录必须与 `uname -r` 一致**（`/usr/lib/modules/7.3.5-cloudbook`），rootfs 已匹配。
2. **根分区卷标必须是 `ROOTFS`**（U 盘）；迁 eMMC 时改成 `EMMC_ROOTFS` 并同步修改 `/etc/fstab`。
3. GStreamer 的 `v4l2sl*` 元素是**按设备动态注册**的，chroot 里看不到属正常，真机上加载 `rockchip-vdec` 后出现。
4. Hyprland/Omarchy 未采用：Panfrost 只有 GLES 3.1，Hyprland 会回退 GLES 3.0，模糊/动画需关闭；4 GB 内存也偏紧。作者验证过的 LightDM + i3wm 是这块板子的稳妥选择。
5. VP9 硬解仍不可用：VDPU346 系列只支持 H.264 + HEVC。