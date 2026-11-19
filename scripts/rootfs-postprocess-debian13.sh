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

# --- kernel modules -------------------------------------------------------
rm -rf "$R"/usr/lib/modules/*
rm -rf /tmp/modstage && mkdir -p /tmp/modstage
tar -xzf "$MODULES_TARBALL" -C /tmp/modstage
mkdir -p "$R/usr/lib/modules"
cp -a /tmp/modstage/lib/modules/. "$R/usr/lib/modules/"
rm -rf /tmp/modstage
depmod -b "$R" "$KVER"

# --- MT7663S Wi-Fi/BT firmware -------------------------------------------
mkdir -p "$R/usr/lib/firmware/mediatek"
cp /inputs/firmware/mt7663_n9_v3.bin /inputs/firmware/mt7663pr2h.bin \
   "$R/usr/lib/firmware/mediatek/"

# --- board configuration overlay -----------------------------------------
cp -a /inputs/rootfs_overlay/etc/. "$R/etc/"

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
cat > "$R/etc/default/locale" <<'EOF'
LANG=C.UTF-8
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
chown -R 1000:1000 "$R/home/cloudbook" 2>/dev/null || true

# --- services (offline enable) -------------------------------------------
for unit in NetworkManager.service bluetooth.service ssh.service; do
    link="$R/etc/systemd/system/multi-user.target.wants/$unit"
    [ -e "$link" ] && continue
    mkdir -p "$R/etc/systemd/system/multi-user.target.wants"
    ln -sf "/usr/lib/systemd/system/$unit" "$link"
done

# Graphical login when a desktop is installed.
if [ -f "$R/usr/lib/systemd/system/lightdm.service" ]; then
    ln -sf /usr/lib/systemd/system/lightdm.service \
           "$R/etc/systemd/system/display-manager.service"
    ln -sf /usr/lib/systemd/system/graphical.target \
           "$R/etc/systemd/system/default.target"
fi

# --- first boot housekeeping ---------------------------------------------
: > "$R/etc/machine-id" 2>/dev/null || true
rm -rf "$R"/var/lib/apt/lists/*
chroot "$R" apt-get clean 2>/dev/null || true

echo "rootfs post-processing done"