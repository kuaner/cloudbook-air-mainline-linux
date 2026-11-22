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

# A previous run hands resolv.conf over to NetworkManager at the end. Restore
# a real file while packages are installed so re-runs can resolve names.
if [ ! -e "$R/etc/resolv.conf" ]; then
    rm -f "$R/etc/resolv.conf"
    cp /etc/resolv.conf "$R/etc/resolv.conf" 2>/dev/null || true
fi

# --- kernel modules -------------------------------------------------------
rm -rf "$R"/usr/lib/modules/*
rm -rf /tmp/modstage && mkdir -p /tmp/modstage
tar -xzf "$MODULES_TARBALL" -C /tmp/modstage
mkdir -p "$R/usr/lib/modules"
cp -a /tmp/modstage/lib/modules/. "$R/usr/lib/modules/"
rm -rf /tmp/modstage
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
tar -C /inputs/rootfs_overlay/etc --owner=0 --group=0 -cf - . |
    tar -C "$R/etc" --numeric-owner -xpf -

# Switch the generic Debian 13 image to the regional archive before installing packages.
case "$KVER" in
    7.3.*)
        if [ ! -s "$R/etc/ssl/certs/ca-certificates.crt" ]; then
            chroot "$R" apt-get update
            DEBIAN_FRONTEND=noninteractive chroot "$R" apt-get install -y ca-certificates
        fi
        install -D -m 0644 /inputs/rootfs_overlay/usr/local/share/cloudbook/debian.sources \
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
if [ -d /inputs/rootfs_overlay/usr/share/alsa/ucm2 ]; then
    mkdir -p "$R/usr/share/alsa/ucm2"
    tar -C /inputs/rootfs_overlay/usr/share/alsa/ucm2 --owner=0 --group=0 -cf - . |
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
chroot "$R" useradd -m -s /bin/bash -G sudo cloudbook 2>/dev/null || true
echo 'cloudbook:cloudbook' | chroot "$R" chpasswd
echo 'root:cloudbook' | chroot "$R" chpasswd

# --- desktop home configuration (i3 / i3status) ---------------------------
if [ -d /inputs/rootfs_overlay/home/cloudbook ]; then
    mkdir -p "$R/home/cloudbook"
    cp -a /inputs/rootfs_overlay/home/cloudbook/. "$R/home/cloudbook/"
    chmod 700 "$R/home/cloudbook"
fi
mkdir -p "$R/home/cloudbook/Pictures"

# polybar reads its palette from colors.ini, which i3-theme rewrites on every
# flavour change. Seed it from the i3 theme so the first login has a styled bar.
if [ -f "$R/home/cloudbook/.config/polybar/config.ini" ]; then
    theme=$R/home/cloudbook/.config/i3/themes/catppuccin-mocha
    {
        echo "[colors]"
        sed -n 's/^set [$]\([a-z0-9]*\) #\([0-9a-fA-F]\{6\}\)$/\1 = #\2/p' "$theme"
    } > "$R/home/cloudbook/.config/polybar/colors.ini"
fi

chown -R 1000:1000 "$R/home/cloudbook" 2>/dev/null || true

# Apply the 7.3 clock configuration to both desktop and headless images.
case "$KVER" in
    7.3.*)
        install -D -m 0755 /inputs/rootfs_overlay/usr/local/sbin/cloudbook-configure-time \
            "$R/usr/local/sbin/cloudbook-configure-time"
        chroot "$R" /usr/local/sbin/cloudbook-configure-time
        # Report A/B boot success the way Android's bootctl does; without it
        # the Rockchip bootloader clears the slot's priority on the next boot.
        install -D -m 0755 /inputs/rootfs_overlay/usr/local/sbin/cloudbook-mark-boot-successful \
            "$R/usr/local/sbin/cloudbook-mark-boot-successful"
        if [ -f "$R/usr/lib/systemd/system/lightdm.service" ]; then
            install -D -m 0755 /inputs/rootfs_overlay/usr/local/sbin/cloudbook-install-i3 \
                "$R/usr/local/sbin/cloudbook-install-i3"
            chroot "$R" /usr/local/sbin/cloudbook-install-i3
        fi
        ;;
esac

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
: > "$R/etc/machine-id" 2>/dev/null || true
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

# --- permission normalisation ---------------------------------------------
# Build inputs arrive from a macOS bind mount and from tarballs produced on it;
# both lose real Unix modes (directories 777, files 666). Strip group/other
# write so the image never ships world-writable content. Read and execute bits
# are untouched, so setuid binaries and private files keep their exact modes.
chmod -R go-w "$R"

# World-writable, sticky temporary directories are intentional and must keep
# mode 1777; package scripts drop privileges (e.g. _apt) and need them.
for d in tmp var/tmp dev/shm; do
    [ -d "$R/$d" ] && chmod 1777 "$R/$d"
done

echo "rootfs post-processing done"
