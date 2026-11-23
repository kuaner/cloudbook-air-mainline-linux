# 7.3.5 U 盘试跑包

> U-Boot 从内置 eMMC 的 A 槽加载 7.3.5 内核，Debian 桌面系统放在 U 盘上。Android B 槽用作回退。
> 系统：Debian 13 (trixie) arm64 + 内核 `7.3.5-cloudbook`（VDPU346 + 全部板级补丁）

## 1. 新版桌面试跑文件

`scripts/build-rootfs-735.sh` 将以下文件写入指定的输出目录。构建命令和本地输入见 [7.3.5 桌面与 rootfs 构建](../mainline-7.3/README.md#7-debian-桌面与-rootfs-构建)。

| 文件 | 用途 |
| :--- | :--- |
| `boot_linux_73_v30_auto.img` | 写入 `boot_a`，优先查找 `ROOTFS` U 盘，未找到时挂载 `EMMC_ROOTFS` |
| `rootfs-7.3.5-cloudbook-usb.ext4` | 8 GiB 整盘 ext4，卷标 `ROOTFS`，可直接写到专用 U 盘 |
| `rootfs-7.3.5-cloudbook-usb.ext4.zst` | 同一文件系统的压缩版 |
| `rootfs-7.3.5-cloudbook-emmc.tar.zst` | 同一桌面的 eMMC 根镜像，默认挂载卷标为 `EMMC_ROOTFS` |
| `SHA256SUMS` | 校验和 |

下文 2026-11-21 和 2026-11-22 的测试记录还引用了早期镜像 `boot_linux_73_v28_usb.img`、`rootfs-7.3.5-cloudbook.ext4` 及 `rootfs-7.3.5-cloudbook.tar.zst`。

## 2. 制作 U 盘

使用专用 U 盘，容量至少 8 GiB。常见标称 8 GB 的 U 盘容量不足以写入整盘镜像。

### 方式 A　写入整盘 ext4 镜像

本机使用整盘 ext4。整盘写入将覆盖 U 盘上原有的系统和文件。先核对设备并卸载目标 U 盘上的文件系统，再从镜像输出目录执行命令。若手中是压缩版，先用 `zstd -d` 解压。

Linux 主机用 `lsblk -dpo NAME,SIZE,MODEL` 确认 U 盘盘符。

```bash
sudo dd if=rootfs-7.3.5-cloudbook-usb.ext4 of=/dev/sdX bs=4M status=progress conv=fsync
sudo blkid -p /dev/sdX  # LABEL="ROOTFS"，TYPE="ext4"
```

macOS 主机用 `diskutil list external physical` 确认 U 盘编号。把下方 `N` 换为实际编号：

```bash
diskutil unmountDisk /dev/diskN
sudo dd if=rootfs-7.3.5-cloudbook-usb.ext4 of=/dev/rdiskN bs=4m
sync
diskutil eject /dev/diskN
```

### 方式 B　从 Linux 主机解包到 U 盘分区

用 `lsblk -dpo NAME,SIZE,MODEL` 核对目标盘符并卸载 U 盘。以下命令会重建其分区表，需要 `parted`、`e2fsprogs` 和 `zstd`。

```bash
sudo parted -s /dev/sdX mklabel gpt
sudo parted -s /dev/sdX mkpart primary ext4 1MiB 100%
sudo mkfs.ext4 -L ROOTFS /dev/sdX1
sudo mkdir -p /mnt/cb && sudo mount /dev/sdX1 /mnt/cb
sudo tar --numeric-owner --xattrs --acls -I zstd -xf rootfs-7.3.5-cloudbook-emmc.tar.zst -C /mnt/cb
sudo sed -i 's/^LABEL=EMMC_ROOTFS /LABEL=ROOTFS /' /mnt/cb/etc/fstab
sync && sudo umount /mnt/cb
```

对于方式 B，确认分区卷标为 `ROOTFS`。

```bash
sudo blkid /dev/sdX1   # 应显示 LABEL="ROOTFS"
```

## 3. 写入 `boot_a` 并首次启动

首次安装按 [写入 A 槽](FLASHING_GUIDE.md#3-首次写入-a-槽)操作。已刷入同批构建的 `boot_linux_73_v30_auto.img` 且当前选择 A 槽时，插着带 `ROOTFS` 卷标的 U 盘重启即可再次试跑。

## 4. 首次启动预期

- UART（`115200 8N1`）：`Linux version 7.3.5-cloudbook`、`CLOUDBOOK USB root found at ...`、systemd 启动
- 屏幕：CloudBook Air 启动画面，随后进入 LightDM。登录和锁屏沿用 Archcraft i3 的东京夜景、深色底和粉色强调色。密码卡片和顶栏 `cloudbook` 标识各自居中，右上角日期、时间和电源图标与桌面采用同一套样式。
- 账户：

| 账户 | 密码 |
| :--- | :--- |
| `kuaner`（sudo） | `kuaner`，可由构建时的 `CLOUDBOOK_LOGIN_PASSWORD` 覆盖 |
| `root` | `cloudbook` |

首次登录后运行 `passwd` 修改当前用户密码，再用 `sudo passwd root` 修改 root 初始密码。

登录后，点顶部网络图标选择 Wi-Fi。通用镜像不含个人 Wi-Fi 密码。

| 快捷键 | 操作 |
| --- | --- |
| `Win+D` / `Win+Shift+D` | 应用菜单 / 命令运行器 |
| `Win+Enter` / `Win+Shift+F` | Xfce Terminal / 文件管理器 |
| `Win+N` | Wi-Fi 连接列表 |
| `Win+Q` / `Win+F` | 关闭当前窗口 / 切换全屏 |
| `Win+方向键` / `Win+Shift+方向键` | 切换窗口焦点 / 移动当前窗口 |
| `Win+1` 至 `Win+8` | 切换工作区，加 `Shift` 将当前窗口移到对应工作区 |
| `Win+Shift+X` / `Win+Shift+E` | 锁屏 / 打开电源菜单 |
| `Ctrl+空格` / `Print` | 切换中文输入 / 截图到 `~/Pictures` |

关机可按 `Win+Shift+E`，选「关机」后确认。登录界面也可用右上角的电源按钮。按 `Win+Shift+X` 或从合盖休眠唤醒后，在屏幕中央输入 Debian 登录密码，按 `Enter` 或点「解锁」。

已部署到 eMMC p21 的双系统可以从 Debian 的电源菜单选择「安卓」。在 Android 中打开「切换系统」可以重启回 Debian。[图形切换与授权步骤](../tools/system-switcher/README.md)

时区为 `Asia/Shanghai`。联网后 `systemd-timesyncd` 依次尝试阿里云、Cloudflare 与 Debian 池的 NTP 源。运行 `timedatectl timesync-status` 可查看应答服务器和收到的数据包数；各 NTP 源均不可达时，系统会继续使用本地时钟，等待后续联网同步。

软件源使用阿里云 Debian 镜像。主仓库、常规更新和安全更新分别使用 `trixie`、`trixie-updates`、`trixie-security`，由 Debian 归档密钥验证签名。

### 安装图形应用

该桌面是 Debian 13 `arm64` 上的 i3/X11，可以安装 Debian 仓库中的应用，也可以安装供应商发布的、依赖与 Debian 13 兼容的 Linux ARM64 `.deb`。使用 `dpkg --print-architecture` 确认架构，用 `apt` 安装以解析依赖。安装后按 `Win+D` 查找应用。

```bash
sudo apt update
sudo apt install mousepad                   # Debian 仓库中的图形文本编辑器
sudo apt install ./PACKAGE_linux_arm64.deb   # 替换为本地软件包的实际文件名
```

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
systemctl suspend                         # 手动休眠及唤醒，合盖另测

# ZRAM（4 GB 机器，默认取内存 50%）
zramctl
swapon --show                             # /dev/zram0，约 2 GB
systemctl status zramswap
```

### 本地验证（2026-11-21）

原 USB 引导镜像只扫描 `/dev/sd*[0-9]`。修订镜像沿用 7.3.5 内核、v28 主线设备树和启动参数，使用本机原厂 `boot_b` 的 RSCE 模板。逐条核验了第 0 项和第 17 项的 DTB、长度与 SHA-1，以及其余 25 项的原厂内容。原厂 A 槽 DTBO 应用后，eDP 面板、`rk3568-vdec` 和解码器 IOMMU 均启用。桌面镜像通过只读 ext4 全盘检查，包含匹配的 `rockchip-vdec.ko` 和 GStreamer V4L2 stateless 插件。

ARM64 QEMU 通过 USB 大容量存储挂载整盘 `/dev/sda`。图形测试在独立副本上增加了 Xorg 虚拟显示驱动。LightDM 接受 `cloudbook` 登录，`loginctl` 报告活动会话 `Desktop=i3`、`Type=x11`，i3 成功打开 xterm 窗口。

### 本机内屏与硬解（2026-11-21）

| 镜像 | 同一块 BOE NV140WUM-N44 的实测结果 |
| --- | --- |
| 6.12.117 `#5` | 登录提示在内屏可见。日志记录了 `240x75` 字符帧缓冲，显示驱动成功绑定。 |
| 7.3.5 `#7` | 文字控制台和 LightDM 均黑屏。面板 EDID 可读，LightDM greeter 已运行。设置链路速率时 PHY 连续五次返回 `-EINVAL`（`-22`），随后显示时钟锁定超时，eDP 驱动结束重试。 |
| 7.3.5 `#8` | 设置速率与训练电压时均传入协商的通道数。真机启动日志中上述错误为零。内屏已目视确认进入 i3 桌面，`seat0` 的活动会话为 `cloudbook`、`Type=x11`、`Desktop=i3`。Xorg 使用 `1920x1200@60`，亮度 `200/255`，背光 PWM 实际占空时间为 `19584/25000 ns`。 |

两次 7.3.5 真机试启动都挂载了 U 盘 `/dev/sda`。强制使用 GStreamer `v4l2slh265dec` 解码 720p HEVC 样本，两次均退出为 0 并输出全部 48 帧。修正版保留了原厂 B 槽提取的 RSCE、设备树和 initramfs。修正版 A 槽写入后已读回校验完整 160 MiB，B 槽的 `boot_b` 与此前的原厂备份一致。

在 `#8` 上又用含移动色块的 `1920×1080`、30 fps 样本分别验证了 `v4l2slh264dec` 和 `v4l2slh265dec`。每种格式均解出 60 帧，转成 I420 后各为 186,624,000 字节，完整输出的 SHA-256 分别与 FFmpeg 软件解码结果一致。这次检验了帧内容，播放速度与 4K 性能尚待测量。

本机写入 A 的试启动计数为 3。`#8` 首次启动后在 Linux 读到 `A=15/1/0`、`B=14/0/1`（优先级、剩余尝试、成功标志）。这一次上电过程中计数减少了 2。先前黑屏时 Linux 和 LightDM 仍在运行，停留在该会话不会触发引导程序再次选槽。收到 `Ctrl+Alt+Delete` 后，设备才开始下一轮启动，自动进入了 Android B，普通 TCP ADB 也随之恢复。

### 桌面电量与外设

本机 SBS 电量计位于 `/sys/class/power_supply/sbs-0-000b/`。初次登录时，内核上报 `present=1`、电量 53%、正在放电。i3status 默认从 `BAT0` 等路径读取电量，状态栏显示 `No battery`。修订配置直接显示 SBS 的 `capacity` 和 `status`。现场启动的状态栏和电量计随后同时报告 `58%`、`Charging`，稍后电量计升至 62%。

Xorg 已在 Mali-G52 上启用 glamor，Mesa 报告 `Panfrost` 和硬件加速。`glxgears` 在垂直同步下测得 59.6 FPS。蓝牙控制器已上电，摄像头可枚举 MJPEG 格式，内置声卡接受了 48 kHz 双声道静音 PCM。摄像头的成像和扬声器的实际听感需要分别检验。

### 鼠标间歇性卡顿

在当前 i3 会话移动光标时，Rockchip 显示控制器连续 1,205 个垂直刷新间隔的均值为 16.67 ms，最长 16.74 ms。同一轮测试中，X11 模拟移动并等待服务器响应偶尔花了 25–46 ms。单看面板刷新率会漏掉这类停顿。

随后用 `uinput` 生成相对鼠标位移，经 evdev、libinput 和 Xorg，用 `XQueryPointer` 测量桌面坐标何时更新。先交替测了两轮 408/816 MHz，再交替测了两轮 600/816 MHz。下表给出每轮的中位数和第 95 百分位数。

| CPU 最低频率 | 每轮输入次数 × 轮数 | 中位延迟 | 95% 的输入在此时间内更新 |
| --- | --- | --- | --- |
| 408 MHz | 270 × 2 | 9.43–9.48 ms | 20.29–20.43 ms |
| 600 MHz | 240 × 2 | 2.58–2.60 ms | 17.48–17.86 ms |
| 816 MHz | 270 × 2、240 × 2 | 2.05–2.26 ms | 14.20–16.72 ms |

还在临时测试窗口绘制了颜色已知的光标，以每 8 ms 一次的相对位移驱动它，并采样 eDP 当前扫描输出的帧缓冲。先用三个已知位置校准，在三个频率下各测两轮，每轮 750 次位移。只统计直线移动且相邻两次刷新均成功采样的情形，排除了折返处。408 MHz 时，544 对相邻刷新中有 4 对光标停在同一位置，600 MHz 时有 2/554，816 MHz 时有 1/553。600 MHz 两轮观察到的两次光标位置变化之间最长间隔分别为 33.42 和 44.52 ms。停顿次数很少，这组数字不足以比较三个频率的长期掉帧率。

真机和桌面根镜像均采用 600 MHz 下限。`/etc/tmpfiles.d/cloudbook-cpu.conf` 应用后，真机读回 `scaling_min_freq=600000`，调频策略为 `schedutil`，上限为 1.992 GHz。这个设置改善了上述模拟输入的中位响应。面板实际发光时刻和续航影响尚待测量。

还交替比较了 GPU 的 50 ms 和 250 ms 自动休眠延时。每次停手 80–170 ms 后移动一次，各设置测了 160 次。50 ms 组有 159 次移动从 GPU 已休眠的状态开始，250 ms 组的 GPU 始终处于活动状态。两组从输入到帧缓冲光标出现变化的中位数均约 10 ms，320 次最长 12.06 ms。真机继续使用原有的 50 ms 自动休眠延时。

最后读取本机 `0911:5288` 触控板的输入能力，创建临时虚拟触控板，让 libinput 处理单指移动。在 600 MHz 下用 8 ms、16 ms 的输入周期各测两轮。四轮合计 1,132 对可比的直线移动刷新都更新了光标位置，包含折返在内的最大位置更新间隔为 28.14–42.85 ms。实体触控板当时没有手指移动。现场另启用限时一小时的触控板时序采样，只保存移动间隔统计与内核丢失事件计数。

随后在使用者实际滑动时分开采集了触控板、X11 和内核等待栈。`#8` 的独立触控板读取器记录了 935 个移动间隔，中位数 7.27 ms，P95 为 7.37 ms。右上方一次 `XQueryPointer` 往返用了 554 ms。另一对相邻观测相隔 234 ms，坐标相差 144 像素。这个时间包含整个 X 请求的等待。Xorg 主线程在移动期间反复进入 `drm_mode_dirtyfb_ioctl → drm_atomic_helper_dirtyfb → drm_atomic_helper_wait_for_vblanks`。Panfrost 和 glamor 均启用，光标画在 VOP2 主平面上。临时使用固定速度曲线时，952 个可比 X 输入样本的增益均为 0.90，仍能测到卡顿。实验后已恢复触控板原有的自适应曲线。

顶栏窗口覆盖 `y=0..22`，反馈位置 `(1771,77)` 位于桌面。交替显示和隐藏顶栏，在上方左右 `y=82` 和下方左右 `y=1108` 以 30 Hz 更新相同色块，共 16 轮。扫描输出帧缓冲中观察到了全部 1,328 次颜色变化。顶栏显示时 3,072 个 Xorg 栈采样中有 713 个处在上述局部重绘等待里，隐藏时为 740/3,073。测试期间实体指针静止。两种设置都会等待，实际滑动时上方更明显的原因仍待复测。

又以触控板的报点周期 7.27 ms 同时更新四个带序号的小色块，每轮 868 次，共三轮。每次读取同一时刻的四个位置，2,236 次有效采样中有 2,233 次读到相同序号。按上、下区域分别在每帧开始后约 1.4 和 14.4 ms 采样，每轮上方两个色块从提交到采样的中位间隔为 20.6–20.7 ms，下方为 16.8–17.0 ms。每个位置共有 1,115 对相邻刷新，左上、右上、左下、右下分别有 5、6、5、5 次读到重复序号。这个约 4 ms 的差异按行位置估算，测量对象是帧缓冲区的色块。面板实际发光时刻以及实体指针是否具有同样的上下差异，还需独立测量。

使用者随后在右上持续滑动，期间临时隐藏顶栏约 8 秒，报告跳动依旧明显。同样手势在右下感觉流畅。按 80–160 mm/s 的手指速度筛选，右上显示顶栏、右上隐藏顶栏、右下显示顶栏时，触控板报点的中位间隔分别为 7.28、7.28、7.29 ms。实际滑动期间，这三个条件下 `XQueryPointer` 最长分别花了 493、487、671 ms。流畅和卡顿区域都能出现很慢的查询，仅凭这个指标无法解释位置差异。

另一次滑动期间，读取当前扫描输出的帧缓冲，用实际箭头的 90 个不透明像素识别它的位置。这一轮在开始时取得光标图案并关闭了 X11 测试连接。在右上 `(1752,62)` 附近，两个相邻刷新采样相隔 17.2 ms，手指仍持续向同一方向移动，箭头两次出现在同一位置。再过 15.8 ms，箭头前进了 21.5 像素。右下的部分读取消耗跨过下一次刷新，可用的连续帧不足以计算同等条件下的掉帧率。这次验证了右上的重复位置和随后跳步，其成因仍需在更改重绘路径后对照。

随后在同一台 `#8` 上临时启动了第二个 Xorg/i3 会话，仍使用 eDP `1920×1200@60`、Mali-G52 glamor、原触控板配置和顶部 i3bar。只在该 Xorg 启动时用进程级过滤让 `DRM_IOCTL_MODE_DIRTYFB` 返回 `ENOSYS`，模拟候选内核对 Xorg 的响应。使用者在新会话的右上实际滑动，采到 1,103 个触控板间隔。725 次移动期间的 X 坐标查询中，P95 为 2.89 ms，最长 4.03 ms。645 次 Xorg 栈采样中，上述同步等待计数为零。正常会话在同一位置、同样显示顶栏时，32 次查询中最长为 493 ms，268 次栈采样中有 195 次在局部重绘路径中等待。这一结果验证了该路径对 X 服务响应时间的影响。测试结束后自动回到了原 LightDM 会话。

使用者确认 TEST 会话中右上角的箭头移动平滑，切回原会话后又出现明显跳步。两边都显示顶栏，此前在原会话隐藏顶栏也能复现。上下观感差异的一种可能解释是逐行扫描与光标写入的时机。按当前显示模式估算，一帧中两个测试高度的扫描时刻相差约 13 ms。如果光标写入有时赶在上方扫描之前，有时落在上下两处扫描之间，上方就可能隔一帧才显示新的位置，而同样时刻的写入在下方仍可能赶上当次刷新。色块实验中四角的重复次数接近，现有帧缓冲采样还不能确认这个机制在面板上的作用。

本地 USB 引导候选 `artifacts/7.3/candidates/boot_linux_73_v28_usb_build9.img` 在这块板上省去 `DIRTYFB` 回调，让 Xorg 停止为局部绘制提交同步刷新。已核对 `#8` 与 `#9` 的 initramfs、设备树、RSCE 和启动参数一致，并用 `#9` 在 QEMU 从 USB 根盘启动到 Debian 登录。

11 月 21 日在运行 `#8` 的真机上，以相同的自动位移分别测试了右上和右下。Xorg 主线程的 1,027 次采样中有 610 次在局部重绘里等待垂直刷新。随后备份了完整 `boot_a` 与 `misc`，将上述 `#9` 镜像写入 A。整个 160 MiB 分区读回的 SHA-256 为 `b22912e507c3de91de569212839bfe0854686120ae752a376003c7abaa3cceb7`。基于当时的 `misc` 将 A 的剩余尝试从 1 补至 3，校验了整个 4 MiB 的读回数据。B 仍为 `14/0/1`，`boot_b` 与本机原厂备份一致。22:15 从 Linux 发起重启。使用者确认 `#9` 实机上的光标已恢复平滑。

### Wi-Fi 连接验证

6.12 真机在 5200 MHz（信道 40）找到了目标 AP，连续三次发送 802.11 认证请求后超时，NetworkManager 最后报告 `ssid-not-found`。从 U 盘回收的日志还记录了 2412 和 2437 MHz 的认证超时，以及一次 2412 MHz 关联后立即收到原因码 4 的断连。Android B 用同一块网卡连到了 Linux 测试的同一个 5 GHz 接入点。`iw dev wlan0 link` 可查看实际连接频率，`nmcli -g IP4.ADDRESS dev show wlan0` 可确认是否取得 IPv4 地址。

两个版本的本地根镜像已补齐 `polkitd` 和 `wireless-regdb`。包内通过 `update-alternatives` 选择上游签名版本，与内核内建证书匹配。QEMU 中两个内核均成功读取所选监管域，本地活动会话的 `cloudbook` 用户均能通过 NetworkManager 创建 Wi-Fi 配置。

7.3.5 起初使用 `linux-firmware` 的 2020 年版本。2.4 GHz 可完成 WPA 和 DHCP，随后曾在约 42–44 秒断开。5 GHz 同一接入点的一轮测试在关联 83 秒时已累计 108 次发送失败。

从本机 eMMC 的 Android B `vendor_b` 只读提取了 N9 和配套 ROM patch，两者分别与本地 OTA 中的文件一致。USB 根盘和本地 7.3.5 根镜像已安装这对文件。重新绑定 SDIO 驱动后，内核从默认固件路径报告构建时间 `20230629193734`。在 `5200 MHz` 连续采了 49 次状态，中间有两分钟没有测试流量，末次关联时长为 290 秒，26 次 512 KiB 下行校验均通过。随后从默认路径重新加载，另作 12 轮各 512 KiB 的上行与下行，全部校验通过。暂时关闭 5 GHz 自动候选时，设备自动回退到 `2437 MHz`，观察 124 秒，3 次传输通过校验。恢复 5 GHz 候选后，设备在重新连接时自动选中 5 GHz。

现场网络的两个配置分别设置为 5 GHz 优先级 100、2.4 GHz 优先级 20。5 GHz 配置允许按 SSID 主动探测，以便首次扫描时发现同名的 5 GHz 接入点。NetworkManager 在建立自动连接时比较这些优先级。现场网络的名称和凭据仅保存于实际使用的 U 盘。

`#9` 真机重启后，`nmcli device` 和 `/sys/class/net` 都只列出 `lo`，`nmtui` 的 SSID 列表为空。启动日志记录了以下顺序。

| 开机后 | 内核记录 |
| --- | --- |
| 7.964 秒 | `mmc1` 识别到 SDR104 卡，sysfs 中可见 Wi-Fi 和蓝牙两个 SDIO 功能。 |
| 9.474 秒 | `btmtksdio` 的固件设置阶段返回 0。 |
| 9.494 秒 | `Bluetooth: hci0: Opcode 0x100c failed: -56`。固件在支持列表中声明了读取本机简单配对选项的命令，执行时却返回 Unknown HCI Command。7.3.5 在此结束 HCI 初始化，未调用 `post_init` 通知 Wi-Fi 可以访问共享 eFuse。 |
| 29.666 秒 | `mt7663s` 等待蓝牙就绪超时，探测以 `-110` 结束。再次运行 `modprobe mt7663s` 后仍只有 `lo`。 |

`#8` 从旧版 ROM patch 启动蓝牙。更换固件后的双频试验只重新绑定了 Wi-Fi 功能，蓝牙已完成当次启动的初始化。两个驱动都会请求 `mediatek/mt7663pr2h.bin`。回读 U 盘日志后确认，`#8` 在第 9 秒报告蓝牙 HCI 就绪和 2020 版 N9，到第 5752 秒才首次加载 2023 版 N9。这轮测试未覆盖整机重启后蓝牙首先使用原厂 ROM patch 的路径。

7.3.5 构建加入 `0003-bluetooth-handle-unsupported-local-pairing-options.patch`。收到上述命令的 Unknown HCI Command 时，内核清除控制器对应的能力位并继续初始化。使用实际写入的 `#9` 内核和虚拟蓝牙控制器，原模块重现了 `-56` 并停在初始化阶段。更换 `bluetooth.ko` 后，相同响应下控制器成功注册到蓝牙管理接口，支持此命令的控制器仍上报正确的最大密钥长度。另验证了未声明支持时跳过查询，以及返回 Command Disallowed 时仍会中止。

实机 U 盘安装该模块后，`#9` 在开机第 9.609 秒记录了 `0x100c failed: -56`、该命令不受支持的提示，以及 `combo HCI initialization result=0`。第 9.752 秒 Wi-Fi 开始加载原厂 N9。NetworkManager 在第 24 秒自动选择 2.4 GHz，约第 25 秒取得 IPv4 地址。经 SSH 读回蓝牙为 `Powered: yes`，内屏继续使用 Mali-G52 glamor。`Win+N` 已部署，可打开连接列表。在伪终端上启动 `nmtui-connect` 三次，读到目标 SSID 用时为 79–118 毫秒。此测量截止于终端输出，内屏实际呈现时间尚待测量。

第一次修复后启动时 `cfg80211` 的初始区域为 `00`，扫描缓存中只有目标网络的 2.4 GHz 接入点。关联后从接入点收到 `CN`，再次定向扫描发现两个 5200 MHz 接入点。5 GHz 与 2.4 GHz 配置的优先级分别为 100 和 20。为验证开机选频，仅在这台机器的 `/etc/modprobe.d/95-cloudbook-wifi-country.conf` 中将初始 `ieee80211_regdom` 设为现场网络广播的 `CN`。

下一次 Linux 重启时，NetworkManager 在第 24.221 秒自动选择 5 GHz 配置，第 24.555 秒在 5200 MHz 发起认证，第 25.304 秒进入 IPv4 地址检查。Mac 与设备之间做了四轮各 512 KiB 的双向传输，每次逐字节内容的 SHA-256 校验均通过。两轮测试之间停发测试流量 65 秒，底层保持同一次关联，末次采样时已连接 93 秒。

### Linux 有线 ADB

真机已核对，当前根盘 `/dev/sda` 挂在 `/sys/devices/platform/fcc00000.usb/` 下。`#9` 设备树将这个 OTG 控制器设为 host，`/sys/class/udc` 为空。要在 Linux 使用有线 ADB，需要先让根盘从独立的 USB 主机控制器启动，再为 OTG 口启用设备模式、FunctionFS 和设备端 `adbd`。本次试机通过 Android B 的现有 ADB 更新 U 盘，Linux 启动后通过 SSH 验证。

## 6. 已知注意事项

1. **模块目录必须与 `uname -r` 一致**（`/usr/lib/modules/7.3.5-cloudbook`），rootfs 已匹配。
2. **根分区卷标必须是 `ROOTFS`**（U 盘）；迁 eMMC 时改成 `EMMC_ROOTFS` 并同步修改 `/etc/fstab`。
3. GStreamer 的 `v4l2sl*` 元素是**按设备动态注册**的，chroot 里看不到属正常，真机上加载 `rockchip-vdec` 后出现。
4. Hyprland/Omarchy 未采用：Panfrost 只有 GLES 3.1，Hyprland 会回退 GLES 3.0，模糊/动画需关闭；4 GB 内存也偏紧。当前通用镜像沿用原作者的 i3 桌面。
5. VP9 硬解仍不可用：VDPU346 系列只支持 H.264 + HEVC。
