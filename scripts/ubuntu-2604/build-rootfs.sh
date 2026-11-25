#!/usr/bin/env bash
# Runs in the disposable ARM64 Ubuntu build container.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

root=/rootfs
kver=7.3.5-cloudbook
ubuntu_sources=/work/scripts/ubuntu-2604/ubuntu.sources
ubuntu_mirror=$(awk '/^URIs: / { print $2; exit }' "$ubuntu_sources")
firmware=/work/artifacts/firmware/$kver
resource=/work/artifacts/extracted_resource.img
board_dtb=/kernel/rk3568-ecloudbook-air-24rtw-v30-emmc.dtb
image_prefix=rootfs-ubuntu-26.04-niri-noctalia
boot_prefix=boot_linux_73_v30_ubuntu_2604
for input in "/kernel/Image-$kver" "/kernel/config-$kver" "/kernel/modules-$kver.tar.gz" \
    "$board_dtb" "$resource" "$firmware/WIFI_RAM_CODE_MT7663.bin" "$firmware/mt7663_patch_e2_hdr.bin"; do
    if [[ ! -s $input ]]; then
        echo "Missing input: $input" >&2
        exit 1
    fi
done
for target in usb emmc; do
    if [[ -e /out/$target ]]; then
        echo "Output already exists: /out/$target" >&2
        exit 1
    fi
done
stage=$(mktemp -d /out/.ubuntu.XXXXXXXX)
trap 'rm -rf -- "$stage"' EXIT

apt_options=(-o Acquire::Retries=3 -o Acquire::Languages=none -o APT::Update::Error-Mode=any)
apt-get "${apt_options[@]}" update
apt-get "${apt_options[@]}" install -y --no-install-recommends \
    ca-certificates curl debootstrap device-tree-compiler e2fsprogs gpg python3 zstd
debootstrap --arch=arm64 --variant=minbase --include=ca-certificates resolute "$root" "$ubuntu_mirror"
test "$(chroot "$root" dpkg --print-architecture)" = arm64
grep -Fx 'VERSION_CODENAME=resolute' "$root/usr/lib/os-release"
: > "$root/etc/apt/sources.list"
install -m 0644 "$ubuntu_sources" "$root/etc/apt/sources.list.d/ubuntu.sources"
cp -L /etc/resolv.conf "$root/etc/resolv.conf"
printf '#!/bin/sh\nexit 101\n' > "$root/usr/sbin/policy-rc.d"
chmod 0755 "$root/usr/sbin/policy-rc.d"
mount -t proc proc "$root/proc"
mount --rbind /sys "$root/sys"
mount --make-rslave "$root/sys"
mount --rbind /dev "$root/dev"
mount --make-rslave "$root/dev"

bash /work/scripts/ubuntu-2604/apt-sources.sh "$root"
chroot "$root" apt-get "${apt_options[@]}" update

chroot "$root" apt-get "${apt_options[@]}" install -y --no-install-recommends \
    ubuntu-minimal systemd-sysv systemd-timesyncd dbus dbus-user-session sudo locales tzdata \
    initramfs-tools busybox zstd kmod e2fsprogs plymouth plymouth-label \
    lightdm lightdm-gtk-greeter xserver-xorg-core xserver-xorg-input-libinput xwayland \
    niri libwayland-server0 xwayland-satellite foot thunar gvfs gvfs-backends vim htop zsh \
    firefox firefox-l10n-zh-cn xdg-utils xdg-user-dirs \
    network-manager wpasupplicant wireless-regdb bluez openssh-server rfkill ethtool \
    pipewire pipewire-pulse pipewire-alsa wireplumber pulseaudio-utils alsa-utils alsa-ucm-conf upower \
    zenity pkexec polkitd lxpolkit brightnessctl zram-tools \
    fcitx5 fcitx5-chinese-addons fcitx5-frontend-gtk3 fcitx5-frontend-gtk4 fcitx5-frontend-qt5 fcitx5-config-qt \
    gsettings-desktop-schemas libglib2.0-bin fonts-noto-cjk fonts-jetbrains-mono adwaita-icon-theme \
    xdg-desktop-portal xdg-desktop-portal-gtk xdg-desktop-portal-gnome libmtp-runtime \
    libinput-tools mesa-utils libgl1-mesa-dri mesa-vulkan-drivers \
    gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
    gstreamer1.0-libav parole v4l-utils
bash /work/scripts/ubuntu-2604/configure-rootfs.sh "$root"

if [[ -d /noctalia-packages ]]; then
    noctalia_packages=/noctalia-packages
else
    noctalia_packages=$(mktemp -d)
    bash /work/scripts/build-noctalia-cpu-backport.sh "$noctalia_packages"
fi
install -d -m 0755 "$root/tmp/cloudbook-noctalia"
noctalia_debs=()
for package in noctalia noctalia-data fonts-noctalia-tabler; do
    if [[ $package = noctalia ]]; then arch=arm64; else arch=all; fi
    archives=("$noctalia_packages/${package}"_*ubuntu*+cloudbook1_"$arch".deb)
    if [[ ${#archives[@]} != 1 || ! -s ${archives[0]} ]]; then
        echo "Missing or ambiguous Ubuntu Noctalia package: $package" >&2
        exit 1
    fi
    filename=${archives[0]##*/}
    install -m 0644 "${archives[0]}" "$root/tmp/cloudbook-noctalia/$filename"
    noctalia_debs+=("/tmp/cloudbook-noctalia/$filename")
done

chroot "$root" apt-get "${apt_options[@]}" install -y --no-install-recommends "${noctalia_debs[@]}"
rm -r "$root/tmp/cloudbook-noctalia"

modstage=$(mktemp -d)
tar -xzf "/kernel/modules-$kver.tar.gz" -C "$modstage"
install -d "$root/usr/lib/modules" "$root/boot" "$root/usr/lib/firmware/mediatek"
cp -a "$modstage/lib/modules/$kver" "$root/usr/lib/modules/"
install -m 0644 "/kernel/config-$kver" "$root/boot/config-$kver"
install -m 0644 "/kernel/Image-$kver" "$root/boot/vmlinuz-$kver"
install -m 0644 "$firmware/WIFI_RAM_CODE_MT7663.bin" "$root/usr/lib/firmware/mediatek/mt7663_n9_v3.bin"
install -m 0644 "$firmware/mt7663_patch_e2_hdr.bin" "$root/usr/lib/firmware/mediatek/mt7663pr2h.bin"
chroot "$root" depmod "$kver"

chroot "$root" runuser -u kuaner -- niri validate --config /etc/niri/config.kdl
chroot "$root" runuser -l kuaner -c \
    'zsh -ic "[[ \$ZSH_THEME == ys && \$+functions[omz] == 1 && \$PROMPT == *ys_hg_prompt_info* ]]"'
chroot "$root" lightdm --show-config
chroot "$root" noctalia --version
chroot "$root" vim --version | sed -n '1p'
chroot "$root" gst-inspect-1.0 --version
chroot "$root" apt-get check
# Niri loads this library at startup, after the configuration-only check.
test -s "$root/usr/lib/aarch64-linux-gnu/libwayland-server.so.0"
test -s "$root/usr/lib/aarch64-linux-gnu/dri/panfrost_dri.so"
test -s "$root/usr/lib/aarch64-linux-gnu/gstreamer-1.0/libgstv4l2codecs.so"
test -s "$root/usr/lib/aarch64-linux-gnu/gstreamer-1.0/libgstwaylandsink.so"
test -x "$root/usr/lib/udev/mtp-probe"
test -s "$root/usr/share/xdg-desktop-portal/portals/gnome.portal"
chroot "$root" mkinitramfs -o "/boot/initrd.img-$kver" "$kver"
chroot "$root" lsinitramfs "/boot/initrd.img-$kver" > /tmp/initrd-files
grep -Fx 'scripts/local-top/00-usb-first' /tmp/initrd-files
grep -F 'plymouth/themes/cloudbook-air/cloudbook-air.script' /tmp/initrd-files
grep -E 'plymouth/script\.so$' /tmp/initrd-files

chroot "$root" apt-get clean
rm -rf "$root"/var/lib/apt/lists/*
chroot "$root" systemctl --global disable foot-server.service foot-server.socket
rm "$root/usr/sbin/policy-rc.d" "$root/etc/resolv.conf"
ln -s /run/NetworkManager/resolv.conf "$root/etc/resolv.conf"
find "$root/etc/ssh" -maxdepth 1 -name 'ssh_host_*' -type f -delete
truncate -s 0 "$root/etc/machine-id"
ln -sfn /etc/machine-id "$root/var/lib/dbus/machine-id"
umount -R "$root/dev"
umount -R "$root/sys"
umount "$root/proc"

console_args='fbcon=vc:3-6 systemd.show_status=no'
dtb="$stage/cloudbook.dtb"
cp "$board_dtb" "$dtb"
dtb_bootargs=$(fdtget -t s "$dtb" /chosen bootargs)
dtb_bootargs=$(sed -E 's/(^|[[:space:]])(fbcon|systemd\.show_status)=[^[:space:]]+//g' <<< "$dtb_bootargs")
fdtput -t s "$dtb" /chosen bootargs "$dtb_bootargs $console_args"
for target in usb emmc; do
    label=ROOTFS
    usb_option=
    if [[ $target = emmc ]]; then
        label=EMMC_ROOTFS
        usb_option=' cloudbook.usb=present'
    fi
    printf 'LABEL=%s / ext4 defaults,noatime,commit=120,errors=remount-ro 0 1\n' "$label" > "$root/etc/fstab"
    mkdir "$stage/$target"
    image="$stage/$target/$image_prefix-$target.ext4"
    truncate -s 8G "$image"
    mke2fs -q -F -t ext4 -L "$label" -m 0 -d "$root" "$image"
    e2fsck -fn "$image"
    debugfs -R 'cat /etc/fstab' "$image" | grep "^LABEL=$label "
    zstd -q -T0 "$image" -o "$image.zst"
    zstd -t "$image.zst"
    python3 /work/pack_boot_linux.py \
        --kernel "/kernel/Image-$kver" --ramdisk "$root/boot/initrd.img-$kver" \
        --dtb "$dtb" --resource-template "$resource" \
        --cmdline "console=tty0 console=ttyS2,115200n8 root=LABEL=EMMC_ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 systemd.default_timeout_start_sec=15s no_console_suspend quiet splash logo.nologo plymouth.ignore-serial-consoles $console_args$usb_option" \
        --output "$stage/$target/${boot_prefix}_${target}.img"
    (cd "$stage/$target" && sha256sum ./*.img ./*.ext4 ./*.ext4.zst > SHA256SUMS)
done
for target in usb emmc; do mv "$stage/$target" /out/; done
printf 'Ubuntu 26.04 CloudBook images written to /out/usb and /out/emmc\n'
