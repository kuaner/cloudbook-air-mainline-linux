#!/usr/bin/env bash
# Pack the Linux artifacts for one kernel version after build_kernel_*.sh.
#
# Usage: scripts/pack_linux_artifacts.sh 6.12|7.3 [--kernel-only]
#
# Produces, from kernel_build/out-*:
#   artifacts/                (6.12)  or artifacts/7.3/  (7.3)
#     Image-<kver>
#     System.map-<kver>
#     config-<kver>
#     modules-<kver>.tar.gz
#     rk3568-ecloudbook-air-24rtw-v30-emmc.dtb (7.3 only)
#     boot_linux_612_*.img (6.12 only, omitted with --kernel-only)
#     SHA256SUMS
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ $# -lt 1 || $# -gt 2 || ( $# = 2 && $2 != --kernel-only ) ]]; then
    echo "Usage: $0 6.12|7.3 [--kernel-only]" >&2
    exit 2
fi
VER=$1
kernel_only=${2:-}
DTS_NAME=rk3568-ecloudbook-air-24rtw

case "$VER" in
  6.12)
    SRC_DIR="$ROOT_DIR/linux-6.12"
    OUT_DIR="$ROOT_DIR/kernel_build/out-6.12"
    KVER=6.12.117-cloudbook
    ADIR="$ROOT_DIR/artifacts"
    BOOT_PREFIX=boot_linux_612
    EMMC_VARIANT=v29_emmc
    USB_VARIANT=v28_usb
    USB_DTS=$DTS_NAME-v28-usb2-pm-fix
    # Legacy hand-written initramfs, still shipped as a prebuilt artifact.
    RAMDISK="$ADIR/initramfs-$KVER-emmc.img"
    ;;
  7.3)
    SRC_DIR="$ROOT_DIR/linux-7.3.5"
    OUT_DIR="$ROOT_DIR/kernel_build/out-7.3.5"
    KVER=7.3.5-cloudbook
    ADIR="$ROOT_DIR/artifacts/7.3"
    # The Ubuntu builder generates the matching initramfs and boot images.
    kernel_only=--kernel-only
    ;;
  *)
    echo "unknown version: $VER (use 6.12 or 7.3)" >&2
    exit 1
    ;;
esac

RESOURCE="$ROOT_DIR/artifacts/extracted_resource.img"
CMD29='console=tty0 console=ttyS2,115200n8 root=LABEL=EMMC_ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 systemd.show_status=1 systemd.default_timeout_start_sec=15s loglevel=7 no_console_suspend'
CMD28='console=tty0 console=ttyS2,115200n8 root=LABEL=ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 systemd.show_status=1 systemd.default_timeout_start_sec=15s loglevel=7 no_console_suspend'

if [[ -z $kernel_only ]]; then
    for f in "$RAMDISK" "$RESOURCE"; do
        if [[ ! -f "$f" ]]; then
            echo "missing $f" >&2
            exit 1
        fi
    done
fi
[[ -f "$OUT_DIR/arch/arm64/boot/Image" ]] || {
    echo "missing $OUT_DIR/arch/arm64/boot/Image; run build_kernel_612.sh or build_kernel_735.sh first" >&2
    exit 1
}

# Build the board DTBs for the requested kernel.
make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 \
    DTC_FLAGS="-@" dtbs

mkdir -p "$ADIR"
if [[ -z $kernel_only ]]; then
    python3 "$ROOT_DIR/pack_boot_linux.py" \
        --kernel "$OUT_DIR/arch/arm64/boot/Image" \
        --ramdisk "$RAMDISK" \
        --dtb "$OUT_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dtb" \
        --resource-template "$RESOURCE" \
        --cmdline "$CMD29" \
        --output "$ADIR/${BOOT_PREFIX}_${EMMC_VARIANT}.img"

    python3 "$ROOT_DIR/pack_boot_linux.py" \
        --kernel "$OUT_DIR/arch/arm64/boot/Image" \
        --ramdisk "$RAMDISK" \
        --dtb "$OUT_DIR/arch/arm64/boot/dts/rockchip/$USB_DTS.dtb" \
        --resource-template "$RESOURCE" \
        --cmdline "$CMD28" \
        --output "$ADIR/${BOOT_PREFIX}_${USB_VARIANT}.img"
fi

# Full module tree for a reproducible rootfs rebuild.
MODTMP=$(mktemp -d)
trap 'rm -rf "$MODTMP"' EXIT
make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 \
    INSTALL_MOD_PATH="$MODTMP" INSTALL_MOD_STRIP=1 DEPMOD=true modules_install
depmod -b "$MODTMP" "$KVER"
# The module tree is installed on top of a bind-mounted macOS directory, which
# does not carry real Unix modes: directories come out 777 and files 666. Those
# modes would otherwise travel through the tarball into the rootfs image.
chmod -R go-w "$MODTMP"
tar --exclude="lib/modules/$KVER/build" --exclude="lib/modules/$KVER/source" -C "$MODTMP" -czf "$ADIR/modules-$KVER.tar.gz" "lib/modules/$KVER"

cp "$OUT_DIR/arch/arm64/boot/Image" "$ADIR/Image-$KVER"
if [[ -f "$OUT_DIR/System.map" ]]; then cp "$OUT_DIR/System.map" "$ADIR/System.map-$KVER"; fi
cp "$OUT_DIR/.config" "$ADIR/config-$KVER"
if [[ "$VER" == 7.3 ]]; then
    cp "$OUT_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dtb" "$ADIR/$DTS_NAME-v30-emmc.dtb"
fi

(
    cd "$ADIR"
    if [[ "$VER" == 7.3 ]]; then
        files=("Image-$KVER" "config-$KVER" "modules-$KVER.tar.gz" "$DTS_NAME-v30-emmc.dtb")
        if [[ -f "System.map-$KVER" ]]; then files+=("System.map-$KVER"); fi
        sha256sum "${files[@]}" | sort -k2 > SHA256SUMS
    else
        find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 |
            xargs -0 sha256sum | sort -k2 > SHA256SUMS
    fi
)

echo "[OK] $VER artifacts in $ADIR"
