#!/bin/sh
# Post-process the debootstrapped Debian 13 (trixie) arm64 rootfs for the
# CloudBook Air (CT2000-24RTW / RK3568B2).
#
# The rootfs is usr-merged: /bin, /sbin and /lib are symlinks into /usr.
# Extracting an archive that carries a "lib/" directory entry directly over
# /lib replaces that symlink with a real directory, which removes the dynamic
# loader path (/lib/ld-linux-aarch64.so.1) and breaks every chroot command.
# Modules are therefore staged in /tmp and copied into /usr/lib/modules.
set -e

R=${ROOTFS_ROOT:-/rootfs}
KVER=${KVER:-6.12.117-cloudbook}
MODULES_TARBALL=${MODULES_TARBALL:-/inputs/modules-6.12.117-cloudbook.tar.gz}
ROOT_LABEL=${ROOT_LABEL:-EMMC_ROOTFS}
OVERLAY=/inputs/rootfs_overlay
LOGIN_USER=kuaner
LOGIN_HOME=/home/$LOGIN_USER

# Package scripts drop privileges. A reused rootfs may have lost these modes.
install -d -m 1777 "$R/tmp" "$R/var/tmp"

# A previous run hands resolv.conf over to NetworkManager at the end. Restore
# a real file while packages are installed so re-runs can resolve names.
if [ -L "$R/etc/resolv.conf" ] || [ ! -s "$R/etc/resolv.conf" ]; then
    rm -f "$R/etc/resolv.conf"
    cp /etc/resolv.conf "$R/etc/resolv.conf"
fi

# --- kernel modules -------------------------------------------------------
modstage=$(mktemp -d)
trap 'rm -rf "$modstage"' EXIT
tar -xzf "$MODULES_TARBALL" -C "$modstage"
[ -d "$modstage/lib/modules/$KVER" ]
mkdir -p "$R/usr/lib/modules"
rm -rf "$R/usr/lib/modules/$KVER"
cp -a "$modstage/lib/modules/$KVER" "$R/usr/lib/modules/"
chmod -R go-w "$R/usr/lib/modules/$KVER"
depmod -b "$R" "$KVER"

# --- MT7663S Wi-Fi/BT firmware -------------------------------------------
fw_input=/inputs/firmware
vendor_fw=${MT7663_VENDOR_FIRMWARE_DIR:-}
if [ -z "$vendor_fw" ] && [ -d "$fw_input/$KVER" ]; then
    vendor_fw=$fw_input/$KVER
fi
if [ -n "$vendor_fw" ]; then
    n9=$vendor_fw/WIFI_RAM_CODE_MT7663.bin
    patch=$vendor_fw/mt7663_patch_e2_hdr.bin
else
    n9=$fw_input/mt7663_n9_v3.bin
    patch=$fw_input/mt7663pr2h.bin
fi
for blob in "$n9" "$patch"; do
    if [ ! -s "$blob" ]; then
        echo "Missing MT7663 firmware file $blob" >&2
        exit 1
    fi
done
fw_dest=$R/usr/lib/firmware/mediatek
mkdir -p "$fw_dest"
install -m 0644 "$n9" "$fw_dest/mt7663_n9_v3.bin"
install -m 0644 "$patch" "$fw_dest/mt7663pr2h.bin"

# The overlay can be bind-mounted from a Mac with UID 501. Give system
# configuration its target ownership, including the /etc directory itself.
tar -C "$OVERLAY/etc" --owner=0 --group=0 --mode=go-w -cf - . |
    tar -C "$R/etc" --numeric-owner -xpf -

# Switch the generic Debian 13 image to the regional archive before installing packages.
case "$KVER" in
    7.3.*)
        if [ ! -s "$R/etc/ssl/certs/ca-certificates.crt" ]; then
            chroot "$R" apt-get update
            DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y ca-certificates
        fi
        install -D -m 0644 "$OVERLAY/usr/local/share/cloudbook/debian.sources" \
            "$R/etc/apt/sources.list.d/debian.sources"
        # debootstrap writes its initial mirror into the legacy file.
        : > "$R/etc/apt/sources.list"
        ;;
esac

# NetworkManager uses polkit for connections created from a local user session.
# Both CloudBook kernels trust the upstream regulatory database signing keys.
if [ ! -x "$R/usr/lib/polkit-1/polkitd" ] || [ ! -s "$R/usr/lib/firmware/regulatory.db-upstream" ]; then
    chroot "$R" apt-get update
    DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y polkitd wireless-regdb
fi
chroot "$R" update-alternatives --set regulatory.db /lib/firmware/regulatory.db-upstream

# --- ALSA UCM2 profiles --------------------------------------------------
if ! chroot "$R" dpkg-query -W -f='${Status}' alsa-ucm-conf 2>/dev/null |
        grep -q 'install ok installed'; then
    chroot "$R" apt-get update
    DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y alsa-ucm-conf
fi
if [ -d "$OVERLAY/usr/share/alsa/ucm2" ]; then
    mkdir -p "$R/usr/share/alsa/ucm2"
    tar -C "$OVERLAY/usr/share/alsa/ucm2" --owner=0 --group=0 --mode=go-w -cf - . |
        tar -C "$R/usr/share/alsa/ucm2" --numeric-owner -xpf -
fi

# --- root filesystem ------------------------------------------------------
cat > "$R/etc/fstab" <<EOF
LABEL=$ROOT_LABEL / ext4 defaults,noatime,commit=120,errors=remount-ro 0 1
tmpfs /tmp tmpfs defaults,nosuid 0 0
EOF

# --- identity / locale ----------------------------------------------------
echo cloudbook > "$R/etc/hostname"
cat > "$R/etc/hosts" <<'EOF'
127.0.0.1	localhost
127.0.1.1	cloudbook
::1		localhost ip6-localhost ip6-loopback
EOF
# Generate zh_CN.UTF-8 for every image, then make it the system default so
# console, services and the desktop session agree. Without a generated locale
# the setting silently falls back to C.
sed -i 's/^# *zh_CN.UTF-8 UTF-8$/zh_CN.UTF-8 UTF-8/' "$R/etc/locale.gen"
chroot "$R" locale-gen
cat > "$R/etc/default/locale" <<'EOF'
LANG=zh_CN.UTF-8
LANGUAGE=zh_CN:zh
EOF

# --- accounts -------------------------------------------------------------
if ! chroot "$R" id "$LOGIN_USER" >/dev/null 2>&1; then
    if chroot "$R" id cloudbook >/dev/null 2>&1; then
        # Rebuilding the previous image keeps its numeric UID and GID.
        chroot "$R" usermod -l "$LOGIN_USER" -d "$LOGIN_HOME" -m cloudbook
        if [ "$(chroot "$R" id -gn "$LOGIN_USER")" = cloudbook ]; then
            chroot "$R" groupmod -n "$LOGIN_USER" cloudbook
        fi
    else
        chroot "$R" useradd -m -d "$LOGIN_HOME" -s /bin/bash "$LOGIN_USER"
    fi
fi
chroot "$R" usermod -aG sudo,video "$LOGIN_USER"
printf '%s:%s\n' "$LOGIN_USER" "${CLOUDBOOK_LOGIN_PASSWORD:-$LOGIN_USER}" | chroot "$R" chpasswd
printf '%s\n' 'root:cloudbook' | chroot "$R" chpasswd

# Report A/B boot success so U-Boot keeps the slot bootable.
install -D -m 0755 "$OVERLAY/usr/local/sbin/cloudbook-mark-boot-successful" \
    "$R/usr/local/sbin/cloudbook-mark-boot-successful"

# Apply the 7.3 clock configuration to both desktop and headless images.
case "$KVER" in
    7.3.*)
        install -D -m 0755 "$OVERLAY/usr/local/sbin/cloudbook-configure-time" \
            "$R/usr/local/sbin/cloudbook-configure-time"
        chroot "$R" /usr/local/sbin/cloudbook-configure-time
        ;;
esac

if [ -f "$R/usr/lib/systemd/system/lightdm.service" ]; then
    install -D -m 0755 "$OVERLAY/usr/local/bin/cloudbook-switch-system" \
        "$R/usr/local/bin/cloudbook-switch-system"
    switch_policy=usr/share/polkit-1/actions/io.github.kuaner.cloudbook.switch-system.policy
    install -D -m 0644 "$OVERLAY/$switch_policy" "$R/$switch_policy"
    # The power menu is the desktop entry point. Clean up launchers from reused bases.
    rm -f "$R/usr/share/applications/cloudbook-switch-system.desktop" \
        "$R/usr/share/icons/hicolor/128x128/apps/cloudbook-switch-system.png"
    greeter_theme=/usr/share/themes/CloudBook-Greeter/gtk-3.0
    for asset in "$OVERLAY$greeter_theme/"*; do
        install -D -m 0644 "$asset" "$R$greeter_theme/${asset##*/}"
    done
    install -D -m 0644 "$OVERLAY$LOGIN_HOME/Pictures/tokyo.png" \
        "$R/usr/share/backgrounds/cloudbook/tokyo.png"
    # Drop the superseded defaults when rebuilding the previous desktop image.
    rm -rf "$R$LOGIN_HOME/.config/polybar" "$R$LOGIN_HOME/.config/i3/themes" \
        "$R$LOGIN_HOME/.config/i3status"
    rm -f "$R$LOGIN_HOME/.local/bin/i3-theme" "$R$LOGIN_HOME/.Xresources" \
        "$R$LOGIN_HOME/.config/picom.conf" "$R$LOGIN_HOME/.config/rofi/archcraft.rasi" \
        "$R$LOGIN_HOME/.config/rofi/catppuccin-default.rasi" \
        "$R$LOGIN_HOME/.config/rofi/catppuccin-mocha.rasi" "$R$LOGIN_HOME/.config/rofi/LICENSE"
    cp -a "$OVERLAY$LOGIN_HOME/." "$R$LOGIN_HOME/"
    mkdir -p "$R$LOGIN_HOME/.local/share"
    tar -C "$R$LOGIN_HOME/.local/share" -xf \
        "$OVERLAY/usr/local/share/cloudbook/archcraft-desktop-assets.tar.zst"
    chown -R "$(chroot "$R" id -u "$LOGIN_USER"):$(chroot "$R" id -g "$LOGIN_USER")" \
        "$R$LOGIN_HOME"
    chmod -R go-w "$R$LOGIN_HOME"
    chmod 700 "$R$LOGIN_HOME"
    install -D -m 0755 "$OVERLAY/usr/local/sbin/cloudbook-install-i3" \
        "$R/usr/local/sbin/cloudbook-install-i3"
    chroot "$R" /usr/local/sbin/cloudbook-install-i3 "$LOGIN_USER"
fi

# --- full recommended package set ----------------------------------------
# The root filesystem is not size-constrained (8 GiB USB image, larger eMMC
# partition), so the image carries the Recommends of everything installed
# instead of a minimised set. Some packages keep required pieces there --
# initramfs-tools only Recommends busybox, and without it the initrd has no
# /bin/sh. --fix-policy satisfies those for the whole installed set in one
# transaction, including whatever the debootstrap base left out.
case "$KVER" in
    7.3.*)
        chroot "$R" apt-get update
        DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y --fix-policy
        ;;
esac

# Reused bases may already contain xterm. Remove it after resolving Recommends.
if [ -f "$R/usr/lib/systemd/system/lightdm.service" ]; then
    DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get purge -y xterm
    case "$KVER" in
        7.3.*)
            DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y plymouth plymouth-label
            theme=/usr/share/plymouth/themes/cloudbook-air
            for asset in "$OVERLAY$theme/"*; do
                install -D -m 0644 "$asset" "$R$theme/${asset##*/}"
            done
            chroot "$R" plymouth-set-default-theme cloudbook-air
            ;;
    esac
fi

# --- initramfs ------------------------------------------------------------
# Debian initramfs-tools replaces the hand-written /init used by the 6.12
# images. Its local script parses root=LABEL=EMMC_ROOTFS (or ROOTFS) from the
# kernel cmdline, and it runs e2fsck, waits through udev and drops to a
# recovery shell instead of panicking.
#
# Every driver needed before root is mounted (mmc, sdhci, sdhci-of-dwcmshc,
# ext4, GPT parsing) is built into the kernel, so MODULES=list with an empty
# module list keeps the initrd small. Add a module to
# /etc/initramfs-tools/modules and switch the driver from =y to =m only if a
# future root stack requires it before mount.
case "$KVER" in
    7.3.*)
        # initramfs-tools only recommends busybox; without it the initrd has
        # no /bin/sh and /init cannot run, so require it explicitly.
        need_initramfs=
        for pkg in initramfs-tools busybox zstd; do
            chroot "$R" dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null |
                grep -q 'install ok installed' || need_initramfs=1
        done
        if [ -n "$need_initramfs" ]; then
            chroot "$R" apt-get update
            DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y \
                initramfs-tools zstd busybox
        fi
        mkdir -p "$R/etc/initramfs-tools" "$R/boot"
        cat > "$R/etc/initramfs-tools/initramfs.conf" <<'EOF'
MODULES=list
COMPRESS=zstd
BUSYBOX=auto
FSTYPE=ext4
EOF
        : > "$R/etc/initramfs-tools/modules"
        chroot "$R" mkinitramfs -o "/boot/initrd.img-$KVER" "$KVER"
        initrd="$R/boot/initrd.img-$KVER"
        [ -s "$initrd" ] || {
            echo "initramfs was not generated at $initrd" >&2
            exit 1
        }
        # The boot image embeds the raw archive; a uInitrd wrapper would carry
        # a 64-byte U-Boot header that the kernel cannot read.
        magic=$(od -An -tx1 -N6 "$initrd" | tr -d ' \n')
        case "$magic" in
        1f8b*|28b52ffd*|fd377a585a00*|02214c18*) ;;
        *)
            echo "initrd is not a raw compressed archive (magic $magic)" >&2
            exit 1
            ;;
        esac
        size=$(stat -c %s "$initrd")
        echo "initramfs: $initrd ($size bytes)"
        if [ "$size" -gt $((80 * 1024 * 1024)) ]; then
            echo "initramfs exceeds the 80 MiB budget left by kernel+resource in the 160 MiB boot partition" >&2
            exit 1
        fi
        ;;
esac

# --- services (offline enable) -------------------------------------------
for unit in NetworkManager.service bluetooth.service ssh.service; do
    link="$R/etc/systemd/system/multi-user.target.wants/$unit"
    [ -e "$link" ] && continue
    mkdir -p "$R/etc/systemd/system/multi-user.target.wants"
    ln -sf "/usr/lib/systemd/system/$unit" "$link"
done

# Our own A/B success reporter lives in /etc, not /usr/lib.
ln -sf /etc/systemd/system/cloudbook-mark-boot-successful.service \
       "$R/etc/systemd/system/multi-user.target.wants/cloudbook-mark-boot-successful.service"

# Graphical login when a desktop is installed.
if [ -f "$R/usr/lib/systemd/system/lightdm.service" ]; then
    ln -sf /usr/lib/systemd/system/lightdm.service \
           "$R/etc/systemd/system/display-manager.service"
    ln -sf /usr/lib/systemd/system/graphical.target \
           "$R/etc/systemd/system/default.target"
fi

# ZRAM swap (4 GB machine) when zram-tools is installed.
if [ -f "$R/usr/lib/systemd/system/zramswap.service" ]; then
    mkdir -p "$R/etc/systemd/system/multi-user.target.wants"
    ln -sf /usr/lib/systemd/system/zramswap.service \
           "$R/etc/systemd/system/multi-user.target.wants/zramswap.service"
fi

# --- first boot housekeeping ---------------------------------------------
printf '%s\n' uninitialized > "$R/etc/machine-id"
if [ -d "$R/var/lib/dbus" ]; then
    ln -sfn /etc/machine-id "$R/var/lib/dbus/machine-id"
fi
if [ -f "$R/usr/lib/systemd/system/sshd-keygen.service" ]; then
    rm -f "$R"/etc/ssh/ssh_host_*_key "$R"/etc/ssh/ssh_host_*_key.pub
fi
rm -rf "$R"/var/lib/apt/lists/*
chroot "$R" apt-get clean 2>/dev/null || true
rm -f "$R/var/cache/apt/pkgcache.bin" "$R/var/cache/apt/srcpkgcache.bin"

# NetworkManager owns resolv.conf at runtime. Leave a real file during the
# build so chroot apt-get can resolve names, then hand it over at the end.
case "$KVER" in
    7.3.*)
        ln -sfn /run/NetworkManager/resolv.conf "$R/etc/resolv.conf"
        ;;
esac

echo "rootfs post-processing done"
