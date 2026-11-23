#!/usr/bin/env bash
# Build the matching USB, eMMC and boot_a images with the verified v30 kernel.
set -euo pipefail

repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
base=$(realpath "${1:-$repo/artifacts/7.3/rootfs-7.3.5-cloudbook.tar.zst}")
output=${2:-$repo/artifacts/7.3/desktop-kuaner-$(date +%Y%m%d-%H%M%S)}
kernel=$(realpath "${CLOUDBOOK_V30_INPUTS:-$repo/artifacts/7.3/candidates/build10-v30}")
mkdir -p "$output"
output=$(cd -- "$output" && pwd)

for file in "$base" "$kernel/Image-7.3.5-cloudbook-build10" \
    "$kernel/modules-7.3.5-cloudbook-build10.tar.gz" "$kernel/config-7.3.5-cloudbook" \
    "$kernel/rk3568-ecloudbook-air-24rtw-v30-emmc.dtb" "$repo/artifacts/extracted_resource.img"; do
    if [[ ! -s "$file" ]]; then
        echo "Missing input: $file" >&2
        exit 1
    fi
done

# Privilege and mounts are confined to the throwaway Linux build container.
docker run --rm -i --privileged --platform linux/arm64 \
    --env CLOUDBOOK_LOGIN_PASSWORD \
    --mount "type=bind,src=$repo,dst=/work,readonly" \
    --mount "type=bind,src=$base,dst=/base.tar.zst,readonly" \
    --mount "type=bind,src=$kernel,dst=/kernel,readonly" \
    --mount "type=bind,src=$output,dst=/out" \
    debian:trixie bash -s <<'BUILD'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
kver=7.3.5-cloudbook
root=/rootfs
prefix=rootfs-$kver
boot=boot_linux_73_v30_auto.img
files=("$prefix-emmc.tar.zst" "$prefix-usb.ext4" "$prefix-usb.ext4.zst" "$boot" SHA256SUMS)
for file in "${files[@]}"; do
    if [[ -e /out/$file ]]; then
        echo "Choose a new output directory. /out/$file already exists." >&2
        exit 1
    fi
done
stage=$(mktemp -d /out/.pack.XXXXXXXX)
trap 'rm -rf -- "$stage"' EXIT

apt-get update
apt-get install -y --no-install-recommends ca-certificates e2fsprogs kmod python3 zstd
mkdir -p "$root" /inputs
ln -s /work/rootfs_overlay /inputs/rootfs_overlay
ln -s /work/artifacts/firmware /inputs/firmware
zstd -dc /base.tar.zst | tar --numeric-owner --xattrs --acls -xpf - -C "$root"
if [[ ! -f "$root/usr/lib/systemd/system/lightdm.service" ]]; then
    echo 'The base archive must contain the Debian 13 desktop with LightDM.' >&2
    exit 1
fi
install -D -m 0644 "/kernel/config-$kver" "$root/boot/config-$kver"

# Keep package installation offline with respect to service startup.
printf '#!/bin/sh\nexit 101\n' > /tmp/no-service-start
policy="$root/usr/sbin/policy-rc.d"
if [[ -e "$policy" ]] && ! cmp -s /tmp/no-service-start "$policy"; then
    mv "$policy" /tmp/base-policy-rc.d
fi
install -m 0755 /tmp/no-service-start "$policy"
mount -t proc proc "$root/proc"
mount --rbind /sys "$root/sys"
mount --make-rslave "$root/sys"
mount --rbind /dev "$root/dev"
mount --make-rslave "$root/dev"

ROOTFS_ROOT="$root" KVER="$kver" ROOT_LABEL=EMMC_ROOTFS \
    MODULES_TARBALL="/kernel/modules-$kver-build10.tar.gz" \
    sh /work/scripts/rootfs-postprocess-debian13.sh

rm "$policy"
if [[ -e /tmp/base-policy-rc.d ]]; then mv /tmp/base-policy-rc.d "$policy"; fi
chroot "$root" runuser -u kuaner -- i3 -C -c /home/kuaner/.config/i3/config
test -x "$root/usr/bin/xfce4-terminal.wrapper"
test ! -e "$root/usr/bin/xterm"
chroot "$root" lsinitramfs "/boot/initrd.img-$kver" > /tmp/initrd-files
grep -Fx 'scripts/local-top/00-usb-first' /tmp/initrd-files
for file in /etc/plymouth/plymouthd.conf /usr/sbin/plymouthd /scripts/init-premount/plymouth \
    /usr/lib/aarch64-linux-gnu/plymouth/{script.so,label-pango.so,renderers/{drm,frame-buffer}.so} \
    /work/rootfs_overlay/usr/share/plymouth/themes/cloudbook-air/*; do
    path=${file#/work/rootfs_overlay}
    grep -Fx "${path#/}" /tmp/initrd-files
done
test "$(chroot "$root" plymouth-set-default-theme)" = cloudbook-air
umount -R "$root/dev"
umount -R "$root/sys"
umount "$root/proc"

# The tarball installs onto eMMC. The standalone ext4 image uses the USB label.
tar --numeric-owner --xattrs --acls --sparse --one-file-system -C "$root" -cf - . |
    zstd -q -T0 -o "$stage/$prefix-emmc.tar.zst"
sed -i 's/^LABEL=EMMC_ROOTFS /LABEL=ROOTFS /' "$root/etc/fstab"
truncate -s 8G "$stage/$prefix-usb.ext4"
mke2fs -q -F -t ext4 -L ROOTFS -m 0 -d "$root" "$stage/$prefix-usb.ext4"
e2fsck -fn "$stage/$prefix-usb.ext4"
debugfs -R 'cat /etc/fstab' "$stage/$prefix-usb.ext4" | grep '^LABEL=ROOTFS '
zstd -q -T0 "$stage/$prefix-usb.ext4" -o "$stage/$prefix-usb.ext4.zst"

python3 /work/pack_boot_linux.py \
    --kernel "/kernel/Image-$kver-build10" --ramdisk "$root/boot/initrd.img-$kver" \
    --dtb /kernel/rk3568-ecloudbook-air-24rtw-v30-emmc.dtb \
    --resource-template /work/artifacts/extracted_resource.img \
    --cmdline 'console=tty0 console=ttyS2,115200n8 root=LABEL=EMMC_ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 systemd.default_timeout_start_sec=15s no_console_suspend quiet splash logo.nologo plymouth.ignore-serial-consoles' \
    --output "$stage/$boot"
(cd "$stage" && sha256sum "${files[@]:0:4}" > SHA256SUMS)
for file in "${files[@]}"; do mv "$stage/$file" /out/; done
BUILD
printf 'Images written to %s\n' "$output"
